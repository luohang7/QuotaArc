# Android app Phase 2A implementation report

## Delivered

- `dev.quotaarc.android` application, activity, process graph, and ViewModel.
- Material 3 light/dark Compose shell with setup and detail destinations.
- HTTPS-origin and token-draft validation. A valid test/save attempt clears the
  token, performs no connection, persists no endpoint or credential, and
  reports the accepted `transport_gate_closed` boundary.
- `QuotaArcData.createGateClosedRepository` integration. The UI observes the
  persistent last-good sanitized snapshot and uses the repository's
  single-flight manual refresh path.
- Source-separated detail presentation for official quota windows, official
  daily activity, local model/project activity, and per-source diagnostics.
- Collector `summary.stale` and phone-cache freshness/fallback are represented
  separately. Mobile cache state never rewrites Collector source semantics.
- Explicit refresh states for running, success, stale fallback, gate closed,
  and normalized failure codes.
- English and Simplified Chinese resources, semantic headings, labeled
  controls, and determinate progress accessibility state.
- Backup/transfer exclusions, `usesCleartextTraffic="false"`, and no
  `android.permission.INTERNET`.
- Pure unit tests for setup validation, source separation, safe presentation
  rejection, processed-token semantics, and phone-cache/Collector-stale
  separation.

## Verification

- `node scripts/check-android-phase2.mjs`: pass
  (`internetPermission=false`, `cleartext=false`,
  `releaseTransport=transport_gate_closed`).
- `xmllint --noout` over every app XML resource and manifest: pass.
- English and Simplified Chinese string/plural resource names: matched.
- Gradle 9.5.0 configuration and dependency resolution: pass under an isolated
  Temurin 17.0.19 runtime.
- `testDebugUnitTest lintDebug assembleDebug`: stopped before Kotlin compilation
  because Platform 36 / Build Tools 36 are absent and their Android SDK licenses
  have not been accepted.

## Open evidence gates

Android compilation, lint, instrumented rendering, Xiaomi 14/HyperOS behavior,
reboot/process-death recovery, and overnight background behavior remain
unverified until the documented JDK/Android Studio/SDK/ADB and physical-device
gates are available. This is a gate-closed Phase 2A vertical slice, not a
connected-phone release.
