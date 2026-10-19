package com.leicamoments.app

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * filter 阶段（快照参数表布局）——参数布局与参考实现 `m9/leicafilter.py` 一致。
 *
 * 参数文件 `leica_filter_param_m9_{snapshot,preview}.bin` 布局（小端）：
 *   0x000 u16 n_scene, u16 scene_size[n_scene], u16 param_off(0x400), u16 lut_off(0x1000),
 *         u16 lut_n(17), u16 n_zoom, u16 zoom_size[n_zoom]
 *   0x320 char param_version[64]
 *   0x360 u16 lut_preview_en, lut_snapshot_en, shading_preview_en, shading_snapshot_en
 *   param_off: 每 scene { char name[64]; u16 n_lux; n_lux × { u16 lo, hi, n_cct;
 *               n_cct × { u16 lo, hi, idx } } }（scene_size 字节）
 *   随后每 zoom { f32 zoom; 同款 lux/cct 树 }（zoom_size 字节）
 *   lut_off:   n_lut × u8[17][17][17][3]，索引 [r][g][b]，texel 为 (B, G, R)
 *   尾部:      n_shading × f32[8]
 *
 * 风格差异：StyleTrans 已在管线内跑过时用 snapshot 参数；未跑过用 preview（内置近似风格）。
 */
object M9LeicaFilter {

    private const val TAG = "M9LF"
    private const val ASSET_SNAPSHOT = "leica_filter_param_m9_snapshot.bin"
    private const val ASSET_PREVIEW = "leica_filter_param_m9_preview.bin"
    private const val LUT_N = 17

    /**
     * 节点：区间 [lo,hi]；有子层（cct 列表）时为内层，否则 idx 指向 LUT/暗角条目。
     */
    private class Bin(val lo: Int, val hi: Int, val idx: Int, val sub: List<Bin>?)

    class Params(bytes: ByteArray) {
        private val b = bytes
        private val u16: IntArray
        val version: String
        private val luts: Array<ByteArray>
        private val scenes = HashMap<String, List<Bin>>()
        private val zooms = ArrayList<Pair<Float, List<Bin>>>()
        private val nShading: Int
        private val shaded: FloatArray

        init {
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val n16 = bytes.size / 2
            u16 = IntArray(n16) { bb.getShort(it * 2).toInt() and 0xFFFF }

            val nScene = u16[0]
            val sceneSizes = IntArray(nScene) { u16[1 + it] }
            var p = 1 + nScene
            val paramOff = u16[p]
            val lutOff = u16[p + 1]
            val lutN = u16[p + 2]
            val nZoom = u16[p + 3]
            val zoomSizes = IntArray(nZoom) { u16[p + 4 + it] }
            version = ascii(0x320, 64)

            var off = paramOff
            var nLut = 0
            for (sz in sceneSizes) {
                val name = ascii(off, 64)
                val tree = readTree(u16, (off + 64) / 2)
                scenes[name] = tree
                nLut = Math.max(nLut, maxIdx(tree))
                off += sz
            }
            for (sz in zoomSizes) {
                val z = bb.getFloat(off)
                val tree = readTree(u16, (off + 4) / 2)
                zooms.add(Pair(z, tree))
                off += sz
            }
            zooms.sortBy { it.first }

            val lutBytes = lutN * lutN * lutN * 3
            luts = Array(nLut) { i ->
                bytes.copyOfRange(lutOff + i * lutBytes, lutOff + (i + 1) * lutBytes)
            }
            val shadingOff = lutOff + nLut * lutBytes
            nShading = Math.max(1, (bytes.size - shadingOff) / 32)
            shaded = FloatArray(nShading * 8) { bb.getFloat(shadingOff + it * 4) }
        }

        private fun ascii(off: Int, len: Int): String {
            val end = (off + len).coerceAtMost(b.size)
            val sb = StringBuilder()
            for (i in off until end) {
                val c = b[i].toInt() and 0xFF
                if (c == 0) break
                sb.append(c.toChar())
            }
            return sb.toString().trim()
        }

        private fun maxIdx(list: List<Bin>): Int {
            var m = -1
            for (bn in list) {
                val s = bn.sub
                m = if (s != null) Math.max(m, maxIdx(s)) else Math.max(m, bn.idx)
            }
            return m
        }

        private fun readTree(u: IntArray, pos0: Int): List<Bin> {
            var pos = pos0
            val nLux = u[pos]; pos++
            val lux = ArrayList<Bin>(nLux)
            for (i in 0 until nLux) {
                val lo = u[pos]; val hi = u[pos + 1]; val nCct = u[pos + 2]; pos += 3
                val ccts = ArrayList<Bin>(nCct)
                for (k in 0 until nCct) {
                    ccts.add(Bin(u[pos], u[pos + 1], u[pos + 2], null))
                    pos += 3
                }
                lux.add(Bin(lo, hi, -1, ccts))
            }
            return lux
        }

