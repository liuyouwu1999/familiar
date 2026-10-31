package dev.liuyouwu.familiar.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    @ColumnInfo(defaultValue = "")
    val systemPrompt: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0")
    val isChatRoom: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val isPinned: Boolean = false,
    val pinnedAt: Long? = null,
    // AI 学习课程配置（JSON，null = 普通会话）
    val tutorConfig: String? = null,
    // 学习进度状态（JSON，客户端维护，每次请求注入提示词防失忆）
    val tutorState: String? = null,
    // Agent 内核 P2：会话绑定的 skill（v16 迁移由 TypeInferer 反推回填；null=旧数据）
    val skillId: String? = null,
    // skill 运行参数（JSON，由 Agent 内核按需写入）
    val skillParams: String? = null
)
