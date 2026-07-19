import { TextDecoder } from "node:util";

import type {
  JsonlChunkResult,
  JsonlCursor,
  ParserDiagnostic,
} from "./types.js";

const utf8 = new TextDecoder("utf-8", { fatal: true });

export interface ParseJsonlOptions {
  /**
   * Absolute byte offset at which chunk starts. It must match readOffset so a
   * caller cannot silently skip or replay bytes into an existing parser state.
   */
  startOffset?: number;
  /**
   * Allows the session indexer to avoid JSON.parse for prompt/message records.
   * The predicate must not retain the supplied source text.
   */
  shouldParseLine?: (line: string) => boolean;
}

export function createJsonlCursor(): JsonlCursor {
  return {
    readOffset: 0,
    committedOffset: 0,
    lineNumber: 0,
    pendingChunks: [],
    pendingByteLength: 0,
  };
}

/**
 * Drop buffered final-line bytes and return the safe restart cursor. The caller
 * must read again from committedOffset.
 */
export function toSafeRestartCursor(cursor: JsonlCursor): JsonlCursor {
  return {
    readOffset: cursor.committedOffset,
    committedOffset: cursor.committedOffset,
    lineNumber: cursor.lineNumber,
    pendingChunks: [],
    pendingByteLength: 0,
  };
}

/**
 * Parse a byte chunk without treating an incomplete final line as an error.
 * The returned cursor is JSON-serializable and can resume at readOffset.
 */
export function parseJsonlChunk(
  chunk: Uint8Array,
  cursor: JsonlCursor,
  options: ParseJsonlOptions = {},
): JsonlChunkResult {
  assertCursor(cursor);

  const startOffset = options.startOffset ?? cursor.readOffset;
  if (startOffset !== cursor.readOffset) {
    throw new RangeError(
      `JSONL chunk starts at ${startOffset}, expected ${cursor.readOffset}`,
    );
  }

  const records: JsonlChunkResult["records"] = [];
  const diagnostics: ParserDiagnostic[] = [];
  let lineNumber = cursor.lineNumber;
  let skippedLines = 0;
  const newlineOffsets: number[] = [];

  for (let index = 0; index < chunk.length; index += 1) {
    if (chunk[index] === 0x0a) {
      newlineOffsets.push(index);
    }
  }

  const parseCompleteLine = (
    untrimmedLineBytes: Uint8Array,
    byteStart: number,
    byteEnd: number,
  ): void => {
    lineNumber += 1;
    let lineBytes = untrimmedLineBytes;
    if (
      lineBytes.length > 0 &&
      lineBytes[lineBytes.length - 1] === 0x0d
    ) {
      lineBytes = lineBytes.subarray(0, lineBytes.length - 1);
    }

    if (lineBytes.length === 0) {
      skippedLines += 1;
      return;
    }

    let line: string;
    try {
      line = utf8.decode(lineBytes);
    } catch {
      diagnostics.push({
        code: "invalid_utf8",
        lineNumber,
        byteOffset: byteStart,
        message: "Complete JSONL line is not valid UTF-8",
      });
      return;
    }

    if (options.shouldParseLine && !options.shouldParseLine(line)) {
      skippedLines += 1;
      return;
    }

    try {
      records.push({
        value: JSON.parse(line) as unknown,
        lineNumber,
        byteStart,
        byteEnd,
      });
    } catch {
      diagnostics.push({
        code: "invalid_json",
        lineNumber,
        byteOffset: byteStart,
        message: "Complete JSONL line is not valid JSON",
      });
    }
  };

  const readOffset = cursor.readOffset + chunk.byteLength;
  if (newlineOffsets.length === 0) {
    return {
      records,
      diagnostics,
      skippedLines,
      cursor: {
        readOffset,
        committedOffset: cursor.committedOffset,
        lineNumber,
        pendingChunks:
          chunk.byteLength === 0
            ? [...cursor.pendingChunks]
            : [...cursor.pendingChunks, encodePendingChunk(chunk)],
        pendingByteLength: cursor.pendingByteLength + chunk.byteLength,
      },
    };
  }

  const firstNewline = newlineOffsets[0]!;
  const firstLine = joinPendingLine(
    cursor,
    chunk.subarray(0, firstNewline),
  );
  parseCompleteLine(
    firstLine,
    cursor.committedOffset,
    cursor.readOffset + firstNewline + 1,
  );

  let previousNewline = firstNewline;
  for (let index = 1; index < newlineOffsets.length; index += 1) {
    const newline = newlineOffsets[index]!;
    parseCompleteLine(
      chunk.subarray(previousNewline + 1, newline),
      cursor.readOffset + previousNewline + 1,
      cursor.readOffset + newline + 1,
    );
    previousNewline = newline;
  }

  const pending = chunk.subarray(previousNewline + 1);
  return {
    records,
    diagnostics,
    skippedLines,
    cursor: {
      readOffset,
      committedOffset: cursor.readOffset + previousNewline + 1,
      lineNumber,
      pendingChunks:
        pending.byteLength === 0 ? [] : [encodePendingChunk(pending)],
      pendingByteLength: pending.byteLength,
    },
  };
}

function assertCursor(cursor: JsonlCursor): void {
  const values = [
    cursor.readOffset,
    cursor.committedOffset,
    cursor.lineNumber,
  ];
  if (
    values.some(
      (value) => !Number.isSafeInteger(value) || value < 0,
    ) ||
    cursor.committedOffset > cursor.readOffset ||
    !Number.isSafeInteger(cursor.pendingByteLength) ||
    cursor.pendingByteLength < 0 ||
    !Array.isArray(cursor.pendingChunks) ||
    cursor.pendingChunks.some((value) => typeof value !== "string") ||
    cursor.readOffset - cursor.committedOffset !== cursor.pendingByteLength
  ) {
    throw new TypeError("Invalid JSONL cursor");
  }
}

function encodePendingChunk(bytes: Uint8Array): string {
  return Buffer.from(
    bytes.buffer,
    bytes.byteOffset,
    bytes.byteLength,
  ).toString("base64");
}

function joinPendingLine(
  cursor: JsonlCursor,
  suffix: Uint8Array,
): Uint8Array {
  if (cursor.pendingByteLength === 0) {
    return suffix;
  }

  const chunks: Uint8Array[] = cursor.pendingChunks.map((encoded) =>
    Buffer.from(encoded, "base64")
  );
  const decodedLength = chunks.reduce(
    (total, pending) => total + pending.byteLength,
    0,
  );
  if (decodedLength !== cursor.pendingByteLength) {
    throw new TypeError("Invalid encoded JSONL cursor");
  }
  chunks.push(suffix);
  return Buffer.concat(chunks, decodedLength + suffix.byteLength);
}
