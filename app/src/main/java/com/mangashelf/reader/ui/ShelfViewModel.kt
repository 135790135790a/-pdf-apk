package com.mangashelf.reader.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.documentfile.provider.DocumentFile
import com.mangashelf.reader.MangaShelfApp
import com.mangashelf.reader.data.Book
import com.mangashelf.reader.data.CoverPrefs
import com.mangashelf.reader.data.CoverQuality
import com.mangashelf.reader.data.GalleryExporter
import com.mangashelf.reader.data.ImportBatch
import com.mangashelf.reader.data.ImportSortPrefs
import com.mangashelf.reader.data.ImportStatusMachine
import com.mangashelf.reader.data.BatchReportExporter
import com.mangashelf.reader.service.ImportControl
import com.mangashelf.reader.service.ImportForegroundService
import com.mangashelf.reader.data.ImportTask
import com.mangashelf.reader.data.PageSortMode
import com.mangashelf.reader.data.PageSource
import com.mangashelf.reader.importer.ArchiveImporter
import com.mangashelf.reader.importer.FileNames
import com.mangashelf.reader.importer.FolderImporter
import com.mangashelf.reader.importer.ImageImporter
import com.mangashelf.reader.importer.PdfImporter
import com.mangashelf.reader.importer.StorageGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val KEY_ORDER = "order"

sealed interface ImportUiState {
    data object Idle : ImportUiState

    /** 单本导入（PDF / 一批图片 / 一个文件夹） */
    data class Running(val title: String, val done: Int, val total: Int) : ImportUiState

    /** 批量导入：一个父文件夹下的第 index/total 本 */
    data class Batch(
        val index: Int,
        val total: Int,
        val title: String,
        val done: Int,
        val totalPages: Int,
        val skipped: Int,
        val paused: Boolean
    ) : ImportUiState

    /** 批量导入收尾汇总 */
    data class Finished(
        val imported: Int,
        val skipped: Int,
        val failed: Int,
        val cancelled: Boolean,
        /** 有失败项时可以按这个 id 重试 */
        val batchId: String? = null
    ) : ImportUiState

    data class Failed(val message: String) : ImportUiState
}

/** v1.5.6：队列里的一批（一个批次 = 用户选的一个文件夹，内含若干本） */
data class BatchSpec(
    val batchId: String,
    val name: String,
    val sourceTreeUri: Uri,
    val children: List<FolderImporter.ChildFolder>,
    /**
     * true = 子项可能不是父树的直接子项（文件页里深进几层再选的情况）。
     * 走 ContentResolver 递归收集，否则 importChild 会在父树直接子项里找不到而报「找不到子文件夹」。
     */
    val deep: Boolean = false
)

/**
 * v1.5.6：批次在界面上的样子。
 *
 * 三级进度里的「批次级」就靠这里的 doneBooks / totalBooks；
 * 书级和页级仍然走 ImportUiState.Batch。
 */
data class BatchRow(
    val batchId: String,
    val name: String,
    val totalBooks: Int,
    val doneBooks: Int,
    val failedBooks: Int,
    val skippedBooks: Int,
    val status: String,
    val message: String? = null
) {
    val finished: Boolean
        get() = status == ImportBatch.DONE || status == ImportBatch.PARTIAL ||
            status == ImportBatch.FAILED || status == ImportBatch.CANCELLED

    val statusLabel: String
        get() = when (status) {
            ImportBatch.QUEUED -> "排队中"
            ImportBatch.RUNNING -> "导入中"
            ImportBatch.PAUSED -> "已暂停"
            ImportBatch.DONE -> "已成功"
            ImportBatch.PARTIAL -> "部分失败"
            ImportBatch.FAILED -> "失败"
            ImportBatch.CANCELLED -> "已取消"
            else -> status
        }
}

/** 跑完一批书的计数 */
data class BatchResult(val done: Int, val failed: Int, val skipped: Int)

/** 上次进程被杀时没导完的一批 */
data class InterruptedBatch(
    val batchId: String,
    val count: Int,
    val titles: List<String>
)

/** 队列里的一本 */
private class QueueItem(
    val taskId: Long,
    val title: String,
    val uri: Uri?,
    val key: String?,
    /** 见 [BatchSpec.deep]：true 时用 ContentResolver 递归读，而不是父树直接子项 */
    val deep: Boolean = false
)

/** 导出到相册的进度 */
data class SaveState(
    val title: String,
    val done: Int,
    val total: Int
)

/** 备份 / 恢复的进度（total = 0 表示进度不可知） */
data class BackupState(
    val label: String,
    val done: Int,
    val total: Int
)

