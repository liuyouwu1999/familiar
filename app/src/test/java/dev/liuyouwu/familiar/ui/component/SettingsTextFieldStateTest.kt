package dev.liuyouwu.familiar.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「外部真值到达填一次 / 用户键入置脏」编辑态纯逻辑单测（审计 A6——原 SettingsDialog
 * 三处复制的 dirty 模式收口进 [SettingsTextFieldState]，组合面的 LaunchedEffect 跟随
 * 体即 [SettingsTextFieldState.followIfClean]，在此验证现场三处语义）。
 */
class SettingsTextFieldStateTest {
    @Test
    fun `初始未填未脏`() {
        val state = SettingsTextFieldState()
        assertNull(state.value)
        assertFalse(state.dirty)
    }

    @Test
    fun `真值到达且未脏则跟随`() {
        val state = SettingsTextFieldState()
        state.followIfClean("30")
        assertEquals("30", state.value)
        assertFalse(state.dirty)
    }

    @Test
    fun `null 真值不落输入框`() {
        val state = SettingsTextFieldState()
        state.followIfClean(null)
        assertNull(state.value)
    }

    @Test
    fun `未脏时真值变化持续跟随`() {
        val state = SettingsTextFieldState()
        state.followIfClean("30")
        state.followIfClean("40")
        assertEquals("40", state.value)
    }

    @Test
    fun `用户键入置脏且记录输入`() {
        val state = SettingsTextFieldState()
        state.onValueChange("45")
        assertEquals("45", state.value)
        assertTrue(state.dirty)
    }

    @Test
    fun `键入后不再跟随真值`() {
        val state = SettingsTextFieldState()
        state.followIfClean("30")
        state.onValueChange("200")
        state.followIfClean("40")
        assertEquals("200", state.value)
    }

    @Test
    fun `程序性回写纠正值不改脏态`() {
        val state = SettingsTextFieldState()
        state.set("50")
        assertEquals("50", state.value)
        assertFalse(state.dirty)
    }

    @Test
    fun `键入置脏后保存回写仍不再跟随`() {
        // 原现场口径：用户键入 200（脏）→ 保存 coerce 成 50 回写 → 流重发被脏挡住
        val state = SettingsTextFieldState()
        state.onValueChange("200")
        state.set("50")
        assertEquals("50", state.value)
        assertTrue(state.dirty)
        state.followIfClean("50")
        assertEquals("50", state.value)
    }

    @Test
    fun `表单播种初值后键入正常`() {
        // 表单复用形态（Preset 模板编辑器）：remember(initial) 播种实体初值
        val state = SettingsTextFieldState("草稿模板")
        assertEquals("草稿模板", state.value)
        assertFalse(state.dirty)
        state.onValueChange("新名")
        assertEquals("新名", state.value)
        assertTrue(state.dirty)
    }
}
