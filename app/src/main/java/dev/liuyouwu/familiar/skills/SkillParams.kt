package dev.liuyouwu.familiar.skills

import com.google.gson.Gson
import dev.liuyouwu.familiar.agent.SamplingParams

/**
 * paramsSchema 的运行时读取器（B2-L1）：此前 paramsSchema 为裸 Map 无人消费
 * （SkillManagementScreen KDoc 自认），本对象建立 "schema 默认 + 会话 skillParams
 * 覆写" 的解析链。float 语法 `"float=0.9"`（与既有 "int=2" 同族）。
 */
object SkillParams {

    private val FLOAT_SCHEMA = Regex("""^float=(-?\d+(\.\d+)?)$""")
    private val gson = Gson()

    /** schema 声明的 float 默认值表（仅收 float= 键；非法值跳过并宽容忽略） */
    fun schemaFloats(def: SkillDefinition): Map<String, Double> =
        def.paramsSchema.orEmpty().mapNotNull { (key, spec) ->
            FLOAT_SCHEMA.matchEntire(spec.trim())
                ?.let { m -> m.groupValues[1].toDoubleOrNull()?.let { key to it } }
        }.toMap()

    /** 采样三元组键：resolveFloats 出口统一钳进 OpenAI 兼容合法域 0..2（见下） */
    private val SAMPLING_RANGE_KEYS = setOf("temperature", "frequencyPenalty", "presencePenalty")

    /** 会话 skillParams（JSON 对象，字符串/数值均可——值域全字符串是表单约定，Gson 直读是 Double）覆写默认 */
    fun resolveFloats(def: SkillDefinition, paramsJson: String?): Map<String, Double> {
        val overrides: Map<*, *>? = try {
            paramsJson?.takeIf { it.isNotBlank() }?.let { gson.fromJson(it, Map::class.java) }
        } catch (_: Exception) {
            null
        }
        val resolved = schemaFloats(def).toMutableMap()
        overrides?.forEach { (key, value) ->
            val k = key as? String ?: return@forEach
            if (k !in resolved) return@forEach
            val parsed = when (value) {
                is Number -> value.toDouble()
                is String -> value.toDoubleOrNull()
                else -> null
            }
            if (parsed != null) resolved[k] = parsed
        }
        // 采样钳制（B2 台账：UI 无上界校验）：温度/两惩罚越界收边 0..2——OpenAI 兼容
        // 合法域；UI 标签 0-1 更严，钳制从宽不误伤。其余 float 键不钳、原样透传
        for (key in SAMPLING_RANGE_KEYS) {
            resolved[key]?.let { resolved[key] = it.coerceIn(0.0, 2.0) }
        }
        return resolved
    }

    /** 采样三元组：schema 未声明的键恒 null（= 请求不携带，宁缺勿滥）。 */
    fun samplingOf(def: SkillDefinition, paramsJson: String?): SamplingParams {
        val f = resolveFloats(def, paramsJson)
        return SamplingParams(f["temperature"], f["frequencyPenalty"], f["presencePenalty"])
    }
}
