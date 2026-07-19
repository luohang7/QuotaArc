export const SESSION_PARSER_VERSION = 2;

export type TokenCounterName =
  | "inputTokens"
  | "cachedInputTokens"
  | "outputTokens"
  | "reasoningOutputTokens"
  | "totalTokens"
  | "cacheWriteInputTokens";

/**
 * A missing field is intentionally different from zero. Codex has added
 * counters over time, and treating an absent field as a zero can manufacture a
 * counter reset when an older/newer record pair is parsed.
 */
export type TokenCounters = Partial<Record<TokenCounterName, number>>;

/**
 * canonicalPath is private Collector state. Device-facing code must use
 * safeBasename (or a separately configured alias), never canonicalPath.
 */
export interface ProjectIdentity {
  canonicalPath: string;
  safeBasename: string;
}

export interface JsonlCursor {
  /** Bytes supplied to the parser, including an incomplete final line. */
  readOffset: number;
  /** Last byte offset ending at a newline; safe when pending chunks are dropped. */
  committedOffset: number;
  /** Number of complete physical lines consumed. */
  lineNumber: number;
  /**
   * Serializable base64 chunks for an incomplete final line. Keeping chunks
   * separate avoids repeatedly boxing/copying every pending byte on append.
   */
  pendingChunks: string[];
  pendingByteLength: number;
}

export interface JsonlRecord {
  value: unknown;
  lineNumber: number;
  byteStart: number;
  byteEnd: number;
}

export type ParserDiagnosticCode =
  | "invalid_json"
  | "invalid_utf8"
  | "invalid_record";

export interface ParserDiagnostic {
  code: ParserDiagnosticCode;
  lineNumber: number;
  byteOffset: number;
  /**
   * Deliberately contains no source-line text: source lines can contain prompts,
   * messages, tool arguments, or paths.
   */
  message: string;
}

export interface JsonlChunkResult {
  records: JsonlRecord[];
  diagnostics: ParserDiagnostic[];
  cursor: JsonlCursor;
  skippedLines: number;
}

export interface CounterLaneState {
  absolute: TokenCounters;
  segment: number;
}

export interface SessionContextState {
  threadId: string | null;
  parentThreadId: string | null;
  provider: string;
  turnId: string | null;
  model: string | null;
  project: ProjectIdentity | null;
  initialProject: ProjectIdentity | null;
}

export interface SessionIndexerState {
  parserVersion: number;
  cursor: JsonlCursor;
  context: SessionContextState;
  counterLanes: Record<string, CounterLaneState>;
}

export type IndexedEventKind =
  | "session_meta"
  | "turn_context"
  | "task_started"
  | "token_count";

export interface IndexedEventBase {
  kind: IndexedEventKind;
  timestamp: string | null;
  threadId: string | null;
  parentThreadId: string | null;
  turnId: string | null;
  provider: string;
  model: string | null;
  project: ProjectIdentity | null;
  eventIndex: number;
  lineNumber: number;
  /**
   * Thread-scoped identity for normal incremental/rescan deduplication.
   */
  fingerprint: string;
  /**
   * Content identity without threadId or physical eventIndex, used to recognize
   * copied parent history whose line positions shifted in a fork. Consumers
   * opt into this more aggressive dedupe explicitly.
   */
  replayFingerprint: string;
}

export interface SessionMetaEvent extends IndexedEventBase {
  kind: "session_meta";
}

export interface TurnContextEvent extends IndexedEventBase {
  kind: "turn_context";
}

export interface TaskStartedEvent extends IndexedEventBase {
  kind: "task_started";
}

export interface TokenCountEvent extends IndexedEventBase {
  kind: "token_count";
  absolute: TokenCounters;
  delta: TokenCounters;
  counterSegment: number;
  counterLane: string;
  counterReset: boolean;
}

export type IndexedSessionEvent =
  | SessionMetaEvent
  | TurnContextEvent
  | TaskStartedEvent
  | TokenCountEvent;

export interface SessionChunkResult {
  events: IndexedSessionEvent[];
  diagnostics: ParserDiagnostic[];
  state: SessionIndexerState;
  skippedLines: number;
}

export interface UsageTotals {
  newInputTokens: number;
  cachedInputTokens: number;
  outputTokens: number;
  reasoningTokens: number;
  processedTokens: number;
  totalTokens: number;
  cacheWriteInputTokens: number;
  tokenEvents: number;
}

export interface ProviderUsage {
  provider: string;
  totals: UsageTotals;
}

export interface ModelUsage {
  provider: string;
  model: string;
  totals: UsageTotals;
}

export interface ProjectUsage {
  project: ProjectIdentity;
  totals: UsageTotals;
}

export interface DayUsage {
  day: string;
  totals: UsageTotals;
}

export interface UsageAggregation {
  totals: UsageTotals;
  providers: ProviderUsage[];
  models: ModelUsage[];
  projects: ProjectUsage[];
  days: DayUsage[];
  duplicatesSkipped: number;
  acceptedFingerprints: string[];
}
