package com.mangashelf.reader.data

import android.content.Context
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 整库备份 / 恢复（含图片）。
 *
 * 备份就是一个 zip：
 * ```
 * backup.json                     ← 书籍与页的元数据
 * pages/<folder>/page_001.jpg     ← 页文件原样打包
 * ```
 *
 * 恢复时**不信任**备份里的任何路径：全部重写到新目录、重新分配 folder 名，
 * 并且丢掉 sourceKey / sourceTreeUri——那些 SAF 授权在换机后本来就失效了，
 * 留着只会让「已导入」判断误伤。
 */
object BackupManager {

    private const val FORMAT_VERSION = 1
    private const val MANIFEST = "backup.json"
    private const val PAGES_ROOT = "pages/"

    data class ExportResult(val books: Int, val pages: Int, val bytes: Long)
    data class RestoreResult(val books: Int, val pages: Int, val skipped: Int)

    /** 把全部书与页导出到 [target]（由 SAF 的 CreateDocument 返回） */
    suspend fun export(
        context: Context,
        storage: ComicStorage,
        books: List<Book>,
        pageDao: PageDao,
        target: Uri,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): ExportResult = withContext(Dispatchers.IO) {
        val manifest = JSONObject()
        manifest.put("format", FORMAT_VERSION)
        manifest.put("createdAt", System.currentTimeMillis())
        manifest.put("app", "MangaShelf")

        val booksJson = JSONArray()
        var totalPages = 0
        books.forEach { book ->
            val pages = runCatching { pageDao.getByBook(book.id).sortedBy { it.idx } }
                .getOrDefault(emptyList())
            booksJson.put(bookJson(book, pages))
            totalPages += pages.size
        }
        manifest.put("books", booksJson)

        var written = 0
        var bytes = 0L
        context.contentResolver.openOutputStream(target)?.let { raw ->
            ZipOutputStream(BufferedOutputStream(raw)).use { zip ->
                val header = manifest.toString().toByteArray(Charsets.UTF_8)
                zip.putNextEntry(ZipEntry(MANIFEST))
                zip.write(header)
                zip.closeEntry()
                bytes += header.size

                books.forEach { book ->
                    val pages = runCatching { pageDao.getByBook(book.id).sortedBy { it.idx } }
                        .getOrDefault(emptyList())
                    val dir = storage.dirOf(book.folder)
                    pages.forEach { page ->
                        val file = File(dir, page.relPath)
                        if (!file.exists()) return@forEach
                        zip.putNextEntry(ZipEntry("$PAGES_ROOT${book.folder}/${page.relPath}"))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                        bytes += file.length()
                        written++
                        onProgress(written, totalPages)
                    }
                }
            }
        } ?: error("无法写入备份文件")
        ExportResult(books = books.size, pages = totalPages, bytes = bytes)
    }

