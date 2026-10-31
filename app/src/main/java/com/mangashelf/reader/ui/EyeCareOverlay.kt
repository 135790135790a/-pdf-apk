package com.mangashelf.reader.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.mangashelf.reader.data.EyeCarePrefs
import com.mangashelf.reader.data.EyeCareState
import com.mangashelf.reader.data.EyeScope

/**
 * 护眼叠加层（v1.5.8 抽出来，主页与阅读器共用）。
 *
 * 为什么用叠加层而不是两套暖色配色：
 * 真正的配色切换要重做 38 个语义色 × 白/夜两套主题，改动面大且容易把对比度改坏；
 * 叠加层视觉等效（一层暖色滤镜），和阅读器 v1.5.7 的行为完全一致。
 *
 * 关键点：`drawWithContent` 只负责画，不参与触摸分发，
 * 所以缩放、翻页、点击手势都不受影响。
 */
object EyeCareOverlay {

    /** 实际叠加的不透明度。深色主题下按 0.7 折算，否则深底 + 暖色会发闷。 */
    fun alpha(state: EyeCarePrefs.State, darkTheme: Boolean): Float {
        if (!state.activeNow()) return 0f
        val base = state.level.alpha
        return if (darkTheme) base * 0.7f else base
    }

    /** 叠加层颜色（已含 alpha） */
    fun color(state: EyeCarePrefs.State, darkTheme: Boolean): Color {
        val a = alpha(state, darkTheme)
        return if (a <= 0f) Color.Transparent else Color(state.level.color).copy(alpha = a)
    }
}

/**
 * 订阅护眼配置。返回的对象只在版本号或范围变化时重建，
 * 不会每帧读 SharedPreferences。
 */
@Composable
fun rememberEyeState(scope: EyeScope): EyeCarePrefs.State {
    val context = LocalContext.current
    val version = EyeCareState.version
    return remember(version, scope, context) {
        runCatching { EyeCarePrefs.load(context, scope) }.getOrElse { EyeCarePrefs.State() }
    }
}

/** 在任意容器上盖一层护眼色。关闭时原样返回，不产生额外图层。 */
@Composable
fun Modifier.eyeCare(state: EyeCarePrefs.State): Modifier {
    val dark = isSystemInDarkTheme()
    val color = EyeCareOverlay.color(state, dark)
    if (color.alpha <= 0f) return this
    return this.drawWithContent {
        drawContent()
        drawRect(color)
    }
}
