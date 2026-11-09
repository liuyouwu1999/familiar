package dev.liuyouwu.familiar.core.llm.client

import com.google.gson.Gson
import com.google.gson.JsonObject
import dev.liuyouwu.familiar.core.llm.marker.MarkerToolAdapter
import dev.liuyouwu.familiar.core.llm.model.AgentMessage
import dev.liuyouwu.familiar.core.llm.model.ContentBlock
import dev.liuyouwu.familiar.core.llm.model.StreamEvent
import dev.liuyouwu.familiar.core.llm.model.Usage
import dev.liuyouwu.familiar.core.llm.think.ThinkSplitter
import dev.liuyouwu.familiar.core.llm.wire.ChatRequest
import dev.liuyouwu.familiar.core.llm.wire.ChatResponse
import dev.liuyouwu.familiar.core.llm.wire.ContentPart
import dev.liuyouwu.familiar.core.llm.wire.MessageDto
import dev.liuyouwu.familiar.core.llm.wire.StreamOptions
import dev.liuyouwu.familiar.core.llm.wire.ToolCallDeltaDto
import dev.liuyouwu.familiar.core.llm.wire.ToolMode
import dev.liuyouwu.familiar.core.llm.wire.ToolSpec
import dev.liuyouwu.familiar.core.llm.wire.WireConverter
import dev.liuyouwu.familiar.util.ApiErrorHint
import dev.liuyouwu.familiar.util.NetGuards
import dev.liuyouwu.familiar.util.PlaintextKeyRefusal
import dev.liuyouwu.familiar.util.ServerUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.UUID

/**
 * Llm 客户端（spec §4.5）：一次 stream() 调用 = 一个 run（对后端一次 HTTP 流式请求），
 * 把 SSE 原始增量规范化为 StreamEvent（块形态是唯一出口——思考只存在于 Thinking 块，
 * spec §4.1 不变式在客户端内达成）。收尾骨架恒定：RunStart → 块事件 → TurnEnd →
 * RunEnd(usage)——TurnStart/turn 序号由 AgentLoop 铸造（它才知道 turn 上下文），
 * 上游传输错误转为 StreamEvent.Error 后仍照常收尾，绝不吊死下游。
 *
 * 双栈路由（spec §4.3）：NATIVE 注请求体 tools 字段、tool_calls 分片经
 * ToolCallAccumulator 累积（JSON 校验失败→丢弃该调用并回填 toolResult 块）；
 * MARKER 不注 tools、由 marker 注指令段，content 流逐行 detectMarker——
 * 命中即发出 ToolCall 块并**取消上游**（标记前文本保留为 Text 块），
 * 预算尽/无工具时标记行剥除（stripMarkerLines）不解析不报错。
 *
 * 思考链优先级（spec §4.2）：协议级 reasoning 字段（reasoning_content/reasoning 双名，
 * 合并 `?:` 发生在本类内使双名优先级可测，评审二 P1-3）直接进 Thinking；
 * 全程无 reasoning 字段时 content 才过 ThinkSplitter 提取——提取器只此一处。
 *
 * `TurnEnd.truncated` 的语义约定：本地上限截断、finish_reason=length、
 * 传输错误中途截断（含 EOF 无 finish 标记）三种成因都置 true，消费方不可区分成因、
 * 也无需区分——统一按"内容不完整"处理（UI 截断标记/续讲入口）。
 */
interface LlmClient {
    fun stream(req: LlmRequest): Flow<StreamEvent>
}

data class LlmRequest(
    val model: String, val system: String, val history: List<AgentMessage>,
    val tools: List<ToolSpec>, val mode: ToolMode, val temperature: Double?,
    val maxTokens: Int?, val searchAllowed: Boolean, val budgetLeft: () -> Boolean,
    val frequencyPenalty: Double? = null,
    val presencePenalty: Double? = null,
)

/**
 * SSE 单增量的事件级表达（可测缝）。生产 SseSource 从 `data:` 行直解 ChatResponse DTO
 * 映射到此处；测试注入 scripted 流。双 reasoning 字段分开携带，?: 合并留给
 * DefaultLlmClient（评审二 P1-3）；usage/eofTruncated 为附加可选字段（缺省不影响
 * brief 的 5 参构造），分别使 RunEnd(usage) 能透传收尾块真实用量、传输层能把
 * "EOF 无 finish_reason" 的截断信号（okHttpSseSource 收尾判据语义）带给客户端。
 */
data class SseRawEvent(
    val content: String?, val reasoningContent: String?, val reasoning: String?,
    val toolCallsJson: String?, val finishReason: String?,
    val usage: Usage? = null,
    val eofTruncated: Boolean = false,
)

