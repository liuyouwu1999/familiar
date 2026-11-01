// ReviewResultParser.kt
package dev.liuyouwu.familiar.skills.profile

/**
 * 复习收尾 JSON 宽容解析（学习画像 Task 10 Step 1，spec §6）：从教练收尾回复里
 * 抠出 `[...]` 数组逐条取 `entryId`/`pass`。宽容语义（坏数据宁丢勿崩）：
 * - 前后噪声/Markdown 围栏不碍事——按首个 `[` 到末个 `]` 截取（截不出配对括号
 *   的畸形串交 Gson 抛异常，统一落空列表）；
 * - 单条缺字段 / id≤0 / 类型不符 → 丢弃该条，不影响其余条目；
 * - 整体不可解析 → 空列表。**空列表 = 调用方不落账任何条目**（VM 侧幂等红线，
 *   Task 10 报告项：解析失败零写入）。
 *
 * 纯 JVM（无 Android 依赖，ReviewResultParserTest 五例钉死）。
 */
object ReviewResultParser {
    fun parse(raw: String): List<Pair<Long, Boolean>> = runCatching {
        val json = raw.substring(raw.indexOf('['), raw.lastIndexOf(']') + 1)
        val arr = com.google.gson.JsonParser.parseString(json).asJsonArray
        arr.mapNotNull { el ->
            val o = el.asJsonObject
            val id = o.get("entryId")?.takeIf { it.isJsonPrimitive }?.asLong ?: return@mapNotNull null
            val pass = o.get("pass")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: return@mapNotNull null
            if (id <= 0) return@mapNotNull null
            id to pass
        }
    }.getOrDefault(emptyList())
}
