package dev.liuyouwu.familiar.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.liuyouwu.familiar.data.local.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    // 置顶的按置顶时间排，未置顶的按更新时间排；
    // 不能简单 pinnedAt DESC——NULL 在 DESC 时沉底会导致"取消置顶后顽固浮顶"
    @Query(
        "SELECT * FROM conversations ORDER BY isPinned DESC, " +
            "CASE WHEN isPinned = 1 THEN pinnedAt ELSE updatedAt END DESC, updatedAt DESC"
    )
    fun getAllConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getConversationById(id: Long): ConversationEntity?

    /** 单个会话的实时观察：聊天页顶栏标题实时订阅（自动命名完成后即时刷新） */
    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observeConversation(id: Long): Flow<ConversationEntity?>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertConversation(conversation: ConversationEntity): Long

    @Query("UPDATE conversations SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE conversations SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: Long, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE conversations SET tutorState = :state WHERE id = :id")
    suspend fun updateTutorStateRaw(id: Long, state: String)

    /** familiar 换卡用：仅改 tutorConfig（systemPrompt 为空串、运行时组装，无需同步改） */
    @Query("UPDATE conversations SET tutorConfig = :cfg WHERE id = :id")
    suspend fun updateTutorConfig(id: Long, cfg: String)

    @Query("UPDATE conversations SET systemPrompt = :prompt WHERE id = :id")
    suspend fun updateSystemPrompt(id: Long, prompt: String)

    /**
     * skillParams 列整写（Agent 内核 P4 T3）：聊天室成员编辑的 skillParams.members
     * 同步写路径（调用方 ChatViewModel.updateChatRoomParticipants 先读后并键——members
     * 是数组值，不走 createConversationWithSkill 的 Map<String,String> 契约，更新也无
     * 现成写点，故补此最小 DAO 口；其余键由调用方原样回写）。
     */
    @Query("UPDATE conversations SET skillParams = :json WHERE id = :id")
    suspend fun updateSkillParams(id: Long, json: String)

    @Delete
    suspend fun deleteConversation(conversation: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE conversations SET isPinned = :pinned, pinnedAt = :pinnedAt WHERE id = :id")
    suspend fun updatePinned(id: Long, pinned: Boolean, pinnedAt: Long?)

    // ===== 备份/恢复 =====

    @Query("SELECT * FROM conversations")
    suspend fun getAllConversationsOnce(): List<ConversationEntity>

    @Query("DELETE FROM conversations")
    suspend fun deleteAllConversations()
}
