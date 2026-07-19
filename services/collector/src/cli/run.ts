import { parseArgs } from "node:util";
import {
  parseDevicePairingBundle,
  type DevicePairingBundle,
} from "@quotaarc/contracts";
import {
  createLiveCollectorPorts,
  type LiveCollectorPorts,
} from "../adapters/live.js";
import { resolveCollectorConfig, type CollectorConfig } from "../config/index.js";
import {
  certificateSha256,
  DeviceRateLimiter,
  DeviceRequestAuthenticator,
  DeviceSnapshotService,
  FileDeviceRegistry,
  generateSelfSignedTlsIdentity,
  requireCertificateHost,
  startDeviceApiServer,
} from "../device-api/index.js";
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
    if (command === "device") {
      return await runDeviceCommand(args, config, io, dependencies.now);
    }
    if (command === "serve") {
      return await runServeCommand(args, config, io, dependencies);
    }
    return fail(io, command ? "unknown_command" : "command_required");
  } catch (error) {
    const code = publicCliErrorCode(error);
    return fail(io, code);
  }
}

async function runDeviceCommand(
  args: string[],
  config: CollectorConfig,
  io: CliIo,
  now: (() => Date) | undefined,
): Promise<number> {
  const [action, ...actionArgs] = args;
  if (action === "tls-init") {
    const parsed = parseArgs({
      args: actionArgs,
      options: {
        host: { type: "string" },
        cert: { type: "string" },
        key: { type: "string" },
        openssl: { type: "string" },
      },
      strict: true,
    });
    if (!parsed.values.host) return fail(io, "device_tls_host_required");
    const generated = await generateSelfSignedTlsIdentity({
      host: parsed.values.host,
      certificateFile: parsed.values.cert ?? config.tlsCertificateFile,
      privateKeyFile: parsed.values.key ?? config.tlsPrivateKeyFile,
      ...(parsed.values.openssl ? { opensslBinary: parsed.values.openssl } : {}),
    });
    writeJson(io.stdout, {
      tlsVersion: 1,
      certificateSha256: generated.certificateSha256,
      subjectAlternativeName: generated.subjectAlternativeName,
    });
    return 0;
  }
  if (action === "issue") {
    const parsed = parseArgs({
      args: actionArgs,
      options: {
        label: { type: "string" },
        endpoint: { type: "string" },
        cert: { type: "string" },
        registry: { type: "string" },
      },
      strict: true,
    });
    if (!parsed.values.label || !parsed.values.endpoint) {
      return fail(io, "device_issue_arguments_required");
    }
    const endpoint = normalizeHttpsOrigin(parsed.values.endpoint);
    const certificateFile = parsed.values.cert ?? config.tlsCertificateFile;
    await requireCertificateHost(
      certificateFile,
      endpointHostname(endpoint),
    );
    const fingerprint = await certificateSha256(certificateFile);
    const registry = new FileDeviceRegistry(
      parsed.values.registry ?? config.deviceRegistryFile,
      {
        ...(now ? { now } : {}),
        allowCreateParent: true,
      },
    );
    const issued = await registry.issue(parsed.values.label);
    const bundle: DevicePairingBundle = {
      pairingVersion: 1,
      endpoint,
      collectorId: issued.collectorId,
      certificateSha256: fingerprint,
      deviceToken: issued.token,
      scopes: issued.scopes,
    };
    writeJson(io.stdout, parseDevicePairingBundle(bundle));
    return 0;
  }
  if (action === "list") {
    const parsed = parseArgs({
      args: actionArgs,
      options: {
        registry: { type: "string" },
      },
      strict: true,
    });
    const registry = new FileDeviceRegistry(
      parsed.values.registry ?? config.deviceRegistryFile,
    );
    writeJson(io.stdout, await registry.list());
    return 0;
  }
  if (action === "revoke") {
    const parsed = parseArgs({
      args: actionArgs,
      options: {
        id: { type: "string" },
        registry: { type: "string" },
      },
      strict: true,
    });
    if (!parsed.values.id) return fail(io, "device_id_required");
    const registry = new FileDeviceRegistry(
      parsed.values.registry ?? config.deviceRegistryFile,
      {
        ...(now ? { now } : {}),
      },
    );
    writeJson(io.stdout, await registry.revoke(parsed.values.id));
    return 0;
  }
  return fail(io, action ? "device_unknown_command" : "device_command_required");
}

