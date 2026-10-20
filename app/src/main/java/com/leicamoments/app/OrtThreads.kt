package com.leicamoments.app

/**
 * ORT 会话线程口径的单一来源（B1 分发保障）。
 *
 * 历史上四处（M9Distill / M9OrtFallback / M9Perfect / Lm9Net）各自硬写 8 线程。
 * 8 线程只在核心数足够时才有意义，4 核机上属于超订：线程池互相抢占、上下文切换变多，
 * 实测常比 4 线程更慢。而这些链全部跑在「时间敏感」的降级路径上——用户感知到的
 * 就是 8s 还是 25s，所以这里统一收敛到 min(4, cores)。
 *
 * 4 这个上限与超分链的实测最优档一致（INT8 + 4 线程），本工程的模型规模再往上没有收益。
 */
internal object OrtThreads {

    /** IntraOp 线程上限。 */
    const val MAX = 4

    /** 实际使用的 IntraOp 线程数（至少 1）。 */
    fun pick(): Int {
        val cores = try {
            Runtime.getRuntime().availableProcessors()
        } catch (_: Throwable) {
            2
        }
        return minOf(MAX, cores.coerceAtLeast(1))
    }
}
