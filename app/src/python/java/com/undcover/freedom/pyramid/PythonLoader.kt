package com.undcover.freedom.pyramid

import android.app.Application
import android.content.Context

import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.github.catvod.Proxy
import com.github.catvod.crawler.Spider
import com.github.catvod.crawler.SpiderNull
import com.github.catvod.crawler.SpiderReaper
import com.github.catvod.net.OkHttp
import com.github.avbox.osc.BuildConfig
import com.github.avbox.osc.net.HttpClients
import com.github.avbox.osc.util.AppLog

import org.json.JSONException
import org.json.JSONObject

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

class PythonLoader {
    private val spiders = ConcurrentHashMap<String, Spider>()

    @Volatile
    private var generation: Long = 0

    fun invalidate() {
        generation++
        val stale = ArrayList(spiders.values)
        spiders.clear()
        for (spider in stale) {
            SpiderReaper.retire(spider)
        }
    }

    @JvmField
    var pyInstance: Python? = null

    @JvmField
    var pyApp: PyObject? = null

    @JvmField
    var androidPlatform: Python.Platform? = null

    private var app: Application? = null
    private val siteMap = HashMap<String, JSONObject>()

    fun clear() {
        val stale = ArrayList(spiders.values)
        spiders.clear()
        siteMap.clear()
        for (spider in stale) {
            SpiderReaper.retire(spider)
        }
    }

    /**
     * 把原生界面能力注入 python 的 builtins，脚本里直接 dialog(...) 就能用。
     * 注入失败只记日志，不影响 python 环境本身。
     */
    private fun installPyBridge(py: Python) {
        try {
            val code = """
                import builtins, json
                from com.undcover.freedom.pyramid import PyDialog

                def dialog(spec=None, **kwargs):
                    d = {}
                    if spec:
                        d.update(spec)
                    if kwargs:
                        d.update(kwargs)
                    r = PyDialog.dialog(json.dumps(d, ensure_ascii=False))
                    if not r:
                        return None
                    return json.loads(r)

                def getActivity():
                    return PyDialog.getActivity()

                def runOnUiThread(fn):
                    return PyDialog.runOnUi(fn)

                builtins.dialog = dialog
                builtins.getActivity = getActivity
                builtins.runOnUiThread = runOnUiThread
            """.trimIndent()
            py.getBuiltins().callAttr("exec", code)
            AppLog.i("PythonLoader: py bridge installed")
        } catch (th: Throwable) {
            AppLog.e("PythonLoader", th)
        }
    }

    /**
     * 宿主兼容加固：放行子程序常用的 ActivityThread 相关 hidden API。
     *
     * 部分 py 工具脚本不调用宿主注入的 builtins.getActivity()，而是自己反射
     * android.app.ActivityThread 取当前 Activity 来弹原生对话框。targetSdk 37 下
     * hidden API 反射被拒会让它们直接 return「已取消本次弹窗」—— 弹窗根本没被创建。
     * 这里只放行必要前缀，平台不提供该开关时静默跳过，不影响 Python 环境其它功能。
     */
    private fun allowHiddenApis() {
        try {
            val vm = Class.forName("dalvik.system.VMRuntime")
            val getRuntime = vm.getDeclaredMethod("getRuntime")
            getRuntime.isAccessible = true
            val runtime = getRuntime.invoke(null) ?: return
            val setExemptions = vm.getDeclaredMethod(
                "setHiddenApiExemptions", Array<String>::class.java,
            )
            setExemptions.isAccessible = true
            val prefixes = arrayOf(
                "Landroid/app/ActivityThread;",
                "Landroid/app/ActivityThread\$",
                "Landroid/app/Activity;",
                "Landroid/app/Application;",
                "Ldalvik/system/VMRuntime;",
            )
            // 作为单个 String[] 参数传入，不能被 vararg 展开
            setExemptions.invoke(runtime, prefixes as Any)
            AppLog.i("PythonLoader: hidden api exemption installed")
        } catch (th: Throwable) {
            AppLog.i("PythonLoader: hidden api exemption unavailable: ${th.message}")
        }
    }

    private fun setSdk(context: Context) {
        val logLevel = if (BuildConfig.DEBUG) PyLog.LEVEL_V else PyLog.LEVEL_RELEASE
        val logFilter = if (BuildConfig.DEBUG) PyLog.FILTER_NW or PyLog.FILTER_LC else 0
        PyLog.getInstance().setLogLevel(logLevel).setFilter(logFilter)
        PyLog.TagConstant.TAG_APP = "PythonLoader"

        PyToast.init(context)
    }

    fun setConfig(config: String) {
        try {
            siteMap.clear()
            val configJo = JSONObject(config)
            val siteList = configJo.getJSONArray("sites")
            for (i in 0 until siteList.length()) {
                val jo = siteList.getJSONObject(i)
                val key = jo.optString("api")
                siteMap[key] = jo
            }
        } catch (e: JSONException) {
            AppLog.e("PythonLoader", e)
        }
    }

    fun setApplication(app: Application): PythonLoader {
        this.app = app
        setSdk(app)
        if (pyInstance == null) {
            try {
                allowHiddenApis()
                if (!Python.isStarted()) {
                    val platform = AndroidPlatform(app)
                    androidPlatform = platform
                    Python.start(platform)
                }
                val py = Python.getInstance()
                pyInstance = py
                pyApp = py.getModule("app")
                installPyBridge(py)
            } catch (th: Throwable) {
                throw RuntimeException(th)
            }
        }
        val pyCache = File(app.cacheDir, "py")
        if (!pyCache.exists()) pyCache.mkdirs()
        setPluginConfig(pyCache.absolutePath)
        return this
    }

