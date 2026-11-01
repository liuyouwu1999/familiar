package dev.liuyouwu.familiar.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** 学习画像条目（spec §1）。status 只有两值；「待复习」是派生态（nextReviewAt<=now），不入库 */
@Entity(tableName = "learning_profile_entries")
data class LearningProfileEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val topic: String,
    val point: String,
    val status: String,              // "UNDERSTOOD" | "STUCK"
    val sourceRoomId: Long,
    val createdAt: Long,
    val lastReviewedAt: Long,        // 0 = 从未复习
    val nextReviewAt: Long,
    @ColumnInfo(defaultValue = "0") val reviewCount: Int = 0,
    @ColumnInfo(defaultValue = "0") val remindedAt: Long = 0,
    @ColumnInfo(defaultValue = "0") val archived: Boolean = false,
)
