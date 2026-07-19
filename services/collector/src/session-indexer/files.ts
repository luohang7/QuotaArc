import { createHash } from "node:crypto";
import { constants } from "node:fs";
import { lstat, open, readdir } from "node:fs/promises";
import path from "node:path";
import { performance } from "node:perf_hooks";

import {
  createSessionIndexerState,
  indexSessionChunk,
} from "./indexer.js";
import type {
  ProjectIdentity,
  TokenCounters,
  TokenCountEvent,
} from "./types.js";

const DEFAULT_CHUNK_SIZE = 1024 * 1024;

export interface ScanSessionFilesOptions {
  /**
   * Directories (or individual JSONL files) to scan. Callers normally pass both
   * the active sessions and archived_sessions roots.
   */
  roots: readonly string[];
  chunkSize?: number;
}

export interface ScannedProjectIdentity {
  /** Stable private-path digest; never the canonical path itself. */
  projectKey: string;
  safeBasename: string;
}

/**
 * File source coordinates and canonical paths are intentionally absent.
 */
export interface ScannedTokenEvent {
  timestamp: string | null;
  threadId: string | null;
  parentThreadId: string | null;
  turnId: string | null;
  provider: string;
  model: string | null;
  project: ScannedProjectIdentity | null;
  eventIndex: number;
  fingerprint: string;
  replayFingerprint: string;
  absolute: TokenCounters;
  delta: TokenCounters;
  counterSegment: number;
  counterReset: boolean;
}

export interface SessionFileScanResult {
  tokenEvents: ScannedTokenEvent[];
  /** Configured roots that currently exist and are safe to scan. */
  rootsAvailable: number;
  filesCount: number;
  firstEventAt: string | null;
  lastEventAt: string | null;
  diagnosticsCount: number;
  bytesScanned: number;
}

export interface SessionScanBenchmark {
  elapsedMs: number;
  rssBeforeBytes: number;
  peakRssBytes: number;
  peakRssDeltaBytes: number;
  bytes: number;
  filesCount: number;
  tokenEventsCount: number;
  diagnosticsCount: number;
}

interface InternalScanOptions extends ScanSessionFilesOptions {
  observeRss?: () => void;
}

export async function scanSessionFiles(
  options: ScanSessionFilesOptions,
): Promise<SessionFileScanResult> {
  return scanSessionFilesInternal(options);
}

/**
 * Run the real scanner while collecting lightweight process-level metrics.
 * The helper neither prints nor persists measurements, so it can be called by
 * tests, diagnostics, or a separate benchmark command.
 */
export async function benchmarkSessionScan(
  options: ScanSessionFilesOptions,
): Promise<SessionScanBenchmark> {
  const rssBefore = process.memoryUsage().rss;
  let peakRss = rssBefore;
  const observeRss = (): void => {
    peakRss = Math.max(peakRss, process.memoryUsage().rss);
  };
  const startedAt = performance.now();
  const scan = await scanSessionFilesInternal({ ...options, observeRss });
  observeRss();

  return {
    elapsedMs: performance.now() - startedAt,
    rssBeforeBytes: rssBefore,
    peakRssBytes: peakRss,
    peakRssDeltaBytes: Math.max(0, peakRss - rssBefore),
    bytes: scan.bytesScanned,
    filesCount: scan.filesCount,
    tokenEventsCount: scan.tokenEvents.length,
    diagnosticsCount: scan.diagnosticsCount,
  };
}

async function scanSessionFilesInternal(
  options: InternalScanOptions,
): Promise<SessionFileScanResult> {
  const chunkSize = normalizeChunkSize(options.chunkSize);
  const discovery = await discoverJsonlFiles(options.roots);
  const tokenEvents: ScannedTokenEvent[] = [];
  const seen = new Set<string>();
  const projectCache = new Map<string, ScannedProjectIdentity>();
  let filesCount = 0;
  let diagnosticsCount = discovery.diagnosticsCount;
  let bytesScanned = 0;
  let firstEvent: { timestamp: string; epochMs: number } | null = null;
  let lastEvent: { timestamp: string; epochMs: number } | null = null;

  for (const filePath of discovery.files) {
    let file;
    try {
      // O_NOFOLLOW closes the final-component race between discovery and open.
      file = await open(
        filePath,
        constants.O_RDONLY | (constants.O_NOFOLLOW ?? 0),
      );
    } catch {
      diagnosticsCount += 1;
      continue;
    }

    filesCount += 1;
    let state = createSessionIndexerState();
    const buffer = Buffer.allocUnsafe(chunkSize);

    try {
      while (true) {
        const read = await file.read(buffer, 0, buffer.byteLength, null);
        if (read.bytesRead === 0) {
          break;
        }
        bytesScanned += read.bytesRead;
        const indexed = indexSessionChunk(
          buffer.subarray(0, read.bytesRead),
          state,
          state.cursor.readOffset,
        );
        state = indexed.state;
        diagnosticsCount += indexed.diagnostics.length;

        for (const event of indexed.events) {
          if (
            event.kind !== "token_count" ||
            seen.has(event.replayFingerprint)
          ) {
            continue;
          }
          // Copied parent history in a fork has a new thread id but retains its
          // turn/timestamp/counter identity. Use the replay-scoped fingerprint
          // across files so that copied history is not billed twice.
          seen.add(event.replayFingerprint);
          tokenEvents.push(sanitizeTokenEvent(event, projectCache));

          const epochMs =
            event.timestamp === null ? Number.NaN : Date.parse(event.timestamp);
          if (!Number.isFinite(epochMs) || event.timestamp === null) {
            continue;
          }
          if (!firstEvent || epochMs < firstEvent.epochMs) {
            firstEvent = { timestamp: event.timestamp, epochMs };
          }
          if (!lastEvent || epochMs > lastEvent.epochMs) {
            lastEvent = { timestamp: event.timestamp, epochMs };
          }
        }
        options.observeRss?.();
      }
      // Pending chunks deliberately remain unflushed: an incomplete final line
      // is not an event and is not a parse diagnostic.
    } catch {
      diagnosticsCount += 1;
    } finally {
      try {
        await file.close();
      } catch {
        diagnosticsCount += 1;
      }
    }
  }

  return {
    tokenEvents,
    rootsAvailable: discovery.rootsAvailable,
    filesCount,
    firstEventAt: firstEvent?.timestamp ?? null,
    lastEventAt: lastEvent?.timestamp ?? null,
    diagnosticsCount,
    bytesScanned,
  };
}

