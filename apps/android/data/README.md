# QuotaArc Android data

This module owns the authenticated, sanitized mobile boundary:

- strict Kotlin serialization and semantic validation for the canonical v1
  summary, health, refresh-receipt, pairing, and normalized-error contracts;
- a fixed-route pinned-HTTPS client for `health`, `summary`, and `refresh`;
- HMAC-SHA256 request signing over method, path, timestamp, nonce, and the
  empty-body digest;
- certificate-validity and leaf-fingerprint checks while retaining the
  platform hostname verifier;
- exact pairing parsing and AES-256-GCM credential wrapping with a
  non-exportable Android Keystore key;
- one atomic encrypted connection document whose restore result is
  Ready/key-unavailable/invalid, plus a non-secret scheduling hint that never
  selects UI or cache identity;
- an identity-bound DataStore envelope containing latest-validated and
  last-good snapshots;
- single-flight refresh, one preserved manual follow-up, persistent fallback,
  and phone-cache freshness state;
- two-phase probe-before-commit activation, replacement-first generation
  publication, and cancellation of the previous Collector generation.

`DisabledQuotaArcDeviceApi` is now only the fail-closed state for a missing
connection or an explicitly unavailable Keystore key. The latter uses
authoritative metadata with a same-identity read-only cache; malformed
documents and AEAD/AAD failures do not. Production release wiring uses
`PinnedHttpsQuotaArcDeviceApi`; the application module owns the single
`android.permission.INTERNET` declaration.

`GET /v1/usage` is not modeled yet because its versioned response schema has
not been specified. Today's model/project data already comes from the v1
summary. Adding usage later requires a shared strict contract rather than a
generic JSON or HTTP escape hatch.

Canonical contract examples and schema are loaded directly from
`packages/contracts` as JVM test resources.

Run the module gates through the shared repository command:

```bash
pnpm android:verify
```
