package com.github.avbox.osc.server

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Environment
import android.text.TextUtils
import android.util.Base64

import com.github.avbox.osc.bean.VodInfo
import com.github.avbox.osc.data.PlaybackPorts
import com.github.avbox.osc.io.LocalSourceTree
import com.github.avbox.osc.net.HttpClients
import com.github.avbox.osc.net.StreamProxy
import com.github.avbox.osc.net.ProxyResponsePolicy
import com.github.avbox.osc.util.AppContextHolder
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.RegexUtils
import com.google.gson.JsonArray
import com.google.gson.JsonObject


import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern

import fi.iki.elonen.NanoHTTPD
import okio.Buffer

class RemoteServer(port: Int, context: Context) : NanoHTTPD(port) {
    private val hostContext: Context = context

    @Volatile
    private var isStarted = false

    private val getRequestList = ArrayList<RequestProcess>()
    private val postRequestList = ArrayList<RequestProcess>()

    init {
        addGetRequestProcess()
        addPostRequestProcess()
    }

    private fun addGetRequestProcess() {
        getRequestList.add(CacheRequestProcess())
    }

    private fun addPostRequestProcess() {
        postRequestList.add(CacheRequestProcess())
    }

    override fun start(timeout: Int, daemon: Boolean) {
        isStarted = true
        super.start(timeout, daemon)
    }

    override fun stop() {
        super.stop()
        isStarted = false
    }

