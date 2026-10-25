package com.mangashelf.reader.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BookRepository(
    private val dao: BookDao,
    val storage: ComicStorage
) {

    fun observeBooks() = dao.observeAll()

    fun observeBook(id: Long) = dao.observeOne(id)

    suspend fun insert(book: Book): Long = dao.insert(book)

    suspend fun delete(book: Book) = withContext(Dispatchers.IO) {
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

    fun pagePaths(book: Book): List<String> =
        (0 until book.pageCount).map { storage.pagePath(book.folder, it) }

    fun coverPath(book: Book): String = storage.coverPath(book.folder)
}
