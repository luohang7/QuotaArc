import assert from "node:assert/strict";
import test from "node:test";

import {
  normalizeAccountUsage,
  normalizeRateLimits,
} from "../../src/codex-rpc/normalize.js";

test("prefers the multi-limit response and derives remaining percentage", () => {
  const normalized = normalizeRateLimits({
    rateLimits: {
      limitId: "compatibility",
      primary: {
        usedPercent: 1,
        windowDurationMins: 60,
        resetsAt: 1_800_000_000,
      },
    },
    rateLimitsByLimitId: {
      zeta: {
        limitId: "zeta",
        limitName: "Zeta model",
        primary: {
          usedPercent: 120,
          windowDurationMins: 10_080,
          resetsAt: 1_800_000_000,
        },
      },
      codex: {
        limitId: "codex",
        limitName: null,
        primary: {
          usedPercent: 19,
          windowDurationMins: 300,
          resetsAt: 1_800_000_000,
        },
        secondary: {
          usedPercent: -5,
          windowDurationMins: 10_080,
          resetsAt: 1_800_086_400,
        },
      },
    },
  });

  assert.deepEqual(
    normalized.limits.map((limit) => limit.limitId),
    ["codex", "zeta"],
  );
  assert.deepEqual(
    normalized.limits[0]?.windows.map((window) => [
      window.usedPercent,
      window.remainingPercent,
    ]),
    [
      [19, 81],
      [0, 100],
    ],
  );
  assert.deepEqual(normalized.limits[1]?.windows[0]?.remainingPercent, 0);
});

test("falls back to the compatibility bucket and tolerates missing windows", () => {
  const normalized = normalizeRateLimits({
    rateLimits: {
      limitId: null,
      limitName: null,
      primary: null,
      secondary: {
        usedPercent: 50,
        windowDurationMins: null,
        resetsAt: null,
      },
    },
  });

  assert.deepEqual(normalized, { limits: [] });
});

test("uses the compatibility bucket when a multi-limit map is unusable", () => {
  const normalized = normalizeRateLimits({
    rateLimitsByLimitId: { codex: "unexpected" },
    rateLimits: {
      limitId: "codex",
      primary: {
        usedPercent: 25,
        windowDurationMins: 300,
        resetsAt: 1_800_000_000,
      },
    },
  });

  assert.equal(normalized.limits[0]?.limitId, "codex");
});

test("falls back when object-shaped multi-limit entries have no valid window", () => {
  const normalized = normalizeRateLimits({
    rateLimitsByLimitId: {
      codex: {
        limitId: "codex-v2",
        primary: {
          usedPercent: "schema-drift",
          windowDurationMins: 300,
          resetsAt: 1_800_000_000,
        },
      },
    },
    rateLimits: {
      limitId: "compatibility",
      primary: {
        usedPercent: 40,
        windowDurationMins: 300,
        resetsAt: 1_800_000_000,
      },
    },
  });

  assert.deepEqual(normalized.limits.map((limit) => limit.limitId), [
    "compatibility",
  ]);
  assert.equal(normalized.limits[0]?.windows[0]?.remainingPercent, 60);
});

test("normalizes, deduplicates, and sorts valid daily Token buckets", () => {
  const normalized = normalizeAccountUsage({
    summary: { lifetimeTokens: 99_999 },
    dailyUsageBuckets: [
      { startDate: "2026-07-19", tokens: 200 },
      { startDate: "not-a-date", tokens: 400 },
      { startDate: "2026-02-31", tokens: 400 },
      { startDate: "2026-07-18", tokens: 100 },
      { startDate: "2026-07-19", tokens: 250 },
      { startDate: "2026-07-20", tokens: -1 },
    ],
  });

  assert.deepEqual(normalized.dailyTokens, [
    { date: "2026-07-18", tokens: 100 },
    { date: "2026-07-19", tokens: 250 },
  ]);
});

test("unknown response shapes degrade to empty normalized data", () => {
  assert.deepEqual(normalizeRateLimits(null), { limits: [] });
  assert.deepEqual(normalizeAccountUsage({ dailyUsageBuckets: "unknown" }), {
    dailyTokens: [],
  });
});
