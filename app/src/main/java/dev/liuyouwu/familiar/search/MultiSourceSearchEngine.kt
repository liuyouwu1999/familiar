package dev.liuyouwu.familiar.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * 多源聚合引擎：并发打所有未熔断源，round-robin 交错去重取样至多 6 条。
 * 为什么聚合：单源抓取不稳（必应偶发降级垃圾页），多源冗余 + 每源失败独立降级，
 * 部分成功即成功；全部失败才走失败语义（diagnostics 汇总逐源原因便于自查）。
 * 熔断器随本实例存活（每次会话装配一个），跨搜索记住哪些源连续挂了。
 */
class MultiSourceSearchEngine(
    private val engines: List<Pair<String, SearchEngine>>  // (来源显示名, 引擎)
) : SearchEngine {

    private val breakers: List<SearchBreaker> = engines.map { SearchBreaker() }

    /** 单源结果：hits 为空即该源失败，diag 为该源的逐条诊断片段。 */
    private class SourceOutcome(val hits: List<SearchHit>, val diag: String)

    override suspend fun search(query: String): SearchResult = withContext(Dispatchers.IO) {
        coroutineScope {
            val outcomes: List<SourceOutcome> = engines.mapIndexed { i, (name, engine) ->
                async {
                    val breaker = breakers[i]
                    if (breaker.isBroken) {
                        SourceOutcome(emptyList(), "$name:熔断跳过（近期连续失败）")
                    } else {
                        // 引擎自身已兜底不抛异常，这里 catch 是最后一道：单源崩不拖垮整体
                        try {
                            val r = engine.search(query)
                            if (r.hits.isEmpty()) {
                                breaker.recordFailure()
                                SourceOutcome(emptyList(), "$name:${r.diagnostics}")
                            } else {
                                breaker.recordSuccess()
                                // 单源引擎不带 source，聚合层统一回填来源显示名
                                SourceOutcome(r.hits.map { it.copy(source = name) }, "$name:命中 ${r.hits.size} 条")
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            breaker.recordFailure()
                            SourceOutcome(emptyList(), "$name:异常 ${e.message}")
                        }
                    }
                }
            }.map { it.await() }

            // limit 8（2026-10-30 jev 事故五件套：6→8，五源交错下每源 ≥1 条的容量）
            val merged = interleaveDedupe(outcomes.map { it.hits }, limit = 8)
            val diagnostics = outcomes.joinToString(" ") { it.diag }
            if (merged.isEmpty()) SearchResult(emptyList(), diagnostics)
            else SearchResult(merged, diagnostics)
        }
    }

    /**
     * round-robin 交错取样：每源轮询取 1 条（源顺序=传入顺序），去重至多 limit 条。
     * 去重键 = URL 去 query 后的小写 host+path，以及小写标题精确匹配（两把都记）。
     */
    private fun interleaveDedupe(sources: List<List<SearchHit>>, limit: Int): List<SearchHit> {
        val merged = mutableListOf<SearchHit>()
        val seen = HashSet<String>()
        var round = 0
        while (merged.size < limit) {
            var advanced = false
            for (hits in sources) {
                if (round >= hits.size) continue
                advanced = true
                val h = hits[round]
                val hostPath = h.url.substringBefore('?').substringBefore('#')
                    .replace(Regex("^https?://"), "").lowercase()
                val keys = listOf("u:$hostPath", "t:${h.title.lowercase()}")
                if (keys.none { it in seen }) {
                    seen.addAll(keys)
                    merged.add(h)
                    if (merged.size >= limit) break
                }
            }
            if (!advanced) break
            round++
        }
        return merged
    }
}
