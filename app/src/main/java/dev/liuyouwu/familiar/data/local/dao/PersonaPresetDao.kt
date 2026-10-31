package dev.liuyouwu.familiar.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.liuyouwu.familiar.data.local.entity.PersonaPresetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PersonaPresetDao {
    @Query("SELECT * FROM persona_presets ORDER BY createdAt DESC")
    fun getAllPresets(): Flow<List<PersonaPresetEntity>>

    @Query("SELECT * FROM persona_presets ORDER BY createdAt DESC")
    suspend fun getAllPresetsSync(): List<PersonaPresetEntity>

    @Query("SELECT * FROM persona_presets WHERE id = :id")
    suspend fun getById(id: Long): PersonaPresetEntity?

    /** 备份恢复（替换式）用：清空后重灌 */
    @Query("DELETE FROM persona_presets")
    suspend fun deleteAllPresets()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(preset: PersonaPresetEntity): Long

    @Delete
    suspend fun delete(preset: PersonaPresetEntity)

    @Query("DELETE FROM persona_presets WHERE id = :id")
    suspend fun deleteById(id: Long)
}
