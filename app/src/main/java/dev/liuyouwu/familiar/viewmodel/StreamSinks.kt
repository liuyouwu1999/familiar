package dev.liuyouwu.familiar.viewmodel

/**
 * 流式状态分组（审计 A3）：ChatScreen 以 combine 四流合一收集，消除逐流 collect
 * 的重复订阅。落位 viewmodel/StreamSinks.kt（与 T3.6 的 StreamingSink 同址）——
 * T3.6 做 VM 侧合并时由 VM 直接产出本类型，UI 消费面不变。
 */
data class StreamingState(
    val isStreaming: Boolean,
    val text: String,
    val reasoning: String,
    val toolStatus: String?,
)

/**
 * 生成用量分组（审计 A3）：顶栏速率/Token 计数的四流合一。
 * nullable 语义 = "本回合未上报/为 0" 视为缺省（合并处 takeIf{>0} 过滤，
 * 与拆分前各 UI 位 `> 0` 判定同口径）。
 */
data class StreamStats(
    val tps: Double?,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val cachedTokens: Int?,
)
