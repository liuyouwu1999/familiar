package dev.liuyouwu.familiar.skills.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Task 5（材料注入）钉死 [MaterialSection.section] 契约：
 * 空/空白 → null（调用方据此零拼接，材料缺席路径与现状逐字节一致）；
 * 有材料 → trim + 口径前缀（圆桌角色 prompt 尾部与 planner systemPrompt 尾部共用同一渲染）。
 */
class MaterialSectionTest {
    @Test fun `空白返null`() {
        assertNull(MaterialSection.section(null))
        assertNull(MaterialSection.section("  \n"))
    }

    @Test fun `有则trim带口径前缀`() {
        assertEquals("圆桌学习材料（讨论以此为准，与材料冲突时以材料为准）：\nABC", MaterialSection.section(" ABC "))
    }
}
