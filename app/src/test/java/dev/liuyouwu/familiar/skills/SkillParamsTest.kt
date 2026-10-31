package dev.liuyouwu.familiar.skills

import dev.liuyouwu.familiar.agent.SamplingParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SkillParamsTest {

    private fun def(schema: Map<String, String>) = SkillDefinition(
        id = "t", name = "t", description = "d",
        systemPrompt = PromptSpec("p"), paramsSchema = schema,
    )
    private val familiarLike = def(mapOf(
        "temperature" to "float=0.9", "frequencyPenalty" to "float=0.4",
        "presencePenalty" to "float=0.3", "searchBudget" to "int=2",
    ))

    @Test
    fun `schema defaults parsed from float= entries`() {
        assertEquals(
            SamplingParams(0.9, 0.4, 0.3),
            SkillParams.samplingOf(familiarLike, null),
        )
    }

    @Test
    fun `paramsJson overrides defaults with string or number values`() {
        // 会话 skillParams 列值域全字符串（SkillManagementScreen 整写）；Gson 直读则是 Double——两种都收
        assertEquals(
            SamplingParams(0.95, 0.4, 0.3),
            SkillParams.samplingOf(familiarLike, """{"temperature":"0.95"}"""),
        )
        assertEquals(
            SamplingParams(0.0, 0.4, 0.3),
            SkillParams.samplingOf(familiarLike, """{"temperature":0}"""),
        )
    }

    @Test
    fun `unparseable override falls back to schema default`() {
        assertEquals(
            SamplingParams(0.9, 0.4, 0.3),
            SkillParams.samplingOf(familiarLike, """{"temperature":"abc"}"""),
        )
    }

    @Test
    fun `skill without float schema yields all-null sampling`() {
        val s = SkillParams.samplingOf(def(emptyMap()), """{"temperature":"0.9"}""")
        assertNull(s.temperature); assertNull(s.frequencyPenalty); assertNull(s.presencePenalty)
    }

    @Test
    fun `sampling floats clamp into openai legal range`() {
        // B2 台账（UI 无上界校验）：温度/两惩罚越界收边——"5"→2.0、"-1"→0.0、惩罚 "9"→2.0。
        // 钳 0..2 是 OpenAI 兼容合法域，UI 标签 0-1 更严、钳制从宽不误伤
        val clamped = def(mapOf(
            "temperature" to "float=0.5", "frequencyPenalty" to "float=0.5",
            "presencePenalty" to "float=0.5",
        ))
        assertEquals(
            SamplingParams(2.0, 2.0, 2.0),
            SkillParams.samplingOf(clamped, """{"temperature":"5","frequencyPenalty":"9","presencePenalty":"9"}"""),
        )
        assertEquals(
            SamplingParams(0.0, 0.5, 0.5),
            SkillParams.samplingOf(clamped, """{"temperature":"-1"}"""),
        )
    }

    @Test
    fun `non sampling floats pass through unclamped`() {
        // 钳制只作用于采样三元组，其余 float 键原样透传
        assertEquals(7.0, SkillParams.resolveFloats(def(mapOf("topP" to "float=7")), null)["topP"]!!, 0.0)
    }
}
