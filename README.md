# QuotaArc

QuotaArc is a local-first companion for viewing Codex quota and sanitized
usage on Android and, later, Xiaomi Watch S4.

The connected Phase 2 Android MVP consists of:

- a local Collector that reads Codex through local stdio and session files;
- a strict aggregate-only v1 contract;
- a same-LAN, fixed-route HTTPS device API;
- an Android app with test-before-save pairing and source diagnostics;
- a responsive Glance widget with manual and 30-minute WorkManager refresh.

Codex credentials, prompts, messages, tool arguments, and absolute paths never
enter the phone contract.

## Status

Implemented on 2026-07-20:

- shared strict schemas for summary, health, pairing, refresh receipts, and
  normalized device errors;
- Collector TLS identity, one-time device issue, list, revoke, separate rate
  limits, HMAC timestamp/nonce replay protection, and explicit concrete-IP LAN
  opt-in;
- only `GET /v1/health`, `GET /v1/summary`, and `POST /v1/refresh`;
- Android leaf-certificate SHA-256 pinning while retaining platform hostname
  verification, no redirects, no cleartext, and a 256 KiB response bound;
- Android Keystore AES-256-GCM credential protection with metadata as
  authenticated associated data;
- test-only connection probing and probe-then-commit save semantics;
- phone-cache isolation by stable `collectorId`;
- one manual follow-up when a manual refresh arrives behind an in-flight
  periodic refresh;
- Glance unit tests, a WorkManager TestDriver managed-device test, debug and
  minified-release build gates.

Current local evidence is recorded in
[implementation status](docs/IMPLEMENTATION_STATUS.md). Physical Xiaomi 14
behavior remains a separate release gate and must follow the
[connection and acceptance runbook](docs/XIAOMI14_CONNECTION_AND_ACCEPTANCE.md).

## Security model

- The Collector listens on `127.0.0.1` by default.
- LAN mode requires both `--allow-lan` and one concrete interface IP; wildcard
  listeners are rejected.
- Android pins the paired certificate and does not override hostname
  verification.
- Requests are device-scoped HMAC-SHA256 signatures over method, fixed path,
  timestamp, nonce, and the empty-body digest.
- The private `devices.json` registry stores authentication-equivalent derived
  verification keys. It is mode-restricted, gitignored, and must be protected
  like any other credential even though plaintext tokens are not recoverable
  from it.
- Pairing is offline CLI-to-phone transfer; there is no unauthenticated pairing
  endpoint, discovery broadcast, raw RPC, CORS surface, or hosted relay.
- Revocation is checked on every request. Rotation is issue-new, save-new,
  revoke-old.

See [ADR 0003](docs/adr/0003-direct-pinned-tls-device-transport.md).

## Develop and verify

Requirements:

- Node.js 24 or newer
- pnpm 11
- OpenSSL 3.x for device TLS identity generation
- JDK 17, Android Platform 36, Build Tools 36, and accepted SDK licenses for
  Android builds
- a signed-in Codex installation for live Collector checks

```bash
pnpm install --frozen-lockfile
pnpm run ci
pnpm android:verify
```

`pnpm run ci` builds and tests the TypeScript workspace, runs the packaged CLI
smoke test, and enforces the Android connected-transport security policy.
`pnpm android:verify` runs Android JVM tests, lint, debug assembly, and the
minified release build.

## Connect a phone

Build the packaged Collector:

```bash
pnpm --filter @quotaarc/collector bundle
```

Replace `192.168.1.23` with the Mac's actual stable LAN address:

```bash
node services/collector/dist/package/quotaarc.mjs \
  device tls-init --host 192.168.1.23

node services/collector/dist/package/quotaarc.mjs \
  device issue \
  --label "Xiaomi 14" \
  --endpoint https://192.168.1.23:8443

node services/collector/dist/package/quotaarc.mjs \
  serve \
  --allow-lan \
  --host 192.168.1.23 \
  --port 8443
```

Paste the complete one-time JSON from `device issue` into Android Setup. “Test
connection” never persists or switches state. “Save connection” repeats the
strict probe, commits the encrypted credential, clears the draft, and
activates the new Collector.

List or revoke without revealing credentials:

```bash
node services/collector/dist/package/quotaarc.mjs device list
node services/collector/dist/package/quotaarc.mjs \
  device revoke --id <deviceId>
```

Do not expose the listener with router port forwarding.

## Documents

- [Research and validation](docs/RESEARCH_AND_VALIDATION.md)
- [Development plan](docs/DEVELOPMENT_PLAN.md)
- [Implementation status and evidence](docs/IMPLEMENTATION_STATUS.md)
- [Release gates](docs/RELEASE_GATES.md)
- [Xiaomi 14 connection and acceptance](docs/XIAOMI14_CONNECTION_AND_ACCEPTANCE.md)

Watch S4 simulator and real-device delivery remain outside the connected phone
MVP. Real Watch S4 readiness still depends on Xiaomi partner access.
