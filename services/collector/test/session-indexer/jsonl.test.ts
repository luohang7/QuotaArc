import assert from "node:assert/strict";
import test from "node:test";
import { TextEncoder } from "node:util";

import {
  createJsonlCursor,
  parseJsonlChunk,
  toSafeRestartCursor,
} from "../../src/session-indexer/index.js";

const utf8 = new TextEncoder();

test("does not diagnose an incomplete final JSON object", () => {
  const first = parseJsonlChunk(
    utf8.encode('{"value":1}\n{"value":'),
    createJsonlCursor(),
  );
  assert.equal(first.records.length, 1);
  assert.equal(first.diagnostics.length, 0);
  assert.ok(first.cursor.pendingByteLength > 0);
  assert.ok(first.cursor.pendingChunks.length > 0);

  const second = parseJsonlChunk(utf8.encode("2}\n"), first.cursor);
  assert.deepEqual(second.records.map((record) => record.value), [
    { value: 2 },
  ]);
  assert.equal(second.cursor.pendingByteLength, 0);
  assert.equal(second.cursor.pendingChunks.length, 0);
});

test("safe restart cursor rewinds only the incomplete line", () => {
  const parsed = parseJsonlChunk(
    utf8.encode('{"value":1}\n{"pending":'),
    createJsonlCursor(),
  );
  const restart = toSafeRestartCursor(parsed.cursor);
  assert.equal(restart.readOffset, parsed.cursor.committedOffset);
  assert.deepEqual(restart.pendingChunks, []);
  assert.equal(restart.pendingByteLength, 0);
  assert.equal(restart.lineNumber, 1);
});

test("rejects a chunk offset that would create a gap or replay", () => {
  assert.throws(
    () =>
      parseJsonlChunk(utf8.encode("{}\n"), createJsonlCursor(), {
        startOffset: 1,
      }),
    /expected 0/u,
  );
});
