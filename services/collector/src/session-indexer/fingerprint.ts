import { createHash } from "node:crypto";

import type {
  IndexedEventKind,
  ProjectIdentity,
  TokenCounters,
} from "./types.js";

export interface FingerprintInput {
  kind: IndexedEventKind;
  timestamp: string | null;
  threadId: string | null;
  parentThreadId: string | null;
  turnId: string | null;
  provider: string;
  model: string | null;
  project: ProjectIdentity | null;
  eventIndex: number;
  absolute: TokenCounters | undefined;
}

export function eventFingerprint(
  input: FingerprintInput,
  scope: "thread" | "replay" = "thread",
): string {
  const identity = [
    "quota-arc-session-event-v1",
    scope,
    input.kind,
    scope === "thread" ? input.threadId : null,
    input.turnId,
    // Physical line positions can shift when Codex copies parent history into
    // a fork. Keep eventIndex in the normal identity, but omit it from the
    // replay-only identity used across files.
    scope === "thread" ? input.eventIndex : null,
    input.timestamp,
    input.provider,
    input.model,
    // Project paths are intentionally excluded. A move/archive/worktree
    // normalization change must not change event identity.
    orderedCounters(input.absolute),
  ];

  return createHash("sha256")
    .update(JSON.stringify(identity))
    .digest("hex");
}

function orderedCounters(
  counters: TokenCounters | undefined,
): Array<[string, number]> | null {
  if (!counters) {
    return null;
  }

  return Object.entries(counters)
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([key, value]) => [key, value]);
}
