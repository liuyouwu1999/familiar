package dev.liuyouwu.familiar.core.llm.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamEventTest {
    @Test
    fun `event family shapes`() {
        assertEquals("r1", StreamEvent.RunStart("r1").runId)
        assertTrue(StreamEvent.TurnEnd(emptyList(), truncated = true).truncated)
        assertEquals("""{"q""", StreamEvent.ToolCallDelta("b1", name = null, argsFragment = """{"q""").argsFragment)
        assertEquals(Usage(1, 2, 3), Usage(1, 2, 3))
        assertTrue(StreamEvent.Error(RuntimeException("x")).error is RuntimeException)
    }
}
