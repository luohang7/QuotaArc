package dev.quotaarc.android.data.repository

import dev.quotaarc.android.data.api.DeviceApiFailure
import dev.quotaarc.android.data.api.DeviceApiFailureKind
import dev.quotaarc.android.data.api.DeviceApiResult
import dev.quotaarc.android.data.api.QuotaArcDeviceApi
import dev.quotaarc.android.data.cache.SnapshotCacheEnvelope
import dev.quotaarc.android.data.cache.SnapshotCacheStore
import dev.quotaarc.android.data.cache.StoredRefreshAttempt
import dev.quotaarc.android.data.cache.StoredSnapshot
import dev.quotaarc.android.data.contract.ContractFailureKind
import dev.quotaarc.android.data.contract.QuotaArcContractException
import dev.quotaarc.android.data.contract.QuotaArcSummary
import dev.quotaarc.android.data.contract.QuotaArcV1JsonCodec
import dev.quotaarc.android.data.contract.SourceStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant

internal class DefaultQuotaArcRepository(
    private val api: QuotaArcDeviceApi,
    private val cache: SnapshotCacheStore,
    parentScope: CoroutineScope,
    private val codec: QuotaArcV1JsonCodec = QuotaArcV1JsonCodec(),
    private val clock: QuotaArcClock = SystemQuotaArcClock,
    private val staleAfter: Duration = Duration.ofMinutes(60),
) : QuotaArcRepository {
    private val scope = CoroutineScope(
        parentScope.coroutineContext +
            SupervisorJob(parentScope.coroutineContext[Job]),
    )
    private val inFlightMutex = Mutex()
    private var inFlight: Deferred<RefreshResult>? = null

    init {
        require(!staleAfter.isNegative && !staleAfter.isZero) {
            "staleAfter must be positive"
        }
    }

    override fun observe(): Flow<RepositoryState> =
        cache.observe().map { envelope -> stateFrom(envelope, clock.now()) }

    override suspend fun current(): RepositoryState =
        try {
            stateFrom(cache.read(), clock.now())
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            val now = clock.now()
            RepositoryState.Error(
                failure = RefreshFailure(
                    kind = RefreshFailureKind.REMOTE,
                    code = "cache.read_failed",
                    retryable = true,
                ),
                attemptedAt = now,
            )
        }

    override suspend fun refresh(trigger: RefreshTrigger): RefreshResult {
        val shared = inFlightMutex.withLock {
            // LAZY deferreds are not active before start. Completion, rather
            // than activity, is the safe predicate for callers racing between
            // leaving this mutex and starting the shared request.
            inFlight?.takeIf { !it.isCompleted } ?: createRefresh(trigger)
        }
        shared.start()
        return shared.await()
    }

    private fun createRefresh(trigger: RefreshTrigger): Deferred<RefreshResult> {
        val created = scope.async(start = CoroutineStart.LAZY) {
            performRefresh(trigger)
        }
        inFlight = created
        created.invokeOnCompletion {
            scope.launch {
                inFlightMutex.withLock {
                    if (inFlight === created) inFlight = null
                }
            }
        }
        return created
    }

    private suspend fun performRefresh(trigger: RefreshTrigger): RefreshResult {
        if (trigger == RefreshTrigger.SETUP) {
            when (val health = callApi { api.health() }) {
                is DeviceApiResult.Failure -> return finishFailure(health.failure)
                is DeviceApiResult.Success -> Unit
            }
        }
        if (trigger == RefreshTrigger.MANUAL) {
            when (val refresh = callApi { api.requestRefresh() }) {
                is DeviceApiResult.Failure -> return finishFailure(refresh.failure)
                is DeviceApiResult.Success -> Unit
            }
        }

        return when (val fetched = callApi { api.fetchSummary() }) {
            is DeviceApiResult.Failure -> finishFailure(fetched.failure)
            is DeviceApiResult.Success -> acceptSummary(fetched.value)
        }
    }

    private suspend fun <T> callApi(
        block: suspend () -> DeviceApiResult<T>,
    ): DeviceApiResult<T> =
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            DeviceApiResult.Failure(
                DeviceApiFailure(
                    kind = DeviceApiFailureKind.REMOTE,
                    code = "transport.call_failed",
                    retryable = true,
                ),
            )
        }

    private suspend fun acceptSummary(bytes: ByteArray): RefreshResult {
        val summary = try {
            codec.decode(bytes)
        } catch (error: QuotaArcContractException) {
            val kind =
                if (error.kind == ContractFailureKind.UNSUPPORTED_SCHEMA) {
                    RefreshFailureKind.UNSUPPORTED_SCHEMA
                } else {
                    RefreshFailureKind.CONTRACT_INVALID
                }
            return finishFailure(
                RefreshFailure(
                    kind = kind,
                    code = "contract.${error.kind.name.lowercase()}",
                    retryable = false,
                ),
            )
        }

        val now = clock.now()
        val stored = StoredSnapshot(
            summaryJson = codec.encode(summary),
            receivedAtEpochMillis = now.toEpochMilli(),
        )
        val renderable = summary.isRenderable()
        var rejection: RefreshFailure? = null

        val committed = try {
            cache.update { current ->
                val existingGood = current.lastGood?.decodeOrNull()
                val isOlder =
                    existingGood != null &&
                        summary.generatedAt.isBefore(existingGood.summary.generatedAt)
                rejection = when {
                    !renderable -> RefreshFailure(
                        kind = RefreshFailureKind.NO_RENDERABLE_DATA,
                        code = "contract.no_renderable_source",
                        retryable = true,
                    )
                    isOlder -> RefreshFailure(
                        kind = RefreshFailureKind.OUT_OF_ORDER,
                        code = "contract.out_of_order",
                        retryable = true,
                    )
                    else -> null
                }
                current.copy(
                    latestValidated = stored,
                    lastGood = if (rejection == null) stored else current.lastGood,
                    lastAttempt = if (rejection == null) {
                        StoredRefreshAttempt(
                            attemptedAtEpochMillis = now.toEpochMilli(),
                            succeeded = true,
                        )
                    } else {
                        rejection!!.toStoredAttempt(now)
                    },
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return RefreshResult.Failed(
                RefreshFailure(
                    kind = RefreshFailureKind.REMOTE,
                    code = "cache.write_failed",
                    retryable = true,
                ),
            )
        }

        val state = stateFrom(committed, now)
        val rejected = rejection
        return if (rejected == null && state is RepositoryState.Content) {
            RefreshResult.Updated(state)
        } else if (rejected != null && state is RepositoryState.Content) {
            RefreshResult.UsingCache(state, rejected)
        } else {
            RefreshResult.Failed(
                rejected ?: RefreshFailure(
                    kind = RefreshFailureKind.REMOTE,
                    code = "cache.commit_missing",
                    retryable = true,
                ),
            )
        }
    }

    private suspend fun finishFailure(failure: DeviceApiFailure): RefreshResult =
        finishFailure(failure.toRepositoryFailure())

    private suspend fun finishFailure(failure: RefreshFailure): RefreshResult {
        val now = clock.now()
        val committed = try {
            cache.update { current ->
                current.copy(lastAttempt = failure.toStoredAttempt(now))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return RefreshResult.Failed(failure)
        }
        return when (val state = stateFrom(committed, now)) {
            is RepositoryState.Content -> RefreshResult.UsingCache(state, failure)
            else -> RefreshResult.Failed(failure)
        }
    }

    private fun stateFrom(
        envelope: SnapshotCacheEnvelope,
        now: Instant,
    ): RepositoryState {
        val snapshot = envelope.lastGood?.decodeOrNull()
        val attempt = envelope.lastAttempt
        if (snapshot == null) {
            return attempt?.toFailureOrNull()?.let {
                RepositoryState.Error(
                    failure = it,
                    attemptedAt = Instant.ofEpochMilli(attempt.attemptedAtEpochMillis),
                )
            } ?: RepositoryState.Empty
        }

        val age = Duration.between(snapshot.receivedAt, now).coerceAtLeast(Duration.ZERO)
        val aged = age > staleAfter
        val lastFailure = attempt?.toFailureOrNull()
        val phoneCacheState = when {
            lastFailure != null && aged -> PhoneCacheState.STALE_FALLBACK
            lastFailure != null -> PhoneCacheState.FALLBACK
            aged -> PhoneCacheState.AGED
            else -> PhoneCacheState.CURRENT
        }
        return RepositoryState.Content(
            snapshot = snapshot,
            phoneCacheState = phoneCacheState,
            lastFailure = lastFailure,
            lastAttemptAt = attempt?.let { Instant.ofEpochMilli(it.attemptedAtEpochMillis) },
        )
    }

    private fun StoredSnapshot.decodeOrNull(): CachedSnapshot? =
        try {
            CachedSnapshot(
                summary = codec.decode(summaryJson),
                receivedAt = Instant.ofEpochMilli(receivedAtEpochMillis),
            )
        } catch (_: Exception) {
            null
        }

    private fun QuotaArcSummary.isRenderable(): Boolean {
        val statuses = listOf(
            sources.quota.status,
            sources.accountUsage.status,
            sources.localUsage.status,
        )
        return statuses.any { it == SourceStatus.OK || it == SourceStatus.STALE }
    }

    private fun DeviceApiFailure.toRepositoryFailure(): RefreshFailure =
        RefreshFailure(
            kind = when (kind) {
                DeviceApiFailureKind.GATE_CLOSED -> RefreshFailureKind.GATE_CLOSED
                DeviceApiFailureKind.OFFLINE -> RefreshFailureKind.OFFLINE
                DeviceApiFailureKind.TIMEOUT -> RefreshFailureKind.TIMEOUT
                DeviceApiFailureKind.UNAUTHORIZED,
                DeviceApiFailureKind.FORBIDDEN,
                -> RefreshFailureKind.AUTH_REQUIRED
                DeviceApiFailureKind.REMOTE,
                DeviceApiFailureKind.PROTOCOL,
                -> RefreshFailureKind.REMOTE
            },
            code = code,
            retryable = retryable,
        )

    private fun RefreshFailure.toStoredAttempt(now: Instant): StoredRefreshAttempt =
        StoredRefreshAttempt(
            attemptedAtEpochMillis = now.toEpochMilli(),
            succeeded = false,
            failureKind = kind.name,
            failureCode = code,
            retryable = retryable,
        )

    private fun StoredRefreshAttempt.toFailureOrNull(): RefreshFailure? {
        if (succeeded) return null
        val parsedKind = failureKind?.let {
            runCatching { RefreshFailureKind.valueOf(it) }.getOrNull()
        } ?: RefreshFailureKind.REMOTE
        return RefreshFailure(
            kind = parsedKind,
            code = failureCode ?: "refresh.failed",
            retryable = retryable,
        )
    }
}

private fun Duration.coerceAtLeast(minimum: Duration): Duration =
    if (this < minimum) minimum else this
