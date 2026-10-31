package dev.liuyouwu.familiar.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShuffleBagTest {

    @Test
    fun `one full cycle yields every item exactly once`() {
        val bag = ShuffleBag(listOf("a", "b", "c"))
        val drawn = (1..3).map { bag.draw() }
        assertEquals(setOf("a", "b", "c"), drawn.toSet())
        assertEquals(3, drawn.size)
    }

    @Test
    fun `no repeat until pool exhausted`() {
        val bag = ShuffleBag(listOf("a", "b", "c", "d"))
        val seen = mutableListOf<String>()
        repeat(8) { seen += bag.draw() }
        // Task 1 遗留补强：钉死整池排列语义——每一轮（每 4 抽）都是全池各一次，
        // 两轮覆盖 8 抽全程，重洗点跨界（第 4/5 抽间）也被夹在断言范围内
        assertEquals(setOf("a", "b", "c", "d"), seen.take(4).toSet())
        assertEquals(setOf("a", "b", "c", "d"), seen.takeLast(4).toSet())
        assertEquals(8, seen.size)
    }

    @Test
    fun `single-item pool always returns it`() {
        assertEquals("x", ShuffleBag(listOf("x")).draw())
        assertEquals("x", ShuffleBag(listOf("x")).draw())
    }

    @Test
    fun `addr placeholder replaced`() {
        assertTrue(ShuffleBag(listOf("你好{addr}")).draw(addr = "老板").contains("老板"))
    }
}
