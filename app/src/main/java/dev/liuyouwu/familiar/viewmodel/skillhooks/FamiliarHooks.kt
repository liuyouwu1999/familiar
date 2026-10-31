package dev.liuyouwu.familiar.viewmodel.skillhooks

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dev.liuyouwu.familiar.agent.AgentHooks
import dev.liuyouwu.familiar.agent.ContextBuilder
import dev.liuyouwu.familiar.agent.RunChain
import dev.liuyouwu.familiar.agent.RunResult
import dev.liuyouwu.familiar.agent.SamplingParams
import dev.liuyouwu.familiar.core.llm.model.AgentMessage
import dev.liuyouwu.familiar.core.llm.model.ContentBlock
import dev.liuyouwu.familiar.core.llm.model.Role
import dev.liuyouwu.familiar.data.local.entity.ChatRoomParticipantEntity
import dev.liuyouwu.familiar.data.local.entity.ConversationEntity
import dev.liuyouwu.familiar.util.ReplySimilarity
import dev.liuyouwu.familiar.util.memory.FamiliarMemory
import dev.liuyouwu.familiar.util.memory.FamiliarMemoryStore
import dev.liuyouwu.familiar.util.persona.MessageSegmenter
import dev.liuyouwu.familiar.util.persona.PersonaCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SkillHost 的设置读取端口（P3 T6 可测缝）：SkillHost/hooks 只需要设置层的少数
 * 真值，且要求**非挂起直读**（AgentSession 的 searchAllowed/maxContext/model
 * 供应商逐请求求值、FamiliarParams.derive 在装配期同步取值）。生产适配
 * （P5 接线）由 SettingsRepository/DataStore 的缓存快照实现（如 Flow stateIn
 * Eagerly 常驻收集后的 .value——不得 WhileSubscribed 冷读，见 ChatViewModel:918
 * 的假值审计）；单测直接实现固定值假件。
 */
interface SkillSettingsPort {
    /** familiar 说话方式（default/proactive/passive，PersonaPromptBuilder.MODES） */
    fun familiarMode(): String

    /** familiar 称呼用户的方式（设置覆写卡值，assembleFamiliar:626 语义） */
    fun familiarAddress(): String

    /** familiar 自称（设置覆写卡值，assembleFamiliar:627 语义） */
    fun familiarSelfName(): String

    /** 桌宠配色（决定蓝/橘版兜底卡，assembleFamiliar:622-624 语义） */
    fun petColor(): String

    /** 联网搜索开关（familiar 的 searchPolicy=user-toggle 供应商） */
    fun webSearchEnabled(): Boolean

    /** 上下文窗口上限（AgentSession.maxContext 供应商 + maxTokens 同源公式入参） */
    fun maxContext(): Int

    /** 当前模型名（AgentSession.model 供应商） */
    fun currentModel(): String

    /**
     * 聊天室每轮发言条数上限（P4 T2，Orchestrator maxTurnsPerRound 装配位）：
     * 设置项 chatRoomMaxReplies（UI 钳 1..6，默认 3——pickRoundPlan 的 maxReplies
     * 同源）。接口缺省=3：端口实现未覆写时回落现值。
     */
    fun chatRoomMaxReplies(): Int = 3

    /**
     * 聊天室成员称呼用户的角色侧称呼（P4 T2，ChatRoomHooks.memberPrompt 的
     * address 参）：设置项 chatRoomAddress（ChatRoomPrompts.memberSystem 缺省
     * "小刘鸭"同源）。接口缺省="小刘鸭"。
     */
    fun chatRoomAddress(): String = "小刘鸭"

    /** 链级采样参数（B2-L1）：缓存快照直读（refreshSamplingCache 每链刷新）；缺省 null = 请求不携带 */
    fun convSampling(): SamplingParams? = null
}

/**
 * SkillHost 的仓库写入端口（P3 T6 可测缝）：hooks 落库动作只此两处（familiar
 * 切段写 segments 列、课堂 TS 状态写 tutorState 列）。生产适配委托
 * ChatRepository 同名方法；单测记账假件。
 *
 * P4 T2 扩展三读/写（聊天室装配 + 标题触发的数据源，delegate 关系不变）：
 * - [getParticipants]：chat_room_participants 表读（members JSON 缺席的存量
 *   回退路径）；
 * - [getConversation]：会话行读（标题触发的 familiar 豁免/旧默认名判定源）；
 * - [updateTitle]：标题写（替换路径，ChatRepository.updateConversationTitle 同语义）。
 */
