package dev.liuyouwu.familiar.ui.component

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShortText
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import dev.liuyouwu.familiar.data.local.entity.MessageEntity
import dev.liuyouwu.familiar.ui.model.MessageRenderModel
import dev.liuyouwu.familiar.ui.model.ToolBlock
import dev.liuyouwu.familiar.ui.model.ToolBlockState
import dev.liuyouwu.familiar.ui.model.UiMessage
import dev.liuyouwu.familiar.ui.theme.GrassGreen
import dev.liuyouwu.familiar.ui.theme.PixelButton
import dev.liuyouwu.familiar.ui.theme.StarGold
import dev.liuyouwu.familiar.ui.theme.WoodBorder
import dev.liuyouwu.familiar.ui.theme.pixelFrame
import dev.liuyouwu.familiar.ui.theme.pixelPanelColors
import dev.liuyouwu.familiar.ui.theme.pixelSecondaryAlpha
import dev.liuyouwu.familiar.ui.theme.pixelUserColors
import dev.jeziellago.compose.markdowntext.MarkdownText

/**
 * 截断标记判据（内核清理 A，Task 4 起常量源为 viewmodel 侧 RunMarkerTexts——
 * agent 链写侧经 RunMarkers 注入同源）。
 * 正文以它结尾即视为"被截断"，末条助手气泡下可给「继续生成」快捷入口。
 */
private val TRUNCATION_MARKER = dev.liuyouwu.familiar.viewmodel.RunMarkerTexts.TRUNCATION_TAIL

/** 正文（渲染模型 fullText）是否以截断标记收尾。 */
private fun isTruncatedMessage(content: String): Boolean =
    content.trimEnd().endsWith(TRUNCATION_MARKER)

/** 附件惰性加载位（审计复审 Minor-4）：loading=true 在途（占位框），false 即终态
 *  ——payload=null 表示空结果（行已删/无可显附件），不再渲染占位 */
private data class LazyAttachmentUi(
    val payload: dev.liuyouwu.familiar.ui.model.AttachmentPayload?,
    val loading: Boolean,
)

/**
 * 旧聊天链兼容重载（Agent 内核 P3 Task 8）：MessageEntity 调用形状原样保留，
 * 内部经 [MessageRenderModel.fromEntity] 折成 [UiMessage] 委托新渲染核心。
 * ChatScreen（T9 改造前）继续走此入口，行为不变。
 */
@Composable
fun MessageBubble(
    message: MessageEntity,
    isStreaming: Boolean = false,
    streamingText: String = "",
    streamingReasoning: String = "",
    showThinking: Boolean = true,
    onEdit: ((Long, String) -> Unit)? = null,
    onCopy: ((String) -> Unit)? = null,
    onRegenerate: (() -> Unit)? = null,
    onContinue: (() -> Unit)? = null,
    onReply: ((MessageEntity) -> Unit)? = null,
    // 换模型重试：非空时末条助手消息的长按菜单出现"换模型重试"，点击后由页面层弹模型选择框
    onRetryWithModel: (() -> Unit)? = null,
    isLastAssistant: Boolean = false,
    // QQ 式头像：大肥鱼传立绘帧 painter，聊天室传 null 走 avatarLetter 首字色块
    avatarRes: Int? = null,
    // 皮肤位图头像（P5）：非空时优先于 avatarRes 渲染（familiar 头像走此通道）
    avatarBitmap: ImageBitmap? = null,
    avatarLetter: String? = null,
    avatarColor: Color? = null,
    // 名字兜底：大肥鱼消息 senderDisplayName 为 null，由调用方传鱼名显示
    fallbackSenderName: String? = null
) {
    val ui = remember(message) { MessageRenderModel.fromEntity(message) }
    MessageBubble(
        message = ui,
        isStreaming = isStreaming,
        streamingText = streamingText,
        streamingReasoning = streamingReasoning,
        showThinking = showThinking,
        onEdit = onEdit?.let { cb -> { _, newContent -> cb(message.id, newContent) } },
        onCopy = onCopy,
        onRegenerate = onRegenerate,
        onContinue = onContinue,
        onReply = onReply?.let { cb -> { _ -> cb(message) } },
        onRetryWithModel = onRetryWithModel,
        isLastAssistant = isLastAssistant,
        avatarRes = avatarRes,
        avatarBitmap = avatarBitmap,
        avatarLetter = avatarLetter,
        avatarColor = avatarColor,
        fallbackSenderName = fallbackSenderName,
    )
}

