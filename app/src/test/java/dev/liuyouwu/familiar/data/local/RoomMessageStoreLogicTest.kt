package dev.liuyouwu.familiar.data.local

import androidx.room.InvalidationTracker
import dev.liuyouwu.familiar.core.llm.model.AgentMessage
import dev.liuyouwu.familiar.core.llm.model.Attachment
import dev.liuyouwu.familiar.core.llm.model.BlockCodec
import dev.liuyouwu.familiar.core.llm.model.ContentBlock
import dev.liuyouwu.familiar.core.llm.model.Role
import dev.liuyouwu.familiar.agent.MessageStore
import dev.liuyouwu.familiar.data.local.dao.ChatRoomParticipantDao
import dev.liuyouwu.familiar.data.local.dao.ConversationDao
import dev.liuyouwu.familiar.data.local.dao.MessageDao
import dev.liuyouwu.familiar.data.local.dao.MessageExportRow
import dev.liuyouwu.familiar.data.local.dao.MessageSearchRow
import dev.liuyouwu.familiar.data.local.dao.ConversationMessageCount
import dev.liuyouwu.familiar.data.local.dao.LastMessageRow
import dev.liuyouwu.familiar.data.local.dao.PersonaPresetDao
import dev.liuyouwu.familiar.data.local.dao.PromptTemplateDao
import dev.liuyouwu.familiar.data.local.entity.ChatRoomParticipantEntity
import dev.liuyouwu.familiar.data.local.entity.ConversationEntity
import dev.liuyouwu.familiar.data.local.entity.MessageEntity
import dev.liuyouwu.familiar.data.local.entity.PersonaPresetEntity
import dev.liuyouwu.familiar.data.local.entity.PromptTemplateEntity
import dev.liuyouwu.familiar.data.repository.ChatRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Task 9：RoomMessageStore 换体后的接口契约防线（评审 P2-7）——P1 内存版
 * （RunMarkerTextsSnapshotTest/InMemoryMessageStore，原标记用例集随内核清理 A
 * 改造更名）钉过的语义在 Room 体上等价钉死：
 * user-turnId 铸造序列（maxId+1）、replaceRange 读改写（锚点定位→删锚及之后→重插）、
 * deleteTurn 删整组/未知返 0、lastAssistantTurnId 末条 NULL→null（降级判据）、
 * String id 非数字/锚点不存在 fail-fast（IAE）。
 *
 * 读时规范化（§8.2）组合判定直接打在 [ChatRepository.getAgentMessages] 上：
 * needsNormalize 才回写三列（updateBlocks），单一流语义（C6）——首个收集值即已规范化，
 * 不依赖回写触发的 DB Flow 二次发射。
 *
 * 纯 JVM 测试：不起 Room/Robolectric——MessageDao 为 interface，手写假实现；
 * ChatRepository 以假 DAO + 假 AppDatabase 构造（仅触达 messageDao 路径）。
 * **DAO/Room 管道本体（@Query SQL 语义、withTransaction 原子性/回滚、Flow 失效
 * 重发射机制）无 Robolectric 不可测，compile-only——真机验证**；事务内的调用序
 * 已由注入接缝假件钉死（见下）。
 *
 * P3 Task 5 增设两道防线：
 * - **append 事务化 + 会话 touch（P2 台账 A-2）**：真 withTransaction 依赖活库
 *   （Android 框架扩展），经构造器注入 `transaction` 接缝——测试给直通假件并
 *   记录 begin/end；fake DAO 与 RecordingConversationDao 同计一张调用序表
 *   [ops]：一次 append 的全部行插入 + `touch(cid)` 恰一次同闭包、touch 在插入后。
 * - **getAgentMessages 去重发射（P2 台账 #21）**：fake 流单次收集内双快照发射
 *   （第二快照重读行状态，模拟回写触发的 DB 失效重发射）——映射后内容相等的
 *   二次发射不得向下游传播（distinctUntilChanged）。
 */
class RoomMessageStoreLogicTest {

