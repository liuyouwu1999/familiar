package dev.liuyouwu.familiar.agent

import com.google.gson.JsonObject
import dev.liuyouwu.familiar.core.llm.client.LlmClient
import dev.liuyouwu.familiar.core.llm.client.LlmRequest
import dev.liuyouwu.familiar.core.llm.model.AgentMessage
import dev.liuyouwu.familiar.core.llm.model.Attachment
import dev.liuyouwu.familiar.core.llm.model.ContentBlock
import dev.liuyouwu.familiar.core.llm.model.ReplyRef
import dev.liuyouwu.familiar.core.llm.model.Role
import dev.liuyouwu.familiar.core.llm.model.SenderInfo
import dev.liuyouwu.familiar.core.llm.model.StreamEvent
import dev.liuyouwu.familiar.core.llm.model.Usage
import dev.liuyouwu.familiar.core.llm.wire.ToolMode
import dev.liuyouwu.familiar.tools.AgentTool
import dev.liuyouwu.familiar.tools.ToolRegistry
import dev.liuyouwu.familiar.viewmodel.RunMarkerTexts
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AgentSession 行为测试（spec §5.2a/§5.4）：轮链生命周期（五入口各开新链）、
 * send 落库形态、regenerate 按 turnId 删组与 NULL 降级、editAndResend 截断重发、
 * compact 单条摘要（不经 LLM）、stop 取消、90ms 节流 + 收线补发、NO_ANSWER 可见
 * 说明、截断标记落库/剥除、RunResult 组装与 titleTrigger。全部代码断言。
 * P4 T1 扩展：acquireChain（同会话同链/闭合换新）、runOnChain（system/model
 * 覆盖贯通 + sender 盖章 + 既有流式/持久化/收线机制全通）。
 * P4 T7 扩展：streamingTextFilter 显示层过滤（发布点对原始累计派生、落库全量、无过滤器不受影响）。
 */
class AgentSessionTest {

    /**
     * 注入用 RunMarkers（内核清理 A，Task 4）：AgentLoop/AgentSession 参数必填
     * （包内无中文缺省值）。取生产字面（SkillHost 装配同源），字面等价由
     * RunMarkerTextsSnapshotTest 快照钉死；截断/无回答断言改读本 fixture
     * （注入贯通随改写）。
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
     * 脚本回放 LlmClient：按调用次数回放脚本，每次调用发完整事件骨架
     * （RunStart → 块事件 → TurnEnd → RunEnd；TurnStart 由 AgentLoop 铸造）。
     * 脚本 DSL（每行一个指令）：
     * - `[T]正文`：正文块（单 delta 整发）
     * - `[K]思考`：思考块
     * - `[C]query`：一次 echo 工具调用
     * - `[W]ms`：流中暂停（取消/节流时序用）
     * - `[!]`：本 turn TurnEnd.truncated=true
     * - `[S]c1|c2|…`：单正文块按 `|` 切多 delta 连发（delta 间 yield）——标记跨
     *   delta 边界的累计过滤契约（P4 T7）用
     * - `[R]n`：n 个 BlockDelta 连发，每个 delta 后 yield——墙钟 ≈0（真节流下
     *   仍只开 1 窗）但 collector 在 delta 间被调度（观察窗口非零：无节流时
     *   ~200 个不同 streamingText 值必被看见，测试有判别力）
     */
    private class ScriptedLlm : LlmClient {
        val calls = mutableListOf<LlmRequest>()
        private val scripts = ArrayDeque<String>()

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
                        // yield（非 delay）：burst 内给 collector 真实调度点——否则
                        // runBlocking 单线程下 collector 全程不被调度，观察值全靠
                        // StateFlow 合流兜底，测试对节流门有无无判别力
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

    /** 回显工具（未装预算门，不与预算语义纠缠）；onProgress 上报进行中状态（流式载体接线断言用） */
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

    /** 记账 hooks：onRunEnd 捕获 RunResult 与 chain 引用（新链对比用） */
    private class RecordingHooks : AgentHooks {
        val chains = mutableListOf<RunChain>()
        val results = mutableListOf<RunResult>()

        override fun onRunEnd(result: RunResult, chain: RunChain) {
            results += result
            chains += chain
        }
    }

    private fun newSession(
        store: MessageStore,
        llm: LlmClient,
        hooks: AgentHooks = object : AgentHooks {},
        maxContext: () -> Int = { 0 },
        titleTrigger: (RunResult) -> Unit = {},
        model: () -> String = { "" },
        maxTokens: (() -> Int)? = null,
        searchAllowed: () -> Boolean = { true },
        sampling: () -> SamplingParams? = { null },
        persistTransform: ((List<AgentMessage>) -> List<AgentMessage>)? = null,
        onPersisted: ((List<AgentMessage>, List<Long>) -> Unit)? = null,
        streamingTextFilter: ((String) -> String)? = null,
    ): AgentSession = AgentSession(
        store = store,
        loop = AgentLoop(llm, ToolRegistry().apply { register(EchoTool()) }, markers),
        markers = markers,
        hooks = hooks,
        system = { "sys" },
        tools = emptyList(),
        mode = { ToolMode.NATIVE },
        maxContext = maxContext,
        summarizer = { "S[" + it + "]" },
        titleTrigger = titleTrigger,
        model = model,
        maxTokens = maxTokens,
        searchAllowed = searchAllowed,
        sampling = sampling,
        persistTransform = persistTransform,
        onPersisted = onPersisted,
        streamingTextFilter = streamingTextFilter,
    )

    private fun textOf(m: AgentMessage): String =
        m.blocks.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }

    private fun row(id: String, role: Role, text: String, turnId: String? = null) =
        AgentMessage(id = id, role = role, blocks = listOf(ContentBlock.Text(text)), turnId = turnId)

    // ---------- ① send 落库形态：user + assistant(ToolCall) + TOOL_RESULT + assistant(Text) ----------

