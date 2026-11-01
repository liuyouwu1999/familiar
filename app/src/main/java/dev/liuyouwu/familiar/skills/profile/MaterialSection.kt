package dev.liuyouwu.familiar.skills.profile

/**
 * 圆桌学习材料的统一渲染段（Task 5，spec §3）：建室可选材料经 skillParams.material
 * 落库后，两处注入缝（[dev.liuyouwu.familiar.viewmodel.SkillHost.roundFor] 角色
 * prompt 尾部 + [dev.liuyouwu.familiar.agent.MultiAgentOrchestrator.planRound] 的
 * systemPrompt 尾部）共用同一渲染——口径前缀钉死"讨论以此为准"，trim 后空 = 无
 * 材料（返回 null，调用方零拼接，缺席路径与现状逐字节一致）。
 *
 * 纯 JVM 对象（无 Android 依赖），同 [LearningProfileLogic] 等 profile 件一样可单测。
 */
object MaterialSection {
    const val MAX_LEN = 2000   // spec §3：原定4000挤爆4096历史预算，砍半+圆桌maxContext提8192

    fun section(material: String?): String? {
        val m = material?.trim().orEmpty()
        if (m.isEmpty()) return null
        return "圆桌学习材料（讨论以此为准，与材料冲突时以材料为准）：\n$m"
    }
}
