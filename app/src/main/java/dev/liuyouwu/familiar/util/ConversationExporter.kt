package dev.liuyouwu.familiar.util

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dev.liuyouwu.familiar.data.local.dao.MessageExportRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会话导出器：Markdown / JSON 两种格式，供 UI 层分享/备份用。
 *
 * - 思考块（<think>…</think>）保留原文，由阅读方自行取舍；
 * - Markdown 头部含会话名 / 模型 / 导出时间，消息按 `## 用户` / `## 助手（角色名）` 分节；
 * - JSON 字段名固定（camelCase），便于外部工具解析。
 */
object ConversationExporter {

    // serializeNulls：token/模型等为 null 的字段也输出显式 null，字段名对消费方保持稳定
    private val gsonPretty: Gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    /** JSON 导出的单条消息结构（字段名稳定，不随实体演进漂移） */
    private data class ExportMessage(
        val role: String,
        val sender: String?,
        val model: String?,
        val timestamp: Long,
        val content: String,
        val promptTokens: Int?,
        val completionTokens: Int?,
        val cachedTokens: Int?
    )

    /** JSON 导出的顶层结构：元数据 + 消息数组 */
    private data class ExportDocument(
        val title: String,
        val model: String?,
        val exportedAt: String,
        val exportedAtMillis: Long,
        val messageCount: Int,
        val messages: List<ExportMessage>
    )

    /** 导出为 Markdown：会话名 + 模型 + 导出时间头部，每条消息一节 */
    fun toMarkdown(title: String, modelName: String?, messages: List<MessageExportRow>): String {
        val sb = StringBuilder()
        sb.append("# ").append(title).append("\n\n")
        sb.append("- 模型：").append(modelName?.takeIf { it.isNotBlank() } ?: "未知").append("\n")
        sb.append("- 导出时间：").append(dateFormat.format(Date())).append("\n")
        sb.append("- 消息数：").append(messages.size).append("\n\n")
        sb.append("---\n")
        for (msg in messages) {
            sb.append("\n")
            sb.append(
                when {
                    msg.role == "user" -> "## 用户"
                    !msg.senderDisplayName.isNullOrBlank() -> "## 助手（${msg.senderDisplayName}）"
                    else -> "## 助手"
                }
            )
            sb.append("\n\n")
            sb.append(msg.content.ifBlank { "（空）" })
            sb.append("\n\n---\n")
        }
        return sb.toString()
    }

    /** 导出为 JSON（缩进美化）：元数据 + 消息数组 */
    fun toJson(title: String, modelName: String?, messages: List<MessageExportRow>): String {
        val doc = ExportDocument(
            title = title,
            model = modelName?.takeIf { it.isNotBlank() },
            exportedAt = dateFormat.format(Date()),
            exportedAtMillis = System.currentTimeMillis(),
            messageCount = messages.size,
            messages = messages.map { msg ->
                ExportMessage(
                    role = msg.role,
                    sender = msg.senderDisplayName,
                    model = msg.senderModel,
                    timestamp = msg.timestamp,
                    content = msg.content,
                    promptTokens = msg.promptTokens,
                    completionTokens = msg.completionTokens,
                    cachedTokens = msg.cachedTokens
                )
            }
        )
        return gsonPretty.toJson(doc)
    }
}
