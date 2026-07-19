# QuotaArc Android

This directory contains the connected Xiaomi 14 Phase 2 MVP:

- `data`: strict v1 decoding, pinned HTTPS, HMAC request signing, encrypted
  connection storage, identity-bound last-good cache, and repository logic;
- `widget`: responsive Glance UI, a unique 30-minute WorkManager schedule, a
  coalesced manual refresh, JVM composition tests, and a TestDriver integration
  test;
- `app`: bilingual pairing/setup and source-separated detail screens.

## Pairing and transport

The app accepts only the complete v1 pairing JSON emitted by
`quotaarc device issue`.

- Test performs pinned TLS, hostname verification, authenticated health, stable
  Collector identity, summary fetch, and strict contract validation. It does
  not persist or switch anything.
- Save repeats the same probe before atomically replacing the encrypted
  metadata+credential document and activating the repository.
- The token is wrapped by a non-exportable Android Keystore AES-256-GCM key;
  the Cipher generates the 96-bit IV, and connection metadata is bound as AAD.
- Phone snapshots are bound to `collectorId`, so a new Collector cannot render
  another identity's last-good cache.
- The Details screen displays the validated non-secret configured
  `collectorId` for pairing and identity-switch evidence.
- Cleartext, redirects, emulator loopback shortcuts, generic RPC, and hostname
  verifier overrides are forbidden by the static policy.

See the accepted
[transport ADR](../../docs/adr/0003-direct-pinned-tls-device-transport.md).

## Toolchain and commands

- AGP 9.3.0
- Gradle 9.5.0
- JDK 17
- compile/target SDK 36
- min SDK 34

```bash
pnpm android:policy
pnpm android:verify
```

The shared verification script runs:

```text
testDebugUnitTest
lintDebug
assembleDebug
assembleRelease
```

GitHub CI additionally boots an API 34 Gradle managed device and runs the
WorkManager TestDriver integration test.

The application ID `dev.quotaarc.android` remains provisional until Xiaomi
interconnect package/signature requirements are verified.

## Physical acceptance

An APK, JVM test, lint report, emulator test, or CI run does not prove HyperOS
widget layout or background behavior. Follow
[the Xiaomi 14 runbook](../../docs/XIAOMI14_CONNECTION_AND_ACCEPTANCE.md) for
pairing, compact/medium resize, offline recovery, revocation, process death,
reboot, battery saver, Doze, dark mode, and accessibility evidence.
