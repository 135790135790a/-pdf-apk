package com.mangashelf.reader.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.mangashelf.reader.MainActivity

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

        /**
         * 当前活着的实例。
         *
         * 只用于更新通知文案：导入在协程里推进，但**不能**为了"更新一行字"再调
         * startService——服务没在跑时那样做会拉起一个没走 startForeground 的服务，
         * 5 秒后就是 ANR/崩溃。这里宁可什么都不做，也不要误启动。
         */
        @Volatile
        private var instance: ImportForegroundService? = null

        /** 只在 Activity 前台可见时调用（Android 12+ 禁止从后台启动前台服务）。 */
        fun start(context: Context, text: String) {
            val intent = Intent(context, ImportForegroundService::class.java)
                .putExtra(EXTRA_TEXT, text)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    // Android 7.x 没有 startForegroundService，用普通 startService；
                    // 7.x 还没有后台执行限制，进程保命效果一样。
                    context.startService(intent)
                }
            }
        }

        /** 刷新通知文案；服务没在跑就忽略（导入结束、或用户收回了通知权限） */
        fun update(text: String) {
            runCatching { instance?.refresh(text) }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, ImportForegroundService::class.java))
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** 把最新进度写到通知上 */
    private fun refresh(text: String) {
        runCatching { startForegroundCompat(buildNotification(text)) }
    }

    private fun startForegroundCompat(notification: Notification): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        true
    }.getOrDefault(false)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT).orEmpty().ifBlank { "正在导入漫画…" }
        ensureChannel()
        instance = this
        val started = startForegroundCompat(buildNotification(text))

        if (!started) {
            // 前台服务起不来（权限被收回、系统限制）时自己退出。
            // 这时导入照旧能跑，只是没有保命——绝不能让保命机制本身把 App 带崩。
            instance = null
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

    private fun buildNotification(text: String): Notification {
        // 点通知回到书架：导入结果就显示在书架上，不需要单独的结果页
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("漫画书架")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
