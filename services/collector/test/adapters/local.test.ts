import assert from "node:assert/strict";
import test from "node:test";
import {
  createSessionLocalUsageReader,
  type SessionScan,
} from "../../src/adapters/local.js";
import type {
  ScannedTokenEvent,
  SessionFileScanResult,
} from "../../src/session-indexer/index.js";
import type { TokenTotals, UsageGroup } from "../../src/snapshot/index.js";

const now = new Date(2026, 6, 19, 12, 0, 0, 0);
const projectA = "a".repeat(64);
const projectB = "b".repeat(64);

test("local reader uses local day boundaries and scans only once", async () => {
  let scans = 0;
  const events = [
    tokenEvent({
      at: new Date(2026, 6, 19, 8),
      provider: "openai",
      model: "gpt",
      projectKey: projectA,
      projectLabel: "QuotaArc",
      input: 100,
      cached: 40,
      output: 20,
      reasoning: 5,
    }),
    tokenEvent({
      at: new Date(2026, 6, 14, 8),
      provider: "custom",
      model: "gpt",
      projectKey: null,
      input: 50,
      cached: 10,
      output: 10,
      reasoning: 2,
    }),
    tokenEvent({
      at: new Date(2026, 6, 2, 8),
      provider: "openai",
      model: "other",
      projectKey: projectB,
      projectLabel: "Other",
      input: 30,
      cached: 0,
      output: 5,
      reasoning: 1,
    }),
    tokenEvent({
      at: new Date(2026, 5, 30, 23),
      provider: "openai",
      model: "old",
      projectKey: projectB,
      input: 999,
    }),
    tokenEvent({
      at: new Date(2026, 6, 20, 1),
      provider: "openai",
      model: "future",
      projectKey: projectB,
      input: 999,
    }),
  ];
  const scan: SessionScan = async () => {
    scans += 1;
    return scanResult(events);
  };
  const reader = createSessionLocalUsageReader({
    roots: ["/not-read/active", "/not-read/archive"],
    now: () => now,
    scan,
  });

  const today = await reader.read("today");
  const week = await reader.read("week");
  const month = await reader.read("month");

  assert.equal(scans, 1);
  assert.deepEqual(totals(today.value), {
    newInputTokens: 60,
    cachedInputTokens: 40,
    outputTokens: 20,
    reasoningTokens: 5,
  });
  assert.deepEqual(totals(week.value), {
    newInputTokens: 100,
    cachedInputTokens: 50,
    outputTokens: 30,
    reasoningTokens: 7,
  });
  assert.deepEqual(totals(month.value), {
    newInputTokens: 130,
    cachedInputTokens: 50,
    outputTokens: 35,
    reasoningTokens: 8,
  });
  assert.equal(week.value?.projects.some((group) => group.label === "Unknown"), true);
  assertBreakdownsSum(week.value?.projects ?? [], totals(week.value));
  assertBreakdownsSum(week.value?.models ?? [], totals(week.value));
  assert.equal(
    new Set(week.value?.models.map((group) => group.id)).size,
    2,
  );
  assert.equal(
    week.value?.models.some((group) => group.label === "gpt (custom)"),
    true,
  );
  assert.equal(week.value?.days.length, 2);
  assert.equal(month.value?.coverage?.files, 4);
  assert.equal(
    month.value?.coverage?.firstEventAt,
    new Date(2026, 5, 30, 23).toISOString(),
  );
  assert.equal(
    month.value?.coverage?.lastEventAt,
    new Date(2026, 6, 20, 1).toISOString(),
  );
});

test("project ids are always re-hashed and public labels redact paths and credentials", async () => {
  const maliciousKey = "safe-looking-project-key";
  const reader = createSessionLocalUsageReader({
    roots: [],
    now: () => now,
    scan: async () => scanResult([
      tokenEvent({
        at: new Date(2026, 6, 19, 8),
        provider: "/Users/alice/private-provider",
        model: "model /opt/company/private-project",
        projectKey: maliciousKey,
        projectLabel: "/Users/alice/sk-abcdefghijklmnop",
        input: 10,
      }),
    ]),
  });

  const result = await reader.read("today");
  const serialized = JSON.stringify(result);
  const project = result.value?.projects[0];
  const model = result.value?.models[0];
  assert.match(project?.id ?? "", /^project-[a-f0-9]{32}$/u);
  assert.notEqual(project?.id, maliciousKey);
  assert.equal(project?.label, "Unknown");
  assert.equal(model?.label, "Unknown (Unknown provider)");
  assert.doesNotMatch(serialized, /Users|alice|\/opt|sk-/u);
});

