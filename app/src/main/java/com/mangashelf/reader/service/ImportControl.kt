package com.mangashelf.reader.service

/**
 * 通知栏「暂停 / 继续」按钮与导入协程之间的桥。
 *
 * 为什么不用广播或 Binder：
 * - 广播要注册接收器，Android 8+ 还有隐式广播限制，为一个按钮不值当；
 * - Binder 要在 Service 和 ViewModel 之间维持连接，导入结束时还得解绑。
 * 这里只是「通知栏点了一下」这种低频事件，用一个带两个回调的 object 最简单，
 * ViewModel 在 init 里挂上、onCleared 里摘掉即可。
 */
object ImportControl {

    /** 通知栏点了「暂停」 */
    @Volatile
    var onPause: (() -> Unit)? = null

    /** 通知栏点了「继续」 */
    @Volatile
    var onResume: (() -> Unit)? = null

    /** 当前是否处于暂停态（决定通知栏显示哪个按钮） */
    @Volatile
    var paused: Boolean = false
}
