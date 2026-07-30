package io.github.ztfang.eye.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import io.github.ztfang.eye.data.local.entity.HistoryRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {

    @Query("SELECT * FROM tb_history ORDER BY timestamp DESC")
    fun getAllRecords(): Flow<List<HistoryRecord>>

    @Query("SELECT * FROM tb_history WHERE isFavorite = 1 ORDER BY timestamp DESC")
    fun getFavoriteRecords(): Flow<List<HistoryRecord>>

    @Insert
    suspend fun insertRecord(record: HistoryRecord)

    @Update
    suspend fun updateRecord(record: HistoryRecord)

    @Delete
    suspend fun deleteRecord(record: HistoryRecord)

    @Query("DELETE FROM tb_history")
    suspend fun deleteAllRecords()

    @Query("SELECT * FROM tb_history WHERE id = :id")
    suspend fun getRecordById(id: Long): HistoryRecord?
}