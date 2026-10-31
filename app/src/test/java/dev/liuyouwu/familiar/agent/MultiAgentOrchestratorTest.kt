package dev.liuyouwu.familiar.agent

import com.google.gson.JsonObject
import dev.liuyouwu.familiar.core.llm.client.LlmClient
import dev.liuyouwu.familiar.core.llm.client.LlmRequest
import dev.liuyouwu.familiar.core.llm.model.AgentMessage
import dev.liuyouwu.familiar.core.llm.model.ContentBlock
import dev.liuyouwu.familiar.core.llm.model.Role
import dev.liuyouwu.familiar.core.llm.model.SenderInfo
import dev.liuyouwu.familiar.core.llm.model.StreamEvent
import dev.liuyouwu.familiar.core.llm.model.Usage
import dev.liuyouwu.familiar.core.llm.wire.ToolMode
import dev.liuyouwu.familiar.tools.AgentTool
import dev.liuyouwu.familiar.tools.ToolRegistry
import dev.liuyouwu.familiar.viewmodel.RunMarkerTexts
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MultiAgentOrchestrator 行为测试（P4 T1，spec §5.3）：一回合=一条链（acquireChain
 * 一次，多角色顺序 runOnChain）；PLANNER 一次轻量调度请求（不入库）+ 解析失败/异常
 * 降级 ROUND_ROBIN；角色 system/model 覆盖到达请求；每角色预算重置 1（§6.2 每角色
 * 回合 1 次）；取消半径=整回合（stop() 中断回合、已产出角色消息保留）；sender
 * 盖章落库。ScriptedLlm 双脚本位（第 1 位=调度，其后=各角色 turn）。
 */
class MultiAgentOrchestratorTest {

    /**
     * 注入用 RunMarkers（内核清理 A，Task 4）：AgentLoop 第三参必填（包内无中文
     * 缺省值）。取生产字面（SkillHost 装配同源），字面等价由 RunMarkerTextsSnapshotTest 快照钉死。
     */
    private val markers = RunMarkers(
        truncationMarker = RunMarkerTexts.TRUNCATION_MARKER,
        truncationTail = RunMarkerTexts.TRUNCATION_TAIL,
        noAnswerMarker = RunMarkerTexts.NO_ANSWER_MARKER,
        noAnswerTail = RunMarkerTexts.NO_ANSWER_TAIL,
        toolCancelled = RunMarkerTexts.TOOL_CANCELLED,
        noClosingAnswerNote = RunMarkerTexts.NO_CLOSING_ANSWER_NOTE,
    )

    /**
     * 脚本回放 LlmClient（AgentSessionTest 同款 DSL + [X] 抛异常位）：
     * `[T]正文` / `[K]思考` / `[C]query` echo 工具调用 / `[W]ms` 流中暂停 /
     * `[X]msg` 本 turn 抛异常（调度请求异常降级用）。
     */
    private class ScriptedLlm : LlmClient {
        val calls = mutableListOf<LlmRequest>()
        val budgetSeen = mutableListOf<Boolean>()
        /** 流自然收完计数（调度窗口哨兵测试的判别位：取消即不再增长） */
        var streamCompletions = 0
        private val scripts = ArrayDeque<String>()

        fun script(vararg turns: String) {
            scripts.addAll(turns)
        }

