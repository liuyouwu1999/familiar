package dev.liuyouwu.familiar

import dev.liuyouwu.familiar.search.MultiSourceSearchEngine
import dev.liuyouwu.familiar.search.SearchEngine
import dev.liuyouwu.familiar.search.SearchHit
import dev.liuyouwu.familiar.search.SearchResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 多源聚合单测：交错取样顺序、去重键、失败语义、熔断跳过（fake 引擎计数触达）。 */
class MultiSourceSearchEngineTest {

    /** 可编程假引擎：记账调用次数，恒返回预置结果或抛异常。 */
    private class FakeEngine(
        private var hits: List<SearchHit> = emptyList(),
        private var diag: String = "解析 0 条",
        private var throwOnSearch: Exception? = null
    ) : SearchEngine {
        var calls = 0
        fun setHits(h: List<SearchHit>, d: String) { hits = h; diag = d }
        override suspend fun search(query: String): SearchResult {
            calls++
            throwOnSearch?.let { throw it }
            return SearchResult(hits, diag)
        }
    }

    private fun hit(title: String, url: String) = SearchHit(title, url, "摘要", "example.com")

    @Test
    fun `interleaves round robin in source order`() = runBlocking {
        val a = FakeEngine(listOf(hit("A1", "https://a.com/1"), hit("A2", "https://a.com/2")), "命中 2 条")
        val b = FakeEngine(listOf(hit("B1", "https://b.com/1"), hit("B2", "https://b.com/2")), "命中 2 条")
        val r = MultiSourceSearchEngine(listOf("必应" to a, "搜狗" to b)).search("q")
        // 每源轮询取 1 条：A1 B1 A2 B2
        assertEquals(listOf("A1", "B1", "A2", "B2"), r.hits.map { it.title })
        // 来源显示名回填
        assertEquals(listOf("必应", "搜狗", "必应", "搜狗"), r.hits.map { it.source })
    }

    @Test
    fun `dedupes by stripped-query url host path and by exact lowercase title`() = runBlocking {
        val a = FakeEngine(listOf(hit("同一篇", "https://example.com/x?utm=1")), "命中 1 条")
        val b = FakeEngine(listOf(
            hit("同一篇", "https://mirror.example.com/x"),   // 标题精确匹配去重（host 不同）
            hit("另一篇", "https://example.com/x"),          // URL host+path 去 query 后相同去重
            hit("独苗", "https://example.com/y"),            // 两把键都不同 → 保留
        ), "命中 2 条")
        val r = MultiSourceSearchEngine(listOf("必应" to a, "搜狗" to b)).search("q")
        assertEquals(listOf("同一篇", "独苗"), r.hits.map { it.title })
    }

    @Test
    fun `caps merged hits at eight`() = runBlocking {
        val fourA = (1..4).map { hit("A$it", "https://a.com/$it") }
        val fourB = (1..4).map { hit("B$it", "https://b.com/$it") }
        val a = FakeEngine(fourA, "命中 4 条")
        val b = FakeEngine(fourB, "命中 4 条")
        val r = MultiSourceSearchEngine(listOf("必应" to a, "搜狗" to b)).search("q")
        assertEquals(8, r.hits.size)
    }

    @Test
    fun `partial success is success with per source diagnostics`() = runBlocking {
        val ok = FakeEngine(listOf(hit("好结果", "https://a.com/1")), "命中 1 条")
        val bad = FakeEngine(diag = "解析 0 条（vrwrap 结构可能改版或被风控）")
        val r = MultiSourceSearchEngine(listOf("必应" to ok, "搜狗" to bad)).search("q")
        assertEquals(1, r.hits.size)
        assertTrue(r.diagnostics.contains("必应:命中 1 条"))
        assertTrue(r.diagnostics.contains("搜狗:解析 0 条（vrwrap 结构可能改版或被风控）"))
    }

    @Test
    fun `engine exception is contained per source`() = runBlocking {
        val ok = FakeEngine(listOf(hit("好结果", "https://a.com/1")), "命中 1 条")
        val boom = FakeEngine(throwOnSearch = IllegalStateException("连接被重置"))
        val r = MultiSourceSearchEngine(listOf("必应" to ok, "维基百科" to boom)).search("q")
        assertEquals(listOf("好结果"), r.hits.map { it.title })
        assertTrue(r.diagnostics.contains("维基百科:异常 连接被重置"))
    }

    @Test
    fun `all sources empty means failure semantics with summary`() = runBlocking {
        val a = FakeEngine(diag = "HTTP 403")
        val b = FakeEngine(diag = "网络异常：timeout")
        val r = MultiSourceSearchEngine(listOf("必应" to a, "搜狗" to b)).search("q")
        assertTrue(r.hits.isEmpty())
        assertTrue(r.diagnostics.contains("必应:HTTP 403"))
        assertTrue(r.diagnostics.contains("搜狗:网络异常：timeout"))
    }

    @Test
    fun `broken source is skipped without touching engine`() = runBlocking {
        val dead = FakeEngine(diag = "网络异常：timeout")   // 恒失败
        val ok = FakeEngine(listOf(hit("好结果", "https://a.com/1")), "命中 1 条")
        val multi = MultiSourceSearchEngine(listOf("维基百科" to dead, "必应" to ok))

        multi.search("q")   // 第 1 次：触达并失败
        multi.search("q")   // 第 2 次：触达并失败 → 计满熔断
        val third = multi.search("q")   // 第 3 次：熔断跳过，不再触达
        assertEquals(2, dead.calls)     // 前两次触达，第三次跳过
        assertEquals(3, ok.calls)       // 正常源每次都触达
        assertTrue(third.diagnostics.contains("维基百科:熔断跳过（近期连续失败）"))
        assertTrue(third.hits.map { it.title }.contains("好结果"))
    }
}
