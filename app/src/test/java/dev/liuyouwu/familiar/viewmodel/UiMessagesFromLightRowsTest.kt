package dev.liuyouwu.familiar.viewmodel

import dev.liuyouwu.familiar.core.llm.model.BlockCodec
import dev.liuyouwu.familiar.core.llm.model.ContentBlock
import dev.liuyouwu.familiar.core.llm.model.Role
import dev.liuyouwu.familiar.data.local.dao.MessageLightRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审计 P1-4 聊天页轻量读路径的 VM 层桥契约（纯函数，JVM 可测）：
 * [uiMessagesFromLightRows]——轻量投影行 → UiMessage：
 * 1. hasAttachment 行附件置空 + 打 [dev.liuyouwu.familiar.ui.model.UiMessage.hasLazyAttachment]
 *    标（真值渲染位按需补载）；
 * 2. 多图 user 组（同 user-turnId 扇出 N 行）：合并为单条消息（id=首行），组内
 *    **任一**行有附件即标记（防「首行无图、后续行有图」的合并组漏标）；
 * 3. 结构列（blocks/segments/turnId）与全量路径同语义透传；blocks==NULL 旧行
 *    回退 content 单 textBlock。
 */
class UiMessagesFromLightRowsTest {

    private fun lightRow(
        id: Long,
        role: String,
        content: String,
        blocks: String? = null,
        turnId: String? = null,
        hasAttachment: Boolean = false,
        segments: String? = null,
    ) = MessageLightRow(
        id = id, conversationId = 1L, role = role, content = content,
        timestamp = id, blocks = blocks, turnId = turnId,
        hasAttachment = hasAttachment, segments = segments,
    )

    @Test
    fun `attachment row is marked lazy with empty inline attachments`() {
        val rows = listOf(
            lightRow(1, "user", "带图问", hasAttachment = true),
            lightRow(2, "assistant", "纯文本答"),
        )
        val ui = uiMessagesFromLightRows(rows)

        assertEquals(listOf("1", "2"), ui.map { it.id })
        assertTrue("hasAttachment 行打惰性标记", ui[0].hasLazyAttachment)
        assertTrue("行内附件置空（base64 不进流）", ui[0].images.isEmpty())
        assertTrue(ui[0].attachments.isEmpty())
        assertFalse("无附件行不标记", ui[1].hasLazyAttachment)
    }

    @Test
    fun `multi image user group merges and marks on any row attachment`() {
        val turnId = "1:u1"
        val rows = listOf(
            // 首行带正文但无附件列（理论形态：图在后续扇出行）
            lightRow(10, "user", "三图", turnId = turnId, blocks = BlockCodec.toJson(listOf(ContentBlock.Text("三图")))),
            lightRow(11, "user", "", turnId = turnId, hasAttachment = true, blocks = "[]"),
            lightRow(12, "user", "", turnId = turnId, hasAttachment = true, blocks = "[]"),
        )
        val ui = uiMessagesFromLightRows(rows)

        assertEquals("合并组单条消息，id=首行", listOf("10"), ui.map { it.id })
        assertTrue("组内任一行有附件 → 首行 id 标记", ui.single().hasLazyAttachment)
        assertEquals("正文取首行", listOf("三图"), ui.single().textBlocks)
    }

    @Test
    fun `structure columns pass through and legacy null blocks fall back to content`() {
        val segments = """["段一","段二"]"""
        val rows = listOf(
            lightRow(
                20, "assistant", "结构答",
                blocks = BlockCodec.toJson(listOf(ContentBlock.Thinking("想"), ContentBlock.Text("结构答"))),
                turnId = "1:u2", segments = segments,
            ),
            lightRow(21, "assistant", "旧行正文"),   // blocks=NULL：content 直出
        )
        val ui = uiMessagesFromLightRows(rows)

        assertEquals("想", ui[0].thinking)
        assertEquals(listOf("结构答"), ui[0].textBlocks)
        assertEquals(listOf("段一", "段二"), ui[0].segments)
        assertEquals(listOf("旧行正文"), ui[1].textBlocks)
    }

    @Test
    fun `hasAttachmentByMessageId follows mapper grouping rules`() {
        val rows = listOf(
            lightRow(30, "user", "a", turnId = "t1", hasAttachment = true),
            lightRow(31, "user", "", turnId = "t1"),                    // 同组：组级 any → 首行 true
            lightRow(32, "assistant", "b", turnId = "t2", hasAttachment = true), // 非合并组：逐行各自
            lightRow(33, "assistant", "c", turnId = "t2"),              // 同 turnId 但非 user 组：独立行
            lightRow(34, "user", "孤立行", turnId = null),
        )
        val map = hasAttachmentByMessageId(rows)

        assertEquals(true, map[30])
        assertEquals("组内成员不单立项（消息 id=首行）", null, map[31])
        assertEquals(true, map[32])
        assertEquals(false, map[33])
        assertEquals(false, map[34])
    }

    // ---------------- 审计复审 Important-1：prepend 副本不遮蔽 DB 新值、裁剪退场 ----------------

    @Test
    fun `light window rows take precedence over prepend copies - db update wins`() {
        // 滚顶加载后 DB 里的旧消息被编辑：轻量流发新值，prepend 还持取页时的旧快照
        val updated = lightRow(101, "user", "编辑后的正文")
        val staleCopy = updated.copy(content = "旧正文（取页时快照）")
        val light = listOf(lightRow(200, "user", "最新消息"), updated)
        val prepended = listOf(lightRow(50, "user", "更早页"), staleCopy, lightRow(99, "user", "缝行"))

        val merged = mergeLightRows(light, prepended)

        assertEquals(listOf(50L, 99L, 101L, 200L), merged.map { it.id })
        assertEquals("重叠 id 上 DB 新值接管，旧副本被压掉", "编辑后的正文", merged.first { it.id == 101L }.content)
        assertEquals("同一 id 只出现一次", 1, merged.count { it.id == 101L })
    }

    @Test
    fun `trim drops prepend rows covered by light window and ghosts`() {
        val light = listOf(lightRow(101, "user", "a"), lightRow(200, "user", "b"))
        // 150 在 prepend 有副本但流内无此行（DB 已删）：>= 窗口最小 id → 裁掉（不留幽灵）
        val prepended = listOf(lightRow(50, "user", "旧"), lightRow(101, "user", "旧副本"), lightRow(150, "user", "幽灵"))

        val trimmed = trimmedPrepend(prepended, light)

        assertEquals("只留窗口之外的行", listOf(50L), trimmed?.map { it.id })
    }

    @Test
    fun `trim is null when no change and clears prepend on empty window`() {
        val light = listOf(lightRow(101, "user", "a"))
        assertNull("全部行已在窗口外 → 无需裁剪（跳过重发射）", trimmedPrepend(listOf(lightRow(50, "user", "旧")), light))
        assertNull("prepend 为空 → 无需裁剪", trimmedPrepend(emptyList(), light))
        assertEquals(
            "窗口为空（清空会话）→ prepend 整体退场",
            emptyList<MessageLightRow>(),
            trimmedPrepend(listOf(lightRow(50, "user", "旧"), lightRow(60, "user", "旧2")), emptyList()),
        )
    }
}
