package com.leicamoments.app

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import com.codex.leicaapp.MadridLegendary
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.util.Locale

object ImageUtils {

    /** 从 URI 读取原始字节。 */
    fun readBytes(context: Context, uri: Uri): ByteArray {
        context.contentResolver.openInputStream(uri)?.use { input ->
            return input.readBytes()
        }
        throw IllegalStateException("无法读取图片")
    }

    /**
     * 解码为 Bitmap，按 [maxDim] 等比缩小。
     * 支持 JPG/PNG/WebP/HEIF/DNG（API 28+ 走 ImageDecoder，兼容部分 RAW）。
     */
    fun decode(context: Context, uri: Uri, maxDim: Int = 1600): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                decodeWithImageDecoder(context, uri, maxDim)
            } else {
                decodeWithBitmapFactory(context, uri, maxDim)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 工作字节尺寸上限：M9 系 styletrans 引擎只接受**横置**画面，硬限为 长边 ≤4096 且 短边 ≤3072。
     *
     * 为什么是长/短边而不是宽/高：引擎吃的是横置画面，而 [com.codex.leicaapp.LegendaryPostProcessor]
     * 的 applyStyleOriented 在送引擎前会把竖图旋转 -90°（竖 3072×4096 → 横 4096×3072）再转回。
     * 若此处按竖置宽高判定，竖拍图会被白白多缩一刀：
     *   · 竖 3072×4096（12.5MP 竖拍）旧口径高 4096 > 3072 → 缩到 2304×3072（丢 60% 像素）；
     *   · 横置口径长 4096 短 3072 → 合规，旋转后正好 4096×3072，零缩放零重编码。
     * 超限死法 = OpenCV copyMakeBorder 负 padding 断言，exit=134 硬 abort 无降级，故上限必须严格守住。
     */
    const val MAX_WORK_LONG = 4096
    const val MAX_WORK_SHORT = 3072

    /** 像素尺寸是否已在工作上限内（横置口径：长边/短边逐轴判定）。 */
    fun withinWorkLimit(w: Int, h: Int): Boolean =
        w >= 1 && h >= 1 && maxOf(w, h) <= MAX_WORK_LONG && minOf(w, h) <= MAX_WORK_SHORT

    /** 把 (w,h) 等比压进横置口径所需的缩放系数；已在限内返回 1f。 */
    fun workScale(w: Int, h: Int): Float {
        if (w < 1 || h < 1) return 1f
        return minOf(
            1f,
            MAX_WORK_LONG.toFloat() / maxOf(w, h),
            MAX_WORK_SHORT.toFloat() / minOf(w, h))
    }

    /** 仅读文件头拿像素尺寸（不分配像素内存），失败返回 null。这是「存储尺寸」，未考虑 EXIF 方向。 */
    fun probeJpegSize(bytes: ByteArray): IntArray? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (opts.outWidth > 0 && opts.outHeight > 0) intArrayOf(opts.outWidth, opts.outHeight) else null
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * JPEG 的「方向归一后」像素尺寸（宽高已按 EXIF Orientation 互换）。
     *
     * 相机竖拍 JPG 像素是**横存**的（如 4096×3072）+ Orientation=6，[normalizeExifOrientation]
     * 会把像素转正成 3072×4096，日志与超分提示都需要这个「真实朝向」尺寸。
     * 注：工作上限已改为长/短边口径（宽高互换不变），故判定结果与存储尺寸等价，
     * 这里保留归一尺寸纯粹为了语义清晰。读取失败（无 EXIF / 非 JPEG）按原尺寸返回。
     */
    fun probeOrientedSize(bytes: ByteArray): IntArray? {
        val size = probeJpegSize(bytes) ?: return null
        return try {
            if (isSwapOrientation(readOrientation(bytes))) intArrayOf(size[1], size[0]) else size
        } catch (t: Throwable) {
            size
        }
    }

    /** EXIF Orientation 是否要求宽高互换（5/6/7/8）。 */
    fun isSwapOrientation(ori: Int): Boolean =
        ori == ExifInterface.ORIENTATION_TRANSPOSE || ori == ExifInterface.ORIENTATION_ROTATE_90 ||
            ori == ExifInterface.ORIENTATION_TRANSVERSE || ori == ExifInterface.ORIENTATION_ROTATE_270

    /** 读源容器的 EXIF Orientation（读不到按 1）。 */
    fun readOrientation(bytes: ByteArray): Int = try {
        ExifInterface(ByteArrayInputStream(bytes))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (t: Throwable) {
        ExifInterface.ORIENTATION_NORMAL
    }

    /**
     * 决定重编码工作字节该写什么 Orientation 值。
     *
     * 常规写 "1"：[decodeWork] 约定解码时已按 EXIF 摆正像素，沿用原值会导致查看器二次旋转。
     * 但「ImageDecoder 是否应用了 HEIF 的朝向」属实现细节——若解码结果的长宽朝向与
     * 「摆正后应有的朝向」相反，说明方向没被应用，此时必须沿用源值，否则导出横竖错。
     * 只有互换类方向（5/6/7/8）才会造成朝向差异，180° 类天然自洽。
     */
    fun resolveWorkOrientation(srcBytes: ByteArray, workW: Int, workH: Int): String {
        if (workW == workH) return "1"
        return try {
            val stored = probeJpegSize(srcBytes) ?: return "1"
            if (stored[0] == stored[1]) return "1"
            val ori = readOrientation(srcBytes)
            // 摆正之后的朝向：需互换时与存储朝向相反
            val uprightLandscape =
                if (isSwapOrientation(ori)) stored[0] < stored[1] else stored[0] > stored[1]
            if ((workW > workH) == uprightLandscape) "1" else ori.toString()
        } catch (t: Throwable) {
            "1"
        }
    }

    /**
     * 全分辨率工作位图：不做预览级降采样，仅在超过横置口径（长 ≤4096 短 ≤3072）时等比封顶。
     * 缩放在 onHeaderDecoded 里用 setTargetSize 完成——超大图（50MP/200MP 级）直接解到目标尺寸，
     * 峰值内存为目标位图本身，而非「先全解再缩」。
     * 返回位图保证两件事：①EXIF 方向已应用（像素摆正，Orientation 语义为 1）；②尺寸已在工作上限内。
     */
    fun decodeWork(context: Context, uri: Uri): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val w = info.size.width
                    val h = info.size.height
                    if (w > 0 && h > 0) {
                        val s = workScale(w, h)
                        if (s < 1f) {
                            decoder.setTargetSize(
                                (w * s).toInt().coerceAtLeast(1),
                                (h * s).toInt().coerceAtLeast(1))
                        }
                    }
                }
                // info.size 在「方向归一前/后」上是实现细节，解码后再按横置口径收敛一次兜底
                clampToWorkLimit(decoded)
            } else {
                // API<28 兜底：BitmapFactory 不认 EXIF 方向，这里按方向标签摆正后再按横置口径收敛
                clampToWorkLimit(decodeOrientedWithBitmapFactory(context, uri, MAX_WORK_LONG))
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 按横置口径把位图收敛到工作上限内；已在限内直接返回原对象（不复制）。 */
    private fun clampToWorkLimit(bmp: Bitmap): Bitmap {
        if (withinWorkLimit(bmp.width, bmp.height)) return bmp
        val s = workScale(bmp.width, bmp.height)
        val out = Bitmap.createScaledBitmap(
            bmp,
            (bmp.width * s).toInt().coerceAtLeast(1),
            (bmp.height * s).toInt().coerceAtLeast(1),
            true)
        if (out !== bmp) bmp.recycle()
        return out
    }

    /** API<28 专用：BitmapFactory 解码（不认方向）+ 按 EXIF Orientation 把像素摆正。 */
    private fun decodeOrientedWithBitmapFactory(context: Context, uri: Uri, maxDim: Int): Bitmap {
        val bmp = decodeWithBitmapFactory(context, uri, maxDim)
        val exif = try {
            context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
        } catch (t: Throwable) {
            null
        } ?: return bmp
        val ori = try {
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (t: Throwable) {
            ExifInterface.ORIENTATION_NORMAL
        }
        if (ori == ExifInterface.ORIENTATION_NORMAL || ori == ExifInterface.ORIENTATION_UNDEFINED) return bmp
        return try {
            val m = Matrix()
            m.setRotate(exif.rotationDegrees.toFloat())
            m.postScale(if (exif.isFlipped) -1f else 1f, 1f)
            val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (out !== bmp) bmp.recycle()
            out
        } catch (t: Throwable) {
            bmp
        }
    }

    private fun decodeWithImageDecoder(context: Context, uri: Uri, maxDim: Int): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val sample = maxOf(1, maxOf(w, h) / maxDim)
            decoder.setTargetSampleSize(sample)
        }
    }

    private fun decodeWithBitmapFactory(context: Context, uri: Uri, maxDim: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalStateException("decode failed")
    }

    /** Bitmap → JPEG 字节。 */
    fun toJpeg(bitmap: Bitmap, quality: Int = 92): ByteArray {
        val bos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        return bos.toByteArray()
    }

    /** JPEG 字节 → Bitmap。 */
    fun fromJpeg(bytes: ByteArray): Bitmap? {
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    /**
     * EXIF 方向归一：Orientation≠1 的 JPEG（如 RAW 伴生 JPG 像素横存、靠 Orientation=6 竖显）
     * 按标签旋转/镜像像素，重编码后回填原 EXIF 并把 Orientation 就地改写为 1。
     * Orientation 缺省或为 1（正常拍摄 JPG）原字节原样返回，零重编码零损失；任何失败返回原字节。
     */
    fun normalizeExifOrientation(bytes: ByteArray): ByteArray {
        return try {
            val exif = ExifInterface(ByteArrayInputStream(bytes))
            val ori = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            if (ori == ExifInterface.ORIENTATION_NORMAL
                || ori == ExifInterface.ORIENTATION_UNDEFINED) return bytes
            val opts = BitmapFactory.Options().apply {
                inMutable = true
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return bytes
            // 官方文档同款矩阵：先按 rotationDegrees 旋转，镜像再水平翻转
            val m = Matrix()
            m.setRotate(exif.rotationDegrees.toFloat())
            m.postScale(if (exif.isFlipped) -1f else 1f, 1f)
            val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (out !== bmp) bmp.recycle()
            val bos = ByteArrayOutputStream(bytes.size)
            out.compress(Bitmap.CompressFormat.JPEG, 95, bos)
            out.recycle()
            MadridLegendary.patchExifOrientationToOne(
                MadridLegendary.copyExif(bytes, bos.toByteArray()))
        } catch (t: Throwable) {
            bytes
        }
    }

    // ======================== C1/C4：全像素直通（像素预算分档） ========================

    /**
     * C4 分档门限：45MP 起启用全像素直通，覆盖 50MP 4:3（8192×6144=50.3MP）与 50MP 竖拍。
     *
     * 12.58MP~45MP 属「取舍区」：工作版 4096×3072 与原生像素差不到 2 倍，而条带重编码要额外
     * 8~10s，收益不成立，统一走工作字节（行为与 C1 之前一致）。
     */
    const val FULLRES_MIN_PIXELS = 45_000_000L
    const val FULLRES_MAX_PIXELS = 300_000_000L

    /** 工作版长边/短边上限（条带编码与引擎链共同封顶，即 12.58MP）。 */
    const val WORK_MAX_LONG = 4096
    const val WORK_MAX_SHORT = 3072

    /** C4 低端回落：总内存 < 6GB（或系统标记 low-ram）强制工作版，避免导入期 OOM。 */
    private const val FULLRES_MIN_TOTAL_RAM_BYTES = 6L * 1024 * 1024 * 1024

    /**
     * 全像素直通需要的最小 Java 堆。
     *
     * 导出尾部已全链路文件化（头部改写 + 尾部流式搬运），堆压力主要来自**导入阶段**：
     * 源字节读入（50~80MB）+ 动态照片截断时的副本（尾部有增益图时再一份）。
     */
    private const val FULLRES_MIN_HEAP_BYTES = 384L * 1024 * 1024

    /**
     * C4 分档判定结果。`reason` 是全中文诊断串，直接进 LMDiag——
     * 真机验收时「这张为什么没走全像素」必须能从日志一句话读出来，而不是靠猜。
     */
    data class FullResDecision(
        val enabled: Boolean,
        val reason: String,
        val totalPx: Long,
        /** 相对工作版 4096×3072 的线性放大倍率（1.0 = 与工作版等大）。 */
        val scale: Float
    ) {
        val megapixels: Double get() = totalPx / 1_000_000.0
        val tier: String get() = when {
            totalPx >= 150_000_000L -> "200MP"
            totalPx >= 45_000_000L -> "50MP"
            else -> "12.5MP"
        }
    }

    /** 原生像素相对工作版 4096×3072 的线性放大倍率。 */
    fun fullResScale(w: Int, h: Int): Float {
        if (w < 1 || h < 1) return 0f
        return maxOf(w.toFloat() / WORK_MAX_LONG, h.toFloat() / WORK_MAX_SHORT)
    }

    /**
     * C4：像素预算分档（**单点判定**，导入期只调这一个函数）。
     *
     * 档位规则：
     *  · 像素 ∈ [45MP, 300MP]、Orientation=1、Java 堆 ≥384MiB、总内存 ≥6GB、用户开关打开 → 全像素直通；
     *  · 其余一律 WORK（解码封顶 4096×3072，与 C1 之前行为完全一致）。
     *
     * 判定顺序刻意把「低端回落」放在「用户开关」之前：4GB 机型即使手动打开也必须回落，
     * 否则导入期就会出现 OOM 而不是变慢。
     */
    fun decideFullRes(
        context: Context,
        w: Int,
        h: Int,
        ori: Int = ExifInterface.ORIENTATION_NORMAL,
        settingEnabled: Boolean = true
    ): FullResDecision {
        if (w < 1 || h < 1) return FullResDecision(false, "尺寸无效 ${w}x${h}", 0L, 0f)
        val px = w.toLong() * h
        val scale = fullResScale(w, h)
        fun d(ok: Boolean, why: String) = FullResDecision(ok, why, px, scale)
        if (px < FULLRES_MIN_PIXELS) return d(false, "${fmtMp(px)} 未达全像素门限 45MP")
        if (px > FULLRES_MAX_PIXELS) return d(false, "${fmtMp(px)} 超过全像素上限 300MP")
        // 小米 200MP 直出 JPEG 会把 Orientation 写成 0（非法值），按 1 处理；
        // 2..8 需要翻转/旋转取样，条带按行切无法直出。
        if (ori > ExifInterface.ORIENTATION_NORMAL) return d(false, "Orientation=$ori 需旋转取样")
        val totalRam = totalRamBytes(context)
        if (isLowEndDevice(context, totalRam)) {
            return d(false, "低端机回落（总内存 ${fmtGb(totalRam)}）")
        }
        val heap = Runtime.getRuntime().maxMemory()
        if (heap < FULLRES_MIN_HEAP_BYTES) return d(false, "Java 堆 ${heap / MB}MB < 384MB")
        if (!settingEnabled) return d(false, "设置已关闭「全像素导出」")
        return d(true, "${fmtMp(px)} · ${String.format(Locale.US, "%.2f", scale)}x 工作版 · 总内存 ${fmtGb(totalRam)}")
    }

    /**
     * C4 低端回落检查（给设置页做禁用态用）。
     * 返回 null 表示本机内存档位允许全像素；否则返回可直接显示的原因串。
     */
    fun lowEndReason(context: Context): String? {
        val totalRam = totalRamBytes(context)
        if (!isLowEndDevice(context, totalRam)) return null
        return "本机内存 ${fmtGb(totalRam)}，已自动关闭"
    }

    /** 兼容旧签名：只要分档通过即可（不带设置项/低端诊断）。 */
    fun supportsFullResDirect(w: Int, h: Int, ori: Int = ExifInterface.ORIENTATION_NORMAL): Boolean {
        if (w < 1 || h < 1) return false
        val px = w.toLong() * h
        if (px < FULLRES_MIN_PIXELS || px > FULLRES_MAX_PIXELS) return false
        if (ori > ExifInterface.ORIENTATION_NORMAL) return false
        return Runtime.getRuntime().maxMemory() >= FULLRES_MIN_HEAP_BYTES
    }

    private const val MB = 1024L * 1024L

    private fun fmtMp(px: Long) = String.format(Locale.US, "%.1fMP", px / 1_000_000.0)
    private fun fmtGb(bytes: Long) =
        if (bytes <= 0) "未知" else String.format(Locale.US, "%.1fGB", bytes / 1024.0 / 1024.0 / 1024.0)

    /** 系统总内存（字节）；取不到返回 0。 */
    private fun totalRamBytes(context: Context): Long {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return 0L
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            info.totalMem
        } catch (t: Throwable) {
            0L
        }
    }

    /**
     * 低端机判定：系统 low-ram 标记，或总内存 < 6GB。
     * 总内存取不到（0）时不当作低端，交给堆判定兜底。
     */
    private fun isLowEndDevice(context: Context, totalRam: Long): Boolean {
        if (totalRam in 1 until FULLRES_MIN_TOTAL_RAM_BYTES) return true
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.isLowRamDevice == true
        } catch (t: Throwable) {
            false
        }
    }

    /** 当前 Java 堆占用（诊断日志用）。 */
    fun heapUsageLabel(): String {
        val r = Runtime.getRuntime()
        return "${(r.totalMemory() - r.freeMemory()) / 1048576}MB/${r.maxMemory() / 1048576}MB"
    }

    /**
     * 条带解码源：用 [BitmapRegionDecoder] 按行区间解码整幅 JPEG。
     * 常驻内存 = 一条带位图（如 16320×256 ARGB ≈ 16MiB），不会整图驻留。
     */
    class StripSource internal constructor(
        private val decoder: BitmapRegionDecoder,
        val width: Int,
        val height: Int,
        private val extra: List<Closeable> = emptyList(),
        private val sampler: ((Int) -> Bitmap?)? = null
    ) : Closeable {

        /** 解码 [top, top+rows) 行；失败返回 null。 */
        fun decode(top: Int, rows: Int): Bitmap? {
            if (rows <= 0 || top < 0 || top >= height) return null
            val h = minOf(rows, height - top)
            return try {
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inMutable = true
                    // 必须显式要 sRGB：不给时 BitmapRegionDecoder 走的是「保留源色彩空间」的
                    // 快路径（region 解码与 BitmapFactory 全图解码口径不一致），广色域源
                    // （如小米 P3 直出）会把 P3 数值原样当 sRGB 用；而整图链的全图解码带
                    // P3 标记、getPixels 会做 P3→sRGB 转换。两条链输入色域不同 → 条带链
                    // 整体偏蓝偏艳（实测 R +5.4 / B +12.5）。这里统一到 sRGB 后，
                    // region 解码出来的数值与整图链 getPixels 的结果逐像素同源。
                    inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
                }
                decoder.decodeRegion(Rect(0, top, width, top + h), opts)
            } catch (t: Throwable) {
                DiagLog.w("Strip", "decodeRegion top=$top rows=$h 失败：${t.message}")
                null
            }
        }

        /** [StripBandOp.prepare] 用的行窗口读取（与 [decode] 同义，越界自动钳制）。 */
        fun read(top: Int, rows: Int): Bitmap? = decode(top, rows)

        /**
         * 低分辨率**全图**采样（最长边≈[maxEdge]），供 PortraitFilm 两遍法求 LUT。
         *
         * 注意与 [read] 的区别：read 只拿到一条带（宽度是整幅的，高度只有几百行），
         * 用它求整图 LUT 会偏色；这里另开一次带 inSampleSize 的整图解码（~1MB），
         * 代价可忽略。返回的 Bitmap 由调用方回收。
         */
        fun sample(maxEdge: Int): Bitmap? {
            val s = sampler ?: return null
            return try {
                s(maxEdge)
            } catch (t: Throwable) {
                DiagLog.w("Strip", "整图采样失败：${t.message}")
                null
            }
        }

        override fun close() {
            try {
                decoder.recycle()
            } catch (t: Throwable) {
                // 已回收
            }
            for (c in extra) runCatching { c.close() }
        }
    }

    /**
     * 全像素条带算子：在「解码一条带」之后、「编码一条带」之前介入。
     *
     * <p>甲（LUT 家族）与乙（蒸馏）共用此接口，约定见 docs「§20.1.5 接口冻结」：</p>
     * <ul>
     *   <li>条带高由 native 决定（200MP=320、50MP=384、12.58MP=512），**不可指定**，
     *       故 [prepare] 拿到整幅 reader，需要跨带对齐的算子自行缓冲；</li>
     *   <li>[apply] 的 top 是**输出图绝对行号**；Orientation 已在上游归一（≤1），无需旋转换算；</li>
     *   <li>返回同一 Bitmap 表示原地改；返回新对象时实现方自行回收原 band（框架会回收返回值）；</li>
     *   <li>返回 null 或抛异常 → 整趟回落，不产出半成品。</li>
     * </ul>
     */
    interface StripBandOp {
        /**
         * 首带写出前调用一次，可借 [reader] 做低分辨率全图采样（PortraitFilm 两遍法用）。
         * 返回 false 表示放弃（调用方回落）。
         */
        fun prepare(reader: StripSource): Boolean

        /** 处理一条带（高 = rows，宽 = fullW）。返回 null 表示失败。 */
        fun apply(band: Bitmap, top: Int, rows: Int, fullW: Int, fullH: Int): Bitmap?

        fun release() {}
    }

    /** 打开条带解码源（整幅字节）；解码器会持有 [bytes] 引用（不再复制像素）。 */
    fun openStripSource(bytes: ByteArray): StripSource? {
        return try {
            val d = if (Build.VERSION.SDK_INT >= 31) {
                BitmapRegionDecoder.newInstance(ByteArrayInputStream(bytes), false)
            } else {
                @Suppress("DEPRECATION")
                BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
            }
            if (d == null) null
            else StripSource(d, d.width, d.height, emptyList()) { sampleBytes(bytes, it) }
        } catch (t: Throwable) {
            DiagLog.w("Strip", "打开条带解码器失败：${t.message}")
            null
        }
    }

    /**
     * 打开条带解码源（文件）。**不在 Java 堆里持有 200MP 源字节**，这是全像素直通的内存关键。
     */
    fun openStripSource(srcFile: File): StripSource? {
        return try {
            if (Build.VERSION.SDK_INT >= 27) {
                val pfd = ParcelFileDescriptor.open(srcFile, ParcelFileDescriptor.MODE_READ_ONLY)
                // 注意：本 SDK 只有 newInstance(FileDescriptor, Boolean)，没有 PFD 重载；
                // PFD 必须在解码器生命周期内保持打开（fd 归它管）
                val d = BitmapRegionDecoder.newInstance(pfd.fileDescriptor, false)
                StripSource(d, d.width, d.height, listOf(pfd)) { sampleFile(srcFile, it) }
            } else {
                val fis = FileInputStream(srcFile)
                @Suppress("DEPRECATION")
                val d = BitmapRegionDecoder.newInstance(fis, false)
                if (d == null) {
                    fis.close()
                    null
                } else {
                    StripSource(d, d.width, d.height, listOf(fis)) { sampleFile(srcFile, it) }
                }
            }
        } catch (t: Throwable) {
            DiagLog.w("Strip", "打开条带解码器失败：${t.message}")
            null
        }
    }

    /** 整图降采样（字节版）：最长边≈[maxEdge]，只用于两遍法求 LUT。 */
    private fun sampleBytes(bytes: ByteArray, maxEdge: Int): Bitmap? =
        sampledDecode(maxEdge) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it) }

    /** 整图降采样（文件版）。 */
    private fun sampleFile(file: File, maxEdge: Int): Bitmap? =
        sampledDecode(maxEdge) { BitmapFactory.decodeFile(file.absolutePath, it) }

    private inline fun sampledDecode(
        maxEdge: Int,
        decode: (BitmapFactory.Options) -> Bitmap?
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { decode(bounds) }
        var s = 1
        val edge = maxOf(bounds.outWidth, bounds.outHeight)
        if (edge > 0) {
            while (edge / (s * 2) >= maxEdge) s *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = s
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return decode(opts)?.takeIf { it.width > 0 && it.height > 0 }
    }

    /**
     * 抽取「EXIF 头部」：SOI 起至 SOS 段末（再补 EOI）。
     *
     * 下游 [ExifTransplant] 只需容器头即可抄写 APP1，不必为了机型/快门/GPS
     * 而在内存里保留 50MB 级主图数据。上限 [maxLen]（默认 2MiB）防异常文件。
     */
    fun extractExifHeader(bytes: ByteArray, maxLen: Int = 2 * 1024 * 1024): ByteArray? {
        if (bytes.size < 4 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return null
        var p = 2
        val eoi = byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        while (p + 3 < bytes.size) {
            if (bytes[p] != 0xFF.toByte()) return null
            var m = p + 1
            while (m < bytes.size && bytes[m] == 0xFF.toByte()) m++
            if (m >= bytes.size) return null
            val marker = bytes[m].toInt() and 0xFF
            if (marker == 0xD8 || marker == 0x01 || (marker in 0xD0..0xD7)) {
                p = m + 1
                continue
            }
            if (marker == 0xD9) return bytes.copyOfRange(0, minOf(m + 1, bytes.size))
            if (m + 2 >= bytes.size) return null
            val segLen = ((bytes[m + 1].toInt() and 0xFF) shl 8) or (bytes[m + 2].toInt() and 0xFF)
            val segEnd = m + 1 + segLen
            if (segLen < 2 || segEnd > bytes.size) return null
            if (marker == 0xDA) {
                // SOS：容器头到此为止（之后是熵编码数据）
                val end = minOf(segEnd, maxLen)
                if (end <= 0) return null
                return bytes.copyOfRange(0, end) + eoi
            }
            p = segEnd
            if (p > maxLen) return null
        }
        return null
    }

    /**
     * 读文件的 JPEG 容器头（SOI 起至 SOS 段末，**不含**熵编码数据、不补 EOI）。
     *
     * 与 [extractExifHeader] 的差别：这里的长度就是「熵编码起点」的绝对偏移，
     * 供文件级尾部处理（徽章 / UltraHDR 回嵌）做「头部改写 + 尾部字节流搬运」。
     * 先按 512KiB 试读，不够再放到 [maxLen]（默认 8MiB，异常文件兜底）。
     */
    fun readFileHeader(file: File, maxLen: Int = 8 * 1024 * 1024): ByteArray? {
        return try {
            val total = file.length()
            if (total < 4) return null
            val hard = minOf(total, maxLen.toLong()).toInt()
            val first = minOf(hard, 512 * 1024)
            scanHeaderBytes(file, first) ?: if (first < hard) scanHeaderBytes(file, hard) else null
        } catch (t: Throwable) {
            DiagLog.w("Strip", "读容器头失败：${t.message}")
            null
        }
    }

    private fun scanHeaderBytes(file: File, cap: Int): ByteArray? {
        val buf = ByteArray(cap)
        var read = 0
        FileInputStream(file).use { ins ->
            while (read < cap) {
                val n = ins.read(buf, read, cap - read)
                if (n <= 0) break
                read += n
            }
        }
        if (read < 4) return null
        if (buf[0] != 0xFF.toByte() || buf[1] != 0xD8.toByte()) return null
        var p = 2
        while (p + 3 < read) {
            if (buf[p] != 0xFF.toByte()) return null
            var m = p + 1
            while (m < read && buf[m] == 0xFF.toByte()) m++
            if (m >= read) return null
            val marker = buf[m].toInt() and 0xFF
            if (marker == 0xD8 || marker == 0x01 || (marker in 0xD0..0xD7)) {
                p = m + 1
                continue
            }
            if (marker == 0xD9) return buf.copyOfRange(0, m + 1)
            if (m + 2 >= read) return null
            val segLen = ((buf[m + 1].toInt() and 0xFF) shl 8) or (buf[m + 2].toInt() and 0xFF)
            if (segLen < 2) return null
            val segEnd = m + 1 + segLen
            if (segEnd > read) return null
            if (marker == 0xDA) return buf.copyOfRange(0, segEnd)
            p = segEnd
        }
        return null
    }

    /** 条带编码结果（[fallbackCopy] = 条带编码不可用，已把源字节原样写入目标）。 */
    class StripEncodeResult(
        val ok: Boolean,
        val fallbackCopy: Boolean,
        val width: Int,
        val height: Int,
        val bandRows: Int,
        val bandCount: Int,
        val elapsedMs: Long,
        val outBytes: Long
    )

    /**
     * 条带流式重编码：[src]（JPEG 字节）→ [outFile]（同尺寸 JPEG）。
     *
     * 逐条带解码 → [JpegStream.Writer] 编码 → native 拼接。任何一步失败都回落到
     * 「把源字节原样写入目标」，保证「200MP 一定能导出」这条底线不被内存/解码问题击穿。
     */
    fun encodeStrip(
        src: ByteArray,
        outFile: File,
        quality: Int = 95,
        maxBandRows: Int = 512,
        onProgress: ((Int, Int) -> Unit)? = null,
        op: StripBandOp? = null,
        fallbackBytes: ByteArray? = null,
        strict: Boolean = false
    ): StripEncodeResult {
        val t0 = System.currentTimeMillis()
        val probed = probeJpegSize(src) ?: intArrayOf(0, 0)
        val copy = { outFile.writeBytes(fallbackBytes ?: src) }
        val dec = openStripSource(src) ?: return fallbackTo(
            "无法打开条带解码器", probed[0], probed[1], t0, outFile, copy, strict
        )
        return encodeStripCore(dec, outFile, quality, maxBandRows, t0, onProgress, op, copy, strict)
    }

    /**
     * 条带流式重编码（文件版）：源驻留磁盘，进程堆里只有一条带位图。
     * 200MP 直通导出走这一条（字节版仅用于小样/批测）。
     */
    fun encodeStripFromFile(
        srcFile: File,
        outFile: File,
        quality: Int = 95,
        maxBandRows: Int = 512,
        onProgress: ((Int, Int) -> Unit)? = null,
        op: StripBandOp? = null,
        fallbackBytes: ByteArray? = null,
        strict: Boolean = false
    ): StripEncodeResult {
        val t0 = System.currentTimeMillis()
        // 带算子时失败必须落到「已套色的工作版」而不是未套色的原始全像素图，
        // 否则用户会收到一张尺寸对但没滤镜的图（比工作版更差）。
        val copy: () -> Unit = {
            if (fallbackBytes != null) {
                outFile.writeBytes(fallbackBytes)
            } else {
                srcFile.copyTo(outFile, overwrite = true)
                Unit
            }
        }
        val dec = openStripSource(srcFile) ?: return fallbackTo(
            "无法打开条带解码器", 0, 0, t0, outFile, copy, strict
        )
        return encodeStripCore(dec, outFile, quality, maxBandRows, t0, onProgress, op, copy, strict)
    }

    /**
     * [strict] = true 时不写「回落副本」：带滤镜的全像素导出宁可失败，也不产出
     * 「尺寸对但没套色」的图误导用户。
     */
    private fun fallbackTo(
        reason: String,
        w: Int,
        h: Int,
        t0: Long,
        outFile: File,
        copy: () -> Unit,
        strict: Boolean = false
    ): StripEncodeResult {
        if (strict) {
            DiagLog.w("Strip", "$reason，strict 模式放弃（不写回落副本）")
            runCatching { outFile.delete() }
            return StripEncodeResult(false, false, w, h, 0, 0, System.currentTimeMillis() - t0, 0)
        }
        DiagLog.w("Strip", "$reason，回落原始字节直通")
        runCatching { copy() }
        val len = runCatching { outFile.length() }.getOrDefault(0L)
        return StripEncodeResult(false, true, w, h, 0, 0, System.currentTimeMillis() - t0, len)
    }

    private fun encodeStripCore(
        dec: StripSource,
        outFile: File,
        quality: Int,
        maxBandRows: Int,
        t0: Long,
        onProgress: ((Int, Int) -> Unit)?,
        op: StripBandOp? = null,
        copyFallback: () -> Unit,
        strict: Boolean = false
    ): StripEncodeResult {
        val w = dec.width
        val h = dec.height
        fun fallback(reason: String): StripEncodeResult {
            runCatching { op?.release() }
            dec.close()
            if (strict) {
                DiagLog.w("Strip", "$reason，strict 模式放弃（不写回落副本）")
                runCatching { outFile.delete() }
                return StripEncodeResult(false, false, w, h, 0, 0, System.currentTimeMillis() - t0, 0)
            }
            DiagLog.w("Strip", "$reason，回落${if (op != null) "工作版" else "原始字节"}直通")
            runCatching { copyFallback() }
            val len = runCatching { outFile.length() }.getOrDefault(0L)
            return StripEncodeResult(false, true, w, h, 0, 0, System.currentTimeMillis() - t0, len)
        }

        val writer = JpegStream.Writer(outFile.absolutePath, w, h, quality, maxBandRows)
        if (!writer.isValid) return fallback("条带写入器打开失败 ${w}x$h")
        val bandRows = writer.bandRows
        val bandCount = writer.bandCount
        DiagLog.i("Strip", "条带编码开始 ${w}x$h band=${bandRows}px×$bandCount q=$quality op=${op?.javaClass?.simpleName ?: "none"} 堆 ${heapUsageLabel()}")

        // 算子准备阶段：PortraitFilm 借 reader 做低分辨率全图采样，其余算子直接返回 true
        if (op != null) {
            val prepared = try {
                op.prepare(dec)
            } catch (t: Throwable) {
                DiagLog.w("Strip", "算子 prepare 异常：${t.message}")
                false
            }
            if (!prepared) {
                writer.abort()
                return fallback("算子 ${op.javaClass.simpleName} prepare 放弃")
            }
        }

        var top = 0
        var index = 0
        try {
            while (top < h) {
                val rows = minOf(bandRows, h - top)
                var band = dec.decode(top, rows)
                    ?: throw IllegalStateException("decodeRegion @$top 失败")
                // decodeRegion 实测不认 inMutable（真机返回 immutable），算子要原地改像素必须先转可变；
                // 只在有算子时付这一次拷贝（12MiB 级），无算子路径保持 C1 零拷贝。
                if (op != null && !band.isMutable) {
                    val mut = band.copy(Bitmap.Config.ARGB_8888, true)
                        ?: throw IllegalStateException("条带转可变失败 @$top")
                    band.recycle()
                    band = mut
                }
                var out: Bitmap = band
                try {
                    if (op != null) {
                        out = op.apply(band, top, rows, w, h)
                            ?: throw IllegalStateException("算子 apply @$top 返回 null")
                    }
                    if (!writer.writeBand(out, rows)) {
                        throw IllegalStateException("writeBand @$top 失败")
                    }
                } finally {
                    if (out !== band) band.recycle()
                    out.recycle()
                }
                top += rows
                index++
                onProgress?.invoke(index, bandCount)
                if (index == 1 || index == bandCount || index % 10 == 0) {
                    DiagLog.i("Strip",
                        "条带 $index/$bandCount top=$top 堆 ${heapUsageLabel()}")
                }
            }
            if (!writer.close()) throw IllegalStateException("close 失败")
        } catch (t: Throwable) {
            writer.abort()
            return fallback("条带编码失败：${t.message}")
        }
        runCatching { op?.release() }
        dec.close()
        val elapsed = System.currentTimeMillis() - t0
        val outLen = runCatching { outFile.length() }.getOrDefault(0L)
        DiagLog.i("Strip",
            "条带编码完成 ${w}x$h band=${bandRows}px×$bandCount ${outLen}B 耗时 ${elapsed}ms 堆 ${heapUsageLabel()}")
        return StripEncodeResult(true, false, w, h, bandRows, bandCount, elapsed, outLen)
    }
}
