package dev.liuyouwu.familiar.data.remote.api

import com.google.gson.Gson
import dev.liuyouwu.familiar.core.llm.client.LineTooLongException
import dev.liuyouwu.familiar.core.llm.client.attachTimeContext
import dev.liuyouwu.familiar.core.llm.client.readLineBounded
// wire DTO（ChatRequest/ChatResponse/MessageDto/StreamOptions 等）以星号导入对应用
import dev.liuyouwu.familiar.core.llm.wire.*
import dev.liuyouwu.familiar.util.ApiErrorHint
import dev.liuyouwu.familiar.util.BackendProvider
import dev.liuyouwu.familiar.util.ServerUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 探针事件三变体（内核清理 A Task 6）：体检/连接探测专用的精简事件。与原解析体
 * 事件语义对照（旧事件模型已随 Task 7 删除批退役——探针解析体零运行期依赖）：
 * Content/Reasoning/Done → [Delta]；Usage → [Usage]；Error → [Error]。
 */
sealed class ProbeEvent {
    /**
     * 流已建立：首个 preview 非空白的非错误可解析 data 行即发（仅一次，发射点在
     * ApiErrorHint 判定之后——携带 API 错误的 data 行只映射 [Error]、不发本事件，
     * 保证错误后端体检仍 FAIL）。preview 取该行文本载荷（思考/正文任一）前 80 字，
     * 纯诊断信息；载荷为空白（usage-only/role-only 空块）不发本事件（B2 台账）。
     */
    data class Delta(val text: String) : ProbeEvent()

    /** 收尾块用量（多家后端字段名兼容口径；体检消费方一律跳过本事件） */
    data class Usage(
        val promptTokens: Int,
        val cachedTokens: Int? = null,
        val completionTokens: Int? = null
    ) : ProbeEvent()

    /** 错误（HTTP 错误体 / API 错误 data 行 / 传输失败）——体检流式步 FAIL 判据 */
    data class Error(val message: String) : ProbeEvent()
}

/**
 * 连接体检/探测专用客户端（内核清理 A Task 6：sendChatStream + fetchServerContext
 * 自 data 层旧客户端逐字搬移——旧客户端已随 Task 7 删除批退役，除事件类型换
 * [ProbeEvent] 外逻辑逐字同原方法）。
 * 六件语义随迁：
 * ① 主客户端 30s/300s 超时（本类私有副本）；② streamOptions 默认
 * StreamOptions(include_usage=true)；③ 地址黑名单学习（Set 由 ConnectionViewModel
 * 持有并经构造注入——保持 VM 级生命周期，读 :171-172 / 写 :214-221 语义随迁）；
 * ④ 本地后端 max_tokens 钳制（companion computeLocalMaxTokens 随迁）；
 * ⑤ 错误串契约（"HTTP ${code}: $body" 等——ConnectionViewModel.diagnoseError
 * 依赖前缀做状态码分派）；⑥ attachTimeContext 复用 core/llm/client 顶层 internal。
 * 解析体自含 SSE 事件循环副本（探针私有——旧事件模型已随删除批退役，本副本为
 * 唯一存活实现；探针不装 ThinkSplitter，
 * 思考/正文统一折进 [ProbeEvent.Delta] 预览）。
 */
