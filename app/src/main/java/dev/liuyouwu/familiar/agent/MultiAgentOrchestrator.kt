package dev.liuyouwu.familiar.agent

import com.google.gson.Gson
import dev.liuyouwu.familiar.core.llm.model.AgentMessage
import dev.liuyouwu.familiar.core.llm.model.ContentBlock
import dev.liuyouwu.familiar.core.llm.model.Role
import dev.liuyouwu.familiar.core.llm.model.SenderInfo
import dev.liuyouwu.familiar.core.llm.model.StreamEvent
import dev.liuyouwu.familiar.core.llm.wire.RoundTurnDto
import dev.liuyouwu.familiar.core.llm.wire.ToolMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * 多角色编排器（P4 T1，spec §5.3 聊天室/圆桌）：一个"回合"（用户输入 → 调度 →
 * N 个角色 run）= **一条轮链**——[AgentSession.acquireChain] 一次，多角色顺序
 * [AgentSession.runOnChain]；stop() 的取消半径=整回合（调度与角色 run 全停，
 * 已产出角色消息保留）。
 *
 * 调度（spec §5.3"planner 整轮规划为主"）：
 * - [Scheduler.PLANNER]：回合先跑**一次轻量调度请求**（指令收敛自现
 *   pickRoundPlan：主持人规划 prompt + `{"turns":[...]}` JSON + 要点注入），
 *   经 [AgentLoop.run] 单 turn 直驱（**不入库**、不驱动会话流式状态、事件不外抛
 *   ——侧信道）；解析失败/请求异常→**降级 [Scheduler.ROUND_ROBIN]**（roles 声明
 *   序轮换，语义收敛自 pickNextSpeaker 兜底），[RoundOutcome.plannerFailed] 置位。
 * - [Scheduler.ROUND_ROBIN]：直接按 roles 声明序各发言一次。
 *
 * 每角色 run（经 runOnChain 全走既有机制——流式发布/持久化钩子/收线/取消贯通）：
 * - systemOverride=角色 prompt（群规段由组装侧 ChatRoomHooks 并入 prompt，本层
 *   不重复拼）；modelOverride=角色模型（null 回落 session 构造位）；sender=
 *   SenderInfo(名字, 模型, 色) 盖章落库（MessageRowMapper sender 三列）；
 * - 历史 wire："名字: "前缀文本（沿用现状 buildRoomHistoryDbMessages 语义：
 *   user→"用户: "、sender 盖章行→名字、无名 assistant→"AI: "），尾部追加调度
 *   user 消息（"现在轮到「X」发言"+规划要点——避开"末尾连续 assistant"类 400）；
 * - **每角色预算：runOnChain 前重置 chain.context.searchBudgetRemaining=1**
 *   （spec §6.2 每角色回合 1 次；ChainContext 默认 2 的落差由此对齐）。
 *
 * 线程模型：suspend、调用方协程内联驱动（与 AgentSession 同口径串行）；取消贯通
 * 分两段（P4 T2 承接①）：调度窗口由 [planRound] 自接哨兵子 Job（调度请求不经
 * executeRun，无哨兵则在飞 SSE 订阅成孤儿跑到自然收完）、角色 run 由 executeRun
 * 哨兵接线即刻取消；调用方协程取消原样传播（整回合作废）。回合收尾经
 * AgentSession.releaseChain 释放链（P4 T2 承接②：一回合=一条链，不跨回合复用）。
 *
 * 每角色终态回调（P4 T4）：[runRound] 内 **runOnChain 返回处**回调 [onRoleTurnEnded]
 * （角色名 + 该角色本条产出正文）——VM 事件桥据此逐角色触发 PetHub/autoRead（恢复
 * 旧链"每角色回完即回完"的频率；旧链聊天室本身无 TTS，此为 P3/P4 事件桥语义的
 * 角色级化）。触发门：`!outcome.cancelled`——停止/硬失败的 run 不触发（对齐旧链
 * "streamError==null 才喊完成"）；回调同步内联（调用方协程内），挂起动作由接收方
 * 自行调度。
 */
