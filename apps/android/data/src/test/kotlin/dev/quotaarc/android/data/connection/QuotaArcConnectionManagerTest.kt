package dev.quotaarc.android.data.connection

import dev.quotaarc.android.data.api.DeviceApiFailure
import dev.quotaarc.android.data.api.DeviceApiFailureKind
import dev.quotaarc.android.data.api.DeviceApiResult
import dev.quotaarc.android.data.repository.QuotaArcRepository
import dev.quotaarc.android.data.repository.RefreshFailure
import dev.quotaarc.android.data.repository.RefreshFailureKind
import dev.quotaarc.android.data.repository.RefreshResult
import dev.quotaarc.android.data.repository.RefreshTrigger
import dev.quotaarc.android.data.repository.RepositoryState
import dev.quotaarc.android.data.repository.SwitchingQuotaArcRepository
import dev.quotaarc.android.data.testing.ScriptedQuotaArcDeviceApi
import dev.quotaarc.android.data.testing.canonicalFixture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class QuotaArcConnectionManagerTest {
    @Test
    fun `test probes health and strict summary without persistence or activation`() = runTest {
        val store = FakeConnectionStore()
        val api = successfulApi()
        val initial = MarkerRepository("initial")
        val manager = manager(store, api, initial)

        val result = manager.test(PAIRING_JSON)

        assertTrue(result is ConnectionTestResult.Success)
        assertNull(store.connection)
        assertEquals(listOf("health", "summary"), api.callOrder)
        assertEquals("initial", marker(manager.repository.current()))
    }

    @Test
    fun `probe failure leaves old connection and repository active`() = runTest {
        val old = DevicePairingCodec.decode(PAIRING_JSON)
        val store = FakeConnectionStore(connection = old)
        val api = ScriptedQuotaArcDeviceApi(
            healthSteps = listOf {
                DeviceApiResult.Failure(
                    DeviceApiFailure(
                        DeviceApiFailureKind.UNAUTHORIZED,
                        "auth.invalid",
                        false,
                    ),
                )
            },
            fetchSteps = listOf { DeviceApiResult.Success(canonicalFixture("summary.ok.json")) },
        )
        val initial = MarkerRepository("old")
        val manager = manager(store, api, initial)

        val result = manager.testAndSave(PAIRING_JSON)

        assertTrue(result is ConnectionActivationResult.Failure)
        assertEquals(old, store.connection)
        assertEquals(0, store.replaceCalls)
        assertEquals("old", marker(manager.repository.current()))
    }

    @Test
    fun `invalid summary cannot replace the old connection or repository`() = runTest {
        val old = DevicePairingCodec.decode(PAIRING_JSON).let { connection ->
            connection.copy(
                metadata = connection.metadata.copy(
                    collectorId = "qac_zyxwvutsrqponmlkjihgfe",
                ),
            )
        }
        val store = FakeConnectionStore(connection = old)
        val api = ScriptedQuotaArcDeviceApi(
            fetchSteps = listOf {
                DeviceApiResult.Success("{not-json".encodeToByteArray())
            },
        )
        val manager = manager(store, api, MarkerRepository("old"))

        val result = manager.testAndSave(PAIRING_JSON)

        assertTrue(result is ConnectionActivationResult.Failure)
        assertEquals(
            ConnectionFailureKind.CONTRACT_INVALID,
            (result as ConnectionActivationResult.Failure).failure.kind,
        )
        assertEquals(0, store.replaceCalls)
        assertEquals(old, store.connection)
        assertEquals("old", marker(manager.repository.current()))
    }

    @Test
    fun `successful probe persists then activates candidate atomically`() = runTest {
        val events = mutableListOf<String>()
        val store = FakeConnectionStore(events = events)
        val api = successfulApi()
        val initial = MarkerRepository("initial")
        val switching = SwitchingQuotaArcRepository(initial)
        val manager = QuotaArcConnectionManager(
            store = store,
            switchingRepository = switching,
            apiFactory = DeviceApiFactory { api },
            repositoryFactory = ConnectedRepositoryFactory { connection, _ ->
                MarkerRepository(connection.metadata.collectorId).also {
                    events += "repository-created"
                }
            },
            unavailableRepositoryFactory =
                UnavailableRepositoryFactory { metadata ->
                    MarkerRepository("unavailable:${metadata.collectorId}")
                },
        )

        val result = manager.testAndSave(PAIRING_JSON)

        assertTrue(result is ConnectionActivationResult.Success)
        assertEquals(COLLECTOR_ID, store.connection?.metadata?.collectorId)
        assertEquals(
            listOf("repository-created", "persisted"),
            events,
        )
        assertEquals(COLLECTOR_ID, marker(manager.repository.current()))
        assertEquals(
            ConnectionRestoreResult.Ready(
                DevicePairingCodec.decode(PAIRING_JSON).metadata,
            ),
            manager.restoreState.value,
        )
    }

    @Test
    fun `persistence failure cannot activate candidate`() = runTest {
        val store = FakeConnectionStore(failReplace = true)
        val manager = manager(
            store = store,
            api = successfulApi(),
            initial = MarkerRepository("old"),
        )

        val result = manager.testAndSave(PAIRING_JSON)

        assertTrue(result is ConnectionActivationResult.Failure)
        assertEquals("old", marker(manager.repository.current()))
    }

    @Test
    fun `cancellation after authoritative commit still activates the persisted candidate`() =
        runTest {
            val persisted = CompletableDeferred<Unit>()
            val releaseCommit = CompletableDeferred<Unit>()
            val store = FakeConnectionStore(
                afterCommit = {
                    persisted.complete(Unit)
                    releaseCommit.await()
                },
            )
            val manager = manager(
                store = store,
                api = successfulApi(),
                initial = MarkerRepository("old"),
            )

            val save = async {
                manager.testAndSave(PAIRING_JSON)
            }
            persisted.await()
            save.cancel()
            releaseCommit.complete(Unit)
            save.join()

            assertEquals(COLLECTOR_ID, store.connection?.metadata?.collectorId)
            assertEquals(COLLECTOR_ID, marker(manager.repository.current()))
        }

    @Test
    fun `unavailable key restores authoritative identity as read only`() = runTest {
        val metadata = DevicePairingCodec.decode(PAIRING_JSON).metadata
        val store = FakeConnectionStore(
            restoreOverride =
                StoredConnectionRestoreResult.CredentialUnavailable(metadata),
        )
        val manager = manager(
            store = store,
            api = successfulApi(),
            initial = MarkerRepository("restore-pending"),
        )

        val restored = manager.restore()

        assertEquals(
            ConnectionRestoreResult.CredentialUnavailable(metadata),
            restored,
        )
        assertEquals(
            "unavailable:${metadata.collectorId}",
            marker(manager.repository.current()),
        )
        assertEquals(restored, manager.restoreState.value)
    }

    @Test
    fun `invalid authoritative document leaves neutral repository active`() = runTest {
        val manager = manager(
            store = FakeConnectionStore(failRestore = true),
            api = successfulApi(),
            initial = MarkerRepository("restore-pending"),
        )

        val restored = manager.restore()

        assertEquals(ConnectionRestoreResult.Invalid, restored)
        assertEquals("restore-pending", marker(manager.repository.current()))
        assertEquals(restored, manager.restoreState.value)
    }

    @Test
    fun `missing authoritative document publishes absent without trusting an index`() = runTest {
        val manager = manager(
            store = FakeConnectionStore(),
            api = successfulApi(),
            initial = MarkerRepository("restore-pending"),
        )

        val restored = manager.restore()

        assertEquals(ConnectionRestoreResult.Absent, restored)
        assertEquals("restore-pending", marker(manager.repository.current()))
        assertEquals(restored, manager.restoreState.value)
    }

    private fun manager(
        store: FakeConnectionStore,
        api: ScriptedQuotaArcDeviceApi,
        initial: MarkerRepository,
    ): QuotaArcConnectionManager {
        val switching = SwitchingQuotaArcRepository(initial)
        return QuotaArcConnectionManager(
            store = store,
            switchingRepository = switching,
            apiFactory = DeviceApiFactory { api },
            repositoryFactory = ConnectedRepositoryFactory { connection, _ ->
                MarkerRepository(connection.metadata.collectorId)
            },
            unavailableRepositoryFactory =
                UnavailableRepositoryFactory { metadata ->
                    MarkerRepository("unavailable:${metadata.collectorId}")
                },
        )
    }

    private fun successfulApi() = ScriptedQuotaArcDeviceApi(
        fetchSteps = listOf {
            DeviceApiResult.Success(canonicalFixture("summary.ok.json"))
        },
    )

    private fun marker(state: RepositoryState): String =
        (state as RepositoryState.Error).failure.code

    private class FakeConnectionStore(
        var connection: DeviceConnection? = null,
        private val failReplace: Boolean = false,
        private val failRestore: Boolean = false,
        private val restoreOverride: StoredConnectionRestoreResult? = null,
        private val events: MutableList<String> = mutableListOf(),
        private val afterCommit: suspend () -> Unit = {},
    ) : ConnectionStore {
        var replaceCalls = 0

        override suspend fun read(): DeviceConnection? = connection

        override suspend fun readForRestore(): StoredConnectionRestoreResult {
            if (failRestore) error("invalid document")
            return restoreOverride ?: super.readForRestore()
        }

        override suspend fun replace(connection: DeviceConnection) {
            replaceCalls += 1
            if (failReplace) error("write failed")
            this.connection = connection
            events += "persisted"
            afterCommit()
        }

        override suspend fun clear() {
            connection = null
        }
    }

    private class MarkerRepository(
        marker: String,
    ) : QuotaArcRepository {
        private val state = RepositoryState.Error(
            failure = RefreshFailure(
                RefreshFailureKind.REMOTE,
                marker,
                false,
            ),
            attemptedAt = Instant.EPOCH,
        )

        override fun observe(): Flow<RepositoryState> = flowOf(state)
        override suspend fun current(): RepositoryState = state
        override suspend fun refresh(trigger: RefreshTrigger): RefreshResult =
            RefreshResult.Failed(state.failure)
    }

    private companion object {
        const val COLLECTOR_ID = "qac_abcdefghijklmnopqrstuv"
        const val PAIRING_JSON =
            """{"pairingVersion":1,"endpoint":"https://collector.example:8443","collectorId":"qac_abcdefghijklmnopqrstuv","certificateSha256":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","deviceToken":"qa1.abcdefghijklmnop.ABCDEFGHIJKLMNOPQRSTUVWXYZabcdef","scopes":["summary.read","refresh.write"]}"""
    }
}
