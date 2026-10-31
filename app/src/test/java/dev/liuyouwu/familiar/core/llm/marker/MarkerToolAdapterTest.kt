package dev.liuyouwu.familiar.core.llm.marker

import dev.liuyouwu.familiar.core.llm.model.AgentMessage
import dev.liuyouwu.familiar.core.llm.model.ContentBlock
import dev.liuyouwu.familiar.core.llm.model.Role
import dev.liuyouwu.familiar.core.llm.wire.ToolSpec
import org.junit.Assert.*
import org.junit.Test

class MarkerToolAdapterTest {
    private val adapter = MarkerToolAdapter()

    // ===== detectMarker：宽容识别 + 行锚收紧 =====

    @Test
    fun `detect accepts four bracket kinds and both colon widths`() {
        // 四种括号（半角/全角方括号、半角/全角圆括号）× 半角/全角冒号
        assertEquals("JEV", adapter.detectMarker("[搜索: JEV]"))
        assertEquals("JEV", adapter.detectMarker("【搜索：JEV】"))
        assertEquals("JEV", adapter.detectMarker("(搜索: JEV)"))
        assertEquals("JEV", adapter.detectMarker("（搜索：JEV）"))
        // 行首缩进容忍
        assertEquals("JEV", adapter.detectMarker("  [搜索: JEV]"))
    }

    @Test
    fun `detect accepts english localized marker`() {
        // 真机回归（2026-10-29）：英文语境下 gemma 输出 [search: ...] 而非 [搜索: ...]，
        // 只认中文时整行按正文漏过、搜索不触发
        assertEquals("fusion energy", adapter.detectMarker("[search: fusion energy]"))
        assertEquals("fusion energy", adapter.detectMarker("[Search: fusion energy]"))
        assertEquals("fusion energy", adapter.detectMarker("【SEARCH：fusion energy】"))
        // 剥行同样剥英文标记行；行中英文标记不误剥
        assertEquals("第一段\n第二段", adapter.stripMarkerLines("第一段\n[search: q]\n第二段"))
        assertNull(adapter.detectMarker("explain [search: q] syntax here"))
    }

    @Test
    fun `detect rejects mid-line occurrences`() {
        // 行中出现的标记形状（模型讲解本机制时）不触发
        assertNull(adapter.detectMarker("先输出一行 [搜索: 查询词] 就行"))
        assertNull(adapter.detectMarker("正文 [搜索: JEV] 结尾"))
        // 有意差异（vs 旧 findSearchMarker）：标记后同行还有内容 → 不再命中（行尾锚收紧，防误触发）
        assertNull(adapter.detectMarker("[搜索: JEV] 以及说明"))
    }

    @Test
    fun `detect strips zero-width chars inside and around marker`() {
        // 零宽剥离集 \u200B-\u200D\uFEFF：括号外/关键字间/查询词内都容忍
        assertEquals("JEV", adapter.detectMarker("\u200B[\u200C搜\u200D索\uFEFF: JEV]"))
        assertEquals("JEV", adapter.detectMarker("[搜索: JE\u200BV]"))
    }

    @Test
    fun `detect enforces 80 char query limit and rejects blank query`() {
        assertEquals("q".repeat(80), adapter.detectMarker("[搜索: ${"q".repeat(80)}]"))
        assertNull(adapter.detectMarker("[搜索: ${"q".repeat(81)}]"))
        assertNull(adapter.detectMarker("[搜索: ]"))
        assertNull(adapter.detectMarker("[搜索:]"))
    }

    // ===== stripMarkerLines：完整行 + 残缺尾 =====

    @Test
    fun `strip removes complete marker lines and dangling tail`() {
        assertEquals("第一段\n第二段", adapter.stripMarkerLines("第一段\n[搜索: JEV]\n第二段"))
        // 残缺尾部（流中断产物）：未闭合标记起截除，其余正文保留
        assertEquals("好的，让我查一下", adapter.stripMarkerLines("好的，让我查一下\n[搜索: 最新HT"))
        // 无标记原样返回
        assertEquals("普通回复", adapter.stripMarkerLines("普通回复"))
        // 括号/冒号变体同样剥除
        assertEquals("后续", adapter.stripMarkerLines("【搜索：q】\n后续"))
    }

    @Test
    fun `strip keeps mid-line marker text and closes are not treated as dangling`() {
        // 行中完整标记（讲解本机制）不剥——与 detect 的行锚收紧一致
        assertEquals(
            "先输出一行 [搜索: 查询词] 就行",
            adapter.stripMarkerLines("先输出一行 [搜索: 查询词] 就行")
        )
    }

    // ===== injectInstructions：通用工具协议段 =====

    @Test
    fun `inject appends generic tool protocol to system prompt`() {
        val base = "你是导师。"
        val out = adapter.injectInstructions(
            base, listOf(ToolSpec("web_search", "联网搜索", """{"type":"object"}"""))
        )
        // 原提示词保留在前，协议段追加在尾
        assertTrue(out.startsWith(base))
        assertTrue(out.length > base.length)
        // 标记协议与工具名、防注入纪律
        assertTrue(out.contains("[搜索: "))
        assertTrue(out.contains("web_search"))
        assertTrue(out.contains("指令"))
        // 专名硬规则（真机回归 2026-10-29）：宁搜不编 + 堵死"解释成课程主题"旁路，
        // 且不再给"纯概念讲解"整段豁免（那正是模型绕搜的口子）
        assertTrue(out.contains("宁搜不编"))
        assertTrue(out.contains("绝不允许把不认识的名词解释成课程主题"))
        assertTrue(!out.contains("纯概念讲解"))
    }

