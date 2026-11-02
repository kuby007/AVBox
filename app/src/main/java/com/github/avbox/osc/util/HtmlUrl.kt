package com.github.avbox.osc.util

import android.net.Uri
import android.os.Environment
import com.github.avbox.osc.server.ControlManager
import com.github.avbox.osc.server.RemoteServer
import java.io.File

/**
 * html 页面地址的【唯一归一化入口】。
 *
 * 【定位】我们是宿主。本地页面一律走宿主自己的文件服务
 * http://127.0.0.1:<实际端口>/file/<相对外存路径> —— 这个端口是宿主起的，
 * 与外部 jar 无关。走 http 而不是 file://，是为了让页面的 baseURI 正确、
 * 页内 fetch/XHR 不被 CORS 拦掉。
 *
 * 以前同一件事散在六处各判各的（拼端口、抽 /file/、file:// 直载、
 * clan:// 换算、正则替换…），端口漂移、归类不一致、远程与本地走两条路
 * 都是这么来的。现在一律收敛到这里，调用方只拿 [Resolved.pageUrl] 去 loadUrl。
 */
object HtmlUrl {

    data class Resolved(
        /** 交给 WebView.loadUrl 的最终地址 */
        val pageUrl: String,
        /** 本地文件绝对路径；远程页面为 null */
        val localPath: String?,
        val local: Boolean,
    )

    private const val MARK = "/file/"

    private fun root(): String =
        Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')

    /**
     * 当前实际端口：9978 被占用时 ControlManager 会自动后挪，配置里写死的端口一律按实际值重建。
     * 只读已启动端口，不主动 startServer —— 配置解析 / 目录扫描阶段不该有拉起服务的副作用。
     */
    private fun base(): String {
        val port = try {
            ControlManager.get().startedPort().takeIf { it > 0 }
        } catch (th: Throwable) {
            null
        } ?: RemoteServer.serverPort
        return "http://127.0.0.1:" + port
    }

    /** 本地绝对路径 → 宿主文件服务地址。 */
    @JvmStatic
    fun localPage(absPath: String): String {
        val r = root()
        val p = absPath.trim()
        val rel = if (p.startsWith(r)) p.substring(r.length).trimStart('/') else p.trimStart('/')
        return base() + MARK + rel
    }

    @JvmStatic
    fun localPage(file: File): String = localPage(file.absolutePath)

    /**
     * 任意写法 → 本地文件绝对路径；不是本地写法返回 null。
     * 支持 file://、clan://localhost/、回环 /file/、裸绝对路径，并修掉 http:/// 三斜杠笔误。
     */
    @JvmStatic
    fun localPathOf(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val s = fixScheme(raw.trim())
        if (s.startsWith("clan://localhost/")) {
            return root() + "/" + Uri.decode(s.substring("clan://localhost/".length)).trimStart('/')
        }
        if (s.startsWith("file://")) {
            val p = Uri.decode(s.substring("file://".length))
            return if (p.startsWith("/")) p else root() + "/" + p
        }
        val i = s.indexOf(MARK)
        if (i >= 0 && isLoopback(s)) {
            val rel = Uri.decode(s.substring(i + MARK.length))
                .trimStart('/')
                .replace(Regex("localhost/"), "/") // 兼容老 clan 写法
            return root() + "/" + rel
        }
        return if (s.startsWith("/")) s else null
    }

    /** 远程页面（非回环）才返回 true；本地 /file/ 地址不算远程。 */
    @JvmStatic
    fun isRemote(raw: String?): Boolean {
        val s = fixScheme(raw?.trim().orEmpty())
        return (s.startsWith("http://") || s.startsWith("https://")) && !isLoopback(s)
    }

    /** 任意输入 → 唯一的页面地址。未知写法原样交回，由 WebView 自己决定。 */
    @JvmStatic
    fun normalize(raw: String?): Resolved {
        val src = raw?.trim().orEmpty()
        if (src.isEmpty()) return Resolved("", null, false)
        val s = fixScheme(src)
        val lp = localPathOf(s)
        if (lp != null) return Resolved(localPage(lp), lp, true)
        if (s.startsWith("http://") || s.startsWith("https://") || s.startsWith("file://")) {
            return Resolved(s, null, false)
        }
        return Resolved(s, null, false)
    }

    private fun fixScheme(s: String): String =
        s.replaceFirst("http:///", "http://").replaceFirst("https:///", "https://")

    private fun isLoopback(s: String): Boolean {
        if (s.startsWith("clan://")) return true
        if (!s.startsWith("http://") && !s.startsWith("https://")) return false
        val host = s.substringAfter("://").substringBefore('/').substringBefore('?')
        return host.startsWith("127.0.0.1") || host.startsWith("localhost") ||
            host.startsWith("[::1]")
    }
}
