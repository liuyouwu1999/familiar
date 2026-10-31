package dev.liuyouwu.familiar.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.liuyouwu.familiar.data.local.entity.PromptTemplateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PromptTemplateDao {
    @Query("SELECT * FROM prompt_templates ORDER BY isDefault DESC, createdAt ASC")
    fun getAllTemplates(): Flow<List<PromptTemplateEntity>>

    /** 备份导出用：全部模板 */
    @Query("SELECT * FROM prompt_templates")
    suspend fun getAllTemplatesSync(): List<PromptTemplateEntity>

    /** 备份恢复（替换式）用：清空后重灌 */
    @Query("DELETE FROM prompt_templates")
    suspend fun deleteAllTemplates()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTemplate(template: PromptTemplateEntity): Long

    @Delete
    suspend fun deleteTemplate(template: PromptTemplateEntity)

    /** 种子模板改名迁移用（幂等：无匹配时零行更新） */
    @Query("UPDATE prompt_templates SET name = :newName WHERE name = :oldName")
    suspend fun renameByName(oldName: String, newName: String)

    @Query("SELECT * FROM prompt_templates WHERE id = :id")
    suspend fun getTemplateById(id: Long): PromptTemplateEntity?

    @Query("UPDATE prompt_templates SET name = :name, content = :content WHERE id = :id")
    suspend fun updateTemplate(id: Long, name: String, content: String)
}
