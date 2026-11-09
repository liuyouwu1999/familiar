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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * RunMarkerTexts 字面快照 + RunMarkers 注入用例集（自 A 批迁移至此，字面与历史
 * 常量逐字节同值；InMemoryMessageStore CRUD 用例原样随迁）：
 * - 6 常量以快照字面量逐一 assertEquals（唯一字面来源 viewmodel/RunMarkerTexts，
 *   四常量与 AgentLoop"已取消"/收尾说明历史字面均逐字节同值；
 *   字面量快照防止「注释约定一字不差」式的静默失配，同 util 侧审计 P2-4 的教训）；
 * - MARKER = "\n\n" + TAIL 结构断言两对各自成立；
 * - RunMarkers 注入断言（哨兵字面）：AgentLoop（收尾说明/活路径取消补落）与
 *   AgentSession（截断标记落库+剥历史/无回答说明）读注入值而非内核字面——
 *   "标记文案零内嵌字面"的行为面钉死（内核只认注入的 RunMarkers）；
 * - MessageStore 内存实现钉死 CRUD 语义（Task 14 AgentSession 的存储端口）：
 *   插入保序/会话隔离/deleteTurn 删同 turnId 全部/lastAssistantTurnId 为
 *   regenerateLastTurn 的「最后一组 turnId vs NULL 降级」判据（spec §5.4）/
 *   replaceRange 从锚点行到末尾整段替换（editAndResend 截断重发、compact 压缩）。
 */
class RunMarkerTextsSnapshotTest {

    // ===== RunMarkerTexts 六常量：字面量快照（防线本体）=====

    @Test
    fun `six marker texts keep the migrated literals one by one`() {
        assertEquals("*（回复因长度限制被截断，可点「继续生成」补全）*", RunMarkerTexts.TRUNCATION_TAIL)
        assertEquals("\n\n*（回复因长度限制被截断，可点「继续生成」补全）*", RunMarkerTexts.TRUNCATION_MARKER)
        assertEquals("*（模型只输出了思考、没有写出回答——多为模型或其思考模板的问题，建议换个模型）*", RunMarkerTexts.NO_ANSWER_TAIL)
        assertEquals("\n\n*（模型只输出了思考、没有写出回答——多为模型或其思考模板的问题，建议换个模型）*", RunMarkerTexts.NO_ANSWER_MARKER)
        assertEquals("已取消", RunMarkerTexts.TOOL_CANCELLED)
        assertEquals("（工具预算已尽，本轮未产生回答）", RunMarkerTexts.NO_CLOSING_ANSWER_NOTE)
    }

    @Test
    fun `marker is double newline plus tail`() {
        // 内部一致性：MARKER = "\n\n" + TAIL 两对各自成立
        assertEquals("\n\n" + RunMarkerTexts.TRUNCATION_TAIL, RunMarkerTexts.TRUNCATION_MARKER)
        assertEquals("\n\n" + RunMarkerTexts.NO_ANSWER_TAIL, RunMarkerTexts.NO_ANSWER_MARKER)
    }

    // ===== RunMarkers 注入断言（哨兵字面：内核读注入值，无字面回退）=====

    /** 生产字面 RunMarkers（对照基线；其六字段与上方快照同源同值）。 */
    private val production = RunMarkers(
        truncationMarker = RunMarkerTexts.TRUNCATION_MARKER,
        truncationTail = RunMarkerTexts.TRUNCATION_TAIL,
        noAnswerMarker = RunMarkerTexts.NO_ANSWER_MARKER,
        noAnswerTail = RunMarkerTexts.NO_ANSWER_TAIL,
        toolCancelled = RunMarkerTexts.TOOL_CANCELLED,
        noClosingAnswerNote = RunMarkerTexts.NO_CLOSING_ANSWER_NOTE,
    )

    private class EchoTool : AgentTool {
        override val name = "echo"
        override val description = "回显"
        override val parametersJson = """{"type":"object","properties":{"query":{"type":"string"}}}"""

        override suspend fun execute(args: JsonObject, onProgress: (String) -> Unit): String =
            "echo:" + (args.get("query")?.takeIf { it.isJsonPrimitive }?.asString ?: "")
    }