async function discoverJsonlFiles(
  roots: readonly string[],
): Promise<{
  files: string[];
  rootsAvailable: number;
  diagnosticsCount: number;
}> {
  const files: string[] = [];
  let rootsAvailable = 0;
  let diagnosticsCount = 0;
  const normalizedRoots = [...new Set(roots.map((root) => path.resolve(root)))]
    .sort(comparePaths);

  const visit = async (candidate: string): Promise<void> => {
    let status;
    try {
      status = await lstat(candidate);
    } catch (error) {
      if (!isMissing(error)) {
        diagnosticsCount += 1;
      }
      return;
    }

    if (status.isSymbolicLink()) {
      return;
    }
    if (status.isFile()) {
      if (candidate.endsWith(".jsonl")) {
        files.push(candidate);
      }
      return;
    }
    if (!status.isDirectory()) {
      return;
    }

    let entries: string[];
    try {
      entries = await readdir(candidate);
    } catch {
      diagnosticsCount += 1;
      return;
    }
    entries.sort(comparePaths);
    for (const entry of entries) {
      await visit(path.join(candidate, entry));
    }
  };

  for (const root of normalizedRoots) {
    try {
      const status = await lstat(root);
      if (
        !status.isSymbolicLink() &&
        (status.isDirectory() ||
          (status.isFile() && root.endsWith(".jsonl")))
      ) {
        rootsAvailable += 1;
      }
    } catch (error) {
      if (!isMissing(error)) {
        diagnosticsCount += 1;
      }
      continue;
    }
    await visit(root);
  }

  files.sort(comparePaths);
  return {
    files: [...new Set(files)],
    rootsAvailable,
    diagnosticsCount,
  };
}

function sanitizeTokenEvent(
  event: TokenCountEvent,
  projectCache: Map<string, ScannedProjectIdentity>,
): ScannedTokenEvent {
  return {
    timestamp: event.timestamp,
    threadId: event.threadId,
    parentThreadId: event.parentThreadId,
    turnId: event.turnId,
    provider: event.provider,
    model: event.model,
    project: event.project
      ? cachedSanitizedProject(event.project, projectCache)
      : null,
    eventIndex: event.eventIndex,
    fingerprint: event.fingerprint,
    replayFingerprint: event.replayFingerprint,
    absolute: event.absolute,
    delta: event.delta,
    counterSegment: event.counterSegment,
    counterReset: event.counterReset,
  };
}

function cachedSanitizedProject(
  project: ProjectIdentity,
  cache: Map<string, ScannedProjectIdentity>,
): ScannedProjectIdentity {
  const cached = cache.get(project.canonicalPath);
  if (cached) {
    return cached;
  }
  const sanitized = sanitizeProject(project);
  cache.set(project.canonicalPath, sanitized);
  return sanitized;
}

function sanitizeProject(project: ProjectIdentity): ScannedProjectIdentity {
  return {
    projectKey: createHash("sha256")
      .update(`quota-arc-project-v1\0${project.canonicalPath}`)
      .digest("hex"),
    safeBasename: project.safeBasename,
  };
}

function normalizeChunkSize(chunkSize: number | undefined): number {
  const value = chunkSize ?? DEFAULT_CHUNK_SIZE;
  if (!Number.isSafeInteger(value) || value <= 0 || value > 16 * 1024 * 1024) {
    throw new RangeError(
      "chunkSize must be an integer between 1 and 16777216 bytes",
    );
  }
  return value;
}

function comparePaths(left: string, right: string): number {
  return left < right ? -1 : left > right ? 1 : 0;
}

function isMissing(error: unknown): boolean {
  return (
    error !== null &&
    typeof error === "object" &&
    "code" in error &&
    error.code === "ENOENT"
  );
}
