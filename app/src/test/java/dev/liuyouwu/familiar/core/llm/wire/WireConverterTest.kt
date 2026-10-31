package dev.liuyouwu.familiar.core.llm.wire

import com.google.gson.GsonBuilder
import dev.liuyouwu.familiar.core.llm.marker.MarkerToolAdapter
import dev.liuyouwu.familiar.core.llm.model.*
import org.junit.Assert.*
import org.junit.Test

class WireConverterTest {
    private val conv = WireConverter()
    private val marker = MarkerToolAdapter() // Task 6 真实现：MARKER 分支走真实折叠/注入

    @Test
    fun `thinking blocks never go to wire`() {
        val msgs = listOf(AgentMessage("1", Role.ASSISTANT, listOf(ContentBlock.Thinking("x"), ContentBlock.Text("正文"))))
        val wire = conv.buildHistory(msgs, ToolMode.NATIVE, marker)
        assertEquals(1, wire.size)
        assertFalse((wire[0] as MessageDto.TextMessage).content.contains("x"))
    }

    @Test
    fun `native mode tool roundtrip shapes`() {
        val msgs = listOf(
            AgentMessage("1", Role.ASSISTANT, listOf(ContentBlock.ToolCall("c1", "web_search", """{"query":"JEV"}"""))),
            AgentMessage("2", Role.TOOL_RESULT, listOf(ContentBlock.ToolResult("c1", "web_search", "结果", false))),
        )
        val wire = conv.buildHistory(msgs, ToolMode.NATIVE, marker)
        val assistant = wire.filterIsInstance<MessageDto.ToolCallsMessage>().first { it.role == "assistant" }
        assertEquals("c1", assistant.toolCalls!![0].id)
        assertEquals("web_search", assistant.toolCalls!![0].function.name)
        val tool = wire.filterIsInstance<MessageDto.ToolCallsMessage>().first { it.role == "tool" }
        assertEquals("c1", tool.toolCallId)
        assertEquals("结果", tool.content)
    }

    @Test
    fun `tool role message omits tool_calls key from json`() {
        // "宁缺勿滥"：tool 应答消息只有 tool_call_id + content，序列化不得出现 "tool_calls":[] 空数组键
        // （回执带配对调用——孤儿回执不上 wire，见 orphan 测试）
        val msgs = listOf(
            AgentMessage("1", Role.ASSISTANT, listOf(ContentBlock.ToolCall("c1", "web_search", """{"query":"q"}"""))),
            AgentMessage("2", Role.TOOL_RESULT, listOf(ContentBlock.ToolResult("c1", "web_search", "结果", false))),
        )
        val wire = conv.buildHistory(msgs, ToolMode.NATIVE, marker)
        val tool = wire.filterIsInstance<MessageDto.ToolCallsMessage>().first { it.role == "tool" }
        val json = GsonBuilder().create().toJson(tool)
        assertFalse(json.contains("tool_calls"))
        assertTrue(json.contains("\"tool_call_id\":\"c1\""))
    }

    // ---------- 孤儿回执丢弃（P1 台账㉑：严格云端对不配对 tool 消息 400，整条不上 wire） ----------

    @Test
    fun `orphan tool receipts dropped from native wire while paired ones kept`() {
        val msgs = listOf(
            AgentMessage("1", Role.ASSISTANT, listOf(ContentBlock.ToolCall("c1", "web_search", """{"query":"JEV"}"""))),
            // 同一条 TOOL_RESULT 消息里配对块与孤儿块混装：只有配对的 c1 上 wire
            AgentMessage("2", Role.TOOL_RESULT, listOf(
                ContentBlock.ToolResult("c1", "web_search", "配对结果", false),
                ContentBlock.ToolResult("ghost", "web_search", "孤儿结果", false),
            )),
            // 整条消息都是孤儿（callId 在历史中无任何配对调用）：整条不上 wire
            AgentMessage("3", Role.TOOL_RESULT, listOf(ContentBlock.ToolResult("ghost2", "web_search", "孤儿结果2", false))),
        )
        val wire = conv.buildHistory(msgs, ToolMode.NATIVE, marker)
        val toolIds = wire.filterIsInstance<MessageDto.ToolCallsMessage>()
            .filter { it.role == "tool" }.map { it.toolCallId }
        assertEquals(listOf("c1"), toolIds)
    }

    @Test
    fun `history without any tool call emits no tool wire messages`() {
        val msgs = listOf(
            AgentMessage("u", Role.USER, listOf(ContentBlock.Text("问题"))),
            AgentMessage("t", Role.TOOL_RESULT, listOf(ContentBlock.ToolResult("ghost", "web_search", "幽灵回执", false))),
        )
        val wire = conv.buildHistory(msgs, ToolMode.NATIVE, marker)
        assertEquals(listOf("user"), wire.map { it.role })
    }

    // ---------- searchAllowed 工具过滤单点（P1 台账㉖：过滤规则只此一处） ----------

