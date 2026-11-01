package dev.liuyouwu.familiar.viewmodel

import android.app.Application
import android.net.Uri
import android.os.PowerManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.liuyouwu.familiar.data.local.AppDatabase
import dev.liuyouwu.familiar.data.local.entity.ChatRoomParticipantEntity
import dev.liuyouwu.familiar.data.local.entity.ConversationEntity
import dev.liuyouwu.familiar.data.local.entity.MessageEntity
import dev.liuyouwu.familiar.data.local.entity.PersonaPresetEntity
import dev.liuyouwu.familiar.agent.AgentSession
import dev.liuyouwu.familiar.core.llm.model.AgentMessage
import dev.liuyouwu.familiar.core.llm.model.ContentBlock
import dev.liuyouwu.familiar.core.llm.model.Role
import dev.liuyouwu.familiar.core.llm.model.SenderInfo
import dev.liuyouwu.familiar.core.llm.model.StreamEvent
import dev.liuyouwu.familiar.core.llm.wire.ModelInfo
import dev.liuyouwu.familiar.core.llm.wire.ToolMode
import dev.liuyouwu.familiar.core.llm.client.LlmRequest
import dev.liuyouwu.familiar.data.remote.api.ModelsApi
import dev.liuyouwu.familiar.data.remote.api.Transcriber
import dev.liuyouwu.familiar.skills.QuickCommand
import dev.liuyouwu.familiar.skills.SkillRegistry
import dev.liuyouwu.familiar.skills.TypeInferer
import dev.liuyouwu.familiar.skills.profile.Instruction
import dev.liuyouwu.familiar.skills.profile.LearningReview
import dev.liuyouwu.familiar.skills.profile.RoundtableSummaryParser
import dev.liuyouwu.familiar.skills.profile.RoundtableSummaryTexts
import dev.liuyouwu.familiar.skills.profile.SingleFlight
import dev.liuyouwu.familiar.ui.model.MessageRenderModel
import dev.liuyouwu.familiar.ui.model.UiMessage
import dev.liuyouwu.familiar.data.repository.ChatRepository
import dev.liuyouwu.familiar.data.repository.SettingsRepository
import dev.liuyouwu.familiar.pet.PetSkin
import dev.liuyouwu.familiar.pet.PetSkinStore
import dev.liuyouwu.familiar.skills.FamiliarDefaults
import dev.liuyouwu.familiar.util.memory.FamiliarMemory
import dev.liuyouwu.familiar.util.memory.FamiliarMemoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ===== 附件与语音相关类型 =====

enum class AttachmentType { IMAGE, FILE, VIDEO }

data class AttachmentInfo(
    val uri: Uri,
    val type: AttachmentType,
    val displayName: String,
    val base64: String? = null,
    val fileTextContent: String? = null,
    val videoFrames: List<String>? = null
)

/**
 * 上下文容量分类统计（估算口径与 agent/ContextBuilder 同源——estimateTokens/
 * reserveForOutput，供"对话设置"里的容量面板展示）。used = 消息 + 附件 + 系统提示词，
 * 与实际请求发送的 prompt 同口径；free 是扣除输出预留 [reserve] 后窗口里的剩余空间，
 * 可能为 0（超窗时裁剪会挤出旧消息）。
 */
data class ContextBreakdown(
    val messages: Int,
    val attachments: Int,
    val systemPrompt: Int,
    val reserve: Int,
    val maxContext: Int
) {
    val used: Int get() = messages + attachments + systemPrompt
    val free: Int get() = (maxContext - used - reserve).coerceAtLeast(0)
}


/** 首页主精灵卡数据：conversationId=null 表示尚无 familiar 会话（点击时新建） */
data class FamiliarHeaderState(
    val conversationId: Long?,
    val cardName: String,
    val colorHex: String,
    val address: String,
)

/**
 * 回复引用目标（Agent 内核 P3 T9）：ChatScreen 持有的是 [UiMessage]（行 id 字符串），
 * 旧链落库只消费 id/role/content 三字段——不再用 [MessageEntity] 整行承载，
 * 消除"渲染模型→实体"的逆向拼装。null = 取消回复。
 */
data class ReplyTarget(
    val id: Long,
    val role: String,
    val content: String,
)

