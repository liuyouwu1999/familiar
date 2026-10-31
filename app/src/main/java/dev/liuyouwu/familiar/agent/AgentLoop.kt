package dev.liuyouwu.familiar.agent

import dev.liuyouwu.familiar.core.llm.client.LlmClient
import dev.liuyouwu.familiar.core.llm.client.LlmRequest
import dev.liuyouwu.familiar.core.llm.model.AgentMessage
import dev.liuyouwu.familiar.core.llm.model.ContentBlock
import dev.liuyouwu.familiar.core.llm.model.Role
import dev.liuyouwu.familiar.core.llm.model.StreamEvent
import dev.liuyouwu.familiar.core.llm.model.Usage
import dev.liuyouwu.familiar.core.llm.wire.ToolMode
import dev.liuyouwu.familiar.core.llm.wire.ToolSpec
import dev.liuyouwu.familiar.tools.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Agent 循环（spec §5.1）：单实例驱动 turn 循环——每 turn 组装上下文（hooks.
 * transformContext 变换 system/tools）→ 收流铸 assistant 消息（逐 turn 铸
 * turnId，spec §8.1）→ 顺序执行工具 → 咨询 finishTurn。无工具 turn 自然收束；
 * maxTurns 越界且末轮有工具 → 追加一次无工具收尾 turn（护栏语 + tools 置空）。
 *
 * 硬契约（均被 AgentLoopTest 钉死）：
 * - turnId = "$runId:$turnIndex"（turnIndex 从 0 计）：同 turn 的 assistant 与
 *   TOOL_RESULT 行共享；TurnStart 事件由本循环铸造（LlmClient 只发 RunStart 起）。
 * - 预算回路（spec §4.3"装配即此"）：LlmRequest.budgetLeft = 链预算 >0；受门工具
 *   （registry.isGated）成功且非 isError 执行后链预算递减。
 * - 取消传播（P3 终审：取消贯通 SSE 订阅，spec §5.1"取消信号贯通"落地）：流收集
 *   挂链作用域（[collectStream]：withContext(chain.scope.coroutineContext)，与工具
 *   执行同口径）——chain.cancel() 即刻取消 collect，callbackFlow 的 awaitClose 触发
 *   OkHttp call.cancel()（生产 okHttpSseSource 已接，LlmClientNormalizeTest 钉死）；
 *   链取消时**取消点前的已完成块照常落账**（spec §5.1 原文语义：取消时已完成的
 *   部分块照常落库；未闭合块丢弃、点后幽灵块不补收），未配对工具调用（含
 *   进行中的）补落 isError ToolResult（取消文案=注入 RunMarkers.toolCancelled）
 *   （评审三 P2-1：消灭孤儿 ToolCall）；
 *   流收集前/工具前/工具后 isActive 检查点保留（取消点在检查点间的兜底）；
 *   调用方协程自身取消（非链取消）→ CancellationException 照常传播（不吞取消）。
 *
 * 请求装配（P3 接线，四件套）：model = run 级 modelOverride ?: session 级
 * model 供应商值；maxTokens 供应商贯通（供应商为 null 或求值 null = 不发送
 * 该字段；T6 由 SkillHost 装配 ContextBuilder.reserveForOutput(maxContext())
 * 同源公式——预留小于实际输出上限时本地小窗模型整请求超窗报错，禁止回落后
 * 端默认）；searchAllowed 供应商值直通（false 时 WireConverter/LlmClient 侧
 * 过滤已单点化，注册制语义由 T6 tools 装配保证）；采样三参数
 * （temperature/frequencyPenalty/presencePenalty）链上下文播种（openChain
 * 单点求值，B2-L1），未配置为 null（null = 键不发送）。maxContext 历史裁剪由 AgentSession 在入参侧经
 * ContextBuilder 预裁（Task 11 移交项 1，循环侧不裁剪；transformContext 逐
 * turn 的系统段/工具变换照常生效）。onProgress（工具卡片进度，P3 T3 接线）：
 * registry.execute 的 onProgress 文本经 [onToolProgress] 可选参贯通给调用方
 * （Session 侧挂到 streamingToolStatus）——非挂起回调（工具执行协程内同步调用），
 * 故不走 StreamEvent 事件通道（emit 为挂起，非挂起回调内无法直调）。
 * 收尾 turn（Task 11 移交项 2/3）：幻觉 ToolCall 一律剥除（无未配对调用落账）；
 * 收流中被取消 → cancelled=true + 补落，与主循环同模式。
 */
