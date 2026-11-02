package com.github.avbox.osc.ui

import android.app.Activity
import android.content.Context
import android.content.MutableContextWrapper
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * 进程级 WebView 复用池。
 *
 * 【为什么】以前每次点开 html 都 new WebView(this)，返回时 destroy()。
 * Android 上 WebView 首次初始化要几百毫秒，每次都付一遍 —— 这就是"在线 html
 * 比浏览器慢"的真因，不是网络慢、也不是脚本慢。主流做法是把它当常驻容器：
 * 预热一次，点开只是 attach + loadUrl，返回 detach 不销毁，第二次起接近浏览器速度。
 *
 * 用 MutableContextWrapper 包 Application context 创建，attach 时把 base 换成当前
 * Activity（页面内 alert / 选文件 / 媒体全屏都需要 Activity 的 theme 与窗口），
 * detach 时换回 Application，避免承载页 finish 后被 WebView 持有导致泄漏。
 *
 * 创建失败（极少数 ROM）时返回 null，调用方退回原来的 new WebView(activity)，
 * 功能不退化，只是不复用。
 */
object WebViewPool {

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var appCtx: Context? = null
    private var wrapper: MutableContextWrapper? = null
    private var web: WebView? = null

    @Volatile
    private var unavailable = false

    /** 应用启动后预热一次，把首次初始化的耗时挪出"点开页面"的关键路径。 */
    @JvmStatic
    fun warm(app: Context) {
        appCtx = app.applicationContext
        main.postDelayed({
            try {
                ensure()
            } catch (ignored: Throwable) {
            }
        }, 800L)
    }

    /** 只在主线程调用 */
    private fun ensure(): WebView? {
        if (unavailable) return null
        web?.let { return it }
        val ctx = appCtx ?: return null
        return try {
            val w = MutableContextWrapper(ctx)
            val v = WebView(w)
            wrapper = w
            web = v
            v
        } catch (th: Throwable) {
            unavailable = true
            null
        }
    }

    /** 主线程调用；返回可复用的 WebView，null 表示调用方需自行新建（不复用） */
    @JvmStatic
    fun attach(activity: Activity): WebView? {
        appCtx = activity.applicationContext
        val v = ensure() ?: return null
        (v.parent as? ViewGroup)?.removeView(v)
        wrapper?.baseContext = activity
        return v
    }

    /**
     * 与承载页解绑，但【不 destroy】。
     * 先换成空 client 再 about:blank，否则会触发上一轮 client 的 onPageStarted /
     * onPageFinished，把上一次的请求又注入执行一遍。
     */
    @JvmStatic
    fun detach() {
        val v = web ?: return
        try {
            (v.parent as? ViewGroup)?.removeView(v)
            v.stopLoading()
            @Suppress("DEPRECATION")
            v.removeJavascriptInterface("FmBridge")
            v.webViewClient = object : WebViewClient() {}
            v.webChromeClient = WebChromeClient()
            v.loadUrl("about:blank")
            v.clearHistory()
        } catch (ignored: Throwable) {
        }
        appCtx?.let { wrapper?.baseContext = it }
    }
}
