import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { join } from "node:path";
import test from "node:test";
import {
  parseQuotaArcSummary,
  type QuotaArcSummary,
} from "@quotaarc/contracts";
import { DeviceSnapshotService } from "../../src/device-api/index.js";

test("concurrent refresh requests share one collection and retain receipts", async () => {
  const fixture = await summaryFixture();
  let collectCalls = 0;
  let release!: (summary: QuotaArcSummary) => void;
  const pending = new Promise<QuotaArcSummary>((resolve) => {
    release = resolve;
  });
  let now = new Date("2026-07-19T10:00:00Z");
  const service = new DeviceSnapshotService(
    "qac_abcdefghijklmnopqrstuv",
    () => {
      collectCalls += 1;
      return pending;
    },
    {
      now: () => now,
      requestId: () => "qar_abcdefghijklmnop",
    },
  );

  const first = service.refresh();
  const second = service.refresh();
  assert.equal(collectCalls, 1);
  now = new Date("2026-07-19T10:00:01Z");
  release(fixture);
  const [firstResult, secondResult] = await Promise.all([first, second]);

  assert.equal(firstResult.receipt.status, "refreshed");
  assert.equal(secondResult.receipt.status, "coalesced");
  assert.equal(firstResult.receipt.requestId, secondResult.receipt.requestId);
  assert.equal(firstResult.summary.generatedAt, fixture.generatedAt);
});

test("summary cache expires and an invalid candidate never replaces last good", async () => {
  const fixture = await summaryFixture();
  let now = new Date("2026-07-19T10:00:00Z");
  let candidate: QuotaArcSummary = fixture;
  let calls = 0;
  const service = new DeviceSnapshotService(
    "qac_abcdefghijklmnopqrstuv",
    async () => {
      calls += 1;
      return candidate;
    },
    {
      now: () => now,
      cacheTtlMs: 1_000,
      requestId: () => `qar_${String(calls).padStart(16, "a")}`,
    },
  );

  assert.equal((await service.summary()).generatedAt, fixture.generatedAt);
  assert.equal((await service.summary()).generatedAt, fixture.generatedAt);
  assert.equal(calls, 1);

  now = new Date("2026-07-19T10:00:02Z");
  candidate = {
    ...fixture,
    schemaVersion: 2,
  } as unknown as QuotaArcSummary;
  await assert.rejects(service.summary());
  assert.equal(calls, 2);
});

async function summaryFixture(): Promise<QuotaArcSummary> {
  const path = join(
    process.cwd(),
    "../../packages/contracts/examples/summary.ok.json",
  );
  return parseQuotaArcSummary(JSON.parse(await readFile(path, "utf8")));
}
