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
import com.github.avbox.osc.util.KV
import com.github.avbox.osc.util.AppLog
import org.json.JSONObject
import java.io.File

/**
 * HTML 脚本（type 6）的全屏承载页。
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
        val wv = WebView(this)
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
                // 常驻模式：只保证 fm 桥在页面上，不调用任何脚本函数
                if (req.resident) injectApiOnly() else injectAndInvoke(req)
            }

            /**
             * 子资源接管：只在【站点开了代理】时才接。
             *
             * WebView 不认 OkHttp 的 proxySelector，代理对它无效，所以要接管。
             * 但接管是同步阻塞在 WebView 的 IO 线程上的——页面资源一多就把线程占满，
             * 表现正是「打开后卡死」。没配代理的站点根本不需要接管，
             * 一律放行给 WebView 原生，既快又不卡。
             */
            override fun shouldInterceptRequest(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
            ): android.webkit.WebResourceResponse? {
                val r = request ?: return null
                if (req.proxy.isBlank()) return null
                if (r.method != "GET") return null
                val url = r.url?.toString() ?: return null
                if (!url.startsWith("http://") && !url.startsWith("https://")) return null
                // 回环地址不能接管，否则自己请求自己 → 死循环
                val host = r.url?.host
                if (host == "127.0.0.1" || host == "localhost") return null
                return try {
                    val resp = com.github.catvod.net.OkHttp.client()
                        .newCall(okhttp3.Request.Builder().url(url).build())
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

            // 在线 url 打不开（DNS/404/证书…）时立刻交付，别让 Spider 干等 30 秒超时
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?,
            ) {
                AppLog.i("$TAG: load error $errorCode ${description ?: ""}")
                // 常驻模式不因加载失败关页：页面留着，用户自己返回
                if (!req.resident) settle(null)
            }

            override fun onReceivedError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                // Android 6+ 走这个重载；只对主请求生效，子资源失败不算
                if (request != null && !request.isForMainFrame) return
                AppLog.i("$TAG: load error ${error?.errorCode} ${error?.description}")
                if (!req.resident) settle(null)
            }
        }

        // 脚本不回调时兜底关闭，避免页面常驻挡住操作
        // （常驻模式例外：页面本来就该常驻，等用户按返回，不能自己关）
        if (!req.resident) {
            mainHandler.postDelayed({ settle(null) }, AUTO_CLOSE_MS)
        }
    }

    /**
     * 加载页面。本地文件直接 loadUrl("file://...")，远程直接 loadUrl(url)。
     *
     * 【为什么不再 loadDataWithBaseURL 塞正文】塞进去的页面 baseURI 会失真，
     * 页面里的相对引用和 fetch 全被打死 —— 表现就是「只出框架、取不到数据」。
     * 主流做法就是 loadUrl，baseURI 天然正确。
     */
    private fun load(wv: WebView, req: Request) {
        val url = req.api
        try {
            when {
                url.startsWith("http://") || url.startsWith("https://") -> wv.loadUrl(url)
                url.startsWith("file://") -> wv.loadUrl(url)
                url.startsWith("/") -> wv.loadUrl("file://$url")
                url.startsWith("clan://localhost/") -> {
                    val p = Environment.getExternalStorageDirectory().absolutePath + "/" +
                        Uri.decode(url.substring("clan://localhost/".length))
                    wv.loadUrl("file://$p")
                }
                else -> {
                    AppLog.i("$TAG: invalid api=$url")
                    if (!req.resident) settle(null)
                }
            }
        } catch (th: Throwable) {
            AppLog.e(TAG, th)
            if (!req.resident) settle(null)
        }
    }

    private fun charsetOf(contentType: String?): String {
        if (contentType.isNullOrEmpty()) return "UTF-8"
        val i = contentType.indexOf("charset=")
        if (i < 0) return "UTF-8"
        return contentType.substring(i + "charset=".length)
            .split(";", ",")[0].trim().trim('"').ifEmpty { "UTF-8" }
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

    /** 按主流顺序收掉 WebView，避免渲染线程上未决崩溃 */
    private fun releaseWebView() {
        val wv = webView ?: return
        webView = null
        try {
            wv.stopLoading()
            @Suppress("DEPRECATION")
            wv.removeJavascriptInterface("FmBridge")
            wv.loadUrl("about:blank")
            wv.clearHistory()
            wv.removeAllViews()
            wv.destroy()
        } catch (ignored: Throwable) {
        }
    }

    private fun finishSafely() {
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

    override fun onDestroy() {
        destroyed = true
        if (live === this) live = null
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

    private fun readAsset(name: String): String? =
        try {
            assets.open(name).bufferedReader().use { it.readText() }
        } catch (th: Throwable) {
            AppLog.e(TAG, th)
            null
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

        /** 页面已存活且是同一站点：把它调到前台，不再新建。返回 true 表示已处理。 */
        @JvmStatic
        fun bringToFront(siteKey: String): Boolean {
            val act = live ?: return false
            if (act.isFinishing || act.isDestroyed) {
                live = null
                return false
            }
            if (act.curSiteKey != siteKey) return false
            return try {
                val i = android.content.Intent(act, HtmlActivity::class.java)
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                act.startActivity(i)
                true
            } catch (th: Throwable) {
                AppLog.e(TAG, th)
                false
            }
        }

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
