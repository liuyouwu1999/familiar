package dev.liuyouwu.familiar.data.local

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dev.liuyouwu.familiar.core.llm.model.AgentMessage
import dev.liuyouwu.familiar.core.llm.model.Attachment
import dev.liuyouwu.familiar.core.llm.model.BlockCodec
import dev.liuyouwu.familiar.core.llm.model.ContentBlock
import dev.liuyouwu.familiar.core.llm.model.MessageMeta
import dev.liuyouwu.familiar.core.llm.model.ReplyRef
import dev.liuyouwu.familiar.core.llm.model.Role
import dev.liuyouwu.familiar.core.llm.model.SenderInfo
import dev.liuyouwu.familiar.data.local.entity.MessageEntity

/**
 * spec §8.1 行映射双向契约的代码化（Agent 内核 P2 Task 7）。全部纯函数——不触 Room、不触时钟以外
 * 的环境（timestamp 列沿用 [MessageEntity] 默认值，行序由 SQL `(timestamp, id)` 保证）。
 *
 * ## 写侧（AgentMessage → 行）
 * - 普通回复：1 消息 = 1 行（blocks 权威列 = `BlockCodec.toJson`；`content` 投影 = Text 块以 `\n`
 *   拼接；`reasoning` = Thinking 块以 `\n` 拼接，无 Thinking 块为 null）。
 * - 含 ToolCall 的 assistant：**不拆条**，整条 1 行，blocks 原样。
 * - TOOL_RESULT：1 行，role=`"tool"`，content 空投影；`toolMeta` 写工具往返元数据 JSON
 *   （`[{callId,name,argsJson}]`，ToolResult 行 argsJson=null；无工具块的行 toolMeta=null）。
 * - 剧本终稿：**mapper 不拆**——调用方先经 skills 层 ContentProtocol 拆出 turns，逐条构造成 Text 块
 *   AgentMessage（首行附加 Thinking 块、usage 挂首行）后走 [toRow]，各行同 turnId。
 * - 多附件消息（实际仅用户多图）：`toRows` 扇出为 N 行（**1 行 1 附件**），N 行同
 *   user-turnId；首行带 content/blocks/reasoning/sender/replyTo/token，后续行仅附件列
 *   （content=""、blocks="[]"）。[toRow] 对多附件消息 **fail-fast**（require，防静默
 *   丢附件——评审交接 a），完整扇出必须走 [toRows]。
 * - 旧列同步写：role 映射 USER/ASSISTANT/SYSTEM→小写、TOOL_RESULT→`"tool"`；sender 三列
 *   （name↔senderDisplayName、model↔senderModel、colorHex↔senderColorHex）；replyTo 三列
 *   （messageId/preview/role）；token 三列（promptTokens/completionTokens/cachedTokens）。
 * - **segments 列直通**：AgentMessage 不携带 segments——mapper 恒写 null，由调用方
 *   post-mapping 写入（拟人节奏分段，P1 推迟项；持久层职责，见 spec §8.1 "segments 列保留"）。
 *
 * ## 读侧（行 → AgentMessage，[fromRows]）
 * - 入参须已按 `(timestamp, id)` 排序（SQL 保证）；mapper 不重排。
 * - turnId 分组取**连续同值段**：turnId=NULL 的行各自成组（旧数据降级语义，spec §5.4）。
 * - 组内 assistant/TOOL_RESULT/剧本拆条行**保持独立消息**（UI 逐气泡）；TOOL_RESULT 行以
 *   blocks 中的 callId 回配同组的 ToolCall（blocks 权威，toolMeta/reasoning 读侧不消费）。
 * - 仅 user 多行组按 user-turnId 合并回单条：blocks/sender/replyTo/meta 取首行，
 *   attachments 按行序拼接。
 * - blocks==NULL 的旧行（LazyNormalizer 之前的兜底）：回退 content 投影——非空 content →
 *   `[Text(content)]`，空 content → 空块列表。
 * - **id 规则（评审 P1-1）**：产出 `AgentMessage.id = row.id.toString()`（合并组取首行 id）
 *   ——deleteById/replaceRange 以此定位。
 * - 未知 role 字符串 fail-fast 抛 [IllegalArgumentException]（与 RoomMessageStore 的
 *   String→Long 桥接同语义）；blocks/videoFrames JSON 损坏同样让解析异常穿透，不静默吞。
 */
