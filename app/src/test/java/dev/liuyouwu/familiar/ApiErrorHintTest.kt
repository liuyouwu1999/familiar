package dev.liuyouwu.familiar

import dev.liuyouwu.familiar.util.ApiErrorHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiErrorHintTest {

    @Test
    fun `error null field is not an error`() {
        // 部分网关每个 chunk 都带 "error": null，合法响应
        assertNull(ApiErrorHint.extractApiError("""{"error": null, "choices": []}"""))
    }

    @Test
    fun `no error field returns null`() {
        assertNull(ApiErrorHint.extractApiError("""{"choices":[{"delta":{"content":"hi"}}]}"""))
        assertNull(ApiErrorHint.extractApiError("not json at all"))
    }

    @Test
    fun `error object message extracted`() {
        val msg = ApiErrorHint.extractApiError(
            """{"error":{"message":"model not found","type":"invalid_request_error"}}"""
        )
        assertEquals("model not found", msg)
    }

    @Test
    fun `error as plain string extracted`() {
        val msg = ApiErrorHint.extractApiError("""{"error":"context overflow"}""")
        assertEquals("context overflow", msg)
    }

    @Test
    fun `http 400 context overflow gets friendly hint`() {
        val hint = ApiErrorHint.fromHttpStatus(400, """{"error":"the request exceeds available context size"}""")
        assertTrue(hint.contains("上下文超限"))
    }

    @Test
    fun `http 404 model not found hint`() {
        val hint = ApiErrorHint.fromHttpStatus(404, """{"error":{"message":"model 'xxx' not found"}}""")
        assertTrue(hint.contains("模型不存在"))
    }

    @Test
    fun `400 with unrelated body does not claim context overflow`() {
        val hint = ApiErrorHint.fromHttpStatus(400, """{"error":"invalid max_tokens value"}""")
        assertTrue(!hint.contains("上下文超限"))
    }

    @Test
    fun `connect exception hint mentions local ports`() {
        val e = java.net.ConnectException("Connection refused")
        val hint = ApiErrorHint.fromThrowable(e)
        assertTrue(hint.contains("8080"))
        assertTrue(hint.contains("11434"))
        assertTrue(hint.contains("1234"))
    }

    @Test
    fun `connect exception hint carries actual address`() {
        // 填错服务器（如把手机自己的组网 IP 当服务器）时，文案必须带实际尝试的地址
        val e = java.net.ConnectException("Failed to connect to /192.168.1.100:1234")
        val hint = ApiErrorHint.fromThrowable(e)
        assertTrue(hint.contains("192.168.1.100:1234"))
    }

    @Test
    fun `timeout hint carries actual address`() {
        val e = java.net.SocketTimeoutException("timeout: failed to connect to /100.64.200.9 after 5000ms")
        val hint = ApiErrorHint.fromThrowable(e)
        assertTrue(hint.contains("100.64.200.9"))
    }
}
