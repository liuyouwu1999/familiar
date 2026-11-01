// ReviewResultParserTest.kt
package dev.liuyouwu.familiar.skills.profile

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 复习收尾 JSON 宽容解析（学习画像 Task 10 Step 1，spec §6）：坏条目逐个丢弃、
 * 整体不可解析回空列表（空列表 = VM 不落账任何条目——幂等红线）。
 */
class ReviewResultParserTest {

    @Test fun `合法数组全量解析`() {
        val raw = """[{"entryId":1,"pass":true},{"entryId":2,"pass":false}]"""
        assertEquals(listOf(1L to true, 2L to false), ReviewResultParser.parse(raw))
    }

    @Test fun `围栏与前后噪声不影响解析`() {
        val raw = """
            好的，本次复习到这里！
            ```json
            [{"entryId":7,"pass":false}]
            ```
        """.trimIndent()
        assertEquals(listOf(7L to false), ReviewResultParser.parse(raw))
    }

    @Test fun `缺字段条目丢弃 其余保留`() {
        val raw = """[{"entryId":1},{"entryId":2,"pass":true},{"pass":false},{"entryId":4,"pass":true}]"""
        assertEquals(listOf(2L to true, 4L to true), ReviewResultParser.parse(raw))
    }

    @Test fun `id非正数丢弃`() {
        val raw = """[{"entryId":0,"pass":true},{"entryId":-3,"pass":false},{"entryId":5,"pass":true}]"""
        assertEquals(listOf(5L to true), ReviewResultParser.parse(raw))
    }

    @Test fun `非JSON回空列表`() {
        assertEquals(emptyList<Pair<Long, Boolean>>(), ReviewResultParser.parse("今天天气不错"))
        assertEquals(emptyList<Pair<Long, Boolean>>(), ReviewResultParser.parse(""))
    }
}
