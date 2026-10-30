package com.mangashelf.reader.ui

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mangashelf.reader.data.DirLevel
import com.mangashelf.reader.data.DirSort
import com.mangashelf.reader.data.DirState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections

/** 文件页里一项：可能是子目录，也可能是一个可导入的文件夹。 */
data class FileItem(
    val treeUri: Uri,
    val docId: String,
    val name: String,
    val isDir: Boolean,
    val sizeBytes: Long,
    val modifiedAt: Long,
    /** 直接子项数量；目录才有意义，目录外的文件为 0 */
    val childCount: Int,
)

/**
 * 文件浏览页的状态机（v1.5.7）。
 *
 * 只做三件事：列目录、记住来时的路、管多选。
 * 真正的导入交给 [ShelfViewModel]，这里只负责把选中的文件夹变成 tree uri 列表。
 */
class FileBrowserViewModel(app: Application) : AndroidViewModel(app) {

    private val context = app.applicationContext

    /** 当前打开的是哪个根目录（SAF 授权的那一级） */
    private val _rootUri = MutableStateFlow<Uri?>(null)
    val rootUri: StateFlow<Uri?> = _rootUri.asStateFlow()

    /** 路径栈：栈底是根，栈顶是当前目录 */
    private val _stack = MutableStateFlow<List<DirLevel>>(emptyList())
    val stack: StateFlow<List<DirLevel>> = _stack.asStateFlow()

    private val _items = MutableStateFlow<List<FileItem>>(emptyList())
    val items: StateFlow<List<FileItem>> = _items.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _sort = MutableStateFlow(DirSort.NAME to true)
    val sort: StateFlow<Pair<DirSort, Boolean>> = _sort.asStateFlow()

    private val _selectMode = MutableStateFlow(false)
    val selectMode: StateFlow<Boolean> = _selectMode.asStateFlow()

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    /** 上次点进去的那一项（返回时高亮用） */
    private val _highlight = MutableStateFlow<String>("")
    val highlight: StateFlow<String> = _highlight.asStateFlow()

    val current: DirLevel?
        get() = _stack.value.lastOrNull()

    // ---------- 入口 ----------

    /** 用户选完根目录后调用；会尝试持久化授权，失败也能用（只是重启后要重选） */
    fun openRoot(treeUri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        DirState.saveRoot(context, treeUri.toString())
        _rootUri.value = treeUri
        val saved = DirState.loadStack(context, treeUri.toString())
        // 上次的路径栈如果都失效了（目录被删），就从根重新开始
        _stack.value = if (saved.isNotEmpty() && saved.all { exists(it.uri) }) {
            saved
        } else {
            listOf(DirLevel(treeUri.toString()))
        }
        refresh()
    }

    /** 冷启动：有记住的根就直接进去 */
    fun restore() {
        if (_rootUri.value != null) return
        val raw = DirState.loadRoot(context)
        if (raw.isBlank()) return
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return
        if (!exists(raw)) {
            // 根目录也被删了，清掉记忆，让 UI 显示"选择根目录"
            DirState.clearRoot(context)
            return
        }
        _rootUri.value = uri
        val saved = DirState.loadStack(context, raw)
        _stack.value = if (saved.isNotEmpty() && saved.all { exists(it.uri) }) {
            saved
        } else {
            listOf(DirLevel(raw))
        }
        refresh()
    }

    fun clearRoot() {
        DirState.resetAll(context)
        _rootUri.value = null
        _stack.value = emptyList()
        _items.value = emptyList()
    }

    // ---------- 目录操作 ----------

    fun currentDocId(): String? = current?.uri?.let { docIdOf(Uri.parse(it)) }

