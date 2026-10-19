package com.leicamoments.app

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * OffLineM9 RAW 前段（B2Y「ForRGB」之前）——移植自 `m9/frontend.py` + `m9/ae.py`。
 *
 * 17U 链路：AllinOne(RAW16 → 线性 RGB16, BLC/LSC/WB) → B2Y ForRGB(LTM/CC/2D-LUT/gamma/CV)。
 * 离线取舍：DNG 已由相机完成合成与降噪，这里从 LibRaw 线性 RGB 起步；AE 在本图线性数据上重测光。
 *
 * 逐像素链（固定 trigger 下表值全部预先取好，只在像素上做算术）：
 *   lin × gain → LTM 全局曲线（TMC/GTM 的 Y 比例增益）→ CC 矩阵 → tdl13(HSV 2D LUT)
 *   → gamma152 → cv122(→ BT.601 full 反变换) → clip [0,1]
 *
 * 只支持主摄（camera="main"）；tele/normal 表未接。
 */
object M9Frontend {

    private const val TAG = "M9FE"
    private const val ROWS_PER_BLOCK = 64
    private const val LEGEND_CCT = 5003.0

    class Result(
        val argb: IntArray,
        val w: Int,
        val h: Int,
        val luxIndex: Double,
        val cct: Double,
        val zoom: Double,
        val ae: M9Ae.AEResult,
        val make: String,
        val model: String
    )

    // ---- 色彩常量 ----

    private val SRGB_TO_XYZ = arrayOf(
        doubleArrayOf(0.4124564, 0.3575761, 0.1804375),
        doubleArrayOf(0.2126729, 0.7151522, 0.0721750),
        doubleArrayOf(0.0193339, 0.1191920, 0.9503041)
    )
    private val XYZ_D50 = doubleArrayOf(0.96422, 1.0, 0.82521)

    private val ILLUM_CCT = mapOf(
        17 to 2856.0, 21 to 6504.0, 20 to 5503.0, 22 to 7504.0, 23 to 5003.0, 1 to 5500.0,
        2 to 4200.0, 3 to 2856.0, 10 to 6500.0, 11 to 7500.0, 12 to 6400.0, 13 to 5000.0,
        14 to 4150.0, 15 to 3450.0, 18 to 4874.0, 19 to 6774.0, 24 to 3200.0
    )

    /** 是否是可处理 RAW（DNG 必需 ColorMatrix1）。 */
    fun isSupported(dng: ByteArray): Boolean {
        val t = DngTags(dng)
        return t.ok() && t.doubles(DngTags.COLOR_MATRIX1)?.size == 9
    }

