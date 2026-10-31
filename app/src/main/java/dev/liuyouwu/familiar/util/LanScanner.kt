package dev.liuyouwu.familiar.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * 局域网自动发现本地 LLM 服务（Off Grid / Conduit 式的零配置连接）：
 * 对 /24 子网的常见端口做并发 TCP 探测，再对开放端口请求 /v1/models 验证
 * 并取回模型数量。全流程在 IO 线程。
 */
object LanScanner {

    data class FoundServer(val url: String, val modelCount: Int)

    /** 常见本地后端端口：llama.cpp / Ollama / LM Studio */
    private val COMMON_PORTS = listOf(8080, 11434, 1234, 1337)

    /** 误报过滤用（审计 P3：每次 probe 重新编译提为常量） */
    private val ID_KEY_REGEX = Regex("\"id\"\\s*:")

    private data class Candidate(val ip: String, val port: Int)

    suspend fun scan(
        localIp: String,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): List<FoundServer> = withContext(Dispatchers.IO) {
        val prefix = ipPrefix(localIp)
            ?: return@withContext emptyList()

        val client = OkHttpClient.Builder()
            .connectTimeout(400, TimeUnit.MILLISECONDS)
            .readTimeout(2500, TimeUnit.MILLISECONDS)
            .build()

        coroutineScope {
            val ips = (1..254).map { "$prefix$it" }
            val candidates = ips.flatMap { ip -> COMMON_PORTS.map { port -> Candidate(ip, port) } }
            val semaphore = java.util.concurrent.Semaphore(48)
            val done = java.util.concurrent.atomic.AtomicInteger()

            candidates.map { c ->
                async {
                    // 可中断获取：协程取消时不再死等信号量
                    runInterruptible { semaphore.acquire() }
                    try {
                        val result = probe(c.ip, c.port, client)
                        val d = done.incrementAndGet()
                        onProgress(d, candidates.size)
                        result
                    } finally {
                        semaphore.release()
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    /** 从设备网卡枚举本机 IPv4（无需定位权限）；拿不到返回 null */
    fun localIpv4OrNull(): String? = runCatching {
        val nics = java.net.NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .toList()
        fun v4(nic: java.net.NetworkInterface): String? =
            nic.inetAddresses.asSequence()
                .filterIsInstance<java.net.Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }?.hostAddress
        // 优先 WiFi/以太网，避免取到蜂窝(rmnet)/VPN(tun/ppp) 网卡扫错子网
        nics.sortedByDescending { nic ->
            when {
                nic.name.startsWith("wlan") || nic.name.startsWith("eth") -> 2
                nic.name.startsWith("tun") || nic.name.startsWith("ppp") ||
                    nic.name.startsWith("rmnet") -> 0
                else -> 1
            }
        }.firstNotNullOfOrNull { v4(it) }
    }.getOrNull()

    /** 从设备 IP 取 /24 前缀；非局域网地址返回 null */
    fun ipPrefix(ip: String): String? {
        val parts = ip.split(".")
        if (parts.size != 4) return null
        val first = parts[0].toIntOrNull() ?: return null
        val second = parts[1].toIntOrNull() ?: return null
        // 仅扫 RFC1918 私有网段：192.168/16、10/8、172.16-31
        val private = (first == 192 && second == 168) ||
            first == 10 ||
            (first == 172 && second in 16..31)
        if (!private) return null
        return "${parts[0]}.${parts[1]}.${parts[2]}."
    }

    private fun probe(ip: String, port: Int, client: OkHttpClient): FoundServer? {
        // TCP 探测
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(ip, port), 350)
        } catch (_: Exception) {
            return null
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }

        // 验证是 OpenAI 兼容服务并统计模型数。
        // 必须是 JSON 且至少有 1 个模型——否则路由器/打印机等
        // 返回 200 网页的设备会被误报为"服务"
        return try {
            val request = Request.Builder()
                .url("http://$ip:$port/v1/models")
                .header("Accept", "application/json")
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val type = resp.header("Content-Type") ?: ""
                if (!type.contains("json", ignoreCase = true)) return@use null
                val body = resp.body?.string() ?: return@use null
                if (!body.trimStart().startsWith("{")) return@use null
                val count = ID_KEY_REGEX.findAll(body).count()
                if (count == 0) return@use null
                FoundServer("http://$ip:$port", count)
            }
        } catch (_: Exception) {
            null
        }
    }
}
