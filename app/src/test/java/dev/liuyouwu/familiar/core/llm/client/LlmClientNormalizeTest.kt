package dev.liuyouwu.familiar.core.llm.client

import dev.liuyouwu.familiar.core.llm.marker.MarkerToolAdapter
import dev.liuyouwu.familiar.core.llm.model.AgentMessage
import dev.liuyouwu.familiar.core.llm.model.ContentBlock
import dev.liuyouwu.familiar.core.llm.model.Role
import dev.liuyouwu.familiar.core.llm.model.StreamEvent
import dev.liuyouwu.familiar.core.llm.model.Usage
import dev.liuyouwu.familiar.core.llm.wire.MessageDto
import dev.liuyouwu.familiar.core.llm.wire.ToolMode
import dev.liuyouwu.familiar.core.llm.wire.ToolSpec
import dev.liuyouwu.familiar.core.llm.wire.WireConverter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * DefaultLlmClient 规范化行为（brief 七用例 + 补充：思考独立上限/run 骨架/用量透传）。
 * 全部经 scripted SseSource 注入（可测缝，评审二 P1-3：双字段合并发生在客户端内，
 * 双名优先级在此可覆盖），不触网。
 */
class LlmClientNormalizeTest {

    private val searchSpec = ToolSpec(
        name = "web_search",
        description = "联网搜索",
        parametersJson = """{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}"""
    )

    // ===== 用例 ①：reasoning 双字段名（评审二 P1-3）=====

    @Test
    fun `reasoning dual field names both stream to thinking and reasoningContent wins`() {
        val events = events(
            scripted(
                SseRawEvent(content = null, reasoningContent = "思考A", reasoning = null, toolCallsJson = null, finishReason = null),
                SseRawEvent(content = null, reasoningContent = null, reasoning = "思考B", toolCallsJson = null, finishReason = null),
                // 同一事件双名同时出现：reasoningContent 优先，reasoning 不重复入流
                SseRawEvent(content = "正文", reasoningContent = "C字", reasoning = "D字", toolCallsJson = null, finishReason = null),
            ),
            request()
        )
        val thinking = blockTexts(events, "thinking").joinToString("")
        assertEquals("思考A思考BC字", thinking)
        assertFalse(thinking.contains("D字"))
        assertEquals("正文", blockTexts(events, "text").joinToString(""))
    }

    // ===== 用例 ②：content 内联 <think> 跨分片切分（无 reasoning 字段→ThinkSplitter）=====

    @Test
    fun `content think tag split across fragments routes thinking and text`() {
        val events = events(
            scripted(
                SseRawEvent("<thi", null, null, null, null),
                SseRawEvent("nk>推理段</th", null, null, null, null),
                SseRawEvent("ink>最终答案", null, null, null, null),
            ),
            request()
        )
        assertEquals(listOf("推理段"), blockTexts(events, "thinking"))
        assertEquals(listOf("最终答案"), blockTexts(events, "text"))
    }

    // ===== 用例 ③：原生 tool_calls 分片累积成合法 ToolCall =====

    @Test
    fun `native tool_calls fragments accumulate into valid ToolCall`() {
        val f1 = """[{"index":0,"id":"c1","function":{"name":"web_search","arguments":"{\"que"}}]"""
        val f2 = """[{"index":0,"function":{"arguments":"ry\":\"JEV\"}"}}]"""
        val evs = events(
            scripted(
                SseRawEvent(null, null, null, f1, null),
                SseRawEvent(null, null, null, f2, "tool_calls"),
            ),
            request()
        )
        val turnEnd = evs.filterIsInstance<StreamEvent.TurnEnd>().single()
        assertEquals(1, turnEnd.toolCalls.size)
        val call = turnEnd.toolCalls[0]
        assertEquals("c1", call.id)
        assertEquals("web_search", call.name)
        assertEquals("""{"query":"JEV"}""", call.argsJson)
        // 合法调用以完整 toolCall 块下发（BlockStart + 全量 ToolCallDelta + BlockEnd）
        assertTrue(evs.any { it is StreamEvent.BlockStart && it.type == "toolCall" })
        assertTrue(
            evs.filterIsInstance<StreamEvent.ToolCallDelta>().any { it.argsFragment == """{"query":"JEV"}""" }
        )
    }

