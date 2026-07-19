package dev.quotaarc.android.data.testing

import dev.quotaarc.android.data.cache.SnapshotCacheEnvelope
import dev.quotaarc.android.data.cache.SnapshotCacheStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class InMemorySnapshotCache(
    initial: SnapshotCacheEnvelope = SnapshotCacheEnvelope.EMPTY,
) : SnapshotCacheStore {
    private val mutex = Mutex()
    private val state = MutableStateFlow(initial)

    override fun observe(): Flow<SnapshotCacheEnvelope> = state

    override suspend fun read(): SnapshotCacheEnvelope = state.value

    override suspend fun update(
        transform: (SnapshotCacheEnvelope) -> SnapshotCacheEnvelope,
    ): SnapshotCacheEnvelope = mutex.withLock {
        transform(state.value).also { state.value = it }
    }
}
