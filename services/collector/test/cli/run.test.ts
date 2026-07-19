import assert from "node:assert/strict";
import test from "node:test";
import { runCli, type CliIo } from "../../src/cli/index.js";
import type { LiveCollectorPorts } from "../../src/adapters/live.js";
import type { CollectorPorts, LocalUsageData } from "../../src/snapshot/index.js";

function captureIo(): { io: CliIo; stdout: string[]; stderr: string[] } {
  const stdout: string[] = [];
  const stderr: string[] = [];
  return {
    stdout,
    stderr,
    io: {
      stdout: (value) => stdout.push(value),
      stderr: (value) => stderr.push(value),
    },
  };
}

const localUsage: LocalUsageData = {
  period: "week",
  newInputTokens: 3,
  cachedInputTokens: 2,
  outputTokens: 1,
  reasoningTokens: 0,
  models: [],
  projects: [],
  days: [{
    id: "2026-07-19",
    label: "2026-07-19",
    newInputTokens: 3,
    cachedInputTokens: 2,
    outputTokens: 1,
    reasoningTokens: 0,
  }],
  coverage: {
    files: 1,
    firstEventAt: "2026-07-19T08:00:00Z",
    lastEventAt: "2026-07-19T09:00:00Z",
  },
};

const ports: CollectorPorts = {
  async readQuota() {
    return {
      status: "ok",
      collectedAt: "2026-07-19T10:00:00Z",
      value: { limits: [] },
    };
  },
  async readAccountUsage() {
    return { status: "unsupported" };
  },
  async readLocalUsage(period) {
    return {
      status: "ok",
      collectedAt: "2026-07-19T10:00:00Z",
      value: { ...localUsage, period },
    };
  },
};

test("collect --once writes only a v1 JSON snapshot", async () => {
  const captured = captureIo();
  const code = await runCli(["collect", "--once"], {
    io: captured.io,
    ports,
    now: () => new Date("2026-07-19T10:00:01Z"),
  });
  const output = JSON.parse(captured.stdout[0] ?? "{}") as {
    schemaVersion?: number;
    sources?: { accountUsage?: { status?: string } };
  };

  assert.equal(code, 0);
  assert.equal(captured.stderr.length, 0);
  assert.equal(output.schemaVersion, 1);
  assert.equal(output.sources?.accountUsage?.status, "unsupported");
});

test("usage supports period and day grouping through the port", async () => {
  const captured = captureIo();
  const code = await runCli([
    "usage",
    "--period",
    "week",
    "--group-by",
    "day",
  ], {
    io: captured.io,
    ports,
    now: () => new Date("2026-07-19T10:00:01Z"),
  });
  const output = JSON.parse(captured.stdout[0] ?? "{}") as {
    period?: string;
    groupBy?: string;
    groups?: unknown[];
  };

  assert.equal(code, 0);
  assert.equal(output.period, "week");
  assert.equal(output.groupBy, "day");
  assert.equal(output.groups?.length, 1);
});

test("invalid CLI values return a normalized error without echoing input", async () => {
  const captured = captureIo();
  const code = await runCli([
    "usage",
    "--period",
    "/Users/alice/private",
    "--group-by",
    "model",
  ], { io: captured.io, ports });

  assert.equal(code, 2);
  assert.deepEqual(JSON.parse(captured.stderr[0] ?? "{}"), {
    error: { code: "usage_invalid_query" },
  });
  assert.doesNotMatch(captured.stderr.join(""), /Users|alice|private/);
});

test("default live ports are injected and always closed", async () => {
  const captured = captureIo();
  let factoryCalls = 0;
  let closes = 0;
  const live: LiveCollectorPorts = {
    ...ports,
    async close() {
      closes += 1;
    },
  };

  const code = await runCli(["collect", "--once"], {
    io: captured.io,
    now: () => new Date("2026-07-19T10:00:01Z"),
    createLivePorts() {
      factoryCalls += 1;
      return live;
    },
  });

  assert.equal(code, 0);
  assert.equal(factoryCalls, 1);
  assert.equal(closes, 1);
});
