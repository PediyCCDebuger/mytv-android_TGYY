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
import java.net.NetworkInterface
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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
    private const val MAX_NODE_TRIES = 6

    // 用 ThreadLocal：同步 call 的 EventListener 回调与 execute 在同一线程，
    // 而内核是多线程并发请求，共享字段会互相覆盖（诊断数据错乱）。
    private val tlIp = ThreadLocal<String>()
    private val tlUrl = ThreadLocal<String>()
    private val tlHeaders = ThreadLocal<String>()

    private val baseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(5, TimeUnit.SECONDS)
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

    // 本机是否真的有可用的公网 IPv6。缓存 60 秒（换网/开关 IPv6 后最迟一分钟自愈）。
    private val ipv6CheckedAt = AtomicLong(0)
    private val ipv6Usable = AtomicBoolean(false)

    /**
     * 判断本机是否存在**可用的全局 IPv6 地址**。
     *
     * 为什么必须判断：域名（如 live-dtocnc-cdn.ysp.cctv.cn）同时有 A 和 AAAA 记录。
     * 机顶盒重启光猫后可能拿不到 IPv6 前缀（实测 `ip -6 addr` 为空、`ping6` 报
     * "Destination unreachable: Beyond scope of source address"），但 AAAA 记录照样
     * 存在。此时若按 "有 AAAA 就优先 IPv6" 去请求，每个节点都会干等到连接超时，
     * 表现为切台黑屏十几秒然后播放器报 2004。
     */
    private fun hasGlobalIpv6(): Boolean {
        val now = System.currentTimeMillis()
        if (now - ipv6CheckedAt.get() < 60_000L) return ipv6Usable.get()
        var found = false
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet6Address &&
                        !addr.isLinkLocalAddress &&
                        !addr.isLoopbackAddress &&
                        !addr.isAnyLocalAddress
                    ) {
                        found = true
                        break
                    }
                }
                if (found) break
            }
        } catch (_: Exception) {
            found = false
        }
        ipv6Usable.set(found)
        ipv6CheckedAt.set(now)
        return found
    }

    private fun clientFor(addr: InetAddress?, timeoutMs: Int = 0): OkHttpClient {
        var builder = baseClient.newBuilder()
        if (timeoutMs > 0) {
            val ms = timeoutMs.toLong().coerceIn(2_000L, 15_000L)
            builder = builder.connectTimeout(ms, TimeUnit.MILLISECONDS)
        }
        if (addr != null) {
            val pinned = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = listOf(addr)
            }
            // newBuilder 复用连接池/配置，开销很小
            builder = builder.dns(pinned)
        }
        return builder.build()
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
     * 解析该 URL 的候选节点。
     *
     * 历史结论是「IPv6 取址返回的地址才有效」（同一时刻 IPv4 取址得到 403），
     * 所以在有 IPv6 的环境里仍然 IPv6 优先。但**必须保留 IPv4 作为后备**：
     * 之前写成 `if (v6.isNotEmpty()) v6 else v4`，只要 DNS 有 AAAA 就完全不试 IPv4，
     * 于是盒子拿不到 IPv6 前缀时每个节点都卡到连接超时（实测 8s×2），
     * 切台黑屏十几秒后才报错。
     */
    private fun resolveNodes(url: String, family: String = ""): List<InetAddress> {
        val host = url.toHttpUrlOrNull()?.host ?: return emptyList()
        return try {
            val all = Dns.SYSTEM.lookup(host)
            val v6 = all.filterIsInstance<Inet6Address>().distinctBy { it.hostAddress }
            val v4 = all.filterIsInstance<Inet4Address>().distinctBy { it.hostAddress }
            when (family.lowercase()) {
                "v4", "4", "ipv4" -> v4
                "v6", "6", "ipv6" -> v6
                // 默认：本机真有全局 IPv6 才把 AAAA 节点排在前面；没有就直接只用 IPv4。
                // 没 IPv6 时连 AAAA 节点纯属浪费（每个都要等到连接超时），干脆不试。
                else -> if (hasGlobalIpv6()) v6 + v4 else v4
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
            return execute(clientFor(null, timeoutMs), request, isHead)
        }

        var last: YspHttpResult? = null
        var tries = 0
        for (addr in nodes.take(MAX_NODE_TRIES)) {
            tries += 1
            // 逐节点重试时把连接超时压到传入值，避免坏节点把整体预算耗尽
            val r = execute(clientFor(addr, timeoutMs), request, isHead)
            r.attempts = tries
            if (r.remoteIp.isBlank()) r.remoteIp = addr.hostAddress ?: ""
            if (r.ok || r.status in 300..399) return r
            last = r
            // 连不上、被节点拒绝、节点故障时才换下一个节点；
            // 其它 4xx（如 404 地址本身失效）换节点也没用，直接返回。
            val worthRetry = r.status == 0 || r.status == 403 || r.status >= 500
            if (!worthRetry) return r
        }
        return last ?: execute(clientFor(null, timeoutMs), request, isHead)
    }
}
