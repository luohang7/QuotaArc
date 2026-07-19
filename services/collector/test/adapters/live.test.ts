import assert from "node:assert/strict";
import test from "node:test";
import type { OfficialAccountRead } from "../../src/codex-rpc/client.js";
import {
  createLiveCollectorPorts,
  type LocalUsageReader,
} from "../../src/adapters/live.js";
import type { OfficialAccountClientPort } from "../../src/adapters/official.js";

test("live ports delegate local usage and close both injected resources", async () => {
  const periods: string[] = [];
  let localCloses = 0;
  let clientStops = 0;
  const local: LocalUsageReader = {
    async read(period) {
      periods.push(period);
      return { status: "unavailable", code: "fixture_empty" };
    },
    async close() {
      localCloses += 1;
    },
  };
  const client: OfficialAccountClientPort = {
    async readOfficialAccount(): Promise<OfficialAccountRead> {
      return {
        rateLimits: {},
        usage: null,
        usageError: null,
        usageErrorCode: null,
      };
    },
    async stop() {
      clientStops += 1;
    },
  };
  const ports = createLiveCollectorPorts({ client, localUsageReader: local });

  const result = await ports.readLocalUsage("month");
  assert.equal(result.status, "unavailable");
  assert.deepEqual(periods, ["month"]);

  await ports.close();
  await ports.close();
  assert.equal(clientStops, 1);
  assert.equal(localCloses, 1);
});

test("session roots use the injected scanner instead of the real filesystem", async () => {
  let scans = 0;
  const client: OfficialAccountClientPort = {
    async readOfficialAccount() {
      return {
        rateLimits: {},
        usage: null,
        usageError: null,
        usageErrorCode: null,
      };
    },
    async stop() {},
  };
  const ports = createLiveCollectorPorts({
    client,
    sessionRoots: ["/must-not-be-read"],
    now: () => new Date(2026, 6, 19, 12),
    async sessionScan(options) {
      scans += 1;
      assert.deepEqual(options.roots, ["/must-not-be-read"]);
      return {
        tokenEvents: [],
        rootsAvailable: 1,
        filesCount: 0,
        firstEventAt: null,
        lastEventAt: null,
        diagnosticsCount: 0,
        bytesScanned: 0,
      };
    },
  });

  const local = await ports.readLocalUsage("today");
  assert.equal(local.status, "ok");
  assert.equal(local.value?.newInputTokens, 0);
  assert.equal(scans, 1);
  await ports.close();
});