    // ===== 用例 ④：argsValid=false 的原生调用 → isError ToolResult 块（评审二 P2-10）=====

    @Test
    fun `native call with invalid json args becomes toolResult block not a ToolCall`() {
        val bad = """[{"index":0,"id":"bad1","function":{"name":"web_search","arguments":"{not-json"}}]"""
        val evs = events(scripted(SseRawEvent(null, null, null, bad, "tool_calls")), request())
        val turnEnd = evs.filterIsInstance<StreamEvent.TurnEnd>().single()
        // 非法调用被丢弃：不进 TurnEnd.toolCalls、不产生 toolCall 块
        assertTrue(turnEnd.toolCalls.isEmpty())
        assertTrue(evs.none { it is StreamEvent.BlockStart && it.type == "toolCall" })
        // 回填 isError 语义的 toolResult 块（AgentLoop 侧重建为 ToolResult(..., isError=true)）
        val results = blockTexts(evs, "toolResult")
        assertEquals(1, results.size)
        assertTrue(results[0].contains("丢弃"))
        assertTrue(results[0].contains("not-json"))
    }

    // ===== 用例 ⑤：marker 命中 → ToolCall + 上游取消 + 仅保留标记前文本 =====

    @Test
    fun `marker hit emits ToolCall cancels upstream and keeps only pre-marker text`() {
        var consumed = 0
        var completedNormally = false
        var cancelCause: Throwable? = null
        val upstream = flowOf(
            SseRawEvent("好的，我先查一下\n", null, null, null, null),
            // 标记行与"同事件标记后文本"同片到达：标记后的内容（无论同片残留还是后续分片）都不得入流
            SseRawEvent("[搜索: JEV]\n同事件标记后文本\n", null, null, null, null),
            SseRawEvent("这段永远不该被消费", null, null, null, null),
        ).onEach { consumed++ }
            .onCompletion { cause -> if (cause == null) completedNormally = true else cancelCause = cause }

        val evs = events(upstream, request(mode = ToolMode.MARKER, tools = listOf(searchSpec)))

        // 上游取消：标记事件之后的分片未被消费，onCompletion 收到取消信号而非正常完成
        assertEquals(2, consumed)
        assertFalse(completedNormally)
        assertTrue(cancelCause is CancellationException)

        val turnEnd = evs.filterIsInstance<StreamEvent.TurnEnd>().single()
        assertEquals(1, turnEnd.toolCalls.size)
        assertEquals("web_search", turnEnd.toolCalls[0].name)
        assertTrue(turnEnd.toolCalls[0].argsJson.contains("JEV"))

        // 标记前文本保留为 Text 块；块内绝无标记行、绝无标记后文本
        //（Task 6 停靠锁：foldHistory 无标记泄漏不变式的执行侧）
        val text = blockTexts(evs, "text").joinToString("")
        assertEquals("好的，我先查一下", text.trim())
        assertTrue(text.lines().none { it.contains("[搜索") })
        assertTrue(blockTexts(evs, "text").none { it.contains("同事件标记后文本") || it.contains("永远不该被消费") })

        // 取消不破坏收尾骨架：RunEnd 兜底
        assertTrue(evs.last() is StreamEvent.RunEnd)
    }

    // ===== 用例 ⑥：预算尽 / 无工具 → 标记行剥除不解析（spec §4.3 硬契约）=====

    @Test
    fun `marker lines stripped not parsed when budget exhausted or no tools`() {
        val script = flowOf(
            SseRawEvent("直接回答。\n", null, null, null, null),
            SseRawEvent("[搜索: 不该执行]\n", null, null, null, null),
            SseRawEvent("结论。\n", null, null, null, null),
        )
        val requests = listOf(
            request(mode = ToolMode.MARKER, tools = listOf(searchSpec), budgetLeft = { false }),
            request(mode = ToolMode.MARKER, tools = emptyList(), budgetLeft = { true }),
        )
        for (req in requests) {
            val evs = events(script, req)
            val turnEnd = evs.filterIsInstance<StreamEvent.TurnEnd>().single()
            assertTrue(turnEnd.toolCalls.isEmpty())
            assertTrue(evs.none { it is StreamEvent.BlockStart && it.type == "toolCall" })
            val text = blockTexts(evs, "text").joinToString("")
            assertEquals("直接回答。\n结论。", text.trim())
            assertFalse(text.contains("[搜索"))
        }
    }

