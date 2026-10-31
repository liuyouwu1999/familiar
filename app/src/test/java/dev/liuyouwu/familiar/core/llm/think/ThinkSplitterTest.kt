package dev.liuyouwu.familiar.core.llm.think

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkSplitterTest {

    private fun content(s: ThinkSplitter, text: String): String =
        s.feed(text).filter { !it.isReasoning }.joinToString("") { it.text }

    private fun reasoning(s: ThinkSplitter, text: String): String =
        s.feed(text).filter { it.isReasoning }.joinToString("") { it.text }

    @Test
    fun `plain text passes through`() {
        val s = ThinkSplitter()
        assertEquals("hello world", content(s, "hello ") + content(s, "world"))
        assertTrue(reasoning(s, "x").isEmpty())
        assertEquals("more", content(s, "more"))
    }

    @Test
    fun `think block split in one chunk`() {
        val s = ThinkSplitter()
        val r = reasoning(s, "<think>let me think")
        val c = content(s, "</think>the answer")
        assertEquals("let me think", r)
        assertEquals("the answer", c)
    }

    @Test
    fun `think tag split across chunks`() {
        val s = ThinkSplitter()
        val r1 = reasoning(s, "<thi")
        val r2 = reasoning(s, "nk>deep ")
        val r3 = reasoning(s, "thought")
        val c = content(s, "</th" + "ink>answer")
        assertEquals("deep thought", r1 + r2 + r3)
        assertEquals("answer", c)
    }

    @Test
    fun `case-insensitive open and close tags`() {
        val s = ThinkSplitter()
        val parts = s.feed("<Think>secret</THINK>visible")
        val r = parts.filter { it.isReasoning }.joinToString("") { it.text }
        val c = parts.filter { !it.isReasoning }.joinToString("") { it.text }
        assertEquals("secret", r)
        assertEquals("visible", c)
    }

    @Test
    fun `case-insensitive tag split across chunks`() {
        val s = ThinkSplitter()
        val r1 = reasoning(s, "<THI")
        val parts = s.feed("NK>abc</think>done")
        val r2 = parts.filter { it.isReasoning }.joinToString("") { it.text }
        val c = parts.filter { !it.isReasoning }.joinToString("") { it.text }
        assertTrue(r1.isEmpty())
        assertEquals("abc", r2)
        assertEquals("done", c)
    }

    @Test
    fun `leading whitespace before think dropped`() {
        val s = ThinkSplitter()
        // "\n\n<think>" 之前的空白不应混进正文
        s.feed("\n\n<think>thinking")
        val c = s.feed("</think>\n\nanswer").filter { !it.isReasoning }.joinToString("") { it.text }
        assertEquals("answer", c)
    }

    @Test
    fun `no thinking tags at all`() {
        val s = ThinkSplitter()
        val c = content(s, "a < b and c > d")
        assertEquals("a < b and c > d", c)
    }

    @Test
    fun `unclosed think goes to reasoning on flush`() {
        val s = ThinkSplitter()
        val fed = s.feed("<think>never closed")
        val all = fed + s.flush()
        assertEquals(1, all.size)
        assertTrue(all[0].isReasoning)
        assertEquals("never closed", all[0].text)
    }

    @Test
    fun `multiple think blocks`() {
        val s = ThinkSplitter()
        reasoning(s, "<think>one")
        reasoning(s, "</think>first")
        val r2 = reasoning(s, "<think>two")
        val c2 = content(s, "</think>second")
        assertEquals("two", r2)
        assertEquals("second", c2)
    }

    // ===== lead-think：无 <think> 标签模型的纯文本思考开头（qwen 系实测） =====

    @Test
    fun `thinking process 开头整段归 reasoning 段落后回正文`() {
        val s = ThinkSplitter()
        val fed = s.feed("Thinking Process:1. Analyze the request.\n\n好的主人，我来啦。")
        val r = fed.filter { it.isReasoning }.joinToString("") { it.text }
        val c = fed.filter { !it.isReasoning }.joinToString("") { it.text }
        assertTrue(r.contains("Analyze the request"))
        assertEquals("好的主人，我来啦。", c)
    }

    @Test
    fun `小写与跨chunk的 thinking 开头也命中`() {
        val s = ThinkSplitter()
        val fed1 = s.feed("thin")
        val fed2 = s.feed("king process: step one\n\n回答")
        val all = fed1 + fed2
        val r = all.filter { it.isReasoning }.joinToString("") { it.text }
        val c = all.filter { !it.isReasoning }.joinToString("") { it.text }
        assertTrue(r.contains("step one"))
        assertEquals("回答", c)
    }

    @Test
    fun `let me think 开头命中`() {
        val s = ThinkSplitter()
        val all = s.feed("Let me think about this.\n\n答案在这")
        val r = all.filter { it.isReasoning }.joinToString("") { it.text }
        val c = all.filter { !it.isReasoning }.joinToString("") { it.text }
        assertEquals("Let me think about this.", r.trim())
        assertEquals("答案在这", c)
    }

    @Test
    fun `普通回复不受影响-中途出现 thinking 字样不拦截`() {
        val s = ThinkSplitter()
        val all = s.feed("主人你好。Thinking Process: 是什么东西，我不懂。")
        assertEquals(1, all.size)
        assertTrue(!all[0].isReasoning)
        assertEquals("主人你好。Thinking Process: 是什么东西，我不懂。", all[0].text)
    }

    @Test
    fun `超过上限无段落边界整段放行为正文`() {
        val s = ThinkSplitter()
        val long = "Thinking process: " + "x".repeat(1400)
        val all = s.feed(long)
        assertTrue(all.isNotEmpty())
        assertTrue(all.none { it.isReasoning })
    }

    @Test
    fun `悬置跨chunk流结束按正文归还`() {
        val s = ThinkSplitter()
        val fed1 = s.feed("thin")
        val flush = s.flush()
        val all = fed1 + flush
        assertEquals("thin", all.joinToString("") { it.text })
        assertTrue(all.none { it.isReasoning })
    }

    @Test
    fun `非思考开头的普通回复不误伤`() {
        val s = ThinkSplitter()
        val all = s.feed("The result is 42.\n\n第二段")
        assertTrue(all.none { it.isReasoning })
    }

    @Test
    fun `编号分析开场-关键词两连击判为思考`() {
        val s = ThinkSplitter()
        val fed = s.feed("1. Analyze the Request: Role: 「精灵」. Mandatory Identity: 我/主人. Language: Chinese.\n\n下午好，主人！")
        val r = fed.filter { it.isReasoning }.joinToString("") { it.text }
        val c = fed.filter { !it.isReasoning }.joinToString("") { it.text }
        assertTrue(r.contains("Analyze the Request"))
        assertEquals("下午好，主人！", c)
    }

    @Test
    fun `单一 role 字样不触发分析腔启发式`() {
        val s = ThinkSplitter()
        val all = s.feed("My role: tank. 咱们开打吧。")
        assertTrue(all.none { it.isReasoning })
    }

    // ===== deferred 延迟分类（familiar 用）：思考墙收尾时按最后段落边界切分 =====

    @Test
    fun `deferred-裸思考墙整墙归思考-答案归正文`() {
        val s = ThinkSplitter(deferred = true)
        val nl = "\n"
        val wall = "1.  **Analyze the Request:**" + nl +
            "    *   **Role:** 精灵." + nl +
            "    *   **Style:** short." + nl +
            "Let's refine for maximum pixel elf vibe." + nl + nl +
            "Final Choice:你好呀，主人！"
        var fed = emptyList<ThinkSplitter.Segment>()
        // 分成多个 chunk 喂
        wall.chunked(40).forEach { fed += s.feed(it) }
        assertTrue("流式过程中不应有任何下发", fed.isEmpty())
        val all = fed + s.flush()
        val r = all.filter { it.isReasoning }.joinToString("") { it.text }
        val c = all.filter { !it.isReasoning }.joinToString("") { it.text }
        assertTrue(r.contains("Analyze the Request"))
        assertTrue(c.contains("你好呀，主人！"))
        assertTrue(!c.contains("Analyze"))
    }

    @Test
    fun `deferred-无边界的整段按正文兜底`() {
        val s = ThinkSplitter(deferred = true)
        val fed = s.feed("Thinking Process: 连空行都没有的一整段话")
        assertTrue(fed.isEmpty())
        val all = fed + s.flush()
        assertEquals(1, all.size)
        assertTrue(!all[0].isReasoning)
    }

    @Test
    fun `deferred-普通短回复收尾冲出为正文`() {
        val s = ThinkSplitter(deferred = true)
        val fed = s.feed("主人你好呀")
        assertTrue(fed.isEmpty())              // deferred 悬置到收尾
        val all = fed + s.flush()
        assertEquals(1, all.size)
        assertTrue(!all[0].isReasoning)
        assertEquals("主人你好呀", all[0].text)
    }

    @Test
    fun `deferred-带think标签的思考收尾冲出`() {
        val s = ThinkSplitter(deferred = true)
        val fed = s.feed("<think>内心盘算</think>回答正文")
        assertTrue(fed.isEmpty())
        val all = fed + s.flush()
        assertEquals(2, all.size)
        assertTrue(all[0].isReasoning)
        assertEquals("内心盘算", all[0].text)
        assertEquals("回答正文", all[1].text)
    }
}
