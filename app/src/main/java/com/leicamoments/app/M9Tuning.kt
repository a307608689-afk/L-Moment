package com.leicamoments.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 17U chromatix（IPE）调校表的 camx 式触发插值 —— 移植自 OffLineM9 `m9/tuning.py` + `m9/tmc.py`。
 *
 * 每个模块是一棵触发树：每层按一个控制变量选出区间 [start, end]，跨区间线性混合，
 * 落在所有区间之外时取最近区间（camx IQInterpolation 行为）。
 * 层序号不是变量 id，故每模块的层变量在此显式给出（LEVEL_VARS，来源 M9-style-analysis）。
 *
 * 只移植主摄 m9 tag（广角 Legend 档）；tele/tele_normal/normal 表未接（日志会提示）。
 */
object M9Tuning {

    private const val TAG = "M9TUNING"
    private const val ASSET = "m9_ipe_tables.json"

    /** 触发树节点：区间 [start, end] + 子节点（子层 List<Region> 或叶 FloatArray）。 */
    private class Region(val start: Double, val end: Double, val child: Any)

    private val LEVEL_VARS = mapOf(
        "gamma152_ipe_v2" to arrayOf("one", "drc", "gain", "one", "lux", "cct"),
        "cc15_ipe_v2" to arrayOf("drc", "flag", "lux", "cct"),
        "cv122_ipe_v2" to arrayOf("drc", "gain", "flag", "lux", "cct"),
        "tdl13_ipe_v2" to arrayOf("drc", "flag", "lux", "cct"),
        "ltm21_ipe_v2" to arrayOf("drc", "lux"),
        "tmc202_sw_v2" to arrayOf("drc", "gain", "lux")
    )

    private const val OPTICAL_ZOOM = 4.0
    private const val GAMMA_N = 257

    @Volatile private var tables: JSONObject? = null
    private val trees = HashMap<String, List<Region>>()

    // ---- 加载 ----

    fun init(app: Context) {
        if (tables != null) return
        synchronized(this) {
            if (tables != null) return
            val t0 = System.currentTimeMillis()
            val text = app.assets.open(ASSET).use { String(it.readBytes(), Charsets.UTF_8) }
            tables = JSONObject(text)
            DiagLog.w(TAG, "IPE 表就绪 ${text.length / 1024}KB ${System.currentTimeMillis() - t0}ms")
        }
    }

    private fun tree(tag: String, module: String): List<Region> {
        val key = "$tag/$module"
        trees[key]?.let { return it }
        val t = tables ?: throw IllegalStateException("M9Tuning.init 未调用")
        val leaves = t.getJSONObject(tag).getJSONArray(module)
        val root = buildTree(leaves)
        sortRecursive(root)
        trees[key] = root
        return root
    }

    /** 与 tuning.py `_build` 同构：按 path 逐层合并建树，叶为 data 数组。 */
    private fun buildTree(leaves: JSONArray): MutableList<Region> {
        val root = ArrayList<Region>()
        for (i in 0 until leaves.length()) {
            val lf = leaves.getJSONObject(i)
            val path = lf.getJSONArray("path")
            val data = lf.getJSONArray("data")
            val values = FloatArray(data.length()) { data.getDouble(it).toFloat() }
            var node: MutableList<Region> = root
            for (k in 0 until path.length()) {
                val seg = path.getJSONArray(k)
                val s = seg.getDouble(1)
                val e = seg.getDouble(2)
                var hit = node.firstOrNull { it.start == s && it.end == e }
                if (hit == null) {
                    hit = Region(s, e, if (k < path.length() - 1) ArrayList<Region>() else values)
                    node.add(hit)
                }
                @Suppress("UNCHECKED_CAST")
                node = hit.child as MutableList<Region>
            }
        }
        return root
    }

    private fun sortRecursive(node: MutableList<Region>) {
        node.sortWith(compareBy({ it.start }, { it.end }))
        for (r in node) {
            val c = r.child
            if (c is MutableList<*>) {
                @Suppress("UNCHECKED_CAST")
                sortRecursive(c as MutableList<Region>)
            }
        }
    }

    // ---- 查询 ----

    /**
     * 取模块在给定触发条件下的插值叶（camx IQInterpolation）。
     * 与 tuning.py `lookup` 等价；'one' 层固定取 0.5。
     */
    fun lookup(tag: String, module: String, drc: Double = 1.0, gain: Double = 1.0,
               lux: Double = 300.0, cct: Double = 5000.0, flag: Double = 0.0): FloatArray {
        val lv = LEVEL_VARS[module] ?: throw IllegalArgumentException("未知模块 $module")
        val m = mapOf("drc" to drc, "gain" to gain, "lux" to lux, "cct" to cct,
            "flag" to flag, "zoom" to OPTICAL_ZOOM)
        val vals = DoubleArray(lv.size) { if (lv[it] == "one") 0.5 else (m[lv[it]] ?: 0.5) }
        return eval(tree(tag, module), vals, 0)
    }