    /**
     * 从备份恢复。已存在的书名不跳过（用户可能想恢复一份旧的），
     * 但会重新分配目录，绝不会覆盖现有书的图片。
     */
    suspend fun restore(
        context: Context,
        storage: ComicStorage,
        repo: BookRepository,
        source: Uri,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): RestoreResult = withContext(Dispatchers.IO) {
        val staging = File(context.cacheDir, "restore-${System.currentTimeMillis()}")
        if (!staging.exists()) staging.mkdirs()
        try {
            var entries = 0
            context.contentResolver.openInputStream(source)?.let { raw ->
                ZipInputStream(raw).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        if (name == MANIFEST) {
                            File(staging, MANIFEST).outputStream().use { zip.copyTo(it) }
                        } else if (name.startsWith(PAGES_ROOT) && !entry.isDirectory) {
                            val relative = name.removePrefix(PAGES_ROOT)
                            // 防路径穿越：备份是外部文件，不能信
                            if (relative.contains("..") || relative.startsWith("/")) {
                                zip.closeEntry()
                                entry = zip.nextEntry
                                continue
                            }
                            val out = File(staging, "pages/$relative")
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zip.copyTo(it) }
                            entries++
                            if (entries % 20 == 0) onProgress(entries, entries)
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: error("无法读取备份文件")

            val manifestFile = File(staging, MANIFEST)
            if (!manifestFile.exists()) error("这个文件不是漫画书架的备份（缺少 $MANIFEST）")
            val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
            if (manifest.optInt("format", 0) != FORMAT_VERSION) {
                error("备份格式版本不支持（${manifest.optInt("format", 0)}）")
            }
            val booksJson = manifest.optJSONArray("books") ?: JSONArray()

            var restoredBooks = 0
            var restoredPages = 0
            var skipped = 0
            for (i in 0 until booksJson.length()) {
                val item = booksJson.getJSONObject(i)
                val oldFolder = item.optString("folder")
                val pagesJson = item.optJSONArray("pages") ?: JSONArray()
                val oldDir = File(staging, "pages/$oldFolder")
                if (!oldDir.exists()) {
                    skipped++
                    continue
                }
                val newFolder = storage.newFolder()
                val newDir = storage.dirOf(newFolder)
                val sourcePages = mutableListOf<PageSource>()
                for (j in 0 until pagesJson.length()) {
                    val p = pagesJson.getJSONObject(j)
                    val relPath = p.optString("relPath")
                    if (relPath.isBlank()) continue
                    val src = File(oldDir, relPath)
                    if (!src.exists()) continue
                    val name = File(relPath).name
                    src.copyTo(File(newDir, name), overwrite = true)
                    sourcePages += PageSource(
                        relPath = name,
                        originName = p.optString("originName"),
                        sizeBytes = p.optLong("sizeBytes", src.length()),
                        modifiedAt = p.optLong("modifiedAt", System.currentTimeMillis())
                    )
                    restoredPages++
                }
                if (sourcePages.isEmpty()) {
                    newDir.deleteRecursively()
                    skipped++
                    continue
                }
                val book = Book(
                    title = item.optString("title").ifBlank { "恢复的漫画" },
                    folder = newFolder,
                    pageCount = sourcePages.size,
                    rtl = item.optBoolean("rtl", true),
                    lastPage = item.optInt("lastPage", 0).coerceIn(0, sourcePages.size - 1),
                    sortMode = item.optString("sortMode").ifBlank { PageSortMode.NATURAL.name },
                    sortAsc = item.optBoolean("sortAsc", true),
                    sourceType = item.optString("sourceType").ifBlank { "RESTORE" },
                    sourceKey = null,
                    sourceTreeUri = null
                )
                val bookId = repo.insertBook(book, sourcePages)
                val mode = PageSortMode.fromStorage(book.sortMode)
                if (mode != PageSortMode.FIXED) {
                    runCatching { repo.applyPageOrder(book.copy(id = bookId), mode, book.sortAsc) }
                }
                restoredBooks++
                onProgress(restoredBooks, booksJson.length())
            }
            RestoreResult(restoredBooks, restoredPages, skipped)
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun bookJson(book: Book, pages: List<PageEntry>): JSONObject {
        val obj = JSONObject()
        obj.put("title", book.title)
        obj.put("folder", book.folder)
        obj.put("pageCount", book.pageCount)
        obj.put("rtl", book.rtl)
        obj.put("lastPage", book.lastPage)
        obj.put("sortMode", book.sortMode)
        obj.put("sortAsc", book.sortAsc)
        obj.put("sourceType", book.sourceType)
        val arr = JSONArray()
        pages.forEach { page ->
            arr.put(
                JSONObject().apply {
                    put("relPath", page.relPath)
                    put("originName", page.originName)
                    put("sizeBytes", page.sizeBytes)
                    put("modifiedAt", page.modifiedAt)
                }
            )
        }
        obj.put("pages", arr)
        return obj
    }
}