        /** findElementAndPrevious：含 v 的区间，或跨隙时的前后两区间；越界夹端点。 */
        private fun find(bins: List<Bin>, v: Int): Pair<Bin, Bin> {
            if (bins.isEmpty()) {
                val e = Bin(0, 0, 0, null)
                return Pair(e, e)
            }
            for (i in bins.indices) {
                val bn = bins[i]
                if (v <= bn.hi) {
                    return if (v >= bn.lo || i == 0) Pair(bn, bn) else Pair(bins[i - 1], bn)
                }
            }
            val last = bins[bins.size - 1]
            return Pair(last, last)
        }

        private fun frac(v: Double, e: Bin, p: Bin): Double {
            val den = p.lo.toDouble() - e.hi.toDouble()
            return if (den != 0.0) (v - e.hi) / den else 0.0
        }

        private fun corners(tree: List<Bin>, lux: Int, cct: Int): List<Pair<Int, Double>> {
            val (le, lp) = find(tree, lux)
            val a = frac(lux.toDouble(), le, lp)
            val ce = le.sub ?: emptyList()
            val cp = lp.sub ?: emptyList()
            val (c0, c1) = find(ce, cct)
            val bq = frac(cct.toDouble(), c0, c1)
            val (d0, d1) = find(cp, cct)
            val cq = frac(cct.toDouble(), d0, d1)
            return listOf(
                Pair(c0.idx, (1 - a) * (1 - bq)),
                Pair(c1.idx, (1 - a) * bq),
                Pair(d0.idx, a * (1 - cq)),
                Pair(d1.idx, a * cq)
            )
        }

        /** 混合后的 u8 LUT（17³×3，texel 为 B,G,R），trunc 语义同插件。 */
        fun lut(scene: String, luxIndex: Double, cct: Double): ByteArray {
            val tree = scenes[scene] ?: scenes["common"] ?: scenes.values.firstOrNull() ?: return ByteArray(LUT_N * LUT_N * LUT_N * 3)
            val cs = corners(tree, luxIndex.toInt(), cct.toInt())
            val acc = LinkedHashMap<Int, Double>()
            for ((i, w) in cs) acc[i] = (acc[i] ?: 0.0) + w
            val size = LUT_N * LUT_N * LUT_N * 3
            if (acc.size == 1) {
                val only = acc.keys.first()
                return if (only in luts.indices) luts[only] else ByteArray(size)
            }
            val s = FloatArray(size)
            for ((i, w) in acc) {
                if (i !in luts.indices) continue
                val src = luts[i]
                val wf = w.toFloat()
                for (k in 0 until size) s[k] += wf * (src[k].toInt() and 0xFF)
            }
            return ByteArray(size) { Math.floor(s[it].toDouble()).toInt().coerceIn(0, 255).toByte() }
        }

        /** CvStyle 参数（8 个）：按 zoom 选树，再按 lux/cct 四角混合。 */
        fun shadingParams(zoom: Double, luxIndex: Double, cct: Double): FloatArray {
            if (zooms.isEmpty()) return FloatArray(8)
            var tree = zooms[0].second
            for ((z, t) in zooms) if (zoom >= z) tree = t
            val cs = corners(tree, luxIndex.toInt(), cct.toInt())
            val out = FloatArray(8)
            for ((i, w) in cs) {
                if (i < 0 || i >= nShading) continue
                val wf = w.toFloat()
                for (k in 0 until 8) out[k] += wf * shaded[i * 8 + k]
            }
            return out
        }
    }

    @Volatile private var snapshot: Params? = null
    @Volatile private var preview: Params? = null

    private fun load(app: Context, asset: String): Params {
        val bytes = app.assets.open(asset).use { it.readBytes() }
        val p = Params(bytes)
        DiagLog.w(TAG, "$asset 就绪 v=${p.version} ${bytes.size / 1024}KB")
        return p
    }

    private fun params(app: Context, styleTransOn: Boolean): Params {
        if (styleTransOn) {
            snapshot?.let { return it }
            synchronized(this) {
                snapshot?.let { return it }
                val p = load(app, ASSET_SNAPSHOT)
                snapshot = p
                return p
            }
        }
        preview?.let { return it }
        synchronized(this) {
            preview?.let { return it }
            val p = load(app, ASSET_PREVIEW)
            preview = p
            return p
        }
    }