class MultiAgentOrchestrator(
    private val session: AgentSession,
    private val loop: AgentLoop,
    /** 调度（主持人）请求的模型供应；默认空串=由组装侧（SkillHost）注入全局当前模型。 */
    private val plannerModel: () -> String = { "" },
    /** 每轮发言条数上限（规划 prompt 的上限约束；语义承接设置项 chatRoomMaxReplies 默认 3）。 */
    private val maxTurnsPerRound: Int = 3,
    /** 每角色 run 终态回调（P4 T4，SkillHost 构造注入）：（角色名, 本条产出正文）。 */
    private val onRoleTurnEnded: (roleName: String, text: String) -> Unit = { _, _ -> },
) {

    /** 单角色规格（组装侧 ChatRoomHooks 从成员参数解析产出）。 */
    data class RoleSpec(
        val name: String,
        val prompt: String,
        val model: String? = null,
        val colorHex: String? = null,
    )

    /** 回合调度策略：PLANNER 整轮规划（失败降级轮换）/ ROUND_ROBIN 轮换。 */
    enum class Scheduler { PLANNER, ROUND_ROBIN }

    /**
     * 执行一个回合（spec §5.3）：acquireChain 一次 → （PLANNER）调度请求 →
     * 按序逐角色 runOnChain。链取消（stop/插话）后剩余角色不再发起。回合收尾
     * 释放链（P4 T2 承接②：下回合 acquire 即新链，预算/查询集全新）。
     *
     * [input] 回合请求历史（含本回合 user 行；调用方持久化 user 行后传入——本层
     * 只落角色产出）；[emit] 角色流式事件汇（转发给 UI 侧；调度请求事件不外抛）。
     */
    suspend fun runRound(
        conversationId: String,
        input: List<AgentMessage>,
        roles: List<RoleSpec>,
        scheduler: Scheduler,
        emit: suspend (StreamEvent) -> Unit,
    ): RoundOutcome {
        val carried = mutableListOf<AgentMessage>()
        if (roles.isEmpty()) return RoundOutcome(carried.toList(), plannerFailed = false)
        val chain = session.acquireChain(conversationId)   // 一回合=一条链
        try {
            var plannerFailed = false
            val turns: List<PlannedTurn> = when (scheduler) {
                Scheduler.ROUND_ROBIN -> roles.map { PlannedTurn(it, point = "") }
                Scheduler.PLANNER -> {
                    val planned = planRound(chain, input, roles)
                    if (planned == null) {
                        plannerFailed = true                   // 解析失败/异常 → 轮换降级
                        roles.map { PlannedTurn(it, point = "") }
                    } else planned
                }
            }

            for (turn in turns) {
                if (!chain.scope.isActive) break               // 取消半径=整回合：后续角色不再发起
                chain.context.searchBudgetRemaining = 1        // 每角色预算重置（§6.2）
                val outcome = session.runOnChain(
                    chain = chain,
                    input = wireHistory(input + carried) + scheduleNote(turn),
                    systemOverride = turn.role.prompt,
                    modelOverride = turn.role.model,
                    sender = turn.role.senderInfo(),
                    emit = emit,
                )
                // 每角色终态回调（P4 T4，runOnChain 返回处）：取消/硬失败的 run 不触发
                //（cancelled 双覆盖——stop 中断与 run 硬失败都置位）；正文=本条产出的
                // Text 块拼接（与落库行同文，persistTransform 聊天室为 null 无变换）
                if (!outcome.cancelled) {
                    val text = outcome.produced
                        .filter { it.role == Role.ASSISTANT }
                        .flatMap { it.blocks.filterIsInstance<ContentBlock.Text>() }
                        .joinToString("\n") { it.text }
                    onRoleTurnEnded(turn.role.name, text)
                }
                // 产出汇总补盖章（LoopOutcome.produced 是循环原始产出，sender 只在落库侧
                // 盖章）——后续角色的"名字: "前缀与 RoundOutcome 的角色归属都依赖它
                carried += outcome.produced.map { it.copy(sender = turn.role.senderInfo()) }
            }
            return RoundOutcome(carried.toList(), plannerFailed)
        } finally {
            // 回合收尾释放（P4 T2 承接②）：本回合各 run 已收线，取消 scope 只释放
            // 资源；下回合 acquireChain 必开新链（预算/查询集全新）。取消路径同样释放
            // （链已被 stop/调用方取消，注销引用即可）。
            session.releaseChain(conversationId)
        }
    }

    // ---------------------------------------------------------------- 调度（PLANNER）

    /**
     * 一次轻量调度请求（收敛自现 pickRoundPlan）：loop.run 单 turn 直驱（不入库、
     * 事件不外抛）；返回 null = 不可用（解析失败/请求异常/全部角色对不上号），
     * 调用方降级 ROUND_ROBIN。链取消（stop 等调度中）→ 空 round（非失败：回合
     * 已被取消，角色循环的 isActive 检查随即收口）。调用方协程取消原样传播。
     *
     * 调度窗口哨兵（P4 T2 承接①，executeRun 同款 Job 形态）：调度请求不经
     * executeRun——流收集挂链作用域（AgentLoop.collectStream 的 withContext），
     * 调用方协程取消不会自动波及在飞 SSE 订阅（孤儿订阅跑到自然收完/readTimeout）。
     * 以哨兵子 Job 把调用方 Job 的**取消起点**接线到 chain.cancel()：调用方
     * cancel() 即刻取消子 Job → 链取消 → 订阅中断（整回合作废，与角色 run 段的
     * executeRun 语义一致）；正常收线 complete() 摘除（cause=null 不触发链取消）。
     */
    private suspend fun planRound(
        chain: RunChain,
        input: List<AgentMessage>,
        roles: List<RoleSpec>,
    ): List<PlannedTurn>? {
        val names = roles.joinToString("、") { it.name }
        val transcript = transcriptOf(input)
        val systemPrompt = """
            你是群聊主持人。阅读下面的群聊记录，为本轮规划一份完整的发言序列。

            可选角色：$names

            规划原则：
            1. 条数由内容决定：一句话能接住的轮次 1-2 条就够，值得讨论的轮次可以多几条；上限 $maxTurnsPerRound 条
            2. 每条写清该角色这一句要围绕什么（一个具体的问题/反驳/补充/例子），不要写空泛的"回应一下"
            3. 角色之间要互相接话：后一条最好回应前一条说过的内容，不各说各的
            4. 如果话题已经聊透、应该轮到用户说话了，输出空数组 []
            5. 只规划本轮，不要替用户安排发言

            只输出一行 JSON，格式：{"turns":[{"speaker":"角色名","point":"这条要说的要点"}]}
        """.trimIndent()
        val callerCancelWatch = currentCoroutineContext()[Job]?.let { callerJob ->
            Job(callerJob).also { watch ->
                watch.invokeOnCompletion { cause ->
                    if (cause is CancellationException) chain.cancel()
                }
            }
        }
        return try {
            val outcome = loop.run(
                input = listOf(
                    AgentMessage(
                        id = "planner-${chain.conversationId}",
                        role = Role.USER,
                        blocks = listOf(ContentBlock.Text("群聊记录：\n$transcript\n\n输出本轮发言规划 JSON。")),
                    )
                ),
                system = systemPrompt,
                tools = emptyList(),
                hooks = object : AgentHooks {},   // 裸 hooks：调度请求不吃会话级上下文变换
                chain = chain,
                mode = ToolMode.NATIVE,
                maxTurns = 1,
                model = plannerModel,
            ) { /* 调度事件不外抛（侧信道，不驱动 UI 流式） */ }
            if (outcome.cancelled) return emptyList()
            // 解析口径收敛自 pickRoundPlan：Text 块拼接（Thinking 不参与）→ turns 数组 → 角色对号
            val raw = outcome.produced
                .lastOrNull { it.role == Role.ASSISTANT }
                ?.blocks?.filterIsInstance<ContentBlock.Text>()
                ?.joinToString("\n") { it.text }
                ?: return null
            val turnsJson = TURNS_JSON_RE.find(raw)?.groupValues?.get(1) ?: return null
            val parsed = runCatching {
                gson.fromJson(turnsJson, Array<RoundTurnDto>::class.java).toList()
            }.getOrNull() ?: return null
            val planned = parsed.take(maxTurnsPerRound).mapNotNull { t ->
                val speaker = t.speaker?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val role = roles.firstOrNull { it.name == speaker }
                    ?: roles.firstOrNull {
                        // 单字名误配风险高：contains 匹配要求名字长度 >= 2（pickRoundPlan 同款）
                        it.name.length >= 2 &&
                            (speaker.contains(it.name) || it.name.contains(speaker))
                    }
                    ?: return@mapNotNull null
                PlannedTurn(role, t.point.orEmpty())
            }
            // 有规划条目却全对不上号 = 规划不可用；空数组（话题聊透交还用户）是合法规划
            if (parsed.isNotEmpty() && planned.isEmpty()) null else planned
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null                                               // 调度请求异常 → 降级
        } finally {
            callerCancelWatch?.complete()   // 本调度收线：哨兵正常完成（无 cause，不触发链取消）
        }
    }

    // ---------------------------------------------------------------- wire 形态

    /** 紧凑群聊记录（收敛自 buildRoomTranscript）：末 N 行"名字: 内容"，长句截断。 */
    private fun transcriptOf(messages: List<AgentMessage>): String =
        messages.takeLast(PLANNER_TRANSCRIPT_MESSAGES).joinToString("\n") { m ->
            val text = m.blocks.filterIsInstance<ContentBlock.Text>()
                .joinToString("\n") { it.text }.take(PLANNER_TRANSCRIPT_LINE_CLAMP)
            "${speakerNameOf(m)}: ${if (text.isBlank()) "[附件]" else text}"
        }

    /**
     * 历史 wire 转换（沿用 buildRoomHistoryDbMessages 语义）：Text 块前加
     * "名字: "前缀（user→"用户: "、sender 盖章行→名字、无名 assistant→"AI: "）；
     * Thinking/工具块原样（思考不回灌正文，工具往返由 WireConverter 折叠）。
     */
    private fun wireHistory(messages: List<AgentMessage>): List<AgentMessage> =
        messages.map { m ->
            val name = speakerNameOf(m)
            m.copy(blocks = m.blocks.map { b ->
                if (b is ContentBlock.Text && b.text.isNotBlank()) ContentBlock.Text("$name: ${b.text}") else b
            })
        }

    private fun speakerNameOf(m: AgentMessage): String = when {
        m.role == Role.USER -> "用户"
        !m.sender?.name.isNullOrBlank() -> m.sender!!.name
        else -> "AI"
    }

    /**
     * 尾部调度 user 消息（收敛自 generateCharacterReply 的 scheduleNote）：以 user
     * 收尾绕开"末尾连续 assistant"类 400；规划要点非空时注入（发言方向由规划定，
     * 措辞自由发挥）。请求专用，不落库。
     */
    private fun scheduleNote(turn: PlannedTurn): AgentMessage {
        val text = if (turn.point.isNotBlank()) {
            "（群聊主持人提示：现在轮到「${turn.role.name}」发言。这一条的要点：${turn.point}。" +
                "直接输出 ta 要说的话，不要输出名字前缀，要点自然融入不要照抄。）"
        } else {
            "（群聊主持人提示：现在轮到「${turn.role.name}」发言。直接输出 ta 要说的话，不要输出名字前缀。）"
        }
        return AgentMessage(
            id = "note-${turn.role.name}-${turn.point.hashCode()}",
            role = Role.USER,
            blocks = listOf(ContentBlock.Text(text)),
        )
    }

    /** 角色外观（sender 三列盖章位）。 */
    private fun RoleSpec.senderInfo(): SenderInfo = SenderInfo(name, model, colorHex)

    /** 调度产物的一步：某角色带要点发言。 */
    private data class PlannedTurn(val role: RoleSpec, val point: String)

    private companion object {
        /** 调度请求的记录行数上限（buildRoomTranscript maxMessages=30 同值）。 */
        const val PLANNER_TRANSCRIPT_MESSAGES = 30

        /** 调度记录单行截断（buildRoomTranscript take(150) 同值）。 */
        const val PLANNER_TRANSCRIPT_LINE_CLAMP = 150

        /** turns 数组提取（pickRoundPlan 同款正则）。 */
        val TURNS_JSON_RE = Regex("\\{\\s*\"turns\"\\s*:\\s*(\\[[\\s\\S]*?\\])\\s*\\}")

        val gson: Gson = Gson()
    }
}

/** 一个回合的收线产物：本回合各角色 run 的产出消息汇总（补 sender 盖章，与落库行同序）+ 调度是否降级。 */
data class RoundOutcome(
    val carriedRoles: List<AgentMessage>,
    val plannerFailed: Boolean,
)
