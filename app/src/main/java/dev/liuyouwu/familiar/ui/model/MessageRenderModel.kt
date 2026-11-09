package dev.liuyouwu.familiar.ui.model

import com.google.gson.Gson
import dev.liuyouwu.familiar.core.llm.model.AgentMessage
import dev.liuyouwu.familiar.core.llm.model.Attachment
import dev.liuyouwu.familiar.core.llm.model.BlockCodec
import dev.liuyouwu.familiar.core.llm.model.ContentBlock
import dev.liuyouwu.familiar.core.llm.model.MessageMeta
import dev.liuyouwu.familiar.core.llm.model.ReplyRef
import dev.liuyouwu.familiar.core.llm.model.Role
import dev.liuyouwu.familiar.core.llm.model.SenderInfo
import dev.liuyouwu.familiar.core.llm.think.ThinkSplitter
import dev.liuyouwu.familiar.data.local.entity.MessageEntity

/**
 * 工具卡片三态（+取消）：RUNNING=未配到结果的 ToolCall（链还在跑）；
 * DONE=配到非 error 结果；ERROR=isError 结果；CANCELLED=isError 且输出为
 * AgentLoop 补落的取消字面量（见 [MessageRenderModel.CANCELLED_OUTPUT]）。
 */
enum class ToolBlockState { RUNNING, DONE, ERROR, CANCELLED }

/** 工具调用卡片渲染模型：名称 + 参数摘要 + 可展开输出 + 终态 */
data class ToolBlock(
    val name: String,
    val argsSummary: String,
    val output: String?,
    val state: ToolBlockState,
)

/** 附件摘要（渲染标签用）：文件名 + 视频帧数（非视频为 null） */
data class AttachmentSummary(val fileName: String?, val videoFrameCount: Int?)

/**
 * 附件惰性加载产物（审计 P1-4）：轻量读路径的 hasAttachment 行在渲染位按需取回的
 * 附件真值——图片 base64 列表 + 附件摘要列表（语义同 [UiMessage.images]/
 * [UiMessage.attachments]）。放 ui.model：气泡侧经回调拿此类型，不触碰 data.local。
 */
data class AttachmentPayload(val images: List<String>, val summaries: List<AttachmentSummary>)

/**
 * MessageBubble 的渲染模型（Agent 内核 P3 Task 8）：把 [AgentMessage] 的块结构 /
 * [MessageEntity] 的旧列结构统一折成"思考 + 文本块 + 工具卡片"三段渲染决策。
 * 纯 Kotlin、无 Compose 依赖——JVM 可测（MessageRenderModelTest）。
 *
 * - thinking：Thinking 块（或存量 `<think>` 前缀拆分）聚合，唯一思考来源——
 *   气泡侧不再做任何思考标签剥除；
 * - textBlocks：Text 块文本列表（渲染时 `\n` 拼接为正文）；
 * - toolBlocks：ToolCall→卡片，后续 TOOL_RESULT 行按 callId 回配出终态；
 * - segments：拟人节奏分段透传（调用方决定按段切泡或整条渲染）；
 * - fullText：正文整体视图（截断判断/编辑初值/复制源）。
 */
data class UiMessage(
    val id: String,
    val role: Role,
    val senderName: String? = null,
    val senderModel: String? = null,
    val senderColorHex: String? = null,
    val thinking: String? = null,
    val textBlocks: List<String> = emptyList(),
    val toolBlocks: List<ToolBlock> = emptyList(),
    /** 图片附件全量列表（P5 清理批多图首图修复：旧渲染只出首图，其余静默丢） */
    val images: List<String> = emptyList(),
    /** 首图兼容位（entity 旧行单图列/既有构造点）；气泡渲染以 [images] 为准 */
    val imageBase64: String? = null,
    val attachments: List<AttachmentSummary> = emptyList(),
    /** 附件惰性标记（审计 P1-4）：轻量读路径的 hasAttachment 行由 VM 层置空附件
     *  （images/attachments 恒空）并置 true；渲染位经 onLoadAttachment 回调按需
     *  取 [AttachmentPayload] 真值。默认 false——全量构造点（from/fromEntity）不变 */
    val hasLazyAttachment: Boolean = false,
    val segments: List<String>? = null,
    val meta: MessageMeta = MessageMeta.NONE,
    val replyPreview: String? = null,
    val replyRole: String? = null,
) {
    val isUser: Boolean get() = role == Role.USER

    /** 正文整体视图：Text 块以 `\n` 拼接（与 content 投影列同一口径） */
    val fullText: String get() = textBlocks.joinToString("\n")
}

