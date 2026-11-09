package dev.liuyouwu.familiar.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.liuyouwu.familiar.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    // timestamp 上加 id 作次级排序：同毫秒消息顺序确定（按自增 id 稳定），不随查询波动
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC, id ASC")
    fun getMessagesForConversation(conversationId: Long): Flow<List<MessageEntity>>

    /** 圆桌建室锚点（"（圆桌话题：…"）计数：>0 即圆桌会话。锚点不随自动命名改写，
     *  比 title 判定可靠（审核高-1：闲聊群第二轮起被自动改名误判为圆桌） */
    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId AND content LIKE '（圆桌话题：%'")
    suspend fun countRoundtableAnchors(conversationId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getMessageById(id: Long): MessageEntity?

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteMessagesForConversation(conversationId: Long)

    @Query("UPDATE messages SET content = :content WHERE id = :id")
    suspend fun updateContent(id: Long, content: String)

    /** 收线单写（审计 P3-3）：正文与 token 用量一条 UPDATE 原子落库 */
    @Query(
        "UPDATE messages SET content = :content, promptTokens = :promptTokens, " +
            "completionTokens = :completionTokens, cachedTokens = :cachedTokens WHERE id = :id"
    )
    suspend fun updateContentAndUsage(
        id: Long,
        content: String,
        promptTokens: Int?,
        completionTokens: Int?,
        cachedTokens: Int?
    )

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 拟人节奏分段落库（JSON 数组字符串；null = 整条渲染） */
    @Query("UPDATE messages SET segments = :segments WHERE id = :id")
    suspend fun updateSegments(id: Long, segments: String?)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND id > :afterMessageId")
    suspend fun deleteMessagesAfter(conversationId: Long, afterMessageId: Long)

    /**
     * 清理指定会话中 content 为空的 assistant 占位消息
     * （发送前插入的占位行，进程被杀后流式回复未落库，遗留"空气泡"）。
     * 进入会话/发送新消息前调用，避免占位行污染请求历史与排序。
     */
    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND role = 'assistant' AND content = ''")
    suspend fun deleteEmptyAssistantPlaceholders(conversationId: Long)

    // ===== 聊天页轻量读路径（审计 P1-4）=====

    /** 会话内最新 [limit] 条消息的轻量投影流（内层倒序取尾、外层恢复升序）。
     *  列清单刻意排除 imageBase64/fileContent/videoFrames 三个大字段（附件经
     *  [getAttachment] 惰性取），hasAttachment 为 SQL 显式布尔——图片行 fileName
     *  恒 null，附件存在性不能靠 fileName 判定。 */
    @Query(
        "SELECT * FROM (SELECT id, conversationId, role, content, fileName, timestamp, " +
            "senderModel, senderDisplayName, senderColorHex, replyToMessageId, replyToContent, " +
            "replyToRole, promptTokens, completionTokens, cachedTokens, segments, reasoning, " +
            "blocks, toolMeta, turnId, " +
            "(imageBase64 IS NOT NULL OR fileName IS NOT NULL OR videoFrames IS NOT NULL) AS hasAttachment " +
            "FROM messages WHERE conversationId = :conversationId " +
            "ORDER BY timestamp DESC, id DESC LIMIT :limit) ORDER BY timestamp ASC, id ASC"
    )
    fun getMessagesLight(conversationId: Long, limit: Int): Flow<List<MessageLightRow>>

    /** keyset 向上分页：id 序严格小于 [beforeId] 的最新 [limit] 条轻量行（id 倒序，
     *  调用方反转回升序）。id 自增单调，作 keyset 锚比 (timestamp,id) 稳定 */
    @Query(
        "SELECT id, conversationId, role, content, fileName, timestamp, " +
            "senderModel, senderDisplayName, senderColorHex, replyToMessageId, replyToContent, " +
            "replyToRole, promptTokens, completionTokens, cachedTokens, segments, reasoning, " +
            "blocks, toolMeta, turnId, " +
            "(imageBase64 IS NOT NULL OR fileName IS NOT NULL OR videoFrames IS NOT NULL) AS hasAttachment " +
            "FROM messages WHERE conversationId = :conversationId AND id < :beforeId " +
            "ORDER BY id DESC LIMIT :limit"
    )
    suspend fun getMessagesBefore(conversationId: Long, beforeId: Long, limit: Int): List<MessageLightRow>

    /** 单行附件按需取（审计 P1-4 附件惰性）：聊天页滚动到可见时才把 base64 拉进内存 */
    @Query("SELECT id, imageBase64, fileName, fileContent, videoFrames FROM messages WHERE id = :id")
    suspend fun getAttachment(id: Long): MessageAttachmentRow?

    /** 同 user-turnId 扇出组（多图 user 1 消息 = N 行）的全部附件行：合并组按组取，
     *  单查 [getAttachment]（首行 id）只能拿到首图——多图首图回归即由此防住 */
    @Query(
        "SELECT id, imageBase64, fileName, fileContent, videoFrames FROM messages " +
            "WHERE conversationId = :conversationId AND turnId = :turnId ORDER BY id ASC"
    )
    suspend fun getAttachmentsForTurn(conversationId: Long, turnId: String): List<MessageAttachmentRow>

    // ===== 跨会话消息搜索 =====

    /** 全文搜索用户/助手消息正文，按时间倒序取前 50 条。
     *  轻量投影：不携带 imageBase64/fileContent 等大字段，避免搜索把整张图片拉进内存。
     *  query 里的 % _ \ 已在 ChatRepository.searchMessages 内统一转义。 */
    @Query(
        "SELECT id, conversationId, role, content, timestamp, senderDisplayName FROM messages " +
            "WHERE content LIKE '%' || :query || '%' ESCAPE '\\' AND role IN ('user','assistant') " +
            "ORDER BY timestamp DESC LIMIT 50"
    )
    suspend fun searchMessages(query: String): List<MessageSearchRow>

    // ===== Token 用量 =====

    /** 落库服务端上报的 token 用量（仅更新三列，正文不动；后端未上报的字段传 null） */
    @Query("UPDATE messages SET promptTokens = :promptTokens, completionTokens = :completionTokens, cachedTokens = :cachedTokens WHERE id = :id")
    suspend fun updateUsage(id: Long, promptTokens: Int?, completionTokens: Int?, cachedTokens: Int?)

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId")
    suspend fun getMessageCount(conversationId: Long): Int

    // ===== 会话列表聚合（审计 P2-14：主线程 O(会话×消息) 全量过滤下推 SQL）=====

    /** 每个会话最新一条消息（按 id 最大，与 timestamp,id 升序的"最后一条"等价），轻量投影 */
    @Query(
        "SELECT conversationId, content, timestamp, senderDisplayName FROM messages " +
            "WHERE id IN (SELECT MAX(id) FROM messages GROUP BY conversationId)"
    )
    fun getLastMessagePerConversation(): Flow<List<LastMessageRow>>

    /** 每个会话的消息条数 */
    @Query("SELECT conversationId, COUNT(*) AS cnt FROM messages GROUP BY conversationId")
    fun getMessageCounts(): Flow<List<ConversationMessageCount>>

    // ===== 备份/恢复 =====

    @Query("SELECT * FROM messages")
    suspend fun getAllMessagesOnce(): List<MessageEntity>

    /** 备份流式导出用（审计 P2-4）：逐会话查询，不把全库消息（含 base64）一次性拉进内存。
     *  取"timestamp,id 升序的最后一条"作会话预览时注意：列表页聚合走 MAX(id)，
     *  系统时钟回拨的极端场景下两者可能有钟差，属可接受的外观差异 */
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC, id ASC")
    suspend fun getMessagesForConversationOnce(conversationId: Long): List<MessageEntity>

    @Query("DELETE FROM messages")
    suspend fun deleteAllMessages()

    // ===== 导出（审计 P2-16：轻量投影，不把 base64 大字段拉进内存）=====

    @Query(
        "SELECT id, conversationId, role, content, timestamp, senderDisplayName, senderModel, " +
            "promptTokens, completionTokens, cachedTokens " +
            "FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC, id ASC"
    )
    suspend fun getMessagesForExport(conversationId: Long): List<MessageExportRow>

    // ===== Agent 内核 P2（Task 9，RoomMessageStore 存储端口原语——评审 P1-6）=====

    /** 删同 turnId 的**全部**行（§5.4 regenerate 删整组：assistant/TOOL_RESULT/剧本拆条行），
     *  返回删除行数；未知 turnId/未知会话返 0、不动存量 */
    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND turnId = :turnId")
    suspend fun deleteByTurnId(conversationId: Long, turnId: String): Int

    /** 时间序（id 序）最后一条 assistant 行的 turnId——regenerate 的分组判据；
     *  末条 assistant 为 NULL（旧数据）或无 assistant 行时返回 null（调用方降级 deleteById）。
     *  末尾 TOOL_RESULT 行（取消补落形态）不参与判定（role 过滤） */
    @Query(
        "SELECT turnId FROM messages WHERE conversationId = :conversationId " +
            "AND role = 'assistant' ORDER BY id DESC LIMIT 1"
    )
    suspend fun getLastAssistantTurnId(conversationId: Long): String?

    /** 读时规范化回写（§8.2 LazyNormalizer）：content/reasoning/blocks 三列一条 UPDATE
     *  原子落库——content 必须随行更新（规范化同时剥离了 think 前缀，正文列与 blocks
     *  投影失配会污染旧 UI/搜索路径）。回写后 blocks 非空，再次读取幂等跳过 */
    @Query("UPDATE messages SET content = :content, reasoning = :reasoning, blocks = :blocks WHERE id = :id")
    suspend fun updateBlocks(id: Long, content: String, reasoning: String?, blocks: String)

    /** user-turnId 铸造序号（RoomMessageStore 以 maxId+1 作 inputSeq 调
     *  MessageRowMapper.newUserTurnId）；并发口径：AgentSession 串行调用，
     *  序号仅要求单调、不承诺无空洞 */
    @Query("SELECT MAX(id) FROM messages WHERE conversationId = :conversationId")
    suspend fun getMaxMessageId(conversationId: Long): Long?
}

