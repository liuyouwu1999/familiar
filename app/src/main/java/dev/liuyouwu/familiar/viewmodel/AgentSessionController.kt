package dev.liuyouwu.familiar.viewmodel

import android.app.Application
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import dev.liuyouwu.familiar.data.local.AppDatabase
import dev.liuyouwu.familiar.data.local.entity.ConversationEntity
import dev.liuyouwu.familiar.agent.AgentSession
import dev.liuyouwu.familiar.core.llm.model.AgentMessage
import dev.liuyouwu.familiar.core.llm.model.Attachment
import dev.liuyouwu.familiar.core.llm.model.ContentBlock
import dev.liuyouwu.familiar.core.llm.model.ReplyRef
import dev.liuyouwu.familiar.core.llm.model.Role
import dev.liuyouwu.familiar.core.llm.model.SenderInfo
import dev.liuyouwu.familiar.data.repository.ChatRepository
import dev.liuyouwu.familiar.data.repository.SettingsRepository
import dev.liuyouwu.familiar.pet.PetBondStore
import dev.liuyouwu.familiar.pet.PetSkinStore
import dev.liuyouwu.familiar.skills.QuickCommand
import dev.liuyouwu.familiar.skills.SkillRegistry
import dev.liuyouwu.familiar.tools.mcp.McpToolManager
import dev.liuyouwu.familiar.util.memory.FamiliarMemoryStore
import dev.liuyouwu.familiar.util.toUserLine
import dev.liuyouwu.familiar.viewmodel.skillhooks.RevisionGuard
import dev.liuyouwu.familiar.viewmodel.skillhooks.ToolGateKeeper

/**
 * 停止代数 + 当前链元数据（P5 T5 自 ChatViewModel 字段原样迁入）：原
 * ChatViewModel 的 `agentStopEpoch`/`agentChainMeta` 两个 @Volatile 字段——
 * 拆分后由 [AgentSessionController] 写（stopGeneration 自增/startAgentChain 置），
 * VoiceController 的三处 TTS/曝光桥读（epoch 门判"本链是否被停止/打断"），
 * 故装入共享 holder 按构造注入，读写语义与原字段逐位等价。
 */
internal class AgentChainGuard {
    /** 原 `agentStopEpoch`：stopGeneration 的新链路由自增（打断判别）。 */
    @Volatile var stopEpoch: Int = 0

    /** 原 `agentChainMeta`：当前新链的元数据（null = 无进行中的链）。 */
    @Volatile var chainMeta: AgentChainMeta? = null
}

/**
 * 当前新链的元数据（familiar 曝光 delays 的 userChars/elapsed 与 reveal 判定用）。
 * 自 ChatViewModel 纯移动拆出（P5 T5）；原 private 类改 internal 供桥读。
 */
internal class AgentChainMeta(
    val conversationId: Long,
    val skillId: String,
    val userChars: Int,
    val startedAt: Long,
    val epoch: Int,
)

/**
 * 桌宠会话状态快照（B1 Task 4）：挂载会话 SessionState → resolver 帧口的载荷
 * （[AgentSessionController.petSessionFrames] 发射，ChatViewModel 收集后转
 * PetHub.resolver.onSessionFrame）。lastError 已在映射侧做空串归 null
 * （空串≠错误——同帧 lastError 压倒 DONE，空串误发会把正常收束判成 ERROR）；
 * textLength 供调用侧维护 textChanged 基线（长度变化即正文恢复增长）。
 */
internal data class PetSessionSnapshot(
    val streaming: Boolean,
    val toolStatus: String?,
    val lastError: String?,
    val textLength: Int,
)

/**
 * 自 ChatViewModel 纯移动拆出（P5 T5）：Agent 新引擎运行时——会话挂载/事件桥/
 * T10 生产装配/五入口/startAgentChain 链编排/聊天室轮链。ChatViewModel 经构造
 * 注入依赖与 UI 状态回写通道（MutableStateFlow/ wakeLock/VoiceController 等），
 * 公开方法与 StateFlow 由 VM 原签名委托，行为与拆分前逐行等价。
 */
