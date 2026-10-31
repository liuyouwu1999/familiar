package dev.liuyouwu.familiar.util

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 后端服务商预设。isLocal 为 true 的后端可以安全发送 max_tokens、
 * chat_template_kwargs 等本地服务器才认的参数；云端严格后端只发最标准的字段。
 */
enum class BackendProvider(
    val id: String,
    val label: String,
    val sampleUrl: String,
    val isLocal: Boolean,
    val hint: String
) {
    LLAMA_CPP("llama_cpp", "llama.cpp", "http://192.168.1.100:8080", true,
        "llama.cpp 默认端口 8080，填电脑的局域网 IP"),
    OLLAMA("ollama", "Ollama", "http://192.168.1.100:11434", true,
        "Ollama 默认端口 11434；电脑上先设 OLLAMA_HOST=0.0.0.0"),
    LM_STUDIO("lm_studio", "LM Studio", "http://192.168.1.100:1234", true,
        "LM Studio 默认端口 1234；先在它的开发者选项里打开本地服务"),
    CUSTOM("custom", "自定义端点", "https://api.openai.com", false,
        "自建/中转服务：填完整 API 地址，例如 https://my.relay.com"),
    CLOUD("cloud", "云端厂商", "https://api.deepseek.com", false,
        "先在下方选厂商（自动填官方地址），再填对应的 API Key");

    companion object {
        fun fromId(id: String?): BackendProvider =
            entries.firstOrNull { it.id == id } ?: LM_STUDIO

        fun fromPort(port: String?): BackendProvider = when (port?.trim()) {
            "8080" -> LLAMA_CPP
            "11434" -> OLLAMA
            "1234" -> LM_STUDIO
            else -> CUSTOM
        }
    }
}

/**
 * 服务器地址规范化。用户可能输入：
 *   192.168.1.5:8080 / http://192.168.1.5:8080/ / https://my.host/llm/v1 / localhost:11434
 * 统一成不含 /v1 后缀、不含尾斜杠的 base URL，供 API 层拼接 /v1/xxx。
 */
object ServerUrl {

    /** 规范化成功返回 base URL，失败返回 null */
    fun normalize(input: String): String? {
        var s = input.trim()
        if (s.isEmpty()) return null
        val schemeIdx = s.indexOf("://")
        if (schemeIdx > 0) {
            // 已带 scheme：只接受 http/https（ftp:// 等一律拒绝）
            val scheme = s.substring(0, schemeIdx).lowercase()
            if (scheme != "http" && scheme != "https") return null
        } else {
            s = "http://$s"
        }
        // 统一剥掉结尾的 /v1（用户常被教程引导填带 /v1 的地址），循环处理 /v1/ 形式
        while (true) {
            val lower = s.lowercase()
            when {
                lower.endsWith("/v1") -> s = s.dropLast(3)
                lower.endsWith("/") -> s = s.dropLast(1)
                else -> break
            }
        }
        val url = s.toHttpUrlOrNull() ?: return null
        if (url.scheme !in setOf("http", "https")) return null
        return url.toString().trimEnd('/')
    }

    /** 返回 null 表示合法，否则返回给用户看的错误说明 */
    fun validate(input: String): String? {
        if (input.trim().isEmpty()) return "服务器地址不能为空"
        val url = normalize(input)
            ?: return "地址格式不正确，示例：192.168.1.100:8080"
        val httpUrl = url.toHttpUrlOrNull() ?: return "地址格式不正确"
        val host = httpUrl.host
        if (host.isEmpty() || host == "0.0.0.0") return "主机地址无效"
        // "8080" 这种纯数字会被当主机名，运行期必然 UnknownHost，提前拦下
        if (host.matches(Regex("\\d+"))) return "地址里像是缺了主机，示例 192.168.1.100:${httpUrl.port}"
        return null
    }

