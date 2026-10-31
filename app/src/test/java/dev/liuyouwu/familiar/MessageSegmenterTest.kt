package dev.liuyouwu.familiar

import dev.liuyouwu.familiar.util.persona.MessageSegmenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageSegmenterTest {
    @Test fun `短回复不切`() = assertEquals(listOf("好呀。"), MessageSegmenter.split("好呀。"))
    @Test fun `按句边界均衡切段且拼接无损`() {
        val parts = MessageSegmenter.split("第一句。第二句！第三句？第四句。第五句。")
        assertTrue(parts.size in 2..4)
        assertEquals("第一句。第二句！第三句？第四句。第五句。", parts.joinToString(""))
    }
    @Test fun `无边界长文本不切`() =
        assertEquals(listOf("很长的没有标点的一段话"), MessageSegmenter.split("很长的没有标点的一段话"))
    @Test fun `阅读延迟公式封顶与抵扣`() {
        assertEquals(1060L, MessageSegmenter.delays("嗯", 2, seed = 1)[0])          // 1000+2*30
        assertTrue(MessageSegmenter.delays("x", 1000, 1)[0] <= 6000L)
        assertEquals(0L, MessageSegmenter.delays("x", 100, 1, elapsedMs = 5000)[0]) // 聚合已耗时抵扣
        val d = MessageSegmenter.delays("你好呀。在干嘛。", 5, 42)
        assertTrue(d.drop(1).all { it in 800..2500 })
    }
    @Test fun `同种子确定性`() = assertEquals(
        MessageSegmenter.delays("a。b。", 3, 7), MessageSegmenter.delays("a。b。", 3, 7),
    )
}