object MessageRowMapper {

    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    /** tool 往返元数据（spec §8.1：callId/name/argsJson；ToolResult 无 argsJson → null） */
    private data class ToolMetaEntry(val callId: String?, val name: String?, val argsJson: String?)

    /** AgentMessage → 行（≤1 附件）；多附件消息 fail-fast（只映射首附件会静默丢其余
     *  附件——数据丢失，评审交接 a）——完整扇出走 [toRows] */
    fun toRow(msg: AgentMessage, conversationId: Long): MessageEntity {
        require(msg.attachments.size <= 1) {
            "toRow 不接受多附件消息（静默丢附件）——完整扇出走 toRows: attachments=${msg.attachments.size}"
        }
        val textProjection = msg.blocks.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }
        val thinkingProjection = msg.blocks.filterIsInstance<ContentBlock.Thinking>().joinToString("\n") { it.text }
        val attachment = msg.attachments.firstOrNull()
        return MessageEntity(
            conversationId = conversationId,
            role = toRoleColumn(msg.role),
            content = if (msg.role == Role.TOOL_RESULT) "" else textProjection,
            imageBase64 = (attachment as? Attachment.Image)?.base64,
            fileName = (attachment as? Attachment.File)?.name,
            fileContent = (attachment as? Attachment.File)?.text,
            videoFrames = (attachment as? Attachment.VideoFrames)?.let { gson.toJson(it.framesBase64) },
            senderModel = msg.sender?.model,
            senderDisplayName = msg.sender?.name,
            senderColorHex = msg.sender?.colorHex,
            replyToMessageId = msg.replyTo?.messageId,
            replyToContent = msg.replyTo?.preview,
            replyToRole = msg.replyTo?.role,
            promptTokens = msg.meta.promptTokens,
            completionTokens = msg.meta.completionTokens,
            cachedTokens = msg.meta.cachedTokens,
            reasoning = thinkingProjection.ifEmpty { null },
            blocks = BlockCodec.toJson(msg.blocks),
            toolMeta = toolMetaOf(msg.blocks),
            turnId = msg.turnId,
            // segments 直通：恒 null，调用方 post-mapping 写
        )
    }

    /** 多附件（用户多图）扇出为 N 行同 turnId；其余消息 1:1（toRow 只收 ≤1 附件） */
    fun toRows(messages: List<AgentMessage>, conversationId: Long): List<MessageEntity> =
        messages.flatMap { msg ->
            if (msg.attachments.size <= 1) {
                listOf(toRow(msg, conversationId))
            } else {
                // 首行 = 首附件走 toRow 契约；后续行：仅附件列 + 同 turnId；content 空投影、blocks 空权威
                val primary = toRow(msg.copy(attachments = msg.attachments.take(1)), conversationId)
                msg.attachments.drop(1).map { attachment ->
                    primary.copy(
                        content = "",
                        imageBase64 = (attachment as? Attachment.Image)?.base64,
                        fileName = (attachment as? Attachment.File)?.name,
                        fileContent = (attachment as? Attachment.File)?.text,
                        videoFrames = (attachment as? Attachment.VideoFrames)?.let { gson.toJson(it.framesBase64) },
                        reasoning = null,
                        blocks = BlockCodec.toJson(emptyList()),
                        toolMeta = null,
                        senderModel = null,
                        senderDisplayName = null,
                        senderColorHex = null,
                        replyToMessageId = null,
                        replyToContent = null,
                        replyToRole = null,
                        promptTokens = null,
                        completionTokens = null,
                        cachedTokens = null,
                    )
                }.let { listOf(primary) + it }
            }
        }

    /** 行 → AgentMessage（读侧重建；入参须已按 (timestamp,id) 排序） */
    fun fromRows(rows: List<MessageEntity>): List<AgentMessage> {
        val out = mutableListOf<AgentMessage>()
        var i = 0
        while (i < rows.size) {
            val turnId = rows[i].turnId
            var j = i + 1 // turnId=NULL → 单行自成组
            if (turnId != null) {
                while (j < rows.size && rows[j].turnId == turnId) j++
            }
            val group = rows.subList(i, j)
            out += if (group.size > 1 && group.all { it.role == ROLE_USER_COLUMN }) {
                listOf(mergeUserGroup(group)) // 仅 user 多行组（多图）按 user-turnId 合并
            } else {
                group.map { rowToMessage(it) } // assistant/TOOL_RESULT/拆条行保持独立消息
            }
            i = j
        }
        return out
    }

    /** 新用户输入的 user-turnId 铸造（P1 推迟项在此落地；inputSeq 由调用方保证单调即可，不承诺无空洞） */
    fun newUserTurnId(conversationId: Long, inputSeq: Long): String = "$conversationId:u$inputSeq"

    // ---------------------------------------------------------------- 内部

    private const val ROLE_USER_COLUMN = "user"

    private fun toRoleColumn(role: Role): String = when (role) {
        Role.USER -> "user"
        Role.ASSISTANT -> "assistant"
        Role.SYSTEM -> "system"
        Role.TOOL_RESULT -> "tool"
    }

    private fun fromRoleColumn(column: String): Role = when (column) {
        "user" -> Role.USER
        "assistant" -> Role.ASSISTANT
        "system" -> Role.SYSTEM
        "tool" -> Role.TOOL_RESULT
        else -> throw IllegalArgumentException("unknown role column: $column")
    }

    private fun toolMetaOf(blocks: List<ContentBlock>): String? {
        val entries = blocks.mapNotNull { b ->
            when (b) {
                is ContentBlock.ToolCall -> ToolMetaEntry(b.id, b.name, b.argsJson)
                is ContentBlock.ToolResult -> ToolMetaEntry(b.callId, b.name, null)
                else -> null
            }
        }
        return entries.takeIf { it.isNotEmpty() }?.let { gson.toJson(it) }
    }

    /** 单行 → 独立 AgentMessage（blocks 权威；NULL 回退 content 投影） */
    private fun rowToMessage(row: MessageEntity): AgentMessage = AgentMessage(
        id = row.id.toString(),
        role = fromRoleColumn(row.role),
        blocks = row.blocks?.let(BlockCodec::fromJson) ?: fallbackBlocks(row.content, row.role),
        attachments = rowAttachments(row),
        sender = rowSender(row),
        replyTo = rowReplyTo(row),
        meta = MessageMeta(row.promptTokens, row.completionTokens, row.cachedTokens),
        turnId = row.turnId,
    )

    /** user 多行组（同 user-turnId）合并：正文/元数据取首行，附件按行序拼接 */
    private fun mergeUserGroup(group: List<MessageEntity>): AgentMessage {
        val head = rowToMessage(group.first())
        return head.copy(attachments = group.flatMap { rowAttachments(it) })
    }

    /** 旧行兜底：非空 content → [Text]；TOOL_RESULT/空 content → 空块 */
    private fun fallbackBlocks(content: String, roleColumn: String): List<ContentBlock> =
        if (roleColumn == "tool" || content.isEmpty()) emptyList() else listOf(ContentBlock.Text(content))

    /** 1 行 ≤1 附件：imageBase64 优先，其次 videoFrames，再次 fileName+fileContent */
    private fun rowAttachments(row: MessageEntity): List<Attachment> = listOfNotNull(
        row.imageBase64?.let { Attachment.Image(it) }
            ?: row.videoFrames?.let { Attachment.VideoFrames(gson.fromJson(it, Array<String>::class.java).toList()) }
            ?: (row.fileName?.takeIf { row.fileContent != null }?.let { Attachment.File(it, row.fileContent!!) }),
    )

    private fun rowSender(row: MessageEntity): SenderInfo? =
        if (row.senderModel == null && row.senderDisplayName == null && row.senderColorHex == null) null
        else SenderInfo(
            name = row.senderDisplayName.orEmpty(),
            model = row.senderModel,
            colorHex = row.senderColorHex,
        )

    private fun rowReplyTo(row: MessageEntity): ReplyRef? =
        if (row.replyToMessageId == null && row.replyToContent == null && row.replyToRole == null) null
        else ReplyRef(
            messageId = row.replyToMessageId,
            preview = row.replyToContent.orEmpty(),
            role = row.replyToRole.orEmpty(),
        )
}