    @Test
    fun `1 send persists user assistant and tool result rows in order`() = runBlocking {
        val store = InMemoryMessageStore()
        val llm = ScriptedLlm().apply { script("[C]hi", "[T]答案好了") }
        val session = newSession(store, llm)

        session.send(
            "c1", "问题",
            attachments = listOf(Attachment.File("a.txt", "内容")),
            replyTo = ReplyRef(null, "前情", "user"),
        )

        val rows = store.list("c1")
        assertEquals(4, rows.size)
        assertEquals(Role.USER, rows[0].role)
        assertEquals("问题", textOf(rows[0]))
        assertEquals(listOf(Attachment.File("a.txt", "内容")), rows[0].attachments)
        assertEquals(ReplyRef(null, "前情", "user"), rows[0].replyTo)
        assertEquals(Role.ASSISTANT, rows[1].role)
        assertTrue(rows[1].blocks.single() is ContentBlock.ToolCall)
        assertEquals(Role.TOOL_RESULT, rows[2].role)
        val tr = rows[2].blocks.single() as ContentBlock.ToolResult
        assertFalse(tr.isError)
        assertEquals("echo:hi", tr.output)
        assertEquals(rows[1].turnId, rows[2].turnId)   // 同 turn 共享 turnId
        assertEquals("答案好了", textOf(rows[3]))
        // 首次请求历史含刚落库的 user 行
        assertEquals(1, llm.calls.first().history.size)
        assertEquals("问题", textOf(llm.calls.first().history.single()))
    }

    // ---------- ② 历史裁剪接线（Task 11 移交项 1）：Session 经 ContextBuilder 预裁 ----------

    @Test
    fun `2 send trims request history via ContextBuilder with maxContext`() = runBlocking {
        val store = InMemoryMessageStore()
        // 预置 3 条长旧消息（每条 301 token）：maxContext=400 → reserve 被
        // reserveForOutput 钳到 256，budget 落 MIN_BUDGET=256 下限——旧消息单条即超，只有最新 user 装得下
        repeat(3) { i ->
            store.append("c2", AgentMessage(id = "old$i", role = Role.ASSISTANT,
                blocks = listOf(ContentBlock.Text("旧".repeat(300) + i))))
        }
        val llm = ScriptedLlm().apply { script("[T]答") }
        val session = newSession(store, llm, maxContext = { 400 })

        session.send("c2", "问")

        val history = llm.calls.single().history
        assertEquals(1, history.size)
        assertEquals("问", textOf(history.single()))
    }

    // ---------- ③ continueRun 开新链（预算重置，hooks 记录 chain 引用对比） ----------

    @Test
    fun `3 continueRun opens new chain with reset budget`() = runBlocking {
        val store = InMemoryMessageStore()
        val llm = ScriptedLlm().apply { script("[T]一答", "[T]续答") }
        val hooks = RecordingHooks()
        val session = newSession(store, llm, hooks = hooks)

        session.send("c3", "问")
        session.continueRun("c3")

        assertEquals(2, hooks.chains.size)
        val (chain1, chain2) = hooks.chains
        assertTrue(chain1 !== chain2)                                 // 新链：引用不同
        assertEquals(2, chain2.context.searchBudgetRemaining)         // 预算重置为默认 2
        assertEquals("续答", textOf(store.list("c3").last()))
        // 续跑历史 = 原历史（user + 首答 assistant）
        assertEquals(2, llm.calls[1].history.size)
    }

    // ---------- ④ regenerate 按 turnId 删整组再重跑 ----------

    @Test
    fun `4 regenerate deletes whole turnId group then reruns`() = runBlocking {
        val store = InMemoryMessageStore()
        store.append("c4", row("u1", Role.USER, "问"))
        store.append("c4", AgentMessage(id = "a1", role = Role.ASSISTANT,
            blocks = listOf(ContentBlock.ToolCall("b1", "echo", "{}")), turnId = "run-x:0"))
        store.append("c4", AgentMessage(id = "r1", role = Role.TOOL_RESULT,
            blocks = listOf(ContentBlock.ToolResult("b1", "echo", "out", isError = false)),
            turnId = "run-x:0"))
        val llm = ScriptedLlm().apply { script("[T]新答") }
        val session = newSession(store, llm)

        session.regenerateLastTurn("c4")

        val rows = store.list("c4")
        assertEquals(2, rows.size)                        // 旧组三行 → user + 新 assistant
        assertEquals("u1", rows[0].id)
        assertEquals("新答", textOf(rows[1]))
        assertTrue(rows.none { it.turnId == "run-x:0" })  // 整组（含 TOOL_RESULT 行）删除
        assertEquals(1, llm.calls.single().history.size)  // 重跑历史已无被删行
    }

    // ---------- ⑤ 旧数据（turnId=NULL）降级 deleteById 删最后一行 assistant ----------

    @Test
    fun `5 regenerate downgrades to deleteById for legacy null turnId`() = runBlocking {
        val store = InMemoryMessageStore()
        store.append("c5", row("u1", Role.USER, "问"))
        store.append("c5", row("a9", Role.ASSISTANT, "旧答", turnId = null))
        val llm = ScriptedLlm().apply { script("[T]重答") }
        val session = newSession(store, llm)

        session.regenerateLastTurn("c5")

        val rows = store.list("c5")
        assertEquals(2, rows.size)
        assertTrue(rows.none { it.id == "a9" })
        assertEquals("重答", textOf(rows[1]))
    }

    // ---------- ⑥ editAndResend：从被编辑行截断并重发 ----------

