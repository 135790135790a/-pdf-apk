package com.mangashelf.reader.importer

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * 多选文件导入：按显示文件名排序后逐个规范化落盘。
 *
 * 按文件名排序是漫画图片序列的通行约定（001.jpg、002.jpg …），
 * 比按 SAF 返回的随机顺序更接近用户预期。
 */
object ImageImporter {

    suspend fun import(
        context: Context,
        uris: List<Uri>,
        destDir: File,
        onProgress: (done: Int, total: Int) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        destDir.mkdirs()

        val ordered = uris.sortedBy { uri ->
            FileNames.displayName(context, uri)?.lowercase(Locale.ROOT).orEmpty()
        }

        var saved = 0
        ordered.forEachIndexed { index, uri ->
            val bitmap = runCatching { ImageCodec.load(context, uri) }.getOrNull()
            if (bitmap != null) {
                try {
                    ImageCodec.write(bitmap, File(destDir, PageFileNaming.of(saved)))
                    saved++
                } finally {
                    bitmap.recycle()
                }
            }
            onProgress(index + 1, ordered.size)
        }
        saved
    }
}
