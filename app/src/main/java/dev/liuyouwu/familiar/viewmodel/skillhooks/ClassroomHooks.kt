package dev.liuyouwu.familiar.viewmodel.skillhooks

import android.util.Log
import com.google.gson.Gson
import dev.liuyouwu.familiar.agent.AgentHooks
import dev.liuyouwu.familiar.skills.ContentProtocol
import dev.liuyouwu.familiar.agent.ContextBuilder
import dev.liuyouwu.familiar.agent.RunChain
import dev.liuyouwu.familiar.agent.RunResult
import dev.liuyouwu.familiar.core.llm.model.AgentMessage
import dev.liuyouwu.familiar.core.llm.model.ContentBlock
import dev.liuyouwu.familiar.core.llm.model.MessageMeta
import dev.liuyouwu.familiar.core.llm.model.Role
import dev.liuyouwu.familiar.core.llm.model.SenderInfo
import dev.liuyouwu.familiar.viewmodel.RunMarkerTexts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 课堂私教会话的行为钩子（spec §6.3 classroomState + autoContinue +
 * contentProtocol=classroomScript + multiSpeakerScript 的运行时侧；P3 T6 语义迁移，
 * **不 import ChatViewModel，也不 import util.TutorPrompts**——状态块/续讲指令文案
 * 照抄，状态类同构复制，spec §11 双份并存至 P4 收敛）：
 *
 * - [transformContext]：TS 状态段注入（**注入文案照抄 TutorPrompts.
 *   stateToPromptBlock:287-313**，含 ✓/▶/○ 进度标记、当前节、小测战绩、薄弱点
 *   上限 5 条、摘要截 200 字）+ 续讲段注入调度指令（照抄 :952-957）+ /start
 *   开讲前按当前节标题注入联网搜索指令（[startSectionSearchNote]，2026-10-31——
 *   searchPolicy always-on 只保证工具在场，小模型不会自发用）。旧链把
 *   进度块并入末条 user 消息（appendProgressToTail:2056）；新链经 ContextBuilder.
 *   appendTrailingUserNote 以**尾部临时 user 附注**注入（P5：状态/续讲指令逐请求
 *   变化，P3 曾迁为系统段致每请求打破 KV 前缀缓存——归位尾部后 system 恒稳定、
 *   缓存命中恢复；仅请求装配层，不落库）。状态源为钩子持有的 [tutorStateJson]
 *   （装配时播种自 tutorState 列，onRunEnd 提取后同步刷新——非挂起直读，无逐请求
 *   DB 读）。每轮捕获末条真实 user 行文本（废稿重试判定的"本轮 user 文本"；
 *   P5 尾部附注消息以 TRAILING_NOTE_ID 排除，续讲复位的"历史以 user 收尾"判定
 *   同排除——附注会以末条 user 形态进 loop 历史）。
 * - [transformPersist]（挂 AgentSession.persistTransform 构造位，T2 pre 钩子）：
 *   无 ToolCall 的终稿按 ProtocolOutcome 拆条（首行带 Thinking、usage 挂首行、
 *   逐说话人 sender 徽章、同 turnId——MessageRowMapper 写侧契约的拆条形态）；
 *   无说话人标记的单条路径以 TS 清洗后的终稿正文落库（extractState 从正文移除
 *   标记的迁移，:1135 + :1166 的 stripMarker 同源）。
 * - [onRunEnd]：TS 提取落 tutorState 列（gson 全量再序列化，:1139-1141 语义）+
 *   废稿重试护栏（:1245：depth==0 && 本轮 user 文本非空 && isDraft → followUpRun，
 *   限一次——depth 计数天然防重）+ autoContinue（:1230-1244：拆出剧本且 phase
 *   != await_user && depth<maxDepth → followUpRun，**段间 delay(900) 在本钩子内、
 *   挂 chain.scope**——链取消即终止含 gap 的后续 run）。steeringLeftover=true
 *   时续讲/废稿全部让位（P-steer 闸①，spec §3.3 两道闸：本 run 收束由
 *   executeRun 的残留转投负责，防同链双 executeRun；TS 提取保留在闸前不丢）。
 * - [onSegmentsPersisted]（P4 T4，挂 AgentSession.onPersisted 构造位）：拆条组逐行
 *   **落库后**回调 [onSegmentPersisted]（构造注入，SkillHost 透传 VM 事件桥）——
 *   触发 autoRead（组末行触发一次、朗读整组正文——旧链收线即朗读的频率恢复）。
 *
 * **chain.scope 纪律（T2 KDoc 的硬约束，本钩子的结构约束）**：链上只准挂
 * gap→run 形态的短生命周期协程——短命但不发 run 的协程会把 AgentSession 的
 * 终态发布（followUpPending 判据）推迟到自身结束后而无人补发，streaming 永久
 * 卡 true。因此 onRunEnd 的**分支判定同步做**：确认要发后续 run 才在
 * chain.scope 上挂 gap 协程（gap → followUpRun）；TS 落列等不产生 run 的动作
 * 一律走注入的宿主 [scope]（非链子协程，不进终态判据）。
 *
 * 已知边界（登记）：
 * - 废稿重试的"本轮 user 文本"取每轮请求历史的末条 user 行——regenerate 入口
 *   无新输入，旧链由空 text 禁用重试、新链可能对 regenerate 产物重试一次
 *   （hooks 无法区分入口；影响面=多发一次 run）。
 * - 续讲调度指令的注入由 [pendingContinuationNote] 一次性旗标传递（autoContinue
 *   分支置位、**wire 装配侧消费**——session 预裁装配（trimOnly）只注入计费不翻转
 *   旗标，见 transformContext；P3/P4 曾在首个 transformContext 消费，而那是被丢弃
 *   的 session 侧 system，指令实际从未上 wire，P5 审查修复）。废稿重试段不注入
 *   （:952 的 continuation=true 条件同源）。用户发新消息（历史以 user 行收尾）时
 *   防御性复位，堵链取消窗口的残留。
 * - TS 列写入异步走宿主 scope：与旧链"同步写列后再续讲"相比，列落库与 gap 并行
 *   （注入侧读的是同步刷新的内存态，不受影响；生产 gap 900ms ≫ 写列耗时）。
 */
