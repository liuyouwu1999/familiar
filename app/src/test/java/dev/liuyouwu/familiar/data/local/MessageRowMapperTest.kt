package dev.liuyouwu.familiar.data.local

import com.google.gson.Gson
import dev.liuyouwu.familiar.core.llm.model.AgentMessage
import dev.liuyouwu.familiar.core.llm.model.Attachment
import dev.liuyouwu.familiar.core.llm.model.BlockCodec
import dev.liuyouwu.familiar.core.llm.model.ContentBlock
import dev.liuyouwu.familiar.core.llm.model.MessageMeta
import dev.liuyouwu.familiar.core.llm.model.ReplyRef
import dev.liuyouwu.familiar.core.llm.model.Role
import dev.liuyouwu.familiar.core.llm.model.SenderInfo
import dev.liuyouwu.familiar.data.local.entity.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * spec §8.1 行映射双向契约全清单（Task 7 测试矩阵）：
 * 普通回复往返；含 ToolCall 不拆条；TOOL_RESULT 行 role="tool" content=""；
 * 剧本三行（首行 [Thinking,Text] 其余 [Text]）往返保形；多图 user 三行读侧合并；
 * TOOL_RESULT callId 回配进同组；NULL turnId 各自成组；segments 直通；user-turnId 同值。
 *
 * 纯函数测试：不起 Room/Robolectric——行 id 由测试手动 copy 注入
 * （Room autoGenerate 在插入时才分配，fromRows 的 id 规则 = row.id.toString()）。
 */
class MessageRowMapperTest {

    private val gson = Gson()

    /** 给行注入 id，模拟 Room 插入后回读（(timestamp,id) 排序已由 SQL 保证，mapper 信任入参顺序）。 */
    private fun List<MessageEntity>.withIds(start: Long = 1L): List<MessageEntity> =
        mapIndexed { i, r -> r.copy(id = start + i) }

    // ---------------------------------------------------------------- toRow 写侧

    @Test
    fun `ordinary assistant reply is one row with all columns written`() {
        val msg = AgentMessage(
            id = "ignored-on-write",
            role = Role.ASSISTANT,
            blocks = listOf(ContentBlock.Thinking("推演"), ContentBlock.Text("答案A"), ContentBlock.Text("答案B")),
            sender = SenderInfo(name = "小明", model = "gpt-4o", colorHex = "#FFAA00"),
            replyTo = ReplyRef(messageId = 42L, preview = "引用内容", role = "user"),
            meta = MessageMeta(promptTokens = 10, completionTokens = 20, cachedTokens = 5),
            turnId = "run1:0",
        )
        val rows = MessageRowMapper.toRows(listOf(msg), conversationId = 7L)
        assertEquals(1, rows.size)
        val r = rows[0]
        assertEquals(7L, r.conversationId)
        assertEquals("assistant", r.role)
        assertEquals("答案A\n答案B", r.content)            // content 投影 = Text 块拼接
        assertEquals("推演", r.reasoning)                   // reasoning = Thinking 块拼接
        assertEquals(BlockCodec.toJson(msg.blocks), r.blocks) // blocks 权威列
        assertEquals("gpt-4o", r.senderModel)
        assertEquals("小明", r.senderDisplayName)
        assertEquals("#FFAA00", r.senderColorHex)
        assertEquals(42L, r.replyToMessageId)
        assertEquals("引用内容", r.replyToContent)
        assertEquals("user", r.replyToRole)
        assertEquals(10, r.promptTokens)
        assertEquals(20, r.completionTokens)
        assertEquals(5, r.cachedTokens)
        assertEquals("run1:0", r.turnId)
        assertNull(r.toolMeta)                              // 无工具块 → toolMeta 不写
    }

    @Test
    fun `assistant with tool call is not split - single row keeps blocks intact`() {
        val blocks = listOf(
            ContentBlock.ToolCall(id = "c1", name = "web_search", argsJson = """{"query":"JEV"}"""),
            ContentBlock.Text("搜索后的残文"),
        )
        val msg = AgentMessage(id = "x", role = Role.ASSISTANT, blocks = blocks, turnId = "run1:0")
        val rows = MessageRowMapper.toRows(listOf(msg), conversationId = 1L)

        assertEquals(1, rows.size) // 不拆条
        val r = rows[0]
        assertEquals("assistant", r.role)
        assertEquals(blocks, BlockCodec.fromJson(r.blocks!!)) // blocks 原样（ToolCall+残文 Text）
        assertEquals("搜索后的残文", r.content)
        val meta = r.toolMeta!!
        assertTrue("toolMeta 应记录 callId/name/argsJson",
            meta.contains("c1") && meta.contains("web_search") && meta.contains("query"))
    }

