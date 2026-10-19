package com.leicamoments.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer

/**
 * Leica Moment M9 自动曝光重测光 —— 移植自 OffLineM9 `m9/ae.py`（17U AEC 的 Legend/M9 档）。
 *
 * 输入是线性、已白平衡的相机 RGB（1.0 = 传感器饱和，来自 nativeDecodeLinear 的 u16/65535）。
 * 移植范围：hist 亮度/暗部百分位 → DR，base_target 二维插值 × stylization → mid target，
 * 16x16 中心加权测光收敛求曝光，亮部参考求 short 目标，DRC 增益上限截断。
 * 未移植（同 OffLineM9 的取舍）：hist 自适应/夜景/防暗/平坦场景/人脸等子调整器、spot 检测。
 */
object M9Ae {

    private const val TAG = "M9AE"
    private const val ASSET = "m9_ae_main.json"

    class AEResult(
        val luxIndex: Double,
        val lumaUnit: Double,      // 中心加权 8bit 亮度（曝光 1）
        val drB2d: Double,
        val baseTarget: Double,
        val styleScale: Double,
        val midTarget: Double,
        val expMid: Double,
        val brightAvgUnit: Double,
        val brightRef: Double,
        val expShort: Double,      // 高光安全曝光（传感器实际曝光）
        val adrcGain: Double       // mid_tone_gain，由全局色调曲线实现
    )

    @Volatile private var root: JSONObject? = null
    @Volatile private var meteringObj: JSONObject? = null

    fun init(app: Context) {
        if (root != null) return
        synchronized(this) {
            if (root != null) return
            val text = app.assets.open(ASSET).use { String(it.readBytes(), Charsets.UTF_8) }
            val r = JSONObject(text)
            root = r
            meteringObj = r.getJSONObject("metering")
            DiagLog.w(TAG, "AE 表就绪 ${text.length / 1024}KB")
        }
    }

    /** 从 16bit 线性缓冲测光（缓冲前 8 字节为 int w、int h；随后 w*h*3 个 u16）。 */
    fun meterFromU16(buf: ByteBuffer, luxIndex: Double, mode: String = "m9"): AEResult {
        val bb = buf.duplicate().order(java.nio.ByteOrder.nativeOrder())
        val w = bb.getInt(0)
        val h = bb.getInt(4)
        return meter(bb, w, h, luxIndex, mode)
    }

    fun meter(bb: ByteBuffer, w: Int, h: Int, luxIndex: Double, mode: String = "m9"): AEResult {
        val r = root ?: throw IllegalStateException("M9Ae.init 未调用")
        val m = if (mode == "m9") meteringObj!! else r.getJSONObject("metering_normal")
        val stats = stats(bb, w, h)
        val blocks = stats.first
        val pix = stats.second
        val lux = luxIndex

        // ---- 动态范围：亮/暗带的百分位均值 ----
        val hl = arr(m, "hist_target_by_lux.hist_short_long_adjustment.hist_lux_node")
        val btSt = i1(lux, hl, arr(m, "hist_target_by_lux.hist_short_long_adjustment.hist_base_bright_start_pct"))
        val dtEn = i1(lux, hl, arr(m, "hist_target_by_lux.hist_short_long_adjustment.hist_dark_tone_end_pct"))
        val brightAvg = pctAvg(pix, btSt, 1.0)
        val darkAvg = pctAvg(pix, 0.0, dtEn)
        val dr = brightAvg / Math.max(darkAvg, 1e-3)

        // ---- mid target：base_target(lux, dr) × stylization ----
        val bl = arr(m, "base_target_by_lux.base_target_lux_node")
        val drNode = arr(m, "base_target_by_lux.base_target_dr_node")
        val baseTab = arr2(m, "base_target_by_lux.base_target")
        val base = i2(lux, dr, bl, drNode, baseTab)
        val s = r.getJSONObject("stylization")
        val styleOn = mode == "m9" && JSONObject(s.toString()).optBoolean("enable_stylization.value", false)
        val style = if (styleOn) {
            val ln = arr(s, "style_base.style_base_target_lux_node")
            val sc = arr2(s, "style_base.style_base_target_scale")[0]
            i1(lux, ln, sc)
        } else 1.0
        val mid = base * style

        // ---- 收敛：中心加权亮度(E) == mid ----
        val wl1 = weightedLuma(blocks, 1.0, lux, r)
        var e = mid / Math.max(wl1, 1e-6)
        for (i in 0 until 20) {
            val eNew = e * mid / Math.max(weightedLuma(blocks, e, lux, r), 1e-6)
            val conv = Math.abs(eNew / e - 1.0) < 1e-4
            e = eNew
            if (conv) break
        }

        // ---- short（高光安全）目标 ----
        val ref = i2(lux, dr, hl,
            arr(m, "hist_target_by_lux.hist_short_long_adjustment.hist_dr_node"),
            arr2(m, "hist_target_by_lux.hist_short_long_adjustment.hist_bright_tone_ref_target"))
        var eShort = ref / Math.max(brightAvg, 1e-6)

        val d = r.getJSONObject("drc")
        val cap = i1(lux, arr(d, "normal_drc_ctrl.nor_drc_lux_node"),
            arr2(d, "normal_drc_ctrl.max_drc_gain")[0])
        val adrc = (e / Math.max(eShort, 1e-9)).coerceIn(1.0, cap)
        eShort = e / adrc

        return AEResult(lux, wl1, dr, base, style, mid, e, brightAvg, ref, eShort, adrc)
    }

    // ---- 统计 ----

