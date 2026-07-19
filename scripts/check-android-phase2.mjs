import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import { extname, join, relative, resolve } from "node:path";

const root = resolve("apps/android");
const files = await walk(root);
const relativeFiles = new Set(files.map((file) => relative(root, file)));

for (const required of [
  "settings.gradle.kts",
  "build.gradle.kts",
  "gradle/libs.versions.toml",
  "gradle/wrapper/gradle-wrapper.jar",
  "gradle/wrapper/gradle-wrapper.properties",
  "gradlew",
  "gradlew.bat",
  "data/build.gradle.kts",
  "widget/build.gradle.kts",
  "app/build.gradle.kts",
  "app/src/main/AndroidManifest.xml",
  "widget/src/main/AndroidManifest.xml",
]) {
  assert.ok(relativeFiles.has(required), `missing Android Phase 2 file: ${required}`);
}

const manifests = await readMatching(files, (file) =>
  file.endsWith("AndroidManifest.xml"),
);
assert.doesNotMatch(
  manifests,
  /<uses-permission\b[^>]*android:name\s*=\s*"android\.permission\.INTERNET"[^>]*\/?>/u,
  "gate-closed Phase 2A must not request INTERNET",
);
assert.doesNotMatch(
  manifests,
  /usesCleartextTraffic\s*=\s*"true"/u,
  "cleartext traffic must stay disabled",
);

const productionKotlinFiles = files.filter(
  (file) =>
    extname(file) === ".kt" &&
    file.includes("/src/main/"),
);
const productionKotlin = await readMany(productionKotlinFiles);
for (const [pattern, message] of [
  [/\b10\.0\.2\.2\b/u, "emulator loopback bypass is forbidden"],
  [/\bHttpURLConnection\b/u, "a real HTTP adapter is gated"],
  [/\bokhttp3\b|\bOkHttpClient\b/u, "OkHttp must not enter gate-closed release code"],
  [/\bX509TrustManager\b|\bHostnameVerifier\b/u, "custom TLS trust code is forbidden"],
  [/\bAuthorization\b/u, "device auth wire behavior is not specified yet"],
  [/\bhttp:\/\//u, "cleartext origins are forbidden"],
]) {
  assert.doesNotMatch(productionKotlin, pattern, message);
}

assert.match(
  productionKotlin,
  /Disabled(?:QuotaArc)?DeviceApi/u,
  "release code must contain the explicit gate-closed device API",
);
assert.match(
  productionKotlin,
  /transport_gate_closed/u,
  "the transport gate must have a normalized visible failure",
);
assert.match(
  productionKotlin,
  /PeriodicWorkRequestBuilder/u,
  "the widget module must schedule periodic WorkManager sync",
);
const widgetSyncPolicy = await readFile(
  join(
    root,
    "widget/src/main/kotlin/dev/quotaarc/android/widget/WidgetSyncPolicy.kt",
  ),
  "utf8",
);
assert.match(
  widgetSyncPolicy,
  /const\s+val\s+PERIOD_MINUTES\s*=\s*30L?\b/u,
  "periodic sync must be fixed at 30 minutes",
);
assert.match(
  productionKotlin,
  /canSchedulePeriodic\(QuotaArcData\.transportGate\)/u,
  "the gate-closed release must not enqueue periodic failure work",
);
assert.match(
  productionKotlin,
  /ExistingWorkPolicy\.KEEP/u,
  "manual refresh work must be coalesced",
);

const appBuild = await readFile(join(root, "app/build.gradle.kts"), "utf8");
assert.match(appBuild, /applicationId\s*=\s*"dev\.quotaarc\.android"/u);
assert.match(appBuild, /targetSdk\s*=\s*36/u);
assert.match(appBuild, /minSdk\s*=\s*34/u);

const catalog = await readFile(
  join(root, "gradle/libs.versions.toml"),
  "utf8",
);
for (const pin of [
  'agp = "9.3.0"',
  'kotlin = "2.3.21"',
  'lifecycle = "2.10.0"',
  'glance = "1.1.1"',
  'work = "2.11.2"',
  'datastore = "1.2.1"',
]) {
  assert.ok(catalog.includes(pin), `missing pinned Android dependency: ${pin}`);
}

const wrapperProperties = await readFile(
  join(root, "gradle/wrapper/gradle-wrapper.properties"),
  "utf8",
);
assert.match(
  wrapperProperties,
  /distributionUrl=https\\:\/\/services\.gradle\.org\/distributions\/gradle-9\.5\.0-bin\.zip/u,
  "the wrapper must use Gradle 9.5.0",
);
assert.match(
  wrapperProperties,
  /distributionSha256Sum=553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746/u,
  "the wrapper distribution checksum must stay pinned",
);

process.stdout.write(
  `${JSON.stringify({
    androidPhase2Policy: "pass",
    files: files.length,
    productionKotlinFiles: productionKotlinFiles.length,
    internetPermission: false,
    cleartext: false,
    releaseTransport: "transport_gate_closed",
  })}\n`,
);

async function walk(directory) {
  const result = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) {
      if (entry.name === "build" || entry.name === ".gradle") continue;
      result.push(...(await walk(path)));
    } else if (entry.isFile()) {
      result.push(path);
    }
  }
  return result.sort();
}

async function readMatching(paths, predicate) {
  return readMany(paths.filter(predicate));
}

async function readMany(paths) {
  return (
    await Promise.all(paths.map((path) => readFile(path, "utf8")))
  ).join("\n");
}