    /**
     * @param ev 曝光补偿（档），作用于 AE 的 short 曝光
     * @return 显示参考 RGB（ARGB 像素，B2Y 输出）；失败 null
     */
    fun b2y(app: Context, dng: ByteArray, ev: Double = 0.0): Result? {
        return try {
            val t0 = System.currentTimeMillis()
            M9Tuning.init(app)
            M9Ae.init(app)

            val tags = DngTags(dng)
            if (!tags.ok()) {
                DiagLog.e(TAG, "DNG 标签解析失败", null)
                return null
            }
            val cm1 = mat3(tags.doubles(DngTags.COLOR_MATRIX1)) ?: run {
                DiagLog.e(TAG, "缺少 ColorMatrix1", null)
                return null
            }
            val cm2 = mat3(tags.doubles(DngTags.COLOR_MATRIX2))

            // AsShotNeutral：缺失时退化为全 1（与 OffLineM9 的 rawpy 回退同语义）
            var neutral = tags.doubles(DngTags.AS_SHOT_NEUTRAL)
            if (neutral == null || neutral.size < 3) neutral = doubleArrayOf(1.0, 1.0, 1.0)
            val nn = doubleArrayOf(neutral[0] / neutral[1], 1.0, neutral[2] / neutral[1])

            val sceneCct = solveCct(cm1, cm2, tags, nn)
            // wb="legend"：锁定 17U Legend D50 点
            val nRaw = mulVec3(cmAt(cm1, cm2, tags, LEGEND_CCT), XYZ_D50)
            val nWb = doubleArrayOf(nRaw[0] / nRaw[1], 1.0, nRaw[2] / nRaw[1])
            val mul = doubleArrayOf(1.0 / nWb[0], 1.0 / nWb[1], 1.0 / nWb[2])

            // ---- 线性解码（16bit，相机色彩空间，已应用 mul）----
            val buf = RawDecoder.nativeDecodeLinear(dng, mul, false) ?: run {
                DiagLog.e(TAG, "线性解码失败", null)
                return null
            }
            try {
                val bb = buf.duplicate().order(ByteOrder.nativeOrder())
                val w = bb.getInt(0)
                val h = bb.getInt(4)
                if (w <= 0 || h <= 0) return null

                val info = tags
                val ev100 = ev100(info)
                val lux = if (ev100 != 0.0) luxIndexFromEv(ev100) else 300.0
                val cct = sceneCct

                val ae = M9Ae.meter(bb, w, h, lux, "m9")
                val gain = ae.expShort * Math.pow(2.0, ev)

                // cam2srgb：diag(1/n) 施加后的相机空间 → 线性 sRGB
                val srgb2cam = mul3(diagMul(mul, cmAt(cm1, cm2, tags, LEGEND_CCT)), SRGB_TO_XYZ)
                for (i in 0 until 3) {
                    val s = srgb2cam[i][0] + srgb2cam[i][1] + srgb2cam[i][2]
                    for (j in 0 until 3) srgb2cam[i][j] /= s
                }
                val cam2srgb = inv3(srgb2cam)

                // CC：M9/normal 相对矩阵作用在 DNG 色彩矩阵之上（mode=m9）
                val ccM9 = M9Tuning.ccMatrix("m9", ae.adrcGain, 0.0, lux, cct)
                val ccNormal = M9Tuning.ccMatrix("normal", ae.adrcGain, 0.0, lux, cct)
                val rel = mul3(mat3f(ccM9), inv3(mat3f(ccNormal)))
                val m = mul3(rel, cam2srgb)

                val tdl = M9Tuning.tdlTables("m9", ae.adrcGain, 0.0, lux, cct)
                val glut = M9Tuning.gammaLut("m9", ae.adrcGain, 1.0, lux, cct)
                val cvp = M9Tuning.cvParams("m9", ae.adrcGain, 1.0, 0.0, lux, cct)
                val gtm = M9Tuning.tmcLut(ae.adrcGain, lux, 4096)

                val argb = chain(bb, w, h, gain, m, tdl.first, tdl.second, glut, cvp, gtm)

                // 只支持主摄（camera="main"）：LeicaFilter zoom 恒 1.0（长焦分支未移植）
                val zoom = 1.0
                DiagLog.w(TAG, "前段完成 ${w}x$h lux=${lux.toInt()} cct=${cct.toInt()} " +
                        "EV100=${"%.2f".format(ev100)} base=${ae.baseTarget} mid=${ae.midTarget} " +
                        "expShort=${"%.3f".format(ae.expShort)} adrc=${"%.3f".format(ae.adrcGain)} " +
                        "${System.currentTimeMillis() - t0}ms")
                Result(argb, w, h, lux, cct, zoom, ae, tags.string(DngTags.MAKE), tags.string(DngTags.MODEL))
            } finally {
                RawDecoder.nativeFreeLinear(buf)
            }
        } catch (t: Throwable) {
            DiagLog.e(TAG, "前段失败", t)
            null
        }
    }

    // ---- 逐块像素链 ----