/**
 * 截断提示标记：唯一来源 viewmodel/RunMarkerTexts（内核清理 A，Task 4 迁出注入——
 * 内核经 SkillHost 注入的 RunMarkers 读同一字面；新链落库修饰 truncated/NO_ANSWER
 * 由 AgentSession 经注入 RunMarkers 直写块列）。
 *
 * P5 T5：本体拆为 AgentSession/Export/Voice 三个 Controller + AgentEngine（配料），
 * 纯移动行为零变化；本类保留依赖装配、StateFlow 声明（经构造注入回写）、
 * familiar/设置域与公开面委托——**公开 API 签名不变**，ChatScreen 等调用点零改动。
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Application = getApplication()
    private val db = AppDatabase.getInstance(context)
    private val chatRepository = ChatRepository(
        conversationDao = db.conversationDao(),
        messageDao = db.messageDao(),
        promptTemplateDao = db.promptTemplateDao(),
        db = db
    )
    private val settingsRepository = SettingsRepository(context)
    private val modelsApi = ModelsApi()
    private val transcriber = Transcriber()

    private val gson = com.google.gson.Gson()

    // familiar 长期记忆（跨会话共享一份文件；构造即 ensure，读失败回退空、写失败不阻塞对话）
    private val memoryStore by lazy {
        FamiliarMemoryStore(context.filesDir) { msg, e ->
            if (e != null) Log.w("FamiliarMemory", msg, e) else Log.w("FamiliarMemory", msg)
        }
    }

    // skill 注册表（assets 惰性加载；快捷指令条/类型判定数据源。T10 装配 SkillHost
    // 时复用同一实例，避免双份注册表）
    private val skillRegistry by lazy {
        SkillRegistry.fromAssets(context.assets, "skills") { msg -> Log.w("SkillRegistry", msg) }
    }

    // ===== Core streaming state =====
    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText

    private val _streamingReasoning = MutableStateFlow("")
    val streamingReasoning: StateFlow<String> = _streamingReasoning

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    // familiar 拟人节奏：活跃接收中的打字指示（精灵名；null = 非熟悉会话或未在接收）
    private val _familiarTypingName = MutableStateFlow<String?>(null)
    val familiarTypingName: StateFlow<String?> = _familiarTypingName

    // familiar 曝光态：正在逐段曝光的消息（id → 已曝光段数，0 = 第一段未亮）。
    // null = 无进行中的分段动画；segments != null 的消息默认全曝光（重进会话/进程重建同款）
    private val _familiarReveal = MutableStateFlow<Pair<Long, Int>?>(null)
    val familiarReveal: StateFlow<Pair<Long, Int>?> = _familiarReveal

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _tokenCount = MutableStateFlow(0)
    val tokenCount: StateFlow<Int> = _tokenCount

    private val _tokensPerSecond = MutableStateFlow(0.0)
    val tokensPerSecond: StateFlow<Double> = _tokensPerSecond

    /** 最近一次请求的缓存命中率（百分比 0-100）；后端不支持用量统计时为 null */
    private val _lastCacheHit = MutableStateFlow<Int?>(null)
    val lastCacheHit: StateFlow<Int?> = _lastCacheHit

    /** 收线后的真实上下文占用（prompt+completion，token）；空闲态顶栏显示源，0=尚无上报 */
    private val _lastContextTokens = MutableStateFlow(0)
    val lastContextTokens: StateFlow<Int> = _lastContextTokens

    /** 搜索进行中的状态文案（"正在搜索：xxx"），null=不在搜索；驱动"生成中"指示行。
     *  旧链写点（doSendChatRoom/generateCharacterReply）已随 T5 删除——现恒 null，
     *  新引擎指示走 streamingToolStatus；字段保留供 ChatScreen 回退读，待 P5 清理。 */
    private val _searchStatus = MutableStateFlow<String?>(null)
    val searchStatus: StateFlow<String?> = _searchStatus

    // Current model
    val currentModel: StateFlow<String> = settingsRepository.modelName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    private val _availableModels = MutableStateFlow<List<ModelInfo>>(emptyList())
    val availableModels: StateFlow<List<ModelInfo>> = _availableModels

    // System prompt
    private val _systemPrompt = MutableStateFlow("")
    val systemPrompt: StateFlow<String> = _systemPrompt

    // Reply target（P3 T9：UiMessage 三字段载体 [ReplyTarget]，旧链读 .id/.content/.role 不变）
    private val _replyingTo = MutableStateFlow<ReplyTarget?>(null)
    val replyingTo: StateFlow<ReplyTarget?> = _replyingTo

    // Attachments
    private val _selectedAttachments = MutableStateFlow<List<AttachmentInfo>>(emptyList())
    val selectedAttachments: StateFlow<List<AttachmentInfo>> = _selectedAttachments

    // Video frame count setting
    private val _videoFrameCount = MutableStateFlow(20)
    val videoFrameCount: StateFlow<Int> = _videoFrameCount

    // Max context tokens
    // 连接成功时 ConnectionViewModel 会把服务器真实上下文写入 DataStore，
    // 这里用流驱动自动跟随，无需重进会话
    val maxContext: StateFlow<Int> = settingsRepository.maxContext
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000),
            dev.liuyouwu.familiar.data.repository.SettingsRepository.DEFAULT_MAX_CONTEXT)

    // Thinking toggle
    private val _thinkingEnabled = MutableStateFlow(false)
    val thinkingEnabled: StateFlow<Boolean> = _thinkingEnabled

    // 聊天室每轮发言数（必须声明在 init 之前：init 协程会立即写它）
    private val _chatRoomMaxReplies = MutableStateFlow(3)
    val chatRoomMaxReplies: StateFlow<Int> = _chatRoomMaxReplies

    // ===== TTS 朗读 / Voice input =====
    // 状态流声明留在 VM（公开面原样）；TtsManager 惰性实例与朗读链随 T5 拆入 [VoiceController]
    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking
    private val _autoReadEnabled = MutableStateFlow(false)
    val autoReadEnabled: StateFlow<Boolean> = _autoReadEnabled

    private val _voiceState = MutableStateFlow(VoiceState.IDLE)
    val voiceState: StateFlow<VoiceState> = _voiceState

    private val _voiceResult = MutableStateFlow<String?>(null)
    val voiceResult: StateFlow<String?> = _voiceResult

    /** 服务端转写可用性（拆分后字段随录音/转写逻辑住在 [VoiceController]，原样暴露）。 */
    var serverWhisperAvailable: Boolean?
        get() = voice.serverWhisperAvailable
        private set(value) { voice.serverWhisperAvailable = value }

    // Persona presets
    val personaPresets: StateFlow<List<PersonaPresetEntity>> = db.personaPresetDao()
        .getAllPresets()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Wake lock: keep screen on during long streaming
    private val wakeLock =
        (context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "familiar:chat_stream")

    // ===== P5 T5 Controller 拆分（纯移动拆出，构造注入依赖与回写通道） =====

    /** 停止代数/链元数据共享读通道（agent 写、voice 桥读，字段语义见其 KDoc）。 */
    private val chainGuard = AgentChainGuard()

    private val voice = VoiceController(
        viewModelScope, context, chatRepository, settingsRepository, transcriber,
        error = _error, isSpeaking = _isSpeaking, autoReadEnabled = _autoReadEnabled,
        voiceState = _voiceState, voiceResult = _voiceResult,
        familiarReveal = _familiarReveal, chainGuard = chainGuard,
    )

    private val agentSession = AgentSessionController(
        viewModelScope, context, db, chatRepository, settingsRepository,
        skillRegistryProvider = { skillRegistry }, memoryStoreProvider = { memoryStore },
        wakeLock = wakeLock, chainGuard = chainGuard, voice = voice,
        skillIdOf = { skillIdOf(it) },
        error = _error, isLoading = _isLoading,
        streamingText = _streamingText, streamingReasoning = _streamingReasoning,
        familiarTypingName = _familiarTypingName,
        tokenCount = _tokenCount, tokensPerSecond = _tokensPerSecond, lastCacheHit = _lastCacheHit,
        lastContextTokens = _lastContextTokens,
        replyingTo = _replyingTo, selectedAttachments = _selectedAttachments,
        autoReadEnabled = _autoReadEnabled, searchStatus = _searchStatus,
        familiarReveal = _familiarReveal,
    )

    private val export = ExportController(
        viewModelScope, context, chatRepository, currentModel,
        error = _error, selectedAttachments = _selectedAttachments, videoFrameCount = _videoFrameCount,
    )

    /** 聊天室建室/成员域（自 ChatViewModel 纯移动拆出；复用 agentSession 的引擎配料）。 */
    private val chatRoomSetup = ChatRoomSetupController(
        viewModelScope, db, chatRepository, gson, _isLoading,
        skillRegistryProvider = { skillRegistry },
        engine = agentSession.engine,
    )

    // ===== familiar 首页卡 / 设置（ChatListScreen「精灵」分区读写）=====

    /**
     * 会话 → skillId（P3 T9 判定源统一）：skillId 列直取，null（v16 后旧 creator 建的
     * 行 / 备份导入未回填，P5 备份适配）经 TypeInferer 六序反推兜底——与
     * [SkillHost.skillIdOf] 同口径。ChatScreen 的 familiar/eli5/classroom 判定与
     * 快捷指令条同读此源（FamiliarKind/Eli5Prompts 不再是判定源）。
     */
    fun skillIdOf(conversation: ConversationEntity): String =
        conversation.skillId
            ?: TypeInferer.infer(conversation.tutorConfig, conversation.isChatRoom, roundtableAnchorCount = 0)

    /** familiar 会话判定（T7 判定源切换，T9 收敛到 [skillIdOf] 单点）。 */
    private fun isFamiliarConversation(conv: ConversationEntity): Boolean =
        skillIdOf(conv) == TypeInferer.FAMILIAR

    /** 首页主精灵卡：记住的会话 id → 会话行（删除即回退默认卡）→ 卡名/卡色/当前称呼 */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val familiarHeader: StateFlow<FamiliarHeaderState> =
        settingsRepository.familiarConversationId
            .flatMapLatest { convId ->
                if (convId == null) flowOf(null)
                else chatRepository.observeConversation(convId)
            }
            .combine(settingsRepository.familiarAddress) { conv, address -> conv to address }
            .combine(settingsRepository.petColor) { (conv, address), color ->
                // 非 familiar 会话行（理论不该出现）视同无会话，避免点进普通会话
                val familiarConv = conv?.takeIf { isFamiliarConversation(it) }
                // 名字/主色随皮肤（personaOf 四分支：内置蓝/橘原卡、皮肤 persona 字段级回落、被删回落蓝卡）
                // withContext(IO)：combine 逐发射执行且 stateIn 跑 Main，现扫皮肤目录须离主线程
                val fallback = withContext(Dispatchers.IO) {
                    dev.liuyouwu.familiar.util.persona.DefaultPersonaCards.personaOf(PetSkinStore.get(color))
                }
                FamiliarHeaderState(
                    conversationId = familiarConv?.id,
                    cardName = fallback.name,
                    colorHex = fallback.colorHex,
                    address = address,
                )
            }
            .stateIn(
                viewModelScope, SharingStarted.WhileSubscribed(5000),
                FamiliarHeaderState(null, FamiliarDefaults.DEFAULT_NAME, "#1976D2", "老板")
            )

    // null = DataStore 真值未回（stateIn 首个同步值）。UI 锁存/选中一律以非 null 为准：
    // 非空占位默认会被当真值锁进设置输入框，保存时把用户自定义静默打回默认（审查 fix1）
    val familiarMode: StateFlow<String?> = settingsRepository.familiarMode
        .map { it as String? }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val familiarAddress: StateFlow<String?> = settingsRepository.familiarAddress
        .map { it as String? }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** 联网搜索开关：作用于大肥鱼对话与聊天室（课堂/圆桌始终开启）。默认关 */
    val webSearchEnabled: StateFlow<Boolean> = settingsRepository.webSearchEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setWebSearchEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.saveWebSearchEnabled(enabled) }
    }

    // ===== Agent 会话事件桥（T5 拆入 AgentSessionController，公开流原样暴露） =====

    /** 工具进度文案桥：SessionState.streamingToolStatus（"正在搜索：xxx"）；未挂载 = null。 */
    val streamingToolStatus: StateFlow<String?> get() = agentSession.streamingToolStatus

    /** 流式发送者桥（P4 T4）：流式中暴露聊天室/圆桌角色 run 的盖章位；间隙归 null。 */
    val streamingSender: StateFlow<SenderInfo?> get() = agentSession.streamingSender

    /** 会话错误桥：SessionState.lastError（run 硬失败消息，下一 run 起点清除）；未挂载 = null。 */
    val sessionLastError: StateFlow<String?> get() = agentSession.sessionLastError

    /** 容量口径同源桥（评审 P0-1）：SessionState.contextBreakdown 五字段同形映射；未挂载 = null。 */
    val sessionContextBreakdown: StateFlow<ContextBreakdown?> get() = agentSession.sessionContextBreakdown

    // ===== 会话挂载与五入口（T5 拆入 AgentSessionController，签名原样委托） =====

    /** 挂载 active 会话的 [AgentSession]（null = 卸载；实现见 AgentSessionController.attachSession）。 */
    fun attachSession(session: AgentSession?) = agentSession.attachSession(session)

    fun stopGeneration() = agentSession.stopGeneration()

    fun sendMessage(conversationId: Long, text: String) = agentSession.sendMessage(conversationId, text)

    /** 排队追问（P-steer spec §4.2）：生成中长按发送，user 行即时落库，收线后自动续跑；降级路径见控制器。 */
    fun queueFollowUp(conversationId: Long, text: String) = agentSession.queueFollowUp(conversationId, text)

    /** 排队中的追问（UI 横幅数据源；null=无排队）。 */
    val pendingFollowUpUi: StateFlow<QueuedFollowUp?> = agentSession.pendingFollowUpUi

    /** 取消排队（横幅「取消」）：撤自动兜答意图，已发出的消息保留。 */
    fun cancelQueuedFollowUp() = agentSession.cancelFollowUp()

    fun regenerateLastResponse(conversationId: Long, modelOverride: String? = null) =
        agentSession.regenerateLastResponse(conversationId, modelOverride)

    fun continueGeneration(conversationId: Long) = agentSession.continueGeneration(conversationId)

    fun editMessageAndTruncate(conversationId: Long, messageId: Long, newContent: String) =
        agentSession.editMessageAndTruncate(conversationId, messageId, newContent)

    fun compactConversation(conversationId: Long) = agentSession.compactConversation(conversationId)

    /** 快捷指令条数据源（P3 T10 收敛单点，T5 委托 [AgentSessionController.quickCommandsFor]）。 */
    fun quickCommandsFor(conversation: ConversationEntity): List<QuickCommand> =
        agentSession.quickCommandsFor(conversation)

    val familiarSelfName: StateFlow<String?> = settingsRepository.familiarSelfName
        .map { it as String? }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val familiarPacing: StateFlow<Boolean?> = settingsRepository.familiarPacing
        .map { it as Boolean? }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** 桌宠开关（null = DataStore 真值未回，UI 按默认开展示） */
    val petEnabled: StateFlow<Boolean?> = settingsRepository.petEnabled
        .map { it as Boolean? }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun setPetEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setPetEnabled(enabled) }
    }

    /** 退后台显示开关（null = 真值未回，UI 按默认关展示）：false = 退后台收窗 */
    val petShowInBackground: StateFlow<Boolean?> = settingsRepository.petShowInBackground
        .map { it as Boolean? }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun setPetShowInBackground(show: Boolean) {
        viewModelScope.launch { settingsRepository.setPetShowInBackground(show) }
    }

    /** 复习拍提醒开关（学习画像 spec §6，null = 真值未回，UI 按默认开展示） */
    val reviewReminder: StateFlow<Boolean?> = settingsRepository.reviewReminder()
        .map { it as Boolean? }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun setReviewReminder(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setReviewReminder(enabled) }
    }

    /** 桌宠皮肤 id（assets/pets/ 或 filesDir/pets/ 目录名），人设/名称随皮肤（personaOf）映射 */
    val petColor: StateFlow<String> = settingsRepository.petColor
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "orange")

    /** 皮肤列表（P5）：进入设置页时 refreshPetSkins() 现扫（spec §5，adb 新推皮肤免重启可见） */
    private val _petSkins = MutableStateFlow<List<PetSkin>>(emptyList())
    val petSkins: StateFlow<List<PetSkin>> = _petSkins.asStateFlow()

    fun refreshPetSkins() {
        viewModelScope.launch(Dispatchers.IO) { _petSkins.value = PetSkinStore.list() }
    }

    /** 界面字体：true = 系统字体 */
    val useSystemFont: StateFlow<Boolean> = settingsRepository.useSystemFont
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setUseSystemFont(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setUseSystemFont(enabled) }
    }

    /** 角色卡对用户的称呼（闲聊群/圆桌；独立于大肥鱼的称呼，默认"小刘鸭"）。
     *  null = DataStore 真值未回（防占位默认被锁存，fix1 同款模式） */
    val chatRoomAddress: StateFlow<String?> = settingsRepository.chatRoomAddress
        .map { it as String? }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun setChatRoomAddress(address: String) {
        viewModelScope.launch { settingsRepository.setChatRoomAddress(address) }
    }

    fun setPetColor(color: String) {
        viewModelScope.launch {
            settingsRepository.setPetColor(color)
            ensureFamiliarTitleMatchesColor()
        }
    }

    /** 主陪伴会话标题跟随默认名（蓝=蓝色大肥鱼/橘=中转站的大肥鱼）。
     *  只纠正"没绑卡、还叫旧默认名（含上古"精灵"）"的会话；绑了卡或用户自己改过名的不动。
     *  换发色时归一化一次；启动归一化由一次性迁移标记负责，此后用户改名即权威。 */
    private suspend fun ensureFamiliarTitleMatchesColor() {
        val convId = settingsRepository.getFamiliarConversationId() ?: return
        val conv = db.conversationDao().getConversationById(convId) ?: return
        if (!isFamiliarConversation(conv)) return
        // cardId=0 = 空卡兜底（没绑卡），只有真绑了卡（>0）的名字才跟卡走。
        // 绑定 id 读 skillParams 列（T7 判定源改 skillId；换卡功能已作废仅防上古
        // "绑卡且标题=默认名"数据被改名——FamiliarKind.cardId 旧判定源随之退役）
        val boundCard = conv.skillParams?.let { params ->
            try {
                (gson.fromJson(params, Map::class.java)["cardId"] as? String)
            } catch (_: Exception) {
                null
            }
        }?.toLongOrNull()?.takeIf { it > 0 }
        if (boundCard != null) return
        val legacyDefaults = setOf(
            "精灵",
            dev.liuyouwu.familiar.util.persona.DefaultPersonaCards.DEFAULT_FAMILIAR_BLUE.name,
            dev.liuyouwu.familiar.util.persona.DefaultPersonaCards.DEFAULT_FAMILIAR_ORANGE.name,
        )
        if (conv.title in legacyDefaults) {
            val color = settingsRepository.getPetColor()
            // withContext(IO)：本函数经 viewModelScope.launch 跑 Main，personaOf 现扫皮肤目录须离主线程
            val name = withContext(Dispatchers.IO) {
                dev.liuyouwu.familiar.util.persona.DefaultPersonaCards.personaOf(PetSkinStore.get(color)).name
            }
            db.conversationDao().updateTitle(convId, name)
        }
    }

    init {
        viewModelScope.launch {
            _systemPrompt.value = settingsRepository.getSystemPrompt()
            _videoFrameCount.value = settingsRepository.getVideoFrameCount()
            // getMaxContext 含老默认 4096 的一次性迁移
            // 触发老默认 4096 的一次性迁移；写入后 maxContext 流自动更新
            settingsRepository.getMaxContext()
            _chatRoomMaxReplies.value = settingsRepository.getChatRoomMaxReplies()
            _thinkingEnabled.value = settingsRepository.getThinkingEnabled()
            _autoReadEnabled.value = settingsRepository.getAutoReadEnabled()
        }
        // 桌宠状态单路（B1）：挂载会话快照流 → resolver 帧口（沿触发/收束沿语义在
        // PetStateResolver，spec §3）。旧散点 isLoading/_error 直写口与收线复位口已随
        // 本批删除——THINKING/SEARCHING/DONE/ERROR 全由帧判定。
        // textChanged = textLength 相对基线变化；基线随 attach 重置（帧流每源前置 null
        // 哨兵，四轮 F6：跨会话重放帧 textChanged 误真会抑制 SEARCHING 首沿）。
        // lastError 空串归 null 已在映射侧完成（空串≠错误，误发会把收束沿压成 ERROR）。
        viewModelScope.launch {
            var lastTextLength = -1
            agentSession.petSessionFrames.collect { snap ->
                if (snap == null) {
                    // attach/卸载哨兵：只清基线不转发帧——null 重放正是要杀的假收束沿
                    lastTextLength = -1
                    return@collect
                }
                val textChanged = snap.textLength != lastTextLength
                lastTextLength = snap.textLength
                dev.liuyouwu.familiar.pet.PetHub.postResolver {
                    dev.liuyouwu.familiar.pet.PetHub.resolver.onSessionFrame(
                        streaming = snap.streaming,
                        toolStatus = snap.toolStatus,
                        lastError = snap.lastError,
                        textChanged = textChanged,
                    )
                }
            }
        }
        // 存量老会话的"精灵"等旧默认标题：按当前发色归一化，一次性（此后用户改名即权威）
        viewModelScope.launch {
            if (!settingsRepository.isMigrated("familiar_title")) {
                ensureFamiliarTitleMatchesColor()
                settingsRepository.markMigrated("familiar_title")
            }
        }
        // 称呼默认值 主人→老板：老默认视同未自定义，一次性搬过去（用户此后改成的值永不覆盖）
        viewModelScope.launch {
            if (!settingsRepository.isMigrated("address_boss")) {
                if (settingsRepository.getFamiliarAddress() == "主人") {
                    settingsRepository.setFamiliarAddress("老板")
                }
                settingsRepository.markMigrated("address_boss")
            }
        }
        // skill 注册表预载（T9 台账"assets 首载主线程"闭合）：IO 线程先触达惰性加载，
        // 会话装配/快捷指令条首读（组合期，主线程）命中已就绪的注册表
        viewModelScope.launch(Dispatchers.IO) { skillRegistry.get(dev.liuyouwu.familiar.skills.TypeInferer.CHAT) }
        fetchModels()
    }

    // ===== Voice / TTS 朗读（T5 拆入 VoiceController，签名原样委托） =====

    fun startVoiceRecording() = voice.startVoiceRecording()

    fun stopVoiceRecordingAndTranscribe() = voice.stopVoiceRecordingAndTranscribe()

    fun cancelVoiceRecording() = voice.cancelVoiceRecording()

    fun clearVoiceResult() = voice.clearVoiceResult()

    fun toggleAutoRead() = voice.toggleAutoRead()

    /** 朗读/停止朗读最后一条 AI 回复（实现见 [VoiceController.toggleSpeakLastReply]）。 */
    fun toggleSpeakLastReply(conversationId: Long) = voice.toggleSpeakLastReply(conversationId)

    fun getMessages(conversationId: Long): StateFlow<List<MessageEntity>> {
        return chatRepository.getMessagesForConversation(conversationId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }

    /**
     * 消息渲染流（Agent 内核 P3 T9）：[ChatRepository.getAgentMessages]（块权威列 +
     * 读时规范化——聊天室旧链写行同样经 LazyNormalizer/MessageRowMapper 读出）×
     * 行 segments 列 → [UiMessage] 列表。列表级工具配对见 [uiMessagesOf]。
     */
    fun getUiMessages(conversationId: Long): Flow<List<UiMessage>> =
        combine(
            chatRepository.getAgentMessages(conversationId),
            chatRepository.getMessagesForConversation(conversationId),
        ) { agentMessages, entities ->
            uiMessagesOf(
                agentMessages,
                entities.associate { it.id.toString() to MessageRenderModel.parseSegments(it.segments) },
            )
        }.flowOn(Dispatchers.Default)   // segments JSON 解析 + 列表配对不占主线程

    fun loadConversationPrompt(conversationId: Long) {
        viewModelScope.launch {
            val conv = db.conversationDao().getConversationById(conversationId)
            if (conv != null) {
                _systemPrompt.value = conv.systemPrompt
                // T10 会话切换挂载：四类型装配 AgentSession（聊天室内部自卸载）。
                // 挂在 loadConversationPrompt（无条件执行）而非 resetForConversationSwitch
                // （生成中早退）——生成中切换会话同样必须换桥，否则新会话读旧会话事件。
                agentSession.attachSessionFor(conv)
            }
        }
    }

    /**
     * 单个会话的实时观察 Flow：聊天页顶栏标题实时订阅（自动命名完成后即时刷新）。
     * UI 订阅本 Flow 即可，无需在消息数变化时手动回读会话行。
     */
    fun observeConversation(conversationId: Long): Flow<ConversationEntity?> =
        chatRepository.observeConversation(conversationId)

    suspend fun getConversationById(conversationId: Long) =
        db.conversationDao().getConversationById(conversationId)

    fun updateSystemPrompt(conversationId: Long, prompt: String) {
        viewModelScope.launch {
            _systemPrompt.value = prompt
            settingsRepository.saveSystemPrompt(prompt)
            db.conversationDao().updateSystemPrompt(conversationId, prompt)
        }
    }

    fun toggleThinking() {
        val next = !_thinkingEnabled.value
        _thinkingEnabled.value = next
        viewModelScope.launch {
            settingsRepository.saveThinkingEnabled(next)
        }
    }

    /** 回复目标设定（P3 T9）：UiMessage → [ReplyTarget]；行 id 非数字（幽灵等合成位）忽略。 */
    fun setReplyingTo(message: UiMessage?) {
        _replyingTo.value = message?.id?.toLongOrNull()?.let { id ->
            ReplyTarget(
                id = id,
                role = if (message.isUser) "user" else "assistant",
                content = message.fullText,
            )
        }
    }

    fun switchModel(modelName: String) {
        viewModelScope.launch {
            settingsRepository.saveModel(modelName)
        }
    }

    fun updateVideoFrameCount(count: Int) {
        _videoFrameCount.value = count
        viewModelScope.launch {
            settingsRepository.saveVideoFrameCount(count)
        }
    }

    fun updateMaxContext(max: Int) {
        viewModelScope.launch {
            settingsRepository.saveMaxContext(max)
        }
    }

    fun fetchModels() {
        viewModelScope.launch {
            val baseUrl = settingsRepository.getBaseUrl()
            // 空地址守卫：不发无 scheme 的请求（Expected URL scheme 报错的根源之一）
            if (baseUrl.isBlank()) {
                _error.value = "还没有连接服务器：先到连接设置里连上"
                return@launch
            }
            val apiKey = settingsRepository.getApiKey()
            val result = modelsApi.fetchModels(baseUrl, apiKey)
            result.onSuccess { response ->
                // response 理论上非空（Result<ModelsResponse>），但 Gson 的 null 穿透
                // 曾让 success 携带 null（审计 P1-1），这里双保险
                _availableModels.value = response?.data.orEmpty()
                // 桌宠出现条件：连上过模型（大肥鱼只说她能确定的事）
                dev.liuyouwu.familiar.pet.PetHub.notifyConnected()
                // 久别问候判定（gap≥2 天置 pending，ambient 下一拍开口；当日仅一次）
                dev.liuyouwu.familiar.pet.PetHub.maybeGreet(dev.liuyouwu.familiar.pet.PetBondStore)
            }
            // 失败可见：提示原因且保留旧列表，不再静默无反馈
            result.onFailure { e ->
                _error.value = "刷新模型失败：${(e.message ?: "未知错误").lineSequence().firstOrNull().orEmpty().take(60)}"
            }
        }
    }

    fun clearConversation(conversationId: Long) {
        if (_isLoading.value) {
            // 生成中清空会把当轮用户消息与占位一起删掉，流却继续跑（审计 P2-3）
            _error.value = "生成中不能清空：先停止生成"
            return
        }
        viewModelScope.launch {
            chatRepository.deleteMessagesForConversation(conversationId)
            db.conversationDao().touch(conversationId)
            // 容量显示随收线统一走 SessionState.contextBreakdown，此处无需清理：
            // 面板读会话桥，清空历史后下次收线即刷新
        }
    }

    // ===== 导出 / 附件（T5 拆入 ExportController，签名原样委托） =====

    suspend fun buildExportPayload(conversationId: Long, format: String): Pair<String, String>? =
        export.buildExportPayload(conversationId, format)

    fun exportConversationHtml(conversationId: Long) = export.exportConversationHtml(conversationId)

    fun addImageAttachment(uri: Uri) = export.addImageAttachment(uri)

    fun addFileAttachment(uri: Uri) = export.addFileAttachment(uri)

    fun addVideoAttachment(uri: Uri) = export.addVideoAttachment(uri)

    fun removeAttachment(attachment: AttachmentInfo) = export.removeAttachment(attachment)

    // ===== Persona presets / chat room setup =====

    fun savePersonaPreset(preset: PersonaPresetEntity) {
        viewModelScope.launch {
            db.personaPresetDao().insert(preset)
        }
    }

    fun deletePersonaPreset(preset: PersonaPresetEntity) {
        viewModelScope.launch {
            db.personaPresetDao().delete(preset)
        }
    }

    // ===== familiar 设置转发（设置弹窗「精灵」分区；保存即写、下轮对话生效）=====
    // （creator 壳 createEli5/createFamiliarConversation 已随 P5 T4b 删除：AppNavigation
    //   调用点直连会话服务，记忆会话复用+设置播种逻辑随迁 AppNavigation.kt）

    fun setFamiliarMode(mode: String) {
        viewModelScope.launch { settingsRepository.setFamiliarMode(mode) }
    }

    fun setFamiliarPacing(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setFamiliarPacing(enabled) }
    }

    /** 称呼/自称一起保存；空值回退默认（主人/我）。onSaved 由 UI 弹「已生效」提示 */
    fun saveFamiliarNames(address: String, selfName: String, onSaved: () -> Unit) {
        viewModelScope.launch {
            settingsRepository.setFamiliarAddress(address.trim().ifBlank { "主人" })
            settingsRepository.setFamiliarSelfName(selfName.trim().ifBlank { "我" })
            onSaved()
        }
    }

    /** 记忆只读预览：FamiliarMemoryStore 单例 current() 后 serialize 渲染 */
    suspend fun readFamiliarMemory(): String = withContext(Dispatchers.IO) {
        val state = memoryStore.current()
        FamiliarMemory.serialize(state.facts, state.lines)
    }

    /** 清空长期记忆（文件层 serialize 空表） */
    fun clearFamiliarMemory(onCleared: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { memoryStore.clear() }
            onCleared()
        }
    }

    // ===== 聊天室/圆桌建室与成员（T5 拆入 ChatRoomSetupController，签名原样委托） =====

    /**
     * 创建聊天室/学习圆桌（P4 T3 委托会话服务；实现与完整 KDoc 见
     * [ChatRoomSetupController.createChatRoomConversation]）。
     */
    suspend fun createChatRoomConversation(
        template: String,
        participants: List<dev.liuyouwu.familiar.ui.screen.chatroom.ParticipantDraft>,
        topic: String = "",
        material: String? = null,
    ): Long = chatRoomSetup.createChatRoomConversation(template, participants, topic, material)

    fun updateChatRoomMaxReplies(count: Int) {
        val coerced = count.coerceIn(1, 6)
        _chatRoomMaxReplies.value = coerced
        viewModelScope.launch {
            settingsRepository.saveChatRoomMaxReplies(coerced)
        }
    }

    /** 主持人：读群聊记录，决定下一个发言的角色；任何失败返回 Unknown（调用方轮换兜底） */
    fun getChatRoomParticipants(conversationId: Long): StateFlow<List<ChatRoomParticipantEntity>> =
        chatRoomSetup.getChatRoomParticipants(conversationId)

    /** 学习圆桌 skill JSON 预设角色（SetupScreen 预填；实现见 [ChatRoomSetupController]）。 */
    fun roundtableRolePresets(): List<dev.liuyouwu.familiar.ui.screen.chatroom.RoundtableRolePreset> =
        chatRoomSetup.roundtableRolePresets()

    /** 保存聊天室成员（返回值语义见 [ChatRoomSetupController.updateChatRoomParticipants]）。 */
    suspend fun updateChatRoomParticipants(
        conversationId: Long,
        participants: List<dev.liuyouwu.familiar.ui.screen.chatroom.ParticipantDraft>
    ): String? = chatRoomSetup.updateChatRoomParticipants(conversationId, participants)

    /** 导航层（AppNavigation）会话创建失败的统一上报口（P5 T4b：creator 壳删除后
     *  调用点直连会话服务，失败仍走 [error] 同一通道——列表页 snackbar/桌宠 ERROR 桥） */
    fun reportError(message: String) {
        _error.value = message
    }

    fun clearError() {
        _error.value = null
    }

    /**
     * 切换会话时清理上一会话的瞬态 UI 状态（复核 Minor-4：单实例化后这些状态跨页共享，
     * 不清理会看到上一会话的附件 chip/引用/错误）。正在生成时不清理。
     */
    fun resetForConversationSwitch(conversationId: Long) {
        if (_isLoading.value) return
        _selectedAttachments.value = emptyList()
        _replyingTo.value = null
        _error.value = null
    }

    // ===== 收桌小结（spec §2）：退出/切换双挂点触发 + 单飞防重 + 落库 + 画像落账 =====

    /** 会话级在飞单飞：小结请求 5-30s 期间，任一挂点再次触发同一会话直接拒绝（不双跑）。 */
    private val summaryFlight = SingleFlight()

    /** 上一会话 id：切换缝判定源——LaunchedEffect(conversationId) 每次进会话都跑，
     *  但「重进同一会话」不该触发小结，只有 prev != null && prev != newId 才补跑 prev。 */
    private var previousConversationId: Long? = null

    /**
     * 会话切换缝（ChatScreen LaunchedEffect(conversationId) 首行调用）：记住本次会话，
     * 若刚从另一个会话切来（prev != null && prev != newId），对 prev 补一次自动小结判定
     * ——兜底用户不经 BackHandler 的切换路径（如列表直达）。重进同一会话不触发。
     */
    fun onConversationSwitched(newId: Long) {
        val prev = previousConversationId
        previousConversationId = newId
        if (prev != null && prev != newId) maybeAutoSummarize(prev)
    }

    /** 退出/切换挂点（BackHandler/顶栏返回）：走节流（≥8 条新增 + 距上次小结 ≥1h）。 */
    fun maybeAutoSummarize(roomId: Long) = summarizeInternal(roomId, force = false)

    /** 列表长按「收桌总结」：跳过节流，单飞仍保留（spec §2）。 */
    fun forceSummarize(roomId: Long) = summarizeInternal(roomId, force = true)

    private fun summarizeInternal(roomId: Long, force: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        if (!summaryFlight.tryEnter(roomId)) return@launch   // 防重：在飞期间切会话不双跑（spec §2 必须）
        try {
            val conv = db.conversationDao().getConversationById(roomId) ?: return@launch
            if (conv.skillId != TypeInferer.ROUNDTABLE) return@launch
            val msgs = db.messageDao().getMessagesForConversationOnce(roomId)
            // 小结基线（零 schema）：最后一条"圆桌小结"assistant 行之后的新增条数
            val lastIdx = msgs.indexOfLast { it.role == "assistant" && it.senderDisplayName == "圆桌小结" }
            val newCount = msgs.size - (lastIdx + 1)
            if (!force) {
                if (!RoundtableSummaryParser.shouldAutoSummarize(newCount)) return@launch
                if (lastIdx >= 0 && System.currentTimeMillis() - msgs[lastIdx].timestamp < 60L * 60 * 1000) return@launch
            }
            // transcript 手工预裁（直驱链路无 ContextBuilder 预裁机器，必须自带）：
            // 最近 60 行、每行截 300 字（compactSummarizer 同款口径）
            val transcript = msgs.drop(maxOf(0, msgs.size - 60)).joinToString("\n") { m ->
                val name = m.senderDisplayName ?: if (m.role == "user") "用户" else "AI"
                "$name: ${m.content.take(300)}"
            }
            // 圆桌 title=课题（建室口径），按课题收画像条目；条目 id/点/状态喂给模型供 updates 锚定
            val entries = db.learningProfileDao().allActive().filter { it.topic == conv.title }
            val entriesJson = gson.toJson(entries.map {
                mapOf("id" to it.id, "point" to it.point, "status" to it.status)
            })
            val raw = requestLlm(
                system = RoundtableSummaryTexts.SYSTEM,
                user = RoundtableSummaryTexts.userInput(
                    transcript, skillParamsString(conv, "material"), entriesJson),
            ) ?: run { Log.w("RoundtableSummary", "小结请求失败 room=$roomId"); return@launch }
            val ex = RoundtableSummaryParser.parse(raw)
                ?: run { Log.w("RoundtableSummary", "小结 JSON 不可解析，丢弃 room=$roomId"); return@launch }
            // 提取→DAO 指令（toDaoInstructions 内丢弃幻觉 id），落学习画像
            RoundtableSummaryParser.toDaoInstructions(ex, entries).forEach { ins ->
                when (ins) {
                    is Instruction.Insert -> db.learningProfileDao().insert(
                        LearningReview.newEntry(
                            conv.title, ins.point, ins.status, roomId, System.currentTimeMillis()))
                    is Instruction.Update -> db.learningProfileDao().byId(ins.id)?.let { e ->
                        db.learningProfileDao().update(e.copy(point = ins.point, status = ins.status))
                    }
                }
            }
            // 小结按 assistant 落库（灰底"圆桌小结"，ChatScreen 现有渲染链自然显示）
            chatRepository.insertMessage(MessageEntity(
                conversationId = roomId, role = "assistant", content = ex.summary,
                senderDisplayName = "圆桌小结", senderColorHex = "#9E9E9E",
            ))
        } finally {
            // 覆盖全部提前 return 路径：小结失败/未达节流/非圆桌都必须放行单飞，否则永久锁死
            summaryFlight.exit(roomId)
        }
    }

    /**
     * 会话 skillParams JSON 里的字符串键（圆桌 material 等；[SkillHost.chatroomParams]
     * 同款读法：gson→Map、非字符串值滤除，解析失败/空值回退 null）。
     */
    private fun skillParamsString(conv: ConversationEntity, key: String): String? {
        val source = conv.skillParams?.takeIf { it.isNotBlank() } ?: return null
        val obj = try {
            gson.fromJson(source, Map::class.java)
        } catch (_: Exception) {
            null
        } ?: return null
        return (obj[key] as? String)?.takeIf { it.isNotBlank() }
    }

    /**
     * VM 侧单 turn 直驱轻量请求（收桌小结用）：stream 收集仿 [AgentEngineRuntime.compactSummarizer]/
     * TitleTrigger.generate 手写——标题生成是 SkillHost 私有件，VM 无现成轻量链路。
     * 仅累积 text 块增量；任何失败（网络/错误事件/空响应）返 null，调用方静默降级。
     * 注意 stream 无 flowOn，网络阻塞随收集方调度——调用方必须在 IO 上收集。
     */
    private suspend fun requestLlm(system: String, user: String): String? = runCatching {
        val engine = agentSession.engine
        val req = LlmRequest(
            model = engine.agentSettingsCache.currentModel,
            system = system,
            history = listOf(
                AgentMessage(
                    id = "roundtable-summary",
                    role = Role.USER,
                    blocks = listOf(ContentBlock.Text(user)),
                )
            ),
            tools = emptyList(),
            mode = ToolMode.NATIVE,
            temperature = null,
            maxTokens = null,
            searchAllowed = false,
            budgetLeft = { true },
        )
        val sb = StringBuilder()
        val openTypes = HashMap<String, String>()
        engine.agentLlm.stream(req).collect { ev ->
            when (ev) {
                is StreamEvent.BlockStart -> openTypes[ev.blockId] = ev.type
                is StreamEvent.BlockEnd -> openTypes.remove(ev.blockId)
                is StreamEvent.BlockDelta -> if (openTypes[ev.blockId] == "text") sb.append(ev.delta)
                is StreamEvent.Error -> throw ev.error
                else -> {}
            }
        }
        sb.toString().trim().takeIf { it.isNotBlank() }
    }.getOrNull()

    override fun onCleared() {
        super.onCleared()
        // 顺序对应原 onCleared：先收语音/TTS 资源（含 reveal 协程），再收新链编排
        voice.onHostCleared()
        agentSession.onHostCleared()
    }
}

