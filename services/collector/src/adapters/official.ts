import type { OfficialAccountRead } from "../codex-rpc/client.js";
import {
  normalizeAccountUsage,
  normalizeRateLimits,
} from "../codex-rpc/normalize.js";
import type {
  AccountUsageData,
  QuotaData,
  SourceResult,
} from "../snapshot/index.js";

export interface OfficialAccountClientPort {
  readOfficialAccount(): Promise<OfficialAccountRead>;
  stop(): Promise<void>;
}

export interface OfficialSourcePort {
  readQuota(): Promise<SourceResult<QuotaData>>;
  readAccountUsage(): Promise<SourceResult<AccountUsageData>>;
  close(): Promise<void>;
}

type OfficialReadEnvelope =
  | {
      ok: true;
      collectedAt: string;
      value: OfficialAccountRead;
    }
  | {
      ok: false;
      collectedAt: string;
    };

/**
 * Shares exactly one account read between quota and account-usage consumers.
 * A later refresh cycle should create a new adapter instance.
 */
export class OfficialAccountAdapter implements OfficialSourcePort {
  readonly #client: OfficialAccountClientPort;
  readonly #now: () => Date;
  #readPromise: Promise<OfficialReadEnvelope> | null = null;
  #closed = false;

  constructor(
    client: OfficialAccountClientPort,
    options: { now?: () => Date } = {},
  ) {
    this.#client = client;
    this.#now = options.now ?? (() => new Date());
  }

  async readQuota(): Promise<SourceResult<QuotaData>> {
    const envelope = await this.#readOnce();
    if (!envelope.ok) {
      return {
        status: "error",
        collectedAt: envelope.collectedAt,
        code: "official_account_read_failed",
      };
    }
    const normalized = normalizeRateLimits(envelope.value.rateLimits);
    if (normalized.limits.length === 0) {
      return {
        status: "error",
        collectedAt: envelope.collectedAt,
        code: "rate_limits_invalid",
      };
    }
    return {
      status: "ok",
      collectedAt: envelope.collectedAt,
      value: normalized,
    };
  }

  async readAccountUsage(): Promise<SourceResult<AccountUsageData>> {
    const envelope = await this.#readOnce();
    if (!envelope.ok) {
      return {
        status: "error",
        collectedAt: envelope.collectedAt,
        code: "official_account_read_failed",
      };
    }
    if (envelope.value.usage !== null) {
      const normalized = recognizedAccountUsage(envelope.value.usage);
      if (!normalized) {
        return {
          status: "error",
          collectedAt: envelope.collectedAt,
          code: "account_usage_invalid",
        };
      }
      return {
        status: "ok",
        collectedAt: envelope.collectedAt,
        value: normalized,
      };
    }
    if (isUnsupportedUsageRead(envelope.value)) {
      return { status: "unsupported" };
    }
    return {
      status: "error",
      collectedAt: envelope.collectedAt,
      code: "account_usage_read_failed",
    };
  }

  async close(): Promise<void> {
    if (this.#closed) return;
    this.#closed = true;
    await this.#client.stop();
  }

  #readOnce(): Promise<OfficialReadEnvelope> {
    this.#readPromise ??= this.#client.readOfficialAccount()
      .then((value): OfficialReadEnvelope => ({
        ok: true,
        collectedAt: this.#now().toISOString(),
        value,
      }))
      .catch((): OfficialReadEnvelope => ({
        ok: false,
        collectedAt: this.#now().toISOString(),
      }));
    return this.#readPromise;
  }
}

function recognizedAccountUsage(raw: unknown): AccountUsageData | null {
  if (
    raw === null ||
    typeof raw !== "object" ||
    Array.isArray(raw) ||
    !Object.hasOwn(raw, "dailyUsageBuckets")
  ) {
    return null;
  }
  const buckets = (raw as Record<string, unknown>).dailyUsageBuckets;
  if (buckets === null) return { dailyTokens: [] };
  if (!Array.isArray(buckets)) return null;
  const normalized = normalizeAccountUsage(raw);
  // An explicitly empty array is a valid zero-activity response. A non-empty
  // array with no recognized row is schema drift, not a new zero snapshot.
  if (buckets.length > 0 && normalized.dailyTokens.length === 0) return null;
  return normalized;
}

export function isUnsupportedUsageRead(read: OfficialAccountRead): boolean {
  if (read.usageErrorCode === -32601) return true;
  return read.usageError !== null &&
    /(?:method\s+not\s+found|unknown\s+method|not\s+supported|unsupported)/iu.test(
      read.usageError,
    );
}
