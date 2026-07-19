# Android app Phase 2B implementation report

Date: 2026-07-20

## Delivered

- `dev.quotaarc.android` application, process graph, ViewModel, bilingual
  Material 3 setup/detail screens, and Glance widget integration.
- One secret-bearing input accepts only the complete pairing JSON emitted by
  the Collector CLI and caps UTF-8 input at 16 KiB.
- **Test connection** performs pinned TLS, hostname verification, HMAC device
  authentication, stable Collector identity, and strict summary validation. It
  does not persist or activate the candidate.
- **Save connection** repeats the strict probe, then atomically commits the
  metadata plus encrypted credential document before activating the new
  repository. Probe/pre-commit failure keeps the previous connection and
  draft; after the authoritative write begins, persistence and activation are
  one non-cancellable boundary. Success clears the draft and navigates to
  Details.
- The device token is wrapped with an Android Keystore AES-256-GCM key. The
  Cipher supplies a random 96-bit IV, and connection metadata is authenticated
  as AAD.
- The app requests `INTERNET` exactly once, disables cleartext in both the
  manifest and network security configuration, refuses redirects, and does not
  override the platform hostname verifier.
- App and widget share a stable switching repository. Snapshot persistence is
  bound to `collectorId`; only an explicit Keystore-key-unavailable result gets
  the authoritative identity's read-only cache, while corrupt documents and
  AAD failures stay fail-closed.
- Cold restore reads metadata and credential from one atomic DataStore
  snapshot; the derived scheduling index never selects UI or cache identity.
- Switching Collector publishes the replacement before old cancellation can
  resume a waiter, then cancels the previous generation. Delayed direct-cache
  reads and completed refresh results re-check the generation, preventing an
  older result from being returned after the switch.
- Setup save schedules periodic work only when a widget exists. Removing the
  final widget cancels the schedule; opening the app cannot recreate needless
  polling without an installed widget.
- Source-separated detail presentation preserves Collector source staleness
  independently from phone-cache current/aged/fallback state.
- App manual refresh invalidates all installed widget instances after updating
  the shared cache.
- Details displays the validated, non-secret configured `collectorId`, including
  after cold-start metadata restoration, so pairing and identity switching can
  be recorded directly.

## Automated coverage

- Data tests cover strict pairing and wire decoding, Node/Kotlin signature
  vector parity, redirect and response bounds, identity mismatch, TLS pin
  match/mismatch/missing/expired/not-yet-valid cases, encrypted storage,
  test-versus-save activation, cache identity switching, read-only failure
  behavior, manual-follow-up coalescing, caller cancellation, and previous
  generation deactivation, authoritative-commit cancellation, delayed-current,
  and completed-refresh switch races.
- App JVM tests cover pairing-draft byte limits, detail/source/cache mapping,
  test-without-save behavior, save success/failure side effects, authoritative
  Ready/key-unavailable/invalid restore, stale or missing derived indexes, and
  foreground refresh.
- Glance JVM composition tests cover compact, medium, empty, no-quota,
  accessibility, and refresh-action states.
- The managed-device WorkManager TestDriver test passed locally on an isolated
  API 34 ARM emulator and covers unique periodic/manual
  `KEEP`, periodic delay, manual initial delay, input propagation, and retry
  attempt state.

## Verification boundary

The repository-level verification commands and exact dated results are
recorded in
[implementation status](../../../docs/IMPLEMENTATION_STATUS.md).

No physical Xiaomi 14 was connected to this Mac during implementation.
HyperOS cropping, resize, reboot, process death, Wi-Fi changes, battery saver,
overnight Doze, and accessibility remain open until the
[physical acceptance runbook](../../../docs/XIAOMI14_CONNECTION_AND_ACCEPTANCE.md)
is executed. Emulator, JVM, lint, and APK evidence do not close those rows.
