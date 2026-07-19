# QuotaArc Android data

This module owns the sanitized mobile boundary:

- strict Kotlin serialization and semantic validation for the canonical v1
  summary contract;
- a fixed `health` / `summary` / `refresh` device API capability interface;
- an atomic DataStore envelope containing latest-validated and last-good
  snapshots;
- single-flight refresh, persistent fallback, and phone-cache freshness state;
- an HTTPS-only endpoint draft validator.

Release wiring uses `DisabledQuotaArcDeviceApi` and declares no internet
permission. Endpoint and credential persistence intentionally do not exist
while the authenticated Collector-to-phone transport gate is closed.

`GET /v1/usage` is not modeled yet because its versioned response schema has
not been specified. Today's model/project data already comes from the v1
summary. Adding usage later requires a shared strict contract rather than a
generic JSON or HTTP escape hatch.

Canonical contract examples and schema are loaded directly from
`packages/contracts` as JVM test resources.
