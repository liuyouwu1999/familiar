package dev.liuyouwu.familiar.pet

import java.time.Instant
import java.time.ZoneId

/**
 * 相伴数据纯数学（B1 Task 7）：streak/久别问候/相伴天数的历日口径计算。
 * 一律用 epochDay 整数比较，时区口径 = 设备当前时区（改时区即按新时区算
 * "新的一天"，跳变显式接受，spec §5）——不触 Android 类，JVM 单测可跑。
 */
object PetBondMath {

    /**
     * 连续相伴天数（O(1) 更新，spec §5 streakDays）：
     * 今天已计（lastChatDay==today）→ 不变（首见 prev=0 记 1）；昨天→今天 → +1；
     * 断档 ≥1 天 → 重置 1。首次打点（lastChatAt 未设）由调用方直接记 1，不经此。
     */
    fun nextStreak(prev: Int, lastChatDay: Int, today: Int): Int = when {
        lastChatDay == today -> if (prev <= 0) 1 else prev
        today - lastChatDay == 1 -> prev + 1
        else -> 1
    }

    /**
     * 久别问候到期：距上次聊天 ≥2 个历日且今天还没问候过
     * （lastGreetedDay 缺它则"问候后不聊天重连"会重复问候，撑不起当日仅一次，评审 F10）。
     * Long 差值防 Int 溢出口径误判。
     */
    fun shouldGreet(lastChatDay: Int, today: Int, greetedDay: Int?): Boolean =
        today.toLong() - lastChatDay >= 2 && greetedDay != today

    /** 相伴天数：首见当日算第 1 天（含头含尾）；时钟回拨/未见面（firstMetDay>today）夹到 1。 */
    fun companionDays(firstMetDay: Int, today: Int): Int =
        (today - firstMetDay + 1).coerceAtLeast(1)

    /** 毫秒 → 当地历日（zone 默认设备当前时区）；历日是后续一切比较的统一口径。 */
    fun epochDay(millis: Long, zone: ZoneId = ZoneId.systemDefault()): Int =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay().toInt()
}
