package dev.quotaarc.android.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.background
import androidx.glance.color.ColorProvider as DayNightColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.semantics.testTag
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider

@Composable
internal fun QuotaArcWidgetContent(ui: WidgetUiModel) {
    val medium = LocalSize.current.width >= QuotaArcWidget.MEDIUM_SIZE.width
    QuotaArcWidgetContent(ui = ui, medium = medium)
}

@Composable
internal fun QuotaArcWidgetContent(
    ui: WidgetUiModel,
    medium: Boolean,
) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(R.color.widget_background)
            .cornerRadius(24.dp)
            .padding(if (medium) 16.dp else 8.dp)
            .semantics {
                contentDescription = ui.accessibilityText
                testTag = WidgetTestTags.ROOT
            },
    ) {
        Header(ui)
        Spacer(GlanceModifier.height(if (medium) 6.dp else 4.dp))
        if (ui.isEmpty) {
            EmptyContent(ui)
        } else if (medium) {
            MediumContent(ui)
        } else if (ui.primaryBucket == null) {
            NoQuotaContent(ui)
        } else {
            CompactContent(ui)
        }
    }
}

@Composable
private fun Header(ui: WidgetUiModel) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = ui.title,
            modifier = GlanceModifier.defaultWeight(),
            style = TextStyle(
                color = primaryColor(),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
        )
        Text(
            text = ui.refreshText,
            modifier = GlanceModifier
                .clickable(actionRunCallback<ManualRefreshAction>())
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .semantics {
                    contentDescription = ui.refreshAccessibilityText
                    testTag = WidgetTestTags.REFRESH
                },
            style = TextStyle(
                color = accentColor(),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
        )
    }
}

@Composable
private fun CompactContent(ui: WidgetUiModel) {
    val primary = ui.primaryBucket ?: return
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(R.color.widget_surface)
            .cornerRadius(18.dp)
            .padding(8.dp)
            .semantics { testTag = WidgetTestTags.COMPACT },
    ) {
        Text(
            text = primary.title,
            style = secondaryStyle(12),
            maxLines = 1,
        )
        Text(
            text = primary.remainingText,
            style = TextStyle(
                color = primaryColor(),
                fontSize = 25.sp,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
        )
        Text(
            text = listOfNotNull(
                primary.resetText,
                ui.statusText,
                ui.freshnessText,
            ).joinToString(" · "),
            style = TextStyle(
                color = statusColor(ui.statusTone),
                fontSize = 10.sp,
            ),
            maxLines = 2,
        )
    }
}

@Composable
private fun NoQuotaContent(ui: WidgetUiModel) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(R.color.widget_surface)
            .cornerRadius(18.dp)
            .padding(10.dp)
            .semantics { testTag = WidgetTestTags.NO_QUOTA },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = ui.noQuotaText,
            style = TextStyle(
                color = warningColor(),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
        )
        ui.todayActivityText?.let {
            Text(text = it, style = secondaryStyle(11), maxLines = 1)
        }
        StatusFooter(ui)
    }
}

@Composable
private fun MediumContent(ui: WidgetUiModel) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(R.color.widget_surface)
            .cornerRadius(18.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .semantics { testTag = WidgetTestTags.MEDIUM },
    ) {
        ui.todayActivityText?.let { activity ->
            Text(
                text = activity,
                style = TextStyle(
                    color = accentColor(),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
            )
            Spacer(GlanceModifier.height(4.dp))
        }
        LazyColumn(
            modifier = GlanceModifier
                .fillMaxWidth()
                .defaultWeight(),
        ) {
            ui.buckets.forEach { bucket ->
                item {
                    BucketRow(bucket)
                }
            }
        }
        StatusFooter(ui)
    }
}

@Composable
private fun BucketRow(bucket: WidgetBucketUi) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = bucket.title,
                style = secondaryStyle(11),
                maxLines = 1,
            )
            Text(
                text = bucket.resetText,
                style = secondaryStyle(10),
                maxLines = 1,
            )
        }
        Text(
            text = bucket.remainingText,
            style = TextStyle(
                color = primaryColor(),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
        )
    }
}

@Composable
private fun EmptyContent(ui: WidgetUiModel) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(R.color.widget_surface)
            .cornerRadius(18.dp)
            .padding(12.dp)
            .semantics { testTag = WidgetTestTags.EMPTY },
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = ui.statusText ?: ui.freshnessText,
            style = TextStyle(
                color = statusColor(ui.statusTone),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 2,
        )
        Spacer(GlanceModifier.height(4.dp))
        Text(
            text = ui.freshnessText,
            style = secondaryStyle(11),
            maxLines = 2,
        )
    }
}

@Composable
private fun StatusFooter(ui: WidgetUiModel) {
    val footer = listOfNotNull(ui.statusText, ui.freshnessText)
        .joinToString(" · ")
    Text(
        text = footer,
        modifier = GlanceModifier.fillMaxWidth(),
        style = TextStyle(
            color = statusColor(ui.statusTone),
            fontSize = 10.sp,
        ),
        maxLines = 1,
    )
}

private fun secondaryStyle(size: Int): TextStyle = TextStyle(
    color = secondaryColor(),
    fontSize = size.sp,
)

private fun statusColor(tone: WidgetStatusTone): ColorProvider = when (tone) {
    WidgetStatusTone.NORMAL -> secondaryColor()
    WidgetStatusTone.WARNING -> warningColor()
    WidgetStatusTone.ERROR -> errorColor()
}

private fun primaryColor(): ColorProvider = DayNightColorProvider(
    day = Color(0xFF231F20),
    night = Color(0xFFFFF8F2),
)

private fun secondaryColor(): ColorProvider = DayNightColorProvider(
    day = Color(0xFF675F5B),
    night = Color(0xFFD2C5BE),
)

private fun accentColor(): ColorProvider = DayNightColorProvider(
    day = Color(0xFF9A4527),
    night = Color(0xFFFFB59A),
)

private fun warningColor(): ColorProvider = DayNightColorProvider(
    day = Color(0xFF8A4B08),
    night = Color(0xFFFFD18C),
)

private fun errorColor(): ColorProvider = DayNightColorProvider(
    day = Color(0xFFA12A2A),
    night = Color(0xFFFFB3AD),
)

internal object WidgetTestTags {
    const val ROOT = "widget-root"
    const val REFRESH = "widget-refresh"
    const val COMPACT = "widget-compact"
    const val MEDIUM = "widget-medium"
    const val EMPTY = "widget-empty"
    const val NO_QUOTA = "widget-no-quota"
}
