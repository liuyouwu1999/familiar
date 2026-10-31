package dev.liuyouwu.familiar.tools.mcp

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * McpServerConfig 纯 JVM 单测（Task 1 / spec §slug）：slug 规范化是 MCP 工具命名的
 * 身份来源，requireLegalName 是拒存判据的唯一权威（saveMcpServer 与 UI 双调）。
 * 钉死的语义：
 * - slugOf 恒返回折叠串：非 [a-zA-Z0-9_-] → "_"，连续 "_" 折叠为单个 "_"；
 *   slugOf("文件")=="_"（折叠串不返回空）；
 * - requireLegalName：slugOf 结果 isBlank 或全 "_" → IllegalArgumentException；
 * - Gson JSON 数组序列化↔反序列化字段齐全（KEY_MCP_SERVERS 存储格式）。
 */
class McpServerConfigTest {

    @Test
    fun `slug ascii passthrough`() {
        assertEquals("files", McpServerConfig.slugOf("files"))
        assertEquals("my-tools_1", McpServerConfig.slugOf("my-tools_1"))
        // 计算属性与 companion 同源
        assertEquals(
            "files",
            McpServerConfig(name = "files", url = "http://localhost:8000/mcp", tokenCipher = null, enabled = false).slug
        )
    }

    @Test
    fun `slug folds chinese to underscores`() {
        assertEquals("_", McpServerConfig.slugOf("文件"))
        // 折叠串恒不返回空：全非法字符名折叠后是 "_" 而非 ""
        assertEquals("_", McpServerConfig.slugOf("!!!"))
    }

    @Test
    fun `slug collapses consecutive underscores`() {
        assertEquals("a_b", McpServerConfig.slugOf("a__b"))
        assertEquals("My_", McpServerConfig.slugOf("My_服务器"))
        assertEquals("_a", McpServerConfig.slugOf("文件_a"))
    }

    @Test
    fun `requireLegalName rejects degenerate`() {
        // 折叠为 "_"（全下划线）→ 拒
        assertThrows(IllegalArgumentException::class.java) { McpServerConfig.requireLegalName("文件") }
        // 空/空白名（折叠空串）→ 拒
        assertThrows(IllegalArgumentException::class.java) { McpServerConfig.requireLegalName("") }
        assertThrows(IllegalArgumentException::class.java) { McpServerConfig.requireLegalName("___") }
    }

    @Test
    fun `requireLegalName accepts normal`() {
        McpServerConfig.requireLegalName("files")
        // 折叠 "My_" 含字母 → 不抛
        McpServerConfig.requireLegalName("My_服务器")
    }

    @Test
    fun `serialize roundtrip`() {
        val gson = Gson()
        val servers = listOf(
            McpServerConfig(name = "files", url = "http://localhost:8000/mcp", tokenCipher = "cipher-A", enabled = true),
            McpServerConfig(name = "My_服务器", url = "https://example.com/mcp", tokenCipher = null, enabled = false)
        )
        val json = gson.toJson(servers)
        // 密文字段确实进了 JSON（KEY_MCP_SERVERS 数组存 tokenCipher）
        org.junit.Assert.assertTrue(json.contains("tokenCipher"))
        org.junit.Assert.assertTrue(json.contains("cipher-A"))
        val type = object : TypeToken<List<McpServerConfig>>() {}.type
        val back: List<McpServerConfig> = gson.fromJson(json, type)
        assertEquals(servers, back)
        // 字段齐：逐字段核对（data class equals 已覆盖，这里显式钉死关键字段）
        assertEquals("files", back[0].name)
        assertEquals("cipher-A", back[0].tokenCipher)
        assertEquals(true, back[0].enabled)
        assertEquals(null, back[1].tokenCipher)
        assertEquals("My_", back[1].slug)
    }
}
