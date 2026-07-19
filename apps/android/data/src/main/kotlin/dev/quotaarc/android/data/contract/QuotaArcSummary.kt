package dev.quotaarc.android.data.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate

const val QUOTA_ARC_SCHEMA_VERSION = 1
const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L

@Serializable
enum class SourceStatus {
    @SerialName("ok")
    OK,

    @SerialName("stale")
    STALE,

    @SerialName("unavailable")
    UNAVAILABLE,

    @SerialName("unsupported")
    UNSUPPORTED,

    @SerialName("error")
    ERROR,
}

@Serializable
enum class SourceKind {
    @SerialName("codex_app_server")
    CODEX_APP_SERVER,

    @SerialName("codex_session_logs")
    CODEX_SESSION_LOGS,
}

@Serializable
enum class LocalUsagePeriod {
    @SerialName("today")
    TODAY,

    @SerialName("week")
    WEEK,

    @SerialName("month")
    MONTH,
}

@Serializable
data class SourceError(
    val code: String,
    val message: String,
    val retryable: Boolean,
)

@Serializable
data class SourceState(
    val kind: SourceKind,
    val status: SourceStatus,
    @Serializable(with = IsoInstantSerializer::class)
    val collectedAt: Instant?,
    val error: SourceError?,
)

@Serializable
data class LocalUsageCoverage(
    val files: Long,
    @Serializable(with = IsoInstantSerializer::class)
    val firstEventAt: Instant?,
    @Serializable(with = IsoInstantSerializer::class)
    val lastEventAt: Instant?,
)

@Serializable
data class LocalUsageSourceState(
    val kind: SourceKind,
    val status: SourceStatus,
    @Serializable(with = IsoInstantSerializer::class)
    val collectedAt: Instant?,
    val error: SourceError?,
    val coverage: LocalUsageCoverage,
)

@Serializable
data class QuotaArcSources(
    val quota: SourceState,
    val accountUsage: SourceState,
    val localUsage: LocalUsageSourceState,
)

@Serializable
data class QuotaWindow(
    val windowMinutes: Long,
    val usedPercent: Double,
    val remainingPercent: Double,
    @Serializable(with = IsoInstantSerializer::class)
    val resetsAt: Instant,
)

@Serializable
data class QuotaLimit(
    val limitId: String,
    val limitName: String?,
    val windows: List<QuotaWindow>,
)

@Serializable
data class QuotaSummary(
    val limits: List<QuotaLimit>,
)

@Serializable
data class AccountDailyTokenUsage(
    @Serializable(with = IsoLocalDateSerializer::class)
    val date: LocalDate,
    val tokens: Long,
)

@Serializable
data class AccountUsageSummary(
    val dailyTokens: List<AccountDailyTokenUsage>,
)

interface TokenUsageCounts {
    val newInputTokens: Long
    val cachedInputTokens: Long
    val outputTokens: Long
    val reasoningTokens: Long
}

@Serializable
data class UsageBreakdown(
    val id: String,
    val label: String,
    override val newInputTokens: Long,
    override val cachedInputTokens: Long,
    override val outputTokens: Long,
    override val reasoningTokens: Long,
) : TokenUsageCounts

@Serializable
data class LocalUsageSummary(
    val period: LocalUsagePeriod,
    override val newInputTokens: Long,
    override val cachedInputTokens: Long,
    override val outputTokens: Long,
    override val reasoningTokens: Long,
    val models: List<UsageBreakdown>,
    val projects: List<UsageBreakdown>,
) : TokenUsageCounts

@Serializable
data class QuotaArcSummary(
    val schemaVersion: Int,
    @Serializable(with = IsoInstantSerializer::class)
    val generatedAt: Instant,
    val stale: Boolean,
    val sources: QuotaArcSources,
    val quota: QuotaSummary,
    val accountUsage: AccountUsageSummary,
    val localUsage: LocalUsageSummary,
)
