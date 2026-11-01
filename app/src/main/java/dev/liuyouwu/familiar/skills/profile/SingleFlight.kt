package dev.liuyouwu.familiar.skills.profile

/**
 * 会话级 in-flight 单飞（spec §2 防重）：check-and-add，exit 后可重进。
 * 小结请求 5-30s 在飞期间，退出/切换挂点再次触发同一会话直接拒绝，不双跑。
 * 纯 JVM 类（无 Android 依赖），synchronized 保证并发正确，可单测。
 */
class SingleFlight {
    private val active = mutableSetOf<Long>()
    fun tryEnter(id: Long): Boolean = synchronized(active) { active.add(id) }
    fun exit(id: Long): Unit = synchronized(active) { active.remove(id) }
}
