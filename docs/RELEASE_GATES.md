# QuotaArc release gates

Date: 2026-07-20

A checked source/test gate is not physical-device or operational evidence.

## Collector and contract

- [x] Sanitized v1 summary fixtures and validation pass.
- [x] Strict device health, pairing, refresh receipt, and normalized error
      contracts pass.
- [x] Current Codex app-server quota probe passes without exposing secrets.
- [x] Current session corpus cold-indexes within 30 seconds and 250 MiB.
- [ ] Durable warm scans meet the 500 ms no-change target and normally read only
      appended bytes.
- [x] Fixture/store regressions cover repeated, append, archive, truncate,
      replace, fork, replay, and counter reset.
- [x] A single-file Collector installs and runs outside the workspace.
- [ ] A 24-hour Collector soak preserves restart-safe last-good state.

## Authenticated phone transport

- [x] ADR 0003 is accepted.
- [x] Loopback default, concrete-IP LAN opt-in, wildcard rejection, TLS 1.3,
      private state/key permissions, and certificate SAN validation are
      implemented and tested.
- [x] Stable Collector/device identity plus one-time issue, list, and revoke
      primitives are implemented and package-smoked; the issue-new, save-new,
      revoke-old rotation order is documented for physical acceptance.
- [x] HMAC timestamp/nonce replay protection, scope enforcement, rate limits,
      fixed routes, strict errors, response bounds, and no redirect/raw RPC are
      tested.
- [x] Android pins the leaf SHA-256 certificate, checks validity, retains
      hostname verification, and forbids cleartext.
- [x] Android encrypts the token with Keystore AES-256-GCM and atomically stores
      metadata plus ciphertext.
- [x] Test never persists; Save probes before commit and keeps the old
      connection on pre-commit failure.
- [x] Cache is bound to `collectorId`; switching cancels the old repository
      generation and disabled restore cannot rebind persistent cache.
- [x] Manual refresh intent is preserved as one follow-up behind periodic work.
- [x] Delayed `current` and completed `refresh` calls re-check the active
      generation and cannot return a previous Collector after switching.
- [ ] Same-LAN firewall, pin, authentication, revoke, and recovery behavior is
      demonstrated on the physical Xiaomi 14.

## Android automated gates

- [x] Static policy reports one `INTERNET` permission, cleartext false,
      `pinned_https`, and Keystore AES-GCM storage.
- [x] Android rejects redirects, oversized responses, identity mismatch, and
      invalid summaries without committing a candidate.
- [x] Android JVM tests pass: data 63, widget 22, app 12.
- [x] Glance composition tests cover compact/medium/empty/no-quota and refresh.
- [x] Android lint and debug APK assembly pass.
- [x] Minified/R8 release APK assembly and resource shrinking pass.
- [x] Widget instrumentation sources and test APK compile.
- [x] An isolated local API 34 Gradle managed device ran the WorkManager
      TestDriver integration test on 2026-07-20.
- [x] GitHub `android` job passes on implementation commit `c5e6af1`
      ([run 29697179837](https://github.com/luohang7/QuotaArc/actions/runs/29697179837)).
- [x] The same GitHub run uses a managed API 34 device for the WorkManager
      TestDriver test and uploads the `workmanager-managed-device-reports`
      artifact.

## Xiaomi 14 / HyperOS

- [ ] ADB sees the exact Xiaomi 14 serial and the debug APK installs.
- [ ] Full pairing JSON tests without saving, then saves and displays the same
      `collectorId`.
- [ ] Wrong pin, hostname mismatch, bad token, and revoked token save nothing
      on the physical device; synthetic protocol mutations remain automated
      gates.
- [ ] Compact widget is uncropped and shows the lowest remaining bucket.
- [ ] Medium widget is uncropped and exposes all current buckets plus today's
      activity.
- [ ] App/widget manual refresh, offline fallback, Collector restart, and later
      recovery are recorded.
- [ ] Collector identity switch never shows the old identity's snapshot.
- [ ] Process death and reboot restore the encrypted connection/cache and
      background schedule.
- [ ] HyperOS battery saver and overnight Doze timing are measured without a
      request loop.
- [ ] Light/dark, large text, and TalkBack remain legible and meaningful.

Use [the physical runbook](XIAOMI14_CONNECTION_AND_ACCEPTANCE.md). Emulator,
JVM, lint, and APK results cannot check these rows.

## Watch S4 simulator

- [ ] AIoT-IDE and a 466×466 simulator are installed and recorded.
- [ ] Simulator build, circular safe-area screenshots, request coalescing, and
      cached offline behavior pass.

## Watch S4 real device

- [ ] Xiaomi partner debugging access is granted.
- [ ] Required OTA and beta Xiaomi Wear app are available.
- [ ] Xiaomi's minimal interconnect demo confirms package/signing requirements.
- [ ] A third-party installation or release channel is confirmed.
- [ ] Installation, launch, paired-phone transfer, reconnect, and reboot are
      demonstrated on the watch.

Until every real-watch row is checked with evidence, status remains
`Watch simulator only; real device blocked`.
