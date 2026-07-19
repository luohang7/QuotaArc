import assert from "node:assert/strict";
import {
  createServer,
  request as httpRequest,
} from "node:http";
import { request as httpsRequest } from "node:https";
import {
  chmod,
  mkdtemp,
  readFile,
  rm,
  symlink,
} from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import {
  parseCollectorHealth,
  parseDeviceApiError,
  parseQuotaArcSummary,
  parseRefreshReceipt,
  type QuotaArcSummary,
} from "@quotaarc/contracts";
import {
  createDeviceApiHandler,
  DeviceRateLimiter,
  DeviceRequestAuthenticator,
  DeviceSnapshotService,
  FileDeviceRegistry,
  generateSelfSignedTlsIdentity,
  signDeviceRequest,
  startDeviceApiServer,
} from "../../src/device-api/index.js";

test("fixed routes require auth, reject replay/query/raw RPC, and never redirect", async () => {
  const context = await createContext();
  try {
    const unauthenticated = await fetch(`${context.origin}/v1/health`);
    assert.equal(unauthenticated.status, 401);
    assert.equal(
      parseDeviceApiError(await unauthenticated.json()).error.code,
      "auth.required",
    );

    const nonce = "abcdefghijklmnopqrstuv";
    const headers = signDeviceRequest(
      context.token,
      "GET",
      "/v1/health",
      { now: context.now, nonce },
    );
    const healthResponse = await fetch(`${context.origin}/v1/health`, {
      headers: new Headers(Object.entries(headers)),
    });
    const health = parseCollectorHealth(await healthResponse.json());
    assert.equal(healthResponse.status, 200);
    assert.equal(health.collectorId, context.collectorId);
    assert.equal(
      healthResponse.headers.get("x-quotaarc-collector-id"),
      context.collectorId,
    );

    const replay = await fetch(`${context.origin}/v1/health`, {
      headers: new Headers(Object.entries(headers)),
    });
    assert.equal(replay.status, 401);
    assert.equal(
      parseDeviceApiError(await replay.json()).error.code,
      "auth.replay",
    );

    const query = await signedFetch(context, "GET", "/v1/summary?debug=1");
    assert.equal(query.status, 400);
    assert.equal(query.headers.get("location"), null);

    for (
      const target of [
        "//evil.invalid/v1/summary",
        "/v1/x/../summary",
        "/v1/%2e/summary",
      ]
    ) {
      const aliased = await rawSignedJsonRequest(
        context,
        "GET",
        target,
        "/v1/summary",
      );
      assert.equal(aliased.status, 400, target);
      assert.equal(
        parseDeviceApiError(aliased.body).error.code,
        "request.target_invalid",
        target,
      );
    }

    const rawRpc = await signedFetch(context, "POST", "/rpc");
    assert.equal(rawRpc.status, 404);
    assert.equal(rawRpc.headers.get("location"), null);
  } finally {
    await context.close();
  }
});

test("authenticated summary and refresh return only strict contracts", async () => {
  const context = await createContext();
  try {
    const summaryResponse = await signedFetch(context, "GET", "/v1/summary");
    const summary = parseQuotaArcSummary(await summaryResponse.json());
    assert.equal(summaryResponse.status, 200);
    assert.equal(summary.generatedAt, context.summary.generatedAt);

    const refreshResponse = await signedFetch(context, "POST", "/v1/refresh");
    const receipt = parseRefreshReceipt(await refreshResponse.json());
    assert.equal(refreshResponse.status, 200);
    assert.equal(receipt.collectorId, context.collectorId);
    assert.equal(receipt.summaryGeneratedAt, context.summary.generatedAt);
    assert.equal(context.collectCalls(), 2);
  } finally {
    await context.close();
  }
});

test("wrong scope, revoked credential, and rate limits fail closed", async () => {
  const context = await createContext({
    scopes: ["summary.read"],
    readLimitPerMinute: 1,
  });
  try {
    const first = await signedFetch(context, "GET", "/v1/health");
    assert.equal(first.status, 200);

    const limited = await signedFetch(context, "GET", "/v1/summary");
    assert.equal(limited.status, 429);
    assert.equal(limited.headers.get("retry-after"), "60");

    const denied = await signedFetch(context, "POST", "/v1/refresh");
    assert.equal(denied.status, 403);
    assert.equal(
      parseDeviceApiError(await denied.json()).error.code,
      "auth.scope_denied",
    );

    await context.registry.revoke(context.deviceId);
    const revoked = await signedFetch(context, "GET", "/v1/health");
    assert.equal(revoked.status, 401);
    assert.equal(
      parseDeviceApiError(await revoked.json()).error.code,
      "auth.invalid",
    );
  } finally {
    await context.close();
  }
});

