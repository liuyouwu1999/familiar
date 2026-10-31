package dev.liuyouwu.familiar.data.local

import androidx.room.withTransaction
import dev.liuyouwu.familiar.agent.MessageStore
import dev.liuyouwu.familiar.core.llm.model.AgentMessage
import dev.liuyouwu.familiar.core.llm.model.Role
import dev.liuyouwu.familiar.data.local.dao.ConversationDao
import dev.liuyouwu.familiar.data.local.dao.MessageDao
import dev.liuyouwu.familiar.data.repository.ChatRepository
import kotlinx.coroutines.flow.first

/**
 * [MessageStore] 的 Room 持久化实现（Agent 内核 P2 Task 9；P1 内存版
 * InMemoryMessageStore 的换体，接口签名不变）。Agent 内核 P2/P3 接入
 * AgentSession，方法语义与 P1 契约（RunMarkerTextsSnapshotTest 钉死，随内核
 * 清理 A 自旧标记用例集改造更名）逐一等价：
 *
 * ## String↔Long 桥接（评审 P1-1）
 * conversationId/messageId 一律 `toLongOrNull()` 桥到 Room 行键，失败抛
 * [IllegalArgumentException]（与 [replaceRange] 锚点 fail-fast 同语义；
 * [MessageRowMapper] 读侧产出 `id = row.id.toString()`，闭环成立）。桥接
 * 失败零副作用——先解析后触库。
 *
 * ## 各方法落库口径
 * - [append]：**单事务收口（P3 T5，P2 台账 A-2）**——[MessageRowMapper.toRows]
 *   扇出（多图 user 1 消息 = N 行同 user-turnId）的全部行插入 + 会话 touch
 *   （[ConversationDao.touch]，刷新 updatedAt → 会话列表排序）在同一
 *   [transaction] 闭包内原子生效；insert→touch 序与 [ChatRepository.insertMessage]
 *   对齐。此前 agent 写路径 DAO 直插绕过 touch → 列表排序陈旧（A-2）；
 *   拆条/多图 N 行间进程被杀留半截的窗口随事务收口消除。
 *   返回与消息一一对齐的**首行**行 id（P3 T2——多图扇出 N 行时取首行，读侧
 *   合并组 id 即首行；assistant/TOOL_RESULT 不扇出，钩子路径恒 1 消息 = 1 id）。
 * - [list]：[ChatRepository.getAgentMessages] 的一次性收集（读时规范化 + 行映射
 *   语义同源，见该方法 KDoc）。
 * - [deleteTurn]：[MessageDao.deleteByTurnId]（删同 turnId **全部**行，返回行数；
 *   未知 turnId 返 0、不动存量）。
 * - [deleteById]：读行校验（存在且属本会话）后 [MessageDao.deleteById]。
 *   **合并组删除危害（评审交接 b）**：多图 user 组 N 行同 user-turnId，读侧合并为
 *   单条消息（id=首行），对合并组 deleteById **只删首行**——残留组读侧将合并出
 *   空正文消息。重生成流程必须走 [deleteTurn]（删整组）；deleteById 仅为
 *   turnId=NULL 旧行的降级路径（§5.4）。
 * - [lastAssistantTurnId]：[MessageDao.getLastAssistantTurnId]（id 序末条
 *   assistant 行；NULL/无 assistant → null = 调用方降级判据；末尾 TOOL_RESULT
 *   补落行不遮蔽，role 过滤天然排除）。
 * - [replaceRange]：读改写——[MessageDao.getMessageById] 校验锚点（不存在或属
 *   他会话 → [IllegalArgumentException]，存量不动）→ [MessageDao.deleteMessagesAfter]
 *   删锚及之后（`id > anchorId-1`，与既有 editAndResend 截断同口径；按 **id 序**
 *   而非 (timestamp,id) 序，系统时钟回拨极端场景下两者或有钟差，属可接受边界）
   → 逐行重插（user 行照常铸造 user-turnId）。删+重插仍两步直调、未包事务
 *   （T5 事务化范围仅 append 写路径，A-2 台账；中途进程被杀留截断态，后续可
 *   同法经 [transaction] 收口）。另可优化为纯 SQL 一次 DELETE。
 *
 * ## user-turnId 铸造
 * USER 且 turnId==NULL 的消息（AgentSession 的用户输入，P1 内存版留 null）在落库前
 * 以 [MessageRowMapper.newUserTurnId]`(conversationId, maxId+1)` 铸造——多图 N 行
 * 共用同一序号；序号单调（id 全局递增，已删行不回收）但不承诺无空洞。并发口径：
 * AgentSession 按链串行调用，无并发面。
 */
