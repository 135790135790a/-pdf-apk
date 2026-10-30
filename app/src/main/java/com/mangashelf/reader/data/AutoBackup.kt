package com.mangashelf.reader.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 每日自动备份（v1.5.7 的数据安全项）。
 *
 * 只备份数据库，不备份图片——图片动辄几个 G，复制一遍既慢又可能把存储写满。
 * 页图丢了还能重新导入，书单/进度/标签/导入历史丢了才是真的麻烦。
 *
 * 位置：`files/backup/auto-yyyyMMdd-HHmm.db`，只留最近 [KEEP] 份。
 * 卸载 App 会连同备份一起删掉——这是应用私有目录的代价，
 * 要跨卸载保留得靠用户自己导出（备份书架功能）。
 */
object AutoBackup {

    private const val KEEP = 3
    private const val INTERVAL_MS = 24L * 60 * 60 * 1000
    private const val PREF = "auto_backup"
    private const val KEY_LAST = "last_at"
    private const val MAX_BYTES = 50L * 1024 * 1024

    private fun dir(context: Context): File =
        File(context.filesDir, "backup").apply { if (!exists()) mkdirs() }

    fun list(context: Context): List<File> =
        dir(context).listFiles { f -> f.isFile && f.name.endsWith(".db") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun lastAt(context: Context): Long = context.applicationContext
        .getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .getLong(KEY_LAST, 0L)

    /**
     * App 启动时调用。距上次不到 24 小时就直接返回，什么都不做。
     *
     * 全程 runCatching：备份失败不能影响启动，这是底线。
     */
    suspend fun maybeRun(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastAt(context) < INTERVAL_MS) return
        withContext(Dispatchers.IO) {
            runCatching { backupNow(context) }
        }
    }

    suspend fun backupNow(context: Context): File? = withContext(Dispatchers.IO) {
        runCatching {
            val db = context.getDatabasePath(AppDatabase.DB_NAME)
            if (!db.exists()) return@runCatching null
            if (db.length() > MAX_BYTES) return@runCatching null

            val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
            val target = File(dir(context), "auto-$stamp.db")
            db.copyTo(target, overwrite = true)

            // 顺手把 -wal/-shm 也带上：只拷主库文件，最近的写入可能还在 wal 里
            File(db.path + "-wal").takeIf { it.exists() }?.copyTo(File(target.path + "-wal"), overwrite = true)
            File(db.path + "-shm").takeIf { it.exists() }?.copyTo(File(target.path + "-shm"), overwrite = true)

            context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()

            prune(context)
            target
        }.getOrNull()
    }

    private fun prune(context: Context) {
        list(context).drop(KEEP).forEach { runCatching { it.delete() } }
    }

    /**
     * 用备份覆盖当前数据库。
     *
     * 调用前必须保证没人再持有库连接——所以这里先 close + 置空单例，
     * 调用方之后应该重建仓库（[MangaShelfApp.resetRepository] 那种做法）。
     */
    suspend fun restore(context: Context, backup: File): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            if (!backup.exists()) return@runCatching false
            AppDatabase.reset()
            val db = context.getDatabasePath(AppDatabase.DB_NAME)
            db.parentFile?.mkdirs()
            backup.copyTo(db, overwrite = true)
            File(db.path + "-wal").takeIf { it.exists() }?.delete()
            File(db.path + "-shm").takeIf { it.exists() }?.delete()
            File(backup.path + "-wal").takeIf { it.exists() }?.let {
                it.copyTo(File(db.path + "-wal"), overwrite = true)
            }
            AppDatabase.reset()
            true
        }.getOrDefault(false)
    }
}
