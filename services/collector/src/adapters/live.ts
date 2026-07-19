import {
  CodexAppServerClient,
  type CodexAppServerClientOptions,
} from "../codex-rpc/client.js";
import type {
  CollectorPorts,
} from "../snapshot/index.js";
import {
  createSessionLocalUsageReader,
  type LocalUsageReader,
  type SessionScan,
} from "./local.js";
export type { LocalUsageReader } from "./local.js";
import {
  OfficialAccountAdapter,
  type OfficialAccountClientPort,
} from "./official.js";

export interface LiveCollectorPorts extends CollectorPorts {
  close(): Promise<void>;
}

export interface LiveCollectorPortsOptions {
  client?: OfficialAccountClientPort;
  clientOptions?: CodexAppServerClientOptions;
  localUsageReader?: LocalUsageReader;
  sessionRoots?: readonly string[];
  sessionScan?: SessionScan;
  now?: () => Date;
}

export function createLiveCollectorPorts(
  options: LiveCollectorPortsOptions = {},
): LiveCollectorPorts {
  const client = options.client ??
    new CodexAppServerClient(options.clientOptions);
  const official = new OfficialAccountAdapter(
    client,
    options.now ? { now: options.now } : {},
  );
  const local = options.localUsageReader ??
    (options.sessionRoots
      ? createSessionLocalUsageReader({
          roots: options.sessionRoots,
          ...(options.now ? { now: options.now } : {}),
          ...(options.sessionScan ? { scan: options.sessionScan } : {}),
        })
      : unavailableLocalUsageReader);
  let closePromise: Promise<void> | null = null;

  return {
    readQuota: () => official.readQuota(),
    readAccountUsage: () => official.readAccountUsage(),
    readLocalUsage: (period) => local.read(period),
    close() {
      closePromise ??= closeAll(official, local);
      return closePromise;
    },
  };
}

export const unavailableLocalUsageReader: LocalUsageReader = {
  async read() {
    return {
      status: "unavailable",
      code: "local_usage_reader_not_configured",
    };
  },
};

async function closeAll(
  official: OfficialAccountAdapter,
  local: LocalUsageReader,
): Promise<void> {
  const operations: Promise<void>[] = [official.close()];
  if (local.close) operations.push(local.close());
  const results = await Promise.allSettled(operations);
  if (results.some((result) => result.status === "rejected")) {
    throw new Error("collector_close_failed");
  }
}
