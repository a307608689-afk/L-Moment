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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlin.concurrent.thread

/**
 * 画质任务前台服务：提升进程优先级保证后台不被打断；实时进度通知。
 * 任务本体在 SuperResTask 单例执行，服务只负责锚定生命周期与通知更新。
 * 两种模式共用本服务：
 *   - 超分（pendingPerfect=false）：光学/快速超分 ×2，完成后自动导出
 *   - 完美（pendingPerfect=true）：OffLineM9 后段全量，完成后不自动导出
 */
class SuperResService : Service() {

    companion object {
        private const val CHANNEL_ID = "super_res"
        private const val CHANNEL_ID_PERFECT = "m9_perfect"
        private const val FG_ID = 2001
        private const val DONE_ID = 2002
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val perfect = SuperResTask.pendingPerfect
        createChannel(perfect)
        startForegroundWith(buildProgressNotification(0, perfect))

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

    private fun startForegroundWith(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(FG_ID, n)
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

    private fun notify(n: Notification, id: Int) {
        try {
            getSystemService(NotificationManager::class.java).notify(id, n)
        } catch (_: Exception) {}
    }
}
