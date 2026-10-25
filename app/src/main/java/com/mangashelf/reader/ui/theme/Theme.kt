package com.mangashelf.reader.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val MangaShelfColors = darkColorScheme(
    primary = ComicOrange,
    onPrimary = ComicOnOrange,
    primaryContainer = ComicSurfaceHigh,
    onPrimaryContainer = ComicText,
    secondary = ComicBlue,
    onSecondary = ComicInk,
    background = ComicInk,
    onBackground = ComicText,
    surface = ComicSurface,
    onSurface = ComicText,
    surfaceVariant = ComicSurfaceHigh,
    onSurfaceVariant = ComicTextDim,
    error = ComicRed,
    onError = ComicInk
)

/** 漫画 App 统一使用深色主题，避免阅读时白底闪眼 */
@Composable
fun MangaShelfTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MangaShelfColors,
        content = content
    )
}
