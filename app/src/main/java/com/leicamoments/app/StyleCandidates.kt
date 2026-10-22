package com.leicamoments.app

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * M7.1 候选生成（L1，§10.2.3）：在逐图风格指纹 e_i 上做白化、k-medoids 与轮廓门限，
 * 挑出供用户挑选的候选。只依赖 emb_seeds.bin（n×d）。
 *
 * 性能：n×n 距离矩阵一次预计算（150²×36 ≈ 0.8M 次乘加，毫秒级），之后全部查表；
 * 真机 150 张端到端 <20 ms。与 PC 版（22_m7_candidates.py）同口径：白化距离、
 * medoid 而非 centroid、轮廓门限 0.25、上限 4 个候选（含候选 0）。
 * ΔE00 上下限裁剪需要留出场景渲染，端侧放在候选预览阶段用实际输出对比。
 */
object StyleCandidates {

    /**
     * 一个候选：index = 代表样本（medoid / 稳健中心）在 e 中的行号，
     * members = 该候选对应的簇成员行号（聚合 e_r 用；单候选时为全部样本）。
     */
    data class Pick(val index: Int, val members: IntArray)

    /** single=true 表示参考集一致，只出候选 0。 */
    data class Result(val single: Boolean, val k: Int, val sil: Float, val picks: List<Pick>)

    private const val SIL_GATE = 0.25f
    private const val DEFAULT_MAX_K = 3
    private const val MAX_PICKS = 4

    /** 列 z-score 白化；std<1e-3 的维度无信号，直接丢弃。返回 (z, 保留维数)。 */
    fun whiten(e: FloatArray, n: Int, d: Int): Pair<FloatArray, Int> {
        val mu = FloatArray(d)
        val sd = FloatArray(d)
        for (k in 0 until d) {
            var m = 0f
            for (i in 0 until n) m += e[i * d + k]
            m /= n
            var s2 = 0f
            for (i in 0 until n) {
                val v = e[i * d + k] - m
                s2 += v * v
            }
            sd[k] = sqrt(s2 / n)
            mu[k] = m
        }
        var nk = 0
        for (k in 0 until d) if (sd[k] > 1e-3f) nk++
        val z = FloatArray(n * nk)
        for (i in 0 until n) {
            var o = i * nk
            for (k in 0 until d) {
                if (sd[k] > 1e-3f) z[o++] = (e[i * d + k] - mu[k]) / sd[k]
            }
        }
        return z to nk
    }