/** 消息搜索的轻量投影：刻意不含 imageBase64/fileContent 等大字段，防止搜索把图片整块拉进内存 */
data class MessageSearchRow(
    val id: Long,
    val conversationId: Long,
    val role: String,
    val content: String,
    val timestamp: Long,
    val senderDisplayName: String?
)

/** 会话列表"最新消息"投影 */
data class LastMessageRow(
    val conversationId: Long,
    val content: String,
    val timestamp: Long,
    val senderDisplayName: String?
)

/** 会话消息计数投影 */
data class ConversationMessageCount(val conversationId: Long, val cnt: Int)

/** 会话导出投影：字段名与 MessageEntity 对应列一致，刻意不含 imageBase64/videoFrames/fileContent */
data class MessageExportRow(
    val id: Long,
    val conversationId: Long,
    val role: String,
    val content: String,
    val timestamp: Long,
    val senderDisplayName: String?,
    val senderModel: String?,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val cachedTokens: Int?
)

/**
 * 聊天页轻量投影行（审计 P1-4）：[MessageEntity] 全列减 imageBase64/fileContent/
 * videoFrames 三个大字段，另加 SQL 显式布尔 [hasAttachment]（附件存在性判定不能
 * 落在 fileName 上——图片行 fileName 恒 null）。聊天读路径（气泡渲染所需的结构列
 * 全在）经此投影，附件由 [MessageDao.getAttachment] 按需补载。
 */
