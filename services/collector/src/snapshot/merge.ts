import {
  assertQuotaArcSummary,
  deriveGlobalStale,
  type LocalUsageCoverage,
  type QuotaArcSummary,
  type SourceError,
  type SourceState,
} from "@quotaarc/contracts";
import {
  emptyLocalUsage,
  type AccountUsageData,
  type LocalUsageData,
  type QuotaData,
  type SourceResult,
} from "./model.js";

export interface SnapshotInputs {
  quota: SourceResult<QuotaData>;
  accountUsage: SourceResult<AccountUsageData>;
  localUsage: SourceResult<LocalUsageData>;
}

export interface MergeSnapshotOptions {
  now?: Date;
  staleAfterMs?: number;
}

export interface ResolvedSnapshotSource<T> {
  value: T;
  descriptor: SourceState;
}

export function mergeSnapshot(
  inputs: SnapshotInputs,
  options: MergeSnapshotOptions = {},
): QuotaArcSummary {
  const now = options.now ?? new Date();
  const staleAfterMs = options.staleAfterMs;
  const quota = resolveSnapshotSource(
    inputs.quota,
    { limits: [] },
    "codex_app_server",
    now,
    staleAfterMs,
  );
  const accountUsage = resolveSnapshotSource(
    inputs.accountUsage,
    { dailyTokens: [] },
    "codex_app_server",
    now,
    staleAfterMs,
  );
  const localUsage = resolveSnapshotSource(
    inputs.localUsage,
    emptyLocalUsage(localUsagePeriod(inputs.localUsage)),
    "codex_session_logs",
    now,
    staleAfterMs,
  );
  const localCoverage = localUsage.value.coverage ?? emptyCoverage();
  const sources: QuotaArcSummary["sources"] = {
    quota: {
      ...quota.descriptor,
      kind: "codex_app_server",
    },
    accountUsage: {
      ...accountUsage.descriptor,
      kind: "codex_app_server",
    },
    localUsage: {
      ...localUsage.descriptor,
      kind: "codex_session_logs",
      coverage: localCoverage,
    },
  };
  const snapshot: QuotaArcSummary = {
    schemaVersion: 1,
    generatedAt: now.toISOString(),
    stale: deriveGlobalStale(sources),
    sources,
    quota: quota.value,
    accountUsage: accountUsage.value,
    localUsage: {
      period: localUsage.value.period,
      newInputTokens: localUsage.value.newInputTokens,
      cachedInputTokens: localUsage.value.cachedInputTokens,
      outputTokens: localUsage.value.outputTokens,
      reasoningTokens: localUsage.value.reasoningTokens,
      models: localUsage.value.models,
      projects: localUsage.value.projects,
    },
  };
  assertQuotaArcSummary(snapshot);
  return snapshot;
}

export function resolveSnapshotSource<T>(
  source: SourceResult<T>,
  empty: T,
  _kind: SourceState["kind"],
  now: Date,
  staleAfterMs?: number,
): ResolvedSnapshotSource<T> {
  const hasCurrentValue =
    (source.status === "ok" || source.status === "stale") &&
    source.value !== undefined &&
    source.collectedAt !== undefined;
  const fallback = !hasCurrentValue ? source.lastGood : undefined;
  const collectedAt = hasCurrentValue
    ? source.collectedAt ?? null
    : fallback?.collectedAt ?? source.collectedAt ?? null;
  const expired = collectedAt !== null &&
    staleAfterMs !== undefined &&
    Number.isFinite(Date.parse(collectedAt)) &&
    now.getTime() - Date.parse(collectedAt) > staleAfterMs;
  const status: SourceState["status"] = fallback || expired || source.status === "stale"
    ? "stale"
    : hasCurrentValue
      ? "ok"
      : source.status === "ok"
        ? "error"
        : source.status;
  const descriptor: SourceState = {
    kind: _kind,
    status,
    collectedAt,
    error: sourceError(status, source.code),
  };

  return {
    value: hasCurrentValue ? source.value as T : fallback?.value ?? empty,
    descriptor,
  };
}

function localUsagePeriod(source: SourceResult<LocalUsageData>): LocalUsageData["period"] {
  if (
    (source.status === "ok" || source.status === "stale") &&
    source.value !== undefined
  ) {
    return source.value.period;
  }
  return source.lastGood?.value.period ?? "today";
}

function sourceError(
  status: SourceState["status"],
  inputCode?: string,
): SourceError | null {
  if (status !== "error" && !(status === "stale" && inputCode)) return null;
  const code = normalizeErrorCode(inputCode ?? "source.invalid_result");
  return {
    code,
    message: "The source could not be refreshed.",
    retryable: true,
  };
}

function normalizeErrorCode(code: string): string {
  const normalized = code
    .toLowerCase()
    .replace(/[^a-z0-9_.-]+/g, "_")
    .replace(/^[^a-z0-9]+/, "")
    .slice(0, 80);
  return normalized || "source.error";
}

function emptyCoverage(): LocalUsageCoverage {
  return {
    files: 0,
    firstEventAt: null,
    lastEventAt: null,
  };
}
