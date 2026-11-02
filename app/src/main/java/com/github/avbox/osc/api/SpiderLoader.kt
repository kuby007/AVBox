package com.github.avbox.osc.api

import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Base64

import com.github.catvod.crawler.JarLoader
import com.github.catvod.crawler.HtmlSpider
import com.github.catvod.crawler.JsLoader
import com.github.catvod.crawler.Spider
import com.github.catvod.crawler.python.IPyLoader
import com.github.avbox.osc.R
import com.github.avbox.osc.base.App
import com.github.avbox.osc.bean.SourceBean
import com.github.avbox.osc.io.FileUtils
import com.github.avbox.osc.net.HttpClients
import com.github.avbox.osc.boot.BootGuard
import com.github.avbox.osc.util.DefaultConfig
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.LanguageManager
import com.github.avbox.osc.util.MD5
import com.github.avbox.osc.util.RegexUtils
import com.google.gson.JsonObject

import org.json.JSONObject

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.util.HashSet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class SpiderLoader {

    private fun str(resId: Int, vararg args: Any?): String {
        val app = App.getInstance()
        return if (app == null) "" else LanguageManager.localized(app).getString(resId, *args)
    }

    private val jarLoader = JarLoader()
    private val jsLoader = JsLoader()
    private val pyLoader: IPyLoader = com.github.catvod.crawler.pyLoader()
    private val jarLoadExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val jarLoadEpoch = LoadEpoch()

    @Volatile
    private var activeJarCall: okhttp3.Call? = null

    private val danmuSearchExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val warmedSearchSpiderKeys: MutableSet<String> = HashSet()
    private val userAgent = "okhttp/3.15"

    var spider: String? = null
    var liveSpider: String = ""
    var currentLiveSpider: String? = null
        private set
    var currentPyKey: String? = ""
    var currentLivePyKey: String? = ""
        private set
    var jarCache: String = "true"

    suspend fun loadJar(useCache: Boolean, spider: String): String? = suspendCancellableCoroutine { cont ->
        loadJar(useCache, spider, object : ApiConfig.LoadConfigCallback {
            override fun success() {
                cont.resume(null)
            }

            override fun error(msg: String?) {
                cont.resume(msg)
            }
        }, 0, jarLoadEpoch.current())
    }

    fun invalidateJarLoads() {
        jarLoadEpoch.next()
        val call = activeJarCall
        if (call != null) {
            AppLog.debugI("echo-switch: cancel jar download")
            call.cancel()
        }
    }

    private fun isJarLoadStale(epoch: Long): Boolean = jarLoadEpoch.isStale(epoch)

    private interface JarLoadCallback {
        fun complete(success: Boolean)
    }

    private interface JarDownloadCallback {
        fun complete(file: File?, error: String?)
    }

    private fun loadJarAsync(file: File?, callback: JarLoadCallback, epoch: Long) {
        jarLoadExecutor.execute(Runnable {
            if (isJarLoadStale(epoch)) {
                AppLog.debugI("echo-switch: drop stale jar load")
                mainHandler.post(Runnable {
                    callback.complete(false)
                })
                return@Runnable
            }
            if (file != null) BootGuard.onJarLoadStart(file.absolutePath, BootGuard.SCOPE_VOD)
            var success = false
            try {
                success = file != null && file.exists() && jarLoader.load(file.absolutePath)
            } catch (th: Throwable) {
                AppLog.e("echo---jar Loader threw exception: " + th.message)
            }
            if (success) BootGuard.scheduleStableRunReset()
            val result = success
            mainHandler.post(Runnable {
                callback.complete(result)
            })
        })
    }

    private fun downloadJarAsync(url: String, isJarInImg: Boolean, cache: File, callback: JarDownloadCallback, epoch: Long) {
        jarLoadExecutor.execute(Runnable {
            if (isJarLoadStale(epoch)) {
                AppLog.debugI("echo-switch: drop stale jar download")
                mainHandler.post(Runnable {
                    callback.complete(null, "stale")
                })
                return@Runnable
            }
            var result: File? = null
            var error: String? = ""
            var response: okhttp3.Response? = null
            var inputStream: InputStream? = null
            var outputStream: FileOutputStream? = null
            var call: okhttp3.Call? = null
            val temp = File(cache.absolutePath + ".tmp")
            try {
                val cacheDir = cache.parentFile
                if (cacheDir != null && !cacheDir.exists()) cacheDir.mkdirs()
                if (temp.exists()) temp.delete()
                val request = okhttp3.Request.Builder()
                        .url(url)
                        .header("User-Agent", userAgent)
                        .build()
                var client: okhttp3.OkHttpClient? = HttpClients.defaultClient
                if (client == null) client = com.github.catvod.net.OkHttp.client()
                val jarCall = client.newCall(request)
                call = jarCall
                activeJarCall = jarCall
                response = jarCall.execute()
                if (!response.isSuccessful) {
                    error = "HTTP " + response.code
                } else if (response.body == null) {
                    error = "empty body"
                } else if (isJarInImg) {
                    val respData = response.body.string()
                    AppLog.i("echo---jar Response: " + respData)
                    val imgJar = getImgJar(respData)
                    if (imgJar == null || imgJar.size == 0) {
                        error = "empty img jar"
                    } else {
                        val output = FileOutputStream(temp)
                        outputStream = output
                        output.write(imgJar)
                        output.flush()
                        closeQuietly(output)
                        outputStream = null
                        result = replaceCache(temp, cache)
                    }
                } else {
                    val input = response.body.byteStream()
                    inputStream = input
                    val output = FileOutputStream(temp)
                    outputStream = output
                    val buffer = ByteArray(16384)
                    var bytesRead = input.read(buffer)
                    while (bytesRead != -1) {
                        output.write(buffer, 0, bytesRead)
                        bytesRead = input.read(buffer)
                    }
                    output.flush()
                    closeQuietly(output)
                    outputStream = null
                    result = replaceCache(temp, cache)
                }
            } catch (th: Throwable) {
                error = th.message
            } finally {
                if (call != null && activeJarCall === call) activeJarCall = null
                closeQuietly(inputStream)
                closeQuietly(outputStream)
                if (response != null) closeQuietly(response.body)
                if (result == null && temp.exists()) temp.delete()
            }
            if (isJarLoadStale(epoch)) {
                AppLog.debugI("echo-switch: discard stale jar download")
                result = null
                error = "stale"
            }
            val finalResult = result
            val finalError = error
            mainHandler.post(Runnable {
                callback.complete(finalResult, finalError)
            })
        })
    }

    private fun replaceCache(temp: File, cache: File): File {
        if (cache.exists() && !cache.delete()) {
            AppLog.i("echo---delete old jar cache failed:" + cache.absolutePath)
        }
        if (!temp.renameTo(cache)) {
            FileUtils.copyFile(temp, cache)
            temp.delete()
        }
        return cache
    }

    private fun getImgJar(body: String): ByteArray? {
        val pattern = RegexUtils.getPattern("[A-Za-z0-9]{8}\\*\\*")
        val matcher = pattern.matcher(body)
        var body = body
        if (matcher.find()) {
            body = body.substring(body.indexOf(matcher.group()) + 10)
            return Base64.decode(body, Base64.DEFAULT)
        }
        return "".toByteArray(Charset.defaultCharset())
    }

    private fun loadJar(useCache: Boolean, spider: String, callback: ApiConfig.LoadConfigCallback, retryCount: Int, epoch: Long) {
        if (isJarLoadStale(epoch)) {
            AppLog.debugI("echo-switch: abort stale jar load")
            callback.error(str(R.string.toast_jar_load_failed))
            return
        }
        val urls = RegexUtils.getPattern(";md5;").split(spider)
        var jarUrl = urls[0]
        val md5 = if (urls.size > 1) urls[1].trim { it <= ' ' } else ""
        val cache = File(requireNotNull(App.getInstance()) { "app instance not ready" }.getFilesDir().getAbsolutePath() + "/csp/" + MD5.string2MD5(jarUrl) + ".jar")

        if (BootGuard.isJarDisabled(cache.absolutePath)) {
            AppLog.i("echo-boot-guard: skip disabled jar " + cache.absolutePath)
            callback.success()
            return
        }

        if (!md5.isEmpty() || useCache) {
            if (cache.exists() && (useCache || MD5.fileMd5(cache).equals(md5, ignoreCase = true))) {
                if (cache.exists()) {
                    loadJarAsync(cache, object : JarLoadCallback {
                        override fun complete(success: Boolean) {
                            if (success) {
                                callback.success()
                            } else {
                                callback.error(str(R.string.toast_jar_load_failed))
                            }
                        }
                    }, epoch)
                    return
                }
                if (jarLoader.load(cache.absolutePath)) {
                    callback.success()
                } else {
                    callback.error(str(R.string.toast_jar_load_failed))
                }
                return
            }
        } else {
            if (jarCache.toBoolean() && cache.exists() && !FileUtils.isWeekAgo(cache)) {
                AppLog.i("echo-load jar jarCache:" + jarUrl)
                if (cache.exists()) {
                    loadJarAsync(cache, object : JarLoadCallback {
                        override fun complete(success: Boolean) {
                            if (success) {
                                callback.success()
                            } else {
                                loadJar(false, spider, callback, retryCount, epoch)
                            }
                        }
                    }, epoch)
                    return
                }
                if (jarLoader.load(cache.absolutePath)) {
                    callback.success()
                    return
                }
            }
        }

        val isJarInImg = jarUrl.startsWith("img+")
        jarUrl = jarUrl.replace("img+", "")
        AppLog.i("echo-load jar start:" + jarUrl)
        val requestUrl = jarUrl
        downloadJarAsync(requestUrl, isJarInImg, cache, object : JarDownloadCallback {
            private fun retryLoad(reason: String): Boolean {
                if (retryCount >= LOAD_JAR_MAX_RETRY) return false
                if (isJarLoadStale(epoch)) {
                    AppLog.debugI("echo-switch: abort stale jar retry")
                    return false
                }
                if (cache.exists() && !cache.delete()) {
                    AppLog.i("echo---delete bad jar cache failed:" + cache.absolutePath)
                }
                AppLog.i("echo---retry load jar reason:" + reason + " url:" + requestUrl + " retry:" + (retryCount + 1))
                loadJar(false, spider, callback, retryCount + 1, epoch)
                return true
            }

            override fun complete(file: File?, error: String?) {
                if (file == cache) {
                    loadJarAsync(file, object : JarLoadCallback {
                        override fun complete(success: Boolean) {
                            if (success) {
                                AppLog.i("echo---load-jar-success")
                                callback.success()
                            } else {
                                AppLog.e("echo---jar Loader returned false")
                                if (retryLoad("loader_false")) return
                                callback.error(str(R.string.toast_jar_load_failed))
                            }
                        }
                    }, epoch)
                    return
                }
                if (!TextUtils.isEmpty(error)) {
                    AppLog.i("echo---jar Request failed: " + error)
                }
                if (cache.exists()) {
                    loadJarAsync(cache, object : JarLoadCallback {
                        override fun complete(success: Boolean) {
                            if (success) {
                                callback.success()
                            } else {
                                if (retryLoad("request_error")) return
                                callback.error(str(R.string.toast_network_error))
                            }
                        }
                    }, epoch)
                    return
                }
                if (retryLoad("request_error")) return
                callback.error(str(R.string.toast_network_error))
            }
        }, epoch)
    }

    fun getCSP(sourceBean: SourceBean): Spider {
        val api = sourceBean.api ?: ""
        // 宿主内置 Csp（如 Csp_Nostr）优先：命中注册表就走我们自己的实现，
        // 不查外部 jar；注册表里没有的 api 仍照旧往下走。
        if (com.github.avbox.osc.spider.BuiltinSpiders.match(api)) {
            currentPyKey = ""
            return com.github.avbox.osc.spider.BuiltinSpiders.create(
                sourceBean.key,
                sourceBean.homePage,
                sourceBean.ext,
                sourceBean.proxy,
            )
        }
        if (api.endsWith(".js") || api.contains(".js?")) {
            currentPyKey = ""
            return jsLoader.getSpider(sourceBean.key, sourceBean.api, sourceBean.ext, sourceBean.jar)
        } else if (api.contains(".py")) {
            currentPyKey = sourceBean.key
            pyLoader.setRecentPyKey(currentPyKey)
            return pyLoader.getSpider(requireNotNull(sourceBean.key) { "source key missing" }, sourceBean.api, sourceBean.ext)
        } else if (api.endsWith(".html") || api.contains(".html?")) {
            // 本地/在线 html：交给内置 Csp 处理，页面地址即 api 本身。
            currentPyKey = ""
            return com.github.avbox.osc.spider.BuiltinSpiders.create(
                sourceBean.key,
                sourceBean.api,
                sourceBean.ext,
                sourceBean.proxy,
            )
        } else {
            currentPyKey = ""
            return jarLoader.getSpider(sourceBean.key, sourceBean.api, sourceBean.ext, sourceBean.jar)
        }
    }

    fun pySpider(key: String?, api: String?, ext: String?): Spider {
        val keyValue = requireNotNull(key) { "py spider key missing" }
        val result = pyLoader.getSpider(keyValue, api, ext)
        pyLoader.setRecentPyKey(keyValue)
        return result
    }

    fun setLiveJar(liveJar: String) {
        if (liveJar.contains(".py")) {
            val key = requireNotNull(MD5.string2MD5(liveJar)) { "md5 of live jar failed" }
            currentLivePyKey = key
            pyLoader.getSpider(key, liveJar, "")
            pyLoader.setRecentPyKey(currentLivePyKey)
        } else if (liveJar.contains(".js")) {
            jsLoader.getSpider(MD5.string2MD5(liveJar), liveJar, "", "")
        } else {
            val jarUrl = if (!liveJar.isEmpty()) liveJar else liveSpider
            jarLoader.setRecentJarKey(MD5.string2MD5(jarUrl))
        }
        currentLiveSpider = liveJar
    }

    fun getPyCSP(url: String): Spider {
        val key = requireNotNull(MD5.string2MD5(url)) { "md5 of live url failed" }
        currentLivePyKey = key
        currentLiveSpider = url
        return pyLoader.getSpider(key, url, "")
    }

    fun getJsCSP(url: String): Spider {
        currentLiveSpider = url
        return jsLoader.getSpider(MD5.string2MD5(url), url, "", "")
    }

    fun getLiveCSP(url: String): Spider {
        return if (url.contains(".js")) getJsCSP(url) else getPyCSP(url)
    }

    fun loadLiveSpider(api: String, jarUrl: String, livesOBJ: JsonObject) {
        AppLog.i("echo-liveApi1" + api)
        if (api.contains(".py")) {
            AppLog.i("echo-pyLoader.getSpider")
            val ext = liveExt(livesOBJ)
            val key = requireNotNull(MD5.string2MD5(api)) { "md5 of live api failed" }
            currentLivePyKey = key
            currentLiveSpider = api
            pyLoader.getSpider(key, api, ext)
        } else if (api.contains(".js")) {
            AppLog.i("echo-jsLoader.getSpider")
            val ext = liveExt(livesOBJ)
            currentLiveSpider = api
            jsLoader.getSpider(MD5.string2MD5(api), api, ext, jarUrl)
        }
        if (!jarUrl.isEmpty() && !isLiveSpiderApi(api)) {
            jarLoader.loadLiveJar(jarUrl)
            if (TextUtils.isEmpty(currentLiveSpider)) {
                currentLiveSpider = jarUrl
            }
        } else if (!liveSpider.isEmpty() && !isLiveSpiderApi(api)) {
            jarLoader.loadLiveJar(liveSpider)
            if (TextUtils.isEmpty(currentLiveSpider)) {
                currentLiveSpider = liveSpider
            }
        }
    }

    private fun liveExt(livesOBJ: JsonObject): String {
        if (livesOBJ.has("ext") && (livesOBJ.get("ext").isJsonObject || livesOBJ.get("ext").isJsonArray)) {
            return livesOBJ.get("ext").toString()
        }
        return DefaultConfig.safeJsonString(livesOBJ, "ext", "")
    }

    fun resetCurrentLiveSpider() {
        currentLiveSpider = ""
        currentLivePyKey = ""
    }

    fun markWarmed(warmKey: String): Boolean {
        synchronized(warmedSearchSpiderKeys) {
            return warmedSearchSpiderKeys.add(warmKey)
        }
    }

    fun proxyInvokeJar(param: Map<String, String>): Array<Any?>? {
        return jarLoader.proxyInvoke(param)
    }

    fun proxyInvokeJs(param: Map<String, String>): Array<Any?>? {
        return jsLoader.proxyInvoke(param)
    }

    fun proxyInvokePy(param: Map<String, String>, pyKey: String?): Array<Any?>? {
        return pyLoader.proxyInvoke(param, pyKey)
    }

    fun jsonExt(key: String, jxs: LinkedHashMap<String, String>, url: String): JSONObject? {
        return jarLoader.jsonExt(key, jxs, url)
    }

    fun jsonExtMix(flag: String, key: String, name: String, jxs: LinkedHashMap<String, HashMap<String, String>>, url: String): JSONObject? {
        return jarLoader.jsonExtMix(flag, key, name, jxs, url)
    }

    fun searchDanmuUi(name: String, episode: String, longClick: Boolean) {
        danmuSearchExecutor.execute(Runnable {
            try {
                jarLoader.searchDanmuUi(name, episode, longClick)
            } catch (th: Throwable) {
                AppLog.e("ApiConfig searchDanmuUi error: " + th.message)
                AppLog.e("SpiderLoader", th)
            }
        })
    }

    fun hasDanmuSearchUi(): Boolean {
        return jarLoader.hasDanmuSearchUi()
    }

    fun clearJarLoader() {
        jarLoader.clear()
    }

    fun clearLoader() {
        invalidateJarLoads()
        val startedAt = System.currentTimeMillis()
        jarLoader.clear()
        val jarDone = System.currentTimeMillis()
        pyLoader.clear()
        val pyDone = System.currentTimeMillis()
        jsLoader.clear()
        val jsDone = System.currentTimeMillis()
        synchronized(warmedSearchSpiderKeys) {
            warmedSearchSpiderKeys.clear()
        }
        AppLog.debugI(
            "echo-switch: clear jar=" + (jarDone - startedAt) + "ms py=" + (pyDone - jarDone) +
                "ms js=" + (jsDone - pyDone) + "ms"
        )
    }

    fun clearSpiderCache() {
        currentPyKey = ""
        currentLivePyKey = ""
        currentLiveSpider = ""
        clearLoader()
    }

    companion object {
        private const val LOAD_JAR_MAX_RETRY = 1
        /** 站点 type 6 = html 脚本（本地 .html 或在线 url，WebView 承载） */
        private const val TYPE_HTML = 6

        @JvmStatic
        fun closeQuietly(closeable: Closeable?) {
            try {
                if (closeable != null) closeable.close()
            } catch (ignored: Throwable) {
                AppLog.d("ApiConfig", "close failed")
            }
        }

        @JvmStatic
        fun isLiveSpiderApi(api: String): Boolean {
            return api.contains(".py") || api.contains(".js")
        }
    }
}
