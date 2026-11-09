package dev.liuyouwu.familiar.ui.screen.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.liuyouwu.familiar.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

/**
 * 结构化导出的 SAF 接线（审计 A3 自 ChatScreen 拆出）：Markdown/JSON 的
 * CreateDocument launcher×2 + 待写 payload 暂存。磁盘 IO 与结果文案下沉在
 * [ChatViewModel.writeExportPayload]（实现在 ExportController），本文件只留
 * launcher 接线，行为与拆分前逐行等价（同一协程顺序：写盘 → 清暂存 → snackbar）。
 *
 * 用法：ChatScreen 组合期调 [rememberChatExportActions] 取得实例，
 * 导出格式选择框按需调 [ChatExportActions.startStructuredExport]。
 */
internal class ChatExportActions internal constructor(
    private val startStructuredExportImpl: (String) -> Unit,
) {
    /** 选定 Markdown/JSON 后：投影读取 + IO 序列化在 ViewModel 内完成（审计 P2-16），这里只负责 SAF 拉起与落盘 */
    fun startStructuredExport(format: String) = startStructuredExportImpl(format)
}

@Composable
internal fun rememberChatExportActions(
    viewModel: ChatViewModel,
    conversationId: Long,
    snackbarHostState: SnackbarHostState,
): ChatExportActions {
    val coroutineScope = rememberCoroutineScope()

    // SAF「另存为」待写入内容：launch 与回调之间暂存（不 saveable，进程重建后重选一次即可）
    var pendingExportPayload by remember { mutableStateOf<String?>(null) }

    // 结构化导出（Markdown/JSON）：SAF CreateDocument 回调统一落盘，结果给 snackbar
    fun writeExportPayload(uri: Uri?) {
        val payload = pendingExportPayload
        if (uri == null || payload == null) return
        coroutineScope.launch {
            val message = viewModel.writeExportPayload(uri, payload)
            pendingExportPayload = null
            snackbarHostState.showSnackbar(message)
        }
    }

    val exportMarkdownLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("text/markdown"),
        ) { uri -> writeExportPayload(uri) }
    val exportJsonLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json"),
        ) { uri -> writeExportPayload(uri) }

    fun startStructuredExport(format: String) {
        coroutineScope.launch {
            val result = viewModel.buildExportPayload(conversationId, format)
            if (result == null) {
                snackbarHostState.showSnackbar("导出失败：会话不存在")
                return@launch
            }
            val (payload, safeTitle) = result
            pendingExportPayload = payload
            if (format == "json") {
                exportJsonLauncher.launch("$safeTitle.json")
            } else {
                exportMarkdownLauncher.launch("$safeTitle.md")
            }
        }
    }

    // 不 remember 缓存：本类只作事件回调载体（非组合输入），随重组重建零成本；
    // 且必须捕获当前重组的 conversationId——ChatScreen 可在同实例内切会话
    // （同路由换参，remember 不重启），缓存旧实例会把导出打到上一个会话
    return ChatExportActions { format -> startStructuredExport(format) }
}
