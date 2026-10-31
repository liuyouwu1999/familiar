package dev.liuyouwu.familiar.tools

import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * 文件三件套（spec §3 / Task 1）：read_file / write_file / list_files。
 * 沙箱根 = 构造注入的 [agentFilesRoot]（生产由 SkillHost 注入 filesDir/agent_files，
 * JVM 测试 @TempDir 直注）。path 一律相对沙箱根，经 [resolveInSandbox] canonical
 * 校验后操作；异常即失败（ToolRegistry 回填 isError），工具自身不 catch。
 * IO 全部 withContext(Dispatchers.IO)（工具链 scope 是 Dispatchers.Default，工具自管 IO）。
 */

/**
 * 沙箱校验单点（文件工具共用，后续工具面沿用）：canonical 解析后必须以
 * `root.canonicalPath + File.separator` 为前缀（separator 防兄弟目录同名前缀
 * `agent_files_evil` 骗过裸 startsWith——spec §3 D3）；root 自身放行
 * （canonical 等值——list_files 的 "."/"" = root 依赖此语义）。
 * @throws IllegalArgumentException 路径越出沙箱
 */
internal fun File.resolveInSandbox(root: File): File {
    val canonical = this.canonicalFile
    val rootCanon = root.canonicalFile
    if (canonical.canonicalPath == rootCanon.canonicalPath) return rootCanon
    val allowed = rootCanon.canonicalPath + File.separator
    if (!canonical.canonicalPath.startsWith(allowed)) {
        throw IllegalArgumentException("路径必须为 agent_files 相对路径：越出沙箱")
    }
    return canonical
}

// ---------- 沙箱入口：字符串层绝对形态先拒，再 join + canonical 校验 ----------

/**
 * path 字符串 → 沙箱内 File 的唯一入口。绝对形态（跨平台：File.isAbsolute 抓盘符形如
 * "C:\x"，前导分隔符抓 "/x"——Windows 上 File(parent, "/x") 会把 child 粘进 parent
 * 变成合法沙箱子路径，canonical 前缀查不出来，必须字符串层先拒）→ IAE；
 * 其余 join 后交 [resolveInSandbox] canonical 越界校验。
 */
private fun sandboxJoin(root: File, path: String): File {
    if (File(path).isAbsolute || path.startsWith('/') || path.startsWith('\\')) {
        throw IllegalArgumentException("路径必须为 agent_files 相对路径：禁止绝对路径 $path")
    }
    return File(root, path).resolveInSandbox(root)
}

// ---------- 参数取值（口径同 WebSearchTool：isJsonPrimitive 守卫，缺参即 IAE） ----------

private fun JsonObject.requiredString(key: String): String =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString
        ?: throw IllegalArgumentException("缺少必填参数 $key")

private fun JsonObject.optionalInt(key: String): Int? =
    get(key)?.takeIf { it.isJsonPrimitive }?.asInt

private fun JsonObject.optionalBoolean(key: String): Boolean? =
    get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean

/** read/write 层的 path：缺参 IAE 之后，空白串先 require（list 的 ""=root 是另一语义，不走这里） */
private fun JsonObject.requiredPath(): String =
    requiredString("path").also { require(it.isNotBlank()) { "path 不能为空白" } }

/**
 * 读文本文件：默认前 8KB（max_bytes 可调 ≤32768），按字节读后 UTF-8 容错解码
 * （MalformedInput→替换字符）；读取范围含 NUL → 异常（防二进制以替换字符直进上下文）；
 * 截断时尾部追加标注。默认 8KB 是结果体量闸（spec D5：4096 token 窗口装不下大文件）。
 */
class ReadFileTool(private val agentFilesRoot: File) : AgentTool {

    override val name = "read_file"
    override val description = "读取沙箱内的文本文件，返回内容（默认前 8KB，超出截断标注）"
    override val parametersJson =
        """{"type":"object","properties":{"path":{"type":"string","description":"相对路径，如 notes/todo.txt"},"max_bytes":{"type":"integer","description":"默认 8192，上限 32768"}},"required":["path"]}"""

