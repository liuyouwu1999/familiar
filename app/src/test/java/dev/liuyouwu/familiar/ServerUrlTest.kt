package dev.liuyouwu.familiar

import dev.liuyouwu.familiar.util.BackendProvider
import dev.liuyouwu.familiar.util.ServerUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlTest {

    @Test
    fun `plain host with port gets scheme`() {
        assertEquals("http://192.168.1.5:8080", ServerUrl.normalize("192.168.1.5:8080"))
    }

    @Test
    fun `trailing slash stripped`() {
        assertEquals("http://192.168.1.5:8080", ServerUrl.normalize("http://192.168.1.5:8080/"))
    }

    @Test
    fun `trailing v1 stripped`() {
        assertEquals("http://192.168.1.5:8080", ServerUrl.normalize("http://192.168.1.5:8080/v1"))
        assertEquals("http://192.168.1.5:8080", ServerUrl.normalize("http://192.168.1.5:8080/v1/"))
    }

    @Test
    fun `path prefix preserved`() {
        assertEquals("https://my.host/llm", ServerUrl.normalize("https://my.host/llm"))
    }

    @Test
    fun `https preserved`() {
        assertEquals("https://api.openai.com", ServerUrl.normalize("https://api.openai.com"))
    }

    @Test
    fun `whitespace trimmed`() {
        assertEquals("http://10.0.0.2:11434", ServerUrl.normalize("  10.0.0.2:11434  "))
    }

    @Test
    fun `localhost works`() {
        assertEquals("http://localhost:11434", ServerUrl.normalize("localhost:11434"))
    }

    @Test
    fun `validate rejects pure numeric host`() {
        // 用户只填了端口号
        val err = ServerUrl.validate("8080")
        assertTrue(err != null)
    }

    @Test
    fun `invalid input returns null`() {
        assertNull(ServerUrl.normalize(""))
        assertNull(ServerUrl.normalize("   "))
        assertNull(ServerUrl.normalize("http://"))
        assertNull(ServerUrl.normalize("ftp://x"))
        assertNull(ServerUrl.normalize("http://ho st:8080"))
    }

    @Test
    fun `validate gives message for blank`() {
        assertTrue(ServerUrl.validate("") != null)
        assertNull(ServerUrl.validate("http://192.168.1.5:8080"))
    }

    @Test
    fun `legacy ip port migration`() {
        assertEquals("http://192.168.1.5:8080", ServerUrl.fromLegacyIpPort("192.168.1.5", "8080"))
        // 用户以前把完整 URL 填进 ip 字段
        assertEquals("http://192.168.1.5:1234", ServerUrl.fromLegacyIpPort("http://192.168.1.5:1234", "1234"))
        assertNull(ServerUrl.fromLegacyIpPort("", "1234"))
    }

    @Test
    fun `provider guessed from port`() {
        assertEquals(BackendProvider.LLAMA_CPP, BackendProvider.fromPort("8080"))
        assertEquals(BackendProvider.OLLAMA, BackendProvider.fromPort("11434"))
        assertEquals(BackendProvider.LM_STUDIO, BackendProvider.fromPort("1234"))
        assertEquals(BackendProvider.CUSTOM, BackendProvider.fromPort("3000"))
        assertEquals(BackendProvider.LM_STUDIO, BackendProvider.fromId(null))
    }

    // ===== stripHttpScheme：输入框显示形态（界面上不出现 http://） =====

    @Test
    fun `strip http scheme removes prefix`() {
        assertEquals("192.168.1.5:8080", ServerUrl.stripHttpScheme("http://192.168.1.5:8080"))
        assertEquals("192.168.1.5:8080", ServerUrl.stripHttpScheme("HTTP://192.168.1.5:8080"))
    }

    @Test
    fun `strip keeps https prefix visible`() {
        // 加密连接必须显式可见，剥掉用户就不知道云端走的是 https
        assertEquals("https://api.openai.com", ServerUrl.stripHttpScheme("https://api.openai.com"))
    }

    @Test
    fun `strip leaves schemeless input unchanged`() {
        assertEquals("192.168.1.5:8080", ServerUrl.stripHttpScheme("192.168.1.5:8080"))
        assertEquals("", ServerUrl.stripHttpScheme(""))
    }

    @Test
    fun `stripped display form renormalizes losslessly`() {
        // 回显形态再进 normalize 必须还原同一地址（显示与存储可无损往返）
        val full = "http://192.168.1.5:8080"
        assertEquals(full, ServerUrl.normalize(ServerUrl.stripHttpScheme(full)))
        val https = "https://my.host/llm"
        assertEquals(https, ServerUrl.normalize(ServerUrl.stripHttpScheme(https)))
    }

    @Test
    fun `validate error examples do not mention http prefix`() {
        val err = ServerUrl.validate("ftp://x")!!
        assertTrue(err.contains("192.168.1.100:8080"))
        assertFalse(err.contains("http://"))
    }
}