class RoomMessageStore(
    private val dao: MessageDao,
    private val repo: ChatRepository,
    private val conversationDao: ConversationDao,
    private val db: AppDatabase,
    /**
     * 事务接缝（P3 T5）：生产默认 `db.withTransaction`（Android 框架扩展，依赖活库）；
     * 纯 JVM 单测注入直通假件并记录调用序（[RoomMessageStoreLogicTest]）。
     * Unit 返回——块内结果经闭包捕获变量带出。
     */
    private val transaction: suspend (block: suspend () -> Unit) -> Unit = { block -> db.withTransaction { block() } },
) : MessageStore {

    override suspend fun append(conversationId: String, message: AgentMessage): List<Long> {
        val cid = conversationId.asRoomId("conversationId")
        var firstRowIds: List<Long> = emptyList()
        transaction {
            firstRowIds = insertStamped(cid, listOf(message))
            conversationDao.touch(cid) // A-2：agent 写路径同享列表排序刷新（insert→touch 序对齐 ChatRepository.insertMessage）
        }
        return firstRowIds
    }

    override suspend fun list(conversationId: String): List<AgentMessage> =
        repo.getAgentMessages(conversationId.asRoomId("conversationId")).first()

    override suspend fun deleteTurn(conversationId: String, turnId: String): Int =
        dao.deleteByTurnId(conversationId.asRoomId("conversationId"), turnId)

    override suspend fun deleteById(conversationId: String, id: String): Boolean {
        val cid = conversationId.asRoomId("conversationId")
        val rowId = id.asRoomId("messageId")
        val row = dao.getMessageById(rowId)
        if (row == null || row.conversationId != cid) return false
        dao.deleteById(rowId)
        return true
    }

    override suspend fun lastAssistantTurnId(conversationId: String): String? =
        dao.getLastAssistantTurnId(conversationId.asRoomId("conversationId"))

    override suspend fun replaceRange(
        conversationId: String,
        fromId: String,
        replacement: List<AgentMessage>,
    ) {
        val cid = conversationId.asRoomId("conversationId")
        val anchorId = fromId.asRoomId("messageId")
        require(dao.getMessageById(anchorId)?.conversationId == cid) {
            "replaceRange 锚点不存在: conversationId=$conversationId, fromId=$fromId"
        }
        dao.deleteMessagesAfter(cid, anchorId - 1L) // id > anchorId-1 ⇔ 锚及之后
        insertStamped(cid, replacement)
    }

    // ---------------------------------------------------------------- 内部

    /** 逐条铸造后落库：USER+turnId==NULL 才铸（既有 turnId/非 user 行原样）；每消息独立取号。
     *  返回与消息对齐的首行 id 列表（见类 KDoc append 条目）。 */
    private suspend fun insertStamped(cid: Long, messages: List<AgentMessage>): List<Long> =
        messages.map { message ->
            val stamped = if (message.role == Role.USER && message.turnId == null) {
                message.copy(
                    turnId = MessageRowMapper.newUserTurnId(cid, (dao.getMaxMessageId(cid) ?: 0L) + 1L)
                )
            } else {
                message
            }
            MessageRowMapper.toRows(listOf(stamped), cid)
                .map { row -> dao.insertMessage(row) }
                .first()   // 扇出（多图 user）取首行——读侧合并组 id 即首行
        }

    /** Agent 侧 String id → Room Long 行键（评审 P1-1：非数字 fail-fast，零副作用） */
    private fun String.asRoomId(field: String): Long =
        toLongOrNull()
            ?: throw IllegalArgumentException("String id 桥接失败（非数字 $field）: $field='$this'")
}
