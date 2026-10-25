package com.mangashelf.reader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.mangashelf.reader.ui.ReaderScreen
import com.mangashelf.reader.ui.ShelfScreen
import com.mangashelf.reader.ui.theme.MangaShelfTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MangaShelfTheme {
                // -1 表示停留在书架；否则为正在阅读的书 id。用 saveable 保证旋转/重建后不丢。
                var openBookId by rememberSaveable { mutableStateOf(NO_BOOK) }
                if (openBookId == NO_BOOK) {
                    ShelfScreen(onOpenBook = { openBookId = it })
                } else {
                    ReaderScreen(bookId = openBookId, onBack = { openBookId = NO_BOOK })
                }
            }
        }
    }

    private companion object {
        const val NO_BOOK = -1L
    }
}
