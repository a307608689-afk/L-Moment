package com.leicamoments.app

/**
 * 模型参考图池的容量与淘汰规则（M4）。
 *
 * 池仍是「只增不删、版本记前缀张数」的老语义，所以淘汰**必须同时重排位置**：
 * 缩略图、排除标记、以及每个版本记的 `seedCount` 都要跟着改，
 * 否则旧版本回退/重烘会用错图。具体由 [ModelDetailActivity] 在一次提交里完成。
 */
internal object ModelPool {

    /** 跨批累计上限 */
    const val MAX_TOTAL = 120

    /** 单次添加上限 */
    const val MAX_BATCH = 20

    /** 断点续跑日志最多留多少张（够两轮 20 张） */
    const val MAX_PENDING = 40

    /** 判「同一张图」的容差：64 位里差 ≤2 位（重编码/缩放会有抖动） */
    private const val DUP_DISTANCE = 2

    /** 未知哈希（M4 之前的老参考）的淘汰分：先于「确实与众不同」的，后于「明确重复」的 */
    private const val UNKNOWN_SCORE = DUP_DISTANCE + 1

    fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    /** [fp] 是否与 [others] 里任一张重复；未知哈希（0）永远不算重复 */
    fun isDuplicate(fp: Long, others: Collection<Long>): Boolean {
        if (fp == 0L) return false
        for (o in others) {
            if (o == 0L) continue
            if (hamming(fp, o) <= DUP_DISTANCE) return true
        }
        return false
    }

    /**
     * 淘汰方案：从 [seeds] 里挑 [need] 张「最冗余」的，返回下标（升序）。
     *
     * 判据 = 与池内其它参考图的最小哈希距离，越小说明越像别人、信息量越低，先淘汰。
     * 旧数据没有哈希时按 [UNKNOWN_SCORE] 计分（排在明确重复之后、与众不同之前），
     * 同分按加入顺序（FIFO）先走，行为可预期；
     * 单张不参与淘汰——宁可拒绝新增，也不要只剩 0 张参考的模型。
     */
    fun evictPlan(seeds: List<RefSeed>, need: Int): List<Int> {
        if (need <= 0 || seeds.size <= 1) return emptyList()
        val alive = seeds.indices.toMutableList()
        val victims = ArrayList<Int>()
        repeat(need.coerceAtMost(seeds.size - 1)) {
            var best = -1
            var bestScore = Int.MAX_VALUE
            for (i in alive) {
                val fp = seeds[i].fingerprint
                if (fp == 0L) {
                    // 未知哈希：没有相似度证据，按兜底分参与比较（FIFO）
                    if (bestScore > UNKNOWN_SCORE) {
                        bestScore = UNKNOWN_SCORE
                        best = i
                    }
                    continue
                }
                var minD = 65
                for (j in alive) {
                    if (j == i) continue
                    val o = seeds[j].fingerprint
                    if (o == 0L) continue
                    val d = hamming(fp, o)
                    if (d < minD) minD = d
                }
                if (minD < bestScore) {
                    bestScore = minD
                    best = i
                }
            }
            if (best < 0) return victims
            victims.add(best)
            alive.remove(best)
        }
        victims.sort()
        return victims
    }
}
