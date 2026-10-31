package dev.liuyouwu.familiar.core.llm.probe

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import dev.liuyouwu.familiar.core.llm.client.SseRawEvent
import dev.liuyouwu.familiar.core.llm.client.SseSource
import dev.liuyouwu.familiar.core.llm.client.chatCompletionsHttpRequest
import dev.liuyouwu.familiar.core.llm.client.llmEndpoint
import dev.liuyouwu.familiar.core.llm.client.ToolCallAccumulator
import dev.liuyouwu.familiar.core.llm.wire.ChatRequest
import dev.liuyouwu.familiar.core.llm.wire.MessageDto
import dev.liuyouwu.familiar.core.llm.wire.ToolCallDeltaDto
import dev.liuyouwu.familiar.core.llm.wire.WireFunction
import dev.liuyouwu.familiar.core.llm.wire.WireFunctionCall
import dev.liuyouwu.familiar.core.llm.wire.WireTool
import dev.liuyouwu.familiar.core.llm.wire.WireToolCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 生产探测传输（P5 T2，spec §4.4 第 3 层）：两步探测请求**复用 LlmClient 的
 * SseSource 与 OkHttp 请求构建**——[chatCompletionsHttpRequest] 单点拼
 * /v1/chat/completions（ep 归一 + Bearer），流经注入的 [SseSource]（生产
 * okHttpSseSource(okHttp)，测试假流）。构造期绑定连接目标（model/baseUrl/apiKey）。
 *
 * 请求规格（[CapabilityProber] KDoc / spec §4.4）：
 * - WITH_TOOLS：tools=[get_current_time]（JSON Schema object 形，同
 *   WireConverter.specToWire 的"schema 对象非转义字符串"契约）+ user 指令
 *   "现在几点？请直接调用工具查询，不要先解释或思考" + max_tokens=512（评审 C3）
 *   + stream_options 不发（宁缺勿滥：探测面向任意陌生后端，Mistral 类拒收）；
 * - TOOL_FOLLOWUP：把第一步 tool call 以 assistant.tool_calls 入历史（id 对位），
 *   工具结果以 role:"tool"+tool_call_id 回传（评审 C2），请求普通收尾回答。
 *
 * SSE 流 → [ProbeResult] 规范化：tool_calls 增量经 [ToolCallAccumulator] 累积
 * （id 跨增量分片不丢，同 LlmClient 口径）→ ToolCalled；只有 content → Ok；
 * 流抛异常 → okHttpSseSource 失败路径的两种报文形（"HTTP <code>: …" /
 * ApiErrorHint.fromHttpStatus 的"… (code)…"）解析出状态码 → HttpError，
 * 解析不出（IOException 等网络层故障）→ Transient。CancellationException 原样
 * 传播（取消不降级）。
 *
 * [ollamaCapabilities]（第 2 层服务端元数据）：POST {base}/api/show 取
 * capabilities 集合，经 [PlainHttp] 执行缝（生产 [okHttpPlainHttp]，测试假件）。
 * 非 200 / 坏体 / 网络故障一律 null——元数据缺失不阻断两步实测。
 */
