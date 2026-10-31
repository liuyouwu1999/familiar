package dev.liuyouwu.familiar.viewmodel

import dev.liuyouwu.familiar.core.llm.model.AgentMessage
import dev.liuyouwu.familiar.core.llm.model.ContentBlock
import dev.liuyouwu.familiar.core.llm.model.Role
import dev.liuyouwu.familiar.ui.model.MessageRenderModel
import dev.liuyouwu.familiar.ui.model.ToolBlockState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Agent 内核 P3 Task 9：[uiMessagesOf] 列表级渲染桥契约（纯函数，JVM 可测）。
 * 覆盖：
 * 1. 列表级工具配对——TOOL_RESULT 行的结果块回配前序 assistant 的 ToolCall
 *    （跨行终态；未配对=RUNNING / isError=ERROR）；
 * 2. TOOL_RESULT 行不独立成泡（已并入卡片，独立泡会重复）；
 * 3. segments 按行 id 透传（familiar 拟人节奏分段）；
 * 4. [MessageRenderModel.stripThinkHead]——旧链切段首段思考头剥离（思考由
 *    UiMessage.thinking 单独承载，段内不得残留标签字面量）；
 * 5. turnId 同域配对（P4 T8 callId 跨 run 重用守卫）——provider 跨 run/跨 turn
 *    重用 callId 不再串卡片；存量 NULL turnId 行保持旧全量后缀行为。
 */
class UiMessagesOfTest {

    @Test
    fun `tool result rows pair into preceding assistant cards and drop as bubbles`() {
        val assistant = AgentMessage(
            id = "10",
            role = Role.ASSISTANT,
            blocks = listOf(ContentBlock.ToolCall("c1", "web_search", """{"query":"猫"}""")),
        )
        val toolRow = AgentMessage(
            id = "11",
            role = Role.TOOL_RESULT,
            blocks = listOf(ContentBlock.ToolResult("c1", "web_search", "结果", isError = false)),
        )
        val final = AgentMessage(
            id = "12",
            role = Role.ASSISTANT,
            blocks = listOf(ContentBlock.Text("答案")),
        )

        val ui = uiMessagesOf(listOf(assistant, toolRow, final), emptyMap())

        assertEquals(listOf("10", "12"), ui.map { it.id })   // TOOL_RESULT 行不成泡
        val card = ui[0].toolBlocks.single()
        assertEquals("web_search", card.name)
        assertEquals(ToolBlockState.DONE, card.state)        // 跨行回配终态
        assertEquals("结果", card.output)
    }

    @Test
    fun `unpaired call stays running and error result maps to error`() {
        val assistant = AgentMessage(
            id = "20",
            role = Role.ASSISTANT,
            blocks = listOf(
                ContentBlock.ToolCall("c1", "web_search", "{}"),
                ContentBlock.ToolCall("c2", "web_search", "{}"),
            ),
        )
        val toolRow = AgentMessage(
            id = "21",
            role = Role.TOOL_RESULT,
            blocks = listOf(ContentBlock.ToolResult("c2", "web_search", "炸了", isError = true)),
        )

        val ui = uiMessagesOf(listOf(assistant, toolRow), emptyMap())

        // 卡片按 ToolCall 声明序：c1 未配到=RUNNING，c2 isError=ERROR
        assertEquals(2, ui.single().toolBlocks.size)
        assertEquals(ToolBlockState.RUNNING, ui.single().toolBlocks[0].state)
        assertEquals(ToolBlockState.ERROR, ui.single().toolBlocks[1].state)
        assertNull(ui.single().toolBlocks[0].output)
        assertEquals("炸了", ui.single().toolBlocks[1].output)
    }

    @Test
    fun `segments pass through keyed by row id`() {
        val familiar = AgentMessage(
            id = "30",
            role = Role.ASSISTANT,
            blocks = listOf(
                ContentBlock.Thinking("想"),
                ContentBlock.Text("第一段\n\n第二段"),
            ),
        )
        val plain = AgentMessage(id = "31", role = Role.ASSISTANT, blocks = listOf(ContentBlock.Text("普通")))

        val ui = uiMessagesOf(
            listOf(familiar, plain),
            mapOf("30" to listOf("第一段", "第二段"), "31" to null),
        )

        assertEquals(listOf("第一段", "第二段"), ui[0].segments)
        assertNull(ui[1].segments)
        assertEquals("想", ui[0].thinking)
    }

