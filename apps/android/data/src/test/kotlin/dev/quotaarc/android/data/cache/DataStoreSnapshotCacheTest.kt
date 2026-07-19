package dev.quotaarc.android.data.cache

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreSnapshotCacheTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder.builder().assureDeletion().build()

    @Test
    fun `envelope update is atomic and readable`() = runTest {
        val file = temporaryFolder.newFile("cache.preferences_pb").also { it.delete() }
        val store = DataStoreSnapshotCache(
            PreferenceDataStoreFactory.create(
                scope = backgroundScope,
                produceFile = { file },
            ),
        )
        assertEquals(SnapshotCacheEnvelope.EMPTY, store.read())

        val committed = store.update { current ->
            current.copy(
                lastGood = StoredSnapshot(
                    summaryJson = """{"schemaVersion":1}""",
                    receivedAtEpochMillis = 123,
                ),
                lastAttempt = StoredRefreshAttempt(
                    attemptedAtEpochMillis = 124,
                    succeeded = true,
                ),
            )
        }

        assertEquals(committed, store.read())
        assertEquals(123L, store.read().lastGood?.receivedAtEpochMillis)
        assertNull(store.read().latestValidated)
    }
}