    // ===== 用例 ⑦：文本超限截断 =====

    @Test
    fun `text beyond MAX_RESPONSE_CHARS truncated to exact cap with TurnEnd truncated`() {
        val big = "a".repeat(MAX_RESPONSE_CHARS + 1)
        val evs = events(scripted(SseRawEvent(big, null, null, null, "stop")), request())
        val text = blockTexts(evs, "text").joinToString("")
        assertEquals(MAX_RESPONSE_CHARS, text.length)
        assertTrue(evs.filterIsInstance<StreamEvent.TurnEnd>().single().truncated)
    }

    // ===== P3 T4：searchAllowed 过滤单点（NATIVE tools 注入同过 WireConverter.effectiveTools） =====

    @Test
    fun `native request tools go through searchAllowed single point filter`() {
        fun wireToolNames(searchAllowed: Boolean, names: List<String>): List<String>? {
            val specs = names.map { ToolSpec(it, it, """{"type":"object"}""") }
            val req = request(tools = specs, searchAllowed = searchAllowed)
            return client(scripted(SseRawEvent("答", null, null, null, "stop")))
                .buildChatRequest(req).tools?.map { it.function.name }
        }
        // 允许搜索：tools 原样上 wire
        assertEquals(listOf("web_search"), wireToolNames(searchAllowed = true, names = listOf("web_search")))
        // 禁搜索且只有搜索工具：滤空 → 不发 tools 键（宁缺勿滥）
        assertNull(wireToolNames(searchAllowed = false, names = listOf("web_search")))
        // 禁搜索但混有其它工具：只滤 web_search，其余照发
        assertEquals(listOf("calc"), wireToolNames(searchAllowed = false, names = listOf("web_search", "calc")))
    }

    // ===== P5 清理批：attachTimeContext 与尾部注入附注的顺序钉死（时间附注在新尾注之后）=====

    @Test
    fun `time stamp appends after trailing user note in assembled request`() {
        // ContextBuilder.build 物化的尾部附注（末条 user 消息）进 history 后，
        // attachTimeContext 把时间附注拼到**同一条末条 user 文本之后**——顺序固定
        // 防漂移：模型读感"先业务附注后时间"，位置漂移会扰动弱模型小窗行为
        val req = LlmRequest(
            model = "test-model",
            system = "你是测试助手。",
            history = listOf(
                AgentMessage("u1", Role.USER, listOf(ContentBlock.Text("问题"))),
                // 模拟尾注形态（ContextBuilder noteMessage：TRAILING_NOTE_ID + 末位 user）
                AgentMessage("trailing-note", Role.USER, listOf(ContentBlock.Text("（系统附注：课堂状态段）"))),
            ),
            tools = emptyList(),
            mode = ToolMode.NATIVE,
            temperature = null,
            maxTokens = null,
            searchAllowed = true,
            budgetLeft = { true },
        )
        val chatReq = client(scripted()).buildChatRequest(req)
        // 尾注消息仍是 wire 最后一条；时间附注在同一消息内、附注文本之后
        val last = chatReq.messages.last() as MessageDto.TextMessage
        assertEquals("user", last.role)
        val noteIdx = last.content.indexOf("（系统附注：课堂状态段）")
        val timeIdx = last.content.indexOf("（系统附注：当前真实时间")
        assertTrue("尾注文本应原样保留于末条 user", noteIdx >= 0)
        assertTrue("时间附注应存在", timeIdx >= 0)
        assertTrue("时间附注必须在新尾注之后", timeIdx > noteIdx)
        assertTrue("时间附注应位于消息末尾", last.content.trimEnd().endsWith("）"))
    }

