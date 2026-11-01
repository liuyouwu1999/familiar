package dev.liuyouwu.familiar.viewmodel

import dev.liuyouwu.familiar.agent.AgentHooks
import dev.liuyouwu.familiar.agent.AgentLoop
import dev.liuyouwu.familiar.agent.AgentSession
import dev.liuyouwu.familiar.agent.ContextBuilder
import dev.liuyouwu.familiar.agent.MessageStore
import dev.liuyouwu.familiar.agent.MultiAgentOrchestrator
import dev.liuyouwu.familiar.agent.RunChain
import dev.liuyouwu.familiar.agent.RunMarkers
import dev.liuyouwu.familiar.agent.RunResult
import dev.liuyouwu.familiar.core.llm.client.LlmClient
import dev.liuyouwu.familiar.core.llm.client.LlmRequest
import dev.liuyouwu.familiar.core.llm.model.AgentMessage
import dev.liuyouwu.familiar.core.llm.model.ContentBlock
import dev.liuyouwu.familiar.core.llm.model.Role
import dev.liuyouwu.familiar.core.llm.model.StreamEvent
import dev.liuyouwu.familiar.core.llm.wire.ToolMode
import dev.liuyouwu.familiar.core.llm.wire.ToolSpec
import dev.liuyouwu.familiar.data.local.entity.ConversationEntity
import dev.liuyouwu.familiar.data.local.entity.LearningProfileEntryEntity
import dev.liuyouwu.familiar.pet.PetSkinStore
import dev.liuyouwu.familiar.search.SearchEngine
import dev.liuyouwu.familiar.search.BingCnEngine
import dev.liuyouwu.familiar.search.MultiSourceSearchEngine
import dev.liuyouwu.familiar.search.SogouEngine
import dev.liuyouwu.familiar.search.So360Engine
import dev.liuyouwu.familiar.search.WikipediaEngine
import dev.liuyouwu.familiar.skills.AssembledSkill
import dev.liuyouwu.familiar.skills.ContentProtocol
import dev.liuyouwu.familiar.skills.QuickCommand
import dev.liuyouwu.familiar.skills.SkillInterpreter
import dev.liuyouwu.familiar.skills.SkillRegistry
import dev.liuyouwu.familiar.skills.TypeInferer
import dev.liuyouwu.familiar.skills.profile.MaterialSection
import dev.liuyouwu.familiar.skills.profile.ReviewCoachPrompt
import dev.liuyouwu.familiar.tools.AgentTool
import dev.liuyouwu.familiar.tools.ToolFactory
import dev.liuyouwu.familiar.tools.ToolRegistry
import dev.liuyouwu.familiar.tools.WebSearchTool
import dev.liuyouwu.familiar.util.ConversationDefaults
import dev.liuyouwu.familiar.util.persona.DefaultPersonaCards
import dev.liuyouwu.familiar.util.persona.PersonaCard
import dev.liuyouwu.familiar.util.memory.FamiliarMemoryStore
import dev.liuyouwu.familiar.viewmodel.skillhooks.ChatRoomHooks
import dev.liuyouwu.familiar.viewmodel.skillhooks.ClassroomHooks
import dev.liuyouwu.familiar.viewmodel.skillhooks.FamiliarHooks
import dev.liuyouwu.familiar.viewmodel.skillhooks.FamiliarParams
import dev.liuyouwu.familiar.viewmodel.skillhooks.SkillRepoPort
import dev.liuyouwu.familiar.viewmodel.skillhooks.SkillSettingsPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.google.gson.Gson
import android.content.Context
import okhttp3.OkHttpClient
import java.io.File

/**
 * SkillHost —— 组装工厂（spec §3 组装位置、§6.4、P3 Task 6 hub）：把 P1/P2/P3-T1~T5
 * 的全部零件按会话类型装配为 [AgentSession]，并暴露快捷指令条数据源与 skill 会话
 * 判定。VM 持有本工厂（每 active 会话装配 + 缓存，切换重建）。
 *
 * ## 组装管线
 * [ConversationEntity] → skillId（skillId 列直取；null = 旧数据经 [TypeInferer]
 * §6.2 六序反推——isChatRoom 行反推 chatroom，圆桌锚点计数传 0：锚点建室消息由
 * T3 建室流程显式注入，P4 前存量圆桌经此装配为 chatroom 形状的已知边界）→
 * [SkillInterpreter.assemble]（params 按类型派生：familiar=FamiliarParams.derive、
 * classroom=skillParams/tutorConfig 五字段、chat=会话行 system_prompt 直通特例、
 * chatroom/roundtable=skillParams 字符串值）→ hooks + tools + 供应商（[AgentSession]
 * 构造位）：
 * - chat/eli5 → 默认空 hooks（裸循环）；familiar → [FamiliarHooks]
 *   （transformContext 记忆注入 / onPersisted 切段 / onRunEnd 记忆落盘+桌宠）；
 *   classroom → [ClassroomHooks]（transformContext 状态段+续讲指令 /
 *   persistTransform 剧本拆条 / onRunEnd TS 落列+废稿重试+续讲）；
 *   **chatroom/roundtable（P4 T2）** → 裸 hooks + [MultiAgentOrchestrator]
 *   装配（[roundFor]，scheduler=PLANNER；roundtable=skill JSON 三角色预设+
 *   恒开搜索、chatroom=成员解析+跟设置——[ChatRoomHooks]）。
 * - 工具装配（§6.2 表，工具面 T6 接线）：skill JSON `tools` 数组（buildX 传
 *   assembled.toolNames）经 [ToolFactory.install] 注册八件（get_current_time/
 *   read_file/write_file/list_files/http_get/clipboard_read/clipboard_write/
 *   open_url；未知名 warn 跳过）；web_search 不归工厂、仍在此处注册 + 预算门
 *   同装（链预算——P5 T2 评审 P1-2 修订：链引用在 openChain 单点经
 *   AgentSession.onChainOpened 回填（setGateChain），allows() 恒读活链，
 *   首 run 起即受限）；名单无 web_search 的 JSON（chat/eli5）→ 零搜索注册
 *   （无注册即无执行面）。
 * - searchAllowed：familiar = webSearchEnabled 设置（user-toggle）、classroom =
 *   恒 true（always-on）、chatroom = 跟设置（user-toggle）、roundtable = 恒 true
 *   （skill JSON searchPolicy 映射）；chat/eli5 无工具恒 true（无注册即无执行面）。
 * - maxTokens = { ContextBuilder.reserveForOutput(maxContext()) }（T1 硬契约：
 *   与旧链 ctx/4 钳 256..8192 三处同源，禁止回落后端默认）。
 * - **titleTrigger（P4 T2 接线）**：四类型 run 级触发 [TitleTrigger]（语义迁自
 *   ChatViewModel.generateTitleIfNeeded：独立轻量请求+单飞去重+familiar 豁免+
 *   旧默认名匹配）；chatroom/roundtable 的 run 级触发**装空**——标题按回合粒度
 *   经 [RoundAssembly.roundTitleTrigger] 触发（每回合一次，非每角色）。
 * - **streamingTextFilter（P4 T7）**：classroom 装配注入 [ClassroomDisplayFilter]
 *   （contentProtocol=classroomScript 时）——TS 标记/说话人行首前缀不进
 *   streamingText（显示层剥离，落库与 ContentProtocol 输入不受影响，见对象 KDoc）。
 *
 * ## 可测缝（brief 构造签名里的 Android 件替换为注入口）
 * - registry / store / llm / settings / repo / memoryStore 全部注入：生产
 *   （P5 接线）传 `SkillRegistry.fromAssets(context.assets, "skills")`、
 *   RoomMessageStore(messageDao, chatRepository, conversationDao, db)、
 *   DefaultLlmClient(Deps(okHttp, okHttpSseSource(okHttp), baseUrl, apiKey,
 *   provider), WireConverter(), MarkerToolAdapter()) 与 Settings/ChatRepository
 *   的端口适配器；单测传 fromAssetsJsons + InMemoryMessageStore + scripted LlmClient
 *   + 假件（assets/Room/OkHttp 均为 Android 框架操作，不可 JVM 单测——同
 *   RoomMessageStore.transaction / DefaultLlmClient.sseSource 的接缝模式）。
 * - [modeResolver] 缺省 { MARKER }：测试占位/未接线装配的兜底。生产（P5 T2）
 *   由 ChatViewModel 注入 [CapabilityReader].mode（纯缓存/目录读：覆盖>缓存快照>
 *   目录先验>UNKNOWN 折叠 MARKER，无挂起/无网络——探测预热只在连接页后台，
 *   评审 P1-4 全局约束）；测试注入假 resolver。
 * - [hooksScope] 供 hooks 内 repo 写/回调发射（生产：IO 常驻 scope）。
 *
 * ## 回调分工（TTS 防双读）
 * [ttsTrigger]/[onRevealDone] 由 VM 消费（familiar 的 TTS 归 reveal 推进链——
 * 末段曝光时读，等价现状 :717-719；非 familiar 的 TTS 归 T10 事件桥）：经
 * [tts]/[revealDone] 暴露给 VM 装配 reveal 链，hooks 永不直接读 TTS。
 * P4 T4 起按段/逐角色触发走构造注入回调：[onSegmentPersisted]（classroom 拆条组
 * 逐行落库后）与 [onRoleTurnEnded]（chatroom/roundtable 每角色 runOnChain 返回处）
 * 透传给 VM 事件桥——这两类的链级成功桥不再触发（防双触发，VM startAgentChain 侧
 * 同批收窄）；chat/eli5 单 run 链仍归 VM 链级桥。
 */
