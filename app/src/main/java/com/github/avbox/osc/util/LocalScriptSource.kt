package com.github.avbox.osc.util

import com.github.avbox.osc.spider.BuiltinSpiders
import com.github.avbox.osc.server.RemoteServer
import com.github.avbox.osc.util.AppLog

import org.json.JSONArray
import org.json.JSONObject

import android.os.Environment
import java.io.File

/**
 * 本地脚本目录当订阅源：把目录里所有 .py / .js / .html 扫出来，生成一份站点配置。
 *
 * 订阅源地址写成 lscript://<绝对路径>，加载时不再走网络，直接按目录内容生成 json。
 *
 * 持久化：目录下 lscript.json（可见，可直接用文件管理器改）。
 * 接口格式保持兼容，顶层只有 warningText / sites / lives，额外增加 sites_bac：
 *   sites     —— 生效站点（脚本条目 + 手工添加的 url 条目，顺序即展示顺序）
 *   sites_bac —— 已移除站点（脚本文件一个字节不动，只是不再出现在首页）
 *
 * 扫描合并规则：
 *   1. 扫到的脚本文件在 sites 里有 → 保留（沿用你改过的 name/ext/proxy 和排列位置）
 *   2. 扫到的脚本文件在 sites_bac 里有 → 留在回收站，不自动复活
 *   3. 扫到的脚本文件两边都没有 → 追加进 sites
 *   4. sites / sites_bac 里指向本地文件的条目，文件已不存在 → 整条删除（两边都删）
 *   5. 远程条目（api 以 http(s):// 开头）不参与扫描，永不自动删除
 */
object LocalScriptSource {

    const val SCHEME = "lscript://"
    const val STORE_FILE = "lscript.json"
    const val PROXY_KEY = "代理"
    const val PROXY_FLAG = "proxy"

    private const val MAX_FILES = 2000

    private val EXTS = arrayOf(".py", ".js", ".html")

    fun path(apiUrl: String): String = apiUrl.removePrefix(SCHEME)

    fun isLocalScript(apiUrl: String?): Boolean = apiUrl != null && apiUrl.startsWith(SCHEME)

    fun scripts(dir: String): List<File> {
        val root = File(dir)
        if (!root.isDirectory) return emptyList()
        val out = ArrayList<File>()
        collect(root, out)
        out.sortBy { it.absolutePath }
        return out
    }

    private fun collect(dir: File, out: MutableList<File>) {
        if (out.size >= MAX_FILES) return
        val list = dir.listFiles() ?: return
        for (f in list) {
            if (out.size >= MAX_FILES) return
            if (f.isDirectory) {
                collect(f, out)
            } else {
                val n = f.name.lowercase()
                if (EXTS.any { n.endsWith(it) }) out.add(f)
            }
        }
    }

    fun count(dir: String): Int = scripts(dir).size

    // ---------------------------------------------------------------- store

    private fun storeFile(dir: String): File = File(dir, STORE_FILE)

    // 远程判定与本地路径换算统一收敛到 HtmlUrl，避免这里与 WebView 加载各判各的
    private fun isRemote(api: String?): Boolean = HtmlUrl.isRemote(api)

    private fun isFileBacked(api: String?): Boolean = !isRemote(api)

    private fun localPathOf(api: String?): String? = HtmlUrl.localPathOf(api)

    private fun extOf(file: File): String {
        val n = file.name.lowercase()
        return EXTS.firstOrNull { n.endsWith(it) } ?: ""
    }

    /** 全部走 type 3：py / js 走脚本通道，html 走宿主内置 Csp_Nostr。 */
    private fun typeOf(ext: String): Int = 3

    private fun labelOf(ext: String): String = when (ext) {
        ".py" -> "[py]"
        ".js" -> "[js]"
        ".html" -> "[html]"
        else -> "[url]"
    }

    /** 站点 key：相对路径去扩展名 + 类型标签，保证唯一且稳定 */
    private fun keyOf(root: File, file: File): String {
        val rel = runCatching {
            file.absolutePath.removePrefix(root.absolutePath).trim('/')
        }.getOrElse { file.name }
        return rel.removeSuffix(extOf(file)) + labelOf(extOf(file))
    }