    @Test
    fun `marker lines stripped not parsed when search disallowed`() {
        // 过滤后的标记生效集为空：标记行剥除不解析（用例 ⑥ 的 searchAllowed=false 变体，
        // 守住 markerToolsEffective 走 WireConverter.effectiveTools 单点后的行为不变）
        val script = flowOf(
            SseRawEvent("直接回答。\n", null, null, null, null),
            SseRawEvent("[搜索: 不该执行]\n", null, null, null, null),
        )
        val evs = events(script, request(mode = ToolMode.MARKER, tools = listOf(searchSpec), searchAllowed = false))
        val turnEnd = evs.filterIsInstance<StreamEvent.TurnEnd>().single()
        assertTrue(turnEnd.toolCalls.isEmpty())
        assertTrue(evs.none { it is StreamEvent.BlockStart && it.type == "toolCall" })
        assertFalse(blockTexts(evs, "text").joinToString("").contains("[搜索"))
    }

    // ===== 对抗审查 B-1 回归（QA 2026-10-31）：禁搜会话的搜索标记必须剥除不执行，
    // 生效集内其它工具的泛化标记照常发射 =====

    @Test
    fun `searchAllowed false strips search marker but keeps generic tool marker`() {
        val readSpec = ToolSpec(
            "read_file", "读文件",
            """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}"""
        )
        // 禁搜：web_search 不在生效集——[搜索:] 剥除不执行（resolveMarkerTool 查生效集，
        // 查 raw 清单就是 B-1 旁路）
        val evs1 = events(
            flowOf(
                SseRawEvent("直接回答。\n", null, null, null, null),
                SseRawEvent("[搜索: 不该执行]\n", null, null, null, null),
                SseRawEvent("结论。\n", null, null, null, null),
            ),
            request(mode = ToolMode.MARKER, tools = listOf(readSpec), searchAllowed = false),
        )
        val turnEnd1 = evs1.filterIsInstance<StreamEvent.TurnEnd>().single()
        assertTrue(turnEnd1.toolCalls.isEmpty())
        val text1 = blockTexts(evs1, "text").joinToString("")
        assertFalse(text1.contains("[搜索"))
        assertTrue(text1.contains("直接回答"))
        // 生效集内其它工具：泛化标记照常发射
        val evs2 = events(
            flowOf(SseRawEvent("[read_file: /etc/hosts]\n", null, null, null, null)),
            request(mode = ToolMode.MARKER, tools = listOf(readSpec), searchAllowed = true),
        )
        val calls2 = evs2.filterIsInstance<StreamEvent.TurnEnd>().single().toolCalls
        assertEquals(1, calls2.size)
        assertEquals("read_file", calls2[0].name)
        assertTrue(calls2[0].argsJson.contains("/etc/hosts"))
    }

    // ===== 补充：思考流独立上限 =====

    @Test
    fun `thinking stream capped independently at MAX_THINKING_CHARS`() {
        val big = "思".repeat(MAX_THINKING_CHARS + 5)
        val evs = events(scripted(SseRawEvent(null, big, null, null, "stop")), request())
        assertEquals(MAX_THINKING_CHARS, blockTexts(evs, "thinking").joinToString("").length)
        assertTrue(evs.filterIsInstance<StreamEvent.TurnEnd>().single().truncated)
        // 思考超限不牵连正文（正文为空也不产生截断外的额外块）
        assertTrue(blockTexts(evs, "text").isEmpty())
    }

    // ===== 补充：run 骨架与用量透传 =====

    @Test
    fun `run envelope is RunStart first RunEnd last carrying usage`() {
        val evs = events(
            scripted(
                SseRawEvent("答", null, null, null, null),
                SseRawEvent(null, null, null, null, "stop", usage = Usage(10, 20, 3)),
            ),
            request()
        )
        assertTrue(evs.first() is StreamEvent.RunStart)
        assertTrue(evs.none { it is StreamEvent.Error })
        val runEnd = evs.filterIsInstance<StreamEvent.RunEnd>().single()
        assertEquals(evs.last(), runEnd)
        assertEquals(Usage(10, 20, 3), runEnd.usage)
    }

    // ===== 生产 SseSource 冒烟（评审修复轮 #1/#2 防线；scripted 测试结构性看不见 close 缺失）=====

