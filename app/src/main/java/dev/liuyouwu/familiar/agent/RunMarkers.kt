package dev.liuyouwu.familiar.agent

/**
 * 用户可见标记文案的中立 holder（内核清理 A，Task 4）：六字段由装配层
 * （viewmodel/SkillHost 的 RunMarkerTexts 常量）双路直注 [AgentLoop] 与
 * [AgentSession]——标记文案自此零内嵌字面，用户可见文案（截断/无回答/
 * 工具取消/收尾说明）的唯一存活来源收敛到 app 侧（SkillHost.kt）。
 *
 * 纯 String holder，无行为、无中文字面；字面等价由 RunMarkerTextsSnapshotTest
 * 快照钉死（防「注释约定一字不差」式静默失配，同 util 侧审计 P2-4 的教训）。
 *
 * 字段语义（与迁出前的内核字面一一对应）：
 * - truncationTail：气泡侧判据——正文 trimEnd 后以此结尾即视为被截断；
 * - truncationMarker：写入端追加的完整标记（前导双换行 + tail），构建请求历史前剥掉；
 * - noAnswerTail：模型只输出思考没写回答的可见说明（尾部形态判据）；
 * - noAnswerMarker：写入端追加的完整标记（前导双换行 + tail），构建请求历史前剥掉；
 * - toolCancelled：取消补落的 isError ToolResult 输出字面量（AgentLoop 写，
 *   UI 渲染模型据其判 CANCELLED 态）；
 * - noClosingAnswerNote：收尾 turn 无正文时的用户可见说明（不回灌模型上下文）。
 */
data class RunMarkers(
    val truncationMarker: String,
    val truncationTail: String,
    val noAnswerMarker: String,
    val noAnswerTail: String,
    val toolCancelled: String,
    val noClosingAnswerNote: String,
)
