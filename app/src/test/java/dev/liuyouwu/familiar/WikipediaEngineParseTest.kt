package dev.liuyouwu.familiar

import dev.liuyouwu.familiar.search.WikipediaEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 维基百科解析的 fixture 单测：action=query&list=search 的 JSON 结构锁定
 *  （snippet 含 searchmatch 高亮标签需剥掉；url 按标题拼 wiki 路径）。 */
class WikipediaEngineParseTest {

    private val fixture = """
        {"batchcomplete":"","query":{"searchinfo":{"totalhits":2180},
          "search":[
            {"ns":0,"title":"日本脑炎","pageid":123,
             "snippet":"<span class=\"searchmatch\">日本脑炎</span>（Japanese encephalitis）由<span class=\"searchmatch\">JEV</span>引起，经蚊传播"},
            {"ns":0,"title":"乙型脑炎疫苗","pageid":456,
             "snippet":"<span class=\"searchmatch\">乙型脑炎疫苗</span>用于预防日本脑炎 &amp; 相关病毒感染"}
          ]}}
    """.trimIndent()

    @Test
    fun `parses wikipedia search json`() {
        val hits = WikipediaEngine.parseWikipedia(fixture)
        assertEquals(2, hits.size)
        val first = hits[0]
        assertEquals("日本脑炎", first.title)
        // snippet 的高亮标签剥掉但内容保留
        assertEquals("日本脑炎（Japanese encephalitis）由JEV引起，经蚊传播", first.snippet)
        // url 按 URLEncoder 标题拼 wiki 路径；domain/source 固定
        assertEquals(
            "https://zh.wikipedia.org/wiki/" + java.net.URLEncoder.encode("日本脑炎", "UTF-8"),
            first.url
        )
        assertEquals("zh.wikipedia.org", first.domain)
        assertEquals("维基百科", first.source)
        // 摘要里的实体也解码
        assertTrue(hits[1].snippet.endsWith("& 相关病毒感染"))
    }

    @Test
    fun `garbage json or missing query parses to empty not crash`() {
        assertTrue(WikipediaEngine.parseWikipedia("<html>被墙拦截页</html>").isEmpty())
        assertTrue(WikipediaEngine.parseWikipedia("""{"error":"no result"}""").isEmpty())
        assertTrue(WikipediaEngine.parseWikipedia("""{"query":{"search":[]}}""").isEmpty())
        assertTrue(WikipediaEngine.parseWikipedia("").isEmpty())
    }

    @Test
    fun `entry without title is skipped`() {
        val json = """{"query":{"search":[{"pageid":1,"snippet":"无标题"},{"title":"正常","snippet":""}]}}"""
        val hits = WikipediaEngine.parseWikipedia(json)
        assertEquals(1, hits.size)
        assertEquals("正常", hits[0].title)
    }

    @Test
    fun `spaced title uses underscore in wiki path`() {
        // MediaWiki 路径空格规范为下划线；URLEncoder 的 + 服务端当字面 + 会 404
        val json = """{"query":{"search":[{"title":"Dengue fever","snippet":"登革热"}]}}"""
        val hits = WikipediaEngine.parseWikipedia(json)
        assertEquals("https://zh.wikipedia.org/wiki/Dengue_fever", hits[0].url)
        val json2 = """{"query":{"search":[{"title":"日本脑炎 疫苗","snippet":""}]}}"""
        assertEquals(
            "https://zh.wikipedia.org/wiki/" + java.net.URLEncoder.encode("日本脑炎_疫苗", "UTF-8"),
            WikipediaEngine.parseWikipedia(json2)[0].url
        )
    }

    @Test
    fun `英文站参数化 url 与来源名`() {
        // 2026-10-30 jev 事故：拉丁字母新技术词 zh 维基常无条目，en 站补位
        val json = """{"query":{"search":[{"title":"Jev (model)","snippet":"AI model"}]}}"""
        val hits = WikipediaEngine.parseWikipedia(json, lang = "en")
        assertEquals(
            "https://en.wikipedia.org/wiki/" + java.net.URLEncoder.encode("Jev_(model)", "UTF-8"),
            hits[0].url
        )
        assertEquals("英文维基", hits[0].source)
        assertEquals("en.wikipedia.org", hits[0].domain)
    }
}
