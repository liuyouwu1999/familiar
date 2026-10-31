package dev.liuyouwu.familiar.agent

import dev.liuyouwu.familiar.core.llm.model.AgentMessage
import dev.liuyouwu.familiar.core.llm.model.ContentBlock
import dev.liuyouwu.familiar.core.llm.model.Usage

/**
 * Agent 运行期的四个挂点（spec §5.2）。全部带默认空实现：不注入 hooks 时
 * AgentLoop 行为即"裸循环"（工具放行、逐 turn 继续、run 结束无回调）。
 * 四成员签名固定，Task 11（AgentLoop）/ Task 14（AgentSession）直接消费。
 */
interface AgentHooks {
    /** 每轮请求前变换上下文：裁剪历史、注入记忆、收尾护栏置空 tools 等。 */
    fun transformContext(ctx: ContextBuilder) {}

    /** 工具调用闸门：Allow 放行；Block(reason) 拒绝并以 isError ToolResult 回填。 */
    fun beforeToolCall(call: ContentBlock.ToolCall): Verdict = Verdict.Allow

    /** 每个 turn 结束后咨询：CONTINUE 继续循环，END 立即收束（无工具 turn 默认即停，见 AgentLoop）。 */
    fun finishTurn(turn: Int, msgs: List<AgentMessage>): Decision = Decision.CONTINUE

    /** run 结束回调（课堂续讲/废稿重试等后续 run 由这里按收线解析产物发起，挂同一轮链）。 */
    fun onRunEnd(result: RunResult, chain: RunChain) {}
}

sealed interface Verdict {
    object Allow : Verdict
    data class Block(val reason: String) : Verdict
}

enum class Decision { CONTINUE, END }

/**
 * 单次 run 的结果汇总（spec §5.2 评审三 P2-6）。
 * 由 AgentSession 从 AgentLoop 的 LoopOutcome 组装（Task 14）。
 */
data class RunResult(
    val finalText: String,
    val blocks: List<ContentBlock>,
    val usage: Usage,
    val turnCount: Int,
    val truncated: Boolean,
    val cancelled: Boolean,
    /** P-steer（spec §3.1）：run 收线时队列仍有未消费插话（由 LoopOutcome 透传）。 */
    val steeringLeftover: Boolean = false,
)
