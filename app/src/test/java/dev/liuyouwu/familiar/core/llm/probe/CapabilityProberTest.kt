package dev.liuyouwu.familiar.core.llm.probe

import dev.liuyouwu.familiar.core.llm.wire.ToolMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/**
 * 四层探测（spec §4.4）纯逻辑测试：transport/cache 全部注入假实现，不发任何网络。
 * 注意：P1 单测【不读】assets/model_capabilities.json——assets 读取留 P3 接线，
 * ModelCatalog 一律直接构造 / fromJson 字面 JSON（与 assets 内容物同形）。
 */
class CapabilityProberTest {

    private val catalog = ModelCatalog(listOf(
        ModelCatalog.Family("qwen3", ToolCapability.NATIVE),
        ModelCatalog.Family("gemma", ToolCapability.MARKER),
    ))

    private fun prober(t: ProbeTransport, c: CapabilityCache = InMemoryCapabilityCache()) =
        CapabilityProber(catalog, t, c)

    /** 按步脚本化的假传输：steps 记录到达顺序；TOOL_FOLLOWUP 未脚本即 fail-fast */
    private class FakeTransport(
        private val first: ProbeResult,
        private val second: ProbeResult? = null,
    ) : ProbeTransport {
        val steps = mutableListOf<ProbeStep>()
        override suspend fun roundTrip(step: ProbeStep): ProbeResult {
            steps += step
            return when (step) {
                ProbeStep.WITH_TOOLS -> first
                ProbeStep.TOOL_FOLLOWUP -> second ?: error("unexpected TOOL_FOLLOWUP roundtrip")
            }
        }
        override suspend fun ollamaCapabilities(baseUrl: String): Set<String>? = null
    }

    /** 任何 roundTrip 都算违规（用于钉死"不该发请求"的优先级） */
    private class NoCallTransport : ProbeTransport {
        override suspend fun roundTrip(step: ProbeStep): ProbeResult =
            error("transport must not be called, got $step")
        override suspend fun ollamaCapabilities(baseUrl: String): Set<String>? = null
    }

    // ===== 第 3 层两步实测：判定矩阵 =====

    @Test
    fun `two step tool call then ok yields native and caches`() = runBlocking {
        val t = FakeTransport(
            first = ProbeResult.ToolCalled("call_1"),
            second = ProbeResult.Ok("现在是 14:32。"),
        )
        val cache = InMemoryCapabilityCache()
        val verdict = prober(t, cache).resolve("ollama", "http://192.168.1.5:11434", "qwen3:32b")
        assertEquals(ToolCapability.NATIVE, verdict)
        // 两步按序发生：WITH_TOOLS → TOOL_FOLLOWUP
        assertEquals(listOf(ProbeStep.WITH_TOOLS, ProbeStep.TOOL_FOLLOWUP), t.steps)
        // 探测成功（含 NATIVE 判定）必须落缓存
        assertEquals(ToolCapability.NATIVE,
            cache.get("toolcap:ollama:http://192.168.1.5:11434:qwen3:32b"))
    }

    @Test
    fun `first step 4xx mentioning tools yields marker`() = runBlocking {
        val t = FakeTransport(first = ProbeResult.HttpError(400, "'tools' is not supported by this model"))
        val cache = InMemoryCapabilityCache()
        val verdict = prober(t, cache).resolve("lm_studio", "http://192.168.1.5:1234", "gemma-3-12b")
        assertEquals(ToolCapability.MARKER, verdict)
        // 第一步即定论，不发第二步
        assertEquals(listOf(ProbeStep.WITH_TOOLS), t.steps)
        assertEquals(ToolCapability.MARKER,
            cache.get("toolcap:lm_studio:http://192.168.1.5:1234:gemma-3-12b"))
    }

    @Test
    fun `first step 200 without tool call yields marker`() = runBlocking {
        val t = FakeTransport(first = ProbeResult.Ok("现在是下午三点左右。"))
        val cache = InMemoryCapabilityCache()
        val verdict = prober(t, cache).resolve("ollama", "http://localhost:11434", "some-model")
        assertEquals(ToolCapability.MARKER, verdict)
        assertEquals(listOf(ProbeStep.WITH_TOOLS), t.steps)
        assertEquals(ToolCapability.MARKER,
            cache.get("toolcap:ollama:http://localhost:11434:some-model"))
    }

    @Test
    fun `second step 4xx yields marker`() = runBlocking {
        // 报错文案不提 tools 也判 MARKER：role:"tool" 回传被 4xx 定式拒绝即原生回路不通（spec 评审 C2）
        val t = FakeTransport(
            first = ProbeResult.ToolCalled("c1"),
            second = ProbeResult.HttpError(400, "invalid message role"),
        )
        val cache = InMemoryCapabilityCache()
        val verdict = prober(t, cache).resolve("llama_cpp", "http://10.0.2.2:8080", "m1")
        assertEquals(ToolCapability.MARKER, verdict)
        assertEquals(ToolCapability.MARKER, cache.get("toolcap:llama_cpp:http://10.0.2.2:8080:m1"))
    }

