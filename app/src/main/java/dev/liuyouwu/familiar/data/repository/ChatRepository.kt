package dev.liuyouwu.familiar.data.repository

import dev.liuyouwu.familiar.core.llm.model.AgentMessage
import dev.liuyouwu.familiar.data.local.LazyNormalizer
import dev.liuyouwu.familiar.data.local.MessageRowMapper
import dev.liuyouwu.familiar.data.local.dao.ConversationDao
import dev.liuyouwu.familiar.data.local.dao.MessageDao
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

    /** 会话列表预览/搜索用轻量流（不含 base64 大字段） */
    fun getAllMessagePreviews(): Flow<List<dev.liuyouwu.familiar.data.local.MessagePreview>> =
        messageDao.getAllMessagePreviews()

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

    // Pagination support
    suspend fun getMessagesPaged(conversationId: Long, limit: Int, offset: Int): List<MessageEntity> {
        return messageDao.getMessagesPaged(conversationId, limit, offset).reversed()
    }

    suspend fun getMessageCount(conversationId: Long): Int {
        return messageDao.getMessageCount(conversationId)
    }

    // ===== 跨会话消息搜索 =====

    /** 全文搜索用户/助手消息正文（时间倒序，最多 50 条），空 query 由上层拦截。
     *  LIKE 通配符（% _ \）在此统一转义，用户搜 "100%" 不会误命中 "100x"。 */
    suspend fun searchMessages(query: String): List<MessageSearchRow> {
        val escaped = query
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        return messageDao.searchMessages(escaped)
    }

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
}
