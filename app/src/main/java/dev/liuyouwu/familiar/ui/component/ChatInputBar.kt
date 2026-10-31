package dev.liuyouwu.familiar.ui.component

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.liuyouwu.familiar.ui.theme.PaperLight
import dev.liuyouwu.familiar.ui.theme.PixelIconButton
import dev.liuyouwu.familiar.ui.theme.WoodBorder

/**
 * 输入长度护栏：钳制到 2 万字符以内，防止粘贴超大文本时
 * 输入框组合/测量卡顿甚至 OOM 崩溃（竞品 gpt_mobile #226 同款事故）。
 */
private const val MAX_INPUT_CHARS = 20_000

/** 输入条：木条底 + 奶油输入框 + 像素按钮，与 PixelTopBar 呼应成上下框。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onImageSelected: (Uri) -> Unit,
    onFileSelected: (Uri) -> Unit,
    onVideoSelected: (Uri) -> Unit,
    onCameraCapture: (Uri) -> Unit,
    isSendEnabled: Boolean,  // Only controls send button
    hasAttachments: Boolean = false,
    isVoiceRecording: Boolean = false,
    isTranscribing: Boolean = false,
    onVoiceInput: () -> Unit = {},
    onCancelVoice: () -> Unit = {},
    onSendLongPress: (() -> Unit)? = null,   // 生成中长按发送=排队追问（null=无长按行为）
    inputHint: String = "输入消息…"          // 生成中四类单体显示双语义提示
) {
    var showAttachmentMenu by remember { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onImageSelected(it) }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onFileSelected(it) }
    }

    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onVideoSelected(it) }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        // Camera result is handled via the URI passed to onCameraCapture
    }

    val frameColor = MaterialTheme.colorScheme.secondary
    val edgeColor = if (dev.liuyouwu.familiar.ui.theme.LocalPixelDark.current)
        dev.liuyouwu.familiar.ui.theme.NightBorder else dev.liuyouwu.familiar.ui.theme.WoodDark

    // 顶部深棕压边 + 木条底，形成与顶栏对称的“下框”
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRect(frameColor)
                val e = 3.dp.toPx()
                drawRect(edgeColor, Offset.Zero, Size(size.width, e))
            }
            .padding(vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            // Attachment button - always enabled
            Box {
                PixelIconButton(
                    icon = Icons.Default.Add,
                    contentDescription = "附件",
                    onClick = { showAttachmentMenu = !showAttachmentMenu },
                    tint = PaperLight
                )
                DropdownMenu(
                    expanded = showAttachmentMenu,
                    onDismissRequest = { showAttachmentMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("拍照") },
                        onClick = {
                            showAttachmentMenu = false
                            onCameraCapture(Uri.EMPTY)  // Trigger camera capture
                        },
                        leadingIcon = { Icon(Icons.Default.CameraAlt, contentDescription = null) } // 装饰性：旁有菜单文字
                    )
                    DropdownMenuItem(
                        text = { Text("相册") },
                        onClick = {
                            showAttachmentMenu = false
                            imagePicker.launch("image/*")
                        },
                        leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) } // 装饰性：旁有菜单文字
                    )
                    DropdownMenuItem(
                        text = { Text("文件") },
                        onClick = {
                            showAttachmentMenu = false
                            filePicker.launch("*/*")
                        },
                        leadingIcon = { Icon(Icons.Default.InsertDriveFile, contentDescription = null) } // 装饰性：旁有菜单文字
                    )
                    DropdownMenuItem(
                        text = { Text("视频") },
                        onClick = {
                            showAttachmentMenu = false
                            videoPicker.launch("video/*")
                        },
                        leadingIcon = { Icon(Icons.Default.Videocam, contentDescription = null) } // 装饰性：旁有菜单文字
                    )
                }
            }

            // Text input - always enabled
            OutlinedTextField(
                value = value,
                // 长度护栏：超长粘贴在此截断，超大文本进不了输入框状态
                onValueChange = { onValueChange(it.take(MAX_INPUT_CHARS)) },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
                placeholder = { Text(inputHint) },
                maxLines = 4,
                singleLine = false,
                shape = MaterialTheme.shapes.small,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.inverseSurface,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                )
            )

            // Right button: stop / loading / mic / send
            when {
                isVoiceRecording -> {
                    // 录音中：取消（丢弃录音）+ 停止并发送
                    PixelIconButton(
                        icon = Icons.Default.Close,
                        contentDescription = "取消录音",
                        onClick = onCancelVoice,
                        tint = PaperLight
                    )
                    PixelIconButton(
                        icon = Icons.Default.Stop,
                        contentDescription = "停止并转写",
                        onClick = onVoiceInput,
                        filled = MaterialTheme.colorScheme.error
                    )
                }
                isTranscribing -> {
                    PixelIconButton(
                        icon = Icons.Default.Mic,
                        contentDescription = "识别中…",
                        onClick = {},
                        enabled = false,
                        tint = PaperLight
                    )
                }
                value.isBlank() && !hasAttachments -> {
                    PixelIconButton(
                        icon = Icons.Default.Mic,
                        contentDescription = "语音输入",
                        onClick = onVoiceInput,
                        tint = PaperLight
                    )
                }
                else -> {
                    PixelIconButton(
                        icon = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "发送",
                        onClick = onSend,
                        enabled = isSendEnabled,
                        filled = MaterialTheme.colorScheme.primary,
                        onLongClick = onSendLongPress
                    )
                }
            }
        }
    }
}
