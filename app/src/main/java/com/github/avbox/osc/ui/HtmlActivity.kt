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
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceResponse
import com.github.avbox.osc.util.KV
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.HtmlUrl
import org.json.JSONObject
import java.io.File

/**
 * HTML 站点的全屏承载页（type 3 内置 Csp）。
 *
 * 【定位】html 是【被打开的应用】，不是【数据源】—— 与 appmenu / WvWv 的思路一致：
 * 分类与宫格由宿主造（一张「启动脚本」卡），页面本身不回传任何数据。
 * 用户点这张卡 → 拉起本页打开页面 → 页面常驻，用户自己操作 → 按返回关闭。
 * 页面被误关后再点卡可再次拉起（二次启动）。
 *
 * 【WebView 设置参照 appmenu 的 WebHome.Overlay】我们是宿主，不依赖子程序，
 * 但 WebView 该怎么养是通用工程经验，主流做法缺一项都可能让页面白屏或卡死：
 *   · 必须 setWebChromeClient —— 缺了它 alert/prompt/console 全不工作，
 *     不少脚本卡在等一个弹窗上，表现就是「打开后卡死」。
 *   · 必须 setMediaPlaybackRequiresUserGesture(false) —— 否则页面内音视频要手动点。
 *   · 必须开 Cookie（含第三方）—— 登录态与同域资源取不到。
 *   · 返回必须先问 WebView.canGoBack() —— 否则 html 的二级页按返回直接关掉整个页，
 *     回不到一级页。
 *   · 关闭时按 stopLoading → 卸桥 → about:blank → clearHistory → destroy 的顺序收，
 *     直接 destroy 会让 WebView 在渲染线程上未决崩溃。
 */
class HtmlActivity : Activity() {

    data class Request(
        val siteKey: String,
        val api: String,
        val ext: String,
        val method: String,
        val args: String,
        /** 常驻模式：html 是【被打开的应用】，只加载页面、不调用脚本函数、
         *  不等回传、不自动关闭，由用户按返回关闭。 */
        val resident: Boolean = false,
        /** 站点代理地址；非空才接管子资源，为空一律交给 WebView 原生加载。 */
        val proxy: String = "",
        val callback: (String?) -> Unit = {},
    )

    private var webView: WebView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var settled = false
    private var currentReq: Request? = null
    private var curSiteKey: String? = null
    /** 每次调用只允许注入执行一次：onPageFinished 会因重定向/iframe 触发多次，
     *  重复执行会让脚本回调两次，Spider 侧拿到第二次的空结果。 */
    private var invoked = false
    private var destroyed = false
    private var loadToken = 0
    private var loadTimeoutRecoveries = 0
    private var loadTimeoutRunnable: Runnable? = null
    private var autoCloseRunnable: Runnable? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val req = pending
        if (req == null) {
            finish()
            return
        }
        pendingRequest = req
        currentReq = req
        curSiteKey = req.siteKey
        live = this

        val root = android.widget.FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        // 复用进程级 WebView：首次点开才付初始化成本，之后 attach + loadUrl 即可
        // 每次承载页持有自己的 WebView 实例，返回时完整销毁。
        // 不再复用实例：复用时 about:blank 异步未完成就下一次 loadUrl，
        // 旧页面的 JS 定时器 / 音视频 / 未决回调会继续活着（脚本没死透）。
        val wv = WebViewPool.attach(this) ?: WebView(this)
        webView = wv
        root.addView(
            wv,
            android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        setContentView(root)

        setupWebView(wv, req)
        load(wv, req)
    }

