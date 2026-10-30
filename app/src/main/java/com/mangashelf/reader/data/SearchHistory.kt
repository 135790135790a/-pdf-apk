package com.mangashelf.reader.data

import android.content.Context
import org.json.JSONArray

/**
 * 搜索历史（v1.5.7）。
 *
 * 规矩：
 * - 最多留 [MAX] 条，超了丢最旧的
 * - 同一个词再搜一次，提到最前面而不是留两条
 * - 空白词不入库
 */
object SearchHistory {

    const val MAX = 20
    private const val PREFS = "search_prefs"
    private const val KEY = "history"

    fun load(context: Context): List<String> {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
    }

    /** 记一条搜索词。返回更新后的完整列表，UI 直接拿来显示。 */
    fun add(context: Context, word: String): List<String> {
        val clean = word.trim()
        if (clean.isEmpty()) return load(context)
        val cur = load(context).filter { !it.equals(clean, ignoreCase = true) }
        val next = (listOf(clean) + cur).take(MAX)
        save(context, next)
        return next
    }

    fun remove(context: Context, word: String): List<String> {
        val next = load(context).filter { it != word }
        save(context, next)
        return next
    }

    fun clear(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
    }

    private fun save(context: Context, list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}
