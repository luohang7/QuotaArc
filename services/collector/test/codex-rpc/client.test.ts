import assert from "node:assert/strict";
import { resolve } from "node:path";
import test from "node:test";

import {
  CodexAppServerClient,
  safeErrorMessage,
} from "../../src/codex-rpc/client.js";

const mockServer = resolve(
  process.cwd(),
  "test/fixtures/app-server/mock-app-server.mjs",
);

test("reads documented account methods after the initialize handshake", async () => {
  const client = new CodexAppServerClient({
    binary: process.execPath,
    args: [mockServer],
    requestTimeoutMs: 1_000,
  });

  try {
    const result = await client.readOfficialAccount();
    assert.equal(result.usageError, null);
    assert.equal(result.usageErrorCode, null);
    assert.equal(
      (
        result.rateLimits as {
          rateLimits: { limitId: string };
        }
      ).rateLimits.limitId,
      "codex",
    );
    assert.equal(
      (
        result.usage as {
          summary: { lifetimeTokens: number };
        }
      ).summary.lifetimeTokens,
      12_345,
    );
  } finally {
    await client.stop();
  }
});

test("keeps quota available when optional account usage is unsupported", async () => {
  const client = new CodexAppServerClient({
    binary: process.execPath,
    args: [mockServer],
    environment: { ...process.env, MOCK_USAGE_MODE: "unsupported" },
    requestTimeoutMs: 1_000,
  });

  try {
    const result = await client.readOfficialAccount();
    assert.ok(result.rateLimits);
    assert.equal(result.usage, null);
    assert.equal(result.usageErrorCode, -32601);
    assert.match(result.usageError ?? "", /Method not found/);
  } finally {
    await client.stop();
  }
});

test("times out a stalled app-server request", async () => {
  const client = new CodexAppServerClient({
    binary: process.execPath,
    args: [mockServer],
    environment: { ...process.env, MOCK_RATE_MODE: "timeout" },
    requestTimeoutMs: 30,
  });

  try {
    await assert.rejects(client.readOfficialAccount(), /timed out/);
  } finally {
    await client.stop();
  }
});

test("redacts credentials and home paths from diagnostics", () => {
  const home = process.env.HOME ?? "";
  const message = safeErrorMessage(
    new Error(`${home}/private sk-exampleSecret Bearer abc.def`),
  );
  assert.doesNotMatch(message, /exampleSecret|abc\.def/);
  if (home) {
    assert.doesNotMatch(message, new RegExp(home.replaceAll("/", "\\/")));
  }
});

test("stop waits, escalates a stuck child, and serializes a safe restart", async () => {
  const client = new CodexAppServerClient({
    binary: process.execPath,
    args: [mockServer],
    environment: { ...process.env, MOCK_TERM_MODE: "ignore" },
    requestTimeoutMs: 1_000,
  });

  try {
    await client.readOfficialAccount();
    const startedAt = Date.now();
    const stopping = client.stop();
    // Deliberately do not await stop before requesting a new generation.
    const restarted = client.readOfficialAccount();
    const [, result] = await Promise.all([stopping, restarted]);

    assert.ok(
      Date.now() - startedAt >= 200,
      "stop returned before the SIGTERM grace period elapsed",
    );
    assert.ok(result.rateLimits);
    assert.equal(result.usageError, null);
  } finally {
    await client.stop();
  }
});
