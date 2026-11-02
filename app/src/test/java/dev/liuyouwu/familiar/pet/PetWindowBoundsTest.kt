package dev.liuyouwu.familiar.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 贴边钳制纯函数。贴边语义（用户裁定）：可视角色贴住屏缘、**不出屏**——
 *  窗口出屏量恰好等于帧图透明边距（左 22.7% / 右 23.0% / 上 21.1% / 下 4.7%）。
 *  模拟器/小米实测参照：screenW=1080、screenH=2340/2400、fish=385、originTop=136/90。 */
class PetWindowBoundsTest {

    private val W = 1080
    private val H = 2340
    private val FISH = 385        // 140dp@440dpi
    private val TOP = 136         // 模拟器挂载原点偏移（小米=90，逻辑同构）

    // —— 角色贴边 = 窗口出屏量恰为透明边距（±1 int 整除误差）——
    private fun assertNear(actual: Int, expected: Int) =
        assertTrue("应≈$actual(±1): $expected", Math.abs(actual - expected) <= 1)

    @Test
    fun `左贴边 角色左缘落在屏幕0`() {
        val min = PetWindowBounds.clampX(Int.MIN_VALUE, W, FISH)
        assertNear(min, -FISH * 227 / 1000)
        assertNear(min + FISH * 227 / 1000, 0)        // 角色可视左缘（窗口内 22.7%）
    }

    @Test
    fun `右贴边 角色右缘落在屏幕右`() {
        val max = PetWindowBounds.clampX(Int.MAX_VALUE, W, FISH)
        assertNear(max, W - FISH + FISH * 230 / 1000)
        assertNear(max + FISH * 770 / 1000, W)        // 角色可视右缘（窗口内 77%）
    }

    @Test
    fun `顶贴边 角色顶缘落在物理屏顶`() {
        val min = PetWindowBounds.clampY(Int.MIN_VALUE, TOP, H, FISH)
        assertNear(min, -TOP - FISH * 211 / 1000)
        assertNear(min + TOP + FISH * 211 / 1000, 0)  // 物理角色顶缘
    }

    @Test
    fun `底贴边 角色脚落在物理屏底`() {
        val max = PetWindowBounds.clampY(Int.MAX_VALUE, TOP, H, FISH)
        assertNear(max, H - TOP - FISH + FISH * 47 / 1000)
        assertNear(max + TOP + FISH * 953 / 1000, H)  // 物理角色脚缘（下边距 4.7%）
    }

    @Test
    fun `屏内值原样通过`() {
        assertEquals(100, PetWindowBounds.clampX(100, W, FISH))
        assertEquals(500, PetWindowBounds.clampY(500, TOP, H, FISH))
    }

    @Test
    fun `小屏兜底 min 永不大于 max 不抛异常`() {
        assertEquals(-TOP - FISH * 211 / 1000, PetWindowBounds.clampY(FISH, TOP, FISH / 2, FISH))
    }

    @Test
    fun `极端入参收拢在允许区间内`() {
        assertTrue(PetWindowBounds.clampX(Int.MIN_VALUE, W, FISH) >= -FISH * 227 / 1000)
        assertTrue(PetWindowBounds.clampX(Int.MAX_VALUE, W, FISH) <= W - FISH + FISH * 230 / 1000)
        assertTrue(PetWindowBounds.clampY(Int.MIN_VALUE, TOP, H, FISH) >= -TOP - FISH * 211 / 1000)
        assertTrue(PetWindowBounds.clampY(Int.MAX_VALUE, TOP, H, FISH) <= H - TOP - FISH + FISH * 47 / 1000)
    }
}
