package dev.liuyouwu.familiar.tools

import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 文件三件套 JVM 单测（Task 1 / spec §3）：@Rule TempDir 作沙箱根直注构造。
 * 钉死的语义：canonical 沙箱校验（root 自身放行、兄弟目录同名前缀骗不过）、
 * read 默认 8KB 截断标注 + max_bytes 硬上限 32768、NUL 拒读、
 * read/write 与 list 的空 path 语义区分（空白串拒 vs ""=root 放行）。
 */
class FileToolsTest {

    @get:Rule
    val temp = TemporaryFolder()

    // ---------- fixtures ----------

    private fun readTool() = ReadFileTool(temp.root)
    private fun writeTool() = WriteFileTool(temp.root)
    private fun listTool() = ListFilesTool(temp.root)

    private fun args(vararg pairs: Pair<String, Any?>): JsonObject {
        val o = JsonObject()
        for ((k, v) in pairs) when (v) {
            null -> o.addProperty(k, null as String?)
            is String -> o.addProperty(k, v)
            is Number -> o.addProperty(k, v)
            is Boolean -> o.addProperty(k, v)
            else -> throw IllegalArgumentException("测试参数类型不支持：$v")
        }
        return o
    }

    private fun write(path: String, content: String, append: Boolean = false): String = runBlocking {
        writeTool().execute(args("path" to path, "content" to content, "append" to append), {})
    }

    private fun read(path: String, maxBytes: Int? = null): String = runBlocking {
        val a = if (maxBytes == null) args("path" to path) else args("path" to path, "max_bytes" to maxBytes)
        readTool().execute(a, {})
    }

    /** path 传 null = 参数缺席（list 的默认 root 语义） */
    private fun list(path: String?): String = runBlocking {
        val a = if (path == null) JsonObject() else args("path" to path)
        listTool().execute(a, {})
    }

    private fun assertIae(block: () -> Unit) {
        try {
            block()
            fail("应抛 IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    // ---------- write / read 基本回路 ----------

    @Test
    fun `write then read roundtrip`() {
        val out = write("notes/a.txt", "内容")
        assertTrue(out.contains("notes/a.txt"))
        // 自动建父目录
        assertEquals("内容", File(temp.root, "notes/a.txt").readText())
        assertEquals("内容", read("notes/a.txt"))
    }

    @Test
    fun `write append`() {
        write("log.txt", "ab")
        write("log.txt", "cd", append = true)
        assertEquals("abcd", read("log.txt"))
    }

    @Test
    fun `read truncates at 8k with marker`() {
        write("big.txt", "x".repeat(9000))
        assertEquals("x".repeat(8192) + "\n[截断，原文更大]", read("big.txt"))
    }

    @Test
    fun `read max_bytes capped at 32768`() {
        write("huge.txt", "y".repeat(40000))
        assertEquals("y".repeat(32768) + "\n[截断，原文更大]", read("huge.txt", 999999))
        assertIae { read("huge.txt", 0) }
        assertIae { read("huge.txt", -5) }
    }

    @Test
    fun `read rejects NUL bytes`() {
        write("bin.txt", "a" + NUL + "b")
        try {
            read("bin.txt")
            fail("NUL 字节应拒读")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("非文本文件"))
        }
    }

    // ---------- 沙箱校验 ----------

    @Test
    fun `path traversal three forms`() {
        for (p in listOf("/etc/x", "../x", "a/../../x")) {
            assertIae { read(p) }
            assertIae { write(p, "x") }
        }
    }

    @Test
    fun `read blank path rejected`() {
        // read/write 的空白串 path 直接拒（先于沙箱解析）；与 list 的 ""=root 语义不同
        assertIae { read(" ") }
        assertIae { write("", "x") }
        assertIae { runBlocking { readTool().execute(JsonObject(), {}) } }   // path 缺席
    }

    @Test
    fun `sibling dir prefix not fooled`() {
        // 沙箱根同级建同名前缀兄弟目录（生产对应 agent_files_evil——
        // 只有同名前缀才骗得过裸 startsWith(rootPath)，恰好钉死 separator 前缀语义）
        val evil = File(temp.root.parentFile, temp.root.name + "_evil").apply { mkdirs() }
        try {
            File(evil, "secret.txt").writeText("敏感")
            assertIae { read("../${evil.name}/secret.txt") }
        } finally {
            evil.deleteRecursively()
        }
    }

    // ---------- list ----------

    @Test
    fun `list root itself allowed`() {
        write("a.txt", "hello")
        assertEquals(list("."), list(""))
        assertEquals(list(""), list(null))
    }

    @Test
    fun `list files single level`() {
        write("a.txt", "hello")          // 5 字节
        write("sub/b.txt", "12345")      // sub/ 自动生成，单层不递归
        assertEquals("a.txt\t5 字节\nsub\t(目录)", list(""))
        File(temp.root, "empty").mkdirs()
        assertEquals("(空)", list("empty"))
    }

    private companion object {
        /** U+0000：构造而得，避免源文件混入字面控制符 */
        val NUL: String = String(byteArrayOf(0), Charsets.UTF_8)
    }
}
