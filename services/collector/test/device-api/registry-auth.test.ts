import assert from "node:assert/strict";
import { readFile, rm, stat } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { mkdtemp } from "node:fs/promises";
import test from "node:test";
import {
  DeviceRequestAuthenticator,
  FileDeviceRegistry,
  signDeviceRequest,
} from "../../src/device-api/index.js";

test("registry persists only a private verification key and supports revocation", async () => {
  const temporary = await mkdtemp(join(tmpdir(), "quotaarc-registry-"));
  try {
    const registryPath = join(temporary, "devices.json");
    const registry = new FileDeviceRegistry(registryPath);
    const issued = await registry.issue("Xiaomi 14");
    const encoded = await readFile(registryPath, "utf8");
    const info = await stat(registryPath);

    assert.match(issued.collectorId, /^qac_/u);
    assert.match(issued.token, /^qa1\./u);
    assert.equal(encoded.includes(issued.token), false);
    assert.equal(encoded.includes(issued.token.split(".")[2]!), false);
    assert.equal(info.mode & 0o077, 0);
    assert.equal((await registry.list()).devices[0]?.revokedAt, null);

    await registry.revoke(issued.deviceId);
    assert.equal(await registry.activeDevice(issued.deviceId), null);
    assert.ok((await registry.list()).devices[0]?.revokedAt);
  } finally {
    await rm(temporary, { recursive: true, force: true });
  }
});

test("signed requests reject replay, expiry, wrong scope, and revoked devices", async () => {
  const temporary = await mkdtemp(join(tmpdir(), "quotaarc-auth-"));
  const now = new Date("2026-07-19T10:00:00Z");
  try {
    const registry = new FileDeviceRegistry(join(temporary, "devices.json"), {
      now: () => now,
    });
    const issued = await registry.issue("Xiaomi 14", ["summary.read"]);
    const authenticator = new DeviceRequestAuthenticator(registry, {
      now: () => now,
      allowedClockSkewMs: 30_000,
    });
    const headers = signDeviceRequest(
      issued.token,
      "GET",
      "/v1/summary",
      { now, nonce: "abcdefghijklmnopqrstuv" },
    );

    const accepted = await authenticator.authenticate(
      { method: "GET", path: "/v1/summary", headers },
      "summary.read",
    );
    assert.equal(accepted.ok, true);

    const replay = await authenticator.authenticate(
      { method: "GET", path: "/v1/summary", headers },
      "summary.read",
    );
    assert.deepEqual(replay, {
      ok: false,
      status: 401,
      code: "auth.replay",
    });

    const wrongScopeHeaders = signDeviceRequest(
      issued.token,
      "POST",
      "/v1/refresh",
      { now, nonce: "abcdefghijklmnopqrstuw" },
    );
    const wrongScope = await authenticator.authenticate(
      { method: "POST", path: "/v1/refresh", headers: wrongScopeHeaders },
      "refresh.write",
    );
    assert.deepEqual(wrongScope, {
      ok: false,
      status: 403,
      code: "auth.scope_denied",
    });

    const expiredHeaders = signDeviceRequest(
      issued.token,
      "GET",
      "/v1/health",
      {
        now: new Date("2026-07-19T09:59:00Z"),
        nonce: "abcdefghijklmnopqrstux",
      },
    );
    const expired = await authenticator.authenticate(
      { method: "GET", path: "/v1/health", headers: expiredHeaders },
      "summary.read",
    );
    assert.deepEqual(expired, {
      ok: false,
      status: 401,
      code: "auth.expired",
    });

    await registry.revoke(issued.deviceId);
    const revokedHeaders = signDeviceRequest(
      issued.token,
      "GET",
      "/v1/health",
      { now, nonce: "abcdefghijklmnopqrstuy" },
    );
    const revoked = await authenticator.authenticate(
      { method: "GET", path: "/v1/health", headers: revokedHeaders },
      "summary.read",
    );
    assert.deepEqual(revoked, {
      ok: false,
      status: 401,
      code: "auth.invalid",
    });
  } finally {
    await rm(temporary, { recursive: true, force: true });
  }
});
