package dev.quotaarc.android.widget

import java.time.Instant

internal enum class WidgetCacheState {
    FRESH,
    CACHED,
    EMPTY,
}

internal enum class WidgetFailureKind {
    GATE_CLOSED,
    NOT_CONFIGURED,
    AUTHENTICATION,
    OFFLINE,
    SECURITY,
    INCOMPATIBLE,
    UNAVAILABLE,
}

internal enum class WidgetStatusTone {
    NORMAL,
    WARNING,
    ERROR,
}

internal data class WidgetBucketInput(
    val limitId: String,
    val limitName: String?,
    val windowMinutes: Long,
    val remainingPercent: Double,
    val resetsAt: Instant,
)

internal data class WidgetActivityInput(
    val newInputTokens: Long,
    val cachedInputTokens: Long,
    val outputTokens: Long,
    val reasoningTokens: Long,
)

internal data class WidgetSnapshotInput(
    val generatedAt: Instant,
    val collectorStale: Boolean,
    val buckets: List<WidgetBucketInput>,
    val todayActivity: WidgetActivityInput,
)

internal data class WidgetMapperInput(
    val snapshot: WidgetSnapshotInput?,
    val phoneCacheState: WidgetCacheState,
    val lastFailure: WidgetFailureKind?,
    val isRefreshing: Boolean,
)

internal data class WidgetBucketUi(
    val title: String,
    val remainingText: String,
    val resetText: String,
    val accessibilityText: String,
)

internal data class WidgetUiModel(
    val title: String,
    val refreshText: String,
    val refreshAccessibilityText: String,
    val noQuotaText: String,
    val primaryBucket: WidgetBucketUi?,
    val buckets: List<WidgetBucketUi>,
    val todayActivityText: String?,
    val freshnessText: String,
    val statusText: String?,
    val statusTone: WidgetStatusTone,
    val isRefreshing: Boolean,
    val isEmpty: Boolean,
    val accessibilityText: String,
)
