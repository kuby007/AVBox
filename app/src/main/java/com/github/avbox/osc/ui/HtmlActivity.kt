package com.github.avbox.osc.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.github.avbox.osc.util.KV
import com.github.avbox.osc.util.AppLog
import org.json.JSONObject
import java.io.File

/**
 * HTML 脚本（type 6）的全屏承载页。
 *
 * 职责只有两个：跑脚本、把结果带回去。UI 由 App 自己画，
 * 分类与宫格数据是脚本 return 出来的，不是 WebView 渲染出来的。
 *
 * 生命周期：HtmlSpider 每次调用都新起一次本页 → 注入 fm-api.js →
 * 调脚本对应的全局函数 → 脚本 return（或调 fm.done）→ 回传 → 本页关闭。
 * 所以进站点会看到它弹一次，之后点宫格再弹一次，符合"用完即走"。
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

        val wv = WebView(this)
        webView = wv
        setContentView(wv, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        // 在线 url 页面常是 https 壳里嵌 http 资源，默认 MIXED_CONTENT_NEVER_ALLOW
        // 会直接拦掉，页面白屏、脚本永远不执行 —— 表现为「url 的 html 打不开」。
        wv.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        wv.settings.loadWithOverviewMode = true
        wv.settings.useWideViewPort = true
        wv.settings.userAgentString = wv.settings.userAgentString
        @Suppress("DEPRECATION")
        wv.settings.allowUniversalAccessFromFileURLs = true
        wv.addJavascriptInterface(Bridge(req), "FmBridge")

        // 脚本不回调时兜底关闭，避免页面常驻挡住操作
        // （常驻模式例外：页面本来就该常驻，等用户按返回，不能自己关）
        if (!req.resident) {
            mainHandler.postDelayed({ settle(null) }, AUTO_CLOSE_MS)
        }

        wv.webViewClient = object : WebViewClient() {
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
             * 把页面里的子资源接到 App 网络栈，让站点代理对 WebView 同样生效 ——
             * WebView 自己是不认 OkHttp 的 proxySelector 的。
             * 取不到就返回 null，交给 WebView 自己加载，不硬拦。
             */
            override fun shouldInterceptRequest(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
            ): android.webkit.WebResourceResponse? {
                val r = request ?: return null
                if (r.method != "GET") return null
                if (r.isForMainFrame) return null // 主文档已由我们走代理取过
                val url = r.url?.toString() ?: return null
                if (!url.startsWith("http://") && !url.startsWith("https://")) return null
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

        val html = loadLocal(req.api)
        if (html != null) {
            // 本地脚本：读正文塞进去，baseUrl 用脚本所在目录，脚本里的相对引用才解析得到
            val base = localBase(req.api)
            wv.loadDataWithBaseURL(base, html, "text/html", "UTF-8", null)
        } else {
            // 远程 url：先用 App 网络栈（走站点代理）把正文取回来再注入。
            // 直接 loadUrl 的话 WebView 走自己的网络栈，代理对它完全无效。
            fetchRemoteThenLoad(wv, req)
        }
    }

    /**
     * 远程 html：走 OkHttp（挂了代理选择器）下载正文，再以原 url 为 baseUrl 注入。
     * 这样页面正文和页面里的子资源都走同一套代理规则；
     * 取不到正文（比如纯动态渲染的页面）才退回直接 loadUrl。
     */
    private fun fetchRemoteThenLoad(wv: WebView, req: Request) {
        val url = req.api
        Thread {
            var text: String? = null
            var charset = "UTF-8"
            try {
                val resp = com.github.catvod.net.OkHttp.client()
                    .newCall(okhttp3.Request.Builder().url(url).build())
                    .execute()
                charset = charsetOf(resp.header("content-type"))
                text = resp.body?.string()
                resp.close()
            } catch (th: Throwable) {
                AppLog.e(TAG, th)
            }
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                val view = webView ?: return@post
                if (text.isNullOrEmpty()) {
                    AppLog.i("$TAG: remote html empty, fallback to loadUrl")
                    view.loadUrl(url)
                } else {
                    view.loadDataWithBaseURL(url, text, "text/html", charset, null)
                }
            }
        }.start()
    }

    private fun charsetOf(contentType: String?): String {
        if (contentType.isNullOrEmpty()) return "UTF-8"
        val i = contentType.indexOf("charset=")
        if (i < 0) return "UTF-8"
        return contentType.substring(i + "charset=".length)
            .split(";", ",")[0].trim().trim('"').ifEmpty { "UTF-8" }
    }

    override fun onDestroy() {
        if (live === this) live = null
        mainHandler.removeCallbacks(idleClose)
        val left = currentReq
        currentReq = null
        left?.callback?.invoke(null)
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    /** 只把 fm-api.js 挂进页面，不调用任何脚本函数（供 onPageStarted 用） */
    private fun injectApiOnly() {
        val api = readAsset("fm-api.js") ?: return
        val view = webView ?: return
        mainHandler.post {
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
            finish()
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

    private fun loadLocal(api: String): String? {
        val path = resolveLocalPath(api) ?: return null
        return try {
            val f = File(path)
            if (f.exists()) f.readText() else null
        } catch (th: Throwable) {
            null
        }
    }

    private fun localBase(api: String): String {
        val path = resolveLocalPath(api) ?: return "file:///android_asset/"
        val f = File(path)
        return "file://" + (f.parent ?: "/") + "/"
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
