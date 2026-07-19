import {
  SOURCE_STATUSES,
  type QuotaArcSources,
  type QuotaArcSummary,
  type SourceStatus,
  type TokenUsageCounts
} from "./types.js";
import { containsUnsafeClientText } from "./security.js";

export interface ContractValidationIssue {
  path: string;
  message: string;
}

export class ContractValidationError extends Error {
  readonly issues: ContractValidationIssue[];

  constructor(issues: ContractValidationIssue[]) {
    super(
      `Invalid QuotaArc v1 summary: ${issues
        .map(({ path, message }) => `${path} ${message}`)
        .join("; ")}`
    );
    this.name = "ContractValidationError";
    this.issues = issues;
  }
}

type JsonObject = Record<string, unknown>;

const timestampPattern =
  /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d{1,9})?(?:Z|([+-])(\d{2}):(\d{2}))$/;
const datePattern = /^(\d{4})-(\d{2})-(\d{2})$/;
const sourceStatusSet = new Set<string>(SOURCE_STATUSES);

function isValidCalendarDate(year: number, month: number, day: number): boolean {
  if (month < 1 || month > 12 || day < 1) return false;
  return day <= new Date(Date.UTC(year, month, 0)).getUTCDate();
}

export function isIsoDate(value: unknown): value is string {
  if (typeof value !== "string") return false;
  const match = datePattern.exec(value);
  if (!match) return false;
  return isValidCalendarDate(Number(match[1]), Number(match[2]), Number(match[3]));
}

export function isIsoTimestamp(value: unknown): value is string {
  if (typeof value !== "string") return false;
  const match = timestampPattern.exec(value);
  if (!match) return false;
  const [, year, month, day, hour, minute, second, , offsetHour, offsetMinute] =
    match;
  return (
    isValidCalendarDate(Number(year), Number(month), Number(day)) &&
    Number(hour) <= 23 &&
    Number(minute) <= 59 &&
    Number(second) <= 59 &&
    (offsetHour === undefined || Number(offsetHour) <= 23) &&
    (offsetMinute === undefined || Number(offsetMinute) <= 59)
  );
}

function objectAt(
  value: unknown,
  path: string,
  issues: ContractValidationIssue[]
): JsonObject | undefined {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    issues.push({ path, message: "must be an object" });
    return undefined;
  }
  return value as JsonObject;
}

function exactKeys(
  object: JsonObject,
  expected: readonly string[],
  path: string,
  issues: ContractValidationIssue[]
): void {
  const expectedSet = new Set(expected);
  for (const key of expected) {
    if (!Object.hasOwn(object, key)) {
      issues.push({ path: `${path}.${key}`, message: "is required" });
    }
  }
  for (const key of Object.keys(object)) {
    if (!expectedSet.has(key)) {
      issues.push({ path: `${path}.${key}`, message: "is not allowed in v1" });
    }
  }
}

function stringAt(
  value: unknown,
  path: string,
  issues: ContractValidationIssue[],
  maxLength = 160
): value is string {
  if (
    typeof value !== "string" ||
    value.length === 0 ||
    value.length > maxLength
  ) {
    issues.push({
      path,
      message: `must be a non-empty string of at most ${maxLength} characters`
    });
    return false;
  }
  return true;
}

function safeClientStringAt(
  value: unknown,
  path: string,
  issues: ContractValidationIssue[],
  maxLength = 160
): value is string {
  if (!stringAt(value, path, issues, maxLength)) return false;
  if (containsUnsafeClientText(value)) {
    issues.push({ path, message: "must not contain a path or credential" });
    return false;
  }
  return true;
}

function nonNegativeIntegerAt(
  value: unknown,
  path: string,
  issues: ContractValidationIssue[]
): value is number {
  if (
    typeof value !== "number" ||
    !Number.isSafeInteger(value) ||
    value < 0
  ) {
    issues.push({ path, message: "must be a non-negative safe integer" });
    return false;
  }
  return true;
}

function timestampAt(
  value: unknown,
  path: string,
  issues: ContractValidationIssue[]
): value is string {
  if (!isIsoTimestamp(value)) {
    issues.push({ path, message: "must be an ISO-8601 timestamp with timezone" });
    return false;
  }
  return true;
}

function nullableTimestampAt(
  value: unknown,
  path: string,
  issues: ContractValidationIssue[]
): value is string | null {
  return value === null || timestampAt(value, path, issues);
}

