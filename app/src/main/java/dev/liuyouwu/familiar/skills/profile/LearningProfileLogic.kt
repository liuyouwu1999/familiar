// LearningProfileLogic.kt
package dev.liuyouwu.familiar.skills.profile

import dev.liuyouwu.familiar.data.local.entity.LearningProfileEntryEntity

/** 遗忘曲线简化版（spec §1）：答对先自增再移位 1天 shl reviewCount，21 天封顶；答错重置 1 天打回卡点 */
object LearningReview {
    const val DAY_MS = 24L * 60 * 60 * 1000
    private const val MAX_INTERVAL_DAYS = 21L

    fun newEntry(topic: String, point: String, status: String, roomId: Long, now: Long) =
        LearningProfileEntryEntity(
            topic = topic, point = point, status = status, sourceRoomId = roomId,
            createdAt = now, lastReviewedAt = 0, nextReviewAt = now + DAY_MS,
        )

    /** 复习拍催提醒判定（spec §6）：到期 + 未归档 + 距上次提醒 ≥2h。DAO due() 只管前两个条件，2h 静默期在此纯函数 */
    fun isNudgeable(e: LearningProfileEntryEntity, now: Long, gapMs: Long = 2L * 60 * 60 * 1000): Boolean =
        e.nextReviewAt <= now && !e.archived && now - e.remindedAt >= gapMs

    fun applyResult(e: LearningProfileEntryEntity, pass: Boolean, now: Long): LearningProfileEntryEntity =
        if (pass) e.copy(
            reviewCount = e.reviewCount + 1,
            lastReviewedAt = now,
            nextReviewAt = now + minOf(1L shl (e.reviewCount + 1), MAX_INTERVAL_DAYS) * DAY_MS,
            status = "UNDERSTOOD",
        ) else e.copy(
            lastReviewedAt = now,
            nextReviewAt = now + DAY_MS,
            status = "STUCK",
        )
}

/** 课题词元（≥2 字）与条目 topic/point 子串匹配（spec §5）。入参列表须已按 createdAt DESC */
object ProfileMatcher {
    fun tokens(topic: String): List<String> =
        topic.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }

    fun matches(topic: String, e: LearningProfileEntryEntity): Boolean =
        tokens(topic).any { e.topic.contains(it, ignoreCase = true) || e.point.contains(it, ignoreCase = true) }

    fun select(topic: String, entries: List<LearningProfileEntryEntity>, limit: Int = 8): List<LearningProfileEntryEntity> =
        entries.filter { !it.archived && matches(topic, it) }.take(limit)
}

object ProfileRenderer {
    fun render(entries: List<LearningProfileEntryEntity>): String? {
        if (entries.isEmpty()) return null
        val ok = entries.filter { it.status == "UNDERSTOOD" }.map { it.point }
        val stuck = entries.filter { it.status == "STUCK" }.map { it.point }
        return buildString {
            append("【学习画像】\n")
            if (ok.isNotEmpty()) append("已懂：").append(ok.joinToString("；")).append('\n')
            if (stuck.isNotEmpty()) append("卡点：").append(stuck.joinToString("；")).append('\n')
            append("（据此衔接讲解/追问，不必重复该用户已懂内容）")
        }
    }
}
