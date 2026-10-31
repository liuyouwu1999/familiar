package dev.liuyouwu.familiar.ui.model

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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Agent 内核 P3 Task 8：MessageRenderModel 纯函数契约（块→渲染段决策）。
 * 覆盖四条主链：
 * 1. from() 块分拣（Thinking 聚合 / Text 列表 / ToolCall→卡片）；
 * 2. 工具卡片状态映射（未配对=RUNNING；TOOL_RESULT 行按 callId 回配=DONE；
 *    isError=true→ERROR，"已取消"→CANCELLED）；
 * 3. fromEntity() 实体适配（sender 三列 / content 单 textBlock / 存量 <think>
 *    前缀一次性拆分 / blocks 权威列 / 附件提取 / segments 透传 / meta）；
 * 4. segments 透传（from 与 fromEntity 两条入口）。
 */
class MessageRenderModelTest {

    private fun entity(
        role: String = "assistant",
        content: String = "",
        blocks: String? = null,
        reasoning: String? = null,
        segments: String? = null,
        senderDisplayName: String? = null,
        senderColorHex: String? = null,
        senderModel: String? = null,
        imageBase64: String? = null,
        fileName: String? = null,
        videoFrames: String? = null,
        replyToContent: String? = null,
        replyToRole: String? = null,
        promptTokens: Int? = null,
        completionTokens: Int? = null,
        cachedTokens: Int? = null,
    ) = MessageEntity(
        id = 5L,
        conversationId = 1L,
        role = role,
        content = content,
        blocks = blocks,
        reasoning = reasoning,
        segments = segments,
        senderDisplayName = senderDisplayName,
        senderColorHex = senderColorHex,
        senderModel = senderModel,
        imageBase64 = imageBase64,
        fileName = fileName,
        videoFrames = videoFrames,
        replyToContent = replyToContent,
        replyToRole = replyToRole,
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        cachedTokens = cachedTokens,
    )

    // ---------------------------------------------------------------- from()：块分拣

    @Test
    fun `from sorts blocks into thinking aggregate text list and running tool cards`() {
        val msg = AgentMessage(
            id = "a1",
            role = Role.ASSISTANT,
            blocks = listOf(
                ContentBlock.Thinking("第一段"),
                ContentBlock.Text("开头"),
                ContentBlock.Thinking("第二段"),
                ContentBlock.ToolCall("c1", "web_search", """{"query": "猫"}"""),
                ContentBlock.Text("结尾"),
            ),
        )
        val ui = MessageRenderModel.from(msg)
        assertEquals("a1", ui.id)
        assertEquals(Role.ASSISTANT, ui.role)
        assertEquals("第一段\n第二段", ui.thinking)
        assertEquals(listOf("开头", "结尾"), ui.textBlocks)
        assertEquals(1, ui.toolBlocks.size)
        assertEquals("web_search", ui.toolBlocks[0].name)
        assertEquals("""{"query": "猫"}""", ui.toolBlocks[0].argsSummary)
        assertEquals(ToolBlockState.RUNNING, ui.toolBlocks[0].state) // 未配对 ToolCall
        assertNull(ui.toolBlocks[0].output)
        assertNull(ui.segments)
    }

    @Test
    fun `from keeps single line args summary capped at 48 chars`() {
        val long = "{\"query\": \"" + "x".repeat(80) + "\"}"
        val msg = AgentMessage(
            id = "a1", role = Role.ASSISTANT,
            blocks = listOf(
                ContentBlock.ToolCall("c1", "web_search", long),
                ContentBlock.ToolCall("c2", "clock", ""),
            ),
        )
        val ui = MessageRenderModel.from(msg)
        assertEquals(48 + 1, ui.toolBlocks[0].argsSummary.length) // 截断 + 省略号
        assertTrue(ui.toolBlocks[0].argsSummary.endsWith("…"))
        assertEquals("", ui.toolBlocks[1].argsSummary)
    }

    // ---------------------------------------------------------------- from()：工具状态映射

    @Test
    fun `from pairs following tool result rows into card final states by callId`() {
        val msg = AgentMessage(
            id = "a1", role = Role.ASSISTANT,
            blocks = listOf(
                ContentBlock.ToolCall("c1", "web_search", """{"q": "a"}"""),
                ContentBlock.ToolCall("c2", "clock", "{}"),
                ContentBlock.ToolCall("c3", "dice", "{}"),
            ),
        )
        val ui = MessageRenderModel.from(
            msg,
            toolResults = listOf(
                ContentBlock.ToolResult("c1", "web_search", "找到3条", isError = false),
                ContentBlock.ToolResult("c2", "clock", "已取消", isError = true),
                ContentBlock.ToolResult("c3", "dice", "工具执行超时", isError = true),
            ),
        )
        assertEquals(ToolBlockState.DONE, ui.toolBlocks[0].state)
        assertEquals("找到3条", ui.toolBlocks[0].output)
        assertEquals(ToolBlockState.CANCELLED, ui.toolBlocks[1].state) // isError + "已取消"
        assertEquals(ToolBlockState.ERROR, ui.toolBlocks[2].state)    // isError 其他输出
    }

