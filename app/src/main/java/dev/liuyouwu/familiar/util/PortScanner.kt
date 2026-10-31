package dev.liuyouwu.familiar.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 二段端口扫描（连接设置"只填 IP 没填端口"的兜底，2026-10-30 用户实测
 * llama.cpp 跑在 8081 而一段常用名单 [8080,11434,1234,5000,8000,1337,7860] 探空）：
 * 一段名单失败后对扩展端口段并行快扫，谁先返回非空模型列表就回填谁的完整地址。
 * 独立 object：ConnectionViewModel 是 AndroidViewModel 无法 JVM 单测，此处以
 * probe 函数注入保持纯 JVM 可测（sweep 的 select 首中即返语义有回归测试钉死）。
 */
object PortScanner {

    /**
     * 扩展扫描段：8000-8099（llama.cpp/本地服务换端口重灾区，覆盖 8081/8082…）、
     * 3000-3009、5000-5010；与一段常用名单取差集（5000/8000 已在一段探过）。
     */
    fun extendedPortsExcluding(common: Collection<Int>): List<Int> =
        ((8000..8099) + (3000..3009) + (5000..5010)).filter { it !in common.toSet() }

    /**
     * 并行快扫：对 [ports] 各起一个探针（[probe] 收改写端口后的完整 base URL，
     * 返回非空模型列表 = 命中，null = 未命中），**首中即返**并取消其余探针；
     * 全部未命中返回 null。select 逐个收割完成事件：只有"已收割的未命中"从
     * pending 移除——几乎同时完成时，其余探针的结果不丢（晚到命中照样回收）。
     * 122 个探针全部瞬时发起（同步 execute 不受 OkHttp Dispatcher 限流），
     * 由 Dispatchers.IO 线程池承载；探针自带 1s 级短超时，总扫时长有界。
     */
    suspend fun sweep(
        hostUrl: String,
        ports: List<Int>,
        probe: suspend (String) -> List<String>?,
    ): Pair<String, List<String>>? = withContext(Dispatchers.IO) {
        val base = hostUrl.toHttpUrlOrNull() ?: return@withContext null
        coroutineScope {
            val probes: List<Deferred<List<String>?>> = ports.map { port ->
                async {
                    val candidate = base.newBuilder().port(port).build().toString().trimEnd('/')
                    probe(candidate)
                }
            }
            var pending = probes
            var result: Pair<String, List<String>>? = null
            while (result == null && pending.isNotEmpty()) {
                var resolved: Deferred<List<String>?>? = null
                val hit: List<String>? = select {
                    pending.forEach { d -> d.onAwait { v -> resolved = d; v } }
                }
                if (hit != null) {
                    val winner = resolved
                    probes.forEach { if (it !== winner) it.cancel() }
                    // winner 定位必须用全量 probes（pending 是过滤后的子集，索引错位）
                    result = base.newBuilder()
                        .port(ports[probes.indexOf(winner)])
                        .build().toString().trimEnd('/') to hit
                } else {
                    pending = pending.filterNot { it === resolved }
                }
            }
            result
        }
    }
}
