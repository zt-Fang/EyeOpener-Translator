package io.github.ztfang.eye.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.ztfang.eye.data.local.dao.HistoryDao
import io.github.ztfang.eye.data.local.entity.HistoryRecord

@Database(
    entities = [HistoryRecord::class],
    version = 2,
    // 导出 schema 到 data/schemas/：将来写 Migration 时才有历史基线可比对。
    // 曾为 false 且配合 fallbackToDestructiveMigration，一旦 version++ 会静默清空用户全部历史。
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao

    companion object {
        /**
         * v1 → v2：为 `tb_history.timestamp` 增加索引。
         *
         * 历史列表固定 `ORDER BY timestamp DESC`，记录量大后无索引会退化为全表扫描 + 排序。
         * 索引名必须与 Room 生成的保持一致（`index_<表名>_<列名>`），否则迁移后
         * Room 校验实际 schema 时会判定不匹配并抛异常。
         */
        val MIGRATION_1_2: Migration =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_tb_history_timestamp ON tb_history(timestamp)")
                }
            }
    }
}
