package dev.liuyouwu.familiar.core.llm.probe

import dev.liuyouwu.familiar.core.llm.client.SseRawEvent
import dev.liuyouwu.familiar.core.llm.client.SseSource
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * HttpProbeTransport 两步探测（P5 T2，spec §4.4 第 3 层）的接缝测试：
 * SseSource 注入假件（okHttpSseSource 的接缝同形），请求经共享的
 * chatCompletionsHttpRequest 构建后可整体验断（不发任何网络）。
 */
class HttpProbeTransportTest {

    /** 记录请求 + 按调用序回放脚本的假 SseSource：每脚本可带收尾异常（失败路径） */
    private class FakeSseSource : SseSource {
        val requests = mutableListOf<Request>()
        private val scripts = ArrayDeque<List<SseRawEvent>>()
        private val failures = ArrayDeque<Throwable?>()

        fun script(events: List<SseRawEvent> = emptyList(), failure: Throwable? = null) {
            scripts += events
            failures += failure
        }

        override fun open(request: Request) = flow {
            requests += request
            val events = scripts.removeFirstOrNull() ?: error("第 ${requests.size} 次打开无脚本")
            failures.removeFirstOrNull()?.let { throw it }
            for (ev in events) emit(ev)
        }
    }

    /** /api/show 执行缝假件：记录请求、返回固定 (code, body) */
    private class FakePlainHttp(
        var code: Int = 404,
        var body: String = "{}",
    ) : HttpProbeTransport.PlainHttp {
        val requests = mutableListOf<Request>()
        override suspend fun call(request: Request): HttpProbeTransport.PlainResponse {
            requests += request
            return HttpProbeTransport.PlainResponse(code, body)
        }
    }

    private fun transport(sse: FakeSseSource, plain: FakePlainHttp = FakePlainHttp()) =
        HttpProbeTransport(
            model = "qwen3:32b",
            sseSource = sse,
            baseUrl = { "http://192.168.1.5:11434" },
            apiKey = { "sk-test" },
            plainHttp = plain,
        )

    /** 探测判定经 CapabilityProber.probeOnce 全链走（transport 注入本传输） */
    private fun prober(sse: FakeSseSource) =
        CapabilityProber(ModelCatalog(emptyList()), transport(sse), InMemoryCapabilityCache())

    private fun bodyOf(request: Request): String {
        val buffer = Buffer()
        requireNotNull(request.body).writeTo(buffer)
        return buffer.readUtf8()
    }

    /** 标准 tool_calls 增量（OpenAI 流式形：id/name 随首个 delta，arguments 分片可空） */
    private fun toolCallDelta(id: String = "call_1") =
        """[{"index":0,"id":"$id","type":"function","function":{"name":"get_current_time","arguments":"{}"}}]"""

    // ===== 第一步请求规格（spec §4.4：工具 + 指令 + 512）=====

