package dev.liuyouwu.familiar.core.llm.wire

import com.google.gson.annotations.SerializedName

/**
 * 聊天请求体。所有可空字段序列化时省略——本地后端（llama.cpp / Ollama）
 * 和云端严格后端（o 系列等）对多余/不支持的参数会直接 400，宁缺勿滥。
 *
 * @param temperature null = 不发送，用后端默认值
 * @param frequencyPenalty null = 不发送（snake_case 键，见字段注；越界值由装配层钳制）
 * @param presencePenalty null = 不发送（同上）
 * @param maxTokens 仅本地后端发送（云端发 max_tokens 可能被 o 系列拒绝）
 * @param chatTemplateKwargs 仅 llama.cpp 发送，如 {"enable_thinking": false}
 */
data class ChatRequest(
    val model: String,
    val messages: List<MessageDto>,
    val stream: Boolean = true,
    val temperature: Double? = null,
    // 采样惩罚（B2-L1）：OpenAI 兼容 snake_case 键；null = 不发送（本文件"宁缺勿滥"契约）。
    // llama.cpp / Ollama / LM Studio / 云端均接受；o 系列拒收 presence_penalty 时
    // 由装配层回退（spec §3.7），DTO 层不做特判
    @SerializedName("frequency_penalty")
    val frequencyPenalty: Double? = null,
    @SerializedName("presence_penalty")
    val presencePenalty: Double? = null,
    @SerializedName("max_tokens")
    val maxTokens: Int? = null,
    @SerializedName("chat_template_kwargs")
    val chatTemplateKwargs: Map<String, Boolean>? = null,
    // 请求最终 usage（含缓存命中统计）：llama.cpp 顺带返回 timings.cache_n，
    // DeepSeek 返回 prompt_cache_hit_tokens，OpenAI 系返回 prompt_tokens_details
    @SerializedName("stream_options")
    val streamOptions: StreamOptions? = StreamOptions(includeUsage = true),
    // 原生工具声明（agent 内核 NATIVE 模式）：null = 序列化省略该键（Gson 默认不发 null），
    // 不用工具的请求保持原有形状，后端无感知
    val tools: List<WireTool>? = null,
    // "auto"/"none" 或 {"type":"function",...} 的字符串形；null = 不发送，用后端默认
    val tool_choice: String? = null
)

data class StreamOptions(
    @SerializedName("include_usage")
    val includeUsage: Boolean = false
)

sealed class MessageDto {
    /** system/user/assistant——基类暴露便于按角色检索（关思考软开关等） */
    abstract val role: String

    data class TextMessage(
        override val role: String,
        val content: String
    ) : MessageDto()

    data class VisionMessage(
        override val role: String,
        val content: List<ContentPart>
    ) : MessageDto()

    /**
     * 原生工具往返的 wire 表达，两种用途共用一形：
     * assistant 发起（role="assistant"，toolCalls=调用列表）与 tool 应答
     * （role="tool"，content=结果、toolCallId=对应调用 id、toolCalls=null——
     * Gson 默认省略 null，tool 应答的 wire 上不出现 "tool_calls":[] 空数组键，
     * 沿用本文件"宁缺勿滥"契约，严格网关不会 400）。
     * 键名必须用 @SerializedName 显式绑定——项目 Gson 是默认字段命名。
     */
    data class ToolCallsMessage(
        override val role: String,
        val content: String?,
        @SerializedName("tool_calls") val toolCalls: List<WireToolCall>? = null,
        @SerializedName("tool_call_id") val toolCallId: String? = null
    ) : MessageDto()
}

/** 请求体 tools 数组的一项：type 固定 "function"（OpenAI 兼容层唯一通用形） */
data class WireTool(
    val type: String = "function",
    val function: WireFunction
)

/** 工具声明：parameters 直接嵌入 JSON Schema 对象（不是转义字符串） */
data class WireFunction(
    val name: String,
    val description: String,
    val parameters: Any
)

/** 历史消息里的单次工具调用（assistant 发起形） */
data class WireToolCall(
    val id: String,
    val type: String = "function",
    val function: WireFunctionCall
)

/** arguments 是"字符串化的 JSON"（OpenAI 流式协议如此，宁保持字符串不被二次转义） */
data class WireFunctionCall(
    val name: String,
    @SerializedName("arguments") val arguments: String
)

data class ContentPart(
    val type: String,
    val text: String? = null,
    @SerializedName("image_url")
    val imageUrl: ImageUrl? = null
)

data class ImageUrl(
    val url: String
)
