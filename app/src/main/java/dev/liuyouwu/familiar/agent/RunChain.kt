package dev.liuyouwu.familiar.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel

/**
 * 轮链（spec §5.2a）：一次用户输入开启一条链，链上所有 run（含 onRunEnd 发起的
 * 续讲/废稿重试后续 run）挂同一 [scope]；下一条用户输入开新链（预算/查询集/深度
 * 全部重置）。链状态（[context]）挂链对象本身，按 conversationId 隔离、进程内保留。
 *
 * 构造签名即 Task 10 前向壳的定稿形状（自 AgentHooks.kt 迁入并扩充）。
 */
class RunChain(val conversationId: String, val scope: CoroutineScope) {

    /** 链上下文：搜索预算/已搜查询去重集/续讲深度计数（autoContinue 判定用，Task 14）。 */
    val context = ChainContext()

    /**
     * 取消整链：取消 [scope] 的 Job——run 执行中（AgentLoop 的 isActive 检查与工具
     * 执行的 withContext）、工具执行中、run 间隙的后续 run 全部即刻停止。
     * scope 无 Job 时抛 IllegalStateException（CoroutineScope 工厂恒带 Job）。
     */
    fun cancel() = scope.cancel()
}

/**
 * 链级采样参数（B2-L1）：openChain 时由 AgentSession 构造位供应商求值播种；
 * null 字段 = LlmRequest 对应键不发送（宁缺勿滥）。仅 familiar 配置非空。
 */
data class SamplingParams(
    val temperature: Double? = null,
    val frequencyPenalty: Double? = null,
    val presencePenalty: Double? = null,
)

/**
 * 链级可变状态（spec §5.2a chainContext）。
 * - [searchBudgetRemaining]：链搜索预算（默认 2）。AgentLoop 装配预算回路
 *   （LlmRequest.budgetLeft）并在受门工具成功执行后递减；
 * - [searchedQueries]：本链已搜查询去重集（装配层写，工具内不查——spec §7）；
 * - [depth]：onRunEnd 发起后续 run 的深度计数（autoContinue.maxDepth 判定，Task 14）；
 * - [sampling]：链级采样参数（B2-L1）：openChain 单点播种，AgentLoop 请求装配
 *   消费（null 字段 = LlmRequest 对应键不发送）。
 */
class ChainContext(
    var searchBudgetRemaining: Int = 2,
    val searchedQueries: MutableSet<String> = mutableSetOf(),
    var depth: Int = 0,
    var sampling: SamplingParams? = null,
)
