package com.mangashelf.reader.data

import android.content.Context
import java.util.Calendar

/** 护眼的色温档位。数值是叠加层的不透明度与颜色，越大越黄。 */
enum class EyeLevel(val label: String, val alpha: Float, val color: Long) {
    WARM("暖黄", 0.20f, 0xFFFFB74D),
    SOFT("柔和", 0.11f, 0xFFFFCC80),
    CUSTOM("自定义", 0.16f, 0xFFFFC466),
}

/** 什么时候开护眼。 */
enum class EyePolicy(val label: String) {
    MANUAL("手动"),
    TIMED("定时"),
    SYSTEM("跟随系统深色"),
}

/**
 * 阅读器护眼模式的配置（v1.5.7）。
 *
 * 只影响阅读器，不影响书架/搜索/设置——用户没要求全局，
 * 全局变色会让缩略图颜色失真、选书时误判封面。
 *
 * 定时策略故意用「时段」而不是真实的日出日落：
 * 后者要定位权限，为了一个调色功能去要位置权限不值得。
 */
object EyeCarePrefs {

    private const val PREFS = "reader_prefs"
    private const val KEY_ENABLED = "eye_enabled"
    private const val KEY_LEVEL = "eye_level"
    private const val KEY_POLICY = "eye_policy"
    private const val KEY_FROM = "eye_from"
    private const val KEY_TO = "eye_to"

    data class State(
        val enabled: Boolean = false,
        val level: EyeLevel = EyeLevel.WARM,
        val policy: EyePolicy = EyePolicy.MANUAL,
        val fromHour: Int = 19,
        val toHour: Int = 7,
    ) {
        /** 按当前策略与时间，这一刻到底该不该开 */
        fun activeNow(hour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)): Boolean {
            if (!enabled) return false
            return when (policy) {
                EyePolicy.MANUAL, EyePolicy.SYSTEM -> true
                EyePolicy.TIMED ->
                    if (fromHour <= toHour) hour in fromHour until toHour
                    else hour >= fromHour || hour < toHour
            }
        }
    }

    fun load(context: Context): State {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return State(
            enabled = p.getBoolean(KEY_ENABLED, false),
            level = EyeLevel.entries.firstOrNull { it.name == p.getString(KEY_LEVEL, null) }
                ?: EyeLevel.WARM,
            policy = EyePolicy.entries.firstOrNull { it.name == p.getString(KEY_POLICY, null) }
                ?: EyePolicy.MANUAL,
            fromHour = p.getInt(KEY_FROM, 19),
            toHour = p.getInt(KEY_TO, 7),
        )
    }

    fun save(context: Context, state: State) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, state.enabled)
            .putString(KEY_LEVEL, state.level.name)
            .putString(KEY_POLICY, state.policy.name)
            .putInt(KEY_FROM, state.fromHour)
            .putInt(KEY_TO, state.toHour)
            .apply()
        // 阅读器那层遮罩靠这个版本号重组：对话框改完立刻生效，不用退出重进
        EyeCareState.version++
    }
}

/**
 * 护眼状态的「变更广播」。
 *
 * 只有一个观察者（当前打开的那本），用一个自增计数器足够，
 * 不值得为它引 SharedFlow 或把状态提到 Activity 里再层层传参。
 */
object EyeCareState {
    private val counter = androidx.compose.runtime.mutableIntStateOf(0)

    /** 读它 = 订阅；在 composable 里读会被重组追踪 */
    var version: Int
        get() = counter.intValue
        internal set(value) {
            counter.intValue = value
        }
}