    /**
     * 两段式脚本 LlmClient：首次调用发一次 echo 工具调用块；其后调用无任何块
     * （收尾 turn 无正文——noClosingAnswerNote 注入断言用，maxTurns=1 强制收尾）。
     */
    private class ClosingScriptLlm : LlmClient {
        val calls = mutableListOf<LlmRequest>()

        override fun stream(req: LlmRequest): Flow<StreamEvent> = flow {
            calls += req
            emit(StreamEvent.RunStart("n-${calls.size}"))
            if (calls.size == 1) {
                val args = """{"query":"q"}"""
                emit(StreamEvent.BlockStart("b0", "toolCall"))
                emit(StreamEvent.ToolCallDelta("b0", "echo", args))
                emit(StreamEvent.BlockEnd("b0"))
                emit(StreamEvent.TurnEnd(listOf(ContentBlock.ToolCall("b0", "echo", args)), false))
            } else {
                emit(StreamEvent.TurnEnd(emptyList(), truncated = false))
            }
            emit(StreamEvent.RunEnd(Usage(promptTokens = 1, completionTokens = 1)))
        }
    }

    /**
     * 会话级脚本 LlmClient（AgentSessionTest ScriptedLlm 的 [T]/[K]/[!] 最小子集）：
     * 截断/无回答标记落库与剥历史的注入断言用。
     */
    private class SessionScriptLlm : LlmClient {
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
                    else -> error("未知脚本行：$line")
                }
            }
            emit(StreamEvent.TurnEnd(emptyList(), truncated))
            emit(StreamEvent.RunEnd(Usage(promptTokens = 10, completionTokens = 5)))
        }
    }

    private fun textOf(m: AgentMessage): String =
        m.blocks.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }

    private fun newSession(store: MessageStore, llm: LlmClient, markers: RunMarkers): AgentSession =
        AgentSession(
            store = store,
            loop = AgentLoop(llm, ToolRegistry(), markers),
            markers = markers,
            hooks = object : AgentHooks {},
            system = { "sys" },
            tools = emptyList(),
            mode = { ToolMode.NATIVE },
            maxContext = { 0 },
            summarizer = { "S[$it]" },
        )

    @Test
    fun `agent loop appends injected no closing answer note`() = runBlocking {
        val sentinel = production.copy(noClosingAnswerNote = "【哨兵】收尾无正文说明")
        val reg = ToolRegistry().apply { register(EchoTool()) }
        val llm = ClosingScriptLlm()

        val out = AgentLoop(llm, reg, sentinel).run(
            input = emptyList(), system = "sys", tools = reg.specs(listOf("echo")),
            hooks = object : AgentHooks {},
            chain = RunChain("c-n1", CoroutineScope(Dispatchers.Unconfined)),
            mode = ToolMode.NATIVE, maxTurns = 1,
        ) { }

        assertFalse(out.cancelled)
        assertEquals(2, llm.calls.size)   // 工具 turn + 收尾 turn
        // 收尾无正文 → 追加的是注入的哨兵说明（非生产字面——注入生效）
        val note = out.produced.last { it.role == Role.ASSISTANT }
        assertEquals(sentinel.noClosingAnswerNote, textOf(note))
    }

    @Test
    fun `agent loop live path backfills injected tool cancelled literal`() = runBlocking {
        val sentinel = production.copy(toolCancelled = "【哨兵】工具已停")
        val chain = RunChain("c-n2", CoroutineScope(Dispatchers.Unconfined))
        val reg = ToolRegistry().apply { register(EchoTool()) }
        // beforeToolCall 内取消链：工具前检查点已过，withContext(chain.scope) 即抛
        // CancellationException → 活路径 catch 补落注入的取消文案（非生产字面）
        val cancelHooks = object : AgentHooks {
            override suspend fun beforeToolCall(call: ContentBlock.ToolCall): Verdict {
                chain.cancel()
                return Verdict.Allow
            }
        }
        val llm = ClosingScriptLlm()

        val out = AgentLoop(llm, reg, sentinel).run(
            input = emptyList(), system = "sys", tools = reg.specs(listOf("echo")),
            hooks = cancelHooks, chain = chain, mode = ToolMode.NATIVE,
        ) { }

        assertTrue(out.cancelled)
        val tr = out.produced.filter { it.role == Role.TOOL_RESULT }
            .flatMap { it.blocks.filterIsInstance<ContentBlock.ToolResult>() }.single()
        assertTrue(tr.isError)
        assertEquals(sentinel.toolCancelled, tr.output)
    }

    @Test
    fun `agent session persists and strips injected truncation marker`() = runBlocking {
        val sentinel = production.copy(
            truncationMarker = "\n\n【哨兵】截断标记",
            truncationTail = "【哨兵】截断标记",
        )
        val store = InMemoryMessageStore()
        val llm = SessionScriptLlm().apply { script("[T]半截\n[!]", "[T]续完") }
        val session = newSession(store, llm, sentinel)

        session.send("c-t1", "问")
        val marked = textOf(store.list("c-t1").last())
        // 落库修饰读注入值：哨兵标记在尾（非生产字面）
        assertTrue(marked.endsWith(sentinel.truncationMarker))
        assertEquals("半截", marked.removeSuffix(sentinel.truncationMarker))

        // 剥标记侧同读注入值：续跑请求历史中哨兵标记已剥
        session.continueRun("c-t1")
        val history = llm.calls[1].history
        assertEquals("半截", textOf(history.last { it.role == Role.ASSISTANT }))
        assertTrue(history.none { textOf(it).contains("哨兵") })
    }

    @Test
    fun `agent session appends injected no answer marker`() = runBlocking {
        val sentinel = production.copy(
            noAnswerMarker = "\n\n【哨兵】只思考无回答说明",
            noAnswerTail = "【哨兵】只思考无回答说明",
        )
        val store = InMemoryMessageStore()
        val llm = SessionScriptLlm().apply { script("[K]推理中") }
        val session = newSession(store, llm, sentinel)

        session.send("c-t2", "问")

        // 整 run 无 Text 有 Thinking → 尾部追加的是注入的哨兵说明
        assertEquals(sentinel.noAnswerMarker, textOf(store.list("c-t2").last()))
    }

    // ===== InMemoryMessageStore：append/list 保序与会话隔离 =====

    @Test
    fun `append and list preserve insertion order across roles`() = runBlocking {
        val store = InMemoryMessageStore()
        val rows = listOf(
            msg("u1", Role.USER),
            msg("a1", Role.ASSISTANT, "run-1:0"),
            msg("r1", Role.TOOL_RESULT, "run-1:0"),
            msg("a2", Role.ASSISTANT, "run-1:1"),
        )
        rows.forEach { store.append("c", it) }
        assertEquals(rows, store.list("c"))
        assertEquals(listOf("u1", "a1", "r1", "a2"), store.list("c").map { it.id })
    }

    @Test
    fun `list isolates conversations and unknown conversation is empty`() = runBlocking {
        val store = InMemoryMessageStore()
        store.append("c1", msg("u1", Role.USER))
        store.append("c2", msg("u2", Role.USER))
        assertEquals(listOf("u1"), store.list("c1").map { it.id })
        assertEquals(listOf("u2"), store.list("c2").map { it.id })
        assertTrue(store.list("missing").isEmpty())
    }

    // ===== deleteTurn：删同 turnId 的全部行（§5.4 重生成删整组）=====

    @Test
    fun `deleteTurn removes every row sharing turnId and only those`() = runBlocking {
        val store = InMemoryMessageStore()
        listOf(
            msg("u1", Role.USER),
            msg("a1", Role.ASSISTANT, "run-1:0"),
            msg("r1", Role.TOOL_RESULT, "run-1:0"),
            msg("a2", Role.ASSISTANT, "run-1:1"),
            msg("r2", Role.TOOL_RESULT, "run-1:1"),
        ).forEach { store.append("c", it) }
        assertEquals(2, store.deleteTurn("c", "run-1:0"))
        assertEquals(listOf("u1", "a2", "r2"), store.list("c").map { it.id })
        // 未知 turnId / 未知会话：0 行、原样不动
        assertEquals(0, store.deleteTurn("c", "run-x:9"))
        assertEquals(0, store.deleteTurn("missing", "run-1:1"))
        assertEquals(listOf("u1", "a2", "r2"), store.list("c").map { it.id })
    }

    // ===== deleteById：NULL turnId 旧数据的降级删单行（§5.4）=====

    @Test
    fun `deleteById removes exactly one row and reports unknown id`() = runBlocking {
        val store = InMemoryMessageStore()
        listOf(
            msg("u1", Role.USER),
            msg("a1", Role.ASSISTANT),
            msg("a2", Role.ASSISTANT),
        ).forEach { store.append("c", it) }
        assertTrue(store.deleteById("c", "a1"))
        assertEquals(listOf("u1", "a2"), store.list("c").map { it.id })
        assertFalse(store.deleteById("c", "nope"))
        assertFalse(store.deleteById("missing", "a2"))
        assertEquals(listOf("u1", "a2"), store.list("c").map { it.id })
    }

    // ===== lastAssistantTurnId：regenerateLastTurn 的判据 =====

    @Test
    fun `lastAssistantTurnId returns turnId of last assistant row`() = runBlocking {
        val store = InMemoryMessageStore()
        listOf(
            msg("u1", Role.USER),
            msg("a1", Role.ASSISTANT, "run-1:0"),
            msg("r1", Role.TOOL_RESULT, "run-1:0"),
            msg("a2", Role.ASSISTANT, "run-1:1"),
            msg("r2", Role.TOOL_RESULT, "run-1:1"),
        ).forEach { store.append("c", it) }
        assertEquals("run-1:1", store.lastAssistantTurnId("c"))
    }

    @Test
    fun `lastAssistantTurnId sees through trailing tool rows after last assistant`() = runBlocking {
        // 取消形态：assistant 带 ToolCall 后被补落「已取消」TOOL_RESULT 收尾——
        // 最后组仍指向该 assistant 的 turnId（删整组即连孤儿工具行一起删）
        val store = InMemoryMessageStore()
        listOf(
            msg("u1", Role.USER),
            msg("a1", Role.ASSISTANT, "run-1:2"),
            msg("r1", Role.TOOL_RESULT, "run-1:2"),
        ).forEach { store.append("c", it) }
        assertEquals("run-1:2", store.lastAssistantTurnId("c"))
    }

    @Test
    fun `lastAssistantTurnId null when no assistant or legacy null turnId`() = runBlocking {
        val store = InMemoryMessageStore()
        assertNull(store.lastAssistantTurnId("missing"))            // 空会话
        store.append("c", msg("u1", Role.USER))
        assertNull(store.lastAssistantTurnId("c"))                  // 无 assistant
        store.append("c", msg("a1", Role.ASSISTANT))                // 旧数据 turnId=NULL → 降级
        assertNull(store.lastAssistantTurnId("c"))
    }

    // ===== replaceRange：从锚点行（含）到末尾整段替换 =====

    @Test
    fun `replaceRange swaps from anchor row to end with replacement list`() = runBlocking {
        val store = InMemoryMessageStore()
        listOf(
            msg("u1", Role.USER),
            msg("a1", Role.ASSISTANT, "run-1:0"),
            msg("a2", Role.ASSISTANT, "run-1:1"),
            msg("r2", Role.TOOL_RESULT, "run-1:1"),
        ).forEach { store.append("c", it) }
        val replacement = listOf(
            msg("n1", Role.USER),
            msg("n2", Role.ASSISTANT, "run-9:0"),
        )
        store.replaceRange("c", "a1", replacement)
        assertEquals(listOf("u1", "n1", "n2"), store.list("c").map { it.id })
        assertEquals(replacement[1], store.list("c").last())
    }

    @Test
    fun `replaceRange with empty replacement truncates from anchor`() = runBlocking {
        val store = InMemoryMessageStore()
        listOf(
            msg("u1", Role.USER),
            msg("a1", Role.ASSISTANT, "run-1:0"),
            msg("a2", Role.ASSISTANT, "run-1:1"),
        ).forEach { store.append("c", it) }
        store.replaceRange("c", "a2", emptyList())
        assertEquals(listOf("u1", "a1"), store.list("c").map { it.id })
    }

    @Test
    fun `replaceRange unknown anchor fails fast without mutation`() = runBlocking {
        val store = InMemoryMessageStore()
        store.append("c", msg("u1", Role.USER))
        val before = store.list("c")
        try {
            store.replaceRange("c", "nope", listOf(msg("n1", Role.USER)))
            fail("锚点不存在必须抛 IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(before, store.list("c"))
        try {
            store.replaceRange("missing", "u1", emptyList())
            fail("会话不存在同样抛 IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun msg(id: String, role: Role, turnId: String? = null) = AgentMessage(
        id = id, role = role,
        blocks = listOf(ContentBlock.Text("text-$id")),
        turnId = turnId,
    )
}
