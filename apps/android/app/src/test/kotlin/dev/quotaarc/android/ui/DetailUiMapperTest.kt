package dev.quotaarc.android.ui

import dev.quotaarc.android.data.contract.AccountDailyTokenUsage
import dev.quotaarc.android.data.contract.AccountUsageSummary
import dev.quotaarc.android.data.contract.ContractValidationException
import dev.quotaarc.android.data.contract.LocalUsageCoverage
import dev.quotaarc.android.data.contract.LocalUsagePeriod
import dev.quotaarc.android.data.contract.LocalUsageSourceState
import dev.quotaarc.android.data.contract.LocalUsageSummary
import dev.quotaarc.android.data.contract.QuotaArcSources
import dev.quotaarc.android.data.contract.QuotaArcSummary
import dev.quotaarc.android.data.contract.QuotaLimit
import dev.quotaarc.android.data.contract.QuotaSummary
import dev.quotaarc.android.data.contract.QuotaWindow
import dev.quotaarc.android.data.contract.SourceKind
import dev.quotaarc.android.data.contract.SourceState
import dev.quotaarc.android.data.contract.SourceStatus
import dev.quotaarc.android.data.contract.UsageBreakdown
import dev.quotaarc.android.data.repository.PhoneCacheState
import dev.quotaarc.android.ui.model.PhoneCacheUiState
import dev.quotaarc.android.ui.model.SourceUiKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailUiMapperTest {
    @Test
    fun `keeps official and local sources separated and excludes reasoning from processed`() {
        val model = DetailUiMapper.map(
            candidate = validSummary(),
            locale = Locale.US,
            zoneId = ZoneOffset.UTC,
        )

        assertTrue(model.hasSnapshot)
        assertFalse(model.stale)
        assertEquals(listOf(SourceUiKind.QUOTA, SourceUiKind.ACCOUNT_USAGE, SourceUiKind.LOCAL_USAGE), model.sources.map { it.kind })
        assertEquals("42,000", model.officialDaily.single().tokensText)
        assertEquals("6,000", model.localModels.single().processedTokensText)
        assertEquals("4,000", model.localModels.single().reasoningTokensText)
        assertEquals(7L, model.sources.last().coverage?.files)
    }

    @Test
    fun `phone cache fallback does not rewrite Collector stale semantics`() {
        val model = DetailUiMapper.map(
            candidate = validSummary().copy(stale = false),
            locale = Locale.US,
            zoneId = ZoneOffset.UTC,
            phoneCacheState = PhoneCacheState.STALE_FALLBACK,
        )

        assertFalse(model.stale)
        assertEquals(PhoneCacheUiState.STALE_FALLBACK, model.phoneCacheState)
    }

    @Test
    fun `refuses unsafe client labels before rendering`() {
        val valid = validSummary()
        val unsafe = valid.copy(
            localUsage = valid.localUsage.copy(
                models = valid.localUsage.models.map {
                    it.copy(label = "/Users/example/private/session.jsonl")
                },
            ),
        )

        assertThrows(ContractValidationException::class.java) {
            DetailUiMapper.map(unsafe, Locale.US, ZoneOffset.UTC)
        }
    }

    private fun validSummary(): QuotaArcSummary {
        val collectedAt = Instant.parse("2026-07-19T06:00:00Z")
        val localBreakdown = UsageBreakdown(
            id = "gpt-5",
            label = "GPT-5",
            newInputTokens = 1_000,
            cachedInputTokens = 2_000,
            outputTokens = 3_000,
            reasoningTokens = 4_000,
        )
        return QuotaArcSummary(
            schemaVersion = 1,
            generatedAt = collectedAt,
            stale = false,
            sources = QuotaArcSources(
                quota = SourceState(
                    kind = SourceKind.CODEX_APP_SERVER,
                    status = SourceStatus.OK,
                    collectedAt = collectedAt,
                    error = null,
                ),
                accountUsage = SourceState(
                    kind = SourceKind.CODEX_APP_SERVER,
                    status = SourceStatus.OK,
                    collectedAt = collectedAt,
                    error = null,
                ),
                localUsage = LocalUsageSourceState(
                    kind = SourceKind.CODEX_SESSION_LOGS,
                    status = SourceStatus.OK,
                    collectedAt = collectedAt,
                    error = null,
                    coverage = LocalUsageCoverage(
                        files = 7,
                        firstEventAt = collectedAt.minusSeconds(3_600),
                        lastEventAt = collectedAt,
                    ),
                ),
            ),
            quota = QuotaSummary(
                limits = listOf(
                    QuotaLimit(
                        limitId = "five-hour",
                        limitName = "Five-hour usage",
                        windows = listOf(
                            QuotaWindow(
                                windowMinutes = 300,
                                usedPercent = 35.0,
                                remainingPercent = 65.0,
                                resetsAt = collectedAt.plusSeconds(1_800),
                            ),
                        ),
                    ),
                ),
            ),
            accountUsage = AccountUsageSummary(
                dailyTokens = listOf(
                    AccountDailyTokenUsage(
                        date = LocalDate.parse("2026-07-19"),
                        tokens = 42_000,
                    ),
                ),
            ),
            localUsage = LocalUsageSummary(
                period = LocalUsagePeriod.TODAY,
                newInputTokens = 1_000,
                cachedInputTokens = 2_000,
                outputTokens = 3_000,
                reasoningTokens = 4_000,
                models = listOf(localBreakdown),
                projects = listOf(localBreakdown.copy(id = "quotaarc", label = "QuotaArc")),
            ),
        )
    }
}
