package dev.quotaarc.android.data.contract

import java.math.BigInteger
import kotlin.math.abs

data class ContractValidationIssue(
    val path: String,
    val message: String,
)

class ContractValidationException(
    val issues: List<ContractValidationIssue>,
) : IllegalArgumentException(
    "Invalid QuotaArc v1 summary: " +
        issues.joinToString("; ") { "${it.path} ${it.message}" },
)

object QuotaArcV1Validator {
    private val errorCodePattern = Regex("^[a-z0-9][a-z0-9_.-]*$")

    fun validate(summary: QuotaArcSummary): List<ContractValidationIssue> = buildList {
        if (summary.schemaVersion != QUOTA_ARC_SCHEMA_VERSION) {
            issue("$.schemaVersion", "must equal $QUOTA_ARC_SCHEMA_VERSION")
        }

        val expectedStale =
            summary.sources.quota.status == SourceStatus.STALE ||
                summary.sources.accountUsage.status == SourceStatus.STALE ||
                summary.sources.localUsage.status == SourceStatus.STALE
        if (summary.stale != expectedStale) {
            issue("$" + ".stale", "must equal the aggregate of stale source statuses")
        }

        validateSource(
            source = summary.sources.quota,
            expectedKind = SourceKind.CODEX_APP_SERVER,
            path = "$.sources.quota",
        )
        validateSource(
            source = summary.sources.accountUsage,
            expectedKind = SourceKind.CODEX_APP_SERVER,
            path = "$.sources.accountUsage",
        )
        validateLocalSource(summary.sources.localUsage)
        validateQuota(summary)
        validateAccountUsage(summary)
        validateLocalUsage(summary.localUsage)
    }

    fun requireValid(summary: QuotaArcSummary): QuotaArcSummary {
        val issues = validate(summary)
        if (issues.isNotEmpty()) throw ContractValidationException(issues)
        return summary
    }

    private fun MutableList<ContractValidationIssue>.validateSource(
        source: SourceState,
        expectedKind: SourceKind,
        path: String,
    ) {
        if (source.kind != expectedKind) issue("$path.kind", "has the wrong provenance")
        validateSourceState(
            status = source.status,
            collectedAtPresent = source.collectedAt != null,
            error = source.error,
            path = path,
        )
    }

    private fun MutableList<ContractValidationIssue>.validateLocalSource(
        source: LocalUsageSourceState,
    ) {
        val path = "$.sources.localUsage"
        if (source.kind != SourceKind.CODEX_SESSION_LOGS) {
            issue("$path.kind", "has the wrong provenance")
        }
        validateSourceState(
            status = source.status,
            collectedAtPresent = source.collectedAt != null,
            error = source.error,
            path = path,
        )
        validateSafeInteger(source.coverage.files, "$path.coverage.files")
        val first = source.coverage.firstEventAt
        val last = source.coverage.lastEventAt
        if (first != null && last != null && first.isAfter(last)) {
            issue("$path.coverage", "firstEventAt must not be after lastEventAt")
        }
    }

    private fun MutableList<ContractValidationIssue>.validateSourceState(
        status: SourceStatus,
        collectedAtPresent: Boolean,
        error: SourceError?,
        path: String,
    ) {
        if ((status == SourceStatus.OK || status == SourceStatus.STALE) && !collectedAtPresent) {
            issue("$path.collectedAt", "is required when status is ${status.name.lowercase()}")
        }
        if (status == SourceStatus.OK && error != null) {
            issue("$path.error", "must be null when status is ok")
        }
        if (status == SourceStatus.UNSUPPORTED && (collectedAtPresent || error != null)) {
            issue(path, "unsupported sources must not claim a collection or error")
        }
        if (status == SourceStatus.ERROR && error == null) {
            issue("$path.error", "is required when status is error")
        }
        if (error != null) validateSourceError(error, "$path.error")
    }

    private fun MutableList<ContractValidationIssue>.validateSourceError(
        error: SourceError,
        path: String,
    ) {
        if (error.code.isEmpty() || error.code.length > 80 || !errorCodePattern.matches(error.code)) {
            issue("$path.code", "must be a normalized lowercase code of at most 80 characters")
        }
        validateSafeClientString(error.message, "$path.message", 320)
    }

