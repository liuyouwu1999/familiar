package dev.liuyouwu.familiar.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 贴边钳制纯函数。帧图透明边距（256 画布实测）：左 22.7% / 右 23.0% / 上 21.1% / 下 4.7%，
 *  各向允许的出屏量必须压过对应边距，角色视觉上才贴得到屏缘——
 *  「拖不到屏幕边缘」即底部 0% 出屏（+displayMetrics 少算导航栏）所致（2026-11-01） */
class PetWindowBoundsTest {

    private val W = 1080
    private val H = 2400
    private val fish = 350   // 140dp@2.5x 密度

    @Test
    fun `横向左45%右55%出屏，沿用既有手感`() {
        assertEquals(-fish * 45 / 100, PetWindowBounds.clampX(-fish, W, fish))      // 拖出左屏也收在 45%
        assertEquals(W - fish * 55 / 100, PetWindowBounds.clampX(W, W, fish))       // 右侧 55%
        assertEquals(fish, PetWindowBounds.clampX(fish, W, fish))                   // 屏内值原样
    }

    @Test
    fun `顶部允许25%出屏`() {
        assertEquals(-fish / 4, PetWindowBounds.clampY(-fish, H, fish))
        assertEquals(100, PetWindowBounds.clampY(100, H, fish))
    }

    @Test
    fun `底部允许10%出屏——脚能踩到物理底边`() {
        // 修复点：旧口径 y ≤ screenH - fishPx（0% 出屏），帧图下边距 4.7% 让脚永远悬在屏内
        assertEquals(H - fish * 90 / 100, PetWindowBounds.clampY(H, H, fish))
        // 窗口底最多越物理底边一成身位，不是整条鱼出屏
        val max = PetWindowBounds.clampY(Int.MAX_VALUE, H, fish)
        assertEquals(H + fish * 10 / 100, max + fish)
    }

    @Test
    fun `小屏兜底 min 永不大于 max 不抛异常`() {
        assertEquals(-fish / 4, PetWindowBounds.clampY(fish, fish / 2, fish))
    }

    @Test
    fun `极端入参收拢在允许区间内`() {
        assertTrue(PetWindowBounds.clampX(Int.MIN_VALUE, W, fish) >= -fish * 45 / 100)
        assertTrue(PetWindowBounds.clampX(Int.MAX_VALUE, W, fish) <= W - fish * 55 / 100)
        assertTrue(PetWindowBounds.clampY(Int.MIN_VALUE, H, fish) >= -fish / 4)
        assertTrue(PetWindowBounds.clampY(Int.MAX_VALUE, H, fish) <= H - fish * 90 / 100)
    }
}
