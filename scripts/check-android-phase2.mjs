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
  "app/src/main/res/xml/network_security_config.xml",
  "widget/src/main/AndroidManifest.xml",
]) {
  assert.ok(relativeFiles.has(required), `missing Android Phase 2 file: ${required}`);
}

const manifestFiles = files.filter((file) =>
  file.endsWith("AndroidManifest.xml")
);
const manifests = await readMany(manifestFiles);
const internetPermissions = manifests.match(
  /<uses-permission\b[^>]*android:name\s*=\s*"android\.permission\.INTERNET"[^>]*\/?>/gu,
) ?? [];
assert.equal(
  internetPermissions.length,
  1,
  "the connected release must declare INTERNET exactly once",
);
assert.doesNotMatch(
  manifests,
  /usesCleartextTraffic\s*=\s*"true"/u,
  "cleartext traffic must stay disabled",
);
const appManifest = await readFile(
  join(root, "app/src/main/AndroidManifest.xml"),
  "utf8",
);
assert.match(appManifest, /usesCleartextTraffic\s*=\s*"false"/u);
assert.match(
  appManifest,
  /networkSecurityConfig\s*=\s*"@xml\/network_security_config"/u,
);
const networkSecurity = await readFile(
  join(root, "app/src/main/res/xml/network_security_config.xml"),
  "utf8",
);
assert.match(networkSecurity, /cleartextTrafficPermitted\s*=\s*"false"/u);
assert.doesNotMatch(networkSecurity, /cleartextTrafficPermitted\s*=\s*"true"/u);

const productionKotlinFiles = files.filter(
  (file) =>
    extname(file) === ".kt" &&
    file.includes("/src/main/"),
);
const productionEntries = await Promise.all(
  productionKotlinFiles.map(async (file) => ({
    path: relative(root, file),
    source: await readFile(file, "utf8"),
  })),
);
const productionKotlin = productionEntries
  .map(({ source }) => source)
  .join("\n");

