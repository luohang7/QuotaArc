package dev.quotaarc.android.widget

import androidx.glance.appwidget.testing.unit.assertHasRunCallbackClickAction
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasContentDescriptionEqualTo
import androidx.glance.testing.unit.hasTestTag
import androidx.glance.testing.unit.hasTextEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuotaArcWidgetContentTest {
    @Test
    fun `compact composition exposes primary bucket and manual refresh action`() =
        runGlanceAppWidgetUnitTest {
            val ui = contentModel()

            provideComposable {
                QuotaArcWidgetContent(ui = ui, medium = false)
            }

            onNode(hasTestTag(WidgetTestTags.COMPACT)).assertExists()
            onNode(hasTestTag(WidgetTestTags.MEDIUM)).assertDoesNotExist()
            onNode(hasTextEqualTo("20% available")).assertExists()
            onNode(hasTestTag(WidgetTestTags.REFRESH))
                .assertHasRunCallbackClickAction<ManualRefreshAction>()
            onNode(hasContentDescriptionEqualTo(ui.accessibilityText)).assertExists()
        }

    @Test
    fun `medium composition keeps every quota bucket and local activity`() =
        runGlanceAppWidgetUnitTest {
            val ui = contentModel()

            provideComposable {
                QuotaArcWidgetContent(ui = ui, medium = true)
            }

            onNode(hasTestTag(WidgetTestTags.MEDIUM)).assertExists()
            onNode(hasTestTag(WidgetTestTags.COMPACT)).assertDoesNotExist()
            onNode(hasTextEqualTo("Today 7.9K Tokens")).assertExists()
            onNode(hasTextEqualTo("Codex · 1 week")).assertExists()
            onNode(hasTextEqualTo("Model · 5 hours")).assertExists()
        }

    @Test
    fun `empty composition renders the explicit safe failure state`() =
        runGlanceAppWidgetUnitTest {
            val ui = contentModel(
                primaryBucket = null,
                buckets = emptyList(),
                todayActivityText = null,
                freshnessText = "No cached data",
                statusText = "Authenticated transport is not available yet",
                statusTone = WidgetStatusTone.WARNING,
                isEmpty = true,
                accessibilityText =
                    "QuotaArc. Authenticated transport is not available yet. No cached data.",
            )

            provideComposable {
                QuotaArcWidgetContent(ui = ui, medium = false)
            }

            onNode(hasTestTag(WidgetTestTags.EMPTY)).assertExists()
            onNode(hasTestTag(WidgetTestTags.COMPACT)).assertDoesNotExist()
            onNode(hasTextEqualTo("Authenticated transport is not available yet"))
                .assertExists()
        }

    @Test
    fun `renderable snapshot without quota uses the no quota branch`() =
        runGlanceAppWidgetUnitTest {
            val ui = contentModel(
                primaryBucket = null,
                buckets = emptyList(),
                todayActivityText = "Today 0 Tokens",
                isEmpty = false,
            )

            provideComposable {
                QuotaArcWidgetContent(ui = ui, medium = false)
            }

            onNode(hasTestTag(WidgetTestTags.NO_QUOTA)).assertExists()
            onNode(hasTextEqualTo("No quota buckets")).assertExists()
            onNode(hasTextEqualTo("Today 0 Tokens")).assertExists()
        }

    private fun contentModel(
        primaryBucket: WidgetBucketUi? = WidgetBucketUi(
            title = "Model · 5 hours",
            remainingText = "20% available",
            resetText = "Resets Jul 19, 20:00",
            accessibilityText = "Model, 5 hours, 20 percent available",
        ),
        buckets: List<WidgetBucketUi> = listOf(
            WidgetBucketUi(
                title = "Codex · 1 week",
                remainingText = "81% available",
                resetText = "Resets Jul 25, 08:00",
                accessibilityText = "Codex, 1 week, 81 percent available",
            ),
            WidgetBucketUi(
                title = "Model · 5 hours",
                remainingText = "20% available",
                resetText = "Resets Jul 19, 20:00",
                accessibilityText = "Model, 5 hours, 20 percent available",
            ),
        ),
        todayActivityText: String? = "Today 7.9K Tokens",
        freshnessText: String = "30 minutes ago",
        statusText: String? = null,
        statusTone: WidgetStatusTone = WidgetStatusTone.NORMAL,
        isEmpty: Boolean = false,
        accessibilityText: String =
            "QuotaArc. Codex, 1 week, 81 percent available. " +
                "Model, 5 hours, 20 percent available. Today 7.9K Tokens.",
    ) = WidgetUiModel(
        title = "QuotaArc",
        refreshText = "Refresh",
        refreshAccessibilityText = "Refresh quota",
        noQuotaText = "No quota buckets",
        primaryBucket = primaryBucket,
        buckets = buckets,
        todayActivityText = todayActivityText,
        freshnessText = freshnessText,
        statusText = statusText,
        statusTone = statusTone,
        isRefreshing = false,
        isEmpty = isEmpty,
        accessibilityText = accessibilityText,
    )
}