class SkillHost(
    private val registry: SkillRegistry,
    private val settings: SkillSettingsPort,
    private val repo: SkillRepoPort,
    private val memoryStore: FamiliarMemoryStore,
    private val store: MessageStore,
    private val llm: LlmClient,
    private val summarizer: suspend (String) -> String,
    ttsTrigger: (Long) -> Unit = {},
    petTurnEnded: () -> Unit = {},
    onSegments: (Long, List<String>) -> Unit = { _, _ -> },
    onRevealDone: () -> Unit = {},
    /**
     * web_search 引擎工厂（生产多源聚合：必应+搜狗+360 直连，维基熔断——被墙自动跳过、
     * 挂代理自动恢复；测试假引擎记账）。熔断器随工厂产物存活一个会话周期。
     */
    private val searchEngine: () -> SearchEngine = {
        MultiSourceSearchEngine(listOf(
            "必应" to BingCnEngine(),
            "搜狗" to SogouEngine(),
            "360" to So360Engine(),
            "维基百科" to WikipediaEngine(),
            // 2026-10-30 jev 事故：拉丁字母新技术词 zh 站常无条目（jev 在 zh 站 0 条），en 站补位
            "英文维基" to WikipediaEngine("en"),
        ))
    },
    /** 工具模式供应商（P5 由 CapabilityProber 四层探测替换；缺省 MARKER 占位，见类 KDoc） */
    private val modeResolver: () -> ToolMode = { ToolMode.MARKER },
    /**
     * 工具面注入三件（T6，[ToolFactory.install] 装配料）：[agentFilesRoot] = 文件
     * 三件套（read_file/write_file/list_files）的沙箱根（生产 = filesDir/agent_files，
     * Controller 派生）；[appContext] = 剪贴板/open_url 的 appContext（只存引用，
     * execute 期才触框架——ToolFactoryTest P1-7 夹具假设，JVM 测试可空构造）；
     * [agentOkHttp] = http_get 的基座 client（派生收窄在 HttpGetTool 构造内，
     * 共享连接池/超时基座不动）。
     */
    private val agentFilesRoot: File,
    private val appContext: Context,
    private val agentOkHttp: OkHttpClient,
    /**
     * MCP 远端工具快照（T4 方案 A：装配期注入，[AgentSessionController.attachSessionFor]
     * 构造点经 McpToolManager.toolsForSkill() 取现值——不在 assembleSession 求值）：
     * 五处 buildX 零改动，全部六类型（含聊天室/圆桌 Orchestrator 复用 sessionFor 产物）
     * 工具面统一追加。缺省空 = 未接线/零配置（既有装配逐位不变——行为等价门的零改动证明）。
     */
    private val mcpTools: List<AgentTool> = emptyList(),
    /** hooks 内 repo 写/回调发射的宿主 scope（生产 IO；测试 runBlocking 作用域） */
    private val hooksScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** B2-L4 familiar 相似度重试发起器（Controller 回填；null = L4 不启用）。参数 = 目标 assistant 行 id */
    private val familiarReviseLauncher: ((Long) -> Unit)? = null,
    /** 课堂续讲段间停顿（VM :1238 的 900ms；测试注短值） */
    private val continuationGapMs: Long = 900L,
    /**
     * 课堂段级落库回调（P4 T4，裁定：hooks 回调经 SkillHost 构造注入——SessionState
     * 不加字段，引擎面最小）：透传 [ClassroomHooks]（拆条组逐行落库后回调）→ VM
     * 事件桥按段触发 autoRead（桌宠"完成"已收拢 resolver 单路，B1）。
     */
    onSegmentPersisted: (turnId: String?, segmentIndex: Int, total: Int) -> Unit = { _, _, _ -> },
    /** 聊天室/圆桌每角色 run 终态回调（P4 T4）：透传 Orchestrator（runOnChain 返回处）。 */
    onRoleTurnEnded: (roleName: String, text: String) -> Unit = { _, _ -> },
    private val warn: (String) -> Unit = {},
    private val titleTrigger: (RunResult) -> Unit = {},
) {
    /** VM reveal 链消费的 TTS 触发口（hooks 不读 TTS，防双读——见类 KDoc）。 */
    val tts: (Long) -> Unit = ttsTrigger

    /** VM reveal 推进完成回调口。 */
    val revealDone: () -> Unit = onRevealDone

    // hooks 消费的回调暂存（构造参数 → 内部可读）
    private val petTurnEndedCallback: () -> Unit = petTurnEnded
    private val onSegmentsCallback: (Long, List<String>) -> Unit = onSegments
    private val onSegmentPersistedCallback: (String?, Int, Int) -> Unit = onSegmentPersisted
    private val onRoleTurnEndedCallback: (String, String) -> Unit = onRoleTurnEnded

    private val interpreter = SkillInterpreter(registry, warn)
    private val gson = Gson()

    /** 聊天室/圆桌组装构件（P4 T2）：成员/预设解析 + 群规段迁移 + 锚点消息渲染。 */
    private val chatRoomHooks = ChatRoomHooks(repo, settings::chatRoomAddress, warn)

    /** 标题自动生成（P4 T2 接线，语义迁自 VM.generateTitleIfNeeded；见类 KDoc titleTrigger 条）。 */
    private val titleGenerator = TitleTrigger(llm, repo, store, settings, hooksScope)

    /** 缓存槽：每 active 会话一个。已知边界：同 id 会话的行内变更（tutorState 由钩子自刷、skillParams/设置变更）不触发重建——自下一条消息（新链）生效（B2-L1：convSampling 每链刷新）（P5 接线如需即时生效可加显式失效口；聊天室成员逐回合重解析，不受此限）。 */
    private var cachedConversationId: Long? = null
    private var cachedSession: AgentSession? = null

    /** 会话配套的 AgentLoop（roundFor 组装 Orchestrator 需要；与 session 同生命周期缓存）。 */
    private var cachedLoop: AgentLoop? = null

    /** 最近一次 familiar 装配的 hooks（B2-L4：Controller 经 [familiarHooksOrNull] 取回，核验通过后置旗标）。 */
    @Volatile private var builtFamiliarHooks: FamiliarHooks? = null

    /**
     * 每 active 会话装配 [AgentSession]（缓存，切换重建）。六类型分派见类 KDoc；
     * 非 skill 会话（未知 skillId）抛 [IllegalArgumentException]——加载失败宁可
     * fail loudly。聊天室/圆桌的回合装配（Orchestrator + 角色）另经 [roundFor]。
     */
    fun sessionFor(conversation: ConversationEntity): AgentSession {
        cachedSession?.let { if (cachedConversationId == conversation.id) return it }
        val (session, loop) = buildWithLoop(conversation)
        cachedConversationId = conversation.id
        cachedSession = session
        cachedLoop = loop
        return session
    }

    /** 最近一次 familiar 装配的 hooks（sessionFor 按会话缓存；挂载会话即最后构建者）。 */
    fun familiarHooksOrNull(): FamiliarHooks? = builtFamiliarHooks

    /** 快捷指令条数据源（skill JSON ui.quickCommands；非 skill 会话为空）。 */
    fun quickCommands(conversation: ConversationEntity): List<QuickCommand> =
        registry.get(skillIdOf(conversation))?.ui?.quickCommands.orEmpty()

    /**
     * 是否走 agent skill 管线（[sessionFor]/[roundFor] 可装配）：注册表已知即
     * true——chatroom/roundtable 自 P4 T2 起归 [roundFor] 的 Orchestrator 装配，
     * 不再是自留拒绝区。null（旧数据未判定）与未知 id 均非 skill 会话（VM 侧
     * 先经 skillIdOf 判定或走旧链）。
     */
    fun isSkillConv(skillId: String?): Boolean =
        skillId != null && registry.get(skillId) != null

    /** 会话 → skillId（skillId 列直取；null 时 §6.2 六序反推——锚点计数传 0，见类 KDoc）。 */
    fun skillIdOf(conversation: ConversationEntity): String =
        conversation.skillId
            ?: TypeInferer.infer(conversation.tutorConfig, conversation.isChatRoom, roundtableAnchorCount = 0)

    /**
     * 聊天室/圆桌的回合装配（P4 T2，spec §5.3；T3 VM 的 orchestrator 驱动通道）：
     * session（[sessionFor] 缓存语义）+ Orchestrator（**scheduler=PLANNER**，
     * plannerModel=全局当前模型、maxTurnsPerRound=设置现值——T1 承接③）+ 角色
     * （chatroom=成员解析【members JSON 优先/表回退】、roundtable=skill JSON 三
     * 角色预设）+ 锚点建室消息（roundtable）+ 回合级标题触发（T1 承接④）。
     * 每回合调用一次（成员/设置逐回合新鲜；session/loop 复用缓存）。
     */
    suspend fun roundFor(conversation: ConversationEntity): RoundAssembly {
        val skillId = skillIdOf(conversation)
        require(skillId == CHATROOM || skillId == ROUNDTABLE) {
            "会话 ${conversation.id} 非聊天室/圆桌（skillId='$skillId'）：roundFor 仅装配 orchestrator 分支（四类型走 sessionFor）"
        }
        val session = sessionFor(conversation)
        val loop = requireNotNull(cachedLoop) { "AgentLoop 未随 sessionFor 缓存（装配序错误）" }
        val params = chatroomParams(conversation)
        val assembled = assembleOrThrow(skillId, params + templatePlaceholder(skillId), conversation)
        // 材料缝①（Task 5，角色侧）：圆桌可选学习材料的统一渲染段（chatroomParams 已按
        // 字符串值透传 skillParams.material，无需新存储通路）。null（无材料/空白）= 角色
        // 列表原样透传（prompt 逐字节不变，chatroom 成员路径零行为变化）；有材料 = 段落
        // 以 `\n\n` 拼到每个 RoleSpec.prompt 尾部（角色 run 的 systemOverride 生效）。
        val materialSection = MaterialSection.section(params["material"])
        // 画像缝（Task 7，角色侧）：建室快照 skillParams.profile 经 chatroomParams 字符串值
        // 透传的**已渲染段**（ProfileRenderer.render 产物，本层不渲染——同 plannerMaterial
        // 口径）。键缺失（chatroom/无条目/无匹配/检索失败）= null = 零拼接（无 else 分支
        // 污染）；有画像 = 与材料段并列拼到每个 RoleSpec.prompt 尾部（先材料、空行、后画像）。
        val profileSection = params["profile"]?.takeIf { it.isNotBlank() }
        val baseRoles = chatRoomHooks.resolveRoles(conversation, skillId, assembled.behaviorReader)
        return RoundAssembly(
            session = session,
            orchestrator = MultiAgentOrchestrator(
                session = session,
                loop = loop,
                // T1 承接③：调度模型=全局当前模型（pickRoundPlan 的 currentModel 同源；
                // 角色模型由 RoleSpec.model 覆盖，空则同样回落此供应商）
                plannerModel = settings::currentModel,
                maxTurnsPerRound = settings.chatRoomMaxReplies(),
                // 材料缝②（Task 5，planner 构造位）：传入已渲染段——planRound 只拼
                // systemPrompt 尾部，不进调度请求的 user 输入（同一请求双份纯浪费）
                plannerMaterial = materialSection,
                // 画像缝（Task 7，planner 构造位）：已渲染段只进调度请求的 user 输入
                //（planRound → RoundtablePlannerPrompt.userInput 的 profileSection 参，
                // Task 6 已留位）——不进 systemPrompt（材料段已占 system 尾部，同请求
                // 双份纯浪费）；chatroom 无此键＝null＝user 输入与现状逐字节一致
                plannerProfileSection = profileSection,
                // 圆桌风格门（Task 6）：仅圆桌走 RoundtablePlannerPrompt 专属文案
                // （轮尾问句/≤N 句分派/无 point 问句兜底）；chatroom 不传＝false，
                // 现场文案逐字保留（chatroom.json 同为 "scheduler": "planner"，零回归红线）
                roundtableStyle = skillId == ROUNDTABLE,
                // P4 T4：每角色 run 终态回调（runOnChain 返回处）——VM 事件桥逐角色触发
                onRoleTurnEnded = onRoleTurnEndedCallback,
            ),
            roles = rolePromptTail(materialSection, profileSection)
                ?.let { tail -> baseRoles.map { it.copy(prompt = "${it.prompt}\n\n$tail") } }
                ?: baseRoles,
            scheduler = schedulerOf(assembled),
            openingMessage = chatRoomHooks.openingMessage(assembled.behaviorReader, params),
            // T1 承接④：回合级标题触发（run 级已装空）——每回合恰一次
            roundTitleTrigger = { titleGenerator.trigger(conversation.id) },
        )
    }

    // ---------------------------------------------------------------- 复习侧信道（学习画像 Task 10）

    /**
     * 复习教练侧信道路径（spec §6「不落库」）：每次调用新鲜装配一个 [ReviewChat]——
     * **不建 Room 会话、不入库**。与六类型装配的关键差异（brief 裁决①"Read 现场"）：
     * - 只造 [AgentLoop]（同源 [RunMarkers]：[RunMarkerTexts] 唯一字面来源；空
     *   [ToolRegistry]——复习零工具面），**不造 [AgentSession]**：loop.run 直驱
     *   （MultiAgentOrchestrator.planRound :184-249 先例）不收 session 参，而
     *   AgentSession 的全部 run 入口（executeRun）无条件 persistProduced——持有它
     *   只会是"接了落库线的死重"，与不落库红线背道而驰；
     * - RunChain 用**公开构造**（RunChain.kt:13，非 session.acquireChain）：
     *   conversationId 恒 "review"（不指向任何会话行），scope 为本 ReviewChat 专享
     *   （cancel 只杀本复习链，不波及宿主）；
     * - system = [ReviewCoachPrompt.system]（逐条出题 + JSON 收尾）；mode=NATIVE、
     *   hooks 裸、maxTurns 缺省（无工具 turn 自然收束）、maxTokens 缺省（不发送——
     *   planRound 同口径；loop 侧无 maxContext 预裁，复习会话短可接受，spec 已裁定）。
     *
     * 装配料复用既有六类型的获取方式：llm / settings（currentModel）即构造注入的
     * 同一份（生产装配点 ChatViewModel.reviewSession，AgentEngineRuntime 同源）。
     */
    fun reviewSession(entries: List<LearningProfileEntryEntity>): ReviewChat = ReviewChat(
        loop = AgentLoop(llm, ToolRegistry(), RunMarkers(
            truncationMarker = RunMarkerTexts.TRUNCATION_MARKER,
            truncationTail = RunMarkerTexts.TRUNCATION_TAIL,
            noAnswerMarker = RunMarkerTexts.NO_ANSWER_MARKER,
            noAnswerTail = RunMarkerTexts.NO_ANSWER_TAIL,
            toolCancelled = RunMarkerTexts.TOOL_CANCELLED,
            noClosingAnswerNote = RunMarkerTexts.NO_CLOSING_ANSWER_NOTE,
        )),
        chain = RunChain(REVIEW_CHAIN_ID, CoroutineScope(SupervisorJob() + Dispatchers.IO)),
        system = ReviewCoachPrompt.system(entries.map { it.id to it.point }),
        model = settings::currentModel,
    )

    // ---------------------------------------------------------------- 装配

    private fun buildWithLoop(conversation: ConversationEntity): Pair<AgentSession, AgentLoop> =
        when (val skillId = skillIdOf(conversation)) {
            FAMILIAR -> buildFamiliar(conversation)
            CLASSROOM -> buildClassroom(conversation)
            CHATROOM -> buildChatroom(conversation)
            ROUNDTABLE -> buildRoundtable(conversation)
            else -> {
                if (!isSkillConv(skillId)) {
                    throw IllegalArgumentException(
                        "会话 ${conversation.id} 非 skill 会话（skillId='$skillId'）：未知 skill 加载失败" +
                            "降级请先经 quickCommands/isSkillConv 判定",
                    )
                }
                buildDefault(skillId, conversation)
            }
        }

    /** chat/eli5：默认空 hooks + 会话行 system_prompt 直通（chat 特例在 interpreter）。 */
    private fun buildDefault(skillId: String, conversation: ConversationEntity): Pair<AgentSession, AgentLoop> {
        val params = if (skillId == CHAT) mapOf(SYSTEM_PROMPT_PARAM to conversation.systemPrompt) else emptyMap()
        val assembled = assembleOrThrow(skillId, params, conversation)
        return assembleSession(
            hooks = object : AgentHooks {},
            system = assembled.systemPrompt,
            toolNames = assembled.toolNames,
            persistTransform = null,
            onPersisted = null,
            searchAllowed = { true },
            titleTrigger = runTitleTrigger(conversation),
        )
    }

    /** familiar：FamiliarParams 派生 + FamiliarHooks（记忆/切段/落盘）+ user-toggle 搜索。 */
    private fun buildFamiliar(conversation: ConversationEntity): Pair<AgentSession, AgentLoop> {
        // 卡解析（assembleFamiliar:618-631 迁移）：按 petColor 现扫皮肤 personaOf 挑人设（皮肤包 P5，
        // 内置蓝/橘原卡原样、自定义皮肤取 persona 字段）+ 设置覆写称呼；
        // cardId 绑定已随换卡功能移除作废（:620-621 注记），不读人设卡表
        val card = PersonaCard.from(DefaultPersonaCards.personaOf(PetSkinStore.get(settings.petColor())))
            .copy(
                address = settings.familiarAddress(),
                selfName = settings.familiarSelfName(),
            )
        val params = FamiliarParams.derive(card, settings)
        val assembled = assembleOrThrow(FAMILIAR, params, conversation)
        val hooks = FamiliarHooks(
            card = card,
            memoryStore = memoryStore,
            repo = repo,
            scope = hooksScope,
            onSegments = onSegmentsCallback,
            onTurnEnded = petTurnEndedCallback,
        )
        hooks.revise = familiarReviseLauncher
        builtFamiliarHooks = hooks
        return assembleSession(
            hooks = hooks,
            // 空值变量空行兜底（P2-T3 登记项）——仅 familiar 用，chat 用户文本不动
            system = FamiliarParams.sanitize(assembled.systemPrompt),
            toolNames = assembled.toolNames,
            persistTransform = null,
            onPersisted = hooks::onPersisted,
            searchAllowed = settings::webSearchEnabled,
            titleTrigger = runTitleTrigger(conversation),
        )
    }

    /** classroom：ClassroomHooks（状态段/拆条/TS 落列/废稿重试/续讲）+ always-on 搜索。 */
    private fun buildClassroom(conversation: ConversationEntity): Pair<AgentSession, AgentLoop> {
        val assembled = assembleOrThrow(CLASSROOM, classroomParams(conversation), conversation)
        val behaviors = assembled.behaviorReader
        val speakers = digSpeakers(behaviors)
        val maxDepth = digMaxDepth(behaviors)
        val hooks = ClassroomHooks(
            conversationId = conversation.id,
            repo = repo,
            speakers = speakers,
            maxDepth = maxDepth,
            initialTutorState = conversation.tutorState,
            gapMs = continuationGapMs,
            scope = hooksScope,
            // P4 T4：段级落库回调（拆条组逐行，组末行=组完成）——VM 事件桥按段触发
            onSegmentPersisted = onSegmentPersistedCallback,
        )
        val (session, loop) = assembleSession(
            hooks = hooks,
            system = assembled.systemPrompt,
            toolNames = assembled.toolNames,
            persistTransform = hooks::transformPersist,
            onPersisted = hooks::onSegmentsPersisted,
            searchAllowed = { true },
            titleTrigger = runTitleTrigger(conversation),
            // P4 T7：课堂流式标记瞬态剥离（显示层过滤）——contentProtocol=classroomScript
            // 时注入；落库与 ContentProtocol 输入不受影响（评审 P1-5 落点裁定：
            // 不在 LlmClient 规范化层剥，否则污染块装配破坏落库解析）
            streamingTextFilter = ClassroomDisplayFilter.ofOrNull(assembled.contentProtocol, speakers),
        )
        // 后续 run 发起器回填（hooks ↔ session 循环引用的解法：装配后接线）
        hooks.followUp = { session.followUpRun(conversation.id.toString()) }
        return session to loop
    }

    /**
     * chatroom：裸 hooks + Orchestrator 会话件（P4 T2）。角色 prompt 不经构造位
     * system——每角色 runOnChain 的 systemOverride 生效（[ChatRoomHooks.memberPrompt]，
     * 群规段并入）；构造位 system 仅占位（模板渲染产物，不参与请求）。
     * 搜索=skill JSON searchPolicy（user-toggle 跟设置）。
     */
    private fun buildChatroom(conversation: ConversationEntity): Pair<AgentSession, AgentLoop> {
        val assembled = assembleOrThrow(CHATROOM, chatroomParams(conversation) + templatePlaceholder(CHATROOM), conversation)
        return assembleSession(
            hooks = object : AgentHooks {},
            system = assembled.systemPrompt,
            toolNames = assembled.toolNames,
            persistTransform = null,
            onPersisted = null,
            searchAllowed = searchAllowedOf(assembled.searchPolicy),
            titleTrigger = { },   // T1 承接④：run 级装空——标题按回合粒度（RoundAssembly.roundTitleTrigger）
        )
    }

    /**
     * roundtable：裸 hooks + Orchestrator 会话件（P4 T2）。三角色预设由
     * [ChatRoomHooks.presetRoles] 在 roundFor 解析；搜索=skill JSON searchPolicy
     * （always-on 恒开）；锚点建室消息由 T3 建室流程取 [RoundAssembly.openingMessage] 写库。
     */
    private fun buildRoundtable(conversation: ConversationEntity): Pair<AgentSession, AgentLoop> {
        val assembled = assembleOrThrow(ROUNDTABLE, chatroomParams(conversation) + templatePlaceholder(ROUNDTABLE), conversation)
        return assembleSession(
            hooks = object : AgentHooks {},
            system = assembled.systemPrompt,
            toolNames = assembled.toolNames,
            persistTransform = null,
            onPersisted = null,
            searchAllowed = searchAllowedOf(assembled.searchPolicy),
            titleTrigger = { },   // 同 chatroom：回合粒度
            // Task 5 评审修订（Finding 2）：**保底下限**——settings 现值（默认 131072）
            // 不收缩，低于 8192 托到 8192（材料最多 2000 字的历史预算兜底；spec §3 已
            // 改写为「保底下限 8192」）。唯一传 override 的 buildX，其余五类型不变；
            // 该供应商同时是圆桌 max_tokens 求值源（assembleSession 单一 effectiveMax）
            maxContextOverride = { roundtableMaxContextFloor(settings.maxContext()) },
        )
    }

    /** AgentSession 构造位收口：四件套供应商 + 工具注入（T6）+ 预算门 + 链跟踪（公共装配段）。 */
    private fun assembleSession(
        hooks: AgentHooks,
        system: String,
        toolNames: List<String>,
        persistTransform: ((List<AgentMessage>) -> List<AgentMessage>)?,
        onPersisted: ((List<AgentMessage>, List<Long>) -> Unit)?,
        searchAllowed: () -> Boolean,
        titleTrigger: (RunResult) -> Unit,
        streamingTextFilter: ((String) -> String)? = null,
        /**
         * maxContext 供应商覆写（Task 5，评审 Finding 2 后为**保底下限**语义）：
         * null（缺省）= settings::maxContext 现状，六类型共享行为不变；圆桌装配传
         * [roundtableMaxContextFloor]（设置现值与 8192 取大——DEFAULT_MAX_CONTEXT
         * 已是 131072，固定 8192 会对默认用户 16 倍收缩）。仅圆桌一个 buildX 传参，
         * 其余五类型零改动。**本供应商是 maxContext 与 maxTokens 的同一来源**
         * （Finding 1 修订：ContextBuilder.reserveForOutput 硬契约「预留不得小于
         * 实际输出上限」，两处各取来源会漂移超窗）。
         */
        maxContextOverride: (() -> Int)? = null,
    ): Pair<AgentSession, AgentLoop> {
        // Finding 1 修订（单一来源收口）：maxContext 与 maxTokens 从同一 effectiveMax
        // 求值——缺省 null 时即 settings::maxContext 原供应商引用，非圆桌类型两值
        // 与改前逐位一致
        val effectiveMaxContext = maxContextOverride ?: settings::maxContext
        val toolRegistry = ToolRegistry()
        // 工具面接线（T6）：skill JSON `tools` 数组（buildX 传 assembled.toolNames）——
        // 八件经 [ToolFactory.install] 注册（web_search 让渡不归工厂：预算门必须同装，
        // 见 ToolFactory KDoc；未知名工厂侧 warn 跳过不废装配）；
        // 预算门（§6.2 轮链 2 的执行侧）随 web_search 在列才装：链引用在 openChain
        // 单点创建链时经 AgentSession.onChainOpened 回填（P5 T2 评审 P1-2 修订）——旧
        // onRunEnd 回填使首个 run 全程无引用、执行侧门恒放行；现在首个 run 起即受限。
        // setGateChain 回调（gate::setChain）随构造位传入，活链一开即交门。
        var gateChain: ((RunChain) -> Unit)? = null
        ToolFactory.install(toolRegistry, toolNames, agentFilesRoot, appContext, agentOkHttp)
        if (WEB_SEARCH in toolNames) {
            toolRegistry.register(WebSearchTool(searchEngine()))
            val gate = SessionBudgetGate()
            toolRegistry.installBudgetGate(WEB_SEARCH) { gate.allows() }
            gateChain = gate::setChain
        }
        // MCP 远端工具（T4 方案 A）：快照整体注册在本地件之后——register 是覆盖写，
        // 与 manager 侧"{slug}__{toolName}"前缀命名双保险，本地八件+web_search 不被撞名顶掉；
        // mcpTools 空（总开关关/零配置/默认）此行零影响。
        mcpTools.forEach(toolRegistry::register)
        // 用户可见标记文案（内核清理 A，Task 4）：RunMarkerTexts 经 [RunMarkers]
        // **双路直注**——loop 与 session 各接一份同值实例（"Session 接收后转发"
        // 不成立：二者构造彼此独立，本函数是唯一注入点），字面唯一来源本文件
        // [RunMarkerTexts]（标记文案自此零内嵌字面）。
        val markers = RunMarkers(
            truncationMarker = RunMarkerTexts.TRUNCATION_MARKER,
            truncationTail = RunMarkerTexts.TRUNCATION_TAIL,
            noAnswerMarker = RunMarkerTexts.NO_ANSWER_MARKER,
            noAnswerTail = RunMarkerTexts.NO_ANSWER_TAIL,
            toolCancelled = RunMarkerTexts.TOOL_CANCELLED,
            noClosingAnswerNote = RunMarkerTexts.NO_CLOSING_ANSWER_NOTE,
        )
        val loop = AgentLoop(llm, toolRegistry, markers)
        val session = AgentSession(
            store = store,
            loop = loop,
            markers = markers,
            hooks = hooks,
            system = { system },
            // specs 按 toolNames（**装配参**，本函数已无同名局部变量——评审 P2-4 遮蔽陷阱
            // 钉死）保序导出；未注册名静默丢弃（ToolRegistry.specs 同口径降级）；
            // MCP 远端工具（T4）不经 skill JSON toolNames 名单，快照整体追加在本地件之后
            // （register 覆盖写 + 管理器前缀命名已保不撞本地名，全量导出即全量可执行面）
            tools = toolRegistry.specs(toolNames) + mcpTools.map { ToolSpec(it.name, it.description, it.parametersJson) },
            mode = modeResolver,
            // Task 5 评审修订：effectiveMaxContext 与下方 maxTokens 同一来源（见函数头注）
            maxContext = effectiveMaxContext,
            summarizer = summarizer,
            titleTrigger = titleTrigger,
            model = settings::currentModel,
            // T1 硬契约：与旧链三处 ctx/4 钳 256..8192 同源，禁止回落后端默认。
            // Finding 1 修订：reserveForOutput 求值自**同一** effectiveMaxContext——
            // 圆桌曾出现 maxContext=8192（reserve=2048）而 max_tokens=reserveForOutput
            // (settings)=8192 的漂移，本地小窗口模型会整请求超窗
            maxTokens = { ContextBuilder.reserveForOutput(effectiveMaxContext()) },
            searchAllowed = searchAllowed,
            // B2-L1：链级采样参数供应商（openChain 单点求值播种——settings 端口缓存
            // 快照由 refreshSamplingCache 每链刷新，生效粒度=链）
            sampling = settings::convSampling,
            persistTransform = persistTransform,
            onPersisted = onPersisted,
            streamingTextFilter = streamingTextFilter,
            onChainOpened = gateChain ?: { },
        )
        return session to loop
    }

    /**
     * 四类型 run 级标题触发（P4 T2 接线）：[TitleTrigger] 迁移语义先行（去重/
     * 豁免在触发口同步判定，不阻塞收线），VM 观察位随后（缺省空）。聊天室/圆桌
     * 不经此（回合级经 [RoundAssembly.roundTitleTrigger]）。
     */
    private fun runTitleTrigger(conversation: ConversationEntity): (RunResult) -> Unit =
        { result ->
            titleGenerator.trigger(conversation.id)
            titleTrigger(result)
        }

    /** interpreter 装配（未知/畸形 → 告警降级 chat 默认——plan 明确推迟项 19）。 */
    private fun assembleOrThrow(
        skillId: String,
        params: Map<String, String>,
        conversation: ConversationEntity,
    ): AssembledSkill =
        interpreter.assemble(skillId, params) ?: run {
            warn("SkillHost: skill '$skillId' 装配失败（未注册），降级 chat 默认装配")
            interpreter.assemble(CHAT, mapOf(SYSTEM_PROMPT_PARAM to conversation.systemPrompt))
                ?: error("chat 兜底装配失败：注册表缺 chat 定义")
        }

    /** classroom 参数（skillParams 列优先；旧数据/未写回落 tutorConfig——五字段同形）。 */
    private fun classroomParams(conversation: ConversationEntity): Map<String, String> {
        val source = conversation.skillParams?.takeIf { it.isNotBlank() }
            ?: conversation.tutorConfig?.takeIf { it.isNotBlank() }
            ?: return emptyMap()
        val obj = try {
            gson.fromJson(source, Map::class.java)
        } catch (_: Exception) {
            null
        } ?: return emptyMap()
        val keys = listOf("subject", "level", "style", "tone", "language", "classmate")
        return keys.mapNotNull { k -> (obj[k] as? String)?.let { k to it } }.toMap()
    }

    /** chatroom/roundtable 参数：skillParams 的字符串值键值（topic 等；members 数组等非字符串值滤除）。 */
    private fun chatroomParams(conversation: ConversationEntity): Map<String, String> {
        val source = conversation.skillParams?.takeIf { it.isNotBlank() } ?: return emptyMap()
        val obj = try {
            gson.fromJson(source, Map::class.java)
        } catch (_: Exception) {
            null
        } ?: return emptyMap()
        return obj.entries.mapNotNull { (k, v) ->
            val key = k as? String ?: return@mapNotNull null
            (v as? String)?.let { key to it }
        }.toMap()
    }

    /** multiAgent.scheduler（缺省/未知 → PLANNER——两内置 JSON 声明即 planner，§5.3 主路径）。 */
    private fun schedulerOf(assembled: AssembledSkill): MultiAgentOrchestrator.Scheduler {
        val raw = (assembled.behaviorReader.raw(MULTI_AGENT) as? Map<*, *>)?.get("scheduler") as? String
        return if (raw == SCHEDULER_ROUND_ROBIN) MultiAgentOrchestrator.Scheduler.ROUND_ROBIN
        else MultiAgentOrchestrator.Scheduler.PLANNER
    }

    /** searchPolicy → searchAllowed 供应商：always-on 恒开；其余（user-toggle）跟设置。 */
    private fun searchAllowedOf(policy: String): () -> Boolean =
        if (policy == POLICY_ALWAYS_ON) ({ true }) else settings::webSearchEnabled

    /** 模板占位补参：chatroom/roundtable 的会话级模板变量不参与请求（systemOverride 生效），空串替换防缺参告警。 */
    private fun templatePlaceholder(skillId: String): Map<String, String> =
        if (skillId == ROUNDTABLE) mapOf("user_name" to "") else mapOf("displayName" to "")

    /** behaviors.multiSpeakerScript.speakers 挖取（嵌套值经 raw 自行 dig——Task 1 交接注记）。 */
    private fun digSpeakers(behaviors: dev.liuyouwu.familiar.skills.Behaviors): List<String> {
        val raw = behaviors.raw(MULTI_SPEAKER) as? Map<*, *> ?: return ClassroomHooks.DEFAULT_SPEAKERS
        val list = raw["speakers"] as? List<*> ?: return ClassroomHooks.DEFAULT_SPEAKERS
        val speakers = list.filterIsInstance<String>().filter { it.isNotBlank() }
        return speakers.ifEmpty { ClassroomHooks.DEFAULT_SPEAKERS }
    }

    /** behaviors.autoContinue.maxDepth 挖取（Gson 数字 = Double，Behaviors.int 同口径归一）。 */
    private fun digMaxDepth(behaviors: dev.liuyouwu.familiar.skills.Behaviors): Int {
        val raw = behaviors.raw(AUTO_CONTINUE) as? Map<*, *> ?: return DEFAULT_MAX_DEPTH
        return (raw["maxDepth"] as? Number)?.toInt() ?: DEFAULT_MAX_DEPTH
    }

    // ---------------------------------------------------------------- 内部件

    /**
     * 预算门（P5 T2 评审 P1-2 修订后的语义）：活链由 [setChain] 在 openChain
     * 单点回填（AgentSession.onChainOpened → gate::setChain）。
     * - allows() 恒读活链：同 run 内预算耗尽后的下一次搜索即被拒（新判据，
     *   SkillHostTest 10b 钉死），首个 run 起即受限；
     * - 引用为 null / 链已取消（stop、新链取代）：拒绝（fail-closed）——
     *   "未系放行"分支已删：生产序 openChain 先于任何 run，null 不可达；
     *   可达即装配错误，宁拒不错放（被拒 run 正在收尾中，误拒无实际影响面）。
     */
    private class SessionBudgetGate {
        /** 属性名避开 setChain——Kotlin @Volatile var 的合成 setter 与同名函数 JVM 签名冲突 */
        @Volatile var live: RunChain? = null
        fun setChain(c: RunChain) { live = c }
        fun allows(): Boolean =
            live?.takeIf { it.scope.isActive }?.context?.searchBudgetRemaining?.let { it > 0 } == true
    }

    private companion object {
        const val FAMILIAR = TypeInferer.FAMILIAR
        const val CLASSROOM = TypeInferer.CLASSROOM
        const val CHAT = TypeInferer.CHAT
        const val CHATROOM = TypeInferer.CHATROOM
        const val ROUNDTABLE = TypeInferer.ROUNDTABLE

        /** chat 特例参数名（SkillInterpreter.CHAT_PARAM_SYSTEM_PROMPT 同值同源） */
        const val SYSTEM_PROMPT_PARAM = SkillInterpreter.CHAT_PARAM_SYSTEM_PROMPT
        const val WEB_SEARCH = "web_search"
        const val MULTI_SPEAKER = "multiSpeakerScript"
        const val AUTO_CONTINUE = "autoContinue"
        private const val MULTI_AGENT = "multiAgent"

        /** 续讲深度上限缺省（VM :1237 的 4；classroom JSON 同值） */
        const val DEFAULT_MAX_DEPTH = 4

        /** skill JSON behaviors 值：调度降级名 / 恒开搜索（§5.3） */
        private const val SCHEDULER_ROUND_ROBIN = "round-robin"
        private const val POLICY_ALWAYS_ON = "always-on"

        /** 复习链 conversationId（Task 10）：不指向任何会话行，仅作链隔离标识 */
        const val REVIEW_CHAIN_ID = "review"
    }
}

