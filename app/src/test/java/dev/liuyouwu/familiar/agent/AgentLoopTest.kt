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
import dev.liuyouwu.familiar.tools.AgentTool
import dev.liuyouwu.familiar.tools.ToolRegistry
import dev.liuyouwu.familiar.viewmodel.RunMarkerTexts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLoopTest {

    // ---------- 测试基建：ScriptedLlm（脚本 DSL）+ 工具 ----------

    /**
     * 脚本回放 LlmClient：按调用次数回放脚本，每次调用发完整事件骨架
     * （RunStart → 块事件 → TurnEnd → RunEnd，TurnStart 由 AgentLoop 铸造故不发）。
     * 脚本 DSL（每行一个指令）：
     * - `[T]正文`：正文块（正文可内嵌原始标记行 `[搜索: …]`）
     * - `[C]query`：一次工具调用（等价标记命中）
     * - `[N]`：空 turn（无任何块）
     * 标记有效性对照 LlmClient 硬契约（spec §4.3）：请求带 [toolName] 工具且
     * budgetLeft() 为 true 才解析为 ToolCall；不生效（无工具/预算尽）→ 标记行整行
     * 剥除、不解析不报错。每次调用 usage 固定 prompt=10/completion=5（跨 turn 累计可断言）。
     */
    private class ScriptedLlm(private val toolName: String) : LlmClient {
        val calls = mutableListOf<LlmRequest>()
        val budgetSeen = mutableListOf<Boolean>()
        private val scripts = ArrayDeque<String>()
        private var blockSeq = 0

        fun script(vararg turns: String) {
            scripts.addAll(turns)
        }

        override fun stream(req: LlmRequest): Flow<StreamEvent> = flow {
            calls += req
            budgetSeen += req.budgetLeft()
            val script = scripts.removeFirstOrNull() ?: error("第 ${calls.size} 次 LLM 调用无脚本")
            emit(StreamEvent.RunStart("scripted-${calls.size}"))
            val effective = req.tools.any { it.name == toolName } && req.budgetLeft()
            val toolCalls = mutableListOf<ContentBlock.ToolCall>()
            var text = StringBuilder()

            suspend fun flushText() {
                if (text.isNotEmpty()) {
                    val id = "s${calls.size}-b${blockSeq++}"
                    emit(StreamEvent.BlockStart(id, "text"))
                    emit(StreamEvent.BlockDelta(id, text.toString()))
                    emit(StreamEvent.BlockEnd(id))
                    text = StringBuilder()
                }
            }

            suspend fun emitCall(query: String) {
                flushText()
                val id = "s${calls.size}-b${blockSeq++}"
                val args = """{"query":"$query"}"""
                emit(StreamEvent.BlockStart(id, "toolCall"))
                emit(StreamEvent.ToolCallDelta(id, toolName, args))
                emit(StreamEvent.BlockEnd(id))
                toolCalls += ContentBlock.ToolCall(id, toolName, args)
            }

            for (line in script.lines().map { it.trim() }) {
                val lineMarker = MARKER_RE.matchEntire(line)
                when {
                    line.isEmpty() || line == "[N]" -> {}
                    // 裸标记行（[T] 正文换行后独立成行）：生效→ToolCall；不生效→剥除
                    lineMarker != null && effective -> emitCall(lineMarker.groupValues[1])
                    lineMarker != null -> {}
                    line.startsWith("[T]") -> for (body in line.removePrefix("[T]").split('\n')) {
                        val marker = MARKER_RE.matchEntire(body.trim())
                        when {
                            marker != null && effective -> emitCall(marker.groupValues[1])
                            marker != null -> {} // 不生效：标记行剥除
                            else -> {
                                if (text.isNotEmpty()) text.append('\n')
                                text.append(body)
                            }
                        }
                    }
                    line.startsWith("[C]") -> if (effective) emitCall(line.removePrefix("[C]").trim())
                    else -> error("未知脚本行：$line")
                }
            }
            flushText()
            emit(StreamEvent.TurnEnd(toolCalls.toList(), truncated = false))
            emit(StreamEvent.RunEnd(Usage(promptTokens = 10, completionTokens = 5)))
        }

        companion object {
            val MARKER_RE = Regex("""\[搜索:\s*(.+?)]""")
        }
    }

    /** 回显工具：[C]query → 输出 echo:query（未装预算门，多轮循环不与预算纠缠） */
    private class EchoTool : AgentTool {
        override val name = "echo"
        override val description = "回显"
        override val parametersJson = """{"type":"object","properties":{"query":{"type":"string"}}}"""

        override suspend fun execute(args: JsonObject, onProgress: (String) -> Unit): String =
            "echo:" + (args.get("query")?.takeIf { it.isJsonPrimitive }?.asString ?: "")
    }

    /** 慢工具：delay(500)，取消测试用（未装预算门） */
    private class SlowTool : AgentTool {
        override val name = "slow"
        override val description = "慢工具"
        override val parametersJson = """{"type":"object","properties":{"query":{"type":"string"}}}"""

        override suspend fun execute(args: JsonObject, onProgress: (String) -> Unit): String {
            delay(500)
            return "slow-done"
        }
    }

    private val noHooks = object : AgentHooks {}

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

    private fun userMsg(id: String, text: String) =
        AgentMessage(id = id, role = Role.USER, blocks = listOf(ContentBlock.Text(text)))

    private fun textOf(m: AgentMessage): String =
        m.blocks.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }

    private fun toolResultBlocks(msgs: List<AgentMessage>): List<ContentBlock.ToolResult> =
        msgs.filter { it.role == Role.TOOL_RESULT }
            .flatMap { it.blocks.filterIsInstance<ContentBlock.ToolResult>() }

    private fun registryOf(vararg tools: AgentTool): ToolRegistry =
        ToolRegistry().apply { tools.forEach { register(it) } }

    // ---------- ① 工具往返第二 turn 作答（默认 hooks + 预算回路装配即此） ----------

    @Test
    fun `1 tool roundtrip answers on second turn with budget loop wired`() = runBlocking {
        val reg = registryOf(EchoTool())
        val chain = RunChain("c1", CoroutineScope(Dispatchers.Unconfined))
        reg.installBudgetGate("echo") { chain.context.searchBudgetRemaining > 0 }
        val llm = ScriptedLlm("echo").apply { script("[C]hi", "[T]答案好了") }

        val out = AgentLoop(llm, reg, markers).run(
            input = listOf(userMsg("u1", "问题")),
            system = "sys", tools = reg.specs(listOf("echo")),
            hooks = noHooks, chain = chain, mode = ToolMode.NATIVE,
        ) { }

        assertFalse(out.cancelled)
        assertFalse(out.truncated)
        assertEquals(2, llm.calls.size)
        // budgetLeft 回路：链上预算按 turn 求值进入请求（两次调用均 true：2→1 后仍 >0）
        assertEquals(listOf(true, true), llm.budgetSeen)
        // echo 已装门：成功且非 isError 执行一次 → 递减 2→1
        assertEquals(1, chain.context.searchBudgetRemaining)
        // 用量跨 turn 累计
        assertEquals(Usage(promptTokens = 20, completionTokens = 10), out.usage)
        // produced：assistant(ToolCall) → TOOL_RESULT → assistant(Text)
        assertEquals(3, out.produced.size)
        assertEquals(Role.ASSISTANT, out.produced[0].role)
        assertTrue(out.produced[0].blocks.single() is ContentBlock.ToolCall)
        assertEquals(Role.TOOL_RESULT, out.produced[1].role)
        val tr = toolResultBlocks(out.produced).single()
        assertFalse(tr.isError)
        assertEquals("echo:hi", tr.output)
        assertEquals("答案好了", textOf(out.produced[2]))
    }

    // ---------- ② finishTurn=END 首轮即停 ----------

    @Test
    fun `2 finishTurn END stops after first tool turn`() = runBlocking {
        val reg = registryOf(EchoTool())
        val chain = RunChain("c2", CoroutineScope(Dispatchers.Unconfined))
        val llm = ScriptedLlm("echo").apply { script("[C]q") }
        val endHooks = object : AgentHooks {
            override fun finishTurn(turn: Int, msgs: List<AgentMessage>) = Decision.END
        }

        val out = AgentLoop(llm, reg, markers).run(
            input = emptyList(), system = "sys", tools = reg.specs(listOf("echo")),
            hooks = endHooks, chain = chain, mode = ToolMode.NATIVE, maxTurns = 8,
        ) { }

        assertFalse(out.cancelled)
        assertEquals(1, llm.calls.size) // END 即停：无第二轮 LLM 调用
        assertEquals(2, out.produced.size) // assistant(ToolCall) + TOOL_RESULT，无收尾 turn
        assertEquals(1, toolResultBlocks(out.produced).size)
    }

    // ---------- ③ maxTurns 收尾护栏 ----------

    @Test
    fun `3 maxTurns exhaustion appends toolless closing turn`() = runBlocking {
        val reg = registryOf(EchoTool()) // 未装预算门：预算耗尽剥标记不干扰本场景
        val chain = RunChain("c3", CoroutineScope(Dispatchers.Unconfined))
        val llm = ScriptedLlm("echo").apply {
            script("[C]a", "[C]b", "[C]c", "[T]收尾作答")
        }

        val out = AgentLoop(llm, reg, markers).run(
            input = emptyList(), system = "sys", tools = reg.specs(listOf("echo")),
            hooks = noHooks, chain = chain, mode = ToolMode.NATIVE, maxTurns = 3,
        ) { }

        assertFalse(out.cancelled)
        assertEquals(4, llm.calls.size) // 3 个工具 turn + 1 次收尾 turn
        // 收尾护栏：末次请求 system 注入护栏语 + tools 置空
        assertTrue(llm.calls[3].system.contains("工具预算已尽，请基于已有信息直接作答，不要再调用工具"))
        assertTrue(llm.calls[3].tools.isEmpty())
        assertTrue(llm.calls[2].tools.isNotEmpty())
        // 3 个 TOOL_RESULT + 收尾正文
        assertEquals(3, toolResultBlocks(out.produced).size)
        assertEquals("收尾作答", textOf(out.produced.last()))
        assertEquals(7, out.produced.size) // 3×(assistant+result) + 收尾 assistant
    }

    // ---------- ④ 收尾标记剥除 ----------

    @Test
    fun `4 closing turn strips marker lines when tools emptied`() = runBlocking {
        val reg = registryOf(EchoTool()) // 未装预算门，预算恒 2（不递减）
        val chain = RunChain("c4", CoroutineScope(Dispatchers.Unconfined))
        val llm = ScriptedLlm("echo").apply {
            script(*Array(8) { i -> "[C]q$i" })
            script("[T]这是收尾\n[搜索: 又要搜]")
        }

        val out = AgentLoop(llm, reg, markers).run(
            input = emptyList(), system = "sys", tools = reg.specs(listOf("echo")),
            hooks = noHooks, chain = chain, mode = ToolMode.MARKER, maxTurns = 8,
        ) { }

        assertFalse(out.cancelled)
        assertEquals(9, llm.calls.size)
        // 恰 8 个 TOOL_RESULT：8 个工具 turn 全执行，收尾 turn 无新调用
        assertEquals(8, toolResultBlocks(out.produced).size)
        // 收尾正文无标记：tools 已置空 → 标记行剥除不解析
        val finalText = textOf(out.produced.last())
        assertEquals("这是收尾", finalText)
        assertFalse(finalText.contains("[搜索:"))
        // 未装门 → 预算不递减
        assertEquals(2, chain.context.searchBudgetRemaining)
    }

    // ---------- ⑤ 取消传播补落 ----------

    @Test
    fun `5 cancellation backfills cancelled tool result and keeps produced`() = runBlocking {
        val reg = registryOf(SlowTool())
        val chain = RunChain("c5", CoroutineScope(Dispatchers.Default))
        val llm = ScriptedLlm("slow").apply { script("[C]q") }
        val loop = AgentLoop(llm, reg, markers)

        val outcome = CoroutineScope(Dispatchers.Unconfined).async {
            loop.run(
                input = emptyList(), system = "sys", tools = reg.specs(listOf("slow")),
                hooks = noHooks, chain = chain, mode = ToolMode.NATIVE, maxTurns = 8,
            ) { }
        }
        Thread.sleep(200) // 慢工具 delay(500) 进行中
        chain.cancel()
        val out = outcome.await()

        assertTrue(out.cancelled)
        assertEquals(1, llm.calls.size)
        // 已产出保留：assistant(ToolCall) 仍在
        assertEquals(Role.ASSISTANT, out.produced.first().role)
        assertTrue(out.produced.first().blocks.single() is ContentBlock.ToolCall)
        // 进行中工具补落 isError ToolResult（"已取消"）
        val tr = toolResultBlocks(out.produced).single()
        assertTrue(tr.isError)
        assertEquals(markers.toolCancelled, tr.output)
        assertEquals("slow", tr.name)
    }

    // ---------- ⑥ turnId 铸造 ----------

    @Test
    fun `6 turnId minted per turn shared by assistant and tool result`() = runBlocking {
        val reg = registryOf(EchoTool())
        val chain = RunChain("c6", CoroutineScope(Dispatchers.Unconfined))
        val llm = ScriptedLlm("echo").apply { script("[C]q", "[T]done") }
        val events = mutableListOf<StreamEvent>()

        val out = AgentLoop(llm, reg, markers).run(
            input = emptyList(), system = "sys", tools = reg.specs(listOf("echo")),
            hooks = noHooks, chain = chain, mode = ToolMode.MARKER,
        ) { ev -> events += ev }

        assertEquals(3, out.produced.size)
        val (a0, r0, a1) = out.produced
        // 同 turn 的 assistant 与 TOOL_RESULT 共享 turnId
        assertEquals(a0.turnId, r0.turnId)
        // turnId 形状与 turn 序号
        assertTrue(a0.turnId!!.matches(Regex("""^run-.+:0$""")))
        assertTrue(a1.turnId!!.matches(Regex("""^run-.+:1$""")))
        // 不同 turn 不同 turnId
        assertTrue(a0.turnId != a1.turnId)
        // TurnStart 由 AgentLoop 铸造（LlmClient 不发）
        assertEquals(listOf(0, 1), events.filterIsInstance<StreamEvent.TurnStart>().map { it.turn })
    }

    // ---------- ⑦ 收尾 turn 幻觉 ToolCall 剥除（Task 11 移交项 2） ----------

    @Test
    fun `7 closing turn hallucinated tool call stripped from persisted assistant`() = runBlocking {
        val reg = registryOf(EchoTool())
        val chain = RunChain("c7", CoroutineScope(Dispatchers.Unconfined))
        // 回放端不理会请求 tools 是否为空：第 2 次（收尾 turn）违规发出 ToolCall+Text，
        // 模拟无工具收尾里模型的幻觉调用
        var callNo = 0
        val llm = object : LlmClient {
            override fun stream(req: LlmRequest): Flow<StreamEvent> = flow {
                callNo++
                emit(StreamEvent.RunStart("h$callNo"))
                if (callNo == 1) {
                    emit(StreamEvent.BlockStart("b1", "toolCall"))
                    emit(StreamEvent.ToolCallDelta("b1", "echo", """{"query":"q"}"""))
                    emit(StreamEvent.BlockEnd("b1"))
                    emit(StreamEvent.TurnEnd(
                        listOf(ContentBlock.ToolCall("b1", "echo", """{"query":"q"}""")), false))
                } else {
                    emit(StreamEvent.BlockStart("b2", "toolCall"))
                    emit(StreamEvent.ToolCallDelta("b2", "echo", """{"query":"幻觉"}"""))
                    emit(StreamEvent.BlockEnd("b2"))
                    emit(StreamEvent.BlockStart("b3", "text"))
                    emit(StreamEvent.BlockDelta("b3", "收尾"))
                    emit(StreamEvent.BlockEnd("b3"))
                    emit(StreamEvent.TurnEnd(
                        listOf(ContentBlock.ToolCall("b2", "echo", """{"query":"幻觉"}""")), false))
                }
                emit(StreamEvent.RunEnd(Usage(promptTokens = 1, completionTokens = 1)))
            }
        }

        val out = AgentLoop(llm, reg, markers).run(
            input = emptyList(), system = "sys", tools = reg.specs(listOf("echo")),
            hooks = noHooks, chain = chain, mode = ToolMode.NATIVE, maxTurns = 1,
        ) { }

        assertFalse(out.cancelled)
        assertEquals(2, callNo)
        // 收尾 assistant 只剩 Text：幻觉 ToolCall 已剥除（无未配对调用落账）
        val closing = out.produced.last { it.role == Role.ASSISTANT }
        assertTrue(closing.blocks.none { it is ContentBlock.ToolCall })
        assertEquals("收尾", closing.blocks.filterIsInstance<ContentBlock.Text>().single().text)
        // TOOL_RESULT 恰 1 个（turn0 的 echo）：收尾调用不执行也无需配对
        assertEquals(1, toolResultBlocks(out.produced).size)
    }

    // ---------- ⑧ 收尾 turn 收流中被取消：cancelled=true + 补落（Task 11 移交项 3） ----------

    @Test
    fun `8 cancel during closing turn marks cancelled and backfills`() = runBlocking {
        val reg = registryOf(EchoTool())
        val chain = RunChain("c8", CoroutineScope(Dispatchers.Default))
        var callNo = 0
        val llm = object : LlmClient {
            override fun stream(req: LlmRequest): Flow<StreamEvent> = flow {
                callNo++
                emit(StreamEvent.RunStart("z$callNo"))
                if (callNo == 1) {
                    emit(StreamEvent.BlockStart("b1", "toolCall"))
                    emit(StreamEvent.ToolCallDelta("b1", "echo", """{"query":"q"}"""))
                    emit(StreamEvent.BlockEnd("b1"))
                    emit(StreamEvent.TurnEnd(
                        listOf(ContentBlock.ToolCall("b1", "echo", """{"query":"q"}""")), false))
                } else {
                    emit(StreamEvent.BlockStart("b2", "toolCall"))
                    emit(StreamEvent.ToolCallDelta("b2", "echo", """{"query":"收尾调用"}"""))
                    emit(StreamEvent.BlockEnd("b2"))
                    delay(400)   // 收尾流进行中被取消
                    emit(StreamEvent.BlockStart("b3", "text"))
                    emit(StreamEvent.BlockDelta("b3", "收尾"))
                    emit(StreamEvent.BlockEnd("b3"))
                    emit(StreamEvent.TurnEnd(
                        listOf(ContentBlock.ToolCall("b2", "echo", """{"query":"收尾调用"}""")), false))
                }
                emit(StreamEvent.RunEnd(Usage(promptTokens = 1, completionTokens = 1)))
            }
        }

        val outcome = CoroutineScope(Dispatchers.Unconfined).async {
            AgentLoop(llm, reg, markers).run(
                input = emptyList(), system = "sys", tools = reg.specs(listOf("echo")),
                hooks = noHooks, chain = chain, mode = ToolMode.NATIVE, maxTurns = 1,
            ) { }
        }
        Thread.sleep(150)   // 收尾 turn 流中（delay(400) 挂起）
        chain.cancel()
        val out = outcome.await()

        assertTrue(out.cancelled)
        // 已产出保留：收尾 assistant（ToolCall+Text 均在），其调用补落 isError "已取消"
        val closing = out.produced.last { it.role == Role.ASSISTANT }
        assertTrue(closing.blocks.any { it is ContentBlock.ToolCall })
        assertEquals(2, toolResultBlocks(out.produced).size)   // turn0 echo 结果 + 收尾补落
        val backfilled = toolResultBlocks(out.produced).last()
        assertTrue(backfilled.isError)
        assertEquals(markers.toolCancelled, backfilled.output)
        assertEquals(closing.turnId, out.produced.last().turnId)   // 补落与收尾 assistant 同 turn
    }

    // ---------- ⑨ 流内 toolResult 块（参数校验失败回填）落独立 TOOL_RESULT 行 ----------

    @Test
    fun `9 stream toolResult block lands as independent TOOL_RESULT row`() = runBlocking {
        val reg = registryOf(EchoTool())
        val chain = RunChain("c9", CoroutineScope(Dispatchers.Unconfined))
        // LlmClient 原生参数校验失败回填形态（DefaultLlmClient.emitNativeCalls 的
        // isError "toolResult" 块事件，spec §4.1 不变式）
        val llm = object : LlmClient {
            override fun stream(req: LlmRequest): Flow<StreamEvent> = flow {
                emit(StreamEvent.RunStart("r1"))
                emit(StreamEvent.BlockStart("b1", "toolResult"))
                emit(StreamEvent.BlockDelta("b1", "模型输出的工具调用参数不是合法 JSON，已丢弃该调用：{坏"))
                emit(StreamEvent.BlockEnd("b1"))
                emit(StreamEvent.TurnEnd(emptyList(), truncated = false))
                emit(StreamEvent.RunEnd(Usage(promptTokens = 1, completionTokens = 1)))
            }
        }

        val out = AgentLoop(llm, reg, markers).run(
            input = emptyList(), system = "sys", tools = reg.specs(listOf("echo")),
            hooks = noHooks, chain = chain, mode = ToolMode.NATIVE,
        ) { }

        assertFalse(out.cancelled)
        assertEquals(1, out.produced.size)                       // 无 assistant 行：唯一产出即回执行
        val row = out.produced.single()
        assertEquals(Role.TOOL_RESULT, row.role)                 // 独立 TOOL_RESULT 消息，非 assistant 内嵌
        assertTrue(row.blocks.single() !is ContentBlock.Text)    // 不混进 assistant 正文形态
        val r = row.blocks.single() as ContentBlock.ToolResult
        assertTrue(r.isError)
        assertEquals("b1", r.callId)
        assertTrue(r.output.contains("不是合法 JSON"))
        assertTrue(row.turnId!!.matches(Regex("""^run-.+:\d+$""")))   // 同 turn turnId
    }

    // ---------- ⑩ model 装配：session 供应商生效 + run 级 override 优先 ----------

    @Test
    fun `10 model supplier fills request and override takes priority`() = runBlocking {
        val reg = registryOf(EchoTool())
        val chain = RunChain("c10", CoroutineScope(Dispatchers.Unconfined))
        val llm = ScriptedLlm("echo").apply { script("[T]一", "[T]二") }
        val loop = AgentLoop(llm, reg, markers)

        // 无 override：session 供应商值进请求
        loop.run(
            input = emptyList(), system = "sys", tools = emptyList(),
            hooks = noHooks, chain = chain, mode = ToolMode.NATIVE,
            model = { "session-model" },
        ) { }
        // override 优先于 session 供应商
        loop.run(
            input = emptyList(), system = "sys", tools = emptyList(),
            hooks = noHooks, chain = chain, mode = ToolMode.NATIVE,
            modelOverride = "override-model", model = { "session-model" },
        ) { }

        assertEquals(listOf("session-model", "override-model"), llm.calls.map { it.model })
    }

    // ---------- ⑪ maxTokens 供应商：装配值进每次 LlmRequest（含收尾 turn） ----------

    @Test
    fun `11 maxTokens supplier plumbed into every request including closing turn`() = runBlocking {
        val reg = registryOf(EchoTool())
        val chain = RunChain("c11", CoroutineScope(Dispatchers.Unconfined))
        val llm = ScriptedLlm("echo").apply { script("[C]a", "[C]b", "[C]c", "[T]收尾") }

        AgentLoop(llm, reg, markers).run(
            input = emptyList(), system = "sys", tools = reg.specs(listOf("echo")),
            hooks = noHooks, chain = chain, mode = ToolMode.NATIVE, maxTurns = 3,
            maxTokens = { 512 },
        ) { }

        // 4 次调用（3 个工具 turn + 1 次收尾 turn）全部带上装配值——"每次 LlmRequest"贯通
        assertEquals(4, llm.calls.size)
        assertEquals(listOf(512, 512, 512, 512), llm.calls.map { it.maxTokens })
    }

    // ---------- ⑫ maxTokens 不发送：默认/显式 null 供应商/供应商返回 null ----------

    @Test
    fun `12 maxTokens omitted when absent null supplier or supplier returns null`() = runBlocking {
        val reg = registryOf(EchoTool())
        val chain = RunChain("c12", CoroutineScope(Dispatchers.Unconfined))
        val llm = ScriptedLlm("echo").apply { script("[T]一", "[T]二", "[T]三") }
        val loop = AgentLoop(llm, reg, markers)

        loop.run(   // 默认（不传）：不带该字段
            input = emptyList(), system = "sys", tools = emptyList(),
            hooks = noHooks, chain = chain, mode = ToolMode.NATIVE,
        ) { }
        loop.run(   // 显式 null 供应商：不带
            input = emptyList(), system = "sys", tools = emptyList(),
            hooks = noHooks, chain = chain, mode = ToolMode.NATIVE, maxTokens = null,
        ) { }
        loop.run(   // 供应商返回 null：不带
            input = emptyList(), system = "sys", tools = emptyList(),
            hooks = noHooks, chain = chain, mode = ToolMode.NATIVE, maxTokens = { null },
        ) { }

        assertEquals(listOf(null, null, null), llm.calls.map { it.maxTokens })
    }

    // ---------- ⑬ searchAllowed 贯通（false 直达请求）+ sampling 未播种仍 null ----------

    @Test
    fun `13 searchAllowed false reaches request and unseeded sampling stays null`() = runBlocking {
        val reg = registryOf(EchoTool())
        val chain = RunChain("c13", CoroutineScope(Dispatchers.Unconfined))
        val llm = ScriptedLlm("echo").apply { script("[T]一", "[C]q", "[T]答") }
        val loop = AgentLoop(llm, reg, markers)

        loop.run(   // 中性默认：允许搜索
            input = emptyList(), system = "sys", tools = emptyList(),
            hooks = noHooks, chain = chain, mode = ToolMode.NATIVE,
        ) { }
        loop.run(   // false 直通进每次请求（两个 turn）
            input = emptyList(), system = "sys", tools = reg.specs(listOf("echo")),
            hooks = noHooks, chain = chain, mode = ToolMode.NATIVE,
            searchAllowed = { false },
        ) { }

        assertEquals(listOf(true, false, false), llm.calls.map { it.searchAllowed })
        // sampling 未播种（默认链上下文）：temperature 仍 null——播种生效见 sampling 专测
        assertEquals(listOf(null, null, null), llm.calls.map { it.temperature })
    }

    // ---------- ⑭ 流收集中链取消（P3 终审：取消贯通 SSE 订阅）：取消点前已完成块落库、点后不落 ----------

    @Test
    fun `14 chain cancel during stream persists completed blocks before cancel point only`() = runBlocking {
        val reg = registryOf(EchoTool())
        val chain = RunChain("c14", CoroutineScope(Dispatchers.Default))
        val llm = object : LlmClient {
            override fun stream(req: LlmRequest): Flow<StreamEvent> = flow {
                emit(StreamEvent.RunStart("s1"))
                emit(StreamEvent.BlockStart("b1", "text"))
                emit(StreamEvent.BlockDelta("b1", "取消点前"))
                emit(StreamEvent.BlockEnd("b1"))
                delay(400)   // 取消点：其后块不再发出
                emit(StreamEvent.BlockStart("b2", "text"))
                emit(StreamEvent.BlockDelta("b2", "取消点后"))
                emit(StreamEvent.BlockEnd("b2"))
                emit(StreamEvent.TurnEnd(emptyList(), truncated = false))
                emit(StreamEvent.RunEnd(Usage(promptTokens = 10, completionTokens = 5)))
            }
        }

        val outcome = CoroutineScope(Dispatchers.Unconfined).async {
            AgentLoop(llm, reg, markers).run(
                input = emptyList(), system = "sys", tools = emptyList(),
                hooks = noHooks, chain = chain, mode = ToolMode.NATIVE,
            ) { }
        }
        Thread.sleep(150)   // 流中（delay(400) 挂起）
        chain.cancel()
        val out = outcome.await()

        assertTrue(out.cancelled)
        // spec §5.1：取消时已完成的部分块照常落库——取消点前块保留
        assertEquals(1, out.produced.size)
        assertEquals("取消点前", textOf(out.produced.single()))
        // 取消点后的幽灵尾块不得落库（终审 Important-1：软停吞鬼影）
        assertTrue(out.produced.none { textOf(it).contains("取消点后") })
        // RunEnd 未达：无用量（原全量收线语义下会累计出 10/5）
        assertEquals(Usage(), out.usage)
    }

    // ---------- ⑮ 流收集中链取消：取消点前未配对的工具调用补落 isError ----------

    @Test
    fun `15 chain cancel during stream backfills unpaired tool call`() = runBlocking {
        val reg = registryOf(EchoTool())
        val chain = RunChain("c15", CoroutineScope(Dispatchers.Default))
        val llm = object : LlmClient {
            override fun stream(req: LlmRequest): Flow<StreamEvent> = flow {
                emit(StreamEvent.RunStart("s1"))
                emit(StreamEvent.BlockStart("b1", "text"))
                emit(StreamEvent.BlockDelta("b1", "先答一半"))
                emit(StreamEvent.BlockEnd("b1"))
                emit(StreamEvent.BlockStart("c1", "toolCall"))
                emit(StreamEvent.ToolCallDelta("c1", "echo", """{"query":"q"}"""))
                emit(StreamEvent.BlockEnd("c1"))
                delay(400)   // 取消点：TurnEnd 未达，调用未执行
                emit(StreamEvent.TurnEnd(
                    listOf(ContentBlock.ToolCall("c1", "echo", """{"query":"q"}""")), false))
                emit(StreamEvent.RunEnd(Usage(promptTokens = 10, completionTokens = 5)))
            }
        }

        val outcome = CoroutineScope(Dispatchers.Unconfined).async {
            AgentLoop(llm, reg, markers).run(
                input = emptyList(), system = "sys", tools = reg.specs(listOf("echo")),
                hooks = noHooks, chain = chain, mode = ToolMode.NATIVE,
            ) { }
        }
        Thread.sleep(150)
        chain.cancel()
        val out = outcome.await()

        assertTrue(out.cancelled)
        // 取消点前已完成块：assistant(Text + 未配对 ToolCall) + 补落 isError "已取消"
        assertEquals(2, out.produced.size)
        val assistant = out.produced.first { it.role == Role.ASSISTANT }
        assertEquals("先答一半", textOf(assistant))
        assertTrue(assistant.blocks.any { it is ContentBlock.ToolCall })
        val tr = toolResultBlocks(out.produced).single()
        assertTrue(tr.isError)
        assertEquals(markers.toolCancelled, tr.output)
        assertEquals(assistant.turnId, out.produced.last().turnId)   // 补落与 assistant 同 turn
    }

    // ---------- ⑯ 尾部附注逐 turn 上 wire（P5 审查修复：request 消费 build 产物） ----------

    /**
     * P5 审查 Important-2 的钉死：transformContext 逐 turn 经 appendTrailingUserNote
     * 注入的附注必须上 wire——AgentLoop.request() 消费 build() 产物（appendSystem
     * 时代逐 turn 注入能力的等价迁移）。多 turn（web_search 工具往返）下每个请求
     * 附注都在尾，且上一 turn 的附注被重新装配剥除取代（不堆积、不重复计费）。
     */
    @Test
    fun `16 trailing note rides every turn request superseded not duplicated`() = runBlocking {
        val reg = registryOf(EchoTool())
        val chain = RunChain("c16", CoroutineScope(Dispatchers.Unconfined))
        reg.installBudgetGate("echo") { chain.context.searchBudgetRemaining > 0 }
        val llm = ScriptedLlm("echo").apply { script("[C]hi", "[T]答案好了") }
        val noteHooks = object : AgentHooks {
            override fun transformContext(ctx: ContextBuilder) {
                ctx.appendTrailingUserNote("（系统附注：记忆尾）")
            }
        }

        AgentLoop(llm, reg, markers).run(
            input = listOf(userMsg("u1", "问题")),
            system = "sys", tools = reg.specs(listOf("echo")),
            hooks = noteHooks, chain = chain, mode = ToolMode.NATIVE,
        ) { }

        assertEquals(2, llm.calls.size)
        // turn0 请求 = [u1, 附注]
        assertEquals(listOf(Role.USER, Role.USER), llm.calls[0].history.map { it.role })
        assertEquals("（系统附注：记忆尾）", textOf(llm.calls[0].history.last()))
        // turn1 请求 = [u1, assistant(toolcall), toolresult, 附注]——上一 turn 的附注
        // 被剥除取代（恒一条、恒在尾），真实历史追加不受影响
        assertEquals(
            listOf(Role.USER, Role.ASSISTANT, Role.TOOL_RESULT, Role.USER),
            llm.calls[1].history.map { it.role },
        )
        assertEquals("（系统附注：记忆尾）", textOf(llm.calls[1].history.last()))
        llm.calls.forEachIndexed { i, call ->
            assertEquals("turn$i 附注应恒一条", 1, call.history.count { it.id == ContextBuilder.TRAILING_NOTE_ID })
        }
        // system 不承载附注（缓存锚点稳定）
        assertEquals("sys", llm.calls[0].system)
        assertEquals(llm.calls[0].system, llm.calls[1].system)
    }

    // ---------- ⑰ sampling 链上下文播种 → LlmRequest（B2-L1） ----------

    @Test
    fun `sampling params flow from chain context into LlmRequest`() = runBlocking {
        // 链上下文播种后请求携带采样参数（fixture 实名核对：ScriptedLlm:41 构造需 toolName
        // 且需 script(...) 供流——审查 N5；val calls 捕获 LlmRequest、registryOf:146、
        // noHooks:134、userMsg(id,text):136）
        val llm = ScriptedLlm("echo").apply { script("[T]好") }   // 脚本形态照抄 :525-546 既有用例
        val chain = RunChain("c1", CoroutineScope(SupervisorJob() + Dispatchers.Default))
        chain.context.sampling = SamplingParams(0.9, 0.4, 0.3)
        AgentLoop(llm, registryOf(), markers).run(
            input = listOf(userMsg("u1", "你好")), system = "s", tools = emptyList(),
            hooks = noHooks, chain = chain, mode = ToolMode.MARKER, emit = {},
        )
        val req = llm.calls.single()
        assertEquals(0.9, req.temperature)
        assertEquals(0.4, req.frequencyPenalty)
        assertEquals(0.3, req.presencePenalty)
    }

    // ---------- ⑱ sampling 默认（未配置 5 skill）：三字段全 null ----------

    @Test
    fun `sampling defaults keep request unchanged`() = runBlocking {
        // 未配置 sampling（其余 5 skill）：三字段全 null——零 diff 根基
        val llm = ScriptedLlm("echo").apply { script("[T]好") }
        AgentLoop(llm, registryOf(), markers).run(
            input = listOf(userMsg("u1", "你好")), system = "s", tools = emptyList(),
            hooks = noHooks, chain = RunChain("c1", CoroutineScope(SupervisorJob() + Dispatchers.Default)),
            mode = ToolMode.MARKER, emit = {},
        )
        val req = llm.calls.single()
        assertNull(req.temperature); assertNull(req.frequencyPenalty); assertNull(req.presencePenalty)
    }
}
