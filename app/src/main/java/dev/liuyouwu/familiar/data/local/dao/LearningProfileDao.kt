package dev.liuyouwu.familiar.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import dev.liuyouwu.familiar.data.local.entity.LearningProfileEntryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LearningProfileDao {
    @Query("SELECT * FROM learning_profile_entries WHERE archived = 0 ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<LearningProfileEntryEntity>>

    @Query("SELECT * FROM learning_profile_entries WHERE archived = 0 ORDER BY createdAt DESC")
    suspend fun allActive(): List<LearningProfileEntryEntity>

    @Query("SELECT * FROM learning_profile_entries WHERE archived = 0 AND nextReviewAt <= :now ORDER BY nextReviewAt ASC")
    suspend fun due(now: Long): List<LearningProfileEntryEntity>

    @Query("SELECT * FROM learning_profile_entries WHERE id = :id")
    suspend fun byId(id: Long): LearningProfileEntryEntity?

    @Insert suspend fun insert(e: LearningProfileEntryEntity): Long
    @Update suspend fun update(e: LearningProfileEntryEntity)

    @Query("UPDATE learning_profile_entries SET remindedAt = :at WHERE id = :id")
    suspend fun markReminded(id: Long, at: Long)

    @Query("UPDATE learning_profile_entries SET archived = 1 WHERE id = :id")
    suspend fun archive(id: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM learning_profile_entries WHERE archived = 0)")
    fun hasActive(): Flow<Boolean>
}
