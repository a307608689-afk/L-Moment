package com.leicamoments.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.codex.leicaapp.MadridLegendary
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Arrays
import kotlin.math.roundToInt

/**
 * L-M9 微网推理链（M9Net：HDRNet-lite 蒸馏网，fp32，1.12MB）。
 *
 * 交付件选型（离线实测）：fp32 与「QAT 假量化图」val MAE 分别
 * 11.03 / 11.09，但假量化图单块 19ms vs 12ms（+58%）——假量化用 Round/Mul/Clip 原语表达，
 * ORT CPU 不会把它融成 int8 内核，只慢不快、体积也没优势。与既有大盘一致（现网 m9_distill.onnx
 * 也是 fp32 导出），故交付 fp32。
 *
 * 与 M9Distill（M9 蒸馏）的差别只有一个，但是关键的：
 *   M9Distill 用「512 分块、末块贴边后写覆盖」——实测块边界差分是邻域的 2.0~4.1 倍
 *   （大盘既有模型的验收标准是 1.0~1.2），会在平滑区留下可见台阶。
 *   本链改为**重叠 64px + 线性羽化加权平均**（离线实测：
 *   接缝比降到 0.90~1.20，与 flush 结果只差 0.56~0.76 u8，12MP 约 3.0s）。
 *
 * 学生网输入/输出（与训练口径严格一致，勿改）：
 *   lowres (1,3,256,256) : 该 512 分块自身 2:1 盒降采样后 /255（= cv2.resize(tile,256,INTER_AREA)）
 *   full   (1,3,512,512) : 该 512 分块 /255
 *   out    (1,3,512,512) : [0,1] 整块结果（网格仿射 + 中频残差都已在网内）
 *
 * 几何协议：fit 4096x3072 → 竖图转横 → 分块起点按 stride=448 步进、末块贴边
 *   → 羽化加权累加（行带内滑动，内存 O(TILE x W)）→ 缩放回原尺寸 → 转回竖图。
 *   L-M9 直出干净：不加暖调 LUT、不加暗角/颗粒（与旧 L-M9 链的语义一致）。
 *
 * 失败（OOM / asset 缺失 / 推理异常）返回 null，调用方回落 M9 正常链并提醒用户。
 */
object Lm9Net {

    private const val TAG = "LM9NET"
    private const val MODEL_ASSET = "lm9_net.onnx"
    private const val TILE = 512
    private const val LOW = 256
    private const val OV = 64                  // 羽化重叠宽度
    private const val STRIDE = TILE - OV
    private const val W4K = 4096
    private const val H4K = 3072
    private const val INV255 = 1f / 255f

    @Volatile private var env: OrtEnvironment? = null
    @Volatile private var session: OrtSession? = null

    /** @return 处理后 JPEG 字节；任何失败返回 null（调用方回落 M9 正常链）。 */
    fun run(app: Context, jpeg: ByteArray): ByteArray? {
        return try {
            val t0 = System.currentTimeMillis()
            val styled = styledInternal(app, jpeg) ?: return null
            val encoded = JpegEnc.encode(styled)
            styled.recycle()
            val out = MadridLegendary.copyExif(jpeg, encoded)
            DiagLog.w(TAG, "L-M9 模型链完成 ${out.size}B, ${System.currentTimeMillis() - t0}ms")
            out
        } catch (t: Throwable) {
            DiagLog.e(TAG, "L-M9 模型链失败，回落 M9 正常链", t)
            null
        }
    }

    /** 微网推理 + 几何还原，产出 Bitmap（直出，无暖调/暗角/颗粒）。 */
    private fun styledInternal(app: Context, jpeg: ByteArray): Bitmap? {
        val t0 = System.currentTimeMillis()
        val sess = obtainSession(app) ?: return null
        val e = env!!

        val src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
        val portrait = src.height > src.width
        val landscape = if (portrait) rotate(src, -90f) else src
        if (landscape !== src) src.recycle()

        val fitted = fit(landscape)
        if (fitted !== landscape) landscape.recycle()
        val fw = fitted.width
        val fh = fitted.height

        // 画布可小于分块边长（缩略图/极小图）→ 等比放大到最小边 512，跑完再缩回
        var work = fitted
        if (fw < TILE || fh < TILE) {
            val s = TILE.toDouble() / minOf(fw, fh)
            work = Bitmap.createScaledBitmap(fitted, Math.ceil(fw * s).toInt(),
                Math.ceil(fh * s).toInt(), true)
            fitted.recycle()
        }
        val ww = work.width
        val wh = work.height

        val outPx = tiledBlend(sess, e, work)
        work.recycle()
        if (outPx == null) return null

        var result = Bitmap.createBitmap(ww, wh, Bitmap.Config.ARGB_8888)
        result.setPixels(outPx, 0, ww, 0, 0, ww, wh)

        if (ww != fw || wh != fh) {
            val back = Bitmap.createScaledBitmap(result, fw, fh, true)
            result.recycle()
            result = back
        }
        if (fw != landscape.width || fh != landscape.height) {
            val back = Bitmap.createScaledBitmap(result, landscape.width, landscape.height, true)
            result.recycle()
            result = back
        }
        landscape.recycle()

        val finalBmp = if (portrait) rotateMutable(result, 90f) else result
        if (finalBmp !== result) result.recycle()
        DiagLog.w(TAG, "L-M9 styled 就绪 ${finalBmp.width}x${finalBmp.height}, "
                + "${System.currentTimeMillis() - t0}ms")
        return finalBmp
    }