/** 可测缝：生产实现包 OkHttp+直解 DTO（[okHttpSseSource]）；测试注入 scripted flow。 */
fun interface SseSource {
    fun open(request: Request): Flow<SseRawEvent>
}

data class Deps(
    val okHttp: OkHttpClient,              // 生产 sseSource 用
    val sseSource: SseSource,              // 注入点（生产：OkHttp + 直解 ChatResponse DTO 的薄层）
    val baseUrl: () -> String, val apiKey: () -> String,
    val provider: () -> String,
    // 可选注入缝（评审修复轮 #3）：null（缺省）= 恒发 stream_options(include_usage=true)；
    // 注入后返回值原样生效（返回 null = 不发送该键）——Mistral 类拒收 stream_options
    // 的后端由装配层决定，P3 接线不必重开此文件
    val streamOptionsOverride: (() -> StreamOptions?)? = null,
    // 诊断落盘目录供应商（P1 终审挂点，P4 T8 + 审计 S4 开关化）：last_request.json 的
    // 落盘目录——**每次请求组装时求值**（生产 agentLlm 是 by lazy 单例，开关必须
    // 每次写盘时读取，不得构造期定型）；返回 null = 跳过落盘（缺省恒 null，测试/
    // core 层纯 JVM 可测）。生产装配注入「读设置开关 → 开则 app 外部文件目录」
    val dumpDir: () -> java.io.File? = { null },
    // B2-L1 penalty 降级记录缝（spec §3.7）：每次剥参重发时注入方收到一条原因文案——
    // null（缺省）= no-op，JVM 单测零 android.util.Log 依赖（FamiliarMemoryStore.kt:9
    // 仓内先例"log 可注入"）；生产装配点注入 { android.util.Log.w("LlmClient", it) }
    val onPenaltyFallback: ((String) -> Unit)? = null,
    // 诊断落盘失败记录缝（审计 S4，照 onPenaltyFallback 先例）：写盘异常时注入方收到
    // 一条原因文案——null（缺省）= no-op；生产装配点注入 Log.w（core/llm 忌直引
    // android.util.Log，纯 JVM 可测原则）
    val onDumpFailure: ((String) -> Unit)? = null,
)

const val MAX_RESPONSE_CHARS = 100_000   // 逐 turn 文本上限（对照现 VM 常量）
const val MAX_THINKING_CHARS = 100_000   // 思考流独立上限（现值即同一常量，ChatViewModel 同值）

/**
 * 默认实现：SSE→StreamEvent 规范化 + 双栈路由。请求侧组装（system 注入/历史折叠/
 * 时间附注/last_request.json 落盘）对照内核清理 A 已删的 data 层旧客户端行为照抄（spec §4.3）。
 */
