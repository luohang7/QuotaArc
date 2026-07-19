# ADR 0002: Phase 2A Android transport boundary

- Status: accepted for Phase 2A
- Date: 2026-07-19

## Context

The Android app needs a typed data client, persistent last-good state, refresh
coordination, and UI before device work can proceed. The Collector currently
has local stdio/CLI entry points only. Its planned `/v1/health`, `/v1/summary`,
`/v1/usage`, and `/v1/refresh` endpoints are not implemented, and only the
summary has a shared wire contract.

Inventing a cleartext LAN endpoint, using emulator loopback or ADB reverse, or
proxying raw Codex RPC would cross the documented security boundary and make a
fixture demo look like a production transport decision.

## Decision

1. Phase 2A implements a fixed `QuotaArcDeviceApi` abstraction. It exposes only
   health, v1 summary, and coalesced refresh capabilities; it does not accept
   arbitrary methods, paths, or request bodies. The planned grouped usage
   endpoint is not guessed before it has a shared versioned response contract;
   the v1 summary's `localUsage.models` and `localUsage.projects` provide the
   Phase 2A detail views.
2. Release code uses `DisabledQuotaArcDeviceApi`, which returns
   `transport_gate_closed`. Canonical fixtures and scripted transports are test
   dependencies only.
3. The Android manifests do not request `INTERNET`, and cleartext traffic is
   disabled. No trust-all TLS code, raw RPC bridge, `10.0.2.2`, or ADB-reverse
   fallback is added.
4. Setup validates only a draft HTTPS origin. It rejects credentials in URLs,
   user info, query strings, fragments, non-root paths, cleartext schemes, and
   control characters. The gate-closed build does not persist a partial
   connection or device credential.
5. Mobile sync failures are separate from Collector source freshness. A cached
   envelope records receipt and attempt times plus a normalized mobile failure;
   it never mutates the v1 `summary.stale` or `sources.*` values.
6. A failed transport, authentication, schema validation, or cache write never
   replaces the last validated snapshot. A cache is scoped to one future
   Collector identity.
7. Periodic work is unique and no more frequent than every 30 minutes. Manual
   work is also unique, while the repository supplies a second single-flight
   boundary.

## Production transport unlock conditions

A real Android transport requires all of the following:

- an accepted transport ADR choosing direct authenticated TLS or a sanitized
  relay, including server identity, pairing, rotation, revocation, discovery,
  listener, and firewall behavior;
- Collector authentication middleware, a fixed endpoint allowlist, default
  loopback binding, and explicit opt-in for any non-loopback listener;
- shared schemas for health, usage, refresh receipts, normalized errors, and
  pairing, including response-size and timeout limits;
- a stable Collector identity that partitions the Android cache, with tested
  clear/switch behavior so one Collector's last-good snapshot cannot appear
  under another Collector;
- refresh-priority semantics for a manual request arriving during a periodic
  fetch: it must either upgrade the pending operation or queue one coalesced
  manual follow-up instead of silently losing the manual refresh intent;
- tests for unauthenticated, revoked, wrong-scope, rate-limited, redirect,
  replay, TLS identity mismatch, redaction, and no-raw-RPC behavior;
- Xiaomi 14 evidence for pairing, reboot, process death, and network changes.

Until then, the Android result is described as a fixture-backed vertical slice,
not a connected phone MVP.

## Consequences

- Android contract, cache, repository, widget, WorkManager, and UI work can
  progress without weakening Collector security.
- The setup screen intentionally reports the transport gate instead of
  pretending to connect.
- The authenticated transport release gate remains unchecked.
- Android Studio/SDK installation and real Xiaomi 14/HyperOS behavior remain
  separate evidence gates.

References:

- [Android network security configuration](https://developer.android.com/privacy-and-security/security-config)
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)
- [Glance widget updates](https://developer.android.com/develop/ui/compose/glance/glance-app-widget)
- [WorkManager periodic work](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)
