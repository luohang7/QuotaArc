import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { chmod, mkdtemp, readdir, rm, stat } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test, { type TestContext } from "node:test";

import {
  CollectorStoreSecurityError,
  STORE_SCHEMA_VERSION,
  openCollectorStore,
  type CollectorStore,
  type EventOccurrenceInput,
  type FileCursorInput,
  type FileIdentity,
} from "../../src/store/index.js";

const fixedNow = new Date("2026-07-19T12:00:00.000Z");

interface WorkerResult {
  readonly code: number | null;
  readonly signal: NodeJS.Signals | null;
  readonly stderr: string;
}

async function temporaryStore(
  context: TestContext,
): Promise<{ directory: string; databasePath: string; store: CollectorStore }> {
  const directory = await mkdtemp(join(tmpdir(), "quotaarc-store-test-"));
  const databasePath = join(directory, "collector.sqlite");
  const store = openCollectorStore(databasePath, {
    now: () => fixedNow,
  });
  context.after(async () => {
    store.close();
    await rm(directory, { recursive: true, force: true });
  });
  return { directory, databasePath, store };
}

function cursor(
  identity: FileIdentity,
  path: string,
  size = 100n,
  byteOffset = size,
  parserVersion = 1,
): FileCursorInput {
  return {
    ...identity,
    path,
    size,
    mtimeNs: 1_752_926_400_000_000_000n + size,
    byteOffset,
    parserVersion,
    parserState: {
      cursor: {
        readOffset: Number(byteOffset),
        pendingBytes: [],
      },
    },
  };
}

function occurrence(
  occurrenceKey: string,
  eventKey: string,
  payload: unknown = { delta: { inputTokens: 10 } },
): EventOccurrenceInput {
  return {
    occurrenceKey,
    lineNumber: 3,
    byteOffset: 42n,
    event: {
      eventKey,
      kind: "token_count",
      occurredAt: "2026-07-19T11:59:00Z",
      payload,
    },
  };
}

function openStoreInWorker(
  moduleUrl: string,
  databasePath: string,
  startAt: number,
): Promise<WorkerResult> {
  const workerSource = `
    const storeModule = await import(process.env.QUOTAARC_STORE_MODULE);
    const delay = Number(process.env.QUOTAARC_START_AT) - Date.now();
    if (delay > 0) {
      await new Promise((resolve) => setTimeout(resolve, delay));
    }
    try {
      const store = storeModule.openCollectorStore(
        process.env.QUOTAARC_DATABASE_PATH,
        { busyTimeoutMs: 15000 },
      );
      store.close();
    } catch (error) {
      console.error(error instanceof Error ? (error.stack ?? error.message) : error);
      process.exitCode = 1;
    }
  `;

  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, ["--input-type=module", "-e", workerSource], {
      env: {
        ...process.env,
        QUOTAARC_DATABASE_PATH: databasePath,
        QUOTAARC_START_AT: String(startAt),
        QUOTAARC_STORE_MODULE: moduleUrl,
      },
    });
    let stderr = "";
    child.stderr.setEncoding("utf8");
    child.stderr.on("data", (chunk: string) => {
      stderr += chunk;
    });
    child.once("error", reject);
    child.once("close", (code, signal) => {
      resolve({ code, signal, stderr });
    });
  });
}

test("archive path move keeps device+inode identity and occurrences", async (context) => {
  const { store } = await temporaryStore(context);
  const identity = { deviceId: "16777234", inode: "9001" };
  const activePath = "/private/session/active/thread.jsonl";
  const archivePath = "/private/session/archived/thread.jsonl";

  const initial = store.upsertFileCursor(cursor(identity, activePath));
  store.replaceOccurrences(identity, [occurrence("line-3", "global-event-a")]);

  const moved = store.upsertFileCursor(cursor(identity, archivePath));

  assert.equal(moved.reset, false);
  assert.equal(moved.replacedSource, false);
  assert.equal(moved.cursor.sourceId, initial.cursor.sourceId);
  assert.equal(store.loadFileCursorByPath(activePath), undefined);
  assert.equal(store.loadFileCursorByPath(archivePath)?.inode, identity.inode);
  assert.equal(store.loadOccurrences(identity).length, 1);
  assert.deepEqual(store.getCounts(), {
    fileCursors: 1,
    events: 1,
    occurrences: 1,
    quotaSnapshots: 0,
    lastGoodSources: 0,
  });
});