/**
 * 块渲染核心（Agent 内核 P3 Task 8）：渲染 [UiMessage]（思考/文本/工具卡片三段）。
 * - 思考：折叠盒【唯一实现】——思考拆分已在 [MessageRenderModel] 完成，气泡侧
 *   不再剥任何思考标签；
 * - 文本：Markdown + LaTeX + 表格隔离（流式）不变；
 * - 工具卡片：ToolCall+ToolResult 三态小卡（见 [ToolCallCard]）；
 * - 图片/附件/回复引用/Token 小字/Action Bar/长按菜单全部保留。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: UiMessage,
    isStreaming: Boolean = false,
    streamingText: String = "",
    streamingReasoning: String = "",
    showThinking: Boolean = true,
    onEdit: ((String, String) -> Unit)? = null,
    onCopy: ((String) -> Unit)? = null,
    onRegenerate: (() -> Unit)? = null,
    onContinue: (() -> Unit)? = null,
    onReply: ((UiMessage) -> Unit)? = null,
    onRetryWithModel: (() -> Unit)? = null,
    // 附件惰性加载（审计 P1-4）：hasLazyAttachment 行经此回调按需取附件真值
    // （VM 层折算 AttachmentPayload——ui 包不触碰 data.local）。null = 调用方无惰性路径
    onLoadAttachment: (suspend (String) -> dev.liuyouwu.familiar.ui.model.AttachmentPayload?)? = null,
    isLastAssistant: Boolean = false,
    avatarRes: Int? = null,
    // 皮肤位图头像（P5）：非空时优先于 avatarRes 渲染（familiar 头像走此通道）
    avatarBitmap: ImageBitmap? = null,
    avatarLetter: String? = null,
    avatarColor: Color? = null,
    fallbackSenderName: String? = null
) {
    val isUser = message.isUser
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val context = LocalContext.current

    // Determine sender info for chat room mode
    val senderDisplayName = message.senderName
    val senderColorHex = message.senderColorHex

    // 思考来源唯一化：模型层已拆好（blocks Thinking / 存量 <think> 一次性拆分），
    // 气泡侧只取 streamingReasoning（流式）或 message.thinking（落库）
    val displayReasoning = if (isStreaming && showThinking) streamingReasoning
    else (if (showThinking) message.thinking else null) ?: ""
    val displayContent = if (isStreaming) streamingText else message.fullText

    val isThinkingPhase = isStreaming && showThinking && streamingText.isEmpty() && streamingReasoning.isNotEmpty()
    val isWaitingPhase = isStreaming && streamingText.isEmpty() && (streamingReasoning.isEmpty() || !showThinking)
    val isContentPhase = isStreaming && streamingText.isNotEmpty()

    var reasoningExpanded by remember(message.id) { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var editingText by remember { mutableStateOf("") }

    // Context menu
    var showContextMenu by remember { mutableStateOf(false) }
    // Selection mode for AI messages
    var isSelectMode by remember { mutableStateOf(false) }
    // Action bar visibility
    var showActionBar by remember { mutableStateOf(false) }

    // Helper to copy to clipboard
    val copyToClipboard: (String) -> Unit = { text ->
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Message", text))
    }

    // Edit dialog
    if (showEditDialog) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("编辑消息") },
            text = {
                OutlinedTextField(
                    value = editingText,
                    onValueChange = { editingText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                )
            },
            confirmButton = {
                Button(shape = RectangleShape, border = BorderStroke(1.dp, WoodBorder), onClick = {
                    onEdit?.invoke(message.id, editingText)
                    showEditDialog = false
                }) {
                    Text("保存并重发")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // Selection mode exit button
    if (isSelectMode && !isUser) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.End
        ) {
            Button(
                shape = RectangleShape,
                border = BorderStroke(1.dp, WoodBorder),
                onClick = { isSelectMode = false },
                modifier = Modifier.padding(end = 4.dp)
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) // 装饰性：旁有"完成选择"文字
                Spacer(modifier = Modifier.size(4.dp))
                Text("完成选择")
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        // 名字：聊天室=发送者名；大肥鱼=调用方传的鱼名。普通单聊两者皆 null 不显示
        val shownSenderName = senderDisplayName ?: fallbackSenderName
        // 头像：聊天室=首字色块（角色色），大肥鱼=皮肤位图；用户消息无头像（右侧气泡）。
        // 位图 null 时靠 avatarColor 出 accent 占位框（familiar 专属路径）；
        // 聊天室 avatarColor 恒伴 avatarLetter，不受该条件影响
        val showAvatar = !isUser && (avatarBitmap != null || avatarRes != null || avatarLetter != null ||
            (avatarColor != null && avatarLetter == null))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            if (showAvatar) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        // clipToBounds：大头照放大绘制裁进框内，立绘身体不外溢
                        .clipToBounds()
                        .border(1.dp, WoodBorder)
                        .background(avatarColor ?: pixelPanelColors().container),
                    contentAlignment = Alignment.Center
                ) {
                    if (avatarBitmap != null) {
                        // 皮肤位图（P5）：Fit 直接显示，40dp 框 + clipToBounds 语义沿用
                        Image(
                            bitmap = avatarBitmap,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    } else if (avatarRes != null) {
                        // 旧 drawable 通道（P5 后 familiar 不再传，保留兼容签名）
                        Image(
                            painter = androidx.compose.ui.res.painterResource(avatarRes),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Text(
                            text = avatarLetter.orEmpty(),
                            style = MaterialTheme.typography.titleMedium,
                            color = StarGold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                Spacer(modifier = Modifier.size(8.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
        // 聊天室角色名：AI 角色气泡上方的小号角色名（颜色跟随气泡角色色）。
        // 根因备注：之前该 Row 放在下方气泡的 Box 内部，与气泡 Column 是兄弟节点——
        // Box 子节点层叠绘制，不透明的 pixelFrame 气泡后画，直接把角色名盖住，
        // 导致真机上"有名字但看不见"。现挪到外层 Column，成为气泡上方独立一行。
        // 普通单聊落库时 senderDisplayName 为 null，自然不显示。
        // 名牌色完全由 sender 色驱动（P3：删除"老师"字面量特判——角色色缺失统一
        // 回退主题色，特例交给写侧补 senderColorHex）
        if (!isUser && shownSenderName != null) {
            val badgeColor = senderColorHex?.let { parseColorHex(it) }
                ?: MaterialTheme.colorScheme.primary
            Box(
                modifier = Modifier
                    .padding(bottom = 4.dp, start = 4.dp)
                    .background(badgeColor)
                    .border(1.dp, WoodBorder)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = shownSenderName,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
        ) {
            Box(modifier = Modifier.widthIn(max = screenWidth * 0.85f)) {
                // Message content column
                // 像素气泡：用户=天蓝凹凸框，AI=木框纸芯；聊天室成员气泡带角色色微染
                val bubble = when {
                    isUser -> pixelUserColors()
                    senderColorHex != null -> pixelPanelColors().copy(
                        container = parseColorHex(senderColorHex).copy(alpha = 0.14f)
                            .compositeOver(pixelPanelColors().container)
                    )
                    else -> pixelPanelColors()
                }
                Column(
                    modifier = Modifier
                        .then(
                            if (isSelectMode) {
                                Modifier.background(bubble.container)
                            } else {
                                Modifier.pixelFrame(
                                    container = bubble.container,
                                    frame = bubble.frame,
                                    highlight = bubble.highlight,
                                    shadow = bubble.shadow,
                                    border = bubble.border
                                )
                            }
                        )
                        .padding(12.dp)
                ) {
                    // Reply reference
                    if (message.replyPreview != null) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isUser)
                                    Color.White.copy(alpha = 0.15f)
                                else
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .height(32.dp)
                                        .background(
                                            if (isUser) Color.White.copy(alpha = 0.7f)
                                            else MaterialTheme.colorScheme.primary
                                        )
                                )
                                Spacer(modifier = Modifier.size(8.dp))
                                Column {
                                    Text(
                                        text = if (message.replyRole == "user") "你" else "AI",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isUser) Color.White.copy(alpha = 0.7f)
                                        else MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = message.replyPreview,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isUser) Color.White.copy(alpha = 0.6f)
                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.size(8.dp))
                    }

                    // 附件惰性加载（审计 P1-4）：轻量读路径的 hasAttachment 行在 VM 层
                    // 附件置空 + 打标，此处经 onLoadAttachment 按需取真值（repo LruCache
                    // 8 条，滚回可见不重复触库）。loading 哨兵区分「加载中」（占位框）
                    // 与「空结果」（loader 返 null = 行已删/无可显附件，不渲染占位，
                    // 审计复审 Minor-4——不再无限期挂着占位框）。
                    val lazyAttachment by androidx.compose.runtime.produceState(
                        initialValue = LazyAttachmentUi(payload = null, loading = message.hasLazyAttachment),
                        key1 = message.id,
                    ) {
                        if (message.hasLazyAttachment) {
                            value = LazyAttachmentUi(payload = onLoadAttachment?.invoke(message.id), loading = false)
                        }
                    }
                    val images = if (message.hasLazyAttachment) lazyAttachment.payload?.images.orEmpty()
                        else message.images.ifEmpty { listOfNotNull(message.imageBase64) }
                    val attachmentSummaries = if (message.hasLazyAttachment) lazyAttachment.payload?.summaries.orEmpty()
                        else message.attachments
                    if (message.hasLazyAttachment && lazyAttachment.loading) {
                        // 附件在途占位（与下方图片解码占位同形）
                        Box(
                            modifier = Modifier
                                .size(200.dp)
                                .background(Color.White.copy(alpha = 0.1f))
                                .border(1.dp, if (isUser) Color.White.copy(alpha = 0.5f) else WoodBorder)
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                    }

                    // Image attachments：base64 后台解码落缓存文件，Coil 加载文件。
                    // （曾直接喂 data URI：大图在部分机型上 Coil 解码失败，气泡只剩蓝底；
                    //  文件路径是 Coil 最稳的加载通道，且按消息 id 缓存不重复解码）
                    // P5 清理批（多图首图修复）：内核附件可带多张 Image——旧渲染
                    // firstOrNull() 只出首图、其余静默丢；改 images 列表全量渲染
                    // （单图形态不变；旧列 imageBase64 兜底兼容既有构造点）
                    images.forEachIndexed { imgIndex, imageB64 ->
                        val context = androidx.compose.ui.platform.LocalContext.current
                        val imageFile by androidx.compose.runtime.produceState<java.io.File?>(
                            initialValue = null,
                            key1 = message.id,
                            key2 = imgIndex,
                        ) {
                            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                runCatching {
                                    val dir = java.io.File(context.cacheDir, "msg_images").apply { mkdirs() }
                                    val f = java.io.File(dir, "msg_${message.id}_$imgIndex.jpg")
                                    if (!f.exists() || f.length() == 0L) {
                                        val b64 = imageB64.substringAfterLast("base64,")
                                        f.writeBytes(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
                                    }
                                    f
                                }.getOrNull()
                            }
                        }
                        if (imageFile != null) {
                            AsyncImage(
                                model = imageFile,
                                contentDescription = "Attached image",
                                modifier = Modifier
                                    .size(200.dp)
                                    .border(1.dp, if (isUser) Color.White.copy(alpha = 0.5f) else WoodBorder)
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(200.dp)
                                    .background(Color.White.copy(alpha = 0.1f))
                                    .border(1.dp, if (isUser) Color.White.copy(alpha = 0.5f) else WoodBorder)
                            )
                        }
                        Spacer(modifier = Modifier.size(8.dp))
                    }

                    // File attachment（渲染模型附件摘要：文件名 + 视频帧数）
                    attachmentSummaries.forEach { attachment ->
                        val attachmentLabel = if (attachment.videoFrameCount != null) {
                            "\uD83C\uDFA5 ${attachment.fileName ?: "视频"} (${attachment.videoFrameCount} frames)"
                        } else {
                            "\uD83D\uDCC1 ${attachment.fileName ?: "文件"}"
                        }
                        Text(
                            text = attachmentLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isUser) Color.White.copy(alpha = 0.7f)
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.size(4.dp))
                    }

                    // Waiting
                    if (isWaitingPhase) {
                        Text(
                            text = "思考中...",
                            style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic),
                            color = if (isUser) Color.White
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = pixelSecondaryAlpha())
                        )
                    }

                    // Reasoning box（唯一实现：思考拆分在 MessageRenderModel，这里只渲染）
                    if (displayReasoning.isNotEmpty()) {
                        // 思考阶段也保持折叠：实时展开会把长思考糊满气泡（maid 式体验 =
                        // 只见"思考中…"指示，完成后折叠成"思考过程"按需展开）
                        val expanded = when {
                            isContentPhase -> false
                            !isStreaming -> reasoningExpanded
                            else -> false
                        }

                        Column(
                            modifier = Modifier
                                .background(
                                    if (isUser) Color.White.copy(alpha = 0.1f)
                                    else Color.Black.copy(alpha = 0.05f)
                                )
                                .border(1.dp, if (isUser) Color.White.copy(alpha = 0.3f) else WoodBorder)
                                .padding(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = if (!isStreaming) {
                                    Modifier.clickable { reasoningExpanded = !reasoningExpanded }
                                } else Modifier
                            ) {
                                Text(
                                    text = if (expanded) "▼" else "▶",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isUser) Color.White.copy(alpha = 0.6f)
                                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                )
                                Spacer(modifier = Modifier.size(4.dp))
                                Text(
                                    text = if (isThinkingPhase) "思考中..." else "思考过程",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isUser) Color.White.copy(alpha = 0.6f)
                                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                )
                            }

                            if (expanded) {
                                Spacer(modifier = Modifier.size(4.dp))
                                Text(
                                    text = displayReasoning,
                                    style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                                    color = if (isUser) Color.White.copy(alpha = 0.7f)
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.size(8.dp))
                    }

                    // Tool cards：ToolCall+ToolResult 三态小卡（思考后、正文前，
                    // 与 agent 链"思考→调工具→作答"的产出顺序一致）
                    message.toolBlocks.forEach { tool ->
                        ToolCallCard(tool = tool)
                        Spacer(modifier = Modifier.size(4.dp))
                    }

                    // Main content
                    if (displayContent.isNotEmpty()) {
                        if (isUser) {
                            Text(
                                text = displayContent,
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White
                            )
                        } else if (isSelectMode) {
                            // Selection mode: show plain text with selection support
                            SelectionContainer(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Text(
                                    text = stripMarkdownForSelection(displayContent),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        } else if (isStreaming) {
                            // 流式降级（审计 P1-5）：正文纯文本渲染，不做 Markdown/LaTeX/
                            // 表格转换——每帧全量正文过 4+6 个 Regex + 重建 48+70 项映射表
                            // 是流式卡顿根因；收线（isStreaming=false）自动回落完整渲染。
                            // 聊天室/圆桌角色流式（streamingSender 非空）同走此分支，
                            // 流式期纯文本是 spec 明示的预期视觉差异，不是回归。
                            // trimEnd()：Markdown 渲染会吞尾部空白，纯文本对齐该行为防尾行抖动。
                            Text(
                                text = displayContent.trimEnd(),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            // Normal mode (not streaming): show markdown with LaTeX support
                            // remember(displayContent)（审计 P1-5）：同正文重组合不重跑
                            // normalizeMarkdown 的全量 Regex 替换，仅内容变化时重算
                            val markdown = remember(displayContent) { normalizeMarkdown(displayContent) }
                            MarkdownText(
                                markdown = markdown,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    // Streaming cursor
                    if (isStreaming && displayContent.isNotEmpty()) {
                        Text(
                            text = " |",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isUser) Color.White
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Token 用量小字：仅助手消息、落库完成后显示（流式占位消息没有用量）
                    if (!isUser && !isStreaming) {
                        TokenUsageText(
                            promptTokens = message.meta.promptTokens,
                            completionTokens = message.meta.completionTokens,
                            cachedTokens = message.meta.cachedTokens
                        )
                    }
                }

                // Transparent overlay to capture touch events (on top of message)
                // Disabled in select mode to allow SelectionContainer to receive touch events
                if (!isStreaming && !isSelectMode) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .zIndex(1f)
                            .combinedClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onLongClick = {
                                    showContextMenu = true
                                },
                                onClick = {
                                    if (!isUser) {
                                        showActionBar = !showActionBar
                                    }
                                }
                            )
                    )
                }

                // Context menu
                DropdownMenu(
                    expanded = showContextMenu,
                    onDismissRequest = { showContextMenu = false }
                ) {
                    // Reply option (for both user and AI messages)
                    if (onReply != null) {
                        DropdownMenuItem(
                            text = { Text("回复") },
                            onClick = {
                                showContextMenu = false
                                onReply(message)
                            },
                            leadingIcon = { Icon(Icons.Default.Reply, contentDescription = null) }
                        )
                    }
                    if (isUser) {
                        // User message: Edit + Copy
                        if (onEdit != null) {
                            DropdownMenuItem(
                                text = { Text("编辑") },
                                onClick = {
                                    showContextMenu = false
                                    editingText = message.fullText
                                    showEditDialog = true
                                },
                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("复制") },
                            onClick = {
                                showContextMenu = false
                                copyToClipboard(message.fullText)
                            },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) }
                        )
                    } else {
                        // AI message: Copy All + Free Select
                        DropdownMenuItem(
                            text = { Text("复制全部") },
                            onClick = {
                                showContextMenu = false
                                copyToClipboard(stripMarkdownForSelection(displayContent))
                            },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text("自由选择复制") },
                            onClick = {
                                showContextMenu = false
                                isSelectMode = true
                                showActionBar = false
                            },
                            leadingIcon = { Icon(Icons.Default.Check, contentDescription = null) }
                        )
                        // 换模型重试：只挂在末条助手消息（历史中间消息语义混乱），页面层弹像素模型选择框
                        if (isLastAssistant && onRetryWithModel != null) {
                            DropdownMenuItem(
                                text = { Text("换模型重试") },
                                onClick = {
                                    showContextMenu = false
                                    onRetryWithModel()
                                },
                                leadingIcon = { Icon(Icons.Default.SwapHoriz, contentDescription = null) }
                            )
                        }
                    }
                }
            }
        }

        // 截断消息的「继续生成」快捷按钮：末条助手消息且正文以截断标记结尾时，
        // 直接摆在气泡下方，省去"点气泡→操作条→继续"两跳；
        // 生成中 ChatScreen 不再传 onContinue（isLastAssistant=false），按钮自然隐藏
        if (!isUser && isLastAssistant && !isStreaming && !isSelectMode &&
            onContinue != null && isTruncatedMessage(message.fullText)
        ) {
            PixelButton(
                text = "继续生成",
                onClick = { onContinue() },
                modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                textStyle = MaterialTheme.typography.labelSmall
            )
        }

        // AI Action Bar - appears below AI messages when clicked
        if (!isUser && !isStreaming && !isSelectMode && displayContent.isNotEmpty()) {
            AnimatedVisibility(
                visible = showActionBar,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Row(
                    modifier = Modifier
                        .padding(top = 4.dp, start = 4.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                        .border(1.dp, WoodBorder)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Copy button
                    ActionButton(
                        icon = Icons.Default.ContentCopy,
                        label = "复制",
                        onClick = {
                            copyToClipboard(stripMarkdownForSelection(displayContent))
                            showActionBar = false
                        }
                    )

                    // Regenerate button (only for last assistant message)
                    if (isLastAssistant && onRegenerate != null) {
                        ActionButton(
                            icon = Icons.Default.Refresh,
                            label = "重新生成",
                            onClick = {
                                onRegenerate()
                                showActionBar = false
                            }
                        )
                    }

                    // Continue button (only for last assistant message)
                    if (isLastAssistant && onContinue != null) {
                        ActionButton(
                            icon = Icons.AutoMirrored.Filled.ShortText,
                            label = "继续",
                            onClick = {
                                onContinue()
                                showActionBar = false
                            }
                        )
                    }
                }
            }
        }
        } // Column(weight(1f))：名字+气泡列
    } // Row(Top)：头像 + 内容列
} // 外层 Column
}

/**
 * 工具调用卡片（PixelButton 风格小卡）：图标+工具名+参数摘要一行，点击展开
 * 输出；右侧状态字三态配色——运行中=主题色 / 完成=GrassGreen / 出错=error /
 * 已取消=次要色。输出超长封顶 12 行防长结果撑爆气泡。
 */
