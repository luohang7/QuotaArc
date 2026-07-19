import assert from "node:assert/strict";
import {
  mkdtemp,
  mkdir,
  readdir,
  rm,
  stat,
  writeFile,
} from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { spawnSync } from "node:child_process";

const temporary = await mkdtemp(join(tmpdir(), "quotaarc-package-smoke-"));
const archiveDirectory = join(temporary, "archive");
const installDirectory = join(temporary, "install");
const fixturePath = join(temporary, "collector-fixture.json");

try {
  await mkdir(archiveDirectory, { recursive: true });
  run("pnpm", ["pack", "--pack-destination", archiveDirectory], process.cwd());
  const archiveName = (await readdir(archiveDirectory)).find((name) =>
    name.endsWith(".tgz")
  );
  assert.ok(archiveName, "pnpm pack did not create an archive");
  const archivePath = join(archiveDirectory, archiveName);

  const listing = run("tar", ["-tzf", archivePath], process.cwd()).stdout
    .trim()
    .split("\n")
    .filter(Boolean);
  assert.ok(
    listing.includes("package/dist/package/quotaarc.mjs"),
    "bundle is missing from the package",
  );
  assert.equal(
    listing.some((entry) =>
      entry.includes("/src/") ||
      entry.includes("/test/") ||
      entry.includes("/fixtures/")
    ),
    false,
    "source, tests, or fixtures leaked into the package",
  );

  const packedManifest = JSON.parse(
    run(
      "tar",
      ["-xOzf", archivePath, "package/package.json"],
      process.cwd(),
    ).stdout,
  );
  assert.equal(
    packedManifest.dependencies?.["@quotaarc/contracts"],
    undefined,
    "packed CLI must not depend on an unpublished workspace package",
  );

  run(
    "npm",
    [
      "install",
      "--ignore-scripts",
      "--no-audit",
      "--no-fund",
      "--offline",
      "--prefix",
      installDirectory,
      archivePath,
    ],
    temporary,
  );

  await writeFile(
    fixturePath,
    `${JSON.stringify(sanitizedFixture(), null, 2)}\n`,
    { mode: 0o600 },
  );
  const executable = resolve(
    installDirectory,
    "node_modules",
    ".bin",
    "quotaarc",
  );
  const smoke = run(
    executable,
    ["collect", "--once", "--fixture", fixturePath],
    temporary,
  );
  const summary = JSON.parse(smoke.stdout);
  assert.equal(summary.schemaVersion, 1);
  assert.equal(summary.sources.quota.status, "ok");
  assert.equal(summary.sources.accountUsage.status, "unsupported");
  assert.equal(summary.sources.localUsage.status, "ok");
  assert.equal(smoke.stdout.includes(temporary), false);
  assert.equal(smoke.stdout.includes("fixture-secret"), false);

  const archiveInfo = await stat(archivePath);
  process.stdout.write(
    `${JSON.stringify({
      smoke: "ok",
      archiveBytes: archiveInfo.size,
      entries: listing.length,
    })}\n`,
  );
} finally {
  await rm(temporary, { recursive: true, force: true });
}

function run(command, args, cwd) {
  const result = spawnSync(command, args, {
    cwd,
    encoding: "utf8",
    env: {
      ...process.env,
      npm_config_update_notifier: "false",
    },
    maxBuffer: 10 * 1024 * 1024,
  });
  if (result.status !== 0) {
    throw new Error(
      `${command} failed (${result.status ?? "signal"}): ${
        result.stderr.trim() || result.stdout.trim()
      }`,
    );
  }
  return result;
}

function sanitizedFixture() {
  const collectedAt = "2026-07-19T10:00:00.000Z";
  const counts = {
    newInputTokens: 10,
    cachedInputTokens: 5,
    outputTokens: 3,
    reasoningTokens: 1,
  };
  const group = {
    id: "fixture-group",
    label: "Fixture",
    ...counts,
  };
  return {
    quota: {
      status: "ok",
      collectedAt,
      value: {
        limits: [{
          limitId: "fixture",
          limitName: null,
          windows: [{
            windowMinutes: 300,
            usedPercent: 25,
            remainingPercent: 75,
            resetsAt: "2026-07-19T15:00:00.000Z",
          }],
        }],
      },
    },
    accountUsage: { status: "unsupported" },
    localUsage: {
      status: "ok",
      collectedAt,
      value: {
        period: "today",
        ...counts,
        models: [group],
        projects: [group],
        days: [{
          id: "2026-07-19",
          label: "2026-07-19",
          ...counts,
        }],
        coverage: {
          files: 1,
          firstEventAt: collectedAt,
          lastEventAt: collectedAt,
        },
      },
    },
  };
}
