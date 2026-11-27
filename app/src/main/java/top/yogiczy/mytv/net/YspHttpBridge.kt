package top.yogiczy.mytv.net

import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.json.JSONObject
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * 一次上游 HTTP 请求的结果，字段全部 @JvmField 以便 Chaquopy 里的 Python 直接读取。
 */
class YspHttpResult {
    @JvmField var ok: Boolean = false
    @JvmField var status: Int = 0
    @JvmField var text: String = ""
    @JvmField var body: ByteArray = ByteArray(0)
    @JvmField var error: String = ""
    @JvmField var finalUrl: String = ""
    @JvmField var contentType: String = ""
    @JvmField var contentRange: String = ""
    @JvmField var acceptRanges: String = ""
    /** 本次实际建立连接的节点 IP（诊断用）。 */
    @JvmField var remoteIp: String = ""
    /** 少量关键响应头（诊断用）。 */
    @JvmField var headers: String = ""
    /** 实际发出的请求头（诊断用，含 OkHttp 自动补充的头）。 */
    @JvmField var sentHeaders: String = ""
    /** 实际发出的 URL（诊断用，用于发现 URL 是否被改写）。 */
    @JvmField var sentUrl: String = ""
    @JvmField var elapsedMs: Long = 0
    /** 本次请求尝试过的节点数量。 */
    @JvmField var attempts: Int = 0
}

/**
 * 内嵌 Python 内核（ysplive.py）访问上游 CDN 的 HTTP 出口。
 *
 * 为什么需要它：
 * 电视盒子上的 Python 标准库（OpenSSL）请求央视/卫视 CDN 的 m3u8 会被风控判为
 * 脚本客户端，且盒子 CPU 弱、urllib 每次重建 TLS 连接太慢，取到的播放地址往往
 * 已经临近过期。改由 OkHttp（系统 TLS 栈 + 连接池）代发请求。
 *
 * 实测另外两个关键点：
 *  1) 强制 HTTP/1.1——OkHttp 默认走 h2，部分 CDN 边缘对 h2 客户端直接 403；
 *  2) 同一域名会解析出多个节点，其中夹杂连不通或直接拒绝的坏节点（实测
 *     117.91.185.19 不可达、43.142.232.32 返回 503），而机体盒子的 DNS 顺序
 *     恰好会把坏节点排在前面，于是每次请求都撞在坏节点上返回 403。
 *     因此这里逐个 IP 重试，跳过失败/拒绝的节点。
 */
object YspHttpBridge {

    /** 单个响应体上限，防止异常上游把内存打满。 */
    private const val MAX_BODY = 40 * 1024 * 1024

    /** 单次请求最多尝试的节点数。 */
    private const val MAX_NODE_TRIES = 4

    // 用 ThreadLocal：同步 call 的 EventListener 回调与 execute 在同一线程，
    // 而内核是多线程并发请求，共享字段会互相覆盖（诊断数据错乱）。
    private val tlIp = ThreadLocal<String>()
    private val tlUrl = ThreadLocal<String>()
    private val tlHeaders = ThreadLocal<String>()

