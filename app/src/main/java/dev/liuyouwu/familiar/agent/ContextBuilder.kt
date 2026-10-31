package dev.liuyouwu.familiar.agent

import dev.liuyouwu.familiar.core.llm.model.AgentMessage
import dev.liuyouwu.familiar.core.llm.model.ContentBlock
import dev.liuyouwu.familiar.core.llm.model.Role
import dev.liuyouwu.familiar.core.llm.wire.ToolSpec

/**
 * build 的产物：裁剪后的请求上下文。droppedCount = 因预算被挤出的消息数
 * （kept 之外的原始 messages 条数），容量面板与调试展示用。
 */
data class BuiltContext(
    val system: String,
    val kept: List<AgentMessage>,
    val droppedCount: Int,
)

/**
 * Agent 侧上下文构建器（spec §5.2，评审四 P1：历史裁剪与 KV 缓存锚点的唯一去处）。
 *
 * 语义自 util/ContextTrimmer.trim 迁入，移植范围按计划圈定（评审二 P1-4）：
 * - budget = (maxContext - reserveForOutput - estimate(system))，[MIN_BUDGET]=256 下限
 *   （源：ContextTrimmer.kt:162-163，system 计费）；
 * - 从尾贪心保留：保留能装进预算的最长后缀（最新优先），最新一条永不单独丢
 *   （源 trim 末段"从最老丢弃直到装下 + 至少留最新一条"的兜底）；
 * - 锚点前缀稳定：纯函数 + 从尾贪心 ⇒ 历史只在尾部追加时，kept 起点（裁剪边界）
 *   单调前移，跨轮前缀字节稳定——这是无状态形态的锚点语义（P5 尾部附注的预算
 *   扣减随附注体积逐请求变化，kept 起点可小幅浮动——浮动只在历史区，system
 *   锚点不动，见 [appendTrailingUserNote]）。
 *
 * 【明确不移植（P3 接线时对照真实 trim 校准，已记推迟项）】
 * - 90%/60% 比例触发带与滞后滑动（TRIGGER_RATIO/TRIM_TARGET_RATIO）；
 * - 被挤出消息的占位符降级（degradeStable）与 MAX_PLACEHOLDERS 上限；
 * - 图片/视频帧/文件的附件 token 计费（TOKENS_PER_IMAGE/FRAME）——estimate 只收
 *   文本口径，附件计费随占位符降级一并推迟；
 * - anchorKey 有状态跨轮锚点缓存（上面无状态锚点语义已覆盖 P1 需求）。
 *
 * P5 修订：estimateTokens 已迁入（companion，公式逐字符复制，ContextTrimmer
 * 随之删除）；其余不移植项随源文件消亡，语义归本类的从尾贪心 + 无状态锚点。
 */
