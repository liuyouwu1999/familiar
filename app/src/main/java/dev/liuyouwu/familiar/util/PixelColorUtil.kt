package dev.liuyouwu.familiar.util

import androidx.compose.ui.graphics.Color

/**
 * "#RRGGBB" 十六进制颜色解析（审计 P3：聊天列表/聊天室创建/编辑三份相同实现收编；
 * MessageBubble 的版本因回退色为 colorScheme.primary 且需可空处理，保持独立）。
 */
fun parseColorHex(hex: String): Color {
    return try {
        val clean = hex.removePrefix("#")
        Color(("FF$clean").toLong(16))
    } catch (_: Exception) {
        Color(0xFF1976D2)
    }
}
