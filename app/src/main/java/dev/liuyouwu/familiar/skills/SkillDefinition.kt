package dev.liuyouwu.familiar.skills

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

/**
 * 声明式 skill 定义（spec §6.1，`assets/skills/<id>.json`）——后续所有 skill 任务的数据货币。
 *
 * Gson 反序列化绕过 Kotlin 构造器与默认值（评审 P2-1，TutorPrompts.fromJson 同款坑）：
 * JSON 缺 tools/behaviors/paramsSchema/ui（含 UiSpec 各字段、quickCommands 各项）时，
 * Gson 经 Unsafe 直接置 null 而非走 Kotlin 默认值——因此可选字段类型一律可空，
 * 由 [SkillJson.parse] 在解析成功后统一补齐默认再返回。
 * 直接用构造器创建时默认值生效（非 null）；经 Gson 裸反序列化的实例则可能为 null，
 * 消费方请使用 parse 产物，或经 [Behaviors] 访问器读取。
 */
data class SkillDefinition(
    val id: String,
    val name: String,
    /** 缺失/空白 → 拒载（parse 返回 null 并告警，spec §6.4 对齐 pi 规范） */
    val description: String,
    /** {"template": "...{{var}}..."}；template 缺失/空白 → 拒载 */
    val systemPrompt: PromptSpec,
    val tools: List<String>? = emptyList(),
    /** 宽容：未知键仅告警不拒载（spec §6.3，穷举见 [Behaviors.KNOWN_KEYS]） */
    val behaviors: Map<String, Any?>? = emptyMap(),
    val paramsSchema: Map<String, String>? = emptyMap(),
    val ui: UiSpec? = UiSpec(),
)

/** 系统提示词模板，{{var}} 简单替换（渲染在 SkillInterpreter，P2 Task 4） */
data class PromptSpec(val template: String)

/**
 * 快捷命令二元组 (command, label)——源自 TutorPrompts.COMMANDS / Eli5Prompts.COMMANDS
 * 的现实数据形状（评审 P1-2；spec §6.1 示例的 List<String> 以此为准修订）。
 */
data class QuickCommand(val command: String, val label: String)

/**
 * UI 偏好（头像/气泡/输入样式 + 快捷指令条数据源，spec §6.1/§9.2）。
 * 同 [SkillDefinition]：字段可空，parse 归一化后缺省值为 "default"/空列表。
 */
data class UiSpec(
    val avatarStyle: String? = "default",
    val bubbleStyle: String? = "default",
    val inputVariant: String? = "default",
    val quickCommands: List<QuickCommand>? = emptyList(),
)

/**
 * skill JSON 的宽容解析入口。
 *
 * 契约（spec §6.4、评审 P2-1）：
 * - id/description/systemPrompt.template 任一缺失或空白 → null + 告警；
 * - behaviors 未知键 → 告警（含键名）但照常加载；
 * - JSON 畸形/空 → null；
 * - 其余可选字段缺失 → 补齐默认值后返回。
 */
object SkillJson {

    // quickCommands 数组内的 null/非对象元素宽容读取（最终审查 Important-2，P3 files/skills 用户输入面）：
    // 适配器归一为 null 元素，由 normalizeUi 统一跳过+告警——既不让 NPE 穿透 parse
    // （fromJson 的 try-catch 之外），也不让单个坏元素把整个 skill 文件打成畸形拒载
    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(QuickCommand::class.java, QuickCommandAdapter)
        .create()

    /** [QuickCommand] 的宽容 TypeAdapter：null/非对象/缺 command 的元素读成 null；label 缺失回退 command（与 normalizeUi 同口径） */
    private object QuickCommandAdapter : TypeAdapter<QuickCommand>() {
        override fun write(out: JsonWriter, value: QuickCommand?) {
            if (value == null) {
                out.nullValue()
                return
            }
            out.beginObject()
            out.name("command").value(value.command)
            out.name("label").value(value.label)
            out.endObject()
        }

        override fun read(reader: JsonReader): QuickCommand? {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue() // 含 NULL：数组内 null 元素
                return null
            }
            var command: String? = null
            var label: String? = null
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "command" -> command = readLenientString(reader)
                    "label" -> label = readLenientString(reader)
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            return command?.let { QuickCommand(it, label ?: it) }
        }