test("same-path replacement and truncation clear only invalid source state", async (context) => {
  const { store } = await temporaryStore(context);
  const oldIdentity = { deviceId: "16777234", inode: "100" };
  const newIdentity = { deviceId: "16777234", inode: "101" };
  const path = "/private/session/active/reused.jsonl";

  store.upsertFileCursor(cursor(oldIdentity, path));
  store.replaceOccurrences(oldIdentity, [
    occurrence("old-line", "old-global-event"),
  ]);

  const replacement = store.upsertFileCursor(cursor(newIdentity, path, 80n));
  assert.equal(replacement.replacedSource, true);
  assert.equal(replacement.reset, false);
  assert.equal(store.loadFileCursor(oldIdentity), undefined);
  assert.deepEqual(store.getCounts(), {
    fileCursors: 1,
    events: 0,
    occurrences: 0,
    quotaSnapshots: 0,
    lastGoodSources: 0,
  });

  store.replaceOccurrences(newIdentity, [
    occurrence("new-line", "new-global-event"),
  ]);
  const truncated = store.upsertFileCursor(cursor(newIdentity, path, 10n, 0n));
  assert.equal(truncated.reset, true);
  assert.equal(store.loadOccurrences(newIdentity).length, 0);
  assert.equal(store.getCounts().events, 0);
});

test("event content is globally deduplicated while occurrences retain sources", async (context) => {
  const { store } = await temporaryStore(context);
  const first = { deviceId: "1", inode: "11" };
  const second = { deviceId: "1", inode: "12" };
  store.upsertFileCursor(cursor(first, "/private/session/first.jsonl"));
  store.upsertFileCursor(cursor(second, "/private/session/second.jsonl"));

  store.replaceOccurrences(first, [
    occurrence("first-copy", "copied-parent-event", {
      model: "fixture",
      delta: { outputTokens: 5, inputTokens: 10 },
    }),
  ]);
  store.replaceOccurrences(second, [
    occurrence("second-copy", "copied-parent-event", {
      delta: { inputTokens: 10, outputTokens: 5 },
      model: "fixture",
    }),
  ]);

  assert.deepEqual(store.getCounts(), {
    fileCursors: 2,
    events: 1,
    occurrences: 2,
    quotaSnapshots: 0,
    lastGoodSources: 0,
  });
  assert.equal(
    store.loadOccurrences(first)[0]?.eventId,
    store.loadOccurrences(second)[0]?.eventId,
  );

  assert.equal(store.removeMissingSources([first]), 1);
  assert.equal(store.getCounts().events, 1);
  assert.equal(store.getCounts().occurrences, 1);

  assert.equal(store.removeMissingSources([]), 1);
  assert.equal(store.getCounts().events, 0);
  assert.equal(store.getCounts().occurrences, 0);
});

test("occurrence replacement is atomic when global event content conflicts", async (context) => {
  const { store } = await temporaryStore(context);
  const first = { deviceId: "1", inode: "21" };
  const second = { deviceId: "1", inode: "22" };
  store.upsertFileCursor(cursor(first, "/private/session/a.jsonl"));
  store.upsertFileCursor(cursor(second, "/private/session/b.jsonl"));
  store.replaceOccurrences(first, [
    occurrence("source-a", "same-key", { inputTokens: 10 }),
  ]);
  store.replaceOccurrences(second, [
    occurrence("source-b-before", "other-key", { inputTokens: 1 }),
  ]);

  assert.throws(
    () =>
      store.replaceOccurrences(second, [
        occurrence("source-b-after", "same-key", { inputTokens: 999 }),
      ]),
    /Conflicting content for global event key/u,
  );

  assert.equal(
    store.loadOccurrences(second)[0]?.occurrenceKey,
    "source-b-before",
  );
  assert.deepEqual(store.getCounts(), {
    fileCursors: 2,
    events: 2,
    occurrences: 2,
    quotaSnapshots: 0,
    lastGoodSources: 0,
  });
});

test("migration, meta, quota, and last-good values survive reopen", async (context) => {
  const directory = await mkdtemp(join(tmpdir(), "quotaarc-store-reopen-"));
  const databasePath = join(directory, "collector.sqlite");
  context.after(async () => {
    await rm(directory, { recursive: true, force: true });
  });

  const first = openCollectorStore(databasePath, { now: () => fixedNow });
  assert.equal(first.schemaVersion, STORE_SCHEMA_VERSION);
  assert.equal(
    first.database.prepare("PRAGMA journal_mode").get()?.journal_mode,
    "wal",
  );
  assert.equal(
    first.database.prepare("PRAGMA foreign_keys").get()?.foreign_keys,
    1n,
  );
  assert.deepEqual(first.getAppliedMigrations(), [
    { version: 1, appliedAt: fixedNow.toISOString() },
  ]);
  first.setMeta("parser.build", { version: 1 });
  first.upsertFileCursor(
    cursor(
      { deviceId: "16777234", inode: "501" },
      "/private/session/persisted.jsonl",
    ),
  );
  first.saveOfficialQuotaSnapshot("2026-07-19T11:58:00Z", {
    limits: [{ limitId: "codex" }],
  });
  first.saveLastGood("quota", "2026-07-19T11:58:00Z", {
    limits: [{ limitId: "codex" }],
  });
  assert.throws(
    () =>
      first.transaction(() => {
        first.setMeta("rolled.back", true);
        throw new Error("rollback fixture");
      }),
    /rollback fixture/u,
  );
  assert.equal(first.getMeta("rolled.back"), undefined);
  first.close();

  const reopened = openCollectorStore(databasePath, { now: () => fixedNow });
  context.after(() => reopened.close());
  assert.equal(reopened.schemaVersion, STORE_SCHEMA_VERSION);
  assert.equal(reopened.getAppliedMigrations().length, 1);
  assert.deepEqual(reopened.getMeta("parser.build"), { version: 1 });
  assert.equal(reopened.listFileCursors().length, 1);
  assert.deepEqual(reopened.loadLatestOfficialQuotaSnapshot()?.value, {
    limits: [{ limitId: "codex" }],
  });
  assert.deepEqual(reopened.loadLastGood("quota")?.value, {
    limits: [{ limitId: "codex" }],
  });
  assert.deepEqual(reopened.getCounts(), {
    fileCursors: 1,
    events: 0,
    occurrences: 0,
    quotaSnapshots: 1,
    lastGoodSources: 1,
  });
});