interface SkillRepoPort {
    suspend fun updateSegments(messageId: Long, segmentsJson: String?)

    suspend fun updateTutorState(conversationId: Long, stateJson: String)

    /** 会话成员行（chat_room_participants；排序由消费侧按 sortOrder 统一）。 */
    suspend fun getParticipants(conversationId: Long): List<ChatRoomParticipantEntity>

    /** 会话行（标题触发的豁免/默认名判定源；无此行返回 null）。 */
    suspend fun getConversation(conversationId: Long): ConversationEntity?

    /** 会话标题写（自动命名替换）。 */
    suspend fun updateTitle(conversationId: Long, title: String)
}

/**
 * familiar 运行时变量派生（spec §6.5、P3 T6）：[PersonaCard] + 设置 →
 * `{{var}}` 模板参数。**迁移出处逐段照抄 PersonaPromptBuilder.build**
 * （不 import 其私有件；spec §11 双份并存至 P4 收敛）：
 * - identity 空值兜底（:51）；
 * - tagBlock/tagGloss ifBlank 推导（:52-66：take(40)/take(20) 截断 + 条件拼接）；
 * - mode → modeGuide 文案（:42-46）；
 * - memory_note = 静态"记忆说明"声明（:86，memoryNote=true 默认路径）——
 *   **动态记忆上下文不在此**（逐请求变化，由 [FamiliarHooks.transformContext]
 *   注入），变量位只承载稳定声明，同卡同模式跨轮字节一致（前缀缓存前提）。
 */
object FamiliarParams {

    /** modeGuide 文案（迁自 PersonaPromptBuilder.modeGuide:42-46，逻辑不改） */
    private fun modeGuide(mode: String, address: String): String = when (mode) {
        "proactive" -> "说话方式=主动：可主动向${address}搭话、关心或提醒，每次最多一句，不打断对方。开场和收尾都可以有，但句式和内容不得连续两轮雷同；话题选项仅在对话确实需要方向时给，别每轮都摆选项。"
        "passive" -> "说话方式=被动：${address}说什么就回应什么，非必要不主动搭话、不增加额外询问，同一说法别连续两轮复用。"
        else -> "说话方式=默认：主动与被动之间均衡，正常互动即可。可以用一句符合人设的话开场或收尾（非强制，自然即可，别每轮套路相同）；不必每轮都提问，自然推进对话就好。"
    }

    /** 静态记忆声明（迁自 PersonaPromptBuilder.build:86 的 memoryNote 块文案） */
    const val MEMORY_NOTE_DECLARATION =
        "记忆说明：你的长期记忆会以消息形式随上下文提供；只可使用其中已发生的事实，严禁编造记忆。"

    /** identity 空值兜底（迁自 PersonaPromptBuilder.build:51） */
    private const val IDENTITY_FALLBACK = "一条住在你手机里的蓝色大肥鱼，慢吞吞，黏人，记性意外地好"

    /** PersonaCard + 设置 → 模板参数（变量名 = familiar.json 骨架位，SkillRegistryTest 钉死） */
    fun derive(card: PersonaCard, settings: SkillSettingsPort): Map<String, String> {
        val tagBlock = card.tagBlock.ifBlank {
            listOfNotNull(
                "LANG_ZH_CN_ONLY",
                card.traits.takeIf { it.isNotBlank() }?.let { "PERSONALITY_" + it.take(40) },
                card.catchphrase.takeIf { it.isNotBlank() }?.let { "CATCHPHRASE_" + it.take(20) },
            ).joinToString("\n")
        }
        val tagGloss = card.tagGloss.ifBlank {
            listOfNotNull(
                "只使用简体中文",
                card.traits.takeIf { it.isNotBlank() }?.let { "性格：$it" },
                card.speechStyle.takeIf { it.isNotBlank() }?.let { "说话方式：$it" },
                card.catchphrase.takeIf { it.isNotBlank() }?.let { "口头禅是压箱底的稀有线：隔很多轮才用一次，只在情绪到位（被质疑、被夸、得意）时甩出来，绝不放在句尾当收尾，更不每轮都用：${it}" },
            ).joinToString("；")
        }
        return mapOf(
            "name" to card.name,
            "identity" to card.identity.ifBlank { IDENTITY_FALLBACK },
            "selfName" to card.selfName,
            "address" to card.address,
            "tagBlock" to tagBlock,
            "tagGloss" to tagGloss,
            "exampleDialogs" to card.exampleDialogs,
            "mode" to modeGuide(settings.familiarMode(), card.address),
            "memory_note" to MEMORY_NOTE_DECLARATION,
        )
    }

