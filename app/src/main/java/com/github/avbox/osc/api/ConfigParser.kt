package com.github.avbox.osc.api

import androidx.media3.common.util.UriUtil

import com.github.avbox.osc.bean.SourceDepot
import com.github.avbox.osc.bean.LiveSettingItem
import com.github.avbox.osc.bean.SourceBean
import com.github.avbox.osc.util.DefaultConfig
import com.github.avbox.osc.util.HeaderGuard
import com.github.avbox.osc.util.HistoryHelper
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.LocalApi
import com.github.avbox.osc.util.RegexUtils
import com.github.avbox.osc.util.SiteExt
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject

import java.util.ArrayList
import java.util.HashMap
import java.util.function.Supplier

object ConfigParser {

    private val gson = Gson()

    private fun isEmpty(text: String?): Boolean {
        return text == null || text.length == 0
    }

    @JvmStatic
    fun trimJsonObject(content: String?): String {
        if (content == null) {
            return ""
        }
        val trimContent = content.trim { it <= ' ' }
        val start = trimContent.indexOf("{")
        val end = trimContent.lastIndexOf("}")
        if (start >= 0 && end > start) {
            return trimContent.substring(start, end + 1)
        }
        return trimContent
    }

    @JvmStatic
    fun isLiveJsonContent(content: String?): Boolean {
        if (content == null) return false
        var text = content.trim { it <= ' ' }
        if (text.startsWith("\uFEFF")) text = text.substring(1).trim { it <= ' ' }
        return text.startsWith("{")
    }

    @JvmStatic
    fun extractLiveTextEpg(content: String?): String {
        if (content == null) return ""
        val text = content.replace("\r\n", "\n").replace('\r', '\n')
        val lines = RegexUtils.getPattern("\n").split(text)
        for (rawLine in lines) {
            var line = rawLine
            line = line.trim { it <= ' ' }
            if (line.startsWith("\uFEFF")) line = line.substring(1).trim { it <= ' ' }
            if (!line.startsWith("#EXTM3U")) continue
            var epg = extractQuotedAttr(line, "x-tvg-url")
            if (epg.isEmpty()) epg = extractQuotedAttr(line, "tvg-url")
            if (epg.isEmpty()) epg = extractQuotedAttr(line, "url-tvg")
            return epg
        }
        return ""
    }

    @JvmStatic
    fun extractQuotedAttr(line: String, key: String): String {
        val token = "$key=\""
        var start = line.indexOf(token)
        if (start < 0) return ""
        start += token.length
        val end = line.indexOf("\"", start)
        if (end < 0) return ""
        return line.substring(start, end).trim { it <= ' ' }
    }

    /**
     * 本地脚本的 api 写成 "file:///sdcard/x.js" 或 "/sdcard/x.js" 这类绝对路径时：
     *  - js 走 JsSpider，会拼成 "http://127.0.0.1:port/file/" + 绝对路径，
     *    而服务端 /file/ 端点会再拼一次外部存储根目录，路径重复就取不到文件，
     *    所以 js 要换算成 clan://localhost/<相对路径>；
     *  - py 走 app.py 的 downloadPlugin，只认 file:// 前缀的【绝对路径】，
     *    给 clan:// 会被当远程地址下载，必然失败，所以 py 要保持 file:// 绝对路径。
     * 早期把两者统一成 clan:// 导致 py 全线加载失败，现在按后缀分流（见 LocalApi.normalize）。
     */
    @JvmStatic
    fun normalizeLocalApi(api: String?): String {
        return LocalApi.normalize(api)
    }

