package dev.liuyouwu.familiar.agent

import com.google.gson.JsonObject
import dev.liuyouwu.familiar.core.llm.client.LlmClient
import dev.liuyouwu.familiar.core.llm.client.LlmRequest
import dev.liuyouwu.familiar.core.llm.model.AgentMessage
import dev.liuyouwu.familiar.core.llm.model.ContentBlock
import dev.liuyouwu.familiar.core.llm.model.StreamEvent
import dev.liuyouwu.familiar.core.llm.model.Usage
import dev.liuyouwu.familiar.core.llm.wire.ToolMode
import dev.liuyouwu.familiar.tools.AgentTool
import dev.liuyouwu.familiar.tools.ToolRegistry
import dev.liuyouwu.familiar.viewmodel.RunMarkerTexts
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * followUpRun 旁路协程异常纪律（审计 R1，任务 1.1）：chain.scope.launch
 * { executeRun } 是无人 await 的旁路协程——run 内落库失败（persistProduced 的
 * store.append 抛，executeRun 不捕、KDoc 钉死"异常逃逸调用方"）会作为未捕获
 * 异常逃逸到链 scope 的全局 handler（进程级风险）。契约（Step 3 清单 2）：
 * 失败不崩、不外抛，错误进 events.lastError（UI 可见错误通道）。
 *
 * 夹具照抄 AgentSessionTest（markers/ScriptedLlm/EchoTool/最小装配 :167-198）；
 * 差异仅 store：包一层让 [failNow] 置位后 append 抛 [IOException]（模拟磁盘满）。
 * 失败注入时机：RecordingHooks.onRunEnd 里置 failNow=true——run 1 的全部 append
 * 先于 onRunEnd 完成，注入点不耦合 run 内 append 次数（比计数稳）。
 */
class AgentSessionFollowUpFailureTest {

    /** 注入用 RunMarkers（AgentSessionTest 同款：生产字面，快照钉死等价）。 */
    private val markers = RunMarkers(
        truncationMarker = RunMarkerTexts.TRUNCATION_MARKER,
        truncationTail = RunMarkerTexts.TRUNCATION_TAIL,
        noAnswerMarker = RunMarkerTexts.NO_ANSWER_MARKER,
        noAnswerTail = RunMarkerTexts.NO_ANSWER_TAIL,
        toolCancelled = RunMarkerTexts.TOOL_CANCELLED,
        noClosingAnswerNote = RunMarkerTexts.NO_CLOSING_ANSWER_NOTE,
    )

    /** 脚本回放 LlmClient（AgentSessionTest 同款最小 DSL：[T]/[K]/[C]/[W]/[!]）。 */
    private class ScriptedLlm : LlmClient {
        val calls = CopyOnWriteArrayList<LlmRequest>()
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
                    else -> error("未知脚本行：$line")
                }
            }
            emit(StreamEvent.TurnEnd(toolCalls.toList(), truncated))
            emit(StreamEvent.RunEnd(Usage(promptTokens = 10, completionTokens = 5)))
        }
    }

    /** 回显工具（AgentSessionTest 同款；本测试脚本不触发，仅装配位照抄）。 */
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

    /** 注入时机的 hooks：run 1 收线（onRunEnd）置 failNow——其后所有 append 失败。 */
    private class RecordingHooks(private val failNow: AtomicBoolean) : AgentHooks {
        override fun onRunEnd(result: RunResult, chain: RunChain) {
            failNow.set(true)
        }
    }

    /** 轮询 helper：超时返回（断言随后失败），不抛 TimeoutException。 */
    private suspend fun awaitUntil(timeoutMs: Long, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) delay(25)
    }

    @Test
    fun `followUp run 落库失败不崩溃且置 lastError`() = runBlocking {
        val base = InMemoryMessageStore()
        val failNow = AtomicBoolean(false)
        val store = object : MessageStore by base {
            override suspend fun append(conversationId: String, message: AgentMessage): List<Long> {
                if (failNow.get()) throw IOException("disk full")
                return base.append(conversationId, message)
            }
        }
        val llm = ScriptedLlm().apply { script("[T]答一", "[T]答二") }
        // 最小装配照抄 AgentSessionTest :167-198（store 换上面包装）
        val session = AgentSession(
            store = store,
            loop = AgentLoop(llm, ToolRegistry().apply { register(EchoTool()) }, markers),
            markers = markers,
            hooks = RecordingHooks(failNow),
            system = { "sys" },
            tools = emptyList(),
            mode = { ToolMode.NATIVE },
            maxContext = { 0 },
            summarizer = { "S[" + it + "]" },
        )
        // 先 send 一轮建立历史；run 1 收线时 onRunEnd 置 failNow=true
        session.send("c1", "hello")
        // followUp 直接调（旁路被测路径：chain.scope.launch { executeRun }，AgentSession.followUpRun）
        session.followUpRun("c1")
        // followUp 落库失败：不抛、不崩；events.lastError 非空
        awaitUntil(5_000) { session.events.value.lastError != null }
        assertNotNull("followUp run 落库失败应置 lastError 而非异常逃逸", session.events.value.lastError)
    }
}