    /**
     * 重叠分块推理 + 羽化加权拼接（返回 ARGB 像素数组，失败返回 null）。
     *
     * 内存：按**行带**滑动累加（O(TILE x W) 浮点），而不是全画布累加——12MP 全画布浮点
     * 累加器要 ~200MB，会 OOM；行带方案 12MP 约 33MB。
     * 权重口径与 PC 端 parity.py blend_weights 完全一致（贴画布边的一侧不羽化）。
     */
    private fun tiledBlend(sess: OrtSession, e: OrtEnvironment, bmp: Bitmap): IntArray? {
        val w = bmp.width
        val h = bmp.height
        val outPx = IntArray(w * h)
        val xs = offsets(w)
        val ys = offsets(h)

        val bandRows = TILE
        val band = FloatArray(3 * bandRows * w)     // RGB 累加（未归一化）
        val bandW = FloatArray(bandRows * w)        // 权重累加
        val rowW = FloatArray(TILE)                 // 每行权重复用
        val wx = FloatArray(TILE)
        val tilePx = IntArray(TILE * TILE)
        val lowPx = IntArray(LOW * LOW)
        val plane = TILE * TILE
        val lowPlane = LOW * LOW
        val fullBuf = ByteBuffer.allocateDirect(4 * plane * 3).order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        val lowBuf = ByteBuffer.allocateDirect(4 * lowPlane * 3).order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        Arrays.fill(band, 0f)
        Arrays.fill(bandW, 0f)

        try {
            var prevY0 = -1
            for (y0 in ys) {
                if (prevY0 >= 0) {
                    val delta = y0 - prevY0
                    finalizeRows(band, bandW, w, 0, delta, prevY0, outPx)
                    val keep = TILE - delta
                    System.arraycopy(band, delta * w * 3, band, 0, keep * w * 3)
                    System.arraycopy(bandW, delta * w, bandW, 0, keep * w)
                    Arrays.fill(band, keep * w * 3, bandRows * w * 3, 0f)
                    Arrays.fill(bandW, keep * w, bandRows * w, 0f)
                }
                weights1d(rowW, h, y0)      // 行权重（整条行带共用）
                for (x0 in xs) {
                    weights1d(wx, w, x0)    // 列权重（本列分块）
                    bmp.getPixels(tilePx, 0, TILE, x0, y0, TILE, TILE)
                    boxDown2(tilePx, lowPx)
                    fillPlanesCHW(tilePx, plane, fullBuf)
                    fillPlanesCHW(lowPx, lowPlane, lowBuf)

                    val fullT = OnnxTensor.createTensor(
                        e, fullBuf, longArrayOf(1, 3, TILE.toLong(), TILE.toLong()))
                    val lowT = OnnxTensor.createTensor(
                        e, lowBuf, longArrayOf(1, 3, LOW.toLong(), LOW.toLong()))
                    fullT.use { ft ->
                        lowT.use { lt ->
                            sess.run(mapOf("lowres" to lt, "full" to ft)).use { res ->
                                val ob = (res[0] as OnnxTensor).floatBuffer
                                for (ty in 0 until TILE) {
                                    val wy = rowW[ty]
                                    val o = ty * w
                                    val i = ty * TILE
                                    val iG = plane + i
                                    val iB = plane * 2 + i
                                    for (tx in 0 until TILE) {
                                        val wgt = wy * wx[tx]
                                        val idx = o + x0 + tx      // 列偏移不可省：否则所有列分块叠进带的前 512 列
                                        band[idx * 3] += wgt * ob.get(i + tx)
                                        band[idx * 3 + 1] += wgt * ob.get(iG + tx)
                                        band[idx * 3 + 2] += wgt * ob.get(iB + tx)
                                        bandW[idx] += wgt
                                    }
                                }
                            }
                        }
                    }
                }
                prevY0 = y0
            }
            if (prevY0 >= 0) finalizeRows(band, bandW, w, 0, h - prevY0, prevY0, outPx)
        } catch (t: Throwable) {
            DiagLog.e(TAG, "重叠分块推理失败", t)
            return null
        }
        return outPx
    }