    override suspend fun execute(args: JsonObject, onProgress: (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            val path = args.requiredPath()
            val maxBytes = (args.optionalInt("max_bytes") ?: DEFAULT_MAX_BYTES)
                .also { require(it > 0) { "max_bytes 必须为正整数：$it" } }
                .coerceAtMost(HARD_MAX_BYTES)
            val file = sandboxJoin(agentFilesRoot, path)
            onProgress("正在读取 $path")

            FileInputStream(file).use { input ->
                val buf = ByteArray(maxBytes + 1)   // 多读 1 字节判截断
                var off = 0
                while (off < buf.size) {
                    val n = input.read(buf, off, buf.size - off)
                    if (n < 0) break
                    off += n
                }
                if (buf.copyOf(off).contains(0.toByte())) {
                    throw IllegalArgumentException("非文本文件：读取范围含 NUL 字节")
                }
                val truncated = off > maxBytes
                val text = String(buf, 0, if (truncated) maxBytes else off, Charsets.UTF_8)
                if (truncated) text + TRUNCATION_MARKER else text
            }
        }

    private companion object {
        const val DEFAULT_MAX_BYTES = 8192
        const val HARD_MAX_BYTES = 32768
        const val TRUNCATION_MARKER = "\n[截断，原文更大]"
    }
}

/**
 * 写/追加文本：append 可选默认 false；自动建父目录；返回 "已写入 N 字符 → path"。
 */
class WriteFileTool(private val agentFilesRoot: File) : AgentTool {

    override val name = "write_file"
    override val description = "写入或追加文本到沙箱内文件（自动创建父目录）"
    override val parametersJson =
        """{"type":"object","properties":{"path":{"type":"string","description":"相对路径"},"content":{"type":"string"},"append":{"type":"boolean","description":"true=追加"}},"required":["path","content"]}"""

    override suspend fun execute(args: JsonObject, onProgress: (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            val path = args.requiredPath()
            val content = args.requiredString("content")
            val append = args.optionalBoolean("append") ?: false
            val file = sandboxJoin(agentFilesRoot, path)
            onProgress("正在写入 $path")

            file.parentFile?.let { it.mkdirs() }
            FileOutputStream(file, append).use { it.write(content.toByteArray(Charsets.UTF_8)) }
            "已写入 ${content.length} 字符 → $path"
        }
}

/**
 * 单层列举沙箱目录（不递归）：目录行 `名字\t(目录)`、文件行 `名字\tN 字节`，
 * 按名字排序保证输出稳定；path 缺省/""/"." = 沙箱根自身（resolveInSandbox root 放行）；
 * 非目录或空目录 → "(空)"。
 */
class ListFilesTool(private val agentFilesRoot: File) : AgentTool {

    override val name = "list_files"
    override val description = "单层列举沙箱内目录的内容（不递归）"
    override val parametersJson =
        """{"type":"object","properties":{"path":{"type":"string","description":"相对目录，默认 ."}},"required":[]}"""

    override suspend fun execute(args: JsonObject, onProgress: (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            val path = args.get("path")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
            // ""/"."= 根自身（resolveInSandbox canonical 等值放行）；"" 不经 File(parent, child)，空 child 跨平台行为不赌
            val dir = if (path.isEmpty()) agentFilesRoot.resolveInSandbox(agentFilesRoot)
            else sandboxJoin(agentFilesRoot, path)
            onProgress("正在列目录 $path")

            val lines = dir.listFiles()
                ?.sortedBy { it.name }
                ?.map { f -> if (f.isDirectory) "${f.name}\t(目录)" else "${f.name}\t${f.length()} 字节" }
            if (lines.isNullOrEmpty()) "(空)" else lines.joinToString("\n")
        }
}