class ContextBuilder(
    system: String,
    val messages: List<AgentMessage>,
    tools: List<ToolSpec>,
    /**
     * 预裁专用装配（P5 审查修复）：true = 本 build 产物只用于预算裁剪（Session
     * 预裁），不上 wire——wire 装配由 AgentLoop 逐 turn 的 request() 经 build()
     * 完成。一次性 hooks 状态（如课堂续讲旗标）**不得在 trimOnly 装配消费**：
     * session 侧 transformContext 先于 loop 侧执行，在其上消费会让 wire 侧无法
     * 重建该状态（P3/P4 的续讲指令正是在此被静默丢弃）。注入照常进行——预裁
     * 预算须与 wire 同口径计费。
     */
    val trimOnly: Boolean = false,
) {
    private val systemBuf = StringBuilder(system)

    /** 尾部附注池（appendTrailingUserNote 收集，build 时物化为末条 user 消息）。 */
    private val trailingNotes = mutableListOf<String>()

    /** 当前工具清单；setTools 整体替换（收尾护栏置空等），只经该口变更。 */
    var tools: List<ToolSpec> = tools
        private set

    /**
     * 追加一条尾部用户附注（P5：appendSystem 缓存 miss 修复）。附注不进 system，
     * 而是 [build] 产出的历史**末尾**追加一条临时 user 消息（attachTimeContext 式：
     * 逐请求变化的内容写进 system 每请求打破一次 KV 前缀缓存；尾部本就是新增字节，
     * 零缓存损失）。与 [appendSystem] 的分工：**稳定**内容（收尾护栏等）走
     * appendSystem；**逐请求变化**的内容（familiar 记忆尾、课堂 TS 状态段、续讲
     * 指令）一律走此口。
     *
     * 仅请求装配层：附注只存在于 build 产物（kept 末位），不落库——Session 落库
     * 只收 loop 产出（persistProduced），store 历史不含此消息。空段忽略；多次
     * 调用并入同一条附注消息（段间空行，形态对齐 appendSystem 的段间分隔）。
     *
     * 附注**占裁剪预算**：build 对 estimate(附注) 作预算扣减（附注本身不进 trim
     * 循环——裁剪只作用于历史），口径对齐旧链（7753c8f 删除前 ChatViewModel:
     * 1527-1537：budgetPrompt 含记忆尾/进度块显式计入 trim 的 systemPrompt 扣减，
     * "进度块/记忆尾部虽注入尾部，token 仍计入预算扣减"）——免费会让本地小窗
     * 模型超窗（reserveForOutput 硬契约防的正是这个）。预算收紧时 kept 较无注入
     * build 少留历史；附注体积逐请求变化令 kept 起点小幅浮动，缓存影响仅在历史
     * 区，system 锚点不动。
     *
     * build() 会剥除输入中已物化的附注消息（loop 侧 ctx 以 Session 预裁产物为
     * 输入，重新装配时本轮附注取代上一轮的——不堆积、不重复计费）。
     */
    fun appendTrailingUserNote(note: String) {
        if (note.isEmpty()) return
        trailingNotes.add(note)
    }

    /** 尾部附注物化为临时 user 消息（id 见 [TRAILING_NOTE_ID]，单 Text 块）。 */
    private fun noteMessage(text: String): AgentMessage =
        AgentMessage(id = TRAILING_NOTE_ID, role = Role.USER, blocks = listOf(ContentBlock.Text(text)))

    /**
     * 追加一段系统提示词（收尾护栏"工具预算已尽"等）。空段忽略。
     *
     * **只装稳定内容**（P5 起）：本口写进 system——跨轮逐字节稳定是 KV 前缀缓存
     * 的锚点前提，逐请求变化的内容（记忆尾/TS 状态/续讲指令）走
     * [appendTrailingUserNote]。AgentLoop 的 CLOSING_GUARD 是唯一运行期
     * appendSystem 调用方：每 run 至多一次、只出现在收尾 turn 的请求上（随 run
     * 结束消失，无"逐请求变化"），代价仅为收尾那一个请求的缓存 miss——可接受，
     * 且护栏必须以系统级指令收束（tools 已置空的无工具 turn）。
     */
    fun appendSystem(section: String) {
        if (section.isEmpty()) return
        if (systemBuf.isNotEmpty()) systemBuf.append("\n\n")
        systemBuf.append(section)
    }

    fun setTools(list: List<ToolSpec>) {
        tools = list
    }

    /** 当前系统提示词全文（追加段之间以空行分隔，字节确定）。 */
    val system: String
        get() = systemBuf.toString()

    /**
     * 按预算裁剪历史。[estimate] 为 token 估算（同一口径用于 system 与消息文本，
     * 调用方传 [estimateTokens]——本类自有实现的同源口径），reserveForOutput 应传
     * [reserveForOutput]（与本地后端 max_tokens 同源）。
     */
    fun build(
        maxContext: Int,
        reserveForOutput: Int,
        estimate: (String) -> Int,
    ): BuiltContext {
        // 输入中若已含上一装配轮物化的附注（loop 侧重新装配形态），一律剥除：
        // 由本轮附注取代——不进 trim、不重复计费、不堆积（见 appendTrailingUserNote KDoc）
        val real = if (messages.any { it.id == TRAILING_NOTE_ID }) {
            messages.filter { it.id != TRAILING_NOTE_ID }
        } else messages
        val noteText = trailingNotes.joinToString("\n\n").ifEmpty { null }
        // maxContext <= 0：无预算概念，原样返回（源 trim 的早退分支）；尾部附注照常注入
        // （注入与裁剪正交——无预算用户的 hooks 注入不得静默消失）
        if (maxContext <= 0 || real.isEmpty()) {
            return BuiltContext(system, if (noteText == null) real else real + noteMessage(noteText), 0)
        }
        // 附注计费（旧链同口径）：占预算扣减但不进 trim 循环（裁剪只作用于历史）
        val noteCost = noteText?.let(estimate) ?: 0
        val budget = (maxContext - reserveForOutput - estimate(system) - noteCost)
            .coerceAtLeast(MIN_BUDGET)

        // 从尾贪心：装下最新 i..end 的最长后缀；最新一条即使单独超预算也保留
        var keepFrom = real.size - 1
        var total = estimate(messageText(real.last()))
        for (i in real.size - 2 downTo 0) {
            val t = estimate(messageText(real[i]))
            if (total + t > budget) break
            total += t
            keepFrom = i
        }
        val kept = real.subList(keepFrom, real.size).toList()
        // 尾部附注在裁剪后追加：system 字节不动（KV 前缀缓存命中恢复）；附注占预算
        // 扣减（预算收紧时较无注入 build 少留历史）；droppedCount 按真实历史计
        return BuiltContext(system, if (noteText == null) kept else kept + noteMessage(noteText), real.size - kept.size)
    }

    companion object {
        /** budget 下限：源 ContextTrimmer.trim（P5 已删）的 coerceAtLeast(256) 同值保留。 */
        const val MIN_BUDGET = 256

        /**
         * 尾部附注消息的合成 id（[appendTrailingUserNote] 物化的临时 user 消息标记）。
         * 公开给"末条真实 user"消费方（hooks 的本轮 user 文本捕获/续讲复位/记忆
         * 查询源）作排除依据——附注随 build 产物进 loop 历史末尾，不排除会把附注
         * 误当用户输入。
         */
        const val TRAILING_NOTE_ID = "trailing-note"

        /**
         * 粗略 token 估算：CJK 字符约 1 字 1 token，其他字符约 4 字符 1 token。
         * P5 起本类的自有实现（语义自 util.ContextTrimmer.estimateTokens 迁入，
         * 公式逐字符复制——等值由 ContextBuilderTest 的 CJK/ASCII/混排对照测试钉死），
         * 旧出处随后者删除消亡；build/computeBreakdown 的默认 estimate 口径即此。
         */
        fun estimateTokens(text: String): Int {
            if (text.isEmpty()) return 0
            var cjk = 0
            var other = 0
            for (ch in text) {
                if (ch.code >= 0x2E80) cjk++ else other++
            }
            return cjk + (other + 3) / 4
        }

        /**
         * 生成预留（本地后端 max_tokens 的同源公式）。公式与
         * ConnectionProbe.computeLocalMaxTokens 同源（上下文窗口 1/4，钳制 256..8192，
         * 均拷贝自内核清理 A 已删的 data 层旧客户端）——
         * spec 硬契约：预留小于实际输出上限时本地小窗口模型会整请求超窗，
         * 不得回落默认值。agent 包不 import data 层（依赖方向 agent → tools →
         * core/llm），故拷贝而非调用；逐值等价曾由测试断言钉死（断言随 Task 6
         * 删除并登记于 ContextBuilderTest）。
         */
        fun reserveForOutput(maxContext: Int): Int =
            (maxContext / 4).coerceIn(256, 8192)

        /**
         * 容量口径统计（P3 T3，评审 P0-1 承接）：计费公式与 [ContextBuilder.build]
         * （trim 语义迁入）**同源**——system 计费、estimateTokens 估算、reserve =
         * [reserveForOutput]；messages 按计费文本（[messageText]：Text/ToolCall/
         * ToolResult 计、Thinking 不计）逐条 estimate 求和。但**输入是原文**：
         * systemPrompt 计传入 system 原文（不含 transformContext 追加段/收尾护栏
         * 等运行期注入），messages 计传入列表原文（Session 传落库原文，未剥 UI
         * 标记）——T6 注入系统段后此处是近似口径，非逐字节等同请求体。Session
         * 收线时调用并经 SessionState.contextBreakdown 暴露，VM 容量面板自此读
         * 同源公式，不再自算一套。
         *
         * attachments 恒 0：附件（图/视频帧/文件）不上 wire（WireConverter 不
         * 序列化 AgentMessage.attachments）、trim/build 也不对其计费——按
         * "请求实际占用"同源口径如实报 0；字段保留只为与 VM 的 ContextBreakdown
         * 同形（五字段 + used/free 派生），面板接线零适配。
         */
        fun computeBreakdown(
            system: String,
            messages: List<AgentMessage>,
            maxContext: Int,
            estimate: (String) -> Int,
        ): SessionBreakdown {
            var messageTokens = 0
            for (m in messages) messageTokens += estimate(messageText(m))
            return SessionBreakdown(
                messages = messageTokens,
                attachments = 0,
                systemPrompt = estimate(system),
                reserve = reserveForOutput(maxContext),
                maxContext = maxContext,
            )
        }

        /**
         * 消息的计费文本：Text/ToolCall/ToolResult 的文本内容（工具往返对请求体有
         * 实际字节占用），Thinking 不计——思考不随历史重发（对齐 stripThinking 语义）。
         * build（trim 侧）与 computeBreakdown（统计侧）共用的唯一计费口径出处。
         */
        private fun messageText(m: AgentMessage): String =
            m.blocks.joinToString("\n") { block ->
                when (block) {
                    is ContentBlock.Text -> block.text
                    is ContentBlock.ToolCall -> block.name + "\n" + block.argsJson
                    is ContentBlock.ToolResult -> block.output
                    is ContentBlock.Thinking -> ""
                }
            }
    }
}
