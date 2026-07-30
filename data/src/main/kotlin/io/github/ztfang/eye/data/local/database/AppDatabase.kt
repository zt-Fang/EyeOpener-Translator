package io.github.ztfang.eye.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import io.github.ztfang.eye.data.local.dao.HistoryDao
import io.github.ztfang.eye.data.local.entity.HistoryRecord

@Database(
    entities = [HistoryRecord::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
}