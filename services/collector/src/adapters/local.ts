import { createHash } from "node:crypto";
import { containsUnsafeClientText } from "@quotaarc/contracts";
import {
  scanSessionFiles,
  type ScanSessionFilesOptions,
  type ScannedTokenEvent,
  type SessionFileScanResult,
} from "../session-indexer/index.js";
import type {
  LocalUsageData,
  Period,
  SourceResult,
  TokenTotals,
  UsageGroup,
} from "../snapshot/index.js";

export interface LocalUsageReader {
  read(period: Period): Promise<SourceResult<LocalUsageData>>;
  close?(): Promise<void>;
}

export type SessionScan = (
  options: ScanSessionFilesOptions,
) => Promise<SessionFileScanResult>;

export interface SessionLocalUsageReaderOptions {
  roots: readonly string[];
  now?: () => Date;
  scan?: SessionScan;
}

interface ScanEnvelope {
  result: SessionFileScanResult | null;
  collectedAt: Date;
}

export function createSessionLocalUsageReader(
  options: SessionLocalUsageReaderOptions,
): LocalUsageReader {
  const roots = [...options.roots];
  const now = options.now ?? (() => new Date());
  const scan = options.scan ?? scanSessionFiles;
  let scanPromise: Promise<ScanEnvelope> | null = null;

  const scanOnce = (): Promise<ScanEnvelope> => {
    scanPromise ??= scan({ roots })
      .then((result) => ({ result, collectedAt: now() }))
      .catch(() => ({ result: null, collectedAt: now() }));
    return scanPromise;
  };

  return {
    async read(period) {
      const envelope = await scanOnce();
      if (!envelope.result) {
        return failedResult(envelope.collectedAt);
      }
      const scanResult = envelope.result;
      if (scanResult.rootsAvailable === 0) {
        return {
          status: "unavailable",
          collectedAt: envelope.collectedAt.toISOString(),
          code: "session_roots_unavailable",
        };
      }
      if (scanResult.diagnosticsCount > 0 && scanResult.tokenEvents.length === 0) {
        return failedResult(envelope.collectedAt);
      }

      let value: LocalUsageData;
      try {
        value = aggregatePeriod(scanResult, period, envelope.collectedAt);
      } catch {
        return failedResult(envelope.collectedAt);
      }
      return scanResult.diagnosticsCount > 0
        ? {
            status: "stale",
            collectedAt: envelope.collectedAt.toISOString(),
            value,
            code: "session_scan_incomplete",
          }
        : {
            status: "ok",
            collectedAt: envelope.collectedAt.toISOString(),
            value,
          };
    },
  };
}

function aggregatePeriod(
  scan: SessionFileScanResult,
  period: Period,
  now: Date,
): LocalUsageData {
  const start = periodStart(period, now).getTime();
  const end = now.getTime();
  const totals = zeroTotals();
  const models = new Map<string, UsageGroup>();
  const projects = new Map<string, UsageGroup>();
  const days = new Map<string, UsageGroup>();

  for (const event of scan.tokenEvents) {
    const timestamp = event.timestamp === null
      ? Number.NaN
      : Date.parse(event.timestamp);
    if (!Number.isFinite(timestamp) || timestamp < start || timestamp > end) {
      continue;
    }
    const usage = usageFromEvent(event);
    addTotals(totals, usage);

    const modelLabel = modelPublicLabel(event.provider, event.model);
    const modelId = modelIdentity(event.provider, event.model);
    addToGroup(models, modelId, modelLabel, usage);

    const projectId = event.project
      ? safeProjectId(event.project.projectKey)
      : "unknown";
    const projectLabel = event.project
      ? sanitizePublicLabel(event.project.safeBasename, "Unknown")
      : "Unknown";
    addToGroup(projects, projectId, projectLabel, usage);

    const day = localDateId(new Date(timestamp));
    addToGroup(days, day, day, usage);
  }

  return {
    period,
    ...totals,
    models: sortedGroups(models),
    projects: sortedGroups(projects),
    days: sortedGroups(days),
    coverage: {
      files: scan.filesCount,
      firstEventAt: normalizeTimestamp(scan.firstEventAt),
      lastEventAt: normalizeTimestamp(scan.lastEventAt),
    },
  };
}

