import type {
  LocalUsageCoverage,
  LocalUsagePeriod,
  LocalUsageSummary,
  QuotaArcSources,
  QuotaArcSummary,
  SourceStatus,
  TokenUsageCounts,
  UsageBreakdown,
} from "@quotaarc/contracts";

// Keep Collector ports structural while the concrete adapters are developed.
// The public snapshot itself is always the shared v1 contract.
export type Period = LocalUsagePeriod;
export type GroupBy = "model" | "project" | "day";
export type TokenTotals = TokenUsageCounts;
export type UsageGroup = UsageBreakdown;
export type QuotaData = QuotaArcSummary["quota"];
export type AccountUsageData = QuotaArcSummary["accountUsage"];

export interface LocalUsageData extends LocalUsageSummary {
  days: UsageGroup[];
  coverage?: LocalUsageCoverage;
}

export interface LastGoodValue<T> {
  collectedAt: string;
  value: T;
}

export interface SourceResult<T> {
  status: SourceStatus;
  collectedAt?: string;
  value?: T;
  lastGood?: LastGoodValue<T>;
  code?: string;
}

export interface SnapshotSourceStatus {
  kind: QuotaArcSources["localUsage"]["kind"];
  status: SourceStatus;
  collectedAt: string | null;
  error: QuotaArcSources["localUsage"]["error"];
  coverage: LocalUsageCoverage;
}

export type QuotaArcSnapshot = QuotaArcSummary;

export interface UsageQuery {
  period: Period;
  groupBy: GroupBy;
}

export interface UsageQueryResult {
  schemaVersion: 1;
  generatedAt: string;
  period: Period;
  groupBy: GroupBy;
  stale: boolean;
  source: QuotaArcSources["localUsage"];
  totals: TokenTotals;
  groups: UsageGroup[];
}

export const EMPTY_TOTALS: TokenTotals = {
  newInputTokens: 0,
  cachedInputTokens: 0,
  outputTokens: 0,
  reasoningTokens: 0,
};

export function emptyLocalUsage(period: Period): LocalUsageData {
  return {
    period,
    ...EMPTY_TOTALS,
    models: [],
    projects: [],
    days: [],
    coverage: {
      files: 0,
      firstEventAt: null,
      lastEventAt: null,
    },
  };
}
