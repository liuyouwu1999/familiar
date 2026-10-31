package dev.liuyouwu.familiar.skills

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TypeInferer 六序判定（spec §6.2 先命中即停）的契约测试：
 * 六步矩阵 + 优先级边界 + 双跑对照（现实量常量副本在测试内自持——util 对象已随
 * P4 删除批 B 删，副本语义即被删 FamiliarKind/Eli5Prompts/TutorPrompts 的逐字拷贝，
 * 生产代码不 import 测试，spec §3 依赖方向）。
 */
class TypeInfererTest {

    /** 旧 FamiliarKind.configJson(1L) 产物的常量副本（kind 标记判定样例） */
    private val FAMILIAR_CONFIG_JSON = """{"kind":"familiar","cardId":1}"""

    /** 旧 Eli5Prompts.CONFIG_JSON 的常量副本 */
    private val ELI5_CONFIG_JSON = """{"kind":"eli5"}"""

    /** 合法课堂 JSON 常量副本（原由 TutorPrompts.toJson 生成——Gson 声明序、
     *  classmate null 省略；保证形状与现实库中数据一致） */
    private val classroomJson =
        """{"subject":"线性代数","level":"入门（知道基本概念）","style":"讲解型（通俗演示为主）","tone":"鼓励式","language":"中文"}"""

    /** 五字段缺一（language 缺）：Gson 解析成功但非空字段实为 null → 非法 */
    private val missingOneFieldJson =
        """{"subject":"数学","level":"入门（知道基本概念）","style":"讲解型（通俗演示为主）","tone":"鼓励式"}"""

    /** 畸形 JSON：Gson 抛异常 */
    private val malformedJson = """{"kind": """

    /** 同时含 kind:familiar 且五字段齐全可解析为课堂（优先级边界样例） */
    private val familiarPlusClassroomJson =
        """{"kind":"familiar","cardId":7,"subject":"数学","level":"入门（知道基本概念）",""" +
            """"style":"讲解型（通俗演示为主）","tone":"鼓励式","language":"中文"}"""

    // ===== 六步矩阵（每步独立命中） =====

    @Test
    fun `step1 familiar config wins`() {
        assertEquals(
            "familiar",
            TypeInferer.infer(FAMILIAR_CONFIG_JSON, isChatRoom = false, roundtableAnchorCount = 0),
        )
    }

    @Test
    fun `step2 eli5 config wins`() {
        assertEquals(
            "eli5",
            TypeInferer.infer(ELI5_CONFIG_JSON, isChatRoom = false, roundtableAnchorCount = 0),
        )
    }

    @Test
    fun `step3 valid classroom config wins`() {
        assertEquals(
            "classroom",
            TypeInferer.infer(classroomJson, isChatRoom = false, roundtableAnchorCount = 0),
        )
    }

    @Test
    fun `step4 roundtable by anchor count in chat room`() {
        // 锚点 = 消息 LIKE '（圆桌话题：%'（MessageDao.countRoundtableAnchors），迁移方以游标 COUNT 传入
        assertEquals("roundtable", TypeInferer.infer(null, isChatRoom = true, roundtableAnchorCount = 1))
        assertEquals("roundtable", TypeInferer.infer(null, isChatRoom = true, roundtableAnchorCount = 3))
    }

    @Test
    fun `step5 chatroom by flag alone`() {
        assertEquals("chatroom", TypeInferer.infer(null, isChatRoom = true, roundtableAnchorCount = 0))
    }

    @Test
    fun `step6 everything else falls to chat`() {
        assertEquals("chat", TypeInferer.infer(null, isChatRoom = false, roundtableAnchorCount = 0))
        assertEquals("chat", TypeInferer.infer("", isChatRoom = false, roundtableAnchorCount = 0))
        assertEquals("chat", TypeInferer.infer("随便一个旧标题", isChatRoom = false, roundtableAnchorCount = 0))
    }

    // ===== 非法配置的落点（垃圾 JSON → chat 或 chatroom） =====

    @Test
    fun `garbage json falls to chat outside chat room`() {
        assertEquals("chat", TypeInferer.infer(malformedJson, isChatRoom = false, roundtableAnchorCount = 0))
        assertEquals("chat", TypeInferer.infer(missingOneFieldJson, isChatRoom = false, roundtableAnchorCount = 0))
    }

    @Test
    fun `garbage json falls to chatroom inside chat room`() {
        assertEquals("chatroom", TypeInferer.infer(malformedJson, isChatRoom = true, roundtableAnchorCount = 0))
        assertEquals("chatroom", TypeInferer.infer(missingOneFieldJson, isChatRoom = true, roundtableAnchorCount = 0))
    }

    // ===== 优先级边界（先命中即停） =====

    @Test
    fun `familiar beats parseable classroom config`() {
        // 同时含 kind:familiar 且可被 Gson 解析为合法课堂 → step1 先命中即停
        assertEquals(
            "familiar",
            TypeInferer.infer(familiarPlusClassroomJson, isChatRoom = false, roundtableAnchorCount = 0),
        )
        // 在圆桌聊天室里同样先命中 familiar
        assertEquals(
            "familiar",
            TypeInferer.infer(familiarPlusClassroomJson, isChatRoom = true, roundtableAnchorCount = 5),
        )
    }

    @Test
    fun `eli5 beats parseable classroom config`() {
        val eli5PlusClassroom = """{"kind":"eli5","subject":"数学","level":"入门（知道基本概念）",""" +
            """"style":"讲解型（通俗演示为主）","tone":"鼓励式","language":"中文"}"""
        assertEquals("eli5", TypeInferer.infer(eli5PlusClassroom, isChatRoom = false, roundtableAnchorCount = 0))
    }