        override fun stream(req: LlmRequest): Flow<StreamEvent> = flow {
            calls += req
            budgetSeen += req.budgetLeft()
            val s = scripts.removeFirstOrNull() ?: error("第 ${calls.size} 次 LLM 调用无脚本")
            emit(StreamEvent.RunStart("s-${calls.size}"))
            var blockSeq = 0
            var truncated = false
            val toolCalls = mutableListOf<ContentBlock.ToolCall>()
            for (line in s.lines().map { it.trim() }) {
                when {
                    line.isEmpty() -> {}
                    line == "[!]" -> truncated = true
                    line.startsWith("[T]") -> {
                        val id = "t${blockSeq++}"
                        emit(StreamEvent.BlockStart(id, "text"))
                        emit(StreamEvent.BlockDelta(id, line.removePrefix("[T]")))
                        emit(StreamEvent.BlockEnd(id))
                    }
                    line.startsWith("[K]") -> {
                        val id = "k${blockSeq++}"
                        emit(StreamEvent.BlockStart(id, "thinking"))
                        emit(StreamEvent.BlockDelta(id, line.removePrefix("[K]")))
                        emit(StreamEvent.BlockEnd(id))
                    }
                    line.startsWith("[C]") -> {
                        val id = "c${blockSeq++}"
                        val args = """{"query":"${line.removePrefix("[C]")}"}"""
                        emit(StreamEvent.BlockStart(id, "toolCall"))
                        emit(StreamEvent.ToolCallDelta(id, "echo", args))
                        emit(StreamEvent.BlockEnd(id))
                        toolCalls += ContentBlock.ToolCall(id, "echo", args)
                    }
                    line.startsWith("[W]") -> delay(line.removePrefix("[W]").trim().toLong())
                    line.startsWith("[X]") -> throw RuntimeException(line.removePrefix("[X]").trim())
                    else -> error("未知脚本行：$line")
                }
            }
            emit(StreamEvent.TurnEnd(toolCalls.toList(), truncated))
            emit(StreamEvent.RunEnd(Usage(promptTokens = 10, completionTokens = 5)))
            streamCompletions++
        }
    }

    /** 回显工具（预算测试装门后即受门管：成功执行递减链预算） */
    private class EchoTool : AgentTool {
        override val name = "echo"
        override val description = "回显"
        override val parametersJson = """{"type":"object","properties":{"query":{"type":"string"}}}"""

        override suspend fun execute(args: JsonObject, onProgress: (String) -> Unit): String =
            "echo:" + (args.get("query")?.takeIf { it.isJsonPrimitive }?.asString ?: "")
    }

    private val roles = listOf(
        MultiAgentOrchestrator.RoleSpec("老师", "你是老师，负责解答", model = "teacher-model", colorHex = "#FF0000"),
        MultiAgentOrchestrator.RoleSpec("学生", "你是学生，负责提问", model = null, colorHex = "#00FF00"),
    )

    /** 装配 session + 共享同一 loop/registry（Orchestrator 构造需要 loop；预算门挂同一 registry） */
    private fun newSession(
        store: MessageStore,
        llm: LlmClient,
        registry: ToolRegistry = ToolRegistry().apply { register(EchoTool()) },
        hooks: AgentHooks = object : AgentHooks {},
    ): Pair<AgentSession, AgentLoop> {
        val loop = AgentLoop(llm, registry, markers)
        val session = AgentSession(
            store = store,
            loop = loop,
            markers = markers,
            hooks = hooks,
            system = { "sys" },
            tools = emptyList(),
            mode = { ToolMode.NATIVE },
            maxContext = { 0 },
            summarizer = { "S[" + it + "]" },
            model = { "sess-model" },
        )
        return session to loop
    }

