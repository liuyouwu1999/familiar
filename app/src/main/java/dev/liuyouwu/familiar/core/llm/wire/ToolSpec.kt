package dev.liuyouwu.familiar.core.llm.wire

/**
 * 工具规格的中间表达：parametersJson 是 JSON Schema 的字符串形（与配置/存储层
 * 打交道的形）。WireConverter.specToWire 嵌入请求体时解析为真 schema 对象。
 */
data class ToolSpec(
    val name: String,
    val description: String,
    val parametersJson: String
)

/**
 * 工具表达模式：NATIVE = 请求体 tools/tool_calls 字段原生往返（需后端支持）；
 * MARKER = 文本标记协议（[搜索: …] 一类，由 MarkerToolAdapter 注入/折叠，任何后端可用）。
 */
enum class ToolMode { NATIVE, MARKER }
