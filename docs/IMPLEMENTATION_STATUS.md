# QuotaArc implementation status

Date: 2026-07-20

## Current milestone

Phase 2B connected Android MVP source is implemented.

The result is no longer the Phase 2A gate-closed skeleton: the Collector has an
accepted, authenticated device API; Android has a production pinned-HTTPS
adapter and encrypted pairing storage; the app and widget use the active
repository; and CI contains debug, lint, minified-release, Glance, and managed
device WorkManager gates.

This statement is about implementation and automated evidence. Xiaomi
14/HyperOS physical acceptance remains open.

## Delivered scope

### Shared contract and Collector

- Strict v1 summary plus strict health, pairing, refresh-receipt, and device
  error schemas and runtime parsers.
- Aggregate-only mobile data with global sensitive-text/path rejection.
- A private device registry with stable `collectorId`, one-time token issue,
  list, revoke, and no stored plaintext device token or secret.
- Self-signed TLS identity generation with SAN validation and private key/state
  permissions.
- Explicit same-LAN opt-in requiring a concrete interface IP; loopback stays
  the default and wildcard listeners are rejected.
- HMAC-SHA256 method/path/timestamp/nonce authentication, constant-time
  signature comparison, bounded skew, replay rejection, per-device scopes, and
  separate read/refresh rate limits.
- Only `GET /v1/health`, `GET /v1/summary`, and `POST /v1/refresh`; no query,
  request body, redirect, discovery, CORS, raw app-server RPC, or relay.
- Bounded, validated summary caching and one Collector-side in-flight refresh.

### Android data and lifecycle

- Exact pairing parsing with a 16 KiB UTF-8 limit.
- HMAC signature parity with the Collector.
- Leaf-certificate SHA-256 pinning plus certificate validity checks while
  leaving the platform hostname verifier intact.
- Fixed HTTPS routes, five/ten-second connect/read timeouts, redirect rejection,
  strict JSON/identity/error parsing, and a 256 KiB response limit.
- Android Keystore AES-256-GCM token protection using a Cipher-generated
  96-bit IV and connection metadata as AAD.
- Test-only probe versus probe-then-commit save. Probe and pre-commit failures
  preserve the old connection; once the authoritative encrypted commit begins,
  persistence, repository activation, and authoritative state publication form
  a non-cancellable boundary.
- Cold restore reads identity and encrypted credential from one atomic DataStore
  snapshot. A derived SharedPreferences index is only a startup scheduling hint
  and can never choose UI or cache identity.
- Stable switching repository and `collectorId`-bound phone cache.
- Switching publishes the replacement generation before cancellation can
  resume an old waiter, then cancels the old generation.
- Delayed direct-cache reads and already-completed refreshes re-check the
  active generation, so callers cannot consume an old Collector result after a
  switch.
- An explicitly missing or permanently invalidated Keystore key uses
  authoritative metadata plus a same-identity read-only cache. AAD, ciphertext,
  or document corruption fails closed and cannot select a cache identity.
- A manual request arriving behind periodic work queues one shared manual
  follow-up; caller cancellation does not cancel shared work.

### App, widget, and automation

- English/Chinese pairing UI with hidden secret input, distinct Test and Save
  actions, safe normalized failures, draft clearing only after save success,
  a visible configured `collectorId`, and source-separated details.
- Responsive compact/medium Glance layouts, dark/light resources,
  accessibility text, explicit empty/fallback/auth/security states, and manual
  refresh.
- Unique 30-minute periodic WorkManager and coalesced manual work. Periodic
  work requires an authoritative `Ready` restore and at least one installed
  widget; a derived hint is considered only while restore is pending.
- Glance JVM composition tests and a WorkManager TestDriver managed-device
  integration test.
- Shared Android verification script for JVM tests, lint, debug APK, and
  minified/R8 release APK.

## Verification evidence

Checks run on this Mac on 2026-07-20:

| Check | Result |
|---|---|
| Root build, typecheck, tests, package smoke, Android policy | `pnpm run ci` passed |
| Shared contracts | 8/8 passed |
| Collector | 71/71 passed |
| Packaged Collector clean-install smoke | passed; 28,683-byte archive, 2 entries |
| Android connected security policy | passed across 114 source/config files / 50 production Kotlin files |
| Android policy result | one `INTERNET` permission; cleartext false; `pinned_https`; `android_keystore_aes_gcm` |
| Android data JVM tests | 63/63 passed |
| Android widget JVM tests | 22/22 passed |
| Android app JVM tests | 12/12 passed |
| Android lint/debug/minified release | `testDebugUnitTest lintDebug assembleDebug assembleRelease` passed with Platform/Build Tools 36 and JDK 17 |
| Debug APK | built |
| Minified release APK | built unsigned with R8 and resource shrinking |
| Widget instrumentation sources/APK | compiled and assembled |
| WorkManager TestDriver on an emulator | passed on an isolated local API 34 ARM Gradle managed device; 1/1 instrumentation test |
| GitHub PR CI for implementation commit `c5e6af1` | [`verify`, `android`, and `android-workmanager` passed](https://github.com/luohang7/QuotaArc/actions/runs/29697179837); managed-device reports uploaded |

The Android toolchain above was an isolated temporary JDK/SDK used for
verification. It included temporary emulator and ADB tooling, but does not mean
Android Studio or a permanent SDK is installed for the developer account.

The prior current-corpus Collector cold scan remains:

| Metric | Result | Target |
|---|---:|---:|
| Bytes scanned | 532,134,560 | current corpus |
| Files | 34 | informational |
| Normalized Token events | 50,360 | informational |
| Diagnostics | 0 | 0 |
| Elapsed | 972 ms | at most 30,000 ms |
| Sampled peak RSS | 166.2 MiB | at most 250 MiB |

That cold-scan evidence does not close the durable warm no-change,
appended-byte-only, or 24-hour soak gates.

## Physical-device evidence boundary

At implementation time this Mac had no Android Studio, permanent Android SDK,
or connected Android USB device. The isolated API 34 emulator run is not a
Xiaomi/HyperOS run. Therefore there is no claim for:

- install or pairing on a Xiaomi 14;
- macOS firewall/same-LAN behavior;
- compact/medium HyperOS widget cropping;
- process death, reboot, Wi-Fi loss/recovery, battery saver, or overnight Doze;
- TalkBack/large text on the target device.

Every row remains executable in
[Xiaomi 14 connection and acceptance](XIAOMI14_CONNECTION_AND_ACCEPTANCE.md).
Blank rows must stay open.

## Remaining gates

- Execute and record the Xiaomi 14 physical matrix.
- Wire the live scanner fully to durable SQLite cursors and restart-safe
  last-good values, prove the 500 ms warm target, and run the 24-hour Collector
  soak.
- Install AIoT-IDE and build the Watch S4 simulator.
- Obtain Xiaomi partner access before claiming real Watch S4 readiness.