/**
 * 用户可见标记文案常量（内核清理 A，Task 4）：agent 内核（AgentLoop/AgentSession）
 * 只经注入的 [RunMarkers] 读用——本对象为这些文案的**唯一存活字面来源**。六常量
 * 迁自内核原字面（标记四常量 + AgentLoop 两字面；util/ChatMarkers 副本已随 P4
 * 删除批 B 删）——迁移均为逐字节拷贝，等价由 RunMarkerTextsSnapshotTest 快照钉死
 * （防「注释约定一字不差」式的静默失配，同 util 侧审计 P2-4 的教训）。
 *
 * 语义：
 * - TAIL：气泡侧判据（正文 trimEnd 后以此结尾即视为被截断/无回答）；
 * - MARKER：写入端追加的完整标记（前导双换行 + TAIL），构建请求历史前剥掉。
 * MARKER 展开为独立字面量、不引用本对象 TAIL——各常量各自钉死，
 * 单独改动任何一个都会被测试抓住。
 */
object RunMarkerTexts {
    /** 气泡侧判据：正文 trimEnd 后以此结尾即视为被截断 */
    const val TRUNCATION_TAIL = "*（回复因长度限制被截断，可点「继续生成」补全）*"

    /** 写入端追加的完整标记（含前导双换行） */
    const val TRUNCATION_MARKER = "\n\n*（回复因长度限制被截断，可点「继续生成」补全）*"

