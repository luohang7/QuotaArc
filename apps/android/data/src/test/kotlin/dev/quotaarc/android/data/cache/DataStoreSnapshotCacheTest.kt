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
                collectorIdentity = "collector-a",
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
        assertEquals("collector-a", store.read().collectorIdentity)
        assertEquals(123L, store.read().lastGood?.receivedAtEpochMillis)
        assertNull(store.read().latestValidated)
    }

    @Test
    fun `read-only view evaluates failures without rebinding persistent identity`() =
        runTest {
            val file = temporaryFolder.newFile("readonly.preferences_pb")
                .also { it.delete() }
            val store = DataStoreSnapshotCache(
                PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                    produceFile = { file },
                ),
            )
            store.update { current ->
                current.copy(collectorIdentity = "collector-real")
            }
            val readOnly = ReadOnlySnapshotCacheStore(store)

            val ephemeral = readOnly.update { current ->
                current.copy(collectorIdentity = "credential-unavailable")
            }

            assertEquals("credential-unavailable", ephemeral.collectorIdentity)
            assertEquals("collector-real", store.read().collectorIdentity)
        }
}
