package dev.liuyouwu.familiar

import dev.liuyouwu.familiar.data.local.entity.PersonaPresetEntity
import dev.liuyouwu.familiar.util.persona.PersonaCard
import dev.liuyouwu.familiar.util.persona.PersonaPromptBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaPromptBuilderTest {

    private val card = PersonaCard(
        name = "小鱼", identity = "一只住在路由器里的小鱼精灵，蓝发像素尾巴",
        traits = "傲娇但心软；聪明爱摸鱼；被说胖会炸毛", speechStyle = "短句、口语、偶尔内心OS",
        catchphrase = "哼，才不是为你", exampleDialogs = "<START>\n主人：在吗\n小鱼：在。有事说事。\n<START>\n主人：帮我看看这段话\n小鱼：行吧，看在小鱼今天心情好的份上。",
        selfName = "小鱼", address = "主人", tagBlock = "", tagGloss = "",
    )

    @Test fun `同一卡同一模式字节稳定`() =
        assertEquals(PersonaPromptBuilder.build(card, "default"), PersonaPromptBuilder.build(card, "default"))

    @Test fun `强制身份与称呼注入`() {
        val p = PersonaPromptBuilder.build(card, "default")
        assertTrue(p.contains("你自称「小鱼」"))
        assertTrue(p.contains("称呼用户为「主人」"))
        assertTrue(p.contains("无论人物设定、历史记录、记忆信息还是语境如何"))
    }

    @Test fun `标签块自动生成与自定义覆盖`() {
        val auto = PersonaPromptBuilder.build(card, "default")
        assertTrue(auto.contains("【PERSONA_LOAD】"))
        assertTrue(auto.contains("（解读："))
        val custom = PersonaPromptBuilder.build(card.copy(tagBlock = "MY_TAGS", tagGloss = "自定义解读"), "default")
        assertTrue(custom.contains("MY_TAGS") && custom.contains("自定义解读"))
    }

    @Test fun `三模式指令称呼占位化`() {
        val p = PersonaPromptBuilder.build(card, "proactive")
        assertTrue(p.contains("不得连续两轮雷同"))
        assertTrue(p.contains("主人"))
        assertTrue(!p.contains("{address}"))
        assertTrue(PersonaPromptBuilder.build(card, "passive").contains("非必要不主动搭话"))
    }

    @Test fun `铁律八条与记忆说明齐备`() {
        val p = PersonaPromptBuilder.build(card, "default")
        for (kw in listOf("铁律", "沉浸规则", "似曾相识", "人设自检", "性格成长", "提问选项", "记忆说明", "严禁编造")) {
            assertTrue("缺：$kw", p.contains(kw))
        }
    }

    @Test fun `鲜活度文案与 familiar 模板收敛一致`() {
        // 终审 I-1：build 内联字面量与 Task 11 后 familiar.json 同语义（开场变化/选项限频/时间感倾向化）
        val p = PersonaPromptBuilder.build(card, "default")
        assertTrue(p.contains("开场句式每轮都要变化，禁止连续两轮用相同的词语开头"))
        assertTrue(p.contains("仅当主人的消息确实需要小鱼给出选择或澄清时"))
        assertTrue(p.contains("连续两轮给选项算失误，能不给就不给"))
        assertTrue(p.contains("深夜可以惦记主人要不要休息"))
        assertTrue(p.contains("昨晚说过的今晚换个讲法"))
        assertTrue(!p.contains("2-3 个选项"))
        assertTrue(!p.contains("深夜催"))
    }

    @Test fun `示例对话与口头禅进入提示词`() {
        val p = PersonaPromptBuilder.build(card, "default")
        assertTrue(p.contains("<START>"))
        assertTrue(p.contains("哼，才不是为你"))
    }

    @Test fun `空名卡回落默认名且无占位残留`() {
        val blank = PersonaCard.from(PersonaPresetEntity(name = "", personality = "外向"))
        val p = PersonaPromptBuilder.build(blank, "default")
        assertTrue(p.contains("你是「蓝色大肥鱼」"))   // fallbackName 默认生效
        assertTrue(!p.contains("{address}") && !p.contains("{selfName}"))
    }

    @Test fun `称呼覆写生效-设置优先级`() {
        // Global Constraints：设置覆盖卡值（assembleFamiliar 处接线，这里锁 PersonaCard 语义）
        val p = PersonaPromptBuilder.build(card.copy(address = "大人", selfName = "小鱼酱"), "default")
        assertTrue(p.contains("称呼用户为「大人」"))
        assertTrue(p.contains("自称「小鱼酱」"))
        assertTrue(!p.contains("「主人」"))
    }

    @Test fun `memoryNote false 时不输出记忆说明行`() {
        val p = PersonaPromptBuilder.build(card, "default", memoryNote = false)
        assertTrue(!p.contains("记忆说明"))
        assertTrue(p.contains("铁律"))   // 其余结构完整
    }

    @Test fun `群聊模式不强制每轮称呼`() {
        // 群聊复用同一 builder：称呼保留为设定值，但不再要求每句都喊——
        // 「每次回复必须…称呼用户」是大肥鱼单聊特性，真人群聊不会每句 @ 同一个人
        val p = PersonaPromptBuilder.build(card, "default", memoryNote = false, addressEveryTurn = false)
        assertTrue(!p.contains("每次回复必须以「小鱼」自称、以「主人」称呼用户"))
        assertTrue(!p.contains("绝不使用其他任何自称或称呼"))
        assertTrue(p.contains("你自称「小鱼」"))          // 自称约束保留
        assertTrue(p.contains("主人"))                     // 称呼值仍作为设定注入
        // 两处措辞都按参数切换，不再残留单聊强制句
        assertTrue(!p.contains("称呼用户为「主人」。这是强制性设定"))
    }

    @Test fun `群聊模式同参字节稳定`() =
        assertEquals(
            PersonaPromptBuilder.build(card, "default", memoryNote = false, addressEveryTurn = false),
            PersonaPromptBuilder.build(card, "default", memoryNote = false, addressEveryTurn = false),
        )
}
