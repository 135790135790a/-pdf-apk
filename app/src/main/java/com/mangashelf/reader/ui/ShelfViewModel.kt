package com.mangashelf.reader.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.documentfile.provider.DocumentFile
import com.mangashelf.reader.MangaShelfApp
import com.mangashelf.reader.data.Book
import com.mangashelf.reader.importer.FileNames
import com.mangashelf.reader.importer.FolderImporter
import com.mangashelf.reader.importer.ImageImporter
import com.mangashelf.reader.importer.PdfImporter
import kotlinx.coroutines.Dispatchers
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
    data class Running(val title: String, val done: Int, val total: Int) : ImportUiState
    data class Failed(val message: String) : ImportUiState
}

class ShelfViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = (application as MangaShelfApp).repository
    private val context = application.applicationContext

    val books: StateFlow<List<Book>> = repo.observeBooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _importState = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val importState: StateFlow<ImportUiState> = _importState.asStateFlow()

    fun coverPath(book: Book): String = repo.coverPath(book)

    fun importPdf(uri: Uri) {
        val title = FileNames.displayName(context, uri)
            ?.let { FileNames.stripExtension(it) }
            ?: "未命名漫画"
        startImport(title) { dir, progress ->
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
        startImport(title) { dir, progress ->
            ImageImporter.import(context, uris, dir, progress)
        }
    }

    /** 整个文件夹 = 一本书（递归子目录，按相对路径自然排序） */
    fun importFolder(treeUri: Uri) {
        val folderName = runCatching {
            DocumentFile.fromTreeUri(context, treeUri)?.name
        }.getOrNull()
        val title = folderName?.trim().orEmpty().ifBlank { "未命名漫画" }
        startImport(title) { dir, progress ->
            FolderImporter.import(context, treeUri, dir, progress)
        }
    }

    fun dismissImportDialog() {
        if (_importState.value !is ImportUiState.Running) _importState.value = ImportUiState.Idle
    }

    fun deleteBook(book: Book) {
        viewModelScope.launch { repo.delete(book) }
    }

    fun renameBook(book: Book, title: String) {
        viewModelScope.launch { repo.rename(book.id, title) }
    }

    fun toggleDirection(book: Book) {
        viewModelScope.launch { repo.setRtl(book.id, !book.rtl) }
    }

    private fun startImport(title: String, work: suspend (File, (Int, Int) -> Unit) -> Int) {
        if (_importState.value is ImportUiState.Running) return
        viewModelScope.launch {
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
                repo.insert(Book(title = title, folder = folder, pageCount = pages))
                _importState.value = ImportUiState.Idle
            } catch (t: Throwable) {
                withContext(Dispatchers.IO) { dir.deleteRecursively() }
                _importState.value = ImportUiState.Failed(t.message ?: "导入失败，请换一个文件试试")
            }
        }
    }
}
