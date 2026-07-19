import { constants } from "node:fs";
import { access, stat } from "node:fs/promises";
import { delimiter, join } from "node:path";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import type { CollectorConfig } from "../config/index.js";

const execFileAsync = promisify(execFile);

export type CheckStatus = "ok" | "degraded" | "error";

export interface DoctorReport {
  schemaVersion: 1;
  checkedAt: string;
  status: CheckStatus;
  checks: {
    node: {
      status: CheckStatus;
      version: string;
      minimumMajor: number;
    };
    codex: {
      status: CheckStatus;
      discoveredBy: "configured" | "chatgpt_app" | "path" | "none";
      version: string | null;
      code?: "not_found" | "not_executable" | "version_unavailable";
    };
    sessions: {
      status: CheckStatus;
      active: DirectoryCheck;
      archived: DirectoryCheck;
    };
  };
}

export interface DirectoryCheck {
  status: "ok" | "missing" | "unreadable";
}

interface CodexDiscovery {
  binary: string;
  discoveredBy: Exclude<DoctorReport["checks"]["codex"]["discoveredBy"], "none">;
}

export interface DoctorSystemPort {
  nodeVersion(): string;
  now(): Date;
  discoverCodex(explicit?: string): Promise<CodexDiscovery | null>;
  codexVersion(binary: string): Promise<string | null>;
  directory(path: string): Promise<DirectoryCheck>;
}

export async function runDoctor(
  config: CollectorConfig,
  system: DoctorSystemPort = defaultDoctorSystem,
): Promise<DoctorReport> {
  const nodeVersion = system.nodeVersion();
  const nodeMajor = Number.parseInt(nodeVersion.replace(/^v/, "").split(".")[0] ?? "", 10);
  const nodeStatus: CheckStatus = nodeMajor >= 24 ? "ok" : "error";
  const discovered = await system.discoverCodex(config.codexBinary);
  let codex: DoctorReport["checks"]["codex"];
  if (!discovered) {
    codex = {
      status: "error",
      discoveredBy: "none",
      version: null,
      code: "not_found",
    };
  } else {
    const version = await system.codexVersion(discovered.binary);
    codex = version
      ? {
          status: "ok",
          discoveredBy: discovered.discoveredBy,
          version,
        }
      : {
          status: "degraded",
          discoveredBy: discovered.discoveredBy,
          version: null,
          code: "version_unavailable",
        };
  }

  const [active, archived] = await Promise.all([
    system.directory(config.activeSessionsDirectory),
    system.directory(config.archivedSessionsDirectory),
  ]);
  const sessionsStatus: CheckStatus = active.status === "ok"
    ? archived.status === "unreadable" ? "degraded" : "ok"
    : active.status === "missing" ? "degraded" : "error";
  const status = combineStatuses([nodeStatus, codex.status, sessionsStatus]);

  return {
    schemaVersion: 1,
    checkedAt: system.now().toISOString(),
    status,
    checks: {
      node: {
        status: nodeStatus,
        version: nodeVersion,
        minimumMajor: 24,
      },
      codex,
      sessions: {
        status: sessionsStatus,
        active,
        archived,
      },
    },
  };
}

export const defaultDoctorSystem: DoctorSystemPort = {
  nodeVersion: () => process.version,
  now: () => new Date(),
  async discoverCodex(explicit) {
    if (explicit) {
      return await executable(explicit)
        ? { binary: explicit, discoveredBy: "configured" }
        : null;
    }
    const appBinary = "/Applications/ChatGPT.app/Contents/Resources/codex";
    if (await executable(appBinary)) {
      return { binary: appBinary, discoveredBy: "chatgpt_app" };
    }
    for (const entry of (process.env.PATH ?? "").split(delimiter)) {
      if (!entry) continue;
      const candidate = join(entry, "codex");
      if (await executable(candidate)) {
        return { binary: candidate, discoveredBy: "path" };
      }
    }
    return null;
  },
  async codexVersion(binary) {
    try {
      const { stdout, stderr } = await execFileAsync(binary, ["--version"], {
        timeout: 5_000,
        maxBuffer: 16_384,
      });
      const match = `${stdout}\n${stderr}`.match(/\b(\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?)\b/);
      return match?.[1] ?? null;
    } catch {
      return null;
    }
  },
  async directory(path) {
    try {
      const info = await stat(path);
      if (!info.isDirectory()) return { status: "unreadable" };
      await access(path, constants.R_OK);
      return { status: "ok" };
    } catch (error) {
      return { status: isMissing(error) ? "missing" : "unreadable" };
    }
  },
};

function combineStatuses(statuses: CheckStatus[]): CheckStatus {
  if (statuses.includes("error")) return "error";
  if (statuses.includes("degraded")) return "degraded";
  return "ok";
}

async function executable(path: string): Promise<boolean> {
  try {
    await access(path, constants.X_OK);
    return true;
  } catch {
    return false;
  }
}

function isMissing(error: unknown): boolean {
  return error instanceof Error &&
    "code" in error &&
    (error as NodeJS.ErrnoException).code === "ENOENT";
}
