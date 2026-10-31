package dev.liuyouwu.familiar.core.llm.model

sealed interface Attachment {
    data class Image(val base64: String) : Attachment
    data class VideoFrames(val framesBase64: List<String>) : Attachment
    data class File(val name: String, val text: String) : Attachment
}

data class SenderInfo(val name: String, val model: String?, val colorHex: String?)
data class ReplyRef(val messageId: Long?, val preview: String, val role: String)

data class MessageMeta(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val cachedTokens: Int? = null,
) { companion object { val NONE = MessageMeta() } }

data class AgentMessage(
    val id: String,
    val role: Role,
    val blocks: List<ContentBlock> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    val sender: SenderInfo? = null,
    val replyTo: ReplyRef? = null,
    val meta: MessageMeta = MessageMeta.NONE,
    val turnId: String? = null,
)
