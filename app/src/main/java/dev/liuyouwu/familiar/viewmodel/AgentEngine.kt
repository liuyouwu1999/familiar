package dev.liuyouwu.familiar.viewmodel

import android.app.Application
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import dev.liuyouwu.familiar.data.local.AppDatabase
import dev.liuyouwu.familiar.data.local.RoomMessageStore
import dev.liuyouwu.familiar.data.local.entity.ChatRoomParticipantEntity
import dev.liuyouwu.familiar.data.local.entity.ConversationEntity
import dev.liuyouwu.familiar.data.local.entity.MessageEntity
import dev.liuyouwu.familiar.core.llm.client.DefaultLlmClient
import dev.liuyouwu.familiar.core.llm.client.Deps
import dev.liuyouwu.familiar.core.llm.client.LlmRequest
import dev.liuyouwu.familiar.core.llm.client.okHttpSseSource
import dev.liuyouwu.familiar.core.llm.marker.MarkerToolAdapter
import dev.liuyouwu.familiar.core.llm.model.AgentMessage
import dev.liuyouwu.familiar.core.llm.model.ContentBlock
import dev.liuyouwu.familiar.core.llm.model.Role
import dev.liuyouwu.familiar.core.llm.model.StreamEvent
import dev.liuyouwu.familiar.core.llm.wire.ToolMode
import dev.liuyouwu.familiar.core.llm.wire.WireConverter
import dev.liuyouwu.familiar.agent.SamplingParams
import dev.liuyouwu.familiar.data.repository.ChatRepository
import dev.liuyouwu.familiar.data.repository.SettingsRepository
import dev.liuyouwu.familiar.skills.SkillParams
import dev.liuyouwu.familiar.skills.SkillRegistry
import dev.liuyouwu.familiar.viewmodel.skillhooks.SkillRepoPort
import dev.liuyouwu.familiar.viewmodel.skillhooks.SkillSettingsPort

/**
 * 自 ChatViewModel 纯移动拆出（P5 T5）：Agent 引擎生产装配配料——SkillHost 每会话
 * 装配所需的设置快照/端口适配/LLM 客户端/消息 store/聊天室组装构件/压缩摘要器。
 * 原为 ChatViewModel 的私有成员（T10 落地），随 T5 拆分移入本类；字段名与行为
 * 逐行等价，AgentSessionController 经同名只读属性委托访问（调用点零改动）。
 */
