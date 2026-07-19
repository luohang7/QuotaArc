import { X509Certificate } from "node:crypto";
import { constants, type Stats } from "node:fs";
import {
  open,
} from "node:fs/promises";
import {
  createServer,
  type Server as HttpsServer,
} from "node:https";
import type {
  IncomingMessage,
  RequestListener,
  ServerResponse,
} from "node:http";
import { isIP } from "node:net";
import {
  DEVICE_CAPABILITIES,
  type CollectorHealth,
  type DeviceApiError,
  type DeviceCapability,
} from "@quotaarc/contracts";
import {
  DeviceRequestAuthenticator,
  type AuthenticatedDevice,
} from "./auth.js";
import { DeviceRateLimiter, type DeviceRateBucket } from "./rate-limit.js";
import type { DeviceSnapshotService } from "./snapshot-service.js";

const MAX_EMPTY_REQUEST_BYTES = 0;
const MAX_RESPONSE_BYTES = 256 * 1024;
const TARGET_BASE_ORIGIN = "https://quotaarc.invalid";

export interface DeviceApiHandlerOptions {
  authenticator: DeviceRequestAuthenticator;
  rateLimiter: DeviceRateLimiter;
  snapshots: DeviceSnapshotService;
  now?: () => Date;
}

export interface StartDeviceApiServerOptions extends DeviceApiHandlerOptions {
  host: string;
  port: number;
  allowLan: boolean;
  tlsCertificateFile: string;
  tlsPrivateKeyFile: string;
}

export interface RunningDeviceApiServer {
  host: string;
  port: number;
  certificateSha256: string;
  close(): Promise<void>;
}

interface Route {
  method: "GET" | "POST";
  path: "/v1/health" | "/v1/summary" | "/v1/refresh";
  scope: DeviceCapability;
  rateBucket: DeviceRateBucket;
}

const routes: readonly Route[] = [
  {
    method: "GET",
    path: "/v1/health",
    scope: "summary.read",
    rateBucket: "read",
  },
  {
    method: "GET",
    path: "/v1/summary",
    scope: "summary.read",
    rateBucket: "read",
  },
  {
    method: "POST",
    path: "/v1/refresh",
    scope: "refresh.write",
    rateBucket: "refresh",
  },
];

export function createDeviceApiHandler(
  options: DeviceApiHandlerOptions,
): RequestListener {
  const now = options.now ?? (() => new Date());
  return (request, response) => {
    void handleRequest(request, response, options, now).catch(() => {
      if (!response.headersSent) {
        writeError(response, 503, "server.unavailable", true);
      } else {
        response.destroy();
      }
    });
  };
}

export async function startDeviceApiServer(
  options: StartDeviceApiServerOptions,
): Promise<RunningDeviceApiServer> {
  const host = validateListener(options.host, options.port, options.allowLan);
  const [certificate, privateKey] = await Promise.all([
    readBoundedFile(options.tlsCertificateFile, 128 * 1024, false),
    readBoundedFile(options.tlsPrivateKeyFile, 64 * 1024, true),
  ]);
  let parsedCertificate: X509Certificate;
  try {
    parsedCertificate = new X509Certificate(certificate);
  } catch {
    throw new Error("tls_certificate_invalid");
  }
  const server = createServer(
    {
      cert: certificate,
      key: privateKey,
      minVersion: "TLSv1.3",
      maxVersion: "TLSv1.3",
      honorCipherOrder: true,
    },
    createDeviceApiHandler(options),
  );
  hardenServer(server);
  const port = await listen(server, host, options.port);
  return {
    host,
    port,
    certificateSha256: parsedCertificate.fingerprint256.replaceAll(":", ""),
    close: () => closeServer(server),
  };
}