    private fun getProxy(rs: Array<Any?>?, url: String?): NanoHTTPD.Response {
        try {
            if (rs == null || rs.size < 3) {
                AppLog.e("echo-proxy error: empty proxy result")
                return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, "500")
            }
            if (rs[0] is NanoHTTPD.Response) return rs[0] as NanoHTTPD.Response
            val code = rs[0] as Int
            val mime = rs[1] as String
            val body = if (rs[2] != null) rs[2] as InputStream else null
            val probe = if (body != null) PushbackInputStream(body, 1) else null
            val firstByte = try {
                probe?.read() ?: -1
            } catch (th: Throwable) {
                -1
            }
            val m3u8 = ProxyResponsePolicy.isM3u8Mime(mime)
            val status = ProxyResponsePolicy.effectiveStatus(code, mime, firstByte)
            if (status != code) {
                AppLog.e("echo-proxy-m3u8 empty body code=$code url=" + url.orEmpty().takeLast(80))
                return NanoHTTPD.newFixedLengthResponse(nanoStatus(status), NanoHTTPD.MIME_PLAINTEXT, "empty m3u8")
            }
            if (firstByte >= 0) probe?.unread(firstByte)
            val stream = if (probe != null && m3u8) ProxyBodyStream(probe, url) else probe
            val response = NanoHTTPD.newChunkedResponse(
                nanoStatus(status),
                mime,
                stream
            )
            if (rs.size >= 4 && rs[3] is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                val mapHeader = rs[3] as Map<String, String>
                if (mapHeader.isNotEmpty()) {
                    for (key in mapHeader.keys) {
                        response.addHeader(key, mapHeader[key])
                    }
                }
            }
            return response
        } catch (th: Throwable) {
            AppLog.e("echo-proxy error: " + th.message)
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, "500")
        }
    }

    private fun nanoStatus(code: Int): NanoHTTPD.Response.Status =
        NanoHTTPD.Response.Status.lookup(code) ?: NanoHTTPD.Response.Status.INTERNAL_ERROR

    private class ProxyBodyStream(private val delegate: InputStream, private val url: String?) : InputStream() {

        private var count = 0

        private var closed = false

        override fun read(): Int {
            val value = delegate.read()
            if (value >= 0) count += 1
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val read = delegate.read(b, off, len)
            if (read > 0) count += read
            return read
        }

        override fun available(): Int = delegate.available()

        override fun close() {
            synchronized(this) {
                if (closed) return
                closed = true
            }
            try {
                delegate.close()
            } finally {
                AppLog.i("echo-proxy-m3u8 served bytes=$count url=" + url.orEmpty().takeLast(80))
            }
        }
    }

    override fun serve(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (!session.uri.isEmpty()) {
            var fileName = session.uri.trim { it <= ' ' }
            if (fileName.indexOf('?') >= 0) {
                fileName = fileName.substring(0, fileName.indexOf('?'))
            }
            if (session.method == NanoHTTPD.Method.GET) {
                if (isProxyRequest(fileName, session.parms)) {
                    return handleProxy(session)
                }
                for (process in getRequestList) {
                    if (process.isRequest(session, fileName)) {
                        return process.doResponse(session, fileName, session.parms, null)
                    }
                }
                if (fileName.startsWith("/file/")) {
                    try {
                        val f = fileName.substring(6)
                        if (f.contains("..")) {
                            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.FORBIDDEN, NanoHTTPD.MIME_PLAINTEXT, "Forbidden")
                        }
                        val root = Environment.getExternalStorageDirectory().absolutePath
                        val file = "$root/$f"
                        val localFile = File(file)
                        if (localFile.isDirectory) {
                            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, NanoHTTPD.MIME_PLAINTEXT, fileList(root, f))
                        }
                        if (localFile.isFile) {
                            try {
                                return NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK, mimeOf(file), FileInputStream(localFile))
                            } catch (ignored: Throwable) {
                                AppLog.d("RemoteServer", "file open failed, fallback to tree grant")
                            }
                        }
                        val granted = if (isLocalRequest(session)) LocalSourceTree.open(requireNotNull(AppContextHolder.context()) { "app context not initialized" }, f) else null
                        if (granted != null) {
                            return NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK, mimeOf(file), granted)
                        }
                        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, "File $file not found!")
                    } catch (th: Throwable) {
                        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, th.message)
                    }
                } else if (fileName == "/dns-query") {
                    val name = session.parms["name"]
                    var rs = ByteArray(0)
                    try {
                        val dnsOverHttps = HttpClients.dnsOverHttps
                        val hostName = name
                        if (dnsOverHttps != null && !hostName.isNullOrEmpty()) {
                            rs = buildDnsResponse(hostName, dnsOverHttps.lookup(hostName))
                        }
                    } catch (th: Throwable) {
                        rs = ByteArray(0)
                    }
                    return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/dns-message", ByteArrayInputStream(rs), rs.size.toLong())
                } else if (fileName.startsWith("/proxyM3u8")) {
                    val content = getM3u8Content(session.parms["k"])
                    if (content == null) {
                        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "m3u8 slot not found")
                    }
                    return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/vnd.apple.mpegurl", content)
                } else if (fileName.startsWith("/dash/")) {
                    val dashData = ServerQueryPorts.ports?.dashData()
                    try {
                        val data = String(Base64.decode(dashData, Base64.DEFAULT or Base64.NO_WRAP), Charsets.UTF_8)
                        return NanoHTTPD.newFixedLengthResponse(
                            NanoHTTPD.Response.Status.OK,
                            "application/dash+xml",
                            data
                        )
                    } catch (th: Throwable) {
                        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, dashData)
                    }
                }
            } else if (session.method == NanoHTTPD.Method.POST) {
                val files = HashMap<String, String>()
                try {
                    if (session.headers.containsKey("content-type")) {
                        val hd = session.headers["content-type"]
                        if (hd != null) {
                            if (hd.lowercase().contains("multipart/form-data") && !hd.lowercase().contains("charset=")) {
                                val matcher = RegexUtils.getPattern("[ |\t]*(boundary[ |\t]*=[ |\t]*['|\"]?[^\"^'^;^,]*['|\"]?)", Pattern.CASE_INSENSITIVE).matcher(hd)
                                val boundary = if (matcher.find()) matcher.group(1) else null
                                if (boundary != null) {
                                    session.headers["content-type"] = "multipart/form-data; charset=utf-8; " + boundary
                                }
                            }
                        }
                    }
                    session.parseBody(files)
                } catch (ioExc: IOException) {
                    return createPlainTextResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "SERVER INTERNAL ERROR: IOException: " + ioExc.message)
                } catch (rex: NanoHTTPD.ResponseException) {
                    return createPlainTextResponse(rex.status, rex.message)
                }
                for (process in postRequestList) {
                    if (process.isRequest(session, fileName)) {
                        return process.doResponse(session, fileName, session.parms, files)
                    }
                }
            }
        }
        return createPlainTextResponse(NanoHTTPD.Response.Status.NOT_FOUND, "Not Found")
    }

    private fun isProxyRequest(fileName: String, params: Map<String, String>?): Boolean {
        if (params == null) return false
        if (!params.containsKey("do") && !params.containsKey("go")) return false
        return fileName == "/proxy" || fileName == "/"
    }

    private fun handleProxy(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val params = HashMap<String, String>(session.headers)
        params.putAll(session.parms)
        if (params.containsKey("do")) {
            val isDanmuProxy = "danmu" == params["do"]
            if (isDanmuProxy) normalizeDanmuParams(params)
            if (isDanmuProxy) AppLog.i("echo-proxy-danmu params: " + params.toString())
            val rs = ServerQueryPorts.ports?.proxyLocal(params)
            return getProxy(rs, params["url"])
        }
        if (params.containsKey("go")) {
            val rs = StreamProxy.proxy(params)
            return getProxy(rs, params["url"])
        }
        return getProxy(null, null)
    }

    private fun normalizeDanmuParams(params: MutableMap<String, String>) {
        try {
            val vodInfo = PlaybackPorts.ports?.currentVod() ?: return
            val vodName = vodInfo.name
            if (!vodName.isNullOrEmpty()) params["vodName"] = vodName
            if (!isNumeric(params["vodIndex"])) {
                val episode = currentEpisodeIndex(vodInfo)
                if (!episode.isNullOrEmpty()) params["vodIndex"] = episode
            }
        } catch (th: Throwable) {
            AppLog.e("echo-proxy-danmu normalize error: " + th.message)
        }
    }

    private fun currentEpisodeIndex(vodInfo: VodInfo): String? {
        val seriesMap = vodInfo.seriesMap
        if (seriesMap != null && !TextUtils.isEmpty(vodInfo.playFlag)) {
            val series = seriesMap[vodInfo.playFlag]
            if (series != null && vodInfo.playIndex >= 0 && vodInfo.playIndex < series.size) {
                val current: VodInfo.VodSeries? = series[vodInfo.playIndex]
                if (current != null && !TextUtils.isEmpty(current.name)) {
                    val number = extractNumber(current.name)
                    return if (TextUtils.isEmpty(number)) current.name else number
                }
            }
        }
        return (Math.max(0, vodInfo.playIndex) + 1).toString()
    }

    private fun isNumeric(text: String?): Boolean {
        return !text.isNullOrEmpty() && text.matches("\\d+".toRegex())
    }

    private fun extractNumber(text: String?): String {
        if (TextUtils.isEmpty(text)) return ""
        val matcher = RegexUtils.getPattern("\\d+").matcher(text)
        return if (matcher.find()) matcher.group() else ""
    }

    fun isStarting(): Boolean {
        return isStarted
    }

    fun serverAddress(): String {
        val ipAddress = getLocalIPAddress(hostContext)
        return "http://" + ipAddress + ":" + RemoteServer.serverPort + "/"
    }

    fun loadAddress(): String {
        return "http://127.0.0.1:" + RemoteServer.serverPort + "/"
    }

    private fun isLocalRequest(session: NanoHTTPD.IHTTPSession): Boolean {
        val address = session.remoteIpAddress
        return address != null && (address.startsWith("127.") || address == "::1")
    }

    private fun fileTime(time: Long, fmt: String): String {
        val calendar = Calendar.getInstance()
        calendar.setTimeInMillis(time)
        val date = calendar.time
        val sdf = SimpleDateFormat(fmt)
        return sdf.format(date)
    }

    private fun fileList(root: String, path: String): String {
        val file = File("$root/$path")
        val list = file.listFiles()
        val info = JsonObject()
        info.addProperty("remote", serverAddress().replace("http://", "clan://"))
        info.addProperty("del", 0)
        if (path.isEmpty()) {
            info.addProperty("parent", ".")
        } else {
            info.addProperty("parent", file.parentFile.absolutePath.replace("$root/", "").replace(root, ""))
        }
        if (list == null || list.isEmpty()) {
            info.add("files", JsonArray())
            return info.toString()
        }
        list.sortWith { o1, o2 ->
            if (o1.isDirectory && o2.isFile) -1
            else if (o1.isFile && o2.isDirectory) 1
            else o1.name.compareTo(o2.name)
        }
        val result = JsonArray()
        for (f in list) {
            if (f.name.startsWith(".")) {
                if (f.name == ".tvbox_folder") {
                    info.addProperty("del", 1)
                }
                continue
            }
            val fileObj = JsonObject()
            fileObj.addProperty("name", f.name)
            fileObj.addProperty("path", f.absolutePath.replace("$root/", ""))
            fileObj.addProperty("time", fileTime(f.lastModified(), "yyyy/MM/dd aHH:mm:ss"))
            fileObj.addProperty("dir", if (f.isDirectory) 1 else 0)
            result.add(fileObj)
        }
        info.add("files", result)
        return info.toString()
    }

    companion object {
        @JvmField
        var serverPort = 9978

        private const val M3U8_SLOT_LIMIT = 4

        private val m3u8Seq = AtomicLong(0)

        private val m3u8Slots: MutableMap<String, String> = Collections.synchronizedMap(
            object : LinkedHashMap<String, String>(8, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean {
                    return size > M3U8_SLOT_LIMIT
                }
            })

        @JvmStatic
        fun putM3u8Content(content: String): String {
            val key = "${System.currentTimeMillis()}-${m3u8Seq.incrementAndGet()}"
            m3u8Slots[key] = content
            return key
        }

        @JvmStatic
        fun getM3u8Content(key: String?): String? {
            return if (key == null) null else m3u8Slots[key]
        }

        @JvmStatic
        fun createPlainTextResponse(status: NanoHTTPD.Response.IStatus, text: String?): NanoHTTPD.Response {
            return NanoHTTPD.newFixedLengthResponse(status, NanoHTTPD.MIME_PLAINTEXT, text)
        }

        @SuppressLint("DefaultLocale")
        @JvmStatic
        fun getLocalIPAddress(context: Context?): String {
            val appContext = context ?: return ""
            val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ipAddress = wifiManager.connectionInfo.ipAddress
            if (ipAddress == 0) {
                try {
                    val enumerationNi = NetworkInterface.getNetworkInterfaces()
                    while (enumerationNi.hasMoreElements()) {
                        val networkInterface = enumerationNi.nextElement()
                        val interfaceName = networkInterface.displayName
                        if (interfaceName == "eth0" || interfaceName == "wlan0") {
                            val enumIpAddr = networkInterface.inetAddresses
                            while (enumIpAddr.hasMoreElements()) {
                                val inetAddress = enumIpAddr.nextElement()
                                if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                                    return inetAddress.hostAddress ?: ""
                                }
                            }
                        }
                    }
                } catch (e: SocketException) {
                    AppLog.e("RemoteServer", e)
                }
            } else {
                return String.format("%d.%d.%d.%d", (ipAddress and 0xff), (ipAddress shr 8 and 0xff), (ipAddress shr 16 and 0xff), (ipAddress shr 24 and 0xff))
            }
            return "0.0.0.0"
        }

        @JvmStatic
        fun buildDnsResponse(hostname: String, addresses: List<InetAddress>): ByteArray {
            var ipv6Only = addresses.isNotEmpty()
            for (address in addresses) {
                if (address !is Inet6Address) {
                    ipv6Only = false
                    break
                }
            }
            val rCode = if (addresses.isEmpty()) 2 else 0
            val buffer = Buffer()
            buffer.writeShort(0)
            buffer.writeShort(0x8180 or rCode)
            buffer.writeShort(1)
            buffer.writeShort(addresses.size)
            buffer.writeShort(0)
            buffer.writeShort(0)
            for (label in RegexUtils.getPattern("\\.").split(hostname)) {
                val raw = label.toByteArray(Charsets.UTF_8)
                buffer.writeByte(raw.size)
                buffer.write(raw)
            }
            buffer.writeByte(0)
            buffer.writeShort(if (ipv6Only) 0x001c else 0x0001)
            buffer.writeShort(1)
            for (address in addresses) {
                val ipv6 = address is Inet6Address
                val raw = address.address
                buffer.writeByte(0xc0)
                buffer.writeByte(0x0c)
                buffer.writeShort(if (ipv6) 0x001c else 0x0001)
                buffer.writeShort(1)
                buffer.writeInt(60)
                buffer.writeShort(raw.size)
                buffer.write(raw)
            }
            return buffer.readByteString().toByteArray()
        }
    }

    /**
     * 按扩展名返回正确的 MIME。
     * 以前一律返回 application/octet-stream，WebView 拿到后只会【触发下载、不渲染】——
     * html 页面经由 http://127.0.0.1:9978/file/ 打开时表现为"打不开/白屏"。
     */
    private fun mimeOf(pathOrName: String): String {
        val lower = pathOrName.substringAfterLast('/').lowercase()
        return when {
            lower.endsWith(".html") || lower.endsWith(".htm") -> "text/html; charset=UTF-8"
            lower.endsWith(".js") || lower.endsWith(".mjs") -> "application/javascript; charset=UTF-8"
            lower.endsWith(".css") -> "text/css; charset=UTF-8"
            lower.endsWith(".json") -> "application/json; charset=UTF-8"
            lower.endsWith(".xml") -> "application/xml; charset=UTF-8"
            lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".log") -> "text/plain; charset=UTF-8"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".svg") -> "image/svg+xml"
            lower.endsWith(".mp4") -> "video/mp4"
            lower.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
            else -> "application/octet-stream"
        }
    }
}
