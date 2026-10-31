package dev.liuyouwu.familiar.data.remote.api

import com.google.gson.Gson
import dev.liuyouwu.familiar.core.llm.wire.ModelsResponse
import dev.liuyouwu.familiar.util.ServerUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 模型列表客户端（内核清理 A Task 5：fetchModels 自 data 层旧客户端逐字搬移——
 * 旧客户端已随 Task 7 删除批退役；签名/返回类型/错误串格式不变，
 * ConnectionViewModel.diagnoseError 依赖 "HTTP ${code}: …" 前缀做状态码分派）。
 * 随迁的私有依赖：modelsClient/probeClient 两组短超时、gson、ep() 端点拼接
 * （/v1 尾段特判的私有副本）。
 */
class ModelsApi {

    // 端口探测用：快速失败，避免逐端口等满 30 秒
    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.SECONDS)
        .build()

    // 二段扩展扫描用（PortScanner 并行百余端口）：1s 级超时压总扫时长；
    // 黑洞地址（防火墙丢包不回 RST）也就挂 1 秒，命中即回
    private val scanClient = OkHttpClient.Builder()
        .connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(1, TimeUnit.SECONDS)
        .writeTimeout(1, TimeUnit.SECONDS)
        .build()

    // 模型列表专用：拉 /v1/models 本就轻量，必须短超时——否则自动连接卡在
    // 不可达地址上时（如组网掉线后的黑洞路由，SYN 无回包），连接按钮"连接中"
    // 要挂满主客户端的 30 秒连接超时，期间用户点什么都没反应
    private val modelsClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
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

    suspend fun fetchModels(
        baseUrl: String,
        apiKey: String = "",
        probe: Boolean = false
    ): Result<ModelsResponse> = try {
        withContext(Dispatchers.IO) {
            // 探测走更短的 probeClient；正式拉模型列表走 modelsClient（短超时，绝不挂 30 秒）
            val http = if (probe) probeClient else modelsClient
            val builder = Request.Builder()
                .url(ep(baseUrl, "/v1/models"))
                .get()
            if (apiKey.isNotBlank()) {
                builder.addHeader("Authorization", "Bearer $apiKey")
            }
            val request = builder.build()

            // Response 用 .use 关闭（审计 P3）：异常路径不再泄漏连接
            http.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                        ?: return@withContext Result.failure(Exception("Empty response"))
                    val modelsResponse = try {
                        gson.fromJson(body, ModelsResponse::class.java)
                    } catch (e: Exception) {
                        // 200 但内容不是 OpenAI JSON（常见：连到了路由器/打印机等设备的网页）
                        val head = body.take(120).replace("\n", " ")
                        return@withContext Result.failure(
                            Exception("该地址不是 OpenAI 兼容服务（返回内容不是 JSON）。地址：$baseUrl/v1/models，返回内容开头：$head")
                        )
                    }
                    // Gson 对 body 为 "null" 字面量或空串时返回 null 而不抛异常，必须显式兜底
                    ?: return@withContext Result.failure(Exception("服务器返回了空响应（HTTP 200 但无有效内容）"))
                    Result.success(modelsResponse)
                } else {
                    val errorBody = response.body?.string()?.take(200) ?: ""
                    Result.failure(Exception("HTTP ${response.code}${if (errorBody.isNotBlank()) ": $errorBody" else ""}"))
                }
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("ModelsApi", "fetchModels failed", e)
        Result.failure(e)
    }

    /**
     * 单端口快探（二段扫描专用，PortScanner.sweep 逐端口调用）：200 且解析出
     * 非空模型列表 → 模型 id 列表；非 2xx / 非 JSON（连到路由器打印机网页）/
     * 空列表 / 任何异常 → null（未命中，不区分原因——扫描不需要失败细节）。
     * 命中后由 ConnectionViewModel 走正常 fetchModels 路径回填，此处结果只作信号。
     */
    suspend fun probePort(baseUrl: String, apiKey: String = ""): List<String>? = try {
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(ep(baseUrl, "/v1/models")).get()
            if (apiKey.isNotBlank()) {
                builder.addHeader("Authorization", "Bearer $apiKey")
            }
            scanClient.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                runCatching { gson.fromJson(body, ModelsResponse::class.java) }.getOrNull()
                    ?.data?.mapNotNull { it.id }?.takeIf { it.isNotEmpty() }
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