internal class AgentEngineRuntime(
    private val db: AppDatabase,
    private val chatRepository: ChatRepository,
    private val settingsRepository: SettingsRepository,
    /** 工具面 T6：改 internal——SkillHost 构造位的 appContext 注入源（app 壳直传，禁回落 FamiliarApp.instance） */
    internal val context: Application,
) {

    /**
     * Agent 引擎设置快照（[SkillSettingsPort]/LlmClient Deps 的非挂起供应商数据源）：
     * DataStore 挂起读不可直供同步供应商，改为「快照 + 定点刷新」——挂载会话与每次
     * startAgentChain 启动时刷新（等价旧链"每请求前读一次设置"的新鲜度；run 内
     * 多 turn 共享同一快照，与旧链 doSend 单次读取同粒度）。VM 内改设置的入口
     * （switchModel 等）保存后旧链本就下一轮才生效，快照在下一轮启动时自然刷新。
     */
    class AgentSettingsCache {
        @Volatile var familiarMode: String = "default"
        @Volatile var familiarAddress: String = "老板"
        @Volatile var familiarSelfName: String = "我"
        @Volatile var petColor: String = "orange"
        @Volatile var familiarPacing: Boolean = true
        @Volatile var webSearchEnabled: Boolean = false
        // 请求诊断转储（审计 S4）：默认关——开则每次请求把完整请求体明文写入
        // last_request.json（诊断用）。随链刷新（agentLlm 是 by lazy 单例，
        // dumpDir 供应商每次写盘时读本快照，不得构造期定型）
        @Volatile var diagDumpEnabled: Boolean = false
        @Volatile var maxContext: Int = 4096
        @Volatile var currentModel: String = ""
        @Volatile var baseUrl: String = ""
        @Volatile var apiKey: String = ""
        @Volatile var providerId: String = ""
        // P4 T2：聊天室装配（Orchestrator maxTurnsPerRound / memberPrompt 的 address 参）
        @Volatile var chatRoomMaxReplies: Int = 3
        @Volatile var chatRoomAddress: String = "小刘鸭"
        // B2-L1：会话采样参数快照（refreshSamplingCache 每链刷新；null = 请求不携带）
        @Volatile var convSampling: SamplingParams? = null
    }

    val agentSettingsCache = AgentSettingsCache()

    suspend fun refreshAgentSettingsCache() {
        agentSettingsCache.apply {
            familiarMode = settingsRepository.getFamiliarMode()
            familiarAddress = settingsRepository.getFamiliarAddress()
            familiarSelfName = settingsRepository.getFamiliarSelfName()
            petColor = settingsRepository.getPetColor()
            familiarPacing = settingsRepository.isFamiliarPacing()
            webSearchEnabled = settingsRepository.webSearchEnabled.first()
            diagDumpEnabled = settingsRepository.diagDumpEnabled.first()
            maxContext = settingsRepository.getMaxContext()
            currentModel = settingsRepository.modelName.first()
            baseUrl = settingsRepository.getBaseUrl()
            apiKey = settingsRepository.getApiKey()
            providerId = settingsRepository.getProviderId()
            chatRoomMaxReplies = settingsRepository.getChatRoomMaxReplies()
            chatRoomAddress = settingsRepository.getChatRoomAddress()
        }
    }

    /**
     * 会话采样参数刷新（B2-L1）：startAgentChain 每链调用（在 refreshAgentSettingsCache
     * 之后、action 之前）——生效粒度=链（spec §3.4：技能管理页改动自下一条消息生效）。
     * 会话行/registry 缺失 → null（请求不携带）。
     */
    suspend fun refreshSamplingCache(conversationId: Long, registry: SkillRegistry) {
        val conv = chatRepository.getConversationById(conversationId)
        val def = conv?.skillId?.let { registry.get(it) }
        agentSettingsCache.convSampling = def?.let { SkillParams.samplingOf(it, conv.skillParams) }
    }

    /** SkillHost 设置端口适配（快照直读）。 */
    val agentSettingsPort = object : SkillSettingsPort {
        override fun familiarMode(): String = agentSettingsCache.familiarMode
        override fun familiarAddress(): String = agentSettingsCache.familiarAddress
        override fun familiarSelfName(): String = agentSettingsCache.familiarSelfName
        override fun petColor(): String = agentSettingsCache.petColor
        override fun webSearchEnabled(): Boolean = agentSettingsCache.webSearchEnabled
        override fun maxContext(): Int = agentSettingsCache.maxContext
        override fun currentModel(): String = agentSettingsCache.currentModel
        // P4 T2：聊天室装配设置（Orchestrator maxTurnsPerRound / memberPrompt 的 address 参）
        override fun chatRoomMaxReplies(): Int = agentSettingsCache.chatRoomMaxReplies
        override fun chatRoomAddress(): String = agentSettingsCache.chatRoomAddress
        // B2-L1：链级采样参数（快照直读）
        override fun convSampling(): SamplingParams? = agentSettingsCache.convSampling
    }

    /** SkillHost 仓库端口适配（familiar 切段/课堂 TS 落列委托 ChatRepository 同名方法）。 */
    val agentRepoPort = object : SkillRepoPort {
        override suspend fun updateSegments(messageId: Long, segmentsJson: String?) =
            chatRepository.updateSegments(messageId, segmentsJson)

        override suspend fun updateTutorState(conversationId: Long, stateJson: String) =
            chatRepository.updateTutorState(conversationId, stateJson)

        // P4 T2：聊天室成员表回退读 / 会话行读 / 标题写（TitleTrigger 替换路径）
        override suspend fun getParticipants(conversationId: Long) =
            db.chatRoomParticipantDao().getParticipantsForConversationSync(conversationId)

        override suspend fun getConversation(conversationId: Long) =
            chatRepository.getConversationById(conversationId)

        override suspend fun updateTitle(conversationId: Long, title: String) =
            chatRepository.updateConversationTitle(conversationId, title)
    }

    /** hooks 宿主 scope（T6：repo 写/回调发射；IO 常驻——非链子协程不进终态判据）。 */
    val agentHooksScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 工具面 T6：改 internal——SkillHost 构造位的 http_get 基座 client 注入源（agentLlm 同源）。 */
    internal val agentOkHttp by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    val agentLlm by lazy {
        DefaultLlmClient(
            deps = Deps(
                okHttp = agentOkHttp,
                sseSource = okHttpSseSource(agentOkHttp),
                baseUrl = { agentSettingsCache.baseUrl },
                apiKey = { agentSettingsCache.apiKey },
                provider = { agentSettingsCache.providerId },
                // 诊断落盘目录供应商（P1 终审挂点，P4 T8 + 审计 S4 开关化）：app 壳目录
                // 在此注入，core/llm 不再直读 FamiliarApp.instance（越层引用清除）。
                // agentLlm 是 by lazy 单例——开关不得构造期定型，供应商每次写盘时读
                // 设置快照（快照随链刷新，改动自下一条消息生效，无需重启）
                dumpDir = { if (agentSettingsCache.diagDumpEnabled) context.getExternalFilesDir(null) else null },
                // B2-L1 penalty 降级记录缝（Task 2 建缝，此处生产装配点接线）：
                // 逐参剥除重发的原因文案落 logcat 诊断
                onPenaltyFallback = { android.util.Log.w("LlmClient", it) },
                // 诊断落盘失败记录缝（审计 S4，照 onPenaltyFallback 同点接线）
                onDumpFailure = { android.util.Log.w("LlmClient", it) },
            ),
            wire = WireConverter(),
            marker = MarkerToolAdapter(),
        )
    }

    val agentStore by lazy { RoomMessageStore(db.messageDao(), chatRepository, db.conversationDao(), db) }

    /**
     * 聊天室/圆桌组装构件（P4 T3）：建室锚点渲染（openingMessage 行为）与圆桌 skill
     * JSON 预设解析与 [SkillHost] 同件同源——repo/address 端口同 [agentRepoPort]/
     * [agentSettingsCache]（SkillHost 每会话自建一份，此处独立实例仅服务建室/预填，
     * 不承载回合装配）。
     */
    val chatRoomAssembler by lazy {
        dev.liuyouwu.familiar.viewmodel.skillhooks.ChatRoomHooks(
            agentRepoPort,
            address = { agentSettingsCache.chatRoomAddress },
        )
    }

    /**
     * 压缩摘要器（T10，四类型 compact）：loop 同款单 turn 摘要请求经 [agentLlm]——
     * 指令文案照抄旧 compactConversation（"请把以下对话压缩成…只输出摘要本身"），
     * 60 条/每条 300 字的防超窗截断按行等价保留（AgentSession 传入的是全量行拼接文本）。
     */
    suspend fun compactSummarizer(historyText: String): String {
        val bounded = historyText.lines().takeLast(60).joinToString("\n") { it.take(300) }
        val req = LlmRequest(
            model = agentSettingsCache.currentModel,
            system = "请把以下对话压缩成一份简洁的摘要，保留关键信息（用户的需求、已达成的结论、重要数据），供后续对话作为上下文使用。只输出摘要本身：",
            history = listOf(
                AgentMessage(
                    id = "compact-in", role = Role.USER,
                    blocks = listOf(ContentBlock.Text(bounded)),
                )
            ),
            tools = emptyList(),
            mode = ToolMode.MARKER,
            temperature = null,
            maxTokens = null,
            searchAllowed = false,
            budgetLeft = { true },
        )
        val sb = StringBuilder()
        val openTypes = HashMap<String, String>()
        agentLlm.stream(req).collect { ev ->
            when (ev) {
                is StreamEvent.BlockStart -> openTypes[ev.blockId] = ev.type
                is StreamEvent.BlockEnd -> openTypes.remove(ev.blockId)
                is StreamEvent.BlockDelta -> if (openTypes[ev.blockId] == "text") sb.append(ev.delta)
                is StreamEvent.Error -> throw ev.error
                else -> {}
            }
        }
        if (sb.isBlank()) throw IllegalStateException("模型未返回内容")
        return sb.toString()
    }
}

