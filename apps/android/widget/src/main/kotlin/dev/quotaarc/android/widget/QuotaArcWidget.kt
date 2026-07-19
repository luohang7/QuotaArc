package dev.quotaarc.android.widget

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import java.time.Clock

class QuotaArcWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(COMPACT_SIZE, MEDIUM_SIZE),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val input = WidgetRepositoryBridge.current(context)
        val clock = Clock.systemDefaultZone()
        val locale = context.resources.configuration.locales[0]
        val ui = WidgetUiMapper.map(
            input = input,
            now = clock.instant(),
            zoneId = clock.zone,
            locale = locale,
            text = AndroidWidgetText(context),
        )
        provideContent {
            QuotaArcWidgetContent(ui)
        }
    }

    companion object {
        internal val COMPACT_SIZE = DpSize(150.dp, 130.dp)
        internal val MEDIUM_SIZE = DpSize(300.dp, 180.dp)
    }
}
