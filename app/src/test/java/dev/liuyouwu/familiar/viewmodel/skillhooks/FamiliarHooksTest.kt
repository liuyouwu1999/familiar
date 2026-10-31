package dev.liuyouwu.familiar.viewmodel.skillhooks

import dev.liuyouwu.familiar.agent.BuiltContext
import dev.liuyouwu.familiar.agent.ContextBuilder
import dev.liuyouwu.familiar.agent.RunChain
import dev.liuyouwu.familiar.agent.RunResult
import dev.liuyouwu.familiar.core.llm.model.AgentMessage
import dev.liuyouwu.familiar.core.llm.model.ContentBlock
import dev.liuyouwu.familiar.core.llm.model.Role
import dev.liuyouwu.familiar.core.llm.model.Usage
import dev.liuyouwu.familiar.data.local.entity.ChatRoomParticipantEntity
import dev.liuyouwu.familiar.data.local.entity.ConversationEntity
import dev.liuyouwu.familiar.util.memory.FamiliarMemoryStore
import dev.liuyouwu.familiar.util.persona.DefaultPersonaCards
import dev.liuyouwu.familiar.util.persona.PersonaCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * B2-L2 滚动反刍附注（transformContext 尾部注入）单元测试（task 7 brief Step 1）：
 * - ≥2 条合格 assistant 开头样本才注入（fewer-than-two 不注入）；
 * - 样本剥句首自称（铁律 6 强制自称开头，不剥则样本恒同）；
 * - 全同样本仍注入（rut 信号本身，spec §7"全同样本也注入"）；
 * - 排除对象：无正文行（纯工具调用）与 compact 摘要行（SUMMARY_ROW_PREFIX）。
 *
 * fixture：真 FamiliarMemoryStore（空目录 → buildContext 返回 null，tail 只含反刍注）、
 * repo 记账假件（照抄 SkillHostTest.FakeRepo 形态）、Unconfined scope（JVM 单测无
 * Main dispatcher，launch 同线程即刻执行）。断言经 build(0, 0) 产物末条 user 行
 * （TRAILING_NOTE_ID）文本——helper tailText 照抄 ContextBuilderTest 同名私有件。
 *
 * task 9 追加：B2-L4 触发机制（捕获/判定/旗标/复位/熔断）+ 顺手项① openerOf
 * 剥自称后空白回退。
 */
class FamiliarHooksTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `anti-rut note injected with two or more assistant openers`() {
        val history = listOf(
            AgentMessage("u1", Role.USER, blocks = listOf(ContentBlock.Text("你好"))),
            AgentMessage("a1", Role.ASSISTANT, blocks = listOf(ContentBlock.Text("本鲸在。说吧，什么事。"))),
            AgentMessage("u2", Role.USER, blocks = listOf(ContentBlock.Text("今天天气如何"))),
            AgentMessage("a2", Role.ASSISTANT, blocks = listOf(ContentBlock.Text("本鲸不看天气。你自己看窗外。"))),
            AgentMessage("u3", Role.USER, blocks = listOf(ContentBlock.Text("切"))),
        )
        val tail = buildTail(history)
        assertTrue(tail.contains("除自称外"))
        assertTrue(tail.contains("在。"))               // 剥自称后首句（openerOf=首句截取）
        assertTrue(tail.contains("不看天气"))
        assertFalse(tail.contains("本鲸"))               // 自称已剥
    }

    @Test
    fun `identical openers still injected (rut signal)`() {
        // spec §7"全同样本也注入"——正是 rut 信号，不得静默
        val history = listOf(
            AgentMessage("u1", Role.USER, blocks = listOf(ContentBlock.Text("你好"))),
            AgentMessage("a1", Role.ASSISTANT, blocks = listOf(ContentBlock.Text("本鲸在。说吧。"))),
            AgentMessage("u2", Role.USER, blocks = listOf(ContentBlock.Text("嗯"))),
            AgentMessage("a2", Role.ASSISTANT, blocks = listOf(ContentBlock.Text("本鲸在。干嘛。"))),
            AgentMessage("u3", Role.USER, blocks = listOf(ContentBlock.Text("切"))),
        )
        val tail = buildTail(history)
        assertTrue(tail.contains("除自称外"))
        assertTrue(tail.contains("在。"))
    }

    @Test
    fun `no anti-rut note with fewer than two assistants`() {
        val history = listOf(
            AgentMessage("u1", Role.USER, blocks = listOf(ContentBlock.Text("你好"))),
            AgentMessage("a1", Role.ASSISTANT, blocks = listOf(ContentBlock.Text("本鲸在。"))),
            AgentMessage("u2", Role.USER, blocks = listOf(ContentBlock.Text("切"))),
        )
        assertFalse(buildTail(history).contains("除自称外"))
    }

    @Test
    fun `empty-text assistant rows and summary rows are skipped`() {
        // 合格样本 2 条（a0/a3）；排除对象：无正文行（a1）与摘要行（a2）——复审 N2：
        // 原版仅 1 条合格样本，与"≥2 才注入"互斥，咕嘟断言必败
        val history = listOf(
            AgentMessage("u1", Role.USER, blocks = listOf(ContentBlock.Text("搜一下 X"))),
            AgentMessage("a0", Role.ASSISTANT, blocks = listOf(ContentBlock.Text("在。在的。"))),
            AgentMessage("a1", Role.ASSISTANT, blocks = listOf(ContentBlock.ToolCall("t1", "web_search", "{}"))),   // 无正文
            AgentMessage("r1", Role.TOOL_RESULT, blocks = listOf(ContentBlock.ToolResult("t1", "web_search", "…", false))),
            AgentMessage("a2", Role.ASSISTANT, blocks = listOf(ContentBlock.Text("[对话摘要] 此前对话的摘要"))),      // 摘要行
            AgentMessage("u2", Role.USER, blocks = listOf(ContentBlock.Text("继续"))),
            AgentMessage("a3", Role.ASSISTANT, blocks = listOf(ContentBlock.Text("咕嘟。接上了。"))),
            AgentMessage("u3", Role.USER, blocks = listOf(ContentBlock.Text("好"))),
        )
        val tail = buildTail(history)
        assertTrue(tail.contains("除自称外"))
        assertTrue(tail.contains("咕嘟"))
        assertFalse(tail.contains("对话摘要"))
    }

    @Test
    fun `openerOf falls back to unstripped sample when self-name strip leaves blank`() {
        // 顺手项①（Task 7 遗留）：样本整条只剩自称+紧邻标点时剥自称后空白，
        // 回退用未剥自称的原首句样本，防 antiRutNote 注入空样本（"／／"形态）
        assertEquals("本鲸：", openerOf("本鲸：", "本鲸"))
    }

    @Test
    fun `openerOf falls back when strip leaves punctuation only`() {
        // B2 台账："本鲸。"剥自称后剩"。"——纯标点非空白（isBlank 不命中），
        // 同样回退未剥自称的原首句，防反刍注注入"。"这类无信息样本
        assertEquals("本鲸。", openerOf("本鲸。", "本鲸"))
    }

    // ---- task 9：B2-L4 触发机制（捕获/判定/旗标/复位/熔断）----

    private val history = mutableListOf<AgentMessage>()
    private var rowIdSeq = 100L
    private var reviseCalls = 0
    private var capturedReviseRowId = -1L

    /** 一轮对话的测试形态：transformContext → onPersisted → onRunEnd（历史累积，
     *  同 user 行 id 传法模拟 regenerate 删后历史的重试轮） */
    private fun runTurn(hooks: FamiliarHooks, userId: String, userText: String, finalText: String, cancelled: Boolean = false) {
        history += AgentMessage(userId, Role.USER, blocks = listOf(ContentBlock.Text(userText)))
        hooks.transformContext(ContextBuilder("sys", history.toList(), emptyList()))
        val asst = AgentMessage("a-${history.size}", Role.ASSISTANT, blocks = listOf(ContentBlock.Text(finalText)))
        history += asst
        hooks.onPersisted(listOf(asst), listOf(rowIdSeq++))
        hooks.onRunEnd(
            RunResult(finalText = finalText, blocks = asst.blocks,
                usage = Usage(), turnCount = 1, truncated = false, cancelled = cancelled),
            RunChain("c1", CoroutineScope(SupervisorJob())),
        )
    }

    private fun hooksWithRevise(): FamiliarHooks = FamiliarHooks(
        card = PersonaCard.from(DefaultPersonaCards.DEFAULT_FAMILIAR_BLUE),
        memoryStore = FamiliarMemoryStore(tmp.newFolder()), repo = FakeRepo(),
        scope = CoroutineScope(Dispatchers.Unconfined),
        onSegments = { _, _ -> }, onTurnEnded = {},
    ).apply {
        revise = { rowId -> reviseCalls++; capturedReviseRowId = rowId }
    }

    /** longReply：s 重复两遍补长到 ≥30 CJK 字（ReplySimilarity 双方 <30 CJK 恒 false） */
    private fun longReply(s: String): String = s + s

    private fun reply() = longReply("哼，才不是特意为你做的呢。本鲸只是刚好路过厨房，顺手把面煮了而已，别多想啊老板。")
    private fun other() = longReply("深夜了还不睡？明天还要早起，先去把灯关了躺下，事情明天再想也一样，我先替你把焦虑嚼了。")
    private fun otherVariant() = longReply("深夜了还不睡？明天还要早起，先去把灯关了躺下，事情明天再想也一样，我先替你把焦虑嚼了呀。")   // 与 other() 高度相似（复审 N3 熔断序列用）

    /** 下一轮尾文本：追加新 user 行后 transformContext+build 取尾文本（附注旗标泄漏检查用） */
    private fun nextTurnTail(hooks: FamiliarHooks, userId: String, userText: String): String {
        history += AgentMessage(userId, Role.USER, blocks = listOf(ContentBlock.Text(userText)))
        val ctx = ContextBuilder("sys", history.toList(), emptyList())
        hooks.transformContext(ctx)
        return tailText(ctx.build(0, 0) { 0 })
    }

    private fun asstOf(text: String) =
        AgentMessage("a-x", Role.ASSISTANT, blocks = listOf(ContentBlock.Text(text)))

    @Test
    fun `similar reply triggers revise with target row id`() {
        val hooks = hooksWithRevise()
        history += asstOf(reply())                       // 播种上一条回复（prev 文本源=历史，复审 N3）
        hooks.onPersisted(listOf(asstOf(reply())), listOf(101L))
        runTurn(hooks, userId = "u2", userText = "再煮一次吧", finalText = reply())
        assertEquals(1, reviseCalls)
        assertEquals(100L, capturedReviseRowId)   // 目标行=当轮自身行：runTurn 的 onPersisted 先于 onRunEnd（rowIdSeq 初值 100，三轮 P0 勘正——原断言 101 与时序互斥）
    }

    @Test
    fun `retry turn does not re-trigger (loop guard)`() {
        // 一轮 #1 / 二轮复验的无限循环防线：重试轮 transformContext 见到的仍是同一
        // user 行 id → revisionAttempted 不得复位 → onRunEnd 不再触发
        val hooks = hooksWithRevise()
        history += asstOf(reply())
        hooks.onPersisted(listOf(asstOf(reply())), listOf(101L))
        runTurn(hooks, userId = "u2", userText = "再煮一次吧", finalText = reply())
        assertEquals(1, reviseCalls)
        runTurn(hooks, userId = "u2", userText = "再煮一次吧", finalText = reply())   // 同 user 行 id：重试轮
        assertEquals(1, reviseCalls)
    }

    @Test
    fun `flag not set when revise never launches (no leak into next turn)`() {
        // 二轮 N2：revise 未接线（或核验方放弃）→ pendingRevisionNote 不得置位，
        // 下一轮（新 user 行）附注里不得出现 revision 指令
        val hooks = FamiliarHooks(
            card = PersonaCard.from(DefaultPersonaCards.DEFAULT_FAMILIAR_BLUE),
            memoryStore = FamiliarMemoryStore(tmp.newFolder()), repo = FakeRepo(),
            scope = CoroutineScope(Dispatchers.Unconfined),
            onSegments = { _, _ -> }, onTurnEnded = {},
        )   // revise 不回填
        history += asstOf(reply())
        hooks.onPersisted(listOf(asstOf(reply())), listOf(101L))
        runTurn(hooks, userId = "u2", userText = "再煮一次吧", finalText = reply())
        val tail = nextTurnTail(hooks, userId = "u3", userText = "今天吃啥")   // 新 user 行
        assertFalse(tail.contains("完全不同的说法"))
    }

    @Test
    fun `breaker blocks third consecutive revision, normal turn resets`() {
        // 三轮 P0-2 修正后的熔断语义：计数在触发点自增、正常轮清零、
        // 新 user 行只复位"每轮限一次"不清计数（原设计是新永真门）
        val hooks = hooksWithRevise()
        history += asstOf(reply())
        hooks.onPersisted(listOf(asstOf(reply())), listOf(101L))
        runTurn(hooks, userId = "u2", userText = "再煮一次吧", finalText = reply())          // 相似 → 触发 (1)
        assertEquals(1, reviseCalls)
        runTurn(hooks, userId = "u3", userText = "再来一次", finalText = reply())            // 新 user 行 → 相似 → 触发 (2)
        assertEquals(2, reviseCalls)
        runTurn(hooks, userId = "u4", userText = "还要", finalText = reply())                // 相似 → 熔断，不触发
        assertEquals(2, reviseCalls)
        runTurn(hooks, userId = "u5", userText = "讲点别的", finalText = other())            // 正常轮 → 计数清零
        runTurn(hooks, userId = "u6", userText = "再讲一遍", finalText = otherVariant())     // prev=other、变体相似 → 触发 (3)（复审 N3：不能再用 reply——与 prev 不相似会走正常轮）
        assertEquals(3, reviseCalls)
    }

    @Test
    fun `cancelled run never triggers nor counts`() {
        val hooks = hooksWithRevise()
        history += asstOf(reply())
        runTurn(hooks, userId = "u2", userText = "你好", finalText = reply(), cancelled = true)   // 取消：不触发不计
        assertEquals(0, reviseCalls)
    }

    @Test
    fun `missing previous assistant never triggers (first turn)`() {
        val hooks = hooksWithRevise()   // 不播种：prev 为空
        runTurn(hooks, userId = "u1", userText = "你好", finalText = reply())
        assertEquals(0, reviseCalls)
    }

    @Test
    fun `trimOnly build does not consume flag, wire build does`() {
        val hooks = hooksWithRevise()
        hooks.markRevisionLaunched()
        val trimCtx = ContextBuilder("sys", emptyList(), emptyList(), trimOnly = true)   // trimOnly 为构造 val（ContextBuilder.kt:53，审查 N6）
        hooks.transformContext(trimCtx)
        assertTrue(hooks.revisionPendingForTest)   // trimOnly 不消费（ClassroomHooks:121-127 纪律）
        val wireCtx = ContextBuilder("sys", emptyList(), emptyList())
        hooks.transformContext(wireCtx)
        assertFalse(hooks.revisionPendingForTest)   // wire 侧消费
        assertTrue(tailText(wireCtx.build(0, 0) { 0 }).contains("完全不同的说法"))
    }

    private fun buildTail(history: List<AgentMessage>): String {
        val hooks = FamiliarHooks(
            card = PersonaCard.from(DefaultPersonaCards.DEFAULT_FAMILIAR_BLUE),
            memoryStore = FamiliarMemoryStore(tmp.newFolder()), repo = FakeRepo(),
            scope = CoroutineScope(Dispatchers.Unconfined),
            onSegments = { _, _ -> }, onTurnEnded = {},
        )
        val ctx = ContextBuilder("sys", history, emptyList())
        hooks.transformContext(ctx)
        return tailText(ctx.build(0, 0) { 0 })
    }

    /** 末条消息的计费文本（kept 末位 = 尾部附注消息时的附注正文提取）——照抄 ContextBuilderTest 同名私有件 */
    private fun tailText(built: BuiltContext): String =
        built.kept.last().blocks.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }

    /** repo 端口假件：照抄 SkillHostTest.FakeRepo 形态（记录三类写；本测试不触达落库动作） */
    private class FakeRepo : SkillRepoPort {
        val segmentUpdates = java.util.concurrent.CopyOnWriteArrayList<Pair<Long, String?>>()
        override suspend fun updateSegments(messageId: Long, segmentsJson: String?) {
            segmentUpdates += messageId to segmentsJson
        }

        override suspend fun updateTutorState(conversationId: Long, stateJson: String) {}
        override suspend fun getParticipants(conversationId: Long): List<ChatRoomParticipantEntity> = emptyList()
        override suspend fun getConversation(conversationId: Long): ConversationEntity? = null
        override suspend fun updateTitle(conversationId: Long, title: String) {}
    }
}
