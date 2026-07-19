export const STORE_SCHEMA_VERSION = 1;

export interface FileIdentity {
  /**
   * Filesystem device identifier. Keep it as text so macOS bigint stat values
   * round-trip without a JavaScript precision loss.
   */
  deviceId: string;
  /** Filesystem inode, also represented as lossless text. */
  inode: string;
}

export interface FileCursorInput extends FileIdentity {
  /** Private Collector-only absolute or canonical source path. */
  path: string;
  size: bigint;
  mtimeNs: bigint;
  byteOffset: bigint;
  parserVersion: number;
  parserState: unknown;
}

export interface StoredFileCursor extends FileCursorInput {
  sourceId: bigint;
  updatedAt: string;
}

export interface CursorUpsertResult {
  cursor: StoredFileCursor;
  /** True when truncation or a parser-version change invalidated occurrences. */
  reset: boolean;
  /** True when the same path had previously referred to another device+inode. */
  replacedSource: boolean;
}

export interface NormalizedEventInput {
  /**
   * Global content identity. Callers normally use replayFingerprint when copied
   * fork history must deduplicate across source files.
   */
  eventKey: string;
  kind: string;
  occurredAt: string | null;
  payload: unknown;
}

export interface EventOccurrenceInput {
  /** Identity of the physical occurrence within this source scan. */
  occurrenceKey: string;
  lineNumber: number;
  byteOffset: bigint;
  event: NormalizedEventInput;
}

export interface StoredEventOccurrence extends EventOccurrenceInput {
  sourceId: bigint;
  eventId: bigint;
}

export type LastGoodSourceName = "quota" | "accountUsage" | "localUsage";

export interface LastGoodSource<T = unknown> {
  source: LastGoodSourceName;
  collectedAt: string;
  value: T;
  updatedAt: string;
}

export interface OfficialQuotaSnapshot<T = unknown> {
  snapshotId: bigint;
  collectedAt: string;
  value: T;
  createdAt: string;
}

export interface StoreCounts {
  fileCursors: number;
  events: number;
  occurrences: number;
  quotaSnapshots: number;
  lastGoodSources: number;
}

export interface CollectorStoreOptions {
  now?: () => Date;
  busyTimeoutMs?: number;
}
