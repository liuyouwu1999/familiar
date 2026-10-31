package dev.liuyouwu.familiar.util.persona

import dev.liuyouwu.familiar.pet.PetSkin
import dev.liuyouwu.familiar.pet.SkinPersona
import org.junit.Assert.assertEquals
import org.junit.Test

class PetSkinsPersonaTest {

    private fun skin(id: String, persona: SkinPersona? = null, accent: String? = "#FF0000", name: String = "测试鱼") =
        PetSkin(id, name, accent, persona, emptyMap(), null, null)

    @Test fun `skin null 落蓝卡`() {
        val got = DefaultPersonaCards.personaOf(null)
        assertEquals(DefaultPersonaCards.DEFAULT_FAMILIAR_BLUE.identity, got.identity)
        assertEquals(DefaultPersonaCards.DEFAULT_FAMILIAR_BLUE.name, got.name)
        assertEquals(DefaultPersonaCards.DEFAULT_FAMILIAR_BLUE.colorHex, got.colorHex)
    }

    @Test fun `内置蓝橘原卡原样`() {
        // 蓝卡逐字段（不整对象 assertEquals：personaOf 经 defaultFamiliar 返回同一实例，
        // 整对象比较是恒真断言零鉴别力——实现误走 copy/改名分支时只有逐字段能红）
        val blue = DefaultPersonaCards.personaOf(skin("blue"))
        assertEquals(DefaultPersonaCards.DEFAULT_FAMILIAR_BLUE.identity, blue.identity)
        assertEquals(DefaultPersonaCards.DEFAULT_FAMILIAR_BLUE.catchphrase, blue.catchphrase)
        assertEquals(DefaultPersonaCards.DEFAULT_FAMILIAR_BLUE.exampleDialogs, blue.exampleDialogs)
        assertEquals(DefaultPersonaCards.DEFAULT_FAMILIAR_BLUE.colorHex, blue.colorHex)
        assertEquals(DefaultPersonaCards.DEFAULT_FAMILIAR_BLUE.name, blue.name)
        // 橘是 DataStore 默认值：逐字段对 DEFAULT_FAMILIAR_ORANGE（防"蓝卡改名"回归，审查 P0-3）
        val got = DefaultPersonaCards.personaOf(skin("orange"))
        assertEquals(DefaultPersonaCards.DEFAULT_FAMILIAR_ORANGE.identity, got.identity)
        assertEquals(DefaultPersonaCards.DEFAULT_FAMILIAR_ORANGE.catchphrase, got.catchphrase)
        assertEquals(DefaultPersonaCards.DEFAULT_FAMILIAR_ORANGE.exampleDialogs, got.exampleDialogs)
        assertEquals(DefaultPersonaCards.DEFAULT_FAMILIAR_ORANGE.colorHex, got.colorHex)
        assertEquals(DefaultPersonaCards.DEFAULT_FAMILIAR_ORANGE.name, got.name)
    }

    @Test fun `自定义带persona字段级回落蓝卡`() {
        val got = DefaultPersonaCards.personaOf(skin("pink", persona = SkinPersona(catchphrase = "粉鲸驾到！")))
        assertEquals("测试鱼", got.name)
        assertEquals("#FF0000", got.colorHex)                       // accent 取皮肤
        assertEquals("粉鲸驾到！", got.catchphrase)                 // persona 有值
        assertEquals(DefaultPersonaCards.DEFAULT_FAMILIAR_BLUE.identity, got.identity)  // 缺省回落蓝卡
        assertEquals(DefaultPersonaCards.DEFAULT_FAMILIAR_BLUE.address, got.address)
    }

    @Test fun `自定义无persona换名不换魂`() {
        val got = DefaultPersonaCards.personaOf(skin("mint", persona = null))
        assertEquals("测试鱼", got.name)
        assertEquals("#FF0000", got.colorHex)
        assertEquals(DefaultPersonaCards.DEFAULT_FAMILIAR_BLUE.identity, got.identity)
        assertEquals(DefaultPersonaCards.DEFAULT_FAMILIAR_BLUE.catchphrase, got.catchphrase)
    }

    @Test fun `自定义persona八字段互异各取皮肤值`() {
        // SkinPersona 八字段各赋唯一值，personaOf 后逐字段断言取到皮肤值（不是蓝卡值）——
        // 封死 personaOf 里 base.copy(...) 字段换位笔误（换位后至少一个字段落蓝卡值即红）
        val p = SkinPersona(
            identity = "甲", personality = "乙", traits = "丙", speechStyle = "丁",
            catchphrase = "戊", exampleDialogs = "己", selfName = "庚", address = "辛",
        )
        val got = DefaultPersonaCards.personaOf(skin("rainbow", persona = p))
        assertEquals("甲", got.identity)
        assertEquals("乙", got.personality)
        assertEquals("丙", got.traits)
        assertEquals("丁", got.speechStyle)
        assertEquals("戊", got.catchphrase)
        assertEquals("己", got.exampleDialogs)
        assertEquals("庚", got.selfName)
        assertEquals("辛", got.address)
    }

    @Test fun `内置名逐字断言`() {
        // 迁移 pet.json 的 name 必须逐字等于内置卡名（spec §3"内置迁移"），此断言是锚
        assertEquals("蓝色大肥鱼", DefaultPersonaCards.DEFAULT_FAMILIAR_BLUE.name)
        assertEquals("中转站的大肥鱼", DefaultPersonaCards.DEFAULT_FAMILIAR_ORANGE.name)
    }
}
