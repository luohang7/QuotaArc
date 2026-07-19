package dev.quotaarc.android.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

class QuotaArcWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuotaArcWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetSyncScheduler.schedulePeriodic(context)
    }

    override fun onDisabled(context: Context) {
        WidgetSyncScheduler.cancelPeriodic(context)
        super.onDisabled(context)
    }
}
