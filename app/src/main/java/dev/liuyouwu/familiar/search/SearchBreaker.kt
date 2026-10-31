package dev.liuyouwu.familiar.search

/**
 * 搜索源熔断器：连续失败 ≥2 次则熔断 10 分钟，熔断期内调用方不再发起请求（省 5s 连接超时，
 * 也避免每次搜索都拖慢总时长）；成功一次即复位计数。
 * 单线程调用（MultiSource 在合并前统一记账），@Volatile 保证跨协程可见性即可，无需加锁。
 * now 以 lambda 注入：时间窗行为可用假时钟确定性单测。
 */
class SearchBreaker(
    private val failureThreshold: Int = 2,
    private val coolDownMs: Long = 10 * 60 * 1000L,
    private val now: () -> Long = System::currentTimeMillis
) {
    @Volatile private var consecutiveFailures = 0
    @Volatile private var brokenUntil = 0L

    /** 熔断期内 true；冷却窗过后自动放行（半开——再失败一次即重新熔断，计数仍在）。 */
    val isBroken: Boolean
        get() = now() < brokenUntil

    fun recordSuccess() {
        consecutiveFailures = 0
        brokenUntil = 0L
    }

    fun recordFailure() {
        consecutiveFailures++
        if (consecutiveFailures >= failureThreshold) brokenUntil = now() + coolDownMs
    }
}
