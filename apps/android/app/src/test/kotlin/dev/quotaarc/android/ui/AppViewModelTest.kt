package dev.quotaarc.android.ui

import dev.quotaarc.android.data.connection.ConnectionActivationResult
import dev.quotaarc.android.data.connection.ConnectionFailure
import dev.quotaarc.android.data.connection.ConnectionFailureKind
import dev.quotaarc.android.data.connection.ConnectionRestoreResult
import dev.quotaarc.android.data.connection.ConnectionTestResult
import dev.quotaarc.android.data.connection.DeviceCapability
import dev.quotaarc.android.data.connection.DeviceConnectionMetadata
import dev.quotaarc.android.data.connection.QuotaArcConnectionCoordinator
import dev.quotaarc.android.data.repository.QuotaArcRepository
import dev.quotaarc.android.data.repository.RefreshFailure
import dev.quotaarc.android.data.repository.RefreshFailureKind
import dev.quotaarc.android.data.repository.RefreshResult
import dev.quotaarc.android.data.repository.RefreshTrigger
import dev.quotaarc.android.data.repository.RepositoryState
import dev.quotaarc.android.ui.model.AppDestination
import dev.quotaarc.android.ui.model.SetupAttemptUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `test keeps the secret draft and causes no activation side effects`() =
        runTest(dispatcher) {
            val coordinator = FakeCoordinator(
                testResult = ConnectionTestResult.Success(METADATA),
            )
            var activated = 0
            var widgetUpdates = 0
            val viewModel = AppViewModel(
                connectionManager = coordinator,
                onConnectionActivated = { activated += 1 },
                onSnapshotChanged = { widgetUpdates += 1 },
            )
            viewModel.updatePairingJson(PAIRING_DRAFT)

            viewModel.testConnection()
            advanceUntilIdle()

            assertEquals(1, coordinator.testCalls)
            assertEquals(0, coordinator.saveCalls)
            assertEquals(PAIRING_DRAFT, viewModel.state.value.setup.pairingJson)
            assertEquals(
                SetupAttemptUi.TestSucceeded(METADATA.collectorId),
                viewModel.state.value.setup.attempt,
            )
            assertEquals(0, activated)
            assertEquals(0, widgetUpdates)
        }

    @Test
    fun `failed save keeps the draft and previous destination`() =
        runTest(dispatcher) {
            val failure = ConnectionFailure(
                kind = ConnectionFailureKind.AUTH_REQUIRED,
                code = "auth.invalid",
                retryable = false,
            )
            val coordinator = FakeCoordinator(
                activationResult = ConnectionActivationResult.Failure(failure),
            )
            var activated = 0
            val viewModel = AppViewModel(
                connectionManager = coordinator,
                onConnectionActivated = { activated += 1 },
            )
            viewModel.updatePairingJson(PAIRING_DRAFT)

            viewModel.saveConnection()
            advanceUntilIdle()

            assertEquals(PAIRING_DRAFT, viewModel.state.value.setup.pairingJson)
            assertEquals(
                SetupAttemptUi.Failed("auth.invalid"),
                viewModel.state.value.setup.attempt,
            )
            assertEquals(AppDestination.SETUP, viewModel.state.value.destination)
            assertEquals(0, activated)
            assertTrue(coordinator.repositoryImpl.refreshTriggers.isEmpty())
        }

    @Test
    fun `successful save clears draft schedules and foreground refreshes`() =
        runTest(dispatcher) {
            val coordinator = FakeCoordinator(
                activationResult = ConnectionActivationResult.Success(METADATA),
            )
            var activated = 0
            var widgetUpdates = 0
            val viewModel = AppViewModel(
                connectionManager = coordinator,
                onConnectionActivated = { activated += 1 },
                onSnapshotChanged = { widgetUpdates += 1 },
            )
            viewModel.updatePairingJson(PAIRING_DRAFT)
            viewModel.togglePairingVisibility()

            viewModel.saveConnection()
            advanceUntilIdle()

            assertEquals("", viewModel.state.value.setup.pairingJson)
            assertFalse(viewModel.state.value.setup.pairingVisible)
            assertEquals(
                SetupAttemptUi.Saved(METADATA.collectorId),
                viewModel.state.value.setup.attempt,
            )
            assertEquals(AppDestination.DETAILS, viewModel.state.value.destination)
            assertEquals(METADATA.collectorId, viewModel.state.value.collectorId)
            assertEquals(1, activated)
            assertEquals(1, widgetUpdates)
            assertEquals(
                listOf(RefreshTrigger.FOREGROUND),
                coordinator.repositoryImpl.refreshTriggers,
            )
        }

    @Test
    fun `authoritative ready restore works when derived metadata index is missing`() =
        runTest(dispatcher) {
            val coordinator = FakeCoordinator(
                restoreResult = ConnectionRestoreResult.Ready(METADATA),
            )
            var widgetUpdates = 0

            val viewModel = AppViewModel(
                connectionManager = coordinator,
                onSnapshotChanged = { widgetUpdates += 1 },
            )
            advanceUntilIdle()

            assertEquals(1, coordinator.awaitRestoreCalls)
            assertEquals(
                listOf(RefreshTrigger.FOREGROUND),
                coordinator.repositoryImpl.refreshTriggers,
            )
            assertEquals(AppDestination.DETAILS, viewModel.state.value.destination)
            assertEquals(METADATA.collectorId, viewModel.state.value.collectorId)
            assertEquals(1, widgetUpdates)
        }

    @Test
    fun `credential unavailable restore uses authoritative collector identity`() =
        runTest(dispatcher) {
            val coordinator = FakeCoordinator(
                restoreResult =
                    ConnectionRestoreResult.CredentialUnavailable(METADATA),
            )

            val viewModel = AppViewModel(connectionManager = coordinator)
            advanceUntilIdle()

            assertEquals(1, coordinator.awaitRestoreCalls)
            assertEquals(AppDestination.DETAILS, viewModel.state.value.destination)
            assertEquals(METADATA.collectorId, viewModel.state.value.collectorId)
            assertEquals(
                listOf(RefreshTrigger.FOREGROUND),
                coordinator.repositoryImpl.refreshTriggers,
            )
        }

    @Test
    fun `invalid authoritative restore ignores any stale derived identity`() =
        runTest(dispatcher) {
            val coordinator = FakeCoordinator(
                restoreResult = ConnectionRestoreResult.Invalid,
            )

            val viewModel = AppViewModel(connectionManager = coordinator)
            advanceUntilIdle()

            assertEquals(AppDestination.SETUP, viewModel.state.value.destination)
            assertEquals(null, viewModel.state.value.collectorId)
            assertTrue(coordinator.repositoryImpl.refreshTriggers.isEmpty())
        }

    private class FakeCoordinator(
        private val testResult: ConnectionTestResult =
            ConnectionTestResult.Success(METADATA),
        private val activationResult: ConnectionActivationResult =
            ConnectionActivationResult.Success(METADATA),
        private val restoreResult: ConnectionRestoreResult =
            ConnectionRestoreResult.Absent,
    ) : QuotaArcConnectionCoordinator {
        val repositoryImpl = FakeRepository()
        var testCalls = 0
        var saveCalls = 0
        var awaitRestoreCalls = 0

        override val repository: QuotaArcRepository = repositoryImpl

        override suspend fun test(pairingJson: String): ConnectionTestResult {
            testCalls += 1
            return testResult
        }

        override suspend fun testAndSave(
            pairingJson: String,
        ): ConnectionActivationResult {
            saveCalls += 1
            return activationResult
        }

        override suspend fun awaitInitialRestore(): ConnectionRestoreResult {
            awaitRestoreCalls += 1
            return restoreResult
        }
    }

    private class FakeRepository : QuotaArcRepository {
        private val state = MutableStateFlow<RepositoryState>(RepositoryState.Empty)
        val refreshTriggers = mutableListOf<RefreshTrigger>()

        override fun observe(): Flow<RepositoryState> = state
        override suspend fun current(): RepositoryState = state.value

        override suspend fun refresh(trigger: RefreshTrigger): RefreshResult {
            refreshTriggers += trigger
            return RefreshResult.Failed(
                RefreshFailure(
                    kind = RefreshFailureKind.REMOTE,
                    code = "fixture.unavailable",
                    retryable = true,
                ),
            )
        }
    }

    private companion object {
        const val PAIRING_DRAFT = """{"pairingVersion":1}"""
        val METADATA = DeviceConnectionMetadata(
            endpoint = "https://collector.example:8443",
            collectorId = "qac_abcdefghijklmnopqrstuv",
            certificateSha256 =
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            deviceId = "abcdefghijklmnop",
            scopes = setOf(
                DeviceCapability.SUMMARY_READ,
                DeviceCapability.REFRESH_WRITE,
            ),
        )
    }
}
