package dev.quotaarc.android.ui

import dev.quotaarc.android.data.contract.LocalUsageSourceState
import dev.quotaarc.android.data.contract.QuotaArcSummary
import dev.quotaarc.android.data.contract.QuotaArcV1Validator
import dev.quotaarc.android.data.contract.SourceError
import dev.quotaarc.android.data.contract.SourceState
import dev.quotaarc.android.data.contract.SourceStatus
import dev.quotaarc.android.data.contract.TokenUsageCounts
import dev.quotaarc.android.data.contract.UsageBreakdown
import dev.quotaarc.android.data.repository.PhoneCacheState
import dev.quotaarc.android.ui.model.CoverageUi
import dev.quotaarc.android.ui.model.DailyUsageUi
import dev.quotaarc.android.ui.model.DetailUiModel
import dev.quotaarc.android.ui.model.LimitUi
import dev.quotaarc.android.ui.model.LimitWindowUi
import dev.quotaarc.android.ui.model.PhoneCacheUiState
import dev.quotaarc.android.ui.model.SourceDiagnosticUi
import dev.quotaarc.android.ui.model.SourceUiKind
import dev.quotaarc.android.ui.model.SourceUiStatus
import dev.quotaarc.android.ui.model.UsageBreakdownUi
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Pure presentation boundary for a validated v1 snapshot. Keeping formatting
 * here makes the official and local provenance lanes explicit and testable.
 */
internal object DetailUiMapper {
    fun map(
        candidate: QuotaArcSummary,
        locale: Locale = Locale.getDefault(),
        zoneId: ZoneId = ZoneId.systemDefault(),
        phoneCacheState: PhoneCacheState = PhoneCacheState.CURRENT,
    ): DetailUiModel {
        val summary = QuotaArcV1Validator.requireValid(candidate)
        val formatter = PresentationFormatter(locale, zoneId)

        return DetailUiModel(
            hasSnapshot = true,
            generatedAtText = formatter.instant(summary.generatedAt),
            stale = summary.stale,
            phoneCacheState = phoneCacheState.toUiState(),
            limits = summary.quota.limits.map { limit ->
                LimitUi(
                    id = limit.limitId,
                    name = limit.limitName,
                    windows = limit.windows.map { window ->
                        LimitWindowUi(
                            windowMinutes = window.windowMinutes,
                            usedPercent = window.usedPercent,
                            remainingPercent = window.remainingPercent,
                            resetText = formatter.instant(window.resetsAt),
                        )
                    },
                )
            },
            officialDaily = summary.accountUsage.dailyTokens.map { daily ->
                DailyUsageUi(
                    dateText = formatter.date(daily.date),
                    tokensText = formatter.number(daily.tokens),
                )
            },
            localModels = summary.localUsage.models.map { formatter.breakdown(it) },
            localProjects = summary.localUsage.projects.map { formatter.breakdown(it) },
            sources = listOf(
                formatter.source(
                    kind = SourceUiKind.QUOTA,
                    source = summary.sources.quota,
                ),
                formatter.source(
                    kind = SourceUiKind.ACCOUNT_USAGE,
                    source = summary.sources.accountUsage,
                ),
                formatter.localSource(summary.sources.localUsage),
            ),
        )
    }
}

private class PresentationFormatter(
    locale: Locale,
    zoneId: ZoneId,
) {
    private val numberFormat = NumberFormat.getIntegerInstance(locale)
    private val instantFormat =
        DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(locale)
            .withZone(zoneId)
    private val dateFormat =
        DateTimeFormatter
            .ofLocalizedDate(FormatStyle.MEDIUM)
            .withLocale(locale)

    fun number(value: Long): String = numberFormat.format(value)

    fun instant(value: Instant): String = instantFormat.format(value)

    fun date(value: LocalDate): String = dateFormat.format(value)

    fun breakdown(value: UsageBreakdown): UsageBreakdownUi =
        UsageBreakdownUi(
            id = value.id,
            label = value.label,
            processedTokensText = number(value.processedTokens()),
            newInputTokensText = number(value.newInputTokens),
            cachedInputTokensText = number(value.cachedInputTokens),
            outputTokensText = number(value.outputTokens),
            reasoningTokensText = number(value.reasoningTokens),
        )

    fun source(
        kind: SourceUiKind,
        source: SourceState,
    ): SourceDiagnosticUi =
        SourceDiagnosticUi(
            kind = kind,
            status = source.status.toUiStatus(),
            collectedAtText = source.collectedAt?.let(::instant),
            errorCode = source.error?.code,
            safeErrorMessage = source.error?.safeMessage(),
            coverage = null,
        )

    fun localSource(source: LocalUsageSourceState): SourceDiagnosticUi =
        SourceDiagnosticUi(
            kind = SourceUiKind.LOCAL_USAGE,
            status = source.status.toUiStatus(),
            collectedAtText = source.collectedAt?.let(::instant),
            errorCode = source.error?.code,
            safeErrorMessage = source.error?.safeMessage(),
            coverage = CoverageUi(
                files = source.coverage.files,
                firstEventAtText = source.coverage.firstEventAt?.let(::instant),
                lastEventAtText = source.coverage.lastEventAt?.let(::instant),
            ),
        )
}

private fun TokenUsageCounts.processedTokens(): Long =
    newInputTokens + cachedInputTokens + outputTokens

private fun SourceStatus.toUiStatus(): SourceUiStatus = when (this) {
    SourceStatus.OK -> SourceUiStatus.OK
    SourceStatus.STALE -> SourceUiStatus.STALE
    SourceStatus.UNAVAILABLE -> SourceUiStatus.UNAVAILABLE
    SourceStatus.UNSUPPORTED -> SourceUiStatus.UNSUPPORTED
    SourceStatus.ERROR -> SourceUiStatus.ERROR
}

private fun PhoneCacheState.toUiState(): PhoneCacheUiState = when (this) {
    PhoneCacheState.CURRENT -> PhoneCacheUiState.CURRENT
    PhoneCacheState.AGED -> PhoneCacheUiState.AGED
    PhoneCacheState.FALLBACK -> PhoneCacheUiState.FALLBACK
    PhoneCacheState.STALE_FALLBACK -> PhoneCacheUiState.STALE_FALLBACK
}

private fun SourceError.safeMessage(): String = message
