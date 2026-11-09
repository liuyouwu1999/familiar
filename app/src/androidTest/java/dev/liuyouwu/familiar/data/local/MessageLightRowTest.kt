package dev.liuyouwu.familiar.data.local

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import dev.liuyouwu.familiar.data.local.dao.MessageAttachmentRow
import dev.liuyouwu.familiar.data.local.entity.ConversationEntity
import dev.liuyouwu.familiar.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 聊天页轻量读路径（审计 P1-4）的真 SQL 契约——纯 JVM fake 验不了 SQL 语义，
 * 须 in-memory Room（与 Migration17_18Test 同批 connected 跑）：
 * - 投影等价：getMessagesLight 的文本+结构列 == getMessagesForConversation 对应列
 * - hasAttachment 三态：纯图 true / 纯文本 false / 视频帧 true（图片行 fileName 恒
 *   null，存在性只能靠 SQL 布尔列）
 * - limit 边界：201 条/limit=200 → 恰 200 条且含最新行
 * - keyset 分页：getMessagesBefore(首屏最早 id) → 恰 1 条（SQL 侧 id 倒序，repo 反转）
 * - 附件惰性：getAttachment 单行按需取；getAttachmentsForTurn 多图组按组整取
 * 本地跑法：./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=dev.liuyouwu.familiar.data.local.MessageLightRowTest
 */
class MessageLightRowTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: dev.liuyouwu.familiar.data.local.dao.MessageDao
    private val convId = 1L

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.messageDao()
        runBlocking {
            db.conversationDao().insertConversation(ConversationEntity(id = convId, title = "t"))
        }
    }

    @After
    fun tearDown() = db.close()

    private suspend fun insert(message: MessageEntity): Long = dao.insertMessage(message)

    @Test
    fun lightProjection_matchesFullQuery_columnsAndOrder() = runBlocking {
        val blocks = """[{"type":"text","text":"hi"}]"""
        insert(MessageEntity(conversationId = convId, role = "user", content = "带图问", imageBase64 = "QUJD", timestamp = 1))
        insert(
            MessageEntity(
                conversationId = convId, role = "assistant", content = "结构答", timestamp = 2,
                reasoning = "思考中", blocks = blocks, toolMeta = """[{"callId":"c1","name":"http_get","argsJson":"{}"}]""",
                turnId = "1:u1", segments = """["段一","段二"]""",
                promptTokens = 10, completionTokens = 20, cachedTokens = 5,
                senderDisplayName = "老师", senderModel = "gpt", senderColorHex = "#1976D2",
                replyToMessageId = 1L, replyToContent = "带图问", replyToRole = "user",
            )
        )
        insert(MessageEntity(conversationId = convId, role = "assistant", content = "普通答", timestamp = 3))

        val light = dao.getMessagesLight(convId, 200).first()
        val full = dao.getMessagesForConversation(convId).first()

        assertEquals("行数一致", full.size, light.size)
        assertEquals("行序一致（timestamp,id 升序）", full.map { it.id }, light.map { it.id })
        light.zip(full).forEach { (l, f) ->
            assertEquals(f.id, l.id)
            assertEquals(f.conversationId, l.conversationId)
            assertEquals(f.role, l.role)
            assertEquals(f.content, l.content)
            assertEquals(f.reasoning, l.reasoning)
            assertEquals(f.blocks, l.blocks)
            assertEquals(f.segments, l.segments)
            assertEquals(f.toolMeta, l.toolMeta)
            assertEquals(f.turnId, l.turnId)
            assertEquals(f.promptTokens, l.promptTokens)
            assertEquals(f.completionTokens, l.completionTokens)
            assertEquals(f.cachedTokens, l.cachedTokens)
            assertEquals(f.timestamp, l.timestamp)
        }
        // 结构列逐值（防集合等值断言漏字段）：第二行携带全部结构载荷
        assertEquals("结构答", light[1].content)
        assertEquals(blocks, light[1].blocks)
        assertEquals("1:u1", light[1].turnId)
        assertEquals(listOf("段一", "段二"), light[1].segments?.let { parseFrames(it) })
    }

    @Test
    fun hasAttachment_threeStates() = runBlocking {
        insert(MessageEntity(conversationId = convId, role = "user", content = "纯图", imageBase64 = "QUJD", timestamp = 1))
        insert(MessageEntity(conversationId = convId, role = "user", content = "纯文本", timestamp = 2))
        insert(MessageEntity(conversationId = convId, role = "user", content = "视频帧", videoFrames = """["ZjE=","ZjI="]""", timestamp = 3))
        insert(MessageEntity(conversationId = convId, role = "user", content = "文件", fileName = "a.txt", fileContent = "hi", timestamp = 4))

        val light = dao.getMessagesLight(convId, 200).first().sortedBy { it.timestamp }
        assertTrue("纯图 → true（fileName 恒 null，不能靠 fileName 判）", light[0].hasAttachment)
        assertFalse("纯文本 → false", light[1].hasAttachment)
        assertTrue("视频帧 → true", light[2].hasAttachment)
        assertTrue("文件 → true（fileName 非空也算附件）", light[3].hasAttachment)
    }

    @Test
    fun limitWindow_201Messages_returnsLatest200WithNewestRow() = runBlocking {
        repeat(201) { i ->
            insert(MessageEntity(conversationId = convId, role = "user", content = "m$i", timestamp = 1_000L + i))
        }
        val light = dao.getMessagesLight(convId, 200).first()
        assertEquals("恰 200 条", 200, light.size)
        assertEquals("含最新行（id 序最大）", 201L, light.maxOf { it.id })
        assertEquals("最旧行是第 2 条（最新 200 条窗口）", 2L, light.minOf { it.id })
        assertEquals("外层恢复升序", light.map { it.id }, light.map { it.id }.sorted())
    }

    @Test
    fun keysetBefore_earliestAnchor_returnsExactlyOneOlderRow() = runBlocking {
        repeat(201) { i ->
            insert(MessageEntity(conversationId = convId, role = "user", content = "m$i", timestamp = 1_000L + i))
        }
        val light = dao.getMessagesLight(convId, 200).first()
        val earliest = light.minOf { it.id }   // 首屏最早 id = 2
        assertEquals(2L, earliest)

        val older = dao.getMessagesBefore(convId, earliest, 200)
        assertEquals("缝里恰剩 1 条", 1, older.size)
        assertEquals(1L, older[0].id)
        assertEquals("SQL 侧 id 倒序（repo 反转回升序）", listOf(1L), older.map { it.id })
    }

    @Test
    fun attachmentLazy_singleAndGroupFetch() = runBlocking {
        // 单行附件（assistant 带图）：getAttachment 按需取，不依赖列表查询
        val singleId = insert(
            MessageEntity(conversationId = convId, role = "assistant", content = "答", imageBase64 = "QUJD", timestamp = 1)
        )
        // 多图 user 组：同 user-turnId 扇出 3 行（首行带正文，后续行仅附件列）
        val turnId = "$convId:u9"
        val g1 = insert(MessageEntity(conversationId = convId, role = "user", content = "三图", imageBase64 = "aW1nMQ==", timestamp = 2, turnId = turnId))
        val g2 = insert(MessageEntity(conversationId = convId, role = "user", content = "", imageBase64 = "aW1nMg==", timestamp = 3, turnId = turnId, blocks = "[]"))
        val g3 = insert(MessageEntity(conversationId = convId, role = "user", content = "", imageBase64 = "aW1nMw==", timestamp = 4, turnId = turnId, blocks = "[]"))
        // 无附件行
        val plainId = insert(MessageEntity(conversationId = convId, role = "user", content = "纯文本", timestamp = 5))

        val single = dao.getAttachment(singleId)
        assertEquals(MessageAttachmentRow(singleId, "QUJD", null, null, null), single)

        // 合并组按组整取（防多图组只出首图）：首行 id 单查只有 1 图，组查有 3 图
        assertEquals(listOf("aW1nMQ=="), listOfNotNull(dao.getAttachment(g1)?.imageBase64))
        val group = dao.getAttachmentsForTurn(convId, turnId)
        assertEquals(listOf(g1, g2, g3), group.map { it.id })
        assertEquals(listOf("aW1nMQ==", "aW1nMg==", "aW1nMw=="), group.mapNotNull { it.imageBase64 })

        // 无附件行：getAttachment 返回行（载荷列全 null），投影 hasAttachment=false
        val plain = dao.getAttachment(plainId)
        assertNotNull(plain)
        assertNull(plain?.imageBase64)
        val light = dao.getMessagesLight(convId, 200).first()
        assertFalse(light.first { it.id == plainId }.hasAttachment)
        assertTrue(light.first { it.id == g2 }.hasAttachment)
    }

    private fun parseFrames(json: String): List<String>? = try {
        com.google.gson.Gson().fromJson(json, Array<String>::class.java)?.toList()
    } catch (_: Exception) {
        null
    }
}
