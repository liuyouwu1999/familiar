package dev.liuyouwu.familiar.core.llm.client

import org.junit.Assert.*
import org.junit.Test

class ToolCallAccumulatorTest {
    @Test
    fun `single call args arrive in fragments`() {
        val acc = ToolCallAccumulator()
        acc.onDelta(0, "call_1", "web_search", """{"que""")
        acc.onDelta(0, null, null, """ry":"JEV"}""")
        val calls = acc.finish()
        assertEquals(1, calls.size)
        assertEquals("call_1", calls[0].id)
        assertEquals("web_search", calls[0].name)
        assertEquals("""{"query":"JEV"}""", calls[0].argsJson)
        assertTrue(calls[0].argsValid)
    }

    @Test
    fun `two interleaved calls keyed by index`() {
        val acc = ToolCallAccumulator()
        acc.onDelta(0, "a", "web_search", """{"q""")
        acc.onDelta(1, "b", "get_time", "{}")
        acc.onDelta(0, null, null, """":1}""")
        val calls = acc.finish()
        assertEquals(listOf("a", "b"), calls.map { it.id })
        assertTrue(calls.all { it.argsValid })
        assertEquals("""{"q":1}""", calls[0].argsJson)
    }

    @Test
    fun `no index falls back to id key`() {
        val acc = ToolCallAccumulator()
        acc.onDelta(null, "x1", "t1", """{"a""")
        acc.onDelta(null, "x1", null, """:1}""")
        assertEquals(1, acc.finish().size)
    }

    @Test
    fun `invalid json args marked not valid`() {
        val acc = ToolCallAccumulator()
        acc.onDelta(0, "bad", "web_search", """{not-json""")
        assertFalse(acc.finish()[0].argsValid)
    }

    @Test
    fun `empty name defaults to empty string`() {
        val acc = ToolCallAccumulator()
        acc.onDelta(0, "c", null, "{}")
        assertEquals("", acc.finish()[0].name)
    }

    @Test
    fun `delta tool_calls gson binding`() {
        // 评审二 P0-1 防线：无 @SerializedName 时该测试必红（toolCalls 恒 null）
        val delta = com.google.gson.Gson().fromJson(
            """{"tool_calls":[{"index":0,"id":"c1","function":{"name":"web_search","arguments":"{}"}}]}""",
            dev.liuyouwu.familiar.core.llm.wire.Delta::class.java)
        assertNotNull(delta.toolCalls)
        assertEquals("c1", delta.toolCalls!![0].id)
        assertEquals("web_search", delta.toolCalls!![0].function!!.name)
    }
}
