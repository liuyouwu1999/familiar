package dev.liuyouwu.familiar.usecase

import dev.liuyouwu.familiar.data.repository.ChatRepository
import dev.liuyouwu.familiar.data.repository.SettingsRepository
import dev.liuyouwu.familiar.skills.TypeInferer
import dev.liuyouwu.familiar.util.persona.DefaultPersonaCards

/**
 * 默认会话创建（审计 A1，D6 use case 路线）：AppNavigation 的 launchFamiliarCreation/
 * launchEli5Creation 创建逻辑整体下沉——导航根部（Activity 级）装配单实例，
 * chatlist 页入口与桌宠 PetHub.onOpenChat 根部钩子复用同一份（记忆会话复用语义
 * 单点）。防连点标志与失败上报（ChatViewModel.reportError → 列表页 snackbar/
 * 桌宠 ERROR 桥）留在 AppNavigation 包装层：本类只负责创建本体，失败抛原异常。
 *
 * 不下沉 ChatListViewModel：nav entry 级会出双实例，且 PetHub 根钩子在列表页外
 * 拿不到列表 VM。
 */
class CreateConversationWithDefaults(
    private val repo: ChatRepository,
    private val settings: SettingsRepository,
) {
    /**
     * 创建/进入主精灵陪伴会话（原 launchFamiliarCreation 函数体随迁，行为等价）——
     * 记忆会话复用：记住的会话仍存在则直接复用，不另建；设置播种：建会话时把卡的
     * address/selfName 写进设置（"设置覆盖卡值"体系的初值来源）；skillParams 只存
     * cardId，人设变量派生在 T6 会话装配时做。
     */
    suspend fun createFamiliar(cardId: Long): Long {
        val remembered = settings.getFamiliarConversationId()
        if (remembered != null && repo.getConversationById(remembered) != null) {
            return remembered
        }
        // 人设随皮肤（personaOf 四分支；cardId 参数保留兼容旧调用，恒写 0）
        val preset = DefaultPersonaCards.personaOf(
            dev.liuyouwu.familiar.pet.PetSkinStore.get(settings.getPetColor())
        )
        val id = repo.createConversationWithSkill(
            title = preset.name,
            skillId = TypeInferer.FAMILIAR,
            skillParams = mapOf("cardId" to cardId.toString())
        )
        settings.setFamiliarConversationId(id)
        settings.setFamiliarAddress(preset.address)
        settings.setFamiliarSelfName(preset.selfName)
        return id
    }

    /** 创建「讲人话」解释模式会话（原 launchEli5Creation 函数体随迁）：零参数 skill。 */
    suspend fun createEli5(): Long = repo.createConversationWithSkill(
        title = "讲人话问答",
        skillId = TypeInferer.ELI5,
        skillParams = emptyMap()
    )
}
