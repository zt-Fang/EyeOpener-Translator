package io.github.ztfang.eye.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 历史记录表。
 *
 * [timestamp] 建索引：历史列表固定按 `ORDER BY timestamp DESC` 查询，
 * 无索引时记录量上千后会退化为全表扫描 + 内存排序。
 */
@Entity(
    tableName = "tb_history",
    indices = [Index("timestamp")],
)
data class HistoryRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceText: String,
    val translatedText: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val timestamp: Long,
    val isFavorite: Boolean = false,
)
