package dev.liuyouwu.familiar

import dev.liuyouwu.familiar.search.SearchBreaker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 熔断器单测：假时钟注入 now，时间窗行为确定性验证（不 sleep）。 */
class SearchBreakerTest {

    @Test
    fun `opens only after threshold consecutive failures`() {
        val b = SearchBreaker(failureThreshold = 2, coolDownMs = 600_000, now = { 0L })
        assertFalse(b.isBroken)
        b.recordFailure()
        assertFalse(b.isBroken)   // 1 次失败不熔断（偶发抖动太常见）
        b.recordFailure()
        assertTrue(b.isBroken)
    }

    @Test
    fun `success resets failure count`() {
        val b = SearchBreaker(failureThreshold = 2, coolDownMs = 600_000, now = { 0L })
        b.recordFailure()
        b.recordSuccess()
        b.recordFailure()
        assertFalse(b.isBroken)   // 成功打断连续计数，单次失败不再熔断
    }

    @Test
    fun `cool down window expiry allows retry and refail reopens`() {
        var t = 0L
        val b = SearchBreaker(failureThreshold = 2, coolDownMs = 100L, now = { t })
        b.recordFailure()
        b.recordFailure()
        assertTrue(b.isBroken)
        t = 99L
        assertTrue(b.isBroken)    // 冷却窗内仍熔断
        t = 100L
        assertFalse(b.isBroken)   // 窗过自动放行（半开）
        b.recordFailure()
        assertTrue(b.isBroken)    // 半开期再失败即重新熔断
    }
}
