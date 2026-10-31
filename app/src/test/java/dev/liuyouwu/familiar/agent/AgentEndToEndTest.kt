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
import dev.liuyouwu.familiar.core.llm.wire.ToolSpec
import dev.liuyouwu.familiar.search.SearchEngine
import dev.liuyouwu.familiar.search.SearchHit
import dev.liuyouwu.familiar.search.SearchResult
import dev.liuyouwu.familiar.tools.AgentTool
import dev.liuyouwu.familiar.tools.ToolRegistry
import dev.liuyouwu.familiar.viewmodel.RunMarkerTexts
import dev.liuyouwu.familiar.tools.WebSearchTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Agent 内核端到端集成测试（spec §11 场景 1-7）：ScriptedLlm + 假 SearchEngine +
 * InMemoryMessageStore + AgentSession/AgentLoop/ToolRegistry/WebSearchTool 全链路，
 * 只测组装后的整体行为，不重复单元边界断言。
 *
 * 本测试扮演两个装配角色：
 * - **预算装配方**（spec §4.3"装配即此"）：`registry.installBudgetGate("web_search")
 *   { chain.context.searchBudgetRemaining > 0 }`——链引用经 hooks.onRunEnd 回填
 *   （AgentSession 在 send 内部开链，装配时点拿不到引用）；run 1 进行中尚无引用
 *   = 新链预算恒 2>0 放行等价，预算耗尽只可能发生在 run 1 结束后的同链后续 run 上，
 *   那时引用已回填、读的就是当前链。
 * - **续讲钩子**（P3 classroom autoContinue 的最小替身，场景 4/5）：onRunEnd 内
 *   **同链**直接驱动 AgentLoop 发起后续 run（depth+1≤2、取消护栏），产出照
 *   AgentSession 语义落库并重放 onRunEnd——"链预算共享/链上多 run"语义由此打通。
 *   已知边界（Task 14 P3 注）：AgentSession 终态发布在 onRunEnd 之后，可能覆盖
 *   同链后续 run 的 streaming=true——本测试只断言 store 内容与 run 计数，不断言
 *   中间 streaming 标志。
 */
class AgentEndToEndTest {

    // ---------- 测试基建 ----------

    /**
     * 注入用 RunMarkers（内核清理 A，Task 4）：AgentLoop 第三参必填（包内无中文
     * 缺省值）。取生产字面（SkillHost 装配同源），字面等价由 RunMarkerTextsSnapshotTest
     * 快照钉死；取消断言改读本 fixture 的 toolCancelled（注入贯通随改写）。
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
     * 脚本回放 LlmClient（AgentSessionTest / AgentLoopTest 同款 DSL 合并改造）：
     * 每次调用回放一条脚本，发完整事件骨架（RunStart → 块事件 → TurnEnd → RunEnd；
     * TurnStart 由 AgentLoop 铸造）。标记生效条件对照 LlmClient 硬契约（spec §4.3）：
     * 请求带 [toolName] 工具且 budgetLeft() 为 true 才解析为 ToolCall；不生效 →
     * 标记行整行剥除（不解析、不报错、不产生 ToolCall）。DSL（每行一个指令）：
     * - `[T]正文`：正文块（可多行，内嵌 `[搜索: …]` 标记行按整行锚判定）
     * - `[K]思考`：思考块
     * - `[C]query`：一次 [toolName] 工具调用
     * - 裸 `[搜索: query]` 标记行（等价 [C]）
     * - `[W]ms`：流中暂停（取消时序用）
     * - `[!]`：本 turn TurnEnd.truncated=true
     */
    private class ScriptedLlm(private val toolName: String) : LlmClient {
        val calls = CopyOnWriteArrayList<LlmRequest>()
        val budgetSeen = CopyOnWriteArrayList<Boolean>()
        private val scripts = ArrayDeque<String>()
        private val lock = Any()

        fun script(vararg turns: String) = synchronized(lock) { scripts.addAll(turns) }