    @Test
    fun `tool result message is one row with role tool and empty content projection`() {
        val blocks = listOf(
            ContentBlock.ToolResult(callId = "c1", name = "web_search", output = "结果", isError = true),
        )
        val msg = AgentMessage(id = "x", role = Role.TOOL_RESULT, blocks = blocks, turnId = "run1:0")
        val r = MessageRowMapper.toRow(msg, conversationId = 1L)

        assertEquals("tool", r.role)
        assertEquals("", r.content) // TOOL_RESULT 行 content 空投影
        assertEquals(blocks, BlockCodec.fromJson(r.blocks!!))
        assertTrue(r.toolMeta!!.contains("c1"))
        assertEquals("run1:0", r.turnId)
    }

    @Test
    fun `role mapping covers all four roles`() {
        val cases = listOf(
            Role.USER to "user",
            Role.ASSISTANT to "assistant",
            Role.SYSTEM to "system",
            Role.TOOL_RESULT to "tool",
        )
        for ((role, column) in cases) {
            val r = MessageRowMapper.toRow(AgentMessage(id = "x", role = role, blocks = listOf(ContentBlock.Text("t"))), 1L)
            assertEquals(column, r.role)
        }
    }

    @Test
    fun `segments column is passthrough - never written, never consumed`() {
        // AgentMessage 不携带 segments：写侧恒 null（由调用方 post-mapping 写，P1 推迟项）
        val r = MessageRowMapper.toRow(
            AgentMessage(id = "x", role = Role.USER, blocks = listOf(ContentBlock.Text("hi"))), 1L,
        )
        assertNull(r.segments)

        // 读侧忽略 segments：仅 segments 不同的两行 → 产出相同消息
        val base = MessageEntity(
            id = 3L, conversationId = 1L, role = "assistant", content = "正文",
            blocks = BlockCodec.toJson(listOf(ContentBlock.Text("正文"))),
        )
        val a = MessageRowMapper.fromRows(listOf(base))
        val b = MessageRowMapper.fromRows(listOf(base.copy(segments = """["正文"]""")))
        assertEquals(a, b)
    }

    // ---------------------------------------------------------------- 双向往返

    @Test
    fun `ordinary reply roundtrip preserves message shape`() {
        val msg = AgentMessage(
            id = "whatever",
            role = Role.ASSISTANT,
            blocks = listOf(ContentBlock.Thinking("想"), ContentBlock.Text("答")),
            sender = SenderInfo(name = "老师", model = "m1", colorHex = "#112233"),
            replyTo = ReplyRef(messageId = 9L, preview = "被引用", role = "user"),
            meta = MessageMeta(promptTokens = 1, completionTokens = 2, cachedTokens = 3),
            turnId = "run2:1",
        )
        val rows = MessageRowMapper.toRows(listOf(msg), 5L).withIds(start = 100L)
        val back = MessageRowMapper.fromRows(rows)

        assertEquals(1, back.size)
        assertEquals(
            msg.copy(id = "100"), // id 规则：AgentMessage.id = row.id.toString()
            back[0],
        )
    }

