# QuotaArc

QuotaArc is a local-first companion for viewing AI quota and usage on a phone
and watch.

The first target is:

- Codex account quota and reset times
- Official Codex account Token activity
- Local Codex usage grouped by day, model, project, and turn
- A Xiaomi 14 Android app and Glance home-screen widget
- A Xiaomi Watch S4 Vela app

## Project status

Research and local feasibility validation were completed on 2026-07-19.
Phase 0/1 Collector work and the gate-closed Phase 2A Android vertical slice
started on 2026-07-19.

Implemented so far:

- a pnpm/TypeScript monorepo with macOS CI;
- a strict, versioned, sanitized v1 contract with JSON Schema and examples;
- a Codex app-server JSONL client for official quota and account activity;
- a streaming active/archive session indexer with counter-reset and fork/replay
  handling;
- a permission-hardened Node SQLite cursor/event/last-good persistence spike
  with serialized first-open migrations;
- live `doctor`, `collect --once`, and grouped `usage` CLI commands;
- a single-file packaged CLI with a clean-install smoke test;
- fixture, contract, privacy, RPC, indexer, store, snapshot, and CLI tests;
- a strict Android v1 decoder, persistent last-good DataStore cache, and
  single-flight refresh repository;
- an English/Chinese Material 3 setup and source-separated detail app;
- a responsive English/Chinese Glance widget with dark/light colors,
  accessibility text, manual refresh, and a unique 30-minute WorkManager policy
  that remains dormant while the transport gate is closed;
- a pinned Gradle Wrapper and an Android CI job.

Still gated or incomplete:

- durable SQLite cursors are not yet wired into the live scanner;
- live last-good/cursor integration, the loopback device API, authentication,
  and refresh coalescing remain Phase 1 work;
- the Android release transport intentionally remains
  `transport_gate_closed`; the app has no `INTERNET` permission and stores no
  endpoint or credential;
- Android Studio/JDK/SDK/ADB are not installed as a usable host toolchain;
  isolated JDK/Gradle configuration passed, but SDK 36 packages and licenses
  still block Android compilation;
- the Android app/widget have not been built, linted, or exercised on a Xiaomi
  14;
- AIoT-IDE and the Watch S4 simulator build have not started;
- real Watch S4 delivery remains gated by Xiaomi partner access.

The earlier validation established that:

- the installed Codex app-server exposes both account rate limits and account
  Token activity;
- local Codex session files contain enough information for model and project
  statistics, but these statistics are local estimates rather than an official
  bill;
- Xiaomi 14 can use the standard Android Glance and WorkManager stack;
- Watch S4 supports Vela `system.fetch`, local storage, and phone
  interconnection;
- Watch S4 real-device debugging is currently restricted to specific Xiaomi
  partners, so watch delivery must be gated separately from the phone MVP.

## Documents

- [Research and validation](docs/RESEARCH_AND_VALIDATION.md)
- [Development plan](docs/DEVELOPMENT_PLAN.md)
- [Implementation status and evidence](docs/IMPLEMENTATION_STATUS.md)
- [Release gates](docs/RELEASE_GATES.md)

## Planned source layout

```text
QuotaArc/
├── apps/
│   ├── android/
│   └── watch-vela/
├── services/
│   └── collector/
├── packages/
│   └── contracts/
└── docs/
```

The old working name `HyperQuota` has been replaced by `QuotaArc`.

## Develop and run

Requirements:

- Node.js 24 or newer
- pnpm 11
- an installed, signed-in Codex app-server for live official data

```bash
pnpm install --frozen-lockfile
pnpm run ci
pnpm build
pnpm --filter @quotaarc/collector package:smoke

node services/collector/dist/src/cli/main.js doctor
node services/collector/dist/src/cli/main.js collect --once
node services/collector/dist/src/cli/main.js usage \
  --period today \
  --group-by model
```

The live commands read Codex through local stdio and scan local session JSONL.
They do not read OAuth credentials, prompts, messages, or tool arguments into
the device-facing v1 contract. Project output is reduced to a safe basename and
a one-way identifier.

See [release gates](docs/RELEASE_GATES.md) before interpreting a successful
Collector run as Android, Watch simulator, or real-device readiness.

The Android source and its security policy can be checked without an SDK:

```bash
pnpm android:policy
```

After the developer has accepted the Android SDK licenses and installed
Platform 36 plus Build Tools 36:

```bash
cd apps/android
./gradlew testDebugUnitTest lintDebug assembleDebug
```
