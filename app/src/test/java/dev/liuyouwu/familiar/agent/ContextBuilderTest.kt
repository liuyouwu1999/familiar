package dev.liuyouwu.familiar.agent

import dev.liuyouwu.familiar.core.llm.model.AgentMessage
import dev.liuyouwu.familiar.core.llm.model.ContentBlock
import dev.liuyouwu.familiar.core.llm.model.Role
import dev.liuyouwu.familiar.core.llm.model.Usage
import dev.liuyouwu.familiar.data.local.entity.ChatRoomParticipantEntity
import dev.liuyouwu.familiar.data.local.entity.ConversationEntity
import dev.liuyouwu.familiar.data.remote.api.ConnectionProbe
import dev.liuyouwu.familiar.util.memory.FamiliarMemoryStore
import dev.liuyouwu.familiar.util.persona.PersonaCard
import dev.liuyouwu.familiar.viewmodel.skillhooks.ClassroomHooks
import dev.liuyouwu.familiar.viewmodel.skillhooks.FamiliarHooks
import dev.liuyouwu.familiar.viewmodel.skillhooks.SkillRepoPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ContextBuilderTest {

    private fun msg(id: String) =
        AgentMessage(id = id, role = Role.USER, blocks = listOf(ContentBlock.Text(id)))

    /** 末条消息的计费文本（kept 末位 = 尾部附注消息时的附注正文提取） */
    private fun tailText(built: BuiltContext): String =
        built.kept.last().blocks.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }

    /** 无动作仓库假件：装配回归只过 transformContext/build，不走落库动作 */
    private val noRepo = object : SkillRepoPort {
        override suspend fun updateSegments(messageId: Long, segmentsJson: String?) {}
        override suspend fun updateTutorState(conversationId: Long, stateJson: String) {}
        override suspend fun getParticipants(conversationId: Long): List<ChatRoomParticipantEntity> = emptyList()
        override suspend fun getConversation(conversationId: Long): ConversationEntity? = null
        override suspend fun updateTitle(conversationId: Long, title: String) {}
    }

    @Test
    fun `keeps newest drops oldest per ContextTrimmer semantics`() {
        // 数据必须超过 256 下限才能触发裁剪（评审三 P1：小数据在下限语义下永远全保留）
        val msgs = (1..400).map { msg("m$it") }
        // ContextTrimmer.trim 真实语义：system 计费（budget = max - reserve - estimate(system)）。
        // estimate={ 1 } 下 system "sys" 占 1 token，故 brief 原值 305 等量 +1 → 306（budget=305）
        val built = ContextBuilder("sys", msgs, emptyList()).build(maxContext = 306, reserveForOutput = 0, estimate = { 1 })
        assertEquals((96..400).map { "m$it" }, built.kept.map { it.id }) // budget=305 → 保留最新 305 条
        assertEquals(95, built.droppedCount)
    }

    @Test
    fun `anchor semantics kept is suffix-monotonic across budgets`() {
        val msgs = (1..400).map { msg("m$it") }
        // system 计费（同上）：两个 maxContext 均等量 +1（600→601、305→306）
        val r1 = ContextBuilder("sys", msgs, emptyList()).build(maxContext = 601, reserveForOutput = 0, estimate = { 1 })
        val r2 = ContextBuilder("sys", msgs, emptyList()).build(maxContext = 306, reserveForOutput = 0, estimate = { 1 })
        val ids1 = r1.kept.map { it.id }; val ids2 = r2.kept.map { it.id }
        assertEquals(400, ids1.size)                   // r1 预算内全保留
        assertEquals(305, ids2.size)                   // r2 确实触发裁剪（超 256 下限，非平凡断言）
        assertEquals(ids1.takeLast(305), ids2)         // 从尾贪心 ⇒ kept 是旧 kept 的尾部子序列（锚点稳定）
        assertEquals(r1.system, r2.system)             // system 字节稳定
    }

    // 「reserve formula identical to data 层旧客户端」断言曾随内核清理 A Task 6 删除并登记；
    // A 终审以新形式回补为下方 computeLocalMaxTokens 逐值等价用例（双副本漂移防线）

    @Test
    fun `reserveForOutput stays value-equal to ConnectionProbe computeLocalMaxTokens`() {
        // 双副本漂移防线（A 终审回补）：agent 包不 import data 层，reserveForOutput 与
        // ConnectionProbe.computeLocalMaxTokens 是同源公式的两个副本——本地后端实际生成
        // 上限取后者，裁剪预留取前者，逐值不等时小窗口本地模型会整请求超窗
        for (maxContext in intArrayOf(4096, 8192, 131072)) {
            assertEquals(
                ContextBuilder.reserveForOutput(maxContext),
                ConnectionProbe.computeLocalMaxTokens(maxContext),
            )
        }
    }

    // ===== estimateTokens 自有实现（P5：语义自 util.ContextTrimmer 迁入，公式逐字符复制）=====

    @Test
    fun `estimateTokens cjk counts one token per char`() {
        // 源 ContextTrimmerTest `cjk counts one token per char` 同断言迁移（该文件随死代码退役）
        assertEquals(5, ContextBuilder.estimateTokens("你好世界呀"))
    }

    @Test
    fun `estimateTokens ascii counts about four chars per token`() {
        // 源 ContextTrimmerTest `ascii counts about four chars per token` 同断言迁移
        val t = ContextBuilder.estimateTokens("abcdefgh")
        assertTrue(t in 2..3)
    }

    @Test
    fun `estimateTokens empty is zero and mixed scripts accumulate`() {
        assertEquals(0, ContextBuilder.estimateTokens(""))
        // 混排双计：2 ASCII → (2+3)/4=1，2 CJK → 2，合计 3
        assertEquals(3, ContextBuilder.estimateTokens("hi你好"))
    }

    @Test
    fun `computeBreakdown bills like trim with system charged and reserve deducted`() {
        val messages = listOf(
            AgentMessage("u1", Role.USER, blocks = listOf(ContentBlock.Text("你好世界"))),   // 4 CJK → 4
            AgentMessage("a1", Role.ASSISTANT, blocks = listOf(ContentBlock.Thinking("深度思考"))), // Thinking 不回发 → 不计费
            AgentMessage(
                "a1", Role.ASSISTANT,
                blocks = listOf(ContentBlock.ToolCall("b1", "echo", """{"query":"hi"}""")),
            ),   // "echo\n{...}" 19 字符 → (19+3)/4=5
            AgentMessage(
                "r1", Role.TOOL_RESULT,
                blocks = listOf(ContentBlock.ToolResult("b1", "echo", "echo:hi", isError = false)),
            ),   // 7 字符 → (7+3)/4=2
        )
        // maxContext=400：budget=(400-256-1)=143 → 落 MIN_BUDGET=256 下限（reserve 亦被钳到 256）
        val bd = ContextBuilder.computeBreakdown(
            system = "sys", messages = messages, maxContext = 400,
            estimate = ContextBuilder::estimateTokens,
        )
        assertEquals(11, bd.messages)        // 4 + 0 + 5 + 2（messageText 口径：Thinking 不计）
        assertEquals(0, bd.attachments)      // 附件不上 wire、trim 不计费 → 同源恒 0
        assertEquals(1, bd.systemPrompt)     // "sys" 3 字符 → 1（system 计费，同 trim budget 扣减项）
        assertEquals(256, bd.reserve)        // reserveForOutput(400)：100 钳 256（同源公式）
        assertEquals(400, bd.maxContext)
        assertEquals(12, bd.used)            // messages + attachments + systemPrompt
        assertEquals(132, bd.free)           // 400 - 12 - 256

        // 同源交叉验证：总量 11 未超 budget 下限 256 → build 全保留，与 breakdown 描述一致
        val built = ContextBuilder("sys", messages, emptyList())
            .build(400, ContextBuilder.reserveForOutput(400), ContextBuilder::estimateTokens)
        assertEquals(4, built.kept.size)
        assertEquals(0, built.droppedCount)
    }

    // ===== 尾部注入 API（P5：appendSystem 缓存 miss 修复）=====

    @Test
    fun `trailing note keeps system bytes identical and charges trim budget`() {
        // 数据超过 256 下限触发裁剪：附注计费进预算扣减（旧链同口径，7753c8f 删除前
        // ChatViewModel:1527-1537 budgetPrompt 计入 trim 的 systemPrompt）但不进 trim 循环
        val msgs = (1..400).map { msg("m$it") }
        val note = "（系统附注：记忆参考——逐请求变化的内容）"
        val plain = ContextBuilder("sys", msgs, emptyList()).build(306, 0) { 1 }
        val injected = ContextBuilder("sys", msgs, emptyList())
            .apply { appendTrailingUserNote(note) }
            .build(306, 0) { 1 }
        // 缓存命中断言：system 字节与无注入 build 完全一致（前缀缓存锚点不动）
        assertEquals(plain.system, injected.system)
        // 附注占预算（estimate=1 → 预算 305→304）：少留最老一条历史 + 末尾附注，
        // 总消息数不变（304 历史 + 1 附注 == 305）
        assertEquals(plain.kept.drop(1).map { it.id }, injected.kept.dropLast(1).map { it.id })
        assertEquals(plain.kept.size, injected.kept.size)
        assertEquals(plain.droppedCount + 1, injected.droppedCount)
        // 附注形态：末条 kept 之后追加的 user 消息（attachTimeContext 式尾部，仅请求装配层）
        assertEquals(Role.USER, injected.kept.last().role)
        assertEquals(note, tailText(injected))
    }

    @Test
    fun `build supersedes previously materialized trailing note in input`() {
        // loop 侧 ctx 形态：历史含上一装配轮物化的附注（Session 预裁产物为输入）——
        // 重新装配时旧附注剥除（不进 trim、不重复计费），由本轮附注取代
        val history = listOf(
            msg("u1"),
            AgentMessage(
                ContextBuilder.TRAILING_NOTE_ID, Role.USER,
                blocks = listOf(ContentBlock.Text("上一轮附注")),
            ),
            AgentMessage("a1", Role.ASSISTANT, blocks = listOf(ContentBlock.Text("回复"))),
        )
        val built = ContextBuilder("sys", history, emptyList())
            .apply { appendTrailingUserNote("本轮附注") }
            .build(10_000, 0) { 1 }
        // kept = 真实历史 + 本轮附注：旧附注不在产物中、附注恒一条恒在尾
        assertEquals(listOf("u1", "a1"), built.kept.dropLast(1).map { it.id })
        assertEquals(1, built.kept.count { it.id == ContextBuilder.TRAILING_NOTE_ID })
        assertEquals("本轮附注", tailText(built))
    }

    @Test
    fun `multiple trailing notes join into single tail message empty ignored`() {
        // ClassroomHooks 单轮两次注入（TS 状态段 + 续讲指令）：并入同一条尾部附注，段间空行
        val ctx = ContextBuilder("sys", listOf(msg("m1")), emptyList())
        ctx.appendTrailingUserNote("")
        ctx.appendTrailingUserNote("附注一")
        ctx.appendTrailingUserNote("附注二")
        val built = ctx.build(10_000, 0) { 1 }
        assertEquals(listOf("m1"), built.kept.dropLast(1).map { it.id })
        assertEquals("附注一\n\n附注二", tailText(built))
    }

    @Test
    fun `trailing note still injected when history empty`() {
        val built = ContextBuilder("sys", emptyList(), emptyList())
            .apply { appendTrailingUserNote("附注") }
            .build(10_000, 0) { 1 }
        assertEquals(1, built.kept.size)
        assertEquals(Role.USER, built.kept[0].role)
        assertEquals("附注", tailText(built))
    }

    // ===== hooks 装配回归（familiar 记忆尾 / 课堂 TS 状态：anchor 语义）=====

    @Test
    fun `familiar memory tail rides trailing user note system stays byte stable`() {
        val dir = Files.createTempDirectory("familiar-mem-p5").toFile()
        val store = FamiliarMemoryStore(dir)
        store.restore("# ===== 对用户的了解（稳定事实）=====\n- 我叫小明\n# ===== 互动记录 =====\n")
        val hooks = FamiliarHooks(
            PersonaCard(name = "小鱼"), store, noRepo, CoroutineScope(Dispatchers.Unconfined),
            { _, _ -> }, {},
        )
        val ctx1 = ContextBuilder("sys", listOf(msg("u1")), emptyList())
        hooks.transformContext(ctx1)
        val b1 = ctx1.build(10_000, 0) { 1 }

        // 跨轮记忆变化（新增稳定事实）+ 历史尾部追加（真实对话推进形态）
        store.restore("# ===== 对用户的了解（稳定事实）=====\n- 我叫小明\n- 我住在杭州\n# ===== 互动记录 =====\n")
        val history2 = listOf(
            msg("u1"),
            AgentMessage("a1", Role.ASSISTANT, blocks = listOf(ContentBlock.Text("你好呀"))),
            msg("u2"),
        )
        val ctx2 = ContextBuilder("sys", history2, emptyList())
        hooks.transformContext(ctx2)
        val b2 = ctx2.build(10_000, 0) { 1 }

        // anchor 语义回归：两轮 system 前缀字节稳定（KV 前缀缓存命中恢复）
        assertEquals("sys", ctx1.system)
        assertEquals(ctx1.system, ctx2.system)
        // 注入文本随记忆变化，且以尾部 user 消息落在末条真实 user 之后
        assertEquals(Role.USER, b1.kept.last().role)
        assertEquals(Role.USER, b2.kept.last().role)
        assertTrue(tailText(b1).contains("我叫小明"))
        assertTrue(tailText(b2).contains("我住在杭州"))
        assertNotEquals(tailText(b1), tailText(b2))
        // 请求装配层注入：原历史列表不被污染（落库原文不含附注）
        assertEquals(listOf("u1"), b1.kept.dropLast(1).map { it.id })
        assertEquals(listOf("u1", "a1", "u2"), b2.kept.dropLast(1).map { it.id })
    }

    @Test
    fun `classroom ts state rides trailing user note system stays byte stable`() {
        val stateA = """{"sections":[{"t":"第一节 加法","st":"done"}],"current":1,"phase":"teaching"}"""
        val stateB = """{"sections":[{"t":"第一节 加法","st":"done"},{"t":"第二节 减法","st":"active"}],"current":2,"phase":"teaching","weak":["借位运算"],"digest":"已完成第一节"}"""
        val history = listOf(msg("u1"))
        val hooksA = ClassroomHooks(1L, noRepo, initialTutorState = stateA, scope = CoroutineScope(Dispatchers.Unconfined))
        val hooksB = ClassroomHooks(1L, noRepo, initialTutorState = stateB, scope = CoroutineScope(Dispatchers.Unconfined))
        val ctxA = ContextBuilder("sys", history, emptyList()).also(hooksA::transformContext)
        val ctxB = ContextBuilder("sys", history, emptyList()).also(hooksB::transformContext)
        val bA = ctxA.build(10_000, 0) { 1 }
        val bB = ctxB.build(10_000, 0) { 1 }

        // anchor 语义回归：状态块逐轮变化只进尾部附注，system 前缀字节稳定
        assertEquals("sys", ctxA.system)
        assertEquals(ctxA.system, ctxB.system)
        assertEquals(Role.USER, bA.kept.last().role)
        assertTrue(tailText(bA).contains("当前学习进度"))
        assertTrue(tailText(bB).contains("第二节 减法"))
        assertTrue(tailText(bB).contains("借位运算"))
        assertNotEquals(tailText(bA), tailText(bB))
        assertEquals(listOf("u1"), bB.kept.dropLast(1).map { it.id })
    }

    @Test
    fun `classroom start command injects per-section search note`() {
        val state = """{"sections":[{"t":"第一节 jev 是什么","st":"active"}],"current":1,"phase":"teaching"}"""
        val history = listOf(AgentMessage("u1", Role.USER, listOf(ContentBlock.Text("/start"))))
        val hooks = ClassroomHooks(1L, noRepo, initialTutorState = state, scope = CoroutineScope(Dispatchers.Unconfined))
        val built = ContextBuilder("sys", history, emptyList()).also(hooks::transformContext).build(10_000, 0) { 1 }
        // 按计划当前节标题逐节注入（2026-10-31 实测：工具在场小模型不自发用）
        assertTrue(tailText(built).contains("联网搜索"))
        assertTrue(tailText(built).contains("第一节 jev 是什么"))
        // 状态块照常同附注
        assertTrue(tailText(built).contains("当前学习进度"))
        assertEquals(listOf("u1"), built.kept.dropLast(1).map { it.id })
    }

    @Test
    fun `classroom start without plan injects no search note`() {
        val history = listOf(AgentMessage("u1", Role.USER, listOf(ContentBlock.Text("/start"))))
        val hooks = ClassroomHooks(1L, noRepo, initialTutorState = null, scope = CoroutineScope(Dispatchers.Unconfined))
        val built = ContextBuilder("sys", history, emptyList()).also(hooks::transformContext).build(10_000, 0) { 1 }
        // 无计划不注入：末位就是真实 user 消息，没有尾部附注
        assertEquals(listOf("u1"), built.kept.map { it.id })
    }

    @Test
    fun `classroom start with empty sections injects no search note`() {
        val state = """{"sections":[],"current":3,"phase":"teaching"}"""
        val history = listOf(AgentMessage("u1", Role.USER, listOf(ContentBlock.Text("/start"))))
        val hooks = ClassroomHooks(1L, noRepo, initialTutorState = state, scope = CoroutineScope(Dispatchers.Unconfined))
        val built = ContextBuilder("sys", history, emptyList()).also(hooks::transformContext).build(10_000, 0) { 1 }
        // 节越界/空大纲：状态块照常，但不带搜索指令
        assertTrue(tailText(built).contains("当前学习进度"))
        assertFalse(tailText(built).contains("联网搜索"))
    }

    @Test
    fun `classroom non-start message injects no search note even with plan`() {
        val state = """{"sections":[{"t":"第一节 jev 是什么","st":"active"}],"current":1,"phase":"teaching"}"""
        val history = listOf(AgentMessage("u1", Role.USER, listOf(ContentBlock.Text("继续"))))
        val hooks = ClassroomHooks(1L, noRepo, initialTutorState = state, scope = CoroutineScope(Dispatchers.Unconfined))
        val built = ContextBuilder("sys", history, emptyList()).also(hooks::transformContext).build(10_000, 0) { 1 }
        assertTrue(tailText(built).contains("当前学习进度"))
        assertFalse(tailText(built).contains("联网搜索"))
    }

    @Test
    fun `classroom continuation note rides trailing user note not system`() {
        val hostScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val hooks = ClassroomHooks(1L, noRepo, initialTutorState = null, gapMs = 1, scope = hostScope)
        hooks.followUp = {}
        val chain = RunChain("c1", CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        // 拆出剧本（turns != null 且 phase != await_user）→ autoContinue 置续讲旗标
        hooks.onRunEnd(
            RunResult("[老师] 我们继续讲下一小节。", emptyList(), Usage(), 1, false, false),
            chain,
        )
        // 续讲 run 的历史以 assistant 行收尾（不触发历史尾 user 的防御性复位）
        val history = listOf(
            msg("u1"),
            AgentMessage("a1", Role.ASSISTANT, blocks = listOf(ContentBlock.Text("[老师] 上一小节讲完了。"))),
        )
        val ctx = ContextBuilder("sys", history, emptyList())
        hooks.transformContext(ctx)
        val built = ctx.build(10_000, 0) { 1 }
        assertEquals("sys", ctx.system)
        assertEquals(Role.USER, built.kept.last().role)
        assertTrue(tailText(built).contains(ClassroomHooks.CONTINUATION_NOTE))
        assertEquals(listOf("u1", "a1"), built.kept.dropLast(1).map { it.id })
        chain.cancel()
        hostScope.cancel()
    }

    @Test
    fun `classroom continuation flag not reset by trailing note tail`() {
        val hostScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val hooks = ClassroomHooks(1L, noRepo, initialTutorState = null, gapMs = 1, scope = hostScope)
        hooks.followUp = {}
        val chain = RunChain("c1", CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        hooks.onRunEnd(
            RunResult("[老师] 我们继续讲下一小节。", emptyList(), Usage(), 1, false, false),
            chain,
        )
        // loop 侧 ctx 形态：真实历史以 assistant 收尾，末尾挂上一轮物化的尾部附注
        //（user 形态）——防御性复位只看真实历史，附注不得把续讲旗标打掉
        val history = listOf(
            msg("u1"),
            AgentMessage("a1", Role.ASSISTANT, blocks = listOf(ContentBlock.Text("[老师] 讲完了。"))),
            AgentMessage(
                ContextBuilder.TRAILING_NOTE_ID, Role.USER,
                blocks = listOf(ContentBlock.Text("（系统附注）")),
            ),
        )
        val ctx = ContextBuilder("sys", history, emptyList())
        hooks.transformContext(ctx)
        val built = ctx.build(10_000, 0) { 1 }
        assertTrue(
            "续讲旗标被附注尾部误复位: ${tailText(built)}",
            tailText(built).contains(ClassroomHooks.CONTINUATION_NOTE),
        )
        chain.cancel()
        hostScope.cancel()
    }

    @Test
    fun `classroom continuation flag survives trim-only assembly`() {
        val hostScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val hooks = ClassroomHooks(1L, noRepo, initialTutorState = null, gapMs = 1, scope = hostScope)
        hooks.followUp = {}
        val chain = RunChain("c1", CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        hooks.onRunEnd(
            RunResult("[老师] 我们继续讲下一小节。", emptyList(), Usage(), 1, false, false),
            chain,
        )
        // 一次 run 的两次装配：session 侧预裁（trimOnly，产物只用于计费裁剪）先于
        // loop 侧 wire 装配执行——一次性旗标只能在 wire 侧消费，否则续讲指令
        // 永远到不了 wire（P3/P4 隐性缺陷：消费发生在被丢弃的 session system 上）
        val history = listOf(
            msg("u1"),
            AgentMessage("a1", Role.ASSISTANT, blocks = listOf(ContentBlock.Text("[老师] 讲完了。"))),
        )
        val built = { ctx: ContextBuilder -> tailText(ctx.build(10_000, 0) { 1 }) }
        val trimCtx = ContextBuilder("sys", history, emptyList(), trimOnly = true)
        hooks.transformContext(trimCtx)
        assertTrue(
            "trimOnly 装配仍需注入附注（预裁预算计费口径）: ${built(trimCtx)}",
            built(trimCtx).contains(ClassroomHooks.CONTINUATION_NOTE),
        )
        val wireCtx = ContextBuilder("sys", history, emptyList())
        hooks.transformContext(wireCtx)
        assertTrue(
            "续讲旗标被 trimOnly 装配误消费: ${built(wireCtx)}",
            built(wireCtx).contains(ClassroomHooks.CONTINUATION_NOTE),
        )
        // wire 侧消费后旗标复位（一次性语义不跨 run 泄漏）
        val nextCtx = ContextBuilder("sys", history, emptyList())
        hooks.transformContext(nextCtx)
        assertFalse(
            "旗标应已在 wire 侧消费: ${built(nextCtx)}",
            built(nextCtx).contains(ClassroomHooks.CONTINUATION_NOTE),
        )
        chain.cancel()
        hostScope.cancel()
    }

    @Test
    fun `familiar turn capture ignores trailing note message`() = runBlocking {
        val dir = Files.createTempDirectory("familiar-mem-p5c").toFile()
        val store = FamiliarMemoryStore(dir)
        val hooks = FamiliarHooks(
            PersonaCard(name = "小鱼"), store, noRepo, CoroutineScope(coroutineContext),
            { _, _ -> }, {},
        )
        // loop 侧 ctx 形态：真实 user 之后挂着上一轮物化的尾部附注（user 形态）
        val ctx = ContextBuilder(
            "sys",
            listOf(
                AgentMessage("u1", Role.USER, blocks = listOf(ContentBlock.Text("帮我记住这件事"))),
                AgentMessage(
                    ContextBuilder.TRAILING_NOTE_ID, Role.USER,
                    blocks = listOf(ContentBlock.Text("（记忆参考——不应被当作本轮 user 文本）")),
                ),
            ),
            emptyList(),
        )
        hooks.transformContext(ctx)
        hooks.onRunEnd(
            RunResult("好的", emptyList(), Usage(), 1, false, false),
            RunChain("c1", CoroutineScope(coroutineContext)),
        )
        // 记忆落盘走 hooksScope 协程（runBlocking 调度器）：delay 让出后轮询
        val deadline = System.currentTimeMillis() + 5_000
        while (store.current().lines.isEmpty() && System.currentTimeMillis() < deadline) delay(10)
        val line = store.current().lines.single()
        assertTrue("落盘应含真实 user 文本: $line", line.contains("帮我记住这件事"))
        assertFalse("附注不得被当作本轮 user 文本: $line", line.contains("记忆参考"))
    }
}