    private fun eval(node: Any, vals: DoubleArray, k: Int): FloatArray {
        if (node is FloatArray) return node
        @Suppress("UNCHECKED_CAST")
        val regs = node as List<Region>
        val v = vals[k]
        var inside: Region? = null
        var lo: Region? = null
        var hi: Region? = null
        for (r in regs) {
            if (inside == null && v >= r.start && v <= r.end) inside = r
            if (r.end < v) lo = r                  // 已按 start 排序，后者更靠近
            if (hi == null && r.start > v) hi = r
        }
        inside?.let { return eval(it.child, vals, k + 1) }
        if (lo == null) return eval(hi!!.child, vals, k + 1)
        if (hi == null) return eval(lo.child, vals, k + 1)
        val t = (v - lo.end) / Math.max(hi.start - lo.end, 1e-9)
        val a = eval(lo.child, vals, k + 1)
        val c = eval(hi.child, vals, k + 1)
        return FloatArray(a.size) { ((1 - t) * a[it] + t * c[it]).toFloat() }
    }

    // ---- 模块解码（tuning.py 同款取值）----

    /** gamma152：257 项曲线，线性 [0,1] 入 → 10bit 出，归一化到 [0,1]。 */
    fun gammaLut(tag: String, drc: Double, gain: Double, lux: Double, cct: Double): FloatArray {
        val g = lookup(tag, "gamma152_ipe_v2", drc, gain, lux, cct)
        val n = Math.min(g.size, GAMMA_N)
        return FloatArray(n) { g[it] / 1023f }
    }

    /** cc15：前 9 项为 3x3 色彩矩阵（第 2 组与 #30-62 是 AI 类别 CCM，未用）。 */
    fun ccMatrix(tag: String, drc: Double, flag: Double, lux: Double, cct: Double): FloatArray {
        val d = lookup(tag, "cc15_ipe_v2", drc, 1.0, lux, cct, flag)
        return FloatArray(9) { d[it] }
    }

    /** cv122：[ry gy by 0 | ap am | bp bm | cp cm | dp dm | k_cb k_cr]。 */
    fun cvParams(tag: String, drc: Double, gain: Double, flag: Double, lux: Double, cct: Double): FloatArray {
        val d = lookup(tag, "cv122_ipe_v2", drc, gain, lux, cct, flag)
        return FloatArray(14) { d[it] }
    }

    /** tdl13：hue 位移（度，24 色相 × 16 饱和）+ 饱和度相对增益（24 × 16）。 */
    fun tdlTables(tag: String, drc: Double, flag: Double, lux: Double, cct: Double):
            Pair<FloatArray, FloatArray> {
        val d = lookup(tag, "tdl13_ipe_v2", drc, 1.0, lux, cct, flag)
        val hue = FloatArray(384) { d[it] }
        val sat = FloatArray(384) { d[384 + it] }
        return hue to sat
    }

    // ---- TMC202（GTM 曲线，tmc.py 移植）----

    private val CLAMP_HI = doubleArrayOf(0.9995, 0.9996, 0.9997, 0.9998, 0.9999)
    private const val CHROMATIX_GAIN_CAP = 256.0

    /** tmc_lut：按 (gain, lux) 取 tmc202 叶后采样 GTM 曲线到 n 点。 */
    fun tmcLut(drcGain: Double, lux: Double, n: Int = 4096, tag: String = "m9"): FloatArray {
        val leaf = lookup(tag, "tmc202_sw_v2", Math.max(drcGain, 1.0), 1.0, lux)
        val knees = anchorKnees(leaf, Math.max(drcGain, 1.0), 1.0, null)
        val out = FloatArray(n)
        val h = hermiteCoeffs(knees.first, knees.second)
        for (i in 0 until n) {
            out[i] = gtmCurveValue(i.toDouble() / (n - 1), knees.first, knees.second, h).toFloat()
        }
        return out
    }

    private fun clampSeq(vals: DoubleArray): DoubleArray {
        val out = DoubleArray(vals.size)
        var prev = 0.0
        for (i in vals.indices) {
            val lo = if (i > 0) prev + 0.0001 else 0.0001
            out[i] = if (vals[i] > lo) Math.min(vals[i], CLAMP_HI[i]) else lo
            prev = out[i]
        }
        return out
    }

