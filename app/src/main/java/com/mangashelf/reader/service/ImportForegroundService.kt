package com.mangashelf.reader.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * 导入期间的前台服务。
 *
 * 唯一的职责是告诉系统「这个进程正在做用户看得见的事」，
 * 这样用户切去微信、锁屏、或者系统内存吃紧时，导入不会被当成后台进程收掉。
 *
 * 它自己不干活：真正的导入仍然跑在 ShelfViewModel 的协程里，这个服务只负责保命。
 */
class ImportForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "import_progress"
        private const val NOTIFICATION_ID = 2001
        private const val EXTRA_TEXT = "text"

        /** 只在 Activity 前台可见时调用（Android 12+ 禁止从后台启动前台服务）。 */
        fun start(context: Context, text: String) {
            val intent = Intent(context, ImportForegroundService::class.java)
                .putExtra(EXTRA_TEXT, text)
            runCatching { context.startForegroundService(intent) }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, ImportForegroundService::class.java))
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT).orEmpty().ifBlank { "正在导入漫画…" }
        ensureChannel()
        val notification = buildNotification(text)
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.isSuccess

        if (!started) {
            // 前台服务起不来（权限被收回、系统限制）时自己退出。
            // 这时导入照旧能跑，只是没有保命——绝不能让保命机制本身把 App 带崩。
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "导入进度",
            NotificationManager.IMPORTANCE_LOW
        )
        channel.setShowBadge(false)
        channel.enableVibration(false)
        channel.setSound(null, null)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("漫画书架")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
}
