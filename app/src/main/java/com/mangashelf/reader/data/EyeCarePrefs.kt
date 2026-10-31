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
 * 护眼作用范围（v1.5.8）。
 *
 * 主页和阅读器分开存：看书时想把画面压黄，不代表翻书架时也愿意看一片黄。
 * READER 的键位保持 v1.5.7 的老名字（`eye_*`），升级后老配置直接继续生效。
 */
enum class EyeScope(val keyPrefix: String) {
    HOME("eye_home_"),
    READER("eye_"),
}

/**
 * 护眼模式的配置。
 *
 * 定时策略故意用「时段」而不是真实的日出日落：
 * 后者要定位权限，为了一个调色功能去要位置权限不值得。
 */
object EyeCarePrefs {

    private const val PREFS = "reader_prefs"

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

        /** 设置页/工具栏上显示的一行摘要 */
        fun summary(): String =
            if (!enabled) "关" else "${level.label} · ${policy.label}"
    }

    private fun key(scope: EyeScope, name: String): String = scope.keyPrefix + name

    fun load(context: Context, scope: EyeScope = EyeScope.READER): State {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fun s(n: String) = p.getString(key(scope, n), null)
        fun b(n: String) = p.getBoolean(key(scope, n), false)
        fun i(n: String, d: Int) = p.getInt(key(scope, n), d)
        return State(
            enabled = b("enabled"),
            level = EyeLevel.entries.firstOrNull { it.name == s("level") } ?: EyeLevel.WARM,
            policy = EyePolicy.entries.firstOrNull { it.name == s("policy") } ?: EyePolicy.MANUAL,
            fromHour = i("from", 19),
            toHour = i("to", 7),
        )
    }

    fun save(context: Context, scope: EyeScope, state: State) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(key(scope, "enabled"), state.enabled)
            .putString(key(scope, "level"), state.level.name)
            .putString(key(scope, "policy"), state.policy.name)
            .putInt(key(scope, "from"), state.fromHour)
            .putInt(key(scope, "to"), state.toHour)
            .apply()
        // 遮罩靠这个版本号重组：对话框改完立刻生效，不用退出重进
        EyeCareState.version++
    }

    /** 恢复默认：把两个范围都关掉 */
    fun resetAll(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val e = p.edit()
        EyeScope.entries.forEach { scope ->
            e.remove(key(scope, "enabled"))
            e.remove(key(scope, "level"))
            e.remove(key(scope, "policy"))
            e.remove(key(scope, "from"))
            e.remove(key(scope, "to"))
        }
        e.apply()
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
