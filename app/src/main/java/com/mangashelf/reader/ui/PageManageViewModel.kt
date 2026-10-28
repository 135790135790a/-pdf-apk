package com.mangashelf.reader.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mangashelf.reader.MangaShelfApp
import com.mangashelf.reader.data.Book
import com.mangashelf.reader.data.BookRepository
import com.mangashelf.reader.data.BookStats
import com.mangashelf.reader.data.PageEntry
import com.mangashelf.reader.data.PageSortMode
import com.mangashelf.reader.data.ThumbStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

/**
 * 页面管理（v1.5.3）：重排、软删除、设封面、恢复、彻底删除。
 *
 * 重排只改 `pages.idx`，**不动磁盘文件名**——所以调顺序不会碰文件，
 * 也就不存在"改到一半断电导致图片丢失"的窗口。
 */
class PageManageViewModel(app: Application, private val bookId: Long) : AndroidViewModel(app) {

    private val repo: BookRepository = (app as MangaShelfApp).repository

    private val _book = MutableStateFlow<Book?>(null)
    val book: StateFlow<Book?> = _book.asStateFlow()

    private val _pages = MutableStateFlow<List<PageEntry>>(emptyList())
    val pages: StateFlow<List<PageEntry>> = _pages.asStateFlow()

    private val _selected = MutableStateFlow<Set<Long>>(emptySet())
    val selected: StateFlow<Set<Long>> = _selected.asStateFlow()

    private val _stats = MutableStateFlow<BookStats?>(null)
    val stats: StateFlow<BookStats?> = _stats.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /**
     * 缩略图路径缓存（内存）。网格滚动时每格都会问一次，
     * 不能每次都 stat 磁盘，也不能每次都重新解码。
     */
    private val thumbs = java.util.concurrent.ConcurrentHashMap<Long, String>()

    /** 同一时刻只生成一张缩略图：并发解码多张大图只会更快地 OOM */
    private val thumbLock = Mutex()

    /** 已经生成过的缩略图路径；没生成过返回 null（调用方先用原图兜底） */
    fun cachedThumb(page: PageEntry): String? = thumbs[page.id]

