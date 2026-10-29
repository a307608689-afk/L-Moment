package com.leicamoments.app

/**
 * 全像素（≥45MP：50MP / 200MP）导出进度单例：Activity 写、前台服务轮询读。
 *
 * 不走 Intent 传状态：导出本身仍在 Activity 的线程池里跑，服务只负责
 *   1) 把进程优先级拉到前台（切后台不被冻结/杀）
 *   2) 持一把 PARTIAL_WAKE_LOCK（息屏后 CPU 不被挂起）
 *   3) 用通知栏展示进度与结论
 */
object ExportProgress {

    /** 是否有导出任务在跑（服务据此决定何时收尾退出） */
    @Volatile
    var running: Boolean = false
        private set

    /** 最近一次任务的结局：true=成功（决定完成通知的文案） */
    @Volatile
    var success: Boolean = false
        private set

    /** 0~100；条带进度未知时保持 [indeterminate] */
    @Volatile
    var percent: Int = 0
        private set

    @Volatile
    var indeterminate: Boolean = true
        private set

    /** 一行摘要（尺寸 + 用时），完成通知与弹窗共用 */
    @Volatile
    var summary: String = ""
        private set

    fun begin() {
        percent = 0
        indeterminate = true
        summary = ""
        success = false
        running = true
    }

    /** 条带回调：done/total 为行数或带数，由 ImageUtils 给出 */
    fun update(done: Int, total: Int) {
        if (total <= 0) return
        percent = (done.toLong() * 100 / total).toInt().coerceIn(0, 100)
        indeterminate = false
    }

    fun end(ok: Boolean, summaryText: String = "") {
        success = ok
        summary = summaryText
        running = false
    }
}