    /** 把行带里 [base+from, base+to) 的累加结果归一化写入 outPx。 */
    private fun finalizeRows(band: FloatArray, bandW: FloatArray, w: Int,
                             from: Int, to: Int, base: Int, outPx: IntArray) {
        for (k in from until to) {
            val o = k * w
            val rowOut = (base + k) * w
            for (j in 0 until w) {
                val ww = bandW[o + j]
                if (ww <= 0f) continue
                val idx = o + j
                val r = (band[idx * 3] / ww * 255f).roundToInt().coerceIn(0, 255)
                val g = (band[idx * 3 + 1] / ww * 255f).roundToInt().coerceIn(0, 255)
                val b = (band[idx * 3 + 2] / ww * 255f).roundToInt().coerceIn(0, 255)
                outPx[rowOut + j] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    /**
     * 一维羽化权重（与 parity.py blend_weights 同口径）：
     * 内部为 1；start>0 时前 OV 个 = k/OV，start+TILE<total 时后 OV 个 = (OV-1-k)/OV；
     * 贴画布边的一侧保持 1（否则该侧权重会归零、除法失效）。
     */
    private fun weights1d(out: FloatArray, total: Int, start: Int) {
        Arrays.fill(out, 1f)
        if (total <= TILE) return
        for (k in 0 until OV) {
            if (start > 0) out[k] = k.toFloat() / OV
            if (start + TILE < total) out[TILE - OV + k] = (OV - 1 - k).toFloat() / OV
        }
    }

    /** 覆盖 [0,total) 的分块起点：stride=448 步进，末块贴边（与 parity.py tile_offsets 同）。 */
    private fun offsets(total: Int): IntArray {
        if (total <= TILE) return intArrayOf(0)
        val list = ArrayList<Int>()
        var o = 0
        while (o <= total - TILE) {
            list.add(o)
            o += STRIDE
        }
        if (list[list.size - 1] != total - TILE) list.add(total - TILE)
        return list.toIntArray()
    }

    /** 512→256 精确 2:1 盒降采样（= cv2.INTER_AREA 在 2:1 下的定义，与训练低分输入一致）。 */
    private fun boxDown2(src: IntArray, dst: IntArray) {
        var d = 0
        for (y in 0 until LOW) {
            var r0 = (2 * y) * TILE
            var r1 = r0 + TILE
            for (x in 0 until LOW) {
                val c = 2 * x
                val p0 = src[r0 + c]
                val p1 = src[r0 + c + 1]
                val p2 = src[r1 + c]
                val p3 = src[r1 + c + 1]
                val r = (((p0 shr 16) and 0xFF) + ((p1 shr 16) and 0xFF)
                        + ((p2 shr 16) and 0xFF) + ((p3 shr 16) and 0xFF) + 2) shr 2
                val g = (((p0 shr 8) and 0xFF) + ((p1 shr 8) and 0xFF)
                        + ((p2 shr 8) and 0xFF) + ((p3 shr 8) and 0xFF) + 2) shr 2
                val b = ((p0 and 0xFF) + (p1 and 0xFF) + (p2 and 0xFF) + (p3 and 0xFF) + 2) shr 2
                dst[d++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    /** ARGB int 数组 → CHW float 平面（/255），写入复用缓冲并在末尾 rewind。 */
    private fun fillPlanesCHW(px: IntArray, n: Int, fb: FloatBuffer) {
        fb.rewind()
        for (c in 0 until 3) {
            val sh = 16 - c * 8
            for (i in 0 until n) {
                fb.put(((px[i] shr sh) and 0xFF) * INV255)
            }
        }
        fb.rewind()
    }

    // ---- ORT session ----

    private fun obtainSession(app: Context): OrtSession? {
        session?.let { return it }
        synchronized(this) {
            session?.let { return it }
            return try {
                val e = OrtEnvironment.getEnvironment()
                val bytes = app.assets.open(MODEL_ASSET).use { it.readBytes() }
                val opt = OrtSession.SessionOptions()
                opt.setIntraOpNumThreads(OrtThreads.pick())
                val s = e.createSession(bytes, opt)
                env = e
                session = s
                DiagLog.w(TAG, "ORT session 就绪 (${bytes.size / 1024}KB, 线程 ${OrtThreads.pick()})")
                s
            } catch (t: Throwable) {
                DiagLog.e(TAG, "session 创建失败", t)
                null
            }
        }
    }

    fun release() {
        synchronized(this) {
            try { session?.close() } catch (_: Throwable) {}
            session = null
        }
    }

    // ---- 辅助 ----

    private fun rotate(b: Bitmap, deg: Float): Bitmap {
        val m = Matrix()
        m.setRotate(deg)
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    private fun rotateMutable(b: Bitmap, deg: Float): Bitmap {
        val rotated = rotate(b, deg)
        if (rotated.isMutable) return rotated
        val out = Bitmap.createBitmap(rotated.width, rotated.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(rotated, 0f, 0f, null)
        rotated.recycle()
        return out
    }

    private fun fit(b: Bitmap): Bitmap {
        val w = b.width
        val h = b.height
        if (w <= W4K && h <= H4K) return b
        val s = minOf(W4K.toFloat() / w, H4K.toFloat() / h)
        return Bitmap.createScaledBitmap(b, (w * s).toInt(), (h * s).toInt(), true)
    }
}
