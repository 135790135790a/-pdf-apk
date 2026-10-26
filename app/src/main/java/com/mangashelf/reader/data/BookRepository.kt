package com.mangashelf.reader.data

import com.mangashelf.reader.importer.FolderImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.withContext
import java.io.File

class BookRepository(
    private val dao: BookDao,
    private val pageDao: PageDao,
    val storage: ComicStorage
) {

    /**
     * 书架列表。查询出错（数据库文件异常、磁盘 IO 错误）时吞掉异常返回空列表，
     * 让 UI 显示"空书架"而不是整页崩溃——重启通常就恢复了。
     */
    fun observeBooks(): Flow<List<Book>> = dao.observeAll().catch { emit(emptyList()) }

    fun observeBook(id: Long): Flow<Book?> = dao.observeOne(id).catch { emit(null) }

    suspend fun insert(book: Book): Long = dao.insert(book)

    /**
     * 插入一本书并同时写入页序表。
     *
     * 两件事必须在一起做：只写书不写页序，阅读器就只能回退到"按文件名猜顺序"，
     * 页序排序功能等于没有。
     */
    suspend fun insertBook(book: Book, pages: List<PageSource>): Long = withContext(Dispatchers.IO) {
        val id = dao.insert(book)
        if (pages.isNotEmpty()) {
            pageDao.insertAll(
                pages.mapIndexed { index, source ->
                    PageEntry(
                        bookId = id,
                        idx = index,
                        relPath = source.relPath,
                        originName = source.originName,
                        sizeBytes = source.sizeBytes,
                        modifiedAt = source.modifiedAt
                    )
                }
            )
        }
        id
    }

    /** 批量导入前判断"这个文件夹是不是已经导过"（断点续传） */
    suspend fun findBySourceKey(key: String): Book? = dao.findBySourceKey(key)

    suspend fun delete(book: Book) = withContext(Dispatchers.IO) {
        // 显式删 pages，不依赖外键级联（部分设备/配置下 foreign_keys 未必生效）
        pageDao.deleteByBook(book.id)
        dao.delete(book.id)
        storage.deleteFolder(book.folder)
    }

    suspend fun rename(bookId: Long, title: String) {
        val book = dao.get(bookId) ?: return
        if (title.isNotBlank() && title != book.title) dao.update(book.copy(title = title))
    }

    suspend fun setRtl(bookId: Long, rtl: Boolean) {
        val book = dao.get(bookId) ?: return
        if (book.rtl != rtl) dao.update(book.copy(rtl = rtl))
    }

    suspend fun saveProgress(bookId: Long, page: Int) {
        val book = dao.get(bookId) ?: return
        val clamped = page.coerceIn(0, (book.pageCount - 1).coerceAtLeast(0))
        if (book.lastPage != clamped) dao.update(book.copy(lastPage = clamped))
    }

    // ---------------------------------------------------------------------
    // 页面顺序（v2 起的"顺序间接层"，v3 起真正按 relPath 取文件）
    // ---------------------------------------------------------------------

    fun observePages(bookId: Long): Flow<List<PageEntry>> = pageDao.observeByBook(bookId)

    suspend fun pageEntries(bookId: Long): List<PageEntry> = pageDao.getByBook(bookId)

    /**
     * 按**落盘文件名**拼路径（v1 的老逻辑）。
     * 只用于封面这类"第一张图"的场景；阅读器必须走 [loadPagePaths]，
     * 否则重排页序后看到的还是旧顺序。
     */
    fun pagePaths(book: Book): List<String> =
        (0 until book.pageCount).map { storage.pagePath(book.folder, it) }

    /**
     * 顺序间接层的真正实现：按 `pages.idx` 顺序、用每行的 `relPath` 拼路径。
     *
     * 这里**不重命名任何文件**——排序只改 idx。几百页的漫画如果靠重命名文件来排序，
     * 中途失败就是半套顺序，而且每次排序要写几百次磁盘。
     */
    suspend fun loadPagePaths(book: Book): List<String> = withContext(Dispatchers.IO) {
        val entries = entriesOrBackfill(book)
        if (entries.isEmpty()) {
            pagePaths(book)
        } else {
            entries.map { File(storage.dirOf(book.folder), it.relPath).absolutePath }
        }
    }

    /**
     * 按指定规则重排页序（只改 pages.idx，不动文件），并把规则记在 books 上，
     * 下次进阅读器/重启 App 都还是这个顺序。
     */
    suspend fun applyPageOrder(book: Book, mode: PageSortMode, asc: Boolean) =
        withContext(Dispatchers.IO) {
            val entries = entriesOrBackfill(book)
            if (entries.isEmpty()) return@withContext

            val comparator: Comparator<PageEntry>? = when (mode) {
                PageSortMode.NATURAL -> Comparator { a, b ->
                    FolderImporter.compareNatural(a.nameKey(), b.nameKey())
                }
                PageSortMode.NAME -> Comparator { a, b ->
                    a.nameKey().compareTo(b.nameKey(), ignoreCase = true)
                }
                PageSortMode.MTIME -> compareBy { it.modifiedAt }
                PageSortMode.SIZE -> compareBy { it.sizeBytes }
                PageSortMode.FIXED -> null
            }

            val ordered = if (comparator == null) {
                entries
            } else {
                val sorted = entries.sortedWith(comparator)
                if (asc) sorted else sorted.reversed()
            }

            // 整表重建：几百行逐条 update 是几百次事务，中途失败会留下半套顺序
            pageDao.replaceAll(
                book.id,
                ordered.mapIndexed { position, entry ->
                    entry.copy(id = 0L, idx = position)
                }
            )
            dao.updateSort(book.id, mode.name, asc)
        }

    /**
     * 取 pages 行；为 0 行时（v1/v2 时代导入的老书）从磁盘懒生成一次。
     *
     * v2 迁移回填过的书 relPath 是 `page_001.jpg`，与文件一致，不需要重建；
     * 但那些行的 sizeBytes / modifiedAt 都是 0，排序会退化成"全部相等"，
     * 所以顺手用磁盘上的真实大小/时间补一次。
     */
    private suspend fun entriesOrBackfill(book: Book): List<PageEntry> {
        val existing = pageDao.getByBook(book.id)
        if (existing.isNotEmpty() && existing.any { it.sizeBytes > 0L }) return existing

        val dir = storage.dirOf(book.folder)
        val files = dir.listFiles { file ->
            file.isFile && file.name.startsWith("page_") && file.name.endsWith(".jpg")
        }?.sortedWith { a, b -> FolderImporter.compareNatural(a.name, b.name) }.orEmpty()
        if (files.isEmpty()) return existing

        val regenerated = files.mapIndexed { index, file ->
            PageEntry(
                bookId = book.id,
                idx = index,
                relPath = file.name,
                originName = file.name,
                sizeBytes = file.length(),
                modifiedAt = file.lastModified()
            )
        }
        pageDao.replaceAll(book.id, regenerated)
        return pageDao.getByBook(book.id)
    }

    private fun PageEntry.nameKey(): String = originName.ifBlank { relPath }

    /** 当前页面列表用于"缩略图选页" */
    suspend fun pageFiles(book: Book): List<File> = withContext(Dispatchers.IO) {
        val entries = entriesOrBackfill(book)
        if (entries.isEmpty()) {
            pagePaths(book).map { File(it) }
        } else {
            entries.map { File(storage.dirOf(book.folder), it.relPath) }
        }
    }

    fun coverPath(book: Book): String = storage.coverPath(book.folder)
}