class ConnectionProbe(
    // 拒绝 stream_options 的后端（如 Mistral 官方 API、严格 schema 网关）：
    // 命中一次后记住该地址，后续请求不再携带，避免整条聊天链路 4xx。
    // 可变集由 ConnectionViewModel 持有（VM 级生命周期）并构造注入
    private val blacklistedBases: MutableSet<String>,
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // 上下文大小查询用：本地接口都快，稍给点余量
    private val propsClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /** 防御性拼接：即使上层漏了规范化，这里也不会拼出 /v1/v1 或双斜杠。
     *  base 自带其他版本段（如智谱 /api/paas/v4）时，剥掉 path 的 /v1 前缀，
     *  否则会拼出 /v4/v1/models 这种必然 404 的地址。 */
    private fun ep(baseUrl: String, path: String): String {
        val base = ServerUrl.normalize(baseUrl) ?: baseUrl.trim().trimEnd('/')
        val p = if (Regex("""/v\d+$""").containsMatchIn(base)) path.removePrefix("/v1") else path
        return "$base$p"
    }

    companion object {
        /**
         * 本地后端 max_tokens 的唯一计算入口（上下文窗口的 1/4，钳制在 256..8192）。
         * 上下文裁剪的"生成预留"必须与此同源：agent 侧 ContextBuilder.reserveForOutput
         * 是本公式的拷贝（agent 包不 import data 层；逐值等价曾由测试断言钉死——
         * 断言随内核清理 A Task 6 删除并登记于 ContextBuilderTest），构建请求历史
         * 时应传其返回值，否则预留 1024 而实际生成最多 8192，小窗口本地模型可能超窗报错。
         */
        fun computeLocalMaxTokens(maxContextTokens: Int): Int =
            (maxContextTokens / 4).coerceIn(256, 8192)
    }

    /**
     * 发起流式对话请求。挂起函数：请求体序列化（大附件时可达数 MB）在 IO 线程完成。
     * 返回的 Flow 只支持单次消费（内部持有一个已执行的 OkHttp Call）。
     */
    suspend fun sendChatStream(
        baseUrl: String,
        model: String,
        messages: List<MessageDto>,
        thinkingEnabled: Boolean = true,
        apiKey: String = "",
        providerId: String = "",
        maxContextTokens: Int = 0
    ): Flow<ProbeEvent> = withContext(Dispatchers.IO) {
        val timedMessages = attachTimeContext(messages)
        val isLocal = BackendProvider.fromId(providerId).isLocal
        // 黑名单键用规范化地址（审计 P3）：否则尾斜杠/大小写变体会重复撞一次 4xx 且产生重复键
        val baseKey = ServerUrl.normalize(baseUrl) ?: baseUrl
        val supportsStreamOptions = when (providerId) {
            // 本地三件套与新式云端都支持；已知拒绝方（Mistral 等）走黑名单/不发送
            BackendProvider.LLAMA_CPP.id, BackendProvider.OLLAMA.id, BackendProvider.LM_STUDIO.id -> true
            else -> synchronized(blacklistedBases) { baseKey !in blacklistedBases }
        }
        val chatRequest = ChatRequest(
            model = model,
            messages = timedMessages,
            stream = true,
            // temperature 形参已删（审计 P3：全链路无调用方传入，预留参数造成"已生效"误解）
            // 仅本地后端发送 max_tokens：云端严格后端（如 OpenAI o 系列）会拒绝
            maxTokens = if (isLocal && maxContextTokens > 0) {
                computeLocalMaxTokens(maxContextTokens)
            } else null,
            // 不再发送 enable_thinking:false / /no_think 禁思考（2026-10-27 复盘）：
            // 强制禁思考会让按"思考块常开"训练的本地微调模型输出退化（只见半个括号、
            // 满屏思考无答案），且模型模板多半不认这些开关。现在让它想——思考经
            // reasoning_content 通道下发，app 负责捕获/展示/与正文分离
            chatTemplateKwargs = null,
            streamOptions = if (supportsStreamOptions) {
                dev.liuyouwu.familiar.core.llm.wire.StreamOptions(includeUsage = true)
            } else null
        )

        val json = gson.toJson(chatRequest)

        // 诊断：完整请求体落盘（不截断），定位"同一模型 curl 正常、app 内退化"类问题
        run {
            try {
                val dir = dev.liuyouwu.familiar.FamiliarApp.instance.getExternalFilesDir(null) ?: return@run
                java.io.File(dir, "last_request.json").writeText(json)
            } catch (_: Exception) {
            }
        }
        val requestBody = json.toRequestBody("application/json".toMediaType())

        val builder = Request.Builder()
            .url(ep(baseUrl, "/v1/chat/completions"))
            .post(requestBody)
        if (apiKey.isNotBlank()) {
            builder.addHeader("Authorization", "Bearer $apiKey")
        }
        val request = builder.build()

        val call = client.newCall(request)
        parseSSEStream(call)
            .onEach { event ->
                if (event is ProbeEvent.Error && event.message.contains("stream_options", ignoreCase = true)) {
                    synchronized(blacklistedBases) {
                        blacklistedBases.add(baseKey)
                    }
                }
            }
    }

    /**
     * 查询服务器真实上下文大小（token 数），连接成功后用于自动对齐应用内设置。
     * 仅本地后端有标准查询方式；自定义/云端没有通用接口，返回 failure。
     *
     * @param origin 形如 http://192.168.1.5:8080（不含接口路径）
     * @param apiPath 接口路径前缀（"" = 标准）
     */
    suspend fun fetchServerContext(
        origin: String,
        apiPath: String,
        providerId: String,
        model: String,
        apiKey: String
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            when (providerId) {
                BackendProvider.LLAMA_CPP.id -> {
                    // /props 在 llama.cpp 根路径；自定义路径前缀时两处都试
                    val urls = listOf(origin + apiPath + "/props", origin + "/props").distinct()
                    for (url in urls) {
                        runCatching {
                            val resp = propsClient.newCall(Request.Builder().url(url).get().build()).execute()
                            resp.use { resp2 ->
                                if (!resp2.isSuccessful) return@runCatching
                                val json = JSONObject(resp2.body?.string() ?: return@runCatching)
                                val n = json.optJSONObject("default_generation_settings")?.optInt("n_ctx")?.takeIf { it > 0 }
                                    ?: json.optInt("n_ctx").takeIf { it > 0 }
                                if (n != null) return@withContext Result.success(n)
                            }
                        }
                    }
                    Result.failure(Exception("llama.cpp /props 未返回上下文大小"))
                }
                BackendProvider.OLLAMA.id -> {
                    // 运行时窗口优先：/api/ps 返回已加载模型的真实 context（num_ctx）；
                    // /api/show 的 context_length 是模型元数据声明上限，实际 num_ctx 可能小得多
                    runCatching {
                        val req = Request.Builder()
                            .url(origin + apiPath + "/api/ps")
                            .post("{}".toRequestBody("application/json".toMediaType()))
                            .build()
                        propsClient.newCall(req).execute().use { resp2 ->
                            if (resp2.isSuccessful) {
                                val json = JSONObject(resp2.body?.string() ?: return@use)
                                val models = json.optJSONArray("Models")
                                if (models != null) {
                                    for (i in 0 until models.length()) {
                                        val n = models.optJSONObject(i)?.optInt("context_length") ?: 0
                                        if (n > 0) return@withContext Result.success(n)
                                    }
                                }
                            }
                        }
                    }
                    val body = JSONObject().put("model", model)
                    val req = Request.Builder()
                        .url(origin + apiPath + "/api/show")
                        .post(body.toString().toRequestBody("application/json".toMediaType()))
                        .build()
                    val resp = propsClient.newCall(req).execute()
                    resp.use { resp2 ->
                        if (!resp2.isSuccessful) {
                            return@withContext Result.failure(Exception("HTTP ${resp2.code}"))
                        }
                        val json = JSONObject(resp2.body?.string() ?: return@withContext Result.failure(Exception("Empty")))
                        // context_length 按架构命名（如 qwen3.context_length），遍历 model_info 找它
                        val info = json.optJSONObject("model_info")
                        var n = json.optInt("context_length")
                        if (info != null && n <= 0) {
                            for (key in info.keys()) {
                                if (key.endsWith(".context_length")) {
                                    n = info.optInt(key)
                                    break
                                }
                            }
                        }
                        if (n > 0) Result.success(n)
                        else Result.failure(Exception("Ollama 未返回上下文大小"))
                    }
                }
                BackendProvider.LM_STUDIO.id -> {
                    val urls = listOf(origin + apiPath + "/api/v0/models", origin + "/api/v0/models").distinct()
                    for (url in urls) {
                        runCatching {
                            val resp = propsClient.newCall(Request.Builder().url(url).get().build()).execute()
                            resp.use { resp2 ->
                                if (!resp2.isSuccessful) return@runCatching
                                val json = JSONObject(resp2.body?.string() ?: return@runCatching)
                                val data = json.optJSONArray("data") ?: return@runCatching
                                var best = 0
                                for (i in 0 until data.length()) {
                                    val item = data.optJSONObject(i) ?: continue
                                    val n = item.optInt("max_context_length")
                                    // 优先取已加载模型；回退只统计"未标记为未加载"的条目，
                                    // 未加载模型声明的窗口不代表当前在跑的模型
                                    if (item.optString("state") == "loaded" && n > 0) {
                                        return@withContext Result.success(n)
                                    }
                                    if (n > best && item.optString("state") != "not-loaded") best = n
                                }
                                if (best > 0) return@withContext Result.success(best)
                            }
                        }
                    }
                    Result.failure(Exception("LM Studio 未返回上下文大小"))
                }
                else -> Result.failure(Exception("该后端没有通用的上下文查询接口"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ===== 自含 SSE 事件循环副本（探针私有——旧事件模型已随 Task 7 删除批退役，
    // 本副本为唯一存活实现）。与原解析体的差异仅两处，均为探针钉死语义：
    // ① 事件换 ProbeEvent 三变体（映射：Content/Reasoning/Done→Delta、Usage→Usage、
    //    Error→Error）；② 无 ThinkSplitter——Delta 只在首个 preview 非空白的可解析
    //    data 行发一次（preview 前 80 字；usage-only/空载荷行不发空 Delta——B2 台账，
    //    纯 [DONE]/纯 usage 流不再补发空 Delta）。truncated 无载体，不再跟踪。 =====

    private fun parseSSEStream(call: Call): Flow<ProbeEvent> = callbackFlow {
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!call.isCanceled()) {
                    trySend(ProbeEvent.Error(ApiErrorHint.fromThrowable(e)))
                }
                close()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    consumeResponse(
                        call, resp,
                        send = { event -> trySend(event) },
                        finish = { close() },
                    )
                }
            }
        })

        awaitClose {
            if (!call.isCanceled()) call.cancel()
        }
    }.buffer(Channel.UNLIMITED)

    private fun consumeResponse(
        call: Call,
        resp: Response,
        send: (ProbeEvent) -> Unit,
        finish: () -> Unit,
    ) {
        if (!resp.isSuccessful) {
            val body = try {
                resp.body?.string() ?: ""
            } catch (_: Exception) {
                ""
            }
            val apiError = ApiErrorHint.extractApiError(body)
            val message = apiError?.let { "HTTP ${resp.code}: $it" }
                ?: ApiErrorHint.fromHttpStatus(resp.code, body)
            send(ProbeEvent.Error(message))
            finish()
            return
        }

        val contentType = resp.header("Content-Type") ?: ""
        if (!contentType.contains("event-stream", ignoreCase = true)) {
            // 网关/老版本后端可能忽略 stream=true，直接返回整段 JSON
            consumeNonStreamBody(call, resp, send, finish)
            return
        }
        consumeEventStream(call, resp, send, finish)
    }

    private fun consumeNonStreamBody(
        call: Call,
        resp: Response,
        send: (ProbeEvent) -> Unit,
        finish: () -> Unit,
    ) {
        val body = try {
            resp.body?.string() ?: ""
        } catch (_: Exception) {
            ""
        }
        if (body.trimStart().startsWith("data:")) {
            handleEventLines(
                call,
                (body.lineSequence() + sequenceOf<String?>(null)).iterator(),
                send, finish
            )
            return
        }
        ApiErrorHint.extractApiError(body)?.let {
            send(ProbeEvent.Error(it))
            finish()
            return
        }
        val parsed = try {
            gson.fromJson(body, ChatResponse::class.java)
        } catch (_: Exception) {
            null
        }
        val message = parsed?.choices?.firstOrNull()?.message
        val hasText = message != null &&
            (!message.content.isNullOrEmpty() ||
                !message.reasoningContent.isNullOrEmpty() ||
                !message.reasoning.isNullOrEmpty())
        if (hasText && message != null) {
            // 载荷顺序对照原实现（思考先行），preview 取首个载荷前 80 字
            send(
                ProbeEvent.Delta(
                    (message.reasoningContent ?: message.reasoning ?: message.content ?: "").take(80)
                )
            )
            parseUsage(parsed)?.let { send(it) }
        } else {
            // 200 但 body 是 HTML/空/无法理解的 JSON：明确报错而不是静默保存空回复
            send(
                ProbeEvent.Error(
                    if (body.isBlank()) "服务器返回了空响应"
                    else "服务器返回了无法解析的响应：" + body.take(150)
                )
            )
        }
        finish()
    }

    private fun consumeEventStream(
        call: Call,
        resp: Response,
        send: (ProbeEvent) -> Unit,
        finish: () -> Unit,
    ) {
        val reader = resp.body?.byteStream()?.bufferedReader()
        if (reader == null) {
            send(ProbeEvent.Error("Empty response body"))
            finish()
            return
        }
        try {
            handleEventLines(call, generateSequence { readLineBounded(reader) }.iterator(), send, finish)
        } finally {
            try {
                reader.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * SSE 事件行处理核心（对照原 handleEventLines 语义：[DONE] 完成信号、坏行跳过、
     * finish 后有界读取防只发注释不关闭的后端死循环）。
     * internal 供 ConnectionProbeHandleLinesTest 直测（行列表→事件列表，不起真网络）。
     * @param lines null 元素表示 EOF
     */
    internal fun handleEventLines(
        call: Call,
        lines: Iterator<String?>,
        send: (ProbeEvent) -> Unit,
        finish: () -> Unit,
    ) {
        // finish_reason 之后部分后端（OpenAI 风格）才单独发 usage 块：
        // 继续读一小段拿缓存统计，读不到立即收尾，绝不吊死连接
        var finished = false
        var postFinishLines = 0
        var deltaSent = false
        try {
            while (true) {
                val current = lines.next() ?: break
                // 只处理 data 行；event:/id:/注释(keep-alive)/空行跳过。
                // SSE 规范里冒号后的空格是可选的，两种写法都要认。
                if (!current.startsWith("data:")) {
                    // finish 后的 keep-alive 注释行也要计数，防止只发注释不关闭的后端导致死循环
                    if (finished && ++postFinishLines > 64) break
                    continue
                }
                val data = current.substring(5).let { if (it.startsWith(" ")) it.substring(1) else it }.trim()
                // [DONE] 是 SSE 规范的正常完成信号（审计 P2-7）：部分网关只发 [DONE] 不发
                // finish_reason，必须置 finished，否则正常完成被误标截断并落库伪标记
                if (data == "[DONE]") {
                    finished = true
                    break
                }

                val apiError = ApiErrorHint.extractApiError(data)
                if (apiError != null) {
                    // Delta 发射点在 ApiErrorHint 判定之后（三审 F2/P2）：携带 API 错误的
                    // data 行只映射 Error、不发 Delta——否则错误后端体检翻成 PASS
                    send(ProbeEvent.Error(apiError))
                    finish()
                    return
                }

                val parsed = try {
                    gson.fromJson(data, ChatResponse::class.java)
                } catch (_: Exception) {
                    continue // 单行坏 JSON 不致命，继续读流
                }
                // usage/timings 出现在收尾块（llama.cpp 的 timings、DeepSeek/OpenAI 的缓存命中数）
                parseUsage(parsed)?.let { send(it) }
                if (finished) {
                    if (++postFinishLines > 64) break
                    continue
                }

                val choice = parsed?.choices?.firstOrNull()

                // Delta：首个 preview 非空白的可解析 data 行即发（preview 取思考/正文
                // 载荷前 80 字；B2 台账：Delta 仅在 preview 非空白时发射——usage-only/
                // role-only 空载荷行不发空 Delta、不伪造"流已建立"，usage/timings 照发
                // [Usage]；空载荷首块不占一次性发射位，等首个非空载荷）
                if (!deltaSent) {
                    val d = choice?.delta
                    val m = choice?.message
                    val preview = d?.reasoningContent ?: d?.reasoning ?: d?.content
                        ?: m?.reasoningContent ?: m?.reasoning ?: m?.content ?: ""
                    if (preview.isNotBlank()) {
                        deltaSent = true
                        send(ProbeEvent.Delta(preview.take(80)))
                    }
                }

                // 任意 finish_reason（stop/length/tool_calls...）都不再立即退出循环，
                // 兼顾不发 [DONE] 的后端（收尾在 finish 后的有界读取里完成）
                if (choice?.finishReason != null) {
                    finished = true
                }
            }
            // [DONE]/EOF 收尾：不再补发空 preview Delta（B2 台账：Delta 仅在 preview
            // 非空白时发射）——纯 [DONE]/纯 usage 流只可能带 [Usage]，体检消费方
            // （ConnectionViewModel 第 4 步）据此判"没有收到流式数据"
        } catch (e: Exception) {
            if (!call.isCanceled()) {
                send(
                    ProbeEvent.Error(
                        if (e is LineTooLongException) (e.message ?: "响应行超长")
                        else ApiErrorHint.fromThrowable(e)
                    )
                )
            }
        }
        finish()
    }

    /**
     * 从收尾块提取 token 用量与缓存命中统计（多家后端字段名不同，逐一兼容，
     * 事件类型换 [ProbeEvent.Usage]）。
     */
    private fun parseUsage(parsed: ChatResponse?): ProbeEvent.Usage? {
        val usage = parsed?.usage
        val timings = parsed?.timings
        // 缓存命中数：OpenAI 嵌套 prompt_tokens_details.cached_tokens（可能缺省）/
        // 顶层 cached_tokens / Anthropic 风格 cache_read_input_tokens /
        // DeepSeek prompt_cache_hit_tokens / llama.cpp timings.cache_n，全部防御性可空
        val cached = usage?.promptTokensDetails?.cachedTokens
            ?: usage?.promptCacheHitTokens
            ?: usage?.cachedTokens
            ?: usage?.cacheReadInputTokens
            ?: timings?.cacheN
        // llama.cpp 的 prompt_n 是"实际处理数"（不含命中），总数 = prompt_n + cache_n
        val promptTokens = usage?.promptTokens
            ?: timings?.let { (it.promptN ?: 0) + (it.cacheN ?: 0) }
        val completionTokens = usage?.completionTokens
        if ((promptTokens ?: 0) <= 0 && (completionTokens ?: 0) <= 0) return null
        val prompt = (promptTokens ?: 0).coerceAtLeast(0)
        return ProbeEvent.Usage(
            promptTokens = prompt,
            completionTokens = completionTokens?.takeIf { it > 0 },
            // 后端没上报缓存命中时保持 null（绝不伪造成 0）；有值时命中数不可能
            // 超过 prompt 总数，prompt=0 原样保留
            cachedTokens = cached?.let { c ->
                if (prompt > 0) c.coerceIn(0, prompt) else c
            }
        )
    }
}
