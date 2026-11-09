package dev.liuyouwu.familiar.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.liuyouwu.familiar.data.local.dao.ChatRoomParticipantDao
import dev.liuyouwu.familiar.data.local.dao.ConversationDao
import dev.liuyouwu.familiar.data.local.dao.LearningProfileDao
import dev.liuyouwu.familiar.data.local.dao.MessageDao
import dev.liuyouwu.familiar.data.local.dao.PersonaPresetDao
import dev.liuyouwu.familiar.data.local.dao.PromptTemplateDao
import dev.liuyouwu.familiar.data.local.entity.ChatRoomParticipantEntity
import dev.liuyouwu.familiar.data.local.entity.ConversationEntity
import dev.liuyouwu.familiar.data.local.entity.LearningProfileEntryEntity
import dev.liuyouwu.familiar.data.local.entity.MessageEntity
import dev.liuyouwu.familiar.data.local.entity.PersonaPresetEntity
import dev.liuyouwu.familiar.data.local.entity.PromptTemplateEntity
import dev.liuyouwu.familiar.skills.TypeInferer
import dev.liuyouwu.familiar.util.persona.DefaultPersonaCards
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        PromptTemplateEntity::class,
        ChatRoomParticipantEntity::class,
        PersonaPresetEntity::class,
        LearningProfileEntryEntity::class
    ],
    version = 18,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun promptTemplateDao(): PromptTemplateDao
    abstract fun chatRoomParticipantDao(): ChatRoomParticipantDao
    abstract fun personaPresetDao(): PersonaPresetDao
    abstract fun learningProfileDao(): LearningProfileDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN systemPrompt TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE TABLE IF NOT EXISTS prompt_templates (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `content` TEXT NOT NULL, `isDefault` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)")
                // 与 onCreate 的 insertDefaultTemplates 三条对齐（审计 P3：升级与新装默认模板不一致）
                db.execSQL("INSERT INTO prompt_templates (name, content, isDefault, createdAt) VALUES ('默认', '你是一个有用的助手。请使用 Markdown 格式回复。用中文思考。', 1, ${System.currentTimeMillis()})")
                db.execSQL("INSERT INTO prompt_templates (name, content, isDefault, createdAt) VALUES ('编程', '你是一个专业的编程助手。请用中文回答问题，代码部分使用 Markdown 代码块格式。回答要简洁准确。', 0, ${System.currentTimeMillis()})")
                db.execSQL("INSERT INTO prompt_templates (name, content, isDefault, createdAt) VALUES ('翻译', '你是一个专业的翻译助手。请准确翻译用户提供的内容，保持原文的语气和风格。', 0, ${System.currentTimeMillis()})")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN videoFrames TEXT DEFAULT NULL")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // F9: Add isChatRoom to conversations
                db.execSQL("ALTER TABLE conversations ADD COLUMN isChatRoom INTEGER NOT NULL DEFAULT 0")

                // F9: Add sender info to messages
                db.execSQL("ALTER TABLE messages ADD COLUMN senderModel TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE messages ADD COLUMN senderDisplayName TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE messages ADD COLUMN senderColorHex TEXT DEFAULT NULL")

                // F9: Create chat_room_participants table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS chat_room_participants (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        conversationId INTEGER NOT NULL,
                        modelName TEXT NOT NULL,
                        displayName TEXT NOT NULL,
                        systemPrompt TEXT NOT NULL DEFAULT '',
                        colorHex TEXT NOT NULL DEFAULT '#1976D2',
                        sortOrder INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY (conversationId) REFERENCES conversations(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_room_participants_conversationId ON chat_room_participants(conversationId)")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Rename systemPrompt to personality in chat_room_participants
                // SQLite doesn't support ALTER COLUMN, so recreate the table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS chat_room_participants_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        conversationId INTEGER NOT NULL,
                        modelName TEXT NOT NULL,
                        displayName TEXT NOT NULL,
                        personality TEXT NOT NULL DEFAULT '',
                        colorHex TEXT NOT NULL DEFAULT '#1976D2',
                        sortOrder INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY (conversationId) REFERENCES conversations(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("INSERT INTO chat_room_participants_new (id, conversationId, modelName, displayName, personality, colorHex, sortOrder) SELECT id, conversationId, modelName, displayName, systemPrompt, colorHex, sortOrder FROM chat_room_participants")
                db.execSQL("DROP TABLE chat_room_participants")
                db.execSQL("ALTER TABLE chat_room_participants_new RENAME TO chat_room_participants")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_room_participants_conversationId ON chat_room_participants(conversationId)")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Create persona_presets table for custom chat room presets
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS persona_presets (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        personality TEXT NOT NULL,
                        colorHex TEXT NOT NULL DEFAULT '#1976D2',
                        createdAt INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add pin support for conversations
                db.execSQL("ALTER TABLE conversations ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE conversations ADD COLUMN pinnedAt INTEGER DEFAULT NULL")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add reply-to support for messages
                db.execSQL("ALTER TABLE messages ADD COLUMN replyToMessageId INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE messages ADD COLUMN replyToContent TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE messages ADD COLUMN replyToRole TEXT DEFAULT NULL")
            }
        }

        // v9：补齐实体声明的列默认值。历史迁移给部分列写了 DEFAULT，但实体此前未
        // 声明 @ColumnInfo(defaultValue)，schema 校验会失败。重建相关表为与实体
        // 完全一致的规范结构（拷贝数据），使"迁移路径安装"与"全新安装"结构统一。
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `conversations_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `systemPrompt` TEXT NOT NULL DEFAULT '',
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `isChatRoom` INTEGER NOT NULL DEFAULT 0,
                        `isPinned` INTEGER NOT NULL DEFAULT 0,
                        `pinnedAt` INTEGER
                    )
                """.trimIndent())
                db.execSQL("INSERT INTO `conversations_new` (`id`, `title`, `systemPrompt`, `createdAt`, `updatedAt`, `isChatRoom`, `isPinned`, `pinnedAt`) SELECT `id`, `title`, `systemPrompt`, `createdAt`, `updatedAt`, `isChatRoom`, `isPinned`, `pinnedAt` FROM `conversations`")
                db.execSQL("DROP TABLE `conversations`")
                db.execSQL("ALTER TABLE `conversations_new` RENAME TO `conversations`")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `chat_room_participants_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `conversationId` INTEGER NOT NULL,
                        `modelName` TEXT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `personality` TEXT NOT NULL DEFAULT '',
                        `colorHex` TEXT NOT NULL DEFAULT '#1976D2',
                        `sortOrder` INTEGER NOT NULL,
                        FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("INSERT INTO `chat_room_participants_new` (`id`, `conversationId`, `modelName`, `displayName`, `personality`, `colorHex`, `sortOrder`) SELECT `id`, `conversationId`, `modelName`, `displayName`, `personality`, `colorHex`, `sortOrder` FROM `chat_room_participants`")
                db.execSQL("DROP TABLE `chat_room_participants`")
                db.execSQL("ALTER TABLE `chat_room_participants_new` RENAME TO `chat_room_participants`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_room_participants_conversationId` ON `chat_room_participants` (`conversationId`)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `persona_presets_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `personality` TEXT NOT NULL,
                        `colorHex` TEXT NOT NULL DEFAULT '#1976D2',
                        `createdAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("INSERT INTO `persona_presets_new` (`id`, `name`, `personality`, `colorHex`, `createdAt`) SELECT `id`, `name`, `personality`, `colorHex`, `createdAt` FROM `persona_presets`")
                db.execSQL("DROP TABLE `persona_presets`")
                db.execSQL("ALTER TABLE `persona_presets_new` RENAME TO `persona_presets`")
            }
        }

        // v10：新增 AI 学习课程配置列（可空，无默认值，与实体声明一致）
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN tutorConfig TEXT")
            }
        }

        // v11：学习进度状态列（可空无默认，与实体声明一致）
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN tutorState TEXT")
            }
        }

        // v12：messages.timestamp 索引（消息查询/会话预览按时间全表排序，
        // 消息上千后收发卡顿）。索引名与 Room 自动生成命名一致
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_timestamp ON messages(timestamp)")
            }
        }

        // v13：token 用量三列（服务端收尾块 usage 上报；可空无默认，与实体声明一致）。
        // Room 默认列名即属性名原样（驼峰不转下划线，见 v12 schema：conversationId/senderModel）
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN promptTokens INTEGER")
                db.execSQL("ALTER TABLE messages ADD COLUMN completionTokens INTEGER")
                db.execSQL("ALTER TABLE messages ADD COLUMN cachedTokens INTEGER")
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 人设卡 v2：两表同组列（participants 少 tagBlock/tagGloss）
                val personaCols = listOf(
                    "identity", "traits", "speechStyle", "catchphrase", "exampleDialogs",
                    "selfName", "address", "tagBlock", "tagGloss",
                )
                val partCols = personaCols.dropLast(2)
                for (name in personaCols) {
                    val dd = if (name == "selfName") "'我'" else if (name == "address") "'主人'" else "''"
                    db.execSQL("ALTER TABLE persona_presets ADD COLUMN $name TEXT NOT NULL DEFAULT $dd")
                }
                for (name in partCols) {
                    val dd = if (name == "selfName") "'我'" else if (name == "address") "'主人'" else "''"
                    db.execSQL("ALTER TABLE chat_room_participants ADD COLUMN $name TEXT NOT NULL DEFAULT $dd")
                }
                // segments 可空无默认
                db.execSQL("ALTER TABLE messages ADD COLUMN segments TEXT")
            }
        }

        /** 聊天室六卡 V3「真人感版」：仅当卡仍是旧默认（名字+旧口头禅精确匹配）
         *  时才刷新为 V3；用户编辑过的卡一律不动（铁律：用户定制优先）。
         *  纯数据迁移，无 schema 变更。已建群的成员快照属用户数据，不在此刷。 */
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val oldCatchphrase = mapOf(
                    "阿急" to "先干再说！",
                    "默默" to "嗯，我在听。",
                    "老陈" to "这不是 bug，是特性。",
                    "糖糖" to "好耶！",
                    "夜夜" to "……随你。",
                    "皮皮" to "命运之轮说——",
                )
                DefaultPersonaCards.PRESETS.forEach { p ->
                    val old = oldCatchphrase[p.name] ?: return@forEach
                    db.execSQL(
                        "UPDATE persona_presets SET personality=?, identity=?, traits=?, " +
                            "speechStyle=?, catchphrase=?, exampleDialogs=? " +
                            "WHERE name=? AND catchphrase=?",
                        arrayOf(
                            p.personality, p.identity, p.traits, p.speechStyle,
                            p.catchphrase, p.exampleDialogs, p.name, old,
                        )
                    )
                }
            }
        }

        // v16：Agent 内核 P2 加列（messages 4 列 + conversations 2 列，全可空默认 null）
        // + 旧会话 skillId 类型反推。反推循环抽为 [inferAll] 纯函数（见下）以便单测。
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN reasoning TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN blocks TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN toolMeta TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN turnId TEXT")
                db.execSQL("ALTER TABLE conversations ADD COLUMN skillId TEXT")
                db.execSQL("ALTER TABLE conversations ADD COLUMN skillParams TEXT")
                // 类型反推：游标遍历 conversations（量小），锚点计数用 SQL COUNT
                // （圆桌 LIKE 文案与 MessageDao.countRoundtableAnchors 一致；DAO 在
                // Migration 里不可直接调）。先收齐行、关游标，再反推、再统一 UPDATE。
                val rows = mutableListOf<Triple<Long, String?, Boolean>>()
                db.query("SELECT id, tutorConfig, isChatRoom FROM conversations").use { c ->
                    while (c.moveToNext()) {
                        rows += Triple(c.getLong(0), c.getString(1), c.getInt(2) != 0)
                    }
                }
                val updates = inferAll(rows) { id ->
                    db.query(
                        "SELECT COUNT(*) FROM messages WHERE conversationId = ? AND content LIKE '（圆桌话题：%'",
                        arrayOf(id)
                    ).use { it.moveToFirst(); it.getInt(0) }
                }
                for ((id, skillId) in updates) {
                    db.execSQL("UPDATE conversations SET skillId = ? WHERE id = ?", arrayOf(skillId, id))
                }
            }
        }

        // v17：学习画像条目表（新建表，无历史数据搬迁；DEFAULT 与实体 @ColumnInfo 逐字一致）
        internal val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `learning_profile_entries` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `topic` TEXT NOT NULL,
                        `point` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `sourceRoomId` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `lastReviewedAt` INTEGER NOT NULL,
                        `nextReviewAt` INTEGER NOT NULL,
                        `reviewCount` INTEGER NOT NULL DEFAULT 0,
                        `remindedAt` INTEGER NOT NULL DEFAULT 0,
                        `archived` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }
        }

        // v18：messages 复合索引 (conversationId, timestamp)（审计 P7）——会话内消息
        // 读路径统一按该序过滤/排序。索引名与 Room 由 @Entity indices 自动生成的
        // 命名一致（index_表名_列1_列2），runMigrationsAndValidate 借此对齐 schema。
        internal val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_messages_conversationId_timestamp` ON `messages` (`conversationId`, `timestamp`)"
                )
            }
        }

        /**
         * MIGRATION_15_16 的类型反推循环（纯函数，供 [Migration15_16LogicTest] 单测）：
         * 每行 (id, tutorConfig, isChatRoom) 经 [TypeInferer.infer] 六序判定得 skillId。
         * [anchorCount] 为圆桌锚点计数注入（迁移里以游标 SQL COUNT 实现），仅对
         * isChatRoom 行调用（非聊天室不查库，圆桌判定本身也要求 isChatRoom）。
         */
        internal fun inferAll(
            rows: List<Triple<Long, String?, Boolean>>,
            anchorCount: (Long) -> Int
        ): List<Pair<Long, String>> = rows.map { (id, cfg, isRoom) ->
            id to TypeInferer.infer(cfg, isRoom, if (isRoom) anchorCount(id) else 0)
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "familiar_database"
                )
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                        MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                        MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13,
                        MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17,
                        MIGRATION_17_18
                    )
                    .addCallback(object : RoomDatabase.Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            // Insert default templates for new installations
                            // SupervisorJob（审计 R4，任务 1.1）：单子协程失败不取消 scope Job
                            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                                // 裸协程兜底：预置写入失败只告警，不能让新装首启闪退
                                runCatching {
                                    INSTANCE?.promptTemplateDao()?.insertDefaultTemplates()
                                }.onFailure {
                                    android.util.Log.w("AppDatabase", "插入默认模板失败", it)
                                }
                                // 内置六张人设卡种子：onCreate 仅库首次创建时触发，
                                // 老用户升级不会重复插入（无需按名字判重）
                                runCatching {
                                    INSTANCE?.personaPresetDao()?.let { dao ->
                                        DefaultPersonaCards.PRESETS.forEach { dao.insert(it) }
                                    }
                                }.onFailure {
                                    android.util.Log.w("AppDatabase", "插入内置人设卡失败", it)
                                }
                            }
                        }
                    })
                    // 不再用 fallbackToDestructiveMigration：缺迁移时宁可报错，
                    // 也不能静默清空用户全部聊天记录
                    .build()
                    .also { INSTANCE = it }
            }
        }

        private suspend fun PromptTemplateDao.insertDefaultTemplates() {
            val defaultTemplates = listOf(
                PromptTemplateEntity(name = "默认", content = "你是一个有用的助手。请使用 Markdown 格式回复。用中文思考。", isDefault = true, createdAt = System.currentTimeMillis()),
                PromptTemplateEntity(name = "编程", content = "你是一个专业的编程助手。请用中文回答问题，代码部分使用 Markdown 代码块格式。回答要简洁准确。", isDefault = false, createdAt = System.currentTimeMillis()),
                PromptTemplateEntity(name = "翻译", content = "你是一个专业的翻译助手。请准确翻译用户提供的内容，保持原文的语气和风格。", isDefault = false, createdAt = System.currentTimeMillis())
            )
            defaultTemplates.forEach { insertTemplate(it) }
        }
    }
}
