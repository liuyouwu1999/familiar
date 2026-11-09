package dev.liuyouwu.familiar.viewmodel

import dev.liuyouwu.familiar.data.local.entity.ChatRoomParticipantEntity
import dev.liuyouwu.familiar.data.local.entity.ConversationEntity
import dev.liuyouwu.familiar.data.local.entity.MessageEntity
import dev.liuyouwu.familiar.data.local.entity.PersonaPresetEntity
import dev.liuyouwu.familiar.data.local.entity.PromptTemplateEntity
import dev.liuyouwu.familiar.ui.model.ChatRoomParticipantUi
import dev.liuyouwu.familiar.ui.model.ConversationUi
import dev.liuyouwu.familiar.ui.model.MessageUi
import dev.liuyouwu.familiar.ui.model.PersonaPresetUi
import dev.liuyouwu.familiar.ui.model.PromptTemplateUi

/**
 * Entity → Ui 投影 mapper（审计 A1）：VM 出口在边界调用 `toUi()` 折投影，
 * Entity 不再出 VM——ui 包由此与 data.local 解耦（反向 `toEntity()` 仅服务
 * VM 写路径把 Ui 调用入参还原成 DAO 形参，见各函数注）。mapper 住 viewmodel
 * 层（本任务"mapper 在 VM 层调用"）：ui→viewmodel 与 viewmodel→data.local 均
 * 为既有依赖方向，不让 data 层反向 import ui.model。
 */

fun PromptTemplateEntity.toUi(): PromptTemplateUi =
    PromptTemplateUi(id = id, name = name, content = content, isDefault = isDefault)

/** 模板全字段都在投影内（删除路径 isDefault 门 + Room @Delete 按 id），roundtrip 无损 */
fun PromptTemplateUi.toEntity(): PromptTemplateEntity =
    PromptTemplateEntity(id = id, name = name, content = content, isDefault = isDefault)

fun PersonaPresetEntity.toUi(): PersonaPresetUi =
    PersonaPresetUi(
        id = id, name = name, personality = personality, colorHex = colorHex,
        identity = identity, traits = traits, speechStyle = speechStyle,
        catchphrase = catchphrase, exampleDialogs = exampleDialogs,
        selfName = selfName, address = address,
    )

/**
 * 保存路径 id=0 → 新插（自增），v2 字段 + selfName/address 与 UI 侧构造同形；
 * tagBlock/tagGloss/createdAt 不在投影内（新建时落 Entity 默认值，与 UI 既有构造点
 * 的默认一致；删除路径直接走 PersonaPresetDao.deleteById 按 id，不经本函数）。
 */
fun PersonaPresetUi.toEntity(): PersonaPresetEntity =
    PersonaPresetEntity(
        id = id, name = name, personality = personality, colorHex = colorHex,
        identity = identity, traits = traits, speechStyle = speechStyle,
        catchphrase = catchphrase, exampleDialogs = exampleDialogs,
        selfName = selfName, address = address,
    )

fun ConversationEntity.toUi(): ConversationUi =
    ConversationUi(id = id, title = title, updatedAt = updatedAt, skillId = skillId, skillParams = skillParams)

fun ChatRoomParticipantEntity.toUi(): ChatRoomParticipantUi =
    ChatRoomParticipantUi(
        modelName = modelName, displayName = displayName, personality = personality,
        colorHex = colorHex, identity = identity, traits = traits, speechStyle = speechStyle,
        catchphrase = catchphrase, exampleDialogs = exampleDialogs,
    )

/** 渲染决策消费列的直移（MessageRenderModel.fromEntity 的入参投影） */
fun MessageEntity.toUi(): MessageUi =
    MessageUi(
        id = id, role = role, content = content,
        imageBase64 = imageBase64, fileName = fileName, fileContent = fileContent,
        videoFrames = videoFrames,
        senderModel = senderModel, senderDisplayName = senderDisplayName, senderColorHex = senderColorHex,
        replyToMessageId = replyToMessageId, replyToContent = replyToContent, replyToRole = replyToRole,
        promptTokens = promptTokens, completionTokens = completionTokens, cachedTokens = cachedTokens,
        segments = segments, reasoning = reasoning, blocks = blocks, turnId = turnId,
    )
