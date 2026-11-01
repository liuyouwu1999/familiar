package dev.liuyouwu.familiar.skills.profile

/**
 * 收桌小结的两段 prompt 文案（spec §2）：SYSTEM 定角色+输出 JSON 契约+纪律，
 * userInput 拼记录/材料/已有画像条目三段（材料空缺省不拼段）。
 */
object RoundtableSummaryTexts {
    const val SYSTEM = """你是圆桌收桌人。阅读圆桌全部记录，产出收桌小结。
只输出一行 JSON：
{"summary":"3-5 句：这次讨论搞清楚了什么、结论是什么",
 "understood":["该用户已确认搞懂的要点，每条一句话"],
 "stuck":["该用户仍未搞懂或讲错的卡点，每条一句话"],
 "updates":[{"existingId":12,"point":"更新后的表述","status":"UNDERSTOOD|STUCK"}],
 "creates":[{"point":"新要点","status":"UNDERSTOOD|STUCK"}]}
规则：只写关于该用户学习状态的事实，不写角色观点；updates 的 existingId 必须来自我给你的
条目列表；列表里没有的新要点放 creates；没有就给空数组；宁缺毋滥，每条必须是具体的一句话。"""

    fun userInput(transcript: String, material: String?, entriesJson: String): String = buildString {
        append("圆桌记录：\n").append(transcript).append('\n')
        if (!material.isNullOrBlank()) append("学习材料：\n").append(material).append('\n')
        append("该课题已有画像条目：").append(entriesJson)
    }
}
