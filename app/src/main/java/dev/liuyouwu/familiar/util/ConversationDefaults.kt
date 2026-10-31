package dev.liuyouwu.familiar.util

/**
 * 会话默认标题唯一来源（审计 P3：原先创建端与自动命名守卫分处两个文件，
 * 靠字面量"恰好相等"维系，改任一处自动命名就静默失效）。
 */
object ConversationDefaults {
    const val NEW_CHAT = "新对话"
    const val CHAT_ROOM = "聊天室"
}
