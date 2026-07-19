package dev.quotaarc.android.widget

import android.content.Context
import dev.quotaarc.android.data.QuotaArcData
import dev.quotaarc.android.data.connection.QuotaArcConnectionCoordinator
import dev.quotaarc.android.data.repository.PhoneCacheState
import dev.quotaarc.android.data.repository.QuotaArcRepository
import dev.quotaarc.android.data.repository.RefreshFailure
import dev.quotaarc.android.data.repository.RefreshFailureKind
import dev.quotaarc.android.data.repository.RefreshResult
import dev.quotaarc.android.data.repository.RefreshTrigger
import dev.quotaarc.android.data.repository.RepositoryState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

internal object WidgetRepositoryBridge {
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var connectionCoordinator: QuotaArcConnectionCoordinator? = null

    suspend fun current(context: Context): WidgetMapperInput {
        val state = try {
            restoredRepository(context).current()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            return WidgetMapperInput(
                snapshot = null,
                phoneCacheState = WidgetCacheState.EMPTY,
                lastFailure = if (QuotaArcData.hasConnectionMetadata(context)) {
                    WidgetFailureKind.UNAVAILABLE
                } else {
                    WidgetFailureKind.NOT_CONFIGURED
                },
                isRefreshing = WidgetRefreshStateStore.isRefreshing(context),
            )
        }
        return state.toWidgetInput(
            isRefreshing = WidgetRefreshStateStore.isRefreshing(context),
        )
    }

    suspend fun refresh(
        context: Context,
        manual: Boolean,
    ): WidgetRefreshDisposition {
        val result = try {
            restoredRepository(context).refresh(
                if (manual) RefreshTrigger.MANUAL else RefreshTrigger.PERIODIC,
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            return WidgetRefreshDisposition.RETRY
        }
        return when (result) {
            is RefreshResult.Updated -> WidgetRefreshDisposition.SUCCESS
            is RefreshResult.UsingCache -> result.failure.toDisposition()
            is RefreshResult.Failed -> result.failure.toDisposition()
        }
    }

    private suspend fun restoredRepository(context: Context): QuotaArcRepository =
        coordinator(context).restoredRepository()

    private fun coordinator(context: Context): QuotaArcConnectionCoordinator {
        connectionCoordinator?.let { return it }
        return synchronized(this) {
            connectionCoordinator ?: QuotaArcData.createConnectionManager(
                context = context.applicationContext,
                scope = repositoryScope,
            ).also { connectionCoordinator = it }
        }
    }

    private fun RepositoryState.toWidgetInput(
        isRefreshing: Boolean,
    ): WidgetMapperInput = when (this) {
        RepositoryState.Empty -> WidgetMapperInput(
            snapshot = null,
            phoneCacheState = WidgetCacheState.EMPTY,
            lastFailure = WidgetFailureKind.NOT_CONFIGURED,
            isRefreshing = isRefreshing,
        )
        is RepositoryState.Error -> WidgetMapperInput(
            snapshot = null,
            phoneCacheState = WidgetCacheState.EMPTY,
            lastFailure = failure.toWidgetFailure(),
            isRefreshing = isRefreshing,
        )
        is RepositoryState.Content -> WidgetMapperInput(
            snapshot = WidgetSnapshotInput(
                generatedAt = snapshot.summary.generatedAt,
                collectorStale = snapshot.summary.stale,
                buckets = snapshot.summary.quota.limits.flatMap { limit ->
                    limit.windows.map { window ->
                        WidgetBucketInput(
                            limitId = limit.limitId,
                            limitName = limit.limitName,
                            windowMinutes = window.windowMinutes,
                            remainingPercent = window.remainingPercent,
                            resetsAt = window.resetsAt,
                        )
                    }
                },
                todayActivity = snapshot.summary.localUsage.let { usage ->
                    WidgetActivityInput(
                        newInputTokens = usage.newInputTokens,
                        cachedInputTokens = usage.cachedInputTokens,
                        outputTokens = usage.outputTokens,
                        reasoningTokens = usage.reasoningTokens,
                    )
                },
            ),
            phoneCacheState = phoneCacheState.toWidgetCacheState(),
            lastFailure = lastFailure?.toWidgetFailure(),
            isRefreshing = isRefreshing,
        )
    }

    private fun PhoneCacheState.toWidgetCacheState(): WidgetCacheState = when (this) {
        PhoneCacheState.CURRENT -> WidgetCacheState.FRESH
        PhoneCacheState.AGED,
        PhoneCacheState.FALLBACK,
        PhoneCacheState.STALE_FALLBACK -> WidgetCacheState.CACHED
    }

    private fun RefreshFailure.toDisposition(): WidgetRefreshDisposition =
        if (retryable) {
            WidgetRefreshDisposition.RETRY
        } else {
            WidgetRefreshDisposition.TERMINAL_FAILURE
        }

    private fun RefreshFailure.toWidgetFailure(): WidgetFailureKind = when (kind) {
        RefreshFailureKind.GATE_CLOSED -> WidgetFailureKind.NOT_CONFIGURED
        RefreshFailureKind.OFFLINE,
        RefreshFailureKind.TIMEOUT -> WidgetFailureKind.OFFLINE
        RefreshFailureKind.AUTH_REQUIRED ->
            if (code == "connection.not_configured") {
                WidgetFailureKind.NOT_CONFIGURED
            } else {
                WidgetFailureKind.AUTHENTICATION
            }
        RefreshFailureKind.CONTRACT_INVALID,
        RefreshFailureKind.UNSUPPORTED_SCHEMA,
        RefreshFailureKind.OUT_OF_ORDER -> WidgetFailureKind.INCOMPATIBLE
        RefreshFailureKind.REMOTE ->
            if (
                code.startsWith("tls.") ||
                code == "response.collector_identity_mismatch" ||
                code == "response.redirect_forbidden"
            ) {
                WidgetFailureKind.SECURITY
            } else {
                WidgetFailureKind.UNAVAILABLE
            }
        RefreshFailureKind.NO_RENDERABLE_DATA -> WidgetFailureKind.UNAVAILABLE
    }
}

/**
 * Prevents a WorkManager or widget-component cold start from issuing a request
 * through the restore-pending repository. The authoritative DataStore/Keystore
 * restore completes before the active repository is selected.
 */
internal suspend fun QuotaArcConnectionCoordinator.restoredRepository(): QuotaArcRepository {
    awaitInitialRestore()
    return repository
}