    @JvmStatic
    fun parseSites(infoJson: JsonObject): List<SourceBean> {
        val sites: MutableList<SourceBean> = ArrayList()
        for (opt in infoJson.get("sites").asJsonArray) {
            val obj = opt as JsonObject
            if (!obj.has("key") || !obj.has("type") || !obj.has("api")) {
                AppLog.i("echo-skip incomplete site config: " + obj)
                continue
            }
            val sb = SourceBean()
            val siteKey = obj.get("key").asString.trim { it <= ' ' }
            sb.key = siteKey
            sb.name = if (obj.has("name")) obj.get("name").asString.trim { it <= ' ' } else siteKey
            sb.type = obj.get("type").asInt
            sb.api = normalizeLocalApi(obj.get("api").asString.trim { it <= ' ' })
            sb.searchable = DefaultConfig.safeJsonInt(obj, "searchable", 1)
            sb.quickSearch = DefaultConfig.safeJsonInt(obj, "quickSearch", 1)
            sb.changeable = DefaultConfig.safeJsonInt(obj, "changeable", 1)
            if (siteKey.startsWith("py_")) {
                sb.filterable = 1
            } else {
                sb.filterable = DefaultConfig.safeJsonInt(obj, "filterable", 1)
            }
            sb.playerUrl = DefaultConfig.safeJsonString(obj, "playUrl", "")
            sb.ext = DefaultConfig.safeJsonString(obj, "ext", "")
            sb.jar = DefaultConfig.safeJsonString(obj, "jar", "")
            sb.homePage = DefaultConfig.safeJsonString(obj, "homePage", "")
            sb.proxy = DefaultConfig.safeJsonString(obj, "proxy", "")
            sb.playerType = DefaultConfig.safeJsonInt(obj, "playerType", -1)
            sb.categories = DefaultConfig.safeJsonStringList(obj, "categories")
            sb.timeout = DefaultConfig.safeJsonInt(obj, "timeout", 0)
            sb.clickSelector = DefaultConfig.safeJsonString(obj, "click", "")
            sb.style = DefaultConfig.safeJsonString(obj, "style", "")
            sb.icon = DefaultConfig.safeJsonString(obj, "icon", "")
            sb.hide = DefaultConfig.safeJsonInt(obj, "hide", 0)
            sb.indexs = DefaultConfig.safeJsonInt(obj, "indexs", 0)
            sb.danmaku = DefaultConfig.safeJsonInt(obj, "danmaku", 1)
            sb.header = parseHeaderObject(obj, "header")
            // 旧版 type 6（html 站点）统一迁移为内置 Csp_Nostr + 顶层 homePage。
            // 我们自己是宿主：html 由内置 Csp 处理，不再保留 type 6 分支。
            if (sb.type == 6) {
                val page = sb.api ?: ""
                if (page.isNotEmpty()) {
                    sb.homePage = page
                    sb.api = com.github.avbox.osc.spider.BuiltinSpiders.HTML_API
                    sb.type = 3
                }
            }
            SiteExt.apply(sb)
            val extPreview = sb.ext
            AppLog.i("echo-site:" + sb.name + " icon:" + sb.icon
                    + " ext:" + (if (extPreview != null && extPreview.length > 160) extPreview.substring(0, 160) else extPreview))
            sites.add(sb)
        }
        return sites
    }

    @JvmStatic
    fun parseApiCollection(jsonStr: String?): ArrayList<String> {
        val apiLines = ArrayList<String>()
        try {
            val json = trimJsonObject(jsonStr)
            if (isEmpty(json)) {
                return apiLines
            }
            val infoJson = gson.fromJson(json, JsonObject::class.java)
            if (!isDepotJson(infoJson)) {
                return apiLines
            }
            for (item in SourceDepot.arrayFrom(infoJson.get("urls").asJsonArray)) {
                apiLines.add(HistoryHelper.buildApiLine(item.getName(), item.getUrl()))
            }
        } catch (ignored: Throwable) {
            AppLog.d("ApiConfig", "api lines parse failed, keep lines so far")
        }
        return apiLines
    }

    @JvmStatic
    fun isDepotJson(infoJson: JsonObject?): Boolean {
        if (infoJson == null || infoJson.has("sites")) return false
        if (!infoJson.has("urls")) return false
        val urls = infoJson.get("urls")
        return urls != null && urls.isJsonArray && urls.asJsonArray.size() > 0
    }

    @JvmStatic
    fun parseLiveSettingItems(livesGroups: JsonArray): ArrayList<LiveSettingItem> {
        val liveSettingItemList = ArrayList<LiveSettingItem>()
        for (i in 0 until livesGroups.size()) {
            val jsonObject = livesGroups.get(i).asJsonObject
            val name = if (jsonObject.has("name")) jsonObject.get("name").asString else "线路" + (i + 1) // i18n: keep(数据默认名,进 bean 且被 ConfigParserTest 锁定)
            val liveSettingItem = LiveSettingItem()
            liveSettingItem.itemIndex = i
            liveSettingItem.itemName = name
            liveSettingItemList.add(liveSettingItem)
        }
        return liveSettingItemList
    }

    @JvmStatic
    fun parseHosts(hostsArray: JsonArray): MutableMap<String, String> {
        val hosts: MutableMap<String, String> = HashMap()
        for (i in 0 until hostsArray.size()) {
            val entry = hostsArray.get(i).asString
            val parts = RegexUtils.getPattern("=").split(entry, 2)
            if (parts.size == 2) {
                hosts[parts[0]] = parts[1]
            }
        }
        return hosts
    }

