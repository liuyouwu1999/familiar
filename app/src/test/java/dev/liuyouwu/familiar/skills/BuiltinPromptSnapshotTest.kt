package dev.liuyouwu.familiar.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置 skill 提示词组装快照（P4 删除批 B：util/TutorPrompts、RoundtablePrompts、
 * Eli5Prompts 删除后，prompt 组装断言的迁移新家——"迁移不是删"，被删断言逐条映射见
 * task-6-report.md）。解析/拆条语义在 ContentProtocolTest / MarkerToolAdapterTest，
 * 装配回路由 SkillHostTest 钉死；这里只钉 skill JSON 模板的内容快照。
 *
 * 测试从 `src/test/resources/skills/<id>.json` 读取（与 assets 逐字节一致，
 * 一致性由任务末尾 bash diff 核对，同 SkillRegistryTest 口径）。
 */
class BuiltinPromptSnapshotTest {

    private fun loadJson(id: String): String {
        val stream = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("skills/$id.json"),
        ) { "test resources 缺少 skills/$id.json" }
        return stream.readBytes().toString(Charsets.UTF_8)
    }

    private val ids = listOf("chat", "familiar", "classroom", "eli5", "chatroom", "roundtable")

    private fun registry(): SkillRegistry =
        SkillRegistry.fromAssetsJsons(ids.associateWith { loadJson(it) }) {}

    private fun classroomTemplate(): String =
        registry().get("classroom")!!.systemPrompt.template

    private fun roundtableRolePrompt(name: String): String {
        val multiAgent = registry().get("roundtable")!!.behaviors!!["multiAgent"] as Map<*, *>
        val roles = multiAgent["roles"] as List<*>
        return roles.map { it as Map<*, *> }
            .first { it["name"] == name }
            .let { it["prompt"] as String }
    }

    // ===== 课堂模板（迁自 TutorPromptsTest 的 buildSystemPrompt 断言）=====

    @Test
    fun `classroom template carries all config value slots`() {
        val t = classroomTemplate()
        // 学生画像槽位：旧 buildSystemPrompt 注入 cfg 五字段，现走 {{}} 变量由
        // SkillInterpreter 用 skillParams 同名字段填充（值域不变）
        assertTrue(t.contains("你是\"{{subject}}\"的私人导师"))
        assertTrue(t.contains("全程使用{{language}}教学"))
        assertTrue(t.contains("水平：{{level}}"))
        assertTrue(t.contains("学习风格：{{style}}"))
        assertTrue(t.contains("期望语气：{{tone}}"))
    }

    @Test
    fun `classroom template has teaching loop and discipline sections`() {
        val t = classroomTemplate()
        assertTrue(t.contains("教学循环"))
        assertTrue(t.contains("对话纪律"))
        assertTrue(t.contains("摸底"))
        assertTrue(t.contains("本节讲完"))
        assertTrue(t.contains("绝不透露或暗示答案"))
        assertTrue(t.contains("await_user"))
        assertTrue(t.contains("[老师]"))
    }

    @Test
    fun `all classroom quick commands declared in template`() {
        val t = classroomTemplate()
        val commands = registry().get("classroom")!!.ui?.quickCommands.orEmpty()
        assertTrue("classroom quickCommands 不应为空", commands.isNotEmpty())
        commands.forEach { cmd ->
            assertTrue("模板缺少命令 ${cmd.command}", t.contains(cmd.command))
        }
    }

    @Test
    fun `classroom template keeps progress report state marker protocol`() {
        // 进度上报（系统机制，对学生不可见）：标记格式与占位护栏文案随模板存活
        val t = classroomTemplate()
        assertTrue(t.contains("<!--TS:"))
        assertTrue(t.contains("禁止照抄示例占位文字"))
        assertTrue(t.contains("没有状态变化时绝不要输出该标记"))
    }

    @Test
    fun `classroom keeps classmate interjection discipline and speaker script`() {
        // AI 同学插话纪律（迁自 TutorPrompts.buildClassmatePrompt 的运行侧：
        // 同学不再是独立请求，插话内嵌在课堂片段模板里）+ 剧本三说话人
        val t = classroomTemplate()
        assertTrue(t.contains("AI 同学插话"))
        assertTrue(t.contains("[小柯]"))
        assertTrue(t.contains("[阿穗]"))
        val multiSpeaker = registry().get("classroom")!!.behaviors!!["multiSpeakerScript"] as Map<*, *>
        assertEquals(listOf("老师", "小柯", "阿穗"), multiSpeaker["speakers"])
    }

    @Test
    fun `classroom template keeps anti-jargon red line`() {
        // spec §5 词汇层：讲师说话口语化，不堆术语和套话（教学结构不变）
        assertTrue(classroomTemplate().contains("不堆术语和套话"))
    }

    // ===== 讲人话模板（迁自已删 Eli5Prompts.buildSystemPrompt——原对象无直接测试，
    //      删除前提示词本体迁 skill JSON，此处补齐同强度快照）=====

    @Test
    fun `eli5 template keeps hard rules`() {
        val t = registry().get("eli5")!!.systemPrompt.template
        assertTrue(t.contains("一次只讲一个核心点"))
        assertTrue(t.contains("不用术语"))
        assertTrue(t.contains("必须用类比"))
        assertTrue(t.contains("最多 5 句"))
        assertTrue(t.contains("零开场白"))
        assertTrue(t.contains("先给简单版"))
    }

    @Test
    fun `eli5 template keeps code change three stages and quick commands`() {
        val t = registry().get("eli5")!!.systemPrompt.template
        assertTrue(t.contains("**是什么：**"))
        assertTrue(t.contains("**为什么：**"))
        assertTrue(t.contains("**改法：**"))
        listOf("再简单点", "换个类比", "深入讲讲").forEach { cmd ->
            assertTrue("eli5 模板缺少快捷指令 $cmd", t.contains(cmd))
        }
    }

    // ===== 学习圆桌三角色（迁自 TutorPromptsTest 的 RoundtablePrompts 四例）=====

    @Test
    fun `roundtable lecturer keeps tutoring discipline and anti-jargon line`() {
        val p = roundtableRolePrompt("讲师")
        assertTrue(p.contains("学习圆桌的讲师"))
        assertTrue(p.contains("一次只讲一个概念"))
        assertTrue(p.contains("不替学生做总结"))
        assertTrue(p.contains("不直接铺完整答案"))
        assertTrue(p.contains("抛一个问题"))
    }

    @Test
    fun `roundtable skeptic challenges to understand not to win`() {
        val p = roundtableRolePrompt("怀疑者")
        assertTrue(p.contains("爱追问的同学"))
        assertTrue(p.contains("凭什么"))
        assertTrue(p.contains("追问是为了搞懂，不是为了赢"))
        assertTrue(p.contains("被说服时明确认错"))
    }

    @Test
    fun `roundtable newcomer exposes confusion instead of faking`() {
        val p = roundtableRolePrompt("新手")
        assertTrue(p.contains("完全外行"))
        assertTrue(p.contains("打比方"))
        assertTrue(p.contains("承认不懂"))
        assertTrue(p.contains("不复述术语装懂"))
    }

    @Test
    fun `roundtable opening carries topic and turn-taking instruction`() {
        val opening = (registry().get("roundtable")!!.behaviors!!["openingMessage"] as Map<*, *>)["template"] as String
        assertTrue(opening.startsWith("（圆桌话题：{{topic}}"))
        assertTrue(opening.contains("讲师先开场"))
        assertTrue(opening.contains("一次一句"))
        assertTrue(opening.contains("等人接话"))
    }

    // ===== 存活性兜底：六个 id 全部可读且非空（快照文件不因 registry 契约测试失效而空转）=====

    @Test
    fun `all six skill templates are non blank`() {
        ids.forEach { id ->
            val def = registry().get(id)
            assertNotNull("skill '$id' 未注册", def)
            assertTrue("[$id] template 为空", def!!.systemPrompt.template.isNotBlank())
        }
    }
}
