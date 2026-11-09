package dev.liuyouwu.familiar.ui.model

/*
 * Ui 层投影模型（审计 A1，ui→data.local 收口）：ui 包不再 import data.local，
 * Entity 在 VM/Repo 出口经 `XxxEntity.toUi()`（data 层 UiMappers）折成本组模型后
 * 才进 Ui。字段 = 各屏**实际消费的列**（现场投影，不搬运全量实体）；
 * mapper 在 VM 层调用（与 T3.6 衔接，本任务先做类型透传）。
 */

/** 系统提示词模板投影（模板选择/管理/填充三屏消费：id/name/content/isDefault） */
data class PromptTemplateUi(
    val id: Long = 0,
    val name: String,
    val content: String,
    val isDefault: Boolean = false,
)

/**
 * 人设卡投影：设置页预设管理（编辑回填含 selfName/address 隐性保留）+ 聊天室
 * 建室/编辑页（快捷换卡 + 保存为预设）。tagBlock/tagGloss/createdAt 无 UI 消费方，
 * 不进投影（保存路径 id=0 走新插，语义与 Entity 默认一致）。
 */
data class PersonaPresetUi(
    val id: Long = 0,
    val name: String,
    val personality: String,
    val colorHex: String = "#1976D2",
    // ===== 人设卡 v2 字段（空串=未选卡/旧数据）=====
    val identity: String = "",
    val traits: String = "",
    val speechStyle: String = "",
    val catchphrase: String = "",
    val exampleDialogs: String = "",
    /** 编辑弹窗隐性保留（无输入框，保存原样回传防抹掉） */
    val selfName: String = "我",
    val address: String = "主人",
)

/**
 * 会话行投影（技能参数管理屏的参数编辑 + 列表删除确认）：skillParams 原样 JSON 串，
 * 解析归消费方（SkillManagementScreen.stringParams 与 SkillHost 同口径）。
 */
data class ConversationUi(
    val id: Long,
    val title: String,
    val updatedAt: Long = 0,
    val skillId: String? = null,
    val skillParams: String? = null,
)

/**
 * 聊天室成员投影（编辑页初始化草稿源）：id/conversationId/selfName/address/sortOrder
 * 无编辑页消费方，不进投影（保存走 ParticipantDraft 先删后插，非按 id 更新）。
 */
data class ChatRoomParticipantUi(
    val modelName: String,
    val displayName: String,
    val personality: String = "",
    val colorHex: String = "#1976D2",
    // ===== 人设卡 v2 字段（编辑保存先删后插不丢）=====
    val identity: String = "",
    val traits: String = "",
    val speechStyle: String = "",
    val catchphrase: String = "",
    val exampleDialogs: String = "",
)

/**
 * 消息行投影（[MessageRenderModel.fromEntity] 旧链适配的消费形态）：即 MessageEntity
 * 中渲染决策实际读取的列（conversationId/timestamp/toolMeta 渲染不用，不进投影）；
 * 字段名与行列同名。
 */
data class MessageUi(
    val id: Long = 0,
    val role: String,
    val content: String,
    val imageBase64: String? = null,
    val fileName: String? = null,
    val fileContent: String? = null,
    val videoFrames: String? = null,
    val senderModel: String? = null,
    val senderDisplayName: String? = null,
    val senderColorHex: String? = null,
    val replyToMessageId: Long? = null,
    val replyToContent: String? = null,
    val replyToRole: String? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val cachedTokens: Int? = null,
    /** 拟人节奏分段（JSON 数组；null=整条渲染） */
    val segments: String? = null,
    /** Agent 内核 P2 结构化列（旧行天然 null） */
    val reasoning: String? = null,
    val blocks: String? = null,
    val turnId: String? = null,
)
