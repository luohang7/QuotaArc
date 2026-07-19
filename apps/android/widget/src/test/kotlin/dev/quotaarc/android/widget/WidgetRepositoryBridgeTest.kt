package dev.quotaarc.android.widget

import dev.quotaarc.android.data.connection.ConnectionActivationResult
import dev.quotaarc.android.data.connection.ConnectionRestoreResult
import dev.quotaarc.android.data.connection.ConnectionTestResult
import dev.quotaarc.android.data.connection.QuotaArcConnectionCoordinator
import dev.quotaarc.android.data.repository.QuotaArcRepository
import dev.quotaarc.android.data.repository.RefreshFailure
import dev.quotaarc.android.data.repository.RefreshFailureKind
import dev.quotaarc.android.data.repository.RefreshResult
import dev.quotaarc.android.data.repository.RefreshTrigger
import dev.quotaarc.android.data.repository.RepositoryState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.Instant

class WidgetRepositoryBridgeTest {
    @Test
    fun `cold widget access waits for authoritative connection restore`() = runTest {
        val restoreGate = CompletableDeferred<Unit>()
        val coordinator = FakeCoordinator(restoreGate)

        val current = async {
            coordinator.restoredRepository().current()
        }

        assertFalse(current.isCompleted)
        restoreGate.complete(Unit)

        assertEquals(MARKER_STATE, current.await())
        assertEquals(1, coordinator.awaitCalls)
    }

    private class FakeCoordinator(
        private val restoreGate: CompletableDeferred<Unit>,
    ) : QuotaArcConnectionCoordinator {
        var awaitCalls = 0

        override val repository: QuotaArcRepository = MarkerRepository()

        override suspend fun test(pairingJson: String): ConnectionTestResult =
            error("not used")

        override suspend fun testAndSave(
            pairingJson: String,
        ): ConnectionActivationResult = error("not used")

        override suspend fun awaitInitialRestore(): ConnectionRestoreResult {
            awaitCalls += 1
            restoreGate.await()
            return ConnectionRestoreResult.Absent
        }
    }

    private class MarkerRepository : QuotaArcRepository {
        override fun observe(): Flow<RepositoryState> = flowOf(MARKER_STATE)

        override suspend fun current(): RepositoryState = MARKER_STATE

        override suspend fun refresh(trigger: RefreshTrigger): RefreshResult =
            RefreshResult.Failed(MARKER_STATE.failure)
    }

    private companion object {
        val MARKER_STATE = RepositoryState.Error(
            failure = RefreshFailure(
                kind = RefreshFailureKind.REMOTE,
                code = "restored",
                retryable = false,
            ),
            attemptedAt = Instant.EPOCH,
        )
    }
}
