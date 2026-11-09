package dev.liuyouwu.familiar.viewmodel.skillhooks

import com.google.gson.Gson
import com.google.gson.JsonObject
import dev.liuyouwu.familiar.agent.AgentHooks
import dev.liuyouwu.familiar.agent.ContextBuilder
import dev.liuyouwu.familiar.agent.Decision
import dev.liuyouwu.familiar.agent.RunChain
import dev.liuyouwu.familiar.agent.RunResult
import dev.liuyouwu.familiar.agent.Verdict
import dev.liuyouwu.familiar.core.llm.model.AgentMessage
import dev.liuyouwu.familiar.core.llm.model.ContentBlock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI

/**
 * 高危工具确认闸门（审计 S2）：http_get / clipboard_read / clipboard_write /
 * open_url 四件执行前须经用户确认——AgentLoop 的 hooks.beforeToolCall 挂起点
 * 上再设一道人审门（其余工具名不拦，web_search 预算门/文件沙箱各自既有防线）。
 *
 * 交互模型（UI 单点 = ChatScreen）：
 * - [pendingApproval] 挂出在途请求（StateFlow：晚订阅者仍见当前值，请求不丢）；
 *   **订阅计数即前台判定**——`subscriptionCount == 0` 视为无 UI 在场，默认拒绝
 *   （后台/无 UI 场景不得静默放行高危工具，安全底线）；
 * - [respond] 三键回填：ALLOW_ONCE / ALLOW_ALWAYS（host 回写白名单持久化）/
 *   DENY；审批窗口 [approvalTimeoutMs] 内无响应视同拒绝；
 * - 等待经 `withTimeoutOrNull { deferred.await() }`；调用方协程取消时
 *   CancellationException 照常上抛（AgentLoop 既有取消路径收口），finally
 *   清挂起请求并取消 deferred（无悬挂审批、无泄漏）。
 *
 * 线程模型：AgentLoop 内工具调用串行执行，审批门天然单飞（同时至多一个在途
 * deferred）；并发到来时后来者覆盖 pending——当前引擎无并发工具路径。
 *
 * 装配：SkillHost 构造注入（缺省 [allowAll] 全放行，既有测试零改），
 * assembleSession 内 `toolGate.wrap(hooks)` 组合包装（四方法转发 +
 * beforeToolCall 交闸门）。复习侧信道（ReviewChat.ask）与群聊调度请求
 * （MultiAgentOrchestrator.planRound）tools=emptyList() 无工具面，不经此门。
 *
 * 白名单：host 级（url 参数的 URI.host），http_get/open_url 共用（两者同持
 * url 参数，"总是允许"语义对二者一致）；剪贴板两件无 host，恒走逐次询问。
 * 存取经构造注入的挂起闭包（生产 = SettingsRepository 的
 * httpGetAllowedHosts / setHttpGetAllowedHosts，DataStore Set<String>）。
 */
