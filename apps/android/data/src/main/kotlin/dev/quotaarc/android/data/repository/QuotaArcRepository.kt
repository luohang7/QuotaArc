package dev.quotaarc.android.data.repository

import kotlinx.coroutines.flow.Flow

interface QuotaArcRepository {
    fun observe(): Flow<RepositoryState>

    suspend fun current(): RepositoryState

    suspend fun refresh(trigger: RefreshTrigger = RefreshTrigger.MANUAL): RefreshResult
}

internal interface DeactivatableQuotaArcRepository {
    fun deactivate()
}
