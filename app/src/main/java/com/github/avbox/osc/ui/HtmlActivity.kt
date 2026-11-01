package com.github.avbox.osc.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.github.avbox.osc.util.KV
import com.github.avbox.osc.util.AppLog
import org.json.JSONObject
import java.io.File

/**
 * HTML 脚本（type 6）的全屏承载页。
 *
 * 采用 WvWv 范式：本页【只是把 html 显示出来】，不参与数据回传。
 * 分类与宫格是宿主自己造的（见 HtmlSpider），点卡片才拉起本页，
 * 拉起后不等任何结果，页面自己渲染自己的内容。
 *
 * 三个必须做对的点：
 *   1) 返回键：页面内跳转过（有一级/二级页面）时，第一次返回必须回到上一级，
 *      而不是直接关掉整个页面 —— 见 onBackPressed；
 *   2) 不设 WebChromeClient 时页面里的 alert/confirm/prompt 不会弹出，
 *      脚本会一直等返回值而被"卡死"，所以这里必须接住并给出结果；
 *   3) 本地 html 用 file:// 直接 loadUrl（配 allowUniversalAccessFromFileURLs
 *      后相对资源才解析得到），不要把正文读出来用 loadDataWithBaseURL 塞进去 ——
 *      那会让页面的 baseURI 失真，页内 fetch 被 CORS 打死。
 */
class HtmlActivity : Activity() {

    data class Request(
        val siteKey: String,
        val api: String,
        val ext: String,
    )

    private var webView: WebView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var curSiteKey: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val req = pending
        if (req == null) {
            finish()
            return
        }
        pending = null
        curSiteKey = req.siteKey
        live = this

        val wv = WebView(this)
        webView = wv
        wv.setBackgroundColor(Color.BLACK)
        setContentView(
            wv,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        setupWebView(wv)
        wv.addJavascriptInterface(Bridge(req), "FmBridge")

        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(
                view: WebView?,
                url: String?,
                favicon: android.graphics.Bitmap?,
            ) {
                // 提前把 fm 挂上：有些脚本在页面顶层就要用 fm.req 预热数据，
                // 等到 onPageFinished 才注入的话那段代码已经因 fm undefined 崩掉了
                injectApi()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                // 页面加载完成后再补一次：重定向过的页面顶层 fm 可能已被覆盖
                injectApi()
            }

            /**
             * 把 http(s) 请求接到 App 网络栈，让站点代理对 WebView 同样生效 ——
             * WebView 自己是不认 OkHttp 的 proxySelector 的。
             * 取不到就返回 null，交给 WebView 自己加载，不硬拦。
             */
            override fun shouldInterceptRequest(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
            ): android.webkit.WebResourceResponse? {
                val r = request ?: return null
                val url = r.url?.toString() ?: return null
                if (!url.startsWith("http://") && !url.startsWith("https://")) return null
                // POST 拿不到请求体（Android 硬限制），接管会丢 body，必须放行
                val method = r.method ?: "GET"
                if (!method.equals("GET", ignoreCase = true)) return null
                return try {
                    // 默认就是 GET，不写 .get() 以兼容不同 OkHttp 版本
                    val builder = okhttp3.Request.Builder().url(url)
                    val headers = r.requestHeaders
                    if (headers != null) {
                        for ((k, v) in headers) builder.header(k, v ?: "")
                    }
                    val resp = com.github.catvod.net.OkHttp.client()
                        .newCall(builder.build())
                        .execute()
                    val type = resp.header("content-type") ?: "application/octet-stream"
                    val mime = type.split(";")[0].trim()
                    android.webkit.WebResourceResponse(
                        mime,
                        charsetOf(type),
                        resp.code,
                        if (resp.message.isNullOrEmpty()) "OK" else resp.message,
                        emptyMap<String, String>(),
                        resp.body?.byteStream(),
                    )
                } catch (th: Throwable) {
                    null
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                // 只对主请求生效，子资源失败不算
                if (request != null && !request.isForMainFrame) return
                AppLog.i("$TAG: load error ${error?.errorCode} ${error?.description}")
            }
        }

        /**
         * 接住页面的 alert / confirm / prompt：不接的话这些弹窗不会显示，
         * 脚本会一直等返回值，表现就是"页面卡死不动"。
         * 这里统一给确定性的结果，保证脚本能继续往下跑。
         */
        wv.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?,
            ): Boolean {
                AppLog.i("$TAG: js alert ${message ?: ""}")
                result?.confirm()
                return true
            }

