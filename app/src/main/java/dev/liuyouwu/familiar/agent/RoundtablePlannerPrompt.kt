package dev.liuyouwu.familiar.agent

/**
 * 圆桌规划器 prompt 常量（Task 6，spec §4/§7.4）：原 [MultiAgentOrchestrator.planRound]
 * 局部 val 的圆桌专属文案迁入，internal 常量供快照测试（仿 RunMarkerTexts 先例）。
 *
 * **门控红线**：chatroom.json:18 与 roundtable.json 同为 `"scheduler": "planner"`——
 * 闲聊群也走 planRound/scheduleNote。本对象的圆桌规则（固定三角色表/轮尾问句/≤N 句
 * 分派/正反例/无 point 问句兜底）只在 `roundtableStyle=true` 时拼入；chatroom 的
 * 现场文案逐字保留在 MultiAgentOrchestrator 的 else 分支（不抽常量、不加测试——
 * 动它＝闲聊群行为回归）。
 */
internal object RoundtablePlannerPrompt {

    /**
     * 圆桌规划 system：固定三角色表（roundtable.json multiAgent.roles 同名）+
     * 轮尾问句规则（原则 4：没聊透时最后一条必须是向用户的具体问题，回合在问句处
     * 停止；聊透了输出空数组 []）+ point 正反例（原则 2）；[materialSection] =
     * **已渲染段**（[dev.liuyouwu.familiar.skills.profile.MaterialSection.section]
     * 产物，MultiAgentOrchestrator.plannerMaterial 构造位即此）——直接拼尾部，
     * 本层**不二次渲染**（再过 section() 会双「圆桌学习材料」header，评审
     * Finding 1）；null/空白 = 零拼接。圆桌角色固定预设（SkillHost.roundFor 经
     * ChatRoomHooks 解析同名三角色），不接动态角色表。
     */
    fun system(maxTurns: Int, materialSection: String?): String = buildString {
        append(
            """
            你是群聊主持人。阅读下面的群聊记录，为本轮规划一份完整的发言序列。

            可选角色：讲师、怀疑者、新手

            规划原则：
            1. 条数由内容决定：一句话能接住的轮次 1-2 条就够，值得讨论的轮次可以多几条；上限 $maxTurns 条
            2. 每条写清该角色这一句要围绕什么（一个具体的问题/反驳/补充/例子），不要写空泛的"回应一下"
               ❌"讲师补充说明重要性" ✅"讲师指出：材料第 2 段的实验没设对照组，结论站不住"
            3. 角色之间要互相接话：后一条最好回应前一条说过的内容，不各说各的
            4. 没聊透：规划若干条，最后一条必须是某个角色向用户提出的一个具体问题（检查理解或
               征求意见），回合在问句处停止，等用户回答；聊透了：输出空数组 []
            5. 只规划本轮，不要替用户安排发言

            只输出一行 JSON，格式：{"turns":[{"speaker":"角色名","point":"这条要说的要点"}]}
            """.trimIndent()
        )
        // 已渲染段直接拼（评审 Finding 1）：plannerMaterial 已是 section() 产物，再渲染即双 header
        if (!materialSection.isNullOrBlank()) append("\n\n").append(materialSection)
    }

    /**
     * 圆桌规划 user 输入：记录 + 可选画像段（Task 7 传 [MaterialSection] 同款渲染
     * 产物，本层不渲染）+ 收尾指令。[profileSection] = null 时与 chatroom 现场拼法
     * 同形，但 chatroom 分支保留其现场字面（不回改——零回归红线）。
     */
    fun userInput(transcript: String, profileSection: String?): String = buildString {
        append("群聊记录：\n").append(transcript).append("\n\n")
        if (profileSection != null) append(profileSection).append("\n\n")
        append("输出本轮发言规划 JSON。")
    }

    /**
     * 圆桌调度 note 文本（spec §7.2）：≤N 句按角色分派（讲师 3/其余 2）；无 point
     * 时问句兜底（ROUND_ROBIN 降级路径也带轮尾问句）——只产文本，包装成
     * AgentMessage 的包装代码留在 [MultiAgentOrchestrator.scheduleNote] 现场不动。
     */
    fun scheduleNote(role: String, point: String?): String {
        val n = if (role == "讲师") 3 else 2
        val head = "（群聊主持人提示：现在轮到「$role」发言。"
        val body = if (point.isNullOrBlank()) {
            "${head}直接以 $role 的身份说一句该说的话，第一句直接回答/反驳/举例，" +
                "禁止铺垫、客套、复述他人，≤$n 句，最后一句必须向用户提一个具体问题。" +
                "不要输出名字前缀。）"
        } else {
            "${head}这一条的要点：$point。以 $role 的身份直接说话：第一句直接回答/反驳/举例，" +
                "禁止铺垫、客套、复述他人，≤$n 句。不要输出名字前缀。）"
        }
        return body
    }
}
