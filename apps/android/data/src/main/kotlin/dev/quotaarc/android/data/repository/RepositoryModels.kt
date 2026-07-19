package dev.quotaarc.android.data.repository

import dev.quotaarc.android.data.contract.QuotaArcSummary
import java.time.Instant

data class CachedSnapshot(
    val summary: QuotaArcSummary,
    val receivedAt: Instant,
)

enum class PhoneCacheState {
    /** The most recent refresh succeeded and the phone cache is within its age policy. */
    CURRENT,

    /** The most recent refresh succeeded, but no newer phone snapshot arrived in time. */
    AGED,

    /** A refresh failed and a still-recent last-good phone snapshot is displayed. */
    FALLBACK,

    /** A refresh failed and the displayed last-good phone snapshot is also old. */
    STALE_FALLBACK,
}

enum class RefreshTrigger {
    SETUP,
    MANUAL,
    PERIODIC,
    FOREGROUND,
}

enum class RefreshFailureKind {
    GATE_CLOSED,
    OFFLINE,
    TIMEOUT,
    AUTH_REQUIRED,
    REMOTE,
    CONTRACT_INVALID,
    UNSUPPORTED_SCHEMA,
    NO_RENDERABLE_DATA,
    OUT_OF_ORDER,
}

data class RefreshFailure(
    val kind: RefreshFailureKind,
    val code: String,
    val retryable: Boolean,
)

sealed interface RepositoryState {
    data object Empty : RepositoryState

    data class Content(
        val snapshot: CachedSnapshot,
        val phoneCacheState: PhoneCacheState,
        val lastFailure: RefreshFailure?,
        val lastAttemptAt: Instant?,
    ) : RepositoryState

    data class Error(
        val failure: RefreshFailure,
        val attemptedAt: Instant,
    ) : RepositoryState
}

sealed interface RefreshResult {
    data class Updated(val content: RepositoryState.Content) : RefreshResult

    data class UsingCache(
        val content: RepositoryState.Content,
        val failure: RefreshFailure,
    ) : RefreshResult

    data class Failed(val failure: RefreshFailure) : RefreshResult
}