async function handleRequest(
  request: IncomingMessage,
  response: ServerResponse,
  options: DeviceApiHandlerOptions,
  now: () => Date,
): Promise<void> {
  applySecurityHeaders(response);
  if (hasBody(request)) {
    request.resume();
    writeError(response, 413, "request.body_forbidden", false);
    return;
  }
  const target = parseTarget(request.url);
  if (!target.ok) {
    writeError(response, 400, target.code, false);
    return;
  }
  const method = request.method?.toUpperCase() ?? "";
  const route = routes.find((candidate) =>
    candidate.method === method && candidate.path === target.path
  );
  if (!route) {
    const knownPath = routes.some((candidate) => candidate.path === target.path);
    writeError(
      response,
      knownPath ? 405 : 404,
      knownPath ? "request.method_not_allowed" : "request.not_found",
      false,
    );
    return;
  }
  const auth = await options.authenticator.authenticate(
    {
      method: route.method,
      path: route.path,
      headers: request.headers,
    },
    route.scope,
  );
  if (!auth.ok) {
    writeError(response, auth.status, auth.code, false);
    return;
  }
  response.setHeader("X-QuotaArc-Collector-Id", auth.device.collectorId);
  if (!options.rateLimiter.consume(auth.device.deviceId, route.rateBucket)) {
    response.setHeader("Retry-After", "60");
    writeError(response, 429, "request.rate_limited", true);
    return;
  }

  if (route.path === "/v1/health") {
    const health: CollectorHealth = {
      schemaVersion: 1,
      collectorId: auth.device.collectorId,
      generatedAt: validNow(now).toISOString(),
      capabilities: effectiveCapabilities(auth.device),
    };
    writeJson(response, 200, health);
    return;
  }
  if (route.path === "/v1/summary") {
    const summary = await options.snapshots.summary();
    writeJson(response, 200, summary);
    return;
  }
  const refreshed = await options.snapshots.refresh();
  writeJson(response, 200, refreshed.receipt);
}

function effectiveCapabilities(
  device: AuthenticatedDevice,
): DeviceCapability[] {
  return DEVICE_CAPABILITIES.filter((capability) =>
    device.scopes.has(capability)
  );
}

function hasBody(request: IncomingMessage): boolean {
  const contentLength = request.headers["content-length"];
  if (contentLength !== undefined && contentLength !== String(MAX_EMPTY_REQUEST_BYTES)) {
    return true;
  }
  return request.headers["transfer-encoding"] !== undefined;
}

function parseTarget(raw: string | undefined):
  | { ok: true; path: string }
  | { ok: false; code: "request.target_invalid" | "request.query_forbidden" } {
  if (!raw || !raw.startsWith("/")) {
    return { ok: false, code: "request.target_invalid" };
  }
  let url: URL;
  try {
    url = new URL(raw, TARGET_BASE_ORIGIN);
  } catch {
    return { ok: false, code: "request.target_invalid" };
  }
  if (url.search !== "" || url.hash !== "") {
    return { ok: false, code: "request.query_forbidden" };
  }
  if (url.origin !== TARGET_BASE_ORIGIN || url.pathname !== raw) {
    return { ok: false, code: "request.target_invalid" };
  }
  return { ok: true, path: raw };
}

function writeJson(
  response: ServerResponse,
  status: number,
  value: unknown,
): void {
  const encoded = Buffer.from(JSON.stringify(value), "utf8");
  if (encoded.byteLength > MAX_RESPONSE_BYTES) {
    writeError(response, 503, "response.too_large", false);
    return;
  }
  response.statusCode = status;
  response.setHeader("Content-Type", "application/json; charset=utf-8");
  response.setHeader("Content-Length", String(encoded.byteLength));
  response.end(encoded);
}

function writeError(
  response: ServerResponse,
  status: number,
  code: string,
  retryable: boolean,
): void {
  const error: DeviceApiError = {
    schemaVersion: 1,
    error: {
      code,
      message: publicErrorMessage(status),
      retryable,
    },
  };
  writeJson(response, status, error);
}