/**
 * 自 ChatViewModel 纯移动拆出（P5 T5）：聊天室/圆桌建室与成员域——建室一行 INSERT
 * （participants 表+skillParams 双写）、成员全量替换事务、圆桌角色预填、成员流订阅。
 * ChatViewModel 公开签名经委托保持不变；行为与拆分前逐行等价。
 */
internal class ChatRoomSetupController(
    private val scope: CoroutineScope,
    private val db: AppDatabase,
    private val chatRepository: ChatRepository,
    private val gson: com.google.gson.Gson,
    private val isLoading: StateFlow<Boolean>,
    private val skillRegistryProvider: () -> SkillRegistry,
    private val engine: AgentEngineRuntime,
) {

    private val skillRegistry: SkillRegistry by lazy(skillRegistryProvider)
    private val chatRoomAssembler get() = engine.chatRoomAssembler

    /**
     * 创建聊天室/学习圆桌（P4 T3 委托会话服务，Task 9 双模板）。
     *
     * 一行 INSERT 携带 skillId/skillParams/isChatRoom 旧列（D6 会话服务形态）——
     * 不走 [ChatRepository.createConversationWithSkill]：其 `Map<String,String>`
     * 参数契约装不下 members **数组**值（新链读侧 [dev.liuyouwu.familiar.viewmodel.skillhooks.ChatRoomHooks.parseMembers] 要求
     * 真数组，字符串值会被滤除/回退表），故按同一约定直写实体。不写 tutorConfig/
     * systemPrompt（聊天室运行期组装，memberPrompt 经 systemOverride 生效）。
     *
     * P5 T4b 原名 createChatRoom——同批删除的三个 creator 壳均为会话服务薄壳，
     * 本函数不是（P4 Ruling：participants 表+旧列双写委托续命），改名去歧义。
     *
     * - skillId=chatroom/roundtable（模板分流）；skillParams=`{"members":[…]}`+
     *   圆桌附 `topic`（[dev.liuyouwu.familiar.viewmodel.skillhooks.ChatRoomHooks.membersArray] 产出，读侧对偶契约）；
     * - **participants 表行 + isChatRoom 旧列双写**【对 spec"旧列成死代码 P5 删"的
     *   有意偏离：委托续命让编辑路径/旧版本兼容更稳——Ruling 记台账】：表=存量回退
     *   读源+旧版本运行+编辑页初始化源；列=ChatScreen 判定源；
     * - roundtable：课题即会话标题（非 CHAT_ROOM 常量，自动命名不覆盖）+ 建室注入
     *   锚点 user 消息（openingMessage 行为渲染 skill JSON 模板——调度锚点，
     *   ChatRepository.insertMessage 为嵌套事务，随外层原子提交）。
     */
    suspend fun createChatRoomConversation(
        template: String,
        participants: List<dev.liuyouwu.familiar.ui.screen.chatroom.ParticipantDraft>,
        topic: String = "",
        material: String? = null,
    ): Long = db.withTransaction {
        val roundtable = template == dev.liuyouwu.familiar.ui.screen.chatroom.ChatRoomTemplates.ROUNDTABLE
        val trimmedTopic = topic.trim()
        // 圆桌可选学习材料（Task 5）：trim 后空白不加键（读侧 chatroomParams 字符串值
        // 透传 → MaterialSection.section 空=无材料）；闲聊群无此概念，键只随圆桌写
        val trimmedMaterial = material?.trim().orEmpty()
        val members = participants.mapIndexed { index, p -> p.toParticipantEntity(index) }
        val params = com.google.gson.JsonObject().apply {
            if (roundtable) addProperty("topic", trimmedTopic)
            if (roundtable && trimmedMaterial.isNotEmpty()) addProperty("material", trimmedMaterial)
            // 学习画像快照（Task 7）：圆桌建室时按课题词元检索活跃条目（T1 DAO allActive
            // 已按 createdAt DESC 排序，即 ProfileMatcher 契约序）→ ProfileMatcher.select
            // → ProfileRenderer.render；渲染 null（无条目/无匹配）不加键——读侧
            // chatroomParams 字符串值透传，键缺失 = 段 null = 零拼接（与 material 键同一
            // 缺席纪律，运行期不重查，每回合经 chatroomParams 新鲜透传本快照）。仅圆桌写
            // （闲聊群无课题，同 material 键的 roundtable 门）。runCatching 宁缺不崩：
            // 检索失败只 Log.w，不阻断建室（CancellationException 原样重抛，协作取消不吞）。
            if (roundtable) {
                runCatching {
                    dev.liuyouwu.familiar.skills.profile.ProfileRenderer.render(
                        dev.liuyouwu.familiar.skills.profile.ProfileMatcher.select(
                            trimmedTopic,
                            db.learningProfileDao().allActive(),
                        )
                    )?.let { addProperty("profile", it) }
                }.onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    android.util.Log.w("LearningProfile", "画像快照失败", it)
                }
            }
            add("members", dev.liuyouwu.familiar.viewmodel.skillhooks.ChatRoomHooks.membersArray(members))
        }
        val convId = db.conversationDao().insertConversation(
            ConversationEntity(
                title = if (roundtable) trimmedTopic else dev.liuyouwu.familiar.util.ConversationDefaults.CHAT_ROOM,
                skillId = if (roundtable) dev.liuyouwu.familiar.skills.TypeInferer.ROUNDTABLE
                else dev.liuyouwu.familiar.skills.TypeInferer.CHATROOM,
                skillParams = params.toString(),
                isChatRoom = true,   // 旧列双写（Ruling 台账，见类 KDoc）
            )
        )
        for (m in members) {
            db.chatRoomParticipantDao().insertParticipant(m.copy(conversationId = convId))
        }
        // 学习圆桌：建室即注入课题开场（讲师开场的调度锚点；openingMessage 行为，
        // 与运行期 SkillHost.roundFor 同一渲染口径）
        if (roundtable) {
            val opening = skillRegistry.get(dev.liuyouwu.familiar.skills.TypeInferer.ROUNDTABLE)
                ?.let { dev.liuyouwu.familiar.skills.Behaviors(it) }
                ?.let { chatRoomAssembler.openingMessage(it, mapOf("topic" to trimmedTopic)) }
            if (!opening.isNullOrBlank()) {
                chatRepository.insertMessage(
                    MessageEntity(conversationId = convId, role = "user", content = opening)
                )
            }
        }
        convId
    }

    /**
     * 成员草稿 → participants 表实体（建室/编辑两写点的同形映射；sortOrder=列表序）。
     */
    private fun dev.liuyouwu.familiar.ui.screen.chatroom.ParticipantDraft.toParticipantEntity(
        sortOrder: Int,
    ): ChatRoomParticipantEntity = ChatRoomParticipantEntity(
        conversationId = 0L,   // 建室时行 id 未定，插入前 copy 补
        modelName = modelName,
        displayName = displayName,
        personality = personality,
        colorHex = colorHex,
        identity = identity,
        traits = traits,
        speechStyle = speechStyle,
        catchphrase = catchphrase,
        exampleDialogs = exampleDialogs,
        sortOrder = sortOrder,
    )

    /**
     * 学习圆桌 skill JSON 预设角色（P4 T3：SetupScreen 三角色卡预填数据源，
     * RoundtablePrompts 引用退役）：名称/提示词即运行期 [dev.liuyouwu.familiar.viewmodel.skillhooks.ChatRoomHooks.presetRoles]
     * 的同一解析（圆桌角色固定自 skill JSON）。注册表缺 roundtable（APK 资产损坏）
     * 返回空表——SetupScreen 无预填则建室门槛不满足。
     */
    fun roundtableRolePresets(): List<dev.liuyouwu.familiar.ui.screen.chatroom.RoundtableRolePreset> {
        val behaviors = skillRegistry.get(dev.liuyouwu.familiar.skills.TypeInferer.ROUNDTABLE)
            ?.let { dev.liuyouwu.familiar.skills.Behaviors(it) }
            ?: return emptyList()
        return chatRoomAssembler.presetRoles(behaviors)
            .map { dev.liuyouwu.familiar.ui.screen.chatroom.RoundtableRolePreset(it.name, it.prompt) }
    }

    /**
     * 保存聊天室成员（全量替换：先删后插 + skillParams.members 同步写，同一事务）。
     *
     * 返回值语义（供 UI 判定）：
     * - null：保存成功；
     * - 非 null：可直接展示给用户的错误文案（生成进行中 / 保存失败），不抛业务异常。
     *
     * 调用方须在协程中调用并把返回值驱动为 snackbar/提示，例如：
     * `scope.launch { vm.updateChatRoomParticipants(id, drafts)?.let { showSnack(it) } }`
     *
     * 双写说明（P4 T3）：participants 表 = 存量回退读源（skillParams 无 members 的
     * 旧行）+ 旧版本运行 + 编辑页初始化源；skillParams.members = 新链读侧优先源
     * （[dev.liuyouwu.familiar.viewmodel.skillhooks.ChatRoomHooks.memberRoles]）——两载体同事务原子同步，params 其余键（圆桌
     * topic 等）原样保留。运行期角色逐回合新鲜解析，保存即下回合生效。
     *
     * 注意：本 ViewModel 已是 Activity 级单例（导航层共享），isLoading 的生成互斥
     * 对聊天页/编辑页全局生效；生成中进入编辑页会被守卫拒绝。
     */
    suspend fun updateChatRoomParticipants(
        conversationId: Long,
        participants: List<dev.liuyouwu.familiar.ui.screen.chatroom.ParticipantDraft>
    ): String? {
        // 同实例生成中：直接拒绝（跨实例场景见 KDoc 说明）
        if (isLoading.value) return "生成中，先别改成员"
        return try {
            db.withTransaction {
                val members = participants.mapIndexed { index, p -> p.toParticipantEntity(index) }
                db.chatRoomParticipantDao().deleteParticipantsForConversation(conversationId)
                members.forEach { m ->
                    db.chatRoomParticipantDao().insertParticipant(m.copy(conversationId = conversationId))
                }
                // skillParams.members 同步写（新链读侧优先源；其余参数键保留——存量回退
                // 依赖表与 members 的同步，见 KDoc 双写说明）
                val current = db.conversationDao().getConversationById(conversationId)?.skillParams
                val obj = current?.takeIf { it.isNotBlank() }
                    ?.let { runCatching { gson.fromJson(it, com.google.gson.JsonObject::class.java) }.getOrNull() }
                    ?: com.google.gson.JsonObject()
                obj.remove("members")
                obj.add("members", dev.liuyouwu.familiar.viewmodel.skillhooks.ChatRoomHooks.membersArray(members))
                db.conversationDao().updateSkillParams(conversationId, obj.toString())
            }
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            "保存成员失败：${(e.message ?: "未知错误").lineSequence().firstOrNull().orEmpty().take(60)}"
        }
    }

    /**
     * 主持人：读群聊记录，决定下一个发言的角色；任何失败返回 Unknown（调用方轮换兜底）
     */
    fun getChatRoomParticipants(conversationId: Long): StateFlow<List<ChatRoomParticipantEntity>> {
        return db.chatRoomParticipantDao().getParticipantsForConversation(conversationId)
            .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())
    }
}
