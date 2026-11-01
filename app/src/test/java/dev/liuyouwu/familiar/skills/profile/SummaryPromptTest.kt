package dev.liuyouwu.familiar.skills.profile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T4：收桌小结 prompt 常量与单飞防重的纯 JVM 用例。
 * 与 T3 的 [RoundtableSummaryLogicTest]（解析逻辑）分文件——本文件只管
 * prompt 文案契约与 SingleFlight 语义。
 */
class SummaryPromptTest {
    @Test fun `user输入含记录材料条目 材料空时不拼段`() {
        val withMaterial = RoundtableSummaryTexts.userInput("讨论记录正文", "材料文本", "[]")
        assertTrue(withMaterial.contains("圆桌记录："))
        assertTrue(withMaterial.contains("讨论记录正文"))
        assertTrue(withMaterial.contains("学习材料："))
        assertTrue(withMaterial.contains("材料文本"))
        assertTrue(withMaterial.contains("该课题已有画像条目：[]"))

        // material 为 null / 空白都不拼材料段
        assertFalse(RoundtableSummaryTexts.userInput("r", null, "[]").contains("学习材料："))
        assertFalse(RoundtableSummaryTexts.userInput("r", "   ", "[]").contains("学习材料："))
    }

    @Test fun `system含updates与宁缺毋滥纪律`() {
        assert(RoundtableSummaryTexts.SYSTEM.contains("existingId") && RoundtableSummaryTexts.SYSTEM.contains("宁缺毋滥"))
    }

    @Test fun `单飞 同id并发只进一次 exit后可重进`() {
        val f = SingleFlight()
        assert(f.tryEnter(1)); assert(!f.tryEnter(1)); assert(f.tryEnter(2))
        f.exit(1); assert(f.tryEnter(1))
    }
}