async function runServeCommand(
  args: string[],
  config: CollectorConfig,
  io: CliIo,
  dependencies: CliDependencies,
): Promise<number> {
  const parsed = parseArgs({
    args,
    options: {
      host: { type: "string", default: "127.0.0.1" },
      port: { type: "string", default: "8443" },
      "allow-lan": { type: "boolean" },
      cert: { type: "string" },
      key: { type: "string" },
      registry: { type: "string" },
      fixture: { type: "string" },
    },
    strict: true,
  });
  const port = parsePort(parsed.values.port);
  const host = parsed.values.host;
  const now = dependencies.now ?? (() => new Date());
  const registry = new FileDeviceRegistry(
    parsed.values.registry ?? config.deviceRegistryFile,
  );
  const collectorId = await registry.collectorId();
  const snapshots = new DeviceSnapshotService(
    collectorId,
    async () => {
      const resolved = await resolvePorts(
        dependencies.ports,
        parsed.values.fixture ?? config.fixtureFile,
        config,
        dependencies.createLivePorts,
      );
      try {
        return await collectSnapshot(resolved.ports, { now: now() });
      } finally {
        await closeQuietly(resolved.close);
      }
    },
    { now },
  );
  const running = await startDeviceApiServer({
    host,
    port,
    allowLan: parsed.values["allow-lan"] ?? false,
    tlsCertificateFile: parsed.values.cert ?? config.tlsCertificateFile,
    tlsPrivateKeyFile: parsed.values.key ?? config.tlsPrivateKeyFile,
    authenticator: new DeviceRequestAuthenticator(registry, { now }),
    rateLimiter: new DeviceRateLimiter({ now }),
    snapshots,
    now,
  });
  try {
    writeJson(io.stdout, {
      serverVersion: 1,
      status: "listening",
      endpoint: formatHttpsOrigin(running.host, running.port),
      collectorId,
      certificateSha256: running.certificateSha256,
      lanEnabled: parsed.values["allow-lan"] ?? false,
    });
    await waitForShutdownSignal();
    return 0;
  } finally {
    await running.close();
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

function parsePort(value: string): number {
  if (!/^\d{1,5}$/u.test(value)) throw new Error("listener_port_invalid");
  const port = Number(value);
  if (!Number.isSafeInteger(port) || port < 1 || port > 65_535) {
    throw new Error("listener_port_invalid");
  }
  return port;
}

function normalizeHttpsOrigin(value: string): string {
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    throw new Error("device_endpoint_invalid");
  }
  if (
    url.protocol !== "https:" ||
    url.username !== "" ||
    url.password !== "" ||
    (url.pathname !== "" && url.pathname !== "/") ||
    url.search !== "" ||
    url.hash !== ""
  ) {
    throw new Error("device_endpoint_invalid");
  }
  return url.origin;
}

function endpointHostname(origin: string): string {
  const hostname = new URL(origin).hostname;
  return hostname.startsWith("[") && hostname.endsWith("]")
    ? hostname.slice(1, -1)
    : hostname;
}

function formatHttpsOrigin(host: string, port: number): string {
  const formattedHost = host.includes(":") ? `[${host}]` : host;
  return `https://${formattedHost}${port === 443 ? "" : `:${port}`}`;
}

function waitForShutdownSignal(): Promise<void> {
  return new Promise((resolve) => {
    const complete = () => {
      process.off("SIGINT", complete);
      process.off("SIGTERM", complete);
      resolve();
    };
    process.once("SIGINT", complete);
    process.once("SIGTERM", complete);
  });
}

function publicCliErrorCode(error: unknown): string {
  if (!(error instanceof Error)) return "command_failed";
  const allowed = new Set([
    "fixture_invalid",
    "device_endpoint_invalid",
    "device_id_invalid",
    "device_not_found",
    "device_label_invalid",
    "device_registry_invalid",
    "device_registry_lock_failed",
    "device_registry_parent_invalid",
    "device_registry_parent_not_private",
    "device_registry_parent_not_owned",
    "device_registry_file_invalid",
    "device_registry_file_not_private",
    "device_registry_file_not_owned",
    "device_registry_too_large",
    "tls_host_invalid",
    "tls_parent_invalid",
    "tls_parent_not_private",
    "tls_parent_not_owned",
    "tls_identity_already_exists",
    "tls_certificate_invalid",
    "tls_certificate_expired",
    "tls_certificate_host_mismatch",
    "tls_private_key_permissions_invalid",
    "tls_file_invalid",
    "tls_file_size_invalid",
    "tls_file_not_owned",
    "openssl_unavailable",
    "tls_generation_failed",
    "listener_port_invalid",
    "listener_lan_opt_in_required",
    "listener_specific_address_required",
    "listener_lan_ip_required",
  ]);
  return allowed.has(error.message) ? error.message : "command_failed";
}

function fail(io: CliIo, code: string): number {
  writeJson(io.stderr, { error: { code } });
  return 2;
}
