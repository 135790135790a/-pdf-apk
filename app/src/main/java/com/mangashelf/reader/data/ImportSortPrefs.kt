package com.mangashelf.reader.data

import android.content.Context

/**
 * 导入时的默认排序规则（文件夹 / 图片分开配置，互不影响）。
 *
 * 只存"下次导入默认用什么"，导入时仍可在弹窗里临时改一次；
 * 临时改动不回写这里，避免一次例外操作把长期习惯改掉。
 */
object ImportSortPrefs {

    /** 导入可选的全部规则（含「导入顺序」= FIXED，表示按读取到的顺序原样入库） */
    val choices: List<PageSortMode> = PageSortMode.entries.toList()

    private const val PREFS = "import_sort"
    private const val KEY_FOLDER_MODE = "folder_mode"
    private const val KEY_FOLDER_ASC = "folder_asc"
    private const val KEY_IMAGE_MODE = "image_mode"
    private const val KEY_IMAGE_ASC = "image_asc"

    fun folderMode(context: Context): PageSortMode = mode(context, KEY_FOLDER_MODE)
    fun folderAsc(context: Context): Boolean = asc(context, KEY_FOLDER_ASC)
    fun imageMode(context: Context): PageSortMode = mode(context, KEY_IMAGE_MODE)
    fun imageAsc(context: Context): Boolean = asc(context, KEY_IMAGE_ASC)

    fun saveFolder(context: Context, mode: PageSortMode, ascending: Boolean) {
        write(context, KEY_FOLDER_MODE, KEY_FOLDER_ASC, mode, ascending)
    }

    fun saveImage(context: Context, mode: PageSortMode, ascending: Boolean) {
        write(context, KEY_IMAGE_MODE, KEY_IMAGE_ASC, mode, ascending)
    }

    private fun mode(context: Context, key: String): PageSortMode {
        val raw = prefs(context).getString(key, null)
        return choices.firstOrNull { it.name == raw } ?: PageSortMode.NATURAL
    }

    private fun asc(context: Context, key: String): Boolean = prefs(context).getBoolean(key, true)

    private fun write(
        context: Context,
        modeKey: String,
        ascKey: String,
        mode: PageSortMode,
        ascending: Boolean
    ) {
        prefs(context).edit().putString(modeKey, mode.name).putBoolean(ascKey, ascending).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
