package com.mangashelf.reader.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 文件浏览页的一层目录状态。
 *
 * [scrollIndex] 和 [lastClicked] 是「返回上级要恢复到哪」的全部信息：
 * 滚动到第几项、上次点进来的是哪一项（那一项要高亮）。
 */
data class DirLevel(
    val uri: String,
    val scrollIndex: Int = 0,
    val lastClicked: String = ""
)

/** 文件页的排序方式。和书架排序是两套，互不影响。 */
enum class DirSort(val label: String) {
    NAME("名称"),
    TIME("修改时间"),
    SIZE("大小"),
    IMPORT("导入顺序"),
    MANUAL("手动顺序"),
}

/**
 * 文件页状态的持久化（v1.5.7）。
 *
 * 为什么不用数据库：这里全是「界面状态」，丢了顶多多点两下，
 * 不值得为它加一张表、加一次 schema 迁移的风险。
 *
 * 存三样东西：
 * - 每个根目录的路径栈（含滚动位置与高亮项）
 * - 每个目录各自的排序方式
 * - 手动拖拽顺序（只在 MANUAL 模式下用）
 */
object DirState {

    private const val PREFS = "dir_state"
    private const val KEY_STACK_PREFIX = "stack_"
    private const val KEY_SORT_PREFIX = "sort_"
    private const val KEY_MANUAL_PREFIX = "manual_"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun hash(uri: String): String =
        Integer.toHexString(uri.hashCode())

    // ---------- 路径栈 ----------

    fun loadStack(context: Context, rootUri: String): List<DirLevel> {
        val raw = prefs(context).getString(KEY_STACK_PREFIX + hash(rootUri), null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            val out = ArrayList<DirLevel>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    DirLevel(
                        uri = o.optString("uri"),
                        scrollIndex = o.optInt("scroll", 0),
                        lastClicked = o.optString("clicked", "")
                    )
                )
            }
            out
        }.getOrDefault(emptyList())
    }

    fun saveStack(context: Context, rootUri: String, stack: List<DirLevel>) {
        val arr = JSONArray()
        stack.forEach { lvl ->
            arr.put(
                JSONObject()
                    .put("uri", lvl.uri)
                    .put("scroll", lvl.scrollIndex)
                    .put("clicked", lvl.lastClicked)
            )
        }
        prefs(context).edit().putString(KEY_STACK_PREFIX + hash(rootUri), arr.toString()).apply()
    }

    /** 记住当前目录的根 URI，冷启动时直接恢复到上次的位置 */
    fun loadRoot(context: Context): String =
        prefs(context).getString("root_uri", "").orEmpty()

    fun saveRoot(context: Context, uri: String) {
        prefs(context).edit().putString("root_uri", uri).apply()
    }

    fun clearRoot(context: Context) {
        prefs(context).edit().remove("root_uri").apply()
    }

    // ---------- 排序 ----------

    /** 返回 (排序方式, 是否升序)。默认按名称升序。 */
    fun loadSort(context: Context, dirUri: String): Pair<DirSort, Boolean> {
        val raw = prefs(context).getString(KEY_SORT_PREFIX + hash(dirUri), null)
            ?: return DirSort.NAME to true
        return runCatching {
            val parts = raw.split('|')
            val sort = DirSort.entries.firstOrNull { it.name == parts.getOrNull(0) } ?: DirSort.NAME
            val asc = parts.getOrNull(1)?.toBooleanStrictOrNull() ?: true
            sort to asc
        }.getOrDefault(DirSort.NAME to true)
    }

    fun saveSort(context: Context, dirUri: String, sort: DirSort, asc: Boolean) {
        prefs(context).edit()
            .putString(KEY_SORT_PREFIX + hash(dirUri), sort.name + "|" + asc)
            .apply()
    }

    // ---------- 手动顺序 ----------

    /** 目录里手动拖出来的顺序；没拖过就是空列表，表示按默认排序 */
    fun loadManual(context: Context, dirUri: String): List<String> {
        val raw = prefs(context).getString(KEY_MANUAL_PREFIX + hash(dirUri), null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
    }

    fun saveManual(context: Context, dirUri: String, order: List<String>) {
        val arr = JSONArray()
        order.forEach { arr.put(it) }
        prefs(context).edit().putString(KEY_MANUAL_PREFIX + hash(dirUri), arr.toString()).apply()
    }

    /** 换根目录时把旧的界面状态全清掉，避免不同项目之间串排序 */
    fun resetAll(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