        override fun stream(req: LlmRequest): Flow<StreamEvent> = flow {
            calls += req
            budgetSeen += req.budgetLeft()
            val s = synchronized(lock) { scripts.removeFirstOrNull() }
                ?: error("第 ${calls.size} 次 LLM 调用无脚本")
            emit(StreamEvent.RunStart("s-${calls.size}"))
            val effective = req.tools.any { it.name == toolName } && req.budgetLeft()
            var blockSeq = 0
            var truncated = false
            val toolCalls = mutableListOf<ContentBlock.ToolCall>()
            val text = StringBuilder()

            suspend fun flushText() {
                if (text.isNotEmpty()) {
                    val id = "b${blockSeq++}"
                    emit(StreamEvent.BlockStart(id, "text"))
                    emit(StreamEvent.BlockDelta(id, text.toString()))
                    emit(StreamEvent.BlockEnd(id))
                    text.setLength(0)
                }
            }

            suspend fun emitCall(query: String) {
                flushText()
                val id = "b${blockSeq++}"
                val args = """{"query":"$query"}"""
                emit(StreamEvent.BlockStart(id, "toolCall"))
                emit(StreamEvent.ToolCallDelta(id, toolName, args))
                emit(StreamEvent.BlockEnd(id))
                toolCalls += ContentBlock.ToolCall(id, toolName, args)
            }

            for (line in s.lines().map { it.trim() }) {
                val marker = MARKER_RE.matchEntire(line)
                when {
                    line.isEmpty() -> {}
                    line.startsWith("[T]") -> for (body in line.removePrefix("[T]").split('\n')) {
                        val m = MARKER_RE.matchEntire(body.trim())
                        when {
                            m != null && effective -> emitCall(m.groupValues[1])
                            m != null -> {}   // 不生效：标记行剥除
                            else -> {
                                if (text.isNotEmpty()) text.append('\n')
                                text.append(body)
                            }
                        }
                    }
                    line.startsWith("[K]") -> {
                        flushText()
                        val id = "b${blockSeq++}"
                        emit(StreamEvent.BlockStart(id, "thinking"))
                        emit(StreamEvent.BlockDelta(id, line.removePrefix("[K]")))
                        emit(StreamEvent.BlockEnd(id))
                    }
                    line.startsWith("[C]") -> if (effective) emitCall(line.removePrefix("[C]").trim())
                    marker != null && effective -> emitCall(marker.groupValues[1])
                    marker != null -> {}   // 不生效（预算尽/tools 置空）：标记行剥除不解析
                    line.startsWith("[W]") -> delay(line.removePrefix("[W]").trim().toLong())
                    line == "[!]" -> truncated = true
                    else -> error("未知脚本行：$line")
                }
            }
            flushText()
            emit(StreamEvent.TurnEnd(toolCalls.toList(), truncated))
            emit(StreamEvent.RunEnd(Usage(promptTokens = 10, completionTokens = 5)))
        }

        companion object {
            val MARKER_RE = Regex("""\[搜索:\s*(.+?)]""")
        }
    }

    /** 假搜索引擎：记录查询顺序（顺序执行断言用）、可注入执行延迟（取消时序用） */
    private class FakeEngine(private val delayMs: Long = 0) : SearchEngine {
        val queries = CopyOnWriteArrayList<String>()

        override suspend fun search(query: String): SearchResult {
            queries += query
            if (delayMs > 0) delay(delayMs)
            return SearchResult(
                hits = listOf(
                    SearchHit(
                        title = "关于${query}的头条",
                        url = "https://example.com/$query",
                        snippet = "关于${query}的答案片段",
                        domain = "example.com",
                    )
                ),
                diagnostics = "命中 1 条",
            )
        }
    }

    /** 回显工具：场景 6 用（未装预算门，多轮 maxTurns 不与预算语义纠缠） */
    private class EchoTool : AgentTool {
        override val name = "echo"
        override val description = "回显"
        override val parametersJson = """{"type":"object","properties":{"query":{"type":"string"}}}"""

        override suspend fun execute(args: JsonObject, onProgress: (String) -> Unit): String =
            "echo:" + (args.get("query")?.takeIf { it.isJsonPrimitive }?.asString ?: "")
    }

    /**
     * 预算门的链引用持有者（装配缝）：门求值时读"当前链"的预算——AgentSession 在
     * send 内部开链，装配时点拿不到引用，经 hooks.onRunEnd 回填。run 1 进行中
     * chain==null = 新链预算恒 2>0 放行；后续 run 与 run 1 同链，读到同一预算。
     */
    private class BudgetGate {
        @Volatile var chain: RunChain? = null
        fun allows(): Boolean = chain?.context?.searchBudgetRemaining?.let { it > 0 } ?: true
    }

