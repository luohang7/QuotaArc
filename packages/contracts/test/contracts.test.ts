import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import { Ajv2020, type AnySchema } from "ajv/dist/2020.js";

import {
  ContractValidationError,
  assertQuotaArcSummary,
  deriveGlobalStale,
  getQuotaArcSummaryIssues,
  parseQuotaArcSummary,
  quotaArcSummarySchemaUrl,
  validateQuotaArcSummary,
  type AccountDailyTokenUsage,
  type LocalUsageSummary,
  type QuotaArcSummary,
  type QuotaLimit,
  type QuotaWindow,
  type SourceStatus,
  type UsageBreakdown
} from "../src/index.js";

const packageRoot = new URL("../../", import.meta.url);

async function readJson(relativePath: string): Promise<unknown> {
  return JSON.parse(
    await readFile(new URL(relativePath, packageRoot), "utf8")
  ) as unknown;
}

function clone(value: unknown): Record<string, unknown> {
  return structuredClone(value) as Record<string, unknown>;
}

test("stable integration type names are exported", () => {
  const status: SourceStatus = "ok";
  const window: QuotaWindow = {
    windowMinutes: 60,
    usedPercent: 25,
    remainingPercent: 75,
    resetsAt: "2026-07-19T10:00:00Z"
  };
  const limit: QuotaLimit = {
    limitId: "fixture",
    limitName: null,
    windows: [window]
  };
  const daily: AccountDailyTokenUsage = {
    date: "2026-07-19",
    tokens: 1
  };
  const breakdown: UsageBreakdown = {
    id: "fixture",
    label: "Fixture",
    newInputTokens: 1,
    cachedInputTokens: 0,
    outputTokens: 0,
    reasoningTokens: 0
  };
  const local: LocalUsageSummary = {
    period: "today",
    newInputTokens: 1,
    cachedInputTokens: 0,
    outputTokens: 0,
    reasoningTokens: 0,
    models: [breakdown],
    projects: [breakdown]
  };

  assert.equal(status, "ok");
  assert.equal(limit.windows[0], window);
  assert.equal(daily.tokens, 1);
  assert.equal(local.models[0], breakdown);
});

test("sanitized examples satisfy the runtime contract and JSON Schema", async () => {
  const schema = JSON.parse(
    await readFile(quotaArcSummarySchemaUrl, "utf8")
  ) as AnySchema;
  const ajv = new Ajv2020({ allErrors: true, strict: true });
  const validateSchema = ajv.compile(schema);

  for (const exampleName of ["summary.ok.json", "summary.degraded.json"]) {
    const example = await readJson(`examples/${exampleName}`);
    assert.equal(
      validateSchema(example),
      true,
      `${exampleName}: ${JSON.stringify(validateSchema.errors)}`
    );
    assert.equal(validateQuotaArcSummary(example), true);
    assert.equal(parseQuotaArcSummary(example).schemaVersion, 1);
  }
});

test("global stale is derived only from stale source freshness", async () => {
  const summary = parseQuotaArcSummary(
    await readJson("examples/summary.degraded.json")
  );
  assert.equal(deriveGlobalStale(summary.sources), true);

  const freshWithUnsupported = structuredClone(summary);
  freshWithUnsupported.sources.localUsage.status = "ok";
  freshWithUnsupported.sources.localUsage.error = null;
  freshWithUnsupported.stale = false;
  assert.equal(deriveGlobalStale(freshWithUnsupported.sources), false);
  assert.equal(validateQuotaArcSummary(freshWithUnsupported), true);
});

test("turn data and unknown fields are rejected from mobile v1", async () => {
  const summary = clone(await readJson("examples/summary.ok.json"));
  const localUsage = summary.localUsage as Record<string, unknown>;
  localUsage.turns = [];

  const issues = getQuotaArcSummaryIssues(summary);
  assert.ok(issues.some((issue) => issue.path === "$.localUsage.turns"));
});

test("invalid aggregation, dates, percentages, and sensitive text are rejected", async () => {
  const original = await readJson("examples/summary.ok.json");
  const schema = await readJson("schema/v1/summary.schema.json") as AnySchema;
  const validateSchema = new Ajv2020({
    allErrors: true,
    strict: true
  }).compile(schema);

  const badStale = clone(original);
  badStale.stale = true;
  assert.equal(validateQuotaArcSummary(badStale), false);

  const badDate = clone(original);
  (
    (badDate.accountUsage as Record<string, unknown>)
      .dailyTokens as Record<string, unknown>[]
  )[0]!.date = "2026-02-31";
  assert.equal(validateQuotaArcSummary(badDate), false);

  const badPercent = clone(original);
  (
    (
      ((badPercent.quota as Record<string, unknown>).limits as Record<
        string,
        unknown
      >[])[0]!.windows as Record<string, unknown>[]
    )[0]!
  ).remainingPercent = 80;
  assert.equal(validateQuotaArcSummary(badPercent), false);

  const leakedPath = clone(original);
  (
    (
      (leakedPath.localUsage as Record<string, unknown>).projects as Record<
        string,
        unknown
      >[]
    )[0]!
  ).label = "/Users/example/private-project";
  assert.equal(validateQuotaArcSummary(leakedPath), false);

  for (const path of [
    "model /opt/company/private-project",
    "/etc/passwd",
    String.raw`C:\company\private\model`,
    String.raw`\\server\share\model`,
    "file:///Users/example/model",
    "~/private/model",
    "Bearer abcdefghijklmnop",
    "sk-abcdefghijklmnop"
  ]) {
    const leakedModelPath = clone(original);
    (
      (
        (leakedModelPath.localUsage as Record<string, unknown>)
          .models as Record<string, unknown>[]
      )[0]!
    ).label = path;
    assert.equal(validateQuotaArcSummary(leakedModelPath), false, path);
    assert.equal(validateSchema(leakedModelPath), false, path);
  }

  const leakedLimitId = clone(original);
  (
    ((leakedLimitId.quota as Record<string, unknown>).limits as Record<
      string,
      unknown
    >[])[0]!
  ).limitId = "/private/backend/limit";
  assert.equal(validateQuotaArcSummary(leakedLimitId), false);
  assert.equal(validateSchema(leakedLimitId), false);

  const badBreakdownTotal = clone(original);
  (
    (
      (badBreakdownTotal.localUsage as Record<string, unknown>)
        .models as Record<string, unknown>[]
    )[0]!
  ).outputTokens = 1;
  assert.equal(validateQuotaArcSummary(badBreakdownTotal), false);
});

test("assertion reports all contract issues", () => {
  assert.throws(
    () => assertQuotaArcSummary({ schemaVersion: 2 }),
    (error: unknown) =>
      error instanceof ContractValidationError &&
      error.issues.length > 1 &&
      error.message.includes("$.schemaVersion")
  );
});

test("a parsed summary retains its stable static type", async () => {
  const value: QuotaArcSummary = parseQuotaArcSummary(
    await readJson("examples/summary.ok.json")
  );
  assert.equal(value.sources.quota.kind, "codex_app_server");
});
