package com.mangashelf.reader.importer

import android.content.Context
import android.net.Uri
import com.mangashelf.reader.data.PageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * CBZ / ZIP 漫画包导入：压缩包里每张图片 = 一页。
 *
 * 为什么要读两遍：`ZipInputStream` 是**顺序流**，不能跳回去重读某个条目。
 * 第一遍只扫条目名（`nextEntry` 会自动跳过条目数据，不解压），排好顺序；
 * 第二遍再解压，按第一遍算出的位置命名（`page_001.jpg` …）。
 * 这样一遍解压就能落到正确序号，不需要临时目录中转。
 */
object ArchiveImporter {

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "jfif")

    /** 单张原图上限：CBZ 里偶尔混进扫描原图，超过这个尺寸直接跳过，避免 OOM */
    private const val MAX_ENTRY_BYTES = 40L * 1024 * 1024

    suspend fun import(
        context: Context,
        uri: Uri,
        destDir: File,
        onProgress: (done: Int, total: Int) -> Unit
    ): List<PageSource> = withContext(Dispatchers.IO) {
        destDir.mkdirs()

        val names = listImageEntries(context, uri)
        if (names.isEmpty()) return@withContext emptyList()

        // 压缩包里的条目顺序常常是打包时的文件系统顺序，不一定等于页序；按名字自然排
        val ordered = names.sortedWith(Comparator { a, b -> FolderImporter.compareNatural(a, b) })
        val targetIndex = ordered.withIndex().associate { (index, name) -> name to index }

        val sources = arrayOfNulls<PageSource>(ordered.size)
        var processed = 0

        runCatching {
            context.contentResolver.openInputStream(uri)?.use { raw ->
                ZipInputStream(BufferedInputStream(raw)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) continue
                        val index = targetIndex[entry.name] ?: continue

                        val bytes = runCatching { zip.readBytes() }.getOrNull()
                        if (bytes != null && bytes.isNotEmpty() &&
                            bytes.size.toLong() <= MAX_ENTRY_BYTES
                        ) {
                            val bitmap = ImageCodec.loadBytes(bytes)
                            if (bitmap != null) {
                                val relPath = PageFileNaming.of(index)
                                runCatching {
                                    ImageCodec.write(bitmap, File(destDir, relPath))
                                    sources[index] = PageSource(
                                        relPath = relPath,
                                        originName = entry.name.substringAfterLast('/'),
                                        // entry.size 是解压后大小，压缩包里有时是 -1
                                        sizeBytes = if (entry.size > 0) entry.size else bytes.size.toLong(),
                                        modifiedAt = entry.time.coerceAtLeast(0L)
                                    )
                                }
                                bitmap.recycle()
                            }
                        }

                        processed++
                        onProgress(processed, ordered.size)
                    }
                }
            }
        }

        // 坏图留空，不让一张图毁掉整本书
        sources.filterNotNull()
    }

    /** 第一遍：只取图片条目名，不解压数据 */
    private fun listImageEntries(context: Context, uri: Uri): List<String> {
        val names = ArrayList<String>()
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { raw ->
                ZipInputStream(BufferedInputStream(raw)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (!entry.isDirectory && isImage(entry.name) && !isNoise(entry.name)) {
                            names.add(entry.name)
                        }
                        zip.closeEntry()
                    }
                }
            }
        }
        return names
    }

    private fun isImage(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext in IMAGE_EXTENSIONS
    }

    /** 跳过 macOS 打包垃圾、隐藏文件和常见的说明文件 */
    private fun isNoise(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        val leaf = lower.substringAfterLast('/')
        return lower.startsWith("__macosx/") ||
            leaf == ".ds_store" ||
            leaf.startsWith(".") ||
            leaf == "thumbs.db"
    }
}
