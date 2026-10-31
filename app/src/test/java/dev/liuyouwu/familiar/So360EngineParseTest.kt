package dev.liuyouwu.familiar

import dev.liuyouwu.familiar.search.So360Engine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 360 搜索抓取解析的 fixture 单测：样本取自 2026-10-27 PC 直连真实结果页（裁剪脱敏），
 *  锚点 = res-list 切块 / res-title 首个 <a> / data-result-summary（退化 res-desc）/ cite 域名。 */
class So360EngineParseTest {

    /** 两个真实结果块：英文站(跳转链接+cite=4adi.com)、疫苗网(跳转链接+cite=vaccine.vip) */
    private val fixture = """
        <li class="res-list" data-result-pos="1"><h3 class="res-title " >
        <a  href="https://www.so.com/link?m=ewaNxu5N3zA1y5gKenPxNhrUvg1mnX%2FDuI6gP3sUGJ1MUVx3a3YA%2BFoO1qNaAJJCR1aqrhbWU80hxE6kz2jMjLPC7cTpP7o9coTI60%2BzU5hQxtyFXSQssZ1Egx5Be6Hx3h9ZRoJLcYZb%2BAjIUgLGwk%2BOaiwU73jdIrw8%2F5%2B%2BHfhuYL1zJIPGtUQ%3D%3D"  rel="noopener" target="_blank">Japanese Encephalitis Virus (<em>JEV</em>) <em>Vaccine</em></a></h3>
        <p class="res-desc"><span data-result-summary>ELISA Kits and reagents for Infectious Disease and <em>Vaccine</em> research ELISA Kits and reagents for Therapeutic mAbs, biosimilars, and proteins Infectious animal di...</span></p><p class="g-linkinfo"><cite><a href="https://www.so.com/link?m=uwvcmamReA88fm1BsRic1I1PlB%2B2PjTYl%2BF063dPQT56fpJV%2FkKHTtkrbKF7lW0%2BJsNOvWm3HpPu4IA%2FWvkxwiZLeqOwWDq7wL7lNy2l5HTzRJqdCvNudQ6g2vv%2FZahu%2BI6WnclBAlQtjSrEFsopTaRAsgbjjHkUwah9KYw%3D%3D" target="_blank" class="g-linkinfo-a" rel="noopener">4adi.com</a></cite></p></li>
        <li class="res-list" data-result-pos="3"><h3 class="res-title " >
        <a  href="https://www.so.com/link?m=zQpe8Vla0QQJ5vrcWbRjpy7IDGD2fuPz%2BCa%2Bzvm1mPOU4LxFFemWMkFYiPRA2HDlR%2B8n1jsMKK%2FCMOKX3JR1WPTaMwta9C%2FxHjUL1WLVaiUWwDFcmiwNd7xwKulpyOsedJo7OfNZz5CfAHnzgUrCs%2Bdq4SFIopMzGGhHBuR6e2bw8yhmCxQLyN0xb%2BWt0QXgEjfBE6DO9Sht9R5H2u5kcRmPIGlvm8zkByc976K3ZpApv6KEKLFAfJ4NelFYt1BFViIlBo6Gwfdbgv29wB02iXqTYA1RSFEWv51rpHC4aWQM8hdyk92PflQ%2BTmDhkiIbP3yR9fA%3D%3D" data-mdurl="https://vaccine.vip/tag/jev/"  rel="noopener" target="_blank">日本脑炎病毒(<em>JEV</em>)疫苗 – 疫苗网:疫苗知识的传播者!</a></h3>
        <p class="res-desc"><span data-result-summary><em>JEV</em>是一种毁灭性的疾病,经常导致感染者永久性脑损伤或死亡。幸运的是,在从美国到该疾病流行的远东地区旅行的人中,<em>JEV</em>是一种不常见的疾病原因。每年从美国到这些地区的旅行...</span></p><p class="g-linkinfo"><cite><a href="https://www.so.com/link?m=zRT16cESkFPjESoYgi0NUCAt5Z3hJ8ycOwXar%2FivwlIC1gxdLKnsx8UYQ4B8c8IVLO%2Fhq6CUJJmPjZQNMXnqxHULples5t7w0Pu3kVGwH4It2lPEYcJsQCzqQq4T9ZiYVVyTwC9ye5%2BFtJ2rIIS7DrXVt" target="_blank" class="g-linkinfo-a" rel="noopener">vaccine.vip</a></cite></p></li>
    """.trimIndent()

    @Test
    fun `parses real so360 result blocks`() {
        val hits = So360Engine.parse360(fixture)
        assertEquals(2, hits.size)
        val first = hits[0]
        assertEquals("Japanese Encephalitis Virus (JEV) Vaccine", first.title)
        // 跳转链接原样保留（浏览器可跳转），不从 data-mdurl 换真实 URL
        assertTrue(first.url.startsWith("https://www.so.com/link?m=ewaNxu5N3zA1y5gKenPxNhrUvg1mnX"))
        assertTrue(first.snippet.startsWith("ELISA Kits and reagents"))
        // 跳转链接的域名从块内 cite 显示文本提取
        assertEquals("4adi.com", first.domain)
        assertEquals("vaccine.vip", hits[1].domain)
        assertTrue(hits[1].title.contains("疫苗网"))
        assertTrue(hits[1].snippet.contains("永久性脑损伤或死亡"))
    }

    @Test
    fun `real url href keeps form decodes entities and domain from host`() {
        val html = """<li class="res-list"><h3 class="res-title"><a href="https://example.com/page?id=1&amp;lang=zh">真实链接结果</a></h3>
            <p class="res-desc"><span data-result-summary>摘要文本</span></p></li>"""
        val hits = So360Engine.parse360(html)
        assertEquals(1, hits.size)
        // 链接形态保留（不拆跳转），但属性实体要解码，否则首个参数后的 query 全坏
        assertEquals("https://example.com/page?id=1&lang=zh", hits[0].url)
        assertEquals("example.com", hits[0].domain)
    }

    @Test
    fun `falls back to res-desc text when summary span missing`() {
        val html = """<li class="res-list"><h3 class="res-title"><a href="https://www.so.com/link?m=abc">标题</a></h3>
            <p class="res-desc">退化摘要，含<em>标记</em>文本</p><cite>fallback.cn</cite></li>"""
        val hits = So360Engine.parse360(html)
        assertEquals(1, hits.size)
        assertEquals("退化摘要，含标记文本", hits[0].snippet)
        assertEquals("fallback.cn", hits[0].domain)
    }

    @Test
    fun `garbage html parses to empty not crash`() {
        assertTrue(So360Engine.parse360("<html>反爬拦截</html>").isEmpty())
        assertTrue(So360Engine.parse360("").isEmpty())
        // 有 res-list 但无 res-title 锚点的块跳过而非崩溃
        assertTrue(So360Engine.parse360("""<li class="res-list"><div>推荐卡片</div></li>""").isEmpty())
    }
}
