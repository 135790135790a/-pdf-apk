package com.mangashelf.reader

import android.app.Application
import android.content.Intent
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.os.Build
import android.os.Process
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache
import com.mangashelf.reader.data.AppDatabase
import com.mangashelf.reader.data.BookRepository
import com.mangashelf.reader.data.ComicStorage
import com.mangashelf.reader.data.StartupGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

class MangaShelfApp : Application(), ImageLoaderFactory {

    @Volatile
    private var repositoryRef: BookRepository? = null

    /**
     * 书架仓库。数据库损坏重建后会把这里置空，下一次取用自动连到新库，
     * 所以不要把这个引用长期缓存到别处。
     */
    val repository: BookRepository
        get() = repositoryRef ?: synchronized(this) {
            repositoryRef ?: createRepository().also { repositoryRef = it }
        }

    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 本次进程是否处于安全模式（连续启动失败后的降级形态，见 [StartupGuard]） */
    val safeMode: Boolean
        get() = StartupGuard.safeMode

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
        StartupGuard.onAppStart(this)
        // 两件事必须串行：先确认数据库可用，再决定要不要清残留目录。
        // 如果数据库损坏刚被重建，books 是空的，这时候清残留会把所有漫画目录删光。
        background.launch {
            val repaired = healDatabaseIfCorrupt()
            if (!repaired) cleanupOrphanFolders()
            runCatching { repository.pruneTasks() }
        }
    }

    /**
     * 启动时清掉"写了一半"的残留目录。
     *
     * 只在启动这一刻安全：那时不可能有导入在跑。导入中途被系统杀掉的目录，
     * 既没有对应的 books 行、也没有完整页，留着只会白占空间。
     * 失败不处理——目录删不掉不至于让 App 起不来。
     */
    private suspend fun cleanupOrphanFolders() {
        runCatching { repository.cleanupOrphanFolders() }
    }

    /** 书架成功显示出书时调用，表示这次启动是健康的 */
    fun markShelfHealthy() {
        StartupGuard.markHealthy(this)
    }

    private fun createRepository(): BookRepository {
        val database = AppDatabase.get(this)
        return BookRepository(
            dao = database.bookDao(),
            pageDao = database.pageDao(),
            taskDao = database.importTaskDao(),
            storage = ComicStorage(this)
        )
    }

    /**
     * 全局图片加载器。
     *
     * 漫画动辄几百页、单页 1800px，内存缓存按百分比上限收，避免看书时缓存无限涨；
     * crossfade 让翻页时不会突然从纯黑跳到图。
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .crossfade(true)
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(0.2)
                .build()
        }
        .build()

    /**
     * 启动时在后台把数据库真正打开一次。
     *
     * Room 的 build() 是懒的，文件损坏要等第一次查询才炸——那时候已经在书架页上，
     * 不处理就是每次打开 App 都崩。这里提前打开，识别出「损坏」就删库重建：
     * 漫画图片文件还在，只是书架需要重新导入，总比永远打不开好。
     *
     * 注意只对「损坏」下手；迁移失败之类的逻辑错误保留原库，交给崩溃页显示原因。
     */
    private suspend fun healDatabaseIfCorrupt(): Boolean {
        val database = runCatching { AppDatabase.get(this) }.getOrNull() ?: return false
        val failure = runCatching { database.openHelper.writableDatabase }.exceptionOrNull()
            ?: return false
        if (!failure.looksCorrupt()) return false

        runCatching { AppDatabase.reset() }
        repositoryRef = null
        runCatching { deleteDatabase(AppDatabase.DB_NAME) }
        runCatching {
            val dir = getExternalFilesDir("crash") ?: File(filesDir, "crash")
            if (!dir.exists()) dir.mkdirs()
            File(dir, "database-reset.txt").writeText(
                buildString {
                    append("书架数据库损坏，已自动重置\n")
                    append("时间: ")
                    append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                    append("\n\n")
                    append(failure.stackTraceToString())
                }
            )
        }
        return true
    }

    private fun Throwable.looksCorrupt(): Boolean {
        var current: Throwable? = this
        var depth = 0
        while (current != null && depth < 6) {
            if (current is SQLiteDatabaseCorruptException) return true
            val message = current.message.orEmpty()
            if (message.contains("corrupt", ignoreCase = true) ||
                message.contains("not a database", ignoreCase = true)
            ) {
                return true
            }
            current = current.cause
            depth++
        }
        return false
    }

    /**
     * 崩溃兜底。
     *
     * 这个 App 是"盲发"的（开发者没有真机），用户拿不到 logcat，
     * 所以崩溃时做两件事：
     *   1. 堆栈原文写到 Android/data/com.mangashelf.reader/files/crash/
     *   2. 弹一个纯原生 View 的页面，把堆栈显示在屏幕上，可选中、可一键复制
     *
     * 注意：这里**不调用系统默认处理器、也不立刻杀进程**，否则刚拉起来的
     * 崩溃页会跟着一起消失。页面上的"退出"按钮才结束进程。
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            var shown = false
            try {
                val stack = StringWriter().let { sw ->
                    error.printStackTrace(PrintWriter(sw))
                    sw.toString()
                }
                val report = buildString {
                    append("漫画书架崩溃报告\n")
                    append("时间: ")
                    append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                    append("\n设备: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                    append("\n系统: Android ").append(Build.VERSION.RELEASE)
                    append(" (API ").append(Build.VERSION.SDK_INT)
                    append(")\n版本: ").append(appVersionName())
                    append("\n\n").append(stack)
                }

                runCatching {
                    val dir = getExternalFilesDir("crash") ?: File(filesDir, "crash")
                    if (!dir.exists()) dir.mkdirs()
                    File(dir, "crash-${System.currentTimeMillis()}.txt").writeText(report)
                }

                runCatching {
                    startActivity(
                        Intent(this, CrashReportActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            .putExtra(CrashReportActivity.EXTRA_TEXT, report)
                    )
                    shown = true
                }
            } catch (ignored: Throwable) {
                // 兜底逻辑自己出错，不能影响原始崩溃的处理
            }

            if (!shown) {
                // 连崩溃页都拉不起来，就走系统默认流程
                if (previous != null) {
                    previous.uncaughtException(thread, error)
                } else {
                    Process.killProcess(Process.myPid())
                    exitProcess(10)
                }
            }
        }
    }

    private fun appVersionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    }.getOrDefault("?")
}
