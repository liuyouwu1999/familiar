package dev.liuyouwu.familiar.pet

import kotlinx.coroutines.flow.Flow
import kotlin.random.Random

/** 复习到期源（brief 指定形状）：纯 Kotlin 调度器只看得到这一个方法——
 *  实现方（data 层 Room 适配器）自带 isNudgeable 2h 静默过滤口径 */
interface ReviewDueSource {
    suspend fun dueCount(): Int
}

/** 复习提醒数据端口（审计 A4/R6：PetOverlayService 不再直连 Room DAO，经此接口注入）。
 *  AmbientScheduler 依赖其中 ReviewDueSource 子集；实现由 data 层注入 */
interface ReviewReminderPort : ReviewDueSource {
    /** 有未归档学习条目（Room 失效追踪响应式；菜单「复习一下」gate + 复习拍门共用） */
    fun observeHasActive(): Flow<Boolean>

    /** 到期且可催的第一条（原 ambientLoop 口径：due() 按 nextReviewAt ASC，过滤后取 first()）；
     *  只取所需标量（服务不接 Entity）。无则 null */
    suspend fun dueTop(): ReviewDueEntry?

    /** 记催（LearningProfileDao.markReminded 转发） */
    suspend fun markReminded(
        id: Long,
        at: Long,
    )
}

/** 复习拍条目标量（A4：调度/台词只需要 id 与 topic） */
data class ReviewDueEntry(val id: Long, val topic: String)

/** 空闲门（spec §3）：挂窗 + 已连接 + 空闲 + 无泡——ambient 闲聊与复习拍共用的门 */
data class AmbientGates(
    val visible: Boolean,
    val connected: Boolean,
    val idle: Boolean,
    val bubbleShowing: Boolean,
) {
    val allOpen: Boolean get() = visible && connected && idle && !bubbleShowing
}

/**
 * 闲置冒泡策略（审计 A4 拆件：从 PetOverlayService.ambientLoop 抽出，纯 Kotlin JVM 可测）。
 * 只做「该不该开口」的裁定，不做副作用（say/perform/写库仍归 Service）。
 *
 * 拍结构（spec §3）：45s 下限 + 0-45s 抖动；判一 = DONE 收束后 45s 内不开口（复位阈值
 * 取调度下限，之后还有 60s 气泡闸再兜一层）；判二 = 距上次任意气泡显示 <60s 不发——
 * 互动泡/镜像段/收束庆祝后不紧跟着闲聊。复习拍（spec §6）：到期条目优先于闲聊，
 * 同受空闲门约束。
 */
class AmbientScheduler(
    private val reviewDue: ReviewDueSource,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
) {
    /** 距上次任意气泡显示（防烦判二；render 显示气泡时经 onBubbleShown 打点） */
    var lastBubbleShownAt = 0L
        private set

    /** 会话收束（DONE）时间戳（防烦判一；render 收束时经 onSessionSettled 打点） */
    var ambientResetAt = 0L
        private set

    /** 一拍间隔：45s 下限 + 0-45s 抖动 */
    fun beatDelayMs(): Long = 45_000L + random.nextLong(45_000L)

    /** 防烦判一：DONE 后 45s 内的拍作废、重新起算（ambientResetAt 初值 0 → 永不在窗） */
    fun inSettleQuietWindow(): Boolean = nowMs() - ambientResetAt < 45_000L

    /** 防烦判二：距上次任意气泡显示 <60s 不发 */
    fun inBubbleQuietWindow(): Boolean = nowMs() - lastBubbleShownAt < 60_000L

    /** 闲聊判定：空闲门全开 + 判二过窗 */
    fun shouldAmbient(gates: AmbientGates): Boolean = gates.allOpen && !inBubbleQuietWindow()

    /** 复习拍判定：开关 + 有未归档条目 + 空闲门 + 到期数>0。
     *  短路与原实现同序：门不开不查到期源（DAO 查询是最后一道） */
    suspend fun shouldNudgeReview(
        reminderOn: Boolean,
        hasEntries: Boolean,
        gates: AmbientGates,
    ): Boolean = reminderOn && hasEntries && gates.allOpen && reviewDue.dueCount() > 0

    /** render 显示气泡时打点（防烦判二计时） */
    fun onBubbleShown() {
        lastBubbleShownAt = nowMs()
    }

    /** render 收束（DONE）时打点（防烦判一计时） */
    fun onSessionSettled() {
        ambientResetAt = nowMs()
    }
}
