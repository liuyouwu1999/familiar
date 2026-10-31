package dev.liuyouwu.familiar.skills

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SkillDefinition 模型与 [SkillJson.parse] 宽容解析的契约测试（spec §6.1/§6.4、评审 P1-2/P2-1）。
 */
class SkillDefinitionTest {

    private val gson = Gson()

    /** spec §6.1 示例形状的全字段定义 */
    private val fullJson = """
        {
          "id": "classroom",
          "name": "课堂私教",
          "description": "苏格拉底式私教，多说话人剧本，带学习进度跟踪与联网查证",
          "systemPrompt": { "template": "你是{{user_name}}的私教，课程：{{syllabus}}" },
          "tools": ["web_search"],
          "behaviors": {
            "classroomState": true,
            "autoContinue": { "maxDepth": 4 },
            "contentProtocol": "classroomScript",
            "searchPolicy": "always-on"
          },
          "paramsSchema": { "syllabus": "text", "searchBudget": "int=2" },
          "ui": {
            "avatarStyle": "initial-badge",
            "bubbleStyle": "default",
            "inputVariant": "default",
            "quickCommands": [
              { "command": "/plan", "label": "学习计划" },
              { "command": "/test", "label": "小测一下" }
            ]
          }
        }
    """.trimIndent()

    /** 最小合法定义；extra 以 ", \"key\": ..." 形式拼接到顶层（systemPrompt 之后） */
    private fun minimal(extra: String = ""): String = """
        {
          "id": "chat",
          "name": "默认对话",
          "description": "随口聊聊",
          "systemPrompt": { "template": "你是{{user_name}}的对话伙伴。" }$extra
        }
    """.trimIndent()

    private fun warns(): MutableList<String> = mutableListOf()

    // ===== 全字段往返 =====

    @Test
    fun `full-field json parses with every field intact`() {
        val w = warns()
        val def = SkillJson.parse(fullJson, w::add)
        assertNotNull(def)
        def!!
        assertEquals("classroom", def.id)
        assertEquals("课堂私教", def.name)
        assertEquals("苏格拉底式私教，多说话人剧本，带学习进度跟踪与联网查证", def.description)
        assertEquals("你是{{user_name}}的私教，课程：{{syllabus}}", def.systemPrompt.template)
        assertEquals(listOf("web_search"), def.tools)
        assertEquals(
            mapOf(
                "classroomState" to true,
                "autoContinue" to mapOf("maxDepth" to 4.0),   // Gson 把 JSON 数字一律读成 Double（评审 P2-1）
                "contentProtocol" to "classroomScript",
                "searchPolicy" to "always-on",
            ),
            def.behaviors,
        )
        assertEquals(mapOf("syllabus" to "text", "searchBudget" to "int=2"), def.paramsSchema)
        assertEquals("initial-badge", def.ui?.avatarStyle)
        assertEquals("default", def.ui?.bubbleStyle)
        assertEquals("default", def.ui?.inputVariant)
        assertEquals(
            listOf(QuickCommand("/plan", "学习计划"), QuickCommand("/test", "小测一下")),
            def.ui?.quickCommands,
        )
        assertTrue(w.isEmpty()) // 全已知键，不应有告警
    }

    @Test
    fun `gson numbers inside behaviors are doubles`() {
        val def = SkillJson.parse(fullJson)
        assertNotNull(def)
        val autoContinue = def!!.behaviors?.get("autoContinue") as Map<*, *>
        assertEquals(4.0, autoContinue["maxDepth"])
    }

    @Test
    fun `parsed definition roundtrips through gson`() {
        val def = SkillJson.parse(fullJson)
        assertNotNull(def)
        val back = SkillJson.parse(gson.toJson(def))
        assertEquals(def, back)
    }

    // ===== 拒载条件 =====

    @Test
    fun `missing description rejects with warning`() {
        val w = warns()
        val def = SkillJson.parse(
            """
            {
              "id": "chat",
              "name": "默认对话",
              "systemPrompt": { "template": "你是对话伙伴。" }
            }
            """.trimIndent(),
            w::add,
        )
        assertNull(def)
        assertTrue(w.any { it.contains("description") })
    }

    @Test
    fun `blank description rejects with warning`() {
        val w = warns()
        val def = SkillJson.parse(minimal().replace("\"随口聊聊\"", "\"   \""), w::add)
        assertNull(def)
        assertTrue(w.any { it.contains("description") })
    }

    @Test
    fun `missing or blank id rejects with warning`() {
        val w1 = warns()
        assertNull(SkillJson.parse(minimal().replace("\"id\": \"chat\",", ""), w1::add))
        assertTrue(w1.any { it.contains("id") })

        val w2 = warns()
        assertNull(SkillJson.parse(minimal().replace("\"chat\"", "\" \""), w2::add))
        assertTrue(w2.any { it.contains("id") })
    }

    @Test
    fun `missing or blank template rejects with warning`() {
        val w1 = warns()
        assertNull(
            SkillJson.parse(
                """
                {
                  "id": "chat",
                  "name": "默认对话",
                  "description": "随口聊聊"
                }
                """.trimIndent(),
                w1::add,
            ),
        )
        assertTrue(w1.any { it.contains("template") })

        val w2 = warns()
        assertNull(SkillJson.parse(minimal().replace("你是{{user_name}}的对话伙伴。", "  "), w2::add))
        assertTrue(w2.any { it.contains("template") })
    }