    /**
     * 空值变量空行兜底（P2-T3 登记项在 T6 的闭合，SkillInterpreter KDoc 的
     * "已知推迟项"）：interpreter 空串照常替换——空 exampleDialogs 会在骨架位
     * 渲染出空行。familiar 模板各段以单换行相接、无排版性空行，故把渲染产物中
     * 连续空行（含空白行）压回单换行即可消除；PersonaPromptBuilder.build 对空
     * exampleDialogs 的"整段跳过"（:74）语义等价。**仅 familiar 用**
     * （chat 的会话行 system_prompt 是用户文本，排版空行不得动）。
     * 边界：多段式 exampleDialogs 值内的排版性空行同样被压（内置卡均为单换行，
     * 已知可接受边界）。
     */
    fun sanitize(rendered: String): String =
        rendered.replace(Regex("\\n[ \\t]*\\n+"), "\n")
}

/**
 * familiar 陪伴会话的行为钩子（spec §6.3 memory=familiar + reveal=
 * suppressed-segmented 的落库侧；P3 T6 语义迁移，不 import ChatViewModel）：
 *
 * - [transformContext]：**buildMemoryTail 语义迁移**（ChatViewModel:635-639）——
 *   查询源 = 本次请求历史的全部 user 消息拼接（本轮 user 已落库，天然含本轮），
 *   经 FamiliarMemory.buildContext 产出记忆上下文（≤500 字块级预算；稳定事实 →
 *   最近互动 → 相关旧事），以**尾部临时 user 附注**注入（P5：旧链本为尾部
 *   ephemeral user 消息，P3 曾随 appendSystem 能力迁为系统段——记忆逐请求变化，
 *   写进 system 每请求打破一次 KV 前缀缓存；P5 经 ContextBuilder.
 *   appendTrailingUserNote 归位尾部，system 恒稳定、缓存命中恢复；仅请求装配
 *   层，不落库）。空记忆（buildContext 返回 null）不注入。同时捕获请求历史末条
 *   真实 user 行文本（记忆落盘的"本轮 user 文本"，非挂起直读；P5 尾部附注消息
 *   以 TRAILING_NOTE_ID 排除——附注会以末条 user 形态进 loop 历史）。B2-L2 滚动
 *   反刍附注同在此注入：最近 ≤3 条合格 assistant 行的开头样本（剥句首自称、跳过
 *   无正文/摘要行）≥2 条时，以尾部附注要求起句不得与最近回复雷同。
 * - [onPersisted]（挂 AgentSession 构造位，T2 post 钩子）：**切段落库语义迁移**
 *   （ChatViewModel:662-675 + :696-698）——splitFamiliarSegments：思考标签变体
 *   整体并入首段（未闭合半截思考不切整条直出），可见正文按句边界切段（拼接无损）；
 *   repo.updateSegments(rowIds[0] 对位行) → onSegments(行 id, segs) 回调
 *   （VM 推进 reveal + delays 时序 + **TTS 归 reveal 推进链**（末段曝光时读，
 *   :717-719）——本钩子不做 TTS，防双读）。空正文跳过（占位收线不留垃圾段）。
 * - [onRunEnd]：**persistFamiliarTurn 语义迁移**（ChatViewModel:644-657）——
 *   本轮互动落盘长期记忆（取消/半截内容也记一轮；双空跳过；NonCancellable
 *   必达）+ 桌宠完成回调（成功 run 才回调——取消路径不喊"完成"，对齐
 *   :1209-1212 的 streamError==null 门）。
 *
 * 线程模型：onPersisted/onRunEnd 是非挂起回调，repo 写（suspend）与回调发射
 * 挂注入的 [scope]（生产：宿主 IO scope；测试：runBlocking 作用域）；切段/
 * 记忆计算纯同步。落库次序（segments 先于 onSegments 回调）在协程内串行保证。
 * transformContext 在调用方协程内联执行（含 memoryStore.current() 的文件读，
 * 旧链 :926 以 withContext(IO) 包裹）：P5 接线时入口应在 IO 调度器驱动
 * AgentSession（或此处改造为异步预热快照），主线程直驱会读盘卡帧——已登记。
 */
