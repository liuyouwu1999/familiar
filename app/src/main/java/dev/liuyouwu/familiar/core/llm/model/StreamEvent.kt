package dev.liuyouwu.familiar.core.llm.model

sealed interface StreamEvent {
    data class RunStart(val runId: String) : StreamEvent
    data class TurnStart(val turn: Int) : StreamEvent
    data class BlockStart(val blockId: String, val type: String) : StreamEvent
    data class BlockDelta(val blockId: String, val delta: String) : StreamEvent
    data class BlockEnd(val blockId: String) : StreamEvent
    data class ToolCallDelta(val blockId: String, val name: String?, val argsFragment: String) : StreamEvent
    data class TurnEnd(val toolCalls: List<ContentBlock.ToolCall>, val truncated: Boolean) : StreamEvent
    data class RunEnd(val usage: Usage) : StreamEvent
    data class Error(val error: Throwable) : StreamEvent
}

data class Usage(val promptTokens: Int = 0, val completionTokens: Int = 0, val cachedTokens: Int = 0)