test("production server uses pinned TLS and refuses implicit LAN listeners", async () => {
  const temporary = await mkdtemp(join(tmpdir(), "quotaarc-device-tls-"));
  const now = new Date("2026-07-19T10:00:00Z");
  const certificateFile = join(temporary, "collector-cert.pem");
  const privateKeyFile = join(temporary, "collector-key.pem");
  try {
    const generated = await generateSelfSignedTlsIdentity({
      host: "127.0.0.1",
      certificateFile,
      privateKeyFile,
    });
    const registry = new FileDeviceRegistry(join(temporary, "devices.json"), {
      now: () => now,
    });
    const issued = await registry.issue("Xiaomi 14");
    const summary = await summaryFixture();
    const snapshots = new DeviceSnapshotService(
      issued.collectorId,
      async () => summary,
      {
        now: () => now,
        requestId: () => "qar_abcdefghijklmnop",
      },
    );
    const common = {
      port: 0,
      tlsCertificateFile: certificateFile,
      tlsPrivateKeyFile: privateKeyFile,
      authenticator: new DeviceRequestAuthenticator(registry, {
        now: () => now,
        allowedClockSkewMs: 30_000,
      }),
      rateLimiter: new DeviceRateLimiter({ now: () => now }),
      snapshots,
      now: () => now,
    };

    const linkedCertificate = join(temporary, "linked-cert.pem");
    const linkedPrivateKey = join(temporary, "linked-key.pem");
    await Promise.all([
      symlink(certificateFile, linkedCertificate),
      symlink(privateKeyFile, linkedPrivateKey),
    ]);
    await assert.rejects(
      startDeviceApiServer({
        ...common,
        host: "127.0.0.1",
        allowLan: false,
        tlsCertificateFile: linkedCertificate,
      }),
      /tls_file_invalid/u,
    );
    await assert.rejects(
      startDeviceApiServer({
        ...common,
        host: "127.0.0.1",
        allowLan: false,
        tlsPrivateKeyFile: linkedPrivateKey,
      }),
      /tls_file_invalid/u,
    );

    await chmod(certificateFile, 0o622);
    try {
      await assert.rejects(
        startDeviceApiServer({
          ...common,
          host: "127.0.0.1",
          allowLan: false,
        }),
        /tls_file_invalid/u,
      );
    } finally {
      await chmod(certificateFile, 0o600);
    }

    await chmod(privateKeyFile, 0o640);
    try {
      await assert.rejects(
        startDeviceApiServer({
          ...common,
          host: "127.0.0.1",
          allowLan: false,
        }),
        /tls_private_key_permissions_invalid/u,
      );
    } finally {
      await chmod(privateKeyFile, 0o600);
    }

    await assert.rejects(
      startDeviceApiServer({
        ...common,
        host: "192.0.2.10",
        allowLan: false,
      }),
      /listener_lan_opt_in_required/u,
    );

    const running = await startDeviceApiServer({
      ...common,
      host: "127.0.0.1",
      allowLan: false,
    });
    try {
      assert.equal(running.certificateSha256, generated.certificateSha256);
      const headers = signDeviceRequest(
        issued.token,
        "GET",
        "/v1/health",
        { now },
      );
      const response = await httpsJsonRequest({
        port: running.port,
        path: "/v1/health",
        headers,
        certificate: await readFile(certificateFile),
      });
      assert.equal(response.status, 200);
      assert.equal(
        parseCollectorHealth(response.body).collectorId,
        issued.collectorId,
      );
    } finally {
      await running.close();
    }
  } finally {
    await rm(temporary, { recursive: true, force: true });
  }
});

interface TestContext {
  origin: string;
  token: string;
  deviceId: string;
  collectorId: string;
  now: Date;
  summary: QuotaArcSummary;
  registry: FileDeviceRegistry;
  collectCalls(): number;
  close(): Promise<void>;
}

