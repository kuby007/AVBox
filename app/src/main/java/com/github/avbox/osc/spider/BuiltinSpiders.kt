package com.github.avbox.osc.spider

import android.os.Handler
import android.os.Looper
import com.github.avbox.osc.ui.HtmlActivity
import com.github.avbox.osc.util.AppLog
import com.github.catvod.crawler.Spider
import org.json.JSONArray
import org.json.JSONObject

/**
 * 宿主内置爬虫（Csp）注册表。
 *
 * 【定位】我们是宿主，py / js / jar 都是跑在我们里的子程序。内置 Csp 由【我们自己】
 * 实现，不回退、也不依赖任何外部 jar —— 名字只是配置层面的写法，命中注册表就走
 * 宿主自己的实现；注册表里没有的 api 仍照旧交给原来的 jar 通道，互不影响。
 *
 * Csp_Nostr：html 站点。页面地址来自配置【顶层】的 homePage，不是 ext 里的键。
 * 范式与主流一致：html 是【被打开的应用】，分类与宫格由宿主（这里即内置 Csp）造，
 * 点宫格拉起全屏页面，页面常驻、按返回关闭。
 */
object BuiltinSpiders {

    const val HTML_API = "Csp_Nostr"

    /** 大小写不敏感：Csp_Nostr / csp_Nostr / Nostr 都认。 */
    fun match(api: String?): Boolean {
        val raw = api?.trim().orEmpty()
        if (raw.isEmpty()) return false
        val name = raw.substringAfterLast('.')
        return name.equals("Csp_Nostr", ignoreCase = true) ||
            name.equals("Nostr", ignoreCase = true)
    }

    /** 详情页点击是否需要"只拉起页面、不进详情页"。 */
    fun isHtmlLaunch(api: String?): Boolean = match(api)

    fun create(key: String?, homePage: String?, ext: String?, proxy: String?): Spider =
        CspNostr(key, homePage, ext, proxy)
}

private class CspNostr(
    key: String?,
    private val homePage: String?,
    private val ext: String?,
    private val proxy: String?,
) : Spider() {

    init {
        siteKey = key
    }

    override fun init(context: android.content.Context?) {
        // 无状态：页面按需拉起，不需要预加载
    }

    /** 首页分类与宫格由内置 Csp 自己造，且【不碰文件、不读内容】—— 只造清单，快。 */
    override fun homeContent(filter: Boolean): String = launchResult()

    override fun homeVideoContent(): String = emptyResult()

    override fun categoryContent(
        tid: String?,
        pg: String,
        filter: Boolean,
        extend: HashMap<String, String>?,
    ): String {
        val page = pg.trim().toIntOrNull() ?: 1
        return if (page <= 1) launchResult() else emptyResult()
    }

    /** 点启动卡 = 拉起页面。返回空列表 → 不进详情页，停在首页。 */
    override fun detailContent(ids: List<String>?): String? {
        val list: List<String> = ids ?: emptyList()
        if (list.isEmpty() || list[0] == LAUNCH_ID) openPage()
        return emptyResult()
    }

    override fun searchContent(key: String?, quick: Boolean): String? = emptyResult()

    override fun searchContent(key: String?, quick: Boolean, pg: String?): String? = emptyResult()

    override fun playerContent(flag: String?, id: String, vipFlags: List<String>?): String? =
        emptyResult()

    override fun proxyLocal(params: Map<String, String>?): Array<Any?>? = null

    override fun destroy() {
        HtmlActivity.finishIfRunning(siteKey)
    }

    /**
     * 页面地址。本地文件服务端口会被占用后自动后挪（9978 → 9979…），
     * 所以 /file/ 形式的地址一律按【当前实际端口】重建，避免配置写死后失效。
     */
    private fun resolvePage(): String {
        val raw = homePage?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        val marker = "/file/"
        val idx = raw.indexOf(marker)
        if (idx < 0) return raw
        val path = raw.substring(idx + marker.length)
        return try {
            com.github.avbox.osc.server.ControlManager.get().getAddress(true).trimEnd('/') +
                marker + path
        } catch (th: Throwable) {
            AppLog.e("CspNostr", th)
            raw
        }
    }

    private fun openPage() {
        Handler(Looper.getMainLooper()).post {
            try {
                val key = siteKey ?: ""
                val page = resolvePage()
                if (page.isEmpty()) return@post
                if (HtmlActivity.bringToFront(key)) return@post
                HtmlActivity.finishIfRunning(null)
                HtmlActivity.pending = HtmlActivity.Request(
                    siteKey = key,
                    api = page,
                    ext = ext ?: "",
                    method = "",
                    args = "{}",
                    resident = true,
                    // 只有站点真配了代理才接管子资源；没配就交给 WebView 原生加载，
                    // 否则每个子资源都同步走一趟 OkHttp，IO 被打满 → 卡死
                    proxy = proxy ?: "",
                ) { /* 常驻页面不回传数据 */ }
                val starter = com.github.avbox.osc.util.ActivityStack.getInstance()
                    .currentActivity()
                    ?.takeIf { !it.isFinishing && !it.isDestroyed }
                    ?: com.github.avbox.osc.util.AppContextHolder.context()
                    ?: return@post
                val launch = android.content.Intent(starter, HtmlActivity::class.java)
                if (starter !is android.app.Activity) {
                    launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                starter.startActivity(launch)
            } catch (th: Throwable) {
                AppLog.e("CspNostr", th)
            }
        }
    }

    /** 带齐分页字段：否则宿主认为"还有下一页"，滚到底就再追加一张卡（越滚越多）。 */
    private fun launchResult(): String = JSONObject()
        .put(
            "class",
            JSONArray().put(
                JSONObject()
                    .put("type_id", LAUNCH_TID)
                    .put("type_name", LAUNCH_CLASS_NAME),
            ),
        )
        .put(
            "list",
            JSONArray().put(
                JSONObject()
                    .put("vod_id", LAUNCH_ID)
                    .put("vod_name", LAUNCH_NAME)
                    .put("vod_pic", "")
                    .put("vod_remarks", "HTML"),
            ),
        )
        .put("page", 1)
        .put("pagecount", 1)
        .put("limit", 1)
        .put("total", 1)
        .toString()

    private fun emptyResult(): String = JSONObject()
        .put("list", JSONArray())
        .put("page", 1)
        .put("pagecount", 1)
        .put("limit", 0)
        .put("total", 0)
        .toString()

    private companion object {
        const val LAUNCH_NAME = "启动脚本"
        const val LAUNCH_ID = "__html_launch__"
        const val LAUNCH_TID = "__html_launch__"
        const val LAUNCH_CLASS_NAME = "启动"
    }
}
