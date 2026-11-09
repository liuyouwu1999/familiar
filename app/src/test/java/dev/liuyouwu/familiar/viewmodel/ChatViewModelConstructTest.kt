package dev.liuyouwu.familiar.viewmodel

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.room.DatabaseConfiguration
import androidx.room.InvalidationTracker
import dev.liuyouwu.familiar.data.local.AppDatabase
import dev.liuyouwu.familiar.data.local.dao.ChatRoomParticipantDao
import dev.liuyouwu.familiar.data.local.dao.ConversationDao
import dev.liuyouwu.familiar.data.local.dao.ConversationMessageCount
import dev.liuyouwu.familiar.data.local.dao.LastMessageRow
import dev.liuyouwu.familiar.data.local.dao.LearningProfileDao
import dev.liuyouwu.familiar.data.local.dao.MessageDao
import dev.liuyouwu.familiar.data.local.dao.MessageAttachmentRow
import dev.liuyouwu.familiar.data.local.dao.MessageExportRow
import dev.liuyouwu.familiar.data.local.dao.MessageLightRow
import dev.liuyouwu.familiar.data.local.dao.MessageSearchRow
import dev.liuyouwu.familiar.data.local.dao.PersonaPresetDao
import dev.liuyouwu.familiar.data.local.dao.PromptTemplateDao
import dev.liuyouwu.familiar.data.local.entity.ChatRoomParticipantEntity
import dev.liuyouwu.familiar.data.local.entity.ConversationEntity
import dev.liuyouwu.familiar.data.local.entity.LearningProfileEntryEntity
import dev.liuyouwu.familiar.data.local.entity.MessageEntity
import dev.liuyouwu.familiar.data.local.entity.PersonaPresetEntity
import dev.liuyouwu.familiar.data.local.entity.PromptTemplateEntity
import dev.liuyouwu.familiar.data.remote.api.ModelsApi
import dev.liuyouwu.familiar.data.remote.api.Transcriber
import dev.liuyouwu.familiar.data.repository.ChatRepository
import dev.liuyouwu.familiar.data.repository.SettingsRepository
import dev.liuyouwu.familiar.ui.model.UiMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

private fun unreachable(): Nothing = throw UnsupportedOperationException("测试不触达")

