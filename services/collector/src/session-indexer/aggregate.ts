import type {
  IndexedSessionEvent,
  TokenCountEvent,
  UsageAggregation,
  UsageTotals,
} from "./types.js";

export interface AggregateOptions {
  dedupe?: "thread" | "replay" | "none";
  seenFingerprints?: Iterable<string>;
}

export function aggregateSessionEvents(
  events: Iterable<IndexedSessionEvent>,
  options: AggregateOptions = {},
): UsageAggregation {
  const dedupe = options.dedupe ?? "thread";
  const seen = new Set(options.seenFingerprints ?? []);
  const acceptedFingerprints: string[] = [];
  const totals = zeroTotals();
  const providers = new Map<string, UsageTotals>();
  const models = new Map<string, { provider: string; model: string; totals: UsageTotals }>();
  const projects = new Map<string, { project: NonNullable<TokenCountEvent["project"]>; totals: UsageTotals }>();
  const days = new Map<string, UsageTotals>();
  let duplicatesSkipped = 0;

  for (const event of events) {
    if (event.kind !== "token_count") {
      continue;
    }
    const fingerprint =
      dedupe === "replay" ? event.replayFingerprint : event.fingerprint;
    if (dedupe !== "none" && seen.has(fingerprint)) {
      duplicatesSkipped += 1;
      continue;
    }
    if (dedupe !== "none") {
      seen.add(fingerprint);
      acceptedFingerprints.push(fingerprint);
    }

    const value = usageFromDelta(event);
    addTotals(totals, value);
    addTotals(mapTotals(providers, event.provider), value);

    const model = event.model ?? "unknown";
    const modelKey = JSON.stringify([event.provider, model]);
    const modelGroup = models.get(modelKey) ?? {
      provider: event.provider,
      model,
      totals: zeroTotals(),
    };
    addTotals(modelGroup.totals, value);
    models.set(modelKey, modelGroup);

    if (event.project) {
      const projectGroup = projects.get(event.project.canonicalPath) ?? {
        project: event.project,
        totals: zeroTotals(),
      };
      addTotals(projectGroup.totals, value);
      projects.set(event.project.canonicalPath, projectGroup);
    }

    const day = event.timestamp?.match(/^\d{4}-\d{2}-\d{2}/u)?.[0] ?? "unknown";
    addTotals(mapTotals(days, day), value);
  }

  return {
    totals,
    providers: [...providers].map(([provider, groupTotals]) => ({
      provider,
      totals: groupTotals,
    })).sort((a, b) => a.provider.localeCompare(b.provider)),
    models: [...models.values()].sort((a, b) =>
      `${a.provider}\0${a.model}`.localeCompare(`${b.provider}\0${b.model}`),
    ),
    projects: [...projects.values()].sort((a, b) =>
      a.project.canonicalPath.localeCompare(b.project.canonicalPath),
    ),
    days: [...days].map(([day, groupTotals]) => ({
      day,
      totals: groupTotals,
    })).sort((a, b) => a.day.localeCompare(b.day)),
    duplicatesSkipped,
    acceptedFingerprints,
  };
}

export function usageFromDelta(event: TokenCountEvent): UsageTotals {
  const input = event.delta.inputTokens ?? 0;
  const cached = event.delta.cachedInputTokens ?? 0;
  const output = event.delta.outputTokens ?? 0;
  const newInput = Math.max(input - cached, 0);
  return {
    newInputTokens: newInput,
    cachedInputTokens: cached,
    outputTokens: output,
    reasoningTokens: event.delta.reasoningOutputTokens ?? 0,
    processedTokens: newInput + cached + output,
    totalTokens: event.delta.totalTokens ?? input + output,
    cacheWriteInputTokens: event.delta.cacheWriteInputTokens ?? 0,
    tokenEvents: 1,
  };
}

function zeroTotals(): UsageTotals {
  return {
    newInputTokens: 0,
    cachedInputTokens: 0,
    outputTokens: 0,
    reasoningTokens: 0,
    processedTokens: 0,
    totalTokens: 0,
    cacheWriteInputTokens: 0,
    tokenEvents: 0,
  };
}

function mapTotals(
  map: Map<string, UsageTotals>,
  key: string,
): UsageTotals {
  const totals = map.get(key) ?? zeroTotals();
  map.set(key, totals);
  return totals;
}

function addTotals(target: UsageTotals, value: UsageTotals): void {
  for (const key of Object.keys(target) as Array<keyof UsageTotals>) {
    target[key] += value[key];
  }
}
