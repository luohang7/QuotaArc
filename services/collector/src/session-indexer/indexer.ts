import { createHash } from "node:crypto";

import { eventFingerprint } from "./fingerprint.js";
import { createJsonlCursor, parseJsonlChunk } from "./jsonl.js";
import { normalizeProject } from "./project.js";
import {
  SESSION_PARSER_VERSION,
  type CounterLaneState,
  type IndexedEventBase,
  type IndexedEventKind,
  type IndexedSessionEvent,
  type ParserDiagnostic,
  type SessionChunkResult,
  type SessionContextState,
  type SessionIndexerState,
  type TokenCounters,
  type TokenCountEvent,
  type TokenCounterName,
} from "./types.js";

type JsonObject = Record<string, unknown>;

const COUNTER_ALIASES: Record<TokenCounterName, string[]> = {
  inputTokens: ["input_tokens", "inputTokens", "prompt_tokens"],
  cachedInputTokens: ["cached_input_tokens", "cachedInputTokens"],
  outputTokens: ["output_tokens", "outputTokens", "completion_tokens"],
  reasoningOutputTokens: [
    "reasoning_output_tokens",
    "reasoningOutputTokens",
  ],
  totalTokens: ["total_tokens", "totalTokens"],
  cacheWriteInputTokens: [
    "cache_write_input_tokens",
    "cacheWriteInputTokens",
  ],
};

const RELEVANT_LINE =
  /"type"\s*:\s*"(?:session_meta|turn_context|task_started|token_count)"/u;

export interface CreateSessionIndexerOptions {
  initialProvider?: string;
}

export function createSessionIndexerState(
  options: CreateSessionIndexerOptions = {},
): SessionIndexerState {
  return {
    parserVersion: SESSION_PARSER_VERSION,
    cursor: createJsonlCursor(),
    context: {
      threadId: null,
      parentThreadId: null,
      provider: normalizeLabel(options.initialProvider) ?? "unknown",
      turnId: null,
      model: null,
      project: null,
      initialProject: null,
    },
    counterLanes: {},
  };
}

export function indexSessionChunk(
  chunk: Uint8Array,
  state: SessionIndexerState,
  startOffset = state.cursor.readOffset,
): SessionChunkResult {
  if (state.parserVersion !== SESSION_PARSER_VERSION) {
    throw new Error(
      `Parser state version ${state.parserVersion} is not supported by ${SESSION_PARSER_VERSION}`,
    );
  }

  const parsed = parseJsonlChunk(chunk, state.cursor, {
    startOffset,
    shouldParseLine: (line) => RELEVANT_LINE.test(line),
  });
  const next: SessionIndexerState = {
    parserVersion: state.parserVersion,
    cursor: parsed.cursor,
    context: cloneContext(state.context),
    counterLanes: { ...state.counterLanes },
  };
  const diagnostics = [...parsed.diagnostics];
  const events: IndexedSessionEvent[] = [];

  for (const record of parsed.records) {
    const row = asObject(record.value);
    const payload = asObject(row?.payload);
    const rowType = asString(row?.type);
    if (!row || !payload || !rowType) {
      diagnostics.push(invalidRecord(record.lineNumber, record.byteStart));
      continue;
    }

    const timestamp =
      normalizeLabel(row.timestamp) ?? normalizeLabel(payload.timestamp);
    const eventIndex =
      asNonNegativeInteger(payload.event_index) ??
      asNonNegativeInteger(payload.eventIndex) ??
      record.lineNumber - 1;

    if (rowType === "session_meta") {
      applySessionMeta(next.context, payload);
      events.push(
        makeBaseEvent("session_meta", next.context, timestamp, eventIndex, record.lineNumber),
      );
      continue;
    }

    if (rowType === "turn_context") {
      applyTurnContext(next.context, payload);
      events.push(
        makeBaseEvent("turn_context", next.context, timestamp, eventIndex, record.lineNumber),
      );
      continue;
    }

    if (rowType !== "event_msg") {
      continue;
    }

    const payloadType = asString(payload.type);
    if (payloadType !== "task_started" && payloadType !== "token_count") {
      continue;
    }

    applyEventContext(next.context, payload);
    if (payloadType === "task_started") {
      events.push(
        makeBaseEvent("task_started", next.context, timestamp, eventIndex, record.lineNumber),
      );
      continue;
    }

    const absolute = extractAbsoluteCounters(payload);
    if (!absolute) {
      diagnostics.push(invalidRecord(record.lineNumber, record.byteStart));
      continue;
    }

    const explicitLane =
      normalizeLabel(payload.counter_id) ??
      normalizeLabel(payload.counterId) ??
      normalizeLabel(payload.lineage_id) ??
      normalizeLabel(asObject(payload.info)?.counter_id) ??
      "thread";
    const lane = laneKey(next.context, explicitLane);
    const previous = next.counterLanes[lane];
    const { delta, reset } = counterDelta(absolute, previous?.absolute);
    const laneState: CounterLaneState = {
      // Optional counters can disappear in an older/intermediate row and then
      // return. Preserve their last absolute baseline unless the lane reset.
      absolute:
        previous && !reset
          ? { ...previous.absolute, ...absolute }
          : { ...absolute },
      segment: previous ? previous.segment + (reset ? 1 : 0) : 0,
    };
    next.counterLanes[lane] = laneState;

    const base = makeBaseEvent(
      "token_count",
      next.context,
      timestamp,
      eventIndex,
      record.lineNumber,
      absolute,
    );
    const tokenEvent: TokenCountEvent = {
      ...base,
      kind: "token_count",
      absolute,
      delta,
      counterSegment: laneState.segment,
      counterLane: lane,
      counterReset: reset,
    };
    events.push(tokenEvent);
  }

  return {
    events,
    diagnostics,
    state: next,
    skippedLines: parsed.skippedLines,
  };
}

