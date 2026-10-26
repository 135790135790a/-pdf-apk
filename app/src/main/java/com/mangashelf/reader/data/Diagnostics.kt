package com.mangashelf.reader.data

import android.app.ActivityManager
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import java.io.BufferedOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 诊断包导出。
 *
 * 用户拿不到 logcat，只能靠"把现场打包发回来"。包里**不含漫画图片本身**
 * （只统计数量和体积），避免几百 MB 的包传不出去；崩溃堆栈、偏好设置、
 * 数据库自检结果、目录清单都在里面，足够定位绝大多数问题。
 */
object Diagnostics {

    private const val MAX_CRASH_FILES = 20
    private const val MAX_REPORT_BYTES = 64 * 1024

    /**
     * 生成一个 zip，路径在应用私有目录 `files/diagnostics/`。
     * 返回 null 表示生成失败（磁盘满、权限异常等）。
     */
    fun export(context: Context): File? = runCatching {
        val dir = File(context.filesDir, "diagnostics").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val zip = File(dir, "漫画书架诊断-$stamp.zip")

        ZipOutputStream(BufferedOutputStream(zip.outputStream())).use { zos ->
            fun put(name: String, bytes: ByteArray) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }

            put("report.txt", buildReport(context).toByteArray(Charsets.UTF_8))

            crashFiles(context).take(MAX_CRASH_FILES).forEach { file ->
                runCatching { put("crash/" + file.name, file.readBytes()) }
            }

            prefsFiles(context).forEach { file ->
                runCatching { put("prefs/" + file.name, file.readBytes()) }
            }
        }
        zip
    }.getOrNull()

    /** 纯文本诊断报告，崩溃页上也能直接显示 */
    fun buildReport(context: Context): String = buildString {
        val app = context.applicationContext as? com.mangashelf.reader.MangaShelfApp

        append("漫画书架诊断报告\n")
        append("时间: ").append(time()).append('\n')
        append("版本: ").append(versionName(context)).append(" (versionCode ")
            .append(versionCode(context)).append(")\n")
        append("包名: ").append(context.packageName).append('\n')
        append("设备: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        append("系统: Android ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        append("ABI: ").append(Build.SUPPORTED_ABIS.joinToString()).append('\n')
        append("内存: ").append(memoryInfo(context)).append('\n')

        append("\n--- 启动守卫 ---\n")
        append("本次安全模式: ").append(if (app?.safeMode == true) "是" else "否").append('\n')
        append("连续未到书架的启动次数: ").append(StartupGuard.pendingStarts(context)).append('\n')
        append("上次记录的 safe 标记: ").append(StartupGuard.storedSafeFlag(context)).append('\n')

        append("\n--- 数据库 ---\n")
        append(databaseInfo(context))

        append("\n--- 存储 ---\n")
        append(dirInfo("files", context.filesDir))
        append(dirInfo("comics", File(context.filesDir, "comics")))
        append(dirInfo("caches", context.cacheDir))
        append(dirInfo("crash", crashDir(context)))

        append("\n--- 最近崩溃 ---\n")
        val crashes = crashFiles(context)
        if (crashes.isEmpty()) {
            append("（无）\n")
        } else {
            crashes.take(3).forEach { file ->
                append("\n>> ").append(file.name).append('\n')
                val text = runCatching { file.readText() }.getOrDefault("（读取失败）")
                append(text.take(4000))
                if (text.length > 4000) append("\n…（已截断）")
                append('\n')
            }
        }
    }

    private fun databaseInfo(context: Context): String = buildString {
        val dbFile = context.getDatabasePath(AppDatabase.DB_NAME)
        if (!dbFile.exists()) {
            append("数据库文件不存在（首次启动或已被重置）\n")
            return@buildString
        }
        append("文件: ").append(dbFile.name).append("  ")
            .append(dbFile.length()).append(" 字节\n")
        runCatching {
            // 只读打开，不做迁移；打不开就说明库真的坏了，这本身就是重要信息
            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                append("books: ").append(count(db, "books")).append(" 行\n")
                append("pages: ").append(count(db, "pages")).append(" 行\n")
                append("user_version: ").append(db.version).append('\n')
                append("integrity_check: ")
                    .append(runCatching {
                        db.rawQuery("PRAGMA integrity_check", null).use { c ->
                            if (c.moveToFirst()) c.getString(0) else "?"
                        }
                    }.getOrDefault("查询失败"))
                    .append('\n')
            }
        }.onFailure {
            append("打开数据库失败: ").append(it.javaClass.simpleName)
                .append(" - ").append(it.message).append('\n')
        }
    }

    private fun count(db: SQLiteDatabase, table: String): String = runCatching {
        db.rawQuery("SELECT COUNT(*) FROM $table", null).use { c ->
            if (c.moveToFirst()) c.getLong(0).toString() else "?"
        }
    }.getOrDefault("表不存在")

    private fun dirInfo(label: String, dir: File): String {
        if (!dir.exists()) return "$label: 不存在\n"
        val files = dir.listFiles().orEmpty()
        val total = files.sumOf { if (it.isFile) it.length() else 0L }
        val sub = files.count { it.isDirectory }
        return "$label: ${files.size} 项（$sub 个子目录），直接文件合计 $total 字节\n"
    }

    private fun memoryInfo(context: Context): String = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        "可用 ${info.availMem / 1024 / 1024} MB / 共 ${info.totalMem / 1024 / 1024} MB"
    }.getOrDefault("?")

    private fun crashDir(context: Context): File =
        context.getExternalFilesDir("crash") ?: File(context.filesDir, "crash")

    private fun crashFiles(context: Context): List<File> =
        crashDir(context).listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    private fun prefsFiles(context: Context): List<File> {
        val dir = File(context.applicationInfo.dataDir, "shared_prefs")
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".xml") }?.toList().orEmpty()
    }

    private fun versionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    private fun versionCode(context: Context): Int = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
    }.getOrDefault(-1)

    private fun time(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}
