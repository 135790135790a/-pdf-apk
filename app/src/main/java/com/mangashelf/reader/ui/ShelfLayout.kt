package com.mangashelf.reader.ui

import android.content.Context

/**
 * 书架布局（v1.5.6 需求三）。
 *
 * 三种视图解决三种场景：
 * - 网格：默认，封面看得清，适合"翻着挑";
 * - 列表：一屏能看十几本，适合书多、靠书名找；
 * - 瀑布流：错落排布，封面按书高矮不齐，视觉上更活泼。
 */
enum class ShelfLayout(val label: String) {
    GRID("网格"),
    LIST("列表"),
    STAGGERED("瀑布流");

    companion object {
        fun fromName(name: String?): ShelfLayout =
            entries.firstOrNull { it.name == name } ?: GRID
    }
}

object ShelfLayoutPrefs {

    private const val FILE = "shelf_view"
    private const val KEY = "layout"

    fun load(context: Context): ShelfLayout =
        ShelfLayout.fromName(prefs(context).getString(KEY, null))

    fun save(context: Context, layout: ShelfLayout) {
        prefs(context).edit().putString(KEY, layout.name).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
