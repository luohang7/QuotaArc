package dev.quotaarc.android.widget

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestDriver
import androidx.work.testing.WorkManagerTestInitHelper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetSyncSchedulerIntegrationTest {
    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private lateinit var testDriver: TestDriver
    private lateinit var recordingFactory: RecordingWorkerFactory

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        recordingFactory = RecordingWorkerFactory()
        val configuration = Configuration.Builder()
            .setMinimumLoggingLevel(Log.WARN)
            .setWorkerFactory(recordingFactory)
            .build()

        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            configuration,
            WorkManagerTestInitHelper.ExecutorsMode
                .LEGACY_OVERRIDE_WITH_SYNCHRONOUS_EXECUTORS,
        )
        workManager = WorkManager.getInstance(context)
        testDriver = requireNotNull(
            WorkManagerTestInitHelper.getTestDriver(context),
        )
    }

    @After
    fun tearDown() {
        workManager.cancelAllWork().result.get(5, TimeUnit.SECONDS)
        workManager.pruneWork().result.get(5, TimeUnit.SECONDS)
        WidgetRefreshStateStore.end(context)
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    @Test
    fun uniqueSchedulesUseTestDriverForPeriodDelayAndManualRetry() {
        val closedOperation = WidgetSyncScheduler.schedulePeriodic(
            context = context,
            transportAvailable = false,
        )
        assertNull(closedOperation)
        assertTrue(
            uniqueWork(WidgetSyncPolicy.PERIODIC_WORK_NAME).isEmpty(),
        )

        val firstPeriodicOperation = WidgetSyncScheduler.schedulePeriodic(
            context = context,
            transportAvailable = true,
        )
        assertNotNull(firstPeriodicOperation)
        requireNotNull(firstPeriodicOperation).result.get(5, TimeUnit.SECONDS)
        val firstPeriodic = uniqueWork(
            WidgetSyncPolicy.PERIODIC_WORK_NAME,
        ).single()

        WidgetSyncScheduler.schedulePeriodic(
            context = context,
            transportAvailable = true,
        )?.result?.get(5, TimeUnit.SECONDS)
        val keptPeriodic = uniqueWork(
            WidgetSyncPolicy.PERIODIC_WORK_NAME,
        ).single()
        assertEquals(firstPeriodic.id, keptPeriodic.id)

        val periodicRunsBeforeDriver = recordingFactory.runs.count {
            !it.manual
        }
        testDriver.setPeriodDelayMet(keptPeriodic.id)
        recordingFactory.awaitRunCount(
            manual = false,
            expectedMinimum = periodicRunsBeforeDriver + 1,
        )
        val periodicAfterDriver = awaitWorkInfo(keptPeriodic.id) {
            it.state == WorkInfo.State.ENQUEUED
        }
        assertEquals(WorkInfo.State.ENQUEUED, periodicAfterDriver.state)
        assertFalse(recordingFactory.runs.last { !it.manual }.manual)

        WidgetSyncScheduler.enqueueManual(
            context = context,
            initialDelayMillis = 10_000L,
        ).result.get(5, TimeUnit.SECONDS)
        val firstManual = uniqueWork(
            WidgetSyncPolicy.MANUAL_WORK_NAME,
        ).single()
        WidgetSyncScheduler.enqueueManual(
            context = context,
            initialDelayMillis = 10_000L,
        ).result.get(5, TimeUnit.SECONDS)
        val keptManual = uniqueWork(
            WidgetSyncPolicy.MANUAL_WORK_NAME,
        ).single()

        assertEquals(firstManual.id, keptManual.id)
        assertEquals(WorkInfo.State.ENQUEUED, keptManual.state)
        assertTrue(recordingFactory.runs.none { it.manual })

        testDriver.setInitialDelayMet(keptManual.id)
        recordingFactory.awaitRunCount(manual = true, expectedMinimum = 1)
        val retryingManual = awaitWorkInfo(keptManual.id) {
            it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount == 1
        }
        val manualRun = recordingFactory.runs.single { it.manual }

        assertTrue(manualRun.manualInput)
        assertEquals(0, manualRun.runAttemptCount)
        assertEquals(WorkInfo.State.ENQUEUED, retryingManual.state)
        assertEquals(1, retryingManual.runAttemptCount)
    }

    private fun uniqueWork(name: String): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(name)
            .get(5, TimeUnit.SECONDS)

    private fun awaitWorkInfo(
        id: java.util.UUID,
        predicate: (WorkInfo) -> Boolean,
    ): WorkInfo {
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        var latest = requireNotNull(
            workManager.getWorkInfoById(id)
                .get(5, TimeUnit.SECONDS),
        ) {
            "WorkInfo disappeared for id=$id"
        }
        while (!predicate(latest) && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(25L)
            latest = requireNotNull(
                workManager.getWorkInfoById(id)
                    .get(5, TimeUnit.SECONDS),
            ) {
                "WorkInfo disappeared for id=$id"
            }
        }
        assertTrue("WorkInfo did not reach the expected state: $latest", predicate(latest))
        return latest
    }
}

private data class RecordedRun(
    val manual: Boolean,
    val manualInput: Boolean,
    val runAttemptCount: Int,
)

private class RecordingWorkerFactory : WorkerFactory() {
    val runs = CopyOnWriteArrayList<RecordedRun>()

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? {
        if (workerClassName != WidgetSyncWorker::class.java.name) return null
        return RecordingWorker(
            appContext = appContext,
            workerParameters = workerParameters,
            factory = this,
        )
    }

    fun awaitRunCount(
        manual: Boolean,
        expectedMinimum: Int,
    ) {
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        while (
            runs.count { it.manual == manual } < expectedMinimum &&
            SystemClock.elapsedRealtime() < deadline
        ) {
            Thread.sleep(25L)
        }
        assertTrue(
            "Expected at least $expectedMinimum manual=$manual runs, got $runs",
            runs.count { it.manual == manual } >= expectedMinimum,
        )
    }

    fun record(workerParameters: WorkerParameters): ListenableWorker.Result {
        val manualInput = workerParameters.inputData.getBoolean(
            WidgetSyncPolicy.MANUAL_INPUT_KEY,
            false,
        )
        runs += RecordedRun(
            manual = manualInput,
            manualInput = manualInput,
            runAttemptCount = workerParameters.runAttemptCount,
        )
        return if (manualInput) {
            ListenableWorker.Result.retry()
        } else {
            ListenableWorker.Result.success()
        }
    }
}

private class RecordingWorker(
    appContext: Context,
    private val workerParameters: WorkerParameters,
    private val factory: RecordingWorkerFactory,
) : Worker(appContext, workerParameters) {
    override fun doWork(): Result = factory.record(workerParameters)
}
