package com.leicamoments.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlin.concurrent.thread

/**
 * 画质任务前台服务：提升进程优先级保证后台不被打断；实时进度通知。
 * 任务本体在各自单例执行，服务只负责锚定生命周期与通知更新。
 * 三种模式共用本服务：
 *   - 超分（pendingPerfect=false）：光学/快速超分 ×2，完成后自动导出
 *   - 完美（pendingPerfect=true）：OffLineM9 后段全量，完成后不自动导出
 *   - 全像素导出（[ACTION_EXPORT]）：200MP 条带导出，任务在 MainActivity 线程池，
 *     服务额外持 PARTIAL_WAKE_LOCK，息屏/切后台后 CPU 不被挂起
 */
class SuperResService : Service() {

    companion object {
        private const val CHANNEL_ID = "super_res"
        private const val CHANNEL_ID_PERFECT = "m9_perfect"
        private const val CHANNEL_ID_EXPORT = "full_export"
        private const val FG_ID = 2001
        private const val DONE_ID = 2002
        private const val FG_ID_EXPORT = 2011
        private const val DONE_ID_EXPORT = 2012

        /** 全像素导出模式：只需要前台优先级 + 进度通知 + 唤醒锁 */
        const val ACTION_EXPORT = "com.leicamoments.app.action.FULL_EXPORT"
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_EXPORT) return startExportMode()

        val perfect = SuperResTask.pendingPerfect
        createChannel(perfect)
        startForegroundWith(buildProgressNotification(0, perfect), FG_ID)

        if (SuperResTask.pendingInput == null || SuperResTask.state is SuperResTask.State.Running) {
            // startForeground 之后才能 stopSelf，否则触发系统 ForegroundServiceDidNotStartInTime 闪退
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        SuperResTask.start(applicationContext)

        var lastProgress = -1
        var lastUpdateMs = 0L
        thread(name = "sr-watch") {
            while (true) {
                when (val s = SuperResTask.state) {
                    is SuperResTask.State.Running -> {
                        val now = System.currentTimeMillis()
                        if (s.progress != lastProgress && now - lastUpdateMs >= 500) {
                            lastProgress = s.progress
                            lastUpdateMs = now
                            notify(buildProgressNotification(s.progress, perfect), FG_ID)
                        }
                        Thread.sleep(300)
                    }
                    is SuperResTask.State.Done -> {
                        notify(buildDoneNotification(s.width, s.height, perfect), DONE_ID)
                        ServiceCompat.stopForeground(this@SuperResService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        return@thread
                    }
                    is SuperResTask.State.Failed -> {
                        ServiceCompat.stopForeground(this@SuperResService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        return@thread
                    }
                    SuperResTask.State.Idle -> {
                        stopSelf()
                        return@thread
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    // ---- 全像素导出模式 ----

    private fun startExportMode(): Int {
        createExportChannel()
        startForegroundWith(buildExportNotification(), FG_ID_EXPORT)
        acquireWakeLock()

        if (!ExportProgress.running) {
            // 极快收尾 / 任务已被取消：startForeground 后立即退出
            finishExport()
            return START_NOT_STICKY
        }

        thread(name = "full-export-watch") {
            var last = Int.MIN_VALUE
            while (ExportProgress.running) {
                val p = if (ExportProgress.indeterminate) -1 else ExportProgress.percent
                if (p != last) {
                    notify(buildExportNotification(), FG_ID_EXPORT)
                    last = p
                }
                Thread.sleep(400)
            }
            notify(buildExportDoneNotification(), DONE_ID_EXPORT)
            finishExport()
        }
        return START_NOT_STICKY
    }

    private fun finishExport() {
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * 部分唤醒锁：前台服务只保进程优先级，不保 CPU；屏幕熄灭后设备可能进入挂起，
     * 条带编码会被拉长到不可接受的时长。上限 30 分钟（200MP 实测约 4 分钟）。
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(PowerManager::class.java) ?: return
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "L-Moment:fullResExport")
            wl.setReferenceCounted(false)
            wl.acquire(30 * 60 * 1000L)
            wakeLock = wl
        } catch (_: Throwable) {
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Throwable) {
        }
        wakeLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun startForegroundWith(n: Notification, id: Int) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(id, n)
        }
    }

    private fun createChannel(perfect: Boolean) {
        if (Build.VERSION.SDK_INT >= 26) {
            val id = if (perfect) CHANNEL_ID_PERFECT else CHANNEL_ID
            val name = if (perfect) "完美处理" else "超分辨率放大"
            val ch = NotificationChannel(id, name, NotificationManager.IMPORTANCE_DEFAULT)
            ch.description = if (perfect) "完美档处理进度" else "超分处理进度"
            ch.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    /** 导出通道用 IMPORTANCE_LOW：长任务的进度刷新不该反复打扰（不响铃、不弹横幅） */
    private fun createExportChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                CHANNEL_ID_EXPORT, "全像素导出", NotificationManager.IMPORTANCE_LOW
            )
            ch.description = "200MP 全像素导出的进度"
            ch.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun contentIntent(): PendingIntent =
        PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun buildProgressNotification(progress: Int, perfect: Boolean): Notification =
        NotificationCompat.Builder(this, if (perfect) CHANNEL_ID_PERFECT else CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (perfect) "完美处理" else "超分辨率放大")
            .setContentText("处理中 $progress%")
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent())
            .build()

    private fun buildDoneNotification(w: Int, h: Int, perfect: Boolean): Notification =
        NotificationCompat.Builder(this, if (perfect) CHANNEL_ID_PERFECT else CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(if (perfect) "完美处理完成" else "超分完成")
            .setContentText(
                if (perfect) "已生成完美成片，请手动导出保存"
                else "已放大至 ${w}×${h}，点击查看"
            )
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .build()

    private fun buildExportNotification(): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID_EXPORT)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("全像素导出")
            .setContentText(
                if (ExportProgress.indeterminate) "逐条带处理中…"
                else "逐条带处理中 ${ExportProgress.percent}%"
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent())
        if (ExportProgress.indeterminate) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(100, ExportProgress.percent, false)
        }
        return builder.build()
    }

    private fun buildExportDoneNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID_EXPORT)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(if (ExportProgress.success) "全像素导出完成" else "全像素导出已结束")
            .setContentText(
                ExportProgress.summary.ifEmpty {
                    if (ExportProgress.success) "已保存到相册" else "本次未生成文件（详情见应用日志）"
                }
            )
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .build()

    private fun notify(n: Notification, id: Int) {
        try {
            getSystemService(NotificationManager::class.java).notify(id, n)
        } catch (_: Exception) {}
    }
}