    private fun proxyOf(root: File, file: File): String {
        val rel = runCatching {
            file.absolutePath.removePrefix(root.absolutePath)
        }.getOrElse { file.name }
        val hit = rel.contains(PROXY_KEY) || file.name.contains(PROXY_KEY) ||
                (file.parentFile?.name?.contains(PROXY_KEY) == true)
        return if (hit) PROXY_FLAG else ""
    }

    private fun newItem(file: File, root: File): JSONObject {
        val ext = extOf(file)
        val label = labelOf(ext)
        val o = JSONObject()
        o.put("key", keyOf(root, file))
        o.put("name", file.nameWithoutExtension + label)
        o.put("type", typeOf(ext))
        if (ext == ".html" || ext == ".htm") {
            // html 站点：走宿主内置 Csp，页面地址放【顶层 homePage】
            o.put("api", BuiltinSpiders.HTML_API)
            o.put("homePage", fileHomePage(file))
        } else {
            o.put("api", "file://" + file.absolutePath)
        }
        o.put("ext", "")
        o.put("proxy", proxyOf(root, file))
        o.put("searchable", 1)
        o.put("changeable", 1)
        o.put("quickSearch", 1)
        return o
    }

    /** 本地 html 页面地址：统一由 HtmlUrl 生成（端口按运行时实际值，不写死 9978）。 */
    private fun fileHomePage(file: File): String = HtmlUrl.localPage(file)

    /** html 条目背后的本地文件路径，用于判断文件还在不在 */
    private fun htmlLocalPath(o: JSONObject): String? =
        HtmlUrl.localPathOf(o.optString("homePage"))

    /** 老数据迁移：type 6 一律换成 type 3 + 内置 Csp_Nostr + 顶层 homePage */
    private fun migrate(o: JSONObject): Boolean {
        val api = o.optString("api")
        var home = o.optString("homePage")
        val isHtmlFile = api.endsWith(".html", true) || api.endsWith(".htm", true)
        if (home.isBlank() && isHtmlFile) home = HtmlUrl.normalize(api).pageUrl
        if (home.isBlank() && HtmlUrl.isRemote(api)) home = HtmlUrl.normalize(api).pageUrl
        if (home.isBlank()) return false
        o.put("type", 3)
        o.put("api", BuiltinSpiders.HTML_API)
        o.put("homePage", home)
        return true
    }

    private fun readStore(dir: String): JSONObject {
        val f = storeFile(dir)
        if (!f.isFile) return JSONObject()
        return try {
            JSONObject(f.readText())
        } catch (th: Throwable) {
            JSONObject()
        }
    }

    private fun writeStore(dir: String, root: JSONObject) {
        val d = File(dir)
        if (!d.isDirectory) return
        try {
            storeFile(dir).writeText(root.toString())
        } catch (th: Throwable) {
            AppLog.e("LocalScriptSource", th)
        }
    }

    private fun arr(root: JSONObject, name: String): JSONArray {
        val a = root.optJSONArray(name)
        if (a != null) return a
        val n = JSONArray()
        root.put(name, n)
        return n
    }

