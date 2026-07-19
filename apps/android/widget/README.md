# QuotaArc Android widget

This module is the Phase 2 Glance surface. It depends on `:data` and does not
contain a network client or declare `android.permission.INTERNET`.

## Delivered behavior

- `SizeMode.Responsive` compact and medium layouts.
- Compact view selects the quota window with the lowest remaining percentage
  and shows its absolute reset time and freshness.
- Medium view keeps every current quota window in a scrollable list and shows
  today's local processed Token activity.
- Empty, cached fallback, Collector-stale, authentication, offline,
  incompatible-data, and refreshing states have fixed safe copy.
- Day/night resource palettes and a composed accessibility description cover
  every bucket, activity, freshness, and refresh state.
- Widget content comes only through `QuotaArcRepository`; Collector source
  freshness and phone cache freshness remain separate.
- One unique 30-minute periodic WorkManager request uses `KEEP`; the
  gate-closed release does not enqueue it and therefore does not create
  background failure writes.
- Manual refresh uses a distinct unique one-time request with `KEEP`.
- `updatePeriodMillis` is zero, so WorkManager is the only periodic scheduler
  and there is no minute-level loop.

The release repository remains backed by the data module's gate-closed
transport. Adding an HTTP client, cleartext exception, emulator host shortcut,
or network permission here would violate the authenticated-transport release
gate.

## Tests

The pure JVM tests cover:

- primary-bucket selection and preservation of every medium bucket;
- local reset-time and deterministic freshness formatting;
- independent and combined cached/offline and Collector-stale presentation;
- safe gate-closed empty state and label redaction;
- refresh and accessibility text;
- local activity accounting without reasoning double counting;
- distinct unique `KEEP` schedules, the 30-minute period, gate-closed periodic
  suppression, and refresh-indicator expiry.

Run after the recorded JDK/SDK/Gradle gate is available:

```bash
gradle -p apps/android :widget:testDebugUnitTest
gradle -p apps/android :widget:lintDebug :widget:assembleDebug
```

An isolated JDK 17 and Gradle 9.5.0 configured the module and resolved its
dependencies. The full tasks still stopped before Kotlin compilation because
Platform 36 / Build Tools 36 are absent and their Android SDK licenses have not
been accepted. This is an environment gate, not a passing test claim.

The checked-in sources were nevertheless verified with:

- XML parsing for every module resource and manifest;
- exact default/`zh-rCN` translatable-key parity;
- a source-level network-boundary scan;
- direct API-signature review against the official Glance 1.1.1 and
  WorkManager 2.11.2 source artifacts.