    @Test
    fun `6 editAndResend truncates from edited message and resends`() = runBlocking {
        val store = InMemoryMessageStore()
        store.append("c6", row("u1", Role.USER, "旧问题"))
        store.append("c6", row("a1", Role.ASSISTANT, "旧答"))
        store.append("c6", row("u2", Role.USER, "被编辑"))
        store.append("c6", row("a2", Role.ASSISTANT, "旧答2"))
        val llm = ScriptedLlm().apply { script("[T]新答") }
        val session = newSession(store, llm)

        session.editAndResend("c6", "u2", "新问题")

        val rows = store.list("c6")
        assertEquals(4, rows.size)                        // u1、a1 保留 + 新 user + 新 assistant
        assertEquals(Role.USER, rows[2].role)
        assertEquals("新问题", textOf(rows[2]))
        assertTrue(rows.none { it.id == "u2" || it.id == "a2" })
        val history = llm.calls.single().history
        assertEquals(3, history.size)   // u1、a1 保留 + 重发的新 user 行（随请求发给模型）
        assertEquals("新问题", textOf(history.last()))
    }

    // ---------- ⑦ stop 取消当前链：SSE 订阅即刻取消 + 部分块落库 + 收线补发完整终态 ----------

    @Test
    fun `7 stop cancels current chain stream immediately and flushes final state`() = runBlocking {
        val store = InMemoryMessageStore()
        val llm = ScriptedLlm().apply { script("[T]开头\n[W]300\n[T]尾巴") }
        val hooks = RecordingHooks()
        val session = newSession(store, llm, hooks = hooks)

        val job = launch { session.send("c7", "问") }
        delay(120)                       // 流中（[W]300 挂起）
        session.stop()
        job.join()

        val chain = hooks.chains.single()
        assertFalse(chain.scope.isActive)                    // stop() 取消当前链
        assertTrue(hooks.results.single().cancelled)
        assertFalse(session.events.value.streaming)          // 收线补发：非流式终态
        // 取消贯通（P3 终审，spec §5.1 原文语义）：订阅即刻取消，RunEnd 未达 →
        // 无用量；取消点前已完成块照常落库，点后幽灵尾块（"尾巴"）不补收
        assertEquals(Usage(), session.events.value.lastUsage)
        val persisted = textOf(store.list("c7").last())
        assertEquals("开头", persisted)
        assertFalse(persisted.contains("尾巴"))
    }

    // ---------- ⑧ 90ms 节流：200 delta 发射 ≤ 5 且收线补发完整（P1"节流可观测"闭合） ----------

    @Test
    fun `8 throttle coalesces 200 deltas into few emissions with complete final state`() = runBlocking {
        val store = InMemoryMessageStore()
        val llm = ScriptedLlm().apply { script("[R]200") }
        val session = newSession(store, llm)

        val seen = mutableListOf<SessionState>()
        val collector = launch { session.events.collect { seen += it } }
        delay(20)                       // 让 collector 先拿到初值
        session.send("c8", "问")
        delay(20)                       // 给 collector 处理终态值的时间（再断言/取消）
        collector.cancel()

        assertTrue("200 delta 后发射 ${seen.size} 次", seen.size <= 5)
        // 节流真验证（P1 台账"节流可观测"）：streamingText 逐增量单调变化——
        // 同值合流救不了它，必须节流本身合流。订阅侧观察到的**不同 streamingText
        // 取值**（StateFlow 对慢收集者按最新值合流，只会更少不会更多）≤ 5。
        val distinctTextValues = seen.map { it.streamingText }.distinct()
        assertTrue("streamingText 不同取值 ${distinctTextValues.size} 个：$distinctTextValues",
            distinctTextValues.size <= 5)
        val finalState = session.events.value
        assertFalse(finalState.streaming)
        assertEquals(Usage(promptTokens = 10, completionTokens = 5), finalState.lastUsage)
        // 收线补发终值完整：增量载体收线快照 = 全部 200 delta（非最后一窗的截断值）
        assertEquals("x".repeat(200), finalState.streamingText)
        assertEquals("x".repeat(200), textOf(store.list("c8").last()))  // 收线内容完整
    }

    // ---------- ⑨ NO_ANSWER：整 run 无 Text 有 Thinking → 追加可见说明 ----------

    @Test
    fun `9 thinking-only run appends visible no-answer note`() = runBlocking {
        val store = InMemoryMessageStore()
        val llm = ScriptedLlm().apply { script("[K]推理中") }
        val session = newSession(store, llm)

        session.send("c9", "问")

        val rows = store.list("c9")
        assertEquals(2, rows.size)
        val assistant = rows[1]
        assertEquals(Role.ASSISTANT, assistant.role)
        assertTrue(assistant.blocks.any { it is ContentBlock.Thinking })
        // 说明追加在同一条 assistant 消息尾部（共享 turnId → regenerate 整组删）
        assertEquals(markers.noAnswerMarker, textOf(assistant))
    }

    // ---------- ⑩ 截断标记落库 + continueRun 构建历史前剥除 ----------

    @Test
    fun `10 truncated run persists marker and continueRun strips it`() = runBlocking {
        val store = InMemoryMessageStore()
        val llm = ScriptedLlm().apply { script("[T]半截\n[!]", "[T]续完") }
        val session = newSession(store, llm)

        session.send("c10", "问")
        val marked = textOf(store.list("c10").last())
        assertTrue(marked.endsWith(markers.truncationMarker))
        assertEquals("半截", marked.removeSuffix(markers.truncationMarker))

        session.continueRun("c10")
        val history = llm.calls[1].history
        assertEquals("半截", textOf(history.last { it.role == Role.ASSISTANT }))  // 标记已剥
    }

    // ---------- ⑪ compact：摘要单条替换、不经 AgentLoop ----------