class AgentLoop(
    private val llm: LlmClient,
    private val registry: ToolRegistry,
    /**
     * 用户可见标记文案（内核清理 A，Task 4，RunMarkers 注入）：取消补落的
     * ToolResult 输出（toolCancelled）与收尾无正文说明（noClosingAnswerNote）
     * 读自此——标记文案零内嵌字面，唯一来源 app 侧 SkillHost 的 RunMarkerTexts
     * （装配层双路直注本件与 AgentSession）。参数必填（无中文缺省值）。
     */
    private val markers: RunMarkers,
) {

    suspend fun run(
        input: List<AgentMessage>,
        system: String,
        tools: List<ToolSpec>,
        hooks: AgentHooks,
        chain: RunChain,
        mode: ToolMode,
        maxTurns: Int = 8,
        modelOverride: String? = null,
        model: () -> String = { "" },
        maxTokens: (() -> Int?)? = null,
        searchAllowed: () -> Boolean = { true },
        steering: SteeringQueue = SteeringQueue(),
        onToolProgress: ((String) -> Unit)? = null,
        emit: suspend (StreamEvent) -> Unit,
    ): LoopOutcome {
        val runId = "run-" + UUID.randomUUID()
        val messages = input.toMutableList()
        val produced = mutableListOf<AgentMessage>()
        var usage = Usage()
        var truncated = false
        var cancelled = false
        var lastTurnExecutedTools = false

        var turnIndex = 0
        while (turnIndex < maxTurns) {
            messages += steering.drain()   // P-steer 回合顶注入：不进 produced（user 行已由 steer 落库）
            if (!chain.scope.isActive) { cancelled = true; break }   // 流收集前
            lastTurnExecutedTools = false

            val ctx = ContextBuilder(system, messages, tools)
            hooks.transformContext(ctx)

            val assembler = StreamAssembler()
            emit(StreamEvent.TurnStart(turnIndex))
            val streamComplete = collectStream(chain, request(ctx, mode, chain,
                modelOverride, model, maxTokens, searchAllowed), assembler, emit)
            consumeTurn(assembler, "$runId:$turnIndex", "$runId:a$turnIndex",
                messages, produced).let { (u, t) -> usage = sumUsage(usage, u); truncated = truncated || t }
            if (!streamComplete) {
                // 流收集中链取消（P3 终审：取消贯通）：取消点前已完成块已随
                // consumeTurn 落账，未配对工具调用补落——点后幽灵块不再补收
                cancelled = true
                backfillCancelled(runId, turnIndex,
                    assembler.blocks.filterIsInstance<ContentBlock.ToolCall>(), messages, produced)
                break
            }

            val toolCalls = assembler.blocks.filterIsInstance<ContentBlock.ToolCall>()
            if (!chain.scope.isActive) {                             // 工具前
                cancelled = true
                backfillCancelled(runId, turnIndex, toolCalls, messages, produced)
                break
            }
            if (toolCalls.isEmpty()) {
                if (steering.peek()) { turnIndex++; continue }   // 插话顶住收束：下一回合顶注入再答
                break                                            // 自然收束（不咨询 finishTurn）
            }
            lastTurnExecutedTools = true

            var i = 0
            while (i < toolCalls.size) {
                if (!chain.scope.isActive) {                         // 工具序列中（每调用前）
                    cancelled = true
                    backfillCancelled(runId, turnIndex, toolCalls.subList(i, toolCalls.size), messages, produced)
                    break
                }
                val call = toolCalls[i]
                val verdict = hooks.beforeToolCall(call)
                val result = if (verdict is Verdict.Block) {
                    ContentBlock.ToolResult(call.id, call.name, verdict.reason, isError = true)
                } else {
                    try {
                        withContext(chain.scope.coroutineContext) {
                            registry.execute(call) { progress -> onToolProgress?.invoke(progress) }
                        }
                    } catch (e: CancellationException) {
                        currentCoroutineContext().ensureActive()     // run 自身被取消：照常传播
                        cancelled = true
                        ContentBlock.ToolResult(call.id, call.name, markers.toolCancelled, isError = true)
                    }
                }
                if (!result.isError && registry.isGated(call.name)) {
                    chain.context.searchBudgetRemaining--           // 预算递减：仅受门且成功
                }
                val m = AgentMessage(
                    id = "$runId:r$turnIndex-$i", role = Role.TOOL_RESULT,
                    blocks = listOf(result), turnId = "$runId:$turnIndex",
                )
                messages += m
                produced += m
                i++
            }
            if (cancelled) break
            if (!chain.scope.isActive) { cancelled = true; break }   // 工具后
            if (hooks.finishTurn(turnIndex, messages) == Decision.END && !steering.peek()) break
            turnIndex++
        }

        // 收尾护栏（评审二 P1-1）：maxTurns 越界退出且末轮执行过工具 → 追加无工具收尾 turn
        if (!cancelled && lastTurnExecutedTools && turnIndex >= maxTurns) {
            if (!chain.scope.isActive) {
                cancelled = true
            } else {
                val closingIndex = maxTurns
                val ctx = ContextBuilder(system, messages, tools)
                hooks.transformContext(ctx)
                ctx.appendSystem(CLOSING_GUARD)
                ctx.setTools(emptyList())
                val assembler = StreamAssembler()
                emit(StreamEvent.TurnStart(closingIndex))
                val streamComplete = collectStream(chain, request(ctx, mode, chain,
                    modelOverride, model, maxTokens, searchAllowed), assembler, emit)
                if (!streamComplete || !chain.scope.isActive) {
                    // 收尾 turn 收流中被取消（Task 11 移交项 3 + P3 终审取消贯通：
                    // 链取消即刻中断流，与"流收完后才发现链已死"同模式收线）——
                    // 已完成块保留 + 末 turn 未配对调用补落 isError 取消文案（注入）
                    cancelled = true
                    consumeTurn(assembler, "$runId:$closingIndex", "$runId:a$closingIndex",
                        messages, produced).let { (u, t) -> usage = sumUsage(usage, u); truncated = truncated || t }
                    backfillCancelled(runId, closingIndex,
                        assembler.blocks.filterIsInstance<ContentBlock.ToolCall>(), messages, produced)
                } else {
                    // 收尾 turn 不执行任何工具调用（tools 已置空）；模型幻觉出的
                    // 调用一律剥除，不落任何未配对的 ToolCall（Task 11 移交项 2）
                    assembler.blocks.removeAll { it is ContentBlock.ToolCall }
                    consumeTurn(assembler, "$runId:$closingIndex", "$runId:a$closingIndex",
                        messages, produced).let { (u, t) -> usage = sumUsage(usage, u); truncated = truncated || t }
                    val hasBody = assembler.blocks.any { it is ContentBlock.Text && it.text.isNotBlank() }
                    if (!hasBody) {
                        // 无正文 → 追加用户可见说明（注入 RunMarkers.noClosingAnswerNote）；
                        // 不回灌模型上下文（messages 不动）
                        produced += AgentMessage(
                            id = "$runId:note$closingIndex", role = Role.ASSISTANT,
                            blocks = listOf(ContentBlock.Text(markers.noClosingAnswerNote)),
                            turnId = "$runId:$closingIndex",
                        )
                    }
                }
            }
        }
        // steeringLeftover 取收尾护栏段之后的 peek：护栏流式中到达的插话也落这里
        return LoopOutcome(produced.toList(), usage, truncated, cancelled, steering.peek())
    }

    /**
     * 流收集（P3 终审修复：取消贯通 SSE 订阅，spec §5.1）：collect 挂链作用域
     * （withContext(chain.scope.coroutineContext)，与工具执行同口径）——
     * chain.cancel() 即刻取消订阅，callbackFlow 的 awaitClose 触发 OkHttp
     * call.cancel()（生产 okHttpSseSource 已接线，LlmClientNormalizeTest 钉死）。
     * 返回 false = 链取消中断（取消点前已完成块已入 assembler，由调用方
     * consumeTurn 落账；未闭合块丢弃）；调用方协程自身被取消（非链取消）→
     * ensureActive 照常传播 CancellationException（不吞取消，与工具执行
     * catch 同模式）。
     */
    private suspend fun collectStream(
        chain: RunChain,
        request: LlmRequest,
        assembler: StreamAssembler,
        emit: suspend (StreamEvent) -> Unit,
    ): Boolean = try {
        withContext(chain.scope.coroutineContext) {
            llm.stream(request).collect { ev ->
                emit(ev)
                assembler.onEvent(ev)
            }
        }
        true
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()   // 调用方协程被取消：照常传播（非链取消）
        false
    }

    /** 组装 LlmRequest：budgetLeft 预算回路 + 装配位（见类 KDoc）；采样三参数链上下文播种，未配置为 null。 */
    private fun request(
        ctx: ContextBuilder,
        mode: ToolMode,
        chain: RunChain,
        modelOverride: String?,
        model: () -> String,
        maxTokens: (() -> Int?)?,
        searchAllowed: () -> Boolean,
    ): LlmRequest {
        // 循环侧不裁剪（Session 已在入参侧经 ContextBuilder 预裁，Task 11 移交项 1）：
        // maxContext=0 走 build 的无预算早退分支，仅借用其附注物化——transformContext
        // 逐 turn 注入的尾部附注照常上 wire（P5 审查修复：request 只消费 system/tools
        // 会静默丢弃 loop 侧附注；P3 的 appendSystem 是被逐 turn 消费的，注入能力
        // 必须等价迁移）。build 剥除输入中的旧附注（上一 turn 物化的）由本轮取代：
        // 附注恒一条恒在尾，不随 turn 堆积。
        val assembled = ctx.build(0, 0) { 0 }
        return LlmRequest(
            model = modelOverride ?: model(),
            system = ctx.system, history = assembled.kept.toList(),
            tools = ctx.tools, mode = mode,
            temperature = chain.context.sampling?.temperature,
            frequencyPenalty = chain.context.sampling?.frequencyPenalty,
            presencePenalty = chain.context.sampling?.presencePenalty,
            maxTokens = maxTokens?.invoke(),
            searchAllowed = searchAllowed(),
            budgetLeft = { chain.context.searchBudgetRemaining > 0 },
        )
    }

    /**
     * 收流产物落账：非空块铸 assistant 消息（共享 turnId）追加进上下文与 produced；
     * 返回 (本 turn 用量, 截断位)。
     *
     * toolResult 块（LlmClient 原生参数校验失败回填路径，spec §4.1 不变式）拆出为
     * 同 turnId 的独立 TOOL_RESULT 消息——内嵌在 assistant blocks 里既违反"ToolResult
     * 一律独立 TOOL_RESULT 消息"，也会被 WireConverter.toNativeWire（只认 Text/
     * ToolCall）整条丢弃，模型收不到"参数非法"回执、重试闭环断线。与工具执行
     * 回填的消息形状一致（同 turnId、每块一行）。
     */
    private fun consumeTurn(
        assembler: StreamAssembler,
        turnId: String,
        messageId: String,
        messages: MutableList<AgentMessage>,
        produced: MutableList<AgentMessage>,
    ): Pair<Usage, Boolean> {
        // toList() 快照（Task 11 移交项 3）：消息持不可变副本——收尾 turn
        // 的幻觉调用剥除等后续装配器变更不回渗进已铸消息
        val blocks = assembler.blocks.toList()
        if (blocks.isNotEmpty()) {
            val assistantBlocks = blocks.filter { it !is ContentBlock.ToolResult }
            if (assistantBlocks.isNotEmpty()) {
                val assistant = AgentMessage(
                    id = messageId, role = Role.ASSISTANT, blocks = assistantBlocks, turnId = turnId,
                )
                messages += assistant
                produced += assistant
            }
            blocks.filterIsInstance<ContentBlock.ToolResult>().forEachIndexed { j, r ->
                val m = AgentMessage(
                    id = "$messageId:r$j", role = Role.TOOL_RESULT, blocks = listOf(r), turnId = turnId,
                )
                messages += m
                produced += m
            }
        }
        return assembler.usage to assembler.truncated
    }

    /**
     * 取消补落：calls 中尚未落结果的工具调用（含进行中被中断的）逐个补落
     * isError ToolResult（输出=注入的取消文案 markers.toolCancelled）——
     * assistant 的 ToolCall 不悬空成孤儿（spec §5.1 评审三 P2-1），与同 turn
     * 共享 turnId。
     */
    private fun backfillCancelled(
        runId: String,
        turnIndex: Int,
        calls: List<ContentBlock.ToolCall>,
        messages: MutableList<AgentMessage>,
        produced: MutableList<AgentMessage>,
    ) {
        calls.forEachIndexed { j, call ->
            val m = AgentMessage(
                id = "$runId:r$turnIndex-x$j", role = Role.TOOL_RESULT,
                blocks = listOf(ContentBlock.ToolResult(call.id, call.name, markers.toolCancelled, isError = true)),
                turnId = "$runId:$turnIndex",
            )
            messages += m
            produced += m
        }
    }

    companion object {
        /** 收尾护栏语（spec §5.1，字面固定）。 */
        const val CLOSING_GUARD = "工具预算已尽，请基于已有信息直接作答，不要再调用工具"
        // 旧 NO_CLOSING_ANSWER_NOTE 常量已随内核清理 A（Task 4）迁 app 侧——
        // 收尾无正文说明现读注入的 RunMarkers.noClosingAnswerNote（SkillHost
        // 的 RunMarkerTexts 为唯一字面来源）。
    }
}