class DefaultLlmClient(
    private val deps: Deps,
    private val wire: WireConverter,
    private val marker: MarkerToolAdapter,
) : LlmClient {

    private val gson = Gson()

    /**
     * 标记命中的上游中止信号：CancellationException 子类——上游（callbackFlow 包
     * enqueue 的生产实现）据此走真实取消路径断开连接，scripted 测试的
     * onCompletion 也能观察到取消而非正常完成。
     */
    private class MarkerStop : CancellationException("marker hit: cancel upstream")

    override fun stream(req: LlmRequest): Flow<StreamEvent> = flow {
        val runId = "run-" + UUID.randomUUID()
        emit(StreamEvent.RunStart(runId))
        var attempt = req
        while (true) {
            val turn = Turn(runId, attempt, this)
            try {
                deps.sseSource.open(buildHttpRequest(buildChatRequest(attempt)))
                    .collect { turn.onEvent(it) }
                turn.finish()
                return@flow
            } catch (stop: MarkerStop) {
                // 标记命中：上游已中止，预标记文本与 ToolCall 块均已发出，照常收尾
                turn.finish()
                return@flow
            } catch (e: CancellationException) {
                throw e   // 外部取消（轮链取消/收集方停止）：原样传播，绝不吞掉
            } catch (e: Throwable) {
                // B2-L1 §3.7 逐参降级：presence → frequency → 全无，每次剥一个并记录；
                // 循环剥参，深度由参数个数（2）天然封顶（复审 N1：嵌套版只支持一次剥参）。
                // 记录走 Deps 注入缝（三轮 P0：android.util.Log 在 JVM 单测未 mock 会崩——
                // FamiliarMemoryStore.kt:9 仓内先例"log 可注入"），生产装配注入 Log.w
                val stripped = when {
                    isPenaltyRejection(e) && attempt.presencePenalty != null -> attempt.copy(presencePenalty = null)
                    isPenaltyRejection(e) && attempt.frequencyPenalty != null -> attempt.copy(frequencyPenalty = null)
                    else -> null
                }
                if (stripped == null) {
                    emit(StreamEvent.Error(e))
                    turn.error = true
                    turn.finish()
                    return@flow
                }
                // 终审 M-2：已产出内容不重发——mid-stream 错误若剥参重试，重发会把
                // 已发给下游的 Text/Thinking 增量再发一遍（重复内容）；按原错误路径收尾
                if (turn.hasProducedContent()) {
                    emit(StreamEvent.Error(e))
                    turn.error = true
                    turn.finish()
                    return@flow
                }
                deps.onPenaltyFallback?.invoke("penalty rejected by backend, retrying with fewer penalties: ${e.message}")
                emit(StreamEvent.Error(e))
                attempt = stripped
            }
        }
    }

    /**
     * penalty 拒收判定：错误文案提及 penalty 字段名（B2 台账收紧：仅含 "400" 的
     * 任意 4xx 不再触发剥参空转重发——penalty 拒收的报错文案必含字段名，
     * ignoreCase 兼容大小写变体）。
     */
    private fun isPenaltyRejection(e: Throwable): Boolean {
        val msg = (e.message ?: return false) + " " + (e.cause?.message ?: "")
        return msg.contains("penalty", ignoreCase = true)
    }

    // ===== 请求侧组装（对照内核清理 A 已删的 data 层旧客户端照抄）=====

    internal fun buildChatRequest(req: LlmRequest): ChatRequest {
        val system = wire.buildSystem(req.system, req.mode, req.tools, marker, req.searchAllowed)
        var messages = wire.buildHistory(req.history, req.mode, marker)
        if (system.isNotBlank()) {
            messages = listOf(MessageDto.TextMessage("system", system)) + messages
        }
        messages = attachTimeContext(messages)
        return ChatRequest(
            model = req.model,
            messages = messages,
            stream = true,
            temperature = req.temperature,
            frequencyPenalty = req.frequencyPenalty,
            presencePenalty = req.presencePenalty,
            maxTokens = req.maxTokens,
            // 不再发送 enable_thinking:false / /no_think 禁思考（2026-10-27 复盘：
            // 强制禁思考会让按"思考块常开"训练的微调模型输出退化）
            chatTemplateKwargs = null,
            // 注入缝生效时返回值原样生效（null = 不发送）；未注入维持默认恒发
            streamOptions = if (deps.streamOptionsOverride != null) {
                deps.streamOptionsOverride.invoke()
            } else {
                StreamOptions(includeUsage = true)
            },
            // 双栈路由（spec §4.3）：NATIVE 注 tools 字段；MARKER 纯文本协议不注。
            // tools 同过 searchAllowed 单点过滤（WireConverter.effectiveTools，P1 台账㉖——
            // 禁搜时不能只隐指令还把工具声明发上去）；空列表发 null 而非 []——
            // 本 wire DTO 家族"宁缺勿滥"（严格网关对多余键 400）
            tools = if (req.mode == ToolMode.NATIVE) {
                val effective = wire.effectiveTools(req.tools, req.searchAllowed)
                if (effective.isNotEmpty()) wire.specToWire(effective) else null
            } else null,
        )
    }

    /** 防御性拼接（照抄已删 data 层旧客户端的 ep）：漏规范化也不拼出 /v1/v1 或双斜杠（语义在 [llmEndpoint] 单点） */
    internal fun ep(baseUrl: String, path: String): String = llmEndpoint(baseUrl, path)

    private fun buildHttpRequest(chatRequest: ChatRequest): Request {
        val json = gson.toJson(chatRequest)
        dumpRequestJson(json)
        // OkHttp 请求构建单点共享（P5 T2）：探测传输（HttpProbeTransport）复用同形
        return chatCompletionsHttpRequest(deps.baseUrl(), deps.apiKey(), json)
    }

    /** 诊断：请求体落盘（审计 S4：开关门控 + 256K 截断），定位"curl 正常、app 内退化"类问题。
     *  目录由 Deps.dumpDir 供应商注入——**每次调用时求值**（null = 跳过；测试缺省恒跳过，
     *  core 层不依赖 app 壳）；失败经 onDumpFailure 注入缝上报（缺省静默，诊断路径不扰主链）。 */
    private fun dumpRequestJson(json: String) {
        val dir = deps.dumpDir() ?: return
        try {
            java.io.File(dir, "last_request.json").writeText(capDumpForDisk(json))
        } catch (e: Exception) {
            deps.onDumpFailure?.invoke("diag dump failed: ${e.message}")
        }
    }

    /**
     * 标记解析的生效条件（spec §4.3 硬契约）：对应工具已注册且预算>0 才解析。
     * 工具生效集走 WireConverter.effectiveTools 单点（P1 台账㉖：searchAllowed
     * 的过滤规则不在此处重复——禁搜时搜索标记不解析，与 tools 注入/系统指令同口径；
     * D4：mode 随 req 入参——MARKER 生效集收敛到 web_search，与 buildSystem 指令段
     * 收敛同函数同口径）
     */
    private fun markerToolsEffective(req: LlmRequest): List<ToolSpec> =
        wire.effectiveTools(req.tools, req.searchAllowed, req.mode)

    // ===== 单 turn 规范化状态机 =====

    private inner class Turn(
        private val runId: String,
        private val req: LlmRequest,
        private val out: FlowCollector<StreamEvent>,
    ) {
        private var blockSeq = 0
        private var openId: String? = null
        private var openType: String? = null

        private val splitter = ThinkSplitter()
        private var sawReasoningField = false

        private var textChars = 0
        private var thinkingChars = 0
        private var truncated = false
        var error = false
        private var finalizing = false

        /** 终审 M-2：本 attempt 是否已产出内容增量（正文/思考任一非空即算，防降级重发重复内容） */
        fun hasProducedContent(): Boolean = textChars > 0 || thinkingChars > 0

        /**
         * 标记已命中：其后的一切正文（同事件残留行/splitter 缓冲/未凑齐换行的尾巴）
         * 都不得再发出——Task 6 停靠锁：foldHistory 的无标记泄漏不变式依赖本类
         * 只保留标记前文本。
         */
        private var markerStopped = false

        private val markerActive = req.mode == ToolMode.MARKER &&
            markerToolsEffective(req).isNotEmpty()
        private val markerPending = StringBuilder()
        private val calls = mutableListOf<ContentBlock.ToolCall>()
        private val nativeAcc = ToolCallAccumulator()
        private var finishReason: String? = null
        private var usage = Usage()

        suspend fun onEvent(ev: SseRawEvent) {
            ev.usage?.let { usage = it }
            // 传输层截断信号（EOF 无 finish_reason，okHttpSseSource 收尾判据语义）
            if (ev.eofTruncated) truncated = true
            // 双名合并在此发生（评审二 P1-3）：reasoning_content 优先于 reasoning
            val reasoning = ev.reasoningContent ?: ev.reasoning
            if (reasoning != null) {
                sawReasoningField = true
                appendThinking(reasoning)
            }
            if (req.mode == ToolMode.NATIVE) {
                ev.toolCallsJson?.let { json ->
                    for (dto in parseToolCallDtos(json)) {
                        nativeAcc.onDelta(dto.index, dto.id, dto.function?.name, dto.function?.arguments ?: "")
                    }
                }
            }
            ev.content?.let { c ->
                if (sawReasoningField) {
                    // 协议级思考已存在，content 即正文（提取器只此一处，spec §4.2）
                    routeContent(c)
                } else {
                    for (seg in splitter.feed(c)) {
                        if (seg.isReasoning) appendThinking(seg.text) else routeContent(seg.text)
                    }
                }
            }
            ev.finishReason?.let { finishReason = it }
        }

        suspend fun finish() {
            finalizing = true
            if (!sawReasoningField && !markerStopped) {
                for (seg in splitter.flush()) {
                    if (seg.isReasoning) appendThinking(seg.text) else routeContent(seg.text)
                }
            }
            // MARKER 收尾：未凑齐换行的尾巴按整行规则处理——完整标记行同样命中，
            // 残缺尾（流中断产物）经 stripMarkerLines 截除。标记已命中则整段丢弃。
            if (req.mode == ToolMode.MARKER && markerPending.isNotEmpty() && !markerStopped) {
                val tail = markerPending.toString()
                markerPending.setLength(0)
                val hit = if (markerActive) marker.detectToolMarker(tail) else null
                val spec = hit?.let { resolveMarkerTool(it.first) }
                val gated = spec != null &&
                    spec.name == WireConverter.SEARCH_TOOL_NAME && !req.budgetLeft()
                if (hit != null && spec != null && !gated) {
                    emitMarkerCall(spec, marker.buildMarkerArgs(spec.parametersJson, hit.second))
                } else {
                    appendText(marker.stripMarkerLines(tail))
                }
            }
            closeBlock()
            if (req.mode == ToolMode.NATIVE) emitNativeCalls()
            if (finishReason == "length") truncated = true
            // truncated 语义（类 KDoc）：本地上限 / finish_reason=length / 传输错误
            // （error）/ EOF 无 finish 标记（eofTruncated 已在 onEvent 置位）——消费方不区分成因
            out.emit(StreamEvent.TurnEnd(calls.toList(), truncated || error))
            out.emit(StreamEvent.RunEnd(usage))
        }

        /**
         * 正文路由：MARKER 检测生效时按整行缓冲——标记可能被 SSE 分片切开，
         * 只有凑齐换行的行才做 detectMarker 判定；命中→ToolCall 块+中止上游
         * （finalizing 阶段流已结束，只补发不再中止）；未生效（预算尽/无工具/
         * NATIVE）时标记行整行丢弃、残缺尾收尾剥除（spec §4.3 硬契约：
         * 不解析、不报错、不产生 ToolCall）。
         */
        private suspend fun routeContent(text: String) {
            if (text.isEmpty()) return
            if (!markerActive && req.mode != ToolMode.MARKER) {
                appendText(text)
                return
            }
            markerPending.append(text)
            while (true) {
                val nl = markerPending.indexOf('\n')
                if (nl < 0) break
                val line = markerPending.substring(0, nl)
                markerPending.delete(0, nl + 1)
                val hit = if (markerActive) marker.detectToolMarker(line) else null
                val spec = hit?.let { resolveMarkerTool(it.first) }
                // 搜索预算单独闸（对抗审查 B-2）：预算尽只拦搜索标记，其余工具照常发射
                val gated = spec != null &&
                    spec.name == WireConverter.SEARCH_TOOL_NAME && !req.budgetLeft()
                if (hit != null && spec != null && markerActive && !gated) {
                    // 生效集内已注册工具的标记行：整行转 ToolCall 并中止正文（残留行丢弃）
                    markerStopped = true
                    markerPending.setLength(0)
                    emitMarkerCall(spec, marker.buildMarkerArgs(spec.parametersJson, hit.second))
                    if (!finalizing) throw MarkerStop()
                    return
                }
                // 已知标记形状但未生效（对抗审查 B-1：禁搜后 web_search 不在生效集、
                // 预算尽的搜索标记、未注册名不在生效集）——spec §4.3 硬契约：剥除不
                // 显示，不解析不报错。未知名标记（"[注意: …]" 类普通行）按正文放行
                val knownShape = marker.detectMarker(line) != null ||
                    (marker.detectToolMarker(line)?.let { resolveMarkerTool(it.first) } != null)
                if (!knownShape) appendText(line + "\n")
            }
        }

        /** 标记里的工具名 → **生效集**（WireConverter.effectiveTools 过滤后）里的 spec：
         *  精确名（忽略大小写）+ 搜索双别名（[搜索:]/[search:] 是 QA 实证的老调用
         *  形态，恒映射 web_search）。对抗审查 B-1（禁搜旁路）：必须查过滤后生效集
         *  而非 raw req.tools——web_search 恒在 raw 清单里，查 raw 会让禁搜会话的
         *  [搜索:] 标记被真实执行。未知名返回 null，调用方按普通文本放行。 */
        private fun resolveMarkerTool(rawName: String): ToolSpec? {
            val n = rawName.trim()
            val effective = markerToolsEffective(req)
            effective.firstOrNull { it.name.equals(n, ignoreCase = true) }?.let { return it }
            if (n.equals("搜索", ignoreCase = true) || n.equals("search", ignoreCase = true)) {
                return effective.firstOrNull { it.name == WireConverter.SEARCH_TOOL_NAME }
            }
            return null
        }

        private suspend fun emitMarkerCall(spec: ToolSpec, args: String) {
            closeBlock()
            val id = nextBlockId()
            out.emit(StreamEvent.BlockStart(id, "toolCall"))
            out.emit(StreamEvent.ToolCallDelta(id, spec.name, args))
            out.emit(StreamEvent.BlockEnd(id))
            calls += ContentBlock.ToolCall(id, spec.name, args)
        }

        /** 原生调用收线（spec §4.5）：JSON 校验失败→丢弃该调用并回填 toolResult 块 */
        private suspend fun emitNativeCalls() {
            for (c in nativeAcc.finish()) {
                if (c.argsValid) {
                    // 保留后端给的 call id（tool 应答按 tool_call_id 对位回传）；后端没给才用块 id
                    val id = c.id.ifBlank { nextBlockId() }
                    out.emit(StreamEvent.BlockStart(id, "toolCall"))
                    out.emit(StreamEvent.ToolCallDelta(id, c.name, c.argsJson))
                    out.emit(StreamEvent.BlockEnd(id))
                    calls += ContentBlock.ToolCall(id, c.name, c.argsJson)
                } else {
                    // 评审二 P2-10：isError 语义的 toolResult 块（块类型词与 BlockCodec
                    // 同源小驼峰；AgentLoop 侧对 LLM 流内的 toolResult 块重建
                    // ToolResult(callId, name, output, isError=true)）
                    val id = nextBlockId()
                    out.emit(StreamEvent.BlockStart(id, "toolResult"))
                    out.emit(
                        StreamEvent.BlockDelta(
                            id, "模型输出的工具调用参数不是合法 JSON，已丢弃该调用：" + c.argsJson.take(80)
                        )
                    )
                    out.emit(StreamEvent.BlockEnd(id))
                }
            }
        }

        private fun parseToolCallDtos(json: String): List<ToolCallDeltaDto> = runCatching {
            gson.fromJson(json, Array<ToolCallDeltaDto>::class.java).orEmpty().filterNotNull()
        }.getOrDefault(emptyList())

        private fun nextBlockId(): String = "$runId:b${blockSeq++}"

        private suspend fun openBlock(type: String): String {
            closeBlock()
            val id = nextBlockId()
            openId = id
            openType = type
            out.emit(StreamEvent.BlockStart(id, type))
            return id
        }

        private suspend fun closeBlock() {
            val id = openId ?: return
            openId = null
            openType = null
            out.emit(StreamEvent.BlockEnd(id))
        }

        /** 超长护栏（spec §4.5）：逐 turn 文本上限，思考流独立上限；超限截断收线 */
        private suspend fun appendText(t: String) {
            if (t.isEmpty()) return
            val room = MAX_RESPONSE_CHARS - textChars
            if (room <= 0) {
                truncated = true
                return
            }
            val part = if (t.length <= room) t else t.substring(0, room)
            if (part.length < t.length) truncated = true
            textChars += part.length
            val id = if (openType == "text") openId!! else openBlock("text")
            out.emit(StreamEvent.BlockDelta(id, part))
        }

        private suspend fun appendThinking(t: String) {
            if (t.isEmpty()) return
            val room = MAX_THINKING_CHARS - thinkingChars
            if (room <= 0) {
                truncated = true
                return
            }
            val part = if (t.length <= room) t else t.substring(0, room)
            if (part.length < t.length) truncated = true
            thinkingChars += part.length
            val id = if (openType == "thinking") openId!! else openBlock("thinking")
            out.emit(StreamEvent.BlockDelta(id, part))
        }
    }
}