    private val baseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(18, TimeUnit.SECONDS)
            .writeTimeout(18, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .eventListenerFactory {
                object : EventListener() {
                    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
                        tlIp.set(inetSocketAddress.address?.hostAddress ?: "")
                    }

                    override fun requestHeadersEnd(call: Call, request: Request) {
                        tlUrl.set(request.url.toString())
                        tlHeaders.set(
                            request.headers.joinToString("; ") { "${it.first}:${it.second}" }
                        )
                    }
                }
            }
            .build()
    }

    private fun clientFor(addr: InetAddress?): OkHttpClient {
        if (addr == null) return baseClient
        val pinned = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = listOf(addr)
        }
        // newBuilder 复用连接池/配置，开销很小
        return baseClient.newBuilder().dns(pinned).build()
    }

    private fun buildRequest(url: String, headersJson: String, method: String): Request? {
        val builder = try {
            Request.Builder().url(url)
        } catch (_: Exception) {
            return null
        }
        if (method.equals("HEAD", ignoreCase = true)) builder.head() else builder.get()
        if (headersJson.isNotBlank()) {
            try {
                val jo = JSONObject(headersJson)
                for (key in jo.keys()) {
                    val value = jo.optString(key, "")
                    if (value.isEmpty()) continue
                    // Host 交给 OkHttp；Content-Length 由请求体决定
                    if (key.equals("Host", true) || key.equals("Content-Length", true)) continue
                    try {
                        builder.header(key, value)
                    } catch (_: IllegalArgumentException) {
                        // 非法头名忽略
                    }
                }
            } catch (_: Exception) {
                // headersJson 解析失败按无额外头处理
            }
        }
        return builder.build()
    }

    private fun execute(client: OkHttpClient, request: Request, isHead: Boolean): YspHttpResult {
        val result = YspHttpResult()
        tlIp.set("")
        tlUrl.set("")
        tlHeaders.set("")
        val startedAt = System.currentTimeMillis()
        try {
            client.newCall(request).execute().use { resp ->
                result.status = resp.code
                result.ok = resp.isSuccessful
                result.finalUrl = resp.request.url.toString()
                result.elapsedMs = System.currentTimeMillis() - startedAt
                result.remoteIp = tlIp.get() ?: ""
                result.contentType = resp.header("Content-Type") ?: ""
                result.contentRange = resp.header("Content-Range") ?: ""
                result.acceptRanges = resp.header("Accept-Ranges") ?: ""
                result.headers = buildString {
                    for (name in listOf("server", "x-cache", "x-via", "via", "date", "content-type")) {
                        resp.header(name)?.let { append(name).append('=').append(it).append(' ') }
                    }
                }
                val bytes = if (isHead) {
                    ByteArray(0)
                } else {
                    resp.body?.let { b ->
                        if (b.contentLength() > MAX_BODY) ByteArray(0) else b.bytes()
                    } ?: ByteArray(0)
                }
                result.body = bytes
                result.text = String(bytes, Charsets.UTF_8)
                result.sentUrl = tlUrl.get() ?: ""
                result.sentHeaders = tlHeaders.get() ?: ""
                if (!resp.isSuccessful) result.error = "HTTP ${resp.code}"
            }
        } catch (e: Exception) {
            result.ok = false
            result.status = 0
            result.error = e.message ?: e.javaClass.simpleName
            result.elapsedMs = System.currentTimeMillis() - startedAt
        }
        return result
    }

    /**
     * 解析该 URL 的候选节点，**IPv6 优先**。
     *
     * 这是实测出来的硬结论：同一时刻向 bkliveinfo 取址，经 IPv6 取回的地址请求 200，
     * 经 IPv4 取回的地址请求 403（CDN 对 IPv4 客户端下发的是无效/降级地址）。
     * 机顶盒有公网 IPv6，因此统一走 IPv6；无 IPv6 才退回 IPv4。
     */
    private fun resolveNodes(url: String, family: String = ""): List<InetAddress> {
        val host = url.toHttpUrlOrNull()?.host ?: return emptyList()
        return try {
            val all = Dns.SYSTEM.lookup(host)
            val v6 = all.filterIsInstance<Inet6Address>()
            val v4 = all.filterIsInstance<Inet4Address>()
            when (family.lowercase()) {
                "v4", "4", "ipv4" -> v4.distinctBy { it.hostAddress }
                "v6", "6", "ipv6" -> v6.distinctBy { it.hostAddress }
                else -> (if (v6.isNotEmpty()) v6 else v4).distinctBy { it.hostAddress }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 发一个请求，返回结果（不抛异常，status=0 表示网络层失败）。
     *
     * @param url         目标地址
     * @param headersJson 额外请求头（JSON 对象字符串）
     * @param timeoutMs   预留参数
     * @param method      GET / HEAD
     */
    @JvmStatic
    @JvmOverloads
    fun fetch(
        url: String,
        headersJson: String = "",
        timeoutMs: Int = 8000,
        method: String = "GET",
        family: String = "",
    ): YspHttpResult {
        val request = buildRequest(url, headersJson, method)
            ?: return YspHttpResult().apply { error = "bad url" }
        val isHead = method.equals("HEAD", ignoreCase = true)

        val nodes = resolveNodes(url, family)
        if (nodes.isEmpty()) {
            return execute(clientFor(null), request, isHead)
        }

        var last: YspHttpResult? = null
        var tries = 0
        for (addr in nodes.take(MAX_NODE_TRIES)) {
            tries += 1
            val r = execute(clientFor(addr), request, isHead)
            r.attempts = tries
            if (r.remoteIp.isBlank()) r.remoteIp = addr.hostAddress ?: ""
            if (r.ok || r.status in 300..399) return r
            last = r
            // 连不上、被节点拒绝、节点故障时才换下一个节点；
            // 其它 4xx（如 404 地址本身失效）换节点也没用，直接返回。
            val worthRetry = r.status == 0 || r.status == 403 || r.status >= 500
            if (!worthRetry) return r
        }
        return last ?: execute(clientFor(null), request, isHead)
    }
}
