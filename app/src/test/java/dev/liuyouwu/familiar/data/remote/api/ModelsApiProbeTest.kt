package dev.liuyouwu.familiar.data.remote.api

import dev.liuyouwu.familiar.util.PlaintextKeyRefusal
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * probePort（二段扫描单端口快探）单测：200+非空模型列表 = 命中；
 * 非 2xx / 非 JSON（路由器网页）/ 空模型列表 = 未命中（null，不区分原因）。
 */
class ModelsApiProbeTest {

    private lateinit var server: MockWebServer
    private lateinit var api: ModelsApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = ModelsApi()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `200 且非空模型列表为命中`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"data":[{"id":"qwen3.8-27b"},{"id":"bge-m3"}]}""")
        )
        val models = api.probePort(server.url("/").toString())
        assertEquals(listOf("qwen3.8-27b", "bge-m3"), models)
        assertEquals("/v1/models", server.takeRequest().path)
    }

    @Test
    fun `非 2xx 为未命中`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(api.probePort(server.url("/").toString()))
    }

    @Test
    fun `非 JSON 响应（路由器网页）为未命中`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("<html><body>router admin</body></html>")
        )
        assertNull(api.probePort(server.url("/").toString()))
    }

    @Test
    fun `空模型列表为未命中`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))
        assertNull(api.probePort(server.url("/").toString()))
    }

    // ---------- 明文 http 拒发闸（审计 S1+S7，裁定 1 · C 出缝） ----------

    /** 阳性：http 公网 + 非空 key → Result.failure(PlaintextKeyRefusal)，请求不出网 */
    @Test
    fun `明文公网http带key拒发不触网`() = runBlocking {
        val result = api.fetchModels("http://8.8.8.8:8080", "sk-x")
        assertTrue("必须以 Result.failure 拒发", result.isFailure)
        val e = result.exceptionOrNull()
        assertTrue("失败因必须是 PlaintextKeyRefusal，实际 $e", e is PlaintextKeyRefusal)
        assertEquals("http 且非局域网目标，已拒绝携带 API Key 发起请求", e?.message)
    }

    /** 阴性：回环（MockWebServer 绑 localhost）+ key → 放行且 Authorization 送达服务器 */
    @Test
    fun `回环带key放行且Authorization送达`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"m1"}]}"""))
        val result = api.fetchModels(server.url("/").toString(), "sk-x")
        assertTrue("回环 http 带 key 不得拒发", result.isSuccess)
        assertEquals("Bearer sk-x", server.takeRequest().getHeader("Authorization"))
    }
}