@Composable
private fun ToolCallCard(tool: ToolBlock) {
    var expanded by remember(tool) { mutableStateOf(false) }
    val (stateLabel, stateColor) = when (tool.state) {
        ToolBlockState.RUNNING -> "运行中" to MaterialTheme.colorScheme.primary
        ToolBlockState.DONE -> "完成" to GrassGreen
        ToolBlockState.ERROR -> "出错" to MaterialTheme.colorScheme.error
        ToolBlockState.CANCELLED -> "已取消" to MaterialTheme.colorScheme.onSurfaceVariant
            .copy(alpha = pixelSecondaryAlpha())
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .border(1.dp, WoodBorder)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { if (tool.output != null) expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Build,
                contentDescription = null, // 装饰性：旁有工具名文字
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.size(6.dp))
            Text(
                text = tool.name,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.primary
            )
            if (tool.argsSummary.isNotEmpty()) {
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = tool.argsSummary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = pixelSecondaryAlpha()),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = stateLabel,
                style = MaterialTheme.typography.labelSmall,
                color = stateColor
            )
        }
        AnimatedVisibility(visible = expanded) {
            tool.output?.let { output ->
                Column {
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(
                        text = output,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 12,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/**
 * 助手气泡底部的 Token 用量小字：`↑提问 ↓回答 · 缓存 N`。
 * 三者全为 null 时不渲染占位；只有部分有值就只显示有的。
 * 像素等宽小字、次要色，不抢正文视线。
 */
@Composable
private fun TokenUsageText(
    promptTokens: Int?,
    completionTokens: Int?,
    cachedTokens: Int?
) {
    val text = buildString {
        val parts = mutableListOf<String>()
        promptTokens?.let { parts.add("↑$it") }
        completionTokens?.let { parts.add("↓$it") }
        if (parts.isNotEmpty()) append(parts.joinToString(" "))
        cachedTokens?.let {
            if (isNotEmpty()) append(" · ")
            append("缓存 $it")
        }
    }
    if (text.isNotEmpty()) {
        Spacer(modifier = Modifier.size(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = pixelSecondaryAlpha())
        )
    }
}

@Composable
private fun ActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.size(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 12.sp
        )
    }
}

// ---- Markdown/LaTeX 渲染常量（审计 P1-5）----
// 顶层化前这些 Regex 在 normalizeMarkdown/latexToUnicode 每次调用时重新编译、
// 两张映射表每次重建（48+70 项、约 118 次 replace）——流式期间每帧对全量正文
// 裸跑是长回答卡顿的根因。提为文件顶层 val 后只构建一次（Regex 线程安全，
// JVM 类初始化保证并发可见）。映射表为 internal 供 MarkdownPerfTest 引用相等断言。

/** 块级 LaTeX `$$...$$` */
private val BLOCK_LATEX_REGEX = Regex("""\$\$([\s\S]+?)\$\$""")

/** 行内 LaTeX `$...$`（负向环视防 `$$` 误配） */
private val INLINE_LATEX_REGEX = Regex("""(?<!\$)\$(?!\$)([^\$\n]+?)(?<!\$)\$(?!\$)""")

/** `**粗体**` → `<b>` */
private val BOLD_ASTERISK_REGEX = Regex("""\*\*(.+?)\*\*""")

/** `__粗体__` → `<b>` */
private val BOLD_UNDERSCORE_REGEX = Regex("""__(.+?)__""")

/**
 * Normalize markdown for display:
 * - Convert **text** to <b>text</b> for reliable bold rendering
 * - Handle LaTeX formulas: $...$ and $$...$$ -> convert to Unicode math symbols
 * - Strip zero-width characters
 * 思考标签剥除已删除（P3 Task 8）：思考拆分唯一实现在 MessageRenderModel，
 * 到达此处的正文已无思考标签。
 */
private fun normalizeMarkdown(text: String): String {
    var result = text

    // Remove zero-width characters
    result = result.replace("\u200B", "").replace("\u200C", "").replace("\uFEFF", "")

    // Handle block LaTeX $$...$$ -> convert to Unicode
    result = result.replace(BLOCK_LATEX_REGEX) { match ->
        val formula = match.groupValues[1].trim()
        latexToUnicode(formula)
    }

    // Handle inline LaTeX $...$ -> convert to Unicode
    result = result.replace(INLINE_LATEX_REGEX) { match ->
        val formula = match.groupValues[1]
        latexToUnicode(formula)
    }

    // Convert **bold** to <b>bold</b>
    result = result.replace(BOLD_ASTERISK_REGEX) { match ->
        "<b>${match.groupValues[1]}</b>"
    }

    // Convert __bold__ to <b>
    result = result.replace(BOLD_UNDERSCORE_REGEX) { match ->
        "<b>${match.groupValues[1]}</b>"
    }

    return result
}

/** LaTeX 希腊字母映射（internal 供 MarkdownPerfTest 引用相等断言——审计 P1-5 防回退） */
internal val LATEX_GREEK_LETTERS: Map<String, String> = mapOf(
    "\\alpha" to "α", "\\beta" to "β", "\\gamma" to "γ", "\\delta" to "δ",
    "\\epsilon" to "ε", "\\zeta" to "ζ", "\\eta" to "η", "\\theta" to "θ",
    "\\iota" to "ι", "\\kappa" to "κ", "\\lambda" to "λ", "\\mu" to "μ",
    "\\nu" to "ν", "\\xi" to "ξ", "\\omicron" to "ο", "\\pi" to "π",
    "\\rho" to "ρ", "\\sigma" to "σ", "\\tau" to "τ", "\\upsilon" to "υ",
    "\\phi" to "φ", "\\chi" to "χ", "\\psi" to "ψ", "\\omega" to "ω",
    "\\Alpha" to "Α", "\\Beta" to "Β", "\\Gamma" to "Γ", "\\Delta" to "Δ",
    "\\Epsilon" to "Ε", "\\Zeta" to "Ζ", "\\Eta" to "Η", "\\Theta" to "Θ",
    "\\Iota" to "Ι", "\\Kappa" to "Κ", "\\Lambda" to "Λ", "\\Mu" to "Μ",
    "\\Nu" to "Ν", "\\Xi" to "Ξ", "\\Omicron" to "Ο", "\\Pi" to "Π",
    "\\Rho" to "Ρ", "\\Sigma" to "Σ", "\\Tau" to "Τ", "\\Upsilon" to "Υ",
    "\\Phi" to "Φ", "\\Chi" to "Χ", "\\Psi" to "Ψ", "\\Omega" to "Ω"
)

/** LaTeX 数学算符/符号映射（internal 供 MarkdownPerfTest 引用相等断言——审计 P1-5 防回退） */
internal val LATEX_MATH_SYMBOLS: Map<String, String> = mapOf(
    "\\times" to "×", "\\div" to "÷", "\\pm" to "±", "\\mp" to "∓",
    "\\cdot" to "·", "\\ast" to "∗", "\\star" to "★",
    "\\leq" to "≤", "\\geq" to "≥", "\\neq" to "≠", "\\approx" to "≈",
    "\\equiv" to "≡", "\\sim" to "∼", "\\propto" to "∝",
    "\\in" to "∈", "\\notin" to "∉", "\\subset" to "⊂", "\\supset" to "⊃",
    "\\subseteq" to "⊆", "\\supseteq" to "⊇", "\\cup" to "∪", "\\cap" to "∩",
    "\\emptyset" to "∅", "\\infty" to "∞",
    "\\sum" to "Σ", "\\prod" to "Π", "\\int" to "∫", "\\oint" to "∮",
    "\\partial" to "∂", "\\nabla" to "∇", "\\forall" to "∀", "\\exists" to "∃",
    "\\rightarrow" to "→", "\\leftarrow" to "←", "\\Rightarrow" to "⇒",
    "\\Leftarrow" to "⇐", "\\leftrightarrow" to "↔", "\\Leftrightarrow" to "⇔",
    "\\therefore" to "∴", "\\because" to "∵",
    "\\sqrt" to "√", "\\cbrt" to "∛",
    "\\degree" to "°", "\\percent" to "%", "\\permil" to "‰",
    "\\prime" to "′", "\\doubleprime" to "″",
    "\\angle" to "∠", "\\perp" to "⊥", "\\parallel" to "∥",
    "\\triangle" to "△", "\\square" to "□", "\\circ" to "○",
    "\\bullet" to "•", "\\diamond" to "◇",
    "\\hbar" to "ℏ", "\\ell" to "ℓ",
    "\\Re" to "ℜ", "\\Im" to "ℑ",
    "\\aleph" to "ℵ", "\\wp" to "℘",
    "\\land" to "∧", "\\lor" to "∨", "\\neg" to "¬",
    "\\oplus" to "⊕", "\\otimes" to "⊗", "\\odot" to "⊙",
    "\\le" to "≤", "\\ge" to "≥", "\\ne" to "≠"
)

/** `\frac{a}{b}` → `a/b` */
private val FRAC_REGEX = Regex("""\\frac\{([^}]*)\}\{([^}]*)\}""")

/** `\sqrt{x}` → `√x` */
private val SQRT_BRACE_REGEX = Regex("""\\sqrt\{([^}]*)\}""")

/** 上标 `^{...}` */
private val SUPERSCRIPT_BRACE_REGEX = Regex("""\^\{([^}]*)\}""")

/** 上标单字符 `^x`（数字/四则/括号/等号） */
private val SUPERSCRIPT_CHAR_REGEX = Regex("""\^([0-9+\-()=])""")

/** 下标 `_{...}` */
private val SUBSCRIPT_BRACE_REGEX = Regex("""_\{([^}]*)\}""")

/** 下标单字符 `_x`（数字/四则/括号/等号） */
private val SUBSCRIPT_CHAR_REGEX = Regex("""_([0-9+\-()=])""")

/**
 * Convert LaTeX math notation to Unicode mathematical symbols
 */
private fun latexToUnicode(latex: String): String {
    var result = latex

    // Apply replacements
    for ((latexSymbol, unicode) in LATEX_GREEK_LETTERS) {
        result = result.replace(latexSymbol, unicode)
    }
    for ((latexSymbol, unicode) in LATEX_MATH_SYMBOLS) {
        result = result.replace(latexSymbol, unicode)
    }

    // Handle \frac{a}{b} -> a/b
    result = result.replace(FRAC_REGEX) { match ->
        val num = match.groupValues[1]
        val den = match.groupValues[2]
        "$num/$den"
    }

    // Handle \sqrt{x} -> √x
    result = result.replace(SQRT_BRACE_REGEX) { match ->
        "√${match.groupValues[1]}"
    }

    // Handle superscripts ^{...} or ^x
    result = result.replace(SUPERSCRIPT_BRACE_REGEX) { match ->
        toSuperscript(match.groupValues[1])
    }
    result = result.replace(SUPERSCRIPT_CHAR_REGEX) { match ->
        toSuperscript(match.groupValues[1])
    }

    // Handle subscripts _{...} or _x
    result = result.replace(SUBSCRIPT_BRACE_REGEX) { match ->
        toSubscript(match.groupValues[1])
    }
    result = result.replace(SUBSCRIPT_CHAR_REGEX) { match ->
        toSubscript(match.groupValues[1])
    }

    // Clean up remaining backslashes and braces
    result = result.replace("\\", "").replace("{", "").replace("}", "")

    return result
}

/**
 * Convert text to superscript Unicode characters
 */
private fun toSuperscript(text: String): String {
    val superscriptMap = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
        '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
        '+' to '⁺', '-' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾',
        'a' to 'ᵃ', 'b' to 'ᵇ', 'c' to 'ᶜ', 'd' to 'ᵈ', 'e' to 'ᵉ',
        'f' to 'ᶠ', 'g' to 'ᵍ', 'h' to 'ʰ', 'i' to 'ⁱ', 'j' to 'ʲ',
        'k' to 'ᵏ', 'l' to 'ˡ', 'm' to 'ᵐ', 'n' to 'ⁿ', 'o' to 'ᵒ',
        'p' to 'ᵖ', 'r' to 'ʳ', 's' to 'ˢ', 't' to 'ᵗ', 'u' to 'ᵘ',
        'v' to 'ᵛ', 'w' to 'ʷ', 'x' to 'ˣ', 'y' to 'ʸ', 'z' to 'ᶻ',
        'A' to 'ᴬ', 'B' to 'ᴮ', 'C' to 'ᶜ', 'D' to 'ᴰ', 'E' to 'ᴱ',
        'F' to 'ᶠ', 'G' to 'ᴳ', 'H' to 'ᴴ', 'I' to 'ᴵ', 'J' to 'ᴶ',
        'K' to 'ᴷ', 'L' to 'ᴸ', 'M' to 'ᴹ', 'N' to 'ᴺ', 'O' to 'ᴼ',
        'P' to 'ᴾ', 'R' to 'ᴿ', 'S' to 'ˢ', 'T' to 'ᵀ', 'U' to 'ᵁ',
        'V' to 'ⱽ', 'W' to 'ᵂ', 'X' to 'ˣ', 'Y' to 'ʸ', 'Z' to 'ᶻ'
    )
    return text.map { superscriptMap[it] ?: it }.joinToString("")
}

/**
 * Convert text to subscript Unicode characters
 */
private fun toSubscript(text: String): String {
    val subscriptMap = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄',
        '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
        '+' to '₊', '-' to '₋', '=' to '₌', '(' to '₍', ')' to '₎',
        'a' to 'ₐ', 'e' to 'ₑ', 'h' to 'ₕ', 'i' to 'ᵢ', 'j' to 'ⱼ',
        'k' to 'ₖ', 'l' to 'ₗ', 'm' to 'ₘ', 'n' to 'ₙ', 'o' to 'ₒ',
        'p' to 'ₚ', 'r' to 'ᵣ', 's' to 'ₛ', 't' to 'ₜ', 'u' to 'ᵤ',
        'v' to 'ᵥ', 'x' to 'ₓ'
    )
    return text.map { subscriptMap[it] ?: it }.joinToString("")
}

