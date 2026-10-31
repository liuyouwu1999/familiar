package dev.liuyouwu.familiar.pet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮窗可见判定（P6 任务2）：PetOverlayService.shouldBeVisible 纯函数的四输入真值边界。
 * 挂载/摘窗的窗口操作属 Android 集成面（手测清单），这里只钉判定逻辑本身。
 */
class PetVisibilityTest {

    @Test
    fun 没连上模型时她不存在() {
        assertFalse(PetOverlayService.shouldBeVisible(connected = false, enabled = true, foreground = true, showInBackground = true))
    }

    @Test
    fun 总开关关时不存在() {
        assertFalse(PetOverlayService.shouldBeVisible(connected = true, enabled = false, foreground = true, showInBackground = true))
    }

    @Test
    fun 前台恒显示_与后台开关无关() {
        assertTrue(PetOverlayService.shouldBeVisible(connected = true, enabled = true, foreground = true, showInBackground = false))
        assertTrue(PetOverlayService.shouldBeVisible(connected = true, enabled = true, foreground = true, showInBackground = true))
    }

    @Test
    fun 退后台按开关决定() {
        // 默认（false）：退后台收窗
        assertFalse(PetOverlayService.shouldBeVisible(connected = true, enabled = true, foreground = false, showInBackground = false))
        // 打开开关：跨应用常驻（旧版行为）
        assertTrue(PetOverlayService.shouldBeVisible(connected = true, enabled = true, foreground = false, showInBackground = true))
    }
}