    private fun MutableList<ContractValidationIssue>.validateQuota(summary: QuotaArcSummary) {
        val ids = mutableSetOf<String>()
        summary.quota.limits.forEachIndexed { limitIndex, limit ->
            val path = "$.quota.limits[$limitIndex]"
            validateSafeClientString(limit.limitId, "$path.limitId", 160)
            if (!ids.add(limit.limitId)) issue("$path.limitId", "must be unique")
            limit.limitName?.let { validateSafeClientString(it, "$path.limitName", 160) }
            if (limit.windows.isEmpty()) issue("$path.windows", "must be a non-empty array")
            limit.windows.forEachIndexed { windowIndex, window ->
                val windowPath = "$path.windows[$windowIndex]"
                if (window.windowMinutes <= 0 || window.windowMinutes > MAX_SAFE_INTEGER) {
                    issue("$windowPath.windowMinutes", "must be a positive safe integer")
                }
                validatePercent(window.usedPercent, "$windowPath.usedPercent")
                validatePercent(window.remainingPercent, "$windowPath.remainingPercent")
                if (
                    window.usedPercent.isFinite() &&
                    window.remainingPercent.isFinite() &&
                    abs(window.remainingPercent - (100.0 - window.usedPercent).coerceIn(0.0, 100.0)) >
                    1e-6
                ) {
                    issue(
                        "$windowPath.remainingPercent",
                        "must equal clamp(100 - usedPercent, 0, 100)",
                    )
                }
            }
        }
    }

    private fun MutableList<ContractValidationIssue>.validateAccountUsage(
        summary: QuotaArcSummary,
    ) {
        val dates = mutableSetOf<String>()
        var previous: String? = null
        summary.accountUsage.dailyTokens.forEachIndexed { index, bucket ->
            val path = "$.accountUsage.dailyTokens[$index]"
            val date = bucket.date.toString()
            if (!dates.add(date)) issue("$path.date", "must be unique")
            previous?.let { previousDate ->
                if (date <= previousDate) {
                    issue("$path.date", "daily buckets must be strictly ascending")
                }
            }
            previous = date
            validateSafeInteger(bucket.tokens, "$path.tokens")
        }
    }

    private fun MutableList<ContractValidationIssue>.validateLocalUsage(
        usage: LocalUsageSummary,
    ) {
        validateCounts(usage, "$.localUsage")
        val models = validateBreakdowns(usage.models, "$.localUsage.models")
        val projects = validateBreakdowns(usage.projects, "$.localUsage.projects")
        validateBreakdownTotals(models, usage, "$.localUsage.models")
        validateBreakdownTotals(projects, usage, "$.localUsage.projects")
    }

    private fun MutableList<ContractValidationIssue>.validateBreakdowns(
        items: List<UsageBreakdown>,
        path: String,
    ): List<UsageBreakdown> {
        val ids = mutableSetOf<String>()
        items.forEachIndexed { index, item ->
            val itemPath = "$path[$index]"
            validateSafeClientString(item.id, "$itemPath.id", 160)
            if (!ids.add(item.id)) issue("$itemPath.id", "must be unique")
            validateSafeClientString(item.label, "$itemPath.label", 160)
            validateCounts(item, itemPath)
        }
        return items
    }

    private fun MutableList<ContractValidationIssue>.validateBreakdownTotals(
        items: List<UsageBreakdown>,
        total: TokenUsageCounts,
        path: String,
    ) {
        if (items.isEmpty()) return
        val fields = listOf(
            "newInputTokens" to TokenUsageCounts::newInputTokens,
            "cachedInputTokens" to TokenUsageCounts::cachedInputTokens,
            "outputTokens" to TokenUsageCounts::outputTokens,
            "reasoningTokens" to TokenUsageCounts::reasoningTokens,
        )
        for ((name, getter) in fields) {
            val sum = items.fold(BigInteger.ZERO) { current, item ->
                current + BigInteger.valueOf(getter.get(item))
            }
            if (sum != BigInteger.valueOf(getter.get(total))) {
                issue(path, "$name breakdown must sum to the local usage total")
            }
        }
    }

    private fun MutableList<ContractValidationIssue>.validateCounts(
        counts: TokenUsageCounts,
        path: String,
    ) {
        validateSafeInteger(counts.newInputTokens, "$path.newInputTokens")
        validateSafeInteger(counts.cachedInputTokens, "$path.cachedInputTokens")
        validateSafeInteger(counts.outputTokens, "$path.outputTokens")
        validateSafeInteger(counts.reasoningTokens, "$path.reasoningTokens")
    }

    private fun MutableList<ContractValidationIssue>.validateSafeInteger(
        value: Long,
        path: String,
    ) {
        if (value < 0 || value > MAX_SAFE_INTEGER) {
            issue(path, "must be a non-negative safe integer")
        }
    }

    private fun MutableList<ContractValidationIssue>.validatePercent(
        value: Double,
        path: String,
    ) {
        if (!value.isFinite() || value < 0.0 || value > 100.0) {
            issue(path, "must be a finite number between 0 and 100")
        }
    }

    private fun MutableList<ContractValidationIssue>.validateSafeClientString(
        value: String,
        path: String,
        maxLength: Int,
    ) {
        if (value.isEmpty() || value.length > maxLength) {
            issue(path, "must be a non-empty string of at most $maxLength characters")
        } else if (containsUnsafeClientText(value)) {
            issue(path, "must not contain a path or credential")
        }
    }

    private fun MutableList<ContractValidationIssue>.issue(path: String, message: String) {
        add(ContractValidationIssue(path, message))
    }
}
