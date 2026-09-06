package io.github.ztfang.eye.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import io.github.ztfang.eye.data.local.dao.HistoryDao
import io.github.ztfang.eye.data.local.entity.HistoryRecord

@Database(
    entities = [HistoryRecord::class],
    version = 1,
    // 导出 schema 到 data/schemas/：将来写 Migration 时才有历史基线可比对。
    // 曾为 false 且配合 fallbackToDestructiveMigration，一旦 version++ 会静默清空用户全部历史。
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
}
