import {
  mergeSnapshot,
  resolveSnapshotSource,
  type MergeSnapshotOptions,
} from "./merge.js";
import {
  EMPTY_TOTALS,
  type AccountUsageData,
  type LocalUsageData,
  type QuotaArcSnapshot,
  type QuotaData,
  type SourceResult,
  type UsageQuery,
  type UsageQueryResult,
} from "./model.js";

export interface CollectorPorts {
  readQuota(): Promise<SourceResult<QuotaData>>;
  readAccountUsage(): Promise<SourceResult<AccountUsageData>>;
  readLocalUsage(period: UsageQuery["period"]): Promise<SourceResult<LocalUsageData>>;
}

export async function collectSnapshot(
  ports: CollectorPorts,
  options: MergeSnapshotOptions = {},
): Promise<QuotaArcSnapshot> {
  const [quota, accountUsage, localUsage] = await Promise.all([
    safelyRead(() => ports.readQuota()),
    safelyRead(() => ports.readAccountUsage()),
    safelyRead(() => ports.readLocalUsage("today")),
  ]);
  return mergeSnapshot({ quota, accountUsage, localUsage }, options);
}

export async function queryUsage(
  ports: CollectorPorts,
  query: UsageQuery,
  options: MergeSnapshotOptions = {},
): Promise<UsageQueryResult> {
  const localUsage = await safelyRead(() => ports.readLocalUsage(query.period));
  const now = options.now ?? new Date();
  const snapshot = mergeSnapshot(
    {
      quota: { status: "unavailable" },
      accountUsage: { status: "unavailable" },
      localUsage,
    },
    normalizedOptions(options, now),
  );
  const resolvedLocalUsage = resolveSnapshotSource(
    localUsage,
    {
      ...EMPTY_TOTALS,
      period: query.period,
      models: [],
      projects: [],
      days: [],
      coverage: {
        files: 0,
        firstEventAt: null,
        lastEventAt: null,
      },
    },
    "codex_session_logs",
    now,
    options.staleAfterMs,
  );
  const groups = query.groupBy === "model"
    ? snapshot.localUsage.models
    : query.groupBy === "project"
      ? snapshot.localUsage.projects
      : resolvedLocalUsage.value.days;

  return {
    schemaVersion: 1,
    generatedAt: snapshot.generatedAt,
    period: query.period,
    groupBy: query.groupBy,
    stale: snapshot.sources.localUsage.status === "stale",
    source: snapshot.sources.localUsage,
    totals: {
      ...EMPTY_TOTALS,
      newInputTokens: snapshot.localUsage.newInputTokens,
      cachedInputTokens: snapshot.localUsage.cachedInputTokens,
      outputTokens: snapshot.localUsage.outputTokens,
      reasoningTokens: snapshot.localUsage.reasoningTokens,
    },
    groups,
  };
}

function normalizedOptions(
  options: MergeSnapshotOptions,
  now: Date,
): MergeSnapshotOptions {
  return options.staleAfterMs === undefined
    ? { now }
    : { now, staleAfterMs: options.staleAfterMs };
}

async function safelyRead<T>(
  operation: () => Promise<SourceResult<T>>,
): Promise<SourceResult<T>> {
  try {
    return await operation();
  } catch {
    return { status: "error", code: "source_exception" };
  }
}

export function unavailableCollectorPorts(): CollectorPorts {
  const unavailable = async <T>(): Promise<SourceResult<T>> => ({
    status: "unavailable",
    code: "adapter_not_configured",
  });
  return {
    readQuota: unavailable,
    readAccountUsage: unavailable,
    readLocalUsage: unavailable,
  };
}