    /** 模型只输出思考没写回答时的可见说明（追加在消息尾部，构建请求历史前剥掉） */
    const val NO_ANSWER_TAIL = "*（模型只输出了思考、没有写出回答——多为模型或其思考模板的问题，建议换个模型）*"

    /** 写入端追加的完整标记（含前导双换行） */
    const val NO_ANSWER_MARKER = "\n\n*（模型只输出了思考、没有写出回答——多为模型或其思考模板的问题，建议换个模型）*"

    /** AgentLoop 取消补落的 isError ToolResult 输出字面量（UI 渲染模型判 CANCELLED 态同源） */
    const val TOOL_CANCELLED = "已取消"

    /** 收尾 turn 无正文时的用户可见说明（不回灌模型上下文；原 AgentLoop.NO_CLOSING_ANSWER_NOTE 字面） */
    const val NO_CLOSING_ANSWER_NOTE = "（工具预算已尽，本轮未产生回答）"
}

/**
 * 聊天室/圆桌的回合装配（P4 T2，[SkillHost.roundFor] 产物；T3 VM 消费）：
 * [sessionFor] 缓存语义不变，回合件每回合经 [SkillHost.roundFor] 新鲜取用
 * （成员/设置可中途变更；session/loop 复用缓存）。
 */
data class RoundAssembly(
    val session: AgentSession,
    val orchestrator: MultiAgentOrchestrator,
    /** 本回合角色（chatroom=成员解析、roundtable=skill JSON 预设；空=VM 报"先加角色"）。 */
    val roles: List<MultiAgentOrchestrator.RoleSpec>,
    val scheduler: MultiAgentOrchestrator.Scheduler,
    /** 圆桌建室锚点消息（openingMessage.template 渲染；chatroom 恒 null——T3 建室流程写库）。 */
    val openingMessage: String?,
    /**
     * 回合级标题触发（T1 承接④）：每回合恰一次，在 runRound 返回后由调用方触发
     * （chatroom/roundtable 的 session run 级 titleTrigger 已装空——角色 run 不触发）。
     */
    val roundTitleTrigger: () -> Unit,
)

