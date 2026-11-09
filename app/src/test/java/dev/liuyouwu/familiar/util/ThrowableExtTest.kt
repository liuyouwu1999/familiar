package dev.liuyouwu.familiar.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ThrowableExtTest {
    @Test
    fun `多行取首行截断60`() {
        assertEquals("x".repeat(60), IllegalStateException("x".repeat(80) + "\nsecond").toUserLine())
    }

    @Test
    fun `null message 用 fallback`() {
        assertEquals("fallback", object : Exception() {}.toUserLine("fallback"))
    }

    @Test
    fun `空串 message 也走 fallback`() {
        assertEquals("未知错误", IllegalStateException("").toUserLine())
    }
}