test("a non-OpenAI model label always retains its safe provider suffix", async () => {
  const reader = createSessionLocalUsageReader({
    roots: [],
    now: () => now,
    scan: async () => scanResult([
      tokenEvent({
        at: new Date(2026, 6, 19, 8),
        provider: "custom",
        model: "m".repeat(200),
        projectKey: projectA,
        input: 1,
      }),
    ]),
  });

  const result = await reader.read("today");
  const label = result.value?.models[0]?.label ?? "";
  assert.equal(label.length, 160);
  assert.match(label, /\(custom\)$/u);
});

test("partial scans are stale while an all-failed scan is error", async () => {
  const event = tokenEvent({
    at: new Date(2026, 6, 19, 8),
    provider: "openai",
    model: "gpt",
    projectKey: projectA,
    input: 10,
  });
  const partialReader = createSessionLocalUsageReader({
    roots: [],
    now: () => now,
    scan: async () => ({
      ...scanResult([event]),
      diagnosticsCount: 2,
    }),
  });
  const failedReader = createSessionLocalUsageReader({
    roots: [],
    now: () => now,
    scan: async () => ({
      ...scanResult([]),
      diagnosticsCount: 2,
    }),
  });
  const rejectedReader = createSessionLocalUsageReader({
    roots: [],
    now: () => now,
    scan: async () => {
      throw new Error("private scan error");
    },
  });

  const partial = await partialReader.read("today");
  const failed = await failedReader.read("today");
  const rejected = await rejectedReader.read("today");
  assert.equal(partial.status, "stale");
  assert.equal(partial.code, "session_scan_incomplete");
  assert.equal(partial.value?.newInputTokens, 10);
  assert.deepEqual(failed, {
    status: "error",
    collectedAt: now.toISOString(),
    code: "session_scan_failed",
  });
  assert.deepEqual(rejected, failed);
});

test("all configured session roots unavailable cannot look like zero usage", async () => {
  const reader = createSessionLocalUsageReader({
    roots: ["/missing/active", "/missing/archive"],
    now: () => now,
    scan: async () => ({
      ...scanResult([]),
      rootsAvailable: 0,
      filesCount: 0,
      bytesScanned: 0,
    }),
  });

  assert.deepEqual(await reader.read("today"), {
    status: "unavailable",
    collectedAt: now.toISOString(),
    code: "session_roots_unavailable",
  });
});

function tokenEvent(options: {
  at: Date;
  provider: string;
  model: string | null;
  projectKey: string | null;
  projectLabel?: string;
  input: number;
  cached?: number;
  output?: number;
  reasoning?: number;
}): ScannedTokenEvent {
  return {
    timestamp: options.at.toISOString(),
    threadId: "thread",
    parentThreadId: null,
    turnId: "turn",
    provider: options.provider,
    model: options.model,
    project: options.projectKey === null
      ? null
      : {
          projectKey: options.projectKey,
          safeBasename: options.projectLabel ?? "Project",
        },
    eventIndex: 1,
    fingerprint: `${options.at.getTime()}-thread`,
    replayFingerprint: `${options.at.getTime()}-replay`,
    absolute: {},
    delta: {
      inputTokens: options.input,
      cachedInputTokens: options.cached ?? 0,
      outputTokens: options.output ?? 0,
      reasoningOutputTokens: options.reasoning ?? 0,
    },
    counterSegment: 0,
    counterReset: false,
  };
}

function scanResult(events: ScannedTokenEvent[]): SessionFileScanResult {
  const timestamps = events
    .map((event) => event.timestamp)
    .filter((value): value is string => value !== null)
    .sort();
  return {
    tokenEvents: events,
    rootsAvailable: 2,
    filesCount: 4,
    firstEventAt: timestamps[0] ?? null,
    lastEventAt: timestamps.at(-1) ?? null,
    diagnosticsCount: 0,
    bytesScanned: 1_000,
  };
}

function totals(
  value: TokenTotals | undefined,
): TokenTotals {
  assert.ok(value);
  return {
    newInputTokens: value.newInputTokens,
    cachedInputTokens: value.cachedInputTokens,
    outputTokens: value.outputTokens,
    reasoningTokens: value.reasoningTokens,
  };
}

function assertBreakdownsSum(
  groups: UsageGroup[],
  expected: TokenTotals,
): void {
  for (const key of [
    "newInputTokens",
    "cachedInputTokens",
    "outputTokens",
    "reasoningTokens",
  ] as const) {
    assert.equal(
      groups.reduce((sum, group) => sum + group[key], 0),
      expected[key],
    );
  }
}