test("database and live SQLite sidecars are restricted to 0600", async (context) => {
  const { databasePath, store } = await temporaryStore(context);
  store.setMeta("permissions.fixture", true);

  for (const artifactPath of [
    databasePath,
    `${databasePath}-wal`,
    `${databasePath}-shm`,
  ]) {
    const artifact = await stat(artifactPath);
    assert.equal(
      artifact.mode & 0o777,
      0o600,
      `${artifactPath} should be private`,
    );
  }
});

test("store rejects and does not chmod a non-private parent directory", async (context) => {
  const directory = await mkdtemp(join(tmpdir(), "quotaarc-store-public-"));
  await chmod(directory, 0o755);
  context.after(async () => {
    await rm(directory, { recursive: true, force: true });
  });
  const databasePath = join(directory, "collector.sqlite");

  assert.throws(
    () => openCollectorStore(databasePath),
    (error: unknown) => {
      assert.ok(error instanceof CollectorStoreSecurityError);
      assert.equal(error.code, "PARENT_DIRECTORY_NOT_PRIVATE");
      return true;
    },
  );
  assert.equal((await stat(directory)).mode & 0o777, 0o755);
  assert.deepEqual(await readdir(directory), []);
});

test("store does not create a missing parent directory", async (context) => {
  const directory = await mkdtemp(join(tmpdir(), "quotaarc-store-parent-"));
  context.after(async () => {
    await rm(directory, { recursive: true, force: true });
  });
  const missingParent = join(directory, "not-created");

  assert.throws(
    () => openCollectorStore(join(missingParent, "collector.sqlite")),
    (error: unknown) => {
      assert.ok(error instanceof CollectorStoreSecurityError);
      assert.equal(error.code, "PARENT_DIRECTORY_MISSING");
      return true;
    },
  );
  assert.deepEqual(await readdir(directory), []);
});

test(
  "concurrent processes serialize the first migration and record it once",
  { timeout: 30_000 },
  async (context) => {
    const directory = await mkdtemp(join(tmpdir(), "quotaarc-store-race-"));
    const databasePath = join(directory, "collector.sqlite");
    context.after(async () => {
      await rm(directory, { recursive: true, force: true });
    });

    const moduleUrl = new URL(
      "../../src/store/index.js",
      import.meta.url,
    ).href;
    const startAt = Date.now() + 1_000;
    const results = await Promise.all(
      Array.from({ length: 12 }, () =>
        openStoreInWorker(moduleUrl, databasePath, startAt),
      ),
    );

    for (const result of results) {
      assert.equal(
        result.code,
        0,
        `worker failed with signal ${String(result.signal)}:\n${result.stderr}`,
      );
    }

    const store = openCollectorStore(databasePath);
    context.after(() => store.close());
    assert.equal(store.schemaVersion, STORE_SCHEMA_VERSION);
    assert.deepEqual(
      store.getAppliedMigrations().map(({ version }) => version),
      [STORE_SCHEMA_VERSION],
    );
  },
);

test("timestamp validation rejects normalized but impossible calendar values", async (context) => {
  const { store } = await temporaryStore(context);
  for (const timestamp of [
    "2026-02-29T12:00:00Z",
    "2026-04-31T12:00:00Z",
    "2026-07-19T24:00:00Z",
    "2026-07-19T12:00:00+14:01",
    "2026-07-19T12:00:00+15:00",
  ]) {
    assert.throws(
      () => store.saveOfficialQuotaSnapshot(timestamp, { fixture: true }),
      /must be an ISO-8601 timestamp with timezone/u,
    );
  }
  assert.equal(store.getCounts().quotaSnapshots, 0);

  const valid = store.saveOfficialQuotaSnapshot(
    "2024-02-29T12:00:00+14:00",
    { fixture: true },
  );
  assert.equal(valid.collectedAt, "2024-02-29T12:00:00+14:00");
});
