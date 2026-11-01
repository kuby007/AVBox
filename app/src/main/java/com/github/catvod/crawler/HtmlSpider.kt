package com.github.catvod.crawler

import com.github.avbox.osc.ui.HtmlActivity
import org.json.JSONArray
import org.json.JSONObject

/**
 * HTML 脚本（站点 type 6）。
 *
 * 采用与 appmenu / 影视仓一致的 WvWv 范式：
 *   · html 是【页面】，不是爬虫脚本 —— 它渲染自己的 UI，不向宿主返回数据；
 *   · 分类与宫格由【宿主自己造】：homeContent 不起 WebView，直接返回
 *     「1 个分类 + 1 张启动卡」，结构恒定，不会因为多次调用而越滚越多；
 *   · 点启动卡（detailContent）才拉起 HtmlActivity 打开页面，且【不等结果】，
 *     立即返回空列表 —— 宿主拿不到详情数据，不会进详情页，网页浮在宫格之上。
 *
 * 为什么不能让 homeContent 去跑 WebView 等 fm.done()（旧实现的三个致命问题）：
 *   1) 绝大多数 html 是页面，压根没有 homeContent() 函数，等 30 秒必然超时 →
 *      Spider 线程被 latch 卡死，界面停在过渡页，就是用户说的"卡死"；
 *   2) 每次调用都往 list 末尾 append 一张启动卡 → 宫格越滚越多；
 *   3) 进站点就要弹一次页面，与"宫格里点卡片才打开"的直觉相反。
 *
 * 现在 homeContent 是纯本地字符串构造，零 WebView、零等待，不可能卡死。
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

    override fun init(context: android.content.Context?, ext: String?) {
        init(context)
    }

    /**
     * 首页：1 个分类 + 1 张启动卡，并声明"只有一页"。
     *
     * page/pagecount/limit/total 是关键：宿主据此认为只有一页，
     * 不会反复发起分页请求，避免抖动与重复造卡。
     */
    override fun homeContent(filter: Boolean): String? = grid()

    /** 首页推荐：返回空。启动卡已在 homeContent 里，这里再给一份会重复。 */
    override fun homeVideoContent(): String? = EMPTY_LIST

    /** 切分类：仍返回同一张启动卡，保证任何分类下都有入口。 */
    override fun categoryContent(
        tid: String?,
        pg: String,
        filter: Boolean,
        extend: HashMap<String, String>?,
    ): String? = grid()

    /**
     * 点宫格：拉起页面，然后返回空列表。
     *
     * 返回空 list 后宿主拿不到详情数据，不会进详情页 —— 网页直接浮在宫格之上，
     * 和用户要的行为一致。这里【不等待】页面加载结果，所以不会卡。
     */
    override fun detailContent(ids: List<String>?): String? {
        HtmlActivity.open(siteKey ?: "", api ?: "", extend ?: "")
        return EMPTY_LIST
    }

    /** 播放由 html 页面自己处理，返回空 parse/url，宿主不会再跳转任何页面。 */
    override fun playerContent(flag: String?, id: String, vipFlags: List<String>?): String? =
        PLAYER_EMPTY

    /** 搜索在页面内完成，宿主侧不提供。 */
    override fun searchContent(key: String?, quick: Boolean): String? = EMPTY_LIST

    override fun searchContent(key: String?, quick: Boolean, pg: String?): String? = EMPTY_LIST

    override fun proxyLocal(params: Map<String, String>?): Array<Any?>? = null

    override fun destroy() {
        HtmlActivity.finishIfRunning(siteKey)
    }

    /** 恒定结构：1 个分类 + 1 张启动卡 + 只有一页 */
    private fun grid(): String = JSONObject()
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
                    .put("vod_name", launchName())
                    .put("vod_pic", "")
                    .put("vod_remarks", "HTML"),
            ),
        )
        .put("page", 1)
        .put("pagecount", 1)
        .put("limit", 1)
        .put("total", 1)
        .toString()

    /**
     * 启动卡名称：优先用脚本文件名（去掉扩展名），比固定的"启动脚本"更好认。
     * 取不到就退回默认名。
     */
    private fun launchName(): String {
        val raw = api ?: return LAUNCH_NAME
        val path = raw.substringBefore('?').substringBefore(";pk;")
        val file = path.substringAfterLast('/', "")
        if (file.isBlank()) return LAUNCH_NAME
        val noExt = file.substringBeforeLast('.')
        return if (noExt.isBlank()) file else noExt
    }

    private companion object {
        const val LAUNCH_NAME = "启动脚本"
        const val LAUNCH_ID = "__html_launch__"
        const val LAUNCH_TID = "__html_launch__"
        const val LAUNCH_CLASS_NAME = "启动"
        const val EMPTY_LIST = "{\"list\":[]}"
        const val PLAYER_EMPTY = "{\"parse\":0,\"url\":\"\"}"
    }
}
