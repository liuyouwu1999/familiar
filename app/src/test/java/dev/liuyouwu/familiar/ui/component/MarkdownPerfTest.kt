package dev.liuyouwu.familiar.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 审计 P1-5 防回退锁：MessageBubble 的 LaTeX 映射表顶层化。
 *
 * 改造前 `latexToUnicode` 每次调用重建 48+70 项两张 Map（约 118 次 replace），
 * `normalizeMarkdown` 每次重编译 4 个 Regex——流式期间每帧对全量正文裸跑，
 * 是长回答流式卡顿的根因。顶层化（文件顶层 `internal val`）后只构建一次。
 *
 * 测试机制：引用相等——连续两次访问暴露的内部映射必须是同一实例；
 * 若有人把映射表挪回函数体（每次调用重建），本文件即编译失败（顶层属性
 * 不存在），性能回退在 CI 阶段被拦下。Regex 常量为 `private val`，顶层
 * 结构由编译器保证，不在此断言。
 */
class MarkdownPerfTest {
    @Test
    fun `greek letter map is a top-level singleton across consecutive accesses`() {
        val first = LATEX_GREEK_LETTERS
        val second = LATEX_GREEK_LETTERS
        assertSame("希腊字母表须为顶层单例（连续访问同实例）", first, second)
    }

    @Test
    fun `math symbol map is a top-level singleton across consecutive accesses`() {
        val first = LATEX_MATH_SYMBOLS
        val second = LATEX_MATH_SYMBOLS
        assertSame("数学符号表须为顶层单例（连续访问同实例）", first, second)
    }

    @Test
    fun `maps carry the audited entry counts and spot-check mappings`() {
        // 审计原文「48+70 项两张 Map」：条目数钉死，防止迁移时丢行
        assertEquals(48, LATEX_GREEK_LETTERS.size)
        assertEquals(70, LATEX_MATH_SYMBOLS.size)
        // 抽查关键映射未在迁移中变值
        assertEquals("α", LATEX_GREEK_LETTERS["\\alpha"])
        assertEquals("Ω", LATEX_GREEK_LETTERS["\\Omega"])
        assertEquals("×", LATEX_MATH_SYMBOLS["\\times"])
        assertEquals("∞", LATEX_MATH_SYMBOLS["\\infty"])
        assertEquals("≤", LATEX_MATH_SYMBOLS["\\le"])
    }
}