data class MessageLightRow(
    val id: Long,
    val conversationId: Long,
    val role: String,
    val content: String,
    val fileName: String? = null,
    val timestamp: Long,
    val senderModel: String? = null,
    val senderDisplayName: String? = null,
    val senderColorHex: String? = null,
    val replyToMessageId: Long? = null,
    val replyToContent: String? = null,
    val replyToRole: String? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val cachedTokens: Int? = null,
    val segments: String? = null,
    val reasoning: String? = null,
    val blocks: String? = null,
    val toolMeta: String? = null,
    val turnId: String? = null,
    val hasAttachment: Boolean = false,
) {
    /** 折回全列实体（三个附件列置 null）：复用 [MessageRowMapper.fromRows] 的
     *  分组/合并/工具配对逻辑，不另写一套行映射 */
    fun toEntity(): MessageEntity = MessageEntity(
        id = id,
        conversationId = conversationId,
        role = role,
        content = content,
        imageBase64 = null,
        fileName = fileName,
        fileContent = null,
        videoFrames = null,
        timestamp = timestamp,
        senderModel = senderModel,
        senderDisplayName = senderDisplayName,
        senderColorHex = senderColorHex,
        replyToMessageId = replyToMessageId,
        replyToContent = replyToContent,
        replyToRole = replyToRole,
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        cachedTokens = cachedTokens,
        segments = segments,
        reasoning = reasoning,
        blocks = blocks,
        toolMeta = toolMeta,
        turnId = turnId,
    )
}

/** 单行附件载荷（审计 P1-4 附件惰性）：轻量投影行按需补载的附件列 */
data class MessageAttachmentRow(
    val id: Long,
    val imageBase64: String?,
    val fileName: String?,
    val fileContent: String?,
    val videoFrames: String?
)
