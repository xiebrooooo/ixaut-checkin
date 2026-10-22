package edu.xaut.xilicheckin.data.repo

import edu.xaut.xilicheckin.data.local.RecordDao
import edu.xaut.xilicheckin.data.local.RecordEntity
import edu.xaut.xilicheckin.domain.SignResult
import kotlinx.coroutines.flow.Flow

class RecordRepository(private val dao: RecordDao) {

    fun observe(): Flow<List<RecordEntity>> = dao.observeAll()

    suspend fun save(result: SignResult, appId: String, pageId: String) {
        dao.insert(
            RecordEntity(
                account = result.account,
                status = result.status.name,
                detail = result.detail,
                appId = appId,
                pageId = pageId,
                createdAt = result.at,
            )
        )
    }

    suspend fun clear() = dao.clear()
}
