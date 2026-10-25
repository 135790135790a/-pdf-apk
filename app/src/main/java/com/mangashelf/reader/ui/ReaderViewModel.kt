package com.mangashelf.reader.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mangashelf.reader.data.Book
import com.mangashelf.reader.data.BookRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ReaderViewModel(
    private val repo: BookRepository,
    private val bookId: Long
) : ViewModel() {

    val book: StateFlow<Book?> = repo.observeBook(bookId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun pagePaths(book: Book): List<String> = repo.pagePaths(book)

    fun saveProgress(page: Int) {
        viewModelScope.launch { repo.saveProgress(bookId, page) }
    }

    fun setRtl(rtl: Boolean) {
        viewModelScope.launch { repo.setRtl(bookId, rtl) }
    }
}

class ReaderViewModelFactory(
    private val repo: BookRepository,
    private val bookId: Long
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ReaderViewModel(repo, bookId) as T
}
