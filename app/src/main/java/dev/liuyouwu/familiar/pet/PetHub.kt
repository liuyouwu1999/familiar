package dev.liuyouwu.familiar.pet

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 大肥鱼桌宠。形象照搬 YunYueSama/codex-deepseek-pet（大肥鱼项目署名许可 1.0），
 * 交互参考 txlznbzsdj-collab/dsh-deepseek-pet：
 * 连上模型后常驻屏幕（默认右下角），可拖动且记住位置；
 * 单击说人设台词、双击庆祝撒花、长按弹菜单（摸摸头/冷笑话/躲起来）、
 * 甩来甩去会晕船吐槽、时不时冒句泡。
 * 气泡文案克制准确：只说她能确定的事（"搞定啦"而不是"成功了"）。
 */

/** 桌宠状态中枢：ChatViewModel/ConnectionScreen 写，PetOverlayService 读渲染。
 *  全应用只有这一只桌宠（悬浮窗），应用内外同一位；会话级，进程重启归零。 */
object PetHub {
    /** THINKING/SEARCHING/SPEAKING 为 B1 会话状态（PetStateResolver 裁定，帧动画复用现有素材）；
     *  DANCE..EAT 为表演动作（P6 任务4b，帧源 codex-deepseek-pet 图集，one-shot 播完回落）；
     *  REVIEW 为复习泡专用态（学习画像 spec §6 chose-mood 方案）：showBubble/settle 按它
     *  分派泡变体（12s 沉降 + 可点击），皮肤无此 clip 帧动画回落 IDLE 不崩 */
    enum class Mood { IDLE, BUSY, DONE, ERROR, SHY, TURN, SLEEP, THINKING, SEARCHING, SPEAKING,
        DANCE, WAVE, JUMP, HUM, TAIL, SNEAK, TOKEN, MEAL, EAT, REVIEW }

    /** 表演动作全集（P6 任务4b）：皮肤可选实现——pet.json 缺键的皮肤（橘版未跟量）运行时
     *  回落 IDLE clip 不崩，只是抽选表演时被过滤（performRandom 只抽真有帧的） */
    val PERFORMANCE_MOODS: Set<Mood> = setOf(
        Mood.DANCE, Mood.WAVE, Mood.JUMP, Mood.HUM, Mood.TAIL, Mood.SNEAK, Mood.TOKEN, Mood.MEAL, Mood.EAT,
    )

    fun isPerformance(mood: Mood): Boolean = mood in PERFORMANCE_MOODS

    data class PetState(
        val connected: Boolean = false,   // 连上过模型才出现（只说她能确定的事）
        val mood: Mood = Mood.IDLE,
        val bubble: String? = null,
    )

    private val _state = MutableStateFlow(PetState())
    val state: StateFlow<PetState> = _state.asStateFlow()

    /** 单击桌宠 → 跳到主陪伴聊天框；AppNavigation 挂钩，未挂时退回说话 */
    var onOpenChat: (() -> Unit)? = null

    /** 复习泡点击/菜单「复习一下」→ 跳到学习画像页；AppNavigation 挂钩（Task 10 注册），
     *  未挂时 no-op——钩子先于路由存在，中间态点击不崩 */
    var onOpenReview: (() -> Unit)? = null

    /** 用户自定义称呼（悬浮层随时回写），台词里的"老板"跟随它 */
    var address = "老板"

    fun openChat() {
        val cb = onOpenChat
        if (cb != null) cb() else say("咕嘟。")
    }

    fun notifyConnected() = _state.update { it.copy(connected = true) }

    // ---------------------------------------------------------------- 相伴数据（B1 Task 7）

    /**
     * 久别问候待发标记（spec §5，判定方/消费方分属两处、持有点必须唯一）：
     * 连接侧 [maybeGreet] 判定 gap≥2 天置位；实际开口由 ambient 调度器下一拍消费
     * （PetOverlayService.ambientLoop：pending → say(GREETING 洗牌袋) 并清位，
     * 替换当日首次 ambient 闲聊）。
     */
    var bondGreetingPending: Boolean = false

    /**
     * 久别问候判定挂点（notifyConnected 三处之后各调一行）：到期则置
     * [bondGreetingPending] 并记当日已问候（当日仅一次，评审 F10）。
     * 只置位不开口——问候时机交给 ambient 调度器，与防烦两判同拍消费。
     */
    fun maybeGreet(bond: PetBondStore, now: () -> Long = bond.now) {
        if (bond.shouldGreet(now)) {
            bondGreetingPending = true
            bond.markGreeted(now)
        }
    }

