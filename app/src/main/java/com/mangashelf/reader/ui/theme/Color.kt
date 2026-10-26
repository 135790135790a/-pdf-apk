package com.mangashelf.reader.ui.theme

import androidx.compose.ui.graphics.Color

val ComicOrange = Color(0xFFFF9F45)
val ComicOnOrange = Color(0xFF201200)
val ComicInk = Color(0xFF0E0E12)
val ComicSurface = Color(0xFF17171E)
val ComicSurfaceHigh = Color(0xFF23232C)
val ComicText = Color(0xFFEDEDF2)
val ComicTextDim = Color(0xFFB9B9C6)
val ComicBlue = Color(0xFF7FB2FF)
val ComicRed = Color(0xFFFF6B6B)
val ComicScrim = Color(0xCC000000)

// ---------------------------------------------------------------------------
// 夜晚：深灰而不是纯黑。全黑背景在 OLED 上边界看不清、翻页时还会和图片边框糊成一片，
// 所以底色用 #16161B，比纯黑亮一点但仍然是明显的夜间视觉。
// ---------------------------------------------------------------------------
val NightBackground = Color(0xFF16161B)
val NightSurface = Color(0xFF1E1E25)
val NightSurfaceHigh = Color(0xFF2A2A33)
val NightText = Color(0xFFE9E9F0)
val NightTextDim = Color(0xFFB6B6C4)

// ---------------------------------------------------------------------------
// 白天：暖白，不用纯白。纯白 + 大面积卡片会发灰刺眼，暖白（偏米）看久了更舒服。
// ---------------------------------------------------------------------------
val DayBackground = Color(0xFFF6F4EF)
val DaySurface = Color(0xFFFFFFFF)
val DaySurfaceHigh = Color(0xFFECE8E0)
val DayText = Color(0xFF1B1B1F)
val DayTextDim = Color(0xFF5D5D66)
val DayOrange = Color(0xFFDD7410)
val DayOnOrange = Color(0xFFFFFFFF)
val DayBlue = Color(0xFF2F6FD0)
