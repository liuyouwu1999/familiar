package dev.liuyouwu.familiar.core.llm.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

/** readLineBounded 三件的回归测试（审计 P2-6；随内核清理 A Task 2 自旧 sse 包迁入本包）。 */
class SseLineReaderTest {

    @Test
    fun `P2-6 readLineBounded throws on oversized line`() {
        val reader = BufferedReader(StringReader("x".repeat(MAX_LINE_CHARS + 1)))
        try {
            readLineBounded(reader)
            throw AssertionError("expected LineTooLongException")
        } catch (e: LineTooLongException) {
            assertTrue(e.message!!.contains("断流"))
        }
    }

    @Test
    fun `readLineBounded handles LF CRLF CR and EOF`() {
        val reader = BufferedReader(StringReader("a\nb\r\nc\rd"))
        assertEquals("a", readLineBounded(reader))
        assertEquals("b", readLineBounded(reader))
        assertEquals("c", readLineBounded(reader))
        assertEquals("d", readLineBounded(reader))
        assertNull(readLineBounded(reader))
    }
}