/**
 * 复习教练侧信道会话（学习画像 Task 10，[SkillHost.reviewSession] 产物；spec §6
 * 「不落库」）：持有 [AgentLoop] + 专享 [RunChain] + **内存历史**，ask 一次即一轮
 * 问答。与六类型会话的本质差异：整条链路不过 AgentSession（executeRun 无条件
 * persistProduced，AgentSession.kt:576）——消息只活在 [history]，Room messages 表
 * 零写入（红线；SkillHostReviewSessionTest 以 InMemoryMessageStore 空表钉死）。
 *
 * - 历史累加（brief 裁决②落点）：mutableList 单调追加——每轮 ask 先补 user 行，
 *   run 收线后把 [LoopOutcome.produced]（assistant + 可能的 TOOL_RESULT；本路径
 *   零工具故只有 assistant，Thinking 块经 WireConverter 天然不上 wire）整段并入，
 *   下一轮请求自带完整多轮上下文（input 传快照 toList，loop 内部再可变拷贝）。
 * - ask 返回值 = 产出 ASSISTANT 行的 Text 块拼接（MultiAgentOrchestrator 的
 *   textOf 同口径），供 VM 展示与 [dev.liuyouwu.familiar.skills.profile.ReviewResultParser]
 *   解析收尾 JSON。
 * - 取消语义：[cancel] 经 RunChain.cancel() 取消专享 scope——在飞 SSE 订阅随
 *   AgentLoop.collectStream 的链作用域挂链即刻中断（P3 终审取消贯通），已收块照常
 *   返回；VM onCleared 调用防泄漏（复习屏退出即释放，允许部分落账语义见 VM）。
 * - run 级参数：mode=NATIVE + 空 tools（planRound 直驱先例同款）；model 取设置
 *   现值；maxTokens 缺省不发送；maxTurns 缺省 8（无工具 turn 自然收束，护栏不达）。
 */