    private fun chain(bb: ByteBuffer, w: Int, h: Int, gain: Double,
                      m: Array<DoubleArray>, hueTab: FloatArray, satTab: FloatArray,
                      glut: FloatArray, cvp: FloatArray, gtm: FloatArray): IntArray {
        val out = IntArray(w * h)
        val stride = w * 3 * 2
        val rows = Math.min(ROWS_PER_BLOCK, h)
        val cap = w * rows
        val rp = FloatArray(cap)
        val gp = FloatArray(cap)
        val bp = FloatArray(cap)

        var y0 = 0
        while (y0 < h) {
            val y1 = Math.min(y0 + rows, h)
            val cnt = (y1 - y0) * w

            // 1) 读入 + 曝光增益
            var p = 0
            for (y in y0 until y1) {
                var o = y * stride
                for (x in 0 until w) {
                    rp[p] = ((bb.getShort(o).toInt() and 0xFFFF) / 65535f * gain).toFloat()
                    gp[p] = ((bb.getShort(o + 2).toInt() and 0xFFFF) / 65535f * gain).toFloat()
                    bp[p] = ((bb.getShort(o + 4).toInt() and 0xFFFF) / 65535f * gain).toFloat()
                    p++
                    o += 6
                }
            }

            // 2) LTM：全局 GTM 曲线作为 Y 比例增益
            val gtmN = gtm.size
            for (i in 0 until cnt) {
                val r = rp[i]; val g = gp[i]; val b = bp[i]
                val yy = 0.299f * r + 0.587f * g + 0.114f * b
                val yc = yy.coerceIn(0f, 1f)
                val fc = yc * (gtmN - 1)
                val i0 = Math.min(fc.toInt(), gtmN - 2)
                val tf = fc - i0
                val c = gtm[i0] + tf * (gtm[i0 + 1] - gtm[i0])
                val gg = c / Math.max(yy, 1e-6f)
                rp[i] = r * gg; gp[i] = g * gg; bp[i] = b * gg
            }

            // 3) CC 矩阵（M = rel · cam2srgb，非负）
            for (i in 0 until cnt) {
                val r = rp[i]; val g = gp[i]; val b = bp[i]
                rp[i] = (m[0][0] * r + m[0][1] * g + m[0][2] * b).toFloat().coerceAtLeast(0f)
                gp[i] = (m[1][0] * r + m[1][1] * g + m[1][2] * b).toFloat().coerceAtLeast(0f)
                bp[i] = (m[2][0] * r + m[2][1] * g + m[2][2] * b).toFloat().coerceAtLeast(0f)
            }

            // 4) tdl13：HSV 域的色相位移 + 饱和度增益
            for (i in 0 until cnt) {
                var r = rp[i]; var g = gp[i]; var b = bp[i]
                var mx = Math.max(r, Math.max(g, b))
                var mn = Math.min(r, Math.min(g, b))
                val scale = if (mx > 1e-6f) mx else 1e-6f
                var hh = 0f
                val d = mx - mn
                if (d > 1e-9f) {
                    hh = when {
                        mx == r -> 60f * (((g - b) / d) % 6f)
                        mx == g -> 60f * ((b - r) / d + 2f)
                        else -> 60f * ((r - g) / d + 4f)
                    }
                    if (hh < 0f) hh += 360f
                }
                val ss = d / scale

                val hi = hh / 15f
                val h0f = Math.floor(hi.toDouble()).toInt()
                var h0 = h0f % 24; if (h0 < 0) h0 += 24
                val h1 = (h0 + 1) % 24
                val fh = (hi - h0f).toFloat()
                val si = ss * 15f
                var s0 = Math.floor(si.toDouble()).toInt().coerceIn(0, 15)
                val s1 = (s0 + 1).coerceAtMost(15)
                val fs = (si - s0).coerceIn(0f, 1f)

                val hueShift = bil(hueTab, h0, h1, s0, s1, fh, fs)
                val satGain = bil(satTab, h0, h1, s0, s1, fh, fs)

                var hNew = (hh + hueShift) % 360f
                if (hNew < 0f) hNew += 360f
                val sNew = (ss * (1f + satGain)).coerceIn(0f, 1f)

                // HSV → RGB（V = scale）
                val c = sNew
                val hp = hNew / 60f
                val xx = c * (1f - Math.abs(hp % 2f - 1f))
                when (hp.toInt()) {
                    0 -> { r = c; g = xx; b = 0f }
                    1 -> { r = xx; g = c; b = 0f }
                    2 -> { r = 0f; g = c; b = xx }
                    3 -> { r = 0f; g = xx; b = c }
                    4 -> { r = xx; g = 0f; b = c }
                    else -> { r = c; g = 0f; b = xx }
                }
                val mm = 1f - c
                rp[i] = (r + mm) * scale
                gp[i] = (g + mm) * scale
                bp[i] = (b + mm) * scale
            }

            // 5) gamma152
            val gN = glut.size
            for (i in 0 until cnt) {
                rp[i] = lut1d(glut, gN, rp[i])
                gp[i] = lut1d(glut, gN, gp[i])
                bp[i] = lut1d(glut, gN, bp[i])
            }

            // 6) cv122 → BT.601 full 反变换
            for (i in 0 until cnt) {
                val r = rp[i]; val g = gp[i]; val b = bp[i]
                val yy = cvp[0] * r + cvp[1] * g + cvp[2] * b
                val bg = b - g
                val rg = r - g
                val cbv = (bg + (if (bg >= 0f) cvp[6] else cvp[7]) * rg) * (if (bg + (if (bg >= 0f) cvp[6] else cvp[7]) * rg >= 0f) cvp[4] else cvp[5])
                val crv = (rg + (if (rg >= 0f) cvp[10] else cvp[11]) * bg) * (if (rg + (if (rg >= 0f) cvp[10] else cvp[11]) * bg >= 0f) cvp[8] else cvp[9])
                val rr = yy + 1.402f * crv
                val gg = yy - 0.344136f * cbv - 0.714136f * crv
                val bb2 = yy + 1.772f * cbv
                val ro = (rr.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                val go = (gg.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                val bo = (bb2.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                out[(y0 * w) + i] = (0xFF shl 24) or (ro shl 16) or (go shl 8) or bo
            }

            y0 = y1
        }
        return out
    }

    private fun lut1d(lut: FloatArray, n: Int, v: Float): Float {
        val x = (v.coerceIn(0f, 1f) * (n - 1))
        val i0 = Math.min(x.toInt(), n - 2)
        val t = x - i0
        return lut[i0] + t * (lut[i0 + 1] - lut[i0])
    }

    private fun bil(t: FloatArray, h0: Int, h1: Int, s0: Int, s1: Int, fh: Float, fs: Float): Float {
        val a = t[h0 * 16 + s0]; val b = t[h0 * 16 + s1]
        val c = t[h1 * 16 + s0]; val d = t[h1 * 16 + s1]
        return (1 - fh) * ((1 - fs) * a + fs * b) + fh * ((1 - fs) * c + fs * d)
    }

    // ---- DNG 色彩模型 ----

    private fun mat3(v: DoubleArray?): Array<DoubleArray>? {
        if (v == null || v.size < 9) return null
        return arrayOf(
            doubleArrayOf(v[0], v[1], v[2]),
            doubleArrayOf(v[3], v[4], v[5]),
            doubleArrayOf(v[6], v[7], v[8])
        )
    }

    private fun mat3f(v: FloatArray): Array<DoubleArray> = arrayOf(
        doubleArrayOf(v[0].toDouble(), v[1].toDouble(), v[2].toDouble()),
        doubleArrayOf(v[3].toDouble(), v[4].toDouble(), v[5].toDouble()),
        doubleArrayOf(v[6].toDouble(), v[7].toDouble(), v[8].toDouble())
    )

    /** 矩阵 × 列向量。 */
    private fun mulVec3(m: Array<DoubleArray>, v: DoubleArray): DoubleArray =
        doubleArrayOf(
            m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
            m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
            m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2]
        )

    private fun cmAt(cm1: Array<DoubleArray>, cm2: Array<DoubleArray>?, tags: DngTags, cct: Double): Array<DoubleArray> {
        if (cm2 == null) return cm1
        val t1 = ILLUM_CCT[tags.value(DngTags.CALIB_ILLUM1, 21.0).toInt()] ?: 6504.0
        val t2 = ILLUM_CCT[tags.value(DngTags.CALIB_ILLUM2, 17.0).toInt()] ?: 2856.0
        val w = if (t1 != t2) ((1 / cct - 1 / t2) / (1 / t1 - 1 / t2)).coerceIn(0.0, 1.0) else 1.0
        val out = Array(3) { DoubleArray(3) }
        for (i in 0 until 3) for (j in 0 until 3) out[i][j] = w * cm1[i][j] + (1 - w) * cm2[i][j]
        return out
    }

    private fun solveCct(cm1: Array<DoubleArray>, cm2: Array<DoubleArray>?, tags: DngTags, neutral: DoubleArray): Double {
        if (cm2 == null) return 5000.0
        val t1 = ILLUM_CCT[tags.value(DngTags.CALIB_ILLUM1, 21.0).toInt()] ?: 6504.0
        val t2 = ILLUM_CCT[tags.value(DngTags.CALIB_ILLUM2, 17.0).toInt()] ?: 2856.0
        var cct = 5000.0
        for (iter in 0 until 30) {
            val w = if (t1 != t2) ((1 / cct - 1 / t2) / (1 / t1 - 1 / t2)).coerceIn(0.0, 1.0) else 1.0
            val cm = Array(3) { i -> DoubleArray(3) { j -> w * cm1[i][j] + (1 - w) * cm2[i][j] } }
            val xyz = solve3(cm, neutral) ?: return cct
            val s = xyz[0] + xyz[1] + xyz[2]
            if (s <= 1e-12) return cct
            val nx = xyz[0] / s
            val ny = xyz[1] / s
            val den = 0.1858 - ny
            if (Math.abs(den) < 1e-9) return cct
            val q = (nx - 0.3320) / den
            val newCct = 449 * q * q * q + 3525 * q * q + 6823.3 * q + 5520.33
            if (Math.abs(newCct - cct) < 1) return newCct
            cct = newCct.coerceIn(1500.0, 20000.0)
        }
        return cct
    }

    private fun ev100(tags: DngTags): Double {
        val et = tags.value(DngTags.EXPOSURE_TIME, 0.0)
        val fn = tags.value(DngTags.FNUMBER, 0.0)
        val iso = tags.value(DngTags.ISO, 0.0)
        if (et <= 0 || fn <= 0 || iso <= 0) return 0.0
        return Math.log(fn * fn / et) / Math.log(2.0) - Math.log(iso / 100.0) / Math.log(2.0)
    }

    /** Qualcomm lux index（+1 档 ≈ 1.03× 曝光），锚点 EV100 13 → 170。 */
    fun luxIndexFromEv(ev100: Double): Double = (474.9 - 23.45 * ev100).coerceIn(0.0, 999.0)

    // ---- 3x3 线性代数 ----

    private fun mul3(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
        val o = Array(3) { DoubleArray(3) }
        for (i in 0 until 3) for (j in 0 until 3) {
            var s = 0.0
            for (k in 0 until 3) s += a[i][k] * b[k][j]
            o[i][j] = s
        }
        return o
    }

    private fun diagMul(d: DoubleArray, m: Array<DoubleArray>): Array<DoubleArray> =
        Array(3) { i -> DoubleArray(3) { j -> d[i] * m[i][j] } }

    private fun inv3(m: Array<DoubleArray>): Array<DoubleArray> {
        val det = m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) -
                m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
                m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])
        if (Math.abs(det) < 1e-12) return m
        val id = 1.0 / det
        return arrayOf(
            doubleArrayOf(
                (m[1][1] * m[2][2] - m[1][2] * m[2][1]) * id,
                (m[0][2] * m[2][1] - m[0][1] * m[2][2]) * id,
                (m[0][1] * m[1][2] - m[0][2] * m[1][1]) * id
            ),
            doubleArrayOf(
                (m[1][2] * m[2][0] - m[1][0] * m[2][2]) * id,
                (m[0][0] * m[2][2] - m[0][2] * m[2][0]) * id,
                (m[0][2] * m[1][0] - m[0][0] * m[1][2]) * id
            ),
            doubleArrayOf(
                (m[1][0] * m[2][1] - m[1][1] * m[2][0]) * id,
                (m[0][1] * m[2][0] - m[0][0] * m[2][1]) * id,
                (m[0][0] * m[1][1] - m[0][1] * m[1][0]) * id
            )
        )
    }

    /** 解 3x3 线性方程组 m·x = v（np.linalg.solve 等价）。 */
    private fun solve3(m: Array<DoubleArray>, v: DoubleArray): DoubleArray? {
        val a = Array(3) { i -> doubleArrayOf(m[i][0], m[i][1], m[i][2], v[i]) }
        for (c in 0 until 3) {
            var piv = c
            for (r in c + 1 until 3) if (Math.abs(a[r][c]) > Math.abs(a[piv][c])) piv = r
            if (Math.abs(a[piv][c]) < 1e-12) return null
            val tmp = a[c]; a[c] = a[piv]; a[piv] = tmp
            for (r in c + 1 until 3) {
                val f = a[r][c] / a[c][c]
                for (k in c until 4) a[r][k] -= f * a[c][k]
            }
        }
        val x = DoubleArray(3)
        for (r in 2 downTo 0) {
            var s = a[r][3]
            for (k in r + 1 until 3) s -= a[r][k] * x[k]
            x[r] = s / a[r][r]
        }
        return x
    }
}
