package io.github.ztfang.eye.domain.repository

import io.github.ztfang.eye.domain.model.HistoryRecord
import kotlinx.coroutines.flow.Flow

interface HistoryRepository {

    fun getAllRecords(): Flow<List<HistoryRecord>>

    fun getFavoriteRecords(): Flow<List<HistoryRecord>>

    suspend fun insertRecord(record: HistoryRecord)

    suspend fun updateRecord(record: HistoryRecord)

    suspend fun deleteRecord(record: HistoryRecord)

    suspend fun deleteAllRecords()
}