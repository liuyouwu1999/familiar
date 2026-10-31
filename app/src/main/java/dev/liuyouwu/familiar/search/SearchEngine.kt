package dev.liuyouwu.familiar.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Base64
import java.util.concurrent.TimeUnit

/** 一条搜索结果。domain 为展示/引用用的站点域名（从真实 URL 提取）。 */
data class SearchHit(
    val title: String,
    val url: String,
    val snippet: String,
    val domain: String,
    /** 来源显示名（"必应"/"搜狗"/"360"/"维基百科"）；单源引擎可空，多源聚合层负责回填。 */
    val source: String = ""
)

/**
 * 搜索结果 + 诊断信息。diagnostics 用于失败降级时的原因透出
 * （HTTP 状态/解析命中数），调用方写进降级提示便于用户自查。
 */
data class SearchResult(
    val hits: List<SearchHit>,
    val diagnostics: String
)

/** 可插拔搜索引擎接口。P1 仅 BingCN；P2 增加 DDG/博查/Tavily。 */
interface SearchEngine {
    suspend fun search(query: String): SearchResult
}

/**
 * 必应中国版网页抓取引擎（免 key，大陆直连实测可用）。
 * 非官方用法：cn.bing.com/robots.txt 对 /search 为 Disallow——可能随时失效或被风控，
 * 调用方必须处理失败路径（解析 0 条 = 失败，不是空成功）。
 * 桌面 UA 必需：移动 UA 会命中与查询无关的首页式布局。
 */
class BingCnEngine(
    private val client: OkHttpClient = searchClient
) : SearchEngine {

    override suspend fun search(query: String): SearchResult = withContext(Dispatchers.IO) {
        val url = "https://cn.bing.com/search?q=" +
            java.net.URLEncoder.encode(query, "UTF-8") + "&mkt=zh-CN&setlang=zh-hans"
        // 浏览器化 header 降低被风控/降级服务的概率（缺 Accept 时 Bing 偶发降级垃圾页）
        val req = Request.Builder().url(url)
            .header("User-Agent", DESKTOP_UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .build()
        runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext SearchResult(emptyList(), "HTTP ${resp.code}")
                }
                val html = resp.body?.string() ?: return@withContext SearchResult(emptyList(), "空响应体")
                val hits = parseBingAlgo(html)
                if (hits.isEmpty()) {
                    SearchResult(emptyList(), "解析 0 条（b_algo 结构可能改版或被风控）")
                } else {
                    SearchResult(hits, "命中 ${hits.size} 条")
                }
            }
        }.getOrElse {
            SearchResult(emptyList(), "网络异常：${it.message}")
        }
    }

    companion object {
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /** 搜索专用短超时 client：不复用聊天链路（那边读取超时 300s，搜索挂 300s 课堂就断） */
        val searchClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()

        /**
         * 解析 cn.bing.com 结果页：按 class="b_algo" 切块，每块取第一个外链 + 标题 + 摘要。
         * 纯函数（fixture 单测锁定行为）；Bing 改版时此处返回空列表，调用方走失败降级。
         */
        fun parseBingAlgo(html: String): List<SearchHit> {
            val blocks = html.split("class=\"b_algo\"").drop(1)
            val hits = mutableListOf<SearchHit>()
            for (b in blocks) {
                val rawUrl = Regex("href=\"(https?://[^\"]+)\"").find(b)?.groupValues?.get(1) ?: continue
                val title = HtmlText.stripTags(Regex("<h2[^>]*>([\\s\\S]*?)</h2>").find(b)?.groupValues?.get(1))
                    .ifBlank { HtmlText.stripTags(Regex("<a[^>]*>([\\s\\S]*?)</a>").find(b)?.groupValues?.get(1)) }
                if (title.isBlank()) continue
                val snippet = HtmlText.stripTags(Regex("<p[^>]*>([\\s\\S]*?)</p>").find(b)?.groupValues?.get(1))
                val realUrl = decodeBingRedirect(rawUrl) ?: continue
                val domain = Regex("https?://([^/]+)").find(realUrl)?.groupValues?.get(1) ?: continue
                hits.add(SearchHit(title, realUrl, snippet, domain))
                if (hits.size >= 5) break
            }
            return hits
        }

        /**
         * Bing 结果链接是 /ck/a?...&u=a1<base64url> 跳转包装：解出真实 URL。
         * getUrlDecoder 本就接受 base64url 字母表；非跳转链接原样返回，解不动返回 null。
         */
        fun decodeBingRedirect(url: String): String? {
            if (!url.contains("/ck/a")) return url
            val u = Regex("[?&]u=a1([^&]+)").find(url)?.groupValues?.get(1) ?: return url
            return runCatching {
                String(Base64.getUrlDecoder().decode(u.toByteArray(Charsets.UTF_8)), Charsets.UTF_8)
            }.getOrNull()?.takeIf { it.startsWith("http") }
        }
    }
}
