package com.mangashelf.reader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.mangashelf.reader.ui.ReaderScreen
import com.mangashelf.reader.ui.ShelfScreen
import com.mangashelf.reader.ui.theme.MangaShelfTheme
import com.mangashelf.reader.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            // 主题放最外层：书架和阅读器一起跟随，切完立刻生效，退出 App 也记得
            var themeMode by remember { mutableStateOf(ThemeMode.load(context)) }

            MangaShelfTheme(mode = themeMode) {
                // -1 表示停留在书架；否则为正在阅读的书 id。用 saveable 保证旋转/重建后不丢。
                var openBookId by rememberSaveable { mutableStateOf(NO_BOOK) }
                if (openBookId == NO_BOOK) {
                    ShelfScreen(
                        onOpenBook = { openBookId = it },
                        themeMode = themeMode,
                        onThemeChange = { mode ->
                            themeMode = mode
                            ThemeMode.save(context, mode)
                        }
                    )
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
