package dev.liuyouwu.familiar.agent

import dev.liuyouwu.familiar.core.llm.model.AgentMessage
import dev.liuyouwu.familiar.core.llm.model.Attachment
import dev.liuyouwu.familiar.core.llm.model.ContentBlock
import dev.liuyouwu.familiar.core.llm.model.ReplyRef
import dev.liuyouwu.familiar.core.llm.model.Role
import dev.liuyouwu.familiar.core.llm.model.SenderInfo
import dev.liuyouwu.familiar.core.llm.model.StreamEvent
import dev.liuyouwu.familiar.core.llm.model.Usage
import dev.liuyouwu.familiar.core.llm.wire.ToolMode
import dev.liuyouwu.familiar.core.llm.wire.ToolSpec
import dev.liuyouwu.familiar.util.appScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 容量口径同源暴露（P3 T3，评审 P0-1 承接）：五字段与 VM 的 ContextBreakdown
 * 同形（含 used/free 派生），数值由 [ContextBuilder.computeBreakdown] 以 trim
 * 同一计费口径算出（messages 按计费文本 estimate 求和、systemPrompt 计费、
 * reserve=reserveForOutput 同源公式、attachments 恒 0——不上 wire 不计费）。
 * VM 容量面板自此读 Session 单一口径，不再自算一套。
 */
data class SessionBreakdown(
    val messages: Int,
    val attachments: Int,
    val systemPrompt: Int,
    val reserve: Int,
    val maxContext: Int,
) {
    val used: Int get() = messages + attachments + systemPrompt
    val free: Int get() = (maxContext - used - reserve).coerceAtLeast(0)
}

/**
 * 会话对外状态流载荷（spec §5.4）：streaming=run 进行中；chainDepth=当前链深度
 * （hooks.onRunEnd 发起续讲/废稿重试时递增，autoContinue.maxDepth 判定用）；
 * lastUsage=最近一次 run 的累计用量。
 *
 * 流式载体五件（P3 T3 + P4 T4）——run 起点重置（RunStart 口径），收线终值完整
 * （不经节流截断）：
 * - streamingText/streamingReasoning：BlockDelta 按块类型累计的正文/思考增量
 *   （90ms 节流发射，收线补发终值=全量；streamingText 经构造位 streamingTextFilter
 *   显示层过滤——P4 T7 课堂标记剥除，落库原文不经此路）；
 * - streamingToolStatus：工具进度回调文本（registry.execute 的 onProgress 经
 *   AgentLoop 贯通，如"正在搜索：xxx"；低频直发不经节流）；
 * - streamingSender：本 run 的落库盖章位（P4 T4，runOnChain 通道非 null——聊天室/
 *   圆桌角色流式的发送者归属：UI 据此给流式泡挂角色名/色徽章；四类型 run 恒 null）；
 * - lastError：错误通道 VM 可见位——run 硬失败置位（异常消息），下一 run 起点
 *   清除；终态无条件写本 run 错误（正常/取消为 null——起点已清，语义等价）。
 * contextBreakdown：最近一次 run 收线时的容量口径（SessionBreakdown）。
 */
data class SessionState(
    val streaming: Boolean = false,
    val chainDepth: Int = 0,
    val lastUsage: Usage = Usage(),
    val streamingText: String = "",
    val streamingReasoning: String = "",
    val streamingToolStatus: String? = null,
    val streamingSender: SenderInfo? = null,
    val lastError: String? = null,
    val contextBreakdown: SessionBreakdown? = null,
)

/**
 * steer 的两值结果（P-steer，spec §3.3）：Deferred 携带"行已落库"前提——控制器
 * 据此走 pendingFollowUp 收线兜答，插话零丢失。文件顶层（非类内嵌套）：T3 以
 * 顶层 FQN 引用。
 */
sealed interface SteerOutcome {
    /** 已入活跃 run 的队列（本 run 消费，回合顶注入进模型上下文）。 */
    data object Steered : SteerOutcome

    /** 未入队（无活跃 run / 队列已关 / 会话错配 / offer 后复检失败）——行已落库。 */
    data object Deferred : SteerOutcome
}