    /** DONE/ERROR 罐头默认文本（状态机出口 showState 单一归属）。旧散点直写口已随
     *  B1 Task 4 收拢 resolver 删除——会话状态一律经 PetStateResolver 帧口裁定，
     *  不再旁路直写 */
    private val doneLine: String get() = "搞定啦$address！鲸鱼完美收工～"
    private val errorLine = "唔……出岔子了，看看提示嘛。"

    /** 互动台词：显式给 mood 用给定值；生成中不打断嚼 token；其余回到待机 */
    fun say(text: String, mood: Mood? = null) = _state.update {
        if (!it.connected) it
        else {
            val m = when {
                mood != null -> mood
                // 生成四态均保持（审查 #3）：THINKING/SEARCHING/SPEAKING 若落 else→IDLE，
                // 生成中单击会把状态视觉打断，且同态同文不重发会让她卡死到下一沿
                it.mood == Mood.BUSY || it.mood == Mood.THINKING ||
                    it.mood == Mood.SEARCHING || it.mood == Mood.SPEAKING -> it.mood
                else -> Mood.IDLE
            }
            it.copy(mood = m, bubble = text)
        }
    }

    fun settle() = _state.update {
        if (it.mood == Mood.IDLE && it.bubble == null) it
        else it.copy(mood = Mood.IDLE, bubble = null)
    }

    /** 表演入口（P6 任务4b）：模型指挥（pet_react）/菜单「来段表演」/空闲微动作共用。
     *  与 [say] 的区别：**故意绕过生成四态保持**——庆祝不该被 THINKING 顶掉；
     *  演完回哪由 [finishPerformance] 按 resolver 持有态裁定，本入口只管开演 */
    fun perform(action: Mood, line: String? = null) = _state.update {
        if (!it.connected) it else it.copy(mood = action, bubble = line)
    }

    /** 表演收束（渲染层帧播完回调）：resolver 的裁定态若仍在流式驱动就恢复它，
     *  否则回 IDLE 静默——表演只是插入演出，不抹掉会话状态 */
    fun finishPerformance() {
        val back = resolver.current
        showState(
            if (back == Mood.THINKING || back == Mood.SEARCHING || back == Mood.SPEAKING) back else Mood.IDLE,
            null,
        )
    }

    /** 状态机出口（resolver emitter 单出口）：resolver 裁定什么就写什么，不走 say 的保持分支；
     *  仍守"没连上时她不存在"；DONE/ERROR 不带文本时回落罐头默认 */
    internal fun showState(mood: Mood, bubble: String?) = _state.update {
        if (!it.connected) it
        else it.copy(
            mood = mood,
            bubble = bubble ?: when (mood) {
                Mood.DONE -> doneLine
                Mood.ERROR -> errorLine
                else -> null
            },
        )
    }

    /** 会话状态机实例：emitter 单出口 → showState（resolver 不直调 PetHub，JVM 单测可跑） */
    internal val resolver = PetStateResolver { mood, bubble -> showState(mood, bubble) }

    /** resolver 的入口跨三路线程来（Main 帧收集/IO 曝光推进/attach 锁块），沿基线 var 无锁——
     *  一律经此 post 到主线程再调（审查 N4；ShuffleBag 的主线程约束同此适用） */
    private val mainHandler = Handler(Looper.getMainLooper())
    fun postResolver(block: () -> Unit) = mainHandler.post(block)

    /** 台词洗牌袋：键=池名（PetLines 的十二个池）。主线程单例，抽空自动重洗，
     *  {addr} 称呼替换内建——根治 random() 直抽的"连抽重样"。
     *  池必须在此注册：漏注册的 key 首次 pickLine 会 getValue 抛 NoSuchKey */
    private val bags: Map<String, ShuffleBag> = mapOf(
        "tap" to ShuffleBag(PetLines.TAP),
        "doubleTap" to ShuffleBag(PetLines.DOUBLE_TAP),
        "stroke" to ShuffleBag(PetLines.STROKE),
        "joke" to ShuffleBag(PetLines.JOKE),
        "feed" to ShuffleBag(PetLines.FEED),
        "ambient" to ShuffleBag(PetLines.AMBIENT),
        "fling" to ShuffleBag(PetLines.FLING),
        "farewell" to ShuffleBag(PetLines.FAREWELL),
        "greeting" to ShuffleBag(PetLines.GREETING),
        "thinking" to ShuffleBag(PetLines.THINKING),
        "searching" to ShuffleBag(PetLines.SEARCHING),
        "review" to ShuffleBag(PetLines.REVIEW),
    )

    /** 按池名抽一句台词（称呼跟随 PetHub.address） */
    fun pickLine(key: String): String = bags.getValue(key).draw(address)
}