    private fun textOf(m: AgentMessage): String =
        m.blocks.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }

    private fun row(id: String, role: Role, text: String) =
        AgentMessage(id = id, role = role, blocks = listOf(ContentBlock.Text(text)))

    // ---------- ① planner 成功序列：调度→按规划顺序发言，覆盖贯通+前缀历史+盖章+不入库 ----------

    @Test
    fun `1 planner success runs planned sequence with overrides, prefixes and sender stamps`() = runBlocking {
        val store = InMemoryMessageStore()
        store.append("c1", row("u1", Role.USER, "聊点什么"))
        val llm = ScriptedLlm().apply {
            script(
                """[T]{"turns":[{"speaker":"学生","point":"先提问"},{"speaker":"老师","point":"再解答"}]}""",
                "[T]学生的话",
                "[T]老师的话",
            )
        }
        val (session, loop) = newSession(store, llm)
        val orch = MultiAgentOrchestrator(session, loop)

        val outcome = orch.runRound("c1", store.list("c1"), roles, MultiAgentOrchestrator.Scheduler.PLANNER) { }

        assertEquals(3, llm.calls.size)                       // 1 调度 + 2 角色
        // 调度请求：主持人 system + 紧凑记录（"用户: " 前缀）+ 要点注入产出规划
        val plan = llm.calls[0]
        assertTrue(plan.system.contains("群聊主持人"))
        assertTrue(textOf(plan.history.single()).contains("用户: 聊点什么"))
        // 角色请求：systemOverride=角色 prompt（非构造位 "sys"）；model 覆盖到达请求
        assertEquals("你是学生，负责提问", llm.calls[1].system)
        assertEquals("sess-model", llm.calls[1].model)        // model=null 回落 session 供应商
        assertEquals("你是老师，负责解答", llm.calls[2].system)
        assertEquals("teacher-model", llm.calls[2].model)
        // 要点注入：调度消息（尾部 user 行）带规划要点
        val scheduleNote = textOf(llm.calls[1].history.last())
        assertTrue(scheduleNote.contains("现在轮到「学生」发言"))
        assertTrue(scheduleNote.contains("要点：先提问"))
        // 历史 wire："名字: " 前缀（沿用现状语义）——第二角色请求含用户行与首角色产出
        val teacherHistory = llm.calls[2].history.map { textOf(it) }
        assertTrue(teacherHistory.any { it == "用户: 聊点什么" })
        assertTrue(teacherHistory.any { it == "学生: 学生的话" })
        assertTrue(textOf(llm.calls[2].history.last()).contains("要点：再解答"))
        // 落库：user + 两个角色产出（sender 三列盖章）；调度产出不入库
        val rows = store.list("c1")
        assertEquals(3, rows.size)
        assertTrue("调度 JSON 不得落库", rows.none { textOf(it).contains("turns") })
        assertEquals(SenderInfo("学生", null, "#00FF00"), rows[1].sender)
        assertEquals("学生的话", textOf(rows[1]))
        assertEquals(SenderInfo("老师", "teacher-model", "#FF0000"), rows[2].sender)
        assertEquals("老师的话", textOf(rows[2]))
        // RoundOutcome：产出汇总按规划顺序；planner 未失败
        assertEquals(listOf("学生的话", "老师的话"), outcome.carriedRoles.map { textOf(it) })
        assertFalse(outcome.plannerFailed)
    }

    // ---------- ② planner 解析失败 → ROUND_ROBIN 降级（roles 顺序发言） ----------

    @Test
    fun `2 planner parse failure degrades to round robin in role order`() = runBlocking {
        val store = InMemoryMessageStore()
        store.append("c2", row("u1", Role.USER, "问"))
        val llm = ScriptedLlm().apply { script("[T]我不会规划", "[T]老师答", "[T]学生答") }
        val (session, loop) = newSession(store, llm)
        val orch = MultiAgentOrchestrator(session, loop)

        val outcome = orch.runRound("c2", store.list("c2"), roles, MultiAgentOrchestrator.Scheduler.PLANNER) { }

        assertTrue(outcome.plannerFailed)                     // 解析失败降级
        assertEquals(3, llm.calls.size)                       // 调度 + 2 角色
        // 降级序=roles 声明序（老师、学生），无要点注入（调度提示不带要点段）
        assertFalse(textOf(llm.calls[1].history.last()).contains("要点："))
        assertEquals(listOf("老师答", "学生答"), store.list("c2").drop(1).map { textOf(it) })
        assertEquals(listOf("老师答", "学生答"), outcome.carriedRoles.map { textOf(it) })
    }

    // ---------- ②' planner 请求异常（LLM 流抛错）→ 同样降级 ROUND_ROBIN ----------

    @Test
    fun `3 planner llm exception degrades to round robin`() = runBlocking {
        val store = InMemoryMessageStore()
        store.append("c3", row("u1", Role.USER, "问"))
        val llm = ScriptedLlm().apply { script("[X]调度请求炸了", "[T]老师答", "[T]学生答") }
        val (session, loop) = newSession(store, llm)
        val orch = MultiAgentOrchestrator(session, loop)

        val outcome = orch.runRound("c3", store.list("c3"), roles, MultiAgentOrchestrator.Scheduler.PLANNER) { }

        assertTrue(outcome.plannerFailed)
        assertEquals(3, llm.calls.size)
        assertEquals(listOf("老师答", "学生答"), outcome.carriedRoles.map { textOf(it) })
    }

    // ---------- ③ 每角色预算独立：预算 1/角色（同角色第二次即拒），重置后下一角色可再搜 ----------

    @Test
    fun `4 per-role budget is one search and resets before each role`() = runBlocking {
        val store = InMemoryMessageStore()
        val llm = ScriptedLlm().apply {
            script("[C]a\n[C]b", "[T]老师答", "[C]c", "[T]学生答")
        }
        val registry = ToolRegistry().apply { register(EchoTool()) }
        val (session, loop) = newSession(store, llm, registry)
        val chain = session.acquireChain("c4")               // 先取链：预算门读该链上下文
        registry.installBudgetGate("echo") { chain.context.searchBudgetRemaining > 0 }
        val orch = MultiAgentOrchestrator(session, loop)

        orch.runRound("c4", listOf(row("u1", Role.USER, "问")), roles, MultiAgentOrchestrator.Scheduler.ROUND_ROBIN) { }

        val toolResults = store.list("c4")
            .filter { it.role == Role.TOOL_RESULT }
            .map { it.blocks.single() as ContentBlock.ToolResult }
        assertEquals(3, toolResults.size)
        // 角色 1（老师）：首搜放行（预算 1→0），同角色第二次被门拒
        assertFalse(toolResults[0].isError)
        assertEquals("echo:a", toolResults[0].output)
        assertTrue("同角色第二搜应被预算门拒绝（每角色预算 1）", toolResults[1].isError)
        assertEquals("搜索预算已用尽", toolResults[1].output)
        // 角色 2（学生）：runOnChain 前重置为 1 → 可再搜
        assertFalse("第二角色重置后应可再搜", toolResults[2].isError)
        assertEquals("echo:c", toolResults[2].output)
        assertEquals(listOf("老师答", "学生答"), store.list("c4")
            .filter { it.role == Role.ASSISTANT && it.blocks.any { b -> b is ContentBlock.Text } }
            .map { textOf(it) })
    }

    // ---------- ④ 取消全停：stop() 中断回合，已产出角色消息保留，后续角色不再发起 ----------

    @Test
    fun `5 stop mid-round interrupts round and preserves produced role messages`() = runBlocking {
        val store = InMemoryMessageStore()
        store.append("c5", row("u1", Role.USER, "问"))       // 调用方先落 user 行（VM 职责）
        val llm = ScriptedLlm().apply { script("[T]半截\n[W]400\n[T]尾") }
        val (session, loop) = newSession(store, llm)
        val orch = MultiAgentOrchestrator(session, loop)

        val job = launch {
            orch.runRound("c5", store.list("c5"), roles, MultiAgentOrchestrator.Scheduler.ROUND_ROBIN) { }
        }
        delay(150)                                            // 角色 1（老师）流中（[W]400 挂起）
        session.stop()                                        // 取消半径=整回合
        job.join()

        assertEquals("第二角色不得再发起（取消半径=整回合）", 1, llm.calls.size)
        val rows = store.list("c5")
        // 已产出角色消息保留：取消点前已完成块落库，点后幽灵尾块（"尾"）不补收
        assertEquals(listOf("问", "半截"), rows.map { textOf(it) })
        assertEquals(SenderInfo("老师", "teacher-model", "#FF0000"), rows[1].sender)
        assertFalse(session.events.value.streaming)           // 终态照常收线（既有机制）
    }

    // ---------- ⑤ P4 T2 承接：回合收尾释放链 + 调度窗口哨兵 ----------

    /**
     * 承接②（acquireChain 跨回合复用半径裁定）：回合收尾释放链，下回合 acquire
     * 即新链——第二回合预算/查询集全新（不复用第一回合耗尽的链上下文）。
     */
    @Test
    fun `6 round end releases the chain and next round runs on a fresh one`() = runBlocking {
        val store = InMemoryMessageStore()
        store.append("c6", row("u1", Role.USER, "问"))
        val llm = ScriptedLlm().apply { script("[T]一答", "[T]二答") }
        val (session, loop) = newSession(store, llm)
        val orch = MultiAgentOrchestrator(session, loop)

        val pre = session.acquireChain("c6")   // 第 1 回合复用它（同会话存活链）
        orch.runRound("c6", store.list("c6"), listOf(roles[0]), MultiAgentOrchestrator.Scheduler.ROUND_ROBIN) { }

        assertFalse("回合收尾须释放链（不跨回合复用）", pre.scope.isActive)
        // 第二回合：换新链照常出话；请求侧预算全新（fresh chain context，非复用旧链）
        orch.runRound("c6", store.list("c6"), listOf(roles[0]), MultiAgentOrchestrator.Scheduler.ROUND_ROBIN) { }
        assertEquals(listOf("问", "一答", "二答"), store.list("c6").map { textOf(it) })
        assertTrue("第二回合请求预算全新", llm.budgetSeen[1])
    }

    /**
     * 承接①（调度窗口哨兵接线）：调用方协程在调度请求流中取消 → 链即刻取消、
     * SSE 订阅即刻中断（不跑到自然收完）、角色 run 不发起。
     */
    @Test
    fun `7 caller cancel during planner stream kills the chain via sentinel watch`() = runBlocking {
        val store = InMemoryMessageStore()
        store.append("c7", row("u1", Role.USER, "问"))
        val llm = ScriptedLlm().apply {
            script("[W]500\n[T]{\"turns\":[{\"speaker\":\"老师\",\"point\":\"p\"}]}", "[T]老师答")
        }
        val (session, loop) = newSession(store, llm)
        val orch = MultiAgentOrchestrator(session, loop)

        val job = launch {
            orch.runRound("c7", store.list("c7"), roles, MultiAgentOrchestrator.Scheduler.PLANNER) { }
        }
        delay(150)                    // 调度流中（[W]500 挂起）
        job.cancel()                  // 调用方协程取消（VM agentJob 死亡同型）
        withTimeout(2000) { job.join() }
        delay(700)                    // 若订阅未被取消，脚本会在 ~500ms 后自然收完
        assertEquals("调度请求不得自然收完（哨兵取消订阅）", 0, llm.streamCompletions)
        assertEquals("链取消：角色 run 不发起", 1, llm.calls.size)
        assertEquals(listOf("问"), store.list("c7").map { textOf(it) })
    }

    // ---------- ⑧ P4 T4：每角色 run 终态回调（runOnChain 返回处） ----------

    /**
     * 每角色正常收线即回调（角色名 + 本条产出正文，按发言序）；调度请求不回调。
     * VM 事件桥据此逐角色触发 PetHub/autoRead（P4 T4 恢复频率）。
     */
    @Test
    fun `8 role turn end callback fires per completed run with role name and text`() = runBlocking {
        val store = InMemoryMessageStore()
        store.append("c8", row("u1", Role.USER, "问"))
        val llm = ScriptedLlm().apply { script("[T]一答", "[T]二答") }
        val (session, loop) = newSession(store, llm)
        val fired = mutableListOf<Pair<String, String>>()
        val orch = MultiAgentOrchestrator(session, loop, onRoleTurnEnded = { name, text -> fired += name to text })

        orch.runRound("c8", store.list("c8"), roles, MultiAgentOrchestrator.Scheduler.ROUND_ROBIN) { }

        // 逐角色按序回调，正文与落库行同文；调度请求（无 ROUND_ROBIN）不产生额外回调
        assertEquals(listOf("老师" to "一答", "学生" to "二答"), fired)
    }

    /** 停止中断的角色 run（cancelled）不回调——对齐"取消路径不喊完成/不朗读"。 */
    @Test
    fun `9 cancelled role run does not fire the turn end callback`() = runBlocking {
        val store = InMemoryMessageStore()
        store.append("c9", row("u1", Role.USER, "问"))
        val llm = ScriptedLlm().apply { script("[T]半截\n[W]400\n[T]尾") }
        val (session, loop) = newSession(store, llm)
        val fired = mutableListOf<Pair<String, String>>()
        val orch = MultiAgentOrchestrator(session, loop, onRoleTurnEnded = { name, text -> fired += name to text })

        val job = launch {
            orch.runRound("c9", store.list("c9"), roles, MultiAgentOrchestrator.Scheduler.ROUND_ROBIN) { }
        }
        delay(150)                    // 角色 1（老师）流中（[W]400 挂起）
        session.stop()
        job.join()

        assertTrue("被取消的 run 不得触发终态回调", fired.isEmpty())
    }
}
