package dev.liuyouwu.familiar.ui.component

import dev.liuyouwu.familiar.util.isEmbeddingModel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 嵌入（embedding）模型判定已下沉 util（isEmbeddingModel），UI/VM 共用同一口径 */

/**
 * 角色绑定的模型选择下拉框。聊天室每个参与者可以用不同模型；
 * selected 为空表示跟随全局模型。
 */
@Composable
fun ModelDropdownField(
    selected: String,
    models: List<String>,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    // 过滤嵌入模型；过滤后为空则显示全部
    val selectableModels = models.filter { !isEmbeddingModel(it) }.ifEmpty { models }
    Column {
        Text("模型", style = MaterialTheme.typography.labelSmall)
        Spacer(modifier = Modifier.height(4.dp))
        Box {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = true },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                )
            ) {
                Text(
                    text = selected.ifBlank { "跟随全局模型" },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.width(280.dp)
            ) {
                if (selectableModels.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("还没连接服务器，没有可用模型") },
                        enabled = false,
                        onClick = {}
                    )
                } else {
                    if (selected.isNotBlank()) {
                        DropdownMenuItem(
                            text = { Text("跟随全局模型", style = MaterialTheme.typography.bodySmall) },
                            onClick = {
                                onSelected("")
                                expanded = false
                            },
                            modifier = Modifier.heightIn(min = 48.dp)
                        )
                    }
                    selectableModels.forEach { model ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    model,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            onClick = {
                                onSelected(model)
                                expanded = false
                            },
                            modifier = Modifier.heightIn(min = 48.dp)
                        )
                    }
                }
            }
        }
    }
}
