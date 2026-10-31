package dev.liuyouwu.familiar.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SkillInterpreter.assemble] 装配契约测试（spec §6.1/§6.5、Task 4）。
 *
 * 职责边界（brief 硬约束）：
 * - interpreter 只产出**基础段**——两模式注入段由 P1 WireConverter.buildSystem 复用，
 *   这里断言产出不含 `[搜索:` / `<<<搜索结果` / `# 工具` 指令段；
 * - chatroom 的 members（JSON 字符串）参数**不在此解析**（P4 orchestrator 消费）；
 * - 空串值变量替换为空（familiar 空行问题为已知推迟项，P3 运行时派生处理）。
 */
class SkillInterpreterTest {

    private val ids = listOf("chat", "familiar", "classroom", "eli5", "chatroom", "roundtable")

    private fun loadJson(id: String): String {
        val stream = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("skills/$id.json"),
        ) { "test resources 缺少 skills/$id.json" }
        return stream.readBytes().toString(Charsets.UTF_8)
    }

    private fun registry(): SkillRegistry =
        SkillRegistry.fromAssetsJsons(ids.associateWith { loadJson(it) })

    private fun interpreter(warn: (String) -> Unit = {}): SkillInterpreter =
        SkillInterpreter(registry(), warn)

    // ===== classroom 全参渲染 =====

    @Test
    fun `classroom full params render replaces vars preserves chinese and carries behavior fields`() {
        val w = mutableListOf<String>()
        val assembled = interpreter(w::add).assemble(
            "classroom",
            mapOf(
                "subject" to "高中物理",
                "level" to "初学者",
                "style" to "例子驱动",
                "tone" to "轻松",
                "language" to "中文",
            ),
        )
        assertNotNull(assembled)
        assembled!!
        val prompt = assembled.systemPrompt
        // 变量替换（含开头画像段与末尾开场白段的 {{subject}} 两处）
        assertTrue("开头段应替换 subject/language", prompt.contains("你是\"高中物理\"的私人导师，不是通用 AI 助手。全程使用中文教学。"))
        assertTrue("画像段应替换 level/style/tone", prompt.contains("水平：初学者。学习风格：例子驱动。期望语气：轻松。"))
        assertTrue("开场白应替换 subject", prompt.contains("你好！我是你的高中物理私教。"))
        assertTrue("中文文案应完整保留", prompt.contains("绝不自称任何模型或厂商名"))
        assertFalse("不应残留任何未替换占位符", prompt.contains("{{"))
        // 基础段不含两模式注入指令（spec §6.4 评审 A2 剥离兜底 + brief 边界）
        assertFalse("基础段不应含 MARKER 工具指令", prompt.contains("[搜索:"))
        assertFalse("基础段不应含搜索结果标记", prompt.contains("<<<搜索结果"))
        assertFalse("基础段不应含 NATIVE 工具指令段", prompt.contains("# 工具"))
        // 装配字段直通（工具面 T6：tools 数组随 JSON 扩为五名——web_search + BASIC 四件；
        // P6 任务4b 起课堂加 pet_react 六名：模型指挥桌宠表演）
        assertEquals("classroom", assembled.skillId)
        assertEquals(
            listOf("web_search", "get_current_time", "read_file", "write_file", "list_files", "pet_react"),
            assembled.toolNames,
        )
        assertEquals("always-on", assembled.searchPolicy)
        assertEquals("classroomScript", assembled.contentProtocol)
        assertEquals(5, assembled.ui.quickCommands?.size)
        assertEquals("学习计划", assembled.ui.quickCommands?.first()?.label)
        // 全参渲染不应有任何缺参告警
        assertTrue("意外告警：${w.joinToString("; ")}", w.isEmpty())
    }

    @Test
    fun `classroom missing param keeps placeholder and warns exactly once`() {
        val w = mutableListOf<String>()
        val assembled = interpreter(w::add).assemble(
            "classroom",
            mapOf(
                "subject" to "高中物理",
                "level" to "初学者",
                "style" to "例子驱动",
                "language" to "中文",
                // tone 缺失
            ),
        )
        assertNotNull(assembled)
        val prompt = assembled!!.systemPrompt
        assertTrue("缺参变量应保留 {{tone}} 占位", prompt.contains("期望语气：{{tone}}。"))
        assertTrue("其余变量照常替换", prompt.contains("高中物理"))
        assertEquals("缺参应恰好告警一次", 1, w.size)
        assertTrue("告警应含变量名与 skillId：${w.first()}", w.first().contains("tone") && w.first().contains("classroom"))
    }

    @Test
    fun `empty-string param substitutes to blank without warn`() {
        // 已知限制（Task 3 移植注记 → P3）：空值变量渲染留空行，interpreter 只做简单替换
        val w = mutableListOf<String>()
        val assembled = interpreter(w::add).assemble(
            "classroom",
            mapOf(
                "subject" to "高中物理",
                "level" to "初学者",
                "style" to "", // 空串：替换为空，不算缺参
                "tone" to "轻松",
                "language" to "中文",
            ),
        )
        assertNotNull(assembled)
        val prompt = assembled!!.systemPrompt
        assertTrue("空串应替换为空而非保留占位符", prompt.contains("学习风格：。"))
        assertFalse(prompt.contains("{{style}}"))
        assertTrue("空串不是缺参，不应告警", w.isEmpty())
    }

    // ===== eli5 / chat 装配 =====

    @Test
    fun `eli5 assembles verbatim template with defaults`() {
        val def = registry().get("eli5")!!
        val w = mutableListOf<String>()
        val assembled = interpreter(w::add).assemble("eli5", emptyMap())
        assertNotNull(assembled)
        assembled!!
        assertEquals(def.systemPrompt.template, assembled.systemPrompt) // 无变量，逐字
        assertTrue(assembled.toolNames.isEmpty()) // tools 默认空
        assertEquals("user-toggle", assembled.searchPolicy) // searchPolicy 默认
        assertNull(assembled.contentProtocol) // 无 contentProtocol → null
        assertEquals(3, assembled.ui.quickCommands?.size)
        assertTrue(w.isEmpty())
    }

    @Test
    fun `chat uses session system_prompt param over template`() {
        val sessionPrompt = "你是一只会说法语的猫，只肯聊鱼。"
        val assembled = interpreter().assemble(
            "chat",
            mapOf("system_prompt" to sessionPrompt, "user_name" to "小明"),
        )
        assertNotNull(assembled)
        assertEquals(sessionPrompt, assembled!!.systemPrompt) // 会话行 prompt 原样直通
        assertFalse("不应回落到占位模板", assembled.systemPrompt.contains("对话伙伴"))
        assertEquals("user-toggle", assembled.searchPolicy)
    }

    @Test
    fun `chat without system_prompt falls back to rendered placeholder template`() {
        val w = mutableListOf<String>()
        val assembled = interpreter(w::add).assemble("chat", mapOf("user_name" to "小明"))
        assertNotNull(assembled)
        assertEquals("你是小明的对话伙伴。", assembled!!.systemPrompt)
        assertTrue(w.isEmpty())
    }

    @Test
    fun `chat fallback with missing user_name keeps placeholder and warns`() {
        val w = mutableListOf<String>()
        val assembled = interpreter(w::add).assemble("chat", emptyMap())
        assertNotNull(assembled)
        assertEquals("你是{{user_name}}的对话伙伴。", assembled!!.systemPrompt)
        assertEquals(1, w.size)
        assertTrue(w.first().contains("user_name"))
    }

    @Test
    fun `chat blank system_prompt counts as absent`() {
        val assembled = interpreter().assemble("chat", mapOf("system_prompt" to "  ", "user_name" to "小明"))
        assertNotNull(assembled)
        assertEquals("你是小明的对话伙伴。", assembled!!.systemPrompt) // 空白串视同未提供
    }

    // ===== roundtable：行为读取（嵌套 dig） =====

    @Test
    fun `roundtable roles and scheduler readable via behaviorReader`() {
        val assembled = interpreter().assemble("roundtable", mapOf("user_name" to "小柯"))
        assertNotNull(assembled)
        assembled!!
        assertEquals("你是小柯的学习圆桌主持人。", assembled.systemPrompt)
        assertEquals("always-on", assembled.searchPolicy)

        val multiAgent = assembled.behaviorReader.raw("multiAgent") as? Map<*, *>
        assertNotNull("behaviorReader 应能读出 multiAgent", multiAgent)
        val roles = multiAgent!!["roles"] as? List<*>
        assertNotNull(roles)
        assertEquals(listOf("讲师", "怀疑者", "新手"), roles!!.map { (it as Map<*, *>)["name"] })
        val lecturer = (roles.first() as Map<*, *>)["prompt"] as String
        assertTrue("讲师 prompt 应为中文人设", lecturer.startsWith("你是学习圆桌的讲师"))
        assertTrue("角色 prompt 不含模式注入段", (roles[1] as Map<*, *>)["prompt"].let { !"$it".contains("[搜索:") })
        assertEquals("planner", multiAgent["scheduler"])

        val opening = (assembled.behaviorReader.raw("openingMessage") as? Map<*, *>)?.get("template") as? String
        assertEquals("（圆桌话题：{{topic}}。讲师先开场，一次一句，等人接话。）", opening)
    }

    @Test
    fun `classroom nested behavior values diggable via behaviorReader`() {
        val assembled = interpreter().assemble(
            "classroom",
            mapOf("subject" to "物理", "level" to "l", "style" to "s", "tone" to "t", "language" to "中文"),
        )
        assertNotNull(assembled)
        assembled!!
        // 嵌套 dig：autoContinue.maxDepth（Gson 数字为 Double，经 Number 归一）
        val autoContinue = assembled.behaviorReader.raw("autoContinue") as? Map<*, *>
        assertEquals(4, (autoContinue?.get("maxDepth") as? Number)?.toInt())
        // 扁平访问器（绑定定义）：bool/string/int 缺省回退
        assertTrue(assembled.behaviorReader.bool("classroomState"))
        assertEquals("classroomScript", assembled.behaviorReader.string("contentProtocol", "x"))
        assertEquals(9, assembled.behaviorReader.int("notExist", 9))
        assertFalse(assembled.behaviorReader.has("notExist"))
    }

    @Test
    fun `chat behaviorReader over empty behaviors falls back to defaults`() {
        val assembled = interpreter().assemble("chat", emptyMap())
        assertNotNull(assembled)
        assertFalse(assembled!!.behaviorReader.bool("anything"))
        assertNull(assembled.behaviorReader.raw("anything"))
    }

    // ===== chatroom：members 不在此解析 =====

    @Test
    fun `chatroom renders displayName but does not parse members json`() {
        val membersJson = """[{"name":"阿狼","prompt":"你是阿狼。"}]"""
        val assembled = interpreter().assemble(
            "chatroom",
            mapOf("displayName" to "阿狼", "members" to membersJson),
        )
        assertNotNull(assembled)
        val prompt = assembled!!.systemPrompt
        assertTrue("displayName 应替换", prompt.contains("只以「阿狼」的身份说话"))
        assertFalse("members JSON 原串不应出现在 prompt", prompt.contains(membersJson))
        assertFalse("members 不应被结构化解析进 prompt", prompt.contains("[{"))
        // 工具面 T6：tools 数组随 JSON 扩为五名（web_search + BASIC 四件）
        assertEquals(
            listOf("web_search", "get_current_time", "read_file", "write_file", "list_files"),
            assembled.toolNames,
        )
        assertEquals("user-toggle", assembled.searchPolicy)
        assertNull(assembled.contentProtocol)
    }

    // ===== 未知 skillId =====

    @Test
    fun `unknown skill id returns null`() {
        assertNull(interpreter().assemble("no-such-skill", emptyMap()))
    }
}
