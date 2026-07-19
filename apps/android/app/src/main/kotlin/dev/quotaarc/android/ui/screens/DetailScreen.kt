package dev.quotaarc.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.quotaarc.android.R
import dev.quotaarc.android.ui.model.DetailUiModel
import dev.quotaarc.android.ui.model.LimitUi
import dev.quotaarc.android.ui.model.PhoneCacheUiState
import dev.quotaarc.android.ui.model.RefreshUi
import dev.quotaarc.android.ui.model.SourceDiagnosticUi
import dev.quotaarc.android.ui.model.SourceUiKind
import dev.quotaarc.android.ui.model.SourceUiStatus
import dev.quotaarc.android.ui.model.UsageBreakdownUi

@Composable
internal fun DetailScreen(
    model: DetailUiModel,
    refresh: RefreshUi,
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 20.dp,
            vertical = 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionHeading(
                text = stringResource(R.string.details_title),
                headline = true,
            )
        }

        item { RefreshCard(refresh) }

        item {
            SnapshotCard(model)
        }

        item { SectionHeading(stringResource(R.string.quota_section)) }
        if (model.limits.isEmpty()) {
            item { EmptyText(stringResource(R.string.quota_empty)) }
        } else {
            items(model.limits, key = { it.id }) { limit ->
                LimitCard(limit)
            }
        }

        item { SectionHeading(stringResource(R.string.official_daily_section)) }
        if (model.officialDaily.isEmpty()) {
            item { EmptyText(stringResource(R.string.official_daily_empty)) }
        } else {
            items(
                items = model.officialDaily,
                key = { "${it.dateText}:${it.tokensText}" },
            ) { daily ->
                Card {
                    Text(
                        text = stringResource(
                            R.string.daily_tokens,
                            daily.dateText,
                            daily.tokensText,
                        ),
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }

        item { SectionHeading(stringResource(R.string.local_models_section)) }
        if (model.localModels.isEmpty()) {
            item { EmptyText(stringResource(R.string.local_empty)) }
        } else {
            items(model.localModels, key = { "model:${it.id}" }) { item ->
                BreakdownCard(item)
            }
        }

        item { SectionHeading(stringResource(R.string.local_projects_section)) }
        if (model.localProjects.isEmpty()) {
            item { EmptyText(stringResource(R.string.local_empty)) }
        } else {
            items(model.localProjects, key = { "project:${it.id}" }) { item ->
                BreakdownCard(item)
            }
        }

        item { SectionHeading(stringResource(R.string.sources_section)) }
        items(model.sources, key = { it.kind.name }) { source ->
            SourceCard(source)
        }
    }
}

@Composable
private fun RefreshCard(refresh: RefreshUi) {
    val (message, isError) = when (refresh) {
        RefreshUi.Idle -> stringResource(R.string.refresh_idle) to false
        RefreshUi.Running -> stringResource(R.string.refresh_in_progress) to false
        RefreshUi.Success -> stringResource(R.string.refresh_success) to false
        RefreshUi.StaleFallback -> stringResource(R.string.refresh_stale) to true
        RefreshUi.GateClosed -> stringResource(R.string.refresh_gate_closed) to true
        is RefreshUi.Failed ->
            stringResource(R.string.refresh_failed, refresh.safeMessage) to true
    }
    Card(
        colors = if (isError) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        } else {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        },
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun SnapshotCard(model: DetailUiModel) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (!model.hasSnapshot || model.generatedAtText == null) {
                Text(stringResource(R.string.snapshot_absent))
            } else {
                Text(
                    text = if (model.stale) {
                        stringResource(R.string.stale_snapshot)
                    } else {
                        stringResource(R.string.current_snapshot)
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.updated_at, model.generatedAtText),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(model.phoneCacheState.labelResource()),
                    color = if (
                        model.phoneCacheState == PhoneCacheUiState.CURRENT
                    ) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        }
    }
}

@Composable
private fun LimitCard(limit: LimitUi) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = limit.name ?: stringResource(R.string.limit_unnamed),
                style = MaterialTheme.typography.titleMedium,
            )
            limit.windows.forEach { window ->
                val usedText = stringResource(R.string.used_percent, window.usedPercent)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = pluralStringResource(
                                R.plurals.window_minutes,
                                pluralCount(window.windowMinutes),
                                window.windowMinutes,
                            ),
                        )
                        Text(
                            text = stringResource(
                                R.string.remaining_percent,
                                window.remainingPercent,
                            ),
                        )
                    }
                    LinearProgressIndicator(
                        progress = { (window.usedPercent / 100.0).toFloat() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                stateDescription = usedText
                                progressBarRangeInfo = ProgressBarRangeInfo(
                                    current = window.usedPercent.toFloat(),
                                    range = 0f..100f,
                                )
                            },
                    )
                    Text(
                        text = stringResource(R.string.resets_at, window.resetText),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun BreakdownCard(item: UsageBreakdownUi) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = item.label,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(
                    R.string.token_breakdown,
                    item.processedTokensText,
                    item.newInputTokensText,
                    item.cachedInputTokensText,
                    item.outputTokensText,
                    item.reasoningTokensText,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SourceCard(source: SourceDiagnosticUi) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(source.kind.labelResource()),
                    style = MaterialTheme.typography.titleMedium,
                )
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        text = stringResource(source.status.labelResource()),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            Text(
                text = source.collectedAtText?.let {
                    stringResource(R.string.source_collected_at, it)
                } ?: stringResource(R.string.source_not_collected),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val errorCode = source.errorCode
            val errorMessage = source.safeErrorMessage
            if (errorCode != null && errorMessage != null) {
                Text(
                    text = stringResource(R.string.source_error, errorCode, errorMessage),
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (errorCode != null) {
                Text(
                    text = stringResource(R.string.source_error_code, errorCode),
                    color = MaterialTheme.colorScheme.error,
                )
            }

            source.coverage?.let { coverage ->
                val first = coverage.firstEventAtText
                val last = coverage.lastEventAtText
                Text(
                    text = if (first != null && last != null) {
                        pluralStringResource(
                            R.plurals.source_coverage,
                            pluralCount(coverage.files),
                            coverage.files,
                            first,
                            last,
                        )
                    } else {
                        pluralStringResource(
                            R.plurals.source_coverage_files,
                            pluralCount(coverage.files),
                            coverage.files,
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SectionHeading(
    text: String,
    headline: Boolean = false,
) {
    Text(
        text = text,
        style = if (headline) {
            MaterialTheme.typography.headlineSmall
        } else {
            MaterialTheme.typography.titleLarge
        },
        modifier = Modifier
            .padding(top = if (headline) 0.dp else 12.dp)
            .semantics { heading() },
    )
}

@Composable
private fun EmptyText(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

private fun SourceUiKind.labelResource(): Int = when (this) {
    SourceUiKind.QUOTA -> R.string.source_quota
    SourceUiKind.ACCOUNT_USAGE -> R.string.source_account_usage
    SourceUiKind.LOCAL_USAGE -> R.string.source_local_usage
}

private fun SourceUiStatus.labelResource(): Int = when (this) {
    SourceUiStatus.OK -> R.string.status_ok
    SourceUiStatus.STALE -> R.string.status_stale
    SourceUiStatus.UNAVAILABLE -> R.string.status_unavailable
    SourceUiStatus.UNSUPPORTED -> R.string.status_unsupported
    SourceUiStatus.ERROR -> R.string.status_error
}

private fun PhoneCacheUiState.labelResource(): Int = when (this) {
    PhoneCacheUiState.NO_SNAPSHOT -> R.string.phone_cache_absent
    PhoneCacheUiState.CURRENT -> R.string.phone_cache_current
    PhoneCacheUiState.AGED -> R.string.phone_cache_aged
    PhoneCacheUiState.FALLBACK -> R.string.phone_cache_fallback
    PhoneCacheUiState.STALE_FALLBACK -> R.string.phone_cache_stale_fallback
}

private fun pluralCount(value: Long): Int =
    value.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
