package dev.liuyouwu.familiar.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MIGRATION_15_16 内类型反推循环的纯逻辑测试：
 * 循环抽为伴生纯函数 [AppDatabase.inferAll]（锚点计数以 lambda 注入，
 * 迁移里用游标 SQL COUNT 实现——DAO 在 Migration 中不可直接调）。
 *
 * 六类行复用 TypeInfererTest（Task 2）语义样例，断言 id 配对与 skillId 结论。
 */
class Migration15_16LogicTest {

    /** 合法课堂 JSON 常量副本（原由 TutorPrompts.toJson 生成——P4 删除批 B 后
     *  util 对象已删；形状与现实库中数据一致：Gson 声明序、classmate null 省略） */
    private val classroomJson =
        """{"subject":"线性代数","level":"入门（知道基本概念）","style":"讲解型（通俗演示为主）","tone":"鼓励式","language":"中文"}"""

    /** 旧 FamiliarKind.configJson(1L) / Eli5Prompts.CONFIG_JSON 的常量副本 */
    private val familiarConfigJson = """{"kind":"familiar","cardId":1}"""
    private val eli5ConfigJson = """{"kind":"eli5"}"""

    /** 五字段缺一（language 缺）：非法课堂配置 */
    private val missingOneFieldJson =
        """{"subject":"数学","level":"入门（知道基本概念）","style":"讲解型（通俗演示为主）","tone":"鼓励式"}"""

    /** 同时含 kind:familiar 且五字段齐全（优先级边界样例） */
    private val familiarPlusClassroomJson =
        """{"kind":"familiar","cardId":7,"subject":"数学","level":"入门（知道基本概念）",""" +
            """"style":"讲解型（通俗演示为主）","tone":"鼓励式","language":"中文"}"""

    @Test
    fun `six type rows infer correct id-skillId pairs`() {
        val rows = listOf(
            Triple(1L, familiarConfigJson, false), // familiar
            Triple(2L, eli5ConfigJson, false),     // eli5
            Triple(3L, classroomJson, false),               // classroom
            Triple(4L, null, true),                         // roundtable（锚点 1）
            Triple(5L, null, true),                         // chatroom（锚点 0）
            Triple(6L, null, false),                        // chat
        )
        val anchorCount: (Long) -> Int = { id -> if (id == 4L) 1 else 0 }
        assertEquals(
            listOf(
                1L to "familiar",
                2L to "eli5",
                3L to "classroom",
                4L to "roundtable",
                5L to "chatroom",
                6L to "chat",
            ),
            AppDatabase.inferAll(rows, anchorCount),
        )
    }

    @Test
    fun `anchor count is only queried for chat room rows`() {
        // 非聊天室行不查锚点（迁移里省一次 COUNT；圆桌判定也要求 isChatRoom）
        val queried = mutableListOf<Long>()
        val rows = listOf(
            Triple(1L, null, false),
            Triple(2L, null, true),
            Triple(3L, null, false),
        )
        AppDatabase.inferAll(rows) { id -> queried += id; 0 }
        assertEquals(listOf(2L), queried)
    }

    @Test
    fun `config beats chatroom flag in priority order`() {
        // 配置命中（step1-3）先于 isChatRoom 分支：familiar 配置的聊天室仍是 familiar
        val rows = listOf(
            Triple(1L, familiarPlusClassroomJson, true),
            Triple(2L, classroomJson, true),
        )
        assertEquals(
            listOf(1L to "familiar", 2L to "classroom"),
            AppDatabase.inferAll(rows) { 3 }, // 锚点再多也不参与判定
        )
    }

    @Test
    fun `garbage config in chat room falls to chatroom or roundtable`() {
        val rows = listOf(
            Triple(1L, missingOneFieldJson, true), // 锚点 0 → chatroom
            Triple(2L, missingOneFieldJson, true), // 锚点 >0 → roundtable
            Triple(3L, """{"kind": """, true),     // 畸形 JSON → 同分支
        )
        val anchors = mapOf(1L to 0, 2L to 2, 3L to 5)
        assertEquals(
            listOf(1L to "chatroom", 2L to "roundtable", 3L to "roundtable"),
            AppDatabase.inferAll(rows) { anchors.getValue(it) },
        )
    }

    @Test
    fun `anchors without chatroom flag stay chat`() {
        // step4 是 && 语义：非聊天室即使锚点 >0 也不升级
        assertEquals(
            listOf(9L to "chat"),
            AppDatabase.inferAll(listOf(Triple(9L, null, false))) { 7 },
        )
    }

    @Test
    fun `empty conversations produce no updates`() {
        assertTrue(AppDatabase.inferAll(emptyList()) { 1 }.isEmpty())
    }
}
