package com.mangashelf.reader

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Process
import android.text.method.ScrollingMovementMethod
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 崩溃报告页。
 *
 * 故意只用最原始的原生 View（不用 Compose、不用自定义主题资源），
 * 因为触发它的原因可能就是 Compose 或资源出了问题——这时候再用它们等于赌命。
 */
class CrashReportActivity : Activity() {

    companion object {
        const val EXTRA_TEXT = "crash_text"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val reportText = intent?.getStringExtra(EXTRA_TEXT) ?: "没有收到崩溃信息"
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#111114"))
            setPadding(pad, pad, pad, pad)
        }

        val title = TextView(this).apply {
            this.text = "漫画书架崩溃了"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        }

        val hint = TextView(this).apply {
            this.text = "请把下面这段文字截图或复制发给开发者。\n" +
                "同时也已保存到：Android/data/com.mangashelf.reader/files/crash/"
            setTextColor(Color.parseColor("#FF9F45"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, pad / 2, 0, pad / 2)
        }

        val body = TextView(this).apply {
            this.text = reportText
            setTextColor(Color.parseColor("#DDDDE5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextIsSelectable(true)
            movementMethod = ScrollingMovementMethod()
        }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#1A1A20"))
            addView(
                body,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val copyButton = Button(this).apply {
            text = "复制全部"
            setOnClickListener {
                runCatching {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("crash", reportText))
                    this.text = "已复制"
                }
            }
        }

        val exitButton = Button(this).apply {
            text = "退出"
            setOnClickListener { Process.killProcess(Process.myPid()) }
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(copyButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(exitButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        root.addView(title)
        root.addView(hint)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(buttons)

        setContentView(root)
    }
}
