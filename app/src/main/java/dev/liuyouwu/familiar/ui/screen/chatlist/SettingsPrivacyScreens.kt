package dev.liuyouwu.familiar.ui.screen.chatlist

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.material3.SnackbarHostState
import dev.liuyouwu.familiar.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

/**
 * 大肥鱼记忆弹窗群（P5 T6 自 ChatListScreen 抽出，隐私域）：记忆只读预览（serialize
 * 渲染）+ 复制 + 两级确认清空。清空走两级确认：记忆只存在本机且无副本，误删无法挽回，
 * 不能单击就删。入口在主「设置」弹窗的「查看记忆」（SettingsDialog.onShowMemory）。
 */
@Composable
internal fun FamiliarMemoryDialogs(
    visible: Boolean,
    chatViewModel: ChatViewModel,
    snackbarHostState: SnackbarHostState,
    onDismiss: () -> Unit
) {
    if (!visible) return
    val uiScope = rememberCoroutineScope()
    var memoryText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { memoryText = chatViewModel.readFamiliarMemory() }
    val clipboard = LocalClipboardManager.current
    // 0=未进入确认 1=第一次确认（说明后果） 2=第二次确认（最终拍板）
    var wipeStep by remember { mutableStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("大肥鱼的记忆") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    memoryText ?: "读取中…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        dismissButton = {
            Row {
                // 手动逃生通道：记忆文本随时可拷走，不依赖备份文件
                TextButton(
                    enabled = memoryText != null,
                    onClick = {
                        clipboard.setText(AnnotatedString(memoryText.orEmpty()))
                        uiScope.launch { snackbarHostState.showSnackbar("已复制") }
                    }
                ) { Text("复制") }
                TextButton(onClick = { wipeStep = 1 }) {
                    Text("清空记忆", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    )
    // 第一级：把后果说清楚再让用户决定
    if (wipeStep == 1) {
        AlertDialog(
            onDismissRequest = { wipeStep = 0 },
            title = { Text("清空记忆？") },
            text = {
                Text(
                    "这会删掉大肥鱼记住的全部内容——你们的经历、习惯和偏好。" +
                        "记忆只保存在本机、没有副本，清掉就永远找不回来了。"
                )
            },
            confirmButton = {
                TextButton(onClick = { wipeStep = 2 }) {
                    Text("继续清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { wipeStep = 0 }) { Text("取消") }
            }
        )
    }
    // 第二级：最终拍板，防手滑
    if (wipeStep == 2) {
        AlertDialog(
            onDismissRequest = { wipeStep = 0 },
            title = { Text("真的要删吗？") },
            text = {
                Text("再确认一次：清空后大肥鱼会忘掉你们之间的一切，从零开始。")
            },
            confirmButton = {
                TextButton(onClick = {
                    wipeStep = 0
                    onDismiss()
                    chatViewModel.clearFamiliarMemory {
                        uiScope.launch { snackbarHostState.showSnackbar("记忆已清空") }
                    }
                }) {
                    Text("确认清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { wipeStep = 0 }) { Text("再想想") }
            }
        )
    }
}
