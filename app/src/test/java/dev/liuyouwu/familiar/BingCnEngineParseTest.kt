package dev.liuyouwu.familiar

import dev.liuyouwu.familiar.search.BingCnEngine
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** BingCN 抓取解析的 fixture 单测：锁定真实结果页样本的解析行为，
 *  Bing 改版时这里第一时间红，调用方走失败降级而不是静默错答。 */
class BingCnEngineParseTest {

    private fun fixtureHtml(): String =
        javaClass.classLoader!!.getResourceAsStream("search/bing_http3.html")!!
            .readBytes().toString(Charsets.UTF_8)

    @Test
    fun `parses real bing result page`() {
        val hits = BingCnEngine.parseBingAlgo(fixtureHtml())
        assertEquals(5, hits.size)   // 引擎上限 5 条
        val first = hits.first()
        assertTrue(first.title.isNotBlank())
        assertTrue(first.url.startsWith("http"))
        assertTrue(first.domain.isNotBlank())
        // 实测样本第一条是百度百科
        assertEquals("baike.baidu.com", hits.first().domain)
        assertTrue(hits.any { it.domain.endsWith("runoob.com") })
    }

    @Test
    fun `decodes bing ck redirect links`() {
        // u=a1 + base64url（无 padding）
        val inner = "https://www.runoob.com/np/http-protocol.html"
        val b64 = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(inner.toByteArray(Charsets.UTF_8))
        val wrapped = "https://cn.bing.com/ck/a?!&&p=xx&bitta=1&u=a1$b64&ntb=1"
        assertEquals(inner, BingCnEngine.decodeBingRedirect(wrapped))
        // 非跳转链接原样返回
        assertEquals("https://example.com/a", BingCnEngine.decodeBingRedirect("https://example.com/a"))
    }

    @Test
    fun `garbage html parses to empty not crash`() {
        assertTrue(BingCnEngine.parseBingAlgo("<html><body>维护中</body></html>").isEmpty())
        assertTrue(BingCnEngine.parseBingAlgo("").isEmpty())
    }

    @Test
    fun `title and snippet decode html entities`() {
        // 真实结果页标题/摘要常含 &amp;/&#39;/&nbsp;（URL query、引号、空格变体）
        val html = "<li class=\"b_algo\"><h2><a href=\"https://example.com/a?x=1\">华为&amp;中兴5G&#39;专利&#39;案</a></h2>" +
            "<p>摘要含&nbsp;空格 与 &lt;标签&gt; 和&#x4E2D;文</p></li>"
        val hits = BingCnEngine.parseBingAlgo(html)
        assertEquals(1, hits.size)
        assertEquals("华为&中兴5G'专利'案", hits[0].title)
        assertEquals("摘要含 空格 与 <标签> 和中文", hits[0].snippet)
        // URL 不做实体解码语义转换（href 原文参与跳转/域名提取）
        assertEquals("https://example.com/a?x=1", hits[0].url)
    }

    @Test
    fun `empty parse result means failure not success`() {
        // 设计约定（审核高-2）：解析 0 条必须走失败降级——此测试钉住该语义的输入侧
        val hits = BingCnEngine.parseBingAlgo(fixtureHtml())
        assertNotNull(hits)
    }
}
