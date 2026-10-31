package dev.liuyouwu.familiar.core.llm.think

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扩展识别集（Task 4）：5 变体标签、Gemma channel 标记、未闭合收尾。
 * Segment 真实字段为 isReasoning（非 isThinking），断言值与任务简报一致。
 */
class ThinkSplitterExtendedTest {

    private fun split(text: String): Pair<String, String> {
        val s = ThinkSplitter()
        val segs = s.feed(text) + s.flush()
        val thinking = segs.filter { it.isReasoning }.joinToString("") { it.text }
        val content = segs.filter { !it.isReasoning }.joinToString("") { it.text }
        return thinking to content
    }

    @Test
    fun `variant tags recognized and paired`() {
        listOf("think", "thinkyle", "thinking", "reasoning", "thought").forEach { tag ->
            val (th, ct) = split("<$tag>推演</$tag>正文")
            assertEquals("tag=$tag", "推演", th)
            assertEquals("tag=$tag", "正文", ct) // 闭标签后正文 trimStart（既有语义）
        }
        val (th2, ct2) = split("<THINK>大写</THINK>正文")
        assertEquals("大写", th2)
        assertEquals("正文", ct2)
    }

    @Test
    fun `gemma channel markers`() {
        val (th, ct) = split("<|channel|>thought分析<|channel|>final答案")
        assertEquals("分析", th)
        assertEquals("答案", ct) // final 标记按闭标签同语义 trimStart
    }

    @Test
    fun `unclosed tag truncates to thinking`() {
        val (th, ct) = split("<think>只有思考没有闭合")
        assertEquals("只有思考没有闭合", th)
        assertEquals("", ct)
    }

    // ===== 交叉错配防护与跨 chunk（配对语义的直接后果，一并锁住） =====

    @Test
    fun `close must pair with its own open tag`() {
        // 开 <thinkyle> 后出现的 </think> 不是它的闭标签，不得提前出思考
        val (th, ct) = split("<thinkyle>推演</think>继续</thinkyle>正文")
        assertEquals("推演</think>继续", th)
        assertEquals("正文", ct)
    }

    @Test
    fun `variant tag split across chunks`() {
        val s = ThinkSplitter()
        val segs = s.feed("<reason") + s.feed("ing>深想") +
            s.feed("</rea") + s.feed("soning>答案") + s.flush()
        val r = segs.filter { it.isReasoning }.joinToString("") { it.text }
        val c = segs.filter { !it.isReasoning }.joinToString("") { it.text }
        assertEquals("深想", r)
        assertEquals("答案", c)
    }

    @Test
    fun `gemma channel markers split across chunks`() {
        val s = ThinkSplitter()
        val segs = s.feed("<|channel|>tho") + s.feed("ught分析<|cha") +
            s.feed("nnel|>final\n\n答案") + s.flush()
        val r = segs.filter { it.isReasoning }.joinToString("") { it.text }
        val c = segs.filter { !it.isReasoning }.joinToString("") { it.text }
        assertEquals("分析", r)
        assertEquals("答案", c)
    }

    @Test
    fun `unclosed variant tag at flush goes to reasoning`() {
        val s = ThinkSplitter()
        val fed = s.feed("<thought>没闭合的思考")
        val all = fed + s.flush()
        val r = all.filter { it.isReasoning }.joinToString("") { it.text }
        val c = all.filter { !it.isReasoning }.joinToString("") { it.text }
        assertEquals("没闭合的思考", r)
        assertTrue(c.isEmpty())
    }

    @Test
    fun `final marker without thought prefix is consumed`() {
        // 有些流直接以 final 开头：标记本身是控制序列，应被吃掉而不是漏进正文
        val (th, ct) = split("<|channel|>final直接答案")
        assertEquals("", th)
        assertEquals("直接答案", ct)
    }
}
