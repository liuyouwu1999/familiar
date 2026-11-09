package dev.liuyouwu.familiar.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.liuyouwu.familiar.data.local.AppDatabase
import dev.liuyouwu.familiar.data.local.entity.ConversationEntity
import dev.liuyouwu.familiar.data.local.entity.MessageEntity
import dev.liuyouwu.familiar.data.local.entity.PersonaPresetEntity
import dev.liuyouwu.familiar.data.local.entity.PromptTemplateEntity
import dev.liuyouwu.familiar.data.remote.api.ModelsApi
import dev.liuyouwu.familiar.core.llm.wire.ModelInfo
import dev.liuyouwu.familiar.data.repository.ChatRepository
import dev.liuyouwu.familiar.data.repository.SettingsRepository
import dev.liuyouwu.familiar.util.toUserLine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// Data class for conversation with preview
data class ConversationWithPreview(
    val id: Long,
    val title: String,
    val updatedAt: Long,
    val isChatRoom: Boolean,
    val isPinned: Boolean,
    val lastMessage: String?,
    val messageCount: Int,
    /** 会话类型（圆桌长按菜单「收桌总结」判定源；ConversationEntity 直取，零 SQL 改动） */
    val skillId: String? = null
)

/**
 * 跨会话消息搜索结果项：会话列表页"消息内容"分区展示用。
 * 由仓库层返回的 MessageEntity 投影而来；命中片段（snippet）在本层计算，
 * 不依赖数据层的大字段（imageBase64 等不会进内存）。
 */
data class MessageSearchHit(
    val conversationId: Long,
    val conversationTitle: String,
    val snippet: String,
    val timestamp: Long
)

class ChatListViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val chatRepository = ChatRepository(
        conversationDao = db.conversationDao(),
        messageDao = db.messageDao(),
        promptTemplateDao = db.promptTemplateDao(),
        db = db
    )
    private val settingsRepository = SettingsRepository(application)
    private val modelsApi = ModelsApi()

    // Raw conversations
    private val rawConversations = chatRepository
        .getAllConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * 会话实体全量观察（P5 T6，SkillManagementScreen 数据源）：与 [conversationsWithPreview]
     * 同一上游流，但保留完整实体列（skillId 分组/skillParams 预填必需）。
     */
    val allConversations: StateFlow<List<ConversationEntity>> = rawConversations

    // Conversations with preview data（审计 P2-14：聚合下推 SQL——消息表每次写入不再触发
    // 主线程 O(会话数×消息数) 的全量过滤；preview 语义不变，MAX(id) 即原"最后一条"）
    val conversationsWithPreview: StateFlow<List<ConversationWithPreview>> =
        combine(
            rawConversations,
            chatRepository.getLastMessagePerConversation(),
            chatRepository.getMessageCounts(),
            settingsRepository.familiarConversationId
        ) { convs, lastMessages, counts, familiarConvId ->
            // 主陪伴会话不进列表：入口只有首页大肥鱼卡/点桌宠，避免出现第二个"同名聊天窗"
            val visible = convs.filterNot { it.id == familiarConvId }
            val lastById = lastMessages.associateBy { it.conversationId }
            val countById = counts.associate { it.conversationId to it.cnt }
            visible.map { conv ->
                val lastMsg = lastById[conv.id]
                ConversationWithPreview(
                    id = conv.id,
                    title = conv.title,
                    updatedAt = conv.updatedAt,
                    isChatRoom = conv.isChatRoom,
                    isPinned = conv.isPinned,
                    lastMessage = lastMsg?.let {
                        it.content.take(100).ifBlank { if (it.senderDisplayName != null) "[消息]" else "[附件]" }
                    },
                    messageCount = countById[conv.id] ?: 0,
                    skillId = conv.skillId
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // （原兼容字段 conversations 已删：全工程无引用，审计 P3 死代码清理）

    val templates: StateFlow<List<PromptTemplateEntity>> = chatRepository
        .getAllTemplates()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Persona presets
    val personaPresets: StateFlow<List<PersonaPresetEntity>> = db.personaPresetDao()
        .getAllPresets()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Model switching
    val currentModel: StateFlow<String> = settingsRepository.modelName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    private val _availableModels = MutableStateFlow<List<ModelInfo>>(emptyList())
    val availableModels: StateFlow<List<ModelInfo>> = _availableModels

    // 模型刷新反馈：进行中/失败原因（失败时保留旧列表不清空，见 fetchModels）
    private val _isFetchingModels = MutableStateFlow(false)
    val isFetchingModels: StateFlow<Boolean> = _isFetchingModels

    private val _modelFetchError = MutableStateFlow<String?>(null)
    val modelFetchError: StateFlow<String?> = _modelFetchError

    // Video frame count setting
    private val _videoFrameCount = MutableStateFlow(20)
    val videoFrameCount: StateFlow<Int> = _videoFrameCount

    // Max context setting：流驱动，连接页自动对齐后实时跟随
    val maxContext: StateFlow<Int> = settingsRepository.maxContext
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000),
            dev.liuyouwu.familiar.data.repository.SettingsRepository.DEFAULT_MAX_CONTEXT)

    /** 桌宠相伴统计行（B1 Task 7）："已相伴 N 天 · 互动 M 次"——设置页桌宠分组展示。
     *  源是 PetBondStore 快照流：init 异步载盘、回合/摸摸头写后镜像刷新都会推新值，
     *  collect 异步跟上（初值取当前快照，未载完时显示"1 天"占位不阻塞 UI）。 */
    val petBondLine: StateFlow<String> = dev.liuyouwu.familiar.pet.PetBondStore.snapshots
        .map(::bondLine)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000),
            bondLine(dev.liuyouwu.familiar.pet.PetBondStore.snapshot()))

    /** 相伴天数口径：首见当日算第 1 天；尚未打点（firstMetAt=0）显示 1 天兜底 */
    private fun bondLine(s: dev.liuyouwu.familiar.pet.PetBondStore.BondSnapshot): String {
        val today = dev.liuyouwu.familiar.pet.PetBondMath.epochDay(System.currentTimeMillis())
        val days = if (s.firstMetAt == 0L) 1
        else dev.liuyouwu.familiar.pet.PetBondMath.companionDays(
            dev.liuyouwu.familiar.pet.PetBondMath.epochDay(s.firstMetAt), today)
        return "已相伴 $days 天 · 互动 ${s.interactionCount} 次"
    }

    init {
        // 种子模板改名迁移（默认助手→默认 等）：一次性；此后用户自己建的/改名的同名词目不受影响
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val repo = dev.liuyouwu.familiar.data.repository.SettingsRepository(getApplication())
            if (!repo.isMigrated("template_rename")) {
                mapOf("默认助手" to "默认", "编程助手" to "编程", "翻译助手" to "翻译")
                    .forEach { (old, new) -> db.promptTemplateDao().renameByName(old, new) }
                repo.markMigrated("template_rename")
            }
        }
        // 不在 init 里 fetchModels：从连接页导航回来时 composeAndSave 的 DataStore 写入
        // 可能尚未落盘，抢跑会读到空地址拼出 /v1/models（Expected URL scheme 崩报错）。
        // 模型列表由聊天页进入时拉取，主页顶部菜单/设置弹窗里有手动「刷新」。
        viewModelScope.launch {
            _videoFrameCount.value = settingsRepository.getVideoFrameCount()
        }
    }

    fun fetchModels() {
        viewModelScope.launch {
            _isFetchingModels.value = true
            _modelFetchError.value = null
            val baseUrl = settingsRepository.getBaseUrl()
            // 空地址守卫：未连接时点刷新直接提示，不发无 scheme 的请求
            if (baseUrl.isBlank()) {
                _isFetchingModels.value = false
                _modelFetchError.value = "还没有连接服务器：先到连接设置里连上"
                return@launch
            }
            val apiKey = settingsRepository.getApiKey()
            val result = modelsApi.fetchModels(baseUrl, apiKey)
            _isFetchingModels.value = false
            result.onSuccess { response ->
                // 双保险：Gson null 穿透时 success 也可能携带 null（审计 P1-1）
                _availableModels.value = response?.data.orEmpty()
                // 桌宠出现条件：连上过模型
                dev.liuyouwu.familiar.pet.PetHub.notifyConnected()
                // 久别问候判定（gap≥2 天置 pending，ambient 下一拍开口；当日仅一次）
                dev.liuyouwu.familiar.pet.PetHub.maybeGreet(dev.liuyouwu.familiar.pet.PetBondStore)
            }.onFailure {
                // 失败时保留旧列表，只记录原因供 UI 显示+重试
                _modelFetchError.value = "获取模型列表失败：${it.toUserLine("服务器无响应")}"
            }
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

    fun createConversation(systemPrompt: String = "", onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val id = chatRepository.createConversation(
                dev.liuyouwu.familiar.util.ConversationDefaults.NEW_CHAT, systemPrompt
            )
            onCreated(id)
        }
    }

    fun deleteConversation(id: Long) {
        viewModelScope.launch {
            chatRepository.deleteConversation(id)
        }
    }

    fun togglePinned(id: Long) {
        viewModelScope.launch {
            val conv = chatRepository.getConversationById(id) ?: return@launch
            val newPinned = !conv.isPinned
            val pinnedAt = if (newPinned) System.currentTimeMillis() else null
            db.conversationDao().updatePinned(id, newPinned, pinnedAt)
        }
    }

    /**
     * 编辑现存会话 skill 参数（P5 T6，SkillManagementScreen 写路径）：
     * ChatRepository.updateSkillParams 透传（JSON 对象整写 skillParams 列）。
     */
    fun updateSkillParams(id: Long, params: Map<String, String>) {
        viewModelScope.launch {
            chatRepository.updateSkillParams(id, params)
        }
    }

    // ===== 跨会话搜索（审计 P5 去双轨：标题下推 DB LIKE + 正文搜索，一次查询分发）=====

    private val _messageSearchResults = MutableStateFlow<List<MessageSearchHit>>(emptyList())
    val messageSearchResults: StateFlow<List<MessageSearchHit>> = _messageSearchResults

    private val _isSearchingMessages = MutableStateFlow(false)
    val isSearchingMessages: StateFlow<Boolean> = _isSearchingMessages

    /** 标题搜索命中（searchByTitle 原样结果，UI 以会话 id 收窄列表、保持既有排序/预览） */
    private val _titleSearchResults = MutableStateFlow<List<ConversationEntity>>(emptyList())
    val titleSearchResults: StateFlow<List<ConversationEntity>> = _titleSearchResults

    /** 产出 [titleSearchResults] 的搜索词：UI 与当前输入对齐后才收窄列表，
     *  防抖窗口内不因旧结果/空集误显"无搜索结果" */
    private val _titleSearchQuery = MutableStateFlow("")
    val titleSearchQuery: StateFlow<String> = _titleSearchQuery

    /**
     * 会话列表搜索统一入口（UI 300ms 防抖后的单次分发，标题+正文合并于此）：
     * 标题过滤任意非空词都查（沿用旧内存过滤 1 字符也生效的语义）；
     * 正文沿用 ≥2 字符门槛，命中行来自 MessageDao.searchMessages 的
     * MessageSearchRow 轻量投影（不含 base64 大字段），命中片段在本层计算。
     */
    suspend fun runSearch(query: String) {
        val q = query.trim()
        if (q.isEmpty()) {
            clearSearch()
            return
        }
        _isSearchingMessages.value = q.length >= 2
        try {
            // 异常兜底（审计 P2-10）：DB 异常沿 LaunchedEffect 上抛会直接崩溃，转为可见错误提示
            val titles = searchSafely(emptyList()) { chatRepository.searchConversationTitles(q) }
            _titleSearchResults.value = titles
            _titleSearchQuery.value = q

            if (q.length < 2) {
                _messageSearchResults.value = emptyList()
                return
            }
            val hits = searchSafely(emptyList()) {
                _messageSearchResults.value = emptyList()
                chatRepository.searchMessages(q)
            }
            val titleById = rawConversations.value.associate { it.id to it.title }
            _messageSearchResults.value = hits.map { msg ->
                MessageSearchHit(
                    conversationId = msg.conversationId,
                    conversationTitle = titleById[msg.conversationId] ?: "会话 #${msg.conversationId}",
                    snippet = buildSnippet(msg.content, q),
                    timestamp = msg.timestamp
                )
            }
        } finally {
            _isSearchingMessages.value = false
        }
    }

    /**
     * 搜索异常兜底：失败转可见错误提示并返回 [fallback]；CancellationException 原样
     * 重抛（防抖重启的旧分发不得吞取消续跑，否则取消态下继续写状态流）。
     */
    private suspend fun <T> searchSafely(fallback: T, block: suspend () -> T): T =
        runCatching { block() }
            .onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                _modelFetchError.value = "搜索失败：${it.toUserLine()}"
            }.getOrDefault(fallback)

    /** 清空搜索结果（搜索词清空或离开搜索态时调用） */
    fun clearSearch() {
        _messageSearchResults.value = emptyList()
        _titleSearchResults.value = emptyList()
        _titleSearchQuery.value = ""
    }

    /** 取首个命中位置附近的一段文字做摘要，两端留上下文并按需加省略号 */
    private fun buildSnippet(content: String, query: String, radius: Int = 40): String {
        if (content.isBlank()) return ""
        val idx = content.indexOf(query, ignoreCase = true)
        if (idx < 0) return content.take(radius * 2).replace('\n', ' ').trim()
        val start = (idx - radius).coerceAtLeast(0)
        val end = (idx + query.length + radius).coerceAtMost(content.length)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < content.length) "…" else ""
        return prefix + content.substring(start, end).replace('\n', ' ').trim() + suffix
    }

    fun addTemplate(name: String, content: String) {
        viewModelScope.launch {
            chatRepository.insertTemplate(
                PromptTemplateEntity(name = name, content = content, isDefault = false)
            )
        }
    }

    fun deleteTemplate(template: PromptTemplateEntity) {
        viewModelScope.launch {
            chatRepository.deleteTemplate(template)
        }
    }

    fun updateTemplate(id: Long, name: String, content: String) {
        viewModelScope.launch {
            chatRepository.updateTemplate(id, name, content)
        }
    }

    // Persona preset management
    /** 人设卡 v2 字段（identity...address）带默认值：旧调用点（仅名称/性格/颜色）不受影响 */
    fun addPersonaPreset(
        name: String, personality: String, colorHex: String,
        identity: String = "", traits: String = "", speechStyle: String = "",
        catchphrase: String = "", exampleDialogs: String = "",
        selfName: String = "我", address: String = "主人",
    ) {
        viewModelScope.launch {
            db.personaPresetDao().insert(
                PersonaPresetEntity(
                    name = name, personality = personality, colorHex = colorHex,
                    identity = identity, traits = traits, speechStyle = speechStyle,
                    catchphrase = catchphrase, exampleDialogs = exampleDialogs,
                    selfName = selfName, address = address,
                )
            )
        }
    }

    fun deletePersonaPreset(preset: PersonaPresetEntity) {
        viewModelScope.launch {
            db.personaPresetDao().delete(preset)
        }
    }

    // ===== 备份 / 恢复 =====

    private val _backupMessage = MutableStateFlow<String?>(null)
    val backupMessage: StateFlow<String?> = _backupMessage

    fun clearBackupMessage() {
        _backupMessage.value = null
    }

    fun exportBackup(uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val out = getApplication<Application>().contentResolver.openOutputStream(uri)
                        ?: throw IllegalStateException("无法写入所选位置")
                    // v4：大肥鱼的记忆 + familiar 设置随备份走（当前序列化态，含上限裁剪）
                    val memoryState = dev.liuyouwu.familiar.util.memory.FamiliarMemoryStore(
                        getApplication<Application>().filesDir
                    ).current()
                    val memoryText = dev.liuyouwu.familiar.util.memory.FamiliarMemory
                        .serialize(memoryState.facts, memoryState.lines)
                    val familiar = dev.liuyouwu.familiar.backup.BackupManager.BackupFamiliar(
                        address = settingsRepository.getFamiliarAddress(),
                        selfName = settingsRepository.getFamiliarSelfName(),
                        mode = settingsRepository.getFamiliarMode(),
                        pacing = settingsRepository.isFamiliarPacing(),
                    )
                    dev.liuyouwu.familiar.backup.BackupManager.export(db, out, memoryText, familiar)
                }
                _backupMessage.value = "备份完成"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _backupMessage.value = "备份失败：${e.toUserLine()}"
            }
        }
    }

    fun importBackup(uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    dev.liuyouwu.familiar.backup.BackupManager.import(
                        db,
                        getApplication<Application>().contentResolver,
                        uri
                    )
                }
                // v4：记忆与设置还原（v1-v3 老备份这两项为 null，自然跳过）。
                // restore 走 parse→serialize 归一化；ChatViewModel 的 store 无内存缓存，落盘即生效
                r.familiarMemory?.let { text ->
                    dev.liuyouwu.familiar.util.memory.FamiliarMemoryStore(
                        getApplication<Application>().filesDir
                    ).restore(text)
                }
                r.familiar?.let { f ->
                    f.address?.let { settingsRepository.setFamiliarAddress(it) }
                    f.selfName?.let { settingsRepository.setFamiliarSelfName(it) }
                    f.mode?.let { settingsRepository.setFamiliarMode(it) }
                    f.pacing?.let { settingsRepository.setFamiliarPacing(it) }
                }
                _backupMessage.value = buildString {
                    append("已恢复 ${r.conversations} 个会话、${r.messages} 条消息、${r.participants} 个聊天室成员")
                    if (r.familiarMemory != null) append("、大肥鱼的记忆")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _backupMessage.value = "恢复失败：${e.toUserLine()}"
            }
        }
    }

    /** REPLACE 式更新（按 id 覆写整行）；v2 字段带默认值，旧调用点不受影响。
     *  注意：tagBlock/tagGloss 未在参数中，编辑保存会重置为空（与既有行为一致）。 */
    fun updatePersonaPreset(
        id: Long, name: String, personality: String, colorHex: String,
        identity: String = "", traits: String = "", speechStyle: String = "",
        catchphrase: String = "", exampleDialogs: String = "",
        selfName: String = "我", address: String = "主人",
    ) {
        viewModelScope.launch {
            db.personaPresetDao().insert(
                PersonaPresetEntity(
                    id = id, name = name, personality = personality, colorHex = colorHex,
                    identity = identity, traits = traits, speechStyle = speechStyle,
                    catchphrase = catchphrase, exampleDialogs = exampleDialogs,
                    selfName = selfName, address = address,
                )
            )
        }
    }
}
