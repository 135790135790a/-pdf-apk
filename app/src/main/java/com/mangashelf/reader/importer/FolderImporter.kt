package com.mangashelf.reader.importer

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * 整个文件夹 = 一本书。
 *
 * 规则（和网页版一致）：
 * - 递归读子文件夹，子文件夹按章节顺序接在后面
 * - 按**相对路径自然排序**：2.png 在 10.png 之前，ch2/ 在 ch10/ 之前
 * - 非图片文件（说明.txt、Thumbs.db、.DS_Store）自动忽略
 * - 坏图跳过并继续，不让整本失败
 */
object FolderImporter {

    private const val MAX_PAGES = 3000
    private const val MAX_DEPTH = 8

    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "avif", "tif", "tiff")

    suspend fun import(
        context: Context,
        treeUri: Uri,
        destDir: File,
        onProgress: (done: Int, total: Int) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        destDir.mkdirs()

        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalStateException("打不开这个文件夹")

        val found = ArrayList<Entry>(64)
        collect(root, "", found, 0)
        if (found.isEmpty()) {
            throw IllegalStateException("这个文件夹里没有图片（支持 jpg/png/webp/bmp/gif/avif/tif）")
        }
        if (found.size > MAX_PAGES) {
            throw IllegalStateException("图片太多（${found.size} 张），上限 $MAX_PAGES 张")
        }

        val ordered = found.sortedWith(Comparator { a, b -> compareNatural(a.path, b.path) })

        var saved = 0
        ordered.forEachIndexed { index, entry ->
            val bitmap = runCatching { ImageCodec.load(context, entry.uri) }.getOrNull()
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
        if (saved == 0) throw IllegalStateException("这个文件夹里的图片都读不出来")
        saved
    }

    private class Entry(val path: String, val uri: Uri)

    private fun collect(dir: DocumentFile, prefix: String, out: MutableList<Entry>, depth: Int) {
        if (depth > MAX_DEPTH) return
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (child in children) {
            val name = child.name ?: continue
            if (name.startsWith(".")) continue
            if (child.isDirectory) {
                collect(child, "$prefix$name/", out, depth + 1)
            } else if (child.isFile && isImage(name)) {
                out.add(Entry("$prefix$name", child.uri))
            }
        }
    }

    private fun isImage(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext in IMAGE_EXT
    }

    /** 自然排序：把字符串切成"文本段 + 数字段"，数字段按数值比较 */
    internal fun compareNatural(a: String, b: String): Int {
        val ta = tokenize(a)
        val tb = tokenize(b)
        var i = 0
        while (i < ta.size && i < tb.size) {
            val x = ta[i]
            val y = tb[i]
            val c = if (x is Long && y is Long) {
                x.compareTo(y)
            } else {
                x.toString().compareTo(y.toString(), ignoreCase = true)
            }
            if (c != 0) return c
            i++
        }
        return ta.size - tb.size
    }

    private fun tokenize(text: String): List<Any> {
        val out = ArrayList<Any>(8)
        val buffer = StringBuilder()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch.isDigit()) {
                if (buffer.isNotEmpty()) {
                    out.add(buffer.toString())
                    buffer.setLength(0)
                }
                var end = i
                while (end < text.length && text[end].isDigit()) end++
                out.add(text.substring(i, end).toLongOrNull() ?: 0L)
                i = end
            } else {
                buffer.append(ch)
                i++
            }
        }
        if (buffer.isNotEmpty()) out.add(buffer.toString())
        return out
    }
}
