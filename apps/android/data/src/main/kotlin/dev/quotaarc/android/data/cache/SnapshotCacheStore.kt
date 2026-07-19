package dev.quotaarc.android.data.cache

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
internal data class StoredSnapshot(
    val summaryJson: String,
    val receivedAtEpochMillis: Long,
)

@Serializable
internal data class StoredRefreshAttempt(
    val attemptedAtEpochMillis: Long,
    val succeeded: Boolean,
    val failureKind: String? = null,
    val failureCode: String? = null,
    val retryable: Boolean = false,
)

@Serializable
internal data class SnapshotCacheEnvelope(
    val cacheFormatVersion: Int = CACHE_FORMAT_VERSION,
    val latestValidated: StoredSnapshot? = null,
    val lastGood: StoredSnapshot? = null,
    val lastAttempt: StoredRefreshAttempt? = null,
) {
    companion object {
        const val CACHE_FORMAT_VERSION = 1
        val EMPTY = SnapshotCacheEnvelope()
    }
}

internal interface SnapshotCacheStore {
    fun observe(): Flow<SnapshotCacheEnvelope>

    suspend fun read(): SnapshotCacheEnvelope

    suspend fun update(
        transform: (SnapshotCacheEnvelope) -> SnapshotCacheEnvelope,
    ): SnapshotCacheEnvelope
}
