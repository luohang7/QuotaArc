import assert from "node:assert/strict";
import test from "node:test";
import type { CollectorConfig } from "../../src/config/index.js";
import {
  runDoctor,
  type DoctorSystemPort,
} from "../../src/diagnostics/index.js";

const config: CollectorConfig = {
  codexBinary: "/Users/alice/private/codex",
  codexHome: "/Users/alice/.codex",
  activeSessionsDirectory: "/Users/alice/.codex/sessions",
  archivedSessionsDirectory: "/Users/alice/.codex/archived_sessions",
  deviceStateDirectory: "/Users/alice/.quotaarc",
  deviceRegistryFile: "/Users/alice/.quotaarc/devices.json",
  tlsCertificateFile: "/Users/alice/.quotaarc/collector-cert.pem",
  tlsPrivateKeyFile: "/Users/alice/.quotaarc/collector-key.pem",
};

test("doctor reports capabilities without returning filesystem paths", async () => {
  const system: DoctorSystemPort = {
    nodeVersion: () => "v24.17.0",
    now: () => new Date("2026-07-19T10:00:00Z"),
    async discoverCodex() {
      return {
        binary: "/Users/alice/private/codex",
        discoveredBy: "configured",
      };
    },
    async codexVersion() {
      return "0.145.0-alpha.18";
    },
    async directory(path) {
      return {
        status: path.endsWith("archived_sessions") ? "missing" : "ok",
      };
    },
  };

  const report = await runDoctor(config, system);
  assert.equal(report.status, "ok");
  assert.equal(report.checks.codex.version, "0.145.0-alpha.18");
  assert.equal(report.checks.sessions.archived.status, "missing");
  assert.doesNotMatch(JSON.stringify(report), /Users|alice|private|\.codex/);
});

test("doctor makes missing Codex an explicit error", async () => {
  const system: DoctorSystemPort = {
    nodeVersion: () => "v24.17.0",
    now: () => new Date("2026-07-19T10:00:00Z"),
    async discoverCodex() {
      return null;
    },
    async codexVersion() {
      return null;
    },
    async directory() {
      return { status: "ok" };
    },
  };

  const report = await runDoctor(config, system);
  assert.equal(report.status, "error");
  assert.equal(report.checks.codex.code, "not_found");
});
