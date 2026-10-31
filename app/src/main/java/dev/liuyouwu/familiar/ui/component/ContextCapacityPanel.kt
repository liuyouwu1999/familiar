package dev.liuyouwu.familiar.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.liuyouwu.familiar.ui.theme.GrassGreen
import dev.liuyouwu.familiar.ui.theme.SkyBlue
import dev.liuyouwu.familiar.ui.theme.StarGold
import dev.liuyouwu.familiar.ui.theme.WoodBorder
import dev.liuyouwu.familiar.ui.theme.pixelSecondaryAlpha
import dev.liuyouwu.familiar.viewmodel.ContextBreakdown

/**
 * 上下文容量面板（"对话设置"弹窗内）：用量行 + 分段进度条 + 分类明细 + 缓存命中率。
 * 纯展示组件，数据来自 ChatViewModel 的 ContextBreakdown（估算口径与 ContextBuilder 同源）。
 * 分类占比按"占已用"计（与系统裁剪无关，只描述 43 个 token 里消息/提示词各占多少），
 * 回复预留与剩余给 token 量——它们不属于"已用"，占比只会误导。
 */
@Composable
fun ContextCapacityPanel(
    breakdown: ContextBreakdown,
    cacheHitPercent: Int?,
    modifier: Modifier = Modifier
) {
    val max = breakdown.maxContext
    if (max <= 0) return

    // 已用构成：三类合计 100%（used=0 时全部显示 0.0%）
    val usedParts: List<Triple<Color, Int, String>> = listOf(
        Triple(SkyBlue, breakdown.messages, "消息"),
        Triple(GrassGreen, breakdown.attachments, "附件"),
        Triple(StarGold, breakdown.systemPrompt, "系统提示词")
    )
    fun pctOfUsed(tokens: Int): String =
        if (breakdown.used <= 0) "0.0%" else "%.1f%%".format(tokens * 100.0 / breakdown.used)

    Column(modifier = modifier.fillMaxWidth()) {
        // 用量行：已用/最大（百分比），与顶栏副标题同口径
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "${shortTokens(breakdown.used)} / ${shortTokens(max)}（${"%.1f%%".format(breakdown.used * 100.0 / max)}）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = pixelSecondaryAlpha())
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        // 分段进度条：木框描边 + 按"占窗口"的真实比例分配宽度；剩余透出底色
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .border(1.dp, WoodBorder)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                // 剩余段必须入列：weight 按比例瓜分全宽，缺了它预留段会被拉伸侵占剩余空间
                val barSegments = usedParts + Triple(
                    MaterialTheme.colorScheme.primary, breakdown.reserve, "回复预留"
                ) + Triple(MaterialTheme.colorScheme.surfaceVariant, breakdown.free, "剩余")
                barSegments.forEach { (color, tokens, _) ->
                    if (tokens > 0) {
                        Box(
                            modifier = Modifier
                                .weight(tokens.toFloat())
                                .fillMaxHeight()
                                .background(color)
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        // 已用构成明细（占已用百分比）
        usedParts.forEach { (color, tokens, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(8.dp).background(color))
                Spacer(modifier = Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    pctOfUsed(tokens),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = pixelSecondaryAlpha())
                )
            }
        }
        // 回复预留 / 剩余：不属于已用，给 token 量而不是百分比
        legendRow(
            color = MaterialTheme.colorScheme.primary,
            label = "回复预留（留给模型输出的）",
            value = shortTokens(breakdown.reserve)
        )
        legendRow(
            color = MaterialTheme.colorScheme.surfaceVariant,
            label = "剩余可用",
            value = shortTokens(breakdown.free)
        )
        Spacer(modifier = Modifier.height(4.dp))
        // 缓存命中率与顶栏副标题同源（后端 usage / llama.cpp timings），无数据显示占位
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("缓存命中率", style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.weight(1f))
            Text(
                cacheHitPercent?.let { "$it%" } ?: "暂无数据",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = pixelSecondaryAlpha())
            )
        }
    }
}

@Composable
private fun legendRow(color: Color, label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color)
                .border(1.dp, WoodBorder)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
        Spacer(modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = pixelSecondaryAlpha())
        )
    }
}

/** 短格式：万位以上用"万"（与截图一致），千位用 k，其余原样 */
private fun shortTokens(tokens: Int): String = when {
    tokens >= 10000 -> {
        val whole = tokens / 10000
        val remainder = tokens % 10000
        if (remainder == 0) "${whole}万" else "%.1f万".format(tokens / 10000.0)
    }
    tokens >= 1000 -> "%.1fk".format(tokens / 1000.0)
    else -> "$tokens"
}