/**
 * Strip markdown formatting for plain text selection/copy
 * Also converts LaTeX to Unicode math symbols
 * 思考标签剥除已删除（P3 Task 8）：入参是渲染模型的正文（已拆思考），
 * 到达此处不再含思考标签。
 */
private fun stripMarkdownForSelection(text: String): String {
    var result = text

    // Convert LaTeX to Unicode (same as display)
    result = result.replace(Regex("""\$\$([\s\S]+?)\$\$""")) { match ->
        latexToUnicode(match.groupValues[1].trim())
    }
    result = result.replace(Regex("""(?<!\$)\$(?!\$)([^\$\n]+?)(?<!\$)\$(?!\$)""")) { match ->
        latexToUnicode(match.groupValues[1])
    }

    // Remove ** markers
    result = result.replace(Regex("""\*\*(.+?)\*\*""")) { it.groupValues[1] }
    result = result.replace(Regex("""__(.+?)__""")) { it.groupValues[1] }

    // Remove * italic markers
    result = result.replace(Regex("""\*(.+?)\*""")) { it.groupValues[1] }
    result = result.replace(Regex("""_(.+?)_""")) { it.groupValues[1] }

    // Remove ` code markers
    result = result.replace(Regex("""`(.+?)`""")) { it.groupValues[1] }

    return result
}

@Composable
private fun parseColorHex(hex: String?): Color {
    if (hex.isNullOrBlank()) return MaterialTheme.colorScheme.primary
    return try {
        val cleanHex = hex.removePrefix("#")
        val color = cleanHex.toLong(16)
        if (cleanHex.length == 6) {
            Color(0xFF000000 or color)
        } else {
            Color(color)
        }
    } catch (_: Exception) {
        MaterialTheme.colorScheme.primary
    }
}
