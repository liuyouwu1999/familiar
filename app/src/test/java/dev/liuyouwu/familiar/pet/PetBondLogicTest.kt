package dev.liuyouwu.familiar.pet

import org.junit.Assert.assertEquals
import org.junit.Test

class PetBondLogicTest {

    private var now = 0L
    private val dayMs = 24 * 3600_000L

    @Test
    fun `streak increments on consecutive days resets after gap`() {
        // epochDay 口径 + 设备当前时区（跳变显式接受，spec §5）——用 epochDay 差值表驱动
        assertEquals(1, PetBondMath.nextStreak(prev = 0, lastChatDay = 100, today = 100))       // 首见
        assertEquals(2, PetBondMath.nextStreak(prev = 1, lastChatDay = 100, today = 101))       // 昨天→今天
        assertEquals(2, PetBondMath.nextStreak(prev = 2, lastChatDay = 101, today = 101))       // 今天已计
        assertEquals(1, PetBondMath.nextStreak(prev = 5, lastChatDay = 98, today = 101))        // 断档重置
    }

    @Test
    fun `greeting due only when gap at least 2 days and not greeted today`() {
        assertEquals(true, PetBondMath.shouldGreet(lastChatDay = 98, today = 101, greetedDay = null))
        assertEquals(false, PetBondMath.shouldGreet(lastChatDay = 101, today = 101, greetedDay = null))
        assertEquals(false, PetBondMath.shouldGreet(lastChatDay = 98, today = 101, greetedDay = 101))
    }

    @Test
    fun `companion days count from first met day inclusive`() {
        // 相伴天数口径：首见当日算第 1 天（含头含尾），差值为负（时钟回拨）夹到 1
        assertEquals(1, PetBondMath.companionDays(firstMetDay = 100, today = 100))
        assertEquals(3, PetBondMath.companionDays(firstMetDay = 100, today = 102))
        assertEquals(1, PetBondMath.companionDays(firstMetDay = 102, today = 100))
    }

    @Test
    fun `epochDay follows the zone calendar day`() {
        // 历日口径 = 毫秒 + 指定时区的当地日历日（生产走设备当前时区的默认参）
        val utc8 = java.time.ZoneId.of("UTC+8")
        val utc = java.time.ZoneId.of("UTC")
        assertEquals(0, PetBondMath.epochDay(0L, utc))                  // 纪元原点 = 历日 0
        assertEquals(1, PetBondMath.epochDay(dayMs, utc))               // UTC 历日随整日推进
        assertEquals(1, PetBondMath.epochDay(dayMs, utc8))              // 东八同一毫秒
        assertEquals(0, PetBondMath.epochDay(dayMs - 1, java.time.ZoneId.of("UTC-5")))
    }
}