class ClassroomHooks(
    private val conversationId: Long,
    private val repo: SkillRepoPort,
    /** 剧本说话人（behaviors.multiSpeakerScript.speakers；缺省内置三说话人） */
    private val speakers: List<String> = DEFAULT_SPEAKERS,
    /** 续讲深度上限（behaviors.autoContinue.maxDepth，VM :1237 的 4 同源） */
    private val maxDepth: Int = 4,
    initialTutorState: String?,
    /** 续讲段间停顿（VM :1238 的 delay(900)；测试注短值） */
    private val gapMs: Long = 900L,
    /** 宿主 scope：TS 落列等不产生 run 的动作（chain.scope 只挂 gap→run，见类 KDoc） */
    private val scope: CoroutineScope,
    /**
     * 段级落库回调（P4 T4，**SkillHost 构造注入**——SessionState 不加字段，裁定）：
     * persistTransform 拆条组的每行**落库后**回调 `(turnId, segmentIndex, total)`
     * ——VM 事件桥据此触发 autoRead（组末行 segmentIndex==total-1=组完成）。
     * 发射点经 [onSegmentsPersisted]（AgentSession.onPersisted 构造位接线）：拆条先于
     * 任何 append，行未落库时回调会让 VM 的 turnId 组全文读空——故在落库后回调
     * （AgentSession.persistProduced：persistTransform → append → onPersisted）。
     */
    private val onSegmentPersisted: (turnId: String?, segmentIndex: Int, total: Int) -> Unit =
        { _, _, _ -> },
) : AgentHooks {

    /** 后续 run 发起器（SkillHost 装配 AgentSession 后回填 session.followUpRun 包装）。 */
    var followUp: (suspend () -> Unit)? = null

    /** 当前 TS 状态 JSON（装配播种 + onRunEnd 同步刷新；transformContext 直读）。 */
    @Volatile private var tutorStateJson: String? = initialTutorState

    /** 续讲指令一次性旗标：autoContinue 置位 → 后续 run 首个 transformContext 消费。 */
    @Volatile private var pendingContinuationNote = false

    /** 本轮 user 文本（transformContext 捕获的请求历史末条 user 行；废稿重试判定用）。 */
    @Volatile private var currentUserText: String = ""

    private val gson = Gson()

    /** 状态段 + （续讲段时）调度指令注入；捕获本轮 user 文本。 */
    override fun transformContext(ctx: ContextBuilder) {
        // 尾部附注（P5 装配层合成的 user 消息）不参与真实历史判定：复位与捕获
        // 一律排除（TRAILING_NOTE_ID——附注会以末条 user 形态进 loop 历史）
        if (ctx.messages.lastOrNull { it.id != ContextBuilder.TRAILING_NOTE_ID }?.role == Role.USER) {
            pendingContinuationNote = false
        }
        currentUserText = ctx.messages.lastOrNull {
            it.role == Role.USER && it.id != ContextBuilder.TRAILING_NOTE_ID
        }?.blocks?.filterIsInstance<ContentBlock.Text>()
            ?.joinToString("\n") { it.text } ?: ""
        stateToPromptBlock(tutorStateJson)?.let { ctx.appendTrailingUserNote(it) }
        // /start 开讲前强制搜索（2026-10-31 实测：searchPolicy always-on 只保证工具
        // 在场，小模型不会自发用——按计划当前节标题注入逐节指令；无计划不注入）
        if (currentUserText.trimStart().startsWith("/start")) {
            startSectionSearchNote()?.let { ctx.appendTrailingUserNote(it) }
        }
        if (pendingContinuationNote) {
            ctx.appendTrailingUserNote(CONTINUATION_NOTE)
            // 一次性消费只在 wire 装配侧（!trimOnly）：session 侧预裁 transformContext
            // 先于 loop 侧执行且其产物不上 wire，在其上消费会让续讲指令永远到不了
            // wire（P3/P4 隐性缺陷；P5 审查修复——trimOnly 装配只注入计费不翻转旗标）
            if (!ctx.trimOnly) pendingContinuationNote = false
        }
    }

    /**
     * 落库前变换（T2 pre 钩子；经 AgentSession.persistTransform 挂入）：
     * 仅作用于末条 assistant 消息（含 ToolCall 的中间 turn 不动）；拆条/清洗
     * 均经 [ContentProtocol.parse]（分隔符截断、CoT 头剥除、英文废稿清洗、锚点
     * 剥离、TS 提取、剧本拆条——管线同源）。落库修饰标记（截断/无回答）先剥
     * 再解析：旧链拆条路径不经 finalize（不落标记），语义等价。
     */
    fun transformPersist(messages: List<AgentMessage>): List<AgentMessage> {
        val last = messages.indexOfLast { it.role == Role.ASSISTANT }
        if (last < 0) return messages
        val msg = messages[last]
        if (msg.blocks.any { it is ContentBlock.ToolCall }) return messages
        var raw = msg.blocks.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }
        raw = raw.trimEnd()
            .removeSuffix(RunMarkerTexts.TRUNCATION_MARKER.trimEnd()).trimEnd()
            .removeSuffix(RunMarkerTexts.NO_ANSWER_MARKER.trimEnd()).trimEnd()
        if (raw.isBlank()) return messages
        val outcome = ContentProtocol.parse(raw, speakers)
        return if (outcome.turns != null) {
            // 拆条：首行 Thinking + usage（meta 挂首行），逐说话人 sender，同 turnId
            val split = outcome.turns.mapIndexed { i, turn ->
                AgentMessage(
                    id = "${msg.id}:s$i",
                    role = Role.ASSISTANT,
                    blocks = if (i == 0) {
                        msg.blocks.filterIsInstance<ContentBlock.Thinking>() + ContentBlock.Text(turn.text)
                    } else listOf(ContentBlock.Text(turn.text)),
                    sender = SenderInfo(name = turn.speaker, model = msg.sender?.model, colorHex = msg.sender?.colorHex),
                    meta = if (i == 0) msg.meta else MessageMeta.NONE,
                    turnId = msg.turnId,
                )
            }
            messages.subList(0, last).toList() + split
        } else if (outcome.finalText != raw) {
            // 单条路径：正文以清洗后终稿落库（TS 标记移除 + 全管线清洗的迁移）。
            // raw 是全部 Text 块的拼接 → 终稿收敛为单 Text 块（首个 Text 位），Thinking 原位保留
            val cleanedBlocks = buildList {
                var placed = false
                for (b in msg.blocks) {
                    if (b is ContentBlock.Text) {
                        if (!placed) {
                            add(ContentBlock.Text(outcome.finalText))
                            placed = true
                        }
                    } else add(b)
                }
            }
            messages.subList(0, last).toList() + msg.copy(blocks = cleanedBlocks)
        } else {
            messages
        }
    }

    /**
     * 落库后段级回调（挂 AgentSession.onPersisted 构造位，P4 T4）：本 run 拆条组的
     * 每行回调 [onSegmentPersisted]。组 = **末条 assistant 的 turnId 同值行**——带
     * 工具往返的 run 里前置 ToolCall assistant 行（turnId="$runId:0"）不属拆条组
     * （终稿组是 "$runId:末turn"），按同值过滤才不虚增 total；单条路径 run 的组
     * 即 1 行（(turnId, 0, 1)，普通回答同样触发，旧链每次收线都喊完成/朗读的语义）。
     * rowIds 与 messages 一一对齐（引擎契约），本回调不消费行 id。
     */
    fun onSegmentsPersisted(messages: List<AgentMessage>, rowIds: List<Long>) {
        val last = messages.lastOrNull { it.role == Role.ASSISTANT } ?: return
        val group = messages.filter { it.role == Role.ASSISTANT && it.turnId == last.turnId }
        group.forEachIndexed { index, msg ->
            onSegmentPersisted(msg.turnId, index, group.size)
        }
    }

    /**
     * TS 提取落库 + 续讲编排。取消 run 不推进（:1230 的 streamError==null 门：
     * 停止/硬失败不续讲）。分支序照抄 :1231-1252：shouldContinue 先于废稿重试
     * （拆出剧本即续讲，废稿判定只兜无剧本回复）。
     *
     * 结构约束（类 KDoc chain.scope 纪律）：分支判定同步做——确认发后续 run 才
     * 挂 chain.scope gap 协程；TS 落列走宿主 scope（不发 run 的动作不进终态判据）。
     */
    override fun onRunEnd(result: RunResult, chain: RunChain) {
        if (result.cancelled) return
        val outcome = ContentProtocol.parse(result.finalText, speakers)
        // TS 提取：内存态同步刷新（下一 run 注入源）+ 列落库异步（宿主 scope）
        outcome.stateJson?.let { json ->
            tutorStateJson = json
            // 旁路 launch 补捕获（审计 R1，任务 1.1）：落列失败只告警，不逃逸
            scope.launch {
                try {
                    repo.updateTutorState(conversationId, json)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("ClassroomHooks", "background hook failed", e)
                }
            }
        }
        if (result.steeringLeftover) return   // P-steer 闸①（spec §3.3 两道闸）：本 run 收束由 executeRun 的残留转投负责，autoContinue/废稿不另起 run（防同链双 executeRun）；TS 提取已先行完成，状态列不丢
        val runner = followUp ?: return
        if (!chain.scope.isActive) return   // 取消护栏：链死不发后续 run（E2E 同款）
        // 续讲/废稿重试判定（VM :1229-1252 分支序照抄），同步求值
        val shouldContinue = when {
            outcome.phase == "await_user" -> false
            outcome.turns != null -> true
            else -> false
        }
        val continueBranch = shouldContinue && chain.context.depth < maxDepth
        val retryBranch = !continueBranch &&
            outcome.isDraft && chain.context.depth == 0 && currentUserText.isNotBlank()
        if (!continueBranch && !retryBranch) return   // 无后续 run：链上不留协程
        // 旁路 launch 补捕获（审计 R1，任务 1.1）：gap/续跑协程失败只告警，不逃逸
        // 无人 await 的旁路；取消（链取消）原样传播。
        chain.scope.launch {
            try {
                if (continueBranch) {
                    chain.context.depth++
                    pendingContinuationNote = true
                    delay(gapMs)   // 段间停顿挂 chain.scope：链取消即终止（不复活）
                } else {
                    chain.context.depth++   // 废稿重试限一次：重试段 depth=1，再废稿不再重试
                }
                runner()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("ClassroomHooks", "background hook failed", e)
            }
        }
    }

    // ---------- 状态段注入文案（照抄 TutorPrompts.stateToPromptBlock:287-313，不 import） ----------

    /** TS 状态 JSON 结构（ContentProtocol.ClassroomState / TutorPrompts.TutorState 同构复制；v 字段读侧不消费） */
    private data class ClassroomState(
        val sections: List<ClassroomSection>? = null,
        val current: Int? = null,
        val phase: String? = null,
        val quizRight: Int? = null,
        val quizTotal: Int? = null,
        val weak: List<String>? = null,
        val digest: String? = null,
    )

    private data class ClassroomSection(val t: String, val goal: String? = null, val st: String? = null)

    /** 状态 JSON 宽容解析（脏数据/结构不符返回 null，不抛） */
    private fun parseState(stateJson: String?): ClassroomState? {
        if (stateJson.isNullOrBlank()) return null
        return try {
            gson.fromJson(stateJson, ClassroomState::class.java)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * /start 开讲前搜索指令：按学习计划当前节标题逐节注入（不是泛泛的"请搜索"——
     * 节名来自 TS 状态 sections[current-1]，正是"创建课程时已写了要学什么"的数据）。
     * 无状态/无 current/节越界/节名空白 → null（不注入，教学流程照旧）。
     * "旧结果不算数"条款（2026-10-31 真机实证）：会话历史里躺过早前轮次的搜索
     * 结果时，模型会把强制前置当已履行直接开讲——必须显式豁免该借口。
     */
    private fun startSectionSearchNote(): String? {
        val st = parseState(tutorStateJson) ?: return null
        val idx = st.current ?: return null
        val topic = st.sections?.getOrNull(idx - 1)?.t?.trim().orEmpty()
        if (topic.isEmpty()) return null
        return "（系统：开讲前置——先联网搜索「$topic」的讲解要点，上下文里已有的旧搜索" +
            "结果不算数，本轮必须重新搜索；拿到新结果前不要输出课堂片段。给学生的内容里" +
            "引用来源编号；没搜到就按既有纪律如实说明。）"
    }

    /** 状态注入提示词的块（防失忆的读侧）——文案与分支逐行照抄 TutorPrompts.stateToPromptBlock */
    private fun stateToPromptBlock(stateJson: String?): String {
        val st = parseState(stateJson) ?: return ""
        val nl = "\n"
        val sb = StringBuilder("# 当前学习进度（系统记录，汇报进度/继续教学时以此为准）").append(nl)
        st.sections?.takeIf { it.isNotEmpty() }?.let { secs ->
            sb.append("课程大纲：").append(nl)
            secs.forEachIndexed { i, sec ->
                val mark = when (sec.st) { "done" -> "✓"; "active" -> "▶"; else -> "○" }
                sb.append(mark).append(i + 1).append(". ").append(sec.t).append(nl)
            }
        }
        st.current?.let { sb.append("当前进行到第 ").append(it).append(" 节。").append(nl) }
        if ((st.quizTotal ?: 0) > 0) {
            sb.append("小测战绩：").append(st.quizRight ?: 0).append('/').append(st.quizTotal).append("。").append(nl)
        }
        st.weak?.takeIf { it.isNotEmpty() }?.let {
            // 注入成本护栏：薄弱点最多 5 条、摘要最多 200 字
            sb.append("薄弱点：").append(it.take(5).joinToString("、")).append("。讲评时优先针对这些。").append(nl)
        }
        st.digest?.takeIf { it.isNotBlank() }?.let { sb.append("最近学习摘要：").append(it.take(200)).append(nl) }
        return sb.toString()
    }

    companion object {
        /** 剧本默认说话人（TutorPrompts.parseClassroomTurns 的硬编码三说话人同源） */
        val DEFAULT_SPEAKERS = listOf("老师", "小柯", "阿穗")

        /** 课堂续讲调度指令（照抄 ChatViewModel:955 的注入文案，:952-957 语义） */
        const val CONTINUATION_NOTE =
            "（系统：继续输出当前小节的下一段课堂片段。若本节已讲完，出小测题并停在 await_user。）"
    }
}