function periodStart(period: Period, now: Date): Date {
  if (!Number.isFinite(now.getTime())) {
    throw new TypeError("invalid_clock");
  }
  const start = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  if (period === "week") {
    const daysSinceMonday = (start.getDay() + 6) % 7;
    start.setDate(start.getDate() - daysSinceMonday);
  } else if (period === "month") {
    start.setDate(1);
  }
  return start;
}

function usageFromEvent(event: ScannedTokenEvent): TokenTotals {
  const input = counter(event.delta.inputTokens);
  const cached = counter(event.delta.cachedInputTokens);
  return {
    newInputTokens: Math.max(input - cached, 0),
    cachedInputTokens: cached,
    outputTokens: counter(event.delta.outputTokens),
    reasoningTokens: counter(event.delta.reasoningOutputTokens),
  };
}

function counter(value: number | undefined): number {
  if (value === undefined) return 0;
  if (!Number.isSafeInteger(value) || value < 0) {
    throw new TypeError("invalid_token_delta");
  }
  return value;
}

function addToGroup(
  groups: Map<string, UsageGroup>,
  id: string,
  label: string,
  usage: TokenTotals,
): void {
  const group = groups.get(id) ?? {
    id,
    label,
    ...zeroTotals(),
  };
  addTotals(group, usage);
  groups.set(id, group);
}

function addTotals(target: TokenTotals, value: TokenTotals): void {
  for (const key of [
    "newInputTokens",
    "cachedInputTokens",
    "outputTokens",
    "reasoningTokens",
  ] as const) {
    const sum = target[key] + value[key];
    if (!Number.isSafeInteger(sum)) throw new RangeError("token_total_overflow");
    target[key] = sum;
  }
}

function zeroTotals(): TokenTotals {
  return {
    newInputTokens: 0,
    cachedInputTokens: 0,
    outputTokens: 0,
    reasoningTokens: 0,
  };
}

function sortedGroups(groups: Map<string, UsageGroup>): UsageGroup[] {
  return [...groups.values()].sort((left, right) =>
    left.label.localeCompare(right.label) || left.id.localeCompare(right.id)
  );
}

function modelIdentity(provider: string, model: string | null): string {
  const providerHash = digest(provider || "unknown").slice(0, 12);
  const modelHash = digest(model || "unknown").slice(0, 16);
  return `model-${providerHash}-${modelHash}`;
}

function safeProjectId(projectKey: string): string {
  return `project-${digest(projectKey).slice(0, 32)}`;
}

function digest(value: string): string {
  return createHash("sha256").update(value).digest("hex");
}

function sanitizePublicLabel(
  value: string | null,
  fallback: string,
): string {
  if (value === null) return fallback;
  const cleaned = value
    .replace(/[\u0000-\u001f\u007f]/gu, "")
    .trim()
    .slice(0, 160);
  if (cleaned.length === 0 || containsUnsafeClientText(cleaned)) {
    return fallback;
  }
  return cleaned;
}

function modelPublicLabel(provider: string, model: string | null): string {
  const modelLabel = sanitizePublicLabel(model, "Unknown");
  if (provider.trim().toLowerCase() === "openai") return modelLabel;
  const providerLabel = sanitizePublicLabel(provider, "Unknown provider").slice(
    0,
    64,
  );
  const suffix = ` (${providerLabel})`;
  return `${modelLabel.slice(0, 160 - suffix.length)}${suffix}`;
}

function localDateId(date: Date): string {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

function normalizeTimestamp(value: string | null): string | null {
  if (value === null) return null;
  const epoch = Date.parse(value);
  return Number.isFinite(epoch) ? new Date(epoch).toISOString() : null;
}

function failedResult(collectedAt: Date): SourceResult<LocalUsageData> {
  return {
    status: "error",
    collectedAt: collectedAt.toISOString(),
    code: "session_scan_failed",
  };
}