function sourceAt(
  value: unknown,
  path: string,
  expectedKind: "codex_app_server" | "codex_session_logs",
  withCoverage: boolean,
  issues: ContractValidationIssue[]
): JsonObject | undefined {
  const source = objectAt(value, path, issues);
  if (!source) return undefined;
  exactKeys(
    source,
    withCoverage
      ? ["kind", "status", "collectedAt", "error", "coverage"]
      : ["kind", "status", "collectedAt", "error"],
    path,
    issues
  );

  if (source.kind !== expectedKind) {
    issues.push({ path: `${path}.kind`, message: `must equal ${expectedKind}` });
  }
  const status = source.status;
  if (typeof status !== "string" || !sourceStatusSet.has(status)) {
    issues.push({
      path: `${path}.status`,
      message: `must be one of ${SOURCE_STATUSES.join(", ")}`
    });
  }
  nullableTimestampAt(source.collectedAt, `${path}.collectedAt`, issues);

  if (source.error !== null) {
    const error = objectAt(source.error, `${path}.error`, issues);
    if (error) {
      exactKeys(error, ["code", "message", "retryable"], `${path}.error`, issues);
      if (
        stringAt(error.code, `${path}.error.code`, issues, 80) &&
        !/^[a-z0-9][a-z0-9_.-]*$/.test(error.code)
      ) {
        issues.push({
          path: `${path}.error.code`,
          message: "must be a normalized lowercase code"
        });
      }
      safeClientStringAt(error.message, `${path}.error.message`, issues, 320);
      if (typeof error.retryable !== "boolean") {
        issues.push({
          path: `${path}.error.retryable`,
          message: "must be a boolean"
        });
      }
    }
  }

  if ((status === "ok" || status === "stale") && source.collectedAt === null) {
    issues.push({
      path: `${path}.collectedAt`,
      message: `must be present when status is ${status}`
    });
  }
  if (status === "ok" && source.error !== null) {
    issues.push({ path: `${path}.error`, message: "must be null when status is ok" });
  }
  if (
    status === "unsupported" &&
    (source.collectedAt !== null || source.error !== null)
  ) {
    issues.push({
      path,
      message: "unsupported sources must not claim a collection or error"
    });
  }
  if (status === "error" && source.error === null) {
    issues.push({ path: `${path}.error`, message: "is required for error status" });
  }

  if (withCoverage) {
    const coverage = objectAt(source.coverage, `${path}.coverage`, issues);
    if (coverage) {
      exactKeys(
        coverage,
        ["files", "firstEventAt", "lastEventAt"],
        `${path}.coverage`,
        issues
      );
      nonNegativeIntegerAt(
        coverage.files,
        `${path}.coverage.files`,
        issues
      );
      const firstValid = nullableTimestampAt(
        coverage.firstEventAt,
        `${path}.coverage.firstEventAt`,
        issues
      );
      const lastValid = nullableTimestampAt(
        coverage.lastEventAt,
        `${path}.coverage.lastEventAt`,
        issues
      );
      if (
        firstValid &&
        lastValid &&
        typeof coverage.firstEventAt === "string" &&
        typeof coverage.lastEventAt === "string" &&
        Date.parse(coverage.firstEventAt) > Date.parse(coverage.lastEventAt)
      ) {
        issues.push({
          path: `${path}.coverage`,
          message: "firstEventAt must not be after lastEventAt"
        });
      }
    }
  }
  return source;
}

function tokenCountsAt(
  object: JsonObject,
  path: string,
  issues: ContractValidationIssue[]
): void {
  for (const key of [
    "newInputTokens",
    "cachedInputTokens",
    "outputTokens",
    "reasoningTokens"
  ] as const) {
    nonNegativeIntegerAt(object[key], `${path}.${key}`, issues);
  }
}

function breakdownsAt(
  value: unknown,
  path: string,
  issues: ContractValidationIssue[]
): JsonObject[] {
  if (!Array.isArray(value)) {
    issues.push({ path, message: "must be an array" });
    return [];
  }
  const result: JsonObject[] = [];
  const ids = new Set<string>();
  value.forEach((item, index) => {
    const itemPath = `${path}[${index}]`;
    const breakdown = objectAt(item, itemPath, issues);
    if (!breakdown) return;
    exactKeys(
      breakdown,
      [
        "id",
        "label",
        "newInputTokens",
        "cachedInputTokens",
        "outputTokens",
        "reasoningTokens"
      ],
      itemPath,
      issues
    );
    if (safeClientStringAt(breakdown.id, `${itemPath}.id`, issues, 160)) {
      if (ids.has(breakdown.id)) {
        issues.push({ path: `${itemPath}.id`, message: "must be unique" });
      }
      ids.add(breakdown.id);
    }
    safeClientStringAt(breakdown.label, `${itemPath}.label`, issues, 160);
    tokenCountsAt(breakdown, itemPath, issues);
    result.push(breakdown);
  });
  return result;
}

