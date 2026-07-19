package dev.quotaarc.android.widget

import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

internal object WidgetUiMapper {
    fun map(
        input: WidgetMapperInput,
        now: Instant,
        zoneId: ZoneId,
        locale: Locale = Locale.SIMPLIFIED_CHINESE,
        text: WidgetText = ZhHansWidgetText,
    ): WidgetUiModel {
        val snapshot = input.snapshot
        if (snapshot == null) {
            return emptyModel(input, text)
        }

        val bucketUis = snapshot.buckets.map { bucket ->
            bucket.toUi(zoneId, locale, text)
        }
        val primaryIndex = snapshot.buckets.indices.minWithOrNull(
            compareBy<Int>(
                { snapshot.buckets[it].remainingPercent.coerceIn(0.0, 100.0) },
                { snapshot.buckets[it].resetsAt },
                {
                    safeLabel(
                        snapshot.buckets[it].limitName,
                        snapshot.buckets[it].limitId,
                        text,
                    )
                },
                { snapshot.buckets[it].windowMinutes },
            ),
        )
        val primary = primaryIndex?.let(bucketUis::get)
        val todayActivity = snapshot.todayActivity.toUiText(text)
        val freshness = freshnessText(input, snapshot, now, text)
        val status = statusText(input, snapshot, text)
        val tone = statusTone(input, snapshot)
        val accessibleBuckets = if (bucketUis.isEmpty()) {
            text.noQuotaBuckets
        } else {
            bucketUis.joinToString("；") { it.accessibilityText }
        }
        val accessibleActivity = todayActivity
        val accessibleStatus = listOfNotNull(
            if (input.isRefreshing) text.refreshingShort else null,
            status,
            freshness,
        ).joinToString("，")

        return WidgetUiModel(
            title = text.title,
            refreshText = if (input.isRefreshing) text.refreshingShort else text.refresh,
            refreshAccessibilityText = if (input.isRefreshing) {
                text.refreshingAccessibility
            } else {
                text.refreshAccessibility
            },
            noQuotaText = text.noQuotaBuckets,
            primaryBucket = primary,
            buckets = bucketUis,
            todayActivityText = todayActivity,
            freshnessText = freshness,
            statusText = status,
            statusTone = tone,
            isRefreshing = input.isRefreshing,
            isEmpty = false,
            accessibilityText = listOf(
                text.title,
                accessibleBuckets,
                accessibleActivity,
                accessibleStatus,
            ).filter(String::isNotBlank).joinToString("。"),
        )
    }

    private fun emptyModel(
        input: WidgetMapperInput,
        text: WidgetText,
    ): WidgetUiModel {
        val status = when {
            input.isRefreshing -> text.readingCache
            input.lastFailure == WidgetFailureKind.GATE_CLOSED -> text.gateClosedEmpty
            input.lastFailure == WidgetFailureKind.NOT_CONFIGURED ->
                text.notConfiguredEmpty
            input.lastFailure == WidgetFailureKind.AUTHENTICATION ->
                text.authenticationEmpty
            input.lastFailure == WidgetFailureKind.OFFLINE -> text.offlineEmpty
            input.lastFailure == WidgetFailureKind.SECURITY -> text.securityEmpty
            input.lastFailure == WidgetFailureKind.INCOMPATIBLE ->
                text.incompatibleEmpty
            else -> text.noCache
        }
        val tone = when (input.lastFailure) {
            WidgetFailureKind.AUTHENTICATION,
            WidgetFailureKind.SECURITY,
            WidgetFailureKind.INCOMPATIBLE -> WidgetStatusTone.ERROR
            else -> WidgetStatusTone.WARNING
        }
        return WidgetUiModel(
            title = text.title,
            refreshText = if (input.isRefreshing) text.refreshingShort else text.refresh,
            refreshAccessibilityText = if (input.isRefreshing) {
                text.refreshingAccessibility
            } else {
                text.refreshAccessibility
            },
            noQuotaText = text.noQuotaBuckets,
            primaryBucket = null,
            buckets = emptyList(),
            todayActivityText = null,
            freshnessText = text.waitFirstSuccess,
            statusText = status,
            statusTone = tone,
            isRefreshing = input.isRefreshing,
            isEmpty = true,
            accessibilityText = "${text.title}。$status。${text.waitFirstSuccess}。",
        )
    }

    private fun WidgetBucketInput.toUi(
        zoneId: ZoneId,
        locale: Locale,
        text: WidgetText,
    ): WidgetBucketUi {
        val title =
            "${safeLabel(limitName, limitId, text)} · ${windowLabel(windowMinutes, text)}"
        val remaining = text.available(
            remainingPercent.coerceIn(0.0, 100.0).roundToInt(),
        )
        val formatter = DateTimeFormatter.ofPattern(text.resetDatePattern, locale)
            .withZone(zoneId)
        val reset = text.resetAt(formatter.format(resetsAt))
        return WidgetBucketUi(
            title = title,
            remainingText = remaining,
            resetText = reset,
            accessibilityText = "$title，$remaining，$reset",
        )
    }

