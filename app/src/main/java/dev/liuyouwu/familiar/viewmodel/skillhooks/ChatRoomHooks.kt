package dev.liuyouwu.familiar.viewmodel.skillhooks

import com.google.gson.Gson
import dev.liuyouwu.familiar.agent.MultiAgentOrchestrator
import dev.liuyouwu.familiar.data.local.entity.ChatRoomParticipantEntity
import dev.liuyouwu.familiar.data.local.entity.ConversationEntity
import dev.liuyouwu.familiar.skills.Behaviors
import dev.liuyouwu.familiar.skills.TypeInferer
import dev.liuyouwu.familiar.util.persona.PersonaCard
import dev.liuyouwu.familiar.util.persona.PersonaPromptBuilder

/**
 * 聊天室/圆桌的组装侧构件（P4 T2，spec §5.3）：成员/预设解析 → Orchestrator
 * [MultiAgentOrchestrator.RoleSpec]、圆桌建室锚点消息渲染。**不实现 AgentHooks**
 * ——运行期四挂点无行为：角色 prompt 经 runOnChain 的 systemOverride 生效（群规段
 * 由 [memberPrompt] 并入 prompt，Orchestrator 不重复拼），会话级 hooks 走裸实现。
 *
 * ## 成员解析（brief 评审 P1-3 修订）
 * - **skillParams.members JSON 优先**：`{"members":[{…}]}`，字段与
 *   chat_room_participants 表同名（displayName/modelName/personality/identity/
 *   traits/speechStyle/catchphrase/exampleDialogs/colorHex/sortOrder，T3 建室/
 *   编辑两写点经 [membersArray] 产出此 JSON）——解析为同形实体后与表路径共用
 *   [memberPrompt]；
 * - **无 members 键（P4 前存量聊天室 skillParams=NULL）或键在但畸形（解析失败/
 *   非数组，告警一行）回退读表**（[SkillRepoPort].getParticipants）；members 键在
 *   但为显式空数组 = 配置为空，不回退（空成员由 VM 报"先加角色"）；
 * - 成员模型名：modelName 非空 → RoleSpec.model（Orchestrator modelOverride），
 *   空串 → null（回落 session 构造位 = 全局当前模型——generateCharacterReply
 *   :2493 的 ifBlank 同语义）。
 *
 * ## 群规段语义迁移（等价锚点：ChatRoomPrompts.memberSystem+groupRules）
 * 人设段式+群规**逐字迁移**（P3 ClassroomHooks 同款"不 import util"纪律——
 * ChatRoomPrompts 归 P4 T6 删除，此为唯一实现）；对同成员、同 address 与
 * ChatRoomPrompts.memberSystem 输出逐字一致（SkillHostTest 对照钉死）。
 * SEARCH_RULE（查证纪律）**不在此**：P2 已从 skill JSON 剥离，工具段由适配层
 * （WireConverter.buildSystem）按模式注入——本类不追加（旧 VM 的
 * `memberSystem + SEARCH_RULE` 拼接随旧链 T5 删）。
 *
 * ## 圆桌
 * 角色预设 = skill JSON behaviors.multiAgent.roles（P2 已迁 RoundtablePrompts
 * 三角色，自带纪律不再加查证段）；恒开搜索由 searchPolicy=always-on 映射
 * （SkillHost）；建室锚点消息经 [openingMessage] 渲染 openingMessage.template
 * （`{{var}}` 简单替换、缺参保留占位——SkillInterpreter 同口径），T3 建室流程
 * 取其返回值写库。
 */
