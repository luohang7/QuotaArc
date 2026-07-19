# QuotaArc implementation status

Date: 2026-07-19

## Delivered scope

The immediate Phase 0 Collector proof, part of the Phase 1 Collector core, and
the gate-closed Phase 2A Android vertical slice are implemented:

- a pnpm/TypeScript workspace and macOS CI;
- a strict, versioned v1 device contract with JSON Schema, sanitized examples,
  and runtime validation;
- a supervised Codex app-server JSONL client for quota and optional account
  activity;
- a streaming active/archive session scanner with replay, fork, partial-line,
  counter-reset, and sensitive-path handling;
- local day/model/project aggregation;
- a Node SQLite persistence spike for cursors, normalized events, quota
  samples, and last-good source values;
- `doctor`, `collect --once`, and grouped `usage` commands;
- a single-file packaged CLI with a clean-install smoke test;
- strict Android v1 decoding and semantic validation against the canonical
  shared fixtures;
- an atomic DataStore envelope for latest-validated, last-good, and last-attempt
  state;
- a repository-level single-flight refresh boundary that never replaces
  last-good data with transport, authentication, malformed, unsupported,
  non-renderable, or out-of-order responses;
- a bilingual Material 3 setup/detail app and bilingual responsive Glance
  widget with dark/light and accessibility states;
- unique WorkManager policy for a 30-minute periodic refresh and a coalesced
  manual refresh; the periodic request is not enqueued while the release
  transport gate is closed.

The device-facing contract contains aggregate quota and usage only. It rejects
credential-shaped text, absolute POSIX and Windows paths, file URIs, home
relative paths, turn data, prompts, messages, tool arguments, and unknown
fields.

Phase 2A deliberately has no real phone transport. Release code uses only
`DisabledQuotaArcDeviceApi`, returns `transport_gate_closed`, declares no
`INTERNET` permission, and never persists the setup draft. Collector source
freshness and phone-cache fallback/age remain separate states.

## Verification evidence

The following checks passed on this Mac on 2026-07-19:

| Check | Result |
|---|---|
| Root build, typecheck, tests, and package smoke | `pnpm run ci` passed |
| Contract tests | 7 passed |
| Collector tests | 61 passed |
| Packaged install | 18,773-byte tarball, 2 entries, installed and ran from a temporary directory |
| Doctor | Node v24.17.0, Codex 0.145.0-alpha.18, active/archive session roots available |
| Live collection | valid v1 contract; quota, account activity, and local usage all `ok` |
| Live grouped usage | `today`/`model` query returned local source `ok` with 3 sanitized groups |
| Live redaction check | no home path, credential pattern, file URI, prompt/message/tool-argument key, thread, turn, or path key |
| SQLite contention | 12 simultaneous first-open processes completed with one v1 migration record |
| SQLite permissions | private `0700` parent required; database, WAL, and SHM verified as `0600` |
| App-server lifecycle | timeout, optional-method failure, ignored `SIGTERM`, forced termination, and immediate restart regressions passed |
| Android static security policy | passed across 88 Android files / 38 production Kotlin files; no `INTERNET`, cleartext, HTTP adapter, emulator shortcut, or custom TLS trust |
| Android XML and wrapper syntax | all manifests/resources parsed; `gradlew` shell syntax passed |
| Android Gradle configuration | Gradle 9.5.0 configured `:data`, `:widget`, and `:app`; pinned dependency resolution passed under isolated Temurin 17.0.19 |

The live verification deliberately reported only source states and structural
counts; it did not print account quota percentages, Token totals, or project
names.

### Current-corpus cold scan

The streaming scanner was measured in a fresh Node process against the current
active plus archived session corpus:

| Metric | Result | Documented target |
|---|---:|---:|
| Bytes scanned | 532,134,560 | current corpus |
| Files | 34 | informational |
| Normalized Token events | 50,360 | informational |
| Diagnostics | 0 | 0 expected |
| Elapsed time | 972 ms | at most 30,000 ms |
| Sampled peak RSS | 166.2 MiB | at most 250 MiB |

This proves only the cold-scan targets on the current machine and corpus.
Durable warm no-change scans and appended-byte-only scans are not yet wired
through the live SQLite cursor store, so the 500 ms warm target and incremental
read target remain open.

### Android Phase 2A verification boundary

There are 41 Android unit-test methods in source covering canonical v1 parsing,
unknown and sensitive fields, timestamp/date exactness, DataStore persistence,
last-good fallback, source/cache freshness separation, out-of-order data,
40-caller single-flight refresh, setup validation, detail mapping, widget
mapping, and scheduling policy.

Those Android tests have **not** run. An isolated JDK 17 and verified Gradle
9.5.0 distribution completed Gradle configuration and dependency resolution,
then the full command stopped before Kotlin compilation because Platform 36 and
Build Tools 36 were absent and their Android SDK licenses had not been accepted:

```text
License for package Android SDK Build-Tools 36 not accepted.
License for package Android SDK Platform 36 not accepted.
```

No license was accepted automatically. Consequently there is no passing Android
unit-test, lint, AAPT/resource, APK, emulator, Xiaomi 14, reboot, Doze, resize,
or HyperOS-cropping claim yet.

## Open gates

- Wire the live scanner to SQLite cursors, last-good values, and restart-safe
  rebuild rules.
- Add the read-only cached API only after an authenticated Collector-to-phone
  transport ADR is accepted. The default remains local stdio/loopback; there is
  no unauthenticated LAN endpoint.
- Before that transport is enabled, add a stable Collector identity for cache
  partition/switch behavior and define how a manual refresh arriving during a
  periodic fetch upgrades or queues one coalesced follow-up.
- Run the 24-hour soak and measure cached API p95 latency.
- Have the developer accept the Android SDK licenses, install and record
  Android Studio/JDK/SDK/ADB, then run Android unit tests, lint, and debug
  assembly.
- Exercise the app and Glance widget on a physical Xiaomi 14, including resize,
  cropping, reboot, process death, network changes, battery saver, and an
  overnight Doze sample.
- Install AIoT-IDE and exercise a 466×466 Watch S4 simulator build.
- Obtain Xiaomi partner access before claiming real Watch S4 readiness.

Until those device gates have evidence, the project is a verified Collector
vertical slice plus a gate-closed Android source vertical slice, not a
connected Android/Watch MVP.
