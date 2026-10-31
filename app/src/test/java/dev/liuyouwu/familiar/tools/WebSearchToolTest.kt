package dev.liuyouwu.familiar.tools

import com.google.gson.JsonObject
import dev.liuyouwu.familiar.search.SearchEngine
import dev.liuyouwu.familiar.search.SearchHit
import dev.liuyouwu.familiar.search.SearchResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索降级语义（2026-10-30 jev 事故修订）：0 结果不再教模型"凭已有知识继续"
 * （jev 爆火新词不在训练语料 → 该指令=教唆编造），改为如实告知+常识显式标注；
 * 0 结果剥词重试一轮；重试命中走正常结果格式。
 */
class WebSearchToolTest {

    private class FakeEngine(
        private val hitsByQuery: Map<String, List<SearchHit>>,
        private val diag: String = "解析 0 条",
    ) : SearchEngine {
        val queries = mutableListOf<String>()
        override suspend fun search(query: String): SearchResult {
            queries.add(query)
            val hits = hitsByQuery[query]
            return if (hits.isNullOrEmpty()) SearchResult(emptyList(), diag) else SearchResult(hits, "命中 ${hits.size} 条")
        }
    }

    private fun hit(title: String) = SearchHit(title, "https://a.com/$title", "摘要", "a.com", "必应")

    @Test
    fun `0 结果不再教模型凭已有知识回答`() = runBlocking {
        val tool = WebSearchTool(FakeEngine(emptyMap()))
        val out = tool.execute(JsonObject().apply { addProperty("query", "jev AI model") }) { }
        assertTrue("应如实告知无结果", out.contains("没有查到结果"))
        assertTrue("常识补充须显式标注", out.contains("非搜索内容"))
        assertTrue("不得再出现教唆编造的旧文案", !out.contains("基于你已有的知识继续回答"))
    }

    @Test
    fun `0 结果剥词重试一轮并命中`() = runBlocking {
        val engine = FakeEngine(mapOf("jev" to listOf(hit("Jev 模型解读"))))
        val tool = WebSearchTool(engine)
        val out = tool.execute(JsonObject().apply { addProperty("query", "jev AI model") }) { }
        assertEquals(listOf("jev AI model", "jev"), engine.queries)
        assertTrue("重试命中应返回正常结果格式", out.contains("<<<搜索结果开始>>>"))
        assertTrue(out.contains("Jev 模型解读"))
    }

    @Test
    fun `重试仍空走降级文案`() = runBlocking {
        val tool = WebSearchTool(FakeEngine(emptyMap()))
        val out = tool.execute(JsonObject().apply { addProperty("query", "冷门词xyz qqq") }) { }
        assertTrue(out.contains("没有查到结果"))
    }

    @Test
    fun `正常命中不触发重试`() = runBlocking {
        val engine = FakeEngine(mapOf("正常查询" to listOf(hit("结果"))))
        val tool = WebSearchTool(engine)
        tool.execute(JsonObject().apply { addProperty("query", "正常查询") }) { }
        assertEquals(listOf("正常查询"), engine.queries)
    }

    @Test
    fun `coreQueryOf 剥词规则`() {
        assertEquals("jev", WebSearchTool.coreQueryOf("jev AI model"))
        assertEquals("RAG", WebSearchTool.coreQueryOf("什么是 RAG"))
        assertEquals("大肥鱼", WebSearchTool.coreQueryOf("大肥鱼是什么意思"))
        assertNull("整串即核心词时不重试", WebSearchTool.coreQueryOf("jev"))
        assertNull("剥完为空不重试", WebSearchTool.coreQueryOf("？"))
    }
}