/** 单次 run 的结果汇总：产出消息（assistant + TOOL_RESULT 行，含收尾说明）/累计用量/截断/取消/插话残留。 */
data class LoopOutcome(
    val produced: List<AgentMessage>,
    val usage: Usage,
    val truncated: Boolean,
    val cancelled: Boolean,
    /** P-steer（spec §3.1）：run 收线时队列仍有未消费插话（预算尽顶住收束等）。 */
    val steeringLeftover: Boolean = false,
)

/**
 * LLM 流事件 → 块形态装配器（spec §4.5"块形态是唯一出口"的收流侧）：按 Block
 * Start/Delta/End 序列重建 ContentBlock（text/thinking/toolCall/toolResult），
 * TurnEnd.truncated 与 RunEnd.usage 顺带归集。toolResult 块仅出现在 LlmClient
 * 原生参数校验失败回填路径 → 重建为 isError ToolResult。
 */
private class StreamAssembler {
    val blocks = mutableListOf<ContentBlock>()
    var truncated = false
        private set
    var usage = Usage()
        private set

    private var openId: String? = null
    private var openType: String? = null
    private val buf = StringBuilder()
    private var tcName: String? = null
    private val tcArgs = StringBuilder()

    fun onEvent(ev: StreamEvent) {
        when (ev) {
            is StreamEvent.BlockStart -> {
                close()
                openId = ev.blockId
                openType = ev.type
                buf.setLength(0)
                tcArgs.setLength(0)
                tcName = null
            }
            is StreamEvent.BlockDelta -> buf.append(ev.delta)
            is StreamEvent.ToolCallDelta -> {
                tcName = ev.name ?: tcName
                tcArgs.append(ev.argsFragment)
            }
            is StreamEvent.BlockEnd -> close()
            is StreamEvent.TurnEnd -> if (ev.truncated) truncated = true
            is StreamEvent.RunEnd -> usage = sumUsage(usage, ev.usage)
            else -> {}
        }
    }

    private fun close() {
        val id = openId ?: return
        when (openType) {
            "text" -> if (buf.isNotEmpty()) blocks += ContentBlock.Text(buf.toString())
            "thinking" -> if (buf.isNotEmpty()) blocks += ContentBlock.Thinking(buf.toString())
            "toolCall" -> blocks += ContentBlock.ToolCall(id, tcName ?: "", tcArgs.toString())
            "toolResult" -> blocks += ContentBlock.ToolResult(
                id, tcName ?: "", buf.toString(), isError = true)
        }
        openId = null
        openType = null
    }
}

/** 用量逐字段累计（跨 turn 求和）。 */
private fun sumUsage(a: Usage, b: Usage) = Usage(
    promptTokens = a.promptTokens + b.promptTokens,
    completionTokens = a.completionTokens + b.completionTokens,
    cachedTokens = a.cachedTokens + b.cachedTokens,
)
