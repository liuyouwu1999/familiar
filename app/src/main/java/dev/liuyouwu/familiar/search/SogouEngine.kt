package dev.liuyouwu.familiar.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

/**
 * 搜狗网页抓取引擎（免 key，2026-10 直连实测 200 且结构稳定）。
 * 非官方用法，可能随时改版/风控——解析 0 条 = 失败语义，调用方降级。
 * 结果链接是 /link?url=... 跳转包装：无法离线解码（服务端加密），原样保留即可
 * （浏览器访问该链接会 302 到真实页面，引用展示不受影响）。
 */
class SogouEngine(
    private val client: OkHttpClient = BingCnEngine.searchClient
) : SearchEngine {

    override suspend fun search(query: String): SearchResult = withContext(Dispatchers.IO) {
        val url = "https://www.sogou.com/web?query=" + URLEncoder.encode(query, "UTF-8")
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
                val hits = parseSogou(html)
                if (hits.isEmpty()) {
                    SearchResult(emptyList(), "解析 0 条（vrwrap 结构可能改版或被风控）")
                } else {
                    SearchResult(hits, "命中 ${hits.size} 条")
                }
            }
        }.getOrElse {
            SearchResult(emptyList(), "网络异常：${it.message}")
        }
    }

    companion object {
        /**
         * 解析搜狗结果页：按 class="vrwrap" 切块，每块取 vr-title 首个 <a> 的标题+跳转链接、
         * space-txt/space-small 元素内文本做摘要、cite 区域显示 URL 提取域名。
         * 纯函数（fixture 单测锁定行为）；改版时返回空列表走失败降级。
         */
        fun parseSogou(html: String): List<SearchHit> {
            val hits = mutableListOf<SearchHit>()
            for (b in html.split("class=\"vrwrap\"").drop(1)) {
                // 标题锚点在 vr-title 区域内第一个 <a>（href 可能带 class/target 等前置属性）
                val a = Regex("<a[^>]*href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>")
                    .find(b.substringAfter("vr-title")) ?: continue
                val title = HtmlText.stripTags(a.groupValues[2])
                if (title.isBlank()) continue
                val href = a.groupValues[1]
                // 站内跳转链接补全 host；已是绝对 URL 的原样保留
                val url = if (href.startsWith("http")) href else "https://www.sogou.com$href"
                // 摘要类名有变体（space-txt / space-txt clamp2 / space-small），元素可为 p 或 div
                val snippet = HtmlText.stripTags(
                    Regex("class=\"[^\"]*space-(?:txt|small)[^\"]*\"[^>]*>([\\s\\S]*?)</(?:p|div)>")
                        .find(b)?.groupValues?.get(1)
                )
                // cite 区域的显示 URL 形如 <span>https://www.bohe.cn/a...</span>（截断展示），取 host
                val domain = Regex("<span[^>]*>\\s*https?://([A-Za-z0-9.-]+)")
                    .find(b)?.groupValues?.get(1) ?: "sogou.com"
                hits.add(SearchHit(title, url, snippet, domain))
                if (hits.size >= 5) break
            }
            return hits
        }
    }
}
