package com.mangashelf.reader.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class BookRepository(
    private val dao: BookDao,
    private val pageDao: PageDao,
    val storage: ComicStorage
) {

    fun observeBooks() = dao.observeAll()

    fun observeBook(id: Long) = dao.observeOne(id)

    suspend fun insert(book: Book): Long = dao.insert(book)

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
    // 页面顺序（v2 起的"间接层"）
    // ---------------------------------------------------------------------

    fun observePages(bookId: Long): Flow<List<PageEntry>> = pageDao.observeByBook(bookId)

    suspend fun pageEntries(bookId: Long): List<PageEntry> = pageDao.getByBook(bookId)

    /**
     * 同步版：按序号文件名取路径（v1 的老逻辑）。
     * 当前阅读器仍用它；等第 2 项把阅读器切到"顺序间接层"后再删掉。
     */
    fun pagePaths(book: Book): List<String> =
        (0 until book.pageCount).map { storage.pagePath(book.folder, it) }

    /**
     * 顺序间接层版本：按 pages.idx 取路径。
     *
     * 老书（v1 升上来后 pages 行已回填，idx = 0..n-1）得到的结果与同步版一致；
     * 极端情况下 pages 为空则回退到老逻辑，保证永不白屏。
     */
    suspend fun loadPagePaths(book: Book): List<String> = withContext(Dispatchers.IO) {
        val entries = pageDao.getByBook(book.id)
        if (entries.isEmpty()) {
            pagePaths(book)
        } else {
            entries.map { storage.pagePath(book.folder, it.idx) }
        }
    }

    /**
     * 写入一套新顺序（排序结果）。
     *
     * 整表重建而不是逐行 update：几百页逐行 update 是几百次事务，慢且中途失败会出现半套顺序。
     */
    suspend fun applyPageOrder(book: Book, ordered: List<PageEntry>, mode: String, asc: Boolean) =
        withContext(Dispatchers.IO) {
            pageDao.deleteByBook(book.id)
            pageDao.insertAll(
                ordered.mapIndexed { position, entry ->
                    entry.copy(id = 0L, bookId = book.id, idx = position)
                }
            )
            dao.updateSort(book.id, mode, asc)
        }

    fun coverPath(book: Book): String = storage.coverPath(book.folder)
}
