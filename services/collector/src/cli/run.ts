import { parseArgs } from "node:util";
import {
  createLiveCollectorPorts,
  type LiveCollectorPorts,
} from "../adapters/live.js";
import { resolveCollectorConfig, type CollectorConfig } from "../config/index.js";
import { runDoctor, type DoctorReport } from "../diagnostics/index.js";
import {
  collectSnapshot,
  fixtureCollectorPorts,
  queryUsage,
  type CollectorPorts,
  type GroupBy,
  type Period,
} from "../snapshot/index.js";

export interface CliIo {
  stdout(value: string): void;
  stderr(value: string): void;
}

export interface CliDependencies {
  config?: CollectorConfig;
  io?: CliIo;
  now?: () => Date;
  doctor?: () => Promise<DoctorReport>;
  ports?: CollectorPorts;
  createLivePorts?: (config: CollectorConfig) => LiveCollectorPorts;
}

export async function runCli(
  argv: string[],
  dependencies: CliDependencies = {},
): Promise<number> {
  const io = dependencies.io ?? {
    stdout: (value) => process.stdout.write(`${value}\n`),
    stderr: (value) => process.stderr.write(`${value}\n`),
  };
  const config = dependencies.config ?? resolveCollectorConfig();
  const [command, ...args] = argv;

  try {
    if (command === "doctor") {
      rejectPositionals(args);
      const report = dependencies.doctor
        ? await dependencies.doctor()
        : await runDoctor(config);
      writeJson(io.stdout, report);
      return report.status === "error" ? 2 : 0;
    }
    if (command === "collect") {
      const parsed = parseArgs({
        args,
        options: {
          once: { type: "boolean" },
          fixture: { type: "string" },
        },
        strict: true,
      });
      if (!parsed.values.once) {
        return fail(io, "collect_requires_once");
      }
      const resolved = await resolvePorts(
        dependencies.ports,
        parsed.values.fixture ?? config.fixtureFile,
        config,
        dependencies.createLivePorts,
      );
      try {
        writeJson(
          io.stdout,
          await collectSnapshot(resolved.ports, nowOptions(dependencies.now)),
        );
        return 0;
      } finally {
        await closeQuietly(resolved.close);
      }
    }
    if (command === "usage") {
      const parsed = parseArgs({
        args,
        options: {
          period: { type: "string" },
          "group-by": { type: "string" },
          fixture: { type: "string" },
        },
        strict: true,
      });
      const period = parsed.values.period;
      const groupBy = parsed.values["group-by"];
      if (!isPeriod(period) || !isGroupBy(groupBy)) {
        return fail(io, "usage_invalid_query");
      }
      const resolved = await resolvePorts(
        dependencies.ports,
        parsed.values.fixture ?? config.fixtureFile,
        config,
        dependencies.createLivePorts,
      );
      try {
        writeJson(
          io.stdout,
          await queryUsage(
            resolved.ports,
            { period, groupBy },
            nowOptions(dependencies.now),
          ),
        );
        return 0;
      } finally {
        await closeQuietly(resolved.close);
      }
    }
    return fail(io, command ? "unknown_command" : "command_required");
  } catch (error) {
    const code = error instanceof Error && error.message === "fixture_invalid"
      ? "fixture_invalid"
      : "command_failed";
    return fail(io, code);
  }
}

async function resolvePorts(
  injected: CollectorPorts | undefined,
  fixture: string | undefined,
  config: CollectorConfig,
  liveFactory: ((config: CollectorConfig) => LiveCollectorPorts) | undefined,
): Promise<{ ports: CollectorPorts; close: () => Promise<void> }> {
  if (injected) {
    return { ports: injected, close: async () => undefined };
  }
  if (fixture) {
    return {
      ports: await fixtureCollectorPorts(fixture),
      close: async () => undefined,
    };
  }
  const live = liveFactory
    ? liveFactory(config)
    : createLiveCollectorPorts({
        clientOptions: config.codexBinary
          ? { binary: config.codexBinary }
          : {},
        sessionRoots: [
          config.activeSessionsDirectory,
          config.archivedSessionsDirectory,
        ],
      });
  return { ports: live, close: () => live.close() };
}

async function closeQuietly(close: () => Promise<void>): Promise<void> {
  try {
    await close();
  } catch {
    // A close failure must not leak diagnostics or replace a valid command result.
  }
}

function isPeriod(value: unknown): value is Period {
  return value === "today" || value === "week" || value === "month";
}

function nowOptions(now: (() => Date) | undefined): { now?: Date } {
  return now ? { now: now() } : {};
}

function isGroupBy(value: unknown): value is GroupBy {
  return value === "model" || value === "project" || value === "day";
}

function rejectPositionals(args: string[]): void {
  if (args.length > 0) throw new Error("unexpected_arguments");
}

function writeJson(write: (value: string) => void, value: unknown): void {
  write(JSON.stringify(value, null, 2));
}

function fail(io: CliIo, code: string): number {
  writeJson(io.stderr, { error: { code } });
  return 2;
}
