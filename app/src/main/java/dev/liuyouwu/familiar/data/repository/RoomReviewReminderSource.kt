package dev.liuyouwu.familiar.data.repository

import android.content.Context
import dev.liuyouwu.familiar.data.local.AppDatabase
import dev.liuyouwu.familiar.pet.ReviewDueEntry
import dev.liuyouwu.familiar.pet.ReviewReminderPort
import dev.liuyouwu.familiar.skills.profile.LearningReview
import kotlinx.coroutines.flow.Flow

/** 复习提醒数据端口（审计 A4）：PetOverlayService 不再直连 Room DAO——
 *  学习画像复习拍/菜单 gate 经 ReviewReminderPort 注入，Room 细节收口在本适配器。
 *  dueCount/dueTop 均带 LearningReview.isNudgeable 的 2h 静默过滤（原 ambientLoop
 *  的 filter 口径）：DAO due() 只管「到期+未归档」，距上次提醒 <2h 的条目不催 */
class RoomReviewReminderSource(context: Context) : ReviewReminderPort {
    private val dao = AppDatabase.getInstance(context).learningProfileDao()

    override fun observeHasActive(): Flow<Boolean> = dao.hasActive()

    override suspend fun dueCount(): Int {
        val now = System.currentTimeMillis()
        return dao.due(now).count { LearningReview.isNudgeable(it, now) }
    }

    override suspend fun dueTop(): ReviewDueEntry? {
        val now = System.currentTimeMillis()
        // 原 ambientLoop 口径：due()（nextReviewAt ASC）内过滤 isNudgeable 后取 first()
        return dao.due(now).firstOrNull { LearningReview.isNudgeable(it, now) }
            ?.let { ReviewDueEntry(id = it.id, topic = it.topic) }
    }

    override suspend fun markReminded(
        id: Long,
        at: Long,
    ) = dao.markReminded(id, at)
}