@OptIn(FlowPreview::class)
class ShelfViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = (application as MangaShelfApp).repository
    private val context = application.applicationContext

    private val prefs = application.getSharedPreferences("shelf", Context.MODE_PRIVATE)

    private val _order = MutableStateFlow(
        ShelfOrder.fromStorage(prefs.getString(KEY_ORDER, null))
    )
    /** 当前书架排序（持久化，重进 App 保持） */
    val order: StateFlow<ShelfOrder> = _order.asStateFlow()

    private val _queryInput = MutableStateFlow("")

    /**
     * 书架搜索词。只放内存、不落盘：下次打开 App 看到的是完整书架。
     *
     * v1.5.5 起带 250ms 防抖：书多了以后每敲一个字就全表过滤（还要重排）会明显卡输入。
     * 防抖放在 ViewModel 而不是输入框，这样"清空搜索"也只是多等 250ms，不会漏掉刷新。
     */
    val query: StateFlow<String> = _queryInput
        .debounce(300)
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val books: StateFlow<List<Book>> = combine(
        repo.observeBooks(),
        _order,
        query
    ) { list, order, keyword ->
        val sorted = order.apply(list)
        val key = keyword.trim()
        if (key.isEmpty()) {
            sorted
        } else {
            // v1.5.5：书名之外再匹配作者、标签、文件夹名。
            // 中文不分大小写，英文/数字 ignoreCase，partial 匹配（contains）符合直觉。
            sorted.filter {
                it.title.contains(key, ignoreCase = true) ||
                    it.author.contains(key, ignoreCase = true) ||
                    it.tags.contains(key, ignoreCase = true) ||
                    it.folder.contains(key, ignoreCase = true)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 书架顶部「继续阅读」用：最近读过、且还没翻到最后一页的那本。
     * 搜索时不动它——搜索是在找别的书，顶部再挂一张"继续上次"的卡片只会碍事。
     */
    val continueBook: StateFlow<Book?> = combine(repo.observeBooks(), query) { list, keyword ->
        if (keyword.isNotBlank()) return@combine null
        list.asSequence()
            .filter { it.lastReadAt > 0L && it.pageCount > 1 && it.lastPage < it.pageCount - 1 }
            .maxByOrNull { it.lastReadAt }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 边打字边过滤，不需要额外点搜索按钮 */
    fun setQuery(keyword: String) {
        _queryInput.value = keyword
    }

    private val _importState = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val importState: StateFlow<ImportUiState> = _importState.asStateFlow()

    /** v1.5.6：当前批次队列（多文件夹导入时每项一批） */
    private val _batchRows = MutableStateFlow<List<BatchRow>>(emptyList())
    val batchRows: StateFlow<List<BatchRow>> = _batchRows.asStateFlow()

    /** v1.5.6：导入历史（import_batches 表，最近 50 批） */
    private val _history = MutableStateFlow<List<ImportBatch>>(emptyList())
    val history: StateFlow<List<ImportBatch>> = _history.asStateFlow()

    /** 导出到相册的进度（null = 没有在导出） */
    private val _saveState = MutableStateFlow<SaveState?>(null)
    val saveState: StateFlow<SaveState?> = _saveState.asStateFlow()

    /** 一次性提示（Toast），UI 消费后调 [clearMessage] */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /**
     * v1.5.6：批量操作门闩。
     *
     * 为什么需要：删除/存相册/打标签都是「对一批书挨个动手」，用户连点两下按钮会并发跑两份，
     * 结果是同一个文件删两次、同一本书加两遍标签。用 CAS 抢锁，抢不到直接忽略这次点击。
     */
    private val batchLatch = java.util.concurrent.atomic.AtomicBoolean(false)

    /** v1.5.6：导入历史（全部批次，含已结束的） */
    val batchHistory: StateFlow<List<ImportBatch>> = repo.observeBatches()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** v1.5.6：把导入历史导出成 CSV，成功后用 Toast 告诉用户存哪了 */
    fun exportReport(context: android.content.Context) {
        viewModelScope.launch {
            val where = BatchReportExporter.export(context, batchHistory.value)
            _message.value = if (where != null) "导入报告已保存到\n$where" else "导出失败，请稍后再试"
        }
    }

    /** 备份 / 恢复进度 */
    private val _backupState = MutableStateFlow<BackupState?>(null)
    val backupState: StateFlow<BackupState?> = _backupState.asStateFlow()

    /**
     * 整库备份到用户选的文件（zip，含图片）。
     *
     * 这是"卸载重装后能拿回来"的本地方案：不依赖账号和服务器，
     * 用户把 zip 放网盘/电脑，换机再导回来。
     */
    fun exportBackup(target: Uri) {
        if (_backupState.value != null) return
        viewModelScope.launch {
            _backupState.value = BackupState("正在打包", 0, 0)
            runCatching {
                repo.exportBackup(context, target) { done, total ->
                    _backupState.value = BackupState("正在打包", done, total)
                }
            }.onSuccess { r ->
                val mb = r.bytes / 1024.0 / 1024.0
                _message.value = "备份完成：${r.books} 本 / ${r.pages} 页，%.1f MB".format(mb)
            }.onFailure { t ->
                _message.value = "备份失败：${t.message ?: "未知错误"}"
            }
            _backupState.value = null
        }
    }

    /** 从备份 zip 恢复（图片一起恢复，书名重复也不覆盖现有书） */
    fun restoreBackup(source: Uri) {
        if (_backupState.value != null) return
        viewModelScope.launch {
            _backupState.value = BackupState("正在恢复", 0, 0)
            runCatching {
                repo.restoreBackup(context, source) { done, total ->
                    _backupState.value = BackupState("正在恢复", done, total)
                }
            }.onSuccess { r ->
                _message.value = buildString {
                    append("恢复完成：${r.books} 本 / ${r.pages} 页")
                    if (r.skipped > 0) append("，${r.skipped} 本缺文件已跳过")
                }
            }.onFailure { t ->
                _message.value = "恢复失败：${t.message ?: "文件不是有效备份"}"
            }
            _backupState.value = null
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    private val _interrupted = MutableStateFlow<InterruptedBatch?>(null)
    /** 上次导入被系统打断时，书架顶部显示的提示 */
    val interrupted: StateFlow<InterruptedBatch?> = _interrupted.asStateFlow()

    private var importJob: Job? = null
    private val paused = MutableStateFlow(false)

    /**
     * 封面缓存：v5 起封面可能是任意一页（「设为封面」），路径要查数据库，
     * 不能在 composition 里同步查，所以在这里批量预热，UI 只读缓存。
     */
    private val _covers = MutableStateFlow<Map<Long, String>>(emptyMap())
    val covers: StateFlow<Map<Long, String>> = _covers.asStateFlow()

    /** 封面质量（v1.5.5）：默认缩略图，切换后立刻重算整张封面表 */
    private val _coverQuality = MutableStateFlow(CoverPrefs.load(getApplication()))
    val coverQuality: StateFlow<CoverQuality> = _coverQuality.asStateFlow()

    fun setCoverQuality(quality: CoverQuality) {
        CoverPrefs.save(getApplication(), quality)
        _coverQuality.value = quality
    }

    private fun refreshCovers() {
        viewModelScope.launch {
            try {
                combine(repo.observeBooks(), _coverQuality) { list, quality -> list to quality }
                    .collectLatest { (list, quality) ->
                        val map = list.associate { it.id to repo.coverPathOf(it, quality) }
                        _covers.value = map
                    }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                // 封面表只决定书架上的缩略图路径，查不出来最多显示占位图，
                // 绝不能因为它把整个书架（以及 App 启动）带崩。
                _covers.value = emptyMap()
            }
        }
    }

    fun coverOf(bookId: Long): String = _covers.value[bookId] ?: ""

    /**
     * v1.5.5 修复：启动预取必须放在所有属性声明之后。
     *
     * 原来这段 init 写在文件中部（`_covers` / `_coverQuality` 声明之前），
     * Kotlin 按源码顺序执行初始化，init 先跑时 `_coverQuality` 还是 JVM 默认值 null，
     * `combine()` 内部对 null 调 `Flow.collect` → 冷启动必崩：
     * `ShelfViewModel.<init> → refreshCovers → CombineKt$combineInternal$2$1.invokeSuspend(Combine.kt:31)`。
     */
    init {
        // v1.5.6：通知栏的「暂停/继续」通过 ImportControl 转进来
        ImportControl.onPause = { pauseImport() }
        ImportControl.onResume = { resumeImport() }
        ImportControl.paused = false

        checkInterrupted()
        refreshCovers()
    }

    /**
     * 切到某个排序：点的是当前排序就翻转方向，点别的就换成它并重置为该排序的默认方向。
     * 结果立刻存盘，重进 App、杀进程都不会丢。
     */
    fun setSort(sort: ShelfSort) {
        val current = _order.value
        val next = if (current.sort == sort) {
            current.copy(asc = !current.asc)
        } else {
            ShelfOrder(sort, sort.defaultAsc)
        }
        _order.value = next
        prefs.edit().putString(KEY_ORDER, next.storageKey()).apply()
    }

    // ------------------------------------------------------------------
    // 单本导入
    // ------------------------------------------------------------------

    fun importPdf(uri: Uri) {
        val title = FileNames.displayName(context, uri)
            ?.let { FileNames.stripExtension(it) }
            ?: "未命名漫画"
        startImport(
            title = title,
            sourceKey = documentIdOf(uri),
            sourceType = "PDF",
            estimate = { StorageGuard.forPdf(PdfImporter.pageCount(context, uri)) }
        ) { dir, progress ->
            PdfImporter.import(context, uri, dir, onProgress = progress)
        }
    }

    fun importImages(
        uris: List<Uri>,
        sortMode: PageSortMode = ImportSortPrefs.imageMode(context),
        sortAsc: Boolean = ImportSortPrefs.imageAsc(context)
    ) {
        if (uris.isEmpty()) return
        val title = if (uris.size == 1) {
            FileNames.displayName(context, uris.first())
                ?.let { FileNames.stripExtension(it) }
                ?: "未命名漫画"
        } else {
            val stamp = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
            "图片漫画 $stamp"
        }
        startImport(
            title = title,
            sourceType = "IMAGE",
            estimate = { StorageGuard.forImages(uris.size) },
            sortMode = sortMode,
            sortAsc = sortAsc
        ) { dir, progress ->
            ImageImporter.import(context, uris, dir, progress)
        }
    }

    /** CBZ / ZIP 漫画包 = 一本书（压缩包里的图片按名字自然排序成页） */
    fun importArchive(uri: Uri) {
        val title = FileNames.displayName(context, uri)
            ?.let { FileNames.stripExtension(it) }
            ?: "未命名漫画"
        startImport(
            title = title,
            sourceKey = documentIdOf(uri),
            sourceType = "CBZ",
            estimate = { StorageGuard.forUnknown() }
        ) { dir, progress ->
            ArchiveImporter.import(context, uri, dir, progress)
        }
    }

    /** 整个文件夹 = 一本书（递归子目录，排序规则可在导入前临时指定） */
    fun importFolder(
        treeUri: Uri,
        sortMode: PageSortMode = ImportSortPrefs.folderMode(context),
        sortAsc: Boolean = ImportSortPrefs.folderAsc(context)
    ) {
        val folderName = runCatching {
            DocumentFile.fromTreeUri(context, treeUri)?.name
        }.getOrNull()
        val title = folderName?.trim().orEmpty().ifBlank { "未命名漫画" }
        startImport(
            title = title,
            sourceKey = documentIdOf(treeUri),
            sourceType = "IMAGE",
            sourceTreeUri = treeUri.toString(),
            estimate = { StorageGuard.forUnknown() },
            sortMode = sortMode,
            sortAsc = sortAsc
        ) { dir, progress ->
            FolderImporter.import(context, treeUri, dir, progress)
        }
    }

    // ------------------------------------------------------------------
    // 批量导入（父文件夹下每个子文件夹 = 一本书）
    // ------------------------------------------------------------------

    /**
     * 队列式批量导入：
     * - 每本书在 import_tasks 里有一行记录，进程被杀后下次启动能说清楚"上次差几本"
     * - 一次一本，导完这本就入库（中途取消已导入的书不丢）
     * - 已导入过的（sourceKey 相同）自动跳过，可以断点续传
     * - 两本书之间可以暂停/继续
     */
    fun importBatch(parentTreeUri: Uri) {
        if (isBusy()) return
        importJob = viewModelScope.launch {
            val children = withContext(Dispatchers.IO) {
                FolderImporter.listChildFolders(context, parentTreeUri)
            }
            if (children.isEmpty()) {
                _importState.value = ImportUiState.Failed(
                    "这个文件夹里没有子文件夹。\n\n" +
                        "批量导入需要「父目录 / 每本一个子目录」的结构（比如 海贼王/第001卷、海贼王/第002卷）。\n" +
                        "如果图片是直接放在这个目录里的，请改用「选择文件夹」。"
                )
                return@launch
            }

            val batchId = newBatchId()
            val now = System.currentTimeMillis()
            val ids = runCatching {
                repo.enqueueTasks(
                    children.mapIndexed { index, child ->
                        ImportTask(
                            batchId = batchId,
                            title = child.name,
                            sourceKey = child.key,
                            // 存父树：子目录的 tree URI 没有 SAF 授权，重试时会打不开
                            sourceTreeUri = parentTreeUri.toString(),
                            sourceType = "IMAGE",
                            sortIndex = index,
                            status = ImportTask.PENDING,
                            error = null,
                            createdAt = now,
                            updatedAt = now
                        )
                    }
                )
            }.getOrElse { emptyList() }

            val items = children.mapIndexed { index, child ->
                QueueItem(
                    taskId = ids.getOrNull(index) ?: -1L,
                    title = child.name,
                    uri = parentTreeUri,
                    key = child.key
                )
            }
            runBatch(batchId, items)
        }
    }

    /**
     * v1.5.6：多文件夹分批导入。
     *
     * 每选一个文件夹就是一个批次（编号 + 名称 + 书册数 + 状态），批次之间顺序执行：
     * 可以跳过某一批、也可以只重试失败的那一批。批次记录落 import_batches 表，
     * 进程被杀后靠它恢复 / 说清楚失败原因。
     */
    fun importFolders(uris: List<Uri>) {
        if (isBusy()) return
        _batchRows.value = emptyList()
        importJob = viewModelScope.launch {
            val specs = ArrayList<BatchSpec>()
            for ((index, uri) in uris.withIndex()) {
                val children = withContext(Dispatchers.IO) {
                    runCatching { FolderImporter.listChildFolders(context, uri) }
                        .getOrDefault(emptyList())
                }
                if (children.isEmpty()) continue
                val name = withContext(Dispatchers.IO) {
                    runCatching { DocumentFile.fromTreeUri(context, uri)?.name }.getOrNull()
                } ?: "文件夹 ${index + 1}"
                specs += BatchSpec(newBatchId(), name, uri, children)
            }
            if (specs.isEmpty()) {
                _importState.value = ImportUiState.Failed(
                    "选中的文件夹里没有可导入的内容。\n\n" +
                        "批量导入要求「父目录 / 每本一个子目录」的结构。"
                )
                return@launch
            }
            _batchRows.value = specs.map {
                BatchRow(it.batchId, it.name, it.children.size, 0, 0, 0, ImportBatch.QUEUED)
            }
            runBatches(specs)
        }
    }

    /**
     * v1.5.7：App 内文件页选中的若干文件夹，每个文件夹 = 一个批次（该文件夹内的图片就是一本书）。
     *
     * 和 [importFolders] 的区别：那里每个 uri 是「父目录」，里面每个子目录是一本；
     * 这里每个 docId 是用户已经进到位的那一层目录本身，直接当一本书导。
     * 因为 docId 可能不是根树的直接子项，必须带 [BatchSpec.deep]。
     */
    fun importDocs(rootTreeUri: Uri, docs: List<Pair<String, String>>) {
        if (isBusy()) return
        if (docs.isEmpty()) return
        _batchRows.value = emptyList()
        importJob = viewModelScope.launch {
            val specs = docs.map { (docId, name) ->
                BatchSpec(
                    batchId = newBatchId(),
                    name = name,
                    sourceTreeUri = rootTreeUri,
                    children = listOf(FolderImporter.ChildFolder(name, docId)),
                    deep = true
                )
            }
            _batchRows.value = specs.map {
                BatchRow(it.batchId, it.name, it.children.size, 0, 0, 0, ImportBatch.QUEUED)
            }
            runBatches(specs)
        }
    }

    /** 顺序跑完整个批次队列；每批结束回写终态，批次之间互不影响 */
    private suspend fun runBatches(specs: List<BatchSpec>) {        var importedTotal = 0
        var failedTotal = 0
        var skippedTotal = 0
        for (spec in specs) {
            val row = _batchRows.value.firstOrNull { it.batchId == spec.batchId } ?: continue
            // 用户在这批开始前就点了「跳过」
            if (row.status == ImportBatch.CANCELLED) continue

            val startedAt = System.currentTimeMillis()
            val now = System.currentTimeMillis()
            val ids = runCatching {
                repo.enqueueTasks(
                    spec.children.mapIndexed { index, child ->
                        ImportTask(
                            batchId = spec.batchId,
                            title = child.name,
                            sourceKey = child.key,
                            sourceTreeUri = spec.sourceTreeUri.toString(),
                            sourceType = "IMAGE",
                            sortIndex = index,
                            status = ImportTask.PENDING,
                            error = null,
                            createdAt = now,
                            updatedAt = now
                        )
                    }
                )
            }.getOrElse { emptyList() }

            runCatching {
                repo.startBatch(
                    batchId = spec.batchId,
                    sourceType = "IMAGE",
                    rootTreeUri = spec.sourceTreeUri.toString(),
                    totalBooks = spec.children.size,
                    totalPages = 0
                )
            }
            updateRow(spec.batchId) { it.copy(status = ImportBatch.RUNNING) }

            val items = spec.children.mapIndexed { index, child ->
                QueueItem(
                    taskId = ids.getOrNull(index) ?: -1L,
                    title = child.name,
                    uri = spec.sourceTreeUri,
                    key = child.key,
                    deep = spec.deep
                )
            }

            try {
                val result = runBatch(
                    batchId = spec.batchId,
                    items = items,
                    keepService = true,
                    suppressFinished = true
                ) { r ->
                    updateRow(spec.batchId) {
                        it.copy(doneBooks = r.done, failedBooks = r.failed, skippedBooks = r.skipped)
                    }
                    viewModelScope.launch {
                        runCatching { repo.batchProgress(spec.batchId, r.done, 0) }
                    }
                }
                importedTotal += result.done
                failedTotal += result.failed
                skippedTotal += result.skipped

                val terminal = ImportStatusMachine.terminalOf(result.done, result.failed, result.skipped)
                updateRow(spec.batchId) {
                    it.copy(
                        doneBooks = result.done,
                        failedBooks = result.failed,
                        skippedBooks = result.skipped,
                        status = terminal
                    )
                }
                runCatching {
                    repo.finishBatch(
                        batchId = spec.batchId,
                        done = result.done,
                        failed = result.failed,
                        skipped = result.skipped,
                        totalPages = 0,
                        donePages = 0,
                        sizeBytes = 0L,
                        elapsedMs = System.currentTimeMillis() - startedAt,
                        failReason = null
                    )
                }
            } catch (ce: CancellationException) {
                markBatchCancelled(spec.batchId)
                throw ce
            }
        }
        _importState.value = ImportUiState.Finished(
            imported = importedTotal,
            skipped = skippedTotal,
            failed = failedTotal,
            cancelled = false
        )
        refreshHistory()
    }

    private fun updateRow(batchId: String, block: (BatchRow) -> BatchRow) {
        _batchRows.value = _batchRows.value.map { if (it.batchId == batchId) block(it) else it }
    }

    private fun markBatchCancelled(batchId: String) {
        updateRow(batchId) { it.copy(status = ImportBatch.CANCELLED) }
        viewModelScope.launch { runCatching { repo.batchStatus(batchId, ImportBatch.CANCELLED) } }
    }

    /** 跳过队列里还没开始的一批（正在跑的那批要先取消整个导入） */
    fun skipBatch(batchId: String) {
        val row = _batchRows.value.firstOrNull { it.batchId == batchId } ?: return
        if (row.status == ImportBatch.RUNNING) {
            _message.value = "这批正在导入，要跳过请先取消整个导入"
            return
        }
        if (ImportStatusMachine.isTerminal(row.status)) return
        updateRow(batchId) { it.copy(status = ImportBatch.CANCELLED) }
        viewModelScope.launch { runCatching { repo.batchStatus(batchId, ImportBatch.CANCELLED) } }
    }

    /**
     * 只重试失败的那一批。
     *
     * 失败列表从 import_tasks 取（FAILED / PENDING），已成功的书不会再导一遍，
     * 所以重试是增量的，不会产生重复书。
     */
    fun retryBatchRow(row: BatchRow) {
        if (isBusy()) return
        importJob = viewModelScope.launch {
            val tasks = runCatching { repo.tasksOfBatch(row.batchId) }.getOrDefault(emptyList())
                .filter { it.status == ImportTask.FAILED || it.status == ImportTask.PENDING }
            if (tasks.isEmpty()) {
                _message.value = "这一批没有可重试的书"
                return@launch
            }
            runCatching { repo.resetFailed(row.batchId) }
            updateRow(row.batchId) { it.copy(status = ImportBatch.RUNNING) }
            runCatching { repo.batchStatus(row.batchId, ImportBatch.RUNNING) }
            val startedAt = System.currentTimeMillis()
            val result = runBatch(
                batchId = row.batchId,
                items = tasks.map {
                    QueueItem(
                        taskId = it.id,
                        title = it.title,
                        uri = it.sourceTreeUri?.let { raw -> runCatching { Uri.parse(raw) }.getOrNull() },
                        key = it.sourceKey
                    )
                }
            ) { r ->
                updateRow(row.batchId) { it.copy(doneBooks = r.done, failedBooks = r.failed) }
            }
            updateRow(row.batchId) {
                it.copy(
                    doneBooks = result.done,
                    failedBooks = result.failed,
                    skippedBooks = result.skipped,
                    status = ImportStatusMachine.terminalOf(result.done, result.failed, result.skipped)
                )
            }
            runCatching {
                repo.finishBatch(
                    batchId = row.batchId,
                    done = result.done,
                    failed = result.failed,
                    skipped = result.skipped,
                    totalPages = 0,
                    donePages = 0,
                    sizeBytes = 0L,
                    elapsedMs = System.currentTimeMillis() - startedAt,
                    failReason = null
                )
            }
            refreshHistory()
        }
    }

    /** 打开导入历史页时刷新一次 */
    fun refreshHistory() {
        viewModelScope.launch {
            _history.value = runCatching {
                repo.historyPage(since = 0L, status = "", sourceType = "", limit = 50, offset = 0)
            }.getOrDefault(emptyList())
        }
    }

    // ------------------------------------------------------------------
    // v1.5.6：书架多选与批量操作
    // ------------------------------------------------------------------

    /** 多选模式开关（进入模式后可以是空集，等用户点书） */
    private val _selectMode = MutableStateFlow(false)
    val selectMode: StateFlow<Boolean> = _selectMode.asStateFlow()

    /** 多选模式里选中的书 id */
    private val _selection = MutableStateFlow<Set<Long>>(emptySet())
    val selection: StateFlow<Set<Long>> = _selection.asStateFlow()

    fun enterSelectMode() {
        _selectMode.value = true
    }

    fun exitSelectMode() {
        _selectMode.value = false
        _selection.value = emptySet()
    }

    fun toggleSelect(bookId: Long) {
        _selection.value = _selection.value.let { cur ->
            if (cur.contains(bookId)) cur - bookId else cur + bookId
        }
    }

    fun selectAll() {
        _selection.value = books.value.map { it.id }.toSet()
    }

    fun clearSelection() {
        _selection.value = emptySet()
    }

    /**
     * 批量删除。每本单独走 [BookRepository.delete]（它会顺带清 pages 行和图片目录），
     * 一本失败不影响其它本；最后按成功数给提示。
     */
    fun deleteSelected() {
        val ids = _selection.value
        if (ids.isEmpty()) return
        if (!batchLatch.compareAndSet(false, true)) return
        val targets = books.value.filter { ids.contains(it.id) }
        viewModelScope.launch {
            try {
                var ok = 0
                var bad = 0
                for (book in targets) {
                    runCatching { repo.delete(book) }
                        .onSuccess { ok++ }
                        .onFailure { bad++ }
                }
                _selection.value = emptySet()
                _selectMode.value = false
                _message.value = if (bad == 0) {
                    "已删除 $ok 本"
                } else {
                    "已删除 $ok 本，$bad 本失败"
                }
            } finally {
                batchLatch.set(false)
            }
        }
    }

    /** 批量导出封面到相册（只导第一页，避免一次几千张） */
    fun saveSelectedToGallery() {
        val ids = _selection.value
        if (ids.isEmpty()) return
        if (_saveState.value != null) return
        if (!batchLatch.compareAndSet(false, true)) return
        val targets = books.value.filter { ids.contains(it.id) }
        viewModelScope.launch {
            var ok = 0
            _saveState.value = SaveState("选中的 ${targets.size} 本", 0, targets.size)
            for ((index, book) in targets.withIndex()) {
                _saveState.value = SaveState("选中的 ${targets.size} 本", index, targets.size)
                val files = runCatching { repo.pageFiles(book) }.getOrDefault(emptyList())
                if (files.isEmpty()) continue
                runCatching { GalleryExporter.export(context, book.title, files.take(1)) { _, _ -> } }
                    .onSuccess { if (it > 0) ok++ }
            }
            _saveState.value = null
            _selection.value = emptySet()
            _selectMode.value = false
            _message.value = "已保存 $ok 本封面到相册"
            batchLatch.set(false)
        }
    }

    /** 批量打标签（分类），标签会追加到已有标签后面 */
    fun tagSelected(tag: String) {
        val ids = _selection.value
        val clean = tag.trim()
        if (ids.isEmpty() || clean.isEmpty()) return
        if (!batchLatch.compareAndSet(false, true)) return
        val targets = books.value.filter { ids.contains(it.id) }
        viewModelScope.launch {
            try {
                var ok = 0
                for (book in targets) {
                    val merged = if (book.tags.isBlank()) clean else "${book.tags}, $clean"
                    runCatching { repo.updateMeta(book.id, book.author, merged) }.onSuccess { ok++ }
                }
                _selection.value = emptySet()
                _selectMode.value = false
                _message.value = "已给 $ok 本加上标签「$clean」"
            } finally {
                batchLatch.set(false)
            }
        }
    }

    fun clearImportHistory() {
        viewModelScope.launch {
            runCatching { repo.clearHistory() }
            _history.value = emptyList()
            _message.value = "导入历史已清空（已导入的书不受影响）"
        }
    }

    /** 重试上次被中断的一批 */
    fun retryInterrupted() {
        val info = _interrupted.value ?: return
        if (isBusy()) return
        _interrupted.value = null
        importJob = viewModelScope.launch {
            val tasks = runCatching { repo.tasksOfBatch(info.batchId) }.getOrDefault(emptyList())
                .filter {
                    it.status == ImportTask.FAILED ||
                        it.status == ImportTask.PENDING ||
                        it.status == ImportTask.RUNNING
                }
            if (tasks.isEmpty()) return@launch

            runCatching { repo.resetFailed(info.batchId) }
            runBatch(
                info.batchId,
                tasks.map {
                    QueueItem(
                        taskId = it.id,
                        title = it.title,
                        uri = it.sourceTreeUri?.let { raw -> runCatching { Uri.parse(raw) }.getOrNull() },
                        key = it.sourceKey
                    )
                }
            )
        }
    }

    /** 选择不重试：把这批标记为失败，提示不再出现 */
    fun dismissInterrupted() {
        val info = _interrupted.value ?: return
        _interrupted.value = null
        viewModelScope.launch {
            runCatching { repo.failUnfinished(info.batchId, "用户跳过了上次未完成的导入") }
        }
    }

    /** 收尾弹窗里点「重试失败项」 */
    fun retryFailedOf(state: ImportUiState.Finished) {
        val batchId = state.batchId ?: return
        _importState.value = ImportUiState.Idle
        viewModelScope.launch {
            val tasks = runCatching { repo.tasksOfBatch(batchId) }.getOrDefault(emptyList())
                .filter { it.status == ImportTask.FAILED || it.status == ImportTask.PENDING }
            if (tasks.isEmpty()) return@launch
            runCatching { repo.resetFailed(batchId) }
            runBatch(
                batchId,
                tasks.map {
                    QueueItem(it.id, it.title, it.sourceTreeUri?.let { raw -> Uri.parse(raw) }, it.sourceKey)
                }
            )
        }
    }

    fun pauseImportFromNotification() = pauseImport()

    fun resumeImportFromNotification() = resumeImport()

    fun pauseImport() {
        if (importJob?.isActive != true) return
        paused.value = true
        ImportControl.paused = true
        // 通知栏也换文案和按钮，否则用户在通知栏看不出已经停了
        ImportForegroundService.update("已暂停 · 点「继续」接着导", true)
    }

    fun resumeImport() {
        paused.value = false
        ImportControl.paused = false
        ImportForegroundService.update("正在导入漫画…", false)
    }

    fun cancelImport() {
        importJob?.cancel()
    }

    override fun onCleared() {
        // v1.5.6：ViewModel 没了就摘掉通知栏回调，别让 Service 拽着已销毁的实例
        ImportControl.onPause = null
        ImportControl.onResume = null
        super.onCleared()
    }

    fun dismissImportDialog() {
        when (_importState.value) {
            is ImportUiState.Running, is ImportUiState.Batch -> Unit
            else -> _importState.value = ImportUiState.Idle
        }
    }

    private fun isBusy(): Boolean =
        importJob?.isActive == true ||
            _importState.value is ImportUiState.Running ||
            _importState.value is ImportUiState.Batch

    /** 两本书之间停一下，直到用户点「继续」 */
    private suspend fun awaitResume(index: Int, total: Int, skipped: Int) {
        while (paused.value) {
            _importState.value = ImportUiState.Batch(
                index = index + 1,
                total = total,
                title = "已暂停",
                done = 0,
                totalPages = 0,
                skipped = skipped,
                paused = true
            )
            delay(150)
        }
    }

    /**
     * 跑一批书。首次导入和"重试上次中断的"走同一条路，保证行为一致。
     *
     * 每本的状态都会写回 import_tasks：DONE / FAILED(带原因)。
     * 进程在这一步被杀，下次启动就能靠这些行说清楚差在哪。
     */
    private suspend fun runBatch(
        batchId: String,
        items: List<QueueItem>,
        keepService: Boolean = false,
        suppressFinished: Boolean = false,
        onBookFinished: ((BatchResult) -> Unit)? = null
    ): BatchResult {
        var imported = 0
        var skipped = 0
        var failed = 0
        var currentDir: File? = null
        paused.value = false
        ImportForegroundService.start(context, "正在导入（共 ${items.size} 本）")

        try {
            for ((index, item) in items.withIndex()) {
                // 批次级进度：每本书开始前把上一本的结果交给调用方（界面/数据库）
                onBookFinished?.invoke(BatchResult(imported, failed, skipped))
                awaitResume(index, items.size, skipped)

                // 断点续传：同一本已经导过就不再重复导入
                if (item.key != null && repo.findBySourceKey(item.key) != null) {
                    skipped++
                    if (item.taskId > 0) repo.taskStatus(item.taskId, ImportTask.DONE)
                    continue
                }

                // 每本开始前再查一次空间：一本动辄上百 MB，中途耗尽会白等一整本
                StorageGuard.check(context, StorageGuard.forUnknown())?.let { message ->
                    if (item.taskId > 0) {
                        repo.taskStatus(item.taskId, ImportTask.FAILED, "存储空间不足")
                    }
                    runCatching { repo.cancelPending(batchId) }
                    _importState.value = ImportUiState.Failed(
                        message + "\n\n已导入 $imported 本，剩下的没有继续。"
                    )
                    return BatchResult(imported, failed, skipped)
                }

                val uri = item.uri
                if (uri == null) {
                    failed++
                    if (item.taskId > 0) repo.taskStatus(item.taskId, ImportTask.FAILED, "来源已失效")
                    continue
                }

                // 批量导入靠 DocumentId 在父树里定位子文件夹
                val childKey = item.key
                if (childKey == null) {
                    failed++
                    if (item.taskId > 0) repo.taskStatus(item.taskId, ImportTask.FAILED, "来源已失效")
                    continue
                }

                if (item.taskId > 0) repo.taskStatus(item.taskId, ImportTask.RUNNING)

                val folder = repo.storage.newFolder()
                val dir = repo.storage.dirOf(folder)
                currentDir = dir

                val pages = try {
                    withContext(Dispatchers.IO) {
                        val progress: (Int, Int) -> Unit = { done, total ->
                            _importState.value = ImportUiState.Batch(
                                index = index + 1,
                                total = items.size,
                                title = item.title,
                                done = done,
                                totalPages = total,
                                skipped = skipped,
                                paused = paused.value
                            )
                            ImportForegroundService.update("正在导入《${item.title}》 $done/$total")
                        }
                        if (item.deep) {
                            // 文件页深进多层选的目录：docId 不是父树的直接子项，只能走 resolver
                            FolderImporter.importDoc(context, uri, childKey, dir, progress)
                        } else {
                            FolderImporter.importChild(context, uri, childKey, dir, progress)
                        }
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    withContext(NonCancellable + Dispatchers.IO) { dir.deleteRecursively() }
                    currentDir = null
                    failed++
                    if (item.taskId > 0) {
                        repo.taskStatus(item.taskId, ImportTask.FAILED, t.message ?: "导入失败")
                    }
                    continue
                }

                currentDir = null
                if (pages.isEmpty()) {
                    withContext(Dispatchers.IO) { dir.deleteRecursively() }
                    failed++
                    if (item.taskId > 0) {
                        repo.taskStatus(item.taskId, ImportTask.FAILED, "没有读到任何页面")
                    }
                    continue
                }

                runCatching {
                    val book = Book(
                        title = item.title,
                        folder = folder,
                        pageCount = pages.size,
                        sourceKey = item.key,
                        sourceTreeUri = uri.toString(),
                        sourceType = "IMAGE"
                    )
                    val bookId = repo.insertBook(book, pages)
                    // 批量导入按「文件夹默认排序」统一处理，用户不必一本本调
                    val mode = ImportSortPrefs.folderMode(context)
                    if (mode != PageSortMode.FIXED) {
                        runCatching { repo.applyPageOrder(book.copy(id = bookId), mode, ImportSortPrefs.folderAsc(context)) }
                    }
                    bookId
                }.onSuccess {
                    imported++
                    if (item.taskId > 0) repo.taskStatus(item.taskId, ImportTask.DONE)
                }.onFailure { t ->
                    // 入库失败：目录已经写完，必须删掉，否则就是孤儿
                    withContext(NonCancellable + Dispatchers.IO) { dir.deleteRecursively() }
                    failed++
                    if (item.taskId > 0) {
                        repo.taskStatus(item.taskId, ImportTask.FAILED, t.message ?: "写入书架失败")
                    }
                }
            }

            runCatching { repo.cancelPending(batchId) }
            if (!suppressFinished) {
                _importState.value = ImportUiState.Finished(
                    imported = imported,
                    skipped = skipped,
                    failed = failed,
                    cancelled = false,
                    batchId = batchId
                )
            }
        } catch (ce: CancellationException) {
            currentDir?.let { dir ->
                withContext(NonCancellable + Dispatchers.IO) { dir.deleteRecursively() }
            }
            runCatching { repo.cancelPending(batchId) }
            _importState.value = ImportUiState.Finished(
                imported = imported,
                skipped = skipped,
                failed = failed,
                cancelled = true,
                batchId = batchId
            )
            throw ce
        } catch (t: Throwable) {
            runCatching { repo.cancelPending(batchId) }
            if (!suppressFinished) {
                _importState.value = ImportUiState.Failed(t.message ?: "批量导入失败")
            }
        } finally {
            paused.value = false
            // 多批次连续导入时服务由外层统一管，这里不能停，否则第二本开始前前台服务就没了
            if (!keepService) ImportForegroundService.stop(context)
        }
        return BatchResult(imported, failed, skipped)
    }

    // ------------------------------------------------------------------

    fun deleteBook(book: Book) {
        viewModelScope.launch { repo.delete(book) }
    }

    /**
     * 把书页导出到相册。
     *
     * [allPages] 为 false 时只导封面（第一页），避免用户手滑把几百页塞满相册。
     * 单张失败不中断，最后按成功张数给提示。
     */
    fun saveToGallery(book: Book, allPages: Boolean) {
        if (_saveState.value != null) return
        viewModelScope.launch {
            val files = runCatching { repo.pageFiles(book) }.getOrDefault(emptyList())
            val chosen = if (allPages) files else files.take(1)
            if (chosen.isEmpty()) {
                _message.value = "这本书没有可导出的页面"
                return@launch
            }
            _saveState.value = SaveState(book.title, 0, chosen.size)
            val result = runCatching {
                GalleryExporter.export(context, book.title, chosen) { done, total ->
                    _saveState.value = SaveState(book.title, done, total)
                }
            }
            _saveState.value = null
            result.onSuccess { saved ->
                _message.value = if (saved > 0) {
                    "已保存 $saved 张到相册 Pictures/MangaShelf"
                } else {
                    "保存失败：相册写入被拒绝，检查存储空间或权限"
                }
            }.onFailure { t ->
                _message.value = "保存失败：${t.message ?: "未知错误"}"
            }
        }
    }

    fun renameBook(book: Book, title: String) {
        viewModelScope.launch { repo.rename(book.id, title) }
    }

    /** v1.5.5：编辑作者与标签（只影响搜索命中，不改其它任何状态） */
    fun updateBookMeta(book: Book, author: String, tags: String) {
        viewModelScope.launch {
            repo.updateMeta(book.id, author, tags)
            _message.value = "已保存「${book.title}」的信息"
        }
    }

    fun toggleDirection(book: Book) {
        viewModelScope.launch { repo.setRtl(book.id, !book.rtl) }
    }

    /** v1.5.5 需求三：左右翻页 / 上下滚动 / 双页并排，按书保存 */
    fun setReadMode(book: Book, mode: String) {
        viewModelScope.launch { runCatching { repo.setReadMode(book.id, mode) } }
    }

    /**
     * 按新的页序规则重排这本书。
     *
     * 只改 `pages.idx`，不重命名、不移动文件——所以改错了再改回来也不会有损失。
     */
    fun reorderPages(book: Book, mode: PageSortMode, asc: Boolean) {
        viewModelScope.launch {
            runCatching { repo.applyPageOrder(book, mode, asc) }
                .onFailure { _importState.value = ImportUiState.Failed(it.message ?: "重排页面失败") }
        }
    }

    private fun startImport(
        title: String,
        sourceKey: String? = null,
        sourceType: String = "FIXED",
        sourceTreeUri: String? = null,
        estimate: (suspend () -> Long)? = null,
        sortMode: PageSortMode = PageSortMode.NATURAL,
        sortAsc: Boolean = true,
        work: suspend (File, (Int, Int) -> Unit) -> List<PageSource>
    ) {
        if (isBusy()) return
        importJob = viewModelScope.launch {
            _importState.value = ImportUiState.Running(title, 0, 0)

            // 空间预检：数量能提前知道就精确估，不知道就按固定门槛兜底。
            // 宁可在这里拦下来，也不要写到一半失败再回滚。
            if (estimate != null) {
                val need = withContext(Dispatchers.IO) { estimate() }
                StorageGuard.check(context, need)?.let { message ->
                    _importState.value = ImportUiState.Failed(message)
                    return@launch
                }
            }

            // 单本导入也留一条任务记录：被打断时用户同样需要知道"上次那本没导完"
            val batchId = newBatchId()
            val now = System.currentTimeMillis()
            val taskId = runCatching {
                repo.enqueueTasks(
                    listOf(
                        ImportTask(
                            batchId = batchId,
                            title = title,
                            sourceKey = sourceKey,
                            sourceTreeUri = sourceTreeUri,
                            sourceType = sourceType,
                            sortIndex = 0,
                            status = ImportTask.RUNNING,
                            error = null,
                            createdAt = now,
                            updatedAt = now
                        )
                    )
                ).firstOrNull() ?: -1L
            }.getOrDefault(-1L)

            // 切后台/锁屏时靠前台服务保命；屏幕本身由 ShelfScreen 的 keepScreenOn 保证不熄
            ImportForegroundService.start(context, "正在导入《$title》")

            val folder = repo.storage.newFolder()
            val dir = repo.storage.dirOf(folder)
            try {
                // v1.5.5 页级进度：PDF 逐页渲染，1000 页要十几分钟。
                // 回调每页都会来，但 UI/通知/数据库没必要每页都刷一遍——
                // 每 5 页或至少间隔 300ms 更新一次，末尾那页强制更新。
                var lastEmit = 0L
                var lastDone = 0
                val pages = withContext(Dispatchers.IO) {
                    work(dir) { done, total ->
                        val now = System.currentTimeMillis()
                        val isLast = total in 1..done
                        if (isLast || done - lastDone >= 5 || now - lastEmit >= 300L) {
                            lastEmit = now
                            lastDone = done
                            _importState.value = ImportUiState.Running(title, done, total)
                            ImportForegroundService.update("正在渲染《$title》 第 $done / $total 页")
                            if (taskId > 0) {
                                // 回调不是 suspend，落库丢到 IO 协程里；已经节流到每 5 页/300ms，不会堆任务
                                val totalNow = total
                                val doneNow = done
                                val currentNow = if (isLast) 0 else done + 1
                                viewModelScope.launch(Dispatchers.IO) {
                                    runCatching {
                                        repo.taskProgress(taskId, totalNow, doneNow, currentNow)
                                    }
                                }
                            }
                        }
                    }
                }
                if (pages.isEmpty()) throw IllegalStateException("没有读到任何页面，请确认文件是否损坏")
                val book = Book(
                    title = title,
                    folder = folder,
                    pageCount = pages.size,
                    sourceKey = sourceKey,
                    sourceTreeUri = sourceTreeUri,
                    sourceType = sourceType
                )
                val bookId = repo.insertBook(book, pages)
                // 按导入时选定的规则重排页序（FIXED = 保持文件名顺序，不重排）。
                // 重排失败不算导入失败：页已经落盘入库，用户之后还能在「页序」里再改一次。
                if (sortMode != PageSortMode.FIXED) {
                    runCatching {
                        repo.applyPageOrder(book.copy(id = bookId), sortMode, sortAsc)
                    }
                }
                if (taskId > 0) repo.taskStatus(taskId, ImportTask.DONE)
                _importState.value = ImportUiState.Idle
            } catch (t: Throwable) {
                withContext(NonCancellable + Dispatchers.IO) { dir.deleteRecursively() }
                if (taskId > 0) {
                    repo.taskStatus(taskId, ImportTask.FAILED, t.message ?: "导入失败")
                }
                _importState.value = ImportUiState.Failed(t.message ?: "导入失败，请换一个文件试试")
            } finally {
                ImportForegroundService.stop(context)
            }
        }
    }

    /** 启动时问一句：上次是不是有没导完的 */
    private fun checkInterrupted() {
        viewModelScope.launch {
            val tasks = runCatching { repo.unfinishedTasks() }.getOrDefault(emptyList())
            if (tasks.isEmpty()) return@launch
            val batchId = tasks.first().batchId
            _interrupted.value = InterruptedBatch(
                batchId = batchId,
                count = tasks.size,
                titles = tasks.take(5).map { it.title }
            )
        }
    }

    private fun newBatchId(): String =
        "b" + System.currentTimeMillis().toString(36) + "-" +
            java.util.UUID.randomUUID().toString().take(4)

    private fun documentIdOf(uri: Uri): String? =
        runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
}
