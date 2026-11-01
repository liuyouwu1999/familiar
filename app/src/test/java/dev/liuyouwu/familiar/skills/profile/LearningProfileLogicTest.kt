// LearningProfileLogicTest.kt
package dev.liuyouwu.familiar.skills.profile

import dev.liuyouwu.familiar.data.local.entity.LearningProfileEntryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LearningProfileLogicTest {
    private val day = 24L * 60 * 60 * 1000
    private fun entry(status: String = "UNDERSTOOD", rc: Int = 0) = LearningProfileEntryEntity(
        id = 1, topic = "二次函数", point = "顶点式", status = status, sourceRoomId = 9,
        createdAt = 0, lastReviewedAt = 0, nextReviewAt = day, reviewCount = rc,
    )

    // —— 遗忘曲线：先自增再移位（spec §1）——
    @Test fun `答对间隔序列 2-4-8-16-21 天封顶`() {
        val now = 100L
        assertEquals(2 * day, LearningReview.applyResult(entry(), pass = true, now).nextReviewAt - now)
        assertEquals(4 * day, LearningReview.applyResult(entry(rc = 1), pass = true, now).nextReviewAt - now)
        // 答对后 newRc = rc+1，间隔 = 2^newRc：rc=2→8 天、rc=3→16 天（brief 原稿此两行 rc 错标 +1，按 spec 钉死公式勘误）
        assertEquals(8 * day, LearningReview.applyResult(entry(rc = 2), pass = true, now).nextReviewAt - now)
        assertEquals(16 * day, LearningReview.applyResult(entry(rc = 3), pass = true, now).nextReviewAt - now)
        assertEquals(21 * day, LearningReview.applyResult(entry(rc = 5), pass = true, now).nextReviewAt - now)
        assertEquals(21 * day, LearningReview.applyResult(entry(rc = 9), pass = true, now).nextReviewAt - now)
    }

    @Test fun `答对 reviewCount 自增且 STUCK 翻成 UNDERSTOOD`() {
        val r = LearningReview.applyResult(entry(status = "STUCK", rc = 2), pass = true, now = 5)
        assertEquals(3, r.reviewCount); assertEquals("UNDERSTOOD", r.status); assertEquals(5, r.lastReviewedAt)
    }

    @Test fun `答错重置一天 卡点 打回`() {
        val r = LearningReview.applyResult(entry(rc = 4), pass = false, now = 7)
        assertEquals(day, r.nextReviewAt - 7); assertEquals("STUCK", r.status); assertEquals(4, r.reviewCount)
    }

    @Test fun `新建条目 一天后到期`() {
        val e = LearningReview.newEntry("二次函数", "顶点式", "UNDERSTOOD", roomId = 9, now = 3)
        assertEquals(day, e.nextReviewAt - 3); assertEquals(0, e.reviewCount)
    }

    // —— 词元匹配（spec §5：课题词元 ⊆ 条目文本）——
    @Test fun `课题词元命中 topic 或 point 子串`() {
        val e = entry().copy(topic = "统计基础", point = "抽样调查的样本量决定结论可靠性")
        assert(ProfileMatcher.select("统计 基础 学习", listOf(e)).isNotEmpty())
        assert(ProfileMatcher.select("抽样调查", listOf(e)).isNotEmpty())   // 词元命中 point
        assert(ProfileMatcher.select("微积分", listOf(e)).isEmpty())
    }

    @Test fun `中文无标点课题整串成词`() {
        val e = entry().copy(topic = "二次函数图像")
        assert(ProfileMatcher.select("二次函数图像", listOf(e)).isNotEmpty())
    }

    @Test fun `到期催拍判定 到期且未归档且过静默期`() {
        val now = 1_000_000L
        val e = entry().copy(nextReviewAt = now - 1, remindedAt = now - 2 * 60 * 60 * 1000)
        assert(LearningReview.isNudgeable(e, now))
        assert(!LearningReview.isNudgeable(e.copy(nextReviewAt = now + 1), now))             // 未到期
        assert(!LearningReview.isNudgeable(e.copy(remindedAt = now - 30 * 60 * 1000), now))  // 2h 内催过
        assert(!LearningReview.isNudgeable(e.copy(archived = true), now))
    }

    @Test fun `归档与限量`() {
        val es = (0 until 12).map { entry().copy(id = it.toLong(), archived = it % 2 == 0, createdAt = -it.toLong()) }
        assertEquals(6, ProfileMatcher.select("顶点式", es).size)          // 只取未归档，createdAt 最近优先 ≤8
    }

    // —— 渲染（spec §5 格式逐字）——
    @Test fun `渲染段格式 空返回null`() {
        assertNull(ProfileRenderer.render(emptyList()))
        val text = ProfileRenderer.render(listOf(entry().copy(point = "顶点式"), entry(status = "STUCK").copy(id = 2, point = "对称轴")))
        assertEquals("【学习画像】\n已懂：顶点式\n卡点：对称轴\n（据此衔接讲解/追问，不必重复该用户已懂内容）", text)
    }
}
