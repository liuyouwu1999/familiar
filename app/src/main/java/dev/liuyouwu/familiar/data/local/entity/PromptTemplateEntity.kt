package dev.liuyouwu.familiar.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "prompt_templates")
data class PromptTemplateEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val content: String,
    val isDefault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
