package com.mangashelf.reader.ui

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.documentfile.provider.DocumentFile
import com.mangashelf.reader.MangaShelfApp
import com.mangashelf.reader.data.Book
import com.mangashelf.reader.importer.FileNames
import com.mangashelf.reader.importer.FolderImporter
import com.mangashelf.reader.importer.ImageImporter
import com.mangashelf.reader.importer.PdfImporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        val cancelled: Boolean
    ) : ImportUiState

    data class Failed(val message: String) : ImportUiState
}

class ShelfViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = (application as MangaShelfApp).repository
    private val context = application.applicationContext

    val books: StateFlow<List<Book>> = repo.observeBooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _importState = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val importState: StateFlow<ImportUiState> = _importState.asStateFlow()

    private var importJob: Job? = null
    private val paused = MutableStateFlow(false)

    fun coverPath(book: Book): String = repo.coverPath(book)

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
            sourceType = "PDF"
        ) { dir, progress ->
            PdfImporter.import(context, uri, dir, progress)
        }
    }

    fun importImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val title = if (uris.size == 1) {
            FileNames.displayName(context, uris.first())
                ?.let { FileNames.stripExtension(it) }
                ?: "未命名漫画"
        } else {
            val stamp = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
            "图片漫画 $stamp"
        }
        startImport(title = title, sourceType = "IMAGE") { dir, progress ->
            ImageImporter.import(context, uris, dir, progress)
        }
    }

    /** 整个文件夹 = 一本书（递归子目录，按相对路径自然排序） */
    fun importFolder(treeUri: Uri) {
        val folderName = runCatching {
            DocumentFile.fromTreeUri(context, treeUri)?.name
        }.getOrNull()
        val title = folderName?.trim().orEmpty().ifBlank { "未命名漫画" }
        startImport(
            title = title,
            sourceKey = documentIdOf(treeUri),
            sourceType = "IMAGE",
            sourceTreeUri = treeUri.toString()
        ) { dir, progress ->
            FolderImporter.import(context, treeUri, dir, progress)
        }
    }

    // ------------------------------------------------------------------
    // 批量导入（父文件夹下每个子文件夹 = 一本书）
    // ------------------------------------------------------------------

    /**
     * 队列式批量导入：
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

            var imported = 0
            var skipped = 0
            var failed = 0
            var currentDir: File? = null
            paused.value = false

            try {
                for ((i, child) in children.withIndex()) {
                    awaitResume(i, children.size, skipped)

                    if (repo.findBySourceKey(child.key) != null) {
                        skipped++
                        continue
                    }

                    val folder = repo.storage.newFolder()
                    val dir = repo.storage.dirOf(folder)
                    currentDir = dir

                    val pages = try {
                        withContext(Dispatchers.IO) {
                            FolderImporter.import(context, child.treeUri, dir) { done, total ->
                                _importState.value = ImportUiState.Batch(
                                    index = i + 1,
                                    total = children.size,
                                    title = child.name,
                                    done = done,
                                    totalPages = total,
                                    skipped = skipped,
                                    paused = paused.value
                                )
                            }
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        withContext(NonCancellable + Dispatchers.IO) { dir.deleteRecursively() }
                        currentDir = null
                        failed++
                        continue
                    }

                    currentDir = null
                    if (pages <= 0) {
                        withContext(Dispatchers.IO) { dir.deleteRecursively() }
                        failed++
                        continue
                    }

                    repo.insert(
                        Book(
                            title = child.name,
                            folder = folder,
                            pageCount = pages,
                            sourceKey = child.key,
                            sourceTreeUri = child.treeUri.toString(),
                            sourceType = "IMAGE"
                        )
                    )
                    imported++
                }
                _importState.value = ImportUiState.Finished(imported, skipped, failed, cancelled = false)
            } catch (ce: CancellationException) {
                currentDir?.let { d ->
                    withContext(NonCancellable + Dispatchers.IO) { d.deleteRecursively() }
                }
                _importState.value = ImportUiState.Finished(imported, skipped, failed, cancelled = true)
                throw ce
            } catch (t: Throwable) {
                _importState.value = ImportUiState.Failed(t.message ?: "批量导入失败")
            } finally {
                paused.value = false
            }
        }
    }

    fun pauseImport() {
        if (importJob?.isActive != true) return
        paused.value = true
    }

    fun resumeImport() {
        paused.value = false
    }

    fun cancelImport() {
        importJob?.cancel()
    }

    fun dismissImportDialog() {
        when (_importState.value) {
            is ImportUiState.Running, is ImportUiState.Batch -> Unit
            else -> _importState.value = ImportUiState.Idle
        }
    }

    private fun isBusy(): Boolean =
        importJob?.isActive == true || _importState.value is ImportUiState.Running

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

    // ------------------------------------------------------------------

    fun deleteBook(book: Book) {
        viewModelScope.launch { repo.delete(book) }
    }

    fun renameBook(book: Book, title: String) {
        viewModelScope.launch { repo.rename(book.id, title) }
    }

    fun toggleDirection(book: Book) {
        viewModelScope.launch { repo.setRtl(book.id, !book.rtl) }
    }

    private fun startImport(
        title: String,
        sourceKey: String? = null,
        sourceType: String = "FIXED",
        sourceTreeUri: String? = null,
        work: suspend (File, (Int, Int) -> Unit) -> Int
    ) {
        if (isBusy()) return
        importJob = viewModelScope.launch {
            _importState.value = ImportUiState.Running(title, 0, 0)
            val folder = repo.storage.newFolder()
            val dir = repo.storage.dirOf(folder)
            try {
                val pages = withContext(Dispatchers.IO) {
                    work(dir) { done, total ->
                        _importState.value = ImportUiState.Running(title, done, total)
                    }
                }
                if (pages <= 0) throw IllegalStateException("没有读到任何页面，请确认文件是否损坏")
                repo.insert(
                    Book(
                        title = title,
                        folder = folder,
                        pageCount = pages,
                        sourceKey = sourceKey,
                        sourceTreeUri = sourceTreeUri,
                        sourceType = sourceType
                    )
                )
                _importState.value = ImportUiState.Idle
            } catch (t: Throwable) {
                withContext(NonCancellable + Dispatchers.IO) { dir.deleteRecursively() }
                _importState.value = ImportUiState.Failed(t.message ?: "导入失败，请换一个文件试试")
            }
        }
    }

    private fun documentIdOf(uri: Uri): String? =
        runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
}
