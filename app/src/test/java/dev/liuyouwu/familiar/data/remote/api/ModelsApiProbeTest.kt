package dev.liuyouwu.familiar.data.remote.api

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
