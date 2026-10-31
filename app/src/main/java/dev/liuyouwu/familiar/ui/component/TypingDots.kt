package dev.liuyouwu.familiar.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * familiar 接收期间的打字指示：三个像素点依次跳动 + 精灵名。
 * ChatScreen 此前没有任何 typing 指示（流式正文即指示）；familiar 流式抑制后
 * 由它顶上——聚合期间与分段曝光的间隙里都显示这一行。
 */
@Composable
fun TypingDots(text: String, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "typingDots")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "typingPhase"
    )
    Row(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(6.dp))
        // 方块点（像素风）：当前相位点亮并放大，其余压暗缩小
        repeat(3) { i ->
            val active = phase.toInt() % 3 == i
            Spacer(modifier = Modifier.width(2.dp))
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .size(if (active) 7.dp else 4.dp)
                    .background(
                        if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                    )
            )
        }
    }
}