    @Test
    fun `effectiveTools is the single searchAllowed filter`() {
        val search = ToolSpec("web_search", "搜索", """{"type":"object"}""")
        val calc = ToolSpec("calc", "计算", """{"type":"object"}""")
        assertEquals(listOf(search, calc), conv.effectiveTools(listOf(search, calc), searchAllowed = true))
        assertEquals(listOf("calc"), conv.effectiveTools(listOf(search, calc), searchAllowed = false).map { it.name })
        assertTrue(conv.effectiveTools(listOf(search), searchAllowed = false).isEmpty())
    }

    @Test
    fun `native mode system filters search tool but keeps other tools when disallowed`() {
        // 与 MARKER 侧同口径：searchAllowed=false 只剔搜索工具，其余工具的指令段照注
        val tools = listOf(
            ToolSpec("web_search", "搜索", """{"type":"object"}"""),
            ToolSpec("calc", "计算", """{"type":"object"}"""),
        )
        val s = conv.buildSystem("基础提示", ToolMode.NATIVE, tools, marker, searchAllowed = false)
        assertTrue(s.contains("可用工具"))
        assertTrue(s.contains("calc"))
        assertFalse(s.contains("web_search"))
    }

    @Test
    fun `marker mode delegates folding`() {
        val msgs = listOf(
            AgentMessage("1", Role.ASSISTANT, listOf(ContentBlock.ToolCall("c1", "web_search", """{"query":"JEV"}"""))),
            AgentMessage("2", Role.TOOL_RESULT, listOf(ContentBlock.ToolResult("c1", "web_search", "结果", false))),
        )
        val wire = conv.buildHistory(msgs, ToolMode.MARKER, marker)
        assertTrue(wire.none { it is MessageDto.ToolCallsMessage }) // MARKER 模式的 wire 永不出现 ToolCallsMessage（工具以文本标记表达；未折叠成文本的非文本块无 wire 形态，跳过——Task 6 覆写 fold 后即为文本）
    }

    @Test
    fun `null tools omitted from json`() {
        val req = ChatRequest(model = "m", messages = emptyList(), stream = true)
        val json = GsonBuilder().create().toJson(req)
        assertFalse(json.contains("tools"))
    }

    @Test
    fun `spec to wire embeds schema`() {
        val wire = conv.specToWire(listOf(ToolSpec("web_search", "搜索", """{"type":"object"}""")))
        assertEquals("web_search", wire[0].function.name)
    }

    // ---------- NATIVE 模式简短工具指令注入（spec §4.3，最终审查修复） ----------

    @Test
    fun `native mode injects short tool section when tools present and search allowed`() {
        val s = conv.buildSystem(
            "基础提示", ToolMode.NATIVE,
            listOf(ToolSpec("web_search", "搜索", """{"type":"object"}""")),
            marker, searchAllowed = true,
        )
        assertTrue(s.startsWith("基础提示"))          // 基础段保留在前
        assertTrue(s.contains("可用工具"))
        assertTrue(s.contains("web_search"))          // 工具清单入段
        assertFalse(s.contains("[搜索:"))             // 不复用标记协议语法（原生模式无标记行）
    }

    @Test
    fun `native mode system unchanged when search disallowed or tools empty`() {
        val tools = listOf(ToolSpec("web_search", "搜索", """{"type":"object"}"""))
        assertEquals("基础提示", conv.buildSystem("基础提示", ToolMode.NATIVE, tools, marker, searchAllowed = false))
        assertEquals("基础提示", conv.buildSystem("基础提示", ToolMode.NATIVE, emptyList(), marker, searchAllowed = true))
    }

    // ---------- effectiveTools 单点 mode 感知（D4：MARKER 收敛 web_search，宁少报不谎报） ----------

    @Test
    fun `marker instructions list only web_search`() {
        // MARKER 协议只认 [搜索:]：指令段列全工具会让模型幻觉 [读取文件:] 标记按正文入库——
        // QA 2026-10-31 P1：标记协议泛化「[工具名: 参数]」后 MARKER 不再收敛单工具
        // ——MCP/本地件同上指令段；"幻觉标记"由调用方按注册表解析兜住（未知名按普通文本放行）
        val tools = listOf(
            ToolSpec("web_search", "搜索", """{"type":"object"}"""),
            ToolSpec("read_file", "读文件", """{"type":"object"}"""),
            ToolSpec("http_get", "取网页", """{"type":"object"}"""),
        )
        val s = conv.buildSystem("基础提示", ToolMode.MARKER, tools, marker, searchAllowed = true)
        assertTrue(s.contains("web_search"))
        assertTrue(s.contains("read_file"))
        assertTrue(s.contains("http_get"))
        assertTrue(s.contains("[工具名: 参数]"))
    }