for (const [pattern, message] of [
  [/\b10\.0\.2\.2\b/u, "emulator loopback bypass is forbidden"],
  [/\bokhttp3\b|\bOkHttpClient\b/u, "an unreviewed generic HTTP client is forbidden"],
  [/\bhttp:\/\//u, "cleartext origins are forbidden"],
  [/\bsetHostnameVerifier\b|\bsetDefaultHostnameVerifier\b/u,
    "hostname-verifier overrides are forbidden"],
  [/\bALLOW_ALL_HOSTNAME_VERIFIER\b|\bTrustAll\b|\btrustAll\b/u,
    "trust-all TLS behavior is forbidden"],
  [/\bsetDefaultSSLSocketFactory\b/u,
    "process-wide TLS socket factory changes are forbidden"],
  [/\bWebView\b/u, "the device API must not use a WebView transport"],
]) {
  assert.doesNotMatch(productionKotlin, pattern, message);
}

const tlsTransportPath =
  "data/src/main/kotlin/dev/quotaarc/android/data/api/DeviceHttpTransport.kt";
const signerPath =
  "data/src/main/kotlin/dev/quotaarc/android/data/api/DeviceRequestSigner.kt";
for (const entry of productionEntries) {
  if (entry.path !== tlsTransportPath) {
    assert.doesNotMatch(
      entry.source,
      /\bHttpsURLConnection\b|\bX509TrustManager\b|\bSSLContext\b/u,
      `TLS primitives are restricted to ${tlsTransportPath}: ${entry.path}`,
    );
  }
  if (entry.path !== signerPath) {
    assert.doesNotMatch(
      entry.source,
      /["']Authorization["']|\bAUTHORIZATION_HEADER\b/u,
      `the Authorization header is restricted to ${signerPath}: ${entry.path}`,
    );
  }
}

const tlsTransport = sourceFor(productionEntries, tlsTransportPath);
for (const requirement of [
  /\bHttpsURLConnection\b/u,
  /\bLeafCertificatePinTrustManager\b/u,
  /\bcheckValidity\b/u,
  /\bMessageDigest\.isEqual\b/u,
  /instanceFollowRedirects\s*=\s*false/u,
  /sslSocketFactory\s*=\s*socketFactory/u,
  /\bMAX_RESPONSE_BYTES\b|\bmaxResponseBytes\b/u,
]) {
  assert.match(tlsTransport, requirement, "pinned HTTPS transport invariant missing");
}

const signer = sourceFor(productionEntries, signerPath);
for (const requirement of [
  /HmacSHA256/u,
  /EMPTY_BODY_SHA256/u,
  /X-QuotaArc-Timestamp/u,
  /X-QuotaArc-Nonce/u,
  /QuotaArc-HMAC/u,
]) {
  assert.match(signer, requirement, "request-signing invariant missing");
}

const deviceApi = sourceFor(
  productionEntries,
  "data/src/main/kotlin/dev/quotaarc/android/data/api/PinnedHttpsQuotaArcDeviceApi.kt",
);
for (const route of ["/v1/health", "/v1/summary", "/v1/refresh"]) {
  assert.match(
    `${signer}\n${deviceApi}`,
    new RegExp(route.replaceAll("/", "\\/"), "u"),
    `fixed device route missing: ${route}`,
  );
}
for (const requirement of [
  /response\.redirect_forbidden/u,
  /response\.collector_identity_mismatch/u,
  /application\/json/u,
  /256\s*\*\s*1024|DEFAULT_MAX_PAYLOAD_BYTES/u,
]) {
  assert.match(deviceApi, requirement, "strict device API invariant missing");
}

const keystore = sourceFor(
  productionEntries,
  "data/src/main/kotlin/dev/quotaarc/android/data/connection/AndroidKeystoreCredentialCipher.kt",
);
for (const requirement of [
  /AndroidKeyStore/u,
  /AES\/GCM\/NoPadding/u,
  /PURPOSE_ENCRYPT\s+or\s+KeyProperties\.PURPOSE_DECRYPT/u,
  /setRandomizedEncryptionRequired\(true\)/u,
  /cipher\.iv/u,
]) {
  assert.match(keystore, requirement, "Keystore credential invariant missing");
}

const pairing = sourceFor(
  productionEntries,
  "data/src/main/kotlin/dev/quotaarc/android/data/connection/DevicePairingCodec.kt",
);
assert.match(pairing, /MAX_PAIRING_BYTES\s*=\s*16\s*\*\s*1024/u);
assert.match(pairing, /ignoreUnknownKeys\s*=\s*false/u);

const connectionManager = sourceFor(
  productionEntries,
  "data/src/main/kotlin/dev/quotaarc/android/data/connection/QuotaArcConnectionManager.kt",
);
for (const requirement of [
  /suspend fun test\(/u,
  /suspend fun testAndSave\(/u,
  /store\.replace\(connection\)/u,
  /switchingRepository\.activate\(repository\)/u,
]) {
  assert.match(
    connectionManager,
    requirement,
    "two-phase connection activation invariant missing",
  );
}
assert.ok(
  connectionManager.indexOf("store.replace(connection)") <
    connectionManager.indexOf("switchingRepository.activate(repository)"),
  "the encrypted connection must commit before repository activation",
);

for (const requirement of [
  /createActiveRepository/u,
  /hasConnectionMetadata/u,
  /connection\.not_configured/u,
  /credential\.unavailable/u,
  /collectorIdentity/u,
  /trigger\s*==\s*RefreshTrigger\.MANUAL/u,
]) {
  assert.match(productionKotlin, requirement, "connected repository invariant missing");
}
assert.doesNotMatch(
  productionKotlin,
  /transport_gate_closed/u,
  "the connected production release must not expose the obsolete transport gate",
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
const widgetScheduler = sourceFor(
  productionEntries,
  "widget/src/main/kotlin/dev/quotaarc/android/widget/WidgetSyncScheduler.kt",
);
for (const requirement of [
  /QuotaArcData\.authoritativeRestoreResult\(\)/u,
  /metadataHint\s*=\s*QuotaArcData\.hasConnectionMetadata\(context\)/u,
  /is ConnectionRestoreResult\.Ready\s*->\s*true/u,
  /null\s*->\s*metadataHint/u,
  /is ConnectionRestoreResult\.CredentialUnavailable/u,
]) {
  assert.match(
    widgetScheduler,
    requirement,
    "periodic work must prefer authoritative restored connection state",
  );
}
assert.match(
  productionKotlin,
  /ExistingWorkPolicy\.KEEP/u,
  "manual refresh work must be coalesced",
);

const appBuild = await readFile(join(root, "app/build.gradle.kts"), "utf8");
assert.match(appBuild, /applicationId\s*=\s*"dev\.quotaarc\.android"/u);
assert.match(appBuild, /targetSdk\s*=\s*36/u);
assert.match(appBuild, /minSdk\s*=\s*34/u);
assert.match(appBuild, /isMinifyEnabled\s*=\s*true/u);

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

const localVerification = await readFile(
  resolve("scripts/verify-android.sh"),
  "utf8",
);
for (const task of [
  "testDebugUnitTest",
  "lintDebug",
  "assembleDebug",
  "assembleRelease",
]) {
  assert.match(
    localVerification,
    new RegExp(`(?:^|\\s)${task}(?:\\s|$)`, "u"),
    `local Android verification must run ${task}`,
  );
}

const githubCi = await readFile(resolve(".github/workflows/ci.yml"), "utf8");
assert.match(
  githubCi,
  /run:\s+bash scripts\/verify-android\.sh/u,
  "GitHub CI must use the shared Android verification script",
);
assert.match(
  githubCi,
  /:widget:pixel2Api34DebugAndroidTest/u,
  "GitHub CI must run the WorkManager TestDriver managed-device test",
);

process.stdout.write(
  `${JSON.stringify({
    androidPhase2Policy: "pass",
    files: files.length,
    productionKotlinFiles: productionKotlinFiles.length,
    internetPermission: true,
    cleartext: false,
    releaseTransport: "pinned_https",
    pairingStorage: "android_keystore_aes_gcm",
  })}\n`,
);

function sourceFor(entries, path) {
  const match = entries.find((entry) => entry.path === path);
  assert.ok(match, `missing production source: ${path}`);
  return match.source;
}

async function walk(directory) {
  const result = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) {
      if (
        entry.name === "build" ||
        entry.name === ".gradle" ||
        entry.name === ".kotlin" ||
        entry.name === ".idea"
      ) {
        continue;
      }
      result.push(...(await walk(path)));
    } else if (entry.isFile()) {
      if (entry.name === ".DS_Store" || entry.name === "local.properties") {
        continue;
      }
      result.push(path);
    }
  }
  return result.sort();
}

async function readMany(paths) {
  return (
    await Promise.all(paths.map((path) => readFile(path, "utf8")))
  ).join("\n");
}
