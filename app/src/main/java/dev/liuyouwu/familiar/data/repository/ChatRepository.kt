package dev.liuyouwu.familiar.data.repository

import dev.liuyouwu.familiar.core.llm.model.AgentMessage
import dev.liuyouwu.familiar.data.local.LazyNormalizer
import dev.liuyouwu.familiar.data.local.MessageRowMapper
import dev.liuyouwu.familiar.data.local.dao.ConversationDao
import dev.liuyouwu.familiar.data.local.dao.MessageDao
import dev.liuyouwu.familiar.data.local.dao.MessageAttachmentRow
import dev.liuyouwu.familiar.data.local.dao.MessageLightRow
import dev.liuyouwu.familiar.data.local.dao.MessageSearchRow
import dev.liuyouwu.familiar.data.local.dao.PromptTemplateDao
import dev.liuyouwu.familiar.data.local.entity.ConversationEntity
import dev.liuyouwu.familiar.data.local.entity.MessageEntity
import dev.liuyouwu.familiar.data.local.entity.PromptTemplateEntity
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class ChatRepository(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val promptTemplateDao: PromptTemplateDao,
    // db 必传（审计 P3：原可空回退分支是"非事务两步写"死代码陷阱）
    private val db: dev.liuyouwu.familiar.data.local.AppDatabase
) {
    // skillParams 列的 JSON 序列化（Map<String,String> → JSON 对象，值域全字符串）
    private val paramsGson = com.google.gson.Gson()
    fun getAllConversations(): Flow<List<ConversationEntity>> =
        conversationDao.getAllConversations()

    fun getMessagesForConversation(conversationId: Long): Flow<List<MessageEntity>> =
        messageDao.getMessagesForConversation(conversationId)

    /** 会话列表预览聚合下推（审计 P2-14）：每会话最新消息 + 条数，SQL 聚合而非内存全量过滤 */
    fun getLastMessagePerConversation(): Flow<List<dev.liuyouwu.familiar.data.local.dao.LastMessageRow>> =
        messageDao.getLastMessagePerConversation()

    fun getMessageCounts(): Flow<List<dev.liuyouwu.familiar.data.local.dao.ConversationMessageCount>> =
        messageDao.getMessageCounts()

    /** 导出专用轻量投影（审计 P2-16）：不含 base64 大字段 */
    suspend fun getMessagesForExport(conversationId: Long): List<dev.liuyouwu.familiar.data.local.dao.MessageExportRow> =
        messageDao.getMessagesForExport(conversationId)

    suspend fun createConversation(title: String, systemPrompt: String = ""): Long {
        return conversationDao.insertConversation(
            ConversationEntity(title = title, systemPrompt = systemPrompt)
        )
    }

    suspend fun updateTutorState(id: Long, state: String) {
        conversationDao.updateTutorStateRaw(id, state)
    }

    /**
     * Agent 内核 P3（Task 7，D6 会话服务入口）：按 skill 创建会话——skillId/skillParams
     * 新列写入（P5 清理批：legacy 双写形参已删，旧列落定值——systemPrompt 存空串、
     * tutorConfig 存 null，类型判定与提示词组装一律走 skillId/skill assets）。
     *
     * 新链（SkillHost）读 skillId/skillParams。[skillParams] 序列化为 JSON 对象，
     * 值域全字符串（classmate 存 "true"/"false"）——与 SkillHost.classroomParams
     * 的字符串值过滤、assets skills 的 paramsSchema 同形。members **数组**值装不下
     * （聊天室/圆桌直写实体，见 ChatViewModel.createChatRoomConversation）。
     */
    suspend fun createConversationWithSkill(
        title: String,
        skillId: String,
        skillParams: Map<String, String>
    ): Long = conversationDao.insertConversation(
        ConversationEntity(
            title = title,
            systemPrompt = "",
            tutorConfig = null,
            skillId = skillId,
            skillParams = paramsGson.toJson(skillParams)
        )
    )

    suspend fun getConversationById(id: Long): ConversationEntity? {
        return conversationDao.getConversationById(id)
    }

    /**
     * 编辑现存会话 skill 参数（P5 T6，SkillManagementScreen 写路径）：
     * Map<String,String> 序列化整写 skillParams 列，与 [createConversationWithSkill]
     * 同形（值域全字符串）。聊天室 members（数组值）不走此口——成员编辑仍由
     * ChatViewModel.updateChatRoomParticipants 先读后并键。
     */
    suspend fun updateSkillParams(id: Long, params: Map<String, String>) {
        conversationDao.updateSkillParams(id, paramsGson.toJson(params))
    }

    /** familiar 组装用：按 id 取人设卡（PersonaPresetDao.getById 的薄壳） */
    suspend fun getPersonaPreset(id: Long): dev.liuyouwu.familiar.data.local.entity.PersonaPresetEntity? {
        return db.personaPresetDao().getById(id)
    }

    /** 单个会话的实时观察：聊天页顶栏标题实时订阅（自动命名完成后即时刷新） */
    fun observeConversation(id: Long): Flow<ConversationEntity?> =
        conversationDao.observeConversation(id)

    suspend fun updateConversationTitle(id: Long, title: String) {
        conversationDao.updateTitle(id, title)
    }

    suspend fun deleteConversation(id: Long) {
        conversationDao.deleteById(id)
    }

    suspend fun insertMessage(message: MessageEntity): Long =
        // 事务化：消息插入与会话 updatedAt 刷新原子生效
        db.withTransaction {
            val id = messageDao.insertMessage(message)
            conversationDao.touch(message.conversationId)
            id
        }

    /** 拟人节奏：切段结果（JSON 数组）写入消息行；null = 恢复整条渲染 */
    suspend fun updateSegments(id: Long, segments: String?) {
        messageDao.updateSegments(id, segments)
    }

    suspend fun deleteMessagesForConversation(conversationId: Long) {
        messageDao.deleteMessagesForConversation(conversationId)
    }

    /**
     * 清理指定会话中 content 为空的 assistant 占位消息（进程被杀遗留的"空气泡"）。
     * 供发送新消息前调用：占位行若不清理，会混入请求历史/触发严格后端拒空 content。
     */
    suspend fun deleteEmptyAssistantPlaceholders(conversationId: Long) {
        messageDao.deleteEmptyAssistantPlaceholders(conversationId)
    }

    // ===== 聊天页轻量读路径（审计 P1-4）=====

    /**
     * 聊天页单权威消息流：会话内最新 [limit] 条的轻量投影（无附件大字段），在读侧
     * 内联 [LazyNormalizer]（同 [getAgentMessages] 语义：旧行 think 前缀拆块并回写
     * content/reasoning/blocks 三列，幂等）。**这是 UI 渲染唯一上游**——旧的双流
     * combine（全量实体流 × segments 流）由此消灭；请求历史/备份/导出仍走全量源，
     * 互不影响。
     */
    fun getMessagesLight(conversationId: Long, limit: Int): Flow<List<MessageLightRow>> =
        messageDao.getMessagesLight(conversationId, limit)
            .map { rows -> normalizeLightRows(rows) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)

    /** keyset 向上分页（滚顶加载）：id < [beforeId] 的最新 [limit] 条，升序返回。
     *  读时规范化同 [getMessagesLight]（prepend 行与流内行同语义）。 */
    suspend fun getMessagesBefore(conversationId: Long, beforeId: Long, limit: Int): List<MessageLightRow> =
        normalizeLightRows(messageDao.getMessagesBefore(conversationId, beforeId, limit).asReversed())

    private suspend fun normalizeLightRows(rows: List<MessageLightRow>): List<MessageLightRow> =
        rows.map { row ->
            if (LazyNormalizer.needsNormalize(row)) {
                val normalized = LazyNormalizer.normalize(row)
                // blocks 非空是 normalize 的产出契约（BlockCodec.toJson），!! 仅为可空声明
                messageDao.updateBlocks(row.id, normalized.content, normalized.reasoning, normalized.blocks!!)
                row.copy(content = normalized.content, reasoning = normalized.reasoning, blocks = normalized.blocks)
            } else {
                row
            }
        }

    /** 附件惰性加载（审计 P1-4）：消息 id → 该消息全部附件行（多图 user 组按
     *  user-turnId 整组取，防合并组只出首图）。LruCache 8 条：气泡滚回可见时
     *  不再重复触库。lazy 装配——纯 JVM 单测构造本类不触 android.util。
     *  返回空列表 = 行已删/无附件。 */
    private val messageLruCache by lazy { android.util.LruCache<Long, List<MessageAttachmentRow>>(8) }

    suspend fun loadAttachment(messageId: Long): List<MessageAttachmentRow> {
        messageLruCache.get(messageId)?.let { return it }
        val rows = withContext(Dispatchers.IO) {
            val head = messageDao.getMessageById(messageId) ?: return@withContext emptyList()
            if (head.role == "user" && head.turnId != null) {
                messageDao.getAttachmentsForTurn(head.conversationId, head.turnId)
            } else {
                listOf(
                    MessageAttachmentRow(
                        id = head.id,
                        imageBase64 = head.imageBase64,
                        fileName = head.fileName,
                        fileContent = head.fileContent,
                        videoFrames = head.videoFrames,
                    )
                )
            }
        }
        if (rows.isNotEmpty()) messageLruCache.put(messageId, rows)
        return rows
    }

    suspend fun getMessageCount(conversationId: Long): Int {
        return messageDao.getMessageCount(conversationId)
    }

    // ===== 跨会话搜索（审计 P5 去双轨：标题与正文同走 DB LIKE）=====

    /** 全文搜索用户/助手消息正文（时间倒序，最多 50 条），空 query 由上层拦截。
     *  LIKE 通配符（% _ \）经 [likeEscape] 统一转义，用户搜 "100%" 不会误命中 "100x"。 */
    suspend fun searchMessages(query: String): List<MessageSearchRow> =
        messageDao.searchMessages(likeEscape(query))

    /** 会话标题搜索（审计 P5：列表过滤下推 DB LIKE，取代"全库正文常驻流 + 内存过滤"双轨）。
     *  排序与 [getAllConversations] 同句——置顶优先语义不能丢。 */
    suspend fun searchConversationTitles(query: String): List<ConversationEntity> =
        conversationDao.searchByTitle(likeEscape(query))

    // ===== Token 用量 =====

    /** 落库服务端上报的 token 用量（只更新三列；后端未上报的字段传 null） */
    suspend fun updateMessageUsage(id: Long, promptTokens: Int?, completionTokens: Int?, cachedTokens: Int?) {
        messageDao.updateUsage(id, promptTokens, completionTokens, cachedTokens)
    }

    // Prompt templates
    fun getAllTemplates(): Flow<List<PromptTemplateEntity>> =
        promptTemplateDao.getAllTemplates()

    suspend fun insertTemplate(template: PromptTemplateEntity): Long {
        return promptTemplateDao.insertTemplate(template)
    }

    suspend fun deleteTemplate(template: PromptTemplateEntity) {
        if (!template.isDefault) {
            promptTemplateDao.deleteTemplate(template)
        }
    }

    suspend fun updateTemplate(id: Long, name: String, content: String) {
        promptTemplateDao.updateTemplate(id, name, content)
    }

    /**
     * Agent 内核 P2（Task 9）：行 → [AgentMessage] 的读路径（spec §8.1 行映射 + §8.2 读时
     * 规范化）。存量旧行（v16 前 blocks=NULL 的 assistant 带 think 前缀）在此**读时拆分**：
     * [LazyNormalizer] 判定 → needsNormalize 的行拆块并以 [MessageDao.updateBlocks] 回写
     * content/reasoning/blocks 三列（幂等：回写后 blocks 非空，下次判定为 false）→
     * 回写后的行经 [MessageRowMapper.fromRows] 重建消息。
     *
     * **单一流语义（C6）**：本流直接向收集方发射规范化结果——首个收集值即已规范化，
     * 回写触发的 DB 失效重发射不是正确性依赖（仅作后续更新通知）。IO 调度器内完成
     * 拆分与回写，不占调用方线程。仅供 Agent 内核（RoomMessageStore/上下文构建）消费；
     * 旧 UI 路径（[getMessagesForConversation]）零改动。
     *
     * **去重发射（P3 T5，P2 台账 #21）**：回写 updateBlocks 触发 DB 失效重发射，
     * 重读快照经本 map 映射后与首发射**内容相等**（[AgentMessage] 数据类结构等值），
     * [distinctUntilChanged] 将其折叠——同一收集期内无变化即不向下游二次发射。
     * 行内容真实变化的重发射不受影响（不等值即放行）。
     */
    fun getAgentMessages(conversationId: Long): Flow<List<AgentMessage>> =
        messageDao.getMessagesForConversation(conversationId)
            .map { rows ->
                rows.map { row ->
                    if (LazyNormalizer.needsNormalize(row)) {
                        val normalized = LazyNormalizer.normalize(row)
                        // blocks 非空是 normalize 的产出契约（BlockCodec.toJson），!! 处仅为满足可空声明
                        messageDao.updateBlocks(row.id, normalized.content, normalized.reasoning, normalized.blocks!!)
                        row.copy(content = normalized.content, reasoning = normalized.reasoning, blocks = normalized.blocks)
                    } else {
                        row
                    }
                }.let(MessageRowMapper::fromRows)
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)

    // ===== 学习画像复习入口（审计 A1：AppNavigation 根钩子改走 repo，ui 零 data.local）=====

    /** 到期复习条目（LearningProfileDao.due 转发；AppNavigation.onOpenReview 兜底源之一） */
    suspend fun dueReviewEntries(now: Long): List<dev.liuyouwu.familiar.data.local.entity.LearningProfileEntryEntity> =
        db.learningProfileDao().due(now)

    /** 未归档条目（LearningProfileDao.allActive 转发；due 空时取前 3 的兜底源） */
    suspend fun allActiveEntries(): List<dev.liuyouwu.familiar.data.local.entity.LearningProfileEntryEntity> =
        db.learningProfileDao().allActive()

    /** ReviewViewModel 工厂注入用 DAO 转发（导航层不再直连 AppDatabase 取 DAO） */
    val learningProfileDao: dev.liuyouwu.familiar.data.local.dao.LearningProfileDao
        get() = db.learningProfileDao()

    companion object {
        /**
         * 无 DAO 装配点入口（审计 A1）：AppDatabase 单例自取——AppNavigation 根部
         * 装配走此工厂，不再在 ui 层触碰 AppDatabase。与主构造装配（VM 层各自
         * getInstance 传 DAO）同库同 DAO，行为等价。
         */
        fun create(context: android.content.Context): ChatRepository {
            val db = dev.liuyouwu.familiar.data.local.AppDatabase.getInstance(context)
            return ChatRepository(
                conversationDao = db.conversationDao(),
                messageDao = db.messageDao(),
                promptTemplateDao = db.promptTemplateDao(),
                db = db
            )
        }
    }
}

/**
 * LIKE 通配符统一转义（审计 P5 从 searchMessages 内联抽出公用）：`\`→`\\`、`%`→`\%`、
 * `_`→`\_`，配合 DAO 查询的 `ESCAPE '\'` 子句——标题/正文搜索共用，用户搜 "100%"
 * 不会误命中 "100x"。注意顺序：反斜杠必须最先替换，否则会二次转义后续产物。
 */
internal fun likeEscape(q: String): String =
    q.replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")
