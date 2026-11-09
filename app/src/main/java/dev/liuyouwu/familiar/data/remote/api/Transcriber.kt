package dev.liuyouwu.familiar.data.remote.api

import dev.liuyouwu.familiar.util.NetGuards
import dev.liuyouwu.familiar.util.ServerUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 服务端语音转写客户端（内核清理 A Task 5：transcribeAudio 自 data 层旧客户端逐字
 * 搬移——旧客户端已随 Task 7 删除批退役；签名/返回类型/错误串格式不变）。随迁的私有
 * 依赖：主客户端（30s/300s——录音文件上传+转写是长请求，不能套模型列表的短超时）、
 * ep() 端点拼接（/v1 尾段特判的私有副本）。
 */
class Transcriber {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 防御性拼接：即使上层漏了规范化，这里也不会拼出 /v1/v1 或双斜杠。
     *  base 自带其他版本段（如智谱 /api/paas/v4）时，剥掉 path 的 /v1 前缀，
     *  否则会拼出 /v4/v1/models 这种必然 404 的地址。 */
    private fun ep(baseUrl: String, path: String): String {
        val base = ServerUrl.normalize(baseUrl) ?: baseUrl.trim().trimEnd('/')
        val p = if (Regex("""/v\d+$""").containsMatchIn(base)) path.removePrefix("/v1") else path
        return "$base$p"
    }

    suspend fun transcribeAudio(
        baseUrl: String,
        audioFile: java.io.File,
        apiKey: String = ""
    ): Result<String> = try {
        // 明文 http 拒发闸（审计 S1+S7，裁定 1 · D 出缝）：http + 非局域网 + 非空
        // key → 抛 PlaintextKeyRefusal（newCall 前，key 不出网），下方 catch-all 折
        // Result.failure 沿调用方 onFailure 链透传（VoiceController 无 try/catch）
        NetGuards.guardPlaintextCredential(ep(baseUrl, "/v1/audio/transcriptions"), apiKey)
        withContext(Dispatchers.IO) {
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", "whisper-1")
                .addFormDataPart(
                    "file", audioFile.name,
                    audioFile.asRequestBody("audio/mp4".toMediaType())
                )
                .build()

            val builder = Request.Builder()
                .url(ep(baseUrl, "/v1/audio/transcriptions"))
                .post(requestBody)
            if (apiKey.isNotBlank()) {
                builder.addHeader("Authorization", "Bearer $apiKey")
            }
            val request = builder.build()

            // Response 用 .use 关闭（审计 P3）：body.string() 中途抛异常不再泄漏连接
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                        ?: return@withContext Result.failure(Exception("Empty response"))
                    val text = JSONObject(body).optString("text", "")
                    if (text.isNotBlank()) Result.success(text)
                    else Result.failure(Exception("Empty transcription"))
                } else {
                    val errorBody = response.body?.string()?.take(200) ?: ""
                    Result.failure(
                        Exception("HTTP ${response.code}${if (errorBody.isNotBlank()) ": $errorBody" else ""}")
                    )
                }
            }
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}