    /**
     * WebView 设置：逐项对齐主流 WebView Spider 的成熟配置。
     * 少一项都可能是「白屏 / 卡死 / 取不到数据」的直接原因。
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(v: WebView, req: Request) {
        v.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
        }

        val s = v.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        // 页面内音视频不要求用户先点击 —— 音乐/播放器类 html 全靠这个
        s.mediaPlaybackRequiresUserGesture = false
        // 在线页面常是 https 壳里嵌 http 资源，默认 NEVER_ALLOW 会直接拦掉 → 白屏
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.textZoom = 100
        // 统一 LOAD_DEFAULT：缓存优先会把一次失败的空响应/半成品缓存住，
        // 下次直接白屏 —— 这正是「有些在线页面加载不出来」的根因。
        // WebView 仍按 HTTP 缓存头走正常缓存，不需要宿主额外改策略。
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.allowFileAccess = true
        s.allowContentAccess = true
        s.allowFileAccessFromFileURLs = true
        @Suppress("DEPRECATION")
        s.allowUniversalAccessFromFileURLs = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            s.offscreenPreRaster = true
        }

        v.setBackgroundColor(Color.BLACK)
        v.overScrollMode = View.OVER_SCROLL_NEVER
        v.scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        v.isFocusable = true
        v.isFocusableInTouchMode = true
        v.requestFocus()

        try {
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                cm.setAcceptThirdPartyCookies(v, true)
            }
        } catch (ignored: Throwable) {
        }

        // 缺了这个 alert/prompt/confirm 全部静默失效，脚本卡在等交互上 → 看起来「卡死」
        v.webChromeClient = WebChromeClient()
        // 复用实例上可能还挂着上一轮的桥（inner class 持有旧 req），先卸再装
        @Suppress("DEPRECATION")
        v.removeJavascriptInterface("FmBridge")
        v.addJavascriptInterface(Bridge(req), "FmBridge")

        v.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val u = url ?: return true
                // http/https/file 交给 WebView 自己加载；其余（自定义 scheme）拦下
                return !(u.startsWith("http://") || u.startsWith("https://")
                        || u.startsWith("file://"))
            }

            override fun onPageStarted(
                view: WebView?,
                url: String?,
                favicon: android.graphics.Bitmap?,
            ) {
                // 提前把 fm 挂上：有些脚本在页面顶层就要用 fm.req 预热数据，
                // 等 onPageFinished 才注入的话那段代码已经因 fm undefined 崩掉了
                injectApiOnly()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                cancelLoadTimeout()
                // 常驻模式：只保证 fm 桥在页面上，不调用任何脚本函数
                if (req.resident) injectApiOnly() else injectAndInvoke(req)
            }

            /**
             * 只接管 http/https 【主文档】，由宿主共享 OkHttp 代取（对齐 webhtv 的
             * WebHomeRawAdapter）。子资源一律交回 WebView 原生栈——逐资源同步请求会
             * 把 WebView 的 IO 线程打满，那是旧实现卡死的根因，不能重蹈。
             *
             * 代取的收益不在「更快」，在「更稳」：共享连接池、明确超时、主帧强制
             * text/html、剥掉 CSP / X-Frame-Options / COEP 等会阻断渲染的响应头，
             * 失败给可见的 502 页而不是白屏干等。
             */
            override fun shouldInterceptRequest(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
            ): WebResourceResponse? {
                val r = request ?: return null
                if (!r.isForMainFrame) return null
                val url = r.url?.toString() ?: return null
                if (!url.startsWith("http://") && !url.startsWith("https://")) return null
                // 本地页面走宿主自己的文件服务，MIME 已正确，不代取
                if (url.contains("127.0.0.1") || url.contains("localhost") || url.contains("[::1]")) return null
                return fetchMainFrame(url, r.requestHeaders)
            }