/**
 * Agent 会话服务（spec §5.2a/§5.4）：五入口（send/continueRun/regenerateLastTurn/
 * editAndResend/compact）+ stop 的会话层编排。轮链生命周期——每个入口各开新链
 * （SupervisorJob scope，预算/查询集/深度全部重置），旧链被新链取代即取消
 * （生成中新输入 = stop-then-enqueue 语义）。
 *
 * 硬契约（AgentSessionTest 钉死）：
 * - 历史装配管线（Task 11 移交项 1）：store 历史 → 剥 UI 标记（截断/无回答，
 *   注入 RunMarkers"构建请求历史前剥掉"）→ hooks.transformContext 过一遍
 *   ContextBuilder（护栏语/记忆注入等系统段参与计费）→ build(maxContext,
 *   reserveForOutput 同源预留) 取 kept 作 loop 入参——AgentLoop 签名仅以
 *   可选参扩展（P3 装配位），既有调用兼容。
 * - RunResult 由本层从 LoopOutcome 组装（finalText/blocks 取末条 assistant 原始产出）。
 * - 落库修饰：truncated → 末条 assistant 末个 Text 块尾接 TRUNCATION_MARKER；
 *   NO_ANSWER（整 run 无 Text 有 Thinking）→ 同消息尾追加 NO_ANSWER_MARKER 可见
 *   说明（共享 turnId，regenerate 整组删）。
 * - 90ms 节流 + 收线补发（spec §5.4）：BlockDelta 驱动的状态发布统一节流，
 *   StateFlow 同值合流 + 节流双保险；run 收线直发终态（不经节流）。
 * - 流式载体（P3 T3，spec §5.4）：streamingText/streamingReasoning 按 BlockDelta
 *   块类型累计、起点重置、收线终值完整；工具 onProgress 文本经 AgentLoop
 *   onToolProgress 贯通到 streamingToolStatus（低频直发）；硬失败错误消息挂
 *   lastError（下一 run 起点清除）；contextBreakdown 收线时经 ContextBuilder.
 *   computeBreakdown（trim 同一计费口径）更新——VM 容量面板读同一口径。
 * - regenerate **延迟删除**（spec §5.4，P3 T2）：先以"删后历史"组装请求重跑
 *   （历史过滤掉旧轮整组/降级行，存量不动）；成功判据（评审 P2-4）——新回复
 *   有任何 assistant 产出落库（含取消半截）即删旧轮；空产出/硬失败 → 旧消息
 *   保留不动。lastAssistantTurnId 非空 → deleteTurn 删整组；NULL（旧数据）降级
 *   deleteById 删最后一行 assistant。
 * - compact：summarizer(historyText) → replaceRange 单条 "[对话摘要]" Text 行，
 *   不经 AgentLoop。
 * - followUpRun（P3 T2，spec §5.2 续讲）：同链续跑——后续 run 挂 chain.scope。
 *   launch，预算/查询集/深度随链共享；段间 delay 由 hooks 控制（T6 课堂 900ms
 *   在 hook 内，同样挂 chain.scope——链取消即终止含 gap 的后续 run）。终态时序
 *   契约（P1 KDoc 挂起约束的落地）：followUpPending 判据 = 链 scope 上的活跃
 *   子协程（onRunEnd 内 launch 同步注册子 Job，比异步置 session 标志更早可见、
 *   免跨协程复位竞态，且天然覆盖段间 gap 的挂起协程；已取消协程不计入——死
 *   协程不可能再产生 run）——本轮收线时链上有挂起的后续 run 则**不发布
 *   streaming=false 终态**，由链上最后一个 run 的收线统一发布；stop() 取消整链
 *   后补发终态（gap 协程被取消不会再有 run 收线）。**chain.scope 纪律（T6
 *   hook 作者须知）**：只准挂 gap/run 形态的短生命周期协程——长生命周期协程
 *   （进度动画/ticker）会推迟终态发布且无人补发，streaming 永久卡 true。
 * - 双持久化钩子（P3 T2，评审 P1-4）：persistTransform 落库前变换 run 产出
 *   消息列表（课堂拆条：1 条→N 条台词消息）；onPersisted 落库后回调
 *   （messages 与 rowIds 一一对齐，familiar 切段取 rowIds[0]）。仅覆盖 run 产出
 *   的落库路径——send 的 user 行 / compact 摘要行不经钩子。异常行为（fail
 *   loudly，均不吞）：persistTransform 抛 → 零落库 + 终态正常发布 + 异常逃逸
 *   调用方；onPersisted 抛 → 行已落库 + onRunEnd/titleTrigger 跳过 + 终态正常
 *   发布 + 异常逃逸调用方（见 persistProduced KDoc）。
 * - run 完成 → hooks.onRunEnd(runResult, chain) → titleTrigger(runResult)；
 *   run 硬失败（LLM 流抛异常）→ 链取消、空产出 cancelled 语义 RunResult、终态
 *   补发（链上无挂起后续 run 时；异常不外抛；错误通道 P3 在 executeRun 挂）；
 *   调用方协程取消（CancellationException）→ 链一并取消（防孤儿 SSE 订阅）后
 *   终态补发、原样传播。
 * - 取消贯通（P3 终审，spec §5.1"取消信号贯通 SSE 订阅"）：run 的流收集挂链
 *   作用域（AgentLoop.collectStream）——stop()/插话开新链即**即刻取消订阅**
 *   （OkHttp call.cancel 到位），取消点前已完成块照常落库、点后幽灵块不补收
 *   （P1 实现偏了的"取消后全量收完"语义终审抓回）；被取代链的收线不发布终态
 *   （发布权归现行链——插话后旧 run 终态不得打回新流，executeRun finally 守卫）；
 *   调用方协程取消 → 链即刻取消（executeRun 入口 invokeOnCompletion(onCancelling)
 *   接线——流收集块挂链 SupervisorJob 而非调用方 Job，不接线则订阅跑到自然收完）。
 *
 * 线程模型：入口为 suspend、在调用方协程内联驱动（P3 由 VM 在 viewModelScope
 * 串行驱动）；currentChain 无锁——同一会话串行调用不构成并发面。
 *
 * 请求装配位（P3 T1，四件套）：构造位 model/maxTokens/searchAllowed 三供应
 * 商逐请求贯通进 LlmRequest（maxTokens 供应商 null 或求值 null = 不发送该字
 * 段；canonical 公式 reserveForOutput(maxContext()) 由 T6 SkillHost 装配）；
 * sampling 构造位供应商（B2-L1）经 openChain 单点求值播种进链上下文——
 * 采样三参数链上下文播种，未配置为 null（null 字段 = 对应键不发送）。
 * modelOverride（regenerateLastTurn 形参，spec §5.4"切换模型并重生成"）：run
 * 级覆盖——LlmRequest.model = modelOverride ?: model()，优先于构造位 model
 * 供应商（见 AgentLoop 类 KDoc 请求装配段）。
 *
 * 回合级 API（P4 T1，spec §5.3 Orchestrator 的驱动通道）：acquireChain 取/建
 * 当前链（一回合=一条链，stop() 取消半径=整回合）、runOnChain 参数化 executeRun
 * 的公开内形态（systemOverride/sender/调用方 emit），既有机制（流式发布/持久化/
 * 收线/取消贯通/哨兵接线）全部复用——Orchestrator 不另起炉灶。
 */
