package dev.liuyouwu.familiar.util

import dev.liuyouwu.familiar.core.llm.client.DefaultLlmClient
import dev.liuyouwu.familiar.core.llm.client.Deps
import dev.liuyouwu.familiar.core.llm.client.LlmRequest
import dev.liuyouwu.familiar.core.llm.client.SseRawEvent
import dev.liuyouwu.familiar.core.llm.marker.MarkerToolAdapter
import dev.liuyouwu.familiar.core.llm.model.AgentMessage
import dev.liuyouwu.familiar.core.llm.model.ContentBlock
import dev.liuyouwu.familiar.core.llm.model.Role
import dev.liuyouwu.familiar.core.llm.model.StreamEvent
import dev.liuyouwu.familiar.core.llm.wire.ToolMode
import dev.liuyouwu.familiar.core.llm.wire.WireConverter
import dev.liuyouwu.familiar.data.remote.api.ConnectionProbe
import dev.liuyouwu.familiar.data.remote.api.Transcriber
import dev.liuyouwu.familiar.core.llm.wire.MessageDto
import dev.liuyouwu.familiar.tools.mcp.McpClient
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files

/**
 * 明文 http 运行时防线（审计 S1+S7）：
 * a) [NetGuards.isPlaintextSafeHost] 判定矩阵——回环/RFC1918/IPv6 回环与链路本地 → true，
 *    公网 IP/域名/越界段 → false。纯字面判定，零 DNS、零真实网络。
 * b) 拒发路径——带 apiKey 的请求组装在「http + 非局域网目标」时抛 [PlaintextKeyRefusal]；
 *    LlmClient 侧照 DiagDumpGateTest 的假 SseSource 搭法（scripted 流注入，收集 stream()
 *    即驱动一次完整请求组装），ConnectionProbe 侧只驱动请求组装不收集冷流（newCall 构造
 *    不发网络）。私网 http 与 https+key 两阴性对照不拒发。
 */
class NetGuardsTest {

    // ===== a) 判定矩阵（brief Step 1 字面） =====

    @Test
    fun `判定矩阵-回环RFC1918与IPv6本地段判安全`() {
        listOf(
            "127.0.0.1",      // IPv4 回环
            "10.0.0.5",       // RFC1918 10/8
            "192.168.1.20",   // RFC1918 192.168/16
            "172.16.0.1",     // RFC1918 172.16/12 段首
            "::1",            // IPv6 回环
            "fe80::1",        // IPv6 链路本地
        ).forEach { host ->
            assertTrue("$host 应判定为明文安全", NetGuards.isPlaintextSafeHost(host))
        }
    }

    @Test
    fun `判定矩阵-公网域名与越界段判不安全`() {
        listOf(
            "8.8.8.8",        // 公网 IP
            "example.com",    // 域名（非 IP 一律不安全，禁止 DNS 解析）
            "172.32.0.1",     // 172.16/12 越界段（172.32 不在 16..31）
        ).forEach { host ->
            assertFalse("$host 应判定为明文不安全", NetGuards.isPlaintextSafeHost(host))
        }
    }

    @Test
    fun `URL级判据-无前缀按http且私网https与输入中途不警示`() {
        // 公网 http（显式/无前缀两种写法）→ 警示
        assertTrue(NetGuards.isPlaintextUnsafeUrl("http://8.8.8.8:8080"))
        assertTrue(NetGuards.isPlaintextUnsafeUrl("8.8.8.8:8080"))
        assertTrue(NetGuards.isPlaintextUnsafeUrl("http://api.example.com/v1"))
        // 私网 http / https 公网 → 不警示
        assertFalse(NetGuards.isPlaintextUnsafeUrl("192.168.1.20:8080"))
        assertFalse(NetGuards.isPlaintextUnsafeUrl("http://[fe80::1]:8080"))
        assertFalse(NetGuards.isPlaintextUnsafeUrl("https://api.example.com"))
        // 输入中途/空串解析不了 → 不打扰（注："192.168." 会被 okhttp 当尾点域名
        // 解析成功且非私网 IP 字面量 → 警示，与旧 isPublicHttpUrl 行为一致）
        assertFalse(NetGuards.isPlaintextUnsafeUrl(""))
        assertFalse(NetGuards.isPlaintextUnsafeUrl("http://"))
    }

