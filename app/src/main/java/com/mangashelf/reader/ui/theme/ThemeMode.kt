package com.mangashelf.reader.ui.theme

import android.content.Context

/** 主题三档：跟随系统 / 白天 / 夜晚 */
enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("白天"),
    DARK("夜晚");

    companion object {
        private const val PREFS = "reader_prefs"
        private const val KEY = "theme_mode"

        fun load(context: Context): ThemeMode {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, null)
            return entries.firstOrNull { it.name == raw } ?: SYSTEM
        }

        fun save(context: Context, mode: ThemeMode) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY, mode.name)
                .apply()
        }
    }
}