    @Test
    fun `classroom beats roundtable and chatroom`() {
        // 合法课堂配置 + isChatRoom 且锚点 >0：step3 先于 step4 命中
        assertEquals("classroom", TypeInferer.infer(classroomJson, isChatRoom = true, roundtableAnchorCount = 3))
    }

    @Test
    fun `chatroom when anchors zero and config invalid`() {
        // isChatRoom 且锚点 0 且 tutorConfig 非法 → chatroom（step4 不满足，落 step5）
        assertEquals("chatroom", TypeInferer.infer(missingOneFieldJson, isChatRoom = true, roundtableAnchorCount = 0))
    }

    @Test
    fun `anchor count alone without chatroom flag stays chat`() {
        // 圆桌判定要求 isChatRoom（step4 是 && 语义）；非聊天室里的圆桌话题锚点不升级会话
        assertEquals("chat", TypeInferer.infer(null, isChatRoom = false, roundtableAnchorCount = 5))
    }

    // ===== 双跑对照：与现实实现结论一致 =====

    /**
     * 现实现的类型判定链（常量副本做对照基准，原 util 对象已删、生产代码不 import）：
     * FamiliarKind.isFamiliar → Eli5Prompts.isEli5 → TutorPrompts.fromJson，全不中 → null。
     * 三个函数体均为被删对象的逐字拷贝（contains 语义 / Gson 反序列化 + 五字段判 null）。
     */
    private fun legacyIsFamiliar(cfg: String?): Boolean =
        cfg != null && cfg.contains("\"kind\":\"familiar\"")

    private fun legacyIsEli5(cfg: String?): Boolean =
        cfg != null && cfg.contains("\"kind\":\"eli5\"")

    /** TutorPrompts.TutorConfig 同构副本（@SerializedName 同名，五字段判 null 语义） */
    private data class LegacyTutorConfig(
        val subject: String?,
        val level: String?,
        val style: String?,
        val tone: String?,
        val language: String?,
    )

    private val legacyGson = Gson()

    private fun legacyClassroomValid(cfg: String?): Boolean = try {
        // Gson 反序列化绕过 Kotlin 构造器，缺字段时非空类型可能实际为 null，逐一校验
        val c = legacyGson.fromJson(cfg, LegacyTutorConfig::class.java)
        c?.subject != null && c.level != null && c.style != null && c.tone != null && c.language != null
    } catch (_: Exception) {
        false
    }

    private fun legacyConfigType(cfg: String?): String? = when {
        legacyIsFamiliar(cfg) -> "familiar"
        legacyIsEli5(cfg) -> "eli5"
        else -> if (legacyClassroomValid(cfg)) "classroom" else null
    }

    @Test
    fun `infer agrees with current impls across six samples`() {
        val samples = listOf(
            FAMILIAR_CONFIG_JSON,              // familiar
            ELI5_CONFIG_JSON,                 // eli5
            classroomJson,                     // classroom（现实现序列化器产物）
            missingOneFieldJson,               // 五字段缺一 → 非法
            malformedJson,                     // Gson 解析失败 → 非法
            familiarPlusClassroomJson,         // 边界：配置命中优先
        )
        for (cfg in samples) {
            val legacy = legacyConfigType(cfg)
            // 配置命中（step1-3）：与现实实现链同结论
            assertEquals(
                "config branch mismatch for: $cfg",
                legacy ?: "chat",
                TypeInferer.infer(cfg, isChatRoom = false, roundtableAnchorCount = 0),
            )
            // 配置命中优先于 isChatRoom 分支（step1-3 先于 step4-5）
            assertEquals(
                "config branch should beat chatroom flag for: $cfg",
                legacy ?: "chatroom",
                TypeInferer.infer(cfg, isChatRoom = true, roundtableAnchorCount = 0),
            )
            if (legacy == null) {
                // 配置非法时 isChatRoom 分支接管（step4-6）
                assertEquals(
                    "roundtable",
                    TypeInferer.infer(cfg, isChatRoom = true, roundtableAnchorCount = 2),
                )
            }
        }
    }

    @Test
    fun `parseClassroomConfig matches TutorPrompts fromJson on samples`() {
        val configs = listOf(
            null,
            "",
            "null",
            "{}",
            "[]",                       // 数组对对象：JsonSyntaxException → 双方 false
            classroomJson,
            missingOneFieldJson,
            malformedJson,
            FAMILIAR_CONFIG_JSON, // kind:familiar 无五字段 → 双方 false
            ELI5_CONFIG_JSON,
            familiarPlusClassroomJson,   // 五字段齐全（多余键被忽略）→ 双方 true
        )
        for (cfg in configs) {
            val legacyValid = legacyClassroomValid(cfg)
            assertEquals(
                "parseClassroomConfig diverges from legacy fromJson copy for: $cfg",
                legacyValid,
                TypeInferer.parseClassroomConfig(cfg),
            )
        }
    }

    @Test
    fun `parseClassroomConfig on each of five missing fields is false`() {
        val full = mapOf(
            "subject" to "数学",
            "level" to "入门（知道基本概念）",
            "style" to "讲解型（通俗演示为主）",
            "tone" to "鼓励式",
            "language" to "中文",
        )
        for (missing in full.keys) {
            val json = full.filterKeys { it != missing }
                .entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":\"$v\"" }
            assertFalse("missing $missing should invalidate", TypeInferer.parseClassroomConfig(json))
        }
        // 全给 → true（classmate 缺省可空，不影响）
        val all = full.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":\"$v\"" }
        assertTrue(TypeInferer.parseClassroomConfig(all))
    }
}