    @JvmStatic
    fun parseHeaderObject(obj: JsonObject?, key: String): MutableMap<String, String>? {
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonObject) {
            return null
        }
        val header: MutableMap<String, String> = HashMap()
        for (entry in obj.getAsJsonObject(key).entrySet()) {
            if (entry.value == null || !entry.value.isJsonPrimitive) continue
            val name = entry.key
            val value = entry.value.asString
            if (!HeaderGuard.isSendable(name, value)) {
                AppLog.i("echo-site-header-skip:" + name)
                continue
            }
            header[name] = value
        }
        return if (header.isEmpty()) null else header
    }

    @JvmStatic
    fun parseLiveChannelName(obj: JsonObject?, sourceUrls: ArrayList<String>): String {
        if (obj != null && obj.has("name")) {
            val name = obj.get("name")
            if (name != null && name.isJsonPrimitive) {
                val text = name.asString.trim { it <= ' ' }
                if (text.isNotEmpty()) return text
            }
        }
        for (url in sourceUrls) {
            if (url.isNotEmpty()) return url
        }
        return ""
    }

    @JvmStatic
    fun parseLiveCatchup(obj: JsonObject?): JsonObject? {
        if (obj == null || !obj.has("catchup")) return null
        val catchup = obj.get("catchup")
        if (catchup == null || catchup.isJsonNull) return null
        if (catchup.isJsonObject) return catchup.asJsonObject
        if (!catchup.isJsonPrimitive) return null
        val catchupObj = JsonObject()
        catchupObj.addProperty("type", catchup.asString)
        val source = DefaultConfig.safeJsonString(obj, "catchup-source", "")
        if (source.isNotEmpty()) catchupObj.addProperty("source", source)
        val replace = DefaultConfig.safeJsonString(obj, "catchup-replace", "")
        if (replace.isNotEmpty()) catchupObj.addProperty("replace", replace)
        return catchupObj
    }

    @JvmStatic
    fun clanToAddress(lanLink: String, localFileBase: Supplier<String>): String {
        if (lanLink.startsWith("clan://localhost/")) {
            return lanLink.replace("clan://localhost/", localFileBase.get() + "file/")
        } else {
            val link = lanLink.substring(7)
            val end = link.indexOf('/')
            return "http://" + link.substring(0, end) + "/file/" + link.substring(end + 1)
        }
    }

    @JvmStatic
    fun clanContentFix(lanLink: String, content: String): String {
        val fix = lanLink.substring(0, lanLink.indexOf("/file/") + 6)
        return content.replace("clan://localhost/", fix).replace("file://", fix)
    }

    @JvmStatic
    fun fixContentPath(url: String, content: String, localFileBase: Supplier<String>): String {
        var url = url
        var content = content
        if (content.contains("\"./") || content.contains("\"../")) {
            url = url.replace("file://", "clan://localhost/")
            if (!url.startsWith("http") && !url.startsWith("clan://")) {
                url = "http://" + url
            }
            if (url.startsWith("clan://")) url = clanToAddress(url, localFileBase)
            content = content.replace("../", UriUtil.resolve(url, "../"))
            content = content.replace("./", UriUtil.resolve(url, "./"))
        }
        return content
    }

    @JvmStatic
    fun configUrl(apiUrl: String, localFileBase: Supplier<String>): ConfigUrl {
        var key: String? = null
        var configUrl = ""
        val pk = ";pk;"
        var apiUrl = apiUrl
        apiUrl = apiUrl.replace("file://", "clan://localhost/")
        if (apiUrl.contains(pk)) {
            val a = RegexUtils.getPattern(pk).split(apiUrl)
            key = a[1]
            if (apiUrl.startsWith("clan")) {
                configUrl = clanToAddress(a[0], localFileBase)
            } else if (apiUrl.startsWith("http")) {
                configUrl = a[0]
            } else {
                configUrl = "http://" + a[0]
            }
        } else if (apiUrl.startsWith("clan")) {
            configUrl = clanToAddress(apiUrl, localFileBase)
        } else if (!apiUrl.startsWith("http")) {
            configUrl = "http://" + apiUrl
        } else {
            configUrl = apiUrl
        }
        return ConfigUrl(configUrl, key)
    }

    class ConfigUrl(@JvmField val url: String, @JvmField val key: String?)
}
