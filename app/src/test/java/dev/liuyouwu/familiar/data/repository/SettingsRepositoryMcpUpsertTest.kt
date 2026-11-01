package dev.liuyouwu.familiar.data.repository

import dev.liuyouwu.familiar.tools.mcp.McpServerConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SettingsRepository.upsertMcpServer] 纯 JVM 单测（终审 F-1）。
 * 钉死的语义：同名 upsert 原位替换不挪队尾——行内启停 Switch / 编辑弹窗保存不再把
 * 行挪到列表末尾（UI 行序稳定；存储顺序是跨服务器撞名工具"后配置者胜"的定序判据，
 * 不因一次保存静默翻转）；新名 append 在尾。
 *
 * repository 本体（DataStore/Context/CryptoStore Keystore）非纯 JVM 可构造，故把
 * saveMcpServer 的合并步抽为伴生纯函数后在此覆盖（Migration15_16LogicTest 先例同款）。
 */
class SettingsRepositoryMcpUpsertTest {

    private val a = McpServerConfig(name = "alpha", url = "https://a.example.com", enabled = true)
    private val b = McpServerConfig(name = "beta", url = "https://b.example.com")

    @Test
    fun `same name upsert replaces in place not appended to tail`() {
        val updated = b.copy(enabled = true, tokenCipher = "cipher")
        val result = SettingsRepository.upsertMcpServer(listOf(a, b), updated)
        // 原位：beta 仍在 alpha 之后，行序不因保存翻转；tokenCipher/enabled 照常更新
        assertEquals(listOf("alpha", "beta"), result.map { it.name })
        assertEquals(listOf(a, updated), result)
    }

    @Test
    fun `same name upsert keeps head position`() {
        val updated = a.copy(enabled = false)
        assertEquals(listOf(updated, b), SettingsRepository.upsertMcpServer(listOf(a, b), updated))
    }

    @Test
    fun `new name appends to tail`() {
        val c = McpServerConfig(name = "gamma", url = "https://c.example.com")
        assertEquals(listOf(a, b, c), SettingsRepository.upsertMcpServer(listOf(a, b), c))
    }

    @Test
    fun `empty list upsert yields single element`() {
        assertEquals(listOf(a), SettingsRepository.upsertMcpServer(emptyList(), a))
    }

    @Test
    fun `input list is not mutated`() {
        val source = mutableListOf(a, b)
        SettingsRepository.upsertMcpServer(source, b.copy(enabled = true))
        // 传入列表不被改写（toMutableList 拷贝后再 set）
        assertEquals(listOf(a, b), source)
    }
}

/**
 * 坏条目防御（2026-11-01 小米真机启动 NPE 实证）：Gson Unsafe 绕过 Kotlin 构造器，
 * 存量 JSON 缺字段时非空类型字段运行时为 null——sanitize 层宁弃不崩。
 */
class SettingsRepositoryMcpSanitizeTest {

    @Test
    fun `缺 name 或 url 的条目整条丢弃`() {
        val parsed = listOf(
            McpServerConfig(name = "good", url = "https://g.example.com"),
        )
        // Gson Unsafe 形态：非空类型字段运行时为 null——用反射置 null 模拟
        val brokenName = McpServerConfig(name = "x", url = "https://n.example.com")
        McpServerConfig::class.java.getDeclaredField("name").apply { isAccessible = true }.set(brokenName, null)
        val brokenUrl = McpServerConfig(name = "y", url = "https://u.example.com")
        McpServerConfig::class.java.getDeclaredField("url").apply { isAccessible = true }.set(brokenUrl, null)

        val clean = SettingsRepository.sanitizeMcpServers(listOf(parsed[0], brokenName, brokenUrl))
        assertEquals(listOf("good"), clean.map { it.name })
    }

    @Test
    fun `全部合法原样通过`() {
        val parsed = listOf(
            McpServerConfig(name = "a", url = "https://a.example.com"),
            McpServerConfig(name = "b", url = "https://b.example.com"),
        )
        assertEquals(parsed, SettingsRepository.sanitizeMcpServers(parsed))
    }
}