    @Test
    fun `11 compact replaces history with single summary row without llm`() = runBlocking {
        val store = InMemoryMessageStore()
        store.append("c11", row("u1", Role.USER, "问一"))
        store.append("c11", row("a1", Role.ASSISTANT, "答一"))
        val llm = ScriptedLlm()
        val session = newSession(store, llm)

        session.compact("c11")

        assertTrue(llm.calls.isEmpty())                  // 不经 AgentLoop
        val rows = store.list("c11")
        assertEquals(1, rows.size)
        assertEquals(Role.ASSISTANT, rows[0].role)
        val text = textOf(rows[0])
        assertTrue(text.startsWith("[对话摘要]\n"))
        assertTrue(text.contains("问一") && text.contains("答一"))   // 摘要输入覆盖全部历史
    }

    // ---------- ⑫ regenerate / compact 也各开新链 ----------

    @Test
    fun `12 regenerate and compact each open a new chain`() = runBlocking {
        val store = InMemoryMessageStore()
        val llm = ScriptedLlm().apply { script("[T]一", "[T]二") }
        val hooks = RecordingHooks()
        val session = newSession(store, llm, hooks = hooks)

        session.send("c12", "问")
        session.regenerateLastTurn("c12")
        assertEquals(2, hooks.chains.size)
        assertTrue(hooks.chains[0] !== hooks.chains[1])
        assertFalse(hooks.chains[0].scope.isActive)      // 旧链被新链取代（取消闭合）
        assertTrue(hooks.chains[1].scope.isActive)       // regenerate 新链自然闭合

        session.compact("c12")
        assertFalse(hooks.chains[1].scope.isActive)      // compact 开新链：旧链取消
        assertEquals(2, llm.calls.size)                  // compact 未走 LLM
    }

    // ---------- ⑬ RunResult 组装 + titleTrigger ----------

    @Test
    fun `13 titleTrigger receives assembled run result`() = runBlocking {
        val store = InMemoryMessageStore()
        val llm = ScriptedLlm().apply { script("[C]q", "[T]终稿") }
        val received = mutableListOf<RunResult>()
        val session = newSession(store, llm, titleTrigger = { received += it })

        session.send("c13", "问")

        assertEquals(1, received.size)
        val r = received.single()
        assertEquals("终稿", r.finalText)
        assertFalse(r.cancelled)
        assertFalse(r.truncated)
        assertEquals(Usage(promptTokens = 20, completionTokens = 10), r.usage)
        assertEquals(2, r.turnCount)                     // 工具 turn + 作答 turn
        assertTrue(r.blocks.single() is ContentBlock.Text)
    }

    // ---------- ⑭ LLM 流抛异常：终态无条件补发、链取消、已落库内容保留 ----------

    @Test
    fun `14 llm exception still flushes terminal state and cancels chain`() = runBlocking {
        val store = InMemoryMessageStore()
        val llm = object : LlmClient {
            override fun stream(req: LlmRequest) = flow {
                emit(StreamEvent.RunStart("boom"))
                throw RuntimeException("网络炸了")
            }
        }
        val hooks = RecordingHooks()
        val session = newSession(store, llm, hooks = hooks)

        session.send("c14", "问")   // 异常收线为 cancelled 语义，不外抛

        val chain = hooks.chains.single()
        assertFalse(chain.scope.isActive)              // 硬失败链作废取消
        val result = hooks.results.single()
        assertTrue(result.cancelled)                   // cancelled 语义 RunResult
        assertEquals("", result.finalText)
        assertEquals(0, result.turnCount)
        assertFalse(session.events.value.streaming)    // 终态无条件补发（评审修复轮 1）
        val rows = store.list("c14")
        assertEquals(1, rows.size)                     // 已落库内容保留：仅 user 行，无垃圾产出
        assertEquals("问", textOf(rows.single()))
    }

    // ---------- ⑮ 请求装配四件套：session 三供应商贯通 + regenerate override 优先 ----------

    @Test
    fun `15 session model maxTokens searchAllowed flow into every request`() = runBlocking {
        val store = InMemoryMessageStore()
        val llm = ScriptedLlm().apply { script("[T]答一", "[T]答二") }
        val session = newSession(
            store, llm,
            model = { "sess-model" },
            maxTokens = { 512 },
            searchAllowed = { false },
        )

        session.send("c15", "问")
        session.regenerateLastTurn("c15", modelOverride = "over-model")

        assertEquals(2, llm.calls.size)
        // send：session 供应商值；regenerate：run 级 override 优先
        assertEquals(listOf("sess-model", "over-model"), llm.calls.map { it.model })
        // maxTokens/searchAllowed 供应商贯通进每次请求（override 只抬 model）
        assertEquals(listOf(512, 512), llm.calls.map { it.maxTokens })
        assertEquals(listOf(false, false), llm.calls.map { it.searchAllowed })
        // sampling 未配置（默认构造）：temperature 恒 null
        assertEquals(listOf(null, null), llm.calls.map { it.temperature })
        // sampling 供应商配置（B2-L1）：构造位求值经 openChain 播种 → 请求携带
        val seededLlm = ScriptedLlm().apply { script("[T]答三") }
        newSession(store, seededLlm, sampling = { SamplingParams(0.8, null, null) })
            .send("c15-seeded", "问")
        assertEquals(0.8, seededLlm.calls.single().temperature)
        assertNull(seededLlm.calls.single().frequencyPenalty)
        assertNull(seededLlm.calls.single().presencePenalty)
    }

    // ---------- ⑯ append 返回行 id：与消息一一对齐、进程内单调 ----------

    @Test
    fun `16 append returns one row id per message in insertion order`() = runBlocking {
        val store = InMemoryMessageStore()
        assertEquals(listOf(1L), store.append("c16", row("u1", Role.USER, "问")))
        assertEquals(listOf(2L), store.append("c16", row("a1", Role.ASSISTANT, "答")))
        // 跨会话计数器不重置：行 id 仅用于钩子对位（非稳定键）
        assertEquals(listOf(3L), store.append("other", row("u2", Role.USER, "问二")))
    }

    // ---------- ⑰ regenerate 延迟删除：取消半截（有 assistant 产出落库）→ 删旧轮 ----------