    /** 统一调用序（P3 T5）：行插入 / 事务 begin-end / touch 按发生顺序追加，断言闭包结构 */
    private val ops = mutableListOf<String>()
    private val dao = FakeMessageDao(ops)
    private val conversationDao = RecordingConversationDao(ops)
    private val fakeDb = FakeAppDatabase()
    private val repo = ChatRepository(conversationDao, dao, ThrowingPromptTemplateDao, fakeDb)
    private val store: MessageStore = RoomMessageStore(
        dao, repo, conversationDao, fakeDb,
    ) { block ->
        ops += "txn:begin"
        block()
        ops += "txn:end"
    }

    private fun msg(id: String, role: Role, turnId: String? = null, vararg attachments: Attachment) = AgentMessage(
        id = id, role = role,
        blocks = listOf(ContentBlock.Text("text-$id")),
        attachments = attachments.toList(),
        turnId = turnId,
    )

    // ---------------------------------------------------------------- user-turnId 铸造序列

    @Test
    fun `append mints user turnId from maxId plus one - sequence ignores per-row fanout`() = runBlocking {
        // 空会话 maxId=null → 0+1=1；三图扇出 3 行共用同一铸造序号
        store.append("5", msg("u1", Role.USER, attachments = arrayOf(Attachment.Image("IMG_1"), Attachment.Image("IMG_2"), Attachment.Image("IMG_3"))))
        val first = dao.rows.filter { it.conversationId == 5L }
        assertEquals(3, first.size)
        assertTrue("多图 N 行同 user-turnId", first.all { it.turnId == "5:u1" })

        // 第二条 user：maxId=3 → 4（序号不承诺无空洞——P1 内存版口径，per-message 铸造）
        store.append("5", msg("u2", Role.USER))
        assertEquals("5:u4", dao.rows.last { it.role == "user" && it.content == "text-u2" }.turnId)
    }

    @Test
    fun `append never rewrites existing turnId nor mints for non-user roles`() = runBlocking {
        store.append("5", msg("u1", Role.USER, turnId = "already-set"))
        store.append("5", msg("a1", Role.ASSISTANT, turnId = "run-1:0"))
        store.append("5", msg("a2", Role.ASSISTANT)) // assistant turnId=null 保持 null（user-turnId 只铸 user 行）
        val byContent = dao.rows.associateBy { it.content }
        assertEquals("already-set", byContent.getValue("text-u1").turnId)
        assertEquals("run-1:0", byContent.getValue("text-a1").turnId)
        assertNull(byContent.getValue("text-a2").turnId)
    }

    // ---------------------------------------------------------------- append 返回行 id（P3 T2：行级 post 钩子前提）

    @Test
    fun `append returns one aligned row id per message - fanout yields first row id`() = runBlocking {
        // 多图 user 1 消息扇出 3 行：返回单个首行 id（读侧合并组 id 即首行——onPersisted 对位口径）
        val ids = store.append("5", msg("u1", Role.USER, attachments = arrayOf(Attachment.Image("IMG_1"), Attachment.Image("IMG_2"), Attachment.Image("IMG_3"))))
        assertEquals(listOf(dao.rows.first().id), ids)
        assertEquals(3, dao.rows.size)

        // 单行消息：返回该行 id
        val single = store.append("5", msg("a1", Role.ASSISTANT, turnId = "run-1:0"))
        assertEquals(listOf(dao.rows.last().id), single)
        assertEquals(dao.rows.filter { it.role == "assistant" }.single().id, single.single())
    }

    // ---------------------------------------------------------------- append 事务化 + touch（P2 台账 A-2，P3 T5）

    @Test
    fun `append wraps all fanout rows and one touch in a single transaction closure`() = runBlocking {
        // 多图 user 1 消息 = 3 行：全部行插入 + touch(cid) 恰一次发生在同一事务闭包内，
        // touch 在插入之后（与 ChatRepository.insertMessage 的 insert→touch 序一致）
        store.append(
            "5",
            msg("u1", Role.USER, attachments = arrayOf(Attachment.Image("IMG_1"), Attachment.Image("IMG_2"), Attachment.Image("IMG_3"))),
        )
        assertEquals(
            listOf("txn:begin", "insert:1", "insert:2", "insert:3", "touch:5", "txn:end"),
            ops,
        )
    }