class ChatRoomHooks(
    private val repo: SkillRepoPort,
    /** 成员称呼用户的角色侧值（[memberPrompt] 的 address 参；SkillSettingsPort.chatRoomAddress） */
    private val address: () -> String,
    private val warn: (String) -> Unit = {},
) {

    /**
     * 角色/成员 → RoleSpec（SkillHost.roundFor 的装配位）：roundtable = skill
     * JSON 三角色预设（表/参数不参与）；chatroom = members JSON → 表回退。
     * 表读 suspend（Room）——装配侧经 [SkillHost.roundFor] 的挂起通道。
     */
    suspend fun resolveRoles(
        conversation: ConversationEntity,
        skillId: String,
        behaviors: Behaviors,
    ): List<MultiAgentOrchestrator.RoleSpec> =
        if (skillId == TypeInferer.ROUNDTABLE) presetRoles(behaviors)
        else memberRoles(conversation)

    /** skill JSON 预设角色（multiAgent.roles：name/prompt 必备，model/colorHex 可选缺省 null）。 */
    fun presetRoles(behaviors: Behaviors): List<MultiAgentOrchestrator.RoleSpec> {
        val raw = behaviors.raw(MULTI_AGENT) as? Map<*, *> ?: return emptyList()
        val list = raw["roles"] as? List<*> ?: return emptyList()
        return list.mapNotNull { el ->
            val m = el as? Map<*, *> ?: return@mapNotNull null
            val name = (m["name"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val prompt = (m["prompt"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            MultiAgentOrchestrator.RoleSpec(
                name = name,
                prompt = prompt,
                model = (m["model"] as? String)?.takeIf { it.isNotBlank() },
                colorHex = (m["colorHex"] as? String)?.takeIf { it.isNotBlank() },
            )
        }
    }

    /**
     * 成员 → RoleSpec：skillParams.members JSON 优先；无 members（存量 NULL）
     * 回退 participants 表（类 KDoc）。产出按 sortOrder 排序、滤空白名字行。
     */
    suspend fun memberRoles(conversation: ConversationEntity): List<MultiAgentOrchestrator.RoleSpec> {
        val fromParams = parseMembers(conversation.skillParams)
        val members = if (fromParams != null) fromParams else {
            // members 键在但解析失败才告警（存量 NULL/无键是正常回退路径，不噪音）
            if (conversation.skillParams?.contains("members") == true) {
                warn("ChatRoomHooks[${conversation.id}] skillParams.members 解析失败，回退 participants 表")
            }
            repo.getParticipants(conversation.id)
        }
        return members.asSequence()
            .filter { it.displayName.isNotBlank() }
            .sortedBy { it.sortOrder }
            .map {
                MultiAgentOrchestrator.RoleSpec(
                    name = it.displayName,
                    prompt = memberPrompt(it, address()),
                    model = it.modelName.ifBlank { null },
                    colorHex = it.colorHex.ifBlank { null },
                )
            }
            .toList()
    }

    /**
     * 建室锚点消息渲染（openingMessage 行为，brief"文案=skill JSON 模板"）：
     * `{{var}}` 以 skillParams 字符串值简单替换（缺参保留占位）；无行为/渲染
     * 空白 → null（chatroom 恒 null——建室不注入锚点）。
     */
    fun openingMessage(behaviors: Behaviors, params: Map<String, String>): String? {
        val raw = behaviors.raw(OPENING_MESSAGE) as? Map<*, *> ?: return null
        val template = (raw["template"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        val rendered = VAR_RE.replace(template) { m -> params[m.groupValues[1]] ?: m.value }
        return rendered.trim().takeIf { it.isNotEmpty() }
    }

    companion object {

        private const val MULTI_AGENT = "multiAgent"
        private const val OPENING_MESSAGE = "openingMessage"

        /** `{{var}}` 变量名口径与 SkillInterpreter 一致：`\w+` */
        private val VAR_RE = Regex("\\{\\{(\\w+)\\}\\}")

        private val gson = Gson()

        /**
         * 成员 system 拼装（**逐字迁自 ChatRoomPrompts.memberSystem**，逻辑不改）：
         * - identity 非空（人设卡 v2 成员，含圆桌三角色前身）：PersonaPromptBuilder.
         *   build(群聊场景卡, "default", memoryNote=false) + 群规段。聊天室角色不
         *   注入记忆，memoryNote=false 去掉"记忆说明"行（否则是虚假陈述）。
         * - identity 空（旧数据 / 纯 personality 成员）：维持原 personality 拼法，
         *   不回归。
         * 纯函数、无时间/随机：同成员跨轮字节稳定（前缀缓存友好）。
         */
        fun memberPrompt(p: ChatRoomParticipantEntity, address: String = "小刘鸭"): String {
            if (p.identity.isBlank()) {
                // 旧数据兼容：与 v1 generateCharacterReply 的拼法逐字一致
                val personality = p.personality.ifBlank { "一个有趣的群聊成员" }
                return "你正在群聊中扮演「${p.displayName}」。\n\n" +
                    "你的人设：$personality\n\n" +
                    groupRules(p.displayName)
            }
            val card = PersonaCard(
                name = p.displayName,
                identity = p.identity,
                traits = p.traits,
                speechStyle = p.speechStyle,
                catchphrase = p.catchphrase,
                exampleDialogs = p.exampleDialogs,
                // 群聊场景映射（brief）：自称=显示名；对用户的称呼来自独立设置（默认"小刘鸭"）
                selfName = p.displayName,
                address = address
            )
            return PersonaPromptBuilder.build(card, "default", memoryNote = false) +
                "\n\n" + groupRules(p.displayName)
        }

        /**
         * 群规段（**逐字迁自 ChatRoomPrompts.groupRules**）：发言格式硬要求（v1
         * 原文）+ V3「真人感」聊天规则 + 去 AI 味红线（融合 OpenMAIC dialogue-
         * rules，每条都来自真机踩坑）。只作用于聊天室路径；大肥鱼单聊不经过这里。
         */
        fun groupRules(displayName: String): String = """
发言要求：
1. 只以「$displayName」的身份说话，一次只说 1-3 句，口语化
2. 绝不代替其他角色或用户发言
3. 直接输出要说的话：不要加角色名前缀，不要输出 JSON 或任何标记
4. 自然衔接前面的对话，回应别人刚说过的话

聊天方式（先正常聊天，再考虑人设）：
5. 回复跟着当前话题走，每轮有实实在在的内容；不为表现性格硬加口头禅、笑话、安慰或漂亮话
6. 长短不固定：一句说清就不凑两句，聊得投入才多说；不默认每次都解释原因、分析心理、给建议
7. 不复述或重新包装别人的话；别人说烦，不必总结成「你一定很难受」
8. 可以吐槽、接不上话、只回「啊？」「真的假的」，也可以认真讨论；不每次把话题引向人生道理
9. 少用现成安慰句式（「真正重要的是……」「这已经很棒了」「我一直都在」），真会这么说、情境也合适才说
10. 不为延长对话而提问；别人已说清楚的事不换个说法追问
11. 可以误解、开不好笑的玩笑、临时改口、承认不知道；但不刻意加错别字、口吃或无意义语气词
12. 不编造现实经历和共同记忆，不捏造别人说过的话
13. 不必围着用户转：可以回应其他角色、接别人的话、产生分歧
14. 示例对话只示范语气，不是模板；按上下文生成不同的回答，不照抄

去 AI 味（红线，写了就重写）：
15. 反应词不冲头开场：凭什么／慢着／好家伙／就这？这类词不单独起头，挂在完整句里说
16. 开场禁「好／嗯／哦／那么／接下来」，禁「我猜／我觉得」起头；引用别人的话直接说内容，不用「你说／你刚说」开头
17. 「不是……而是……」改成肯定陈述；删段末总结升华；不反问强调、不三连排比、不以金句收尾
18. 说话带主语，别写连续无主语的解说腔；语气词自然放，结论随意一点

本轮接力（上下文里已有其他角色刚说过的话时）：
19. 先读别人刚说的：不复述、不重复打招呼和自我介绍，不把别人讲过的道理换个说法重讲
20. 在别人的话上接：质疑它、补一个例子、往前推一步；认同就一句带过，然后加新东西
        """.trimIndent()

        /**
         * skillParams.members JSON → 同形成员实体（类 KDoc 字段表）：宽容解析——
         * 逐元素跳过非对象/空白名字项。返回 null = **无 members 键或 JSON 畸形**
         * （调用方回退 participants 表）；显式空数组返回空列表（不回退）。
         */
        fun parseMembers(skillParams: String?): List<ChatRoomParticipantEntity>? {
            if (skillParams.isNullOrBlank()) return null
            return try {
                val obj = gson.fromJson(skillParams, com.google.gson.JsonObject::class.java)
                    ?: return null
                val arr = obj.get("members")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
                arr.mapNotNull { el ->
                    val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                    fun str(key: String): String =
                        o.get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: ""
                    ChatRoomParticipantEntity(
                        conversationId = 0L,   // 解析期无宿主行；成员仅按载体字段消费
                        displayName = str("displayName"),
                        modelName = str("modelName"),
                        personality = str("personality"),
                        identity = str("identity"),
                        traits = str("traits"),
                        speechStyle = str("speechStyle"),
                        catchphrase = str("catchphrase"),
                        exampleDialogs = str("exampleDialogs"),
                        colorHex = str("colorHex").ifBlank { "#1976D2" },
                        sortOrder = o.get("sortOrder")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0,
                    )
                }
            } catch (_: Exception) {
                null                                           // 畸形 JSON → 表回退信号
            }
        }

        /**
         * 成员实体 → skillParams.members JSON 数组（T3 建室/编辑两写点的产出侧，
         * [parseMembers] 读侧的对偶契约）：字段与 chat_room_participants 表同名、
         * 序即列表序（sortOrder 由调用方按插入序带在实体上）。round-trip（写→读）
         * 由 SkillHostTest 钉住。
         */
        fun membersArray(members: List<ChatRoomParticipantEntity>): com.google.gson.JsonArray =
            com.google.gson.JsonArray().apply {
                members.forEach { m ->
                    add(com.google.gson.JsonObject().apply {
                        addProperty("displayName", m.displayName)
                        addProperty("modelName", m.modelName)
                        addProperty("personality", m.personality)
                        addProperty("identity", m.identity)
                        addProperty("traits", m.traits)
                        addProperty("speechStyle", m.speechStyle)
                        addProperty("catchphrase", m.catchphrase)
                        addProperty("exampleDialogs", m.exampleDialogs)
                        addProperty("colorHex", m.colorHex)
                        addProperty("sortOrder", m.sortOrder)
                    })
                }
            }
    }
}