    @Test
    fun `production sse source completes after DONE without hanging`() {
        val body = "data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
            "data: [DONE]\n\n"
        val evs = prodEvents(body)
        assertEquals("你", blockTexts(evs, "text").joinToString(""))
        assertFalse(evs.filterIsInstance<StreamEvent.TurnEnd>().single().truncated)
        assertTrue(evs.none { it is StreamEvent.Error })
        // RunEnd 到达 = 生产流在 [DONE] 后正常 close()，未挂起（withTimeout 超时即红）
        assertTrue(evs.last() is StreamEvent.RunEnd)
    }

    @Test
    fun `production sse source flags truncation when EOF has no finish marker`() {
        // 服务器写完分片直接关连接：无 finish_reason、无 [DONE] → 必须上报截断（okHttpSseSource 收尾判据）
        val body = "data: {\"choices\":[{\"delta\":{\"content\":\"半截\"}}]}\n\n"
        val evs = prodEvents(body)
        assertEquals("半截", blockTexts(evs, "text").joinToString(""))
        assertTrue(evs.filterIsInstance<StreamEvent.TurnEnd>().single().truncated)
        assertTrue(evs.last() is StreamEvent.RunEnd)
    }

    @Test
    fun `bare DONE without finish reason is clean completion`() {
        // 审计 P2-7：只发 [DONE] 不发 finish_reason 的网关，
        // 正常完成不得误标截断——与修复轮 #2 字面规格（bare [DONE]→truncated=true）相反，
        // 按原 SSE 解析体语义实现（该解析体已随内核清理 A 删除批退役），分歧已写入任务报告待复核
        val body = "data: {\"choices\":[{\"delta\":{\"content\":\"答\"}}]}\n\ndata: [DONE]\n\n"
        val evs = prodEvents(body)
        assertEquals("答", blockTexts(evs, "text").joinToString(""))
        assertFalse(evs.filterIsInstance<StreamEvent.TurnEnd>().single().truncated)
        assertTrue(evs.last() is StreamEvent.RunEnd)
    }

    // ===== P3 终审：取消贯通断言（collector 取消 → OkHttp call.cancel 到达） =====

    @Test
    fun `collector cancellation propagates to okhttp call cancel`() {
        // 生产 okHttpSseSource 的取消链路钉死（AgentLoop.collectStream 挂链作用域
        // 取消的下游前提）：collect 取消 → callbackFlow awaitClose → call.cancel()。
        // 本地 ServerSocket 保持连接打开不发一字节——读侧阻塞在真实 socket 上，
        // 唯一出口即取消（拦截器仅捕获 call 引用，请求照常走真网络）。
        val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        val accepted = CountDownLatch(1)
        val serverThread = Thread {
            try {
                val socket = server.accept()
                accepted.countDown()
                Thread.sleep(60_000)   // 保持连接打开：不写不关——客户端读阻塞
                socket.close()
            } catch (_: Exception) {
            }
        }
        serverThread.isDaemon = true
        serverThread.start()
        val captured = AtomicReference<Call?>(null)
        val okHttp = OkHttpClient.Builder()
            .addInterceptor { chain ->
                captured.set(chain.call())
                chain.proceed(chain.request())
            }
            .build()
        try {
            runBlocking {
                val events = okHttpSseSource(okHttp)
                    .open(Request.Builder().url("http://127.0.0.1:$port/sse").build())
                val job = launch { events.collect { } }   // 订阅
                val deadline = System.currentTimeMillis() + 5_000
                while ((captured.get() == null || accepted.count == 0L) &&
                    System.currentTimeMillis() < deadline
                ) delay(5)
                assertTrue("连接未建立或未捕获 call", captured.get() != null && accepted.count == 0L)
                delay(100)   // 读侧确实阻塞在空流上

                job.cancelAndJoin()   // collector 取消（AgentLoop 链取消的等价动作）

                val cancelDeadline = System.currentTimeMillis() + 5_000
                while (captured.get()?.isCanceled() != true && System.currentTimeMillis() < cancelDeadline) {
                    delay(10)
                }
                assertTrue("collector 取消必须经 awaitClose 到达 call.cancel()", captured.get()?.isCanceled() == true)
            }
        } finally {
            server.close()   // 关闭服务端 socket，阻塞读解除、线程退出
        }
    }

    // ===== 助手 =====

