package com.mangashelf.reader.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.util.Locale

object FileNames {

    /** SAF Uri 的显示文件名（可能为 null） */
    fun displayName(context: Context, uri: Uri): String? {
        val queried = runCatching {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) cursor.getString(index) else null
                    } else {
                        null
                    }
                }
        }.getOrNull()
        return queried ?: uri.lastPathSegment?.substringAfterLast('/')
    }

    /** 去掉扩展名，用来做默认书名 */
    fun stripExtension(name: String): String {
        val base = name.substringBeforeLast('.', name).trim()
        return base.ifBlank { name }
    }
}

/** 页面文件命名规则，导入端与阅读端共用 */
internal object PageFileNaming {
    fun of(index: Int): String = String.format(Locale.US, "page_%03d.jpg", index + 1)
}
