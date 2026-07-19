import assert from "node:assert/strict";
import test from "node:test";
import type { OfficialAccountRead } from "../../src/codex-rpc/client.js";
import {
  isUnsupportedUsageRead,
  OfficialAccountAdapter,
  type OfficialAccountClientPort,
} from "../../src/adapters/official.js";

class FakeClient implements OfficialAccountClientPort {
  reads = 0;
  stops = 0;

  constructor(
    readonly response: OfficialAccountRead | Error,
  ) {}

  async readOfficialAccount(): Promise<OfficialAccountRead> {
    this.reads += 1;
    if (this.response instanceof Error) throw this.response;
    return this.response;
  }

  async stop(): Promise<void> {
    this.stops += 1;
  }
}

test("quota and account usage share one official account read", async () => {
  const client = new FakeClient({
    rateLimits: {
      rateLimitsByLimitId: {
        codex: {
          limitId: "codex",
          primary: {
            usedPercent: 25,
            windowDurationMins: 10080,
            resetsAt: 1785000000,
          },
        },
      },
    },
    usage: {
      dailyUsageBuckets: [{
        startDate: "2026-07-19",
        tokens: 42,
      }],
    },
    usageError: null,
    usageErrorCode: null,
  });
  const adapter = new OfficialAccountAdapter(client, {
    now: () => new Date("2026-07-19T10:00:00Z"),
  });

  const [quota, usage] = await Promise.all([
    adapter.readQuota(),
    adapter.readAccountUsage(),
  ]);

  assert.equal(client.reads, 1);
  assert.equal(quota.status, "ok");
  assert.equal(quota.value?.limits[0]?.windows[0]?.remainingPercent, 75);
  assert.equal(usage.status, "ok");
  assert.equal(usage.value?.dailyTokens[0]?.tokens, 42);
  assert.equal(quota.collectedAt, usage.collectedAt);

  await adapter.close();
  await adapter.close();
  assert.equal(client.stops, 1);
});

test("JSON-RPC method-not-found maps optional usage to unsupported", async () => {
  const client = new FakeClient({
    rateLimits: {},
    usage: null,
    usageError: "opaque error text",
    usageErrorCode: -32601,
  });
  const adapter = new OfficialAccountAdapter(client);

  const usage = await adapter.readAccountUsage();
  assert.deepEqual(usage, { status: "unsupported" });
});

test("message matching remains a fallback for older clients", () => {
  assert.equal(isUnsupportedUsageRead({
    rateLimits: {},
    usage: null,
    usageError: "Method not found: account/usage/read",
    usageErrorCode: null,
  }), true);
  assert.equal(isUnsupportedUsageRead({
    rateLimits: {},
    usage: null,
    usageError: "Request timed out",
    usageErrorCode: null,
  }), false);
});

test("a failed shared read becomes independent normalized source errors", async () => {
  const client = new FakeClient(new Error("Bearer secret /Users/alice"));
  const adapter = new OfficialAccountAdapter(client, {
    now: () => new Date("2026-07-19T10:00:00Z"),
  });

  const [quota, usage] = await Promise.all([
    adapter.readQuota(),
    adapter.readAccountUsage(),
  ]);

  assert.equal(client.reads, 1);
  assert.equal(quota.code, "official_account_read_failed");
  assert.equal(usage.code, "official_account_read_failed");
  assert.doesNotMatch(JSON.stringify([quota, usage]), /Bearer|Users|alice/);
});

test("mandatory quota rejects malformed or empty normalized buckets", async () => {
  const client = new FakeClient({
    rateLimits: { rateLimitsByLimitId: { codex: { primary: {} } } },
    usage: { dailyUsageBuckets: [] },
    usageError: null,
    usageErrorCode: null,
  });
  const adapter = new OfficialAccountAdapter(client, {
    now: () => new Date("2026-07-19T10:00:00Z"),
  });

  const quota = await adapter.readQuota();
  assert.equal(quota.status, "error");
  assert.equal(quota.code, "rate_limits_invalid");
  assert.equal(quota.value, undefined);
});

test("usage accepts explicit null or empty buckets as recognized empty activity", async () => {
  for (const dailyUsageBuckets of [null, []]) {
    const client = new FakeClient({
      rateLimits: {},
      usage: { dailyUsageBuckets },
      usageError: null,
      usageErrorCode: null,
    });
    const adapter = new OfficialAccountAdapter(client);
    const usage = await adapter.readAccountUsage();
    assert.equal(usage.status, "ok");
    assert.deepEqual(usage.value, { dailyTokens: [] });
  }
});

test("unknown usage objects are errors and cannot publish an empty replacement", async () => {
  for (const usagePayload of [
    { summary: { lifetimeTokens: 100 } },
    { dailyUsageBuckets: "new-shape" },
    { dailyUsageBuckets: [{ newDate: "2026-07-19", value: 100 }] },
  ]) {
    const client = new FakeClient({
      rateLimits: {},
      usage: usagePayload,
      usageError: null,
      usageErrorCode: null,
    });
    const adapter = new OfficialAccountAdapter(client, {
      now: () => new Date("2026-07-19T10:00:00Z"),
    });
    const usage = await adapter.readAccountUsage();
    assert.equal(usage.status, "error");
    assert.equal(usage.code, "account_usage_invalid");
    assert.equal(usage.value, undefined);
  }
});