    @Test
    fun `17 regenerate deferred deletion removes old turn when cancelled partial persisted`() = runBlocking {
        val store = InMemoryMessageStore()
        store.append("c17", row("u1", Role.USER, "问"))
        store.append("c17", AgentMessage(id = "a1", role = Role.ASSISTANT,
            blocks = listOf(ContentBlock.ToolCall("b1", "echo", "{}")), turnId = "run-old:0"))
        store.append("c17", AgentMessage(id = "r1", role = Role.TOOL_RESULT,
            blocks = listOf(ContentBlock.ToolResult("b1", "echo", "旧结果", isError = false)),
            turnId = "run-old:0"))
        val llm = ScriptedLlm().apply { script("[T]半截\n[W]300\n[T]续") }
        val session = newSession(store, llm)

        val job = launch { session.regenerateLastTurn("c17") }
        delay(120)                       // 流中（[W]300 挂起）
        session.stop()
        job.join()

        val rows = store.list("c17")
        // 成功判据：新回复有 assistant 产出落库（含取消半截）→ 旧轮整组删除
        assertTrue("旧轮整组删除", rows.none { it.turnId == "run-old:0" })
        assertEquals(2, rows.size)                        // user + 取消半截新答
        assertEquals("u1", rows[0].id)
        // 取消贯通（P3 终审）：取消点前已完成块落库，点后幽灵尾块（"续"）不补收
        val partial = textOf(rows[1])
        assertEquals("半截", partial)
        assertFalse(partial.contains("续"))
        // 重跑历史以"删后历史"装配（无旧轮组）
        assertEquals(1, llm.calls.single().history.size)
    }

    // ---------- ⑱ regenerate 延迟删除：硬失败（空产出）→ 旧消息保留不动 ----------

    @Test
    fun `18 regenerate deferred deletion keeps old turn on hard failure`() = runBlocking {
        val store = InMemoryMessageStore()
        store.append("c18", row("u1", Role.USER, "问"))
        store.append("c18", row("a1", Role.ASSISTANT, "旧答", turnId = "run-old:0"))
        val llm = object : LlmClient {
            override fun stream(req: LlmRequest) = flow {
                emit(StreamEvent.RunStart("boom"))
                throw RuntimeException("网络炸了")
            }
        }
        val session = newSession(store, llm)

        session.regenerateLastTurn("c18")   // 硬失败收线为 cancelled 语义，不外抛

        val rows = store.list("c18")
        // 空产出落库 → 旧消息保留不动：u1 + a1 原样
        assertEquals(2, rows.size)
        assertEquals("u1", rows[0].id)
        assertEquals("a1", rows[1].id)
        assertEquals("旧答", textOf(rows[1]))
    }

    // ---------- ⑲ followUpRun 同链续跑：预算共享 + 终态推迟到最后一个 run ----------

    @Test
    fun `19 followUpRun reruns on same chain and defers terminal to last run`() = runBlocking {
        val store = InMemoryMessageStore()
        val llm = ScriptedLlm().apply { script("[T]答一\n[W]50", "[T]答二\n[W]150") }
        lateinit var session: AgentSession
        val hooks = object : AgentHooks {
            val chains = mutableListOf<RunChain>()
            override fun onRunEnd(result: RunResult, chain: RunChain) {
                chains += chain
                if (result.finalText == "答一" && !result.cancelled) {
                    chain.scope.launch { session.followUpRun(chain.conversationId) }
                }
            }
        }
        session = newSession(store, llm, hooks = hooks)

        session.send("c19", "问")
        // 本轮不发布 streaming=false 终态：run1 收线后（followUp 已挂链上）流仍处流式态
        assertTrue("中途终态不得吞掉续讲链", session.events.value.streaming)

        // 等续讲 run 收线统一发布终态
        var waited = 0
        while (session.events.value.streaming && waited < 2000) { delay(10); waited += 10 }
        assertFalse(session.events.value.streaming)

        assertEquals(2, llm.calls.size)
        assertTrue("同链续跑：chain 引用相同", hooks.chains[0] === hooks.chains[1])   // 预算/查询集/深度共享
        assertEquals("答二", textOf(store.list("c19").last()))
        // 续跑历史含首答（以落库后的历史续跑）
        assertEquals(listOf("问", "答一"), llm.calls[1].history.map { textOf(it) })
    }

    // ---------- ⑳ 链取消全停（含段间 gap 的后续 run）+ 终态补发 ----------

    @Test
    fun `20 chain cancel during segment gap stops follow-up run and flushes terminal`() = runBlocking {
        val store = InMemoryMessageStore()
        val llm = ScriptedLlm().apply { script("[T]答一") }
        lateinit var session: AgentSession
        val chains = mutableListOf<RunChain>()
        val hooks = object : AgentHooks {
            override fun onRunEnd(result: RunResult, chain: RunChain) {
                chains += chain
                // T6 课堂形态：段间 delay 在 hook 内（挂 chain.scope）
                chain.scope.launch {
                    delay(600)
                    session.followUpRun(chain.conversationId)
                }
            }
        }
        session = newSession(store, llm, hooks = hooks)

        val job = launch { session.send("c20", "问") }
        job.join()
        delay(150)                       // 处于段间 gap（600ms delay 中）
        assertTrue("gap 期间流式态保持（续讲链未收线）", session.events.value.streaming)

        session.stop()                   // VM 停止按钮：取消整链——gap 后续 run 全停
        delay(200)

        val chain = chains.single()
        assertFalse(chain.scope.isActive)               // 整链取消（含 gap 协程）
        assertFalse(session.events.value.streaming)     // 终态补发：无 streaming 卡 true
        assertEquals(1, llm.calls.size)                 // gap 后的续讲 run 未执行
        assertEquals("答一", textOf(store.list("c20").last()))
    }

    // ---------- ㉑ persistTransform：落库前消息列表变换（课堂拆条形态） ----------