class ReviewChat internal constructor(
    private val loop: AgentLoop,
    private val chain: RunChain,
    private val system: String,
    private val model: () -> String,
) {

    /** 内存历史（不落库）：user 行与每轮 produced 顺序追加，进程内保留。 */
    private val history = mutableListOf<AgentMessage>()

    /** 复习链是否还活着（被 cancel 后为 false——VM 可据此拒绝后续发送）。 */
    val isActive: Boolean get() = chain.scope.isActive

    /**
     * 一轮问答：追加 user 到内存历史 → loop.run 直驱（事件不外抛，复习不走流式
     * UI）→ produced 并入历史 → 返回 assistant 正文。请求异常（网络/后端）原样
     * 抛给调用方（VM 报错壳兜底）；调用方协程取消照常传播（CancellationException
     * 不吞，AgentLoop.collectStream 同约定）。
     */
    suspend fun ask(userText: String): String {
        history += AgentMessage(
            id = "review-u${history.size}",
            role = Role.USER,
            blocks = listOf(ContentBlock.Text(userText)),
        )
        val outcome = loop.run(
            input = history.toList(),
            system = system,
            tools = emptyList(),
            hooks = object : AgentHooks {},   // 裸 hooks：复习不吃任何会话级上下文变换
            chain = chain,
            mode = ToolMode.NATIVE,
            model = model,
        ) { /* 侧信道事件不外抛（不驱动流式 UI，同 planRound 调度请求） */ }
        history += outcome.produced
        return outcome.produced
            .filter { it.role == Role.ASSISTANT }
            .flatMap { it.blocks.filterIsInstance<ContentBlock.Text>() }
            .joinToString("\n") { it.text }
            .trim()
    }

    /** 取消整条复习链（在飞 run 即刻中断；scope 释放，实例不可复用）。 */
    fun cancel() = chain.cancel()
}