/**
 * 块→渲染段决策的纯函数集（Agent 内核 P3 Task 8）。
 *
 * - [from]：AgentMessage（+ 后续 TOOL_RESULT 行的块）→ [UiMessage]。思考聚合；
 *   ToolCall 未配对=RUNNING、按 callId 配到结果=DONE、isError=ERROR/CANCELLED。
 * - [fromEntity]：MessageEntity → [UiMessage]，旧聊天链适配——blocks 权威列
 *   非空走块分拣；为空时 content 整体单 textBlock，存量 `<think>` 前缀经
 *   [ThinkSplitter] 一次性拆分（扩展识别集：think/thinkyle/thinking/reasoning/
 *   thought + Gemma channel 标记），替代气泡侧原 splitThinkingTags。
 */
object MessageRenderModel {

    /** AgentLoop 取消补落的 ToolResult 输出字面量：isError + 该字面量 → CANCELLED
     *  （内核清理 A，Task 4：字面唯一来源 viewmodel.RunMarkerTexts.TOOL_CANCELLED——
     *  AgentLoop 经注入 RunMarkers 写出同值，本常量改引用不再持字面；ui→viewmodel
     *  依赖同 AttachmentPreview 的先例） */
    const val CANCELLED_OUTPUT = dev.liuyouwu.familiar.viewmodel.RunMarkerTexts.TOOL_CANCELLED

    /** 参数摘要截断上限（单行化后超出部分以省略号收尾） */
    internal const val ARGS_SUMMARY_MAX_CHARS = 48

    private val gson = Gson()

    /**
     * @param agentMessage 内核消息（assistant/user/TOOL_RESULT 行皆可）
     * @param segments 拟人节奏分段（透传，null=整条渲染）
     * @param toolResults 该 assistant 消息**之后**的 TOOL_RESULT 行产出的结果块
     *   （调用方扫消息列表收集；按 callId 回配出卡片终态）
     */
    fun from(
        agentMessage: AgentMessage,
        segments: List<String>? = null,
        toolResults: List<ContentBlock.ToolResult> = emptyList(),
    ): UiMessage {
        val ownResults = agentMessage.blocks.filterIsInstance<ContentBlock.ToolResult>()
        val resultsByCallId = (toolResults + ownResults).associateBy { it.callId }
        return UiMessage(
            id = agentMessage.id,
            role = agentMessage.role,
            senderName = agentMessage.sender?.name?.takeIf { it.isNotEmpty() },
            senderModel = agentMessage.sender?.model,
            senderColorHex = agentMessage.sender?.colorHex,
            thinking = agentMessage.blocks.filterIsInstance<ContentBlock.Thinking>()
                .joinToString("\n") { it.text }.takeIf { it.isNotEmpty() },
            textBlocks = agentMessage.blocks.filterIsInstance<ContentBlock.Text>().map { it.text },
            toolBlocks = toolBlocksOf(agentMessage.blocks, resultsByCallId, ownResults),
            images = agentMessage.attachments.filterIsInstance<Attachment.Image>()
                .map { it.base64 },
            imageBase64 = agentMessage.attachments.filterIsInstance<Attachment.Image>()
                .firstOrNull()?.base64,
            attachments = agentMessage.attachments.mapNotNull { it.summary() },
            segments = segments,
            meta = agentMessage.meta,
            replyPreview = agentMessage.replyTo?.preview,
            replyRole = agentMessage.replyTo?.role,
        )
    }

