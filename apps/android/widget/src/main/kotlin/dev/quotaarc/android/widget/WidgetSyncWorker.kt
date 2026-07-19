package dev.quotaarc.android.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class WidgetSyncWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val manual = inputData.getBoolean(WidgetSyncPolicy.MANUAL_INPUT_KEY, false)
        if (manual) {
            WidgetRefreshStateStore.begin(applicationContext)
            QuotaArcWidget().updateAll(applicationContext)
        }
        return try {
            when (
                WidgetRepositoryBridge.refresh(
                    context = applicationContext,
                    manual = manual,
                )
            ) {
                WidgetRefreshDisposition.SUCCESS -> Result.success()
                WidgetRefreshDisposition.RETRY -> Result.retry()
                WidgetRefreshDisposition.TERMINAL_FAILURE -> Result.failure()
            }
        } finally {
            if (manual) WidgetRefreshStateStore.end(applicationContext)
            QuotaArcWidget().updateAll(applicationContext)
        }
    }
}