/**
 * 圆桌 maxContext **保底下限**（Task 5，评审 Finding 2 裁定：SettingsRepository.DEFAULT_MAX_CONTEXT
 * 已是 131072，固定 { 8192 } 对默认用户是 16 倍收缩——语义改为取大）：设置现值低于
 * 8192 托到 8192（材料最多 2000 字的历史预算兜底），高于则原样放行（保底不封顶）。
 * [SkillHost.buildRoundtable] 经 assembleSession 的 maxContextOverride 传入；该供应商
 * 同时是圆桌 max_tokens 的求值源（同源契约——ContextBuilder.reserveForOutput 硬契约
 * 「预留不得小于实际输出上限」，assembleSession 内单一 effectiveMax 收口）。
 * 纯函数（SkillHostTest 27 钉死三界：4096→8192、8192→8192、131072→131072）。
 */
internal fun roundtableMaxContextFloor(currentMaxContext: Int): Int =
    maxOf(currentMaxContext, ROUNDTABLE_MIN_MAX_CONTEXT)

/** 保底下限值（spec §3「保底下限 8192」；历史预算兜底材料 ≤[MaterialSection.MAX_LEN] 字）。 */
internal const val ROUNDTABLE_MIN_MAX_CONTEXT = 8192

/**
 * 角色 prompt 尾部段拼接（Task 7）：圆桌学习材料段（Task 5，MaterialSection.section
 * 产物）与学习画像段（Task 7，ProfileRenderer.render 建室快照产物，两段均**已渲染**，
 * 本层不二次渲染）按「材料 → 空行 → 画像」并列。两段皆 null（无 material 键且无
 * profile 键——chatroom、新装、无条目/无匹配/检索失败）→ null：调用方 baseRoles
 * 原样透传，prompt 逐字节不变（空路径纪律）。纯 JVM（RolePromptTailTest 钉死，
 * 同 [roundtableMaxContextFloor] 先例）。
 */
internal fun rolePromptTail(materialSection: String?, profileSection: String?): String? =
    listOfNotNull(materialSection, profileSection).joinToString("\n\n").ifEmpty { null }

/**
 * 课堂流式显示过滤器（P4 T7，评审 P1-5 落点裁定：剥离在**显示层**——AgentSession
 * 的 streamingText 发布点，不在 LlmClient 规范化层剥，否则污染块装配破坏
 * ContentProtocol 落库解析）。contentProtocol=classroomScript 时由
 * [SkillHost.buildClassroom] 注入 AgentSession 构造位：
 * - TS 状态标记（[ContentProtocol.STATE_MARKER_BEGIN]/[ContentProtocol.STATE_MARKER_END]
 *   整体形态）整剥——落库侧 ContentProtocol.extractState 收线后照常从原始终稿
 *   提取状态，状态语义无损；
 * - 说话人 `[xx]` 行首标记剥前缀（台词正文保留；行首锚定与剧本格式一致——行中
 *   `[xx]` 是正文不剥）；
 * - 增量安全：AgentSession 对**累计文本**整体套用（非逐 delta）——跨 delta 切分
 *   的完整标记聚合后照剥；流中未收尾标记自起点扣留（残缺标记体、`<!--TS:` 起始
 *   头真前缀、末行未合拢 `[xx` 前缀），收尾到达后下一窗口整标记消失——瞬态
 *   ghost 不闪现（残缺尾部截除与 ContentProtocol.extractState 同语义）。
 */
internal object ClassroomDisplayFilter {

    /** classroomScript 协议名（skills/classroom.json behaviors.contentProtocol 同源）。 */
    internal const val PROTOCOL_CLASSROOM_SCRIPT = "classroomScript"

    /**
     * classroomScript 协议 → 过滤器；其余协议/缺省 → null（不过滤，streamingText
     * 保持原始累计——非课堂会话零行为变化）。[speakers] 恒非空（digSpeakers 各
     * 退化路径兜底 DEFAULT_SPEAKERS）——speakers.isNotEmpty() 判死分支已清（P5 清理批）。
     */
    fun ofOrNull(contentProtocol: String?, speakers: List<String>): ((String) -> String)? =
        if (contentProtocol == PROTOCOL_CLASSROOM_SCRIPT) of(speakers) else null