    private fun distMatrix(z: FloatArray, n: Int, d: Int): FloatArray {
        val dm = FloatArray(n * n)
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                var s = 0f
                for (k in 0 until d) {
                    val v = z[i * d + k] - z[j * d + k]
                    s += v * v
                }
                val dd = sqrt(s)
                dm[i * n + j] = dd
                dm[j * n + i] = dd
            }
        }
        return dm
    }

    private fun assignTo(dm: FloatArray, n: Int, med: IntArray): IntArray {
        val a = IntArray(n)
        for (i in 0 until n) {
            var bi = 0
            var bd = Float.MAX_VALUE
            for (j in med.indices) {
                val dd = dm[i * n + med[j]]
                if (dd < bd) {
                    bd = dd
                    bi = j
                }
            }
            a[i] = bi
        }
        return a
    }

    /** k-medoids：最远点初始化 + PAM 式 assign/swap，最多 20 轮。 */
    fun medoids(dm: FloatArray, n: Int, k: Int, seed: Long = 7L): IntArray {
        val kk = min(k, n)
        val rng = Random(seed)
        val med = IntArray(kk)
        med[0] = rng.nextInt(n)
        for (m in 1 until kk) {
            var best = -1
            var bestD = -1f
            for (i in 0 until n) {
                var dmin = Float.MAX_VALUE
                for (j in 0 until m) dmin = min(dmin, dm[i * n + med[j]])
                if (dmin > bestD) {
                    bestD = dmin
                    best = i
                }
            }
            med[m] = best
        }
        val assign = IntArray(n)
        var changed = true
        var iter = 0
        while (changed && iter++ < 20) {
            changed = false
            for (i in 0 until n) {
                var bi = 0
                var bd = Float.MAX_VALUE
                for (j in 0 until kk) {
                    val dd = dm[i * n + med[j]]
                    if (dd < bd) {
                        bd = dd
                        bi = j
                    }
                }
                if (assign[i] != bi) {
                    assign[i] = bi
                    changed = true
                }
            }
            for (j in 0 until kk) {
                var bi = med[j]
                var bsum = Float.MAX_VALUE
                for (cand in 0 until n) {
                    var s = 0f
                    for (i in 0 until n) if (assign[i] == j) s += dm[cand * n + i]
                    if (s < bsum) {
                        bsum = s
                        bi = cand
                    }
                }
                if (bi != med[j]) {
                    med[j] = bi
                    changed = true
                }
            }
        }
        return med
    }

    /** 全体点的平均轮廓系数。 */
    fun silhouette(dm: FloatArray, n: Int, assign: IntArray): Float {
        val k = if (n == 0) 0 else assign.max()
        if (k < 1) return 0f
        val size = IntArray(k + 1)
        for (i in 0 until n) size[assign[i]]++
        var total = 0f
        var counted = 0
        for (i in 0 until n) {
            val ci = assign[i]
            val acc = FloatArray(k + 1)
            val cnt = IntArray(k + 1)
            for (j in 0 until n) {
                if (j == i) continue
                val cj = assign[j]
                acc[cj] += dm[i * n + j]
                cnt[cj]++
            }
            val a = if (size[ci] > 1) acc[ci] / cnt[ci] else 0f
            var b = Float.MAX_VALUE
            for (c in 0..k) if (c != ci && cnt[c] > 0) b = min(b, acc[c] / cnt[c])
            val m = max(a, b)
            if (m > 0f) {
                total += (b - a) / m
                counted++
            }
        }
        return if (counted > 0) total / counted else 0f
    }

    /** 稳健中心：距全集中位最近的样本（恒存在，抗离群）。 */
    fun robustCenter(e: FloatArray, n: Int, d: Int): Int {
        val med = FloatArray(d)
        for (k in 0 until d) {
            val col = FloatArray(n) { i -> e[i * d + k] }
            col.sort()
            med[k] = if (n % 2 == 1) col[n / 2] else 0.5f * (col[n / 2 - 1] + col[n / 2])
        }
        var best = 0
        var bestD = Float.MAX_VALUE
        for (i in 0 until n) {
            var s = 0f
            for (k in 0 until d) {
                val v = e[i * d + k] - med[k]
                s += v * v
            }
            if (s < bestD) {
                bestD = s
                best = i
            }
        }
        return best
    }

    /**
     * 候选 0 = 稳健中心；候选 1.. = 各簇 medoid（按与中心距离排序，去重，最多 4 个）。
     * 轮廓系数 <0.25 → 判参考集一致，返回单候选。e 按 row-major（n 行 d 列）。
     *
     * [prune]：可选的候选剔除回调（对应 PC 版 ΔE00 上限 12 的离群簇裁剪）。
     * 端侧在候选预览阶段用真实渲染 ΔE 实现；传 null 则不做上限裁剪。
     */
    fun candidates(
        e: FloatArray,
        n: Int,
        d: Int,
        maxK: Int = DEFAULT_MAX_K,
        prune: ((center: Int, cand: Int) -> Boolean)? = null
    ): Result {
        val all = IntArray(n) { it }
        if (n < 4) return Result(true, 1, 0f, listOf(Pick(robustCenter(e, n, d), all)))
        val (z, dd) = whiten(e, n, d)
        if (dd == 0) return Result(true, 1, 0f, listOf(Pick(robustCenter(e, n, d), all)))
        val dm = distMatrix(z, n, dd)

        var bestK = 1
        var bestSil = -1f
        var bestMed = IntArray(0)
        var bestAssign = IntArray(0)
        val km = min(maxK, n - 1)
        if (km >= 2) {
            for (k in 2..km) {
                val med = medoids(dm, n, k)
                val assign = assignTo(dm, n, med)
                val sil = silhouette(dm, n, assign)
                if (sil > bestSil) {
                    bestSil = sil
                    bestK = k
                    bestMed = med
                    bestAssign = assign
                }
            }
        }

        val center = robustCenter(e, n, d)
        if (bestSil < SIL_GATE || bestMed.isEmpty()) {
            return Result(true, 1, max(bestSil, 0f), listOf(Pick(center, all)))
        }

        // 簇标签 → 成员行号；medoid 的标签是它在 bestMed 里的位置
        fun membersOf(medPos: Int): IntArray {
            var cnt = 0
            for (a in bestAssign) if (a == medPos) cnt++
            val out = IntArray(cnt)
            var o = 0
            for (i in bestAssign.indices) if (bestAssign[i] == medPos) out[o++] = i
            return out
        }

        val picks = ArrayList<Pick>()
        picks.add(Pick(center, membersOf(bestAssign[center])))
        bestMed.sortedBy { i ->
            var s = 0f
            for (k in 0 until dd) {
                val v = e[i * d + k] - e[center * d + k]
                s += v * v
            }
            s
        }.forEach { med ->
            if (picks.size < MAX_PICKS && picks.none { it.index == med } && prune?.invoke(center, med) != true) {
                picks.add(Pick(med, membersOf(bestMed.indexOf(med))))
            }
        }
        return Result(false, bestK, bestSil, picks)
    }
}
