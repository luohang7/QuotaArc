# QuotaArc Android widget

This module is the connected Phase 2 Glance surface. It depends on `:data`,
uses the process-wide active repository, and does not contain a second network
client or declare `android.permission.INTERNET`.

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
  scheduler requires an authoritative Ready connection and at least one
  installed widget. A non-secret metadata hint is used only while the atomic
  restore is still pending.
- Manual refresh uses a distinct unique one-time request with `KEEP`.
- A manual request arriving behind periodic work is preserved as one shared
  follow-up by the data repository.
- Removing the last widget cancels periodic work; opening the app cannot
  recreate polling until a widget exists.
- `updatePeriodMillis` is zero, so WorkManager is the only periodic scheduler
  and there is no minute-level loop.

Release wiring restores the encrypted pairing and activates the data module's
pinned-HTTPS transport before widget `current` or `refresh` can use the
repository. Adding another HTTP client, a cleartext exception, an emulator host
shortcut, or a network permission in this module would violate the
authenticated-transport boundary.

## Tests

The pure JVM tests cover:

- primary-bucket selection and preservation of every medium bucket;
- local reset-time and deterministic freshness formatting;
- independent and combined cached/offline and Collector-stale presentation;
- safe not-configured/authentication/security states and label redaction;
- refresh and accessibility text;
- local activity accounting without reasoning double counting;
- distinct unique `KEEP` schedules, the 30-minute period, scheduling gates,
  and refresh-indicator expiry;
- compact, medium, empty, no-quota, and refresh Glance compositions.

The shared local gate runs JVM tests, lint, debug assembly, and minified/R8
release assembly:

```bash
pnpm android:verify
```

The GitHub `android-workmanager` job additionally boots an API 34 Gradle
managed device and uses WorkManager `TestDriver` to exercise the unique-work,
period-delay, initial-delay, input, and retry lifecycle. See
`docs/IMPLEMENTATION_STATUS.md` for the latest evidence boundary; an emulator
result never substitutes for Xiaomi 14/HyperOS acceptance.
