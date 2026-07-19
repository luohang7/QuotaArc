import assert from "node:assert/strict";
import {
  copyFile,
  mkdir,
  mkdtemp,
  readFile,
  rm,
  symlink,
  writeFile,
} from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import {
  benchmarkSessionScan,
  scanSessionFiles,
} from "../../src/session-indexer/index.js";

const fixtures = path.resolve(
  process.cwd(),
  "test",
  "fixtures",
  "sessions",
);

test("recursively scans active and archived JSONL in stable order", async (t) => {
  const temporary = await mkdtemp(
    path.join(os.tmpdir(), "quotaarc-session-files-"),
  );
  t.after(async () => {
    await rm(temporary, { recursive: true, force: true });
  });

  const active = path.join(temporary, "active");
  const archived = path.join(temporary, "archived_sessions");
  const nestedActive = path.join(active, "2026", "07", "19");
  await mkdir(nestedActive, { recursive: true });
  await mkdir(archived, { recursive: true });
  await copyFile(
    path.join(fixtures, "basic.jsonl"),
    path.join(nestedActive, "basic.jsonl"),
  );
  await copyFile(
    path.join(fixtures, "custom-old-fields.jsonl"),
    path.join(archived, "custom.jsonl"),
  );
  await symlink(
    path.join(archived, "custom.jsonl"),
    path.join(active, "ignored-symlink.jsonl"),
  );

  const options = {
    roots: [archived, path.join(temporary, "missing"), active],
    chunkSize: 37,
  };
  const first = await scanSessionFiles(options);
  const repeated = await scanSessionFiles({
    ...options,
    roots: [...options.roots].reverse(),
  });

  assert.equal(first.filesCount, 2);
  assert.equal(first.tokenEvents.length, 3);
  assert.equal(first.diagnosticsCount, 0);
  assert.equal(first.firstEventAt, "2026-07-18T03:00:02.000Z");
  assert.equal(first.lastEventAt, "2026-07-19T01:00:05.000Z");
  assert.deepEqual(
    first.tokenEvents.map((event) => event.fingerprint),
    repeated.tokenEvents.map((event) => event.fingerprint),
  );
  assert.deepEqual(
    [...new Set(first.tokenEvents.map((event) => event.provider))].sort(),
    ["custom", "openai"],
  );

  const publicJson = JSON.stringify(first);
  assert.equal(publicJson.includes(temporary), false);
  assert.equal(publicJson.includes(".jsonl"), false);
  assert.equal(publicJson.includes("/workspace/"), false);
  assert.match(first.tokenEvents[0]?.project?.projectKey ?? "", /^[a-f0-9]{64}$/u);
});

test("missing roots are an empty successful scan", async () => {
  const result = await scanSessionFiles({
    roots: [path.join(os.tmpdir(), "quotaarc-does-not-exist", "sessions")],
    chunkSize: 16,
  });
  assert.deepEqual(result, {
    tokenEvents: [],
    rootsAvailable: 0,
    filesCount: 0,
    firstEventAt: null,
    lastEventAt: null,
    diagnosticsCount: 0,
    bytesScanned: 0,
  });
});

test("does not count an incomplete final token line", async (t) => {
  const temporary = await mkdtemp(
    path.join(os.tmpdir(), "quotaarc-session-partial-"),
  );
  t.after(async () => {
    await rm(temporary, { recursive: true, force: true });
  });
  const complete = await readFile(path.join(fixtures, "basic.jsonl"));
  const finalMarker = complete.indexOf(Buffer.from('{"timestamp":"2026-07-19T01:00:05'));
  assert.ok(finalMarker > 0);
  const partial = complete.subarray(0, finalMarker + 80);
  await writeFile(path.join(temporary, "partial.jsonl"), partial);

  const result = await scanSessionFiles({
    roots: [temporary],
    chunkSize: 11,
  });
  assert.equal(result.filesCount, 1);
  assert.equal(result.tokenEvents.length, 1);
  assert.equal(result.diagnosticsCount, 0);
  assert.equal(result.lastEventAt, "2026-07-19T01:00:04.000Z");
  assert.equal(result.bytesScanned, partial.byteLength);
});

test("deduplicates copied parent history across fork files", async (t) => {
  const temporary = await mkdtemp(
    path.join(os.tmpdir(), "quotaarc-session-fork-"),
  );
  t.after(async () => {
    await rm(temporary, { recursive: true, force: true });
  });
  await copyFile(
    path.join(fixtures, "parent.jsonl"),
    path.join(temporary, "parent.jsonl"),
  );
  await copyFile(
    path.join(fixtures, "fork-replay.jsonl"),
    path.join(temporary, "child.jsonl"),
  );

  const result = await scanSessionFiles({ roots: [temporary], chunkSize: 31 });
  assert.equal(result.filesCount, 2);
  assert.equal(result.tokenEvents.length, 2);
});

test("benchmark helper reports elapsed time, RSS delta, and bytes", async () => {
  const source = path.join(fixtures, "reset.jsonl");
  const expectedBytes = (await readFile(source)).byteLength;
  const benchmark = await benchmarkSessionScan({
    roots: [source],
    chunkSize: 23,
  });

  assert.ok(benchmark.elapsedMs >= 0);
  assert.ok(benchmark.rssBeforeBytes > 0);
  assert.ok(benchmark.peakRssBytes >= benchmark.rssBeforeBytes);
  assert.ok(benchmark.peakRssDeltaBytes >= 0);
  assert.equal(
    benchmark.peakRssDeltaBytes,
    benchmark.peakRssBytes - benchmark.rssBeforeBytes,
  );
  assert.equal(benchmark.bytes, expectedBytes);
  assert.equal(benchmark.filesCount, 1);
  assert.equal(benchmark.tokenEventsCount, 2);
  assert.equal(benchmark.diagnosticsCount, 0);
});
