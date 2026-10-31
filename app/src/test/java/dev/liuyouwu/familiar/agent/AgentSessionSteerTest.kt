package dev.liuyouwu.familiar.agent

import com.google.gson.JsonObject
import dev.liuyouwu.familiar.core.llm.client.LlmClient
import dev.liuyouwu.familiar.core.llm.client.LlmRequest
import dev.liuyouwu.familiar.core.llm.model.AgentMessage
import dev.liuyouwu.familiar.core.llm.model.ContentBlock
import dev.liuyouwu.familiar.core.llm.model.Role
import dev.liuyouwu.familiar.core.llm.model.StreamEvent
import dev.liuyouwu.familiar.core.llm.model.Usage
import dev.liuyouwu.familiar.core.llm.wire.ToolMode
import dev.liuyouwu.familiar.data.local.entity.ChatRoomParticipantEntity
import dev.liuyouwu.familiar.data.local.entity.ConversationEntity
import dev.liuyouwu.familiar.tools.AgentTool
import dev.liuyouwu.familiar.tools.ToolRegistry
import dev.liuyouwu.familiar.viewmodel.RunMarkerTexts
import dev.liuyouwu.familiar.viewmodel.skillhooks.ClassroomHooks
import dev.liuyouwu.familiar.viewmodel.skillhooks.SkillRepoPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AgentSession 插话层测试（P-steer，spec §3.3，Task 2 会话层）：
 * - steer 四步 TOCTOU（落库 → 活跃/开放/会话匹配判定 → offer → isOpen 复检）：
 *   活跃返回 Steered（观察口=第二次请求历史含插话行——队列对 fake LLM 不可见）、
 *   不活跃/收线后/stop 后/会话错配返回 Deferred 且行已落库（收线兜答数据源）；
 * - stop 后队列废弃不入新 run（executeRun 收尾 close + finally 身份守卫摘指针）；
 * - 残留转投两道闸（spec §3.3）：maxTurns 早退队列非空 → executeRun 收尾
 *   close-before-peek 判残留 → followUpRun 兜答；ClassroomHooks 闸①
 *   （steeringLeftover=true 时 onRunEnd 不发 gap 续跑协程，TS 提取保留在前）；
 * - 注入行不双写（steer 落库一次，loop 注入/持久化不重写）。
 * 构造样板照抄 AgentSessionTest（真 AgentLoop + 脚本 LlmClient + 记账 hooks；
 * AgentLoop 为 final 类不可继承，无 FakeLoop 路线）；ScriptedLlm 独立演化三处：
 * [X] 指令（流中入队插话——残留用例的确定性 offer 点，"收线前在最后一个窗口
 * offer"的任务书意图落在脚本形态上：早退时队列非空由脚本自然产生）。
 * 另含 SteeringQueue close 契约（T1 无覆盖的评审承接：close 后 offer 丢弃、
 * isOpen=false、close-before-peek 残留判定依赖的 peek 可用性）。
 */
class AgentSessionSteerTest {

    /**
     * 注入用 RunMarkers（内核清理 A，Task 4）：AgentLoop/AgentSession 参数必填。
     * 取生产字面（SkillHost 装配同源），字面等价由 RunMarkerTextsSnapshotTest 快照钉死。
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
     * 脚本回放 LlmClient（复制自 AgentSessionTest，独立演化）：按调用次数回放
     * 脚本，每次调用发完整事件骨架（RunStart → 块事件 → TurnEnd → RunEnd；
     * TurnStart 由 AgentLoop 铸造）。脚本 DSL（每行一个指令）：
     * - `[T]正文`：正文块（单 delta 整发）
     * - `[K]思考`：思考块
     * - `[C]query`：一次 echo 工具调用
     * - `[W]ms`：流中暂停（取消/节流/插话窗口的时序用）
     * - `[!]`：本 turn TurnEnd.truncated=true
     * - `[X]文本`：流中调用 [onSteer]（挂起）——本套件的生命线：插话在脚本指
     *   定点确定性入队（session.steer），替代墙钟 offerer（多回合链路下 delay
     *   窗口漂移不可控）
     * - `[S]c1|c2|…`：单正文块按 `|` 切多 delta 连发（delta 间 yield）
     * - `[R]n`：n 个 BlockDelta 连发，每个 delta 后 yield
     */
    private class ScriptedLlm : LlmClient {
        val calls = mutableListOf<LlmRequest>()
        private val scripts = ArrayDeque<String>()

