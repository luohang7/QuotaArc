import assert from "node:assert/strict";
import test from "node:test";
import { assertQuotaArcSummary } from "@quotaarc/contracts";
import {
  collectSnapshot,
  mergeSnapshot,
  queryUsage,
  type CollectorPorts,
  type LocalUsageData,
} from "../../src/snapshot/index.js";

const now = new Date("2026-07-19T10:00:00Z");
const localUsage: LocalUsageData = {
  period: "today",
  newInputTokens: 10,
  cachedInputTokens: 5,
  outputTokens: 4,
  reasoningTokens: 1,
  models: [{
    id: "model-1",
    label: "Model",
    newInputTokens: 10,
    cachedInputTokens: 5,
    outputTokens: 4,
    reasoningTokens: 1,
  }],
  projects: [{
    id: "project-1",
    label: "Project",
    newInputTokens: 10,
    cachedInputTokens: 5,
    outputTokens: 4,
    reasoningTokens: 1,
  }],
  days: [],
  coverage: {
    files: 2,
    firstEventAt: "2026-07-19T08:00:00Z",
    lastEventAt: "2026-07-19T09:59:00Z",
  },
};

test("mergeSnapshot returns the exact shared v1 contract", () => {
  const snapshot = mergeSnapshot({
    quota: {
      status: "ok",
      collectedAt: "2026-07-19T09:59:58Z",
      value: { limits: [] },
    },
    accountUsage: {
      status: "unavailable",
      code: "method_unavailable",
    },
    localUsage: {
      status: "ok",
      collectedAt: "2026-07-19T09:59:59Z",
      value: localUsage,
    },
  }, { now });

  assert.doesNotThrow(() => assertQuotaArcSummary(snapshot));
  assert.equal(snapshot.stale, false);
  assert.equal(snapshot.sources.accountUsage.status, "unavailable");
  assert.deepEqual(Object.keys(snapshot.localUsage).sort(), [
    "cachedInputTokens",
    "models",
    "newInputTokens",
    "outputTokens",
    "period",
    "projects",
    "reasoningTokens",
  ]);
  assert.equal(snapshot.sources.localUsage.coverage.files, 2);
});

test("last-good data maps a failed source to stale without hiding peers", () => {
  const snapshot = mergeSnapshot({
    quota: {
      status: "error",
      code: "rpc_timeout",
      lastGood: {
        collectedAt: "2026-07-19T09:30:00Z",
        value: { limits: [] },
      },
    },
    accountUsage: {
      status: "ok",
      collectedAt: "2026-07-19T09:59:00Z",
      value: { dailyTokens: [] },
    },
    localUsage: {
      status: "ok",
      collectedAt: "2026-07-19T09:59:00Z",
      value: localUsage,
    },
  }, { now });

  assert.equal(snapshot.stale, true);
  assert.equal(snapshot.sources.quota.status, "stale");
  assert.equal(snapshot.sources.quota.error?.code, "rpc_timeout");
  assert.equal(snapshot.sources.accountUsage.status, "ok");
});

test("collectSnapshot isolates a throwing source adapter", async () => {
  const ports: CollectorPorts = {
    async readQuota() {
      throw new Error("secret /Users/private/token");
    },
    async readAccountUsage() {
      return {
        status: "ok",
        collectedAt: now.toISOString(),
        value: { dailyTokens: [] },
      };
    },
    async readLocalUsage() {
      return {
        status: "ok",
        collectedAt: now.toISOString(),
        value: localUsage,
      };
    },
  };

  const snapshot = await collectSnapshot(ports, { now });
  assert.equal(snapshot.sources.quota.status, "error");
  assert.equal(snapshot.sources.quota.error?.code, "source_exception");
  assert.doesNotMatch(JSON.stringify(snapshot), /Users|secret|token/);
});

test("day usage uses the same last-good resolution as snapshot totals", async () => {
  const currentButInvalid: LocalUsageData = {
    ...localUsage,
    period: "week",
    newInputTokens: 999,
    days: [{
      id: "current-error",
      label: "Current error",
      newInputTokens: 999,
      cachedInputTokens: 5,
      outputTokens: 4,
      reasoningTokens: 1,
    }],
  };
  const lastGood: LocalUsageData = {
    ...localUsage,
    period: "week",
    days: [{
      id: "2026-07-19",
      label: "2026-07-19",
      newInputTokens: 10,
      cachedInputTokens: 5,
      outputTokens: 4,
      reasoningTokens: 1,
    }],
  };
  const ports: CollectorPorts = {
    async readQuota() {
      return { status: "unavailable" };
    },
    async readAccountUsage() {
      return { status: "unavailable" };
    },
    async readLocalUsage() {
      return {
        status: "error",
        collectedAt: now.toISOString(),
        code: "scan_failed",
        value: currentButInvalid,
        lastGood: {
          collectedAt: "2026-07-19T09:00:00Z",
          value: lastGood,
        },
      };
    },
  };

  const result = await queryUsage(
    ports,
    { period: "week", groupBy: "day" },
    { now },
  );
  assert.equal(result.source.status, "stale");
  assert.equal(result.totals.newInputTokens, 10);
  assert.deepEqual(result.groups.map((group) => group.id), ["2026-07-19"]);
});

test("an error value without last-good data cannot populate day groups", async () => {
  const ports: CollectorPorts = {
    async readQuota() {
      return { status: "unavailable" };
    },
    async readAccountUsage() {
      return { status: "unavailable" };
    },
    async readLocalUsage() {
      return {
        status: "error",
        collectedAt: now.toISOString(),
        code: "scan_failed",
        value: {
          ...localUsage,
          period: "month",
          days: [{
            id: "must-not-escape",
            label: "Must not escape",
            newInputTokens: 10,
            cachedInputTokens: 5,
            outputTokens: 4,
            reasoningTokens: 1,
          }],
        },
      };
    },
  };

  const result = await queryUsage(
    ports,
    { period: "month", groupBy: "day" },
    { now },
  );
  assert.equal(result.source.status, "error");
  assert.deepEqual(result.groups, []);
  assert.equal(result.totals.newInputTokens, 0);
});
