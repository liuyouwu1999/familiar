package dev.liuyouwu.familiar.data.local

/**
 * 消息轻量投影：只含会话列表预览/搜索需要的字段，
 * 绝不把 imageBase64 / videoFrames / fileContent 大字段拉进内存。
 */
data class MessagePreview(
    val conversationId: Long,
    val content: String,
    val timestamp: Long,
    val senderDisplayName: String?
)