    /** 16x16 块亮度（8bit 线性，曝光 1）+ 降采样像素亮度（升序，供百分位）。 */
    private fun stats(bb: ByteBuffer, w: Int, h: Int): Pair<DoubleArray, DoubleArray> {
        val bh = h / 16
        val bw = w / 16
        val blocks = DoubleArray(256)
        if (bh == 0 || bw == 0) return Pair(blocks, DoubleArray(0))
        val stride = w * 3 * 2
        for (by in 0 until 16) {
            for (bx in 0 until 16) {
                var sum = 0.0
                var cnt = 0
                val y0 = by * bh
                val y1 = y0 + bh
                val x0 = bx * bw
                val x1 = x0 + bw
                for (y in y0 until y1) {
                    var o = y * stride + x0 * 6
                    for (x in x0 until x1) {
                        val r = (bb.getShort(o).toInt() and 0xFFFF) / 65535.0
                        val g = (bb.getShort(o + 2).toInt() and 0xFFFF) / 65535.0
                        val b = (bb.getShort(o + 4).toInt() and 0xFFFF) / 65535.0
                        sum += 0.299 * r + 0.587 * g + 0.114 * b
                        cnt++
                        o += 6
                    }
                }
                blocks[by * 16 + bx] = if (cnt > 0) sum / cnt * 255.0 else 0.0
            }
        }

        val step = Math.max(1, Math.sqrt(w.toDouble() * h / 400_000.0).toInt())
        val n = ((h + step - 1) / step) * ((w + step - 1) / step)
        val pix = DoubleArray(n)
        var k = 0
        var y = 0
        while (y < h) {
            var o = y * stride
            var x = 0
            while (x < w) {
                val r = (bb.getShort(o).toInt() and 0xFFFF) / 65535.0
                val g = (bb.getShort(o + 2).toInt() and 0xFFFF) / 65535.0
                val b = (bb.getShort(o + 4).toInt() and 0xFFFF) / 65535.0
                pix[k++] = (0.299 * r + 0.587 * g + 0.114 * b) * 255.0
                o += step * 6
                x += step
            }
            y += step
        }
        val out = if (k == n) pix else pix.copyOf(k)
        java.util.Arrays.sort(out)
        return Pair(blocks, out)
    }

    private fun pctAvg(sorted: DoubleArray, lo: Double, hi: Double): Double {
        val n = sorted.size
        if (n == 0) return 0.0
        val a = (lo * n).toInt().coerceIn(0, n - 1)
        val b = Math.max((hi * n).toInt(), a + 1).coerceAtMost(n)
        if (b <= a) return sorted[a]
        var s = 0.0
        for (i in a until b) s += sorted[i]
        return s / (b - a)
    }

    private fun weightedLuma(blocks: DoubleArray, exposure: Double, lux: Double, r: JSONObject): Double {
        val t = r.getJSONObject("luma")
        val tbl = arr2(t, "weight_luma_calculation.center_wghted_mtr_tbl")
        val ln = arr(t, "weight_luma_calculation.lux_node")
        val bs = i1(lux, ln, arr(t, "weight_luma_calculation.bright_start"))
        val be = i1(lux, ln, arr(t, "weight_luma_calculation.bright_end"))
        val bwk = i1(lux, ln, arr(t, "weight_luma_calculation.bright_weight"))
        var num = 0.0
        var den = 0.0
        for (i in 0 until 256) {
            val b = blocks[i] * exposure
            val ramp = ((b - bs) / Math.max(be - bs, 1e-6)).coerceIn(0.0, 1.0)
            val w = tbl[i / 16][i % 16] * (1.0 + (bwk - 1.0) * ramp)
            num += w * b
            den += w
        }
        return if (den > 0) num / den else 0.0
    }

    // ---- 表取用 ----

    private fun arr(o: JSONObject, key: String): DoubleArray {
        val a = o.optJSONArray(key) ?: return DoubleArray(0)
        return DoubleArray(a.length()) { a.getDouble(it) }
    }

    private fun arr2(o: JSONObject, key: String): Array<DoubleArray> {
        val a = o.optJSONArray(key) ?: return arrayOf(DoubleArray(0))
        return Array(a.length()) { i -> jsonToDoubles(a.getJSONArray(i)) }
    }

    private fun jsonToDoubles(a: JSONArray): DoubleArray =
        DoubleArray(a.length()) { a.getDouble(it) }

    /** 一维线性插值（超界夹取端点，与 np.interp 同）。 */
    private fun i1(x: Double, xs: DoubleArray, ys: DoubleArray): Double {
        if (xs.isEmpty() || ys.isEmpty()) return 0.0
        if (xs.size == 1) return ys[0]
        val n = Math.min(xs.size, ys.size)
        if (x <= xs[0]) return ys[0]
        if (x >= xs[n - 1]) return ys[n - 1]
        var i = 0
        while (i < n - 2 && xs[i + 1] < x) i++
        val t = (x - xs[i]) / Math.max(xs[i + 1] - xs[i], 1e-12)
        return ys[i] + t * (ys[i + 1] - ys[i])
    }

    /** 二维：先按 xs 对每行插值，再按 ys 插值（与 ae.py `_i2` 同）。 */
    private fun i2(x: Double, y: Double, xs: DoubleArray, ys: DoubleArray, z: Array<DoubleArray>): Double {
        if (z.isEmpty()) return 0.0
        val col = DoubleArray(z.size)
        for (i in z.indices) col[i] = i1(x, xs, z[i])
        return i1(y, ys, col)
    }
}