    @Test
    fun `21 persistTransform splits run output before persisting`() = runBlocking {
        val store = InMemoryMessageStore()
        val llm = ScriptedLlm().apply { script("[K]思考\n[T]第一段\n[T]第二段") }
        val session = newSession(store, llm, persistTransform = { produced ->
            // 课堂拆条形态：终稿 1 条 → 2 条台词消息，首条带 Thinking
            produced.flatMap { m ->
                if (m.role != Role.ASSISTANT) return@flatMap listOf(m)
                val thinking = m.blocks.filterIsInstance<ContentBlock.Thinking>()
                val texts = m.blocks.filterIsInstance<ContentBlock.Text>()
                texts.mapIndexed { i, t ->
                    m.copy(id = "${m.id}#$i",
                        blocks = if (i == 0) thinking + t else listOf(t))
                }
            }
        })

        session.send("c21", "问")

        val rows = store.list("c21")
        assertEquals(3, rows.size)                       // user + 拆出的 2 条
        assertEquals(Role.USER, rows[0].role)
        assertEquals("第一段", textOf(rows[1]))
        assertTrue(rows[1].blocks.any { it is ContentBlock.Thinking })   // 首条带 Thinking
        assertEquals("第二段", textOf(rows[2]))
        assertTrue(rows[2].blocks.none { it is ContentBlock.Thinking })
    }

    // ---------- ㉒ onPersisted：落库后回调拿到与消息对齐的行 id ----------

    @Test
    fun `22 onPersisted receives persisted messages and aligned row ids`() = runBlocking {
        val store = InMemoryMessageStore()
        val llm = ScriptedLlm().apply { script("[C]q", "[T]终稿") }
        val received = mutableListOf<Pair<List<AgentMessage>, List<Long>>>()
        val session = newSession(store, llm, onPersisted = { messages, rowIds -> received += messages to rowIds })

        session.send("c22", "问")

        // 单 run 三条产出（assistant/TOOL_RESULT/assistant）一次回调；user 行不走钩子路径
        val (messages, rowIds) = received.single()
        assertEquals(3, messages.size)
        assertEquals(listOf(Role.ASSISTANT, Role.TOOL_RESULT, Role.ASSISTANT), messages.map { it.role })
        // 行 id 与消息一一对齐：user 行占 1 号 → 产出从 2 号起顺序编号
        assertEquals(listOf(2L, 3L, 4L), rowIds)
    }

    // ---------- ⑳③ 违约钩子：onRunEnd 内取消链上挂起的 followUp → 死子协程不得卡死终态 ----------

    @Test
    fun `23 cancelled child on chain scope does not defer terminal publish`() = runBlocking {
        val store = InMemoryMessageStore()
        val llm = ScriptedLlm().apply { script("[T]答") }
        lateinit var session: AgentSession
        val hooks = object : AgentHooks {
            override fun onRunEnd(result: RunResult, chain: RunChain) {
                // 违约形态（审查 Important 1）：launch followUp 后又取消整链——
                // 已取消子协程短暂仍挂附链 scope（Cancelling→detach 异步），但不可能再产生 run
                chain.scope.launch {
                    delay(600)
                    session.followUpRun(chain.conversationId)
                }
                chain.cancel()
            }
        }
        session = newSession(store, llm, hooks = hooks)

        session.send("c23", "问")

        // 死子协程不可能再产生 run：不得推迟终态——streaming 不卡 true
        assertFalse("死子协程不得推迟终态发布", session.events.value.streaming)
        assertEquals(1, llm.calls.size)   // followUp 未执行（gap 协程被取消）
        assertEquals("答", textOf(store.list("c23").last()))
    }

    // ---------- ㉔ 流式载体：text/reasoning 按 BlockDelta 块类型累计，run 起点重置 ----------

    @Test
    fun `24 streamingText and streamingReasoning accumulate by block type and reset per run`() = runBlocking {
        val store = InMemoryMessageStore()
        val llm = ScriptedLlm().apply { script("[K]思考\n[T]答一", "[K]再思考") }
        val session = newSession(store, llm)

        session.send("c24", "问")
        val afterFirst = session.events.value
        // 收线快照完整：正文与思考各自累计齐（终值不经节流截断）
        assertEquals("答一", afterFirst.streamingText)
        assertEquals("思考", afterFirst.streamingReasoning)
        assertFalse(afterFirst.streaming)

        session.continueRun("c24")
        val afterSecond = session.events.value
        // run 起点重置（RunStart 口径）：第二 run 无正文块 → streamingText 空串
        // （保留上一 run 残值就是串台）；reasoning 累计的是本 run 的思考
        assertEquals("", afterSecond.streamingText)
        assertEquals("再思考", afterSecond.streamingReasoning)
    }

    // ---------- ㉕ 工具进度回调：onProgress 文本经 loop 贯通到 streamingToolStatus ----------

    @Test
    fun `25 tool progress text surfaces in streamingToolStatus and resets next run`() = runBlocking {
        val store = InMemoryMessageStore()
        val llm = ScriptedLlm().apply { script("[C]hi\n[T]答", "[T]续答") }
        val session = newSession(store, llm)

        session.send("c25", "问")
        // EchoTool.onProgress("回显中：hi") → registry.execute → AgentLoop 贯通 → Session 状态
        assertEquals("回显中：hi", session.events.value.streamingToolStatus)

        session.continueRun("c25")
        // 下一 run 无工具调用 → 载体重置为 null（不残留上一 run 的工具状态）
        assertNull(session.events.value.streamingToolStatus)
    }

    // ---------- ㉖ lastError：硬失败置位、下一 run 起点清除 ----------

