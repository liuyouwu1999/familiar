package dev.liuyouwu.familiar.data.remote.api

import dev.liuyouwu.familiar.core.llm.client.MAX_LINE_CHARS
import dev.liuyouwu.familiar.core.llm.client.readLineBounded
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 探针 SSE 行处理防线（A 终审 Important + B2 台账）：handleEventLines 提 internal 后
 * 直测"行输入→事件输出"，不起真网络（Call 仅作异常路径取消检查的惰性对象，从不执行）。
 * 钉住的体检不变量：
 * - 携带 API 错误的 data 行只映射 [ProbeEvent.Error]、不发 [ProbeEvent.Delta]
 *   （错误后端体检必须 FAIL——Delta 先行会翻成 PASS）；
 * - [DONE] 尾随空格容忍、CRLF 行尾、超长行（> MAX_LINE_CHARS）报错而非挂死；
 * - Delta 仅在 preview 非空白时发射（B2 台账：usage-only/role-only 空载荷行不再
 *   伪造"流已建立"空 Delta；usage 照发）。
 */
class ConnectionProbeHandleLinesTest {

    private val probe = ConnectionProbe(mutableSetOf())

    // 未执行的 Call：handleEventLines 只在异常路径读 isCanceled()，构造不触网
    private val call = OkHttpClient().newCall(Request.Builder().url("http://probe.invalid/v1").build())

    /** 行列表直灌 handleEventLines（null = EOF），返回 (事件序列, finish 是否回调) */
    private fun run(vararg lines: String?): Pair<List<ProbeEvent>, Boolean> {
        val events = mutableListOf<ProbeEvent>()
        var finished = false
        probe.handleEventLines(call, lines.iterator(), { events += it }, { finished = true })
        return events to finished
    }

    /** 原始报文经 readLineBounded 逐行喂入（覆盖 CRLF/超长行的真实读取路径） */
    private fun viaReader(raw: String): Pair<List<ProbeEvent>, Boolean> {
        val events = mutableListOf<ProbeEvent>()
        var finished = false
        val reader = raw.byteInputStream().bufferedReader()
        reader.use { r ->
            probe.handleEventLines(
                call,
                generateSequence { readLineBounded(r) }.iterator(),
                { events += it }, { finished = true },
            )
        }
        return events to finished
    }

    @Test
    fun `error data line emits only Error without Delta`() {
        // 服务端脚本：stream_options 被拒（HTTP 400 错误体以 data 行下发时）——
        // 事件序列只含 Error、无 Delta（体检 FAIL 保序不变量）
        val (events, finished) = run(
            """data: {"error":{"message":"'stream_options' is not supported","type":"invalid_request_error"}}""",
            null,
        )
        assertEquals(
            listOf<ProbeEvent>(ProbeEvent.Error("'stream_options' is not supported")),
            events,
        )
        assertTrue(finished)
    }

    @Test
    fun `done line with trailing space still finishes`() {
        // SSE 规范冒号后空格可选、载荷尾随空格容忍："data: [DONE] " 仍正常收尾、不报错
        val (events, finished) = run("data: [DONE] ", null)
        assertTrue(finished)
        assertTrue(events.none { it is ProbeEvent.Error })
        assertTrue(events.none { it is ProbeEvent.Delta })   // 空 preview 不发 Delta（B2 台账）
    }

    @Test
    fun `crlf line endings parse normally`() {
        val (events, finished) = viaReader(
            "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\r\ndata: [DONE]\r\n",
        )
        assertEquals(listOf<ProbeEvent>(ProbeEvent.Delta("你好")), events)
        assertTrue(finished)
    }

    @Test
    fun `overlong line yields Error event instead of hanging`() {
        val giant = "data: " + "x".repeat(MAX_LINE_CHARS + 1)
        val (events, finished) = viaReader(giant)
        val errors = events.filterIsInstance<ProbeEvent.Error>()
        assertEquals(1, errors.size)
        assertTrue(errors.single().message.contains("断流"))
        assertTrue(finished)
        assertTrue(events.none { it is ProbeEvent.Delta })
    }

    @Test
    fun `usage only line emits Usage without empty Delta`() {
        // B2 台账：usage-only 行不再发空 Delta（usage 照发）——空 Delta 曾伪造"流已建立"
        val (events, finished) = run(
            """data: {"usage":{"prompt_tokens":5,"completion_tokens":2}}""",
            null,
        )
        assertEquals(
            listOf<ProbeEvent>(ProbeEvent.Usage(promptTokens = 5, completionTokens = 2)),
            events,
        )
        assertTrue(finished)
    }

    @Test
    fun `role only first chunk defers delta to first non blank payload`() {
        // 同一不变量的流内形态：role-only 空载荷首块不发空 Delta，首个非空载荷才发
        val (events, finished) = run(
            """data: {"choices":[{"delta":{"role":"assistant","content":""}}]}""",
            """data: {"choices":[{"delta":{"content":"在"}}]}""",
            null,
        )
        assertEquals(listOf<ProbeEvent>(ProbeEvent.Delta("在")), events)
        assertTrue(finished)
    }
}