/** 版本尾段特判（如智谱 /api/paas/v4）——[llmEndpoint] 与 DefaultLlmClient 同源 */
private val llmVersionTail = Regex("""/v\d+$""")

/**
 * LlmClient 与 HttpProbeTransport 共享的端点拼接（原 DefaultLlmClient.ep 语义
 * 逐行上提为单点）：ServerUrl 规范化 + 版本尾段特判，防 /v1/v1 双拼。
 */
internal fun llmEndpoint(baseUrl: String, path: String): String {
    val base = ServerUrl.normalize(baseUrl) ?: baseUrl.trim().trimEnd('/')
    val p = if (llmVersionTail.containsMatchIn(base)) path.removePrefix("/v1") else path
    return "$base$p"
}

/**
 * 时间感知（dsh-humanized-deepseek-maid 原版思路的请求时变体；maid 把时间戳写进
 * 记忆行，本应用记忆行已带 [MM-dd HH:mm]，这里补"当前时间"）：把真实时间附到
 * 请求尾部最新的 user 消息上，模型由此知道现在几点/星期几。放尾部而非系统提示词
 * ——时间每分钟都变，写进 system 每分钟打破一次 KV 前缀缓存；尾部本来就是新增
 * 内容，零缓存损失。只改请求不改库。
 *
 * 顶层 internal（循 [llmEndpoint] 先例）：DefaultLlmClient 请求组装与
 * ConnectionProbe（data 层体检探针）跨包复用同一实现。
 */
