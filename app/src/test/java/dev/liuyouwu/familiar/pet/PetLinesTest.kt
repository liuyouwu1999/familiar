package dev.liuyouwu.familiar.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PetLinesTest {
    @Test
    fun `all pools meet minimum size`() {
        assertTrue("tap ≥20", PetLines.TAP.size >= 20)
        assertTrue("doubleTap ≥16", PetLines.DOUBLE_TAP.size >= 16)
        assertTrue("stroke ≥16", PetLines.STROKE.size >= 16)
        assertTrue("joke ≥16", PetLines.JOKE.size >= 16)
        assertTrue("ambient ≥16", PetLines.AMBIENT.size >= 16)
        assertTrue("fling ≥16", PetLines.FLING.size >= 16)
        assertTrue("farewell ≥8", PetLines.FAREWELL.size >= 8)
        assertTrue("greeting ≥8", PetLines.GREETING.size >= 8)   // 审查 N9：久别问候池断言
        assertTrue("thinking ≥6", PetLines.THINKING.size >= 6)
        assertTrue("searching ≥6", PetLines.SEARCHING.size >= 6)
        assertTrue("feed ≥8", PetLines.FEED.size >= 8)   // P6 任务6：喂食池
    }

    @Test
    fun `no duplicate lines within any pool`() {
        // 穷举 PetLines 全部池：任一池内不允许重复台词（洗牌袋一轮之内同句不二现的前提）
        val pools = mapOf(
            "tap" to PetLines.TAP,
            "doubleTap" to PetLines.DOUBLE_TAP,
            "stroke" to PetLines.STROKE,
            "joke" to PetLines.JOKE,
            "ambient" to PetLines.AMBIENT,
            "fling" to PetLines.FLING,
            "farewell" to PetLines.FAREWELL,
            "greeting" to PetLines.GREETING,
            "thinking" to PetLines.THINKING,
            "searching" to PetLines.SEARCHING,
            "feed" to PetLines.FEED,
        )
        assertEquals(11, pools.size)   // 防漏：池总数变化时这里先炸，提醒补断言
        pools.forEach { (name, list) ->
            assertEquals("池 $name 有重复台词", list.size, list.toSet().size)
        }
    }
}