    fun refresh() {
        val cur = current ?: return
        val sortPair = DirState.loadSort(context, cur.uri)
        _sort.value = sortPair
        val manual = DirState.loadManual(context, cur.uri)
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            val list = withContext(Dispatchers.IO) {
                runCatching { listChildren(Uri.parse(cur.uri)) }
                    .getOrElse { e ->
                        _error.value = describe(e)
                        emptyList()
                    }
            }
            _items.value = sortItems(list, sortPair.first, sortPair.second, manual)
            _loading.value = false
        }
    }

    fun setSort(sort: DirSort, asc: Boolean) {
        val cur = current ?: return
        DirState.saveSort(context, cur.uri, sort, asc)
        _sort.value = sort to asc
        _items.value = sortItems(_items.value, sort, asc, DirState.loadManual(context, cur.uri))
    }

    /** 进入子目录；把「从哪一项进来的」记到父层，返回时高亮它 */
    fun enter(item: FileItem, scrollIndex: Int) {
        if (!item.isDir) return
        val cur = current ?: return
        val updated = DirLevel(cur.uri, scrollIndex, item.docId)
        val nextStack = _stack.value.dropLast(1) + updated + DirLevel(item.treeUri.toString())
        _stack.value = nextStack
        _selected.value = emptySet()
        _selectMode.value = false
        _highlight.value = ""
        DirState.saveStack(context, _rootUri.value.toString(), nextStack)
        refresh()
    }

    /** 返回上级。返回 true 表示吃掉了这次返回；false 表示已经在根目录，交给外面退出页面 */
    fun goUp(): Boolean {
        if (_stack.value.size <= 1) return false
        val nextStack = _stack.value.dropLast(1)
        _stack.value = nextStack
        _selected.value = emptySet()
        _selectMode.value = false
        DirState.saveStack(context, _rootUri.value.toString(), nextStack)
        val parent = nextStack.last()
        _highlight.value = parent.lastClicked
        refresh()
        return true
    }

    /** 记录当前目录的滚动位置，切换目录前调用 */
    fun rememberScroll(index: Int) {
        val cur = current ?: return
        if (cur.scrollIndex == index) return
        val next = _stack.value.dropLast(1) + DirLevel(cur.uri, index, cur.lastClicked)
        _stack.value = next
        DirState.saveStack(context, _rootUri.value.toString(), next)
    }

    // ---------- 多选 ----------

    fun enterSelectMode(docId: String) {
        _selectMode.value = true
        _selected.value = setOf(docId)
    }

    fun toggleSelect(docId: String) {
        _selected.value = _selected.value.let { if (it.contains(docId)) it - docId else it + docId }
    }

    fun selectAll() {
        _selected.value = _items.value.map { it.docId }.toSet()
    }

    fun invertSelection() {
        val all = _items.value.map { it.docId }.toSet()
        _selected.value = all - _selected.value
    }

    fun exitSelectMode() {
        _selectMode.value = false
        _selected.value = emptySet()
    }

    /**
     * 选中的文件夹 → (docId, 名称)。
     *
     * 这里刻意不返回 tree URI：用户只授权了根树，子目录另造 tree uri 会丢 grant，
     * 导入时会被判「找不到子文件夹」。走 docId + 根树，深度不受限。
     */
    fun selectedDocs(): List<Pair<String, String>> =
        _items.value
            .filter { _selected.value.contains(it.docId) && it.isDir }
            .map { it.docId to it.name }

    /** 选中目录的总大小：目录本身 size=0，直接用子项估算会偏小，所以只作参考值展示 */
    fun selectedSizeBytes(): Long =
        _items.value.filter { _selected.value.contains(it.docId) }.sumOf { it.sizeBytes }

    /** 选中项的文件夹名，用于导入前的提示 */
    fun selectedNames(): List<String> =
        _items.value.filter { _selected.value.contains(it.docId) }.map { it.name }

    // ---------- 列目录 ----------

    private fun exists(uriString: String): Boolean = runCatching {
        val uri = Uri.parse(uriString)
        context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
            ?.use { it.moveToFirst() } ?: false
    }.getOrDefault(false)

    private fun listChildren(dirUri: Uri): List<FileItem> {
        val treeUri = _rootUri.value ?: dirUri
        val docId = docIdOf(dirUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val out = ArrayList<FileItem>()
        context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val childId = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2).orEmpty()
                val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                val size = if (c.isNull(3)) 0L else c.getLong(3)
                val modified = if (c.isNull(4)) 0L else c.getLong(4)
                out.add(
                    FileItem(
                        treeUri = DocumentsContract.buildTreeDocumentUri(treeUri.authority!!, childId),
                        docId = childId,
                        name = name,
                        isDir = isDir,
                        sizeBytes = size,
                        modifiedAt = modified,
                        childCount = 0
                    )
                )
            }
        }
        return out
    }

    private fun sortItems(
        list: List<FileItem>,
        sort: DirSort,
        asc: Boolean,
        manual: List<String>
    ): List<FileItem> {
        // 目录永远排前面，再按字段排——这条规则不随排序方式变
        val dirsFirst = { a: FileItem, _: FileItem -> a.isDir }
        val collator = java.text.Collator.getInstance(java.util.Locale.CHINA)
        val base: Comparator<FileItem> = when (sort) {
            DirSort.NAME -> Comparator { a, b -> naturalCompare(a.name, b.name) }
            DirSort.TIME -> compareBy { it.modifiedAt }
            DirSort.SIZE -> compareBy { it.sizeBytes }
            DirSort.IMPORT -> compareBy { it.docId }
            DirSort.MANUAL -> Comparator { a, b ->
                val ia = manual.indexOf(a.docId).let { if (it < 0) Int.MAX_VALUE else it }
                val ib = manual.indexOf(b.docId).let { if (it < 0) Int.MAX_VALUE else it }
                if (ia != ib) ia - ib else naturalCompare(a.name, b.name)
            }
        }
        val dirCmp = Comparator<FileItem> { a, b ->
            if (a.isDir == b.isDir) 0 else if (a.isDir) -1 else 1
        }
        val full = dirCmp.then(if (asc) base else base.reversed())
        return list.sortedWith(full)
    }

    /** `第2话` 要排在 `第10话` 前面，逐字符比较时把数字段当整数 */
    private fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ni = i
                while (ni < a.length && a[ni].isDigit()) ni++
                var nj = j
                while (nj < b.length && b[nj].isDigit()) nj++
                val na = a.substring(i, ni).toLongOrNull() ?: 0
                val nb = b.substring(j, nj).toLongOrNull() ?: 0
                if (na != nb) return if (na < nb) -1 else 1
                i = ni
                j = nj
            } else {
                val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (cmp != 0) return cmp
                i++
                j++
            }
        }
        return (a.length - i).compareTo(b.length - j)
    }

    private fun describe(e: Throwable): String = when (e) {
        is SecurityException -> "这个文件夹的访问权限失效了，请重新选择根目录"
        is java.io.FileNotFoundException -> "文件夹不存在或已被移动"
        else -> "打不开这个文件夹：${e.message ?: e.javaClass.simpleName}"
    }

    private fun docIdOf(uri: Uri): String = runCatching {
        DocumentsContract.getDocumentId(uri)
    }.getOrElse { DocumentsContract.getTreeDocumentId(uri) }
}