    @Test
    fun `consecutive appends get one transaction and one touch each`() = runBlocking {
        // 两次 append 各自成闭包：事务不跨 append 合并，touch 每次 append 恰一次
        store.append("5", msg("u1", Role.USER))
        store.append("5", msg("a1", Role.ASSISTANT, turnId = "run-1:0"))
        assertEquals(
            listOf(
                "txn:begin", "insert:1", "touch:5", "txn:end",
                "txn:begin", "insert:2", "touch:5", "txn:end",
            ),
            ops,
        )
    }

    // ---------------------------------------------------------------- list（getAgentMessages 语义）

    @Test
    fun `list roundtrips - multi image user merges back to one message with row id as string id`() = runBlocking {
        store.append("5", msg("u1", Role.USER, attachments = arrayOf(Attachment.Image("IMG_1"), Attachment.Image("IMG_2"))))
        store.append("5", msg("a1", Role.ASSISTANT, turnId = "run-1:0"))
        val back = store.list("5")
        assertEquals(2, back.size)
        assertEquals(2, back[0].attachments.size)       // 同 user-turnId 两行合并回单条
        assertEquals(dao.rows.first().id.toString(), back[0].id) // id 规则 = 首行 row.id.toString()
        assertEquals("run-1:0", back[1].turnId)
        assertTrue(store.list("404").isEmpty() && store.list("405").isEmpty()) // 未触达会话 → 空（id 桥接通过）
    }

    // ---------------------------------------------------------------- deleteTurn：删整组

    @Test
    fun `deleteTurn removes every row sharing turnId and returns count`() = runBlocking {
        seedTurnGroup()
        assertEquals(2, store.deleteTurn("5", "run-1:0"))
        // TOOL_RESULT 行 content 空投影（§8.1）：r2 行 content=""
        assertEquals(listOf("text-u1", "text-a2", ""), dao.rows.map { it.content })
    }

    @Test
    fun `deleteTurn unknown turnId returns 0 and leaves rows untouched`() = runBlocking {
        seedTurnGroup()
        assertEquals(0, store.deleteTurn("5", "run-x:9"))
        assertEquals(0, store.deleteTurn("404", "run-1:1")) // 未知会话（数字 id，桥接通过）→ 0
        assertEquals(5, dao.rows.size)
    }

    private suspend fun seedTurnGroup() {
        store.append("5", msg("u1", Role.USER))
        store.append("5", msg("a1", Role.ASSISTANT, turnId = "run-1:0"))
        store.append("5", msg("r1", Role.TOOL_RESULT, turnId = "run-1:0"))
        store.append("5", msg("a2", Role.ASSISTANT, turnId = "run-1:1"))
        store.append("5", msg("r2", Role.TOOL_RESULT, turnId = "run-1:1"))
    }

    // ---------------------------------------------------------------- lastAssistantTurnId：降级判据

    @Test
    fun `lastAssistantTurnId returns turnId of last assistant row by id order`() = runBlocking {
        seedTurnGroup()
        assertEquals("run-1:1", store.lastAssistantTurnId("5"))
    }

    @Test
    fun `lastAssistantTurnId null when last assistant row has NULL turnId`() = runBlocking {
        // 旧数据：末条 assistant turnId=NULL → null（调用方降级 deleteById，§5.4）
        store.append("5", msg("u1", Role.USER))
        store.append("5", msg("a1", Role.ASSISTANT, turnId = "run-1:0"))
        store.append("5", msg("a2", Role.ASSISTANT)) // 旧行 NULL
        assertNull(store.lastAssistantTurnId("5"))
        assertNull(store.lastAssistantTurnId("404")) // 空会话（数字 id，桥接通过）→ null
    }

    // ---------------------------------------------------------------- deleteById：降级删单行

    @Test
    fun `deleteById removes exactly one row and reports unknown or foreign conversation`() = runBlocking {
        store.append("5", msg("a1", Role.ASSISTANT))
        val rowId = dao.rows.first().id
        assertTrue(store.deleteById("5", rowId.toString()))
        assertTrue(dao.rows.isEmpty())

        store.append("6", msg("a2", Role.ASSISTANT))
        assertFalse(store.deleteById("5", dao.rows.first().id.toString())) // 行存在但属他会话 → false 不动
        assertEquals(1, dao.rows.size)
        assertFalse(store.deleteById("5", "424242")) // 不存在 → false
    }

