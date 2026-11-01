package com.github.catvod.crawler

import android.os.Handler
import android.os.Looper
import com.github.avbox.osc.ui.HtmlActivity
import com.github.avbox.osc.util.AppContextHolder
import com.github.avbox.osc.util.AppLog as LOG
import org.json.JSONObject

/**
 * HTML 站点（type 6）爬虫。
 *
 * 【范式】html 是【被打开的应用】，不是【数据源】—— 与 appmenu / WvWv 的思路一致：
 * 分类与宫格由【宿主】造（恒为一张「启动脚本」卡），页面本身不回传任何数据。
 * 用户点这张卡 → 拉起全屏 HtmlActivity 打开页面 → 页面常驻，用户自己操作 →
 * 按返回关闭。页面被误关后再点卡可再次拉起（二次启动）。
 *
 * 【为什么不再让 html 当爬虫】
 * 之前的写法是"每次被调用都起一次页面、等脚本 window.fm.done() 回传"：
 *   · 进站点（homeContent）就会【自动弹出页面】，用户什么都没点；
 *   · 绝大多数 html（音乐聚合、导航页这类）根本不实现 fm 回调，于是拿不到结果，
 *     页面 8 秒后自动关闭 —— 表现就是"打开后闪退"。
 * 宿主不该指望子程序配合：主流 html 就是纯页面，宿主负责把它打开并养住即可。
 *
 * 【为什么不用 csp_Nostr + http://127.0.0.1:9978】
 * 那是 appmenu.jar 的内部类和它自带的文件服务端口。我们是宿主，不依赖子程序的
 * 内部实现；本地文件直接走 file 路径加载，远程 url 走 App 网络栈（站点代理同样生效）。
 */
class HtmlSpider(
    key: String?,
    private val api: String?,
    private val extend: String?,
) : Spider() {

    init {
        this.siteKey = key
    }

    override fun init(context: android.content.Context?) {
        // 无状态：页面按需拉起，不需要预加载
    }

    /**
     * 首页：宿主造分类与宫格，不弹页面。
     * 分类恒为「启动」，宫格恒为一张启动卡 —— 保证站点永远不是空的，
     * 不会因为脚本不回传就被判成「加载失败，请检查网络」。
     */
    override fun homeContent(filter: Boolean): String = launchResult()

    /**
     * 首页推荐位：返回空 —— 宫格只由 categoryContent 一处提供。
     * 这里再返回一张卡的话会和分区结果叠加成两张（与 WvWv 的做法一致）。
     */
    override fun homeVideoContent(): String = emptyResult()

    /**
     * 分区宫格。只有第一页给启动卡，第二页起给空列表。
     *
     * 【为什么必须带分页字段】宿主 HomeViewModel 的判定是：
     *   FIRST_PAGE = 1，hasMore = !(maxPage > 0 && nextPage > maxPage)，
     *   merged = if (page == 0) videos else p.videos + videos
     * 不返回 pagecount 时 maxPage = 0 → hasMore 恒为 true，且 page 从 1 开始
     * 走的是累加分支 —— 于是滚到底就再追加一张启动卡，越滚越多，
     * 表现就是「生成了无数个启动脚本的宫格」。
     */
    override fun categoryContent(
        tid: String?,
        pg: String,
        filter: Boolean,
        extend: HashMap<String, String>?,
    ): String {
        val page = pg.trim().toIntOrNull() ?: 1
        return if (page <= 1) launchResult() else emptyResult()
    }

    /**
     * 点启动卡 = 拉起页面。返回空列表 → 不进详情页，停在首页。
     * （DetailLoader 对 type 6 也不走详情流程，只把这次调用当作"开页面"的信号。）
     */
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
     * 拉起（或唤起已存活的）页面。不等回调、不阻塞 —— 页面是常驻的，
     * 用户自己按返回关闭，不是"用完即走"。
     */
    private fun openPage() {
        Handler(Looper.getMainLooper()).post {
            try {
                val key = siteKey ?: ""
                // 页面还活着：直接把它调到前台，不重复创建
                if (HtmlActivity.bringToFront(key)) return@post
                HtmlActivity.finishIfRunning(null)
                HtmlActivity.pending = HtmlActivity.Request(
                    siteKey = key,
                    api = api ?: "",
                    ext = extend ?: "",
                    method = "",
                    args = "{}",
                    resident = true,
                    // 只有站点真配了代理才让承载页接管子资源；没配就交给 WebView
                    // 原生加载，否则每个子资源都同步走一趟 OkHttp，IO 线程被占满 → 卡死
                    proxy = siteProxy(key),
                ) { /* 常驻页面不回传数据 */ }
                val starter = resolveStarter() ?: return@post
                val launch = android.content.Intent(starter, HtmlActivity::class.java)
                if (starter !is android.app.Activity) {
                    launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                starter.startActivity(launch)
            } catch (th: Throwable) {
                LOG.e(TAG, th)
            }
        }
    }

    /** 站点配置的代理地址；没配返回空串。 */
    private fun siteProxy(key: String): String =
        try {
            com.github.avbox.osc.api.ApiConfig.get().getSource(key)?.proxy ?: ""
        } catch (th: Throwable) {
            ""
        }

    /** 取栈顶 Activity 作为启动上下文。拿不到才退回 Application。 */
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
        return AppContextHolder.context()
    }

    /** 分类「启动」+ 宫格一张启动卡，并带齐分页字段（见 categoryContent 的说明） */
    private fun launchResult(): String = JSONObject()
        .put(
            "class",
            org.json.JSONArray().put(
                JSONObject()
                    .put("type_id", LAUNCH_TID)
                    .put("type_name", LAUNCH_CLASS_NAME),
            ),
        )
        .put(
            "list",
            org.json.JSONArray().put(
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

    /** 空结果同样带分页字段，否则宿主仍会认为「还有下一页」继续追加 */
    private fun emptyResult(): String =
        JSONObject()
            .put("list", org.json.JSONArray())
            .put("page", 1)
            .put("pagecount", 1)
            .put("limit", 0)
            .put("total", 0)
            .toString()

    private companion object {
        const val TAG = "HtmlSpider"
        const val LAUNCH_NAME = "启动脚本"
        const val LAUNCH_ID = "__html_launch__"
        const val LAUNCH_TID = "__html_launch__"
        const val LAUNCH_CLASS_NAME = "启动"
    }
}
