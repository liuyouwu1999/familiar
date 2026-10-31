package dev.liuyouwu.familiar.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.liuyouwu.familiar.data.local.entity.ChatRoomParticipantEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatRoomParticipantDao {
    @Query("SELECT * FROM chat_room_participants WHERE conversationId = :conversationId ORDER BY sortOrder ASC")
    fun getParticipantsForConversation(conversationId: Long): Flow<List<ChatRoomParticipantEntity>>

    @Query("SELECT * FROM chat_room_participants WHERE conversationId = :conversationId ORDER BY sortOrder ASC")
    suspend fun getParticipantsForConversationSync(conversationId: Long): List<ChatRoomParticipantEntity>

    /** 备份导出用：全部成员 */
    @Query("SELECT * FROM chat_room_participants ORDER BY conversationId ASC, sortOrder ASC")
    suspend fun getAllParticipantsSync(): List<ChatRoomParticipantEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertParticipant(participant: ChatRoomParticipantEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertParticipants(participants: List<ChatRoomParticipantEntity>)

    @Delete
    suspend fun deleteParticipant(participant: ChatRoomParticipantEntity)

    @Query("DELETE FROM chat_room_participants WHERE conversationId = :conversationId")
    suspend fun deleteParticipantsForConversation(conversationId: Long)
}
