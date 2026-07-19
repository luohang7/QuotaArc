package dev.quotaarc.android.widget

import java.time.Duration

internal object WidgetSyncPolicy {
    const val PERIODIC_WORK_NAME = "quotaarc.widget.periodic"
    const val MANUAL_WORK_NAME = "quotaarc.widget.manual"
    const val MANUAL_INPUT_KEY = "manual"
    const val PERIOD_MINUTES = 30L
    val REFRESH_INDICATOR_TTL: Duration = Duration.ofMinutes(10)

    val periodicSpec = WidgetScheduleSpec(
        uniqueName = PERIODIC_WORK_NAME,
        intervalMinutes = PERIOD_MINUTES,
        keepExisting = true,
    )
    val manualSpec = WidgetScheduleSpec(
        uniqueName = MANUAL_WORK_NAME,
        intervalMinutes = null,
        keepExisting = true,
    )

    fun canSchedulePeriodic(hasConnectionMetadata: Boolean): Boolean =
        hasConnectionMetadata
}

internal data class WidgetScheduleSpec(
    val uniqueName: String,
    val intervalMinutes: Long?,
    val keepExisting: Boolean,
)

internal enum class WidgetRefreshDisposition {
    SUCCESS,
    RETRY,
    TERMINAL_FAILURE,
}