    /**
     * 应用到 ARGB 像素（原地不动，返回新数组）：CubeLutEffect 三线性 + CvStyle 亮度暗角。
     * 逐行执行且只需当前行/坐标，不需要全图 float 缓冲。
     */
    fun applyArgb(app: Context, src: IntArray, w: Int, h: Int,
                  luxIndex: Double, cct: Double, zoom: Double, scene: String,
                  styleTransOn: Boolean): IntArray {
        val p = params(app, styleTransOn)
        val lutU8 = p.lut(scene, luxIndex, cct)
        val sh = p.shadingParams(zoom, luxIndex, cct)

        // bin 的 texel 为 (B,G,R) → 展开成 float 的 (R,G,B)
        val lut = FloatArray(lutU8.size)
        var k = 0
        var i = 0
        while (i < lutU8.size) {
            lut[k] = (lutU8[i + 2].toInt() and 0xFF) / 255f
            lut[k + 1] = (lutU8[i + 1].toInt() and 0xFF) / 255f
            lut[k + 2] = (lutU8[i].toInt() and 0xFF) / 255f
            k += 3
            i += 3
        }

        val start = sh[0].toDouble(); val end = sh[1].toDouble()
        val coordScale = sh[2].toDouble(); val valueScale = sh[3].toDouble()
        val kk = sh[4].toDouble(); val bb = sh[5].toDouble()
        val vv = sh[6].toDouble(); val tt0 = sh[7].toDouble()
        val span = if (end - start != 0.0) end - start else 1e-9

        val out = IntArray(src.size)
        val n1 = LUT_N - 1
        val wh = w.toDouble() / h.toDouble()
        var idx = 0
        for (y in 0 until h) {
            val uy = (y + 0.5) / h - 0.5
            val ny = uy - 0.5
            for (x in 0 until w) {
                // --- LUT（三线性）---
                val c = src[idx]
                var r = ((c shr 16) and 0xFF) / 255f
                var g = ((c shr 8) and 0xFF) / 255f
                var b = (c and 0xFF) / 255f
                val xr = r.coerceIn(0f, 1f) * n1
                val xg = g.coerceIn(0f, 1f) * n1
                val xb = b.coerceIn(0f, 1f) * n1
                val r0 = Math.min(xr.toInt(), LUT_N - 2)
                val g0 = Math.min(xg.toInt(), LUT_N - 2)
                val b0 = Math.min(xb.toInt(), LUT_N - 2)
                val fr = xr - r0; val fg = xg - g0; val fb = xb - b0
                var a0 = 0f; var a1 = 0f; var a2 = 0f
                for (dr in 0..1) {
                    val wr = if (dr == 1) fr else 1f - fr
                    for (dg in 0..1) {
                        val wg = if (dg == 1) fg else 1f - fg
                        for (db in 0..1) {
                            val wb = if (db == 1) fb else 1f - fb
                            val wgt = wr * wg * wb
                            val off = (((r0 + dr) * LUT_N + (g0 + dg)) * LUT_N + (b0 + db)) * 3
                            a0 += wgt * lut[off]
                            a1 += wgt * lut[off + 1]
                            a2 += wgt * lut[off + 2]
                        }
                    }
                }
                r = a0; g = a1; b = a2

                // --- CvStyle（亮度暗角）---
                val uvx = ((x + 0.5) / w - 0.5) * wh + 0.5
                val ux = uvx - 0.5
                var dist = sqrt(ux * ux + uy * uy)
                val nx = ux - 0.5
                var rnd = 0.0
                for (cc in doubleArrayOf(0.07, 0.11, 0.13, 0.17, 0.19, 0.23)) {
                    rnd += nrand(nx + cc * tt0, ny + cc * tt0)
                }
                rnd /= 6.0
                dist += 0.004 * rnd * 10.0 * coordScale
                val ss = ((dist - start) / span).coerceIn(0.0, 1.0)
                var vig = ss * ss * (3.0 - 2.0 * ss)
                val rd = r.toDouble(); val gd = g.toDouble(); val bd = b.toDouble()
                val l = 0.499 * rd + 0.387 * gd + 0.114 * bd
                val yy = 0.299 * rd + 0.587 * gd + 0.114 * bd
                val pf = vv / (1.0 + exp(kk * (-l + bb))) + tt0
                vig = ((1.0 - pf) * vig + pf) * valueScale
                val delta = yy * (vig - 1.0)

                val ro = ((r + delta) * 255f + 0.5f).toInt().coerceIn(0, 255)
                val go = ((g + delta) * 255f + 0.5f).toInt().coerceIn(0, 255)
                val bo = ((b + delta) * 255f + 0.5f).toInt().coerceIn(0, 255)
                out[idx] = (0xFF shl 24) or (ro shl 16) or (go shl 8) or bo
                idx++
            }
        }
        return out
    }

    private fun nrand(u: Double, v: Double): Double {
        val s = sin(u * 12.9898 + v * 78.233) * 43758.5453
        return s - Math.floor(s)
    }
}