class ToolGateKeeper(
    private val loadAllowedHosts: suspend () -> Set<String> = { emptySet() },
    private val saveAllowedHosts: suspend (Set<String>) -> Unit = {},
    private val approvalTimeoutMs: Long = DEFAULT_APPROVAL_TIMEOUT_MS,
    /** [allowAll] 专用：true = 闸门短路全放行（测试/兜底，不触白名单与订阅判定）。 */
    private val disabled: Boolean = false,
) {
    /** 在途审批请求（null = 无）。UI collect 本流即计为前台监听者在场。 */
    data class ToolApprovalRequest(val tool: String, val summary: String, val host: String?)

    /** 在途审批请求（null = 无）。挂出/清空只在 [beforeToolCall] 的请求段与 finally。 */
    val pendingApproval = MutableStateFlow<ToolApprovalRequest?>(null)

    /** 当前等待 UI 回填的 deferred（串行单飞，见类 KDoc 线程模型）。 */
    private var awaiting: CompletableDeferred<ToolGateResponse>? = null

    /** UI 三键回填口；无在途请求时 no-op（迟到响应/重复点击防御）。 */
    fun respond(verdict: ToolGateResponse) {
        awaiting?.complete(verdict)
    }

    /**
     * 闸门判定（被 [wrap] 织入 AgentHooks.beforeToolCall）：
     * 非拦截面 / 白名单 host 命中 → Allow；无订阅者 → Block（默认拒）；
     * 否则挂请求等 UI 回填或超时。取消（CancellationException）原样上抛，
     * finally 清挂起请求 + 取消 deferred。
     */
    suspend fun beforeToolCall(call: ContentBlock.ToolCall): Verdict {
        if (disabled || call.name !in GATED_TOOLS) return Verdict.Allow
        val host = hostOf(call)
        // 白名单直放（"总是允许"回写面）：在订阅判定之前——已授信 host 不再打扰用户
        if (host != null && host in loadAllowedHosts()) return Verdict.Allow
        // 无前台监听者：默认拒（审计 S2 安全底线——无 UI 场景静默放行高危工具）
        if (pendingApproval.subscriptionCount.value == 0) {
            return Verdict.Block(NO_FRONT_STAGE_REASON)
        }
        val deferred = CompletableDeferred<ToolGateResponse>()
        awaiting = deferred
        pendingApproval.value = ToolApprovalRequest(call.name, summaryOf(call), host)
        return try {
            when (withTimeoutOrNull(approvalTimeoutMs) { deferred.await() }) {
                ToolGateResponse.ALLOW_ONCE -> Verdict.Allow
                ToolGateResponse.ALLOW_ALWAYS -> {
                    // host 回写白名单（剪贴板两件 host=null：不持久化，本次放行即止）
                    if (host != null) saveAllowedHosts(loadAllowedHosts() + host)
                    Verdict.Allow
                }
                ToolGateResponse.DENY -> Verdict.Block(DENY_REASON)
                null -> Verdict.Block(TIMEOUT_REASON) // 审批超时视同拒绝
            }
        } finally {
            pendingApproval.value = null
            awaiting = null
            deferred.cancel() // 已完成时 no-op；超时/取消路径防泄漏
        }
    }

    /**
     * 组合包装：四方法转发原 hooks，beforeToolCall 交闸门（SkillHost.
     * assembleSession 首行 `toolGate.wrap(hooks)` 的唯一消费形态）。
     */
    fun wrap(hooks: AgentHooks): AgentHooks =
        object : AgentHooks {
            override fun transformContext(ctx: ContextBuilder) = hooks.transformContext(ctx)

            override suspend fun beforeToolCall(call: ContentBlock.ToolCall): Verdict = this@ToolGateKeeper.beforeToolCall(call)

            override fun finishTurn(
                turn: Int,
                msgs: List<AgentMessage>,
            ): Decision = hooks.finishTurn(turn, msgs)

            override fun onRunEnd(
                result: RunResult,
                chain: RunChain,
            ) = hooks.onRunEnd(result, chain)
        }

    /** url 参数 → URI.host（解析失败/缺参 → null：无 host 不进白名单路径）。 */
    private fun hostOf(call: ContentBlock.ToolCall): String? =
        urlOf(call.argsJson)?.let { url -> runCatching { URI(url).host }.getOrNull() }

    private fun urlOf(argsJson: String): String? =
        runCatching {
            GSON.fromJson(argsJson, JsonObject::class.java)
                .get("url")?.takeIf { it.isJsonPrimitive }?.asString
        }.getOrNull()

    /** 审批框正文（人话摘要，非裸 argsJson）。 */
    private fun summaryOf(call: ContentBlock.ToolCall): String =
        when (call.name) {
            "http_get" -> "请求网址：${urlOf(call.argsJson) ?: call.argsJson}"
            "open_url" -> "打开链接：${urlOf(call.argsJson) ?: call.argsJson}"
            "clipboard_read" -> "读取系统剪贴板的文本内容"
            "clipboard_write" -> "写入剪贴板：${textPreview(call.argsJson) ?: call.argsJson}"
            else -> call.argsJson
        }

    private fun textPreview(argsJson: String): String? =
        runCatching {
            GSON.fromJson(argsJson, JsonObject::class.java)
                .get("text")?.takeIf { it.isJsonPrimitive }?.asString?.take(80)
        }.getOrNull()

    companion object {
        /** 测试/兜底：全放行闸门（SkillHost 缺省注入——既有测试与未接线装配零改动）。 */
        fun allowAll(): ToolGateKeeper = ToolGateKeeper(disabled = true)

        /** 受门四件（审计 S2 拦截面）：网络两件 + 剪贴板两件；其余工具名直通。 */
        private val GATED_TOOLS = setOf("http_get", "clipboard_read", "clipboard_write", "open_url")

        const val DEFAULT_APPROVAL_TIMEOUT_MS = 60_000L
        const val NO_FRONT_STAGE_REASON = "当前无前台会话界面，工具调用被拒绝"
        const val DENY_REASON = "用户拒绝了本次工具调用"
        const val TIMEOUT_REASON = "用户未确认（审批超时），工具调用被拒绝"

        private val GSON: Gson = Gson()
    }
}

/** UI 三键判定（ChatScreen 审批对话框 → [ToolGateKeeper.respond]）。 */
enum class ToolGateResponse { ALLOW_ONCE, ALLOW_ALWAYS, DENY }