class FamiliarHooks(
    private val card: PersonaCard,
    private val memoryStore: FamiliarMemoryStore,
    private val repo: SkillRepoPort,
    /** 宿主 scope：repo 写/回调发射（非链子协程，不进 AgentSession 终态判据） */
    private val scope: CoroutineScope,
    private val onSegments: (Long, List<String>) -> Unit,
    /**
     * familiar 回合成功收线口（B1 Task 4④ 更名，旧名直写"完成"的散点回调）：
     * 成功 run 门不变（取消路径不回调）。resolver 收拢桌宠状态后，本口只承载
     * 回合级伴随打点——Task 7 在 Controller 装配位接 PetBondStore.onTurnEnded。
     */
    private val onTurnEnded: () -> Unit,
) : AgentHooks {

    /** B2-L4 相似度重试发起器（SkillHost 回填，Controller 侧异步核验后调 regenerate）。
     *  参数 = 目标 assistant 行 id（onPersisted 捕获）；null = 未接线，L4 整体不启用。 */
    var revise: ((Long) -> Unit)? = null

    /** 核验通过、即将真正发起重试时由 Controller 调（二轮 N2：先核验后置旗标，防泄漏）。
     *  熔断计数**不在**此自增——计数与复位必须同处（onRunEnd）才可达，三轮 P0-2 */
    fun markRevisionLaunched() {
        pendingRevisionNote = true
    }

    /** 测试观察口 */
    internal val revisionPendingForTest: Boolean get() = pendingRevisionNote

    @Volatile private var pendingRevisionNote = false
    @Volatile private var revisionAttempted = false
    @Volatile private var consecutiveRevisions = 0
    @Volatile private var lastSeenUserId: String? = null
    @Volatile private var previousAssistantText: String = ""
    @Volatile private var lastAssistantRowId: Long? = null

    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    /** 本轮 user 文本（transformContext 捕获的请求历史末条 user 行；记忆落盘用）。 */
    @Volatile private var currentUserText: String = ""

    /**
     * 记忆注入（每轮请求前；AgentSession 预裁与 AgentLoop 逐 turn 均过此钩）。
     * 注入落点为请求装配的尾部附注，逐 turn 均生效：Session 侧随预裁 build() 物化，
     * AgentLoop 逐 turn 的 request() 亦经 build() 物化（上一装配轮的附注被剥除
     * 取代）——familiar tools 含 web_search（skill JSON tools 数组接线+开关放行），工具往返的
     * 多 turn run 下每个请求附注都在尾，对齐 P3 appendSystem 的逐 turn 注入能力。
     * 查询源为 ctx 内全部真实 user 行正文拼接（尾部附注除外）。
     */
    override fun transformContext(ctx: ContextBuilder) {
        // 尾部附注（P5 装配层合成的 user 消息）不得当作本轮 user 输入：捕获与
        // 记忆查询源一律排除（TRAILING_NOTE_ID——附注会以末条 user 形态进 loop 历史）
        currentUserText = ctx.messages.lastOrNull {
            it.role == Role.USER && it.id != ContextBuilder.TRAILING_NOTE_ID
        }?.blocks?.filterIsInstance<ContentBlock.Text>()
            ?.joinToString("\n") { it.text } ?: ""
        // 复位判据（一轮 #1 修正 + 三轮 P0-2 收紧）：新 user 行 id 只复位"每轮限一次"；
        // **熔断计数不在此清**——若随新行清零则计数永远到不了 2（新永真门），它由
        // onRunEnd 的"正常轮"清零（见下）
        val currentUserId = ctx.messages.lastOrNull {
            it.role == Role.USER && it.id != ContextBuilder.TRAILING_NOTE_ID
        }?.id
        if (currentUserId != null && currentUserId != lastSeenUserId) {
            lastSeenUserId = currentUserId
            revisionAttempted = false
        }
        // L4 上一条回复捕获：最近一条含非空 Text 的 assistant 行（跳过无正文行/摘要行）
        previousAssistantText = ctx.messages
            .filter { it.role == Role.ASSISTANT && it.id != ContextBuilder.TRAILING_NOTE_ID }
            .mapNotNull { m ->
                m.blocks.filterIsInstance<ContentBlock.Text>()
                    .joinToString("\n") { it.text }.takeIf { it.isNotBlank() }
            }
            .lastOrNull { !it.startsWith(SUMMARY_ROW_PREFIX) } ?: ""
        val state = memoryStore.current()
        val query = ctx.messages
            .filter { it.role == Role.USER && it.id != ContextBuilder.TRAILING_NOTE_ID }
            .joinToString("\n") { m -> m.blocks.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text } }
        val tail = FamiliarMemory.buildContext(state.facts, state.lines, card.address, card.selfName, query)
        tail?.let { ctx.appendTrailingUserNote(it) }
        // B2-L2 滚动反刍附注：最近 ≤3 条含非空 Text 的 assistant 行的开头样本
        // （剥句首自称——铁律 6 强制自称开头，不剥则样本恒同、与 D3 自相矛盾，评审一轮 #5）
        val openers = ctx.messages
            .filter { it.role == Role.ASSISTANT && it.id != ContextBuilder.TRAILING_NOTE_ID }
            .mapNotNull { m ->
                m.blocks.filterIsInstance<ContentBlock.Text>()
                    .joinToString("\n") { it.text }.takeIf { it.isNotBlank() }
            }
            .filter { !it.startsWith(SUMMARY_ROW_PREFIX) }
            .takeLast(3)
            .map { openerOf(it, card.selfName) }
        if (openers.size >= 2) {
            ctx.appendTrailingUserNote(antiRutNote(openers))
        }
        // B2-L4 revision 附注（第三位：记忆尾注、反刍附注之后）。旗标由 Controller
        // 核验通过后经 markRevisionLaunched 置位；trimOnly 预裁不消费（wire 侧须
        // 能重建，ClassroomHooks:121-127 纪律）
        if (pendingRevisionNote) {
            ctx.appendTrailingUserNote(
                "（系统：你上一条回复与之前的过于相似。本轮以完全不同的说法与句式重新回应${card.address}当前的消息。）"
            )
            if (!ctx.trimOnly) pendingRevisionNote = false
        }
    }

    /**
     * 切段落库 + 曝光回调（T2 post 钩子，AgentSession 构造位挂入；rowIds 与
     * messages 一一对齐——familiar 单条 assistant 产出恒取 index 对位的首行）。
     */
    fun onPersisted(messages: List<AgentMessage>, rowIds: List<Long>) {
        val idx = messages.indexOfLast { it.role == Role.ASSISTANT }
        if (idx < 0 || idx >= rowIds.size) return
        val finalText = messages[idx].blocks
            .filterIsInstance<ContentBlock.Text>()
            .joinToString("\n") { it.text }
        if (finalText.isBlank()) return   // 空正文不切段（双空跳过，:650 同源）
        val rowId = rowIds[idx]
        lastAssistantRowId = rowId   // B2-L4：重试目标行 id 捕获（revise 的 invoke 参）
        val segs = splitFamiliarSegments(finalText)
        scope.launch {
            repo.updateSegments(rowId, gson.toJson(segs))
            onSegments(rowId, segs)
        }
    }

    /** 记忆落盘 + 桌宠回调（取消 run 也落记忆，但不喊完成）。 */
    override fun onRunEnd(result: RunResult, chain: RunChain) {
        // B2-L4 相似度重试（替换语义在 Controller 侧 regenerateLastResponse 落地）：
        // 同步分支判定（对齐 ClassroomHooks 结构纪律）；revise 未接线 = L4 不启用。
        // depth 门除名（familiar 链 depth 恒 0，永真式）。
        // 熔断生命周期（三轮 P0-2 修正）：计数在触发点自增；正常轮（非取消、双方合格、
        // 不相似）清零；新 user 行不清计数——否则计数永攒不满，熔断成永真门。
        if (!result.cancelled) {
            val similar = previousAssistantText.length >= 30 && result.finalText.length >= 30 &&
                ReplySimilarity.similar(result.finalText, previousAssistantText)
            if (similar && !revisionAttempted && consecutiveRevisions < 2) {
                val target = lastAssistantRowId
                if (target != null) {
                    revisionAttempted = true
                    consecutiveRevisions++
                    revise?.invoke(target)   // 旗标不在此置——核验通过后 markRevisionLaunched 才置
                }
            } else if (!similar && result.finalText.isNotBlank()) {
                consecutiveRevisions = 0   // 正常轮：熔断复位
            }
        }
        val assistantText = result.finalText
        val userText = currentUserText
        scope.launch {
            if (userText.isBlank() && assistantText.isBlank()) return@launch
            withContext(NonCancellable) {
                memoryStore.appendTurn(
                    userText, assistantText, card.address, card.selfName, System.currentTimeMillis(),
                )
                // 桌宠回合口随 NonCancellable 必达（B1 Task 4④：与 appendTurn 同块——
                // 宿主 scope 被取消时成功回合的伴随打点也不丢）；取消 run 不回调（成功门不变）
                if (!result.cancelled) onTurnEnded()
            }
        }
    }

    /**
     * familiar 收线内容切段（**逻辑复制自 ChatViewModel.splitFamiliarSegments:662-675，
     * 不 import**）：思考标签变体清单对齐 Open WebUI reasoning_tags 做法——思考块
     * 整体并入第一段（`</think>` 不落中段气泡），未闭合半截（流中断残留）整条直出；
     * 正文按句边界均衡切段（MessageSegmenter.split，默认 2-4 段）。拼接无损。
     */
    private fun splitFamiliarSegments(content: String): List<String> {
        for (tag in listOf("think", "thinkyle", "thinking", "reasoning", "thought")) {
            val open = "<$tag>"
            val start = content.indexOf(open)
            if (start < 0) continue
            val close = "</$tag>"
            val end = content.indexOf(close, start)
            if (end < 0) return listOf(content)
            val head = content.substring(0, end + close.length)
            val segs = MessageSegmenter.split(content.substring(end + close.length))
            return listOf(head + segs.first()) + segs.drop(1)
        }
        return MessageSegmenter.split(content)
    }
}