internal class AgentSessionController(
    private val scope: CoroutineScope,
    private val context: Application,
    private val db: AppDatabase,
    private val chatRepository: ChatRepository,
    private val settingsRepository: SettingsRepository,
    // 惰性数据源经供应商注入（VM 侧 by lazy 语义保持：不在控制器构造期触达）
    private val skillRegistryProvider: () -> SkillRegistry,
    private val memoryStoreProvider: () -> FamiliarMemoryStore,
    private val wakeLock: PowerManager.WakeLock,
    val chainGuard: AgentChainGuard,
    private val voice: VoiceController,
    private val skillIdOf: (ConversationEntity) -> String,
    // ===== UI 状态回写通道（自 ChatViewModel 构造注入）=====
    private val error: MutableStateFlow<String?>,
    private val isLoading: MutableStateFlow<Boolean>,
    private val streamingText: MutableStateFlow<String>,
    private val streamingReasoning: MutableStateFlow<String>,
    private val familiarTypingName: MutableStateFlow<String?>,
    private val tokenCount: MutableStateFlow<Int>,
    private val tokensPerSecond: MutableStateFlow<Double>,
    private val lastCacheHit: MutableStateFlow<Int?>,
    /** 收线后的真实上下文占用（lastUsage.prompt+completion，token）；0=本会话尚无已上报用量 */
    private val lastContextTokens: MutableStateFlow<Int>,
    private val replyingTo: MutableStateFlow<ReplyTarget?>,
    private val selectedAttachments: MutableStateFlow<List<AttachmentInfo>>,
    private val autoReadEnabled: StateFlow<Boolean>,
    private val searchStatus: MutableStateFlow<String?>,
    private val familiarReveal: MutableStateFlow<Pair<Long, Int>?>,
    /** 工具确认闸门（审计 S2）：ChatViewModel 持有共享实例，经此透传 SkillHost 装配。 */
    private val toolGate: ToolGateKeeper,
) {

    private val skillRegistry: SkillRegistry by lazy(skillRegistryProvider)
    private val memoryStore: FamiliarMemoryStore by lazy(memoryStoreProvider)

    // ===== Agent 新引擎运行时（Agent 内核 P3 T10） =====
    // 六类型的 run/回合编排协程：入口同步置位 [isLoading] 后由 [startAgentChain]
    // 启动，协程内驱动 AgentSession 入口（四类型）或 Orchestrator 回合（聊天室/
    // 圆桌，P4 T3）+ 等待整链收线（课堂续讲链/聊天室回合的后续 run 挂 chain.scope，
    // 非 本协程子代——send 返回 ≠ 链结束）+ 成功收线事件桥（TTS/桌宠/标题/容量）。
    // streamJob 仅剩 compactConversation 的挂载槽（与 agentJob 链经 isLoading 互斥；
    // 旧链兜底协程已随 T5 删除）。
    private var agentJob: Job? = null

    /** 正在跑链的会话（停止路由用：可能与挂载会话不同——后台生成中用户切走）。 */
    @Volatile private var runningSession: AgentSession? = null

    /**
     * 排队追问目标（P-steer，spec §4.1 生命周期）：非空 = 有已落库的 user 行待收线后
     * 续跑兜答。写点：queueFollowUp / sendMessage steer 路由的 Deferred；清点：
     * startAgentChain 收线桥消费块（含条件不满足的静默清）与 stopGeneration
     * （停止=终止一切排队意图）。
     */
    @Volatile private var pendingFollowUp: Long? = null

    /** 排队追问的 UI 镜像（[pendingFollowUp] 是裸 volatile 热路径旗标，UI 读不到）：
     *  queueFollowUp / steer Deferred 置位，收线消费、停止、显式取消清零。
     *  纯展示用，异步落后者可接受——置位仍以 volatile 的同步语义为准（终审 F-1）。 */
    private val _pendingFollowUpUi =
        kotlinx.coroutines.flow.MutableStateFlow<QueuedFollowUp?>(null)
    val pendingFollowUpUi: kotlinx.coroutines.flow.StateFlow<QueuedFollowUp?> = _pendingFollowUpUi

    /** 显式取消排队（用户点横幅「取消」）：只撤自动兜答意图，已落库的 user 行保留——与停止同语义。 */
    fun cancelFollowUp() {
        pendingFollowUp = null
        _pendingFollowUpUi.value = null
    }

    // ===== Agent 会话事件桥（Agent 内核 P3 T9 接缝） =====
    // T9 建三条 flatMapLatest 桥（工具进度/错误/容量）；T10 落地挂载与镜像桥：
    // [attachSession] 挂入 active 会话的 AgentSession 时同步启动事件镜像收集器
    // （streamingText/streamingReasoning→幽灵气泡数据源、familiar 打字指示、
    // lastError→[error]，整个收集在 Dispatchers.IO——P1 台账 #10），卸载即清镜像
    // （防后台会话的流式文本串台到新会话的幽灵气泡）。

    private val activeSession = MutableStateFlow<AgentSession?>(null)

    /** 挂载会话的事件镜像收集器（attachSession 启动/替换；卸载取消）。 */
    private var sessionMirrorJob: Job? = null

    /** 挂载会话所属会话 id（路由判定：sendMessage 等五入口按它分流新旧链）。 */
    @Volatile private var attachedConversationId: Long? = null

    /** 挂载会话的 skillId（镜像桥判 familiar 打字指示；null = 未挂载）。 */
    @Volatile private var attachedSkillId: String? = null

    /** 挂载会话的 SkillHost（快捷指令条单点数据源；T9 镜像的收敛）。 */
    @Volatile private var attachedHost: SkillHost? = null

    /**
     * 装配代数（P3 终审 Minor-5，P4 T8 落地）：[attachSessionFor] 每次调用自增，
     * 终态写入前核对——晚完成（IO 装配慢于后一次切换）的旧装配不得覆盖新会话
     * （latest-wins）。自增在调用方 Main 线程按发射序发生；写入在 IO 线程，与
     * 自增的唯一串行点是 [assemblyLock]（核对+写入持同一锁，check-then-act 原子）。
     */
    private val assemblyLock = Any()

    /** 当前最新装配代数（仅 [assemblyLock] 内读写）。 */
    private var assemblyGeneration = 0L

    /** compactConversation 的挂载槽（与 agentJob 链经 isLoading 互斥）。 */
    private var streamJob: Job? = null

    /**
     * 挂载 active 会话的 [AgentSession]（事件桥自此有源）；null = 卸载。
     * 挂载即热切：镜像收集器随会话重建，StateFlow 三桥（T9）自动换源。
     */
    fun attachSession(session: AgentSession?) {
        activeSession.value = session
        sessionMirrorJob?.cancel()
        // 挂载即按会话现值重置上下文占用（跨会话防串）；有 lastUsage 的会话
        // 同步回填（StateFlow 现值直读），空闲顶栏「上下文 N tokens」不丢
        lastContextTokens.value = 0
        if (session == null) {
            attachedConversationId = null
            attachedSkillId = null
            attachedHost = null
            // 卸载清镜像：后台旧会话仍可能在流式（事件不再被收集），残留文本会
            // 串进新会话的幽灵气泡
            streamingText.value = ""
            streamingReasoning.value = ""
            familiarTypingName.value = null
            return
        }
        session.events.value.lastUsage.takeIf { it.promptTokens > 0 }?.let { u ->
            lastContextTokens.value = u.promptTokens + u.completionTokens
        }
        sessionMirrorJob = scope.launch(Dispatchers.IO) {
            var firstEmission = true
            var wasStreaming = false
            var streamStartMs = 0L
            session.events.collect { st ->
                if (st.streaming && !wasStreaming) streamStartMs = System.currentTimeMillis()
                val settled = wasStreaming && !st.streaming
                wasStreaming = st.streaming
                streamingText.value = st.streamingText
                streamingReasoning.value = st.streamingReasoning
                familiarTypingName.value =
                    if (st.streaming && attachedSkillId == dev.liuyouwu.familiar.skills.TypeInferer.FAMILIAR) {
                        dev.liuyouwu.familiar.util.persona.DefaultPersonaCards
                            .personaOf(PetSkinStore.get(agentSettingsCache.petColor)).name
                    } else null
                // token 统计插值（修复轮 Important 2，旧链 :928-:1000 字符计数口径的
                // 等价物）：流式期以 streamingText+streamingReasoning 长度+流经时间驱动
                // 顶栏计数/速率——思考段也计入，Qwen 系先流 reasoning 的长段顶栏不再
                // 空白（2026-10-31 实测「t/s 时有时无」主因；UI 仅 >0 显示）；收线以
                // SessionState.lastUsage 精确值覆盖（后端未上报=0 时保留插值），并把
                // prompt+completion 落 lastContextTokens（空闲态顶栏上下文显示源）
                if (st.streaming) {
                    val count = st.streamingText.length + st.streamingReasoning.length
                    if (count > 0) {
                        tokenCount.value = count
                        val elapsed = (System.currentTimeMillis() - streamStartMs) / 1000.0
                        if (elapsed > 0.3) tokensPerSecond.value = count / elapsed
                    }
                } else if (settled) {
                    val usage = st.lastUsage
                    if (usage.completionTokens > 0) {
                        tokenCount.value = usage.completionTokens
                        val elapsed = (System.currentTimeMillis() - streamStartMs) / 1000.0
                        if (elapsed > 0.3) tokensPerSecond.value = usage.completionTokens / elapsed
                    }
                    if (usage.promptTokens > 0) {
                        lastContextTokens.value = usage.promptTokens + usage.completionTokens
                    }
                }
                // 错误并入 VM error 通道（T9 交接项）：下一 run 起点会话侧自清，
                // VM 侧由新一轮发送前统一清空（旧链同款时序）。首次发射跳过——
                // StateFlow 重挂即重放当前值，不跳会把终态旧错误在每次切回时重弹
                // Snackbar（修复轮 Minor 3）；在飞错误由 agentJob 收线处置不漏
                if (!firstEmission) st.lastError?.let { error.value = it }
                firstEmission = false
            }
        }
    }

    /**
     * 切会话吞帧（B1 Task 4②，审查 N3）：[attachSessionFor] 的三条切会话路径
     * （attach 成功 / 未知 skillId 卸载 / 装配失败回退）统一重置 resolver 的沿基线
     * （prevStreaming/prevToolStatus）——旧会话残帧与新会话回放帧一律按新基线判沿
     * （false 重放无沿不放假 DONE，true 重放恢复 THINKING，两方向都对）。
     * 调用点在 IO 装配锁块内，经 postResolver 收束主线程；与 petSessionFrames 的
     * null 哨兵（清调用侧 textLength 基线）分工不同、随 attach 同批生效。
     */
    private fun signalPetContextSwitch() {
        dev.liuyouwu.familiar.pet.PetHub.postResolver {
            dev.liuyouwu.familiar.pet.PetHub.resolver.onContextSwitch()
        }
    }

    // ---------------------------------------------------------------- T10 生产装配

    // 生产装配配料（设置快照/端口/LLM 客户端/store/聊天室组装构件/压缩摘要器）
    // 随 T5 拆入 [AgentEngineRuntime]（纯移动），此处同名只读委托——调用点零改动。
    internal val engine = AgentEngineRuntime(db, chatRepository, settingsRepository, context)
    private val agentSettingsCache get() = engine.agentSettingsCache
    private val agentSettingsPort get() = engine.agentSettingsPort
    private val agentRepoPort get() = engine.agentRepoPort
    private val agentHooksScope get() = engine.agentHooksScope
    private val agentLlm get() = engine.agentLlm
    private val agentStore get() = engine.agentStore
    private val chatRoomAssembler get() = engine.chatRoomAssembler
    private suspend fun compactSummarizer(historyText: String): String = engine.compactSummarizer(historyText)

    init {
        // ===== MCP 常驻收集链（T4）：设置流 → McpToolManager.refresh 的全量重发现 =====
        // 宿主 scope = 本控制器的 [scope]（Activity 级 ChatViewModel 的 viewModelScope，
        // AppNavigation 单例化后即 app 实际生命周期——onHostCleared 不额外收口，随 VM
        // 死亡自然取消）。DataStore 流收集起点即发射现值 → **启动首刷天然覆盖**（collect
        // 一次同时是常驻收集与冷启动首刷，无需另起一次显式 refresh）。
        scope.launch(Dispatchers.IO) {
            combine(settingsRepository.mcpServers, settingsRepository.mcpEnabled) { s, e -> s to e }
                // I-1（评审 Important 1）：两源是裸 dataStore.data.map——settings 为全 app
                // 共享 DataStore 文件，**任何 key** 写入（能力探测缓存、更新检查时间戳…）
                // 都重发两流。Pair 结构相等去重后，无关写入不再触发 refresh 全量重跑
                // （否则 N 台服务器反复 initialize+tools/list 网络重打）。
                .distinctUntilChanged()
                .collect { (servers, enabled) ->
                    // collect 内直调 suspend refresh：collect 背压保证多次发射**天然串行**
                    // （上一轮发现完成前下一个设置变更挂起排队）——禁再 launch：并发 refresh
                    // 完成序乱序会用陈旧配置的快照覆盖新快照。发现全程失败隔离在
                    // manager 侧（单台错误不外溢），此处不需 try。
                    McpToolManager.refresh(servers, enabled)
                }
        }
    }

    /**
     * 会话切换挂载（T10 切换口；P4 T3 起六类型全挂载）：注册表已知 skillId 经
     * [SkillHost.sessionFor] 装配并挂入事件桥（聊天室/圆桌的**回合**另经
     * [SkillHost.roundFor] 的 Orchestrator 装配，见 [runChatRoomRound]）；
     * 未知 skillId 卸载（无引擎）。每次切换新建 SkillHost——
     * titleTrigger/petTurnEnded 闭包随会话捕获 convId（后台链完成后标题/桌宠不串会话），
     * 与 T6"切换重建"的缓存语义一致。装配段整体在 IO：assets 首载（skillRegistry
     * 惰性初始化）不占主线程（T9 交接项）。
     *
     * 装配代数守卫（P3 终审 Minor-5，P4 T8）：调用序即代数序（自增在调用方 Main
     * 串行发射，见 [assemblyGeneration]）——快速切换 A→B 时 A 的 IO 装配可能晚于
     * B 完成，三处终态写入（挂载/两处卸载回退）前都核对代数，非最新即整体作废
     * （latest-wins），晚到装配不覆盖新会话（含不触发 attachSession(null) 的
     * 误卸载）。核对+写入持 [assemblyLock] 同锁，check-then-act 原子。
     */
    internal suspend fun attachSessionFor(conversation: ConversationEntity) {
        val gen = synchronized(assemblyLock) { ++assemblyGeneration }
        withContext(Dispatchers.IO) {
            // 装配判定与 SkillHost 构建整体在 IO：skillRegistry 首次触达要读 assets
            //（T9 交接项：assets 预载不占主线程）
            val skillId = skillIdOf(conversation)
            if (!isAgentSkillConversation(skillId)) {
                synchronized(assemblyLock) {
                    if (gen == assemblyGeneration) {
                        attachSession(null)
                        signalPetContextSwitch()
                    }
                }
                return@withContext
            }
            engine.refreshAgentSettingsCache()
            val convId = conversation.id
            val host = SkillHost(
                registry = skillRegistry,
                settings = agentSettingsPort,
                repo = agentRepoPort,
                memoryStore = memoryStore,
                store = agentStore,
                llm = agentLlm,
                summarizer = ::compactSummarizer,
                // 工具面 T6：ToolFactory 注入三件——文件沙箱根 = app 私有 agent_files
                // 目录（本构造点派生，禁回落 FamiliarApp.instance）+ 剪贴板/open_url
                // 的 appContext + http_get 基座 client（engine 暴露，agentLlm 同源）
                agentFilesRoot = File(context.filesDir, "agent_files"),
                appContext = engine.context,
                agentOkHttp = engine.agentOkHttp,
                // MCP 远端工具快照（T4 方案 A）：构造点取常驻收集链维护的现值（无锁读
                // @Volatile 快照）；新快照自下一次装配生效（会话缓存语义，SkillHost KDoc）
                mcpTools = McpToolManager.toolsForSkill(),
                // familiar 的桌宠"完成"归 hooks（成功 run 门，T6 语义）；B1 Task 4 起改名
                // petTurnEnded——resolver 收拢后本回调只承载回合级伴随打点。Task 7 落地：
                // object 单例直调（四轮 F3），非取消回合计相伴（取消门在 FamiliarHooks.onRunEnd）
                petTurnEnded = { scope.launch { PetBondStore.noteTurn() } },
                // familiar 曝光链（T6 裁定）：切段回调驱动 VM reveal 推进 + TTS（末段）
                onSegments = { rowId, segs ->
                    voice.onFamiliarSegments(rowId, segs, agentSettingsCache.familiarPacing)
                },
                // P4 T4：课堂拆条组逐行落库回调 → 按段触发 autoRead（组末行；桌宠"完成"
                // 已收拢 resolver 单路，B1）；
                // 聊天室/圆桌每角色 run 终态回调 → 逐角色朗读。闭包捕获 convId：
                // 后台段/角色收线不串会话（titleTrigger 同款）
                onSegmentPersisted = { turnId, index, total ->
                    voice.onClassroomSegmentPersisted(convId, turnId, index, total)
                },
                onRoleTurnEnded = { _, text -> voice.onChatRoomRoleTurnEnded(convId, text) },
                // P5 T2（评审 P1-4）：modeResolver = 纯缓存/目录读（无挂起/无网络）——
                // 覆盖>缓存快照>目录先验>UNKNOWN 折叠 MARKER；网络探测只在连接页
                // 后台预热（ConnectionViewModel.warmCapabilities），本供应商只读现成值
                modeResolver = {
                    AgentCapability.reader(context).mode(
                        agentSettingsCache.providerId,
                        agentSettingsCache.baseUrl,
                        agentSettingsCache.currentModel,
                    )
                },
                hooksScope = agentHooksScope,
                // B2-L4 familiar 相似度重试发起器：闭包捕获 convId（titleTrigger 同款——
                // 异步核验/重试不串会话），核验通过后才经 regenerateLastResponse 发起
                familiarReviseLauncher = { targetRowId -> launchFamiliarRevision(convId, targetRowId) },
                // 审计 S2：工具确认闸门（assembleSession 内 wrap 织入 beforeToolCall）
                toolGate = toolGate,
            )
            val session = try {
                host.sessionFor(conversation)
            } catch (e: IllegalArgumentException) {
                // 四类型无旧链可回（T13 已删）：卸载后按聊天室式未挂载路由——
                // 发送/编辑落"会话没准备好"报错壳
                Log.w("ChatViewModel", "agent session 装配失败，落未挂载报错壳: ${e.message}")
                synchronized(assemblyLock) {
                    if (gen == assemblyGeneration) {
                        attachSession(null)
                        signalPetContextSwitch()
                    }
                }
                return@withContext
            }
            synchronized(assemblyLock) {
                if (gen != assemblyGeneration) return@withContext   // 晚到装配：已有更新切换，整体作废
                attachedConversationId = convId
                attachedSkillId = skillId
                attachedHost = host
                attachSession(session)
                signalPetContextSwitch()
            }
        }
    }

    /** 注册表已知即 agent skill 会话（P4 T3 起含 chatroom/roundtable——与 SkillHost.isSkillConv 同口径；回合走 [runChatRoomRound]）。 */
    private fun isAgentSkillConversation(skillId: String): Boolean =
        skillRegistry.get(skillId) != null

    /**
     * 五入口的聊天室/圆桌路由判定（P4 T3）：挂载会话属本会话且 skillId 为
     * chatroom/roundtable——true 时 send/continue/edit 走 Orchestrator 回合
     * （[runChatRoomRound]），regenerate/compact 走公共入口拒绝守卫。判定源
     * [attachedSkillId]（挂载时快照；未挂载会话一律走"会话没准备好"报错壳，T5 起）。
     */
    private fun chatRoomRoundRoute(conversationId: Long): Boolean =
        attachedConversationId == conversationId &&
            (attachedSkillId == dev.liuyouwu.familiar.skills.TypeInferer.CHATROOM ||
                attachedSkillId == dev.liuyouwu.familiar.skills.TypeInferer.ROUNDTABLE)

    /** 挂载会话且属于本会话（未挂载/挂的是别的会话 = 旧链兜底路由）。 */
    private fun sessionIfAttached(conversationId: Long): AgentSession? =
        activeSession.value.takeIf { attachedConversationId == conversationId }

    /** 工具进度文案桥：SessionState.streamingToolStatus（"正在搜索：xxx"）；未挂载 = null。 */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val streamingToolStatus: StateFlow<String?> = activeSession
        .flatMapLatest { s -> if (s == null) flowOf(null) else s.events.map { it.streamingToolStatus } }
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * 流式发送者桥（P4 T4）：SessionState.streamingSender 限**流式中**暴露——聊天室/
     * 圆桌角色 run 的盖章位（runOnChain 通道非 null，四类型 run 恒 null）。ChatScreen
     * 据此给列表尾流式泡挂角色名/色徽章（旧链占位行 + 发送者徽章流式泡的恢复形态）。
     * streaming=false 窗口（回合间调度/收线间隙）归 null：终态快照保留的上一角色
     * sender 不得让幽灵泡在调度窗口复活（旧链该窗口同样无占位行）。
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val streamingSender: StateFlow<SenderInfo?> = activeSession
        .flatMapLatest { s ->
            if (s == null) flowOf(null)
            else s.events.map { st -> if (st.streaming) st.streamingSender else null }
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), null)

    /** 会话错误桥：SessionState.lastError（run 硬失败消息，下一 run 起点清除）；未挂载 = null。
     *  旧链错误走 [error]（T10 事件桥并流）。 */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val sessionLastError: StateFlow<String?> = activeSession
        .flatMapLatest { s -> if (s == null) flowOf(null) else s.events.map { it.lastError } }
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), null)

    /** 容量口径同源桥（评审 P0-1）：SessionState.contextBreakdown（[SessionBreakdown]
     *  五字段同形映射为 VM [ContextBreakdown]，used/free 派生同式）；未挂载 = null。
     *  六类型容量面板唯一数据源（旧链 contextBreakdown 流已随 P5 清理批删除）。 */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val sessionContextBreakdown: StateFlow<ContextBreakdown?> = activeSession
        .flatMapLatest { s -> if (s == null) flowOf(null) else s.events.map { it.contextBreakdown } }
        .map { sb ->
            sb?.let { ContextBreakdown(it.messages, it.attachments, it.systemPrompt, it.reserve, it.maxContext) }
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * 桌宠会话状态快照流（B1 Task 4①）：挂载会话 SessionState → [PetSessionSnapshot]，
     * ChatViewModel 唯一收集者（init），collect 转 PetHub.resolver.onSessionFrame
     * （沿触发/收束沿语义在 resolver，spec §3——本流只搬运帧）。
     * - **null 源不发帧**：卸载（attachSession(null)）发 null 哨兵——null 是"清调用侧
     *   textLength 基线"的信号而非帧；null 重放正是要杀的假收束沿（卸载后 residual
     *   的 false 终态会被旧基线判成收束沿补发假 DONE）。
     * - **每源首帧前置 null 哨兵**：A→B 热切同样重置基线（四轮 F6：跨会话重放帧
     *   textChanged 误真会抑制 SEARCHING 首沿）——attach 时清 textLength 基线。
     * - resolver 自身的沿基线（prevStreaming/prevToolStatus）不归本流管：由
     *   [attachSessionFor] 三处切会话路径的 onContextSwitch 显式重置。
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val petSessionFrames: Flow<PetSessionSnapshot?> = activeSession
        .flatMapLatest { s ->
            if (s == null) flowOf<PetSessionSnapshot?>(null)
            else flow {
                emit(null)   // attach 哨兵：清调用侧 textLength 基线，不转发帧
                emitAll(
                    s.events.map { st ->
                        PetSessionSnapshot(
                            streaming = st.streaming,
                            toolStatus = st.streamingToolStatus,
                            lastError = st.lastError?.takeIf { it.isNotBlank() },
                            textLength = st.streamingText.length,
                        )
                    }
                )
            }
        }

    /** 快捷指令条数据源（P3 T10 收敛单点）：挂载会话经 [SkillHost.quickCommands]，
     *  未挂载（聊天室/装配在途）回落注册表同式镜像（T9 实现，表达式等值）。 */
    fun quickCommandsFor(conversation: ConversationEntity): List<QuickCommand> =
        attachedHost?.takeIf { attachedConversationId == conversation.id }?.quickCommands(conversation)
            ?: skillRegistry.get(skillIdOf(conversation))?.ui?.quickCommands.orEmpty()

    // ===== Message sending =====

    fun sendMessage(conversationId: Long, text: String) {
        // P-steer 生成中路由（spec §4.1）：打断只属停止按钮/聊天室圆桌/错配/装配竞态兜底。
        // **chatRoomRoundRoute 必须先于 steer 判定显式打断**（评审 N-P0：外层条件排除会让
        // 聊天室生成中发送完全丢失 stopGeneration——新旧两链并发/跨会话孤儿链，违反 D3）
        if (isLoading.value) {
            if (chatRoomRoundRoute(conversationId)) {
                stopGeneration()   // 聊天室/圆桌：现状打断（D3 维持不动）
            } else {
                // 错配（评审 Important-1）= 无进行中的链（isLoading 且 runningSession==null
                // = 仅 compact 的 streamJob 窗口——落现状打断路由 streamJob.cancel，恢复
                // "compact 期间发送=打断 compact 立即回答"，排队 flag 不再悬挂无消费者）
                // 或后台链属他会话（chainMeta 记的是别的会话）
                val mismatch = runningSession == null ||
                    chainGuard.chainMeta?.conversationId != conversationId   // AgentChainMeta?.conversationId 是 Long
                val session = sessionIfAttached(conversationId)
                if (!mismatch && session != null) {
                    // steer 路由：user 行在 steer 内即时落库并注入本 run 下一回合；
                    // 不置位 isLoading（原 run 拥有它，不动现链的流式/忙态）。
                    // Deferred = 引擎队列未开（行已落库），pendingFollowUp 交收线桥兜答（零丢失）
                    val replyTarget0 = replyingTo.value
                    replyingTo.value = null
                    val attachments = selectedAttachments.value.toList()   // 先捕获后清空（launch 内读已清空值=空附件）
                    if (selectedAttachments.value.isNotEmpty()) selectedAttachments.value = emptyList()
                    // 同步先置位闭写侧竞态（终审 F-1）：pendingFollowUp 的写侧（本处/
                    // queueFollowUp）原在 scope.launch 内，与 IO 线程的收线消费读、Main
                    // 线程的 stopGeneration 清零无次序保证——发送落在 run 末毫秒窗时消费
                    // 读先读 null、flag 后到（孤儿 flag 幽灵续跑/跨会话静默清），发送后
                    // 立刻停止时 stop 先清、在途 steer 完成后复位（停止意图被违反）。
                    // 本调用在 Main（viewModelScope），同步置位后收线消费/停止清零与写侧
                    // 获得 happens-before（flag @Volatile）；Steered 同步清除的冗余续跑
                    // 是 spec §4.1 已接受项（零丢失优先于零冗余）。
                    val epochAtSend = chainGuard.stopEpoch
                    pendingFollowUp = conversationId
                    scope.launch {
                        // 旁路 launch 补捕获（审计 R1，任务 1.1）：steer 落库等失败
                        // 收口进 error 通道，不逃逸 viewModelScope；取消原样传播。
                        try {
                            // UI 附件/引用类型 → 内核类型，映射照抄现状 send 的 s.send 调用点
                            val coreAtts = attachments.mapNotNull(::toCoreAttachment)
                            val replyRef = replyTarget0?.let { ReplyRef(it.id, it.content.take(100), it.role) }
                            when (session.steer(conversationId.toString(), text, coreAtts, replyRef)) {
                                // Steered：消息已注入当前 run 下一回合，收线兜答不必——清 flag；
                                // 仅在未被停止清过（epoch 未进）且仍是本会话在位时清（被停止清零
                                // 即保持 null，不复活——停止语义优先；被收线消费/异会话改写亦不动）。
                                // UI 镜像同步清：插话成功不是排队，横幅不亮
                                dev.liuyouwu.familiar.agent.SteerOutcome.Steered -> {
                                    if (chainGuard.stopEpoch == epochAtSend && pendingFollowUp == conversationId) {
                                        pendingFollowUp = null
                                        _pendingFollowUpUi.value = null
                                    }
                                }
                                // Deferred：引擎队列未开（行已落库），flag 已在位——保持到收线
                                // 消费兜答（零丢失）；期间被停止清零即保持 null（停止语义优先）。
                                // 镜像按同样守卫置位（异步落后可接受，置位语义以 volatile 为准）
                                dev.liuyouwu.familiar.agent.SteerOutcome.Deferred -> {
                                    if (chainGuard.stopEpoch == epochAtSend && pendingFollowUp == conversationId) {
                                        _pendingFollowUpUi.value = QueuedFollowUp(conversationId, text)
                                    }
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error.value = e.message ?: "操作失败"
                        }
                    }
                    return
                }
                // 错配（无链=compact 窗口/后台链属他会话）/未挂载：现状打断路由——取消当前
                // 流（半截内容由收线 NonCancellable 保存），用户消息随后正常入库（静默丢弃
                // 会让用户的话凭空消失）
                stopGeneration()
            }
        }
        // 同步置位：堵住入口守卫与 DB 读取之间的竞态窗口（对照 compactConversation 的修法）
        isLoading.value = true
        val attachments = selectedAttachments.value.toList()
        // 三路由：聊天室/圆桌挂载会话 → Orchestrator 回合（user 行先入库，打断语义
        // 同款：上面的 stopGeneration 内部 cancel 旧 agentJob 的链 + session.stop）；
        // 四类型挂载 → AgentSession；未挂载（装配在途/装配失败竞态、未知 skillId）→
        // 报错壳（旧链兜底已随 T5 删除）
        if (chatRoomRoundRoute(conversationId)) {
            // 引用目标随行捕获（旧链在 doSendChatRoom 内消费后清空，此处等价同步消费）
            val replyTarget0 = replyingTo.value
            replyingTo.value = null
            // 附件即时清空：user 行由 appendRoomUserMessage 落库（入库失败=DB 已不可用，
            // 与四类型新链同口径）
            if (attachments.isNotEmpty()) selectedAttachments.value = emptyList()
            startAgentChain(conversationId, userChars = text.length) {
                error.value = null
                appendRoomUserMessage(conversationId, text, attachments, replyTarget0)
                runChatRoomRound(conversationId)
                true
            }
            return
        }
        val session = sessionIfAttached(conversationId)
        if (session != null) {
            // 引用目标随行捕获（旧链在 doSend 内消费后清空，此处等价同步消费）
            val replyTarget0 = replyingTo.value
            replyingTo.value = null
            // 附件即时清空：新链 user 行由 session.send 落库（入库失败=DB 已不可用，
            // 与旧链"入库成功后再清"的差别仅剩极端故障窗口）
            if (attachments.isNotEmpty()) selectedAttachments.value = emptyList()
            startAgentChain(conversationId, userChars = text.length) { s ->
                error.value = null
                s.send(
                    conversationId.toString(), text,
                    attachments = attachments.mapNotNull(::toCoreAttachment),
                    replyTo = replyTarget0?.let { ReplyRef(it.id, it.content.take(100), it.role) },
                )
                true
            }
            return
        }
        // 报错壳：无旧链可走（T5 删）——附件保留供重试
        error.value = "会话没准备好：请退出会话后重进再试"
        isLoading.value = false
    }

    /**
     * 排队追问（spec §4.2）：生成中长按发送——user 行即时落库，收线后自动续跑。
     * 空闲/聊天室圆桌/错配（无链=compact 窗口，评审 Important-1；或后台链属他会话）/
     * 未挂载降级普通 sendMessage（空闲时长按=普通发送，行为一致）。
     */
    fun queueFollowUp(conversationId: Long, text: String) {
        if (!isLoading.value || chatRoomRoundRoute(conversationId)) { sendMessage(conversationId, text); return }
        val mismatch = runningSession == null ||
            chainGuard.chainMeta?.conversationId != conversationId
        val session = sessionIfAttached(conversationId)
        if (mismatch || session == null) { sendMessage(conversationId, text); return }
        val replyTarget0 = replyingTo.value
        replyingTo.value = null
        val attachments = selectedAttachments.value.toList()
        if (selectedAttachments.value.isNotEmpty()) selectedAttachments.value = emptyList()
        // 同步先置位闭写侧竞态（终审 F-1）：与 steer 路由同款——flag 置位提到 launch 前
        // （persistUser 仍在 launch 内），收线消费（IO 读）/停止清零（Main stopGeneration）
        // 与写侧获得 happens-before；期间被停止清零 → 行随后落库成孤儿（少兜答不丢行，
        // 停止语义优先）。此处无 Steered 清除分支：persistUser 无注入语义，flag 保持到收线。
        pendingFollowUp = conversationId
        _pendingFollowUpUi.value = QueuedFollowUp(conversationId, text)
        // 旁路 launch 补捕获（审计 R1，任务 1.1）：排队落库失败收口进 error 通道
        scope.launch {
            try {
                val coreAtts = attachments.mapNotNull(::toCoreAttachment)
                val replyRef = replyTarget0?.let { ReplyRef(it.id, it.content.take(100), it.role) }
                session.persistUser(conversationId.toString(), text, coreAtts, replyRef)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = e.message ?: "操作失败"
            }
        }
    }

    /**
     * 聊天室回合的 user 行入库（P4 T3）：RoomMessageStore 扇出契约（多附件首行带正文
     * /其余行仅附件列，同四类型 send）；replyTo 三列随行。替代旧链 doSendChatRoom 的
     * 分类型多行插法（视频/文件行各带全文的旧列习惯不再保留——新引擎 1 行 1 附件同
     * turnId，读侧合并）。
     */
    private suspend fun appendRoomUserMessage(
        conversationId: Long,
        text: String,
        attachments: List<AttachmentInfo>,
        replyTarget: ReplyTarget?,
    ) {
        agentStore.append(
            conversationId.toString(),
            AgentMessage(
                id = "u-" + java.util.UUID.randomUUID(),
                role = Role.USER,
                blocks = listOf(ContentBlock.Text(text)),
                attachments = attachments.mapNotNull(::toCoreAttachment),
                replyTo = replyTarget?.let { ReplyRef(it.id, it.content.take(100), it.role) },
            ),
        )
    }

    /**
     * 聊天室/圆桌一回合（P4 T3，spec §5.3 Orchestrator 接线）：[SkillHost.roundFor]
     * 装配（session/Orchestrator/角色/scheduler/锚点/回合级标题，成员与设置每回合
     * 新鲜解析——成员编辑即回合生效）→ [MultiAgentOrchestrator.runRound]（回合请求
     * 历史=截断后含本回合 user 行的库史，store.list 同口径）→ 回合收线触发标题。
     * 流式/落库/取消贯通全走 runOnChain 既有机制（SessionState 事件桥照常驱动 UI）。
     */
    private suspend fun runChatRoomRound(conversationId: Long) {
        val conversation = db.conversationDao().getConversationById(conversationId) ?: return
        val host = attachedHost?.takeIf { attachedConversationId == conversationId } ?: return
        val round = host.roundFor(conversation)
        if (round.roles.isEmpty()) {
            error.value = "聊天室没有成员：先加角色"
            return
        }
        round.orchestrator.runRound(
            conversationId = conversationId.toString(),
            input = agentStore.list(conversationId.toString()),
            roles = round.roles,
            scheduler = round.scheduler,
            emit = { },   // 流式已由 SessionState 发布（事件桥镜像到 UI），调用方汇无需追加
        )
        round.roundTitleTrigger()
    }

    /** VM 附件（已解码负载）→ core/llm Attachment（上 wire/落库由引擎侧映射）。 */
    private fun toCoreAttachment(a: AttachmentInfo): Attachment? = when (a.type) {
        AttachmentType.IMAGE -> a.base64?.let { Attachment.Image(it) }
        AttachmentType.FILE -> Attachment.File(a.displayName, a.fileTextContent ?: "")
        AttachmentType.VIDEO -> a.videoFrames?.let { Attachment.VideoFrames(it) }
    }

    /**
     * 新链 run 编排（T10 五入口公共段）：协程内「刷新设置快照 → action（入口 API）→
     * 等整链收线 → 成功事件桥」；整体 Dispatchers.IO（P1 台账 #10）。
     *
     * - **等链收线**：session.send 返回时仅首 run 结束（classroom 续讲链后续 run 挂
     *   chain.scope，非本协程子代），读 events 至 streaming=false（followUpPending
     *   期间恒 true，终态由链上最后 run 统一发布）——isLoading/停止键全程覆盖整链。
     * - **成功事件桥**（未被停止且无 lastError）：仅 chat/eli5 触发非 familiar TTS
     *   （familiar 归 hooks onTurnEnded+reveal 链、classroom 归拆条组逐段回调、
     *   chatroom/roundtable 归每角色 run 终态回调——P4 T4 起四类各有其桥，防双触发；
     *   B1 起桌宠完成收拢 resolver 单路，本桥不再直调 PetHub）
     *   + 容量重算（标题生成已迁 SkillHost）。
     * - action 返回 false = 入口守卫拦下（无回复可重生成等），跳过事件桥。
     */
    private fun startAgentChain(
        conversationId: Long,
        userChars: Int,
        action: suspend (AgentSession) -> Boolean,
    ) {
        val session = sessionIfAttached(conversationId) ?: return
        val epochAtStart = chainGuard.stopEpoch
        val skillId = attachedSkillId ?: ""
        chainGuard.chainMeta = AgentChainMeta(conversationId, skillId, userChars, System.currentTimeMillis(), epochAtStart)
        runningSession = session
        agentJob = scope.launch(Dispatchers.IO) {
            try {
                // 唤醒锁上限按 skill 类型放宽（P4 终审 Minor-5 预防性修正）：聊天室/
                // 圆桌一回合最多六个角色 run（各含搜索），10 分钟上限可能中途到期、
                // 屏幕熄灭卡流——回合类型放宽到 30 分钟；其余单 run 链维持 10 分钟。
                // 到期只是保活上限，链完成/停止照常走 finally 即时释放，不等到期。
                val wakeLockTimeoutMs =
                    if (skillId == dev.liuyouwu.familiar.skills.TypeInferer.CHATROOM ||
                        skillId == dev.liuyouwu.familiar.skills.TypeInferer.ROUNDTABLE
                    ) 30 * 60 * 1000L else 10 * 60 * 1000L
                wakeLock.acquire(wakeLockTimeoutMs)
                engine.refreshAgentSettingsCache()
                // B2-L1：会话采样参数每链刷新（在设置快照之后、action 之前——
                // 技能管理页/会话参数改动自下一条消息（新链）生效，spec §3.4）
                engine.refreshSamplingCache(conversationId, skillRegistry)
                tokenCount.value = 0
                tokensPerSecond.value = 0.0
                // 新链无 SSE Usage 事件桥（SessionState.lastUsage 不区分"未上报"与 0），
                // 命中率面板清 null 防旧链残留值跨链显示（修复轮 Minor 7 一并）
                lastCacheHit.value = null
                if (!action(session)) return@launch
                val final = session.events.first { !it.streaming }
                if (chainGuard.stopEpoch != epochAtStart) return@launch   // 本链被停止/打断
                // 背景收束旁路（B1 Task 4②）：链属后台会话（挂载已切走）时帧流不再收集
                // 本会话，收束沿永不到达 resolver——桌宠卡 THINKING/SEARCHING（该两态豁免
                // 4s 沉降）。仅后台链送静默收束（不在场不庆祝）；前台链由 petSessionFrames
                // 帧流判收束沿，双发防住。置于停止代数检查之后：被打断的链归 stopGeneration
                // 的 onCancelled 管，此处补发会把新链刚起的 THINKING 静默抹掉。
                // （conversationId 即本链 chainMeta 的会话 id——读入口参数而非
                //  chainGuard.chainMeta，后者可能已被新链覆盖；能走到这里说明本链未被
                //  停止打断，runningSession 恒非空，判空只是防御。）
                if (runningSession != null && attachedConversationId != conversationId) {
                    dev.liuyouwu.familiar.pet.PetHub.postResolver {
                        dev.liuyouwu.familiar.pet.PetHub.resolver.onChainSettledBackground()
                    }
                }
                var consumedFollowUp = false
                // P-steer 排队消费（spec §4.1）：epoch 检查之后——停止后续跑=违背停止意图。
                // 已被后续 run 顺带覆盖也仍续跑（零丢失优先于零冗余）。
                // 历史守卫（评审 P1-8②）：clearConversation/compact 后无 user 行→静默清
                //（continueRun 对空历史空跑）
                val followTarget = pendingFollowUp
                pendingFollowUp = null
                _pendingFollowUpUi.value = null   // 横幅随消费/静默清同灭（消费后进入兜答 run，无需再显示排队态）
                // Minor-3：会话比对短路在前——异会话链收线不对无关会话做 Room 读
                val hasQueuedUserRow = followTarget != null && followTarget == conversationId && runCatching {
                    chatRepository.getMessagesForConversation(conversationId).first().any { it.role == "user" }
                }.getOrDefault(false)
                if (followTarget != null && final.lastError == null &&
                    followTarget == conversationId && sessionIfAttached(followTarget) != null && hasQueuedUserRow
                ) {
                    voice.cancelRevealAnimation()   // 旧答案曝光/朗读不掐会与新 run 并行开口（spec §6 代价已登记）
                    consumedFollowUp = true
                    isLoading.value = true
                    startAgentChain(followTarget, userChars = 0) { s ->
                        s.continueRun(followTarget.toString())
                        true
                    }
                }
                // followTarget 非空但 error/不匹配/未挂载/无历史：静默清（禁全局 error 壳打进
                // 别的会话；行内气泡标记裁决为不落 spec §4.1 承诺——行本身可见于历史，另立批次）
                val lastError = final.lastError
                if (lastError != null) {
                    error.value = lastError
                } else {
                    // 链级成功桥只余 chat/eli5 的 TTS：classroom 归拆条组逐段回调、
                    // chatroom/roundtable 归每角色 run 终态回调（P4 T4，均已在链中触发——
                    // 此处再触发即双朗读）；familiar 归 hooks onTurnEnded+reveal 链。
                    // 桌宠"完成"已收拢 resolver 单路（B1 Task 4③）：DONE 由帧流收束沿判，
                    // 本桥不再直调 PetHub
                    if (skillId != dev.liuyouwu.familiar.skills.TypeInferer.FAMILIAR &&
                        skillId != dev.liuyouwu.familiar.skills.TypeInferer.CLASSROOM &&
                        skillId != dev.liuyouwu.familiar.skills.TypeInferer.CHATROOM &&
                        skillId != dev.liuyouwu.familiar.skills.TypeInferer.ROUNDTABLE
                    ) {
                        // P2-4：消费已起新链，旧答案不再自动朗读（与 cancelRevealAnimation
                        // 动机对齐；familiar/chat·eli5 口径统一——排除表内 skillId 同门）
                        if (!consumedFollowUp && autoReadEnabled.value) voice.speakLastAssistant(conversationId)
                    }
                    // 标题生成已随 P4 T2 迁 SkillHost（六类型统一 titleTrigger，聊天室/
                    // 圆桌按回合粒度）；容量经 SessionState.contextBreakdown 收线发布
                    //（sessionContextBreakdown 桥）——VM 旧 updateContextTokenUsage 已随 T5 删除
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = dev.liuyouwu.familiar.util.ApiErrorHint.fromThrowable(e)
            } finally {
                // 停止权移交守卫（修复轮 Important 1）：**协程身份**判等，非 session
                // 对象判等——同会话打断时新旧链共享同一 AgentSession（sessionFor 缓存），
                // 对象判等会让被打断的旧协程 finally 把在跑链的 runningSession 误清
                // null（停止键失效/onCleared 孤立链/停止不 bump epoch 致成功桥接误触发）。
                // 与 resetAgentStreamState 的身份守卫同款：只有"仍是当前 agentJob"的
                // 收线才清停止权。
                val currentJob = kotlin.coroutines.coroutineContext[Job]
                if (agentJob === currentJob) runningSession = null
                resetAgentStreamState()
            }
        }
    }

    /**
     * B2-L4 相似度重试（spec §6.2，二轮收口三段式）。在 hooksScope 之外跑本方法：
     * ① 等待谓词 = isLoading==false（不是 streaming——streaming 在 executeRun finally
     *   发布（AgentSession.kt:519），isLoading 在 agentJob finally 才复位（:576），
     *   谓词错位会被 regenerateLastResponse 入口守卫 :627 静默丢弃）；
     * ② 核验 = 挂载未变 + 历史末行仍是目标行（行 id 比对——文本比对防不住"追加 user
     *   行"的竞争，评审二轮 N3）；任一失败静默放弃（切会话的报错壳不得打进新会话）；
     * ③ 核验通过才 markRevisionLaunched（置旗标+熔断计数）并 regenerateLastResponse。
     * 窗口 <1s 量级；窗口内点停止不拦截已排程 revise（note 级接受，spec §6.3）。
     */
    private fun launchFamiliarRevision(conversationId: Long, targetRowId: Long) {
        scope.launch(Dispatchers.IO) {
            // ① 等待谓词 = isLoading==false（守卫 :627 的释放点；streaming=false 早于它，
            //    用 streaming 做谓词会在窗口内被守卫静默丢弃——二轮 N1）
            withTimeoutOrNull(5_000) { isLoading.first { !it } } ?: return@launch
            // ② 挂载校验（二轮 N3）：切会话后 regenerateLastResponse 的报错壳会打进新会话
            if (attachedConversationId != conversationId) return@launch
            // ③ 目标核验：末行仍是目标 assistant 行（行 id 比对——用户新输入只追加行）；
            //    Room 挂起读与 ④ 之间再复查挂载（复审 N9：窗口内切会话会把旗标打进新会话 hooks）
            val rows = chatRepository.getMessagesForConversation(conversationId).first()
            if (!RevisionGuard.shouldLaunch(
                    attached = attachedConversationId == conversationId,
                    lastRowId = rows.lastOrNull()?.id, targetRowId = targetRowId,
                )
            ) return@launch
            // ④ 核验全过才置旗标（熔断计数在 onRunEnd 触发点——先置后弃会泄漏陈旧指令，二轮 N2），再发起
            val hooks = attachedHost?.familiarHooksOrNull() ?: return@launch
            hooks.markRevisionLaunched()
            regenerateLastResponse(conversationId)
        }
    }

    /**
     * 新链收线复位（身份守卫语义同旧链 [resetStreamState] 的 streamJob 守卫）：
     * 打断即发场景旧协程的 finally 晚于新链的同步置位，只复位"仍是当前 agentJob"
     * 的收线，否则新一轮生成的忙态/流式状态被旧协程整包清掉。
     * _familiarReveal 不在此清：曝光动画（revealJob）独立于 agentJob 生命周期，
     * 停止/新链由 revealJob 的 invokeOnCompletion 收口。
     */
    private suspend fun resetAgentStreamState() {
        val currentJob = kotlin.coroutines.coroutineContext[Job]
        if (agentJob != null && agentJob !== currentJob) return
        if (wakeLock.isHeld) wakeLock.release()
        streamingText.value = ""
        streamingReasoning.value = ""
        isLoading.value = false
        familiarTypingName.value = null
    }

    private suspend fun resetStreamState() {
        // 打断即发场景：旧协程的 finally 会晚于新请求的同步置位才跑到这里。
        // 只复位"仍是当前 streamJob"的收线（引用被清空=无新请求，照常复位），
        // 否则新一轮生成的忙态/流式状态会被旧协程整包清掉——
        // 表现为输入框不锁、停止键消失、可并发第二条流
        val currentJob = kotlin.coroutines.coroutineContext[Job]
        if (streamJob != null && streamJob !== currentJob) return
        searchStatus.value = null
        streamingText.value = ""
        streamingReasoning.value = ""
        isLoading.value = false
        lastCacheHit.value = null
        // familiar 拟人节奏：打字指示与曝光态一并复位（reveal 置 null = 全曝光，
        // 与重进会话/进程重建的默认一致）
        familiarTypingName.value = null
        familiarReveal.value = null
        if (wakeLock.isHeld) wakeLock.release()
    }

    fun stopGeneration() {
        // T10 双路由：新链（runningSession 所在链，可能是切走会话的后台链）——
        // session.stop 取消整链（run/工具/续讲 gap 全停，已产出照常落库=旧链
        // NonCancellable 半截保存的等价），收线事件桥经停止代数比对跳过；
        // familiar 曝光动画同停（旧链由 streamJob 取消连带）。
        // 停止=终止一切排队意图（spec §4.1 生命周期表；Minor-2：提出 let 外——compact 的
        // streamJob 停止同样终结排队意图）
        pendingFollowUp = null
        _pendingFollowUpUi.value = null
        runningSession?.let {
            chainGuard.stopEpoch++
            it.stop()
        }
        // 取消静默回 IDLE（B1 Task 4⑤）：她不知道你为什么停，不庆祝。帧流的终态帧兜底
        // 与此幂等（两口均重置沿基线，迟到的 streaming=false 终态不再补收束沿"补庆祝"，
        // 审查 #7）；compact 的 streamJob 取消路径不经会话帧流，也由这次调用兜住
        dev.liuyouwu.familiar.pet.PetHub.postResolver {
            dev.liuyouwu.familiar.pet.PetHub.resolver.onCancelled()
        }
        voice.cancelRevealAnimation()
        // streamJob 槽（compactConversation）：不清引用——resetStreamState 靠"=== 当前 job"
        // 身份守卫决定是否复位，这里清空反而让纯停止路径的收线被误判成"过期"而不复位
        // （输入框永远锁死）
        streamJob?.cancel()
    }

    /**
     * 重新生成最后一条回复。
     *
     * 三路由：**聊天室拒绝守卫前置到公共入口**（评审 P2-4：聊天室一批是多条
     * 消息，只删最后一条整批重发会内容重复——isChatRoom 形态直接置 error，不截断
     * 不动历史）；四类型挂载会话 → session.regenerateLastTurn（延迟删除语义在引擎侧）；
     * 未挂载（装配在途/装配失败竞态、未知 skillId）→ 报错壳（旧链变体已随 T5 删）。
     *
     * @param modelOverride 非空时本次请求改用该模型（"换模型重试"），仅影响本次请求，
     * 不写回全局模型设置；UI 层把用户在对话框里选的模型传进来即可
     */
    fun regenerateLastResponse(conversationId: Long, modelOverride: String? = null) {
        if (isLoading.value) return
        if (chatRoomRoundRoute(conversationId)) {
            error.value = "聊天室暂不支持重新生成"
            return
        }
        // 同步置位：堵住入口守卫与 DB 读取之间的竞态窗口（对照 compactConversation 的修法）
        isLoading.value = true
        if (sessionIfAttached(conversationId) != null) {
            startAgentChain(conversationId, userChars = 0) { s ->
                val messages = chatRepository.getMessagesForConversation(conversationId).first()
                if (messages.none { it.role == "assistant" }) {
                    error.value = "没有可重新生成的回复"
                    return@startAgentChain false
                }
                if (messages.none { it.role == "user" }) {
                    error.value = "会话已压缩，没有可重新生成的提问"
                    return@startAgentChain false
                }
                error.value = null
                s.regenerateLastTurn(conversationId.toString(), modelOverride)
                true
            }
            return
        }
        // 报错壳：无旧链可走（T5 删）
        error.value = "会话没准备好：请退出会话后重进再试"
        isLoading.value = false
    }

    fun continueGeneration(conversationId: Long) {
        if (isLoading.value) return
        // 同步置位：堵住入口守卫与 DB 读取之间的竞态窗口（对照 compactConversation 的修法）
        isLoading.value = true
        // 三路由：聊天室/圆桌挂载会话 → 以"请继续"触发下一回合（user 行先入库避免
        // 双插，旧链同语义）；四类型挂载 → session.continueRun（引擎侧剥 UI 标记后以
        // 现有历史续跑）；未挂载 → 报错壳（旧链变体已随 T5 删）
        if (chatRoomRoundRoute(conversationId)) {
            startAgentChain(conversationId, userChars = 0) {
                val history = agentStore.list(conversationId.toString())
                if (history.none { it.role == Role.ASSISTANT }) return@startAgentChain false
                error.value = null
                appendRoomUserMessage(conversationId, "请继续", emptyList(), null)
                runChatRoomRound(conversationId)
                true
            }
            return
        }
        if (sessionIfAttached(conversationId) != null) {
            startAgentChain(conversationId, userChars = 0) { s ->
                val messages = chatRepository.getMessagesForConversation(conversationId).first()
                if (messages.none { it.role == "assistant" }) return@startAgentChain false
                error.value = null
                s.continueRun(conversationId.toString())
                true
            }
            return
        }
        // 报错壳：无旧链可走（T5 删）
        error.value = "会话没准备好：请退出会话后重进再试"
        isLoading.value = false
    }

    fun editMessageAndTruncate(conversationId: Long, messageId: Long, newContent: String) {
        if (isLoading.value) return
        // 同步置位：堵住入口守卫与 DB 读取之间的竞态窗口（对照 compactConversation 的修法）
        isLoading.value = true
        // 三路由：聊天室/圆桌挂载会话 → store.replaceRange 截断替入新 user 行 +
        // Orchestrator 回合（spec §5.4：P4 后聊天室编辑走 replaceRange 通道；替代旧链
        // "deleteMessagesAfter+updateContent 截断事务 + 旧链重跑"——content 列单写改
        // 不动新引擎行的 blocks 权威列，必须走行映射写侧）；四类型挂载 →
        // session.editAndResend（引擎侧 replaceRange 同机制）；未挂载 → 报错壳
        // （旧链变体已随 T5 删——不可挂载直接报错，原对话一字不动）
        if (chatRoomRoundRoute(conversationId)) {
            startAgentChain(conversationId, userChars = newContent.length) {
                error.value = null
                agentStore.replaceRange(
                    conversationId.toString(),
                    messageId.toString(),
                    listOf(
                        AgentMessage(
                            id = "u-" + java.util.UUID.randomUUID(),
                            role = Role.USER,
                            blocks = listOf(ContentBlock.Text(newContent)),
                        )
                    ),
                )
                // 旧链编辑事务带 touch（列表排序刷新）；replaceRange 的写路径不含
                // touch，此处补齐等价（四类型路径同款）
                db.conversationDao().touch(conversationId)
                runChatRoomRound(conversationId)
                true
            }
            return
        }
        // 双路由：四类型挂载会话 → session.editAndResend（引擎侧 replaceRange：
        // 从被编辑行起整段截断替入新 user 行后重跑）；未挂载 → 报错壳（T5 起前置
        // 判定——不截断，原对话一字不动）
        if (sessionIfAttached(conversationId) != null) {
            startAgentChain(conversationId, userChars = newContent.length) { s ->
                error.value = null
                s.editAndResend(conversationId.toString(), messageId.toString(), newContent)
                // 旧链编辑事务带 touch（列表排序刷新）；replaceRange 的写路径不含
                // touch，此处补齐等价
                db.conversationDao().touch(conversationId)
                true
            }
            return
        }
        // 报错壳：无旧链可走（T5 删）——不截断，历史一字不动
        error.value = "会话没准备好：请退出会话后重进再试"
        isLoading.value = false
    }

    fun compactConversation(conversationId: Long) {
        if (isLoading.value) return
        // 聊天室拒绝守卫（P4 T3 前置到公共入口，语义照抄旧链 compactConversationLegacyChatRoom）：
        // 多角色发言史压成单条摘要会丢 sender 盖章与回合结构——置 error 不动历史；
        // 旧变体随守卫失去调用方（T5 删本体）
        if (chatRoomRoundRoute(conversationId)) {
            error.value = "聊天室不支持压缩对话"
            return
        }
        // 同步置位：堵住入口守卫与 DB 读取之间的竞态窗口（期间发消息会被误删）
        isLoading.value = true
        // T10 双路由：四类型挂载会话 → session.compact（summarizer=loop 单 turn 摘要请求，
        // 指令文案照抄旧体，见 [compactSummarizer]；摘要行替换在引擎侧事务完成）；
        // 未挂载 → 报错壳（旧链原体已随 T5 删）。压缩后的容量面板随下次 run 收线
        // 刷新（SessionState 口径）；旧 updateContextTokenUsage 即时重算已删。
        if (sessionIfAttached(conversationId) != null) {
            // 挂 streamJob 槽（与 agentJob 链经 isLoading 互斥）：stopGeneration 可取消，
            // finally 的身份守卫（=== 当前 job）可过——不复刻旧体"未挂槽致复位被
            // 陈旧 streamJob 引用挡下"的缺陷
            streamJob = scope.launch(Dispatchers.IO) {
                try {
                    val messages = chatRepository.getMessagesForConversation(conversationId).first()
                    if (messages.size <= 2) {
                        // 不再静默：告知用户为何没有压缩（加载态由 finally 的 resetStreamState 复位）
                        error.value = "对话消息较少，无需压缩"
                        return@launch
                    }
                    error.value = null
                    val session = sessionIfAttached(conversationId) ?: return@launch
                    session.compact(conversationId.toString())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error.value = "压缩失败：${e.toUserLine()}（原对话未改动）"
                } finally {
                    resetStreamState()
                }
            }
            return
        }
        // 报错壳：无旧链可走（T5 删）
        error.value = "会话没准备好：请退出会话后重进再试"
        isLoading.value = false
    }

    /** 宿主（ChatViewModel）销毁收口：原 onCleared 的新链段（顺序保持）。 */
    fun onHostCleared() {
        if (wakeLock.isHeld) wakeLock.release()
        streamJob?.cancel()
        // 新链（T10）：先停链（续讲 gap/工具协程随 chain.scope 取消）再收编排协程；
        // hooks 宿主 scope 一并收口（切段/TS 落列的尾写在 VM 死亡后不再有意义）
        runningSession?.stop()
        // VM 死亡路径（审查 N2）：不经 stopGeneration 且帧流随 viewModelScope 死亡——
        // 链的终态帧永不到达 resolver；不补这行 THINKING/SEARCHING 又豁免 4s 沉降，
        // 桌宠会全局卡思考态到下一次 run（新引入回归）
        dev.liuyouwu.familiar.pet.PetHub.postResolver {
            dev.liuyouwu.familiar.pet.PetHub.resolver.onCancelled()
        }
        agentJob?.cancel()
        agentHooksScope.cancel()
        sessionMirrorJob?.cancel()
    }
}

/** 排队中的追问（会话 id + 原文，UI 横幅显示与取消用）。文件级：宿主类 internal，
 *  ChatViewModel 要公开透出此类型（P-steer UI 面，2026-10-31「排队看不到」反馈）。 */
data class QueuedFollowUp(val conversationId: Long, val text: String)
