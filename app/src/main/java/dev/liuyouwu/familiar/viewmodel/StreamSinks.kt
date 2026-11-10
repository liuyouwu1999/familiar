package dev.liuyouwu.familiar.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 流式状态分组（审计 A3）：ChatScreen 以 combine 四流合一收集，消除逐流 collect
 * 的重复订阅。落位 viewmodel/StreamSinks.kt（与 T3.6 的 StreamingSink 同址）——
 * T3.6 已把合并下沉 VM 侧（ChatViewModel.streamingState 直接产出本类型）。
 */
data class StreamingState(
    val isStreaming: Boolean,
    val text: String,
    val reasoning: String,
    val toolStatus: String?,
)

/**
 * 生成用量分组（审计 A3）：顶栏速率/Token 计数的四流合一。
 * nullable 语义 = "本回合未上报/为 0" 视为缺省（合并处 takeIf{>0} 过滤，
 * 与拆分前各 UI 位 `> 0` 判定同口径）。
 *
 * 字段语义（终审 Minor-4，消费点 ChatScreen 顶栏同口径）：promptTokens =
 * 上下文总占用（请求侧全量上下文 token 数，非本回合新增；空闲态显示为
 * 「上下文 N tokens」）；completionTokens = 本回合生成数（思考段计入，
 * 流式态显示为「N tokens · X t/s」）；cachedTokens = 最近一次请求的缓存
 * 命中率（百分比）；tps = 本回合生成速率（tokens/s）。
 */
data class StreamStats(
    val tps: Double?,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val cachedTokens: Int?,
)

// ===== 审计 A5：AgentSessionController 的 UI 状态回写通道 Sink 化 =====
// 构造注入的 13 个 MutableStateFlow 按写点域归并为三接口（流式呈现 6 / 用量 4 /
// 对话交互 3）：controller 只认域不认单流，ChatViewModel 持有全部流并以 object
// 实现三接口，写/读语义逐位等价。接口暴露 MutableStateFlow 是有意的内部缝设计：
// controller 是各域的主写者（autoRead 只读走独立 StateFlow 参数），例外见各接口
// KDoc——DialogSink.error 由 VM 自身/VoiceController/ExportController 共写，
// StreamingSink.familiarReveal 同源写入另有 VoiceController（终审 Minor-6）；
// 逐写点具名的方法式封装只会得到 1:1 setter 而放大漂移面——等价搬运纪律优先。

/**
 * 流式呈现域（6）：链忙态 + 流式正文/思考段 + familiar 拟人节奏（打字指示/曝光态）
 * + 搜索状态文案。写点全在 [AgentSessionController]：attachSession 镜像帧/卸载清镜像、
 * startAgentChain/resetAgentStreamState/resetStreamState 收线复位、五入口忙态置位。
 * VM 侧把前四路（含 controller 派生的工具进度文案流）聚合成 [StreamingState]
 * 供 ChatScreen 单流收集；familiarReveal 同源写入另有 VoiceController（TTS 曝光推进）。
 */
internal interface StreamingSink {
    val isLoading: MutableStateFlow<Boolean>
    val streamingText: MutableStateFlow<String>
    val streamingReasoning: MutableStateFlow<String>

    /** familiar 活跃接收中的打字指示（精灵名；null = 非熟悉会话或未在接收） */
    val familiarTypingName: MutableStateFlow<String?>

    /** familiar 分段曝光态（id → 已曝光段数；null = 无进行中的分段动画） */
    val familiarReveal: MutableStateFlow<Pair<Long, Int>?>

    /** 搜索状态文案（旧链残留通道，现恒 null；resetStreamState 收线清） */
    val searchStatus: MutableStateFlow<String?>
}

/**
 * 生成用量域（4）：[StreamStats] 四源。写点全在 [AgentSessionController]：
 * startAgentChain 链起点清零、镜像帧 token 插值、收线精确值覆盖、attachSession
 * 的上下文占用回填/跨会话清零。
 */
internal interface StatsSink {
    val tokenCount: MutableStateFlow<Int>
    val tokensPerSecond: MutableStateFlow<Double>

    /** 最近一次请求的缓存命中率（百分比 0-100）；后端不支持用量统计时 null */
    val lastCacheHit: MutableStateFlow<Int?>

    /** 收线后的真实上下文占用（prompt+completion，token）；0=本会话尚无已上报用量 */
    val lastContextTokens: MutableStateFlow<Int>
}

/**
 * 对话交互域（3）：错误反馈 + 引用条 + 附件 chips——[AgentSessionController] 的
 * "捕获-清空"（send/steer/queueFollowUp 消费引用与附件）与错误上报写点。
 * 注（终审 Minor-6）：error 并非 controller 独写——ChatViewModel 自身
 * （fetchModels/clearHistory/reportError 等）与 VoiceController、ExportController
 * 共写同一条流，controller 只是对话域的主写者。
 * 另：ChatScreen 的 6 个对话框均由 UI 本地 rememberSaveable 态驱动，controller
 * 无 show* 触发位——brief 草图的 showPickModel/showEditPrompt 无实际成员，
 * 本接口内容按实际驱动位裁定（task-3.6-brief "…以实际为准"）。
 */
internal interface DialogSink {
    val error: MutableStateFlow<String?>
    val replyingTo: MutableStateFlow<ReplyTarget?>
    val selectedAttachments: MutableStateFlow<List<AttachmentInfo>>
}