    @Test
    fun `deleteById on merged multi image group removes only first row - regenerate must use deleteTurn`() = runBlocking {
        // 评审交接（b）：合并组（N 行同 user-turnId）deleteById 只删首行——读侧将合并出
        // 空正文残组。KDoc 已钉死：重生成流程必须走 deleteTurn。
        store.append("5", msg("u1", Role.USER, attachments = arrayOf(Attachment.Image("IMG_1"), Attachment.Image("IMG_2"), Attachment.Image("IMG_3"))))
        val mergedId = dao.rows.first().id
        assertTrue(store.deleteById("5", mergedId.toString()))
        assertEquals(2, dao.rows.size) // 后续两行残留（危害记录，非缺陷）
        assertTrue(dao.rows.all { it.turnId != null })
    }

    // ---------------------------------------------------------------- replaceRange：读改写

    @Test
    fun `replaceRange truncates from anchor row to end and inserts replacement`() = runBlocking {
        seedTurnGroup() // u1, a1(run-1:0), r1(run-1:0), a2(run-1:1), r2(run-1:1)
        val anchorId = dao.rows.first { it.content == "text-a1" }.id
        store.replaceRange("5", anchorId.toString(), listOf(msg("e1", Role.USER), msg("e2", Role.ASSISTANT, turnId = "run-9:0")))
        assertEquals(listOf("text-u1", "text-e1", "text-e2"), dao.rows.map { it.content })
        // 删除后重插的 user 行同样铸造 user-turnId：截断后仅剩 u1（id=A）→ 铸 A+1
        val u1Id = dao.rows.first { it.content == "text-u1" }.id
        assertEquals("5:u${u1Id + 1L}", dao.rows.first { it.content == "text-e1" }.turnId)
        assertEquals("run-9:0", dao.rows.last().turnId) // 非 user 行 turnId 原样
    }

    @Test
    fun `replaceRange with empty replacement truncates from anchor`() = runBlocking {
        store.append("5", msg("u1", Role.USER))
        store.append("5", msg("a1", Role.ASSISTANT, turnId = "run-1:0"))
        store.append("5", msg("a2", Role.ASSISTANT, turnId = "run-1:1"))
        val anchorId = dao.rows.first { it.content == "text-a2" }.id
        store.replaceRange("5", anchorId.toString(), emptyList())
        assertEquals(listOf("text-u1", "text-a1"), dao.rows.map { it.content })
    }