/**
 * 审计 A2（Task 3.2）：ChatViewModel / ChatListViewModel 依赖构造注入化的「可注入」证明。
 *
 * 两个 Activity 级 VM 原构造只收 `application`，AppDatabase.getInstance 与全部依赖
 * 在构造期自 new——VM 无法脱离真实库/网络构建。本组用例用 fake DAO + fake
 * AppDatabase + fake DataStore Context 构造**新签名的**两 VM（spec 验收：都可注入），
 * 并各带一条轻量读路径断言（getUiMessages 映射 / allConversations Ui 投影）。
 *
 * 纯 JVM（循 RoomMessageStoreLogicTest 先例，无 Robolectric）：
 * - RoomDatabase 构造器仅建 InvalidationTracker 骨架，FakeAppDatabase JVM 安全；
 * - viewModelScope 走 `Dispatchers.Main.immediate`——`Dispatchers.setMain(StandardTestDispatcher)`
 *   顶住，init 里的 Main 协程只入队不执行（行为观察点全在 IO/DataStore 读路径）；
 * - SettingsRepository 构造期触达 `context.dataStore`（preferencesDataStore 委托建实例）——
 *   [DataStoreContext] 给出 applicationContext/filesDir，DataStore 落到 @TempDir 真实
 *   读写（datastore 纯 JVM）；读写失败只影响默认值，测试不依赖设置值。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelConstructTest {

    @get:Rule
    val temp = TemporaryFolder()


    @Before
    fun setUp() {
        // Unconfined：Main 协程即时执行——stateIn(WhileSubscribed) 共享链在订阅时同步
        // 跑通（构造期 init 协程触达的都是 fake DAO/真实 DataStore/无操作路径，见类注释）
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---------------- ChatViewModel：fake 依赖构造 + getUiMessages 映射断言 ----------------

    @Test
    fun `chat view model constructs from injected fakes and maps ui messages`() = runBlocking {
        val msgDao = FakeMessageDao(
            lightRows = listOf(
                MessageLightRow(id = 1, conversationId = 42, role = "user", content = "你好", timestamp = 1),
                MessageLightRow(id = 2, conversationId = 42, role = "assistant", content = "在的", timestamp = 2),
            ),
        )
        val db = FakeAppDatabase(FakeConversationDao(), msgDao)
        val vm = ChatViewModel(
            application = Application(),
            db = db,
            chatRepository = ChatRepository(db.conversationDao(), msgDao, FakePromptTemplateDao, db),
            settingsRepository = fakeSettings(),
            modelsApi = ModelsApi(),
            transcriber = Transcriber(),
        )

        val ui: List<UiMessage> = vm.getUiMessages(42L).first()

        assertEquals(listOf("1", "2"), ui.map { it.id })
        assertEquals(listOf("你好"), ui[0].textBlocks)
        assertEquals(listOf("在的"), ui[1].textBlocks)
        assertFalse(ui[0].hasLazyAttachment)
    }

    // ---------------- ChatListViewModel：同款 fake 构造断言 ----------------

    @Test
    fun `chat list view model constructs from injected fakes and projects conversations`() = runBlocking {
        val convDao = FakeConversationDao(
            conversations = listOf(ConversationEntity(id = 7, title = "测试会话")),
        )
        val db = FakeAppDatabase(convDao, FakeMessageDao())
        val vm = ChatListViewModel(
            application = Application(),
            db = db,
            chatRepository = ChatRepository(convDao, db.messageDao(), FakePromptTemplateDao, db),
            settingsRepository = fakeSettings(),
            modelsApi = ModelsApi(),
        )

        // WhileSubscribed 首值是空表占位：等上游 Ui 投影首次送达再断言
        val conversations = vm.allConversations.first { it.isNotEmpty() }

        assertEquals(listOf(7L), conversations.map { it.id })
        assertEquals("测试会话", conversations.single().title)
    }

    // ---------------- 夹具 ----------------

    /** AppDatabase 假体（RoomMessageStoreLogicTest 同源）：RoomDatabase 构造器 JVM 安全，
     *  被构造期触达的 DAO getter 返回注入假件；未触达的 Room 管道 fail-fast。 */
    private class FakeAppDatabase(
        private val conversationDao: ConversationDao,
        private val messageDao: MessageDao,
    ) : AppDatabase() {
        override fun conversationDao(): ConversationDao = conversationDao
        override fun messageDao(): MessageDao = messageDao
        override fun promptTemplateDao(): PromptTemplateDao = FakePromptTemplateDao
        override fun chatRoomParticipantDao(): ChatRoomParticipantDao = ThrowingParticipantDao
        override fun personaPresetDao(): PersonaPresetDao = FakePersonaPresetDao
        override fun learningProfileDao(): LearningProfileDao = ThrowingLearningProfileDao
        override fun createInvalidationTracker(): InvalidationTracker = InvalidationTracker(this, "messages")
        override fun createOpenHelper(config: DatabaseConfiguration) =
            throw UnsupportedOperationException("Room 管道真机验证")
        override fun clearAllTables() = throw UnsupportedOperationException("Room 管道真机验证")
    }

    /** DataStore 假位：ContextWrapper 空构造 + applicationContext/filesDir 覆写——
     *  preferencesDataStore 委托只触达这两点，DataStore 文件落到真实临时目录（纯 JVM）。 */
    private class DataStoreContext(private val filesDir: File) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = filesDir
    }

    /** SettingsRepository 构造位：DataStore 走 @TempDir（见 [DataStoreContext]），值为默认 */
    private fun fakeSettings(): SettingsRepository = SettingsRepository(DataStoreContext(temp.newFolder()))

    /** 构造期触达两处（ChatListViewModel.templates 流 / 种子模板迁移），供给空表 + no-op */
    private object FakePromptTemplateDao : PromptTemplateDao {
        override fun getAllTemplates(): Flow<List<PromptTemplateEntity>> = flowOf(emptyList())
        override suspend fun getAllTemplatesSync(): List<PromptTemplateEntity> = unreachable()
        override suspend fun deleteAllTemplates() = unreachable()
        override suspend fun insertTemplate(template: PromptTemplateEntity): Long = unreachable()
        override suspend fun deleteTemplate(template: PromptTemplateEntity) = unreachable()
        // 种子模板改名迁移（init IO 协程）会触达：no-op 即可（真库该行无副本）
        override suspend fun renameByName(oldName: String, newName: String) = Unit
        override suspend fun getTemplateById(id: Long): PromptTemplateEntity? = unreachable()
        override suspend fun updateTemplate(id: Long, name: String, content: String) = unreachable()
    }

    /** 构造期触达一处（ChatViewModel.personaPresets 流），供给空表 */
    private object FakePersonaPresetDao : PersonaPresetDao {
        override fun getAllPresets(): Flow<List<PersonaPresetEntity>> = flowOf(emptyList())
        override suspend fun getAllPresetsSync(): List<PersonaPresetEntity> = unreachable()
        override suspend fun getById(id: Long): PersonaPresetEntity? = unreachable()
        override suspend fun deleteAllPresets() = unreachable()
        override suspend fun insert(preset: PersonaPresetEntity): Long = unreachable()
        override suspend fun delete(preset: PersonaPresetEntity) = unreachable()
        override suspend fun deleteById(id: Long) = unreachable()
    }

    private object ThrowingParticipantDao : ChatRoomParticipantDao {
        override fun getParticipantsForConversation(conversationId: Long): Flow<List<ChatRoomParticipantEntity>> = unreachable()
        override suspend fun getParticipantsForConversationSync(conversationId: Long): List<ChatRoomParticipantEntity> = unreachable()
        override suspend fun getAllParticipantsSync(): List<ChatRoomParticipantEntity> = unreachable()
        override suspend fun insertParticipant(participant: ChatRoomParticipantEntity): Long = unreachable()
        override suspend fun insertParticipants(participants: List<ChatRoomParticipantEntity>) = unreachable()
        override suspend fun deleteParticipant(participant: ChatRoomParticipantEntity) = unreachable()
        override suspend fun deleteParticipantsForConversation(conversationId: Long) = unreachable()
    }

    private object ThrowingLearningProfileDao : LearningProfileDao {
        override fun observeAll(): Flow<List<LearningProfileEntryEntity>> = unreachable()
        override suspend fun allActive(): List<LearningProfileEntryEntity> = unreachable()
        override suspend fun due(now: Long): List<LearningProfileEntryEntity> = unreachable()
        override suspend fun byId(id: Long): LearningProfileEntryEntity? = unreachable()
        override suspend fun insert(e: LearningProfileEntryEntity): Long = unreachable()
        override suspend fun update(e: LearningProfileEntryEntity) = unreachable()
        override suspend fun markReminded(id: Long, at: Long) = unreachable()
        override suspend fun archive(id: Long) = unreachable()
        override fun hasActive(): Flow<Boolean> = unreachable()
    }

    private class FakeConversationDao(
        private val conversations: List<ConversationEntity> = emptyList(),
    ) : ConversationDao {
        override fun getAllConversations(): Flow<List<ConversationEntity>> = flowOf(conversations)
        override suspend fun searchByTitle(escaped: String, limit: Int): List<ConversationEntity> = unreachable()
        override suspend fun getConversationById(id: Long): ConversationEntity? = unreachable()
        override fun observeConversation(id: Long): Flow<ConversationEntity?> = flowOf(null)
        override suspend fun insertConversation(conversation: ConversationEntity): Long = unreachable()
        override suspend fun updateTitle(id: Long, title: String, updatedAt: Long) = unreachable()
        override suspend fun touch(id: Long, updatedAt: Long) = unreachable()
        override suspend fun updateTutorStateRaw(id: Long, state: String) = unreachable()
        override suspend fun updateTutorConfig(id: Long, cfg: String) = unreachable()
        override suspend fun updateSystemPrompt(id: Long, prompt: String) = unreachable()
        override suspend fun updateSkillParams(id: Long, json: String) = unreachable()
        override suspend fun deleteConversation(conversation: ConversationEntity) = unreachable()
        override suspend fun deleteById(id: Long) = unreachable()
        override suspend fun updatePinned(id: Long, pinned: Boolean, pinnedAt: Long?) = unreachable()
        override suspend fun getAllConversationsOnce(): List<ConversationEntity> = unreachable()
        override suspend fun deleteAllConversations() = unreachable()
    }

    private class FakeMessageDao(
        private val lightRows: List<MessageLightRow> = emptyList(),
    ) : MessageDao {
        override fun getMessagesForConversation(conversationId: Long): Flow<List<MessageEntity>> = unreachable()
        override suspend fun countRoundtableAnchors(conversationId: Long): Int = unreachable()
        override suspend fun insertMessage(message: MessageEntity): Long = unreachable()
        override suspend fun getMessageById(id: Long): MessageEntity? = unreachable()
        override suspend fun deleteMessagesForConversation(conversationId: Long) = unreachable()
        override suspend fun updateContent(id: Long, content: String) = unreachable()
        override suspend fun updateContentAndUsage(
            id: Long, content: String, promptTokens: Int?, completionTokens: Int?, cachedTokens: Int?
        ) = unreachable()
        override suspend fun deleteById(id: Long) = unreachable()
        override suspend fun updateSegments(id: Long, segments: String?) = unreachable()
        override suspend fun deleteMessagesAfter(conversationId: Long, afterMessageId: Long) = unreachable()
        override suspend fun deleteEmptyAssistantPlaceholders(conversationId: Long) = unreachable()
        override fun getMessagesLight(conversationId: Long, limit: Int): Flow<List<MessageLightRow>> =
            flowOf(lightRows.filter { it.conversationId == conversationId })
        override suspend fun getMessagesBefore(conversationId: Long, beforeId: Long, limit: Int): List<MessageLightRow> = unreachable()
        override suspend fun getAttachment(id: Long): MessageAttachmentRow? = unreachable()
        override suspend fun getAttachmentsForTurn(conversationId: Long, turnId: String): List<MessageAttachmentRow> = unreachable()
        override suspend fun searchMessages(query: String): List<MessageSearchRow> = unreachable()
        override suspend fun updateUsage(id: Long, promptTokens: Int?, completionTokens: Int?, cachedTokens: Int?) = unreachable()
        override suspend fun getMessageCount(conversationId: Long): Int = unreachable()
        override fun getLastMessagePerConversation(): Flow<List<LastMessageRow>> = flowOf(emptyList())
        override fun getMessageCounts(): Flow<List<ConversationMessageCount>> = flowOf(emptyList())
        override suspend fun getAllMessagesOnce(): List<MessageEntity> = unreachable()
        override suspend fun getMessagesForConversationOnce(conversationId: Long): List<MessageEntity> = unreachable()
        override suspend fun deleteAllMessages() = unreachable()
        override suspend fun getMessagesForExport(conversationId: Long): List<MessageExportRow> = unreachable()
        override suspend fun deleteByTurnId(conversationId: Long, turnId: String): Int = unreachable()
        override suspend fun getLastAssistantTurnId(conversationId: Long): String? = unreachable()
        override suspend fun updateBlocks(id: Long, content: String, reasoning: String?, blocks: String) = unreachable()
        override suspend fun getMaxMessageId(conversationId: Long): Long? = unreachable()
    }
}