    /** CalculateAnchorKneePoints（M9 路径）：返回 (X knots[7], Y knots[7])。 */
    fun anchorKnees(leafIn: FloatArray, drcGain: Double, drcDark: Double,
                    pcIn: Double?): Pair<DoubleArray, DoubleArray> {
        val p = DoubleArray(leafIn.size) { leafIn[it].toDouble() }
        if (p.size < 0x2b) return Pair(doubleArrayOf(0.0, 1.0), doubleArrayOf(0.0, 1.0))
        p[4] = 0.0                          // node +0x58 == 0
        for (i in 12 until 16) p[i] = 0.0    // node +0x5c == 1 → memset(p+48,0,16)
        var g = 1.0 + (drcGain - 1.0) * p[0x10] + p[0x11]
        g = if (g > 1e-6) Math.min(g, 1023.0) else 1e-6
        var gd = 1.0 + (drcDark - 1.0) * p[0x13] + p[0x14]
        gd = if (gd > 1e-6) Math.min(gd, 1023.0) else 1e-6
        val lim = Math.min(g * gd, CHROMATIX_GAIN_CAP)
        val r = g - 1.0
        val q = (p[0x2a] - p[0x26]) * 0.25
        val g3 = 1 + r * p[0x27]
        val g4 = 1 + r * p[0x28]
        val g5 = 1 + r * p[0x29]

        var x1 = p[0x25] / lim
        x1 = if (x1 > p[0x24] + 0.0001) Math.min(x1, 0.9995) else p[0x24] + 0.0001
        var x2 = p[0x26] / g
        x2 = if (x2 > x1 + 0.0001) Math.min(x2, 0.9996) else x1 + 0.0001
        var x3 = (p[0x26] + q) / g3
        x3 = if (x3 > x2 + 0.0001) Math.min(x3, 0.9997) else x2 + 0.0001
        var x4 = (p[0x26] + 2 * q) / g4
        x4 = if (x4 > x3 + 0.0001) Math.min(x4, 0.9998) else x3 + 0.0001
        var x5 = (p[0x26] + 3 * q) / g5
        x5 = if (x5 > x4 + 0.0001) Math.min(x5, 0.9999) else x4 + 0.0001

        val inner = clampSeq(doubleArrayOf(x1, x2, x3, x4, x5))
        val X = DoubleArray(7)
        X[0] = 0.0
        for (i in 0 until 5) X[i + 1] = inner[i]
        X[6] = 1.0

        val pc = pcIn ?: p[0]
        val gains = doubleArrayOf(lim, g, g3, g4, g5)
        val yRaw = DoubleArray(5) { X[it + 1] * Math.pow(gains[it], pc) }
        val yInner = clampSeq(yRaw)
        val Y = DoubleArray(7)
        Y[0] = 0.0
        for (i in 0 until 5) Y[i + 1] = yInner[i]
        Y[6] = 1.0
        return Pair(X, Y)
    }

    /** FUN_00e9fa30：加权调和斜率 d1..d5 + 端点斜率 d6 与三次系数。 */
    private class Hermite(val d: DoubleArray, val c2: DoubleArray, val c3: DoubleArray)

    private fun hermiteCoeffs(X: DoubleArray, Y: DoubleArray): Hermite {
        val h = DoubleArray(6) { X[it + 1] - X[it] }
        val dl = DoubleArray(6) { (Y[it + 1] - Y[it]) / h[it] }
        val d = DoubleArray(7)
        for (k in 1..5) {
            val w1 = 2 * h[k] + h[k - 1]
            val w2 = h[k] + 2 * h[k - 1]
            d[k] = (w1 + w2) / (w1 / dl[k - 1] + w2 / dl[k])
        }
        var e = ((h[4] + 2 * h[5]) * dl[5] - h[5] * dl[4]) / (h[4] + h[5])
        e = if (dl[4] * dl[5] >= 0 || Math.abs(e) <= Math.abs(3 * dl[5])) {
            if (dl[5] * e >= 0) e else 0.0
        } else {
            3 * dl[5]
        }
        d[6] = e
        val c2 = DoubleArray(7)
        val c3 = DoubleArray(7)
        for (k in 1..5) {
            c2[k] = (3 * dl[k] - 2 * d[k] - d[k + 1]) / h[k]
            c3[k] = (d[k] - 2 * dl[k] + d[k + 1]) / h[k] / h[k]
        }
        return Hermite(d, c2, c3)
    }

    private fun gtmCurveValue(xIn: Double, X: DoubleArray, Y: DoubleArray, hm: Hermite): Double {
        val x = xIn.coerceIn(0.0, 1.0)
        var y = x * (Y[1] / X[1])
        for (k in 1..5) {
            if (x >= X[k] && x <= X[k + 1]) {
                val t = x - X[k]
                return Y[k] + hm.d[k] * t + hm.c2[k] * t * t + hm.c3[k] * t * t * t
            }
        }
        return y
    }
}