    @Test
    fun `first step request carries probe tool prompt and max tokens`() = runBlocking {
        val sse = FakeSseSource()
        sse.script(listOf(SseRawEvent(null, null, null, toolCallDelta(), "tool_calls")))
        transport(sse).roundTrip(ProbeStep.WITH_TOOLS)

        val req = sse.requests.single()
        assertEquals("POST", req.method)
        assertEquals("http://192.168.1.5:11434/v1/chat/completions", req.url.toString())
        assertEquals("Bearer sk-test", req.header("Authorization"))
        val body = bodyOf(req)
        assertTrue("模型名入请求体", body.contains(""""model":"qwen3:32b""""))
        assertTrue("探测工具 get_current_time 必须声明", body.contains("get_current_time"))
        assertTrue(
            "探测指令文案（spec §4.4 字面）",
            body.contains("现在几点？请直接调用工具查询，不要先解释或思考"),
        )
        assertTrue("max_tokens=512（评审 C3）", body.contains(""""max_tokens":512"""))
    }

    // ===== 两步判定：ToolCalled → role:"tool" 回传 → NATIVE =====

    @Test
    fun `tool call then ok followup maps to native and echoes call id`() = runBlocking {
        val sse = FakeSseSource()
        sse.script(listOf(SseRawEvent(null, null, null, toolCallDelta("call_9"), "tool_calls")))
        sse.script(listOf(SseRawEvent("现在是 14:32。", null, null, null, "stop")))
        val verdict = prober(sse).probeOnce("ollama", "http://192.168.1.5:11434", "qwen3:32b")
        assertEquals(ToolCapability.NATIVE, verdict)
        assertEquals(2, sse.requests.size)

        val followUp = bodyOf(sse.requests[1])
        // 第一步调用以 assistant.tool_calls 入历史（id 对位）
        assertTrue("assistant 发起形入历史", followUp.contains(""""role":"assistant""""))
        assertTrue("调用 id 原样对位", followUp.contains("call_9"))
        // 工具结果以 role:"tool" + tool_call_id 回传（spec 评审 C2）
        assertTrue("role:tool 应答形", followUp.contains(""""role":"tool""""))
        assertTrue("tool_call_id 对位回传", followUp.contains(""""tool_call_id":"call_9""""))
    }

    @Test
    fun `tool call id split across deltas accumulates`() = runBlocking {
        val sse = FakeSseSource()
        sse.script(
            listOf(
                // 首增量只带 id、次增量补 arguments——id 不得丢
                SseRawEvent(null, null, null, """[{"index":0,"id":"call_7","function":{"name":"get_current_time"}}]""", null),
                SseRawEvent(null, null, null, """[{"index":0,"function":{"arguments":"{}"}}]""", "tool_calls"),
            )
        )
        sse.script(listOf(SseRawEvent("好的，现在 14:32。", null, null, null, "stop")))
        val verdict = prober(sse).probeOnce("ollama", "http://192.168.1.5:11434", "qwen3:32b")
        assertEquals(ToolCapability.NATIVE, verdict)
        assertTrue("累积出的 id 用于第二步对位", bodyOf(sse.requests[1]).contains("call_7"))
    }

    // ===== 单步定论：200 无调用 → MARKER（不发第二步）=====

    @Test
    fun `first step plain answer maps to marker without second request`() = runBlocking {
        val sse = FakeSseSource()
        sse.script(listOf(SseRawEvent("现在是下午三点左右。", null, null, null, "stop")))
        val verdict = prober(sse).probeOnce("ollama", "http://192.168.1.5:11434", "qwen3:32b")
        assertEquals(ToolCapability.MARKER, verdict)
        assertEquals("第一步已定论，第二步不发", 1, sse.requests.size)
    }

    // ===== HttpError 映射：okHttpSseSource 失败路径的两种报文形 =====

    @Test
    fun `http error mentioning tools maps to marker`() = runBlocking {
        val sse = FakeSseSource()
        // okHttpSseSource 非 2xx 形一："HTTP <code>: <apiError 提取>"
        sse.script(failure = RuntimeException("HTTP 400: 'tools' is not supported by this model"))
        val verdict = prober(sse).probeOnce("ollama", "http://192.168.1.5:11434", "qwen3:32b")
        assertEquals(ToolCapability.MARKER, verdict)
    }

    @Test
    fun `plain 4xx without tools wording on followup maps to marker`() = runBlocking {
        val sse = FakeSseSource()
        sse.script(listOf(SseRawEvent(null, null, null, toolCallDelta("c1"), "tool_calls")))
        // 形二（ApiErrorHint.fromHttpStatus）：代码在括号里、文案不提 tools——
        // role:"tool" 回传被 4xx 定式拒绝即原生回路不通（spec 评审 C2）
        sse.script(failure = RuntimeException("请求被服务器拒绝 (400)\n服务器返回：invalid message role"))
        val verdict = prober(sse).probeOnce("ollama", "http://192.168.1.5:11434", "qwen3:32b")
        assertEquals(ToolCapability.MARKER, verdict)
    }

    // ===== 瞬时映射：网络错误 / 5xx → ProbeTransientException（不写缓存的前置）=====

    @Test
    fun `io exception maps to transient`() = runBlocking {
        val sse = FakeSseSource()
        sse.script(failure = IOException("connect timeout"))
        try {
            prober(sse).probeOnce("ollama", "http://192.168.1.5:11434", "qwen3:32b")
            org.junit.Assert.fail("expected ProbeTransientException")
        } catch (e: ProbeTransientException) {
            assertEquals("connect timeout", e.cause?.message)
        }
    }

    @Test
    fun `five hundred status maps to transient`() = runBlocking {
        val sse = FakeSseSource()
        sse.script(failure = RuntimeException("服务器内部错误 (500)：看后端日志"))
        try {
            prober(sse).probeOnce("ollama", "http://192.168.1.5:11434", "qwen3:32b")
            org.junit.Assert.fail("expected ProbeTransientException")
        } catch (_: ProbeTransientException) {
        }
    }

    // ===== 第 2 层服务端元数据：Ollama /api/show =====

    @Test
    fun `ollama capabilities parses show response`() = runBlocking {
        val sse = FakeSseSource()
        val plain = FakePlainHttp(200, """{"capabilities":["completion","tools"]}""")
        val caps = transport(sse, plain).ollamaCapabilities("http://192.168.1.5:11434")
        assertEquals(setOf("completion", "tools"), caps)
        val req = plain.requests.single()
        assertEquals("http://192.168.1.5:11434/api/show", req.url.toString())
        assertTrue("模型名入 show 请求体", bodyOf(req).contains("qwen3:32b"))
    }

    @Test
    fun `ollama capabilities returns null on non 200 and on bad body`() = runBlocking {
        val sse = FakeSseSource()
        assertEquals(null, transport(sse, FakePlainHttp(404, "{}")).ollamaCapabilities("http://192.168.1.5:11434"))
        assertEquals(null, transport(sse, FakePlainHttp(200, "not json")).ollamaCapabilities("http://192.168.1.5:11434"))
        assertNull(transport(sse, FakePlainHttp(200, """{"capabilities":null}""")).ollamaCapabilities("http://x"))
    }
}
