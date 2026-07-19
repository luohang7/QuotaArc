export const SOURCE_STATUSES = [
  "ok",
  "stale",
  "unavailable",
  "unsupported",
  "error"
] as const;

export type SourceStatus = (typeof SOURCE_STATUSES)[number];
export type IsoTimestamp = string;
export type IsoDate = string;
export type LocalUsagePeriod = "today" | "week" | "month";

export interface SourceError {
  code: string;
  message: string;
  retryable: boolean;
}

export interface SourceState {
  kind: "codex_app_server" | "codex_session_logs";
  status: SourceStatus;
  collectedAt: IsoTimestamp | null;
  error: SourceError | null;
}

export interface LocalUsageCoverage {
  files: number;
  firstEventAt: IsoTimestamp | null;
  lastEventAt: IsoTimestamp | null;
}

export interface QuotaArcSources {
  quota: SourceState & { kind: "codex_app_server" };
  accountUsage: SourceState & { kind: "codex_app_server" };
  localUsage: SourceState & {
    kind: "codex_session_logs";
    coverage: LocalUsageCoverage;
  };
}

export interface QuotaWindow {
  windowMinutes: number;
  usedPercent: number;
  remainingPercent: number;
  resetsAt: IsoTimestamp;
}

export interface QuotaLimit {
  limitId: string;
  limitName: string | null;
  windows: QuotaWindow[];
}

export interface AccountDailyTokenUsage {
  date: IsoDate;
  tokens: number;
}

export interface TokenUsageCounts {
  newInputTokens: number;
  cachedInputTokens: number;
  outputTokens: number;
  reasoningTokens: number;
}

export interface UsageBreakdown extends TokenUsageCounts {
  id: string;
  label: string;
}

export interface LocalUsageSummary extends TokenUsageCounts {
  period: LocalUsagePeriod;
  models: UsageBreakdown[];
  projects: UsageBreakdown[];
}

export interface QuotaArcSummary {
  schemaVersion: 1;
  generatedAt: IsoTimestamp;
  /**
   * Aggregate freshness flag. It is true exactly when at least one source has
   * status "stale"; unavailable/unsupported/error remain visible separately.
   */
  stale: boolean;
  sources: QuotaArcSources;
  quota: {
    limits: QuotaLimit[];
  };
  accountUsage: {
    dailyTokens: AccountDailyTokenUsage[];
  };
  localUsage: LocalUsageSummary;
}
