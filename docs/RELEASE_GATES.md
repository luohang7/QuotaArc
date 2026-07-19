# QuotaArc release gates

This file keeps environment and external dependencies visible. A gate is not
complete merely because source code or a package skeleton exists.

## Collector and contract

- [x] Sanitized v1 contract fixtures and validation pass.
- [x] Current Codex app-server quota probe passes without exposing secrets.
- [x] Current local session corpus cold-indexes within the 30-second and
      250-MiB limits.
- [ ] Durable warm scans meet the 500-ms no-change target and normally read only
      appended bytes.
- [x] Fixture and store regressions verify idempotence for repeated, append,
      archive, truncate, replace, fork, replay, and counter-reset cases.
- [x] A packaged single-file Collector installs and runs outside the workspace.
- [ ] A 24-hour Collector soak preserves its last-good snapshot.
- [ ] Authenticated Collector-to-phone transport has an accepted ADR and test
      evidence.
- [ ] Real transport partitions the phone cache by stable Collector identity
      and preserves a manual refresh arriving during an in-flight periodic
      fetch.

See [implementation status](IMPLEMENTATION_STATUS.md) for dated measurements
and the boundary between the cold-scan proof and the open durable warm-scan
work.

## Xiaomi 14

- [x] Gate-closed Phase 2A source implements strict v1 decoding, persistent
      last-good cache, single-flight refresh, source-separated app UI, a
      responsive Glance widget, and a unique 30-minute WorkManager policy that
      stays dormant while transport is closed.
- [x] Static policy verifies no release `INTERNET` permission, no cleartext or
      trust-all client, no emulator bypass, and the explicit
      `transport_gate_closed` adapter.
- [x] Gradle 9.5.0 configures all three Android modules and resolves their
      pinned dependencies under an isolated JDK 17.
- [ ] Android Studio, JDK, SDK, and ADB are installed and recorded.
- [ ] SDK 36 licenses are accepted by the developer; Android unit tests, lint,
      resource processing, and debug assembly pass.
- [ ] Glance hello-world builds and runs on the physical Xiaomi 14.
- [ ] Widget resize, HyperOS cropping, reboot, process death, network loss,
      battery saver, and overnight Doze behavior are measured.

## Watch S4 simulator

- [ ] AIoT-IDE and a 466×466 simulator are installed and recorded.
- [ ] Simulator build, circular safe-area screenshots, request coalescing, and
      cached offline behavior pass.

## Watch S4 real device

- [ ] Xiaomi partner debugging access is granted.
- [ ] Required OTA and beta Xiaomi Wear app are available.
- [ ] Xiaomi's minimal interconnect demo confirms package-name and signing
      requirements.
- [ ] A third-party installation or release channel is confirmed.
- [ ] Installation, launch, paired-phone transfer, reconnect, and reboot are
      demonstrated on the physical watch.

Until every real-device item is checked with evidence, status must remain
`Watch simulator only; real device blocked`.
