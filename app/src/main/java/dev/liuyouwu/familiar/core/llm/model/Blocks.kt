package dev.liuyouwu.familiar.core.llm.model

sealed interface ContentBlock {
    data class Text(val text: String) : ContentBlock
    data class Thinking(val text: String) : ContentBlock
    data class ToolCall(val id: String, val name: String, val argsJson: String) : ContentBlock
    data class ToolResult(val callId: String, val name: String, val output: String, val isError: Boolean) : ContentBlock
}

enum class Role { USER, ASSISTANT, SYSTEM, TOOL_RESULT }
