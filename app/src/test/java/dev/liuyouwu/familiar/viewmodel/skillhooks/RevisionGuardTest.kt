package dev.liuyouwu.familiar.viewmodel.skillhooks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RevisionGuard 纯决策四用例（spec §6.2 二轮 N3）：挂载校验 + 历史末行 id 比对。
 * Controller 协程壳的等待/超时分支不可 JVM 单测（诚实标注：手工清单验收，
 * 见 task brief Step 4）。
 */
class RevisionGuardTest {
    @Test fun `attached with matching last row launches`() =
        assertTrue(RevisionGuard.shouldLaunch(true, 101L, 101L))
    @Test fun `session switched abandons`() =
        assertFalse(RevisionGuard.shouldLaunch(false, 101L, 101L))
    @Test fun `new user row appended abandons`() =
        assertFalse(RevisionGuard.shouldLaunch(true, 102L, 101L))
    @Test fun `empty history abandons`() =
        assertFalse(RevisionGuard.shouldLaunch(true, null, 101L))
}