        /** [X] 指令执行体（注入 session.steer；null 时遇 [X] 报错——拼写漂移早暴露） */
        var onSteer: (suspend (String) -> Unit)? = null

        fun script(vararg turns: String) {
            scripts.addAll(turns)
        }

        override fun stream(req: LlmRequest): Flow<StreamEvent> = flow {
            calls += req
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
                    line.startsWith("[X]") -> {
                        val steer = onSteer ?: error("[X] 指令无执行体 onSteer")
                        steer(line.removePrefix("[X]"))
                    }
                    line.startsWith("[S]") -> {
                        val id = "s${blockSeq++}"
                        emit(StreamEvent.BlockStart(id, "text"))
                        line.removePrefix("[S]").split('|').forEach { chunk ->
                            emit(StreamEvent.BlockDelta(id, chunk))
                            yield()
                        }
                        emit(StreamEvent.BlockEnd(id))
                    }
                    line.startsWith("[R]") -> {
                        val n = line.removePrefix("[R]").trim().toInt()
                        val id = "r${blockSeq++}"
                        emit(StreamEvent.BlockStart(id, "text"))
                        repeat(n) {
                            emit(StreamEvent.BlockDelta(id, "x"))
                            yield()
                        }
                        emit(StreamEvent.BlockEnd(id))
                    }
                    else -> error("未知脚本行：$line")
                }
            }
            emit(StreamEvent.TurnEnd(toolCalls.toList(), truncated))
            emit(StreamEvent.RunEnd(Usage(promptTokens = 10, completionTokens = 5)))
        }
    }

    /** 回显工具（复制自 AgentSessionTest；未装预算门，不与预算语义纠缠） */
    private class EchoTool : AgentTool {
        override val name = "echo"
        override val description = "回显"
        override val parametersJson = """{"type":"object","properties":{"query":{"type":"string"}}}"""

        override suspend fun execute(args: JsonObject, onProgress: (String) -> Unit): String {
            val query = args.get("query")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
            onProgress("回显中：$query")
            return "echo:$query"
        }
    }

    /** 记账 hooks：onRunEnd 捕获 RunResult 与 chain 引用（secondRunStarted 据此记账） */
    private class RecordingHooks : AgentHooks {
        val chains = mutableListOf<RunChain>()
        val results = mutableListOf<RunResult>()

        override fun onRunEnd(result: RunResult, chain: RunChain) {
            results += result
            chains += chain
        }
    }

    /** repo 记账假件（ContextBuilderTest.noRepo 形态 + tutorState 记账，闸① 提取保留断言用） */
    private class RecordingRepo : SkillRepoPort {
        val tutorStates = mutableListOf<Pair<Long, String>>()
        override suspend fun updateSegments(messageId: Long, segmentsJson: String?) {}
        override suspend fun updateTutorState(conversationId: Long, stateJson: String) {
            tutorStates += conversationId to stateJson
        }
        override suspend fun getParticipants(conversationId: Long): List<ChatRoomParticipantEntity> = emptyList()
        override suspend fun getConversation(conversationId: Long): ConversationEntity? = null
        override suspend fun updateTitle(conversationId: Long, title: String) {}
    }

    /**
     * 缝位交错器（评审 F1/F2 回归的确定性构造）：InMemoryMessageStore 包装，
     * list 与 **assistant 行** append 可注入延迟——run 启动缝（F2：置位 vs store
     * 读）与收尾缝（F1：旧 run 收尾 vs 新 run 置位）的交错顺序由延迟强制，不靠
     * 墙钟竞运气。user 行 append 不延迟（send 起点不被拖慢）。
     */
    private class SeamStore(
        private val listDelayMs: Long = 0,
        private val assistantAppendDelayMs: Long = 0,
    ) : MessageStore {
        private val inner = InMemoryMessageStore()

        override suspend fun append(conversationId: String, message: AgentMessage): List<Long> {
            if (assistantAppendDelayMs > 0 && message.role == Role.ASSISTANT) delay(assistantAppendDelayMs)
            return inner.append(conversationId, message)
        }

        override suspend fun list(conversationId: String): List<AgentMessage> {
            if (listDelayMs > 0) delay(listDelayMs)
            return inner.list(conversationId)
        }

        override suspend fun deleteTurn(conversationId: String, turnId: String): Int =
            inner.deleteTurn(conversationId, turnId)

        override suspend fun deleteById(conversationId: String, id: String): Boolean =
            inner.deleteById(conversationId, id)

        override suspend fun lastAssistantTurnId(conversationId: String): String? =
            inner.lastAssistantTurnId(conversationId)

        override suspend fun replaceRange(
            conversationId: String,
            fromId: String,
            replacement: List<AgentMessage>,
        ) = inner.replaceRange(conversationId, fromId, replacement)
    }

    private fun newSession(
        store: MessageStore,
        llm: LlmClient,
        hooks: AgentHooks = object : AgentHooks {},
    ): AgentSession = AgentSession(
        store = store,
        loop = AgentLoop(llm, ToolRegistry().apply { register(EchoTool()) }, markers),
        markers = markers,
        hooks = hooks,
        system = { "sys" },
        tools = emptyList(),
        mode = { ToolMode.NATIVE },
        maxContext = { 0 },
        summarizer = { "S[" + it + "]" },
    )

    private fun textOf(m: AgentMessage): String =
        m.blocks.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }

    /** 等流式终态落定（followUp run 的收线发布；仿 AgentSessionTest ⑲ 的轮询） */
    private suspend fun awaitIdle(session: AgentSession, timeoutMs: Long = 5000) {
        var waited = 0
        while (session.events.value.streaming && waited < timeoutMs) {
            delay(10); waited += 10
        }
        assertFalse("超时仍 streaming", session.events.value.streaming)
    }

    // ---------- ① steer 活跃：入队返回 Steered，第二请求历史含插话行 ----------

    @Test
    fun `steer活跃入队返回Steered`() = runBlocking {
        val store = InMemoryMessageStore()
        val llm = ScriptedLlm().apply { script("[T]一\n[W]300\n[T]收", "[T]接住插话再答") }
        val session = newSession(store, llm)

        val job = launch { session.send("s1", "问题") }
        delay(120)                       // 流中（[W]300 挂起窗口内）
        val verdict = session.steer("s1", "插话：补充一句")
        job.join()

        assertTrue("活跃 run 上的插话应入队", verdict === SteerOutcome.Steered)
        // 队列对 fake 不可见，观察口 = 第二次请求历史含插话行（回合顶注入）
        assertEquals(2, llm.calls.size)
        val injected = llm.calls[1].history.last()
        assertEquals(Role.USER, injected.role)
        assertEquals("插话：补充一句", textOf(injected))
        // 落库即时（写序即时、UI 即现）：steer 返回时行已在 store
        assertTrue(store.list("s1").any { it.role == Role.USER && textOf(it) == "插话：补充一句" })
    }

    // ---------- ② steer 不活跃：Deferred 且行已落库 ----------

    @Test
    fun `steer不活跃返回Deferred且行已落库`() = runBlocking {
        val store = InMemoryMessageStore()
        val llm = ScriptedLlm()
        val session = newSession(store, llm)

        val verdict = session.steer("s2", "无人接的话")

        assertTrue(verdict === SteerOutcome.Deferred)
        assertEquals(0, llm.calls.size)                  // 不开 run
        assertEquals(1, store.list("s2").size)           // 行已落库（pendingFollowUp 兜答数据源）
        assertEquals("无人接的话", textOf(store.list("s2").single()))
    }

    // ---------- ③ run 收线后 steer：Deferred（activeSteering=null 路径） ----------

    @Test
    fun `run收线后steer返回Deferred且行已落库`() = runBlocking {
        val store = InMemoryMessageStore()
        val llm = ScriptedLlm().apply { script("[T]答") }
        val session = newSession(store, llm)

        session.send("s3", "问")
        val verdict = session.steer("s3", "迟来的一句")

        // 确定性等价用例（评审 P1-6）：offer→复检间无挂起点，竞态窗不可确定性构造
        // ——窗口闭合由实现的 close-before-peek 次序保证，本用例钉 activeSteering
        // ==null 路径（finally 身份守卫已摘指针）
        assertTrue(verdict === SteerOutcome.Deferred)
        assertEquals(1, llm.calls.size)
        assertEquals(3, store.list("s3").size)   // 问 + 答 + 迟来的一句（行已落库，兜答数据源）
        assertEquals("迟来的一句", textOf(store.list("s3").last()))
    }

    // ---------- ④ stop 后队列废弃不入新 run ----------

    @Test
    fun `stop后队列废弃不入新run`() = runBlocking {
        val store = InMemoryMessageStore()
        val llm = ScriptedLlm().apply { script("[T]一\n[W]400\n[T]收", "[T]答二") }
        val session = newSession(store, llm)

        val job1 = launch { session.send("s4", "问一") }
        delay(120)                       // run1 流中
        assertTrue(session.steer("s4", "插话一") === SteerOutcome.Steered)   // 已入 run1 队列
        session.stop()                   // 链取消：队列随收尾 close 废弃（cancelled 路径不转投）
        job1.join()

        // stop 后（无活跃 run）steer：Deferred（finally 已摘 activeSteering）
        assertTrue(session.steer("s4", "插话二") === SteerOutcome.Deferred)

        session.continueRun("s4")        // 新 run 拿全新队列
        // 新 run 首请求：插话一仅出现一次（store 行）；队列残留不得二次注入
        val first = llm.calls[1].history
        assertEquals("队列废弃失败会把插话一注入两次", 1, first.count { textOf(it) == "插话一" })
        assertEquals("答二", textOf(store.list("s4").last()))
    }

    // ---------- ⑤ 会话错配：Deferred 不注入 ----------

    @Test
    fun `会话错配返回Deferred不注入`() = runBlocking {
        val store = InMemoryMessageStore()
        val llm = ScriptedLlm().apply { script("[T]答A\n[W]300\n[T]尾") }
        val session = newSession(store, llm)

        val job = launch { session.send("s5a", "问A") }
        delay(120)                       // A 的 run 活跃（[W]300 窗口内）
        val verdict = session.steer("s5b", "错配插话")
        job.join()

        assertTrue(verdict === SteerOutcome.Deferred)
        // A 的队列空：收束不被顶住（单请求收线），历史无错配行
        assertEquals(1, llm.calls.size)
        assertTrue(llm.calls.single().history.none { textOf(it) == "错配插话" })
        // 行落在 B 会话（兜答数据源）
        assertEquals("错配插话", textOf(store.list("s5b").single()))
    }

    // ---------- ⑥ 残留转投：maxTurns 早退队列非空 → followUpRun 兜答 ----------

    @Test
    fun `残留转投followUpRun`() = runBlocking {
        val store = InMemoryMessageStore()
        lateinit var session: AgentSession
        val llm = ScriptedLlm()
        llm.onSteer = { text -> session.steer("s6", text) }
        // executeRun 固定 maxTurns=8（AgentLoop 默认；任务书"maxTurns 让 loop 早退"
        // 按现场无此杠杆校正为默认值撑满）。turn0-6 带工具调用（无插话时无工具 turn
        // 自然收束到不了 turn7）+ CONTINUE hooks 推进；turn7 脚本内 [X] 确定性入队
        // 一条插话——自然收束被顶住 → turnIndex 越界退出（队列非空 →
        // steeringLeftover=true）→ 收尾残留转投 followUpRun，第 9 条脚本由续跑消费
        llm.script(
            "[C]q0", "[C]q1", "[C]q2", "[C]q3", "[C]q4", "[C]q5", "[C]q6",
            "[T]答七\n[X]遗留一句", "[T]兜答",
        )
        val hooks = RecordingHooks()
        session = newSession(store, llm, hooks = hooks)

        session.send("s6", "问")
        awaitIdle(session)               // 续跑 run 收线统一发布终态（followUpPending 判据）

        // 记账：secondRunStarted——hooks 第二次 onRunEnd 即续跑已发生
        assertEquals("残留应转投 followUpRun（两道闸闸②）", 2, hooks.results.size)
        assertTrue("first run 应带 steeringLeftover", hooks.results[0].steeringLeftover)
        assertFalse(hooks.results[1].steeringLeftover)
        assertEquals("兜答", hooks.results[1].finalText)
        assertEquals(9, llm.calls.size)   // run1 八回合 + 续跑一回合
        // 遗留插话进入续跑请求（store 行随历史装配——模型最终见到它）
        assertTrue(llm.calls[8].history.any { textOf(it) == "遗留一句" })
    }

    // ---------- ⑦ 两道闸 hooks 让位（闸①：steeringLeftover 跳过续讲） ----------

    @Test
    fun `两道闸_hooks让位`() = runBlocking {
        val repo = RecordingRepo()
        val hostScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val hooks = ClassroomHooks(1L, repo, initialTutorState = null, gapMs = 1, scope = hostScope)
        hooks.followUp = {}
        // 拆出剧本（turns != null 且 phase != await_user）+ TS 状态标记：
        // 无闸时该输入必发 gap 续跑协程（ContextBuilderTest 同款输入）
        val finalText = "[老师] 我们继续讲下一小节。" +
            "<!--TS:{\"sections\":[{\"t\":\"第一节 加法\",\"st\":\"done\"}],\"current\":1,\"phase\":\"teaching\"}-->"

        // 闸①：steeringLeftover=true → onRunEnd 不挂任何链上协程（本 run 收束由残留转投负责）
        val chainGated = RunChain("s7a", CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        hooks.onRunEnd(
            RunResult(finalText, emptyList(), Usage(), 1, false, false, steeringLeftover = true),
            chainGated,
        )
        assertEquals(
            "闸①失效会挂 gap 协程、与残留转投形成同链双 executeRun",
            0, chainGated.scope.coroutineContext[Job]?.children?.count { it.isActive },
        )
        // TS 提取保留在前：闸只挡续讲，状态列照常落库（下一 run 注入源不丢）
        assertEquals(1, repo.tutorStates.size)
        assertTrue(repo.tutorStates.single().second.contains("teaching"))

        // 对照组：同形输入 leftover=false → gap 续跑协程照常挂（闸只让位于转投，不误伤常规续讲）
        val chainFree = RunChain("s7b", CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        hooks.onRunEnd(
            RunResult(finalText, emptyList(), Usage(), 1, false, false),
            chainFree,
        )
        assertEquals(1, chainFree.scope.coroutineContext[Job]?.children?.count { it.isActive })

        chainGated.cancel(); chainFree.cancel(); hostScope.cancel()
    }

    // ---------- ⑧ 注入行不双写 ----------

    @Test
    fun `注入行不双写`() = runBlocking {
        val store = InMemoryMessageStore()
        // 两个脚本 turn 均无产出块（produced 空 → run 不落任何行）：steer 行是唯一新增
        // ——若注入路径双写（loop 落库/持久化重写），终值会是 +2
        val llm = ScriptedLlm().apply { script("[W]300", "[W]50") }
        val session = newSession(store, llm)

        val job = launch { session.send("s8", "问") }
        delay(120)
        assertTrue(session.steer("s8", "插话一次") === SteerOutcome.Steered)
        job.join()

        assertEquals(2, store.list("s8").size)   // 初始 1（问）+ 1（仅 steer 的 user 行）
        assertEquals(listOf("问", "插话一次"), store.list("s8").map { textOf(it) })
        assertEquals(2, llm.calls.size)          // 收束被顶住的第二回合确实发生（注入进上下文）
    }

    // ---------- ⑨ persistUser：只落 user 行不开 run ----------

    @Test
    fun `persistUser只落库不开run`() = runBlocking {
        val store = InMemoryMessageStore()
        val llm = ScriptedLlm()
        val session = newSession(store, llm)

        session.persistUser("s9", "排队追问")

        assertEquals(0, llm.calls.size)                  // 不开 run（续跑由控制器收线后驱动）
        val row = store.list("s9").single()
        assertEquals(Role.USER, row.role)
        assertEquals("排队追问", textOf(row))
    }

    // ---------- ⑩ SteeringQueue close 契约（T1 补覆盖，评审承接②） ----------

    @Test
    fun `queue_close后offer丢弃isOpen置否peek照常`() {
        val q = SteeringQueue()
        q.offer(AgentMessage(id = "u1", role = Role.USER, blocks = listOf(ContentBlock.Text("早到的插话"))))
        q.close()
        assertFalse("close 后 isOpen=false", q.isOpen())
        // close 后 offer 静默丢弃：drain 只含 close 前的一条
        q.offer(AgentMessage(id = "u2", role = Role.USER, blocks = listOf(ContentBlock.Text("迟到的插话"))))
        // close-before-peek 契约：close 后 peek 照常可用（executeRun 残留判定依赖）
        assertTrue("close 后 peek 必须照常报告存量", q.peek())
        val drained = q.drain()
        assertEquals(1, drained.size)
        assertEquals("早到的插话", textOf(drained.single()))
        assertFalse(q.peek())
    }

    // ---------- ⑪ 评审 F1：打断接力下旧 run 收尾只关自己的队列，不关新 run 的 ----------

    @Test
    fun `打断接力旧收尾不关新run队列`() = runBlocking {
        // assistant 行 append 注入 300ms 延迟：旧 run（取消后正常返回）的收尾 persist
        // 被拖到新 run 置位 activeSteering（continueRun 无 user 行、装配零延迟）之后
        // ——收尾 close 执行时指针已指向新 run 队列。修复前的指针形态
        // （activeSteering?.close()）会把新队列关掉：新 run 存活期 steer 恒 Deferred。
        val store = SeamStore(assistantAppendDelayMs = 300)
        val llm = ScriptedLlm().apply { script("[T]一\n[W]400\n[T]收", "[W]800\n[T]答二", "[T]接住") }
        val hooks = RecordingHooks()
        val session = newSession(store, llm, hooks = hooks)

        val job1 = launch { session.send("s11", "问一") }
        delay(120)                       // run1 流中（[W]400）
        val job2 = launch { session.continueRun("s11") }   // 打断接力：openChain 取消旧链
        job1.join()                      // 旧 run 收线完成（收尾 close 已执行，此刻指针=新队列）
        assertTrue("旧 run 应为取消语义", hooks.results[0].cancelled)
        // 新 run 的 [W]800 窗口内 steer：新队列必须还活着
        assertTrue(
            "旧收尾不得关掉新 run 队列（指针形态会让此断言翻 Deferred）",
            session.steer("s11", "插话给新run") === SteerOutcome.Steered,
        )
        job2.join()

        // 新队列活且被消费：收束被顶住 → 第二回合接住插话（run1 1 次 + run2 2 次）
        assertEquals(3, llm.calls.size)
        assertEquals("接住", textOf(store.list("s11").last()))
    }

    // ---------- ⑫ 评审 F2：run 启动缝 steer 落入不双注入 ----------

    @Test
    fun `run启动缝steer落入不双注入`() = runBlocking {
        // list 注入 300ms 延迟：把"装配 input（store 读）"的启动缝拉开——缝内
        // steer 落入时 activeSteering 尚未置位，必须 Deferred（修复前置位早于装配，
        // 会 Steered 且该行既进 turn0 历史又被回合顶 drain 再注一次，模型见重复提问）。
        val store = SeamStore(listDelayMs = 300)
        val llm = ScriptedLlm().apply { script("[T]答") }
        val session = newSession(store, llm)

        val job = launch { session.send("s12", "问") }
        delay(120)                       // 缝内（list 延迟中）
        val verdict = session.steer("s12", "缝内插话")
        job.join()

        assertTrue("启动缝内的 steer 应 Deferred（行只随 store 进首请求一次）", verdict === SteerOutcome.Deferred)
        assertEquals(1, llm.calls.size)
        assertEquals("双注入会出两份", 1, llm.calls.single().history.count { textOf(it) == "缝内插话" })
    }
}