internal fun attachTimeContext(messages: List<MessageDto>): List<MessageDto> {
    val idx = messages.indexOfLast { it.role == "user" }
    if (idx < 0) return messages
    val stamp = java.text.SimpleDateFormat("yyyy-MM-dd E HH:mm", java.util.Locale.CHINESE)
        .format(java.util.Date())
    val note = "（系统附注：当前真实时间 $stamp）"
    return messages.mapIndexed { i, m ->
        if (i != idx) m
        else when (m) {
            is MessageDto.TextMessage ->
                MessageDto.TextMessage(m.role, m.content + "\n\n" + note)
            is MessageDto.VisionMessage ->
                MessageDto.VisionMessage(m.role, m.content + ContentPart(type = "text", text = note))
            // 工具消息不追加时间附注：它以 tool_call_id 对位，改 content 会污染工具结果；
            // MessageDto 加变体后穷尽 when 需显式覆盖（行为=原样直通）
            is MessageDto.ToolCallsMessage -> m
        }
    }
}

private const val PLACEHOLDER_URL = "http://localhost/"

/**
 * 请求体 JSON → POST /v1/chat/completions 的 OkHttp 请求（单点共享，P5 T2）：
 * DefaultLlmClient 的对话请求与 HttpProbeTransport 的探测请求同形——JSON 体、
 * Bearer 认证、占位 URL 兜底（baseUrl 空/非法时让可测缝仍拿到 Request 对象，
 * 生产以连接失败落 Error 事件而非崩溃）。
 *
 * 明文 http 拒发闸（审计 S1+S7）在此单点生效：http + 非局域网目标（[NetGuards]）
 * + 非空 key → 附 Authorization 前一刻抛 [PlaintextKeyRefusal]，key 绝不明文
 * 出网；无 key 的 http 探测照常放行。
 */
