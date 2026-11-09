package dev.liuyouwu.familiar.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 审计 P5（会话列表搜索去双轨）转义防线：标题搜索下推 DB LIKE 后，用户输入经
 * [likeEscape] 统一转义再进 `LIKE '%' || :escaped || '%' ESCAPE '\'`——本组用例
 * 钉死转义契约（正文搜索 ChatRepository.searchMessages 同用此函数）。
 *
 * 纯 JVM 测试：`ESCAPE '\'` 的 SQL 匹配语义与 searchByTitle 的排序（置顶优先）
 * 依赖 Room/SQLite，无 Robolectric 不可测——compile-only，真机/冒烟验证；
 * 全量回归代偿见 task-2.7 报告。
 */
class ConversationDaoLikeTest {
    @Test
    fun `plain text passes through unchanged`() {
        assertEquals("hello", likeEscape("hello"))
        assertEquals("默认会话 01", likeEscape("默认会话 01"))
    }

    @Test
    fun `percent is escaped so it no longer matches any-sequence`() {
        // 未转义时 "100%" 会 LIKE 命中 "100x"；转义后只匹配字面 "100%"
        assertEquals("100\\%", likeEscape("100%"))
    }

    @Test
    fun `underscore is escaped so it no longer matches any-single-char`() {
        // 未转义时 "a_b" 会 LIKE 命中 "axb"；转义后只匹配字面 "a_b"
        assertEquals("a\\_b", likeEscape("a_b"))
    }

    @Test
    fun `backslash is escaped first and never double-escapes later output`() {
        // 单个反斜杠 → 双反斜杠
        assertEquals("a\\\\b", likeEscape("a\\b"))
        // 反斜杠必须最先替换：若后替换，前两步产物里的 "\" 会被二次转义
        // 输入 "100%\"：期望 "100\%\\"（% 与 \ 各自独立转义，无连锁放大）
        assertEquals("100\\%\\\\", likeEscape("100%\\"))
    }

    @Test
    fun `mixed wildcards all escaped in one pass`() {
        assertEquals("\\%\\_\\\\", likeEscape("%_\\"))
    }

    @Test
    fun `empty and blank queries pass through`() {
        assertEquals("", likeEscape(""))
        assertEquals("   ", likeEscape("   "))
    }
}
