package com.mangashelf.reader.ui

/**
 * 音量键翻页的路由。
 *
 * 音量键不会进 Compose 的手势体系，只能在 `Activity.dispatchKeyEvent` 里拦，
 * 所以用一个全局回调把事件送回来：阅读器用 DisposableEffect 挂上，退出时摘掉。
 * 摘掉之后书架上按音量键仍然是调音量，不会莫名其妙翻页。
 */
object PageKeyBus {

    /** forward = true 表示"下一页" */
    @Volatile
    var onTurn: ((forward: Boolean) -> Unit)? = null
}