internal fun chatCompletionsHttpRequest(baseUrl: String, apiKey: String, jsonBody: String): Request {
    val body = jsonBody.toRequestBody("application/json".toMediaType())
    val url = llmEndpoint(baseUrl, "/v1/chat/completions")
    val parsed = url.toHttpUrlOrNull()
    if (parsed != null && parsed.scheme == "http" &&
        !NetGuards.isPlaintextSafeHost(parsed.host) && apiKey.isNotBlank()
    ) {
        throw PlaintextKeyRefusal("http 且非局域网目标，已拒绝携带 API Key 发起请求")
    }
    val builder = if (parsed != null) {
        Request.Builder().url(url)
    } else {
        Request.Builder().url(PLACEHOLDER_URL)
    }
    if (apiKey.isNotBlank()) {
        builder.addHeader("Authorization", "Bearer $apiKey")
    }
    return builder.post(body).build()
}

/**
 * 诊断转储截断（审计 S4）：last_request.json 是明文完整对话内容，超长请求体
 * 截到 [MAX_DUMP_CHARS] 字符并附截断标记（防数 MB 附件请求把外部文件目录写爆、
 * 也防明文无限堆积）。顶层 internal（循 [llmEndpoint]/[attachTimeContext] 先例）：
 * DefaultLlmClient.dumpRequestJson 与 ConnectionProbe（data 层探针写盘路径）同口径共享。
 */