    @Test
    fun `provider reusing callId across runs pairs within own run only`() {
        // P4 T8 callId 跨 run 重用守卫：同会话先后两 run（聊天室逐角色形态），
        // provider 两 run 原样回传同一 callId "1"——各 run 卡片只配本 run 的回执，
        // 后一 run 的结果不得覆盖前一 run 的卡片（旧行为 associateBy 恒取后值）
        val runACall = AgentMessage(
            id = "40", role = Role.ASSISTANT, turnId = "run-A:0",
            blocks = listOf(ContentBlock.ToolCall("1", "web_search", """{"query":"A"}""")),
        )
        val runAResult = AgentMessage(
            id = "41", role = Role.TOOL_RESULT, turnId = "run-A:0",
            blocks = listOf(ContentBlock.ToolResult("1", "web_search", "A的结果", isError = false)),
        )
        val runACall2 = AgentMessage(
            id = "42", role = Role.ASSISTANT, turnId = "run-A:1",
            blocks = listOf(ContentBlock.ToolCall("1", "web_search", """{"query":"A2"}""")),
        )
        val runAResult2 = AgentMessage(
            id = "43", role = Role.TOOL_RESULT, turnId = "run-A:1",
            blocks = listOf(ContentBlock.ToolResult("1", "web_search", "A2的结果", isError = false)),
        )
        val runBCall = AgentMessage(
            id = "44", role = Role.ASSISTANT, turnId = "run-B:0",
            blocks = listOf(ContentBlock.ToolCall("1", "web_search", """{"query":"B"}""")),
        )
        val runBResult = AgentMessage(
            id = "45", role = Role.TOOL_RESULT, turnId = "run-B:0",
            blocks = listOf(ContentBlock.ToolResult("1", "web_search", "B的结果", isError = false)),
        )

        val ui = uiMessagesOf(listOf(runACall, runAResult, runACall2, runAResult2, runBCall, runBResult), emptyMap())

        assertEquals(listOf("40", "42", "44"), ui.map { it.id })
        // run-A turn0：配 run-A:0 的回执（跨 turn 同 callId 的 run-A:1 回执不同域）
        assertEquals("A的结果", ui[0].toolBlocks.single().output)
        assertEquals(ToolBlockState.DONE, ui[0].toolBlocks.single().state)
        // run-A turn1：配 run-A:1 的回执
        assertEquals("A2的结果", ui[1].toolBlocks.single().output)
        // run-B：配 run-B 的回执（跨 run 同 callId 不串）
        assertEquals("B的结果", ui[2].toolBlocks.single().output)
    }

    @Test
    fun `legacy null turnId rows keep whole-suffix pairing`() {
        // 存量旧行（turnId=NULL）各行同桶：与旧"全量后缀"行为等值，老数据配对不变
        val assistant = AgentMessage(
            id = "50", role = Role.ASSISTANT, turnId = null,
            blocks = listOf(ContentBlock.ToolCall("1", "web_search", "{}")),
        )
        val toolRow = AgentMessage(
            id = "51", role = Role.TOOL_RESULT, turnId = null,
            blocks = listOf(ContentBlock.ToolResult("1", "web_search", "旧行结果", isError = false)),
        )

        val ui = uiMessagesOf(listOf(assistant, toolRow), emptyMap())

        assertEquals("旧行结果", ui.single().toolBlocks.single().output)
        assertEquals(ToolBlockState.DONE, ui.single().toolBlocks.single().state)
    }

    @Test
    fun `strip think head removes leading think block from first segment`() {
        val seg0 = "<think>\n推理\n</think>\n你好"
        assertEquals("你好", MessageRenderModel.stripThinkHead(seg0))
        // 无标签：原样返回
        assertEquals("普通正文", MessageRenderModel.stripThinkHead("普通正文"))
        // 变体标签 + 思考独占段（剥完为空）
        assertEquals("", MessageRenderModel.stripThinkHead("<reasoning>only</reasoning>"))
    }
}
