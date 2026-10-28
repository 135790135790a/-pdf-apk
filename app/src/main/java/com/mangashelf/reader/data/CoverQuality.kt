package com.mangashelf.reader.data

import android.content.Context

/**
 * 书架封面用哪张图（v1.5.5）。
 *
 * 书架一屏最多同时出现十几本，每本都解码 1440~1800px 的原图（内存里单张 10~18 MB），
 * 100+ 本书快速滚动时会持续掉帧、逼着 GC 反复回收。
 * [THUMB] 走 [ThumbStore] 的 400px WebP（8~20 KB，解码快一个数量级），是默认值；
 * [FULL] 留给"我就想看清晰封面"的场景。
 */
enum class CoverQuality(val label: String) {
    THUMB("缩略图（省内存，默认）"),
    FULL("原图（更清晰）");

    companion object {
        fun fromName(name: String?): CoverQuality =
            entries.firstOrNull { it.name == name } ?: THUMB
    }
}

object CoverPrefs {

    private const val FILE = "shelf_cover"
    private const val KEY = "quality"

    fun load(context: Context): CoverQuality =
        CoverQuality.fromName(prefs(context).getString(KEY, null))

    fun save(context: Context, quality: CoverQuality) {
        prefs(context).edit().putString(KEY, quality.name).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