    /** 旧聊天链适配：行 → 渲染模型（ChatScreen 的 MessageEntity 调用形状由此兼容） */
    fun fromEntity(entity: MessageEntity): UiMessage {
        if (entity.blocks != null) {
            // blocks 权威列（Agent 内核写侧 / LazyNormalizer 回写行）：走块分拣
            return from(
                AgentMessage(
                    id = entity.id.toString(),
                    role = roleOf(entity.role),
                    blocks = BlockCodec.fromJson(entity.blocks),
                    attachments = entityAttachments(entity),
                    sender = entitySender(entity),
                    replyTo = ReplyRef(entity.replyToMessageId, entity.replyToContent.orEmpty(), entity.replyToRole.orEmpty()),
                    meta = MessageMeta(entity.promptTokens, entity.completionTokens, entity.cachedTokens),
                    turnId = entity.turnId,
                ),
                segments = parseSegments(entity.segments),
            )
        }
        // 旧行：content 直出单 textBlock；存量 <think> 前缀一次性拆分（非 user 行——
        // 旧行为 user 消息不做思考拆分，字面量标签原样展示）
        val (thinking, textBlocks) =
            if (entity.role == ROLE_USER_COLUMN) null to listOf(entity.content)
            else oneShotThinkSplit(entity.content)
        return UiMessage(
            id = entity.id.toString(),
            role = roleOf(entity.role),
            senderName = entity.senderDisplayName,
            senderModel = entity.senderModel,
            senderColorHex = entity.senderColorHex,
            thinking = entity.reasoning?.takeIf { it.isNotBlank() } ?: thinking,
            textBlocks = textBlocks,
            // 行模型单图列（1 行 ≤1 附件，MessageRowMapper 同口径）——列表透传全量渲染
            images = listOfNotNull(entity.imageBase64),
            imageBase64 = entity.imageBase64,
            // 旧列语义：文件标签由 fileName 驱动，videoFrames 只贡献帧数（同旧气泡单标签）
            attachments = entityAttachmentSummaries(entity),
            segments = parseSegments(entity.segments),
            meta = MessageMeta(entity.promptTokens, entity.completionTokens, entity.cachedTokens),
            replyPreview = entity.replyToContent,
            replyRole = entity.replyToRole,
        )
    }