    // ===== 瞬时故障：回落第 1 层先验且不写缓存（spec 评审二 P2-3） =====

    @Test
    fun `transient falls back to catalog prior without writing cache`() = runBlocking {
        val t = FakeTransport(first = ProbeResult.Transient(IOException("connect timeout")))
        val cache = InMemoryCapabilityCache()
        // gemma 先验是 MARKER——回落值与 MARKER 同形，靠"缓存为空"区分回落与实测
        val verdict = prober(t, cache).resolve("ollama", "http://192.168.1.5:11434", "Gemma-3-12b")
        assertEquals(ToolCapability.MARKER, verdict)
        assertNull(cache.get("toolcap:ollama:http://192.168.1.5:11434:Gemma-3-12b"))
    }

    @Test
    fun `five hundred http error is transient falls back without writing cache`() = runBlocking {
        val t = FakeTransport(first = ProbeResult.HttpError(502, "bad gateway"))
        val cache = InMemoryCapabilityCache()
        val verdict = prober(t, cache).resolve("ollama", "http://192.168.1.5:11434", "qwen3:32b")
        // qwen3 先验 NATIVE：5xx 不是定论，回落先验
        assertEquals(ToolCapability.NATIVE, verdict)
        assertNull(cache.get("toolcap:ollama:http://192.168.1.5:11434:qwen3:32b"))
    }

    @Test
    fun `second step five xx is transient falls back without writing cache`() = runBlocking {
        val t = FakeTransport(
            first = ProbeResult.ToolCalled("c1"),
            second = ProbeResult.HttpError(500, "server exploded"),
        )
        val cache = InMemoryCapabilityCache()
        val verdict = prober(t, cache).resolve("ollama", "http://192.168.1.5:11434", "qwen3:32b")
        assertEquals(ToolCapability.NATIVE, verdict)
        assertNull(cache.get("toolcap:ollama:http://192.168.1.5:11434:qwen3:32b"))
    }

    @Test
    fun `transient on uncatalogued model yields unknown`() = runBlocking {
        val t = FakeTransport(first = ProbeResult.Transient(IOException("network unreachable")))
        val cache = InMemoryCapabilityCache()
        val verdict = prober(t, cache).resolve("custom", "https://my.relay.com", "llama-3.1-8b")
        assertEquals(ToolCapability.UNKNOWN, verdict)
        assertNull(cache.get("toolcap:custom:https://my.relay.com:llama-3.1-8b"))
    }

    @Test
    fun `probe once throws transient exception on transport transient`() = runBlocking {
        val t = FakeTransport(first = ProbeResult.Transient(IOException("read timeout")))
        try {
            prober(t).probeOnce("ollama", "http://192.168.1.5:11434", "qwen3:32b")
            fail("expected ProbeTransientException")
        } catch (e: ProbeTransientException) {
            assertEquals("read timeout", e.cause?.message)
        }
    }

    // ===== 第 4 层手动覆盖 > 第 2.5 层缓存 > 第 3 层实测（优先级） =====

    @Test
    fun `manual override wins over cache and probe`() = runBlocking {
        val cache = InMemoryCapabilityCache()
        val key = "toolcap:ollama:http://192.168.1.5:11434:qwen3:32b"
        cache.put(key, ToolCapability.NATIVE)
        // OFF 仅来自用户手动设置（spec 评审 A7），最高优先：不发请求、不看缓存
        val verdict = prober(NoCallTransport(), cache)
            .resolve("ollama", "http://192.168.1.5:11434", "qwen3:32b", override = ToolCapability.OFF)
        assertEquals(ToolCapability.OFF, verdict)
    }

    @Test
    fun `cache hit short circuits probe`() = runBlocking {
        val cache = InMemoryCapabilityCache()
        val key = "toolcap:ollama:http://192.168.1.5:11434:qwen3:32b"
        cache.put(key, ToolCapability.MARKER)
        val verdict = prober(NoCallTransport(), cache)
            .resolve("ollama", "http://192.168.1.5:11434", "qwen3:32b")
        assertEquals(ToolCapability.MARKER, verdict)
    }

    // ===== 第 1 层内置目录 =====

