package dev.liuyouwu.familiar

import dev.liuyouwu.familiar.core.llm.client.DefaultLlmClient
import dev.liuyouwu.familiar.core.llm.client.Deps
import dev.liuyouwu.familiar.core.llm.client.LlmRequest
import dev.liuyouwu.familiar.core.llm.client.SseRawEvent
import dev.liuyouwu.familiar.core.llm.marker.MarkerToolAdapter
import dev.liuyouwu.familiar.core.llm.model.AgentMessage
import dev.liuyouwu.familiar.core.llm.model.ContentBlock
import dev.liuyouwu.familiar.core.llm.model.Role
import dev.liuyouwu.familiar.core.llm.wire.ToolMode
import dev.liuyouwu.familiar.core.llm.wire.WireConverter
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 诊断转储开关化（审计 S4）：last_request.json 默认不落盘 + 超长截断带标记。
 * 全链路照 LlmClientNormalizeTest 的假 SseSource 搭法（scripted 流注入，不触网），
 * 落盘断言打在真实请求路径上——dumpRequestJson 由 buildHttpRequest 触发，
 * 收集 stream() 即驱动一次完整请求组装。
 *
 * dumpDir 是供应商（() -> File?）而非构造期 File——生产 agentLlm 是 by lazy
 * 单例，开关必须在每次写盘时求值（不得构造期定型），用例③钉死该不变式。
 */
class DiagDumpGateTest {
    /** 载荷超 256K 字符：Gson 序列化后的请求体必超 MAX_DUMP_CHARS=256*1024 */
    private val bigPayload = "x".repeat(300 * 1024)

    private fun client(dumpDir: () -> File?): DefaultLlmClient =
        DefaultLlmClient(
            Deps(
                okHttp = OkHttpClient(),
                sseSource = { _ -> listOf(SseRawEvent("hi", null, null, null, "stop")).asFlow() },
                baseUrl = { "" },
                apiKey = { "" },
                provider = { "" },
                dumpDir = dumpDir,
            ),
            WireConverter(),
            MarkerToolAdapter(),
        )

    private fun request(text: String): LlmRequest =
        LlmRequest(
            model = "test-model",
            system = "你是测试助手。",
            history = listOf(AgentMessage("u1", Role.USER, listOf(ContentBlock.Text(text)))),
            tools = emptyList(),
            mode = ToolMode.NATIVE,
            temperature = null,
            maxTokens = null,
            searchAllowed = false,
            budgetLeft = { true },
        )

    /** 发一次请求并收集完毕（驱动 buildHttpRequest → dumpRequestJson 全路径） */
    private fun runOnce(
        client: DefaultLlmClient,
        text: String,
    ) {
        runBlocking { client.stream(request(text)).toList() }
    }

    @Test
    fun `supplier null 不落盘`() {
        val tmp = Files.createTempDirectory("diagdump").toFile()
        try {
            runOnce(client(dumpDir = { null }), "问题")
            assertFalse(File(tmp, "last_request.json").exists())
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `超长截断带标记`() {
        val tmp = Files.createTempDirectory("diagdump").toFile()
        try {
            runOnce(client(dumpDir = { tmp }), bigPayload)
            val f = File(tmp, "last_request.json")
            assertTrue("开关开时必须落盘", f.exists())
            val text = f.readText()
            // 构造前提成立：截断确实发生过（文件不等于原始全长）
            assertTrue(text.length < 300 * 1024)
            assertTrue("截断必须带标记", text.endsWith("…[truncated]"))
            // 上限 256K 字符 + 截断标记本身
            assertTrue(text.length <= 256 * 1024 + "…[truncated]".length)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `开关翻转同实例每次请求重读供应商`() {
        // agentLlm 是 by lazy 单例：同一 client 实例 + 读可变开关的供应商——
        // 关 → 无文件；开 → 出现；再关 → 不再更新（对应 Step 5 手工验证的
        // "开关→再聊天→文件 mtime 不变"，无真机环境下以内容不变代偿）
        val tmp = Files.createTempDirectory("diagdump").toFile()
        var on = false
        val c = client(dumpDir = { if (on) tmp else null })
        try {
            val f = File(tmp, "last_request.json")
            runOnce(c, "第一问")
            assertFalse("开关关（供应商 null）不得落盘", f.exists())
            on = true
            runOnce(c, "第二问")
            assertTrue("开关开后同实例立即落盘（无需重建 client）", f.exists())
            assertTrue(f.readText().contains("第二问"))
            on = false
            runOnce(c, "第三问")
            val text = f.readText()
            assertTrue("开关再关后文件不得更新", text.contains("第二问"))
            assertFalse(text.contains("第三问"))
        } finally {
            tmp.deleteRecursively()
        }
    }
}
