package dev.liuyouwu.familiar.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    // conversationId+timestamp 复合索引（审计 P7）：会话内消息读路径（轻量投影/分页/
    // 全量升序读）与备份逐会话查询都按 (conversationId, timestamp) 过滤排序，单列
    // conversationId 索引命中后仍需对组内行按 timestamp 排序——复合索引消除该排序。
    indices = [Index("conversationId"), Index("timestamp"), Index("conversationId", "timestamp")]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val conversationId: Long,
    val role: String,
    val content: String,
    val imageBase64: String? = null,
    val fileName: String? = null,
    val fileContent: String? = null,
    val videoFrames: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val senderModel: String? = null,
    val senderDisplayName: String? = null,
    val senderColorHex: String? = null,
    val replyToMessageId: Long? = null,
    val replyToContent: String? = null,
    val replyToRole: String? = null,
    // 服务端上报的真实 token 用量（收尾块 usage；后端未上报时为 null）
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val cachedTokens: Int? = null,
    // 拟人节奏分段（JSON 数组；null=整条渲染。可空无默认：老消息天然 NULL）
    val segments: String? = null,
    // Agent 内核 P2：结构化回复（JSON；老消息/无对应数据时天然 NULL）
    val reasoning: String? = null,
    val blocks: String? = null,
    val toolMeta: String? = null,
    val turnId: String? = null
)