    private fun toList(a: JSONArray?): MutableList<JSONObject> {
        val out = ArrayList<JSONObject>()
        if (a == null) return out
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            out.add(o)
        }
        return out
    }

    /** name 去重：重名自动追加 (2) (3) ... */
    private fun uniqueName(name: String, taken: Collection<String>): String {
        if (!taken.contains(name)) return name
        var i = 2
        while (taken.contains("$name ($i)")) i++
        return "$name ($i)"
    }

    /** 扫描目录 + 读 json，按规则合并；有变更时回写 json。返回 [生效列表, 回收站列表] */
    fun load(dir: String): Pair<MutableList<JSONObject>, MutableList<JSONObject>> {
        val rootDir = File(dir)
        if (!rootDir.isDirectory) return Pair(ArrayList(), ArrayList())

        val store = readStore(dir)
        val sites = toList(store.optJSONArray("sites"))
        val bac = toList(store.optJSONArray("sites_bac"))

        val found = scripts(dir)
        val foundPaths = HashMap<String, File>()
        for (f in found) foundPaths[localPathOf("file://" + f.absolutePath) ?: f.absolutePath] = f

        // key 集合必须在清除之前算：否则刚被清掉的条目会被下面当成"新文件"重新加回来
        val liveKeys = HashSet<String>()
        for (o in sites) liveKeys.add(o.optString("key"))
        for (o in bac) liveKeys.add(o.optString("key"))

        // 老数据迁移：type 6（旧 html 站点）统一换成 type 3 + Csp_Nostr + 顶层 homePage
        var migrated = false
        for (o in sites) if (migrate(o)) migrated = true
        for (o in bac) if (migrate(o)) migrated = true

        // 规则 4：两边都清掉已不存在的本地文件条目
        purgeGone(sites, foundPaths)
        purgeGone(bac, foundPaths)

        // 规则 3：新文件追加进 sites（规则 1、2 自然满足——已在里面就不动）
        var changed = false
        for (f in found) {
            val k = keyOf(rootDir, f)
            if (liveKeys.contains(k)) continue
            sites.add(newItem(f, rootDir))
            liveKeys.add(k)
            changed = true
        }

        if (changed || migrated) save(dir, sites, bac)
        return Pair(sites, bac)
    }

    private fun purgeGone(list: MutableList<JSONObject>, foundPaths: Map<String, File>) {
        val it = list.iterator()
        while (it.hasNext()) {
            val o = it.next()
            val api = o.optString("api")
            val home = o.optString("homePage")
            // 远程条目（含远程 html 页面）永不自动删除
            if (isRemote(api) || isRemote(home)) continue
            val p = localPathOf(api) ?: htmlLocalPath(o) ?: continue
            if (!foundPaths.containsKey(p)) it.remove()
        }
    }

    fun save(dir: String, sites: List<JSONObject>, bac: List<JSONObject>) {
        val store = readStore(dir)
        val sa = JSONArray()
        for (o in sites) sa.put(o)
        val ba = JSONArray()
        for (o in bac) ba.put(o)
        store.put("sites", sa)
        store.put("sites_bac", ba)
        if (!store.has("lives")) {
            val lives = JSONArray()
            lives.put(JSONObject())
            store.put("lives", lives)
        }
        writeStore(dir, store)
    }

    fun buildJson(apiUrl: String): String {
        val dir = path(apiUrl)
        val (sites, _) = load(dir)
        val out = JSONArray()
        for (i in sites.indices) {
            val o = sites[i]
            // 顺序即展示顺序，写入时按当前下标固化
            out.put(o)
        }
        val lives = JSONArray()
        lives.put(JSONObject())
        val root = JSONObject()
        root.put("warningText", "本地脚本目录：" + dir)
        root.put("sites", out)
        root.put("lives", lives)
        return root.toString()
    }

    // ---------------------------------------------------------------- 编辑动作

    /** 手工添加远程 url 站点 */
    fun addUrl(dir: String, name: String, url: String): Boolean {
        val (sites, bac) = load(dir)
        val taken = HashSet<String>()
        for (o in sites) taken.add(o.optString("name"))
        for (o in bac) taken.add(o.optString("name"))
        val proxy = if (url.contains("#$PROXY_KEY")) PROXY_FLAG else ""
        val o = JSONObject()
        o.put("key", (MD5.encode(url) ?: url).take(12))
        o.put("name", uniqueName(name.ifBlank { url } + "[url]", taken))
        o.put("type", 3)
        o.put("api", BuiltinSpiders.HTML_API)
        // 粘贴的 url 同样过一遍归一化：三斜杠笔误、file://、回环 /file/ 都在这里修好
        o.put("homePage", HtmlUrl.normalize(url).pageUrl)
        o.put("ext", "")
        o.put("proxy", proxy)
        o.put("searchable", 1)
        o.put("changeable", 1)
        o.put("quickSearch", 1)
        sites.add(o)
        save(dir, sites, bac)
        return true
    }

    /** sites -> sites_bac（脚本文件本身不动） */
    fun remove(dir: String, keys: Collection<String>): Boolean {
        if (keys.isEmpty()) return false
        val (sites, bac) = load(dir)
        val move = sites.filter { keys.contains(it.optString("key")) }
        if (move.isEmpty()) return false
        sites.removeAll(move.toSet())
        for (o in move) {
            val taken = HashSet<String>()
            for (x in bac) taken.add(x.optString("key"))
            if (!taken.contains(o.optString("key"))) bac.add(o)
        }
        save(dir, sites, bac)
        return true
    }

    /** sites_bac -> sites */
    fun restore(dir: String, keys: Collection<String>): Boolean {
        if (keys.isEmpty()) return false
        val (sites, bac) = load(dir)
        val move = bac.filter { keys.contains(it.optString("key")) }
        if (move.isEmpty()) return false
        bac.removeAll(move.toSet())
        val taken = HashSet<String>()
        for (x in sites) taken.add(x.optString("key"))
        for (o in move) if (!taken.contains(o.optString("key"))) sites.add(o)
        save(dir, sites, bac)
        return true
    }

    /**
     * 排序：把 keys 里的条目整体上移/下移一位；inBac=true 时作用于回收站。
     *
     * visibleKeys 非空表示当前处于某个分类筛选下。此时显示的是全量列表的【子集】，
     * 若仍按全量相邻交换，可见的相邻两项在全量里往往隔着别的分类项，换完屏幕上的
     * 顺序纹丝不动 —— 就是"切换分类后上下移动无效"的错觉来源。
     * 分类模式下改为在可见序列内与相邻可见项交换，再写回全量对应的位置。
     */
    fun reorder(
        dir: String,
        keys: Collection<String>,
        up: Boolean,
        inBac: Boolean = false,
        visibleKeys: Set<String>? = null,
    ): Boolean {
        if (keys.isEmpty()) return false
        val (sites, bac) = load(dir)
        val list = if (inBac) bac else sites
        if (visibleKeys != null) {
            val pos = (0 until list.size).filter { visibleKeys.contains(list[it].optString("key")) }
            if (pos.isEmpty()) return false
            val vis = ArrayList(pos.map { list[it] })
            var changed = false
            if (up) {
                for (i in 1 until vis.size) {
                    if (keys.contains(vis[i].optString("key")) &&
                        !keys.contains(vis[i - 1].optString("key"))
                    ) {
                        val t = vis[i]; vis[i] = vis[i - 1]; vis[i - 1] = t
                        changed = true
                    }
                }
            } else {
                for (i in vis.size - 2 downTo 0) {
                    if (keys.contains(vis[i].optString("key")) &&
                        !keys.contains(vis[i + 1].optString("key"))
                    ) {
                        val t = vis[i]; vis[i] = vis[i + 1]; vis[i + 1] = t
                        changed = true
                    }
                }
            }
            if (!changed) return false
            for ((k, p) in pos.withIndex()) list[p] = vis[k]
            save(dir, sites, bac)
            return true
        }
        val idx = ArrayList<Int>()
        for (i in list.indices) if (keys.contains(list[i].optString("key"))) idx.add(i)
        if (idx.isEmpty()) return false
        if (up) {
            if (idx.first() <= 0) return false
            for (i in idx) {
                val t = list[i]
                list[i] = list[i - 1]
                list[i - 1] = t
            }
        } else {
            if (idx.last() >= list.size - 1) return false
            for (i in idx.reversed()) {
                val t = list[i]
                list[i] = list[i + 1]
                list[i + 1] = t
            }
        }
        save(dir, sites, bac)
        return true
    }

    /** 位置互换：两侧被选中的条目同时移到对面尾部（保持各自相对顺序） */
    fun swap(dir: String, fromSites: Collection<String>, fromBac: Collection<String>): Boolean {
        if (fromSites.isEmpty() && fromBac.isEmpty()) return false
        val (sites, bac) = load(dir)
        val a = sites.filter { fromSites.contains(it.optString("key")) }
        val b = bac.filter { fromBac.contains(it.optString("key")) }
        if (a.isEmpty() && b.isEmpty()) return false
        sites.removeAll(a.toSet())
        bac.removeAll(b.toSet())
        for (o in a) bac.add(o)
        for (o in b) sites.add(o)
        save(dir, sites, bac)
        return true
    }

    /** 改 name / ext / proxy / api（远程地址）；本地脚本的 api 路径不可改 */
    fun update(dir: String, key: String, name: String?, ext: String?, proxy: String?, api: String?): Boolean {
        val (sites, bac) = load(dir)
        val target = sites.firstOrNull { it.optString("key") == key }
            ?: bac.firstOrNull { it.optString("key") == key }
            ?: return false
        if (!name.isNullOrEmpty()) {
            val taken = HashSet<String>()
            for (o in sites) if (o.optString("key") != key) taken.add(o.optString("name"))
            for (o in bac) if (o.optString("key") != key) taken.add(o.optString("name"))
            target.put("name", uniqueName(name, taken))
        }
        if (ext != null) target.put("ext", ext)
        if (proxy != null) target.put("proxy", proxy)
        if (!api.isNullOrEmpty() && isRemote(api)) target.put("api", api)
        save(dir, sites, bac)
        return true
    }
}
