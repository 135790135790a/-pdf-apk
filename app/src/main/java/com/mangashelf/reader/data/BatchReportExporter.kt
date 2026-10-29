package com.mangashelf.reader.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.5.6：把导入历史导成 CSV。
 *
 * 为什么用 CSV 不用 PDF：用户要的是「能拿去核对/记账」的东西，Excel/WPS 都能直接打开，
 * 手机上也一点就看。生成 PDF 要引第三方库，为一个导出功能不值当。
 */
object BatchReportExporter {

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /**
     * 导出成功返回「保存位置」的可读描述，失败返回 null。
     *
     * Android 10 及以上直接进系统「下载」目录（MediaStore，不需要任何权限）；
     * Android 7~9 写进应用专属外部目录（也不需要权限），路径是可见的。
     */
    suspend fun export(context: Context, batches: List<ImportBatch>): String? = withContext(Dispatchers.IO) {
        if (batches.isEmpty()) return@withContext null

        val csv = buildString {
            append("批次ID,来源,状态,书册数,总页数,已完成书,已完成页,失败,跳过,创建时间,结束时间\n")
            batches.forEach { b ->
                append(csvField(b.batchId)).append(',')
                append(csvField(b.sourceType)).append(',')
                append(csvField(statusLabel(b.status))).append(',')
                append(b.totalBooks).append(',')
                append(b.totalPages).append(',')
                append(b.doneBooks).append(',')
                append(b.donePages).append(',')
                append(b.failedBooks).append(',')
                append(b.skippedBooks).append(',')
                append(if (b.createdAt > 0) stamp.format(Date(b.createdAt)) else "").append(',')
                append(if (b.finishedAt > 0) stamp.format(Date(b.finishedAt)) else "").append('\n')
            }
        }
        val bytes = csv.toByteArray(Charsets.UTF_8)
        val name = "漫画书架-导入报告-${System.currentTimeMillis()}.csv"

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return@withContext null
                resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return@withContext null
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                "下载/$name"
            } else {
                val dir = context.getExternalFilesDir(null) ?: context.filesDir
                val file = File(dir, name)
                file.writeBytes(bytes)
                file.absolutePath
            }
        }.getOrNull()
    }

    private fun statusLabel(status: String): String = when (status) {
        ImportBatch.QUEUED -> "排队中"
        ImportBatch.RUNNING -> "导入中"
        ImportBatch.PAUSED -> "已暂停"
        ImportBatch.DONE -> "已完成"
        ImportBatch.PARTIAL -> "部分失败"
        ImportBatch.FAILED -> "失败"
        ImportBatch.CANCELLED -> "已取消"
        else -> status
    }

    /** CSV 里出现逗号/引号/换行会把整行搞乱，统一用双引号包起来并转义内部引号。 */
    private fun csvField(raw: String): String {
        val escaped = raw.replace("\"", "\"\"")
        return "\"$escaped\""
    }
}