    private var cache = ""

    fun setPluginConfig(config: String?): PythonLoader {
        cache = if (config == null || config.isEmpty()) {
            ""
        } else if (config.endsWith(File.separator)) {
            config
        } else {
            config + File.separator
        }
        return this
    }

    fun getCachePath(): String {
        return cache
    }

    fun getUrlByApi(api: String): String {
        var key = ""
        var url = ""
        if (siteMap.containsKey(api)) {
            val jo = siteMap.getValue(api)
            key = jo.optString("key")
            url = jo.optString("ext")
        }
        if (!key.isEmpty() && !url.isEmpty()) {
            return if (spiders.containsKey(key)) {
                ""
            } else {
                url
            }
        }
        return ""
    }

    fun getSpider(key: String, url: String?): Spider {
        return getSpider(key, url, "")
    }

    fun getSpider(key: String, url: String?, ext: String?): Spider {
        if (app == null) throw Exception("set application first")
        if (spiders.containsKey(key)) {
            PyLog.d("$key :缓存加载成功！")
            return spiders.getValue(key)
        }

        val startGeneration = generation
        val executor = Executors.newSingleThreadExecutor()
        var future: Future<*>? = null
        var sp: PythonSpider? = null
        try {
            sp = PythonSpider(key, cache)
            val spider: PythonSpider = sp

            future = executor.submit {
                try {
                    spider.init(app, url, ext)
                } catch (e: Exception) {
                    AppLog.e("PythonLoader", e)
                }
            }

            future.get(30, TimeUnit.SECONDS)

            if (startGeneration != generation) return SpiderNull()
            if (!spider.isLoadSuccess()) return SpiderNull()
            spiders[key] = spider
            return spider
        } catch (e: TimeoutException) {
            PyLog.e("echo-init方法执行超时")
            val pending = sp
            val initFuture = future
            executor.submit {
                try {
                    requireNotNull(initFuture).get()
                    val done = requireNotNull(pending)
                    if (done.isLoadSuccess() && startGeneration == generation) {
                        spiders.putIfAbsent(key, done)
                    }
                } catch (th: Throwable) {
                    AppLog.e("PyLoader", "python spider init failed", th)
                }
            }
            return SpiderNull()
        } catch (e: ExecutionException) {
            PyLog.e("echo-init:ExecutionException|InterruptedException")
            return SpiderNull()
        } catch (e: InterruptedException) {
            PyLog.e("echo-init:ExecutionException|InterruptedException")
            return SpiderNull()
        } finally {
            executor.shutdown()
        }
    }

    fun localProxyUrl(): String {
        return Proxy.getUrl(true)
    }

    fun str2map(header: String?): Map<String, String> {
        val map = HashMap<String, String>()
        if (header == null || header.isEmpty())
            return map
        try {
            val jo = JSONObject(header)
            val it = jo.keys()
            while (it.hasNext()) {
                val key = it.next()
                val value = jo.optString(key)
                map[key] = value
            }
        } catch (e: JSONException) {
            AppLog.e("PythonLoader", e)
        }
        return map
    }

    fun getFileStream(url: String, param: String, header: String): InputStream? {
        val streamCallback = this.streamCallback
        if (streamCallback != null) {
            return streamCallback.get(url, str2map(param), str2map(header))
        } else {
            try {
                val client = HttpClients.defaultClient ?: OkHttp.client()
                val response = client.newCall(getRequest(url, str2map(param), str2map(header))).execute()
                val body = response.body
                if (body != null) return body.byteStream()
                response.close()
                return ByteArrayInputStream(ByteArray(0))
            } catch (e: Exception) {
                return ByteArrayInputStream(ByteArray(0))
            }
        }
    }

    fun getFileString(url: String, header: String): String {
        val stringCallback = this.stringCallback
        return if (stringCallback != null) {
            stringCallback.get(url, str2map(header))
        } else {
            OkHttp.string(url, str2map(header))
        }
    }

    private fun getRequest(url: String, paramsMap: Map<String, String>?, headerMap: Map<String, String>?): Request {
        var httpUrl = url.toHttpUrlOrNull()
        if (httpUrl != null && paramsMap != null && !paramsMap.isEmpty()) {
            val builder = httpUrl.newBuilder()
            for ((key, value) in paramsMap) {
                builder.addQueryParameter(key, value)
            }
            httpUrl = builder.build()
        }
        val builder = Request.Builder()
        if (httpUrl != null) {
            builder.url(httpUrl)
        } else {
            builder.url(url.toHttpUrl())
        }
        if (headerMap != null) {
            for ((key, value) in headerMap) {
                builder.addHeader(key, value)
            }
        }
        return builder.build()
    }

    private var streamCallback: FileStreamCallback? = null
    private var stringCallback: FileStringCallback? = null

    fun setFileStreamCallback(callback: FileStreamCallback?): PythonLoader {
        streamCallback = callback
        return this
    }

    fun setFileStringCallback(callback: FileStringCallback?): PythonLoader {
        stringCallback = callback
        return this
    }

    interface FileStreamCallback {
        fun get(url: String, paramsMap: Map<String, String>?, headerMap: Map<String, String>?): InputStream?
    }

    interface FileStringCallback {
        fun get(url: String, headerMap: Map<String, String>?): String
    }

    companion object {
        @Volatile
        private var sInstance: PythonLoader? = null

        @JvmStatic
        fun getInstance(): PythonLoader {
            if (sInstance == null) {
                synchronized(PyToast::class.java) {
                    if (sInstance == null) {
                        sInstance = PythonLoader()
                    }
                }
            }
            return requireNotNull(sInstance)
        }
    }
}
