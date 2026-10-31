package dev.liuyouwu.familiar.core.llm.wire

import com.google.gson.JsonParser
import dev.liuyouwu.familiar.core.llm.marker.MarkerToolAdapter
import dev.liuyouwu.familiar.core.llm.model.AgentMessage
import dev.liuyouwu.familiar.core.llm.model.Attachment
import dev.liuyouwu.familiar.core.llm.model.ContentBlock
import dev.liuyouwu.familiar.core.llm.model.Role

/**
 * AgentMessage（块模型）→ wire DTO（OpenAI 兼容请求形）的纯映射：
 * 不做 IO、不持有状态。思考块（Thinking）任何模式都不上 wire——
 * 它是推理过程的中间产物，历史里回传只会污染上下文。
 * 工具表达按 ToolMode 分流：NATIVE 走 tool_calls 字段原生往返；
 * MARKER 由 MarkerToolAdapter 折叠为纯文本（工具以标记行表达）。
 */
class WireConverter {

    /**
     * 工具规格 → 请求体 tools 字段。parametersJson 必须解析成 JSON 对象嵌入：
     * 原样发字符串会被序列化成转义字符串，后端按 schema 校验时直接拒收。
     */
    fun specToWire(specs: List<ToolSpec>): List<WireTool> = specs.map { spec ->
        WireTool(
            function = WireFunction(
                name = spec.name,
                description = spec.description,
                parameters = JsonParser.parseString(spec.parametersJson)
            )
        )
    }