            // 在线 url 打不开（DNS/404/证书…）时立刻交付，别让 Spider 干等 30 秒超时
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?,
            ) {
                AppLog.i("$TAG: load error $errorCode ${description ?: ""}")
                // 常驻模式不因加载失败关页：页面留着，用户自己返回；
                // 但必须提示，否则就是一片白屏干等，看起来像「加载不出来」
                if (!req.resident) settle(null) else showLoadError(description)
            }

            override fun onReceivedError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                // Android 6+ 走这个重载；只对主请求生效，子资源失败不算
                if (request != null && !request.isForMainFrame) return
                AppLog.i("$TAG: load error ${error?.errorCode} ${error?.description}")
                if (!req.resident) settle(null) else showLoadError(error?.description?.toString())
            }

            /** 渲染进程崩溃时自愈：重建 WebView 后重载，避免白屏停在死页面上。 */
            @Suppress("NewApi")
            override fun onRenderProcessGone(
                view: WebView?,
                detail: android.webkit.RenderProcessGoneDetail?,
            ): Boolean {
                AppLog.i("$TAG: render process gone, recreate")
                if (loadTimeoutRecoveries++ <= 1) {
                    mainHandler.post { recreateWebView() }
                } else {
                    showLoadError("渲染进程异常")
                }
                return true
            }
        }

        // 脚本不回调时兜底关闭，避免页面常驻挡住操作
        // （常驻模式例外：页面本来就该常驻，等用户按返回，不能自己关）
        if (!req.resident) {
            autoCloseRunnable?.let { mainHandler.removeCallbacks(it) }
            val r = Runnable { settle(null) }
            autoCloseRunnable = r
            mainHandler.postDelayed(r, AUTO_CLOSE_MS)
        }
    }

    /**
     * 加载页面：地址一律先经 [HtmlUrl.normalize] 归一化。
     *
     * 本地文件（file:// / clan:// / 裸绝对路径 / 回环 /file/）统一换算成宿主文件服务的
     * http 地址 —— baseURI 正确、页内 fetch/XHR 不被 file:// 的 CORS 拦掉、MIME 也正确；
     * 远程 http/https 原样交给 WebView，并享受缓存优先。
     */
    private fun load(wv: WebView, req: Request) {
        val page = HtmlUrl.normalize(req.api)
        if (page.pageUrl.isEmpty()) {
            AppLog.i("$TAG: invalid api=${req.api}")
            if (!req.resident) settle(null)
            return
        }
        try {
            scheduleLoadTimeout()
            wv.loadUrl(page.pageUrl)
        } catch (th: Throwable) {
            AppLog.e(TAG, th)
            cancelLoadTimeout()
            if (!req.resident) settle(null)
        }
    }

    /* ---------------- 主文档代取 / 超时自愈（webhtv 对齐） ---------------- */

    private fun scheduleLoadTimeout() {
        loadTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        val token = ++loadToken
        val r = Runnable { handleLoadTimeout(token) }
        loadTimeoutRunnable = r
        mainHandler.postDelayed(r, LOAD_TIMEOUT_MS)
    }

    private fun cancelLoadTimeout() {
        loadTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        loadTimeoutRunnable = null
    }

    private fun handleLoadTimeout(token: Int) {
        if (token != loadToken || destroyed || isFinishing || isDestroyed) return
        if (loadTimeoutRecoveries++ > 0) {
            showLoadError("加载超时")
            return
        }
        AppLog.i("$TAG: load timeout, recreate WebView")
        recreateWebView()
    }

    /** 超时或渲染进程崩溃后重建 WebView：销毁旧实例 → 新建 → 重新装配 → 重载。 */
    private fun recreateWebView() {
        val old = webView ?: return
        val root = old.parent as? ViewGroup ?: return
        val index = root.indexOfChild(old)
        val lp = old.layoutParams
        try {
            old.stopLoading()
            @Suppress("DEPRECATION")
            old.removeJavascriptInterface("FmBridge")
            old.onPause()
            root.removeView(old)
            old.loadUrl("about:blank")
            old.clearHistory()
            old.removeAllViews()
            old.destroy()
        } catch (ignored: Throwable) {
        }
        webView = null
        val req = currentReq ?: pendingRequest
        if (req == null || destroyed || isFinishing || isDestroyed) return
        val wv = WebView(this)
        webView = wv
        root.addView(wv, maxOf(0, index), lp)
        invoked = false
        setupWebView(wv, req)
        load(wv, req)
    }

    /**
     * 主文档代取。请求头过滤掉逐跳字段；响应头剥掉会阻断渲染的头；
     * 主帧强制 text/html，Set-Cookie 交回 CookieManager 保住登录态。
     */
    private fun fetchMainFrame(url: String, webHeaders: Map<String, String>?): WebResourceResponse {
        var resp: okhttp3.Response? = null
        return try {
            val builder = okhttp3.Request.Builder().url(url)
            var hasUa = false
            webHeaders?.forEach { (k, v) ->
                val lk = k.lowercase()
                if (lk in BLOCK_REQ_HEADERS) return@forEach
                if (lk == "user-agent") hasUa = true
                builder.header(k, v)
            }
            if (!hasUa) builder.header("User-Agent", DEFAULT_UA)
            val client = com.github.catvod.net.OkHttp.client(MAIN_FRAME_TIMEOUT_MS)
            resp = client.newCall(builder.get().build()).execute()
            val response = resp!!
            val headers = HashMap<String, String>()
            for (name in response.headers.names()) {
                if (name.lowercase() in BLOCK_RESP_HEADERS) continue
                headers[name] = response.headers.values(name).joinToString(", ")
            }
            val cookies = response.headers.values("Set-Cookie")
            if (cookies.isNotEmpty()) {
                mainHandler.post {
                    try {
                        val cm = CookieManager.getInstance()
                        cookies.forEach { cm.setCookie(url, it) }
                    } catch (ignored: Throwable) {
                    }
                }
            }
            headers["Content-Type"] = "text/html; charset=UTF-8"
            if (!headers.containsKey("Cache-Control")) headers["Cache-Control"] = "no-store"
            val rawCode = response.code
            val code = if (rawCode in 100..599) rawCode else 502
            val status = if (code in 300..399) 200 else code
            val stream = response.body?.byteStream()
                ?: java.io.ByteArrayInputStream(ByteArray(0))
            WebResourceResponse(
                "text/html", "UTF-8", status,
                if (status == 200) "OK" else "HTTP $status",
                headers, stream,
            )
        } catch (th: Throwable) {
            try { resp?.close() } catch (ignored: Throwable) {}
            AppLog.i("$TAG: main frame fetch failed ${th.message}")
            val msg = (th.message ?: "网络错误").replace("<", "&lt;")
            val html = "<!doctype html><meta charset=utf-8><body style='background:#111;color:#ddd;font:16px sans-serif;padding:24px'>页面加载失败：$msg</body>"
            WebResourceResponse(
                "text/html", "UTF-8", 502, "Bad Gateway",
                mapOf("Content-Type" to "text/html; charset=UTF-8", "Cache-Control" to "no-store"),
                java.io.ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)),
            )
        }
    }

    private fun showLoadError(desc: String?) {
        mainHandler.post {
            if (destroyed) return@post
            try {
                android.widget.Toast.makeText(
                    this@HtmlActivity,
                    "页面加载失败：${desc ?: "网络错误"}",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun charsetOf(contentType: String?): String {
        if (contentType.isNullOrEmpty()) return "UTF-8"
        val i = contentType.indexOf("charset=")
        if (i < 0) return "UTF-8"
        return contentType.substring(i + "charset=".length)
            .split(";", ",")[0].trim().trim('"').ifEmpty { "UTF-8" }
    }

    /** 对齐 webhtv：页面不可见立即暂停 WebView，JS 定时器 / 音视频不会继续跑。 */
    override fun onPause() {
        try { webView?.onPause() } catch (ignored: Throwable) {}
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        try {
            webView?.onResume()
            webView?.resumeTimers()
        } catch (ignored: Throwable) {}
    }

    /**
     * 返回键：先看 WebView 内部有没有上一级页面。
     * 缺这一步的话，html 的二级页按返回会直接把整个承载页关掉，
     * 根本回不到一级页 —— 这是之前「二级页面返回不到一级」的直接原因。
     */
    @Suppress("MissingSuperCall")
    override fun onBackPressed() {
        val wv = webView
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
            return
        }
        // 不依赖 super（各版本行为不一，且可能被预测式返回吃掉），直接收掉自己，
        // 保证「返回一定回到首页宫格」，不会停在过渡页上
        finishSafely()
    }

    /** 兜底：部分机型/全屏场景下 onBackPressed 不触发，这里再接一次 */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            val wv = webView
            if (wv != null && wv.canGoBack()) {
                wv.goBack()
                return true
            }
            finishSafely()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * 承载页结束时只解绑、不销毁：进程级 WebView 留给下一次点开复用。
     * 只有 WebViewPool 不可用、本页自己 new 的那一个才按原顺序 destroy。
     */
    private fun releaseWebView() {
        val wv = webView ?: return
        webView = null
        try {
            wv.stopLoading()
            @Suppress("DEPRECATION")
            wv.removeJavascriptInterface("FmBridge")
            wv.webViewClient = object : WebViewClient() {}
            wv.webChromeClient = WebChromeClient()
            wv.onPause()
            wv.loadUrl("about:blank")
            wv.clearHistory()
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.removeAllViews()
            wv.destroy()
        } catch (ignored: Throwable) {
        }
    }

    private fun finishSafely() {
        mainHandler.removeCallbacks(idleClose)
        // 立刻断开静态引用：finish 到 onDestroy 之间还有窗口，
        // 旧引用若被 bringToFront 命中就会把没死透的页面拉回前台
        if (live === this) live = null
        pending = null
        pendingRequest = null
        val left = currentReq
        currentReq = null
        try {
            left?.callback?.invoke(null)
        } catch (th: Throwable) {
            AppLog.e(TAG, th)
        }
        try {
            if (!isFinishing && !isDestroyed) finish()
        } catch (ignored: Throwable) {
        }
    }

    override fun onDestroy() {
        destroyed = true
        if (live === this) live = null
        pending = null
        pendingRequest = null
        cancelLoadTimeout()
        autoCloseRunnable?.let { mainHandler.removeCallbacks(it) }
        mainHandler.removeCallbacks(idleClose)
        val left = currentReq
        currentReq = null
        try {
            left?.callback?.invoke(null)
        } catch (th: Throwable) {
            AppLog.e(TAG, th)
        }
        releaseWebView()
        super.onDestroy()
    }

    /** 只把 fm-api.js 挂进页面，不调用任何脚本函数（供 onPageStarted 用） */
    private fun injectApiOnly() {
        // 每次 onPageStarted / onPageFinished 都挂一次：重定向会重建 JS 上下文，
        // 只注入一次会让新页面丢掉 fm。fm-api.js 自身幂等（window.fm 已存在则跳过），
        // 重复注入不会重复执行脚本回调，开销可忽略。
        val api = readAsset("fm-api.js") ?: return
        val view = webView ?: return
        mainHandler.post {
            if (destroyed) return@post
            try {
                @Suppress("DEPRECATION")
                view.evaluateJavascript(api, null)
            } catch (th: Throwable) {
                AppLog.e(TAG, th)
            }
        }
    }

    private fun injectAndInvoke(req: Request) {
        if (invoked) return
        invoked = true
        val api = readAsset("fm-api.js") ?: ""
        val argsArray = argsToArray(req.method, req.args)
        val script = """
            (function(){
              try { $api } catch(e) {}
              // 复用页面时 fm 已存在，必须先复位回传闸门，否则 fm.done() 被吞
              try { if (window.fm && typeof window.fm.reset === 'function') window.fm.reset(); } catch(e) {}
              var fin = function(v){ try { window.fm && window.fm.done(v); } catch(e) {} };
              try {
                var fn = window['${req.method}'];
                if (typeof fn !== 'function') { fin({}); return; }
                var r = fn.apply(null, $argsArray);
                // 脚本写成 async / 返回 Promise 时，直接 done(Promise) 会被
                // JSON.stringify 成 {} —— 必须等它落地再取真实值
                if (r && typeof r.then === 'function') {
                  Promise.resolve(r).then(function(v){
                    fin(v === undefined || v === null ? {} : v);
                  }).catch(function(e){
                    try { window.fm && window.fm.log('invoke error: ' + e); } catch(e2) {}
                    fin({});
                  });
                  return;
                }
                if (r !== undefined && r !== null) { fin(r); return; }
                // 同步无返回值：交给脚本自己 fm.done()，这里什么都不做
              } catch(e) {
                try { window.fm && window.fm.log('invoke error: ' + e); } catch(e2) {}
                fin({});
              }
            })()
        """.trimIndent()
        val view = webView ?: return
        mainHandler.post {
            if (destroyed) return@post
            try {
                @Suppress("DEPRECATION")
                view.evaluateJavascript(script, null)
            } catch (th: Throwable) {
                AppLog.e(TAG, th)
                settle(null)
            }
        }
    }

    /** 把 Spider 侧的 JSONObject 还原成脚本函数的实参列表（按 TVBox html 约定的签名顺序） */
    private fun argsToArray(method: String, args: String): String {
        val o = try {
            JSONObject(args)
        } catch (th: Throwable) {
            return "[]"
        }
        return try {
            when (method) {
                "homeContent" -> org.json.JSONArray().put(o.optBoolean("filter")).toString()
                "homeVideoContent" -> "[]"
                "categoryContent" -> org.json.JSONArray()
                    .put(o.optString("tid"))
                    .put(o.optString("pg"))
                    .put(o.optBoolean("filter"))
                    .put(o.optString("extend")).toString()
                "detailContent" -> org.json.JSONArray().put(o.optJSONArray("ids") ?: org.json.JSONArray()).toString()
                "searchContent" -> org.json.JSONArray()
                    .put(o.optString("key"))
                    .put(o.optBoolean("quick")).toString()
                "playerContent" -> org.json.JSONArray()
                    .put(o.optString("flag"))
                    .put(o.optString("id"))
                    .put(o.optJSONArray("vipFlags") ?: org.json.JSONArray()).toString()
                else -> org.json.JSONArray().put(o).toString()
            }
        } catch (th: Throwable) {
            "[]"
        }
    }

    /**
     * 交付结果但【不关页】：同一站点的后续调用（翻页、点宫格…）继续复用本页。
     * 之前是"每次调用都 startActivity → finish"，首页加载要跑
     * homeContent / homeVideoContent / categoryContent 三次，于是反复前后台切换。
     */
    private fun settle(result: String?) {
        val req = currentReq ?: pendingRequest ?: return
        currentReq = null
        pendingRequest = null
        try {
            req.callback.invoke(result)
        } catch (th: Throwable) {
            AppLog.e(TAG, th)
        }
        // 常驻页面交付后继续活着，不排自动关闭
        if (req.resident) {
            mainHandler.removeCallbacks(idleClose)
            return
        }
        mainHandler.removeCallbacks(idleClose)
        mainHandler.postDelayed(idleClose, IDLE_CLOSE_MS)
    }

    private val idleClose = Runnable { finishNow() }

    private fun finishNow() {
        mainHandler.removeCallbacks(idleClose)
        val left = currentReq
        currentReq = null
        try {
            left?.callback?.invoke(null)
        } catch (th: Throwable) {
            AppLog.e(TAG, th)
        }
        try {
            if (!isFinishing && !isDestroyed) finish()
        } catch (ignored: Throwable) {
        }
    }

    /** 复用本页再跑一次脚本（仅 siteKey 相同时调用） */
    private fun dispatch(req: Request) {
        mainHandler.removeCallbacks(idleClose)
        currentReq = req
        curSiteKey = req.siteKey
        invoked = false   // 复用本页跑下一次调用，允许再注入一次
        injectAndInvoke(req)
    }

    private fun readAsset(name: String): String? {
        fmApiCache?.let { return it }
        return try {
            val text = assets.open(name).bufferedReader().use { it.readText() }
            fmApiCache = text
            text
        } catch (th: Throwable) {
            AppLog.e(TAG, th)
            null
        }
    }

    private inner class Bridge(private val req: Request) {

        @JavascriptInterface
        fun siteKey(): String = req.siteKey

        @JavascriptInterface
        fun ext(): String = req.ext

        /** 同源网关地址（宿主自己的文件服务端口），供 fm.res() 转跨域请求。 */
        @JavascriptInterface
        fun gatewayBase(): String = try {
            com.github.avbox.osc.server.ControlManager.get().getAddress(true)
        } catch (th: Throwable) {
            ""
        }

        @JavascriptInterface
        fun log(msg: String?) {
            AppLog.i("$TAG[${req.siteKey}]: ${msg ?: ""}")
        }

        /** 同步请求：走 App 网络栈，站点代理开关对它同样生效 */
        @JavascriptInterface
        fun req(url: String?, headers: String?, method: String?, body: String?): String {
            if (url.isNullOrEmpty()) return ""
            return try {
                val client = com.github.catvod.net.OkHttp.client()
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
                val resp = client.newCall(builder.build()).execute()
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

        @JavascriptInterface
        fun done(result: String?) {
            mainHandler.post { settle(result) }
        }
    }

    companion object {
        private const val TAG = "HtmlActivity"
        private const val AUTO_CLOSE_MS = 30_000L
        private const val IDLE_CLOSE_MS = 8_000L
        private const val LOAD_TIMEOUT_MS = 15_000L
        private const val MAIN_FRAME_TIMEOUT_MS = 15_000L
        private const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 12; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        private val BLOCK_REQ_HEADERS = setOf(
            "host", "connection", "content-length", "content-encoding",
            "accept-encoding", "if-none-match", "if-modified-since", "range",
        )
        private val BLOCK_RESP_HEADERS = setOf(
            "connection", "transfer-encoding", "keep-alive", "content-length",
            "content-encoding", "content-type", "content-disposition",
            "set-cookie", "set-cookie2",
            "content-security-policy", "content-security-policy-report-only",
            "x-content-type-options", "x-frame-options",
            "cross-origin-embedder-policy", "cross-origin-opener-policy",
            "cross-origin-resource-policy",
        )

        @Volatile
        private var fmApiCache: String? = null

        @Volatile
        var pending: Request? = null

        @Volatile
        private var pendingRequest: Request? = null

        @Volatile
        var live: HtmlActivity? = null

        /** 站点相同则复用已存活的页面再跑一次，返回 true 表示已投递、无需新建 */
        @JvmStatic
        fun dispatchToLive(req: Request): Boolean {
            val act = live ?: return false
            if (act.isFinishing || act.isDestroyed) {
                live = null
                return false
            }
            if (act.curSiteKey != req.siteKey) return false
            act.dispatch(req)
            return true
        }

        /**
         * 【已停用】旧 type 6 遗留的 HtmlSpider 仍会调用，保留签名以免编译断裂，
         * 但一律返回 false —— 不再把旧页面 reorder 到前台。返回宫格后旧实例必须
         * 已经死透，每次点开都是干净的新实例（webhtv 生命周期对齐）。
         */
        @JvmStatic
        fun bringToFront(siteKey: String): Boolean = false

        /** 立刻收掉页面（切站点 / spider 销毁时） */
        @JvmStatic
        fun finishIfRunning(siteKey: String?) {
            pending = null
            val act = live ?: return
            live = null
            act.mainHandler.post { act.finishNow() }
        }

        private fun varKey(siteKey: String, key: String?): String =
            "html_var_" + siteKey + "_" + (key ?: "")

    }
}
