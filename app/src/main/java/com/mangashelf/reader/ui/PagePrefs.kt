package com.mangashelf.reader.ui

import android.content.Context

/**
 * 翻页灵敏度。
 *
 * 阈值用"屏幕宽度的百分比"和"像素/秒"，不用 dp——不同机型屏宽能差一倍，
 * 写死 dp 的后果是小屏拖半天不翻、大屏碰一下就翻。
 */
enum class PageSensitivity(
    val label: String,
    /** 拖到图片边界后，还要再拖多少屏宽才翻页 */
    val edgeFraction: Float,
    /** 或者松手时的横向速度超过这个值（px/s）也翻 */
    val flingVelocity: Float,
    /** 翻页动画时长 */
    val animMs: Int
) {
    LOW("低 · 不易误翻", 0.22f, 900f, 220),
    MEDIUM("中 · 默认", 0.14f, 600f, 180),
    HIGH("高 · 很灵敏", 0.08f, 350f, 140);

    companion object {
        fun fromName(name: String?): PageSensitivity =
            entries.firstOrNull { it.name == name } ?: MEDIUM
    }
}

/**
 * 阅读器相关的偏好，全部存 `reader_prefs`（和主题模式同一个文件）。
 *
 * 直接读 SharedPreferences 而不是放进 ViewModel：这些都是"打开对话框时读一次、
 * 改完立刻写回"的轻量开关，没必要为它们建 StateFlow 再往上层传。
 */
object PagePrefs {

    private const val FILE = "reader_prefs"
    private const val KEY_SENSITIVITY = "page_sensitivity"
    private const val KEY_VOLUME_KEYS = "volume_keys"
    private const val KEY_LONG_PRESS_TURN = "long_press_turn"

    fun sensitivity(context: Context): PageSensitivity {
        val raw = prefs(context).getString(KEY_SENSITIVITY, null)
        return PageSensitivity.fromName(raw)
    }

    fun setSensitivity(context: Context, value: PageSensitivity) {
        prefs(context).edit().putString(KEY_SENSITIVITY, value.name).apply()
    }

    /** 音量键翻页，默认开：单手拿着看的时候比找屏幕边缘方便 */
    fun volumeKeys(context: Context): Boolean =
        prefs(context).getBoolean(KEY_VOLUME_KEYS, true)

    fun setVolumeKeys(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_VOLUME_KEYS, enabled).apply()
    }

    /**
     * 长按边缘连续翻页。默认关——长按在阅读器里本来是"保存本页"，
     * 两个功能抢同一个手势，只能让用户二选一。
     */
    fun longPressTurn(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LONG_PRESS_TURN, false)

    fun setLongPressTurn(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LONG_PRESS_TURN, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
