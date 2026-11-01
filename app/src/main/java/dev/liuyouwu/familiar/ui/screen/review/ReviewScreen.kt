// ReviewScreen.kt
package dev.liuyouwu.familiar.ui.screen.review

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liuyouwu.familiar.ui.theme.PixelIconButton
import dev.liuyouwu.familiar.ui.theme.PixelTopBar

/**
 * 快问快答复习屏（学习画像 Task 10 Step 4，spec §6）：轻量对话流——内存态消息
 * （[ReviewViewModel.messages]，**不落库**）、用户右侧/教练左侧气泡、底部输入行；
 * 收尾 JSON 落账后输入行让位结果卡（对/错清单 + 总计），「完成」退出。
 * 侧信道路径（ReviewChat）在 VM 内驱动，本屏零消息链路依赖。
 */
@Composable
fun ReviewScreen(
    viewModel: ReviewViewModel,
    onBack: () -> Unit,
) {
    val messages = viewModel.messages
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // 新消息到达自动滚底（首条出题也在列表里）
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Scaffold(
        topBar = {
            PixelTopBar(
                title = "快问快答复习",
                navigationIcon = {
                    PixelIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        onClick = onBack,
                    )
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(messages) { msg -> MessageBubble(msg) }
            }

            val result = summary
            if (result == null) {
                // 输入行：完成态让位结果卡
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("答一下…") },
                        enabled = !busy,
                        maxLines = 4,
                    )
                    IconButton(
                        onClick = {
                            viewModel.send(input)
                            input = ""
                        },
                        enabled = !busy && input.isNotBlank(),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                    }
                }
            } else {
                ResultCard(
                    summary = result,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    onDone = onBack,
                )
            }
        }
    }
}

/** 轻量气泡：用户右/教练左，无新依赖（Box+Text）。 */
@Composable
private fun MessageBubble(msg: ReviewViewModel.UiMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (msg.fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(
                    color = if (msg.fromUser) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(10.dp),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = msg.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** 结果卡（收尾 JSON 已落账）：逐条对/错 + 总计 + 完成。 */
@Composable
private fun ResultCard(
    summary: ReviewViewModel.Summary,
    modifier: Modifier = Modifier,
    onDone: () -> Unit,
) {
    Column(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(10.dp),
            )
            .padding(12.dp),
    ) {
        Text(
            text = "本轮复习完成：${summary.passed}/${summary.total} 答对",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Spacer(modifier = Modifier.padding(top = 4.dp))
        summary.items.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (item.pass) "✓" else "✗",
                    color = if (item.pass) dev.liuyouwu.familiar.ui.theme.GrassGreen
                    else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                Text(
                    text = item.point,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDone) { Text("完成") }
        }
    }
}