    /** 裁定 2：localhost 字面特判回环（spec「loopback → true」，大小写不敏感，零 DNS） */
    @Test
    fun `判定矩阵-localhost字面特判回环安全`() {
        assertTrue(NetGuards.isPlaintextSafeHost("localhost"))
        assertTrue(NetGuards.isPlaintextSafeHost("LOCALHOST"))
        assertTrue(NetGuards.isPlaintextSafeHost("LocalHost"))
        // URL 级：localhost 的 http 不警示
        assertFalse(NetGuards.isPlaintextUnsafeUrl("http://localhost:8080"))
        assertFalse(NetGuards.isPlaintextUnsafeUrl("localhost:11434"))
    }

    // ===== b) 拒发路径 =====

    /** 照 DiagDumpGateTest 的假 SseSource 搭法（scripted 流，不触网） */
    private fun client(
        baseUrl: () -> String,
        apiKey: () -> String,
        opened: () -> Unit = {},
    ): DefaultLlmClient = DefaultLlmClient(
        Deps(
            okHttp = OkHttpClient(),
            sseSource = { _ ->
                opened()
                listOf(SseRawEvent("hi", null, null, null, "stop")).asFlow()
            },
            baseUrl = baseUrl,
            apiKey = apiKey,
            provider = { "" },
        ),
        WireConverter(),
        MarkerToolAdapter(),
    )

    private fun request(): LlmRequest = LlmRequest(
        model = "test-model",
        system = "你是测试助手。",
        history = listOf(AgentMessage("u1", Role.USER, listOf(ContentBlock.Text("问题")))),
        tools = emptyList(),
        mode = ToolMode.NATIVE,
        temperature = null,
        maxTokens = null,
        searchAllowed = false,
        budgetLeft = { true },
    )

    @Test
    fun `拒发路径-http公网加非空key请求组装抛PlaintextKeyRefusal`() {
        var opened = false
        val c = client({ "http://8.8.8.8:8080" }, { "sk-test-123" }) { opened = true }
        val events = runBlocking { c.stream(request()).toList() }
        val err = events.filterIsInstance<StreamEvent.Error>().singleOrNull()
        assertNotNull("必须以 StreamEvent.Error 拒发", err)
        assertTrue(
            "错误必须是 PlaintextKeyRefusal，实际 ${err?.error?.javaClass}",
            err?.error is PlaintextKeyRefusal
        )
        assertEquals("http 且非局域网目标，已拒绝携带 API Key 发起请求", err?.error?.message)
        assertFalse("拒发请求不得出缝（假 SseSource 不得被打开）", opened)
    }

    @Test
    fun `拒发路径-私网http与https加key两阴性对照不拒发`() {
        // 阴性对照一：私网 http + key —— 放行（请求组装完成并出缝，scripted 流正常收回）
        var openedPrivate = false
        val private = client({ "http://192.168.1.20:8080" }, { "sk-test-123" }) { openedPrivate = true }
        val privateEvents = runBlocking { private.stream(request()).toList() }
        assertTrue("私网 http 不得拒发", privateEvents.filterIsInstance<StreamEvent.Error>().isEmpty())
        assertTrue("私网 http 请求应正常出缝", openedPrivate)

        // 阴性对照二：https + 公网 + key —— 加密传输放行
        var openedHttps = false
        val https = client({ "https://api.example.com" }, { "sk-test-123" }) { openedHttps = true }
        val httpsEvents = runBlocking { https.stream(request()).toList() }
        assertTrue("https 不得拒发", httpsEvents.filterIsInstance<StreamEvent.Error>().isEmpty())
        assertTrue("https 请求应正常出缝", openedHttps)
    }

    @Test
    fun `拒发路径-ConnectionProbe带key发http公网抛PlaintextKeyRefusal`() {
        val probe = ConnectionProbe(mutableSetOf(), diagEnabled = { false })
        try {
            // 不收集返回的冷流：抛点在请求组装（Authorization 附头前），组装即触发
            runBlocking {
                probe.sendChatStream(
                    baseUrl = "http://8.8.8.8:8080",
                    model = "test-model",
                    messages = listOf(MessageDto.TextMessage(role = "user", content = "ping")),
                    apiKey = "sk-test-123",
                )
            }
            fail("http 公网 + 非空 key 必须抛 PlaintextKeyRefusal")
        } catch (e: PlaintextKeyRefusal) {
            assertEquals("http 且非局域网目标，已拒绝携带 API Key 发起请求", e.message)
        }
    }