internal fun capDumpForDisk(json: String): String =
    if (json.length > MAX_DUMP_CHARS) json.take(MAX_DUMP_CHARS) + DUMP_TRUNCATED_MARK else json

/** 诊断转储字符上限：256K（[capDumpForDisk] 单点） */
internal const val MAX_DUMP_CHARS = 256 * 1024

/** 截断标记（[capDumpForDisk] 单点；测试断言钉死其后缀） */
internal const val DUMP_TRUNCATED_MARK = "…[truncated]"

/**
 * 生产 SseSource：OkHttp enqueue + `data:` 行直解 ChatResponse DTO（评审二 P2-9
 * 定死：直解而不经中间事件层——中间事件层内置 ThinkSplitter 会在规范化层二次切分，
 * 且单一事件结构装不下 tool_calls）。行读取复用同包 readLineBounded 的
 * 有界防护（故障网关滴字不换行不再静默 OOM）。
 */
fun okHttpSseSource(okHttp: OkHttpClient): SseSource = SseSource { request ->
    callbackFlow {
        val call = okHttp.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!call.isCanceled()) close(e) else close()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { consume(it) }
            }

            /**
             * 事件行处理核心（语义：[DONE] 完成信号/坏行跳过/错误体识别）。
             * 收尾保证：任何正常出口（[DONE]/EOF）都必须落到 close()——绝不能用
             * return 跳过它，否则下游 collect 永久挂起（评审修复轮 #1）。
             */
            fun handleLines(lines: Sequence<String>) {
                var sawFinishReason = false
                var sawDone = false
                try {
                    for (line in lines) {
                        // 只处理 data 行；event:/id:/注释(keep-alive)/空行跳过。
                        // SSE 规范里冒号后的空格是可选的，两种写法都要认
                        if (!line.startsWith("data:")) continue
                        val data = line.substring(5).let { if (it.startsWith(" ")) it.substring(1) else it }.trim()
                        // [DONE] = 流的正常完成信号（break+finish）。
                        // break 落入下方 close()——绝不能 return 跳过它
                        if (data == "[DONE]") {
                            sawDone = true
                            break
                        }
                        val apiError = ApiErrorHint.extractApiError(data)
                        if (apiError != null) {
                            close(RuntimeException(apiError))
                            return
                        }
                        // 单行坏 JSON 不致命，继续读流
                        val parsed = runCatching { sseGson.fromJson(data, ChatResponse::class.java) }.getOrNull()
                            ?: continue
                        if (parsed.choices?.firstOrNull()?.finishReason != null) sawFinishReason = true
                        toRawEvent(parsed)?.let { trySend(it) }
                    }
                    // 截断信号：流结束（EOF 或 [DONE]）且
                    // finish_reason 与 [DONE] 两者皆无——服务端干净关闭却没发任何完成
                    // 标记（网关 proxy_read_timeout 截断等），回复可能是半截的，必须
                    // 上报截断。只发 [DONE] 不发 finish_reason 的网关是干净完成
                    // （审计 P2-7：不得误标截断落库伪标记），不进本分支
                    if (!sawFinishReason && !sawDone) {
                        trySend(SseRawEvent(null, null, null, null, null, eofTruncated = true))
                    }
                    close()
                } catch (e: Throwable) {
                    if (!call.isCanceled()) close(e) else close()
                }
            }

            /** 非流式兜底（网关忽略 stream=true）：整段 JSON 或内嵌 data: 行 */
            fun consumeNonStreamBody(resp: Response) {
                val body = runCatching { resp.body?.string() }.getOrNull().orEmpty()
                if (body.trimStart().startsWith("data:")) {
                    handleLines(body.lineSequence())
                    return
                }
                ApiErrorHint.extractApiError(body)?.let {
                    close(RuntimeException(it))
                    return
                }
                val parsed = runCatching { sseGson.fromJson(body, ChatResponse::class.java) }.getOrNull()
                val ev = parsed?.let { toRawEvent(it) }
                if (ev == null) {
                    close(
                        RuntimeException(
                            if (body.isBlank()) "服务器返回了空响应"
                            else "服务器返回了无法解析的响应：" + body.take(150)
                        )
                    )
                    return
                }
                trySend(ev)
                close()
            }

            fun consume(resp: Response) {
                if (!resp.isSuccessful) {
                    val body = runCatching { resp.body?.string() }.getOrNull().orEmpty()
                    val apiError = ApiErrorHint.extractApiError(body)
                    val msg = apiError?.let { "HTTP ${resp.code}: $it" }
                        ?: ApiErrorHint.fromHttpStatus(resp.code, body)
                    close(RuntimeException(msg))
                    return
                }
                val contentType = resp.header("Content-Type") ?: ""
                if (!contentType.contains("event-stream", ignoreCase = true)) {
                    consumeNonStreamBody(resp)
                    return
                }
                val reader = resp.body?.byteStream()?.bufferedReader()
                if (reader == null) {
                    close(RuntimeException("Empty response body"))
                    return
                }
                try {
                    handleLines(generateSequence { readLineBounded(reader) })
                } finally {
                    runCatching { reader.close() }
                }
            }
        })
        awaitClose { if (!call.isCanceled()) call.cancel() }
    }.buffer(Channel.UNLIMITED)
}

