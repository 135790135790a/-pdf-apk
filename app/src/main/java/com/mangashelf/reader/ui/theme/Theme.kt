package com.mangashelf.reader.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val NightColors = darkColorScheme(
    primary = ComicOrange,
    onPrimary = ComicOnOrange,
    primaryContainer = NightSurfaceHigh,
    onPrimaryContainer = NightText,
    secondary = ComicBlue,
    onSecondary = ComicInk,
    background = NightBackground,
    onBackground = NightText,
    surface = NightSurface,
    onSurface = NightText,
    surfaceVariant = NightSurfaceHigh,
    onSurfaceVariant = NightTextDim,
    error = ComicRed,
    onError = ComicInk
)

private val DayColors = lightColorScheme(
    primary = DayOrange,
    onPrimary = DayOnOrange,
    primaryContainer = DaySurfaceHigh,
    onPrimaryContainer = DayText,
    secondary = DayBlue,
    onSecondary = DayOnOrange,
    background = DayBackground,
    onBackground = DayText,
    surface = DaySurface,
    onSurface = DayText,
    surfaceVariant = DaySurfaceHigh,
    onSurfaceVariant = DayTextDim,
    error = Color(0xFFB3261E),
    onError = DayOnOrange
)

/**
 * 全局主题。
 *
 * 夜间是深灰不是纯黑（见 Color.kt 注释）；白天是暖白。
 * 卡片/弹窗等 Material 组件会自动跟随 colorScheme，不用逐个改。
 */
@Composable
fun MangaShelfTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            // 白天模式的状态栏图标要变深色，否则白底白图标看不见
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }

    MaterialTheme(
        colorScheme = if (dark) NightColors else DayColors,
        content = content
    )
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
