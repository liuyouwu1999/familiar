package dev.liuyouwu.familiar

import dev.liuyouwu.familiar.util.memory.FamiliarMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FamiliarMemoryTest {

    @Test
    fun `序列化与宽松解析往返`() {
        val s = FamiliarMemory.serialize(listOf("我叫阿友"), listOf("[10-24 19:00] 主人: hi → 我: 哟"))
        val state = FamiliarMemory.parse(s)
        assertEquals(listOf("我叫阿友"), state.facts)
        assertEquals(1, state.lines.size)
    }

    @Test
    fun `宽松解析-未知段头跳过段外行归互动`() {
        val raw = "# 随便写的\n- 不是事实\n# ===== 对用户的了解（稳定事实）=====\n- 我叫阿友\n# ===== 互动记录 =====\n[10-24 19:00] 主人: hi"
        val state = FamiliarMemory.parse(raw)
        assertEquals(listOf("我叫阿友"), state.facts)
        // 段外的"- 不是事实"与互动行都归 lines（maid 宽松语义：不丢内容）
        assertTrue(state.lines.contains("[10-24 19:00] 主人: hi"))
        assertTrue(state.lines.contains("- 不是事实"))
    }

    @Test
    fun `空串返回空状态`() {
        assertEquals(0, FamiliarMemory.parse("").facts.size)
        assertEquals(0, FamiliarMemory.parse("").lines.size)
    }

    @Test
    fun `事实抽取四类正则`() {
        assertEquals("我叫阿友", FamiliarMemory.extractFacts("对了，我叫阿友")[0])
        assertEquals("我最喜欢深夜写代码", FamiliarMemory.extractFacts("我最喜欢深夜写代码")[0])
        assertEquals("我讨厌加班", FamiliarMemory.extractFacts("我讨厌加班")[0])
        assertNotNull(FamiliarMemory.extractFacts("请记得明天提醒我倒垃圾").firstOrNull())
        assertTrue(FamiliarMemory.extractFacts("今天天气不错").isEmpty())
    }

    @Test
    fun `Dice 相似度与去重阈值`() {
        assertTrue(FamiliarMemory.similarity("我叫阿友", "我叫阿友") > 0.99)
        assertTrue(FamiliarMemory.similarity("我叫阿友", "我喜欢猫") < 0.6)
    }

    @Test
    fun `userSideOf 丢弃精灵侧原文`() {
        assertEquals("[10-24 19:00] 主人: hi", FamiliarMemory.userSideOf("[10-24 19:00] 主人: hi → 女仆: 哟"))
        assertEquals("无箭头行", FamiliarMemory.userSideOf("无箭头行"))
    }

    @Test
    fun `relabelLine 重贴当前称呼`() {
        assertEquals("[10-24 19:00] 大人: hi → 小鱼: 哟",
            FamiliarMemory.relabelLine("[10-24 19:00] 主人: hi → 女仆: 哟", "大人", "小鱼"))
    }

    @Test
    fun `截断与超长省略`() {
        // 60 字截到 36 字 + 省略号
        assertEquals("很长".repeat(18) + "…", FamiliarMemory.clampText("很长".repeat(30), 36))
        assertEquals("刚好", FamiliarMemory.clampText("刚好", 36))
    }

    @Test
    fun `封顶裁剪-事实30条互动200行`() {
        val facts = (1..40).map { "事实$it" }
        val lines = (1..250).map { "互动$it" }
        val state = FamiliarMemory.parse(FamiliarMemory.serialize(facts, lines))
        assertEquals(30, state.facts.size)
        assertEquals("事实40", state.facts.last())
        assertEquals(200, state.lines.size)
    }

    @Test
    fun `停用词二元组被剔除`() {
        val kw = FamiliarMemory.queryKeywords("最近在学 Rust 所有权")
        assertTrue(kw.contains("在学"))
        assertTrue(kw.none { it == "最近" })
    }

    @Test
    fun `召回-命中计数取前三`() {
        val lines = listOf("[1] 主人: Rust 所有权真难", "[2] 主人: 今天吃了吗", "[3] 主人: Rust 借用检查", "[4] 主人: Rust 生命周期", "[5] 主人: 天气", "[6] 主人: 其他")
        val hits = FamiliarMemory.recall(lines, FamiliarMemory.queryKeywords("Rust 所有权和借用"), recentCount = 0)
        assertTrue(hits.size <= 3)
        assertTrue(hits.first().contains("所有权"))
    }

    @Test
    fun `注入预算封顶-事实块超预算丢最旧挤进去`() {
        // 20 条 × 32 字 ≈ 640+ 字 > 500：丢最旧的挤进预算（扩容后行为），
        // 而不是整块放弃——整块丢弃 = 一条都不记得，最伤
        val facts = (1..20).map { "事实".repeat(15) + it }   // 每条 30 字 + 序号
        val ctx = FamiliarMemory.buildContext(facts, emptyList(), "主人", "我", "")
        assertNotNull("预算内截尾后应仍有记忆块", ctx)
        assertTrue("最新的事实必须保留: $ctx", ctx!!.contains("- " + "事实".repeat(15) + "20"))
        assertFalse("最旧的事实被挤出: $ctx", ctx.contains("- " + "事实".repeat(15) + "1\n"))
        // 正常场景：小事实集进入注入且格式正确
        val ok = FamiliarMemory.buildContext(listOf("我叫阿友"), emptyList(), "主人", "我", "")
        assertTrue(ok!!.startsWith("（记忆参考——") && ok.contains("我叫阿友"))
    }
}