class HttpProbeTransport(
    private val model: String,
    private val sseSource: SseSource,
    private val baseUrl: () -> String,
    private val apiKey: () -> String = { "" },
    /** /api/show 等非流式请求的执行缝（探测与聊天流不同路：无 SSE，短超时直连） */
    private val plainHttp: PlainHttp,
) : ProbeTransport {

    /** 非流式 HTTP 执行缝：探测专用（调用方协程切 IO，见 [okHttpPlainHttp]） */
    fun interface PlainHttp {
        suspend fun call(request: Request): PlainResponse
    }

    data class PlainResponse(val code: Int, val body: String)

    private val gson = Gson()

    /** 第一步 tool call id（第二步 tool_call_id 对位回传；WITH_TOOLS 起点重置） */
    @Volatile private var pendingToolCallId: String? = null

    override suspend fun roundTrip(step: ProbeStep): ProbeResult = when (step) {
        ProbeStep.WITH_TOOLS -> {
            pendingToolCallId = null
            classify(chatCompletionsHttpRequest(baseUrl(), apiKey(), gson.toJson(firstStepRequest())))
        }

        ProbeStep.TOOL_FOLLOWUP -> {
            val callId = pendingToolCallId
                ?: error("TOOL_FOLLOWUP 前必须有第一步 ToolCalled（探测序契约）")
            classify(chatCompletionsHttpRequest(baseUrl(), apiKey(), gson.toJson(followUpRequest(callId))))
        }
    }

    override suspend fun ollamaCapabilities(showBaseUrl: String): Set<String>? {
        return try {
            val body = JsonObject().apply { addProperty("model", model) }.toString()
            val request = Request.Builder()
                .url(llmEndpoint(showBaseUrl, "/api/show"))
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            val resp = plainHttp.call(request)
            if (resp.code != 200) return null
            val obj = runCatching { gson.fromJson(resp.body, JsonObject::class.java) }.getOrNull()
                ?: return null
            val caps = obj.get("capabilities") as? com.google.gson.JsonArray ?: return null
            return caps.mapNotNull { el ->
                (el as? JsonPrimitive)?.takeIf { p -> p.isString }?.asString
            }.toSet()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null   // 元数据缺失不阻断两步实测（KDoc：非 Ollama/查询失败 → null）
        }
    }

    // ===== 请求装配（spec §4.4 字面规格）=====

    private fun firstStepRequest() = ChatRequest(
        model = model,
        messages = listOf(MessageDto.TextMessage(role = "user", content = PROBE_PROMPT)),
        stream = true,
        maxTokens = PROBE_MAX_TOKENS,
        tools = listOf(PROBE_TOOL),
        // 探测面向任意陌生后端：stream_options 不发（宁缺勿滥，同 wire 家族契约）
        streamOptions = null,
    )

    private fun followUpRequest(callId: String): ChatRequest {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINESE).format(java.util.Date())
        return ChatRequest(
            model = model,
            messages = listOf(
                // assistant 发起形：content 与 tool_calls 同带（OpenAI 允许；无正文 null 省略）
                MessageDto.ToolCallsMessage(
                    role = "assistant",
                    content = null,
                    toolCalls = listOf(
                        WireToolCall(
                            id = callId,
                            function = WireFunctionCall(name = PROBE_TOOL_NAME, arguments = "{}"),
                        )
                    ),
                ),
                // 工具结果回传（tool_calls 留 null——wire 上不发空数组键）
                MessageDto.ToolCallsMessage(role = "tool", content = "当前时间：$now", toolCallId = callId),
            ),
            stream = true,
            maxTokens = PROBE_MAX_TOKENS,
        )
    }

    // ===== SSE 流 → ProbeResult 规范化 =====

    private suspend fun classify(request: Request): ProbeResult {
        val acc = ToolCallAccumulator()
        val text = StringBuilder()
        try {
            sseSource.open(request).collect { ev -> ev.ingest(acc, text) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return errorToResult(e)
        }
        val first = acc.finish().firstOrNull()
        return if (first != null) {
            val id = first.id.ifBlank { SYNTHETIC_CALL_ID }
            pendingToolCallId = id
            ProbeResult.ToolCalled(id)
        } else {
            ProbeResult.Ok(text.toString())
        }
    }

    /** tool_calls 增量累积（id 跨增量分片不丢）+ content 聚合（同 LlmClient 双通道口径） */
    private fun SseRawEvent.ingest(acc: ToolCallAccumulator, text: StringBuilder) {
        toolCallsJson?.let { json ->
            val dtos = runCatching { gson.fromJson(json, Array<ToolCallDeltaDto>::class.java) }
                .getOrNull() ?: return
            dtos.filterNotNull().forEach { dto ->
                acc.onDelta(dto.index, dto.id, dto.function?.name, dto.function?.arguments ?: "")
            }
        }
        content?.let { text.append(it) }
    }

    /**
     * okHttpSseSource 失败路径 → HttpError/Transient：两种报文形各带状态码
     * （"HTTP <code>: <apiError>" 直提；ApiErrorHint.fromHttpStatus 的
     * "… (code)…" 括号形兜底），解析不出即网络层故障 → Transient。
     */
    private fun errorToResult(e: Exception): ProbeResult {
        val msg = e.message.orEmpty()
        val code = HTTP_PREFIX_CODE.find(msg)?.groupValues?.get(1)?.toInt()
            ?: PAREN_CODE.find(msg)?.groupValues?.get(1)?.toInt()
        return if (code != null) ProbeResult.HttpError(code, msg) else ProbeResult.Transient(e)
    }

    companion object {
        /** 探测收尾回答的输出上限（评审 C3：512） */
        const val PROBE_MAX_TOKENS = 512

        /** 探测工具名（spec §4.4 字面） */
        const val PROBE_TOOL_NAME = "get_current_time"

        /** 探测指令（spec §4.4 字面） */
        const val PROBE_PROMPT = "现在几点？请直接调用工具查询，不要先解释或思考"

        private const val SYNTHETIC_CALL_ID = "probe_call_0"

        private val HTTP_PREFIX_CODE = Regex("""HTTP (\d{3})""")
        private val PAREN_CODE = Regex("""\((\d{3})\)""")

        /** 探测工具声明：parameters 嵌 JSON Schema 对象（同 WireConverter.specToWire 契约） */
        private val PROBE_TOOL = WireTool(
            type = "function",
            function = WireFunction(
                name = PROBE_TOOL_NAME,
                description = "查询当前真实时间。回答任何时间问题前必须先调用此工具。",
                parameters = JsonObject().apply {
                    add("type", JsonPrimitive("object"))
                    add("properties", JsonObject())
                },
            ),
        )

        /** 生产执行缝：OkHttp 同步执行切 IO（探测与聊天流不同路，短超时小客户端由装配方给） */
        fun okHttpPlainHttp(okHttp: OkHttpClient): PlainHttp = PlainHttp { request ->
            withContext(Dispatchers.IO) {
                okHttp.newCall(request).execute().use { resp ->
                    PlainResponse(resp.code, resp.body?.string().orEmpty())
                }
            }
        }
    }
}
