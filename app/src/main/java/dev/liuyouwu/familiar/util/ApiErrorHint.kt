package dev.liuyouwu.familiar.util

/**
 * 把 HTTP 状态码 / 异常 / 后端错误体翻译成用户能看懂的中文提示。
 * 所有网络路径（模型列表 / 聊天流 / 语音转写）统一走这里。
 */
object ApiErrorHint {

    fun fromHttpStatus(status: Int, body: String): String {
        val detail = body.take(300).trim()
        val lower = detail.lowercase()
        return buildString {
            when {
                status == 401 || status == 403 ->
                    append("认证失败：API Key 不正确或已过期")
                status == 404 -> when {
                    lower.contains("model") ->
                        append("模型不存在：去连接页重新选一个")
                    else ->
                        append("接口不存在 (404)：检查服务器地址")
                }
                status == 400 && (lower.contains("context") || lower.contains("exceed") || lower.contains("too long")) ->
                    append("上下文超限：调小「最大上下文」、清掉大附件，或新建会话")
                status == 400 ->
                    append("请求被服务器拒绝 (400)")
                status == 429 ->
                    append("请求过于频繁或额度不足 (429)")
                status == 402 ->
                    append("余额不足 (402)：充值或检查这个 Key 的计费设置")
                status == 408 ->
                    append("请求超时 (408)：稍后重试")
                status == 413 ->
                    append("内容过大 (413)：缩短消息或移除大附件")
                status in 500..599 ->
                    append("服务端出错了 ($status)：重启服务或看它的日志")
                else ->
                    append("请求失败 (HTTP $status)")
            }
            if (detail.isNotEmpty()) append("\n服务器返回：").append(detail)
        }
    }

    fun fromThrowable(e: Throwable?, serverDesc: String = "服务器"): String {
        val msg = e?.message ?: "未知错误"
        // 原始异常信息里带实际尝试的地址（如 "Failed to connect to /192.168.10.233:1234"），
        // 一并展示：填错 IP（如把手机自己的组网 IP 当服务器）一眼就能看出来
        return when (e) {
            is java.net.ConnectException ->
                "连不上 $serverDesc（${msg.take(100)}）。常用端口：8080 (llama.cpp) / 11434 (Ollama) / 1234 (LM Studio)；填完整地址再试，或用局域网扫描"
            is java.net.SocketTimeoutException ->
                "连接超时：$serverDesc 没响应（${msg.take(100)}）。模型太大加载慢会这样，稍后重试"
            is java.net.UnknownHostException ->
                "解析不了主机名：检查地址拼写"
            is javax.net.ssl.SSLException ->
                "HTTPS 证书错误：${msg.take(120)}"
            is java.net.NoRouteToHostException, is java.net.SocketException ->
                "服务没响应：确认服务已启动、连的是同一个网络，或重启服务"
            else -> msg
        }
    }

    /** 解析流式/非流式响应里的 {"error": ...} 结构，返回错误描述，没有则返回 null */
    fun extractApiError(jsonText: String): String? {
        return try {
            val obj = com.google.gson.JsonParser.parseString(jsonText).takeIf { it.isJsonObject }
                ?.asJsonObject ?: return null
            val err = obj.get("error") ?: return null
            // "error": null 是合法响应（部分网关每个 chunk 都带这个键），不算错误
            if (err.isJsonNull) return null
            when {
                err.isJsonObject -> {
                    val e = err.asJsonObject
                    val msg = e.get("message")?.takeIf { !it.isJsonNull }?.asString
                        ?: e.get("content")?.takeIf { !it.isJsonNull }?.asString
                        ?: e.toString()
                    msg
                }
                err.isJsonPrimitive -> err.asString
                else -> err.toString()
            }
        } catch (_: Exception) {
            null
        }
    }
}
