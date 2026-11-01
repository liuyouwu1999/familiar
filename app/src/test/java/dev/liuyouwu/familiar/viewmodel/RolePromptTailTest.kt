package dev.liuyouwu.familiar.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Task 7（画像注入）钉死角色 prompt 尾部的注入空判（[rolePromptTail]）：
 * - 两段皆 null（无 material 键且无 profile 键——chatroom、新装、无条目/无匹配/检索
 *   失败）→ null：调用方 baseRoles 原样透传，prompt 与现状逐字节一致（空路径纪律）；
 * - 单段原样（Task 5 材料单路径行为不回归）；双段「材料 → 空行 → 画像」。
 * 两段均为已渲染产物，本层不渲染（ProfileRenderer.render null 判定已在
 * LearningProfileLogicTest 钉死，此处只判拼接）。
 */
class RolePromptTailTest {
    @Test fun `两段皆null不拼接`() {
        assertNull(rolePromptTail(null, null))
    }

    @Test fun `单段原样`() {
        assertEquals("材料段", rolePromptTail("材料段", null))
        assertEquals("画像段", rolePromptTail(null, "画像段"))
    }

    @Test fun `双段材料在前空行分隔`() {
        assertEquals("材料段\n\n画像段", rolePromptTail("材料段", "画像段"))
    }
}