    private fun scripted(vararg raw: SseRawEvent): Flow<SseRawEvent> = raw.toList().asFlow()

    private fun client(scripted: Flow<SseRawEvent>): DefaultLlmClient = DefaultLlmClient(
        Deps(
            okHttp = OkHttpClient(),
            sseSource = { _ -> scripted },
            baseUrl = { "" },
            apiKey = { "" },
            provider = { "" },
        ),
        WireConverter(),
        MarkerToolAdapter(),
    )

    private fun events(scripted: Flow<SseRawEvent>, req: LlmRequest): List<StreamEvent> =
        runBlocking { client(scripted).stream(req).toList() }

    /**
     * 生产冒烟：裸 ServerSocket 回放一段 SSE 报文（MockWebServer 不可用、android.jar
     * 无 com.sun.httpserver），驱动真实 okHttpSseSource 完整接线（enqueue→行循环→close）。
     * withTimeout 是挂起防线——close() 缺失时 collect 永久挂起，测试以超时红掉而非无声吊死。
     */
    private fun prodEvents(body: String): List<StreamEvent> {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val response = ("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n" +
            "Content-Length: ${bodyBytes.size}\r\nConnection: close\r\n\r\n")
            .toByteArray(Charsets.ISO_8859_1) + bodyBytes
        val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        val serverThread = Thread {
            try {
                server.accept().use { s ->
                    s.soTimeout = 10_000
                    val input = s.getInputStream()
                    val head = StringBuilder()
                    val buf = ByteArray(8192)
                    while (!head.contains("\r\n\r\n")) {
                        val n = input.read(buf)
                        if (n < 0) break
                        head.append(String(buf, 0, n, Charsets.ISO_8859_1))
                    }
                    // 请求体按 Content-Length 读完——未读数据就关 socket 会 RST 吃掉响应
                    val sep = head.indexOf("\r\n\r\n")
                    val len = Regex("Content-Length:\\s*(\\d+)", RegexOption.IGNORE_CASE)
                        .find(head)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    var remaining = len - (if (sep >= 0) head.length - sep - 4 else 0)
                    while (remaining > 0) {
                        val n = input.read(buf)
                        if (n < 0) break
                        remaining -= n
                    }
                    s.getOutputStream().apply {
                        write(response)
                        flush()
                    }
                }
            } catch (_: Exception) {
            }
        }
        serverThread.isDaemon = true
        serverThread.start()
        try {
            val client = DefaultLlmClient(
                Deps(
                    okHttp = OkHttpClient(),
                    sseSource = okHttpSseSource(OkHttpClient()),
                    baseUrl = { "http://127.0.0.1:$port" },
                    apiKey = { "" },
                    provider = { "" },
                ),
                WireConverter(),
                MarkerToolAdapter(),
            )
            return runBlocking { withTimeout(15_000) { client.stream(request()).toList() } }
        } finally {
            server.close()
        }
    }

    private fun request(
        mode: ToolMode = ToolMode.NATIVE,
        tools: List<ToolSpec> = emptyList(),
        budgetLeft: () -> Boolean = { true },
        searchAllowed: Boolean = true,
    ): LlmRequest = LlmRequest(
        model = "test-model",
        system = "你是测试助手。",
        history = listOf(AgentMessage("u1", Role.USER, listOf(ContentBlock.Text("问题")))),
        tools = tools,
        mode = mode,
        temperature = null,
        maxTokens = null,
        searchAllowed = searchAllowed,
        budgetLeft = budgetLeft,
    )

    /** 提取指定类型块的拼接文本（按 BlockStart(type)/BlockDelta/BlockEnd 分组） */
    private fun blockTexts(evs: List<StreamEvent>, type: String): List<String> {
        val out = mutableListOf<String>()
        val open = StringBuilder()
        var tracking = false
        for (e in evs) when (e) {
            is StreamEvent.BlockStart -> if (e.type == type) {
                tracking = true; open.setLength(0)
            }
            is StreamEvent.BlockDelta -> if (tracking) open.append(e.delta)
            is StreamEvent.BlockEnd -> if (tracking) {
                out.add(open.toString()); tracking = false
            }
            else -> {}
        }
        return out
    }
}