    @Test
    fun `model catalog fromJson parses and lookup matches case insensitively`() {
        // 与 app/src/main/assets/model_capabilities.json 内容物同形（P1 单测不读 assets）
        val json = """{"families":[
            {"match":"qwen3","capability":"NATIVE"},
            {"match":"qwen2.5","capability":"NATIVE"},
            {"match":"gemma","capability":"MARKER"},
            {"match":"glm","capability":"NATIVE"},
            {"match":"deepseek","capability":"NATIVE"}]}"""
        val catalog = ModelCatalog.fromJson(json)
        assertEquals(5, catalog.families.size)
        // 匹配 = 模型名小写 contains（大小写不敏感）
        assertEquals(ToolCapability.NATIVE, catalog.lookup("Qwen3-32B"))
        assertEquals(ToolCapability.NATIVE, catalog.lookup("qwen2.5-coder-instruct"))
        assertEquals(ToolCapability.MARKER, catalog.lookup("gemma-2-9b-it"))
        assertEquals(ToolCapability.NATIVE, catalog.lookup("GLM-4-Flash"))
        assertEquals(ToolCapability.NATIVE, catalog.lookup("deepseek-chat"))
        // 无命中 → null（resolve 侧折为 UNKNOWN）
        assertNull(catalog.lookup("llama-3"))
    }

    // ===== 缓存键（spec 评审 C5）：归一化后同址同键 =====

    @Test
    fun `cache key normalizes trailing v1 slash and missing scheme`() {
        val prober = prober(NoCallTransport())
        val a = prober.cacheKey("ollama", "http://192.168.1.5:8080/v1/", "qwen3:32b")
        val b = prober.cacheKey("ollama", "192.168.1.5:8080", "qwen3:32b")
        assertEquals("toolcap:ollama:http://192.168.1.5:8080:qwen3:32b", a)
        assertEquals(a, b)
        // providerId / model 原样入键（不同 provider 不同键）
        assertNotEquals(a, prober.cacheKey("lm_studio", "192.168.1.5:8080", "qwen3:32b"))
        assertNotEquals(a, prober.cacheKey("ollama", "192.168.1.5:8080", "qwen2.5:32b"))
    }

    // ===== 纯读侧（P5 T2，评审 P1-4）：CapabilityReader 只读缓存/目录，无传输可持 =====

    /** 缓存快照假件（生产 = DataStoreCapabilityCache 的内存镜像 peek） */
    private fun snapshotOf(vararg entries: Pair<String, ToolCapability>) =
        CapabilitySnapshot { _, _, m -> entries.firstOrNull { it.first == m }?.second }

    @Test
    fun `reader folds cache miss through catalog prior to unknown and marker mode`() {
        // 构造签名即无网络路径：reader 只持目录+快照，没有 transport 可发起探测
        val reader = CapabilityReader(catalog, snapshotOf())
        assertEquals(ToolCapability.UNKNOWN, reader.capability("custom", "http://x", "llama-3"))
        assertEquals(ToolMode.MARKER, reader.mode("custom", "http://x", "llama-3"))
        // 目录先验命中：qwen3→NATIVE、gemma→MARKER
        assertEquals(ToolCapability.NATIVE, reader.capability("ollama", "http://x", "Qwen3-32B"))
        assertEquals(ToolMode.NATIVE, reader.mode("ollama", "http://x", "Qwen3-32B"))
        assertEquals(ToolCapability.MARKER, reader.capability("ollama", "http://x", "gemma-2-9b"))
        assertEquals(ToolMode.MARKER, reader.mode("ollama", "http://x", "gemma-2-9b"))
    }

    @Test
    fun `reader snapshot cache wins over catalog prior and folds unknown to marker`() {
        // 缓存比目录先验高一层（spec §4.4 层序）：gemma 目录 MARKER、缓存 NATIVE → NATIVE
        val reader = CapabilityReader(
            catalog,
            snapshotOf("gemma-2-9b" to ToolCapability.NATIVE, "mystery" to ToolCapability.MARKER),
        )
        assertEquals(ToolCapability.NATIVE, reader.capability("ollama", "http://x", "gemma-2-9b"))
        assertEquals(ToolMode.NATIVE, reader.mode("ollama", "http://x", "gemma-2-9b"))
        // 缓存 MARKER → MARKER
        assertEquals(ToolMode.MARKER, reader.mode("ollama", "http://x", "mystery"))
    }

    @Test
    fun `reader manual override wins and off never maps to native`() {
        val reader = CapabilityReader(catalog, snapshotOf("qwen3" to ToolCapability.NATIVE))
        // 第 4 层手动覆盖最高优先：OFF 只来自手动设置（评审 A7）
        assertEquals(ToolCapability.OFF, reader.capability("ollama", "http://x", "qwen3", override = ToolCapability.OFF))
        // OFF 不是 NATIVE：模式一律折叠 MARKER 协议（ToolMode 无 OFF 形，折叠语义）
        assertEquals(ToolMode.MARKER, reader.mode("ollama", "http://x", "qwen3", override = ToolCapability.OFF))
        assertEquals(ToolMode.MARKER, reader.mode("ollama", "http://x", "qwen3", override = ToolCapability.MARKER))
    }

    @Test
    fun `probe cache key is single sourced between prober and snapshot side`() {
        val prober = prober(NoCallTransport())
        assertEquals(prober.cacheKey("ollama", "http://192.168.1.5:8080/v1/", "qwen3:32b"),
            probeCacheKey("ollama", "192.168.1.5:8080", "qwen3:32b"))
    }
}
