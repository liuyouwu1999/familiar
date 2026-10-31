package dev.liuyouwu.familiar.data.update

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

interface ReleaseHttp {
    @Throws(IOException::class)
    fun get(url: String): String
}

class GitHubHttpException(val code: Int) : IOException("HTTP $code")

class OkHttpReleaseHttp(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build(),
) : ReleaseHttp {
    override fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Familiar")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw GitHubHttpException(resp.code)
            return resp.body!!.string()
        }
    }
}

sealed interface UpdateCheckResult {
    data object UpToDate : UpdateCheckResult

    data class Available(
        val release: Release,
        val changelog: List<String>,
        val asset: ReleaseAsset?,
        val sums: Map<String, String>?,
    ) : UpdateCheckResult {
        /** 资产与校验文件齐备才允许应用内安装——宁可不装，不做未校验安装 */
        val canInstall: Boolean get() = asset != null && sums != null
    }

    data class Failed(val reason: Reason) : UpdateCheckResult {
        enum class Reason { NETWORK, RATE_LIMITED, BAD_RESPONSE }
    }
}

class UpdateRepository(private val http: ReleaseHttp) {

    /**
     * 编排：fetch json → 异常映射（403/429→RATE_LIMITED，其余→NETWORK）→ 解析失败→BAD_RESPONSE
     * → 无新版→UpToDate（不发多余请求）→ 选 APK 资产 → sums best-effort（取不到不阻断 Available）。
     */
    fun check(localVersionName: String): UpdateCheckResult {
        val body = try {
            http.get(DEFAULT_API_URL)
        } catch (e: GitHubHttpException) {
            // GitHubHttpException extends IOException，须先捕
            return UpdateCheckResult.Failed(
                if (e.code == 403 || e.code == 429) UpdateCheckResult.Failed.Reason.RATE_LIMITED
                else UpdateCheckResult.Failed.Reason.NETWORK,
            )
        } catch (e: IOException) {
            return UpdateCheckResult.Failed(UpdateCheckResult.Failed.Reason.NETWORK)
        }

        val release = ReleaseJsonParser.parse(body)
            ?: return UpdateCheckResult.Failed(UpdateCheckResult.Failed.Reason.BAD_RESPONSE)

        if (!VersionCompare.isUpdateAvailable(localVersionName, release.tagName)) {
            return UpdateCheckResult.UpToDate
        }

        val asset = AssetPicker.pick(release.assets)

        // sums 是独立资产（SHA256SUMS.txt），best-effort：取不到/解析空 → null，不影响 Available
        val sums = if (asset == null) null else release.assets
            .firstOrNull { it.name == SUMS_ASSET_NAME }
            ?.url?.let { url ->
                runCatching { http.get(url) }.getOrNull()
                    ?.let(Sha256SumsParser::parse)
                    ?.takeIf { it.isNotEmpty() }
            }

        return UpdateCheckResult.Available(
            release = release,
            changelog = ChangelogExtractor.extract(release.body),
            asset = asset,
            sums = sums,
        )
    }

    companion object {
        const val DEFAULT_API_URL = "https://api.github.com/repos/liuyouwu1999/familiar/releases/latest"

        /** 发版时随 APK 一并上传的校验和资产名 */
        const val SUMS_ASSET_NAME = "SHA256SUMS.txt"
    }
}