function checkBreakdownTotals(
  items: JsonObject[],
  total: JsonObject,
  path: string,
  issues: ContractValidationIssue[]
): void {
  if (items.length === 0) return;
  for (const key of [
    "newInputTokens",
    "cachedInputTokens",
    "outputTokens",
    "reasoningTokens"
  ] as const satisfies readonly (keyof TokenUsageCounts)[]) {
    if (!Number.isSafeInteger(total[key])) continue;
    const sum = items.reduce(
      (current, item) =>
        current + (Number.isSafeInteger(item[key]) ? BigInt(item[key] as number) : 0n),
      0n
    );
    if (sum !== BigInt(total[key] as number)) {
      issues.push({
        path,
        message: `${key} breakdown must sum to the local usage total`
      });
    }
  }
}

export function deriveGlobalStale(sources: QuotaArcSources): boolean {
  return (
    sources.quota.status === "stale" ||
    sources.accountUsage.status === "stale" ||
    sources.localUsage.status === "stale"
  );
}

export function getQuotaArcSummaryIssues(
  value: unknown
): ContractValidationIssue[] {
  const issues: ContractValidationIssue[] = [];
  const root = objectAt(value, "$", issues);
  if (!root) return issues;
  exactKeys(
    root,
    [
      "schemaVersion",
      "generatedAt",
      "stale",
      "sources",
      "quota",
      "accountUsage",
      "localUsage"
    ],
    "$",
    issues
  );
  if (root.schemaVersion !== 1) {
    issues.push({ path: "$.schemaVersion", message: "must equal 1" });
  }
  timestampAt(root.generatedAt, "$.generatedAt", issues);
  if (typeof root.stale !== "boolean") {
    issues.push({ path: "$.stale", message: "must be a boolean" });
  }

  const sources = objectAt(root.sources, "$.sources", issues);
  if (sources) {
    exactKeys(sources, ["quota", "accountUsage", "localUsage"], "$.sources", issues);
    sourceAt(
      sources.quota,
      "$.sources.quota",
      "codex_app_server",
      false,
      issues
    );
    sourceAt(
      sources.accountUsage,
      "$.sources.accountUsage",
      "codex_app_server",
      false,
      issues
    );
    sourceAt(
      sources.localUsage,
      "$.sources.localUsage",
      "codex_session_logs",
      true,
      issues
    );
    const statuses = [
      (sources.quota as JsonObject | undefined)?.status,
      (sources.accountUsage as JsonObject | undefined)?.status,
      (sources.localUsage as JsonObject | undefined)?.status
    ];
    if (
      statuses.every(
        (status): status is SourceStatus =>
          typeof status === "string" && sourceStatusSet.has(status)
      )
    ) {
      const aggregateStale = statuses.includes("stale");
      if (root.stale !== aggregateStale) {
        issues.push({
          path: "$.stale",
          message: "must equal the aggregate of stale source statuses"
        });
      }
    }
  }

  const quota = objectAt(root.quota, "$.quota", issues);
  if (quota) {
    exactKeys(quota, ["limits"], "$.quota", issues);
    if (!Array.isArray(quota.limits)) {
      issues.push({ path: "$.quota.limits", message: "must be an array" });
    } else {
      const limitIds = new Set<string>();
      quota.limits.forEach((item, limitIndex) => {
        const path = `$.quota.limits[${limitIndex}]`;
        const limit = objectAt(item, path, issues);
        if (!limit) return;
        exactKeys(limit, ["limitId", "limitName", "windows"], path, issues);
        if (
          safeClientStringAt(
            limit.limitId,
            `${path}.limitId`,
            issues,
            160
          )
        ) {
          if (limitIds.has(limit.limitId)) {
            issues.push({ path: `${path}.limitId`, message: "must be unique" });
          }
          limitIds.add(limit.limitId);
        }
        if (
          limit.limitName !== null &&
          !safeClientStringAt(limit.limitName, `${path}.limitName`, issues, 160)
        ) {
          // The helper records the issue.
        }
        if (!Array.isArray(limit.windows) || limit.windows.length === 0) {
          issues.push({
            path: `${path}.windows`,
            message: "must be a non-empty array"
          });
        } else {
          limit.windows.forEach((item, windowIndex) => {
            const windowPath = `${path}.windows[${windowIndex}]`;
            const window = objectAt(item, windowPath, issues);
            if (!window) return;
            exactKeys(
              window,
              [
                "windowMinutes",
                "usedPercent",
                "remainingPercent",
                "resetsAt"
              ],
              windowPath,
              issues
            );
            if (
              nonNegativeIntegerAt(
                window.windowMinutes,
                `${windowPath}.windowMinutes`,
                issues
              ) &&
              window.windowMinutes === 0
            ) {
              issues.push({
                path: `${windowPath}.windowMinutes`,
                message: "must be greater than zero"
              });
            }
            for (const key of ["usedPercent", "remainingPercent"] as const) {
              const percent = window[key];
              if (
                typeof percent !== "number" ||
                !Number.isFinite(percent) ||
                percent < 0 ||
                percent > 100
              ) {
                issues.push({
                  path: `${windowPath}.${key}`,
                  message: "must be a number between 0 and 100"
                });
              }
            }
            if (
              typeof window.usedPercent === "number" &&
              typeof window.remainingPercent === "number" &&
              Number.isFinite(window.usedPercent) &&
              Number.isFinite(window.remainingPercent) &&
              Math.abs(
                Math.max(0, Math.min(100, 100 - window.usedPercent)) -
                  window.remainingPercent
              ) > 1e-6
            ) {
              issues.push({
                path: `${windowPath}.remainingPercent`,
                message: "must equal clamp(100 - usedPercent, 0, 100)"
              });
            }
            timestampAt(window.resetsAt, `${windowPath}.resetsAt`, issues);
          });
        }
      });
    }
  }

  const accountUsage = objectAt(root.accountUsage, "$.accountUsage", issues);
  if (accountUsage) {
    exactKeys(accountUsage, ["dailyTokens"], "$.accountUsage", issues);
    if (!Array.isArray(accountUsage.dailyTokens)) {
      issues.push({
        path: "$.accountUsage.dailyTokens",
        message: "must be an array"
      });
    } else {
      let previousDate: string | undefined;
      const dates = new Set<string>();
      accountUsage.dailyTokens.forEach((item, index) => {
        const path = `$.accountUsage.dailyTokens[${index}]`;
        const bucket = objectAt(item, path, issues);
        if (!bucket) return;
        exactKeys(bucket, ["date", "tokens"], path, issues);
        if (!isIsoDate(bucket.date)) {
          issues.push({ path: `${path}.date`, message: "must be an ISO date" });
        } else {
          if (dates.has(bucket.date)) {
            issues.push({ path: `${path}.date`, message: "must be unique" });
          }
          if (previousDate !== undefined && bucket.date <= previousDate) {
            issues.push({
              path: `${path}.date`,
              message: "daily buckets must be strictly ascending"
            });
          }
          dates.add(bucket.date);
          previousDate = bucket.date;
        }
        nonNegativeIntegerAt(bucket.tokens, `${path}.tokens`, issues);
      });
    }
  }

  const localUsage = objectAt(root.localUsage, "$.localUsage", issues);
  if (localUsage) {
    exactKeys(
      localUsage,
      [
        "period",
        "newInputTokens",
        "cachedInputTokens",
        "outputTokens",
        "reasoningTokens",
        "models",
        "projects"
      ],
      "$.localUsage",
      issues
    );
    if (
      localUsage.period !== "today" &&
      localUsage.period !== "week" &&
      localUsage.period !== "month"
    ) {
      issues.push({
        path: "$.localUsage.period",
        message: "must be today, week, or month"
      });
    }
    tokenCountsAt(localUsage, "$.localUsage", issues);
    const models = breakdownsAt(localUsage.models, "$.localUsage.models", issues);
    const projects = breakdownsAt(
      localUsage.projects,
      "$.localUsage.projects",
      issues
    );
    checkBreakdownTotals(models, localUsage, "$.localUsage.models", issues);
    checkBreakdownTotals(projects, localUsage, "$.localUsage.projects", issues);
  }
  return issues;
}

export function validateQuotaArcSummary(
  value: unknown
): value is QuotaArcSummary {
  return getQuotaArcSummaryIssues(value).length === 0;
}

export function assertQuotaArcSummary(
  value: unknown
): asserts value is QuotaArcSummary {
  const issues = getQuotaArcSummaryIssues(value);
  if (issues.length > 0) throw new ContractValidationError(issues);
}

export function parseQuotaArcSummary(value: unknown): QuotaArcSummary {
  assertQuotaArcSummary(value);
  return value;
}
