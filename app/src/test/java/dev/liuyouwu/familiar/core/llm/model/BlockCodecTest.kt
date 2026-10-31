package dev.liuyouwu.familiar.core.llm.model

import org.junit.Assert.assertEquals
import org.junit.Test

class BlockCodecTest {
    @Test
    fun `roundtrip all block kinds`() {
        val blocks = listOf(
            ContentBlock.Thinking("让我想想"),
            ContentBlock.Text("答案"),
            ContentBlock.ToolCall(id = "c1", name = "web_search", argsJson = """{"query":"JEV"}"""),
            ContentBlock.ToolResult(callId = "c1", name = "web_search", output = "结果", isError = false),
        )
        assertEquals(blocks, BlockCodec.fromJson(BlockCodec.toJson(blocks)))
    }

    @Test
    fun `empty survives`() {
        assertEquals(emptyList<ContentBlock>(), BlockCodec.fromJson("[]"))
        assertEquals("[]", BlockCodec.toJson(emptyList()))
    }
}
