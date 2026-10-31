package dev.liuyouwu.familiar.core.llm.wire

import com.google.gson.annotations.SerializedName

/**
 * 响应 DTO。字段全部可空：Gson 反序列化不走构造器默认值，
 * 缺字段时只有可空类型是安全的。思考内容有 reasoning_content
 * （DeepSeek / llama.cpp）和 reasoning（vLLM / 部分网关）两种字段名。
 */
data class ChatResponse(
    val id: String? = null,
    val choices: List<Choice>? = null,
    val model: String? = null,
    val usage: UsageDto? = null,
    // llama.cpp 专有：cache_n = 本次从 KV 缓存复用的 token 数
    val timings: TimingsDto? = null
)

data class UsageDto(
    @SerializedName("prompt_tokens")
    val promptTokens: Int? = null,
    @SerializedName("completion_tokens")
    val completionTokens: Int? = null,
    @SerializedName("prompt_tokens_details")
    val promptTokensDetails: PromptTokensDetails? = null,
    // DeepSeek 字段：命中/未命中的 prompt token 数
    @SerializedName("prompt_cache_hit_tokens")
    val promptCacheHitTokens: Int? = null,
    // 部分网关把缓存命中平铺在 usage 顶层
    @SerializedName("cached_tokens")
    val cachedTokens: Int? = null,
    // Anthropic 风格变体（经 OpenAI 兼容层透传时可见）
    @SerializedName("cache_read_input_tokens")
    val cacheReadInputTokens: Int? = null
)

data class PromptTokensDetails(
    @SerializedName("cached_tokens")
    val cachedTokens: Int? = null
)

data class TimingsDto(
    @SerializedName("prompt_n")
    val promptN: Int? = null,
    @SerializedName("cache_n")
    val cacheN: Int? = null,
    @SerializedName("prompt_ms")
    val promptMs: Double? = null,
    @SerializedName("cache_ms")
    val cacheMs: Double? = null
)

data class Choice(
    val index: Int? = null,
    val delta: Delta? = null,
    val message: Message? = null,
    @SerializedName("finish_reason")
    val finishReason: String? = null
)

data class Delta(
    val role: String? = null,
    val content: String? = null,
    @SerializedName("reasoning_content")
    val reasoningContent: String? = null,
    val reasoning: String? = null,
    @SerializedName("tool_calls")
    val toolCalls: List<ToolCallDeltaDto>? = null
)

data class ToolCallDeltaDto(
    val index: Int? = null,
    val id: String? = null,
    val function: ToolCallFunctionDto? = null
)

data class ToolCallFunctionDto(
    val name: String? = null,
    @SerializedName("arguments")
    val arguments: String? = null
)

data class Message(
    val role: String? = null,
    val content: String? = null,
    @SerializedName("reasoning_content")
    val reasoningContent: String? = null,
    val reasoning: String? = null
)

data class ModelsResponse(
    val data: List<ModelInfo>? = null
)

data class ModelInfo(
    val id: String? = null,
    @SerializedName("object")
    val objectType: String? = null
)

/** 聊天室主持人规划式调度：本轮发言序列的单条规划（谁说、说什么要点） */
data class RoundTurnDto(
    val speaker: String? = null,
    val point: String? = null
)