    /**
     * segments 列（MessageSegmenter JSON 数组字符串）解析；坏数据/空数组回退 null
     * （整条单气泡渲染，与 ChatScreen.parseFamiliarSegments 同语义）。
     */
    internal fun parseSegments(json: String?): List<String>? = json?.let {
        try {
            gson.fromJson(it, Array<String>::class.java)?.toList()
                ?.filter { s -> s.isNotEmpty() }
                ?.takeIf { l -> l.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 拆掉分段首段里的思考头（P3 T9）：旧链切段把 `<think>…</think>` 前缀并进首段
     * （splitFamiliarSegments 语义）；新渲染链思考由块侧 [UiMessage.thinking] 单独
     * 承载，正文段不得再含标签字面量。无标签时原样返回；思考独占段返回空串
     * （该泡只剩思考盒，与旧链等价）。
     */
    internal fun stripThinkHead(segment: String): String =
        oneShotThinkSplit(segment).second.joinToString("\n")

    /** 参数摘要：压成单行，超过 [ARGS_SUMMARY_MAX_CHARS] 截断加省略号 */
    internal fun argsSummaryOf(argsJson: String): String {
        val oneLine = argsJson.replace(Regex("\\s+"), " ").trim()
        if (oneLine.isEmpty()) return ""
        return if (oneLine.length <= ARGS_SUMMARY_MAX_CHARS) oneLine
        else oneLine.take(ARGS_SUMMARY_MAX_CHARS) + "…"
    }

    // ---------------------------------------------------------------- 内部

    private const val ROLE_USER_COLUMN = "user"

    /** 未知 role 列容错为 ASSISTANT（UI 不炸渲染；写侧 fail-fast 在 MessageRowMapper） */
    private fun roleOf(column: String): Role = when (column) {
        "user" -> Role.USER
        "assistant" -> Role.ASSISTANT
        "system" -> Role.SYSTEM
        "tool" -> Role.TOOL_RESULT
        else -> Role.ASSISTANT
    }

    /**
     * ToolCall→卡片（含终态映射）+ 未配自身调用的 ToolResult 块兜底成独立卡片
     * （TOOL_RESULT 行 / 取消补落行直接成泡渲染的路径）。
     */
    private fun toolBlocksOf(
        blocks: List<ContentBlock>,
        resultsByCallId: Map<String, ContentBlock.ToolResult>,
        ownResults: List<ContentBlock.ToolResult>,
    ): List<ToolBlock> {
        val calls = blocks.filterIsInstance<ContentBlock.ToolCall>()
        val callIds = calls.map { it.id }.toSet()
        val cards = calls.map { call ->
            val result = resultsByCallId[call.id]
            ToolBlock(
                name = call.name,
                argsSummary = argsSummaryOf(call.argsJson),
                output = result?.output,
                state = stateOf(result),
            )
        }
        val leftovers = ownResults.filter { it.callId !in callIds }
        return cards + leftovers.map { result ->
            ToolBlock(
                name = result.name,
                argsSummary = "",
                output = result.output,
                state = stateOf(result),
            )
        }
    }

    private fun stateOf(result: ContentBlock.ToolResult?): ToolBlockState = when {
        result == null -> ToolBlockState.RUNNING
        result.isError ->
            if (result.output.trim() == CANCELLED_OUTPUT) ToolBlockState.CANCELLED
            else ToolBlockState.ERROR
        else -> ToolBlockState.DONE
    }

    /** 存量思考前缀一次性拆分（非流式 ThinkSplitter 全量 feed+flush，同 LazyNormalizer） */
    private fun oneShotThinkSplit(content: String): Pair<String?, List<String>> {
        if (content.isEmpty()) return null to emptyList()
        val splitter = ThinkSplitter()
        val segments = splitter.feed(content) + splitter.flush()
        val thinking = segments.filter { it.isReasoning }
            .joinToString("\n") { it.text }.takeIf { it.isNotEmpty() }
        val text = segments.filter { !it.isReasoning }.map { it.text }.filter { it.isNotEmpty() }
        return thinking to text
    }

    /** 行附件 → 内核附件模型（imageBase64 优先，其次 videoFrames，再次 file——同 mapper 读侧） */
    private fun entityAttachments(entity: MessageEntity): List<Attachment> = listOfNotNull(
        entity.imageBase64?.let { Attachment.Image(it) },
        entity.videoFrames?.let { Attachment.VideoFrames(frameBase64ListOf(it)) },
        entity.fileName?.let { Attachment.File(it, entity.fileContent.orEmpty()) },
    )

    /** 旧列语义的附件摘要：文件标签由 fileName 驱动，videoFrames 只贡献帧数（单标签） */
    private fun entityAttachmentSummaries(entity: MessageEntity): List<AttachmentSummary> =
        entity.fileName?.let { name ->
            listOf(AttachmentSummary(name, entity.videoFrames?.let { frameBase64ListOf(it).size }))
        }.orEmpty()

    private fun frameBase64ListOf(json: String): List<String> = try {
        gson.fromJson(json, Array<String>::class.java)?.toList().orEmpty()
    } catch (_: Exception) {
        emptyList()
    }

    private fun Attachment.summary(): AttachmentSummary? = when (this) {
        is Attachment.Image -> null // 图片走 UiMessage.images 独立通道（imageBase64 保留首图兼容）
        is Attachment.File -> AttachmentSummary(name, null)
        is Attachment.VideoFrames -> AttachmentSummary(null, framesBase64.size)
    }

    private fun entitySender(entity: MessageEntity): SenderInfo? =
        if (entity.senderModel == null && entity.senderDisplayName == null && entity.senderColorHex == null) null
        else SenderInfo(
            name = entity.senderDisplayName.orEmpty(),
            model = entity.senderModel,
            colorHex = entity.senderColorHex,
        )
}
