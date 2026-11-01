package dev.liuyouwu.familiar.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 贴边钳制纯函数。帧图透明边距（256 画布实测）：左 22.7% / 右 23.0% / 上 21.1% / 下 4.7%，
 *  各向允许的出屏量必须压过对应边距，角色视觉上才贴得到屏缘。
 *  纵向坐标空间：NO_LIMITS 后鱼窗请求坐标的挂载原点在状态栏下（originTop=状态栏
 *  inset，挂载后 rootWindowInsets 实测校准），物理底=screenH——模拟器实测
 *  originTop=136 / screenH=2340 / fish=385。 */
class PetWindowBoundsTest {

    private val W = 1080
    private val H = 2340
    private val FISH = 385        // 140dp@440dpi（模拟器实测）
    private val TOP = 136         // 状态栏（模拟器实测挂载原点偏移）

    @Test
    fun `横向左45%右55%出屏，沿用既有手感`() {
        assertEquals(-FISH * 45 / 100, PetWindowBounds.clampX(-FISH, W, FISH))
        assertEquals(W - FISH * 55 / 100, PetWindowBounds.clampX(W, W, FISH))
        assertEquals(FISH, PetWindowBounds.clampX(FISH, W, FISH))
    }

    @Test
    fun `顶部补偿原点偏移后允许25%出屏——可探入状态栏`() {
        assertEquals(-TOP - FISH / 4, PetWindowBounds.clampY(-FISH, TOP, H, FISH))
        assertEquals(-TOP - FISH / 4, PetWindowBounds.clampY(Int.MIN_VALUE, TOP, H, FISH))
        assertEquals(100, PetWindowBounds.clampY(100, TOP, H, FISH))
    }

    @Test
    fun `底部脚踩物理底边——窗口物理底越屏一成身位`() {
        val max = PetWindowBounds.clampY(Int.MAX_VALUE, TOP, H, FISH)
        assertEquals(H - TOP - FISH * 90 / 100, max)
        // 物理 window 底 = 请求 y + originTop + fish；越物理屏 10%（±1 int 整除误差）
        val overshoot = max + TOP + FISH - H
        assertTrue("过冲应为一成身位±1: $overshoot", overshoot in FISH * 10 / 100..FISH * 10 / 100 + 1)
    }

    @Test
    fun `小屏兜底 min 永不大于 max 不抛异常`() {
        assertEquals(-TOP - FISH / 4, PetWindowBounds.clampY(FISH, TOP, FISH / 2, FISH))
    }

    @Test
    fun `极端入参收拢在允许区间内`() {
        assertTrue(PetWindowBounds.clampX(Int.MIN_VALUE, W, FISH) >= -FISH * 45 / 100)
        assertTrue(PetWindowBounds.clampX(Int.MAX_VALUE, W, FISH) <= W - FISH * 55 / 100)
        assertTrue(PetWindowBounds.clampY(Int.MIN_VALUE, TOP, H, FISH) >= -TOP - FISH / 4)
        assertTrue(PetWindowBounds.clampY(Int.MAX_VALUE, TOP, H, FISH) <= H - TOP - FISH * 90 / 100)
    }
}