    @Test
    fun `replaceRange unknown anchor fails fast without mutation`() = runBlocking {
        seedTurnGroup()
        val before = dao.rows.toList()
        try {
            store.replaceRange("5", "424242", listOf(msg("n1", Role.USER)))
            fail("锚点不存在必须抛 IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        try {
            store.replaceRange("404", dao.rows.first().id.toString(), emptyList()) // 会话存在性无关：锚点属会话 5，在 404 内不存在 → IAE
            fail("会话内锚点不存在同样抛 IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(before, dao.rows) // fail-fast：存量不动
    }

    // ---------------------------------------------------------------- String id 桥接（评审 P1-1）

    @Test
    fun `non numeric string ids fail fast with IllegalArgumentException`() = runBlocking {
        try { store.append("abc", msg("u1", Role.USER)); fail("append 非数字 conversationId 应抛 IAE") } catch (_: IllegalArgumentException) {}
        try { store.list("abc"); fail("list 非数字应抛 IAE") } catch (_: IllegalArgumentException) {}
        try { store.deleteTurn("abc", "t"); fail("deleteTurn 非数字应抛 IAE") } catch (_: IllegalArgumentException) {}
        try { store.deleteById("5", "not-a-long"); fail("deleteById 非数字 id 应抛 IAE") } catch (_: IllegalArgumentException) {}
        try { store.lastAssistantTurnId("x"); fail("lastAssistantTurnId 非数字应抛 IAE") } catch (_: IllegalArgumentException) {}
        try { store.replaceRange("5", "xyz", emptyList()); fail("replaceRange 非数字 fromId 应抛 IAE") } catch (_: IllegalArgumentException) {}
        assertTrue(dao.rows.isEmpty()) // 桥接失败零副作用
    }

    // ---------------------------------------------------------------- getAgentMessages：读时规范化（§8.2）

    @Test
    fun `getAgentMessages normalizes legacy think rows and writes back three columns`() = runBlocking {
        val legacy = MessageEntity(
            id = 1L, conversationId = 5L, role = "assistant",
            content = "<think>推演</think>正文", blocks = null, // 存量行：v16 前无 blocks
        )
        val cleanBlocks = BlockCodec.toJson(listOf(ContentBlock.Text("新路径回复")))
        val clean = MessageEntity(id = 2L, conversationId = 5L, role = "assistant", content = "新路径回复", blocks = cleanBlocks)
        val user = MessageEntity(id = 3L, conversationId = 5L, role = "user", content = "<think>伪装标记</think>问题", blocks = null)
        dao.rows += listOf(legacy, clean, user)

        val back = repo.getAgentMessages(5L).first()

        // 回写判定：仅 legacy assistant 行进门（user 行不拆、blocks 已非空幂等跳过）
        assertEquals(listOf(1L), dao.updateBlocksIds)
        val written = dao.rows.first { it.id == 1L }
        assertEquals("正文", written.content)                       // 三列回写落库（content 随行剥离 think 前缀）
        assertEquals("推演", written.reasoning)
        assertEquals(BlockCodec.toJson(listOf(ContentBlock.Thinking("推演"), ContentBlock.Text("正文"))), written.blocks)

        // 发射值：首个收集值即已规范化（Thinking/Text 拆块），id=row.id.toString()
        assertEquals(3, back.size)
        assertEquals(listOf(ContentBlock.Thinking("推演"), ContentBlock.Text("正文")), back[0].blocks)
        assertEquals("1", back[0].id)
        assertEquals(listOf(ContentBlock.Text("新路径回复")), back[1].blocks)
        assertEquals(listOf(ContentBlock.Text("<think>伪装标记</think>问题")), back[2].blocks) // user 行原样
    }

    @Test
    fun `getAgentMessages is single stream - normalized value without DB re-emission`() = runBlocking {
        // C6：fake 流每次收集只发射一次快照——first() 拿到的已是回写后内容，
        // 证明规范化结果由本流自行产出，不依赖回写触发的 DB 失效二次发射
        dao.rows += MessageEntity(id = 7L, conversationId = 5L, role = "assistant", content = "<think>r</think>答案", blocks = null)
        val back = repo.getAgentMessages(5L).first()
        assertEquals(1, dao.flowEmissionCount)
        assertEquals(listOf(ContentBlock.Thinking("r"), ContentBlock.Text("答案")), back.single().blocks)
    }

    @Test
    fun `getAgentMessages empty conversation emits empty list`() = runBlocking {
        assertTrue(repo.getAgentMessages(404L).first().isEmpty())
    }

    @Test
    fun `getAgentMessages dedupes content-equal re-emissions - distinctUntilChanged`() = runBlocking {
        // P2 台账 #21：legacy 行首发射被规范化并回写 → 回写触发 DB 失效重发射
        // （fake：同收集内重读快照）——两快照映射后内容相等，下游不得二次收到
        dao.rows += MessageEntity(id = 7L, conversationId = 5L, role = "assistant", content = "<think>r</think>答案", blocks = null)
        dao.emitDuplicate = true
        val emissions = repo.getAgentMessages(5L).toList()
        assertEquals(2, dao.snapshotsEmitted)           // 上游确发射两快照（重读已含回写后行状态）
        assertEquals(1, emissions.size)                 // 下游只见一次——内容相等的重发射被去重
        assertEquals(listOf(ContentBlock.Thinking("r"), ContentBlock.Text("答案")), emissions.single().single().blocks)
        assertEquals(listOf(7L), dao.updateBlocksIds)   // 第二快照已规范化（幂等跳过），回写仅一次
    }

    // ================================================================ 假件（手写 fake DAO——无 Robolectric）

    /**
     * MessageDao 手写假实现：仅忠实实现 RoomMessageStore/getAgentMessages 触达的方法
     * （id 自增模拟 autoGenerate；读序 (timestamp,id) 对齐 SQL）；未触达方法 fail-fast
     * 抛错——若生产代码开始调用它们，测试先行暴露。
     *
     * P3 T5：行插入计入统一调用序 [ops]（事务闭包结构断言）；
     * [emitDuplicate] 让消息流单次收集内连发两快照（第二快照重读行状态），
     * 模拟回写触发的 DB 失效重发射（P2 台账 #21 判定前提）。
     */
    private class FakeMessageDao(private val ops: MutableList<String>) : MessageDao {
        val rows = mutableListOf<MessageEntity>()
        val updateBlocksIds = mutableListOf<Long>()
        var flowEmissionCount = 0
        var snapshotsEmitted = 0
        var emitDuplicate = false

        private fun nextId(): Long = (rows.maxOfOrNull { it.id } ?: 0L) + 1L
        private fun ordered(cid: Long): List<MessageEntity> =
            rows.filter { it.conversationId == cid }.sortedWith(compareBy({ it.timestamp }, { it.id }))

        override suspend fun insertMessage(message: MessageEntity): Long {
            val id = if (message.id == 0L) nextId() else message.id
            rows.removeAll { it.id == id } // OnConflictStrategy.REPLACE
            rows += message.copy(id = id)
            ops += "insert:$id"
            return id
        }

        override fun getMessagesForConversation(conversationId: Long): Flow<List<MessageEntity>> = flow {
            flowEmissionCount++
            snapshotsEmitted++
            emit(ordered(conversationId))
            if (emitDuplicate) {
                snapshotsEmitted++
                emit(ordered(conversationId)) // 重读快照：含首发射回写后的行状态
            }
        }

        override suspend fun getMessageById(id: Long): MessageEntity? = rows.firstOrNull { it.id == id }

        override suspend fun deleteById(id: Long) {
            rows.removeAll { it.id == id }
        }

        override suspend fun deleteMessagesAfter(conversationId: Long, afterMessageId: Long) {
            rows.removeAll { it.conversationId == conversationId && it.id > afterMessageId }
        }

        override suspend fun deleteByTurnId(conversationId: Long, turnId: String): Int {
            val before = rows.size
            rows.removeAll { it.conversationId == conversationId && it.turnId == turnId }
            return before - rows.size
        }

        override suspend fun getLastAssistantTurnId(conversationId: Long): String? =
            rows.filter { it.conversationId == conversationId && it.role == "assistant" }
                .maxByOrNull { it.id }?.turnId

        override suspend fun updateBlocks(id: Long, content: String, reasoning: String?, blocks: String) {
            updateBlocksIds += id
            val i = rows.indexOfFirst { it.id == id }
            if (i >= 0) rows[i] = rows[i].copy(content = content, reasoning = reasoning, blocks = blocks)
        }

        override suspend fun getMaxMessageId(conversationId: Long): Long? =
            rows.filter { it.conversationId == conversationId }.maxOfOrNull { it.id }

        override suspend fun getMessagesForConversationOnce(conversationId: Long): List<MessageEntity> = ordered(conversationId)

        // ---- 以下未被 RoomMessageStore/getAgentMessages 触达：fail-fast ----
        private fun unreachable(): Nothing = throw UnsupportedOperationException("FakeMessageDao 未实现该方法（Room 管道 P3/真机验证）")

        override suspend fun countRoundtableAnchors(conversationId: Long): Int = unreachable()
        override fun getAllMessages(): Flow<List<MessageEntity>> = unreachable()
        override fun getAllMessagePreviews(): Flow<List<MessagePreview>> = unreachable()
        override suspend fun deleteMessagesForConversation(conversationId: Long) = unreachable()
        override suspend fun updateContent(id: Long, content: String) = unreachable()
        override suspend fun updateContentAndUsage(id: Long, content: String, promptTokens: Int?, completionTokens: Int?, cachedTokens: Int?) = unreachable()
        override suspend fun updateSegments(id: Long, segments: String?) = unreachable()
        override suspend fun deleteEmptyAssistantPlaceholders(conversationId: Long) = unreachable()
        override suspend fun getMessagesPaged(conversationId: Long, limit: Int, offset: Int): List<MessageEntity> = unreachable()
        override suspend fun searchMessages(query: String): List<MessageSearchRow> = unreachable()
        override suspend fun updateUsage(id: Long, promptTokens: Int?, completionTokens: Int?, cachedTokens: Int?) = unreachable()
        override suspend fun getMessageCount(conversationId: Long): Int = unreachable()
        override fun getLastMessagePerConversation(): Flow<List<LastMessageRow>> = unreachable()
        override fun getMessageCounts(): Flow<List<ConversationMessageCount>> = unreachable()
        override suspend fun getAllMessagesOnce(): List<MessageEntity> = unreachable()
        override suspend fun deleteAllMessages() = unreachable()
        override suspend fun getMessagesForExport(conversationId: Long): List<MessageExportRow> = unreachable()
    }

    /** 构造 ChatRepository/RoomMessageStore 专用：AppDatabase 假体——RoomDatabase 构造器仅建
     *  InvalidationTracker 骨架（纯 JVM 安全）；DAO/事务接缝均经构造器注入，db 本体不被测试触达
     *  （transaction 默认值引用 db.withTransaction，仅生产装配生效）。 */
    private class FakeAppDatabase : AppDatabase() {
        override fun conversationDao(): ConversationDao = throw UnsupportedOperationException("测试不取道 db.conversationDao()")
        override fun messageDao(): MessageDao = TODO("ChatRepository 经构造器注入 messageDao，不取道 db.messageDao()")
        override fun promptTemplateDao(): PromptTemplateDao = ThrowingPromptTemplateDao
        override fun chatRoomParticipantDao(): ChatRoomParticipantDao = throw UnsupportedOperationException()
        override fun personaPresetDao(): PersonaPresetDao = throw UnsupportedOperationException()
        override fun createInvalidationTracker(): InvalidationTracker = InvalidationTracker(this, "messages")
        override fun createOpenHelper(config: androidx.room.DatabaseConfiguration) =
            throw UnsupportedOperationException("Room 管道 P3/真机验证")
        override fun clearAllTables() = throw UnsupportedOperationException("Room 管道 P3/真机验证")
    }

    /** P3 T5：会话 touch 记录假件——仅实现 touch（`touch:<id>` 计入统一调用序，A-2 防线），
     *  其余 fail-fast（生产若开始触达，测试先行暴露）。db.conversationDao() 路径
     *  同样 fail-fast（DAO 经构造器注入，不走 db getter）。 */
    private class RecordingConversationDao(private val ops: MutableList<String>) : ConversationDao {
        private fun unreachable(): Nothing = throw UnsupportedOperationException("测试不触达")
        override suspend fun touch(id: Long, updatedAt: Long) { ops += "touch:$id" }
        override fun getAllConversations(): Flow<List<ConversationEntity>> = unreachable()
        override suspend fun getConversationById(id: Long): ConversationEntity? = unreachable()
        override fun observeConversation(id: Long): Flow<ConversationEntity?> = unreachable()
        override suspend fun insertConversation(conversation: ConversationEntity): Long = unreachable()
        override suspend fun updateTitle(id: Long, title: String, updatedAt: Long) = unreachable()
        override suspend fun updateTutorStateRaw(id: Long, state: String) = unreachable()
        override suspend fun updateTutorConfig(id: Long, cfg: String) = unreachable()
        override suspend fun updateSystemPrompt(id: Long, prompt: String) = unreachable()
        override suspend fun updateSkillParams(id: Long, json: String) = unreachable()
        override suspend fun deleteConversation(conversation: ConversationEntity) = unreachable()
        override suspend fun deleteById(id: Long) = unreachable()
        override suspend fun updatePinned(id: Long, pinned: Boolean, pinnedAt: Long?) = unreachable()
        override suspend fun getAllConversationsOnce(): List<ConversationEntity> = unreachable()
        override suspend fun deleteAllConversations() = unreachable()
    }

    private object ThrowingPromptTemplateDao : PromptTemplateDao {
        private fun unreachable(): Nothing = throw UnsupportedOperationException("测试不触达")
        override fun getAllTemplates(): Flow<List<PromptTemplateEntity>> = unreachable()
        override suspend fun getAllTemplatesSync(): List<PromptTemplateEntity> = unreachable()
        override suspend fun deleteAllTemplates() = unreachable()
        override suspend fun insertTemplate(template: PromptTemplateEntity): Long = unreachable()
        override suspend fun deleteTemplate(template: PromptTemplateEntity) = unreachable()
        override suspend fun renameByName(oldName: String, newName: String) = unreachable()
        override suspend fun getTemplateById(id: Long): PromptTemplateEntity? = unreachable()
        override suspend fun updateTemplate(id: Long, name: String, content: String) = unreachable()
    }
}
