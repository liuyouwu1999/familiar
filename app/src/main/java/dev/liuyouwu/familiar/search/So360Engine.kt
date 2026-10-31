package dev.liuyouwu.familiar.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

/**
 * 360 搜索（so.com）网页抓取引擎（免 key，2026-10 直连实测 200 且结构稳定）。
 * 非官方用法，可能随时改版/风控——解析 0 条 = 失败语义，调用方降级。
 * 结果链接两种形态都原样保留：真实 URL 或 /link?m=... 跳转（浏览器可跳转）。
 */
class So360Engine(
    private val client: OkHttpClient = BingCnEngine.searchClient
) : SearchEngine {

    override suspend fun search(query: String): SearchResult = withContext(Dispatchers.IO) {
        val url = "https://www.so.com/s?q=" + URLEncoder.encode(query, "UTF-8")
        val req = Request.Builder().url(url)
            .header("User-Agent", BingCnEngine.DESKTOP_UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .build()
        runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext SearchResult(emptyList(), "HTTP ${resp.code}")
                }
                val html = resp.body?.string() ?: return@withContext SearchResult(emptyList(), "空响应体")
                val hits = parse360(html)
                if (hits.isEmpty()) {
                    SearchResult(emptyList(), "解析 0 条（res-list 结构可能改版或被风控）")
                } else {
                    SearchResult(hits, "命中 ${hits.size} 条")
                }
            }
        }.getOrElse {
            SearchResult(emptyList(), "网络异常：${it.message}")
        }
    }

    companion object {
        private val REDIRECT = Regex("so\\.com/link")

        /**
         * 解析 360 结果页：按 <li class="res-list 切块，每块取 res-title 首个 <a> 的标题+链接、
         * [data-result-summary] span（退化 res-desc）做摘要、cite 文本（退化 so.com）做域名。
         * 纯函数（fixture 单测锁定行为）；改版时返回空列表走失败降级。
         */
        fun parse360(html: String): List<SearchHit> {
            val hits = mutableListOf<SearchHit>()
            for (b in html.split("<li class=\"res-list").drop(1)) {
                val a = Regex("class=\"res-title[^\"]*\"[^>]*>\\s*<a[^>]*href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>")
                    .find(b) ?: continue
                val title = HtmlText.stripTags(a.groupValues[2])
                if (title.isBlank()) continue
                // href 是 HTML 属性原文，&amp; 解回 &：不解会把首个参数之后的 query 全弄坏
                val href = HtmlText.decodeEntities(a.groupValues[1])
                // 摘要优先带标记的 summary span，缺失时退化为 res-desc 容器剥标签
                val snippet = HtmlText.stripTags(
                    Regex("data-result-summary[^>]*>([\\s\\S]*?)</span>").find(b)?.groupValues?.get(1)
                        ?: Regex("class=\"res-desc[^\"]*\"[^>]*>([\\s\\S]*?)</(?:p|div)>").find(b)?.groupValues?.get(1)
                )
                // 真实 URL 直接取 host；跳转链接从块内 <cite> 显示文本拿域名，再退化 so.com
                val domain = if (!REDIRECT.containsMatchIn(href)) {
                    Regex("https?://([^/?#\"]+)").find(href)?.groupValues?.get(1) ?: "so.com"
                } else {
                    Regex("<cite>([\\s\\S]*?)</cite>").find(b)
                        ?.let { HtmlText.stripTags(it.groupValues[1]) }
                        ?.takeIf { it.isNotBlank() } ?: "so.com"
                }
                hits.add(SearchHit(title, href, snippet, domain))
                if (hits.size >= 5) break
            }
            return hits
        }
    }
}