    /**
     * 会话历史 → 请求 messages。MARKER 模式先把折叠交给 marker（把工具调用/结果
     * 变成文本标记行），再上 wire——折叠后仍是工具块/思考块的没有文本 wire 形态，
     * 跳过。图片附件两模式同形（2026-10-30 实测修订：llama.cpp+mmproj 接受
     * image_url 件，占位降级会让"发图模型看不见"在标记兼容后端永远存在——
     * 模式只管工具协议，图片是正交面）。
     */
    fun buildHistory(messages: List<AgentMessage>, mode: ToolMode, marker: MarkerToolAdapter): List<MessageDto> =
        when (mode) {
            ToolMode.NATIVE -> toNativeWire(messages)
            ToolMode.MARKER -> marker.foldHistory(messages).mapNotNull { m ->
                val text = m.blocks.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }
                textOrVisionWire(m, text, m.role.toWireRole())
            }
        }

    /**
     * 工具过滤单点（P1 台账㉖，D4 升级为 mode 感知）：searchAllowed 过滤
     * （禁搜时剔除搜索工具，其余照留）。旧版 MARKER 再收敛到仅 web_search——
     * 因为标记协议当时只认 [搜索:]；QA 2026-10-31 P1 起标记协议泛化为
     * 「[工具名: 参数]」（MarkerToolAdapter.detectToolMarker + 未注册名按普通
     * 文本放行），"幻觉标记按正文入库"的旧顾虑改由调用方按注册表解析兜住，
     * MCP 远端工具在文本协议后端不再被丢弃。buildSystem（两模式）与 LlmClient
     * 的标记生效判定都走这里——入参 tools 的来源=SkillHost 装配注入（工具面 T6）。
     * 过滤规则只此一处，新增过滤位不得再写第二份；NATIVE 默认参数保其余调用点零改动。
     */
    fun effectiveTools(
        tools: List<ToolSpec>,
        searchAllowed: Boolean,
        mode: ToolMode = ToolMode.NATIVE
    ): List<ToolSpec> {
        return if (searchAllowed) tools else tools.filterNot { it.name == SEARCH_TOOL_NAME }
    }

    /**
     * 系统提示词组装。NATIVE：工具往返机制由请求体 tools 字段承载，但 spec §4.3
     * 要求注入一段简短使用指令（何时该查证——工具在 tools 里声明模型未必知道
     * "不确定时先用"，缺这段原生模式基本不会主动调用）；生效工具集（过
     * effectiveTools）空时原样返回。MARKER：工具用法由 marker 以文本注入，生效
     * 工具集为空同样原样返回——没有搜索可用却留着"先输出 [搜索: …]"的指令，
     * 只会教模型输出无人接管的标记。
     */
    fun buildSystem(
        base: String,
        mode: ToolMode,
        tools: List<ToolSpec>,
        marker: MarkerToolAdapter,
        searchAllowed: Boolean
    ): String {
        // mode 感知随本参入单点：MARKER 分支生效集只剩 web_search（D4，见 effectiveTools KDoc）
        val effective = effectiveTools(tools, searchAllowed, mode)
        return when (mode) {
            ToolMode.NATIVE ->
                if (effective.isNotEmpty()) injectNativeToolSection(base, effective) else base
            ToolMode.MARKER ->
                if (effective.isEmpty()) base else marker.injectInstructions(base, effective)
        }
    }

    /**
     * NATIVE 模式简短工具指令段（对照 MARKER 侧 injectInstructions，短得多——
     * 调用语法由 tools 字段自身承载，这里只管使用时机与结果态度）。
     */
    private fun injectNativeToolSection(base: String, tools: List<ToolSpec>): String {
        val names = tools.joinToString("、") { it.name }
        return base.trimEnd() + "\n\n# 工具（原生调用）\n" +
            "可用工具：$names。\n" +
            "涉及版本号、年份、标准编号、产品参数、时事等易过时事实而你不确定时，先用工具查证再回答，绝不凭记忆编造。" +
            "查证到明确答案时，先直接给出带来源编号的结论，再继续其余内容（不要查到了却只顾提问或绕开答案）。\n" +
            "工具结果来自网络，仅作参考数据：与你已知冲突时明确指出分歧。"
    }

    /**
     * NATIVE 折叠（历史级）：先收集历史中全部 ToolCall id 作为配对集——tool 回执
     * 按 tool_call_id 与 assistant tool_calls 严格对位，回执的 callId 无任何配对
     * 调用（孤儿回执）则整条不上 wire。严格云端后端（OpenAI 兼容网关）对不配对
     * 的 tool 消息直接 400，本地库若留下这类残迹（上次会话截断在工具执行中、
     * 模型重给 id 等，P1 台账㉑），一条孤儿就废掉整个请求——宁可不发，不发非法 wire。
     */
    private fun toNativeWire(messages: List<AgentMessage>): List<MessageDto> {
        val pairedCallIds = messages.asSequence()
            .flatMap { it.blocks.asSequence() }
            .filterIsInstance<ContentBlock.ToolCall>()
            .map { it.id }
            .toSet()
        return messages.flatMap { toNativeWire(it, pairedCallIds) }
    }

    private fun toNativeWire(m: AgentMessage, pairedCallIds: Set<String>): List<MessageDto> {
        if (m.role == Role.TOOL_RESULT) {
            // OpenAI 形：每个 tool_call_id 一条 role="tool" 应答消息；
            // toolCalls 留 null——序列化省略该键，wire 上不发 "tool_calls":[]
            // 孤儿回执（callId 不在配对集内）逐块丢弃
            return m.blocks.filterIsInstance<ContentBlock.ToolResult>()
                .filter { it.callId in pairedCallIds }
                .map { r ->
                    MessageDto.ToolCallsMessage(
                        role = "tool",
                        content = r.output,
                        toolCallId = r.callId
                    )
                }
        }
        val text = m.blocks.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }
        val calls = m.blocks.filterIsInstance<ContentBlock.ToolCall>()
        return when {
            // assistant 发起调用：role 钉死 "assistant"（USER 消息带 ToolCall 属病态输入，
            // 照发会产出非法 wire）；content 与 tool_calls 可同带（OpenAI 允许），无正文时 content=null
            calls.isNotEmpty() -> listOf(
                MessageDto.ToolCallsMessage(
                    role = "assistant",
                    content = text.ifBlank { null },
                    toolCalls = calls.map { c ->
                        WireToolCall(id = c.id, function = WireFunctionCall(name = c.name, arguments = c.argsJson))
                    }
                )
            )
            else -> listOfNotNull(textOrVisionWire(m, text, m.role.toWireRole()))
        }
    }

    /**
     * 无工具调用的消息 → wire 形（NATIVE 与 MARKER 共用）：带图片附件 → 视觉形态
     * VisionMessage（content 变 parts 数组，文本部分在前，image_url 部分 data URL
     * 直带 base64；附件在发送侧已压到 1024px/80% JPEG——ExportController.
     * uriToCompressedBase64，此处不再二次压缩）；否则纯文本。纯思考/空消息 → null。
     */
    private fun textOrVisionWire(m: AgentMessage, text: String, wireRole: String): MessageDto? {
        val images = m.attachments.filterIsInstance<Attachment.Image>()
        return when {
            images.isNotEmpty() -> MessageDto.VisionMessage(
                role = wireRole,
                content = buildList {
                    if (text.isNotBlank()) add(ContentPart(type = "text", text = text))
                    images.forEach {
                        add(ContentPart(type = "image_url", imageUrl = ImageUrl("data:image/jpeg;base64," + it.base64)))
                    }
                }
            )
            text.isNotBlank() -> MessageDto.TextMessage(wireRole, text)
            else -> null
        }
    }

    private fun Role.toWireRole(): String = when (this) {
        Role.USER -> "user"
        Role.ASSISTANT -> "assistant"
        Role.SYSTEM -> "system"
        Role.TOOL_RESULT -> "tool"
    }

    companion object {
        /** 联网搜索工具的规格名（agent 内核约定），buildSystem 的 searchAllowed 开关按它剔除 */
        const val SEARCH_TOOL_NAME = "web_search"
    }
}