    /**
     * 显示用形态：剥掉 http:// 前缀——界面上不让用户看到/维护明文协议前缀，
     * 输入框里只留 IP:端口，保存与请求前由 [normalize] 自动补回。
     * https:// 保留：加密连接必须显式可见，剥掉用户就不知道云端走的是 https。
     */
    fun stripHttpScheme(input: String): String {
        val s = input.trim()
        return if (s.length >= 7 && s.substring(0, 7).equals("http://", ignoreCase = true)) {
            s.substring(7)
        } else {
            s
        }
    }

    /** 从遗留的 ip+port 设置构造规范化地址（老版本迁移用） */
    fun fromLegacyIpPort(ip: String, port: String): String? {
        if (ip.isBlank()) return null
        // 老数据里 ip 可能已经被用户填成完整 URL
        if (ip.startsWith("http://", true) || ip.startsWith("https://", true)) {
            return normalize(ip)
        }
        val p = port.trim()
        return normalize(if (p.isEmpty()) ip else "$ip:$p")
    }

    /** 规范化后的地址是否带显式端口（http 默认 80 / https 默认 443 视为未带） */
    fun hasExplicitPort(normalized: String): Boolean {
        val url = normalized.toHttpUrlOrNull() ?: return true
        return when (url.scheme) {
            "https" -> url.port != 443
            else -> url.port != 80
        }
    }

    /** 接口路径规范化：空输入 → ""；否则保证以 / 开头、不以 / 结尾（如 "/api"） */
    fun normalizeApiPath(input: String): String {
        var s = input.trim()
        if (s.isEmpty()) return ""
        if (!s.startsWith("/")) s = "/$s"
        while (s.length > 1 && s.endsWith("/")) s = s.dropLast(1)
        return s
    }

    /** 疑似裸 IPv6（含 "::" 且没有方括号）时给出写法提示（审计 P3：报错时不告诉用户方括号写法） */
    fun ipv6Hint(input: String): String? =
        if (input.contains("::") && !input.contains("[")) "IPv6 地址需要用方括号包裹，如 [::1]:11434"
        else null
}


/** 云端厂商预设：官方 OpenAI 兼容地址 + 示例模型名 + 获取 Key 的地方 */
enum class CloudVendor(
    val id: String,
    val label: String,
    val baseUrl: String,
    val sampleModel: String,
    val keyHint: String
) {
    OPENAI("openai", "OpenAI", "https://api.openai.com", "gpt-4o-mini", "platform.openai.com/api-keys"),
    DEEPSEEK("deepseek", "DeepSeek", "https://api.deepseek.com", "deepseek-chat", "platform.deepseek.com"),
    GLM("glm", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash", "open.bigmodel.cn"),
    KIMI("kimi", "Kimi 月之暗面", "https://api.moonshot.cn", "moonshot-v1-8k", "platform.moonshot.cn"),
    SILICONFLOW("siliconflow", "硅基流动", "https://api.siliconflow.cn", "deepseek-ai/DeepSeek-V3", "cloud.siliconflow.cn"),
    OPENROUTER("openrouter", "OpenRouter", "https://openrouter.ai/api", "openai/gpt-4o-mini", "openrouter.ai/keys"),
    QWEN("qwen", "通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", "bailian.console.aliyun.com"),
    DOUBAO("doubao", "豆包火山方舟", "https://ark.cn-beijing.volces.com/api/v3", "doubao-pro-32k", "console.volcengine.com/ark"),
    MINIMAX("minimax", "MiniMax", "https://api.minimax.chat/v1", "abab6.5s-chat", "platform.minimaxi.com"),
    HUNYUAN("hunyuan", "腾讯混元", "https://api.hunyuan.cloud.tencent.com/v1", "hunyuan-lite", "console.cloud.tencent.com/hunyuan"),
    GROQ("groq", "Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", "console.groq.com/keys"),
    XAI("xai", "xAI Grok", "https://api.x.ai/v1", "grok-2-latest", "console.x.ai");

    companion object {
        fun fromId(id: String?): CloudVendor = entries.firstOrNull { it.id == id } ?: DEEPSEEK
    }
}