    @Test
    fun `from unpaired tool call stays running`() {
        val msg = AgentMessage(
            id = "a1", role = Role.ASSISTANT,
            blocks = listOf(
                ContentBlock.ToolCall("c1", "web_search", "{}"),
                ContentBlock.ToolCall("cX", "clock", "{}"),
            ),
        )
        val ui = MessageRenderModel.from(
            msg, toolResults = listOf(ContentBlock.ToolResult("cX", "clock", "12:00", isError = false))
        )
        assertEquals(ToolBlockState.RUNNING, ui.toolBlocks[0].state)
        assertNull(ui.toolBlocks[0].output)
        assertEquals(ToolBlockState.DONE, ui.toolBlocks[1].state)
    }

    @Test
    fun `from renders own tool result blocks without call as standalone cards`() {
        // TOOL_RESULT 行（单独回填/取消补落）：blocks 只含 ToolResult，无自身 ToolCall
        val msg = AgentMessage(
            id = "t1", role = Role.TOOL_RESULT,
            blocks = listOf(ContentBlock.ToolResult("c9", "web_search", "boom", isError = true)),
        )
        val ui = MessageRenderModel.from(msg)
        assertEquals(1, ui.toolBlocks.size)
        assertEquals("web_search", ui.toolBlocks[0].name)
        assertEquals(ToolBlockState.ERROR, ui.toolBlocks[0].state)
        assertEquals("boom", ui.toolBlocks[0].output)
    }

    // ---------------------------------------------------------------- from()：sender/附件/segments/meta

    @Test
    fun `from maps sender attachments reply meta and passes segments through`() {
        val msg = AgentMessage(
            id = "u1", role = Role.USER,
            blocks = listOf(ContentBlock.Text("看这张图")),
            attachments = listOf(
                Attachment.Image("BASE64DATA"),
                Attachment.File("note.txt", "文件内容"),
                Attachment.VideoFrames(listOf("f1", "f2")),
            ),
            sender = SenderInfo(name = "小明", model = "gpt-4o", colorHex = "#FFAA00"),
            replyTo = ReplyRef(messageId = 9L, preview = "引用内容", role = "user"),
            meta = MessageMeta(promptTokens = 1, completionTokens = 2, cachedTokens = 3),
        )
        val ui = MessageRenderModel.from(msg, segments = listOf("段1", "段2"))
        assertEquals("小明", ui.senderName)
        assertEquals("gpt-4o", ui.senderModel)
        assertEquals("#FFAA00", ui.senderColorHex)
        assertEquals("BASE64DATA", ui.imageBase64)
        assertEquals(
            listOf(AttachmentSummary("note.txt", null), AttachmentSummary(null, 2)),
            ui.attachments,
        )
        assertEquals(listOf("段1", "段2"), ui.segments)
        assertEquals(MessageMeta(1, 2, 3), ui.meta)
        assertEquals("引用内容", ui.replyPreview)
        assertEquals("user", ui.replyRole)
        assertTrue(ui.isUser)
    }

    // ---------------------------------------------------------------- fromEntity()：实体适配（旧聊天链）

    @Test
    fun `fromEntity legacy row maps content as single textBlock with think prefix split`() {
        val e = entity(content = "<think>推理过程</think>\n\n正式回答")
        val ui = MessageRenderModel.fromEntity(e)
        assertEquals("5", ui.id)
        assertEquals(Role.ASSISTANT, ui.role)
        assertEquals("推理过程", ui.thinking)
        assertEquals(listOf("正式回答"), ui.textBlocks)
        assertTrue(ui.toolBlocks.isEmpty())
        assertNull(ui.imageBase64)
    }

    @Test
    fun `fromEntity recognizes legacy thinkyle tag and unclosed think from interrupted stream`() {
        val legacy = MessageRenderModel.fromEntity(entity(content = "<thinkyle>旧思考</thinkyle>答案"))
        assertEquals("旧思考", legacy.thinking)
        assertEquals(listOf("答案"), legacy.textBlocks)

        val unclosed = MessageRenderModel.fromEntity(entity(content = "<think>没写完的思考"))
        assertEquals("没写完的思考", unclosed.thinking)
        assertTrue(unclosed.textBlocks.isEmpty())
    }

