package dev.liuyouwu.familiar.search

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

/**
 * 维基百科站内搜索引擎（官方 API，免 key）。lang="zh" 中文站（默认）/ "en" 英文站
 * （2026-10-30 jev 事故：拉丁字母新技术词 zh 站常无条目，en 站补位）。
 * 熔断源：无代理的大陆网络下 DNS 污染 + 连接超时（GFW），失败自动跳过、
 * 用户开代理后自动恢复——所以失败文案必须点明"可能被墙"，别让用户以为搜索坏了。
 * snippet 是 HTML 片段（含 searchmatch 高亮标签），复用 HtmlText 清洗。
 */
class WikipediaEngine(
    private val lang: String = "zh",
    private val client: OkHttpClient = BingCnEngine.searchClient
) : SearchEngine {

    override suspend fun search(query: String): SearchResult = withContext(Dispatchers.IO) {
        val url = "https://$lang.wikipedia.org/w/api.php?action=query&list=search" +
            "&srsearch=" + URLEncoder.encode(query, "UTF-8") + "&format=json&srlimit=4"
        val req = Request.Builder().url(url).header("User-Agent", BingCnEngine.DESKTOP_UA).build()
        runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext SearchResult(emptyList(), "HTTP ${resp.code}（维基百科可能被墙，需代理）")
                }
                val body = resp.body?.string()
                    ?: return@withContext SearchResult(emptyList(), "空响应体（维基百科可能被墙，需代理）")
                val hits = parseWikipedia(body, lang)
                if (hits.isEmpty()) {
                    SearchResult(emptyList(), "解析 0 条（维基百科可能被墙或确无结果）")
                } else {
                    SearchResult(hits, "命中 ${hits.size} 条")
                }
            }
        }.getOrElse {
            SearchResult(emptyList(), "网络异常：${it.message}（维基百科可能被墙，需代理）")
        }
    }

    companion object {
        /** 解析 action=query&list=search 的 JSON：query.search[] 的 title/snippet。坏 JSON 返回空走失败。 */
        fun parseWikipedia(json: String, lang: String = "zh"): List<SearchHit> {
            val domain = "${lang}.wikipedia.org"
            val sourceName = if (lang == "zh") "维基百科" else "英文维基"
            val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()
                ?: return emptyList()
            val arr = runCatching { root.getAsJsonObject("query").getAsJsonArray("search") }
                .getOrNull() ?: return emptyList()
            return arr.mapNotNull { el ->
                val o = el.asJsonObject
                val title = o.get("title")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
                SearchHit(
                    title = title,
                    // MediaWiki 路径空格规范是下划线：URLEncoder 会给 +，服务端当字面 + 会 404
                    url = "https://$domain/wiki/" + URLEncoder.encode(title.replace(" ", "_"), "UTF-8"),
                    snippet = HtmlText.stripTags(o.get("snippet")?.takeIf { it.isJsonPrimitive }?.asString),
                    domain = domain,
                    source = sourceName
                )
            }.take(4)
        }
    }
}
