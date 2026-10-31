package dev.liuyouwu.familiar.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.liuyouwu.familiar.ui.theme.PixelBottomPanel
import dev.liuyouwu.familiar.ui.theme.pixelSecondaryAlpha

/**
 * 像素风模型单选对话框：底部弹出木框面板（PixelBottomPanel），
 * 每行一个模型，等宽字体 + 「■/□」方块选中标记（实心=当前模型）。
 * 列表由调用方给定：聊天页复用设置面板的 availableModels 并沿用 isEmbeddingModel 过滤。
 */
@Composable
fun PixelModelPickerDialog(
    title: String,
    models: List<String>,
    currentModel: String,
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit
) {
    PixelBottomPanel(onDismiss = onDismiss, title = title) {
        if (models.isEmpty()) {
            Text(
                text = "还没连接服务器，没有可用模型",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = pixelSecondaryAlpha())
            )
        } else {
            models.forEach { model ->
                val selected = model == currentModel
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .clickable { onSelected(model) }
                        .padding(horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 像素选中标记：实心方块=当前模型，空心方块=可选项
                    Text(
                        text = if (selected) "■" else "□",
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = pixelSecondaryAlpha())
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = model,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
