package dev.quotaarc.android.widget

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dev.quotaarc.android.data.QuotaArcData
import java.util.concurrent.TimeUnit

object WidgetSyncScheduler {
    fun schedulePeriodic(context: Context) {
        if (!WidgetSyncPolicy.canSchedulePeriodic(QuotaArcData.transportGate)) {
            return
        }
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
        WorkManager.getInstance(context.applicationContext)
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
        val spec = WidgetSyncPolicy.manualSpec
        WidgetRefreshStateStore.begin(context.applicationContext)
        val request = OneTimeWorkRequestBuilder<WidgetSyncWorker>()
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
            .build()
        WorkManager.getInstance(context.applicationContext)
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
}
