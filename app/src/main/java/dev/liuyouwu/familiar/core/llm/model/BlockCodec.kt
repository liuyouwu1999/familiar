package dev.liuyouwu.familiar.core.llm.model

import com.google.gson.*

object BlockCodec {
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    fun toJson(blocks: List<ContentBlock>): String = gson.toJson(blocks.map { envelope(it) })
    fun fromJson(json: String): List<ContentBlock> =
        gson.fromJson(json, Array<Envelope>::class.java).orEmpty().map { it.toBlock() }

    private data class Envelope(val type: String, val text: String?, val id: String?, val name: String?,
                                 val argsJson: String?, val callId: String?, val output: String?, val isError: Boolean?)

    private fun envelope(b: ContentBlock) = when (b) {
        is ContentBlock.Text -> Envelope("text", b.text, null, null, null, null, null, null)
        is ContentBlock.Thinking -> Envelope("thinking", b.text, null, null, null, null, null, null)
        is ContentBlock.ToolCall -> Envelope("toolCall", null, b.id, b.name, b.argsJson, null, null, null)
        is ContentBlock.ToolResult -> Envelope("toolResult", null, null, b.name, null, b.callId, b.output, b.isError)
    }

    private fun Envelope.toBlock(): ContentBlock = when (type) {
        "text" -> ContentBlock.Text(text.orEmpty())
        "thinking" -> ContentBlock.Thinking(text.orEmpty())
        "toolCall" -> ContentBlock.ToolCall(id.orEmpty(), name.orEmpty(), argsJson.orEmpty())
        "toolResult" -> ContentBlock.ToolResult(callId.orEmpty(), name.orEmpty(), output.orEmpty(), isError ?: false)
        else -> throw JsonParseException("unknown block type: $type")
    }
}
