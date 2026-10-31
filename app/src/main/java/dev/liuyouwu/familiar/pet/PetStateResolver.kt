package dev.liuyouwu.familiar.pet

/**
 * PetStateResolver（B1）：会话事件 → 桌宠状态的确定性归约状态机（spec §3 归约表）。
 * 纯逻辑、无网络无模型；emitter 单出口（构造注入），不直调 PetHub——JVM 单测可跑。
 *
 * 沿触发语义（照"平判"实现必错）：
 * - THINKING = streaming 的上升沿（true 进入；run 起点即有状态，首 token 延迟期不再是真空）；
 * - SEARCHING = streaming 中 toolStatus 文本变化（沿，含 null→非空与另一查询文本）
 *   且正文未恢复增长（textChanged）；正文恢复增长即回落 THINKING；
 * - 收束沿（streaming true→false）是 DONE 的唯一入口；同帧 lastError 非空压倒 DONE → ERROR；
 * - onCancelled()/onChainSettledBackground() → IDLE 静默（bubble=null）并重置沿基线
 *   （prevStreaming=false）——迟到终态帧不得再判收束沿"补庆祝"；
 * - onContextSwitch() → 只重置基线（prevStreaming=false、prevToolStatus=null），
 *   随后的回放帧按新基线正常处理：false 重放无沿不放假 DONE，true 重放恢复 THINKING（两方向都对）；
 * - onSegmentRevealed：index<total-1 → SPEAKING 镜像当前曝光段文本；
 *   index==total-1 → 回 DONE（收束沿已落过 DONE，reveal 只负责回拉）；
 * - 沿基线：首帧前内部态视为"虚拟 IDLE"（prevStreaming=false）——STICKY 重启后
 *   首帧 streaming=true 照常进 THINKING。
 *
 * 发布规则：同态同文不重发（SPEAKING 镜像文本更新除外——每段文本不同，天然通过）。
 *
 * 线程约束：所有入口一律经 [PetHub.postResolver] 收束到主线程后再调
 * （实测跨 Main/IO/attach 锁块三路来）；沿基线 var 无锁，与 ShuffleBag 同款主线程约束。
 */
internal class PetStateResolver(private val emitter: (PetHub.Mood, String?) -> Unit) {

    /** 观察口（Task 6 镜像期单击判定用）：当前裁定态，首帧前 IDLE */
    val current: PetHub.Mood get() = state

    private var state = PetHub.Mood.IDLE
    private var lastBubble: String? = null
    private var prevStreaming = false          // 沿基线：首帧前视为虚拟 IDLE
    private var prevToolStatus: String? = null

    /** THINKING/SEARCHING 罐头台词：直接吃 PetLines 池（经洗牌袋，一轮不重样）；
     *  自持袋子而非调 PetHub.pickLine——不触碰 PetHub object，单测不牵连 Android 依赖 */
    private val thinkingBag = ShuffleBag(PetLines.THINKING)
    private val searchingBag = ShuffleBag(PetLines.SEARCHING)

    /** 帧口（ChatViewModel 收集快照转发）：streaming + toolStatus + lastError，textChanged 默认 false */
    fun onSessionFrame(
        streaming: Boolean,
        toolStatus: String?,
        lastError: String?,
        textChanged: Boolean = false,
    ) {
        val wasStreaming = prevStreaming
        val toolChanged = toolStatus != prevToolStatus
        prevStreaming = streaming
        prevToolStatus = toolStatus

        if (!streaming) {
            if (wasStreaming) {                            // 收束沿：DONE 唯一入口
                if (lastError != null) publish(PetHub.Mood.ERROR, null)   // 同帧 lastError 压倒 DONE
                else publish(PetHub.Mood.DONE, null)
            }
            return                                         // 无沿：忽略（迟到终态帧/空闲重放）
        }

        if (!wasStreaming) publish(PetHub.Mood.THINKING, thinkingBag.draw())   // 上升沿进 THINKING
        else if (textChanged && state == PetHub.Mood.SEARCHING) {
            publish(PetHub.Mood.THINKING, thinkingBag.draw())   // 正文恢复增长，回落 THINKING
        }
        if (toolChanged && !textChanged) {
            publish(PetHub.Mood.SEARCHING, searchingBag.draw()) // 文本变化即沿（含 null→非空）
        }
    }

    /** 停止/取消：静默回 IDLE 不庆祝（她不知道你为什么停），并重置沿基线 */
    fun onCancelled() {
        prevStreaming = false
        prevToolStatus = null
        publish(PetHub.Mood.IDLE, null)
    }

    /** 背景链收束（非挂载会话）：同取消——不在场不庆祝 */
    fun onChainSettledBackground() {
        prevStreaming = false
        prevToolStatus = null
        publish(PetHub.Mood.IDLE, null)
    }

    /** 切会话：只重置基线，不发事件；随后的回放帧按新基线正常处理 */
    fun onContextSwitch() {
        prevStreaming = false
        prevToolStatus = null
    }

    /** 分段曝光推进（index 为 0 基段序，调用方负责从 1..total 计数换算）：
     *  非末段回拉 SPEAKING 镜像当前段，末段回 DONE */
    fun onSegmentRevealed(index: Int, total: Int, text: String) {
        if (total <= 0 || index !in 0 until total) return   // 乱序/异常输入：忽略该帧，保持现状
        if (index < total - 1) publish(PetHub.Mood.SPEAKING, text)
        else publish(PetHub.Mood.DONE, null)
    }

    /** 发布规则：同态同文不重发（DONE/ERROR 走 null，由渲染门面回落罐头默认，文本单一归属 PetHub） */
    private fun publish(mood: PetHub.Mood, bubble: String?) {
        if (mood == state && bubble == lastBubble) return
        state = mood
        lastBubble = bubble
        emitter(mood, bubble)
    }
}
