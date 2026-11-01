package dev.liuyouwu.familiar.skills.profile

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import dev.liuyouwu.familiar.data.local.entity.LearningProfileEntryEntity

data class ExistingUpdate(val existingId: Long, val point: String, val status: String)
data class EntryCreate(val point: String, val status: String)
data class SummaryExtraction(
    val summary: String,
    val understood: List<String> = emptyList(),
    val stuck: List<String> = emptyList(),
    val updates: List<ExistingUpdate> = emptyList(),
    val creates: List<EntryCreate> = emptyList(),
)

/** 提取→DAO 指令（spec §2：update 校验 existing 存在，create 全收；纯函数执行侧 upsert） */
sealed interface Instruction {
    data class Insert(val point: String, val status: String) : Instruction
    data class Update(val id: Long, val point: String, val status: String) : Instruction
}

object RoundtableSummaryParser {
    private val gson = Gson()
    private val legal = setOf("UNDERSTOOD", "STUCK")

    fun parse(raw: String): SummaryExtraction? = runCatching {
        val json = raw.substringAfterFirstBrace().substringBeforeLastBrace()
        val dto = gson.fromJson(json, Dto::class.java)
        val summary = dto.summary?.trim().orEmpty()
        if (summary.isEmpty()) return null
        SummaryExtraction(
            summary = summary,
            understood = dto.understood.orEmpty().filter { it.isNotBlank() },
            stuck = dto.stuck.orEmpty().filter { it.isNotBlank() },
            updates = dto.updates.orEmpty().mapNotNull { u ->
                val id = u.existingId ?: 0L
                val st = norm(u.status) ?: return@mapNotNull null
                val p = u.point?.trim().orEmpty()
                if (id <= 0 || p.isEmpty()) return@mapNotNull null
                ExistingUpdate(id, p, st)
            },
            creates = dto.creates.orEmpty().mapNotNull { c ->
                val st = norm(c.status) ?: return@mapNotNull null
                val p = c.point?.trim().orEmpty()
                if (p.isEmpty()) return@mapNotNull null
                EntryCreate(p, st)
            },
        )
    }.getOrNull()

    /** spec §2 节流：新增 ≥8 条才跑（1 小时时长校验在调用方查小结消息时间戳） */
    fun shouldAutoSummarize(newMessagesSinceLast: Int): Boolean = newMessagesSinceLast >= 8

    /** 提取→DAO 指令推导：updates 里 existingId 不在 existing 中的丢弃（防幻觉 id），creates 全收 */
    fun toDaoInstructions(ex: SummaryExtraction, existing: List<LearningProfileEntryEntity>): List<Instruction> {
        val ids = existing.map { it.id }.toSet()
        return ex.updates.filter { it.existingId in ids }
            .map { Instruction.Update(it.existingId, it.point, it.status) } +
            ex.creates.map { Instruction.Insert(it.point, it.status) }
    }

    private fun norm(s: String?): String? = s?.trim()?.uppercase()?.takeIf { it in legal }

    private fun String.substringAfterFirstBrace(): String = substring(indexOf('{').coerceAtLeast(0))
    private fun String.substringBeforeLastBrace(): String = substring(0, lastIndexOf('}') + 1)

    private class Dto(
        val summary: String? = null,
        val understood: List<String>? = null,
        val stuck: List<String>? = null,
        val updates: List<UDto>? = null,
        val creates: List<CDto>? = null,
    )

    private class UDto(
        @SerializedName("existingId") val existingId: Long? = null,
        @SerializedName("point") val point: String? = null,
        @SerializedName("status") val status: String? = null,
    )

    private class CDto(
        @SerializedName("point") val point: String? = null,
        @SerializedName("status") val status: String? = null,
    )
}
