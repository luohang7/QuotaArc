import type {
  AccountDailyTokenUsage,
  QuotaLimit,
  QuotaWindow,
} from "@quotaarc/contracts";

export interface NormalizedRateLimits {
  limits: QuotaLimit[];
}

export interface NormalizedAccountUsage {
  dailyTokens: AccountDailyTokenUsage[];
}

export function normalizeRateLimits(raw: unknown): NormalizedRateLimits {
  const response = asRecord(raw);
  if (!response) {
    return { limits: [] };
  }

  const byLimitId = asRecord(response.rateLimitsByLimitId);
  const snapshots: Array<[string, Record<string, unknown>]> = [];

  if (byLimitId && Object.keys(byLimitId).length > 0) {
    for (const [key, value] of Object.entries(byLimitId)) {
      const snapshot = asRecord(value);
      if (snapshot) {
        snapshots.push([key, snapshot]);
      }
    }
  }
  let limits = normalizedLimits(snapshots);
  // A present multi-map is preferred only when it yields an actual window.
  // During schema transitions the map can contain object-shaped but unusable
  // entries while the compatibility bucket remains valid.
  if (limits.length === 0) {
    const compatibleSnapshot = asRecord(response.rateLimits);
    if (compatibleSnapshot) {
      limits = normalizedLimits([["default", compatibleSnapshot]]);
    }
  }

  return { limits };
}

function normalizedLimits(
  snapshots: Array<[string, Record<string, unknown>]>,
): QuotaLimit[] {
  const byNormalizedId = new Map<string, QuotaLimit>();
  for (const [fallbackId, snapshot] of snapshots) {
    const limit = normalizeLimit(fallbackId, snapshot);
    if (limit.windows.length > 0) {
      byNormalizedId.set(limit.limitId, limit);
    }
  }
  const limits = [...byNormalizedId.values()];
  limits.sort((left, right) => left.limitId.localeCompare(right.limitId));
  return limits;
}

export function normalizeAccountUsage(raw: unknown): NormalizedAccountUsage {
  const response = asRecord(raw);
  const buckets = Array.isArray(response?.dailyUsageBuckets)
    ? response.dailyUsageBuckets
    : [];
  const byDate = new Map<string, number>();

  for (const value of buckets) {
    const bucket = asRecord(value);
    const date = bucket?.startDate;
    const tokens = bucket?.tokens;
    if (
      typeof date !== "string" ||
      !isIsoDate(date) ||
      !isNonNegativeSafeInteger(tokens)
    ) {
      continue;
    }
    byDate.set(date, tokens);
  }

  return {
    dailyTokens: [...byDate.entries()]
      .sort(([left], [right]) => left.localeCompare(right))
      .map(([date, tokens]) => ({ date, tokens })),
  };
}

function normalizeLimit(
  fallbackId: string,
  snapshot: Record<string, unknown>,
): QuotaLimit {
  const limitId =
    nonEmptyString(snapshot.limitId) ?? nonEmptyString(fallbackId) ?? "default";
  const limitName =
    typeof snapshot.limitName === "string" ? snapshot.limitName : null;
  const windows: QuotaWindow[] = [];

  for (const key of ["primary", "secondary"] as const) {
    const normalized = normalizeWindow(snapshot[key]);
    if (normalized) {
      windows.push(normalized);
    }
  }
  windows.sort((left, right) => left.windowMinutes - right.windowMinutes);

  return {
    limitId,
    limitName,
    windows,
  };
}

function normalizeWindow(raw: unknown): QuotaWindow | null {
  const window = asRecord(raw);
  if (!window) {
    return null;
  }

  const used = window.usedPercent;
  const duration = window.windowDurationMins;
  const resetsAt = unixSecondsToIso(window.resetsAt);
  if (
    typeof used !== "number" ||
    !Number.isFinite(used) ||
    !isPositiveSafeInteger(duration) ||
    resetsAt === null
  ) {
    return null;
  }

  const usedPercent = clampPercent(used);
  return {
    windowMinutes: duration,
    usedPercent,
    remainingPercent: clampPercent(100 - usedPercent),
    resetsAt,
  };
}

function unixSecondsToIso(value: unknown): string | null {
  if (!isNonNegativeSafeInteger(value)) {
    return null;
  }
  try {
    return new Date(value * 1_000).toISOString();
  } catch {
    return null;
  }
}

function clampPercent(value: number): number {
  return Math.min(100, Math.max(0, value));
}

function asRecord(value: unknown): Record<string, unknown> | null {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    return null;
  }
  return value as Record<string, unknown>;
}

function nonEmptyString(value: unknown): string | null {
  return typeof value === "string" && value.trim() !== "" ? value : null;
}

function isPositiveSafeInteger(value: unknown): value is number {
  return isNonNegativeSafeInteger(value) && value > 0;
}

function isNonNegativeSafeInteger(value: unknown): value is number {
  return Number.isSafeInteger(value) && (value as number) >= 0;
}

function isIsoDate(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/u.test(value)) {
    return false;
  }
  const date = new Date(`${value}T00:00:00.000Z`);
  return !Number.isNaN(date.getTime()) &&
    date.toISOString().slice(0, 10) === value;
}