async function createContext(
  options: {
    scopes?: ("summary.read" | "refresh.write")[];
    readLimitPerMinute?: number;
  } = {},
): Promise<TestContext> {
  const temporary = await mkdtemp(join(tmpdir(), "quotaarc-device-api-"));
  const now = new Date("2026-07-19T10:00:00Z");
  const registry = new FileDeviceRegistry(join(temporary, "devices.json"), {
    now: () => now,
  });
  const issued = await registry.issue(
    "Xiaomi 14",
    options.scopes ?? ["summary.read", "refresh.write"],
  );
  const summary = await summaryFixture();
  let calls = 0;
  const snapshots = new DeviceSnapshotService(
    issued.collectorId,
    async () => {
      calls += 1;
      return summary;
    },
    {
      now: () => now,
      requestId: () => `qar_${String(calls).padStart(16, "a")}`,
    },
  );
  const server = createServer(createDeviceApiHandler({
    authenticator: new DeviceRequestAuthenticator(registry, {
      now: () => now,
      allowedClockSkewMs: 30_000,
    }),
    rateLimiter: new DeviceRateLimiter({
      now: () => now,
      readLimitPerMinute: options.readLimitPerMinute ?? 120,
    }),
    snapshots,
    now: () => now,
  }));
  await new Promise<void>((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => resolve());
  });
  const address = server.address();
  assert.ok(address && typeof address !== "string");
  return {
    origin: `http://127.0.0.1:${address.port}`,
    token: issued.token,
    deviceId: issued.deviceId,
    collectorId: issued.collectorId,
    now,
    summary,
    registry,
    collectCalls: () => calls,
    async close() {
      await new Promise<void>((resolve, reject) =>
        server.close((error) => error ? reject(error) : resolve())
      );
      await rm(temporary, { recursive: true, force: true });
    },
  };
}

async function signedFetch(
  context: TestContext,
  method: "GET" | "POST",
  target: string,
): Promise<Response> {
  const signedPath = target.includes("?") ? target.slice(0, target.indexOf("?")) : target;
  const headers = signDeviceRequest(
    context.token,
    method,
    signedPath,
    { now: context.now },
  );
  return fetch(`${context.origin}${target}`, {
    method,
    headers: new Headers(Object.entries(headers)),
    redirect: "manual",
  });
}

function rawSignedJsonRequest(
  context: TestContext,
  method: "GET" | "POST",
  rawTarget: string,
  signedPath: string,
): Promise<{ status: number; body: unknown }> {
  const endpoint = new URL(context.origin);
  const headers = signDeviceRequest(
    context.token,
    method,
    signedPath,
    { now: context.now },
  );
  return new Promise((resolve, reject) => {
    const request = httpRequest({
      hostname: endpoint.hostname,
      port: endpoint.port,
      path: rawTarget,
      method,
      headers,
    }, (response) => {
      const chunks: Buffer[] = [];
      response.on("data", (chunk: Buffer) => chunks.push(chunk));
      response.once("error", reject);
      response.once("end", () => {
        try {
          resolve({
            status: response.statusCode ?? 0,
            body: JSON.parse(Buffer.concat(chunks).toString("utf8")),
          });
        } catch (error) {
          reject(error);
        }
      });
    });
    request.once("error", reject);
    request.end();
  });
}

async function summaryFixture(): Promise<QuotaArcSummary> {
  const path = join(
    process.cwd(),
    "../../packages/contracts/examples/summary.ok.json",
  );
  return parseQuotaArcSummary(JSON.parse(await readFile(path, "utf8")));
}

function httpsJsonRequest(options: {
  port: number;
  path: string;
  headers: Record<string, string>;
  certificate: Buffer;
}): Promise<{ status: number; body: unknown }> {
  return new Promise((resolve, reject) => {
    const request = httpsRequest({
      hostname: "127.0.0.1",
      port: options.port,
      path: options.path,
      method: "GET",
      headers: options.headers,
      ca: options.certificate,
      rejectUnauthorized: true,
      minVersion: "TLSv1.3",
      maxVersion: "TLSv1.3",
    }, (response) => {
      const chunks: Buffer[] = [];
      response.on("data", (chunk: Buffer) => chunks.push(chunk));
      response.once("error", reject);
      response.once("end", () => {
        try {
          resolve({
            status: response.statusCode ?? 0,
            body: JSON.parse(Buffer.concat(chunks).toString("utf8")),
          });
        } catch (error) {
          reject(error);
        }
      });
    });
    request.once("error", reject);
    request.end();
  });
}