    @Test
    fun `inject omits search teaching when search not in effective set`() {
        // 对抗审查 B-1：禁搜会话教 "[搜索: 查询词]" = 教模型发会被剥除的标记行
        val out = adapter.injectInstructions(
            "你是导师。",
            listOf(ToolSpec("read_file", "读文件", """{"type":"object"}"""))
        )
        assertTrue(out.contains("[工具名: 参数]"))
        assertTrue(out.contains("不可信参考数据"))
        assertFalse(out.contains("[搜索: "))
        assertFalse(out.contains("宁搜不编"))
    }

    // ===== foldHistory：中性折叠（spec §4.3）=====

    @Test
    fun `fold neutralizes tool roundtrip into text messages`() {
        val history = listOf(
            AgentMessage("u1", Role.USER, listOf(ContentBlock.Text("什么是 JEV？"))),
            AgentMessage(
                "a1", Role.ASSISTANT, listOf(
                    ContentBlock.Thinking("思考不该回传"),
                    ContentBlock.Text("好的，我先查一下"),
                    ContentBlock.ToolCall("c1", "web_search", """{"query":"JEV"}""")
                )
            ),
            AgentMessage(
                "t1", Role.TOOL_RESULT, listOf(
                    ContentBlock.ToolResult("c1", "web_search", "1. JEV 条目", false)
                )
            ),
        )
        val folded = adapter.foldHistory(history)

        // 全局不变式：折叠后无 ToolCall 块、无 TOOL_RESULT 角色残留
        assertTrue(folded.none { m -> m.blocks.any { it is ContentBlock.ToolCall } })
        assertTrue(folded.none { m -> m.role == Role.TOOL_RESULT })
        // 非工具消息原样保留
        assertEquals(3, folded.size)
        assertEquals("什么是 JEV？", (folded[0].blocks[0] as ContentBlock.Text).text)

        // assistant 侧：保留标记前半截正文 + 中性描述（绝不含字面标记，防小模型复读）
        val a = folded[1]
        assertEquals(Role.ASSISTANT, a.role)
        val aText = a.blocks.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }
        assertTrue(aText.contains("好的，我先查一下"))
        assertTrue(aText.contains("（已联网查询：JEV）"))
        assertFalse(aText.contains("[搜索:"))
        // 折叠后是纯文本消息：Thinking 也不回传
        assertTrue(a.blocks.all { it is ContentBlock.Text })

        // ToolResult 侧：折叠为 user 文本，沿用搜索结果包裹标记（Task 9 同款）
        val r = folded[2]
        assertEquals(Role.USER, r.role)
        val rText = r.blocks.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }
        assertTrue(rText.contains("<<<搜索结果开始>>>"))
        assertTrue(rText.contains("1. JEV 条目"))
        assertTrue(rText.contains("<<<搜索结果结束>>>"))
    }

    @Test
    fun `fold passes through plain history unchanged`() {
        val plain = listOf(
            AgentMessage("u1", Role.USER, listOf(ContentBlock.Text("问题"))),
            AgentMessage("a1", Role.ASSISTANT, listOf(ContentBlock.Text("回答"))),
        )
        assertEquals(plain, adapter.foldHistory(plain))
    }

    @Test
    fun `fold tolerates unparseable call args`() {
        val history = listOf(
            AgentMessage(
                "a1", Role.ASSISTANT, listOf(
                    ContentBlock.ToolCall("c1", "web_search", "not-json")
                )
            ),
        )
        val folded = adapter.foldHistory(history)
        assertEquals(1, folded.size)
        val text = folded[0].blocks.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }
        assertTrue(text.contains("已联网查询"))
        assertFalse(text.contains("not-json"))
    }

    // ===== 通用工具标记（QA 2026-10-31 P1：MARKER 泛化多工具，MCP 不再被丢） =====

    @Test
    fun `detectToolMarker captures tool name and args`() {
        assertEquals("qa-mcp__echo" to "hello-mcp", adapter.detectToolMarker("[qa-mcp__echo: hello-mcp]"))
        assertEquals("搜索" to "jev", adapter.detectToolMarker("[搜索: jev]"))
        assertEquals("search" to "jev", adapter.detectToolMarker("[search: jev]"))
        // 全角括号/冒号同口径
        assertEquals("qa-mcp__now" to "utc", adapter.detectToolMarker("【qa-mcp__now：utc】"))
    }

    @Test
    fun `detectToolMarker rejects blank name or args`() {
        assertEquals(null, adapter.detectToolMarker("[: value]"))
        assertEquals(null, adapter.detectToolMarker("[tool: ]"))
        assertEquals(null, adapter.detectToolMarker("普通正文 [注意: 这不是工具行]"))
    }

    @Test
    fun `buildMarkerArgs wraps single string property`() {
        val schema = """{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}"""
        assertEquals("""{"text":"hello-mcp"}""", adapter.buildMarkerArgs(schema, "hello-mcp"))
    }

    @Test
    fun `buildMarkerArgs handles empty and multi property schemas`() {
        assertEquals("{}", adapter.buildMarkerArgs("""{"type":"object","properties":{}}""", "x"))
        val multi = """{"type":"object","properties":{"a":{"type":"string"},"b":{"type":"string"}}}"""
        // 多参：参数文本本身是 JSON 对象时直通
        assertEquals("""{"a":"1","b":"2"}""", adapter.buildMarkerArgs(multi, """{"a":"1","b":"2"}"""))
        // 多参：不是 JSON 时回退首个参数名
        assertEquals("""{"a":"plain"}""", adapter.buildMarkerArgs(multi, "plain"))
    }

    @Test
    fun `buildMarkerArgs tolerates broken schema`() {
        assertEquals("{}", adapter.buildMarkerArgs("not-json", "x"))
    }
}
