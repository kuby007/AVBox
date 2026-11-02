package com.github.avbox.osc.ui

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView

/**
 * WebView 内核预热。
 *
 * 【为什么不再复用实例】进程级复用同一个 WebView 实例、返回时只 detach 不销毁：
 * about:blank 是异步的，旧的 JS 上下文、setInterval、音视频、未决网络回调可能还活着，
 * 下一次 attach 立刻 loadUrl 会与它竞争 —— 表现就是「返回宫格后脚本没完全销毁」。
 * 稳定优先：每次承载页自己 new WebView(activity)，关闭时按完整顺序 destroy。
 *
 * 快不靠复用脏实例，靠【内核预热】：Android 上第一次创建 WebView 要加载 chromium
 * 内核（几百毫秒），之后 new WebView() 几乎零成本。启动时创建一个立刻销毁，
 * 把这段耗时挪出「点开页面」的关键路径即可，不需要留一个实例在池里。
 */
object WebViewPool {

    @Volatile
    private var warmed = false

    /** 应用启动后预热一次内核；不保留实例。 */
    @JvmStatic
    fun warm(app: Context) {
        if (warmed) return
        warmed = true
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                WebView(app.applicationContext).destroy()
            } catch (ignored: Throwable) {
            }
        }, 800L)
    }

    /** 不再复用实例：始终返回 null，由承载页自己创建、自己销毁。 */
    @JvmStatic
    fun attach(activity: Activity): WebView? = null

    /** 保留空实现，兼容旧调用点。 */
    @JvmStatic
    fun detach() {}
}
