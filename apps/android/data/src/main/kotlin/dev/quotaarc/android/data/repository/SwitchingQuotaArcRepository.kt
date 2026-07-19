package dev.quotaarc.android.data.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest

/**
 * Stable repository handle used by app and widget while the active Collector
 * can change after a successfully validated pairing.
 */
internal class SwitchingQuotaArcRepository(
    initial: QuotaArcRepository,
) : QuotaArcRepository {
    private val active = MutableStateFlow(initial)

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observe(): Flow<RepositoryState> =
        active.flatMapLatest(QuotaArcRepository::observe)

    override suspend fun current(): RepositoryState {
        while (true) {
            val selected = active.value
            val state = try {
                selected.current()
            } catch (error: CancellationException) {
                currentCoroutineContext().ensureActive()
                if (active.value !== selected) continue
                throw error
            }
            // A cache read can suspend outside the repository-owned Job. Never
            // return its state after a newer Collector generation is active.
            if (active.value === selected) return state
        }
    }

    override suspend fun refresh(trigger: RefreshTrigger): RefreshResult {
        while (true) {
            val selected = active.value
            val result = try {
                selected.refresh(trigger)
            } catch (error: CancellationException) {
                currentCoroutineContext().ensureActive()
                if (active.value !== selected) continue
                throw error
            }
            // Deactivation cancels normal in-flight work, but a result may
            // already have completed. Re-run the caller's intent against the
            // current generation rather than exposing an old identity.
            if (active.value === selected) return result
        }
    }

    @Synchronized
    fun activate(repository: QuotaArcRepository) {
        val previous = active.value
        if (previous === repository) return
        // Publish the replacement before cancellation can resume an old
        // repository waiter. The waiter then observes a different generation
        // and retries against it instead of leaking CancellationException (or
        // an already-completed old result) to an otherwise active caller.
        active.value = repository
        (previous as? DeactivatableQuotaArcRepository)?.deactivate()
    }
}
