package com.leicamoments.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 伪 RAW（JPG / HEIF）线性化：把已成片的 8-bit 像素展开成 16-bit 线性 sRGB 三通道，
 * 使 [RawToneMap] 的 16-bit 管线（ToneCurve 七档）可以直接作用其上。
 *
 * 能力边界（务必不要对外承诺更多）：
 * - **不增加信息量**：源已去马赛克、已削顶，线性化只是把 256 级精确映射到 0..65535，
 *   目的是让后续算子不再逐级量化，而不是「恢复 RAW 的高光余量」。
 * - **不做 RAW 提亮曲线 / 内嵌预览分位匹配**：那两项对已成片没有意义（源本身就是最终观感，
 *   分位匹配比值恒等），调用方必须以 [RawToneMap.PSEUDO_LEVEL] 进入管线。
 * - **不产出真 HDR 增益**：`HdrGainMap.synthFromLinear` 需要物理线性亮度，伪线性给不出，
 *   `MainActivity.ensureRawLinearLuma` 保持仅对真 RAW 生效。
 *
 * 输入契约与 RawToneMap 完全一致（这也是本类存在的唯一理由）：
 * direct ByteBuffer，前 8 字节 int w/h（本机字节序），随后 w*h*3 个 u16（native order）。
 *
 * 失败语义：任何异常（含 direct 内存分配失败）返回 null，调用方回落 8-bit 链，绝不抛给上层。
 */
object PseudoRawLinear {

    private const val TAG = "PSEUDO"

    /**
     * 线性化像素上限：与工作字节上限（横置口径 长≤4096 短≤3072）对齐。
     * 超出说明调用方给了未 clamp 的位图，直接拒绝比冒险分配 100MB+ direct 内存更安全。
     */
    private const val MAX_PIXELS = 4096 * 3072

    /**
     * sRGB(0..255) → 线性 u16 查表（IEC 61966-2-1 逆伽马）。
     *
     * 用表而不是逐像素 `pow`：12.6MP × 3 通道 ≈ 3800 万次幂运算，实测会成为导入/导出的
     * 主要耗时（秒级）；查表把这一步压到内存带宽级别（百毫秒级）。
     */
    private val SRGB_TO_LIN16: IntArray = IntArray(256) { v ->
        val s = v / 255.0
        val lin = if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        (lin * 65535.0).roundToInt().coerceIn(0, 65535)
    }

    /**
     * 位图 → 16-bit 线性三通道 direct 缓冲（RawToneMap 契约格式）。
     *
     * 内存：4096×3072 时约 75.5MB（u16×3），direct 分配不计入 Java 堆，但受进程总内存约束；
     * 分配失败返回 null。缓冲区由调用方使用完毕后交由 GC 回收（无 native 句柄需显式释放）。
     *
     * 色彩空间：按 sRGB 解读。工作字节由工程内 [ImageUtils.toJpeg] / [ImageDecoder] 产生，
     * 默认目标色域就是 sRGB；若上游给出 Display P3 等广色域位图，本方法不另做色域转换
     * （会多一次全图浮点运算），只在日志里留痕，由调用方决定是否需要。
     */
    fun toLinearBuffer(bitmap: Bitmap): ByteBuffer? {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return null
        val pixels = w.toLong() * h.toLong()
        if (pixels > MAX_PIXELS) {
            DiagLog.w(TAG, "线性化跳过：${w}x$h 超像素上限 $MAX_PIXELS")
            return null
        }
        val cs = bitmap.colorSpace
        if (cs != null && !cs.isSrgb) {
            DiagLog.w(TAG, "输入色彩空间非 sRGB（${cs.name}），按 sRGB 解读可能有轻微色偏")
        }
        return try {
            val buf = ByteBuffer.allocateDirect(8 + (pixels * 6).toInt())
                .order(ByteOrder.nativeOrder())
            buf.putInt(0, w)
            buf.putInt(4, h)
            val sb = buf.asShortBuffer()
            val stride = w * 3
            val row = IntArray(w)
            val out = ShortArray(stride)
            // short 索引 4 = 字节偏移 8，与 RawToneMap 的 base=4 约定一致
            var base = 4
            for (y in 0 until h) {
                bitmap.getPixels(row, 0, w, 0, y, w, 1)
                var i = 0
                for (x in 0 until w) {
                    val c = row[x]
                    out[i++] = SRGB_TO_LIN16[(c shr 16) and 0xFF].toShort()
                    out[i++] = SRGB_TO_LIN16[(c shr 8) and 0xFF].toShort()
                    out[i++] = SRGB_TO_LIN16[c and 0xFF].toShort()
                }
                sb.position(base)
                sb.put(out, 0, stride)
                base += stride
            }
            DiagLog.i(TAG, "线性化完成 ${w}x$h 缓冲 ${(8 + pixels * 6) / 1048576}MB")
            buf
        } catch (t: Throwable) {
            // 含 OutOfMemoryError：direct 分配 75MB 在低端机上可能失败，一律降级不抛出
            DiagLog.w(TAG, "线性化失败 ${w}x$h：${t.javaClass.simpleName} ${t.message}")
            null
        }
    }