    @Test
    fun `26 lastError set on hard failure and cleared on next run start`() = runBlocking {
        val store = InMemoryMessageStore()
        var failedOnce = false
        val llm = object : LlmClient {
            override fun stream(req: LlmRequest) = flow {
                if (!failedOnce) {
                    failedOnce = true
                    emit(StreamEvent.RunStart("boom"))
                    throw RuntimeException("网络炸了")
                }
                emit(StreamEvent.TurnEnd(emptyList(), false))
                emit(StreamEvent.RunEnd(Usage(promptTokens = 10, completionTokens = 5)))
            }
        }
        val session = newSession(store, llm)

        session.send("c26", "问一")   // 硬失败：异常收线为 cancelled 语义（不外抛）
        assertEquals("网络炸了", session.events.value.lastError)
        assertFalse(session.events.value.streaming)

        session.send("c26", "问二")   // 下一 run 起点清除（成功 run 无错误）
        assertNull(session.events.value.lastError)
        assertFalse(session.events.value.streaming)
    }

    // ---------- ㉗ contextBreakdown：收线时经 computeBreakdown 同源计算（trim 口径手算） ----------

    @Test
    fun `27 contextBreakdown computed at run completion with trim-aligned billing`() = runBlocking {
        val store = InMemoryMessageStore()
        val llm = ScriptedLlm().apply { script("[C]q", "[T]终稿") }
        val session = newSession(store, llm, maxContext = { 400 })

        session.send(
            "c27", "问",
            attachments = listOf(Attachment.File("a.txt", "内容")),   // 附件不进请求 → 同源口径不计费
        )

        // 手算（estimateTokens：CJK 1:1，其他 4:1；计费文本口径同 build/trim 的 messageText）：
        // user "问"=1；assistant ToolCall("echo\n{\"query\":\"q\"}") 18 字符 → (18+3)/4=5；
        // TOOL_RESULT "echo:q"=6 字符 → (6+3)/4=2；assistant "终稿"=2 → messages=10
        // system "sys" 3 字符 → 1；reserve=reserveForOutput(400)=400/4=100 钳 256（budget 同落 256 下限）
        val bd = session.events.value.contextBreakdown
        assertTrue("收线时更新 contextBreakdown", bd != null)
        assertEquals(10, bd!!.messages)
        assertEquals(0, bd.attachments)          // 附件不上 wire、trim 不计费 → 同源恒 0
        assertEquals(1, bd.systemPrompt)         // system 计费（同 trim 的 budget 扣减项）
        assertEquals(256, bd.reserve)            // reserveForOutput 同源公式（钳位后）
        assertEquals(400, bd.maxContext)
        assertEquals(11, bd.used)                // messages+attachments+systemPrompt
        assertEquals(133, bd.free)               // 400-11-256
    }

    // ---------- ㉘ 插话打断（P3 终审 Important-1/2）：流即刻取消、部分块落库、被取代链终态让位 ----------

    @Test
    fun `28 interrupt mid-stream cancels old run immediately and new chain owns terminal`() = runBlocking {
        val store = InMemoryMessageStore()
        // run1：取消点前 1 块 + 取消点后幽灵尾块；run2：先挂起再作答（保持流式中）
        val llm = ScriptedLlm().apply { script("[T]一\n[W]300\n[T]尾巴", "[W]300\n[T]新答") }
        val session = newSession(store, llm)

        val old = launch { session.send("c28", "问一") }
        delay(120)                       // 旧 run 流中（[W]300 挂起）
        val new = launch { session.send("c28", "问二") }   // 插话 = 打断 + 立即新 send
        old.join()                       // 旧 run 收线完成（取消 → 部分块落库 → finally 让位终态）

        // 被取代链不得打回终态：新 run 流式中，streaming 保持 true（无 false→true 闪烁）
        assertTrue("被取代链的收线不得发布终态", session.events.value.streaming)
        new.join()
        assertFalse(session.events.value.streaming)          // 终态由新链收线发布

        assertEquals(2, llm.calls.size)
        // 旧 run 部分块即刻落库：幽灵尾块（"尾巴"）永不落库——软停吞鬼影根除
        assertTrue("幽灵尾块不得落库", store.list("c28").none { textOf(it).contains("尾巴") })
        // 行序确定：user问一 → user问二（send 先落 user 再开新链取消）→ 旧 run 部分块 → 新答
        assertEquals(listOf("问一", "问二", "一", "新答"), store.list("c28").map { textOf(it) })
    }

    // ---------- ㉙ 调用方协程取消（P3 复审加固）：链即刻取消，SSE 订阅不拖延到流自然收完 ----------

    @Test
    fun `29 caller job cancellation cancels chain immediately without waiting for stream end`() = runBlocking {
        val store = InMemoryMessageStore()
        val llm = ScriptedLlm().apply { script("[W]2000\n[T]答") }   // 慢流：自然收完需 2s
        val session = newSession(store, llm)

        val job = launch { session.send("c29", "问") }
        delay(150)                        // 流中（[W]2000 挂起，collect 在链作用域）
        val t0 = System.currentTimeMillis()
        job.cancelAndJoin()               // 调用方协程取消（VM agentJob onCleared 等价）
        val elapsed = System.currentTimeMillis() - t0

        // 取消必须即刻生效（接线到链取消），不等流自然收完（2s）
        assertTrue("取消应在流自然收完（2s）前即刻生效，实际 ${elapsed}ms", elapsed < 1000)
        // 链已取消：后续 followUpRun 不再发起 run（无第二次 LLM 调用）
        session.followUpRun("c29")
        delay(200)
        assertEquals(1, llm.calls.size)
        assertFalse(session.events.value.streaming)
    }

    // ---------- ㉚ acquireChain（P4 T1）：同会话同链、登记 currentChain、闭合后换新 ----------

