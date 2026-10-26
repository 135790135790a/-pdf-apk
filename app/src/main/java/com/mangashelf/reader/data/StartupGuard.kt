package com.mangashelf.reader.data

import android.content.Context
import android.os.Build
import java.io.File

/**
 * 启动守卫：连续几次"启动后没走到书架"就进入安全模式。
 *
 * 为什么需要它：这个 App 是盲发的，用户遇到启动即崩时没有任何自救手段——
 * 只能反复闪退、卸载重装，而卸载会连带删掉漫画文件。安全模式做的是"降级到最可能跑通的状态"：
 * 清掉图片缓存、书架不加载封面（省内存、避开解码路径），让用户至少能进来看一眼书架和诊断信息。
 *
 * 判定方式刻意做得简单可信：每次进程启动 +1，书架成功显示出书就归零。
 * 不猜"闪退原因"，只看结果——这样不会因为某次慢启动就误判。
 * 版本号变化时计数清零，避免上个版本的故障连累新版本。
 */
object StartupGuard {

    private const val PREFS = "startup_guard"
    private const val KEY_VERSION = "last_version"
    private const val KEY_PENDING = "pending_starts"
    private const val KEY_SAFE = "safe_mode"

    /** 连续失败多少次进安全模式 */
    private const val THRESHOLD = 3

    /** 本次进程是否处于安全模式（只在内存里，进程结束即失效） */
    @Volatile
    var safeMode: Boolean = false
        private set

    /**
     * 在 Application.onCreate 里调用，返回本次是否进入安全模式。
     */
    fun onAppStart(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val version = versionCode(context)
        val stored = prefs.getInt(KEY_VERSION, -1)

        if (stored != version) {
            // 刚安装/刚更新：计数重来，不继承上个版本的失败
            prefs.edit()
                .putInt(KEY_VERSION, version)
                .putInt(KEY_PENDING, 1)
                .putBoolean(KEY_SAFE, false)
                .apply()
            safeMode = false
            return false
        }

        val pending = prefs.getInt(KEY_PENDING, 0) + 1
        val safe = pending >= THRESHOLD
        prefs.edit()
            .putInt(KEY_PENDING, pending)
            .putBoolean(KEY_SAFE, safe)
            .apply()
        safeMode = safe
        if (safe) clearImageCache(context)
        return safe
    }

    /**
     * 书架成功显示出内容时调用：本轮启动算健康，计数归零。
     *
     * 安全模式在本次进程内保持（UI 已经按降级形态渲染了），下次启动自动恢复正常。
     */
    fun markHealthy(context: Context) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_PENDING, 0)
                .putBoolean(KEY_SAFE, false)
                .apply()
        }
    }

    fun pendingStarts(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_PENDING, 0)

    fun storedSafeFlag(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SAFE, false)

    /** Coil 2 的磁盘缓存目录是 cacheDir/image_cache，顺手把老版本的 coil 目录也清掉 */
    private fun clearImageCache(context: Context) {
        runCatching { File(context.cacheDir, "image_cache").deleteRecursively() }
        runCatching { File(context.cacheDir, "coil").deleteRecursively() }
    }

    private fun versionCode(context: Context): Int = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
    }.getOrDefault(-1)
}
