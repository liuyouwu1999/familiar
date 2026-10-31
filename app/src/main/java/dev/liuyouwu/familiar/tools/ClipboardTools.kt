package dev.liuyouwu.familiar.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 剪贴板读/写（spec §3 / Task 3）：ClipboardManager 限主线程——链 scope 是
 * Dispatchers.Default（SupervisorJob，openChain 装配），工具内
 * withContext(Dispatchers.Main) 切换（链 scope 非 Main，常规派发无死锁）。
 * 异常即失败（ToolRegistry 回填 isError），工具自身不 catch
 * （CancellationException 契约见 AgentTool）。Android 框架件无 JVM 测试，
 * 仅模拟器验收（spec §6 显式声明）。
 */

/**
 * 读剪贴板：空/不可读返回 "(剪贴板为空或不可读)" 非异常——空剪贴板是常态输入
 * 而非故障（API 33+ 后台无焦点读也落此兜底），模型按内容自行应答。
 */
class ClipboardReadTool(private val appContext: Context) : AgentTool {

    override val name = "clipboard_read"
    override val description = "读取系统剪贴板的文本内容"
    override val parametersJson = """{"type":"object","properties":{},"required":[]}"""

    override suspend fun execute(args: JsonObject, onProgress: (String) -> Unit): String =
        withContext(Dispatchers.Main) {
            onProgress("正在读取剪贴板")
            val clip = clipboard().primaryClip
            // coerceToText：Intent/Uri 等非纯文本剪贴项降级为文本表示；空串视同空剪贴板
            val text = clip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(appContext)?.toString()
            if (text.isNullOrEmpty()) EMPTY_MESSAGE else text
        }

    private fun clipboard(): ClipboardManager =
        appContext.getSystemService(ClipboardManager::class.java)

    private companion object {
        const val EMPTY_MESSAGE = "(剪贴板为空或不可读)"
    }
}

/**
 * 写剪贴板：返回 "已复制 N 字符"（N=字符数，口径同 write_file 的字符计数）。
 */
class ClipboardWriteTool(private val appContext: Context) : AgentTool {

    override val name = "clipboard_write"
    override val description = "把文本写入系统剪贴板"
    override val parametersJson =
        """{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}"""

    override suspend fun execute(args: JsonObject, onProgress: (String) -> Unit): String =
        withContext(Dispatchers.Main) {
            val text = args.requiredString("text")
            onProgress("正在写入剪贴板")
            clipboard().setPrimaryClip(ClipData.newPlainText(null, text))
            "已复制 ${text.length} 字符"
        }

    private fun clipboard(): ClipboardManager =
        appContext.getSystemService(ClipboardManager::class.java)
}

// ---------- 参数取值（口径同 FileTools：isJsonPrimitive 守卫，缺参即 IAE） ----------

private fun JsonObject.requiredString(key: String): String =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString
        ?: throw IllegalArgumentException("缺少必填参数 $key")
