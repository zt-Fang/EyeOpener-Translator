package io.github.ztfang.eye.data.repository

import io.github.ztfang.eye.data.local.dao.HistoryDao
import io.github.ztfang.eye.data.local.entity.HistoryRecord as EntityRecord
import io.github.ztfang.eye.domain.model.HistoryRecord as DomainRecord
import io.github.ztfang.eye.domain.repository.HistoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class HistoryRepositoryImpl @Inject constructor(
    private val historyDao: HistoryDao
) : HistoryRepository {

    private fun EntityRecord.toDomain(): DomainRecord = DomainRecord(
        id = id,
        sourceText = sourceText,
        translatedText = translatedText,
        sourceLanguage = sourceLanguage,
        targetLanguage = targetLanguage,
        timestamp = timestamp,
        isFavorite = isFavorite
    )

    private fun DomainRecord.toEntity(): EntityRecord = EntityRecord(
        id = id,
        sourceText = sourceText,
        translatedText = translatedText,
        sourceLanguage = sourceLanguage,
        targetLanguage = targetLanguage,
        timestamp = timestamp,
        isFavorite = isFavorite
    )

    override fun getAllRecords(): Flow<List<DomainRecord>> =
        historyDao.getAllRecords().map { it.map { record -> record.toDomain() } }

    override fun getFavoriteRecords(): Flow<List<DomainRecord>> =
        historyDao.getFavoriteRecords().map { it.map { record -> record.toDomain() } }

    override suspend fun insertRecord(record: DomainRecord) {
        historyDao.insertRecord(record.toEntity())
    }

    override suspend fun updateRecord(record: DomainRecord) {
        historyDao.updateRecord(record.toEntity())
    }

    override suspend fun deleteRecord(record: DomainRecord) {
        historyDao.deleteRecord(record.toEntity())
    }

    override suspend fun deleteAllRecords() {
        historyDao.deleteAllRecords()
    }
}