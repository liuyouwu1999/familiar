package dev.liuyouwu.familiar.skills

/**
 * skill 装配器（spec §6.5）：把 [SkillRegistry] 里的 [SkillDefinition] 按运行时参数
 * 装配为可直接投喂 agent 的 [AssembledSkill]（基础段）。纯函数式，不做 IO 不持状态。
 *
 * 职责边界（Task 4 brief）：
 * - `{{var}}` **简单替换**：params 缺失的变量保留 `{{var}}` 占位并经 [warn] 告警；
 *   空串值照常替换为空——familiar 骨架的空值变量会渲染出空行，这是**已知推迟项**
 *   （Task 3 移植注记）：P3 运行时变量派生（tagBlock/tagGloss 等的 ifBlank 兜底串）
 *   负责消除，interpreter 不做删行/条件块处理。
 * - 两模式注入段**不在此**：NATIVE 的 `# 工具` 段与 MARKER 的 `[搜索: …]` 指令由
 *   P1 `WireConverter.buildSystem(mode,…)` 在请求装配时注入，本类只产出不含任何
 *   模式指令的基础段（内置 JSON 的剥离兜底见 SkillRegistryTest）。
 * - chatroom 的 members 参数（JSON 字符串）**不在此解析**——成员人设由 P4
 *   orchestrator 经 AssembledSkill.behaviorReader/params 自行消费。
 * - **chat 特例**：assemble("chat") 的 systemPrompt = params["system_prompt"]
 *   （会话行 systemPrompt 直通，空白串视同未提供），缺省回落到占位模板的正常
 *   渲染——P3 接线时模板体系并入该参数。
 * - hooks 组合为明确推迟项（spec §6.4，P3）。
 */
class SkillInterpreter(
    private val registry: SkillRegistry,
    private val warn: (String) -> Unit = {},
) {

    /** 未知 skillId → null；已知 id → 基础段装配产物（见类 KDoc 边界） */
    fun assemble(skillId: String, params: Map<String, String>): AssembledSkill? {
        val def = registry.get(skillId) ?: return null
        val behaviors = Behaviors(def)
        return AssembledSkill(
            skillId = def.id,
            systemPrompt = basePrompt(def, params),
            toolNames = def.tools.orEmpty(), // tools 默认空（brief）
            searchPolicy = behaviors.string(BEHAVIOR_SEARCH_POLICY, DEFAULT_SEARCH_POLICY),
            ui = def.ui ?: UiSpec(),
            contentProtocol = behaviors.raw(BEHAVIOR_CONTENT_PROTOCOL) as? String,
            behaviorReader = behaviors,
        )
    }

    /** 基础段：chat 特例优先（会话行 system_prompt 直通），其余走模板渲染 */
    private fun basePrompt(def: SkillDefinition, params: Map<String, String>): String =
        if (def.id == CHAT_SKILL_ID) {
            params[CHAT_PARAM_SYSTEM_PROMPT]?.takeIf { it.isNotBlank() } ?: render(def, params)
        } else {
            render(def, params)
        }

    /** `{{var}}` 简单替换：缺参保留占位并告警，空串照常替换（见类 KDoc 已知推迟项） */
    private fun render(def: SkillDefinition, params: Map<String, String>): String =
        VAR_PATTERN.replace(def.systemPrompt.template) { match ->
            val name = match.groupValues[1]
            val value = params[name]
            if (value == null) {
                warn("SkillInterpreter[${def.id}] 模板变量 '$name' 缺参，保留占位符 {{…}} 原样进入基础段")
                match.value
            } else {
                value
            }
        }

    companion object {
        /** 变量名口径与 SkillRegistryTest 一致：`\w+`，不含空格/中文变量名 */
        private val VAR_PATTERN = Regex("\\{\\{(\\w+)\\}\\}")

        /** chat 特例（brief）：skillId 与会话行 systemPrompt 的参数名 */
        const val CHAT_SKILL_ID = "chat"
        const val CHAT_PARAM_SYSTEM_PROMPT = "system_prompt"

        /** behaviors.searchPolicy 缺省（brief）；policy→searchAllowed 的映射在 P3 运行时 */
        const val DEFAULT_SEARCH_POLICY = "user-toggle"
        private const val BEHAVIOR_SEARCH_POLICY = "searchPolicy"
        private const val BEHAVIOR_CONTENT_PROTOCOL = "contentProtocol"
    }
}

/**
 * 装配产物：agent 侧消费的 skill 基础段（systemPrompt 已渲染、无模式注入段）。
 *
 * [behaviorReader] 绑定源定义——扁平行为开关（bool/int/string/has）直接读，
 * 嵌套值（autoContinue.maxDepth、multiAgent.roles、openingMessage.template 等）
 * 经 `raw(key)` 取出后由调用方自行 dig（Task 1/3 交接注记）。
 */
data class AssembledSkill(
    val skillId: String,
    val systemPrompt: String,
    val toolNames: List<String>,
    val searchPolicy: String,
    val ui: UiSpec,
    val contentProtocol: String?,
    val behaviorReader: Behaviors,
)