    /**
     * 确保这一页的缩略图存在。生成是懒的：网格滚到哪页才生成哪页。
     * 返回缩略图的绝对路径，失败返回 null。
     */
    suspend fun ensureThumb(page: PageEntry): String? {
        thumbs[page.id]?.let { return it }
        val book = _book.value ?: return null
        return thumbLock.withLock {
            thumbs[page.id]?.let { return@withLock it }
            val rel = runCatching { ThumbStore.ensure(repo.storage, book.folder, page) }
                .getOrNull() ?: return@withLock null
            if (rel.isEmpty()) return@withLock null
            val path = repo.storage.thumbFile(book.folder, page.relPath).absolutePath
            thumbs[page.id] = path
            path
        }
    }

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            refresh()
        }
    }

    private suspend fun refresh() {
        val book = runCatching { repo.observeBook(bookId).first() }.getOrNull()
        _book.value = book
        _pages.value = repo.pageEntries(bookId)
        _stats.value = book?.let { runCatching { repo.bookStats(it) }.getOrNull() }
    }

    fun toggle(id: Long) {
        _selected.value = _selected.value.let { if (id in it) it - id else it + id }
    }

    fun selectAll() {
        _selected.value = _pages.value.filter { it.deleted == 0 }.map { it.id }.toSet()
    }

    fun clearSelection() {
        _selected.value = emptySet()
    }

    fun consumeMessage() {
        _message.value = null
    }

    /** 上移 / 下移选中块；direction = -1 上移，+1 下移 */
    fun moveSelected(direction: Int) {
        val book = _book.value ?: return
        val visible = _pages.value.filter { it.deleted == 0 }
        val sel = _selected.value
        if (sel.isEmpty()) return
        val indices = visible.indices.filter { visible[it].id in sel }
        if (indices.isEmpty()) return
        if (direction < 0 && indices.first() == 0) return
        if (direction > 0 && indices.last() == visible.lastIndex) return
        _busy.value = true
        viewModelScope.launch {
            val list = visible.toMutableList()
            if (direction < 0) {
                indices.forEach { i ->
                    val tmp = list[i]
                    list[i] = list[i - 1]
                    list[i - 1] = tmp
                }
            } else {
                indices.reversed().forEach { i ->
                    val tmp = list[i]
                    list[i] = list[i + 1]
                    list[i + 1] = tmp
                }
            }
            runCatching { repo.reorderPages(book, list.map { it.id }) }
            refresh()
            _busy.value = false
        }
    }

    /** 拖动排序：把某页往前/往后挪 [offset] 位（负数往前）。只改 idx，不动文件 */
    fun movePage(pageId: Long, offset: Int) {
        if (offset == 0) return
        val book = _book.value ?: return
        val visible = _pages.value.filter { it.deleted == 0 }
        val from = visible.indexOfFirst { it.id == pageId }
        if (from < 0) return
        val to = (from + offset).coerceIn(0, visible.lastIndex)
        if (to == from) return
        _busy.value = true
        viewModelScope.launch {
            val list = visible.toMutableList()
            list.add(to, list.removeAt(from))
            runCatching { repo.reorderPages(book, list.map { it.id }) }
            refresh()
            _busy.value = false
            _message.value = "已移动到第 ${to + 1} 位"
        }
    }

    /** 选中块置顶（edge = -1）或置底（edge = +1） */
    fun moveSelectedTo(edge: Int) {
        val book = _book.value ?: return
        val visible = _pages.value.filter { it.deleted == 0 }
        val sel = _selected.value
        if (sel.isEmpty()) return
        val picked = visible.filter { it.id in sel }
        if (picked.isEmpty()) return
        val rest = visible.filterNot { it.id in sel }
        val ordered = if (edge < 0) picked + rest else rest + picked
        if (ordered.map { it.id } == visible.map { it.id }) {
            _message.value = if (edge < 0) "已经在最前面了" else "已经在最后面了"
            return
        }
        _busy.value = true
        viewModelScope.launch {
            runCatching { repo.reorderPages(book, ordered.map { it.id }) }
            refresh()
            _busy.value = false
            _message.value = if (edge < 0) "已置顶 ${picked.size} 页" else "已置底 ${picked.size} 页"
        }
    }

    fun applySort(mode: PageSortMode) {
        val book = _book.value ?: return
        _busy.value = true
        viewModelScope.launch {
            runCatching { repo.applyPageOrder(book, mode, mode.defaultAsc) }
            refresh()
            _selected.value = emptySet()
            _busy.value = false
            _message.value = "已按「${mode.label}」重排"
        }
    }

    fun resetOrder() {
        val book = _book.value ?: return
        _busy.value = true
        viewModelScope.launch {
            runCatching { repo.resetPageOrder(book) }
            refresh()
            _selected.value = emptySet()
            _busy.value = false
            _message.value = "已恢复导入时的顺序"
        }
    }

    /** 设为封面：参数是当前可见顺序里的位置（0 基） */
    fun setCover(position: Int) {
        val book = _book.value ?: return
        viewModelScope.launch {
            runCatching { repo.setCoverPage(book, position) }
            refresh()
            _message.value = "封面已更新"
        }
    }

    fun deleteSelected() {
        val book = _book.value ?: return
        val sel = _selected.value
        if (sel.isEmpty()) return
        _busy.value = true
        viewModelScope.launch {
            val n = runCatching { repo.softDeletePages(book, sel.toList()) }.getOrDefault(0)
            refresh()
            _selected.value = emptySet()
            _busy.value = false
            _message.value = "已把 $n 页移入回收站"
        }
    }

    fun restoreSelected() {
        val book = _book.value ?: return
        val sel = _selected.value
        if (sel.isEmpty()) return
        _busy.value = true
        viewModelScope.launch {
            val n = runCatching { repo.restorePages(book, sel.toList()) }.getOrDefault(0)
            refresh()
            _selected.value = emptySet()
            _busy.value = false
            _message.value = "已恢复 $n 页"
        }
    }

    /** 彻底删除回收站里的页（文件一起删，不可恢复） */
    fun purgeDeleted() {
        val book = _book.value ?: return
        _busy.value = true
        viewModelScope.launch {
            val (n, bytes) = runCatching { repo.purgeDeletedPages(book) }
                .getOrDefault(0 to 0L)
            refresh()
            _busy.value = false
            _message.value = "已彻底删除 $n 页，释放 ${formatBytes(bytes)}"
        }
    }

    fun restoreAllDeleted() {
        val book = _book.value ?: return
        val deleted = _pages.value.filter { it.deleted != 0 }.map { it.id }
        if (deleted.isEmpty()) return
        _busy.value = true
        viewModelScope.launch {
            val n = runCatching { repo.restorePages(book, deleted) }.getOrDefault(0)
            refresh()
            _busy.value = false
            _message.value = "已恢复 $n 页"
        }
    }

    companion object {
        fun factory(app: Application, bookId: Long): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return PageManageViewModel(app, bookId) as T
                }
            }
    }
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