export function counterDelta(
  current: TokenCounters,
  previous?: TokenCounters,
): { delta: TokenCounters; reset: boolean } {
  if (!previous) {
    return { delta: { ...current }, reset: false };
  }

  const reset = (Object.keys(current) as TokenCounterName[]).some((key) => {
    const before = previous[key];
    const now = current[key];
    return before !== undefined && now !== undefined && now < before;
  });

  if (reset) {
    return { delta: { ...current }, reset: true };
  }

  const delta: TokenCounters = {};
  for (const key of Object.keys(current) as TokenCounterName[]) {
    const now = current[key];
    if (now === undefined) {
      continue;
    }
    delta[key] = now - (previous[key] ?? 0);
  }
  return { delta, reset: false };
}

function extractAbsoluteCounters(payload: JsonObject): TokenCounters | null {
  const info = asObject(payload.info);
  const candidates = [
    asObject(info?.total_token_usage),
    asObject(info?.totalTokenUsage),
    asObject(payload.total_token_usage),
    asObject(payload.totalTokenUsage),
    asObject(payload.token_usage),
    asObject(payload.usage),
    info,
  ];
  const source = candidates.find(
    (candidate) =>
      candidate &&
      Object.values(COUNTER_ALIASES).some((aliases) =>
        aliases.some((alias) => candidate[alias] !== undefined),
      ),
  );
  if (!source) {
    return null;
  }

  const counters: TokenCounters = {};
  for (const [target, aliases] of Object.entries(COUNTER_ALIASES) as Array<
    [TokenCounterName, string[]]
  >) {
    for (const alias of aliases) {
      const value = asCounter(source[alias]);
      if (value !== null) {
        counters[target] = value;
        break;
      }
    }
  }
  return Object.keys(counters).length > 0 ? counters : null;
}

function applySessionMeta(
  context: SessionContextState,
  payload: JsonObject,
): void {
  context.threadId =
    normalizeLabel(payload.id) ??
    normalizeLabel(payload.thread_id) ??
    normalizeLabel(payload.threadId) ??
    context.threadId;
  context.parentThreadId =
    normalizeLabel(payload.parent_thread_id) ??
    normalizeLabel(payload.parentThreadId) ??
    nestedParentThreadId(payload) ??
    context.parentThreadId;
  context.provider =
    normalizeLabel(payload.model_provider) ??
    normalizeLabel(payload.provider) ??
    context.provider;
  const project = normalizeProject(payload.cwd);
  if (project) {
    context.initialProject = project;
    context.project = project;
  }
}