    /** 记账 hooks：onRunEnd 捕获 RunResult 与 chain 引用；持有预算门时回填链引用 */
    private open class RecordingHooks(private val gate: BudgetGate? = null) : AgentHooks {
        val chains = mutableListOf<RunChain>()
        val results = mutableListOf<RunResult>()

        override fun onRunEnd(result: RunResult, chain: RunChain) {
            results += result
            chains += chain
            gate?.chain = chain
        }
    }

    /**
     * 续讲钩子（P3 classroom autoContinue 最小替身，场景 4/5）：run 正常收线、链
     * 活着且 depth<2 时，**同链**直接驱动 AgentLoop 发起后续 run；产出照
     * AgentSession 语义落库（取消亦照常落库）并重放 onRunEnd——深度护栏与取消
     * 护栏在此闭环。请求装配取 store 原史（本测试场景无 UI 标记，无需剥除）。
     */
    private class AutoContinueHooks(
        val scope: CoroutineScope,
        private val loop: AgentLoop,
        private val store: MessageStore,
        private val tools: List<ToolSpec>,
        private val gate: BudgetGate? = null,
        private val maxDepth: Int = 2,
    ) : AgentHooks {
        val jobs = CopyOnWriteArrayList<Job>()
        val chains = mutableListOf<RunChain>()
        val results = mutableListOf<RunResult>()

        override fun onRunEnd(result: RunResult, chain: RunChain) {
            results += result
            chains += chain
            gate?.chain = chain
            if (result.cancelled || !chain.scope.isActive) return   // 取消护栏：无后续 run
            if (chain.context.depth >= maxDepth) return            // depth+1 ≤ maxDepth
            chain.context.depth++
            jobs += scope.launch {
                val input = store.list(chain.conversationId)
                val outcome = loop.run(
                    input = input, system = "sys", tools = tools,
                    hooks = this@AutoContinueHooks, chain = chain, mode = ToolMode.NATIVE,
                ) { }
                outcome.produced.forEach { store.append(chain.conversationId, it) }
                onRunEnd(assemble(outcome), chain)
            }
        }

        /** LoopOutcome → RunResult（AgentSession.assembleRunResult 的最小同构替身） */
        private fun assemble(outcome: LoopOutcome): RunResult {
            val last = outcome.produced.lastOrNull { it.role == Role.ASSISTANT }
            return RunResult(
                finalText = last?.blocks?.filterIsInstance<ContentBlock.Text>()
                    ?.joinToString("\n") { it.text } ?: "",
                blocks = last?.blocks ?: emptyList(),
                usage = outcome.usage,
                turnCount = outcome.produced.mapNotNull { it.turnId }.distinct().size,
                truncated = outcome.truncated,
                cancelled = outcome.cancelled,
            )
        }
    }

    /** 等全部后续 run 收线（jobs 列表会随后续 run 递增——轮询直到无未完成项） */
    private suspend fun awaitAllRuns(hooks: AutoContinueHooks) {
        while (true) {
            val pending = hooks.jobs.filter { !it.isCompleted }
            if (pending.isEmpty()) return
            pending.joinAll()
        }
    }

    /** 预算装配方（本测试的核心扮演位，见类 KDoc）：web_search 受门 + 假引擎 */
    private fun webSearchRegistry(engine: SearchEngine, gate: BudgetGate): ToolRegistry =
        ToolRegistry().apply {
            register(WebSearchTool(engine))
            installBudgetGate("web_search") { gate.allows() }
        }

    private fun newSession(
        store: MessageStore,
        loop: AgentLoop,
        hooks: AgentHooks,
        tools: List<ToolSpec>,
    ): AgentSession = AgentSession(
        store = store,
        loop = loop,
        markers = markers,
        hooks = hooks,
        system = { "sys" },
        tools = tools,
        mode = { ToolMode.NATIVE },
        maxContext = { 0 },
        summarizer = { "S[$it]" },
    )