/** compact 摘要行的正文前缀（AgentSession.compact 落库形态）——非真实回复，不入样本 */
private const val SUMMARY_ROW_PREFIX = "[对话摘要]"

/** 开头样本：首句（无句读则全句）截前 10 字；先剥句首自称与其后紧邻标点，
 *  剥后仅剩标点/空白（isBlank 或不含字母数字，如 "本鲸。"→"。"）则回退未剥自称
 *  的原句——整条只剩自称+标点的样本剥成空串会以"／／"形态注入反刍注（顺手项①，
 *  Task 7 遗留），剥成纯标点串同理无信息（B2 台账边界） */
internal fun openerOf(text: String, selfName: String): String {
    var t = text.trim()
    val unstripped = t
    if (selfName.isNotEmpty() && t.startsWith(selfName)) {
        t = t.removePrefix(selfName).trimStart('：', ':', '，', ',', ' ')
    }
    if (t.isBlank() || t.none { it.isLetterOrDigit() }) t = unstripped
    val end = t.indexOfAny(charArrayOf('。', '！', '？', '!', '?', '…', '\n'))
    val sentence = if (end < 0) t else t.substring(0, end + 1)
    return sentence.take(10)
}

internal fun antiRutNote(openers: List<String>): String =
    "（系统：除自称外，你的起句方式不得与最近回复相同或高度相似：${openers.joinToString("／")}）"
