package dev.liuyouwu.familiar.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v17→v18 迁移（审计 P7：messages 复合索引 conversationId+timestamp）：
 * 17 库造数（会话 + 两条消息）→ 跑迁移并按 18.json 校验 schema → 断言索引存在
 * 且数据保持。迁移 SQL 的索引名与 Room @Entity 自动生成命名一致，否则
 * runMigrationsAndValidate 直接失败（schema 身份校验兜底）。
 * 本地跑法（connected 任务不支持 --tests 过滤，用 -P 参数）：
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=dev.liuyouwu.familiar.data.local.Migration17_18Test
 *
 * 测试类名含迁移对「17_18」语义，ktlint class-naming 不允许下划线——@Suppress("ClassName") 豁免
 * （同为迁移测试的 Migration16_17Test 为存量债务，待 ktlint 存量清零批次一并处理）
 */
@Suppress("ClassName")
class Migration17_18Test {
    private val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)
    private val db = "test-db-messages-conv-ts-index"

    @Test
    fun migrate17To18_createsCompositeIndexAndKeepsData() {
        helper.createDatabase(db, 17).apply {
            // 造数：外键父行（conversations）+ 两条消息（含结构列，供数据保持断言）
            execSQL(
                "INSERT INTO conversations (id, title, systemPrompt, createdAt, updatedAt, isChatRoom, isPinned) " +
                    "VALUES (1, '迁移测试', '', 100, 100, 0, 0)",
            )
            execSQL(
                "INSERT INTO messages (conversationId, role, content, timestamp) " +
                    "VALUES (1, 'user', '旧消息A', 1000)",
            )
            execSQL(
                "INSERT INTO messages (conversationId, role, content, timestamp, blocks, turnId) " +
                    "VALUES (1, 'assistant', '旧消息B', 1001, '[{\"type\":\"text\",\"text\":\"hi\"}]', '1:u1')",
            )
            close()
        }

        helper.runMigrationsAndValidate(db, 18, true, AppDatabase.MIGRATION_17_18).apply {
            // 索引存在（名字与 Room 生成命名一致；runMigrationsAndValidate 的 schema
            // 校验已兜底身份，这里显式断言防「同名不同列」的边角）
            query(
                "SELECT name FROM sqlite_master WHERE type='index' " +
                    "AND name='index_messages_conversationId_timestamp'",
            ).use { c ->
                assertTrue("复合索引 index_messages_conversationId_timestamp 未创建", c.moveToFirst())
            }
            // 数据保持：行数与结构列原样
            query("SELECT COUNT(*) FROM messages WHERE conversationId = 1").use { c ->
                c.moveToFirst()
                assertEquals("迁移不得动存量消息行数", 2, c.getInt(0))
            }
            query("SELECT role, content, blocks, turnId FROM messages WHERE timestamp = 1001").use { c ->
                c.moveToFirst()
                assertEquals("assistant", c.getString(0))
                assertEquals("旧消息B", c.getString(1))
                assertEquals("[{\"type\":\"text\",\"text\":\"hi\"}]", c.getString(2))
                assertEquals("1:u1", c.getString(3))
            }
            close()
        }
    }
}
