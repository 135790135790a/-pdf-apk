package com.mangashelf.reader

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Process
import com.mangashelf.reader.data.AppDatabase
import com.mangashelf.reader.data.BookRepository
import com.mangashelf.reader.data.ComicStorage
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

class MangaShelfApp : Application() {

    val repository: BookRepository by lazy {
        val database = AppDatabase.get(this)
        BookRepository(
            dao = database.bookDao(),
            pageDao = database.pageDao(),
            storage = ComicStorage(this)
        )
    }

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
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
