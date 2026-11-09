package dev.liuyouwu.familiar.viewmodel.skillhooks

import dev.liuyouwu.familiar.agent.AgentHooks
import dev.liuyouwu.familiar.agent.Verdict
import dev.liuyouwu.familiar.core.llm.model.ContentBlock
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ToolGateKeeper 行为测试（审计 S2，TDD RED 先行）：
 * - 白名单 host（http_get）直接放行，无需 UI 在场；
 * - 无订阅者（UI 未挂载）默认拒绝——无 UI 场景默认拒是安全底线；
 * - 订阅后 respond(DENY) → Block 回填；
 * - respond(ALLOW_ALWAYS) → saveAllowedHosts 回写 host，同 host 二次直放；
 * - 订阅但不 respond → approvalTimeoutMs 超时 Block；
 * - 等待被取消 → finally 清 pendingApproval（无悬挂审批）；
 * - wrap(hooks)：非拦四名直通、拦四名交闸门。
 *
 * 订阅者模拟：launch(start = UNDISPATCHED) 收集 pendingApproval——UNDISPATCHED
 * 保证 collect 注册（subscriptionCount+1）后才返回主流程，闸门的订阅计数判定无竞态。
 * ToolCall 构造是三参 (id, name, argsJson)（core/llm/model/Blocks.kt，位置参数勿错位）。
 */
class ToolGateKeeperTest {

    private fun httpGet(url: String) =
        ContentBlock.ToolCall("t1", "http_get", """{"url":"$url"}""")

    @Test
    fun `白名单 host 直接放行`() = runBlocking {
        val gate = ToolGateKeeper(loadAllowedHosts = { setOf("api.github.com") })
        // 无订阅者也不影响：白名单命中在订阅判定之前
        assertEquals(Verdict.Allow, gate.beforeToolCall(httpGet("https://api.github.com/x")))
    }

    @Test
    fun `无订阅者默认拒绝`() = runBlocking {
        val gate = ToolGateKeeper()
        val verdict = gate.beforeToolCall(httpGet("https://evil.example.com/data"))
        assertTrue("无前台订阅者必须拒绝（无 UI 场景默认拒）", verdict is Verdict.Block)
        assertTrue((verdict as Verdict.Block).reason.contains("前台"))
    }

    @Test
    fun `订阅后拒绝回填 Block`() = runBlocking {
        val gate = ToolGateKeeper()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.pendingApproval.collect { if (it != null) gate.respond(ToolGateResponse.DENY) }
        }
        val verdict = gate.beforeToolCall(ContentBlock.ToolCall("t1", "clipboard_read", "{}"))
        assertTrue(verdict is Verdict.Block)
        assertNull("收线后挂起请求须清空", gate.pendingApproval.value)
        collector.cancel()
    }

    @Test
    fun `ALLOW_ALWAYS 回写白名单`() = runBlocking {
        var allowed = emptySet<String>()
        val saved = mutableListOf<Set<String>>()
        val gate = ToolGateKeeper(
            loadAllowedHosts = { allowed },
            saveAllowedHosts = { hosts -> saved += hosts; allowed = hosts },
        )
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.pendingApproval.collect { if (it != null) gate.respond(ToolGateResponse.ALLOW_ALWAYS) }
        }
        assertEquals(Verdict.Allow, gate.beforeToolCall(httpGet("https://api.github.com/y")))
        assertEquals("回写收到 host 白名单", listOf(setOf("api.github.com")), saved)
        // 同 host 二次调用：白名单直放——collector 已撤订，若仍走询问路径会落无订阅拒绝
        collector.cancel()
        assertEquals(Verdict.Allow, gate.beforeToolCall(httpGet("https://api.github.com/z")))
    }

    @Test
    fun `订阅后不响应超时拒绝`() = runBlocking {
        val gate = ToolGateKeeper(approvalTimeoutMs = 100)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.pendingApproval.collect { /* 不响应：走超时路径 */ }
        }
        val startAt = System.currentTimeMillis()
        val verdict = gate.beforeToolCall(httpGet("https://slow.example.com/x"))
        assertTrue("超时窗口须真实等待（≥90ms）", System.currentTimeMillis() - startAt >= 90)
        assertTrue(verdict is Verdict.Block)
        assertNull("超时后挂起请求须清空", gate.pendingApproval.value)
        collector.cancel()
    }

    @Test
    fun `取消等待后清理挂起请求`() = runBlocking {
        val gate = ToolGateKeeper(approvalTimeoutMs = 60_000)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.pendingApproval.collect { }
        }
        val awaiting = async {
            gate.beforeToolCall(ContentBlock.ToolCall("t1", "clipboard_write", """{"text":"hi"}"""))
        }
        gate.pendingApproval.first { it != null }   // 请求已挂出
        awaiting.cancel()                           // 调用方协程取消（AgentLoop 既有取消路径同型）
        awaiting.join()
        assertNull("取消路径 finally 须清挂起请求", gate.pendingApproval.value)
        collector.cancel()
    }

    @Test
    fun `wrap 拦四名交闸门其余直通`() = runBlocking {
        val gate = ToolGateKeeper()   // 无订阅者：拦四名一律拒
        val wrapped = gate.wrap(object : AgentHooks {})
        assertEquals("非拦截面直通", Verdict.Allow,
            wrapped.beforeToolCall(ContentBlock.ToolCall("t1", "web_search", "{}")))
        assertTrue("拦截面（open_url）交闸门",
            wrapped.beforeToolCall(ContentBlock.ToolCall("t2", "open_url", """{"url":"https://a.com"}"""))
                is Verdict.Block)
    }
}
