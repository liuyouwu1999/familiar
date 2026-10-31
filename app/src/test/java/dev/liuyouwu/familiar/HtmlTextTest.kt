package dev.liuyouwu.familiar

import dev.liuyouwu.familiar.search.HtmlText
import org.junit.Assert.assertEquals
import org.junit.Test

/** 实体解码/剥标签共用工具的单测：命名 + 数字（十/十六进制）+ 混合 + 越界兜底。 */
class HtmlTextTest {

    @Test
    fun `decodes common named entities`() {
        assertEquals("a&b", HtmlText.decodeEntities("a&amp;b"))
        assertEquals("<tag>", HtmlText.decodeEntities("&lt;tag&gt;"))
        assertEquals("\"说\"话\"", HtmlText.decodeEntities("&quot;说&quot;话&quot;"))
        assertEquals("it's", HtmlText.decodeEntities("it&#39;s"))
        // nbsp 折算普通空格：U+00A0 不是 isWhitespace，留着会让空摘要误判非空
        assertEquals("x y", HtmlText.decodeEntities("x&nbsp;y"))
    }

    @Test
    fun `decodes numeric entities decimal and hex`() {
        assertEquals("中", HtmlText.decodeEntities("&#20013;"))
        assertEquals("中", HtmlText.decodeEntities("&#x4E2D;"))
        assertEquals("中", HtmlText.decodeEntities("&#x4e2d;"))
        // 越出 BMP 的码点走代理对，不产生乱码单 char
        assertEquals("😀", HtmlText.decodeEntities("&#128512;"))
    }

    @Test
    fun `mixed content decodes in one pass without double decoding`() {
        // &amp;lt; 必须得 &lt; 而不是 <（单遍替换，命名与数字实体混排）
        assertEquals("&lt;b&gt;", HtmlText.decodeEntities("&amp;lt;b&amp;gt;"))
        assertEquals("A<B&C>\"' ", HtmlText.decodeEntities("A&lt;B&amp;C&gt;&quot;&#39;&nbsp;"))
    }

    @Test
    fun `unknown or invalid entities stay verbatim`() {
        assertEquals("&nosuch;", HtmlText.decodeEntities("&nosuch;"))
        // 码点越界（> 0x10FFFF）解码失败原样保留，不抛异常
        assertEquals("&#x110000;", HtmlText.decodeEntities("&#x110000;"))
        assertEquals("&#;", HtmlText.decodeEntities("&#;"))
    }

    @Test
    fun `stripTags removes tags and comments then decodes then trims`() {
        // 搜狗摘要里的 <!--red_beg--> 标记注释也属于 <[^>]*>，一并吃掉
        assertEquals(
            "JEV是什么疫苗",
            HtmlText.stripTags("<em><!--red_beg-->JEV<!--red_end--></em>是什么<em>疫苗</em>  ")
        )
        // 先剥标签再解码：&lt;div&gt; 解码出的尖括号不能再被当标签剥掉
        assertEquals("<div>正文</div>", HtmlText.stripTags("&lt;div&gt;正文&lt;/div&gt;"))
        assertEquals("", HtmlText.stripTags(null))
        assertEquals("", HtmlText.stripTags(""))
    }
}