        private fun readLenientString(reader: JsonReader): String? =
            if (reader.peek() == JsonToken.NULL) {
                reader.nextNull()
                null
            } else {
                reader.nextString()
            }
    }

    fun parse(json: String, warn: (String) -> Unit = {}): SkillDefinition? {
        // Gson 对空串/null 字面量返回 null、对畸形 JSON 抛异常，统一归为拒载
        val raw: SkillDefinition? = try {
            gson.fromJson(json, SkillDefinition::class.java)
        } catch (_: Exception) {
            null
        }
        if (raw == null) {
            warn("SkillDefinition 解析失败：JSON 畸形或为空，拒载")
            return null
        }

        // Gson 绕过 Kotlin 非空类型：必填字段缺失时实际为 null，
        // 经可空局部变量读取后逐一校验（TutorPrompts.fromJson 同款防御）
        val id: String? = raw.id
        val name: String? = raw.name
        val description: String? = raw.description
        val prompt: PromptSpec? = raw.systemPrompt
        val template: String? = prompt?.template
        if (id.isNullOrBlank()) {
            warn("SkillDefinition 解析失败：id 缺失或空白，拒载")
            return null
        }
        if (description.isNullOrBlank()) {
            warn("SkillDefinition[$id] 解析失败：description 缺失或空白，拒载（spec §6.4）")
            return null
        }
        if (template.isNullOrBlank()) {
            warn("SkillDefinition[$id] 解析失败：systemPrompt.template 缺失或空白，拒载")
            return null
        }

        // 宽容：未知 behavior 开关仅告警不拒载（spec §6.3，新增开关需内核发版）
        raw.behaviors?.keys?.forEach { key ->
            if (key !in Behaviors.KNOWN_KEYS) {
                warn("SkillDefinition[$id] behaviors 含未知开关 '$key'（spec §6.3），已忽略")
            }
        }

        return SkillDefinition(
            id = id,
            name = name ?: id, // name 非拒载项，缺失时回退 id
            description = description,
            systemPrompt = PromptSpec(template),
            tools = raw.tools ?: emptyList(),
            behaviors = raw.behaviors ?: emptyMap(),
            paramsSchema = raw.paramsSchema ?: emptyMap(),
            ui = normalizeUi(raw.ui, id, warn),
        )
    }

    /** 补齐 UiSpec 默认值；跳过 quickCommands 里的 null/非对象/缺 command 项与空白 command 项（仅告警，不拒载不抛异常） */
    private fun normalizeUi(raw: UiSpec?, id: String, warn: (String) -> Unit): UiSpec {
        val ui = raw ?: return UiSpec()
        val quickCommands = ui.quickCommands.orEmpty().mapNotNull { element ->
            // Gson 路径的 null/非对象/缺 command 元素已由 QuickCommandAdapter 归一为 null；
            // 手工构造（List 协变）也可能混入 null——统一在此跳过+告警，绝不让 NPE 穿透 parse
            // （最终审查 Important-2：此处位于 fromJson 的 try-catch 之外，崩溃会穿透注册表构造）
            val item: QuickCommand? = element
            if (item == null) {
                warn("SkillDefinition[$id] ui.quickCommands 含 null/非对象/缺 command 的无效项，已跳过")
                null
            } else {
                val command: String? = item.command
                val label: String? = item.label
                if (command.isNullOrBlank()) {
                    warn("SkillDefinition[$id] ui.quickCommands 含无效项（command 缺失或空白），已丢弃")
                    null
                } else {
                    QuickCommand(command, label ?: command)
                }
            }
        }
        return UiSpec(
            avatarStyle = ui.avatarStyle ?: "default",
            bubbleStyle = ui.bubbleStyle ?: "default",
            inputVariant = ui.inputVariant ?: "default",
            quickCommands = quickCommands,
        )
    }
}

/**
 * behaviors Map 的宽容访问器（Task 4/5 消费口径）。
 *
 * Task 4 起 object 改为**绑定定义的实例读取器**（data class）：[SkillInterpreter.assemble]
 * 产出 AssembledSkill.behaviorReader 时把定义绑进去，P3 运行时/P4 orchestrator 等
 * 消费方读行为不再随身携带 SkillDefinition。Task 1 的静态形态（首参为 def）原样
 * 保留在伴生对象，[SkillJson.parse] 与既有调用点不受影响。
 *
 * 注意 Gson 把一切 JSON 数字读成 Double（评审 P2-1）：[int] 以 `as? Number` 归一，
 * 同时覆盖 Gson 的 Double 与直接构造的 Kotlin Int/Long；类型不符一律回退默认值，不抛异常。
 *
 * 扁平访问器只读顶层键；嵌套值（autoContinue.maxDepth、multiAgent.roles 等）经
 * [raw] 取出后由调用方自行 dig（Task 1/3 交接注记，Task 4 测试有 dig 范式）。
 */
data class Behaviors(private val def: SkillDefinition) {

    /** 缺失/非 Boolean → false */
    fun bool(key: String): Boolean = def.behaviors?.get(key) as? Boolean ?: false

    fun int(key: String, default: Int): Int =
        (def.behaviors?.get(key) as? Number)?.toInt() ?: default

    fun string(key: String, default: String): String =
        def.behaviors?.get(key) as? String ?: default

    fun has(key: String): Boolean =
        def.behaviors?.containsKey(key) == true

    /** 顶层键原始值（嵌套 Map/List/数字原样返回，缺失 → null）；嵌套结构由调用方自行 dig */
    fun raw(key: String): Any? = def.behaviors?.get(key)

    companion object {

        /** 内核内置 behaviors 开关穷举（spec §6.3）；未知键由 SkillJson.parse 告警 */
        val KNOWN_KEYS: Set<String> = setOf(
            "reveal",
            "memory",
            "classroomState",
            "autoContinue",
            "contentProtocol",
            "multiSpeakerScript",
            "multiAgent",
            "searchPolicy",
            "openingMessage",
        )

        fun has(def: SkillDefinition, key: String): Boolean = Behaviors(def).has(key)

        /** 缺失/非 Boolean → false */
        fun bool(def: SkillDefinition, key: String): Boolean = Behaviors(def).bool(key)

        fun int(def: SkillDefinition, key: String, default: Int): Int = Behaviors(def).int(key, default)

        fun string(def: SkillDefinition, key: String, default: String): String = Behaviors(def).string(key, default)
    }
}