function applyTurnContext(
  context: SessionContextState,
  payload: JsonObject,
): void {
  context.turnId =
    normalizeLabel(payload.turn_id) ??
    normalizeLabel(payload.turnId) ??
    context.turnId;
  context.model = normalizeLabel(payload.model) ?? context.model;
  context.provider =
    normalizeLabel(payload.model_provider) ??
    normalizeLabel(payload.provider) ??
    context.provider;
  context.project =
    normalizeProject(payload.cwd) ??
    context.project ??
    context.initialProject;
}

function applyEventContext(
  context: SessionContextState,
  payload: JsonObject,
): void {
  context.turnId =
    normalizeLabel(payload.turn_id) ??
    normalizeLabel(payload.turnId) ??
    context.turnId;
  context.model = normalizeLabel(payload.model) ?? context.model;
  context.provider =
    normalizeLabel(payload.model_provider) ??
    normalizeLabel(payload.provider) ??
    context.provider;
}

function makeBaseEvent<Kind extends IndexedEventKind>(
  kind: Kind,
  context: SessionContextState,
  timestamp: string | null,
  eventIndex: number,
  lineNumber: number,
  absolute?: TokenCounters,
): IndexedEventBase & { kind: Kind } {
  const input = {
    kind,
    timestamp,
    threadId: context.threadId,
    parentThreadId: context.parentThreadId,
    turnId: context.turnId,
    provider: context.provider,
    model: context.model,
    project: context.project,
    eventIndex,
    absolute,
  };
  return {
    ...input,
    lineNumber,
    fingerprint: eventFingerprint(input, "thread"),
    replayFingerprint: eventFingerprint(input, "replay"),
  };
}

function laneKey(context: SessionContextState, explicitLane: string): string {
  return createHash("sha256")
    .update(
      JSON.stringify([
        "quota-arc-counter-lane-v1",
        context.threadId,
        context.provider,
        explicitLane,
      ]),
    )
    .digest("hex");
}

function nestedParentThreadId(payload: JsonObject): string | null {
  const source = asObject(payload.source);
  const subagent = asObject(source?.subagent);
  const spawn =
    asObject(subagent?.thread_spawn) ?? asObject(subagent?.threadSpawn);
  const fork = asObject(source?.fork);
  return (
    normalizeLabel(spawn?.parent_thread_id) ??
    normalizeLabel(spawn?.parentThreadId) ??
    normalizeLabel(fork?.parent_thread_id) ??
    normalizeLabel(fork?.parentThreadId)
  );
}

function cloneContext(context: SessionContextState): SessionContextState {
  return {
    ...context,
    project: context.project ? { ...context.project } : null,
    initialProject: context.initialProject
      ? { ...context.initialProject }
      : null,
  };
}

function invalidRecord(
  lineNumber: number,
  byteOffset: number,
): ParserDiagnostic {
  return {
    code: "invalid_record",
    lineNumber,
    byteOffset,
    message: "Relevant JSONL record has an unsupported shape",
  };
}

function asObject(value: unknown): JsonObject | null {
  return value !== null && typeof value === "object" && !Array.isArray(value)
    ? (value as JsonObject)
    : null;
}

function asString(value: unknown): string | null {
  return typeof value === "string" ? value : null;
}

function normalizeLabel(value: unknown): string | null {
  if (typeof value !== "string") {
    return null;
  }
  const normalized = value
    .replace(/[\u0000-\u001f\u007f]/gu, "")
    .trim();
  return normalized ? normalized.slice(0, 256) : null;
}

function asCounter(value: unknown): number | null {
  return typeof value === "number" &&
    Number.isSafeInteger(value) &&
    value >= 0
    ? value
    : null;
}

function asNonNegativeInteger(value: unknown): number | null {
  return typeof value === "number" &&
    Number.isSafeInteger(value) &&
    value >= 0
    ? value
    : null;
}