    @Test
    fun `fromEntity user row keeps literal think tags in content`() {
        // 旧行为：user 消息不做思考拆分（字面量 <think> 原样展示）
        val ui = MessageRenderModel.fromEntity(entity(role = "user", content = "<think>我打的字</think>你好"))
        assertEquals(Role.USER, ui.role)
        assertNull(ui.thinking)
        assertEquals(listOf("<think>我打的字</think>你好"), ui.textBlocks)
    }

    @Test
    fun `fromEntity blocks row uses blocks as authority with tool card pairing`() {
        val blocks = listOf(
            ContentBlock.Thinking("深想"),
            ContentBlock.Text("答"),
            ContentBlock.ToolCall("c1", "t", "{}"),
            ContentBlock.ToolResult("c1", "t", "ok", isError = false),
        )
        val e = entity(
            role = "assistant", content = "答", reasoning = "深想",
            blocks = BlockCodec.toJson(blocks),
            senderDisplayName = "老师", senderColorHex = "#4E8FD8", senderModel = "glm",
            segments = """["a", "b"]""",
            promptTokens = 10, completionTokens = 20, cachedTokens = 5,
            replyToContent = "引用", replyToRole = "assistant",
        )
        val ui = MessageRenderModel.fromEntity(e)
        assertEquals("深想", ui.thinking)
        assertEquals(listOf("答"), ui.textBlocks)
        assertEquals(1, ui.toolBlocks.size)
        assertEquals(ToolBlockState.DONE, ui.toolBlocks[0].state)
        assertEquals("老师", ui.senderName)
        assertEquals("#4E8FD8", ui.senderColorHex)
        assertEquals("glm", ui.senderModel)
        assertEquals(listOf("a", "b"), ui.segments)
        assertEquals(MessageMeta(10, 20, 5), ui.meta)
        assertEquals("引用", ui.replyPreview)
        assertEquals("assistant", ui.replyRole)
    }

    @Test
    fun `fromEntity extracts image and file attachments with video frame count`() {
        val withImage = MessageRenderModel.fromEntity(entity(role = "user", content = "图", imageBase64 = "B64"))
        assertEquals("B64", withImage.imageBase64)
        // 行模型单图列 → images 列表透传（P5 多图首图修复：渲染侧全量出图）
        assertEquals(listOf("B64"), withImage.images)
        assertEquals(emptyList<String>(), MessageRenderModel.fromEntity(entity(content = "无图")).images)

        val withFile = MessageRenderModel.fromEntity(
            entity(role = "user", content = "视频", fileName = "clip.mp4", videoFrames = """["f1","f2","f3"]""")
        )
        assertEquals(listOf(AttachmentSummary("clip.mp4", 3)), withFile.attachments)

        val brokenFrames = MessageRenderModel.fromEntity(
            entity(role = "user", content = "视频", fileName = "clip.mp4", videoFrames = "not-json")
        )
        assertEquals(AttachmentSummary("clip.mp4", 0), brokenFrames.attachments.single())
    }

    @Test
    fun `from maps all image attachments not just the first`() {
        // P5 多图首图修复：内核附件多张 Image 全量进 UiMessage.images（首图位照旧兼容）
        val msg = AgentMessage(
            id = "u2", role = Role.USER,
            blocks = listOf(ContentBlock.Text("两图")),
            attachments = listOf(
                Attachment.Image("IMG1"),
                Attachment.Image("IMG2"),
                Attachment.File("f.txt", "x"),
            ),
        )
        val ui = MessageRenderModel.from(msg)
        assertEquals(listOf("IMG1", "IMG2"), ui.images)
        assertEquals("IMG1", ui.imageBase64)
    }

    @Test
    fun `fromEntity segments passthrough tolerates bad json and empty arrays`() {
        assertNull(MessageRenderModel.fromEntity(entity(segments = "not-json")).segments)
        assertNull(MessageRenderModel.fromEntity(entity(segments = """[]""")).segments)
        assertNull(MessageRenderModel.fromEntity(entity(segments = null)).segments)
        assertEquals(
            listOf("x"),
            MessageRenderModel.fromEntity(entity(segments = """["x"]""")).segments,
        )
    }

    @Test
    fun `fromEntity empty streaming ghost maps to empty render state`() {
        val ui = MessageRenderModel.fromEntity(entity(role = "assistant", content = ""))
        assertNull(ui.thinking)
        assertTrue(ui.textBlocks.isEmpty())
        assertEquals(MessageMeta.NONE, ui.meta)
    }
}
