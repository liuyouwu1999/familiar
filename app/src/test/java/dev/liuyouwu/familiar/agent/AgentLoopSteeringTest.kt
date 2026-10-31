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
import kotlinx.coroutines.Job
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
 * AgentLoop 回合顶 steer 注入测试（P-steer，spec §3.1，Task 1 内核层）：
 * - 回合顶 drain 注入进请求上下文（不进 produced——user 行由 session 层 steer
 *   落库，本任务只管注入进 messages）；
 * - 自然收束 / finishTurn=END 被插话顶住（下一回合顶注入后再答）；
 * - 空队列默认参数回归（既有直调点零改动）；
 * - 预算尽残留标志（LoopOutcome.steeringLeftover）。
 * 全部直调 AgentLoop.run（steering 参数的消费者就是 run 本身；session 侧
 * steer 接线是后续任务）。构造样板照抄 AgentSessionTest（echo 注册、tools
 * 空表、RunMarkers 注入），RunChain 取 AgentLoopTest 直调形态。
 */
class AgentLoopSteeringTest {

    /**
     * 注入用 RunMarkers（内核清理 A，Task 4）：AgentLoop 参数必填。取生产字面
     * （SkillHost 装配同源），字面等价由 RunMarkerTextsSnapshotTest 快照钉死。
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
     * 脚本回放 LlmClient（复制自 AgentSessionTest，两处独立演化）：按调用次数
     * 回放脚本，每次调用发完整事件骨架（RunStart → 块事件 → TurnEnd → RunEnd；
     * TurnStart 由 AgentLoop 铸造）。脚本 DSL（每行一个指令）：
     * - `[T]正文`：正文块（单 delta 整发）
     * - `[K]思考`：思考块
     * - `[C]query`：一次 echo 工具调用
     * - `[W]ms`：流中暂停（本套件的生命线：流中挂起点，"流中途 offer"的交错
     *   手法靠它——`launch { delay; queue.offer(...) }` 在窗口内入队）
     * - `[!]`：本 turn TurnEnd.truncated=true
     * - `[S]c1|c2|…`：单正文块按 `|` 切多 delta 连发（delta 间 yield）
     * - `[R]n`：n 个 BlockDelta 连发，每个 delta 后 yield
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
                        // yield（非 delay）：burst 内给 collector 真实调度点
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

    /** 构造样板照抄 AgentSessionTest：echo 注册进 registry（session 侧 tools 为空表）。 */
    private fun newLoop(llm: LlmClient): AgentLoop =
        AgentLoop(llm, ToolRegistry().apply { register(EchoTool()) }, markers)

    /** 链构造（AgentLoopTest 直调形态；Unconfined——runBlocking 单线程事件循环下
     *  [W] 的 delay 挂起点即 offerer 的调度点，与 AgentSessionTest 停顿用例同一交错机制）。 */
    private fun chain(id: String) = RunChain(id, CoroutineScope(Dispatchers.Unconfined))

    /** "流中途 offer"：子协程在首 turn [W] 窗口内入队一条插话（返回句柄供 join）。 */
    private fun CoroutineScope.offerMidStream(
        queue: SteeringQueue,
        text: String,
    ): Job = launch {
        delay(120)                       // 流中（首 turn [W]300 挂起窗口内）
        queue.offer(steerMsg(text))
    }

    private fun steerMsg(text: String) =
        AgentMessage(id = "steer-user", role = Role.USER, blocks = listOf(ContentBlock.Text(text)))

    private fun userMsg(id: String, text: String) =
        AgentMessage(id = id, role = Role.USER, blocks = listOf(ContentBlock.Text(text)))

