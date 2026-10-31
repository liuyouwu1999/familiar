package dev.liuyouwu.familiar.tools

import android.app.Application
import okhttp3.OkHttpClient
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * ToolFactory JVM 单测（Task 5）：root=@TempDir 直注沙箱、context=Application()
 * 空构造（P1-7 夹具假设：装配期零框架调用，首用例单点验证）、client=OkHttpClient()。
 * 钉死的语义：BASIC 四件 / EXTENDED 八件的注册边界、web_search 不归工厂
 * （SkillHost 专管预算门，已知名静默让渡）、未知名 warn 跳过不抛（android.util.Log
 * 在 JVM 未 mock 会崩——LlmClient.kt:108 先例，warn 走注入缝记录断言）、空表零注册。
 */
class ToolFactoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** warn 注入记录器：JUnit4 每用例新实例，字段天然按用例隔离 */
    private val warns = mutableListOf<String>()

    private fun install(toolNames: List<String>): ToolRegistry {
        val registry = ToolRegistry()
        ToolFactory.install(
            registry, toolNames,
            agentFilesRoot = temp.root,
            appContext = Application(),
            agentOkHttp = OkHttpClient(),
            warn = { warns += it },
        )
        return registry
    }

    // ---------- P1-7 夹具假设单点（Step 2.5，保持文件首位） ----------

    @Test
    fun `application empty constructor works on jvm`() {
        // 只验证空构造（八件装配期对 context 的全部依赖 = 存引用，零方法调用）；
        // 任何成员调用（如 resources）本身必抛 Stub!，不得出现在此用例
        Application()
    }

    // ---------- 注册边界 ----------

    @Test
    fun `basic set registers four`() {
        val registry = install(ToolFactory.BASIC)
        ToolFactory.BASIC.forEach { assertNotNull("应注册 $it", registry.find(it)) }
        // BASIC 不触 EXTENDED 后四件：以 http_get 为代表钉住边界
        assertNull(registry.find("http_get"))
    }

    @Test
    fun `extended set registers eight`() {
        val registry = install(ToolFactory.EXTENDED)
        ToolFactory.EXTENDED.forEach { assertNotNull("应注册 $it", registry.find(it)) }
    }

    @Test
    fun `web search not handled by factory`() {
        // web_search 归 SkillHost（注册与预算门必须同装，工厂不得单独造）：
        // 已知名让渡 = 设计内行为，静默跳过（warn 留给真未知名）
        val registry = install(listOf("web_search"))
        assertNull(registry.find("web_search"))
        assertTrue("web_search 让渡不应产生告警", warns.isEmpty())
    }

    @Test
    fun `unknown names skipped with warning`() {
        val registry = install(listOf("read_file", "mystery_tool"))
        assertNotNull(registry.find("read_file"))
        assertNull("未知名不注册", registry.find("mystery_tool"))
        assertTrue("warn 记录应含未知名", warns.any { it.contains("mystery_tool") })
    }

    @Test
    fun `empty toolNames registers nothing`() {
        val registry = install(emptyList())
        (ToolFactory.EXTENDED + "web_search").forEach { assertNull(registry.find(it)) }
    }
}
