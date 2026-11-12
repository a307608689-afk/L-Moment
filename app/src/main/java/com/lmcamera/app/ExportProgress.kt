package com.lmcamera.app

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

    /** 通知标题：单张「全像素导出」/ 批量「批量导出」 */
    @Volatile
    var title: String = "全像素导出"
        private set

    /** 当前阶段一行（批量：「第 3/12 张 · L-M9」）；空 = 走条带默认文案 */
    @Volatile
    var stage: String = ""
        private set

    /** 长任务（批量）：服务放宽唤醒锁时长，避免锁屏后 CPU 挂起 */
    @Volatile
    var longTask: Boolean = false
        private set

    // 当前这张在整体进度里占的区间：条带的 0~100% 映射进这一段，批量百分比才连续
    @Volatile
    private var bandBase = 0
    @Volatile
    private var bandSpan = 100

    fun begin(title: String = "全像素导出", stage: String = "", longTask: Boolean = false) {
        this.title = title
        this.stage = stage
        this.longTask = longTask
        percent = 0
        indeterminate = stage.isEmpty()
        summary = ""
        success = false
        bandBase = 0
        bandSpan = 100
        running = true
    }

    /**
     * 批量：进入第 [index] 张（0 基，共 [total] 张）。
     *
     * 每张均分一段整体进度；该张内部的条带进度通过 [update] 落进这一段，
     * 所以通知栏百分比是"整批"的，不会每张从 0 重来。
     */
    fun batchItem(index: Int, total: Int, label: String) {
        if (total <= 0) return
        bandBase = index * 100 / total
        bandSpan = (index + 1) * 100 / total - bandBase
        percent = bandBase
        indeterminate = false
        stage = if (total > 1) "第 ${index + 1}/$total 张 · $label" else label
    }

    /** 只改阶段文案（不前进百分比），例如「正在编码」「已保存」 */
    fun setStage(text: String) {
        stage = text
    }

    /** 条带回调：done/total 为行数或带数；映射进当前张的区间 */
    fun update(done: Int, total: Int) {
        if (total <= 0) return
        val f = (done.toLong() * 100 / total).toInt().coerceIn(0, 100)
        percent = (bandBase + f * bandSpan / 100).coerceIn(0, 100)
        indeterminate = false
    }

    fun end(ok: Boolean, summaryText: String = "") {
        success = ok
        summary = summaryText
        if (ok) percent = 100
        running = false
    }
}
