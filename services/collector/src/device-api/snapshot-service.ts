import { randomBytes } from "node:crypto";
import {
  assertQuotaArcSummary,
  type QuotaArcSummary,
  type RefreshReceipt,
} from "@quotaarc/contracts";

export interface SnapshotRefreshResult {
  summary: QuotaArcSummary;
  receipt: RefreshReceipt;
}

export interface DeviceSnapshotServiceOptions {
  now?: () => Date;
  cacheTtlMs?: number;
  maximumSummaryBytes?: number;
  requestId?: () => string;
}

interface InFlightRefresh {
  requestId: string;
  acceptedAt: string;
  promise: Promise<QuotaArcSummary>;
}

/**
 * Provides a second single-flight boundary at the Collector. GET requests use
 * a short-lived validated cache, while POST refresh always asks for a new
 * snapshot and coalesces with an already-running collection.
 */
export class DeviceSnapshotService {
  readonly #collectorId: string;
  readonly #collect: () => Promise<QuotaArcSummary>;
  readonly #now: () => Date;
  readonly #cacheTtlMs: number;
  readonly #maximumSummaryBytes: number;
  readonly #requestId: () => string;
  #lastGood: { summary: QuotaArcSummary; storedAt: number } | null = null;
  #inFlight: InFlightRefresh | null = null;

  constructor(
    collectorId: string,
    collect: () => Promise<QuotaArcSummary>,
    options: DeviceSnapshotServiceOptions = {},
  ) {
    this.#collectorId = collectorId;
    this.#collect = collect;
    this.#now = options.now ?? (() => new Date());
    this.#cacheTtlMs = options.cacheTtlMs ?? 60_000;
    this.#maximumSummaryBytes = options.maximumSummaryBytes ?? 256 * 1024;
    this.#requestId = options.requestId ??
      (() => `qar_${randomBytes(18).toString("base64url")}`);
    if (
      !Number.isSafeInteger(this.#cacheTtlMs) ||
      this.#cacheTtlMs < 1_000 ||
      this.#cacheTtlMs > 30 * 60_000
    ) {
      throw new Error("snapshot_cache_ttl_invalid");
    }
    if (
      !Number.isSafeInteger(this.#maximumSummaryBytes) ||
      this.#maximumSummaryBytes < 16 * 1024 ||
      this.#maximumSummaryBytes > 2 * 1024 * 1024
    ) {
      throw new Error("snapshot_size_limit_invalid");
    }
  }

  async summary(): Promise<QuotaArcSummary> {
    const now = validNow(this.#now);
    if (
      this.#lastGood &&
      now.getTime() - this.#lastGood.storedAt <= this.#cacheTtlMs
    ) {
      return this.#lastGood.summary;
    }
    return (await this.refresh()).summary;
  }

  async refresh(): Promise<SnapshotRefreshResult> {
    const existing = this.#inFlight;
    if (existing) {
      const summary = await existing.promise;
      const completedAt = completionTimestamp(this.#now, existing.acceptedAt);
      return {
        summary,
        receipt: {
          schemaVersion: 1,
          collectorId: this.#collectorId,
          requestId: existing.requestId,
          acceptedAt: existing.acceptedAt,
          completedAt,
          status: "coalesced",
          summaryGeneratedAt: summary.generatedAt,
        },
      };
    }

    const acceptedAt = validNow(this.#now).toISOString();
    const requestId = this.#requestId();
    if (!/^qar_[A-Za-z0-9_-]{16,64}$/u.test(requestId)) {
      throw new Error("refresh_request_id_invalid");
    }
    const promise = this.#collect()
      .then((candidate) => this.#accept(candidate));
    const created: InFlightRefresh = { requestId, acceptedAt, promise };
    this.#inFlight = created;
    try {
      const summary = await promise;
      const completedAt = completionTimestamp(this.#now, acceptedAt);
      return {
        summary,
        receipt: {
          schemaVersion: 1,
          collectorId: this.#collectorId,
          requestId,
          acceptedAt,
          completedAt,
          status: "refreshed",
          summaryGeneratedAt: summary.generatedAt,
        },
      };
    } finally {
      if (this.#inFlight === created) this.#inFlight = null;
    }
  }

  #accept(candidate: QuotaArcSummary): QuotaArcSummary {
    assertQuotaArcSummary(candidate);
    const bytes = Buffer.byteLength(JSON.stringify(candidate), "utf8");
    if (bytes > this.#maximumSummaryBytes) {
      throw new Error("summary_response_too_large");
    }
    this.#lastGood = {
      summary: structuredClone(candidate),
      storedAt: validNow(this.#now).getTime(),
    };
    return this.#lastGood.summary;
  }
}

function validNow(now: () => Date): Date {
  const value = now();
  if (!Number.isFinite(value.getTime())) throw new Error("clock_invalid");
  return value;
}

function completionTimestamp(now: () => Date, acceptedAt: string): string {
  const completed = validNow(now);
  const acceptedMillis = Date.parse(acceptedAt);
  return new Date(Math.max(completed.getTime(), acceptedMillis)).toISOString();
}