/**
 * AgentMessage 列表 → [UiMessage] 渲染列表（Agent 内核 P3 T9，纯函数——JVM 可测）。
 *
 * - **列表级工具配对（turnId 同域，P4 T8 callId 跨 run 守卫）**：每个位置之后
 *   **与其 turnId 相同**的行所产出的 ToolResult 块经 [MessageRenderModel.from]
 *   的 toolResults 入参回配前序 assistant 的 ToolCall。同域判据的依据是 AgentLoop
 *   落账不变式：TOOL_RESULT 行与被应答的 assistant 行共享 turnId "$runId:$turnIndex"
 *   （工具执行/取消补落/原生参数校验回填三路径同款）——跨 run（聊天室逐角色 run
 *   共会话）与跨 turn 的结果互不可见，provider 跨 run 重用 callId（数字小 id 复用
 *   是常见形态）不再把后一 run 的回执错配到前一 run 的卡片。P1 侧 id.ifBlank
 *   （LlmClient 铸 "$runId:b$n"）管空白 id，本守卫管非空重复 id，两层合拢闭环。
 *   后缀表一次逆序按 turnId 分桶累积，O(n·k) 不放成 O(n²)；turnId=NULL（存量
 *   旧行）各行同桶，与旧"全量后缀"行为等值，老数据配对不变。
 * - **TOOL_RESULT 行不独立成泡**：其结果块已并入 assistant 的工具卡片，独立成泡
 *   会与卡片重复（孤儿回执本就由 AgentLoop 写侧丢弃，T4）。
 * - **segments 透传**：行 id → 分段列表（familiar 拟人节奏按段切泡，null=整条）。
 */
internal fun uiMessagesOf(
    messages: List<AgentMessage>,
    segmentsById: Map<String, List<String>?>,
): List<UiMessage> {
    // 后缀结果表：resultsAfter[i] = messages[i] 之后与它同 turnId 的行产出的 ToolResult 块
    // （逆序分桶：accByTurn[t] 恒为"已扫过行中 turnId=t 的结果块，按行序排列"）
    val resultsAfter = arrayOfNulls<List<ContentBlock.ToolResult>>(messages.size)
    val accByTurn = HashMap<String?, List<ContentBlock.ToolResult>>()
    for (i in messages.indices.reversed()) {
        resultsAfter[i] = accByTurn[messages[i].turnId] ?: emptyList()
        val results = messages[i].blocks.filterIsInstance<ContentBlock.ToolResult>()
        if (results.isNotEmpty()) {
            accByTurn[messages[i].turnId] = results + (accByTurn[messages[i].turnId] ?: emptyList())
        }
    }
    return messages.mapIndexed { i, message ->
        if (message.role == Role.TOOL_RESULT) null   // 配对进卡片，不成独立泡
        else MessageRenderModel.from(message, segmentsById[message.id], resultsAfter[i] ?: emptyList())
    }.filterNotNull()
}
