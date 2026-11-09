package dev.liuyouwu.familiar.util

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 明文 http 运行时防线（审计 S1+S7）：http 请求只有目标是"局域网/本机"才允许携带
 * API Key 出网，公网 http 一律拒附（[PlaintextKeyRefusal]）。
 *
 * 判定是**纯函数、零 DNS 解析**：只做字面形态判定（点分十进制分段 / IPv6 冒号段），
 * 主机名（域名）一律不安全——不做 InetAddress 解析，测试与运行时都不触网。
 */
object NetGuards {

    /**
     * 目标主机是否"明文 http 安全"（本机/局域网）：
     * - IPv4：回环 127.0.0.0/8 + RFC1918 三段（10/8、172.16/12、192.168/16）→ true；
     * - `localhost`：回环保留名字面特判（spec「loopback → true」，大小写不敏感、
     *   零 DNS——本机自建服务 `http://localhost:8080` 是常态写法，按域名判不安全
     *   会误伤）；
     * - IPv6：回环 `::1` 与链路本地 fe80::/10 前缀（首组 fe80..febf）→ true；
     * - 其余（公网 IP、域名/主机名、解析不了的形态）→ false。
     * 方括号包裹（URL authority 形如 `[fe80::1]`）与大小写容错。
     */
    fun isPlaintextSafeHost(host: String): Boolean {
        val h = host.trim().lowercase().removeSurrounding("[", "]")
        if (h.isEmpty()) return false
        if (h == "localhost") return true   // 回环保留名（裁定 2）：字面特判，不解析
        if (h.contains(':')) {
            // IPv6 字面量（压缩形）：精确 "::1" 或 fe80:: 前缀（fe80::/10 链路本地段）
            if (h == "::1") return true
            val firstGroup = h.substringBefore(':').toIntOrNull(16) ?: return false
            return firstGroup in 0xFE80..0xFEBF
        }
        // 仅当确实是合法点分十进制 IPv4 才按私网段判定；域名一律不安全
        // （"192.168.evil.com" 这类私网前缀开头的域名不能靠 startsWith 放过）
        val octets = h.split('.')
        val isIpv4 = octets.size == 4 &&
            octets.all { it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) && it.toInt() in 0..255 }
        if (!isIpv4) return false
        val o1 = octets[0].toInt()
        val o2 = octets[1].toInt()
        return when {
            o1 == 127 -> true                        // 回环 127.0.0.0/8
            o1 == 10 -> true                         // RFC1918 10/8
            o1 == 172 && o2 in 16..31 -> true        // RFC1918 172.16/12（172.32 起越界）
            o1 == 192 && o2 == 168 -> true           // RFC1918 192.168/16
            else -> false
        }
    }

    /**
     * URL 串是否「明文 http 且目标非局域网」（UI 警示行判据，审计 S1+S7）：
     * 无协议前缀按 http:// 处理（与 [ServerUrl.normalize] 同约定——连接页输入框
     * 一律显示无前缀形态）；解析不了（输入中途/空串）→ false 不打扰。
     */
    fun isPlaintextUnsafeUrl(url: String): Boolean {
        val raw = url.trim()
        if (raw.isEmpty()) return false
        val withScheme = if (raw.contains("://")) raw else "http://$raw"
        val parsed = withScheme.toHttpUrlOrNull() ?: return false
        return parsed.scheme == "http" && !isPlaintextSafeHost(parsed.host)
    }

    /**
     * 明文 http 拒发闸（审计 S1+S7，裁定 1：五处带 Authorization 的请求组装出缝
     * 共用单点——LlmClient.chatCompletionsHttpRequest / ConnectionProbe.sendChatStream /
     * ModelsApi.fetchModels+probePort / Transcriber.transcribeAudio / McpClient）：
     * url 为明文 http 且目标非局域网（[isPlaintextSafeHost]）且凭证非空 → 抛
     * [PlaintextKeyRefusal]；其余（https、私网/回环 http、无凭证、解析失败）放行。
     *
     * @param what 文案名词：缺省 "API Key"（C/D/A/B 出缝），McpClient 传 "MCP token"
     */
    fun guardPlaintextCredential(url: String, credential: String, what: String = "API Key") {
        if (credential.isBlank()) return
        val parsed = url.toHttpUrlOrNull() ?: return
        if (parsed.scheme == "http" && !isPlaintextSafeHost(parsed.host)) {
            throw PlaintextKeyRefusal("http 且非局域网目标，已拒绝携带 $what 发起请求")
        }
    }
}

/**
 * 明文 http + 非局域网目标仍要携带 API Key 的拒发信号（审计 S1：key 明文出网）。
 * 消息即用户可见文案，经 [ApiErrorHint.fromThrowable] 的类型分支透传 UI。
 */
class PlaintextKeyRefusal(message: String) : RuntimeException(message)
