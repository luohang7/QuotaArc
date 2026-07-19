package dev.quotaarc.android.widget

import android.content.Context

internal object WidgetRefreshStateStore {
    private const val PREFERENCES = "quotaarc_widget_state"
    private const val STARTED_AT_MILLIS = "refresh_started_at_millis"

    fun begin(context: Context, nowMillis: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putLong(STARTED_AT_MILLIS, nowMillis)
            .apply()
    }

    fun end(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .remove(STARTED_AT_MILLIS)
            .apply()
    }

    fun isRefreshing(
        context: Context,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        val startedAt = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getLong(STARTED_AT_MILLIS, 0L)
        return isActive(startedAt, nowMillis)
    }

    internal fun isActive(startedAtMillis: Long, nowMillis: Long): Boolean {
        if (startedAtMillis <= 0L || nowMillis < startedAtMillis) return false
        return nowMillis - startedAtMillis <=
            WidgetSyncPolicy.REFRESH_INDICATOR_TTL.toMillis()
    }
}