    /**
     * 工作字节（已方向归一、带 EXIF 的 JPEG）→ 线性缓冲。
     *
     * 只用于导出期按需重建（不常驻）：与 RAW「重读源文件再解一次」在结构上同构。
     * 解码用 BitmapFactory（工作字节是我们自己 [ImageUtils.toJpeg] 产出的标准 JPEG，
     * 不含需要 ImageDecoder 才能摆正的朝向信息）。
     */
    fun fromWorkBytes(jpeg: ByteArray): ByteBuffer? {
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = false
        }
        val bmp = try {
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
        } catch (t: Throwable) {
            DiagLog.w(TAG, "工作字节解码失败：${t.javaClass.simpleName} ${t.message}")
            null
        } ?: return null
        return try {
            toLinearBuffer(bmp)
        } finally {
            bmp.recycle()
        }
    }

    /**
     * HEIF/HEIC（以及任何 ImageDecoder 可解的容器）→ 16-bit 线性缓冲。
     *
     * 这是伪 RAW 中**唯一有真实位深收益**的入口：手机 HEIF 常为 10-bit，把目标色域设为
     * [ColorSpace.Named.LINEAR_EXTENDED_SRGB] 时，解码器有机会直接给 [Bitmap.Config.RGBA_F16]
     * （线性、每通道半精度），映射到 u16 后与 RAW 管线 0..65535 的线性语义完全一致。
     *
     * 厂商解码器差异（部分机型静默降级到 ARGB_8888）不视为错误：此时按 8-bit 位图走
     * [toLinearBuffer]，功能照常，只是没有额外位深——所以调用方无需区分成功等级。
     *
     * @param targetLongEdge >0 按该长边解码（导入期预览基准）；<=0 按工作上限
     *   （横置口径 长≤4096 短≤3072）收敛，供导出期重放。
     */
    fun fromHeif(context: Context, uri: Uri, targetLongEdge: Int = 0): ByteBuffer? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            var linearTarget = false
            val bmp = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                if (w > 0 && h > 0) {
                    val edgeScale =
                        if (targetLongEdge > 0) targetLongEdge.toFloat() / max(w, h) else 1f
                    val scale = minOf(edgeScale, ImageUtils.workScale(w, h))
                    if (scale < 1f) {
                        decoder.setTargetSize(
                            (w * scale).toInt().coerceAtLeast(1),
                            (h * scale).toInt().coerceAtLeast(1)
                        )
                    }
                }
                try {
                    decoder.setTargetColorSpace(
                        ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB)
                    )
                    linearTarget = true
                } catch (t: Throwable) {
                    // 源容器/解码器不支持线性广色域：保持默认色域，后面按 8-bit 处理
                    DiagLog.w(TAG, "线性扩展现色域不受支持，退回默认色域：${t.javaClass.simpleName}")
                }
            }
            val f16 = bmp.config == Bitmap.Config.RGBA_F16
            DiagLog.i(TAG, "HEIF 解码 ${bmp.width}x${bmp.height} config=${bmp.config} " +
                "linearTarget=$linearTarget f16=$f16")
            try {
                if (f16) f16ToLinearBuffer(bmp) else toLinearBuffer(bmp)
            } finally {
                bmp.recycle()
            }
        } catch (t: Throwable) {
            DiagLog.w(TAG, "HEIF 高位深解码失败：${t.javaClass.simpleName} ${t.message}")
            null
        }
    }

    /**
     * HEIF 预览基准：导入期用（长边 ≤1600），避免把 12MP 的 F16 图整张留在内存里。
     * 失败返回 null，调用方退回 8-bit 的 [buildPreviewBase]。
     */
    fun previewBaseFromHeif(context: Context, uri: Uri, maxDim: Int = 1600): RawToneMap.Lin16? {
        return try {
            val buf = fromHeif(context, uri, maxDim) ?: return null
            val bb = buf.order(ByteOrder.nativeOrder())
            val w = bb.getInt(0)
            val h = bb.getInt(4)
            if (w <= 0 || h <= 0) return null
            val n = w * h * 3
            val data = ShortArray(n)
            val sb = bb.asShortBuffer()
            sb.position(4)
            sb.get(data, 0, n)
            DiagLog.i(TAG, "HEIF 预览基准 ${w}x$h ${n * 2 / 1048576}MB")
            RawToneMap.Lin16(data, w, h)
        } catch (t: Throwable) {
            DiagLog.w(TAG, "HEIF 预览基准失败：${t.javaClass.simpleName} ${t.message}")
            null
        }
    }

    /**
     * RGBA_F16 位图 → u16 线性缓冲。
     *
     * 思路：Bitmap 侧无法分行读取 F16，只能整图 `copyPixelsToBuffer`（8B/px），再逐像素
     * 转成 u16（6B/px）。12.6MP 峰值约 176MB native 内存——分配失败/超预算走 catch，
     * 调用方自动回落 8-bit 伪线性，不会崩。
     *
     * 值域：线性扩展现色域里 1.0 = SDR 白，HDR 高光可 >1.0；这里夹到 [0,1] 后映射到
     * 0..65535，与 RAW 管线（nativeDecodeLinearSrgb 输出）的语义保持一致。
     */
    private fun f16ToLinearBuffer(bitmap: Bitmap): ByteBuffer? {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return null
        val pixels = w.toLong() * h.toLong()
        if (pixels > MAX_PIXELS) {
            DiagLog.w(TAG, "F16 线性化跳过：${w}x$h 超像素上限 $MAX_PIXELS")
            return null
        }
        return try {
            val raw = ByteBuffer.allocateDirect((pixels * 8).toInt())
                .order(ByteOrder.nativeOrder())
            bitmap.copyPixelsToBuffer(raw)
            val sb = raw.asShortBuffer()
            val out = ByteBuffer.allocateDirect(8 + (pixels * 6).toInt())
                .order(ByteOrder.nativeOrder())
            out.putInt(0, w)
            out.putInt(4, h)
            val osb = out.asShortBuffer()
            val row = ShortArray(w * 3)
            // RGBA_F16 每像素 4 个 half（R,G,B,A），alpha 不参与线性化
            var si = 0
            var base = 4
            for (y in 0 until h) {
                var i = 0
                for (x in 0 until w) {
                    row[i++] = HALF_TO_LIN16[sb.get(si).toInt() and 0xFFFF].toShort()
                    row[i++] = HALF_TO_LIN16[sb.get(si + 1).toInt() and 0xFFFF].toShort()
                    row[i++] = HALF_TO_LIN16[sb.get(si + 2).toInt() and 0xFFFF].toShort()
                    si += 4
                }
                osb.position(base)
                osb.put(row, 0, row.size)
                base += row.size
            }
            DiagLog.i(TAG, "F16 线性化完成 ${w}x$h")
            out
        } catch (t: Throwable) {
            DiagLog.w(TAG, "F16 线性化失败 ${w}x$h：${t.javaClass.simpleName} ${t.message}")
            null
        }
    }

    /**
     * 半精度位模式 → 线性 u16 查表（65536 项，256KB）。
     *
     * 不依赖 `android.util.Half`（API 26 才有的类，且逐像素调用没有查表快）：
     * 构建一次表，之后每个通道只做一次数组索引。NaN/负数按 0，≥1.0 夹到 65535。
     */
    private val HALF_TO_LIN16: IntArray by lazy {
        IntArray(65536) { h ->
            val f = halfToFloat(h)
            val v = if (f.isNaN() || f <= 0f) 0f else if (f >= 1f) 1f else f
            (v * 65535f + 0.5f).toInt()
        }
    }

    /** IEEE 754 半精度位模式（16-bit）→ float。 */
    private fun halfToFloat(h: Int): Float {
        val sign = if (h and 0x8000 != 0) -1f else 1f
        val exp = (h shr 10) and 0x1F
        val mant = h and 0x3FF
        return when (exp) {
            0 -> if (mant == 0) sign * 0f else sign * (mant / 1024f) * 2f.pow(-14)
            31 -> if (mant == 0) sign * Float.POSITIVE_INFINITY else Float.NaN
            else -> sign * (1f + mant / 1024f) * 2f.pow(exp - 15)
        }
    }

    /**
     * 预览基准（[RawToneMap.Lin16]）：导入时一次构建，之后拖动七档滑杆直接复用。
     *
     * 调用方应传**已有的预览位图**（长边 ≤1600）：这样既不额外解码，也与屏幕上看到的
     * 采样口径一致（预览与导出同源）。超过 [maxDim] 时才在 sRGB 域缩放一次
     * ——这与现有预览链的采样方式一致，误差在 1/255 量级，不额外引入线性域重采样。
     */
    fun buildPreviewBase(bitmap: Bitmap, maxDim: Int = 1600): RawToneMap.Lin16? {
        return try {
            val w0 = bitmap.width
            val h0 = bitmap.height
            if (w0 <= 0 || h0 <= 0) return null
            val edge = max(w0, h0)
            val src = if (edge <= maxDim) {
                bitmap
            } else {
                val s = maxDim.toFloat() / edge
                Bitmap.createScaledBitmap(
                    bitmap,
                    (w0 * s).toInt().coerceAtLeast(1),
                    (h0 * s).toInt().coerceAtLeast(1),
                    true
                )
            }
            try {
                val w = src.width
                val h = src.height
                val out = ShortArray(w * h * 3)
                val row = IntArray(w)
                var o = 0
                for (y in 0 until h) {
                    src.getPixels(row, 0, w, 0, y, w, 1)
                    for (x in 0 until w) {
                        val c = row[x]
                        out[o++] = SRGB_TO_LIN16[(c shr 16) and 0xFF].toShort()
                        out[o++] = SRGB_TO_LIN16[(c shr 8) and 0xFF].toShort()
                        out[o++] = SRGB_TO_LIN16[c and 0xFF].toShort()
                    }
                }
                DiagLog.i(TAG, "预览基准 ${w}x$h ${out.size * 2 / 1048576}MB")
                RawToneMap.Lin16(out, w, h)
            } finally {
                if (src !== bitmap) src.recycle()
            }
        } catch (t: Throwable) {
            DiagLog.w(TAG, "预览基准失败：${t.javaClass.simpleName} ${t.message}")
            null
        }
    }
}