    @Test
    fun `malformed json returns null`() {
        assertNull(SkillJson.parse("{ not json"))
        assertNull(SkillJson.parse(""))
    }

    // ===== 宽容加载 =====

    @Test
    fun `unknown behavior key warns but still loads`() {
        val w = warns()
        val def = SkillJson.parse(
            minimal(""", "behaviors": { "searchPolicy": "user-toggle", "warpDrive": true }"""),
            w::add,
        )
        assertNotNull(def)
        assertTrue(w.any { it.contains("warpDrive") })
        assertEquals("user-toggle", Behaviors.string(def!!, "searchPolicy", "fallback"))
    }

    @Test
    fun `missing optional fields normalize to defaults`() {
        val def = SkillJson.parse(minimal())
        assertNotNull(def)
        def!!
        assertEquals(emptyList<String>(), def.tools)
        assertEquals(emptyMap<String, Any?>(), def.behaviors)
        assertEquals(emptyMap<String, String>(), def.paramsSchema)
        assertEquals(UiSpec(), def.ui)
        assertEquals(emptyList<QuickCommand>(), def.ui?.quickCommands)
    }

    @Test
    fun `partial ui fills default styles`() {
        val def = SkillJson.parse(
            minimal(""", "ui": { "quickCommands": [{ "command": "/go", "label": "继续" }] }"""),
        )
        assertNotNull(def)
        assertEquals("default", def!!.ui?.avatarStyle)
        assertEquals("default", def.ui?.bubbleStyle)
        assertEquals("default", def.ui?.inputVariant)
        assertEquals(listOf(QuickCommand("/go", "继续")), def.ui?.quickCommands)
    }

    @Test
    fun `missing name falls back to id`() {
        val def = SkillJson.parse(
            """
            {
              "id": "chat",
              "description": "随口聊聊",
              "systemPrompt": { "template": "你是对话伙伴。" }
            }
            """.trimIndent(),
        )
        assertNotNull(def)
        assertEquals("chat", def!!.name)
    }

    // ===== 最终审查 Important-2：quickCommands 数组内 null/非对象元素不得让 NPE 穿透 parse =====

    @Test
    fun `quickCommands null element is skipped with warning`() {
        val w = warns()
        val def = SkillJson.parse(
            minimal(""", "ui": { "quickCommands": [null, { "command": "a", "label": "b" }] }"""),
            w::add,
        )
        assertNotNull(def)
        assertEquals(listOf(QuickCommand("a", "b")), def!!.ui?.quickCommands)
        assertTrue(w.any { it.contains("quickCommands") })
    }

    @Test
    fun `quickCommands non-object element is skipped with warning`() {
        val w = warns()
        val def = SkillJson.parse(
            minimal(""", "ui": { "quickCommands": ["oops", 5, { "command": "a", "label": "b" }] }"""),
            w::add,
        )
        assertNotNull(def)
        assertEquals(listOf(QuickCommand("a", "b")), def!!.ui?.quickCommands)
        assertTrue(w.any { it.contains("quickCommands") })
    }

    // ===== Behaviors 宽容访问器（Task 4/5 消费口径） =====

    @Test
    fun `behaviors accessors read values and fall back to defaults`() {
        val def = SkillDefinition(
            id = "x",
            name = "x",
            description = "x",
            systemPrompt = PromptSpec("t"),
            behaviors = mapOf(
                "flag" to true,
                "asDouble" to 4.0,   // Gson 数字路径
                "asInt" to 3,        // 直接构造的 Kotlin Int 路径
                "txt" to "fast",
                "badBool" to "yes",
                "badNum" to "4",
                "badTxt" to 1.0,
            ),
        )
        assertTrue(Behaviors.bool(def, "flag"))
        assertFalse(Behaviors.bool(def, "missing"))
        assertFalse(Behaviors.bool(def, "badBool"))
        assertEquals(4, Behaviors.int(def, "asDouble", -1))
        assertEquals(3, Behaviors.int(def, "asInt", -1))
        assertEquals(-1, Behaviors.int(def, "missing", -1))
        assertEquals(-1, Behaviors.int(def, "badNum", -1))
        assertEquals("fast", Behaviors.string(def, "txt", "d"))
        assertEquals("d", Behaviors.string(def, "missing", "d"))
        assertEquals("d", Behaviors.string(def, "badTxt", "d"))
        assertTrue(Behaviors.has(def, "flag"))
        assertFalse(Behaviors.has(def, "missing"))
    }

    @Test
    fun `behaviors accessors tolerate null map`() {
        // 模拟未经 parse 归一化的裸实例（Gson 缺字段时置 null，评审 P2-1）
        val def = SkillDefinition(
            id = "x",
            name = "x",
            description = "x",
            systemPrompt = PromptSpec("t"),
            tools = null,
            behaviors = null,
            paramsSchema = null,
            ui = null,
        )
        assertFalse(Behaviors.bool(def, "anything"))
        assertEquals(7, Behaviors.int(def, "anything", 7))
        assertEquals("d", Behaviors.string(def, "anything", "d"))
        assertFalse(Behaviors.has(def, "anything"))
    }
}