    @Test
    fun `30 acquireChain returns same active chain per conversation and stop closes it`() {
        val store = InMemoryMessageStore()
        val llm = ScriptedLlm()
        val session = newSession(store, llm)

        val c1 = session.acquireChain("c30")
        val c2 = session.acquireChain("c30")
        assertEquals("c30", c1.conversationId)
        assertTrue("重复 acquire 同会话返回同链", c1 === c2)

        session.stop()                       // stop 半径=登记的 currentChain（覆盖整回合）
        assertFalse(c1.scope.isActive)

        val c3 = session.acquireChain("c30")   // 已闭合（取消）的链不复活：开新链
        assertTrue(c3 !== c1)
        assertTrue(c3.scope.isActive)
    }

    // ---------- ㉛ runOnChain（P4 T1）：system/model 覆盖 + sender 盖章 + 既有机制全通 ----------

    @Test
    fun `31 runOnChain overrides system and model, stamps sender, keeps existing machinery`() = runBlocking {
        val store = InMemoryMessageStore()
        store.append("c31", row("u1", Role.USER, "问"))
        val llm = ScriptedLlm().apply { script("[T]角色答") }
        val session = newSession(store, llm, model = { "sess-model" })
        val chain = session.acquireChain("c31")

        val seen = mutableListOf<StreamEvent>()
        val outcome = session.runOnChain(
            chain, store.list("c31"),
            systemOverride = "角色系统提示",
            modelOverride = "role-model",
            sender = SenderInfo("老师", "role-model", "#FF0000"),
        ) { ev -> seen += ev }

        val req = llm.calls.single()
        assertEquals("角色系统提示", req.system)          // systemOverride 替换构造位 system()
        assertEquals("role-model", req.model)             // modelOverride 贯通 LlmRequest
        assertEquals("问", textOf(req.history.single()))  // input 直供请求历史
        // sender 盖章落库（MessageRowMapper sender 三列的数据源）
        val rows = store.list("c31")
        assertEquals(SenderInfo("老师", "role-model", "#FF0000"), rows[1].sender)
        assertEquals("角色答", textOf(rows[1]))
        // 返回 LoopOutcome（Orchestrator 的驱动通道契约）
        assertEquals("角色答", textOf(outcome.produced.single()))
        assertFalse(outcome.cancelled)
        // 流式发布/终态收线走既有机制：调用方 emit 收到事件 + SessionState 终态完整
        assertTrue(seen.isNotEmpty())
        assertFalse(session.events.value.streaming)
        assertEquals("角色答", session.events.value.streamingText)
    }

    // ---------- ㉜ streamingSender（P4 T4）：runOnChain 盖章位进流式状态；四类型 run 恒 null ----------

    @Test
    fun `32 streamingSender carries runOnChain sender in streaming state and resets to null on plain runs`() = runBlocking {
        val store = InMemoryMessageStore()
        store.append("c32", row("u1", Role.USER, "问"))
        val llm = ScriptedLlm().apply { script("[W]150\n[T]角色答", "[T]普通答") }
        val session = newSession(store, llm)
        val chain = session.acquireChain("c32")

        var midStreamingSender: SenderInfo? = null
        session.runOnChain(
            chain, store.list("c32"),
            sender = SenderInfo("老师", null, "#FF0000"),
        ) { _ ->
            if (midStreamingSender == null) midStreamingSender = session.events.value.streamingSender
        }
        // 流式期：角色 run 的 sender 盖章随流式状态发布（UI 角色徽章数据源）
        assertEquals(SenderInfo("老师", null, "#FF0000"), midStreamingSender)
        // 终态保留本 run 的 sender（收线快照契约，与 streamingText 终值同口径）
        assertEquals(SenderInfo("老师", null, "#FF0000"), session.events.value.streamingSender)

        // 四类型路径（send）：streamingSender 起点重置为 null——UI 据此区分角色流式与普通流式
        session.send("c32", "再问")
        assertNull(session.events.value.streamingSender)
    }

    // ---------- ㉝ streamingTextFilter（P4 T7）：发布点对原始累计派生、落库全量、无过滤器不受影响 ----------

    /**
     * 显示层过滤契约（P4 T7，课堂 ghost 剥离）：过滤器在 streamingText 发布点对
     * **原始累计文本**整体套用——扣留形态过滤器（substringBefore = 真实课堂过滤的
     * 形状：自标记起点扣留后续文本）下，作用于"过滤后载体 + delta"会得出不同终值
     * （"秘密尾巴"：被扣留的标记体从过滤器输入丢失、后续 delta 泄漏），对原始
     * 累计套用得"秘密"——本断言钉死发布点派生口径（也解释了载体为何必须保持
     * 原始累计）。落库路径（produced → persist）读循环原始产出，不过滤器——
     * 全量文本照落。
     */
    @Test
    fun `33 streamingTextFilter applies on accumulated carrier and persists raw text`() = runBlocking {
        val store = InMemoryMessageStore()
        // 标记跨 delta 切分：三个 delta 各含一段，"鬼"标记横跨 delta 边界
        val llm = ScriptedLlm().apply { script("[S]秘密|鬼|尾巴") }
        val session = newSession(store, llm, streamingTextFilter = { it.substringBefore("鬼") })

        session.send("c33", "问")

        // 流式载体（收线终值，与节流中间态同源累计）：自标记起扣留——按 delta 套用则是"秘密尾巴"
        assertEquals("秘密", session.events.value.streamingText)
        // 落库全量：显示层过滤不触及持久化与收线解析输入
        assertEquals("秘密鬼尾巴", textOf(store.list("c33").last()))
    }

    @Test
    fun `34 session without streamingTextFilter keeps raw carrier text`() = runBlocking {
        val store = InMemoryMessageStore()
        val llm = ScriptedLlm().apply { script("[S]秘密|鬼|尾巴") }
        val session = newSession(store, llm)   // 无过滤器（四类型/既有会话形态）

        session.send("c34", "问")

        assertEquals("秘密鬼尾巴", session.events.value.streamingText)
        assertEquals("秘密鬼尾巴", textOf(store.list("c34").last()))
    }
}
