package dev.liuyouwu.familiar.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "persona_presets")
data class PersonaPresetEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val personality: String,
    @ColumnInfo(defaultValue = "#1976D2")
    val colorHex: String = "#1976D2",
    // ===== 人设卡 v2（非空默认空串：与迁移 NOT NULL DEFAULT '' 对齐；空值=旧数据，提示词侧跳过）=====
    @ColumnInfo(defaultValue = "") val identity: String = "",
    @ColumnInfo(defaultValue = "") val traits: String = "",
    @ColumnInfo(defaultValue = "") val speechStyle: String = "",
    @ColumnInfo(defaultValue = "") val catchphrase: String = "",
    @ColumnInfo(defaultValue = "") val exampleDialogs: String = "",
    // 简报笔误修正：此处 defaultValue 必须为 "我"（与迁移 DEFAULT '我' 一致，照 address="主人" 先例；
    // 若为 "" 则升级时 Room 校验 default 不匹配，抛 "Migration didn't properly handle"）
    @ColumnInfo(defaultValue = "我") val selfName: String = "我",
    @ColumnInfo(defaultValue = "主人") val address: String = "主人",
    @ColumnInfo(defaultValue = "") val tagBlock: String = "",
    @ColumnInfo(defaultValue = "") val tagGloss: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