            override fun onJsConfirm(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?,
            ): Boolean {
                AppLog.i("$TAG: js confirm ${message ?: ""}")
                result?.confirm()
                return true
            }

            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: JsPromptResult?,
            ): Boolean {
                AppLog.i("$TAG: js prompt ${message ?: ""} default=${defaultValue ?: ""}")
                result?.confirm(defaultValue ?: "")
                return true
            }

            override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                if (msg != null) {
                    AppLog.i("$TAG[console]: ${msg.message()} @${msg.lineNumber()}")
                }
                return true
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                // 进度过半时再挂一次 fm：极端情况下 onPageStarted 注入会被页面覆盖
                if (newProgress > 50) injectApi()
            }
        }

        val url = normalize(req.api)
        if (url.isEmpty()) {
            AppLog.i("$TAG: empty url, close")
            finish()
            return
        }
        AppLog.i("$TAG: open $url")
        wv.loadUrl(url)
    }

    /**
     * WebView 设置，对齐影视仓 / appmenu 的 WebHome：
     * 每一项都是实际踩过坑才加的。
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(v: WebView) {
        v.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
        if (Build.VERSION.SDK_INT >= 26) {
            v.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
        }
        if (Build.VERSION.SDK_INT >= 23) v.setOffscreenPreRaster(true)

        val s = v.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        // 页面里的音视频要能自动起播，否则音乐/影视类 html 点了没反应
        s.mediaPlaybackRequiresUserGesture = false
        // 在线页面常是 https 壳里嵌 http 资源，默认 NEVER_ALLOW 会直接拦掉 → 白屏
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.textZoom = 100
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.allowFileAccess = true
        s.allowContentAccess = true
        // 本地 file:// 页面要能取同目录/子目录的资源与 XHR，否则图片和接口全挂
        s.allowFileAccessFromFileURLs = true
        s.allowUniversalAccessFromFileURLs = true
        v.setOverScrollMode(android.view.View.OVER_SCROLL_NEVER)
        v.setScrollBarStyle(android.view.View.SCROLLBARS_INSIDE_OVERLAY)
        v.isFocusable = true
        v.isFocusableInTouchMode = true
        v.requestFocus()

        try {
            val cm = android.webkit.CookieManager.getInstance()
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(v, true)
        } catch (ignored: Throwable) {
        }
    }

    /**
     * 返回键：页面内跳转过就先回上一级，没有历史才关掉本页。
     * 这是"html 二级页面按返回能回到一级页面"的实现点。
     */
    override fun onBackPressed() {
        val wv = webView
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
            return
        }
        super.onBackPressed()
    }

    override fun onDestroy() {
        if (live === this) live = null
        val wv = webView
        webView = null
        try {
            wv?.stopLoading()
            wv?.loadUrl("about:blank")
            wv?.clearHistory()
            wv?.removeAllViews()
            wv?.destroy()
        } catch (ignored: Throwable) {
        }
        super.onDestroy()
    }

    /** 把 fm-api.js 挂进页面（幂等，页面顶层就能用 fm.req） */
    private fun injectApi() {
        val api = readAsset("fm-api.js") ?: return
        val view = webView
        if (view == null) return
        mainHandler.post {
            try {
                @Suppress("DEPRECATION")
                view.evaluateJavascript(api, null)
            } catch (th: Throwable) {
                AppLog.e(TAG, th)
            }
        }
    }

    private fun readAsset(name: String): String? =
        try {
            assets.open(name).bufferedReader().use { it.readText() }
        } catch (th: Throwable) {
            AppLog.e(TAG, th)
            null
        }

    private fun charsetOf(contentType: String?): String {
        if (contentType.isNullOrEmpty()) return "UTF-8"
        val i = contentType.indexOf("charset=")
        if (i < 0) return "UTF-8"
        return contentType.substring(i + "charset=".length)
            .split(";", ",")[0].trim().trim('"').ifEmpty { "UTF-8" }
    }

    /** api → 可 loadUrl 的地址：本地绝对路径转 file://，远程原样返回 */
    private fun normalize(api: String): String {
        val t = api.trim()
        if (t.startsWith("file://") || t.startsWith("http://") || t.startsWith("https://")) return t
        val path = resolveLocalPath(api)
        return if (path != null) Uri.fromFile(File(path)).toString() else t
    }

    /** 复用配置解析里同一套换算：clan://localhost/x 与 file://x 都落到真实路径 */
    private fun resolveLocalPath(api: String): String? {
        var url = api
        val pk = url.indexOf(";pk;")
        if (pk >= 0) url = url.substring(0, pk)
        val q = url.indexOf('?')
        if (q >= 0) url = url.substring(0, q)
        if (url.startsWith("clan://localhost/")) {
            return Environment.getExternalStorageDirectory().absolutePath + "/" +
                Uri.decode(url.substring("clan://localhost/".length))
        }
        if (url.startsWith("file://")) return Uri.decode(url.substring("file://".length))
        return if (url.startsWith("/")) url else null
    }

    private inner class Bridge(private val req: Request) {

        @JavascriptInterface
        fun siteKey(): String = req.siteKey

        @JavascriptInterface
        fun ext(): String = req.ext

        @JavascriptInterface
        fun log(msg: String?) {
            AppLog.i("$TAG[${req.siteKey}]: ${msg ?: ""}")
        }

        /** 同步请求：走 App 网络栈，站点代理开关对它同样生效 */
        @JavascriptInterface
        fun req(url: String?, headers: String?, method: String?, body: String?): String {
            if (url.isNullOrEmpty()) return ""
            return try {
                val builder = okhttp3.Request.Builder().url(url)
                if (!headers.isNullOrEmpty()) {
                    val h = JSONObject(headers)
                    val it = h.keys()
                    while (it.hasNext()) {
                        val k = it.next()
                        builder.header(k, h.optString(k))
                    }
                }
                val m = (method ?: "GET").uppercase()
                val payload = if (body.isNullOrEmpty()) null
                else okhttp3.RequestBody.create(null, body)
                builder.method(m, payload)
                val resp = com.github.catvod.net.OkHttp.client()
                    .newCall(builder.build())
                    .execute()
                val text = resp.body?.string() ?: ""
                resp.close()
                text
            } catch (th: Throwable) {
                AppLog.e(TAG, th)
                ""
            }
        }

        @JavascriptInterface
        fun getVar(key: String?): String =
            try {
                KV.get(varKey(req.siteKey, key), "") ?: ""
            } catch (th: Throwable) {
                ""
            }

        @JavascriptInterface
        fun setVar(key: String?, value: String?) {
            try {
                KV.put(varKey(req.siteKey, key), value ?: "")
            } catch (th: Throwable) {
                AppLog.e(TAG, th)
            }
        }

        /** 展示模式下没有"回传数据"这回事，仅记日志，页面调了也不会出错 */
        @JavascriptInterface
        fun done(result: String?) {
            AppLog.i("$TAG: fm.done ignored in view mode: ${result ?: ""}")
        }
    }

    companion object {
        private const val TAG = "HtmlActivity"

        @Volatile
        var pending: Request? = null

        @Volatile
        var live: HtmlActivity? = null

        /**
         * 拉起页面展示 html。
         *
         * 可以在任意线程调用（Spider 侧跑在 IO 线程），内部统一切主线程。
         * 同站点页面已存活时不再重建 —— 重建会让页面重新加载、闪烁，
         * 也就是用户说的"点了没反应/又重头加载"。
         */
        @JvmStatic
        fun open(siteKey: String, api: String, ext: String) {
            Handler(Looper.getMainLooper()).post {
                try {
                    val act = live
                    if (act != null && !act.isFinishing && !act.isDestroyed) {
                        if (act.curSiteKey == siteKey) {
                            AppLog.i("$TAG: already showing, ignore reopen")
                            return@post
                        }
                        // 换站点：先收掉旧页面
                        live = null
                        act.finish()
                    }
                    pending = Request(siteKey, api, ext)
                    val starter = resolveStarter() ?: return@post
                    val intent = android.content.Intent(starter, HtmlActivity::class.java)
                    if (starter !is Activity) {
                        // 用 Application 启动必须带 NEW_TASK，但会被压到别的 task，
                        // 所以只在实在拿不到栈顶 Activity 时兜底
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    starter.startActivity(intent)
                } catch (th: Throwable) {
                    AppLog.e(TAG, th)
                }
            }
        }

        /** 立刻收掉页面（切站点 / spider 销毁时） */
        @JvmStatic
        fun finishIfRunning(siteKey: String?) {
            pending = null
            val act = live ?: return
            live = null
            act.mainHandler.post {
                try {
                    act.finish()
                } catch (ignored: Throwable) {
                }
            }
        }

        /**
         * 取栈顶 Activity 作为启动上下文，拿不到才退回 Application。
         * 用 Application + NEW_TASK 启动常把页面压到别的 task 后面，
         * 表现就是"页面被首页遮住"。
         */
        private fun resolveStarter(): android.content.Context? {
            val stack = try {
                com.github.avbox.osc.util.ActivityStack.getInstance()
            } catch (th: Throwable) {
                null
            }
            if (stack != null) {
                try {
                    val top = stack.currentActivity()
                    if (!top.isFinishing && !top.isDestroyed) return top
                } catch (th: Throwable) {
                    // 栈为空等，忽略，走下面的兜底
                }
            }
            return com.github.avbox.osc.util.AppContextHolder.context()
        }

        private fun varKey(siteKey: String, key: String?): String =
            "html_var_" + siteKey + "_" + (key ?: "")
    }
}
