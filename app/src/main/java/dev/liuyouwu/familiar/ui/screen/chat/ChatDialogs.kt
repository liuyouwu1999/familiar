package dev.liuyouwu.familiar.ui.screen.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liuyouwu.familiar.core.llm.wire.ModelInfo
import dev.liuyouwu.familiar.ui.component.ContextCapacityPanel
import dev.liuyouwu.familiar.ui.component.PixelModelPickerDialog
import dev.liuyouwu.familiar.ui.theme.PixelBottomPanel
import dev.liuyouwu.familiar.ui.theme.PixelModeRow
import dev.liuyouwu.familiar.ui.theme.WoodBorder
import dev.liuyouwu.familiar.ui.theme.pixelSecondaryAlpha
import dev.liuyouwu.familiar.util.isEmbeddingModel
import dev.liuyouwu.familiar.viewmodel.ContextBreakdown
import dev.liuyouwu.familiar.viewmodel.skillhooks.ToolGateKeeper
import dev.liuyouwu.familiar.viewmodel.skillhooks.ToolGateResponse
import kotlinx.coroutines.flow.StateFlow

/*
 * ChatScreen 对话框拆出（审计 A3）：brief 清单 6 个 dialog + 指标核对阶段补搬的
 * 记忆框/导出格式选择框（HEAD :1522/:1498 一带，Step 2「检查 dialog 是否漏搬」），
 * 各自私有（模块内）composable。签名 = 所需 state + 回调 lambda；仅本对话框消费的
 * 流在对话框内部 collect（ChatScreen 与对话框共用的 state 由 ChatScreen 收集后以
 * 现值传入）。行为与拆分前逐行等价：确认/关闭回调里的 VM 调用留在 ChatScreen 的
 * lambda 里。
 */

// 模型列表过滤单点（原三处重复：设置面板/换模型重试/切模型同款——剔除 embedding，
// 过滤后为空则显示全部）。纯函数（LazyColumn scope 非 composable，不能内 remember）
private fun filterSelectableModels(availableModels: List<ModelInfo>): List<ModelInfo> =
    availableModels
        .filter { !isEmbeddingModel(it.id.orEmpty()) }
        .ifEmpty { availableModels }

// ===== 1/6：对话设置（模型切换 + 视频帧数 + 最大上下文 + 容量面板 + 聊天室每轮发言数）=====
// 原内联于 ChatScreen（约 :486-679），等价搬出

