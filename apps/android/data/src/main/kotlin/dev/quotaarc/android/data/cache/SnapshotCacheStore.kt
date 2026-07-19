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
    val collectorIdentity: String? = null,
    val latestValidated: StoredSnapshot? = null,
    val lastGood: StoredSnapshot? = null,
    val lastAttempt: StoredRefreshAttempt? = null,
) {
    companion object {
        const val CACHE_FORMAT_VERSION = 2
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

/**
 * Preserves an identity-bound last-good cache while the credential is missing
 * or cannot be decrypted. Refresh failures can be evaluated in memory, but a
 * disabled repository can never rebind or erase the authoritative cache.
 */
internal class ReadOnlySnapshotCacheStore(
    private val delegate: SnapshotCacheStore,
) : SnapshotCacheStore {
    override fun observe(): Flow<SnapshotCacheEnvelope> = delegate.observe()

    override suspend fun read(): SnapshotCacheEnvelope = delegate.read()

    override suspend fun update(
        transform: (SnapshotCacheEnvelope) -> SnapshotCacheEnvelope,
    ): SnapshotCacheEnvelope = transform(delegate.read())
}
