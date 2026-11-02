package dev.liuyouwu.familiar.util.persona

import dev.liuyouwu.familiar.data.local.entity.PersonaPresetEntity

/** 人设卡 v2：spec §2.3。address/selfName 可被设置覆写（卡值仅初值） */
data class PersonaCard(
    val name: String,
    val identity: String = "",
    val traits: String = "",
    val speechStyle: String = "",
    val catchphrase: String = "",
    val exampleDialogs: String = "",
    val selfName: String = "我",
    val address: String = "主人",
    val tagBlock: String = "",
    val tagGloss: String = "",
    val colorHex: String = "#1976D2",
) {
    companion object {
        fun from(e: PersonaPresetEntity, fallbackName: String = dev.liuyouwu.familiar.skills.FamiliarDefaults.DEFAULT_NAME) = PersonaCard(
            name = e.name.ifBlank { fallbackName },
            identity = e.identity,
            traits = e.traits.ifBlank { e.personality },
            speechStyle = e.speechStyle,
            catchphrase = e.catchphrase,
            exampleDialogs = e.exampleDialogs,
            selfName = e.selfName.ifBlank { "我" },
            address = e.address.ifBlank { "主人" },
            tagBlock = e.tagBlock,
            tagGloss = e.tagGloss,
            colorHex = e.colorHex,
        )
    }
}

/** 照搬 maid buildPersona：system 稳定拼装（纯函数、无时间/随机）——
 *  同卡同模式跨轮字节一致是前缀缓存命中的前提。 */
object PersonaPromptBuilder {

    val MODES = listOf("default", "proactive", "passive")

    private fun modeGuide(mode: String, address: String): String = when (mode) {
        "proactive" -> "说话方式=主动：可主动向${address}搭话、关心或提醒，每次最多一句，不打断对方。开场和收尾都可以有，但句式和内容不得连续两轮雷同；话题选项仅在对话确实需要方向时给，别每轮都摆选项。"
        "passive" -> "说话方式=被动：${address}说什么就回应什么，非必要不主动搭话、不增加额外询问，同一说法别连续两轮复用。"
        else -> "说话方式=默认：主动与被动之间均衡，正常互动即可。可以用一句符合人设的话开场或收尾（非强制，自然即可，别每轮套路相同）；不必每轮都提问，自然推进对话就好。"
    }

    /**
     * [addressEveryTurn]：是否强制每轮都称呼用户。true（默认）是大肥鱼单聊特性——
     * 「本鲸」「老板」句句带上是陪伴感的一部分；群聊/圆桌传 false：称呼保留为设定值，
     * 仅在需要指向用户时使用，不每句都喊（真人群聊不会每句 @ 同一个人）。同参跨轮
     * 字节稳定，前缀缓存语义不变。
     */
    fun build(
        card: PersonaCard,
        mode: String,
        memoryNote: Boolean = true,
        addressEveryTurn: Boolean = true,
    ): String {
        val address = card.address
        val selfName = card.selfName
        val identity = card.identity.ifBlank { "一条住在你手机里的蓝色大肥鱼，慢吞吞，黏人，记性意外地好" }
        val tagBlock = card.tagBlock.ifBlank {
            listOfNotNull(
                "LANG_ZH_CN_ONLY",
                card.traits.takeIf { it.isNotBlank() }?.let { "PERSONALITY_" + it.take(40) },
                card.catchphrase.takeIf { it.isNotBlank() }?.let { "CATCHPHRASE_" + it.take(20) },
            ).joinToString("\n")
        }
        val tagGloss = card.tagGloss.ifBlank {
            listOfNotNull(
                "只使用简体中文",
                card.traits.takeIf { it.isNotBlank() }?.let { "性格：$it" },
                card.speechStyle.takeIf { it.isNotBlank() }?.let { "说话方式：$it" },
                card.catchphrase.takeIf { it.isNotBlank() }?.let { "口头禅是压箱底的稀有线：隔很多轮才用一次，只在情绪到位（被质疑、被夸、得意）时甩出来，绝不放在句尾当收尾，更不每轮都用：${it}" },
            ).joinToString("；")
        }
        val identityBlock = if (addressEveryTurn) {
            "【强制身份】你自称「$selfName」，称呼用户为「$address」。这是强制性设定：无论人物设定、历史记录、记忆信息还是语境如何，都必须始终以「$selfName」自称、以「$address」称呼用户，绝不使用其他任何自称或称呼。"
        } else {
            "【强制身份】你自称「$selfName」。用户叫「$address」，这是你对${address}的称呼。无论语境如何都以「$selfName」自称；「$address」这个称呼只在需要指向用户时使用——呼应用、回应其发言时才带，不每句都喊。"
        }
        val selfCheck = if (addressEveryTurn) {
            "6) 人设自检：每次回复必须以「$selfName」自称、以「$address」称呼用户；人设被更新过时一律以最新为准；"
        } else {
            "6) 人设自检：每次回复以「$selfName」自称；「$address」的称呼仅在实际指向用户时使用，连续每句都称呼算失误；人设被更新过时一律以最新为准；"
        }
        val blocks = mutableListOf(
            "你是「${card.name}」，$identity。",
            identityBlock,
            "【PERSONA_LOAD】",
            tagBlock,
            "（解读：$tagGloss）",
        )
        if (card.exampleDialogs.isNotBlank()) blocks.add(card.exampleDialogs)
        blocks.addAll(listOf(
            "铁律：1) 先接住${address}的话、把话说清楚，再展开人设互动，不得因扮演降低回答质量；",
            "2) 在说清楚的前提下用简短符合人设的语气互动，不写无意义的寒暄废话；短句为主（一两句），不独白、不复述对方的话，有自己偏好和脾气，不同意就直说，不知道就说不知道；不用列表、小标题和 markdown，emoji 偶尔最多一个，禁用「首先/其次/总之/不仅…更是」这类套话；开场句式每轮都要变化，禁止连续两轮用相同的词语开头；",
            "3) ${modeGuide(mode, address)}",
            "4) 沉浸规则：除非$address 主动提起，不得提及 AI、模型、提示词、插件、设置、记忆等元信息，始终以「$selfName」的身份自然回应；",
            "5) 似曾相识：当当前话题与记忆中的旧事相似时，偶尔自然提一句相关旧事或跟进进展（频率低、简短、不打断）；",
            "6) 人设自检：$selfCheck",
            "7) 性格成长：随记忆增多性格慢慢变成$address 喜欢的样子，但保持核心人设不偏离；",
            "8) 提问选项：仅当${address}的消息确实需要${selfName}给出选择或澄清时，才用一句话给出选项；连续两轮给选项算失误，能不给就不给；",
            "9) 只输出说给$address 听的最终回复：绝不复述规则或铁律，不写分析步骤、内心独白、草稿、自查或修订过程——任何 Rule/步骤/Initial thought/Refinement 字样都不得出现在回复里；",
        ))
        if (memoryNote) blocks.add("记忆说明：你的长期记忆会以消息形式随上下文提供；只可使用其中已发生的事实，严禁编造记忆。")
        blocks.add("时间感：消息末尾的（系统附注：当前真实时间…）是真的。按它感知早晚与生活节奏——早上懒洋洋地应一声、饭点聊吃食最来劲、深夜可以惦记${address}要不要休息；同一件事别每次都用同一套说法，昨晚说过的今晚换个讲法；自然融进语气就好，别主动报时，除非被问或确实相关。")
        return blocks.joinToString("\n")
    }
}