    private fun WidgetActivityInput.toUiText(text: WidgetText): String {
        val processed = saturatingAdd(
            saturatingAdd(newInputTokens, cachedInputTokens),
            outputTokens,
        )
        return text.todayActivity(compactNumber(processed.coerceAtLeast(0L)))
    }

    private fun freshnessText(
        input: WidgetMapperInput,
        snapshot: WidgetSnapshotInput,
        now: Instant,
        text: WidgetText,
    ): String {
        val age = Duration.between(snapshot.generatedAt, now)
            .coerceAtLeast(Duration.ZERO)
        val ageText = when {
            age < Duration.ofMinutes(1) -> text.justUpdated
            age < Duration.ofHours(1) -> text.minutesAgo(age.toMinutes())
            age < Duration.ofDays(1) -> text.hoursAgo(age.toHours())
            else -> text.daysAgo(age.toDays())
        }
        return when {
            input.phoneCacheState == WidgetCacheState.CACHED && snapshot.collectorStale ->
                text.cached(text.sourceStale(ageText))
            input.phoneCacheState == WidgetCacheState.CACHED ->
                text.cached(ageText)
            snapshot.collectorStale -> text.sourceStale(ageText)
            else -> ageText
        }
    }

    private fun statusText(
        input: WidgetMapperInput,
        snapshot: WidgetSnapshotInput,
        text: WidgetText,
    ): String? {
        val refreshOrPhoneStatus = when {
            input.isRefreshing -> text.refreshingStatus
            input.lastFailure == WidgetFailureKind.GATE_CLOSED -> text.gateClosedCached
            input.lastFailure == WidgetFailureKind.NOT_CONFIGURED ->
                text.notConfiguredCached
            input.lastFailure == WidgetFailureKind.AUTHENTICATION ->
                text.authenticationCached
            input.lastFailure == WidgetFailureKind.OFFLINE -> text.offlineCached
            input.lastFailure == WidgetFailureKind.SECURITY -> text.securityCached
            input.lastFailure == WidgetFailureKind.INCOMPATIBLE ->
                text.incompatibleCached
            input.lastFailure == WidgetFailureKind.UNAVAILABLE -> text.unavailableCached
            else -> null
        }
        val collectorStatus = text.sourceLastGood.takeIf { snapshot.collectorStale }
        return listOfNotNull(refreshOrPhoneStatus, collectorStatus)
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" · ")
    }

    private fun statusTone(
        input: WidgetMapperInput,
        snapshot: WidgetSnapshotInput,
    ): WidgetStatusTone = when {
        input.lastFailure == WidgetFailureKind.AUTHENTICATION ||
            input.lastFailure == WidgetFailureKind.SECURITY ||
            input.lastFailure == WidgetFailureKind.INCOMPATIBLE ->
            WidgetStatusTone.ERROR
        input.lastFailure != null ||
            snapshot.collectorStale ||
            input.phoneCacheState == WidgetCacheState.CACHED ->
            WidgetStatusTone.WARNING
        else -> WidgetStatusTone.NORMAL
    }

    private fun safeLabel(
        limitName: String?,
        limitId: String,
        text: WidgetText,
    ): String {
        val candidate = limitName?.takeIf(String::isNotBlank) ?: limitId
        if (candidate.isBlank() ||
            candidate.length > 80 ||
            candidate.any(Char::isISOControl) ||
            candidate.contains('/') ||
            candidate.contains('\\') ||
            candidate.contains("Bearer ", ignoreCase = true) ||
            candidate.startsWith("sk-", ignoreCase = true)
        ) {
            return text.quotaFallback
        }
        return candidate
    }

    private fun windowLabel(minutes: Long, text: WidgetText): String = when {
        minutes > 0 && minutes % (7L * 24L * 60L) == 0L ->
            text.weeks(minutes / (7L * 24L * 60L))
        minutes > 0 && minutes % (24L * 60L) == 0L ->
            text.days(minutes / (24L * 60L))
        minutes > 0 && minutes % 60L == 0L ->
            text.hours(minutes / 60L)
        else -> text.minutes(minutes.coerceAtLeast(1L))
    }

    private fun compactNumber(value: Long): String {
        if (value < 1_000L) return value.toString()
        val divisor = when {
            value >= 1_000_000_000L -> 1_000_000_000L
            value >= 1_000_000L -> 1_000_000L
            else -> 1_000L
        }
        val suffix = when (divisor) {
            1_000_000_000L -> "B"
            1_000_000L -> "M"
            else -> "K"
        }
        val formatter = DecimalFormat(
            if (value % divisor == 0L) "0" else "0.#",
            DecimalFormatSymbols(Locale.US),
        ).apply {
            roundingMode = RoundingMode.HALF_UP
        }
        return "${formatter.format(value.toDouble() / divisor)}$suffix"
    }

    private fun saturatingAdd(left: Long, right: Long): Long {
        val safeLeft = left.coerceAtLeast(0L)
        val safeRight = right.coerceAtLeast(0L)
        return if (Long.MAX_VALUE - safeLeft < safeRight) {
            Long.MAX_VALUE
        } else {
            safeLeft + safeRight
        }
    }
}
