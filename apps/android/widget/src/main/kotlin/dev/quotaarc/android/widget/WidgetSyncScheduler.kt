package dev.quotaarc.android.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dev.quotaarc.android.data.QuotaArcData
import dev.quotaarc.android.data.connection.ConnectionRestoreResult
import java.util.concurrent.TimeUnit

object WidgetSyncScheduler {
    fun schedulePeriodic(context: Context) {
        val transportAvailable = connectionReadyForScheduling(
            restoreResult = QuotaArcData.authoritativeRestoreResult(),
            metadataHint = QuotaArcData.hasConnectionMetadata(context),
        )
        schedulePeriodic(
            context = context,
            transportAvailable = WidgetSyncPolicy.canSchedulePeriodic(
                transportAvailable,
            ) && hasActiveWidgets(context),
        )
    }

    /**
     * Called only after the connection manager has completed its strict probe
     * and authoritative encrypted commit.
     */
    fun schedulePeriodicForActiveConnection(context: Context) {
        schedulePeriodic(
            context = context,
            transportAvailable = hasActiveWidgets(context),
        )
    }

    internal fun schedulePeriodic(
        context: Context,
        transportAvailable: Boolean,
    ): Operation? {
        if (!transportAvailable) return null

        val spec = WidgetSyncPolicy.periodicSpec
        val request = PeriodicWorkRequestBuilder<WidgetSyncWorker>(
            requireNotNull(spec.intervalMinutes),
            TimeUnit.MINUTES,
        )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30L,
                TimeUnit.SECONDS,
            )
            .build()
        return WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(
                spec.uniqueName,
                if (spec.keepExisting) {
                    ExistingPeriodicWorkPolicy.KEEP
                } else {
                    ExistingPeriodicWorkPolicy.UPDATE
                },
                request,
            )
    }

    fun enqueueManual(context: Context) {
        enqueueManual(context = context, initialDelayMillis = 0L)
    }

    internal fun enqueueManual(
        context: Context,
        initialDelayMillis: Long,
    ): Operation {
        require(initialDelayMillis >= 0L) {
            "initialDelayMillis must be non-negative"
        }

        val spec = WidgetSyncPolicy.manualSpec
        WidgetRefreshStateStore.begin(context.applicationContext)
        val requestBuilder = OneTimeWorkRequestBuilder<WidgetSyncWorker>()
            .setInputData(
                Data.Builder()
                    .putBoolean(WidgetSyncPolicy.MANUAL_INPUT_KEY, true)
                    .build(),
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30L,
                TimeUnit.SECONDS,
            )
        if (initialDelayMillis > 0L) {
            requestBuilder.setInitialDelay(
                initialDelayMillis,
                TimeUnit.MILLISECONDS,
            )
        }
        val request = requestBuilder.build()
        return WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(
                spec.uniqueName,
                if (spec.keepExisting) {
                    ExistingWorkPolicy.KEEP
                } else {
                    ExistingWorkPolicy.REPLACE
                },
                request,
            )
    }

    fun cancelPeriodic(context: Context) {
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork(WidgetSyncPolicy.PERIODIC_WORK_NAME)
    }

    private fun hasActiveWidgets(context: Context): Boolean =
        AppWidgetManager.getInstance(context.applicationContext)
            .getAppWidgetIds(
                ComponentName(
                    context.applicationContext,
                    QuotaArcWidgetReceiver::class.java,
                ),
            )
            .isNotEmpty()
}

/**
 * A completed authoritative restore always wins over the derived scheduling
 * hint. The hint is used only during the short startup window; AppGraph
 * reconciles scheduling again when restore completes.
 */
internal fun connectionReadyForScheduling(
    restoreResult: ConnectionRestoreResult?,
    metadataHint: Boolean,
): Boolean = when (restoreResult) {
    is ConnectionRestoreResult.Ready -> true
    null -> metadataHint
    ConnectionRestoreResult.Absent,
    is ConnectionRestoreResult.CredentialUnavailable,
    ConnectionRestoreResult.Invalid,
    -> false
}
