package dev.liuyouwu.familiar

import dev.liuyouwu.familiar.search.SogouEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 搜狗抓取解析的 fixture 单测：样本取自 2026-10-27 PC 直连真实结果页（裁剪脱敏），
 *  锚点 = vrwrap 切块 / vr-title 首个 <a> / space-txt|space-small 摘要 / cite 显示 URL 域名。 */
class SogouEngineParseTest {

    /** 三个真实结果块：百科卡片(space-txt)、视频卡(clamp2 变体)、Nature 英文卡(red_beg 注释标记) */
    private val fixture = """
        <div class="vrwrap"><h3 class="vr-title  "  vrcid="title.ba18e87">              <a class=" " target="_blank" href="/link?url=hedJjaC291OfGk423QpE647VV2qRKcS2BldbSsrFPMORsVJSWe4qcrVGOPZkV2SJOHdl-hdEkGA."   ><em><!--red_beg-->JEV<!--red_end--></em>是什么<em><!--red_beg-->疫苗<!--red_end--></em>-专家文章-博禾医生         </a>                         </h3>
        <div class="fz-mid space-txt"  vrsid="otherLayout.ab819d7" vrcid="otherLayout.876963e">            一般情况下，<em><!--red_beg-->JEV<!--red_end--></em>是指流行性乙脑病毒，而并非<em><!--red_beg-->疫苗<!--red_end--></em>。具体分析如下： 流行性乙脑病毒首先从日本患者的脑组织中分离，所以其英文简写为<em><!--red_beg-->JEV<!--red_end--></em>，该病毒属...                                                            </div>
        <a class="citeLinkClass" target="_blank" href="/link?url=xxx"  vrcid="citeurl.876963e"><img class="footerIcon" src="https://cdn-yb.icon.qq.com/zuowei_dir/www_bohe_cn.ico" ><span>博禾医生</span><span class="">https://www.bohe.cn/a...</span><span class="cite-date">2025-12-02</span>      </a></div>
        <div class="vrwrap"><h3 class="vr-title"><!--awbg1--><a name="dttl" target="_blank" href="/link?url=hedJjaC291NG84d_4zUswnkYGk65FGGCaJrgRIXUqR5omuBEhdSpHkQrNIZvamWPa7f-ZR0Jfx3QT3jFNDbDeEN6wheu7wF-uSTghKs7xfclR6rs9BckNQ.." id="sogou_vr_30000000_1" cachestrategy="qcr:-1" bingpb=""><!--awbg1-->图文详情</a></h3><div class="fz-mid space-txt base-ellipsis clamp2" id="cacheresult_summary_1">(Japanese Encephalitis <em>Vaccine</em>,<em>JEV</em>)可以同时在不同部位接种。但因发现<em>JEV</em>与MV同时接种后,MV的免疫应答有轻微降低,因此鼓励针对<em>JEV</em>与MV同时...</div>  <a class="citeLinkClass" target="_blank" href="/link?url=yyy" id="cacheresult_cite_1"> <img class="footerIcon" src="https://cdn-yb.icon.qq.com/zuowei_dir/www_kepuchina_cn.ico" alt="cite"> <span>科普中国网</span> <span>https://www.kepuchina.cn/a...</span>  <span>2021-12-31</span>  </a></div>
        <div class="vrwrap"><h3 class="vr-title  "  vrcid="title.ba18e87">
                <a class=" " target="_blank" href="/link?url=hedJjaC291NiC3354PawSpj9Z9hoHnf-wtblb-9F2pJ68nt5jtalo8g7GcWNwIsU8uoSdGIVR9nFCKulYCCLhbT0cQwBoEyT"   >Japanese encephalitis <em><!--red_beg-->vaccine<!--red_end--></em>-specific envelope protein E138K ...
                </a></h3>
        <div class="fz-mid space-txt"  vrsid="otherLayout.ab819d7" vrcid="otherLayout.876963e">The most widely used <em><!--red_beg-->JEV<!--red_end--></em> <em><!--red_beg-->vaccine<!--red_end--></em> utilizes a live attenuated...</div>
        <a class="citeLinkClass" target="_blank" href="/link?url=zzz"  vrcid="citeurl.876963e"><img class="footerIcon" src="https://cdn-yb.icon.qq.com/zuowei_dir/www_nature_com.ico" ><span>Nature</span><span class="">https://www.nature.com/a...</span><span class="cite-date">2019-12-05</span>      </a></div>
    """.trimIndent()

    @Test
    fun `parses real sogou result blocks`() {
        val hits = SogouEngine.parseSogou(fixture)
        assertEquals(3, hits.size)
        val first = hits[0]
        // red_beg/red_end 标记注释随标签一起剥掉
        assertEquals("JEV是什么疫苗-专家文章-博禾医生", first.title)
        // 站内相对跳转链接补全为绝对地址，原样保留（浏览器可跳转）
        assertEquals(
            "https://www.sogou.com/link?url=hedJjaC291OfGk423QpE647VV2qRKcS2BldbSsrFPMORsVJSWe4qcrVGOPZkV2SJOHdl-hdEkGA.",
            first.url
        )
        assertTrue(first.snippet.startsWith("一般情况下"))
        assertTrue(first.snippet.contains("流行性乙脑病毒"))
        assertEquals("www.bohe.cn", first.domain)
    }

    @Test
    fun `handles clamp2 snippet variant and cite span without class`() {
        val hits = SogouEngine.parseSogou(fixture)
        // 第二块：clamp2 变体摘要 + 无 class 的 cite span
        assertEquals("图文详情", hits[1].title)
        assertEquals("www.kepuchina.cn", hits[1].domain)
        assertTrue(hits[1].snippet.contains("可以同时在不同部位接种"))
        // 第三块：英文结果，域名取 nature.com
        assertTrue(hits[2].title.startsWith("Japanese encephalitis vaccine-specific"))
        assertEquals("www.nature.com", hits[2].domain)
        assertTrue(hits[2].snippet.contains("The most widely used"))
    }

    @Test
    fun `missing snippet yields empty string but title and url survive`() {
        val html = """<div class="vrwrap"><h3 class="vr-title"><a target="_blank" href="/link?url=abc">只有标题</a></h3></div>"""
        val hits = SogouEngine.parseSogou(html)
        assertEquals(1, hits.size)
        assertEquals("只有标题", hits[0].title)
        assertEquals("https://www.sogou.com/link?url=abc", hits[0].url)
        assertEquals("", hits[0].snippet)
        // cite 区域没有显示 URL 时域名退化 sogou.com
        assertEquals("sogou.com", hits[0].domain)
    }

    @Test
    fun `garbage html parses to empty not crash`() {
        assertTrue(SogouEngine.parseSogou("<html>验证码</html>").isEmpty())
        assertTrue(SogouEngine.parseSogou("").isEmpty())
    }
}
