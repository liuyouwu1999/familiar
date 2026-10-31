package dev.liuyouwu.familiar.skills

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置 6 个 skill JSON + [SkillRegistry] 的加载契约测试（spec §6.1/§6.2/§6.4、Task 3）。
 *
 * 测试从 `src/test/resources/skills/<id>.json` 读取——该目录与
 * `app/src/main/assets/skills/` 逐字节一致（assets 在 APK 内单测读不到，
 * 一致性以 Task 末尾的 bash diff 步骤核对，见任务简报）。
 *
 * 模式段剥离兜底（spec §6.4 评审 A2）：任何 template / 角色 prompt 不得含
 * `[搜索:` 或 `<<<搜索结果` —— 现实提示词里的查证纪律段迁移时必须删净。
 *
 * quickCommands 为快照字面量（P4 删除批 B：util/TutorPrompts/Eli5Prompts 删除，
 * 双跑对照锚点收拢为快照——原对照在 `classroom/eli5 quickCommands equal … COMMANDS`）。
 */
class SkillRegistryTest {

    private val ids = listOf("chat", "familiar", "classroom", "eli5", "chatroom", "roundtable")

    /** test resources 与 assets 逐字节一致（bash diff 核对），这里只走 classpath */
    private fun loadJson(id: String): String {
        val stream = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("skills/$id.json"),
        ) { "test resources 缺少 skills/$id.json" }
        return stream.readBytes().toString(Charsets.UTF_8)
    }

    private fun registry(warn: (String) -> Unit = {}): SkillRegistry =
        SkillRegistry.fromAssetsJsons(ids.associateWith { loadJson(it) }, warn)

    // ===== 六个内置定义全部 parse 成功且 id 集合恰为六个 =====

    @Test
    fun `all six builtin skills parse and register with exact id set`() {
        val w = mutableListOf<String>()
        val reg = registry(w::add)
        ids.forEach { assertNotNull("skill '$it' 未注册", reg.get(it)) }
        assertEquals(ids.toSet(), reg.all().map { it.id }.toSet())
        assertEquals(6, reg.all().size)
        // 内置 JSON 全为已知 behaviors 键且无拒载项，不应产生任何告警
        assertTrue("意外告警：${w.joinToString("; ")}", w.isEmpty())
    }

    @Test
    fun `get returns null for unknown id`() {
        assertNull(registry().get("no-such-skill"))
    }

    // ===== 模式段剥离兜底：不含工具/搜索指令标记 =====

    @Test
    fun `no template contains tool-search instruction markers`() {
        val reg = registry()
        ids.forEach { id ->
            val t = reg.get(id)!!.systemPrompt.template
            assertFalse("[$id] template 残留 [搜索: 指令段", t.contains("[搜索:"))
            assertFalse("[$id] template 残留 <<<搜索结果 指令段", t.contains("<<<搜索结果"))
        }
    }

    @Test
    fun `roundtable role prompts and opening message are stripped and well-formed`() {
        val def = registry().get("roundtable")!!
        val multiAgent = def.behaviors!!["multiAgent"] as Map<*, *>
        val roles = multiAgent["roles"] as List<*>
        assertEquals(listOf("讲师", "怀疑者", "新手"), roles.map { (it as Map<*, *>)["name"] })
        roles.forEach { role ->
            val prompt = (role as Map<*, *>)["prompt"] as String
            assertTrue("角色 prompt 为空", prompt.isNotBlank())
            assertFalse("圆桌角色 prompt 残留查证纪律段", prompt.contains("[搜索:"))
            assertFalse("圆桌角色 prompt 残留查证纪律段", prompt.contains("查证纪律"))
        }
        val opening = (def.behaviors!!["openingMessage"] as Map<*, *>)["template"] as String
        assertTrue("opening 应以（圆桌话题：开头", opening.startsWith("（圆桌话题："))
        assertTrue("opening 应含 {{topic}} 变量", opening.contains("{{topic}}"))
    }

    @Test
    fun `chatroom template is group rules with displayName variable`() {
        val t = registry().get("chatroom")!!.systemPrompt.template
        assertTrue(t.contains("「{{displayName}}」的身份说话"))
        assertTrue(t.contains("去 AI 味"))
        val multiAgent = registry().get("chatroom")!!.behaviors!!["multiAgent"] as Map<*, *>
        assertEquals(emptyList<Any?>(), multiAgent["roles"]) // 成员来自 params，运行时承载
        assertEquals("planner", multiAgent["scheduler"])
    }

    // ===== quickCommands 快照（P4 删除批 B：util COMMANDS 对照收拢为字面量）=====

    @Test
    fun `classroom quickCommands match snapshot pair by pair`() {
        val commands = registry().get("classroom")!!.ui?.quickCommands
        assertNotNull(commands)
        assertFalse("classroom quickCommands 不应为空", commands.isNullOrEmpty())
        assertEquals(
            listOf(
                QuickCommand("/plan", "学习计划"),
                QuickCommand("/start", "开始上课"),
                QuickCommand("/test", "小测一下"),
                QuickCommand("/example", "再举个例子"),
                QuickCommand("/progress", "学习进度"),
            ),
            commands,
        )
    }

    @Test
    fun `eli5 quickCommands match snapshot pair by pair`() {
        val commands = registry().get("eli5")!!.ui?.quickCommands
        assertEquals(
            listOf(
                QuickCommand("再简单点", "再简单点"),
                QuickCommand("换个类比", "换个类比"),
                QuickCommand("深入讲讲", "深入讲讲"),
            ),
            commands,
        )
    }

    // ===== familiar 骨架变量契约（PersonaCard 字段名） =====

    @Test
    fun `familiar skeleton exposes persona card variable slots`() {
        val t = registry().get("familiar")!!.systemPrompt.template
        val vars = Regex("\\{\\{(\\w+)\\}\\}").findAll(t).map { it.groupValues[1] }.toSet()
        // 骨架位变量：PersonaCard 插值位 + mode/memory_note。
        // traits/speechStyle/catchphrase 是 P3 注入的运行时变量（变量名=字段名），
        // 但在 build() 里只出现在 tagBlock/tagGloss 的 ifBlank 派生串内——无骨架位，
        // 派生在 P3 运行时完成后经 {{tagBlock}}/{{tagGloss}} 注入（评审 P1-3）。
        assertEquals(
            setOf("name", "identity", "selfName", "address", "tagBlock", "tagGloss",
                "exampleDialogs", "mode", "memory_note"),
            vars,
        )
    }

    // ===== 宽容加载 =====

    @Test
    fun `unknown behavior key in builtin copy warns but still loads`() {
        val w = mutableListOf<String>()
        val obj = JsonParser.parseString(loadJson("chat")).asJsonObject
        obj.add("behaviors", JsonParser.parseString("""{"warpDrive": true}""").asJsonObject)
        val def = SkillJson.parse(obj.toString(), w::add)
        assertNotNull("未知 behaviors 键不应拒载", def)
        assertTrue(w.any { it.contains("warpDrive") })
    }

    @Test
    fun `malformed json is skipped with warning, the rest still register`() {
        val w = mutableListOf<String>()
        val reg = SkillRegistry.fromAssetsJsons(
            mapOf("bad.json" to "{ not json", "chat.json" to loadJson("chat")),
            w::add,
        )
        assertNull(reg.get("bad"))
        assertNotNull(reg.get("chat"))
        assertTrue(w.any { it.contains("解析失败") })
    }

    @Test
    fun `duplicate parsed id keeps the first definition and warns`() {
        val w = mutableListOf<String>()
        val chat = loadJson("chat")
        val reg = SkillRegistry.fromAssetsJsons(
            mapOf("a.json" to chat, "b.json" to chat),
            w::add,
        )
        assertEquals(1, reg.all().size)
        assertTrue(w.any { it.contains("重复") })
    }

    // ===== user 覆盖 builtin（pi 语义） =====

    private fun def(id: String, name: String) = SkillDefinition(
        id = id, name = name, description = "d", systemPrompt = PromptSpec("t"),
    )

    @Test
    fun `user definition overrides builtin with same id`() {
        val reg = SkillRegistry(
            builtin = mapOf("chat" to def("chat", "builtin"), "eli5" to def("eli5", "builtin")),
            user = mapOf("chat" to def("chat", "user")),
        )
        assertEquals("user", reg.get("chat")?.name)
        assertEquals("builtin", reg.get("eli5")?.name)
        // 同 id 不重复出现，user 独有项追加
        assertEquals(listOf("chat", "eli5"), reg.all().map { it.id })
        assertEquals(listOf("user", "builtin"), reg.all().map { it.name })
    }

    @Test
    fun `empty user map defaults and builtin map iterates standalone`() {
        val reg = SkillRegistry(mapOf("chat" to def("chat", "c")))
        assertEquals(1, reg.all().size)
        assertEquals("c", reg.get("chat")?.name)
    }
}
