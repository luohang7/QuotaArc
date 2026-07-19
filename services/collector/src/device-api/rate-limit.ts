export type DeviceRateBucket = "read" | "refresh";

export interface DeviceRateLimiterOptions {
  now?: () => Date;
  readLimitPerMinute?: number;
  refreshLimitPerMinute?: number;
}

interface Counter {
  window: number;
  count: number;
}

/**
 * A deliberately small in-memory fixed-window limiter. Device revocation and
 * scopes remain authoritative in the file registry; this limiter only bounds
 * accidental or abusive request volume for the running process.
 */
export class DeviceRateLimiter {
  readonly #now: () => Date;
  readonly #limits: Record<DeviceRateBucket, number>;
  readonly #counters = new Map<string, Counter>();

  constructor(options: DeviceRateLimiterOptions = {}) {
    this.#now = options.now ?? (() => new Date());
    this.#limits = {
      read: normalizeLimit(options.readLimitPerMinute ?? 120),
      refresh: normalizeLimit(options.refreshLimitPerMinute ?? 6),
    };
  }

  consume(deviceId: string, bucket: DeviceRateBucket): boolean {
    const now = this.#now();
    if (!Number.isFinite(now.getTime())) throw new Error("clock_invalid");
    const window = Math.floor(now.getTime() / 60_000);
    const key = `${deviceId}:${bucket}`;
    const current = this.#counters.get(key);
    if (!current || current.window !== window) {
      this.#counters.set(key, { window, count: 1 });
      return true;
    }
    if (current.count >= this.#limits[bucket]) return false;
    current.count += 1;
    return true;
  }
}

function normalizeLimit(value: number): number {
  if (!Number.isSafeInteger(value) || value < 1 || value > 10_000) {
    throw new Error("rate_limit_invalid");
  }
  return value;
}