    private fun textOf(m: AgentMessage): String =
        m.blocks.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }

    // ---------- ① 回合顶注入：下一请求上下文含插话行 ----------

    @Test
    fun `回合顶注入 下一请求上下文含插话行`() = runBlocking {
        val llm = ScriptedLlm().apply { script("[T]一\n[W]300\n[C]q1", "[T]二") }
        val queue = SteeringQueue()
        val offerer = offerMidStream(queue, "插话：补充一句")
        val out = newLoop(llm).run(
            input = listOf(userMsg("u1", "问题")), system = "sys", tools = emptyList(),
            hooks = object : AgentHooks {}, chain = chain("steer-1"), mode = ToolMode.NATIVE,
            steering = queue,
        ) { }
        offerer.join()

        assertEquals(2, llm.calls.size)
        // 注入发生在回合顶：第二请求历史末条即插话 user 行
        val last = llm.calls[1].history.last()
        assertEquals(Role.USER, last.role)
        assertTrue(textOf(last).contains("插话：补充一句"))
        // 注入不经 loop 落库：produced 是 session persist 的唯一数据源——
        // user 行不进 produced（session 层 steer 负责落库，本任务只管注入 messages）
        assertTrue(out.produced.none { it.role == Role.USER })
        assertTrue(out.produced.none { textOf(it).contains("插话：补充一句") })
        assertFalse(out.steeringLeftover)                // 已消费：无残留
    }

    // ---------- ② 自然收束被插话顶住 ----------

    @Test
    fun `自然收束被插话顶住`() = runBlocking {
        val llm = ScriptedLlm().apply { script("[T]一\n[W]300\n[T]收", "[T]接住插话再答") }
        val queue = SteeringQueue()
        val offerer = offerMidStream(queue, "插话：再讲讲")
        val out = newLoop(llm).run(
            input = listOf(userMsg("u1", "问题")), system = "sys", tools = emptyList(),
            hooks = object : AgentHooks {}, chain = chain("steer-2"), mode = ToolMode.NATIVE,
            steering = queue,
        ) { }
        offerer.join()

        // 未 break：纯文本自然收束被 peek 顶住，第二回合顶注入后作答
        assertEquals(2, llm.calls.size)
        assertFalse(out.cancelled)
        assertEquals("接住插话再答", textOf(out.produced.last()))
        assertFalse(out.steeringLeftover)
    }

    // ---------- ③ END 被插话顶住 ----------

    @Test
    fun `END被插话顶住`() = runBlocking {
        // finishTurn 仅在工具 turn 后被咨询（AgentLoopTest ② 同款）：
        // turn0 必须有工具调用才能走到 END 分支
        val llm = ScriptedLlm().apply { script("[T]一\n[W]300\n[C]q1", "[T]接住插话再答") }
        val endHooks = object : AgentHooks {
            override fun finishTurn(turn: Int, msgs: List<AgentMessage>) = Decision.END
        }
        val queue = SteeringQueue()
        val offerer = offerMidStream(queue, "插话：还有呢")
        val out = newLoop(llm).run(
            input = listOf(userMsg("u1", "问题")), system = "sys", tools = emptyList(),
            hooks = endHooks, chain = chain("steer-3"), mode = ToolMode.NATIVE,
            steering = queue,
        ) { }
        offerer.join()

        // END 被 peek 顶住：第二回合顶注入后作答，队列排空
        assertEquals(2, llm.calls.size)
        assertEquals("接住插话再答", textOf(out.produced.last()))
        assertFalse(out.steeringLeftover)
    }

    // ---------- ④ 无插话时行为不变（默认参数回归） ----------

    @Test
    fun `无插话时行为不变`() = runBlocking {
        val scripts = arrayOf("[T]一\n[C]q1", "[T]二")
        // 显式空 SteeringQueue
        val llmExplicit = ScriptedLlm().apply { script(*scripts) }
        val outExplicit = newLoop(llmExplicit).run(
            input = listOf(userMsg("u1", "问题")), system = "sys", tools = emptyList(),
            hooks = object : AgentHooks {}, chain = chain("steer-4a"), mode = ToolMode.NATIVE,
            steering = SteeringQueue(),
        ) { }
        // 不传 steering：默认参数（空队列）——既有直调点的零改动回归对照
        val llmDefault = ScriptedLlm().apply { script(*scripts) }
        val outDefault = newLoop(llmDefault).run(
            input = listOf(userMsg("u1", "问题")), system = "sys", tools = emptyList(),
            hooks = object : AgentHooks {}, chain = chain("steer-4b"), mode = ToolMode.NATIVE,
        ) { }

        assertEquals(2, llmDefault.calls.size)
        assertEquals(llmDefault.calls.size, llmExplicit.calls.size)
        assertEquals(outDefault.produced.map(::textOf), outExplicit.produced.map(::textOf))
        assertFalse(outExplicit.cancelled)
        assertFalse(outDefault.cancelled)
        assertFalse(outExplicit.steeringLeftover)
        assertFalse(outDefault.steeringLeftover)
    }

    // ---------- ⑤ 预算尽残留标志 ----------

    @Test
    fun `预算尽残留标志`() = runBlocking {
        val llm = ScriptedLlm().apply { script("[T]一\n[W]300\n[T]收", "[T]不会被执行") }
        val queue = SteeringQueue()
        // offer 走 [W] 窗口（同其它用例）："立刻 offer"会撞 turn0 回合顶 drain
        // 把消息消费掉，leftover 断言翻车
        val offerer = offerMidStream(queue, "插话：被落下")
        val out = newLoop(llm).run(
            input = listOf(userMsg("u1", "问题")), system = "sys", tools = emptyList(),
            hooks = object : AgentHooks {}, chain = chain("steer-5"), mode = ToolMode.NATIVE,
            maxTurns = 1, steering = queue,
        ) { }
        offerer.join()

        // 收束被顶住（turnIndex++ 后越界退出）但预算尽：无第二回合
        assertEquals(1, llm.calls.size)
        assertTrue(out.steeringLeftover)                 // 消息未被消费：残留标志置位
    }
}
