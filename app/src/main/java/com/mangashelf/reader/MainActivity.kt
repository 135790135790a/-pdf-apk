package com.mangashelf.reader

import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.mangashelf.reader.ui.PageManageScreen
import com.mangashelf.reader.ui.ReaderScreen
import com.mangashelf.reader.ui.ShelfScreen
import com.mangashelf.reader.ui.theme.MangaShelfTheme
import com.mangashelf.reader.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // 必须在 super.onCreate 之前：启动窗口的底色是系统在这一步之后画的
        applyStartupTheme()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            // 主题放最外层：书架和阅读器一起跟随，切完立刻生效，退出 App 也记得
            var themeMode by remember { mutableStateOf(ThemeMode.load(context)) }

            MangaShelfTheme(mode = themeMode) {
                // -1 表示停留在书架；否则为正在阅读的书 id。用 saveable 保证旋转/重建后不丢。
                var openBookId by rememberSaveable { mutableStateOf(NO_BOOK) }
                var manageBookId by rememberSaveable { mutableStateOf(NO_BOOK) }
                when {
                    manageBookId != NO_BOOK -> PageManageScreen(
                        bookId = manageBookId,
                        onBack = { manageBookId = NO_BOOK }
                    )

                    openBookId != NO_BOOK -> ReaderScreen(
                        bookId = openBookId,
                        onBack = { openBookId = NO_BOOK },
                        onManagePages = { manageBookId = openBookId }
                    )

                    else -> ShelfScreen(
                        onOpenBook = { openBookId = it },
                        onManagePages = { manageBookId = it },
                        themeMode = themeMode,
                        onThemeChange = { mode ->
                            themeMode = mode
                            ThemeMode.save(context, mode)
                        }
                    )
                }
            }
        }
    }

    /**
     * 音量键翻页（v1.5.6）。
     *
     * 只在阅读器把回调挂到 [PageKeyBus] 时才拦截，其余界面原样交还给系统调音量。
     * 返回 true 表示"这个按键我们消费掉了"，系统就不会再调音量。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val turn = com.mangashelf.reader.ui.PageKeyBus.onTurn
        if (turn != null && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                // 往下按 = 往后翻，符合"往下按就是下一页"的直觉
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    turn(true)
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    turn(false)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * 冷启动那一帧的底色由启动窗口主题决定，那时 Compose 还没起来、读不到主题状态。
     * 所以先按用户保存的选择挑一个窗口背景，避免「白天模式启动闪一下黑」。
     *
     * 注意：App 内切换主题时不重建 Activity（Compose 直接重组，窗口背景被 Surface 盖住），
     * 所以这里只需要处理冷启动。
     */
    private fun applyStartupTheme() {
        val dark = when (ThemeMode.load(this)) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            ThemeMode.SYSTEM ->
                (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
        }
        setTheme(if (dark) R.style.Theme_MangaShelf_Night else R.style.Theme_MangaShelf_Day)
    }

    private companion object {
        const val NO_BOOK = -1L
    }
}
