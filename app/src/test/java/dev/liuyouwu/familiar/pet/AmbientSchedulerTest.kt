package dev.liuyouwu.familiar.pet

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * AmbientScheduler 纯 Kotlin 策略单测（审计 A4 拆件，TDD 先行）：
 * 防烦窗口（spec §3 判二 60s）、复习拍判定（spec §6）、判一 DONE 收束复位、
 * 时钟/随机注入可控（JVM 无 Android 依赖）。
 */
class AmbientSchedulerTest {

    /** 可控时钟：t 手动推进 */
    private var t = 0L

    /** 到期源 fake：计数 + 调用观测（验证门关时短路不查库） */
    private class FakeDueSource(var count: Int = 0) : ReviewDueSource {
        var calls = 0
        override suspend fun dueCount(): Int {
            calls++
            return count
        }
    }

    private fun scheduler(due: FakeDueSource = FakeDueSource()): AmbientScheduler =
        AmbientScheduler(reviewDue = due, nowMs = { t })

    /** 全开的空闲门：挂窗+已连接+空闲+无泡 */
    private val openGates = AmbientGates(
        visible = true, connected = true, idle = true, bubbleShowing = false,
    )

    // —— 时钟注入可控 ——

    @Test
    fun `时钟注入 显示气泡打点取注入时钟`() {
        val s = scheduler()
        t = 1000L
        s.onBubbleShown()
        assertEquals(1000L, s.lastBubbleShownAt)
        t = 9000L
        s.onSessionSettled()
        assertEquals(9000L, s.ambientResetAt)
    }

    // —— 判二：60s 防烦窗口（spec §3）——

    @Test
    fun `上次气泡后60s内 shouldAmbient 为false`() {
        val s = scheduler()
        t = 60_000L
        s.onBubbleShown()
        t = 60_001L
        assertFalse(s.shouldAmbient(openGates))
        t = 119_999L
        assertFalse(s.shouldAmbient(openGates))
    }

    @Test
    fun `距上次气泡满60s 门全开 shouldAmbient 为true`() {
        val s = scheduler()
        t = 60_000L
        s.onBubbleShown()
        t = 120_000L
        assertTrue(s.shouldAmbient(openGates))
    }

    @Test
    fun `从未冒泡过 应可 ambient`() {
        val s = scheduler()
        // 真机时钟=epoch 大值，lastBubbleShownAt 初值 0 → 差值巨大不在防烦窗；
        // 注入时钟下把 t 拨到现实量级复现同一基线
        t = 1_700_000_000_000L
        assertTrue(s.shouldAmbient(openGates))
    }

    @Test
    fun `门未全开 shouldAmbient 恒false 含有泡态`() {
        val s = scheduler()
        assertFalse(s.shouldAmbient(openGates.copy(visible = false)))
        assertFalse(s.shouldAmbient(openGates.copy(connected = false)))
        assertFalse(s.shouldAmbient(openGates.copy(idle = false)))
        assertFalse(s.shouldAmbient(openGates.copy(bubbleShowing = true)))
    }

    // —— 复习拍判定（spec §6）——

    @Test
    fun `复习到期 dueCount大于0 且窗口开 shouldNudgeReview 为true`() = runTest {
        val due = FakeDueSource(count = 1)
        val s = scheduler(due)
        t = 500_000L
        assertTrue(s.shouldNudgeReview(reminderOn = true, hasEntries = true, gates = openGates))
        assertEquals(1, due.calls)
    }

    @Test
    fun `无到期 dueCount为0 shouldNudgeReview 为false`() = runTest {
        val s = scheduler(FakeDueSource(count = 0))
        assertFalse(s.shouldNudgeReview(reminderOn = true, hasEntries = true, gates = openGates))
    }

    @Test
    fun `复习开关关 有到期也不催`() = runTest {
        val due = FakeDueSource(count = 2)
        val s = scheduler(due)
        assertFalse(s.shouldNudgeReview(reminderOn = false, hasEntries = true, gates = openGates))
        assertEquals("门关应短路，不查到期源", 0, due.calls)
    }

    @Test
    fun `无未归档条目 不催`() = runTest {
        val s = scheduler(FakeDueSource(count = 2))
        assertFalse(s.shouldNudgeReview(reminderOn = true, hasEntries = false, gates = openGates))
    }

    @Test
    fun `复习拍同样受空闲门约束`() = runTest {
        val due = FakeDueSource(count = 2)
        val s = scheduler(due)
        assertFalse(s.shouldNudgeReview(true, true, openGates.copy(visible = false)))
        assertFalse(s.shouldNudgeReview(true, true, openGates.copy(bubbleShowing = true)))
        assertFalse(s.shouldNudgeReview(true, true, openGates.copy(idle = false)))
    }

    // —— 判一：DONE 收束 45s 复位窗口（会话刚收束的下一拍不开口）——

    @Test
    fun `收束后45s内 判一在窗`() {
        val s = scheduler()
        t = 100_000L
        s.onSessionSettled()
        t = 144_999L
        assertTrue(s.inSettleQuietWindow())
        t = 145_000L
        assertFalse(s.inSettleQuietWindow())
    }

    @Test
    fun `从未收束过 判一不在窗`() {
        val s = scheduler()
        // 同上：ambientResetAt 初值 0，现实时钟量级下差值巨大 → 不在复位窗
        t = 1_700_000_000_000L
        assertFalse(s.inSettleQuietWindow())
    }

    // —— 拍间隔：45s 下限 + 0-45s 抖动 ——

    @Test
    fun `beatDelay 落在 45s 到 90s 区间`() {
        for (seed in 1..20) {
            val s = AmbientScheduler(FakeDueSource(), nowMs = { t }, random = Random(seed))
            val d = s.beatDelayMs()
            assertTrue("seed=$seed d=$d", d >= 45_000L && d < 90_000L)
        }
    }
}