@Composable
internal fun ChatSettingsDialog(
    isChatRoom: Boolean,
    currentModel: String,
    availableModels: List<ModelInfo>,
    lastCacheHit: Int?,
    videoFrameCount: StateFlow<Int>,
    maxContext: StateFlow<Int>,
    sessionContextBreakdown: StateFlow<ContextBreakdown?>,
    chatRoomMaxReplies: StateFlow<Int>,
    onDismiss: () -> Unit,
    onModelSelected: (String) -> Unit,
    onVideoFrameCountSaved: (Int) -> Unit,
    onMaxContextSaved: (Int) -> Unit,
    onChatRoomMaxRepliesChanged: (Int) -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val frameCount by videoFrameCount.collectAsStateWithLifecycle()
    val maxCtx by maxContext.collectAsStateWithLifecycle()
    val breakdown by sessionContextBreakdown.collectAsStateWithLifecycle()
    val roomMaxReplies by chatRoomMaxReplies.collectAsStateWithLifecycle()

    var editingFrameCount by remember { mutableStateOf(frameCount.toString()) }
    var editingMaxContext by remember { mutableStateOf(maxCtx.toString()) }
    var modelsExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置") },
        text = {
            LazyColumn {
                // Model section
                item {
                    Text("模型", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.size(8.dp))
                }

                // Show only first 4 models by default, or all if expanded
                // 过滤嵌入模型：误选对话必报错；过滤后为空则显示全部
                val selectableModels = filterSelectableModels(availableModels)
                val displayedModels = if (modelsExpanded) selectableModels else selectableModels.take(4)
                items(displayedModels) { model ->
                    Card(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .heightIn(min = 48.dp)
                                .clickable { onModelSelected(model.id.orEmpty()) },
                        colors =
                            CardDefaults.cardColors(
                                containerColor =
                                    if ((model.id ?: "") == currentModel) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                            ),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = (model.id ?: "") == currentModel,
                                onClick = { onModelSelected(model.id.orEmpty()) },
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(
                                text = model.id.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                // Expand/Collapse button
                if (selectableModels.size > 4) {
                    item {
                        TextButton(
                            onClick = { modelsExpanded = !modelsExpanded },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                imageVector = if (modelsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                // 装饰性：旁有"展开/收起"文字
                                contentDescription = null,
                            )
                            Spacer(modifier = Modifier.size(4.dp))
                            Text(if (modelsExpanded) "收起 (${selectableModels.size - 4} 个)" else "展开全部 (${selectableModels.size - 4} 个)")
                        }
                    }
                }

                // Video frames section
                item {
                    Spacer(modifier = Modifier.size(16.dp))
                    Text("视频帧数", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.size(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = editingFrameCount,
                            onValueChange = { editingFrameCount = it },
                            label = { Text("帧数") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(shape = RectangleShape, border = BorderStroke(1.dp, WoodBorder), onClick = {
                            keyboardController?.hide()
                            val count = editingFrameCount.toIntOrNull()?.coerceIn(1, 50) ?: 20
                            editingFrameCount = count.toString()
                            onVideoFrameCountSaved(count)
                        }) {
                            Text("保存")
                        }
                    }
                    Text(
                        "采集帧数 1-50，当前 $frameCount",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = pixelSecondaryAlpha()),
                    )
                }

                // F5: Max Context section
                item {
                    Spacer(modifier = Modifier.size(16.dp))
                    Text("最大上下文 Token", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.size(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = editingMaxContext,
                            onValueChange = { editingMaxContext = it },
                            label = { Text("Token 数") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(shape = RectangleShape, border = BorderStroke(1.dp, WoodBorder), onClick = {
                            keyboardController?.hide()
                            val ctx =
                                editingMaxContext.toIntOrNull()?.coerceIn(
                                    512,
                                    dev.liuyouwu.familiar.data.repository.SettingsRepository.MAX_CONTEXT_LIMIT,
                                )
                                    ?: dev.liuyouwu.familiar.data.repository.SettingsRepository.DEFAULT_MAX_CONTEXT
                            editingMaxContext = ctx.toString()
                            onMaxContextSaved(ctx)
                        }) {
                            Text("保存")
                        }
                    }
                    Text(
                        "上下文上限 512-${dev.liuyouwu.familiar.data.repository.SettingsRepository.MAX_CONTEXT_LIMIT}，当前 $maxCtx",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = pixelSecondaryAlpha()),
                    )
                }

                // 上下文容量：构成明细 + 分段进度条。数据源（T5 统一）：六类型读
                // SessionState.contextBreakdown（VM 旧链 contextBreakdown 随
                // updateContextTokenUsage 删除退役）
                item {
                    Spacer(modifier = Modifier.size(16.dp))
                    Text("上下文容量", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.size(8.dp))
                    val capacity = breakdown
                    if (capacity != null && capacity.maxContext > 0) {
                        ContextCapacityPanel(
                            breakdown = capacity,
                            cacheHitPercent = lastCacheHit,
                        )
                    }
                }

                // Chat room: AI replies per round
                item {
                    Spacer(modifier = Modifier.size(16.dp))
                    Text("聊天室·每轮AI发言数", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.size(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        OutlinedButton(
                            shape = RectangleShape,
                            onClick = { onChatRoomMaxRepliesChanged(roomMaxReplies - 1) },
                            enabled = roomMaxReplies > 1,
                        ) { Text("−") }
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            "$roomMaxReplies",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        OutlinedButton(
                            shape = RectangleShape,
                            onClick = { onChatRoomMaxRepliesChanged(roomMaxReplies + 1) },
                            enabled = roomMaxReplies < 6,
                        ) { Text("+") }
                    }
                    Text(
                        if (isChatRoom) {
                            "你每发一条消息，主持人最多安排几个 AI 接话"
                        } else {
                            "仅聊天室生效（当前为普通会话）"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = pixelSecondaryAlpha()),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

// ===== 2/6：编辑系统提示词 =====

@Composable
internal fun ChatPromptEditDialog(
    prompt: String,
    onPromptChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("系统提示词") },
        text = {
            OutlinedTextField(
                value = prompt,
                onValueChange = onPromptChange,
                modifier = Modifier.fillMaxWidth().height(200.dp),
                placeholder = { Text("输入系统提示词…") },
            )
        },
        confirmButton = {
            Button(shape = RectangleShape, border = BorderStroke(1.dp, WoodBorder), onClick = {
                keyboardController?.hide()
                onSave(prompt)
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ===== 3/6：/clear 二次确认（与删除会话同标准）=====

@Composable
internal fun ChatClearConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("清空对话") },
        text = { Text("清空全部消息？清掉就找不回来了") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("清空", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

// ===== 4/6：工具审批（审计 S2）=====
// 标题=工具名，正文=摘要/目标 host；三键=拒绝/允许一次/总是允许（host 回写白名单）。
// 点外部/返回键=拒绝（默认不放行）；闸门侧超时同样视同拒绝。
// 注意：pendingApproval 流的常驻订阅留在 ChatScreen（订阅计数 = 前台监听者在场
// 判定源），本对话框只接收解析后的请求值——订阅随对话框可见性起落会破坏在场判定。

@Composable
internal fun ChatToolApprovalDialog(
    approval: ToolGateKeeper.ToolApprovalRequest,
    onRespond: (ToolGateResponse) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onRespond(ToolGateResponse.DENY) },
        title = { Text("确认工具调用：${approval.tool}") },
        text = {
            Column {
                Text(approval.summary)
                approval.host?.let {
                    Text(
                        "目标主机：$it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "AI 请求执行上述操作，允许吗？",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { onRespond(ToolGateResponse.ALLOW_ALWAYS) }) {
                    Text("总是允许")
                }
                TextButton(onClick = { onRespond(ToolGateResponse.ALLOW_ONCE) }) {
                    Text("允许一次")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { onRespond(ToolGateResponse.DENY) }) {
                Text("拒绝", color = MaterialTheme.colorScheme.error)
            }
        },
    )
}

// ===== 5/6：换模型重试（像素单选框）=====
// 数据源与设置面板一致（availableModels 剔除 embedding，空则回退全量，取 id 列）

@Composable
internal fun ChatRetryModelPickerDialog(
    availableModels: List<ModelInfo>,
    currentModel: String,
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit,
) {
    val retryModels =
        remember(availableModels) {
            filterSelectableModels(availableModels).mapNotNull { it.id }
        }
    PixelModelPickerDialog(
        title = "换模型重试",
        models = retryModels,
        currentModel = currentModel,
        onDismiss = onDismiss,
        onSelected = onSelected,
    )
}

// ===== 6/6：顶栏切模型（选完立即落库生效）=====

@Composable
internal fun ChatModelSwitcherDialog(
    availableModels: List<ModelInfo>,
    currentModel: String,
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit,
) {
    val switcherModels =
        remember(availableModels) {
            filterSelectableModels(availableModels).mapNotNull { it.id }
        }
    PixelModelPickerDialog(
        title = "切换模型（立即生效）",
        models = switcherModels,
        currentModel = currentModel,
        onDismiss = onDismiss,
        onSelected = onSelected,
    )
}

// ===== 7/8：大肥鱼的记忆（查看 + 复制；清空入口只留在首页设置，聊天页不给误触机会）=====
// memoryText 的读取（readFamiliarMemory 挂起点）留在 ChatScreen 调用位：打开即读，
// 与拆分前同时序。复制动作（剪贴板 + snackbar）经 onCopy 回调留在 ChatScreen。

@Composable
internal fun ChatMemoryDialog(
    memoryText: String?,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("大肥鱼的记忆") },
        text = {
            Column(
                modifier =
                    Modifier
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    memoryText ?: "读取中…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        dismissButton = {
            TextButton(
                enabled = memoryText != null,
                onClick = onCopy,
            ) { Text("复制") }
        },
    )
}

// ===== 8/8：导出格式选择框（Markdown / JSON 走 SAF 另存为；纯文本分享沿用原 HTML 分享）=====

@Composable
internal fun ChatExportChooserDialog(
    onDismiss: () -> Unit,
    onExportMarkdown: () -> Unit,
    onExportJson: () -> Unit,
    onSharePlainText: () -> Unit,
) {
    PixelBottomPanel(onDismiss = onDismiss, title = "导出对话") {
        PixelModeRow(
            icon = Icons.Default.Description,
            title = "Markdown",
            subtitle = "保存为 .md 文件",
            iconBox = MaterialTheme.colorScheme.primary,
            onClick = onExportMarkdown,
        )
        Spacer(modifier = Modifier.height(2.dp))
        PixelModeRow(
            icon = Icons.Default.DataObject,
            title = "JSON",
            subtitle = "保存为 .json 文件",
            iconBox = MaterialTheme.colorScheme.secondary,
            onClick = onExportJson,
        )
        Spacer(modifier = Modifier.height(2.dp))
        PixelModeRow(
            icon = Icons.Default.Share,
            title = "纯文本分享",
            subtitle = "生成网页分享",
            iconBox = MaterialTheme.colorScheme.tertiary,
            onClick = onSharePlainText,
        )
    }
}