/** ChatResponse DTO → SseRawEvent（delta 优先，message 兜底；无任何载荷的块丢弃） */
private fun toRawEvent(p: ChatResponse): SseRawEvent? {
    val choice = p.choices?.firstOrNull()
    val delta = choice?.delta
    val message = choice?.message
    val content = delta?.content ?: message?.content
    val reasoningContent = delta?.reasoningContent ?: message?.reasoningContent
    val reasoning = delta?.reasoning ?: message?.reasoning
    val toolCallsJson = delta?.toolCalls?.let { sseGson.toJson(it) }
    val usage = mapUsage(p)
    if (content == null && reasoningContent == null && reasoning == null &&
        toolCallsJson == null && choice?.finishReason == null && usage == null
    ) return null
    return SseRawEvent(
        content = content,
        reasoningContent = reasoningContent,
        reasoning = reasoning,
        toolCallsJson = toolCallsJson,
        finishReason = choice?.finishReason,
        usage = usage,
    )
}

/** 各家后端用量字段名逐一兼容（折成非空 Usage） */
private fun mapUsage(p: ChatResponse): Usage? {
    val usage = p.usage
    val timings = p.timings
    val cached = usage?.promptTokensDetails?.cachedTokens
        ?: usage?.promptCacheHitTokens
        ?: usage?.cachedTokens
        ?: usage?.cacheReadInputTokens
        ?: timings?.cacheN
    // llama.cpp 的 prompt_n 是"实际处理数"（不含命中），总数 = prompt_n + cache_n
    val promptTokens = usage?.promptTokens
        ?: timings?.let { (it.promptN ?: 0) + (it.cacheN ?: 0) }
    val completionTokens = usage?.completionTokens
    if ((promptTokens ?: 0) <= 0 && (completionTokens ?: 0) <= 0) return null
    val prompt = (promptTokens ?: 0).coerceAtLeast(0)
    return Usage(
        promptTokens = prompt,
        completionTokens = completionTokens ?: 0,
        cachedTokens = cached?.let { if (prompt > 0) it.coerceIn(0, prompt) else it } ?: 0
    )
}

private val sseGson = Gson()
