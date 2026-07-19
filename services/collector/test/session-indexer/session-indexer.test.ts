import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";

import {
  aggregateSessionEvents,
  createSessionIndexerState,
  eventFingerprint,
  indexSessionChunk,
  type IndexedSessionEvent,
  type SessionIndexerState,
  type TokenCountEvent,
} from "../../src/session-indexer/index.js";

async function fixture(name: string): Promise<Uint8Array> {
  return readFile(
    path.resolve(process.cwd(), "test", "fixtures", "sessions", name),
  );
}

function tokens(events: IndexedSessionEvent[]): TokenCountEvent[] {
  return events.filter(
    (event): event is TokenCountEvent => event.kind === "token_count",
  );
}

test("continues from byte cursor and buffers an incomplete final line", async () => {
  const bytes = await fixture("basic.jsonl");
  const marker = Buffer.from('"event_index":5');
  const markerOffset = Buffer.from(bytes).indexOf(marker);
  assert.ok(markerOffset > 0);
  const split = markerOffset + 12;

  const first = indexSessionChunk(
    bytes.subarray(0, split),
    createSessionIndexerState(),
  );
  assert.equal(tokens(first.events).length, 1);
  assert.deepEqual(
    first.events.map((event) => event.kind),
    ["session_meta", "turn_context", "task_started", "token_count"],
  );
  assert.ok(first.state.cursor.pendingByteLength > 0);
  assert.ok(
    first.state.cursor.committedOffset < first.state.cursor.readOffset,
  );

  const persistedState = JSON.parse(
    JSON.stringify(first.state),
  ) as SessionIndexerState;
  const second = indexSessionChunk(
    bytes.subarray(split),
    persistedState,
    split,
  );
  const secondToken = tokens(second.events)[0];
  assert.ok(secondToken);
  assert.deepEqual(secondToken.delta, {
    inputTokens: 60,
    cachedInputTokens: 10,
    outputTokens: 10,
    reasoningOutputTokens: 2,
    totalTokens: 70,
    cacheWriteInputTokens: 6,
  });
  assert.equal(second.state.cursor.pendingByteLength, 0);
  assert.equal(second.state.cursor.committedOffset, bytes.byteLength);
  assert.equal(second.diagnostics.length, 0);
});

test("computes pure usage totals and deduplicates a repeated scan", async () => {
  const bytes = await fixture("basic.jsonl");
  const first = indexSessionChunk(bytes, createSessionIndexerState());
  const repeated = indexSessionChunk(bytes, createSessionIndexerState());
  const aggregate = aggregateSessionEvents(
    [...first.events, ...repeated.events],
    { dedupe: "thread" },
  );

  assert.deepEqual(aggregate.totals, {
    newInputTokens: 130,
    cachedInputTokens: 30,
    outputTokens: 20,
    reasoningTokens: 4,
    processedTokens: 180,
    totalTokens: 180,
    cacheWriteInputTokens: 6,
    tokenEvents: 2,
  });
  assert.equal(aggregate.duplicatesSkipped, 2);
  assert.deepEqual(
    aggregate.providers.map((group) => group.provider),
    ["openai"],
  );
  assert.equal(aggregate.projects[0]?.project.safeBasename, "alpha");
  assert.equal("cwd" in first.events[0]!, false);
  assert.ok(first.skippedLines >= 1);
});

test("starts a new segment when an absolute counter decreases", async () => {
  const result = indexSessionChunk(
    await fixture("reset.jsonl"),
    createSessionIndexerState(),
  );
  const events = tokens(result.events);
  assert.equal(events[0]?.counterSegment, 0);
  assert.equal(events[0]?.counterReset, false);
  assert.equal(events[1]?.counterSegment, 1);
  assert.equal(events[1]?.counterReset, true);
  assert.deepEqual(events[1]?.delta, {
    inputTokens: 30,
    cachedInputTokens: 5,
    outputTokens: 3,
    totalTokens: 33,
  });
});

test("accepts old field aliases and keeps custom provider usage separate", async () => {
  const custom = indexSessionChunk(
    await fixture("custom-old-fields.jsonl"),
    createSessionIndexerState(),
  );
  const openai = indexSessionChunk(
    await fixture("reset.jsonl"),
    createSessionIndexerState(),
  );
  const aggregate = aggregateSessionEvents(
    [...custom.events, ...openai.events],
    { dedupe: "thread" },
  );

  assert.deepEqual(
    aggregate.providers.map((group) => group.provider),
    ["custom", "openai"],
  );
  assert.deepEqual(tokens(custom.events)[0]?.delta, {
    inputTokens: 50,
    outputTokens: 8,
    totalTokens: 58,
  });
});

test("preserves an optional counter baseline while the field is absent", async () => {
  const result = indexSessionChunk(
    await fixture("optional-counter-reappears.jsonl"),
    createSessionIndexerState(),
  );
  const events = tokens(result.events);

  assert.equal(events[1]?.delta.reasoningOutputTokens, undefined);
  assert.equal(events[2]?.delta.reasoningOutputTokens, 2);
  assert.equal(events[2]?.counterReset, false);
  assert.equal(events[2]?.counterSegment, 0);
});

test("offers replay fingerprint dedupe for copied parent history", async () => {
  const parent = indexSessionChunk(
    await fixture("parent.jsonl"),
    createSessionIndexerState(),
  );
  const child = indexSessionChunk(
    await fixture("fork-replay.jsonl"),
    createSessionIndexerState(),
  );
  const parentToken = tokens(parent.events)[0];
  const childTokens = tokens(child.events);

  assert.ok(parentToken);
  assert.equal(childTokens[0]?.threadId, "thread-child");
  assert.equal(childTokens[0]?.parentThreadId, "thread-parent");
  assert.notEqual(parentToken.fingerprint, childTokens[0]?.fingerprint);
  assert.equal(
    parentToken.replayFingerprint,
    childTokens[0]?.replayFingerprint,
  );

  const aggregate = aggregateSessionEvents(
    [...parent.events, ...child.events],
    { dedupe: "replay" },
  );
  assert.equal(aggregate.duplicatesSkipped, 1);
  assert.equal(aggregate.totals.processedTokens, 155);
  assert.equal(aggregate.totals.tokenEvents, 2);
});

test("replay identity survives a shifted physical event index", async () => {
  const indexed = indexSessionChunk(
    await fixture("parent.jsonl"),
    createSessionIndexerState(),
  );
  const token = tokens(indexed.events)[0];
  assert.ok(token);

  const shifted = {
    ...token,
    eventIndex: token.eventIndex + 100,
    replayFingerprint: eventFingerprint(
      {
        kind: token.kind,
        timestamp: token.timestamp,
        threadId: token.threadId,
        parentThreadId: token.parentThreadId,
        turnId: token.turnId,
        provider: token.provider,
        model: token.model,
        project: token.project,
        eventIndex: token.eventIndex + 100,
        absolute: token.absolute,
      },
      "replay",
    ),
  };
  assert.equal(shifted.replayFingerprint, token.replayFingerprint);
  const aggregate = aggregateSessionEvents([token, shifted], {
    dedupe: "replay",
  });
  assert.equal(aggregate.duplicatesSkipped, 1);
  assert.equal(aggregate.totals.tokenEvents, 1);
});