    @Test
    fun `拒发路径-ConnectionProbe私网http与https阴性对照不抛`() {
        val probe = ConnectionProbe(mutableSetOf(), diagEnabled = { false })
        // 只驱动请求组装、不 collect 冷流（newCall 构造不发网络）——不抛即放行
        runBlocking {
            probe.sendChatStream(
                baseUrl = "http://192.168.1.20:8080",
                model = "test-model",
                messages = listOf(MessageDto.TextMessage(role = "user", content = "ping")),
                apiKey = "sk-test-123",
            )
        }
        runBlocking {
            probe.sendChatStream(
                baseUrl = "https://api.example.com",
                model = "test-model",
                messages = listOf(MessageDto.TextMessage(role = "user", content = "ping")),
                apiKey = "sk-test-123",
            )
        }
    }

    /** 裁定 2 钉死：localhost 带 key 放行（http://localhost:8080 是本机回环服务常态写法） */
    @Test
    fun `拒发路径-localhost带key放行`() {
        var opened = false
        val c = client({ "http://localhost:8080" }, { "sk-test-123" }) { opened = true }
        val events = runBlocking { c.stream(request()).toList() }
        assertTrue("localhost 带 key 不得拒发", events.filterIsInstance<StreamEvent.Error>().isEmpty())
        assertTrue("localhost 请求应正常出缝", opened)
    }

    /** 裁定 1：五处出缝共用的拒发闸单点（guardPlaintextCredential）四分支 + MCP 文案区分 */
    @Test
    fun `共用拒发闸-四分支与MCP文案`() {
        // http + 公网 + 非空凭证 → 抛（缺省 what=API Key，与 A/B 出缝文案逐字一致）
        try {
            NetGuards.guardPlaintextCredential("http://8.8.8.8:8080/v1/models", "sk-x")
            fail("http 公网 + 非空 key 必须抛")
        } catch (e: PlaintextKeyRefusal) {
            assertEquals("http 且非局域网目标，已拒绝携带 API Key 发起请求", e.message)
        }
        // E 出缝（McpClient）文案区分：MCP token
        try {
            NetGuards.guardPlaintextCredential("http://mcp.example.com/mcp", "tok", what = "MCP token")
            fail("http 公网 + 非空 MCP token 必须抛")
        } catch (e: PlaintextKeyRefusal) {
            assertEquals("http 且非局域网目标，已拒绝携带 MCP token 发起请求", e.message)
        }
        // 阴性分支：私网 http / localhost / https / 空白凭证 / 解析失败 —— 均不抛
        NetGuards.guardPlaintextCredential("http://192.168.1.20:8080", "sk-x")
        NetGuards.guardPlaintextCredential("http://localhost:8080", "sk-x")
        NetGuards.guardPlaintextCredential("https://8.8.8.8", "sk-x")
        NetGuards.guardPlaintextCredential("http://8.8.8.8:8080", "")
        NetGuards.guardPlaintextCredential("http://8.8.8.8:8080", " ")
        NetGuards.guardPlaintextCredential("::::不是地址::::", "sk-x")
    }

    /** 裁定 1 · D 出缝（Transcriber）：带 key 发 http 公网 → Result.failure(PlaintextKeyRefusal) */
    @Test
    fun `拒发路径-Transcriber带key发http公网返回拒发失败`() {
        val tmp = Files.createTempFile("voice", ".mp4").toFile()
        try {
            val result = runBlocking {
                Transcriber().transcribeAudio("http://8.8.8.8:8080", tmp, "sk-x")
            }
            assertTrue("必须以 Result.failure 拒发", result.isFailure)
            val e = result.exceptionOrNull()
            assertTrue("失败因必须是 PlaintextKeyRefusal，实际 $e", e is PlaintextKeyRefusal)
            assertEquals("http 且非局域网目标，已拒绝携带 API Key 发起请求", e?.message)
        } finally {
            tmp.delete()
        }
    }

    /** 裁定 1 · E 出缝（McpClient）：带 token 发 http 公网 → 抛 PlaintextKeyRefusal（MCP token 文案）。
     *  阴性（回环 + token 正常携带 Authorization）由 McpClientTest 既有 Bearer 断言覆盖 */
    @Test
    fun `拒发路径-McpClient带token发http公网抛PlaintextKeyRefusal`() {
        val mcp = McpClient(serverUrl = "http://8.8.8.8:8080/mcp", bearerToken = "tok")
        try {
            runBlocking { mcp.listTools() }
            fail("http 公网 + 非空 MCP token 必须抛 PlaintextKeyRefusal")
        } catch (e: PlaintextKeyRefusal) {
            assertEquals("http 且非局域网目标，已拒绝携带 MCP token 发起请求", e.message)
        }
    }
}