    fun of(speakers: List<String>): (String) -> String {
        val speakerLine = Regex(
            speakers.joinToString("|", prefix = "(?m)^\\[(?:", postfix = ")\\]") { Regex.escape(it) }
        )
        val tsComplete = Regex(
            Regex.escape(ContentProtocol.STATE_MARKER_BEGIN) +
                ".*?" + Regex.escape(ContentProtocol.STATE_MARKER_END),
            RegexOption.DOT_MATCHES_ALL,
        )
        return { raw ->
            var t = tsComplete.replace(raw, "")
            // 残缺扣留（完整标记已整剥，残留只有流中未收尾形态；截除点取最早者）：
            // - 未收尾标记体：残留的 STATE_MARKER_BEGIN 必无收尾——自其起点截除
            //   （其后字节全属标记体）；
            // - 跨 delta 切分的起始头真前缀 / 末行未合拢说话人前缀：尾巴是某完整
            //   形态的真前缀即自其起点扣留。
            val cut = listOfNotNull(
                // 首个残留 BEGIN（indexOf）——多个未收尾标记时与 ContentProtocol.extractState
                // 的"自首个未收尾起点截除"同语义（P4-T7 minor 一词改动，原 lastIndexOf
                // 会把更早的残留 `<!--TS:` 体漏进显示）
                t.indexOf(ContentProtocol.STATE_MARKER_BEGIN).takeIf { it >= 0 },
                partialHeadCut(t),
                partialSpeakerCut(t, speakers),
            ).minOrNull()
            if (cut != null) t = t.substring(0, cut)
            speakerLine.replace(t, "")
        }
    }

    /** 尾部是 TS 起始头的真前缀（如 "…<!--T"）→ 扣留起点；否则 null。取最长匹配（最早起点）。 */
    private fun partialHeadCut(t: String): Int? {
        val head = ContentProtocol.STATE_MARKER_BEGIN
        for (w in head.length - 1 downTo 1) {
            if (t.endsWith(head.substring(0, w))) return t.length - w
        }
        return null
    }

    /** 末行尾巴（最后一个 \n 之后）是某说话人标记 "[xx]" 的未合拢真前缀（如 "\n[老"）→ 扣留行首；否则 null。 */
    private fun partialSpeakerCut(t: String, speakers: List<String>): Int? {
        val lineStart = t.lastIndexOf('\n') + 1
        val tail = t.substring(lineStart)
        if (tail.isEmpty()) return null
        val partial = speakers.any { spk ->
            val full = "[$spk]"
            tail.length < full.length && full.startsWith(tail)
        }
        return if (partial) lineStart else null
    }
}

/**
 * 标题自动生成（P4 T2，语义迁自 ChatViewModel.generateTitleIfNeeded；六类型统一
 * 走此路径——四类型 run 级、聊天室/圆桌回合级经 [RoundAssembly.roundTitleTrigger]）：
 * - **单飞去重**（等价旧 titleJob.isActive）：在途未完成时新触发直接跳过——
 *   串行聊天室每回合触发一次 + 四类型每 run 触发，不去重会重复发起且两次都读旧标题；
 * - **familiar 豁免**：标题=卡名永不自动命名（判定源=skillId 列，P3 T7 统一单点；
 *   systemPrompt 也是空串运行时组装）；
 * - **旧默认名匹配**：仅默认标题触发生成（ConversationDefaults 两常量 + 英文旧值
 *   "New Chat"/"Chat Room" 照抄——保留历史会话自动命名）；
 * - **独立轻量请求**：llm 直驱单 turn（不经 AgentLoop/会话 hooks/链——无预算门、
 *   不入库、不驱动流式状态），system 文案照抄，预览=前 4 行"User/AI 名: 正文≤200"
 *   （sender 盖章行用角色名，无名 assistant="AI"）；
 * - **替换**：清洗（引号变体剥离/换行压空/take(20)——文案照抄）后非空且异于
 *   现值才 updateTitle。
 *
 * 请求装配：mode=NATIVE + 空 tools（WireConverter.buildSystem 对空工具直通 base，
 * 无注入污染）；searchAllowed=false；maxTokens=null（wire 键整体省略，任何后端
 * 都不发 max_tokens——Gson 默认不序列化 null）。
 * 已知微漂移（P4 终审评审 P2-4 裁定**接受并记录**——行为影响被清洗 take(20)
 * 钳住，只波及生成长度/耗时，不波及标题内容正确性；对照基线=内核清理 A 已删的
 * data 层旧客户端直驱路径）：
 * - max_tokens：旧直驱路径本地后端发 computeLocalMaxTokens(maxContext)（该公式
 *   今仅存于 ConnectionProbe 体检钳制），本路径不发；
 * - attachTimeContext：时间附注经 LlmClient 请求组装统一挂（尾部 user 附注），
 *   无字面漂移，列出仅为备查；
 * - thinkingEnabled=false：旧直驱路径曾传参（其末版已不再发禁思考键），本路径
 *   无此形参——wire 同形。
 * 已知迁移差异：旧直驱路径 stripThinkingTags 清洗——现客户端思考只存在于 Thinking 块
 * （spec §4.1），text 增量天然无思考标签，清洗仅保留引号/换行/take。
 * 失败吞并（catch 记日志语义）：标题失败不影响会话；scope 为宿主 hooksScope
 * （非链子协程，不进终态判据）。
 */
internal class TitleTrigger(
    private val llm: LlmClient,
    private val repo: SkillRepoPort,
    private val store: MessageStore,
    private val settings: SkillSettingsPort,
    private val scope: CoroutineScope,
) {

    @Volatile private var inFlight = false

    /** run/回合收线触发口（去重判定同步；生成为 scope.launch 异步，不阻塞收线）。 */
    fun trigger(conversationId: Long) {
        if (inFlight) return
        inFlight = true
        scope.launch {
            try {
                generate(conversationId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 旧路径 catch(Exception) 仅记日志：标题失败不影响会话
            } finally {
                inFlight = false
            }
        }
    }

    private suspend fun generate(conversationId: Long) {
        val conversation = repo.getConversation(conversationId) ?: return
        // familiar 会话标题=卡名，永不自动命名
        if (conversation.skillId == TypeInferer.FAMILIAR) return
        // Only generate if title is still default（保留英文旧值：历史会话也能自动命名）
        if (conversation.title !in DEFAULT_TITLES) return
        val messages = store.list(conversationId.toString())
        if (messages.isEmpty()) return

        // Build a summary from the first few messages
        val contentPreview = messages.take(4)
            .filter { textOf(it).isNotBlank() }
            .joinToString("\n") { m -> "${speakerLabel(m)}: ${textOf(m).take(200)}" }
        if (contentPreview.isBlank()) return

        // Generate title using LLM（独立轻量请求：单 turn 直驱，无 hooks/链/预算门）
        val sb = StringBuilder()
        val openTypes = HashMap<String, String>()
        llm.stream(
            LlmRequest(
                model = settings.currentModel(),
                system = TITLE_PROMPT,
                history = listOf(
                    AgentMessage(
                        id = "title-$conversationId",
                        role = Role.USER,
                        blocks = listOf(ContentBlock.Text(contentPreview)),
                    )
                ),
                tools = emptyList(),
                mode = ToolMode.NATIVE,
                temperature = null,
                maxTokens = null,
                searchAllowed = false,
                budgetLeft = { true },
            )
        ).collect { ev ->
            when (ev) {
                is StreamEvent.BlockStart -> openTypes[ev.blockId] = ev.type
                is StreamEvent.BlockEnd -> openTypes.remove(ev.blockId)
                is StreamEvent.BlockDelta -> if (openTypes[ev.blockId] == "text") sb.append(ev.delta)
                else -> {}
            }
        }

        // Clean up and save title（剥离可能混入的思考块——新客户端思考为独立块，
        // 此处仅引号/换行/take 清洗；文案照抄 generateTitleIfNeeded）
        val cleanTitle = sb.toString()
            .trim()
            .removeSurrounding("\"")
            .removeSurrounding("\"")
            .removeSurrounding("「", "」")
            .removeSurrounding("【", "】")
            .replace("\n", " ")
            .take(20)

        if (cleanTitle.isNotBlank() && cleanTitle != conversation.title) {
            repo.updateTitle(conversationId, cleanTitle)
        }
    }

    private fun speakerLabel(m: AgentMessage): String =
        if (m.role == Role.USER) "User" else (m.sender?.name ?: "AI")

    private fun textOf(m: AgentMessage): String =
        m.blocks.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }

    private companion object {
        /** 默认标题唯一来源 util/ConversationDefaults；英文旧值保留（generateTitleIfNeeded 同表） */
        val DEFAULT_TITLES = setOf(
            ConversationDefaults.NEW_CHAT,
            ConversationDefaults.CHAT_ROOM,
            "New Chat",
            "Chat Room",
        )

        /** 标题请求 system 文案（generateTitleIfNeeded 照抄） */
        const val TITLE_PROMPT = "根据以下对话内容，生成一个简短的标题（最多10个字）。只输出标题，不要输出其他内容。"
    }
}