class AgentSession(
    private val store: MessageStore,
    private val loop: AgentLoop,
    /**
     * 用户可见标记文案（内核清理 A，Task 4，RunMarkers 注入）：decorate（截断/
     * 无回答标记追加）与 stripUiMarkers（构建请求历史前剥标记）读自此——标记
     * 文案零内嵌字面，唯一来源 app 侧 SkillHost 的 RunMarkerTexts（装配层双路直注
     * 本件与 AgentLoop）。参数必填（无中文缺省值）。
     */
    private val markers: RunMarkers,
    private val hooks: AgentHooks,
    private val system: () -> String,
    private val tools: List<ToolSpec>,
    private val mode: () -> ToolMode,
    private val maxContext: () -> Int,
    private val summarizer: suspend (String) -> String,
    private val titleTrigger: (RunResult) -> Unit = {},
    private val model: () -> String = { "" },
    private val maxTokens: (() -> Int?)? = null,
    private val searchAllowed: () -> Boolean = { true },
    /** 链级采样参数供应商（B2-L1）：openChain 单点求值播种（生效粒度=链，spec §3.4）。 */
    private val sampling: () -> SamplingParams? = { null },
    /** 落库前 run 产出变换（课堂拆条：1 条→N 条台词消息，首行带 Thinking）。 */
    private val persistTransform: ((List<AgentMessage>) -> List<AgentMessage>)? = null,
    /** 落库后回调（rowIds 与 messages 一一对齐；familiar 切段取 rowIds[0] 调 repo.updateSegments）。 */
    private val onPersisted: ((messages: List<AgentMessage>, rowIds: List<Long>) -> Unit)? = null,
    /**
     * 流式正文显示层过滤（P4 T7，课堂 ghost 剥离）：非 null 时在 streamingText 的
     * **发布点**（节流中间态 + 收线终值快照）对**原始累计文本**整体套用——发布点
     * 套用（而非载体改写点）是增量安全的关键：扣留形态过滤器（自标记起点截除后
     * 续文本）若作用于"过滤后载体 + delta"，被扣留的标记体从输入中丢失、后续
     * delta 泄漏（AgentSessionTest 33 钉死）；对原始累计套用则每窗口重算自完整
     * 上下文。仅作用于 streamingText，落库（outcome.produced → persist 路径）与
     * 收线解析输入不受影响。SkillHost 在 contentProtocol=classroomScript
     * 时注入课堂标记剥除（评审 P1-5：不在 LlmClient 规范化层剥——会污染块装配
     * 破坏落库解析）。null = 不过滤（四类型/既有会话零行为变化）。
     */
    private val streamingTextFilter: ((String) -> String)? = null,
    /**
     * 链创建即回填（P5 T2，评审 P1-2 修订方案——"setGateChain"回调）：openChain
     * 是全链创建**单点**（五入口 + followUpRun 兜底 + acquireChain 回落全经此），
     * 创建即把活链交给装配层。旧方案（onRunEnd 回填）使首个 run 全程无链引用、
     * 执行侧预算门恒放行——回填挂 openChain 后首个 run 起门即受限。缺省空：
     * 不需要预算门的装配零负担。
     */
    private val onChainOpened: (RunChain) -> Unit = {},
) {

    /** 会话状态流：UI 侧唯一订阅口（节流后的流式生命周期 + 终态用量）。 */
    val events = MutableStateFlow(SessionState())

    /** 工具面 T6 装配断言锚点：[tools] 构造位的只读暴露（SkillHostTest 断言名单用）。 */
    internal val toolsSpecs: List<ToolSpec> get() = tools

    private var currentChain: RunChain? = null
    private var lastThrottleNanos = 0L

    // 本 run 流式增量载体（P3 T3）：executeRun 起点重置；onStreamEvent（调用方
    // 协程）/onToolProgress（工具执行协程，chain.scope 调度器）跨线程写——
    // volatile 保终态快照的可见性（两写者实际不并发：工具执行时收流已挂起）。
    // streamText 恒为原始累计；streamingText 发布点（publishStreaming/收线快照）
    // 经构造位 streamingTextFilter 显示层过滤（P4 T7，课堂标记剥除）——落库路径
    // 读 produced，不读此载体。
    @Volatile private var streamText = ""
    @Volatile private var streamReasoning = ""
    @Volatile private var streamToolStatus: String? = null

    /** 本 run 的 sender 盖章位（P4 T4）：executeRun 起点随载体重置（runOnChain 通道非 null）。 */
    @Volatile private var streamSender: SenderInfo? = null

    /** BlockStart 登记的块类型表（BlockDelta 只有 blockId，类型查此表分派累计目标）。 */
    private val openBlockTypes = HashMap<String, String>()

    /**
     * 活跃 run 的插话队列（P-steer，spec §3.3）：executeRun 造（loop.run 前置位）、
     * 收尾 close + finally 摘指针（身份守卫——转投的新 run 可能已置新队列）。
     * steer 的活跃/isOpen 判定源；@Volatile：steer 可自任意线程调用（UI 线程或
     * loop 流收集协程内）读本位，executeRun 在调用方协程写。
     */
    @Volatile private var activeSteering: SteeringQueue? = null

    // ---------- 五入口 ----------

    /** 新输入：落 user 行（附件/回复引用随行）并开新链执行 run。 */
    suspend fun send(
        conversationId: String,
        text: String,
        attachments: List<Attachment> = emptyList(),
        replyTo: ReplyRef? = null,
    ) {
        val user = AgentMessage(
            id = mintId("u"), role = Role.USER,
            blocks = listOf(ContentBlock.Text(text)),
            attachments = attachments, replyTo = replyTo,
            // user 侧 turnId 铸造推迟至 P2/P3 行映射任务（Task 13 注）
        )
        store.append(conversationId, user)
        executeRun(conversationId, openChain(conversationId), modelOverride = null)
    }

    /** 继续生成：以现有历史续跑（剥 UI 标记后），开新链（预算重置）。 */
    suspend fun continueRun(conversationId: String) {
        executeRun(conversationId, openChain(conversationId), modelOverride = null)
    }

    /**
     * 重生成（**延迟删除**，spec §5.4，P3 T2）：先以"删后历史"组装请求重跑——
     * lastAssistantTurnId 非空 → 历史滤掉整组、重跑后 deleteTurn 删整组；NULL
     * （旧数据）降级滤掉/删最后一行 assistant。成功判据（评审 P2-4）：新回复
     * 有任何 assistant 产出落库（含取消半截）即删旧轮；空产出/硬失败 → 旧消息
     * 保留不动。开新链。
     */
    suspend fun regenerateLastTurn(conversationId: String, modelOverride: String? = null) {
        val turnId = store.lastAssistantTurnId(conversationId)
        val legacyRowId = if (turnId == null) {
            store.list(conversationId).lastOrNull { it.role == Role.ASSISTANT }?.id
        } else null
        // 删后历史：存量不动，仅请求装配侧过滤（旧轮整组 / 降级行）
        val deferredHistory = if (turnId != null) {
            store.list(conversationId).filterNot { it.turnId == turnId }
        } else if (legacyRowId != null) {
            store.list(conversationId).filterNot { it.id == legacyRowId }
        } else null
        val execution = executeRun(
            conversationId, openChain(conversationId), modelOverride,
            historyOverride = deferredHistory,
        )
        if (execution.persisted.any { it.role == Role.ASSISTANT }) {
            if (turnId != null) store.deleteTurn(conversationId, turnId)
            else legacyRowId?.let { store.deleteById(conversationId, it) }
        }
    }

    /**
     * 同链续跑（spec §5.2 续讲，P3 T2）：后续 run 挂 currentChain.scope.launch
     * （预算/查询集/深度随链共享；无现行链时开新链兜底）。调用方（T6 hooks 在
     * onRunEnd 内）须在 chain.scope 协程内调用；链已取消则不续跑并补发终态。
     * 终态时序契约见类 KDoc followUpRun 条目——**chain.scope 只准挂 gap/run
     * 形态的短生命周期协程**（长生命周期协程会推迟终态且无人补发）。
     */
    suspend fun followUpRun(conversationId: String) {
        val chain = currentChain ?: openChain(conversationId)
        if (!chain.scope.isActive) {
            // 链已取消：本续跑不执行。前序 run 可能因链上挂起协程跳过了终态发布，
            // 此处补发收线（幂等——events 终态为同值合流）。
            publish { st -> st.copy(streaming = false, chainDepth = chain.context.depth) }
            return
        }
        // 旁路 launch 补捕获（审计 R1，任务 1.1）：executeRun 不捕的落库等异常
        // 原样逃逸无人 await 的旁路协程——此处收口进 lastError（不崩、不外抛）；
        // 取消原样传播（链取消语义不动）。
        chain.scope.launch {
            try {
                executeRun(conversationId, chain, modelOverride = null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                publish { it.copy(lastError = e.message ?: "后台续讲失败") }
            }
        }
    }

    /** 编辑重发：从被编辑行起整段截断，替入新 user 行重跑（开新链）。 */
    suspend fun editAndResend(conversationId: String, messageId: String, newText: String) {
        val edited = AgentMessage(
            id = mintId("u"), role = Role.USER,
            blocks = listOf(ContentBlock.Text(newText)),
        )
        store.replaceRange(conversationId, messageId, listOf(edited))
        executeRun(conversationId, openChain(conversationId), modelOverride = null)
    }

    /**
     * 对话压缩：summarizer(全文历史) → 自首行起整段替换单条
     * "[对话摘要]" Text 行（assistant）。不经 AgentLoop（spec §5.4），但同样
     * 开新链（预算重置，旧链取消闭合）。空会话无锚点，直接返回。
     */
    suspend fun compact(conversationId: String) {
        openChain(conversationId)
        val rows = store.list(conversationId)
        if (rows.isEmpty()) return
        val historyText = rows.joinToString("\n") { m ->
            val label = if (m.role == Role.USER) "用户" else "助手"
            "$label: " + m.blocks.filterIsInstance<ContentBlock.Text>()
                .joinToString("\n") { it.text }
        }
        val summary = AgentMessage(
            id = mintId("sum"), role = Role.ASSISTANT,
            blocks = listOf(ContentBlock.Text("[对话摘要]\n" + summarizer(historyText))),
        )
        store.replaceRange(conversationId, rows.first().id, listOf(summary))
    }

    /**
     * 打断：取消当前链整链——run 执行中/工具中/gap 挂起的后续 run 全停，已产出
     * 照常落库。取消后**补发终态**（P3 T2）：被 gap 协程推迟了终态发布的链，
     * 其挂起协程被取消后不会再有 run 收线，此处统一收口（在飞 run 的收线补发
     * 随后同值再发，幂等）。
     */
    fun stop() {
        val chain = currentChain ?: return
        chain.cancel()
        publish { st -> st.copy(streaming = false, chainDepth = chain.context.depth) }
    }

    /**
     * 生成中插话（P-steer，spec §3.3）：user 行即时落库（写序即时、UI 即现；读序
     * 与模型所见序存在已登记分歧——同节"落库序与模型所见序的分歧"）；run 活跃且
     * 会话匹配 → 入队返回 [SteerOutcome.Steered]；否则 [SteerOutcome.Deferred]
     * （行已落库，控制器 pendingFollowUp 兜答）。offer 后复检 isOpen 是 TOCTOU
     * 契约第④步：close 后 offer 走复检失败 → Deferred → 收线兜答，零丢失闭合。
     *
     * 实现注记（偏离 spec 的登记）：spec 用 loopActive 名目，实现以
     * activeSteering!=null + isOpen() 等价承担——窗口略宽（onRunEnd/titleTrigger
     * 期间仍可 Steered），落在该窗的 offer 由收尾残留判定兜住转投，行为不劣。
     */
    suspend fun steer(
        conversationId: String,
        text: String,
        attachments: List<Attachment> = emptyList(),
        replyTo: ReplyRef? = null,
    ): SteerOutcome {
        val user = AgentMessage(
            id = mintId("u"), role = Role.USER,
            blocks = listOf(ContentBlock.Text(text)),
            attachments = attachments, replyTo = replyTo,
        )
        store.append(conversationId, user)   // 先落库：Deferred 路径的行也在（兜答数据源）
        val q = activeSteering ?: return SteerOutcome.Deferred
        if (!q.isOpen() || currentChain?.conversationId != conversationId) return SteerOutcome.Deferred
        q.offer(user)
        return if (q.isOpen()) SteerOutcome.Steered else SteerOutcome.Deferred
    }

    /**
     * 排队追问落库（followUp 路用，P-steer）：只落 user 行不开 run——续跑由
     * 控制器收线后驱动。与 [steer] 的 Deferred 路径同落库形态（user 行、无 sender）。
     */
    suspend fun persistUser(
        conversationId: String,
        text: String,
        attachments: List<Attachment> = emptyList(),
        replyTo: ReplyRef? = null,
    ) {
        store.append(
            conversationId,
            AgentMessage(
                id = mintId("u"), role = Role.USER,
                blocks = listOf(ContentBlock.Text(text)),
                attachments = attachments, replyTo = replyTo,
            ),
        )
    }

    // ---------- 回合级 API（P4 T1，spec §5.3） ----------

    /**
     * 取/建当前链并登记 currentChain（P4 T1，Orchestrator 的驱动通道）：同一会话
     * 的现行链存活期间重复 acquire 返回**同一条链**（一回合=一条链，多角色顺序
     * runOnChain 共享预算/查询集/深度；stop() 的取消半径即覆盖整回合）；现行链
     * 已闭合（stop 取消/被新链取代）或属他会话时开新链（旧链取消闭合，预算全
     * 重置——与五入口 openChain 同语义）。复用半径=**回合内**：回合收尾由
     * Orchestrator 经 [releaseChain] 释放（P4 T2 承接②），链不跨回合存活——
     * 下回合 acquire 必开新链。
     */
    internal fun acquireChain(conversationId: String): RunChain {
        val existing = currentChain
        if (existing != null && existing.conversationId == conversationId && existing.scope.isActive) {
            return existing
        }
        return openChain(conversationId)
    }

    /**
     * 回合收尾释放链（P4 T2，T1 评审承接②"每回合强制换链"）：注销并取消
     * currentChain——下回合 [acquireChain] 必开新链（预算/查询集/深度全新）。
     * 仅当现行链属 [conversationId] 时动作（他会话链不误伤）。回合内 stop()
     * 语义不变；回合各 run 已收线，取消 scope 只释放资源、不再影响产出与终态
     * （终态发布发生在各 run 收线时，彼时 currentChain 仍指本链）。
     */
    internal fun releaseChain(conversationId: String) {
        val chain = currentChain ?: return
        if (chain.conversationId == conversationId) {
            currentChain = null
            chain.cancel()
        }
    }

    /**
     * 指定链上执行一次 run（P4 T1）：参数化 [executeRun] 的公开内形态——
     * - [input] 直供请求历史（"删后历史"同机制：剥 UI 标记 → transformContext
     *   → maxContext 预裁；Orchestrator 以"名字: "前缀 wire 形态传入）；
     * - [systemOverride] 替换构造位 system()（角色 prompt 优先于会话系统提示）；
     * - [modelOverride] 贯通 loop.run（null 回落构造位 model 供应商）；
     * - [sender] 盖章落库消息（MessageRowMapper sender 三列的数据源；user 行/
     *   compact 摘要行不经此路不盖章）；
     * - [emit] 调用方事件汇（Orchestrator → VM 流式展示），既有流式发布
     *   （SessionState 节流/收线终态）照常先行；
     * - 持久化（persistTransform/onPersisted 钩子）/收线/取消贯通（哨兵接线）/
     *   被取代链终态让位全走 executeRun 既有机制。
     *
     * 返回该 run 的 LoopOutcome（Orchestrator 据此汇总 RoundOutcome；注意
     * produced 是循环原始产出，sender 盖章只发生在落库侧）。
     */
    internal suspend fun runOnChain(
        chain: RunChain,
        input: List<AgentMessage>,
        systemOverride: String? = null,
        modelOverride: String? = null,
        sender: SenderInfo? = null,
        emit: suspend (StreamEvent) -> Unit,
    ): LoopOutcome = executeRun(
        conversationId = chain.conversationId,
        chain = chain,
        modelOverride = modelOverride,
        historyOverride = input,
        systemOverride = systemOverride,
        sender = sender,
        extraEmit = emit,
    ).outcome

    // ---------- run 编排 ----------

    private suspend fun executeRun(
        conversationId: String,
        chain: RunChain,
        modelOverride: String?,   // run 级模型覆盖：优先于构造位 model 供应商
        historyOverride: List<AgentMessage>? = null,   // "删后历史"装配（regenerate 延迟删除 / runOnChain 直供）
        systemOverride: String? = null,   // run 级系统提示覆盖（runOnChain：角色 prompt 替换构造位 system()）
        sender: SenderInfo? = null,       // 落库消息 sender 盖章（runOnChain：MessageRowMapper sender 三列）
        extraEmit: (suspend (StreamEvent) -> Unit)? = null,   // 调用方事件汇（runOnChain：既有流式发布照常先行）
    ): RunExecution {
        // 调用方协程死亡 → 链即刻取消（P3 复审加固）：collectStream 的 withContext
        // 使流收集块脱离调用方 Job（挂链 SupervisorJob）——调用方取消（VM
        // agentJob onCleared 等）不再即刻波及在飞 SSE 订阅，订阅会跑到自然收完/
        // readTimeout（实测时点回归：慢流下取消需等全程）。此处以哨兵子 Job 把
        // 调用方 Job 的**取消起点**接线到 chain.cancel()：调用方 cancel() 即刻
        // 取消子 Job（invokeOnCompletion 公开 API 于取消时点触发，非 Job 终态）→
        // collect 子协程随即取消 → awaitClose → OkHttp call.cancel。正常收线以
        // complete() 摘除（cause=null 不触发链取消，防泄漏/防旧哨兵误触发）。
        val callerCancelWatch = currentCoroutineContext()[Job]?.let { callerJob ->
            Job(callerJob).also { watch ->
                watch.invokeOnCompletion { cause ->
                    if (cause is CancellationException) chain.cancel()
                }
            }
        }
        // 流式载体起点重置（RunStart 口径，P3 T3）：本 run 的增量载体清零——
        // 上一 run 残值串台即 UI 显示错误；lastError 同步清除（错误不跨 run）。
        // streamingSender 随行重置（P4 T4）：本 run 的 sender 盖章（四类型 run 恒 null）。
        streamText = ""
        streamReasoning = ""
        streamToolStatus = null
        streamSender = sender
        openBlockTypes.clear()
        publish {
            it.copy(
                streaming = true, chainDepth = chain.context.depth,
                streamingText = "", streamingReasoning = "", streamingToolStatus = null, lastError = null,
                streamingSender = sender,
            )
        }
        var outcome: LoopOutcome? = null   // null = 未收线（调用方取消路径）
        var hardFailed = false
        var runError: String? = null       // 硬失败错误消息（终态置位 lastError）
        var persistedNow: List<AgentMessage> = emptyList()
        // P-steer（spec §3.3）：本 run 的插话队列——与 outcome 同层声明（try 之前），
        // finally 的 activeSteering === queue 身份比较要求此处作用域可见（评审 P2-7）
        val queue = SteeringQueue()
        // 收线快照（P3 T3）：onRunEnd 前取——后续 followUp run 可能已在 chain.scope
        // 上开始并重置实例载体，终态发布只读本 run 快照。
        var finalStreamText = ""
        var finalStreamReasoning = ""
        var finalToolStatus: String? = null
        var finalSender: SenderInfo? = null
        var breakdown: SessionBreakdown? = null
        try {
            // 评审 F2：先装配 input（buildRequestHistory 的 store 读）再置
            // activeSteering——置位与 store 读之间的 steer 落入会既进 turn0 历史
            // （经 store 重建）又被回合顶 drain 再注一次，模型见重复提问；换序后
            // 缝内的 steer 走 Deferred（行只随 store 进首请求），零成本闭合。
            val input = buildRequestHistory(conversationId, historyOverride)
            activeSteering = queue   // loop.run 前置位（steer 的活跃判定源）
            val resolved: LoopOutcome = try {
                loop.run(
                    input = input,
                    system = systemOverride ?: system(),
                    tools = tools,
                    hooks = hooks,
                    chain = chain,
                    mode = mode(),
                    modelOverride = modelOverride,
                    model = model,
                    maxTokens = maxTokens,
                    searchAllowed = searchAllowed,
                    steering = queue,
                    onToolProgress = ::onToolProgress,
                ) { ev ->
                    onStreamEvent(ev, chain)            // 既有流式机制先行
                    extraEmit?.invoke(ev)               // 调用方事件汇随后（P4 T1）
                }
            } catch (e: CancellationException) {
                // 调用方协程被取消（VM scope 死亡等）：run 作废——**链一并取消**
                // （P3 终审：流收集已挂链作用域，调用方取消不自动波及链子协程，
                // 不取消即孤儿 SSE 订阅），finally 补发终态后原样传播（不吞取消）
                chain.cancel()
                throw e
            } catch (e: Throwable) {
                // run 硬失败（P3 真实网络流抛异常）：链作废取消；produced 困在 run 内
                // 无从回收 → 空产出按 cancelled 语义组装 RunResult（评审修复轮 1；
                // 异常不外抛；错误通道 P3 在此挂——lastError 置位错误消息）。
                // 已落库内容不动。
                chain.cancel()
                hardFailed = true
                runError = e.message ?: e.javaClass.simpleName
                LoopOutcome(produced = emptyList(), usage = Usage(), truncated = false, cancelled = true)
            }
            outcome = resolved
            // 收线快照含显示层过滤（P4 T7）：与节流中间态同一派生口径（对原始累计
            // 整体套用），streamingText 的中间态与终值都无标记
            finalStreamText = applyTextFilter(streamText)
            finalStreamReasoning = streamReasoning
            finalToolStatus = streamToolStatus
            finalSender = streamSender
            persistedNow = persistProduced(conversationId, decorate(resolved), sender)
            // 容量口径同源暴露（P3 T3）：收线时以落库后的整段历史（含本 run 产出）
            // 经 computeBreakdown（trim 同一计费口径）计算。regenerate 的旧轮删除
            // 发生在 executeRun 返回后——本值为删除前快照，由下一 run 收线修正。
            breakdown = ContextBuilder.computeBreakdown(
                system(), store.list(conversationId), maxContext(), ContextBuilder::estimateTokens)
            val result = assembleRunResult(resolved)
            hooks.onRunEnd(result, chain)
            titleTrigger(result)
            // P-steer 残留转投（spec §3.3 两道闸）。close 必须先于残留判定（评审
            // P1-5）：peek 判定后、close 前 offer 的窗口会让 steer 返回 Steered 但
            // 消息困死队列——close 后 offer 走复检失败→Deferred→收线兜答，零丢失
            // 闭合；close 后 peek 照常可用（SteeringQueue 契约，AgentSessionSteerTest 钉）。
            // 评审 F1：close 关**本 run 自己的队列**而非"当前指针"——打断接力路径
            // （活跃 run 中新 send/continueRun → openChain 取消旧链 → loop 转正常
            // 返回）下，本 run 走到此处时新 run 可能已置 activeSteering=queue2，指针
            // 形态会关掉新 run 的活队列（整个替换 run 存活期 steer 恒 Deferred）。
            // run 拥有自己的队列，queue.close() 身份天然安全。
            queue.close()
            if (queue.peek() && !resolved.cancelled) {
                // 闸②（防御纵深）：onRunEnd 已先行，hooks 抢先发的 gap 子协程会让
                // 本判断让位（主闸=闸① steeringLeftover 让 hooks 不发，ClassroomHooks）；
                // 判据与 followUpPending 同款——"链上无活跃子协程"（排除自身；
                // 已取消协程不计入）。resolved.cancelled 时残留作废（stop 语义，
                // 行已落库可接受——回合间取消已 drain 的插话既没给模型也不转投）。
                val self = currentCoroutineContext()[Job]
                val childrenBusy = chain.scope.coroutineContext[Job]
                    ?.children?.any { it.isActive && it != self } == true
                if (chain.scope.isActive && !childrenBusy) followUpRun(conversationId)
            }
            return RunExecution(resolved, result, persistedNow)
        } finally {
            callerCancelWatch?.complete()   // 本 run 收线：哨兵正常完成（无 cause，不触发链取消）
            // P-steer：摘队列指针（spec §3.3 收尾清理）。身份守卫防跨 run 清除（评审
            // P1-4）：转投的 followUpRun 可能已起新 executeRun 置了新 queue，无条件清
            // 会把新 run 的队列指针抹掉、steer 恒 Deferred。
            if (activeSteering === queue) activeSteering = null
            // 收线补发（不经节流）是基线契约（评审修复轮 1）：正常/取消/异常路径都
            // 必须落终态——events 是 UI 唯一订阅点且无重置 API，漏发即 streaming
            // 永久 true。publish 非挂起，已取消协程的 finally 内照常执行；未收线
            // 路径（取消/硬失败）无本 run 用量，保留上次值。
            // 终态携带流式载体收线快照（终值完整，非最后一节流窗的截断值）+
            // lastError + contextBreakdown（P3 T3）。
            // P3 顺序约束（T2 落地，P1 KDoc 挂起项闭合）：本终态发布在 hooks.
            // onRunEnd 之后——onRunEnd 内挂 chain.scope 发起的后续 run（含段间
            // gap 协程）已同步注册为链 scope 的活跃子 Job，此时本轮**不发布**
            // streaming=false，由链上最后一个 run 的收线统一发布（followUpPending
            // 判据，见类 KDoc）。排除自身：followUp run 本身也是链子协程；
            // 过滤已取消协程（isActive）——违约/hard-fail 场景下链可能已取消而
            // 死子协程短暂仍挂附（Cancelling→detach 异步），死协程不可能再产生
            // run，计入会把终态永久卡在 streaming=true（审查 Important 1）。
            val self = currentCoroutineContext()[Job]
            val followUpPending = chain.scope.coroutineContext[Job]
                ?.children?.any { it.isActive && it != self } == true
            // 被取代链让位（P3 终审 Important-2）：插话开新链后，旧链 run 的收线
            // **不发布终态**——发布权归现行链，旧 run 的 streaming=false 不得把
            // 新流的 streaming=true 打回（幽灵终态）。旧链若为最后链（stop/自然
            // 收线）则 chain === currentChain 成立，照常发布。
            if (!followUpPending && chain === currentChain) {
                val runUsage = if (hardFailed) null else outcome?.usage
                publish { st ->
                    st.copy(
                        streaming = false,
                        chainDepth = chain.context.depth,
                        lastUsage = runUsage ?: st.lastUsage,
                        streamingText = finalStreamText,
                        streamingReasoning = finalStreamReasoning,
                        streamingToolStatus = finalToolStatus,
                        streamingSender = finalSender,
                        lastError = runError,
                        contextBreakdown = breakdown ?: st.contextBreakdown,
                    )
                }
            }
        }
    }

    /**
     * 开新链（spec §5.2a）：旧链取消闭合（生成中新输入 = 打断后接力），预算全重置。
     * 创建即经 [onChainOpened] 把活链交给装配层（P5 T2 评审 P1-2 修订：预算门
     * 链引用回填挂此单点——五入口/followUpRun 兜底/acquireChain 回落全经此开链，
     * 挂一处即全覆盖；首个 run 起执行侧门即受限）。链创建同点播种 sampling
     * （B2-L1：构造位供应商单点求值，生效粒度=链）。
     */
    private fun openChain(conversationId: String): RunChain {
        currentChain?.cancel()
        // 链 scope 装统一 handler（审计 R1，任务 1.1）：旁路协程异常收口记日志，
        // 不再逃逸全局 handler（进程级风险）；SupervisorJob 语义不变。
        return RunChain(conversationId, appScope(Dispatchers.Default, "AgentSessionChain"))
        // 采样播种在 onChainOpened 之前：预算门回调（gate::setChain）不消费 sampling，
        // 次序无依赖，但播种先行使首个 transformContext 起即可读
            .also {
                currentChain = it
                it.context.sampling = sampling()
                onChainOpened(it)
            }
    }

    /**
     * 请求历史装配（Task 11 移交项 1）：剥 UI 标记 → hooks.transformContext 过
     * ContextBuilder → 按 maxContext 预裁取 kept（reserve 与本地 max_tokens 同源
     * 公式），传入 loop；循环内逐 turn 再过一遍 transformContext（护栏语/记忆等
     * 系统段变换在工具往返后的中间 turn 照常生效）。本侧装配 trimOnly=true：产物
     * 只用于预裁计费，尾部附注由 loop 侧 wire 装配重新物化（一次性 hooks 状态的
     * 消费点在 wire 侧，见 ContextBuilder.trimOnly）。
     */
    private suspend fun buildRequestHistory(
        conversationId: String,
        override: List<AgentMessage>? = null,   // "删后历史"直供（regenerate 延迟删除）
    ): List<AgentMessage> {
        val raw = override ?: store.list(conversationId)
        val stripped = raw
            .map(::stripUiMarkers)
            .filter { it.role != Role.ASSISTANT || it.blocks.isNotEmpty() }
        // trimOnly 装配（P5 审查修复）：本 build 产物只用于预裁，wire 装配由 loop 侧
        // request() 逐 turn 经 build() 完成——一次性 hooks 状态（续讲旗标）不在此消费
        val ctx = ContextBuilder(system(), stripped, tools, trimOnly = true)
        hooks.transformContext(ctx)
        val maxCtx = maxContext()
        return ctx.build(maxCtx, ContextBuilder.reserveForOutput(maxCtx), ContextBuilder::estimateTokens).kept
    }

    /** assistant 行剥 UI 标记（注入 RunMarkers：构建请求历史前剥掉）；剥空的 Text 块整块丢弃。 */
    private fun stripUiMarkers(m: AgentMessage): AgentMessage {
        if (m.role != Role.ASSISTANT) return m
        return m.copy(blocks = m.blocks.mapNotNull { b ->
            if (b !is ContentBlock.Text) b
            else stripMarkerSuffixes(b.text).takeIf { it.isNotBlank() }?.let { ContentBlock.Text(it) }
        })
    }

    private fun stripMarkerSuffixes(text: String): String {
        var t = text.removeSuffix(markers.truncationMarker)
            .removeSuffix(markers.noAnswerMarker)
        // 整条说明消息（旧数据单行形态）：对模型上下文等价于没有这轮
        if (t.trim() == markers.noAnswerTail.trim('*')) t = ""
        return t
    }

    /** run 产出的落库形态修饰：截断标记 / NO_ANSWER 可见说明（均在消息尾部，共享 turnId）。 */
    private fun decorate(outcome: LoopOutcome): List<AgentMessage> {
        var produced = outcome.produced.toList()
        if (produced.isEmpty()) return produced
        val hasText = produced.any { m -> m.role == Role.ASSISTANT && m.blocks.any { it is ContentBlock.Text } }
        val hasThinking = produced.any { m -> m.blocks.any { it is ContentBlock.Thinking } }
        if (!hasText && hasThinking) {
            produced = appendToLastAssistant(produced) { blocks ->
                blocks + ContentBlock.Text(markers.noAnswerMarker)
            }
        }
        if (outcome.truncated) {
            produced = appendToLastAssistant(produced, ::appendTruncationMarker)
        }
        return produced
    }

    private fun appendToLastAssistant(
        produced: List<AgentMessage>,
        transform: (List<ContentBlock>) -> List<ContentBlock>,
    ): List<AgentMessage> {
        val last = produced.indexOfLast { it.role == Role.ASSISTANT }
        if (last < 0) return produced
        return produced.mapIndexed { i, m -> if (i == last) m.copy(blocks = transform(m.blocks)) else m }
    }

    private fun appendTruncationMarker(blocks: List<ContentBlock>): List<ContentBlock> {
        val lastText = blocks.indexOfLast { it is ContentBlock.Text }
        if (lastText < 0) return blocks
        return blocks.mapIndexed { i, b ->
            if (i == lastText && b is ContentBlock.Text) {
                ContentBlock.Text(b.text + markers.truncationMarker)
            } else b
        }
    }

    /**
     * run 产出落库（P3 T2 双持久化钩子）：persistTransform 落库前变换（课堂拆条）
     * → 逐条 append（返回对齐行 id）→ onPersisted 落库后回调（familiar 切段）。
     * 返回实际落库的消息列表（regenerate 延迟删除判据：含 assistant 即删旧轮）。
     * 空产出不触发钩子。仅 run 产出走此路径——send 的 user 行/compact 摘要行不经钩子。
     * sender 盖章（P4 T1，runOnChain 通道）：非 null 时产出消息先盖 SenderInfo
     * （MessageRowMapper sender 三列；先于 persistTransform——拆条行同章）。
     *
     * 异常行为（fail loudly，均不吞；审查 Minor 5 钉死）：
     * - persistTransform 抛 → **零落库**（变换先于任何 append）+ 终态正常发布
     *   （finally 照常收线）+ 异常逃逸调用方（executeRun 不捕，直传五入口）；
     * - onPersisted 抛 → 行**已落库** + onRunEnd/titleTrigger **跳过**（后续
     *   编排不再执行）+ 终态正常发布 + 异常逃逸调用方——切段半程等状态由调用
     *   方（VM/T6）自行处置，本层不吞不错。
     */
    private suspend fun persistProduced(
        conversationId: String,
        produced: List<AgentMessage>,
        sender: SenderInfo? = null,
    ): List<AgentMessage> {
        val stamped = if (sender == null) produced else produced.map { it.copy(sender = sender) }
        val toPersist = persistTransform?.invoke(stamped) ?: stamped
        if (toPersist.isEmpty()) return toPersist
        val rowIds = toPersist.flatMap { store.append(conversationId, it) }
        onPersisted?.invoke(toPersist, rowIds)
        return toPersist
    }

    /** LoopOutcome → RunResult（模型原始产出，不含落库修饰标记；steeringLeftover 透传——hooks 闸①消费）。 */
    private fun assembleRunResult(outcome: LoopOutcome): RunResult {
        val finalAssistant = outcome.produced.lastOrNull { it.role == Role.ASSISTANT }
        return RunResult(
            finalText = finalAssistant?.blocks
                ?.filterIsInstance<ContentBlock.Text>()
                ?.joinToString("\n") { it.text } ?: "",
            blocks = finalAssistant?.blocks ?: emptyList(),
            usage = outcome.usage,
            turnCount = outcome.produced.mapNotNull { it.turnId }.distinct().size,
            truncated = outcome.truncated,
            cancelled = outcome.cancelled,
            steeringLeftover = outcome.steeringLeftover,
        )
    }

    // ---------- 状态流（90ms 节流 + 收线补发） ----------

    /**
     * loop 事件回调（P3 T3 扩为全量分派）：BlockStart 登记块类型 → BlockDelta
     * 按类型累计进 streamingText/streamingReasoning 载体，90ms 节流发射载体快照；
     * BlockEnd 注销登记。生命周期转换（run 起/收线）由 executeRun 直发不经节流。
     * StateFlow 同值合流 + 节流双保险——200 增量到达订阅者的不同 streamingText
     * 取值也至多次个位（节流窗内增量只累积不发射；终值由收线补发保证完整）。
     */
    private fun onStreamEvent(ev: StreamEvent, chain: RunChain) {
        when (ev) {
            is StreamEvent.BlockStart -> openBlockTypes[ev.blockId] = ev.type
            is StreamEvent.BlockEnd -> openBlockTypes.remove(ev.blockId)
            is StreamEvent.BlockDelta -> {
                when (openBlockTypes[ev.blockId]) {
                    // 正文累计保持原始增量（显示层过滤在发布点套用，P4 T7）
                    "text" -> streamText += ev.delta
                    "thinking" -> streamReasoning += ev.delta
                }   // toolCall 参数增量（ToolCallDelta）不经此路；未知类型丢弃
                val now = System.nanoTime()
                if (now - lastThrottleNanos >= THROTTLE_INTERVAL_MS * 1_000_000) {
                    lastThrottleNanos = now
                    publishStreaming(chain)
                }
            }
            else -> {}
        }
    }

    /** 流式正文显示层过滤（P4 T7，发布点派生）：无过滤器直通（既有会话零行为变化）。 */
    private fun applyTextFilter(rawAccumulated: String): String =
        streamingTextFilter?.invoke(rawAccumulated) ?: rawAccumulated

    /**
     * 工具进度回调（P3 T3，P1 悬空项闭合）：AgentLoop 贯通 registry.execute 的
     * onProgress 文本（如"正在搜索：xxx"）到 streamingToolStatus。低频事件
     * （每工具步一行）不走 BlockDelta 节流——直发；运行于工具执行协程
     * （chain.scope 调度器），此时收流已挂起，与 onStreamEvent 不并发。
     */
    private fun onToolProgress(text: String) {
        streamToolStatus = text
        publish { it.copy(streaming = true, streamingToolStatus = text) }
    }

    /** 节流窗打开时的载体快照发射（streaming=true 中间态；终态由 executeRun 收线直发）。 */
    private fun publishStreaming(chain: RunChain) {
        publish {
            it.copy(
                streaming = true, chainDepth = chain.context.depth,
                // streamingText 发布点套用显示层过滤（P4 T7）：对原始累计整体套用
                streamingText = applyTextFilter(streamText),
                streamingReasoning = streamReasoning,
                streamingToolStatus = streamToolStatus, streamingSender = streamSender,
            )
        }
    }

    /** 原子发布（审计 R4，任务 1.1）：read-modify-write 的竞态（丢更新）改 CAS 循环。 */
    private inline fun publish(transform: (SessionState) -> SessionState) {
        events.update { transform(it) }
    }

    private fun mintId(prefix: String): String = "$prefix-" + UUID.randomUUID()

    companion object {
        /** 流式状态发布节流间隔（spec §5.4：BlockDelta 发射统一 90ms 节流 + 收线补发）。 */
        const val THROTTLE_INTERVAL_MS = 90L
    }
}

/** executeRun 的收线产物：循环原始产出（runOnChain 返回位）+ 组装结果 + 本次实际落库消息（regenerate 延迟删除判据用）。 */
private class RunExecution(
    val outcome: LoopOutcome,
    val result: RunResult,
    val persisted: List<AgentMessage>,
)