    @Test
    fun `marker instructions drop banned search but keep other tools`() {
        // searchAllowed 先行：禁搜后 web_search 已剔——指令段不再含搜索，
        // 但其余工具照注（泛化标记协议下它们照常可用）
        val tools = listOf(
            ToolSpec("web_search", "搜索", """{"type":"object"}"""),
            ToolSpec("read_file", "读文件", """{"type":"object"}"""),
        )
        val s = conv.buildSystem("基础提示", ToolMode.MARKER, tools, marker, searchAllowed = false)
        assertFalse(s.contains("web_search"))
        assertTrue(s.contains("read_file"))
    }

    @Test
    fun `native mode unchanged`() {
        // 回归：NATIVE（默认参数）全量保留，mode 感知只影响 MARKER 分支
        val tools = listOf(
            ToolSpec("web_search", "搜索", """{"type":"object"}"""),
            ToolSpec("read_file", "读文件", """{"type":"object"}"""),
            ToolSpec("http_get", "取网页", """{"type":"object"}"""),
        )
        val s = conv.buildSystem("基础提示", ToolMode.NATIVE, tools, marker, searchAllowed = true)
        assertTrue(s.contains("web_search"))
        assertTrue(s.contains("read_file"))
        assertTrue(s.contains("http_get"))
    }

    // ===== 图片附件上 wire（2026-10-30 用户实测：发图模型看不见——toNativeWire
    // 只搬文本块，attachments 整个被丢。wire 层 VisionMessage/ContentPart 早已就位）=====

    @Test
    fun `native 模式用户图片附件转 image_url parts 且带 data URL 前缀`() {
        val msgs = listOf(
            AgentMessage(
                "u1", Role.USER,
                listOf(ContentBlock.Text("这张图里是什么？")),
                attachments = listOf(Attachment.Image("QUJD")),
            ),
        )
        val wire = conv.buildHistory(msgs, ToolMode.NATIVE, marker)
        assertEquals(1, wire.size)
        val vision = wire[0] as MessageDto.VisionMessage
        assertEquals("user", vision.role)
        assertEquals(2, vision.content.size)
        assertEquals("text", vision.content[0].type)
        assertEquals("这张图里是什么？", vision.content[0].text)
        assertEquals("image_url", vision.content[1].type)
        assertEquals("data:image/jpeg;base64,QUJD", vision.content[1].imageUrl!!.url)
        // 序列化形状：image_url 键名 + data URL 原样进 JSON
        val json = GsonBuilder().create().toJson(vision)
        assertTrue(json.contains("\"image_url\""))
        assertTrue(json.contains("data:image/jpeg;base64,QUJD"))
    }

    @Test
    fun `纯图片无正文也上 wire`() {
        val msgs = listOf(
            AgentMessage("u1", Role.USER, emptyList(), attachments = listOf(Attachment.Image("QUJD"))),
        )
        val wire = conv.buildHistory(msgs, ToolMode.NATIVE, marker)
        assertEquals(1, wire.size)
        val vision = wire[0] as MessageDto.VisionMessage
        assertEquals(1, vision.content.size)
        assertEquals("image_url", vision.content[0].type)
    }

    @Test
    fun `多图消息按序展开多个 image_url`() {
        val msgs = listOf(
            AgentMessage(
                "u1", Role.USER,
                listOf(ContentBlock.Text("对比这两张图")),
                attachments = listOf(Attachment.Image("AAA"), Attachment.Image("BBB")),
            ),
        )
        val wire = conv.buildHistory(msgs, ToolMode.NATIVE, marker)
        val vision = wire[0] as MessageDto.VisionMessage
        val urls = vision.content.filter { it.type == "image_url" }.map { it.imageUrl!!.url }
        assertEquals(listOf("data:image/jpeg;base64,AAA", "data:image/jpeg;base64,BBB"), urls)
    }

    @Test
    fun `无附件消息保持 TextMessage 形态`() {
        // 回归：图片链路不得改变无附件请求的 wire 形状（本地文本模型后端无感知）
        val msgs = listOf(
            AgentMessage("u1", Role.USER, listOf(ContentBlock.Text("普通问题"))),
        )
        val wire = conv.buildHistory(msgs, ToolMode.NATIVE, marker)
        val text = wire[0] as MessageDto.TextMessage
        assertEquals("普通问题", text.content)
    }

    @Test
    fun `MARKER 模式图片同形上 image_url parts（不降级占位）`() {
        // 2026-10-30 实测修订：llama.cpp+mmproj 接受 image_url 件，占位降级会让
        // "发图模型看不见"在标记兼容后端永远存在——模式只管工具协议，图片正交
        val msgs = listOf(
            AgentMessage(
                "u1", Role.USER,
                listOf(ContentBlock.Text("看看这张图")),
                attachments = listOf(Attachment.Image("QUJD")),
            ),
        )
        val wire = conv.buildHistory(msgs, ToolMode.MARKER, marker)
        val vision = wire[0] as MessageDto.VisionMessage
        assertEquals(2, vision.content.size)
        assertEquals("看看这张图", vision.content[0].text)
        assertEquals("data:image/jpeg;base64,QUJD", vision.content[1].imageUrl!!.url)
    }
}