    private fun textOf(m: AgentMessage): String =
        m.blocks.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }

    private fun toolResults(msgs: List<AgentMessage>): List<ContentBlock.ToolResult> =
        msgs.filter { it.role == Role.TOOL_RESULT }
            .flatMap { it.blocks.filterIsInstance<ContentBlock.ToolResult>() }

    // ---------- ① 单工具往返：第二 turn 作答（TOOL_RESULT 落库，正文引用搜索结果） ----------

    @Test
    fun `1 single tool roundtrip answers on second turn citing search result`() = runBlocking {
        val store = InMemoryMessageStore()
        val engine = FakeEngine()
        val gate = BudgetGate()
        val registry = webSearchRegistry(engine, gate)
        val llm = ScriptedLlm("web_search").apply { script("[C]最新动态", "[T]已核实：关于最新动态的答案片段") }
        val hooks = RecordingHooks(gate)
        val session = newSession(store, AgentLoop(llm, registry, markers), hooks, registry.specs(listOf("web_search")))

        session.send("c1", "帮我查最新动态")

        val rows = store.list("c1")
        assertEquals(4, rows.size)   // user / assistant(ToolCall) / TOOL_RESULT / assistant(Text)
        assertTrue(rows[1].blocks.single() is ContentBlock.ToolCall)
        val tr = toolResults(rows).single()
        assertFalse(tr.isError)
        assertEquals("web_search", tr.name)
        assertTrue(tr.output.contains("关于最新动态的答案片段"))   // 引擎结果经工具落库
        assertEquals(rows[1].turnId, rows[2].turnId)              // 同 turn 共享 turnId
        // 作答 turn 的请求历史带着 TOOL_RESULT 行——模型"看到"了搜索结果，正文引用才有据
        val answerReq = llm.calls[1]
        assertTrue(answerReq.history.any {
            it.role == Role.TOOL_RESULT &&
                (it.blocks.single() as ContentBlock.ToolResult).output.contains("关于最新动态的答案片段")
        })
        assertEquals("已核实：关于最新动态的答案片段", textOf(rows.last()))
        // 预算回路：受门工具成功执行一次 → 链预算 2→1
        assertEquals(1, hooks.chains.single().context.searchBudgetRemaining)
    }

    // ---------- ② 多工具顺序执行：一个 turn 两条标记行 → 两个 ToolResult 顺序落库 ----------

    @Test
    fun `2 two marker lines in one turn execute sequentially and persist in order`() = runBlocking {
        val store = InMemoryMessageStore()
        val engine = FakeEngine()
        val gate = BudgetGate()
        val registry = webSearchRegistry(engine, gate)
        val llm = ScriptedLlm("web_search").apply { script("[C]q1\n[C]q2", "[T]两问都查完了") }
        val hooks = RecordingHooks(gate)
        val session = newSession(store, AgentLoop(llm, registry, markers), hooks, registry.specs(listOf("web_search")))

        session.send("c2", "两个问题")

        val rows = store.list("c2")
        // user / assistant(两个 ToolCall) / TOOL_RESULT(q1) / TOOL_RESULT(q2) / assistant(Text)
        assertEquals(5, rows.size)
        assertEquals(2, rows[1].blocks.filterIsInstance<ContentBlock.ToolCall>().size)
        val trs = toolResults(rows)
        assertEquals(2, trs.size)
        assertTrue(trs[0].output.contains("关于q1的答案片段"))   // 顺序落库：q1 在前
        assertTrue(trs[1].output.contains("关于q2的答案片段"))   // q2 在后
        assertEquals(listOf("q1", "q2"), engine.queries.toList())
        assertEquals(rows[1].turnId, rows[2].turnId)            // 两结果均与同 turn assistant 共享
        assertEquals(rows[1].turnId, rows[3].turnId)
        assertEquals("两问都查完了", textOf(rows.last()))
        // 两次受门成功 → 链预算 2→0
        assertEquals(0, hooks.chains.single().context.searchBudgetRemaining)
    }

    // ---------- ③ beforeToolCall Block：isError 回填理由、链继续到正文 ----------

    @Test
    fun `3 blocked tool call yields error result with reason and chain continues`() = runBlocking {
        val store = InMemoryMessageStore()
        val engine = FakeEngine()
        val gate = BudgetGate()
        val registry = webSearchRegistry(engine, gate)
        val llm = ScriptedLlm("web_search").apply { script("[C]敏感词", "[T]正文照常") }
        val hooks = object : RecordingHooks(gate) {
            override fun beforeToolCall(call: ContentBlock.ToolCall) = Verdict.Block("管理员禁止")
        }
        val session = newSession(store, AgentLoop(llm, registry, markers), hooks, registry.specs(listOf("web_search")))

        session.send("c3", "问")

        val rows = store.list("c3")
        assertEquals(4, rows.size)
        val tr = toolResults(rows).single()
        assertTrue(tr.isError)
        assertEquals("管理员禁止", tr.output)     // 理由透传
        assertTrue(engine.queries.isEmpty())     // 闸门前置：引擎未被执行
        assertEquals(2, llm.calls.size)          // 链继续到正文 turn
        assertEquals("正文照常", textOf(rows.last()))
        // Block 回填 isError → 不递减链预算
        assertEquals(2, hooks.chains.single().context.searchBudgetRemaining)
    }

    // ---------- ④ 续讲链与取消：onRunEnd 同链发起后续 run，第 2 run 流中 stop → 链死、无第 3 run、部分块落库 ----------

    @Test
    fun `4 stop during followup run kills chain and prevents third run`() = runBlocking {
        val store = InMemoryMessageStore()
        val engine = FakeEngine()
        val gate = BudgetGate()
        val registry = webSearchRegistry(engine, gate)
        val llm = ScriptedLlm("web_search").apply { script("[T]一答", "[K]二答半\n[W]1000\n[T]二答尾") }
        val loop = AgentLoop(llm, registry, markers)
        val extScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val hooks = AutoContinueHooks(extScope, loop, store, registry.specs(listOf("web_search")), gate)
        val session = newSession(store, loop, hooks, registry.specs(listOf("web_search")))

        session.send("c4", "问")   // run 1 完成 → onRunEnd 同链发起 run 2（depth 0→1）

        // 等 run 2 的流开（"[K]二答半"已过、[W]1000 挂起 = run 2 进行中；[T] 在本
        // Scripted 是脚本尾统一 flush——取消点前的已完成块须用即时发射的 [K]）
        val deadline = System.currentTimeMillis() + 5_000
        while (llm.calls.size < 2 && System.currentTimeMillis() < deadline) delay(20)
        assertEquals(2, llm.calls.size)
        delay(50)
        session.stop()             // 取消整链（run 2 流中，[W]1000 挂起）

        awaitAllRuns(hooks)
        extScope.cancel()

        assertEquals(2, llm.calls.size)                  // 无第 3 个 run（取消护栏生效）
        assertEquals(2, hooks.results.size)              // onRunEnd 重放：run 1（session）+ run 2（钩子）
        val chain = hooks.chains.first()
        assertTrue(hooks.chains.all { it === chain })    // 两个 run 同一条链
        assertFalse(chain.scope.isActive)                // 链死
        assertEquals(1, chain.context.depth)             // depth 0→1；run 2 取消后不再 +1
        assertTrue(hooks.results[1].cancelled)
        val rows = store.list("c4")
        // 取消贯通（P3 终审）：取消点前已完成块照常落库，点后幽灵尾块（"二答尾"）不补收
        assertEquals(3, rows.size)                       // user + 一答 + 二答半（thinking 块）
        val partial2 = rows.last().blocks.filterIsInstance<ContentBlock.Thinking>().single()
        assertEquals("二答半", partial2.text)
        assertTrue(rows.none { textOf(it).contains("二答尾") })
    }

    // ---------- ⑤ 链预算共享：run 1 用尽 2 次 → 续讲 run 标记剥除不执行；depth 护栏收束 ----------

    @Test
    fun `5 followup run on same chain shares exhausted budget and strips markers`() = runBlocking {
        val store = InMemoryMessageStore()
        val engine = FakeEngine()
        val gate = BudgetGate()
        val registry = webSearchRegistry(engine, gate)
        val llm = ScriptedLlm("web_search").apply {
            script("[C]q1\n[C]q2", "[T]一答", "[C]再搜\n[T]预算尽说明", "[T]三答")
        }
        val loop = AgentLoop(llm, registry, markers)
        val extScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val hooks = AutoContinueHooks(extScope, loop, store, registry.specs(listOf("web_search")), gate)
        val session = newSession(store, loop, hooks, registry.specs(listOf("web_search")))

        session.send("c5", "问")   // run 1：两次搜索把链预算 2→0 + 作答
        awaitAllRuns(hooks)        // run 2（depth 1）标记剥除 + run 3（depth 2）自然收束
        extScope.cancel()

        assertEquals(4, llm.calls.size)   // run1 两 turn + run2 + run3；depth 护栏 → 无 run4
        // 预算回路逐请求可观测：turn1 true（2>0），其后全部 false（0）——run 2 的
        // 请求即在其中，budgetLeft=false → 标记行剥除不解析
        assertEquals(listOf(true, false, false, false), llm.budgetSeen.toList())
        val chain = hooks.chains.first()
        assertEquals(2, chain.context.depth)             // depth 0→1→2，护栏收束
        assertEquals(0, chain.context.searchBudgetRemaining)
        assertTrue(hooks.results.none { it.cancelled })
        // 剥除不执行：引擎只搜过 run 1 的两次，TOOL_RESULT 计数不增
        assertEquals(listOf("q1", "q2"), engine.queries.toList())
        assertEquals(2, toolResults(store.list("c5")).size)
        // run 2 正文保留（剥的是标记行，不是整段文本）
        val rows = store.list("c5")
        assertEquals(7, rows.size)   // user + a2call + 2×TOOL_RESULT + 一答 + 预算尽说明 + 三答
        assertEquals("预算尽说明", textOf(rows[5]))
        assertEquals("三答", textOf(rows.last()))
    }

    // ---------- ⑥ maxTurns 收尾护栏 + 收尾标记剥除（非门控工具，预算不干扰） ----------

    @Test
    fun `6 maxTurns closing guard fires and strips closing marker lines`() = runBlocking {
        val store = InMemoryMessageStore()
        val registry = ToolRegistry().apply { register(EchoTool()) }   // 非门控：预算恒 2 不递减
        val llm = ScriptedLlm("echo").apply {
            script(*Array(8) { i -> "[C]q$i" })
            script("[T]收尾\n[搜索: 又要搜]")
        }
        val hooks = RecordingHooks()
        val session = newSession(store, AgentLoop(llm, registry, markers), hooks, registry.specs(listOf("echo")))

        session.send("c6", "问")

        assertEquals(9, llm.calls.size)   // 8 个工具 turn + 1 次收尾 turn（AgentSession 默认 maxTurns=8）
        val closing = llm.calls[8]
        assertTrue(closing.system.contains(AgentLoop.CLOSING_GUARD))   // 收尾护栏语注入
        assertTrue(closing.tools.isEmpty())                            // 收尾 tools 置空
        assertTrue(llm.calls[7].tools.isNotEmpty())
        val rows = store.list("c6")
        assertEquals(8, toolResults(rows).size)   // 8 次顺序执行，收尾 turn 无新调用
        val finalText = textOf(rows.last())
        assertEquals("收尾", finalText)           // 收尾标记剥除：正文无标记、无调用
        assertFalse(finalText.contains("[搜索:"))
        // echo 未装门 → 链预算不递减（评审二 P2-1：非门控工具变体）
        assertEquals(2, hooks.chains.single().context.searchBudgetRemaining)
    }

    // ---------- ⑦ 取消落库：工具执行中 stop → 部分文本保留 + "已取消" ToolResult ----------

    @Test
    fun `7 stop during tool execution keeps partial text and backfills cancelled result`() = runBlocking {
        val store = InMemoryMessageStore()
        val engine = FakeEngine(delayMs = 800)
        val gate = BudgetGate()
        val registry = webSearchRegistry(engine, gate)
        val llm = ScriptedLlm("web_search").apply { script("[T]先说一半\n[C]慢查询") }
        val hooks = RecordingHooks(gate)
        val session = newSession(store, AgentLoop(llm, registry, markers), hooks, registry.specs(listOf("web_search")))

        val job = launch { session.send("c7", "问") }
        delay(150)   // 工具执行中（引擎 delay(800) 挂起；即便慢半拍落在工具前，取消补落形态一致）
        session.stop()
        job.join()

        val rows = store.list("c7")
        assertEquals(3, rows.size)   // user + assistant(部分文本+ToolCall) + TOOL_RESULT(已取消)
        assertEquals("先说一半", textOf(rows[1]))   // 部分文本保留
        assertTrue(rows[1].blocks.any { it is ContentBlock.ToolCall })
        val tr = toolResults(rows).single()
        assertTrue(tr.isError)
        assertEquals(markers.toolCancelled, tr.output)   // 进行中工具补落（消灭孤儿 ToolCall）
        assertEquals("web_search", tr.name)
        assertEquals(rows[1].turnId, rows[2].turnId)
        assertEquals(listOf("慢查询"), engine.queries.toList())   // 执行被打断（查询已受理）
        assertTrue(hooks.results.single().cancelled)
        assertFalse(session.events.value.streaming)   // 终态收线补发
    }
}