function publicErrorMessage(status: number): string {
  if (status === 400 || status === 405 || status === 413) {
    return "The request is not supported";
  }
  if (status === 401 || status === 403) {
    return "Device authentication failed";
  }
  if (status === 404) return "The requested endpoint does not exist";
  if (status === 429) return "The device request limit was reached";
  return "The Collector is temporarily unavailable";
}

function applySecurityHeaders(response: ServerResponse): void {
  response.setHeader("Cache-Control", "no-store");
  response.setHeader("Pragma", "no-cache");
  response.setHeader("X-Content-Type-Options", "nosniff");
  response.setHeader("Referrer-Policy", "no-referrer");
  response.setHeader("Content-Security-Policy", "default-src 'none'");
}

function validateListener(
  host: string,
  port: number,
  allowLan: boolean,
): string {
  if (!Number.isSafeInteger(port) || port < 0 || port > 65_535) {
    throw new Error("listener_port_invalid");
  }
  const normalized = host.trim().toLowerCase();
  const loopback = normalized === "localhost" ||
    normalized === "127.0.0.1" ||
    normalized === "::1";
  if (!loopback && !allowLan) throw new Error("listener_lan_opt_in_required");
  if (
    normalized.length === 0 ||
    normalized === "0.0.0.0" ||
    normalized === "::"
  ) {
    throw new Error("listener_specific_address_required");
  }
  if (!loopback && isIP(normalized) === 0) {
    throw new Error("listener_lan_ip_required");
  }
  return normalized;
}

async function readBoundedFile(
  path: string,
  maximumBytes: number,
  requirePrivate: boolean,
): Promise<Buffer> {
  let handle: Awaited<ReturnType<typeof open>>;
  try {
    handle = await open(
      path,
      constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK,
    );
  } catch {
    throw new Error("tls_file_invalid");
  }
  try {
    const before = await handle.stat();
    validateOpenedTlsFile(before, maximumBytes, requirePrivate);
    const contents = await handle.readFile();
    const after = await handle.stat();
    validateOpenedTlsFile(after, maximumBytes, requirePrivate);
    if (contents.byteLength !== after.size) {
      throw new Error("tls_file_size_invalid");
    }
    return contents;
  } finally {
    await handle.close();
  }
}

function validateOpenedTlsFile(
  info: Stats,
  maximumBytes: number,
  requirePrivate: boolean,
): void {
  if (!info.isFile()) throw new Error("tls_file_invalid");
  if (info.size <= 0 || info.size > maximumBytes) {
    throw new Error("tls_file_size_invalid");
  }
  if (requirePrivate) {
    if ((info.mode & 0o077) !== 0) {
      throw new Error("tls_private_key_permissions_invalid");
    }
  } else if ((info.mode & 0o022) !== 0) {
    throw new Error("tls_file_invalid");
  }
  const uid = typeof process.getuid === "function" ? process.getuid() : null;
  if (uid !== null && info.uid !== uid) throw new Error("tls_file_not_owned");
}

function hardenServer(server: HttpsServer): void {
  server.requestTimeout = 10_000;
  server.headersTimeout = 5_000;
  server.keepAliveTimeout = 5_000;
  server.maxHeadersCount = 40;
  server.maxConnections = 32;
}

function listen(server: HttpsServer, host: string, port: number): Promise<number> {
  return new Promise((resolve, reject) => {
    const onError = (error: Error) => {
      server.off("listening", onListening);
      reject(error);
    };
    const onListening = () => {
      server.off("error", onError);
      const address = server.address();
      if (!address || typeof address === "string") {
        reject(new Error("listener_address_invalid"));
        return;
      }
      resolve(address.port);
    };
    server.once("error", onError);
    server.once("listening", onListening);
    server.listen(port, host);
  });
}

function closeServer(server: HttpsServer): Promise<void> {
  return new Promise((resolve, reject) => {
    server.close((error) => error ? reject(error) : resolve());
  });
}

function validNow(now: () => Date): Date {
  const value = now();
  if (!Number.isFinite(value.getTime())) throw new Error("clock_invalid");
  return value;
}
