package dev.liuyouwu.familiar.core.llm.wire

import com.google.gson.Gson
import dev.liuyouwu.familiar.core.llm.client.DefaultLlmClient
import dev.liuyouwu.familiar.core.llm.client.Deps
import dev.liuyouwu.familiar.core.llm.client.LlmRequest
import dev.liuyouwu.familiar.core.llm.client.SseRawEvent
import dev.liuyouwu.familiar.core.llm.marker.MarkerToolAdapter
import dev.liuyouwu.familiar.core.llm.model.StreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** L1 wire 契约：penalty 字段 null 省略（宁缺勿滥）、非 null 上 wire 且键名 snake_case。 */
class ChatRequestPenaltyTest {

    private val gson = Gson()   // 与 DefaultLlmClient 同款默认实例：不序列化 null

    private fun request(f: Double?, p: Double?): String = gson.toJson(
        ChatRequest(
            model = "m", messages = listOf(MessageDto.TextMessage("user", "hi")),
            temperature = null, frequencyPenalty = f, presencePenalty = p,
        )
    )

    @Test
    fun `penalties non-null serialize with snake_case keys`() {
        val json = request(0.9, 0.3)
        assertTrue(json.contains("\"frequency_penalty\":0.9"))
        assertTrue(json.contains("\"presence_penalty\":0.3"))
    }

    @Test
    fun `penalties null are omitted`() {
        val json = request(null, null)
        assertFalse(json.contains("frequency_penalty"))
        assertFalse(json.contains("presence_penalty"))
    }

    // ===== L1 透传：LlmRequest 的 penalty 到达 wire（Task 2）=====

    @Test
    fun `buildChatRequest carries penalties from LlmRequest`() {
        val client = client(flowOf())   // fixture 同 LlmClientNormalizeTest.client(...)
        val req = LlmRequest(
            model = "m", system = "s", history = emptyList(),
            tools = emptyList(), mode = ToolMode.NATIVE, temperature = null,
            maxTokens = null, searchAllowed = false, budgetLeft = { true },
            frequencyPenalty = 0.4, presencePenalty = 0.3,
        )
        val json = gson.toJson(client.buildChatRequest(req))
        assertTrue(json.contains("\"frequency_penalty\":0.4"))
        assertTrue(json.contains("\"presence_penalty\":0.3"))
    }

    // ===== L1 降级：400 penalty 拒收逐参剥除重发（Task 2，spec §3.7）=====

    @Test
    fun `penalty rejection strips one penalty per retry until accepted`() {
        // 三段脚本：双惩罚 400 → 仅 frequency 400 → 全无成功
        var opens = 0
        val sources = listOf(
            flow { throw RuntimeException("HTTP 400: 'presence_penalty' is not supported") },
            flow { throw RuntimeException("HTTP 400: 'frequency_penalty' is not supported") },
            flowOf(SseRawEvent(content = "好", reasoningContent = null, reasoning = null,
                toolCallsJson = null, finishReason = "stop")),
        )
        val client = clientMulti { opens++; sources[opens - 1] }   // fixture 照抄 client(...)：open 第 n 次返回 sources[n-1]
        val req = LlmRequest(
            model = "m", system = "s", history = emptyList(),
            tools = emptyList(), mode = ToolMode.NATIVE, temperature = null,
            maxTokens = null, searchAllowed = false, budgetLeft = { true },
            frequencyPenalty = 0.4, presencePenalty = 0.3,
        )
        val events = runBlocking { client.stream(req).toList() }
        assertTrue(events.any { it is StreamEvent.RunEnd })
        assertEquals(3, opens)   // 断言 open 计数器（非列表长度——审查 P1-9）
    }

    @Test
    fun `non-penalty errors do not retry`() {
        var opens = 0
        val client = clientMulti { opens++; flow { throw RuntimeException("connection reset") } }
        val events = runBlocking { client.stream(reqWithPenalties()).toList() }
        assertEquals(1, opens)
        assertTrue(events.any { it is StreamEvent.Error })
    }

    @Test
    fun `plain 400 without penalty mention does not retry`() {
        // B2 台账收紧：宽松匹配任意 "400" 会让无关 4xx 空转重发——剥参判定只认
        // penalty 字段名（penalty 拒收的报错文案必含字段名）
        var opens = 0
        val client = clientMulti { opens++; flow { throw RuntimeException("HTTP 400: invalid request") } }
        val events = runBlocking { client.stream(reqWithPenalties()).toList() }
        assertEquals(1, opens)
        assertTrue(events.any { it is StreamEvent.Error })
    }

    /** 带双惩罚的 LlmRequest 小辅助（brief Step 4：non-penalty 用例共用）。 */
    private fun reqWithPenalties(): LlmRequest = LlmRequest(
        model = "m", system = "s", history = emptyList(),
        tools = emptyList(), mode = ToolMode.NATIVE, temperature = null,
        maxTokens = null, searchAllowed = false, budgetLeft = { true },
        frequencyPenalty = 0.4, presencePenalty = 0.3,
    )

    /** clientMulti 照抄 client(...)（:392）改造：SseSource 用计数闭包按次返回流。 */
    private fun clientMulti(source: () -> Flow<SseRawEvent>): DefaultLlmClient = DefaultLlmClient(
        Deps(
            okHttp = OkHttpClient(),
            sseSource = { _ -> source() },
            baseUrl = { "" },
            apiKey = { "" },
            provider = { "" },
        ),
        WireConverter(),
        MarkerToolAdapter(),
    )

    /** fixture 照抄 LlmClientNormalizeTest.client(...)（:392）：scripted SseSource 注入，不触网。 */
    private fun client(scripted: Flow<SseRawEvent>): DefaultLlmClient = DefaultLlmClient(
        Deps(
            okHttp = OkHttpClient(),
            sseSource = { _ -> scripted },
            baseUrl = { "" },
            apiKey = { "" },
            provider = { "" },
        ),
        WireConverter(),
        MarkerToolAdapter(),
    )
}
