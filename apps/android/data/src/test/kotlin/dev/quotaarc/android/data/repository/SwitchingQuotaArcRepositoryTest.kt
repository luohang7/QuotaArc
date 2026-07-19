package dev.quotaarc.android.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch

class SwitchingQuotaArcRepositoryTest {
    @Test
    fun `replacement is visible before old cancellation resumes a waiter`() = runTest {
        val old = BlockingRepository()
        val replacement = MarkerRepository("replacement")
        val switching = SwitchingQuotaArcRepository(old)
        val caller = async(Dispatchers.Default) {
            switching.refresh(RefreshTrigger.PERIODIC)
        }
        old.started.await()

        val activation = async(Dispatchers.Default) {
            switching.activate(replacement)
        }
        old.deactivationStarted.await()

        try {
            val result = caller.await()
            assertEquals(
                "replacement",
                (result as RefreshResult.Failed).failure.code,
            )
        } finally {
            old.releaseDeactivation.countDown()
        }
        activation.await()

        assertTrue(old.deactivated)
        assertEquals(
            "replacement",
            (switching.current() as RepositoryState.Error).failure.code,
        )
    }

    @Test
    fun `delayed current cannot return the previous collector after activation`() = runTest {
        val old = DelayedResultRepository("old")
        val replacement = MarkerRepository("replacement")
        val switching = SwitchingQuotaArcRepository(old)
        val caller = async { switching.current() }
        old.currentStarted.await()

        switching.activate(replacement)
        old.releaseCurrent.complete(Unit)

        assertEquals(
            "replacement",
            (caller.await() as RepositoryState.Error).failure.code,
        )
    }

    @Test
    fun `completed refresh result is retried against the active collector`() = runTest {
        val old = DelayedResultRepository("old")
        val replacement = MarkerRepository("replacement")
        val switching = SwitchingQuotaArcRepository(old)
        val caller = async { switching.refresh(RefreshTrigger.MANUAL) }
        old.refreshStarted.await()

        switching.activate(replacement)
        old.releaseRefresh.complete(Unit)

        assertEquals(
            "replacement",
            (caller.await() as RefreshResult.Failed).failure.code,
        )
    }

    private class BlockingRepository :
        QuotaArcRepository,
        DeactivatableQuotaArcRepository {
        val started = CompletableDeferred<Unit>()
        private val cancelled = CompletableDeferred<Unit>()
        val deactivationStarted = CompletableDeferred<Unit>()
        val releaseDeactivation = CountDownLatch(1)
        var deactivated = false

        override fun observe(): Flow<RepositoryState> = flowOf(RepositoryState.Empty)

        override suspend fun current(): RepositoryState = RepositoryState.Empty

        override suspend fun refresh(trigger: RefreshTrigger): RefreshResult {
            started.complete(Unit)
            cancelled.await()
            throw CancellationException("old repository deactivated")
        }

        override fun deactivate() {
            deactivated = true
            cancelled.complete(Unit)
            deactivationStarted.complete(Unit)
            releaseDeactivation.await()
        }
    }

    private class DelayedResultRepository(
        marker: String,
    ) : QuotaArcRepository, DeactivatableQuotaArcRepository {
        private val state = markerState(marker)
        val currentStarted = CompletableDeferred<Unit>()
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseCurrent = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()

        override fun observe(): Flow<RepositoryState> = flowOf(state)

        override suspend fun current(): RepositoryState {
            currentStarted.complete(Unit)
            releaseCurrent.await()
            return state
        }

        override suspend fun refresh(trigger: RefreshTrigger): RefreshResult {
            refreshStarted.complete(Unit)
            releaseRefresh.await()
            return RefreshResult.Failed(state.failure)
        }

        override fun deactivate() = Unit
    }

    private class MarkerRepository(marker: String) : QuotaArcRepository {
        private val state = markerState(marker)

        override fun observe(): Flow<RepositoryState> = flowOf(state)
        override suspend fun current(): RepositoryState = state
        override suspend fun refresh(trigger: RefreshTrigger): RefreshResult =
            RefreshResult.Failed(state.failure)
    }

    private companion object {
        fun markerState(marker: String) = RepositoryState.Error(
            failure = RefreshFailure(
                kind = RefreshFailureKind.REMOTE,
                code = marker,
                retryable = false,
            ),
            attemptedAt = Instant.EPOCH,
        )
    }
}