    @Test
    fun `script three rows roundtrip keeps per-row shape`() {
        // 剧本终稿：调用方先经 skills 层 ContentProtocol 拆出 turns 再逐条构造——首行 [Thinking,Text] 其余 [Text]，
        // usage 挂首行，同 turnId
        val m1 = AgentMessage(
            id = "a", role = Role.ASSISTANT,
            blocks = listOf(ContentBlock.Thinking("备课"), ContentBlock.Text("[老师] 同学们好")),
            meta = MessageMeta(promptTokens = 11, completionTokens = 22, cachedTokens = 4),
            turnId = "run3:0",
        )
        val m2 = AgentMessage(id = "b", role = Role.ASSISTANT, blocks = listOf(ContentBlock.Text("[学生] 老师好")), turnId = "run3:0")
        val m3 = AgentMessage(id = "c", role = Role.ASSISTANT, blocks = listOf(ContentBlock.Text("[老师] 开始上课")), turnId = "run3:0")

        val rows = MessageRowMapper.toRows(listOf(m1, m2, m3), 1L)
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.turnId == "run3:0" })
        assertEquals("备课", rows[0].reasoning)
        assertNull(rows[1].reasoning)
        assertNull(rows[2].reasoning)
        assertEquals(11, rows[0].promptTokens)
        assertNull(rows[1].promptTokens)
        assertNull(rows[2].promptTokens)

        val back = MessageRowMapper.fromRows(rows.withIds())
        // 拆条行保持独立消息（UI 逐气泡）——不合并
        assertEquals(3, back.size)
        assertEquals(m1.copy(id = "1"), back[0])
        assertEquals(m2.copy(id = "2"), back[1])
        assertEquals(m3.copy(id = "3"), back[2])
    }

    @Test
    fun `multi image user fans out to three rows sharing user turnId and merges back`() {
        val msg = AgentMessage(
            id = "u1",
            role = Role.USER,
            blocks = listOf(ContentBlock.Text("看这三张图")),
            attachments = listOf(
                Attachment.Image("IMG_1"),
                Attachment.Image("IMG_2"),
                Attachment.Image("IMG_3"),
            ),
            turnId = MessageRowMapper.newUserTurnId(5L, 1L), // user-turnId：发送侧铸造
        )
        val rows = MessageRowMapper.toRows(listOf(msg), 5L)
        assertEquals(3, rows.size)
        assertTrue("多图 N 行同 user-turnId", rows.all { it.turnId == "5:u1" })
        assertEquals("user", rows[0].role)
        assertEquals("看这三张图", rows[0].content)
        assertEquals("IMG_1", rows[0].imageBase64)
        assertEquals("", rows[1].content) // 后续行 content 空投影
        assertEquals("IMG_2", rows[1].imageBase64)
        assertEquals("IMG_3", rows[2].imageBase64)

        // 读侧按 user-turnId 合并回 1 条 attachments=3
        val back = MessageRowMapper.fromRows(rows.withIds(start = 70L))
        assertEquals(1, back.size)
        assertEquals(
            msg.copy(id = "70"),
            back[0],
        )
        assertEquals(3, back[0].attachments.size)
    }

    @Test
    fun `mixed attachments fan out per row and merge back in order`() {
        val msg = AgentMessage(
            id = "u2",
            role = Role.USER,
            blocks = listOf(ContentBlock.Text("图、文件、视频帧")),
            attachments = listOf(
                Attachment.Image("IMG"),
                Attachment.File(name = "笔记.txt", text = "文件内容"),
                Attachment.VideoFrames(framesBase64 = listOf("f1", "f2")),
            ),
            turnId = "6:u9",
        )
        val rows = MessageRowMapper.toRows(listOf(msg), 6L)
        assertEquals(3, rows.size)
        assertEquals("IMG", rows[0].imageBase64)
        assertNull(rows[0].fileName)
        assertNull(rows[0].fileContent)
        assertNull(rows[1].imageBase64)
        assertEquals("笔记.txt", rows[1].fileName)
        assertEquals("文件内容", rows[1].fileContent)
        assertEquals(gson.toJson(listOf("f1", "f2")), rows[2].videoFrames)

        val back = MessageRowMapper.fromRows(rows.withIds())
        assertEquals(listOf(msg.copy(id = "1")), back)
    }

    @Test
    fun `toRow rejects multi attachment messages - silent drop guard`() {
        // 评审交接（a）：toRow 只映射首附件，静默丢其余附件属数据丢失——fail-fast 钉死，
        // 完整扇出必须走 toRows（偏离注记：原 toRow 对多附件返回首行，现改 require 拒绝）
        val msg = AgentMessage(
            id = "u1", role = Role.USER,
            blocks = listOf(ContentBlock.Text("两图")),
            attachments = listOf(Attachment.Image("IMG_1"), Attachment.Image("IMG_2")),
        )
        try {
            MessageRowMapper.toRow(msg, 1L)
            fail("多附件消息必须抛 IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `tool result callId stays paired within same turn group`() {
        // 同一 turn：assistant 行（含 ToolCall）+ TOOL_RESULT 行，callId 回配
        val assistant = AgentMessage(
            id = "a", role = Role.ASSISTANT,
            blocks = listOf(ContentBlock.ToolCall(id = "c9", name = "calc", argsJson = "{}")),
            turnId = "run4:0",
        )
        val toolResult = AgentMessage(
            id = "t", role = Role.TOOL_RESULT,
            blocks = listOf(ContentBlock.ToolResult(callId = "c9", name = "calc", output = "42", isError = false)),
            turnId = "run4:0",
        )
        val rows = MessageRowMapper.toRows(listOf(assistant, toolResult), 1L)
        assertEquals(2, rows.size)
        assertEquals("assistant", rows[0].role)
        assertEquals("tool", rows[1].role)

        val back = MessageRowMapper.fromRows(rows.withIds())
        assertEquals(2, back.size)
        assertEquals(assistant.copy(id = "1"), back[0])
        assertEquals(toolResult.copy(id = "2"), back[1]) // callId=c9 回配进同组
        assertEquals("run4:0", back[0].turnId)
        assertEquals("run4:0", back[1].turnId)
    }

    // ---------------------------------------------------------------- 读侧重建

    @Test
    fun `null turnId rows are each their own group`() {
        // 旧数据：turnId 全 NULL、blocks NULL——各自成组，blocks 回退 content 投影
        val rows = listOf(
            MessageEntity(id = 11L, conversationId = 1L, role = "user", content = "你好"),
            MessageEntity(id = 12L, conversationId = 1L, role = "assistant", content = "你好！"),
            MessageEntity(id = 13L, conversationId = 1L, role = "user", content = ""),
        )
        val back = MessageRowMapper.fromRows(rows)
        assertEquals(3, back.size) // 不因同 NULL 而合并
        assertEquals(AgentMessage(id = "11", role = Role.USER, blocks = listOf(ContentBlock.Text("你好"))), back[0])
        assertEquals(AgentMessage(id = "12", role = Role.ASSISTANT, blocks = listOf(ContentBlock.Text("你好！"))), back[1])
        assertEquals(AgentMessage(id = "13", role = Role.USER, blocks = emptyList()), back[2]) // 空内容 → 空块
        assertTrue(back.all { it.turnId == null && it.sender == null && it.meta == MessageMeta.NONE })
    }

    @Test
    fun `empty rows produce empty messages`() {
        assertEquals(emptyList<AgentMessage>(), MessageRowMapper.fromRows(emptyList()))
    }

    @Test
    fun `unknown role fails fast`() {
        val row = MessageEntity(id = 1L, conversationId = 1L, role = "alien", content = "?")
        try {
            MessageRowMapper.fromRows(listOf(row))
            fail("未知 role 应 fail-fast（与 RoomMessageStore String→Long 桥接同语义）")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `toRows equals per-message toRow for single attachment messages`() {
        val msgs = listOf(
            AgentMessage(id = "1", role = Role.USER, blocks = listOf(ContentBlock.Text("q")), turnId = "5:u1"),
            AgentMessage(id = "2", role = Role.ASSISTANT, blocks = listOf(ContentBlock.Text("a")), turnId = "run:0"),
            AgentMessage(id = "3", role = Role.TOOL_RESULT, blocks = listOf(ContentBlock.ToolResult("c", "n", "o", false)), turnId = "run:0"),
        )
        assertEquals(
            msgs.map { MessageRowMapper.toRow(it, 9L).copy(timestamp = 0L) }, // timestamp 是构造默认值（墙上钟），不属映射契约
            MessageRowMapper.toRows(msgs, 9L).map { it.copy(timestamp = 0L) },
        )
    }

    // ---------------------------------------------------------------- user-turnId 铸造

    @Test
    fun `new user turnId format is conversation scoped`() {
        assertEquals("5:u3", MessageRowMapper.newUserTurnId(5L, 3L))
        assertEquals("0:u0", MessageRowMapper.newUserTurnId(0L, 0L))
        assertNotEquals(MessageRowMapper.newUserTurnId(5L, 3L), MessageRowMapper.newUserTurnId(5L, 4L))
        assertNotEquals(MessageRowMapper.newUserTurnId(5L, 3L), MessageRowMapper.newUserTurnId(6L, 3L))
    }
}
