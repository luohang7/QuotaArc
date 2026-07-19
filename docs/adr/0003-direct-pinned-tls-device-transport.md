# ADR 0003: Direct pinned-TLS device transport

- Status: accepted for Phase 2B
- Date: 2026-07-19

## Context

Phase 2A deliberately shipped an Android data boundary with no network
permission and a `transport_gate_closed` implementation. The Collector exposed
only local CLI and stdio behavior. A connected Xiaomi 14 build now needs a
production transport without exposing Codex credentials, session contents,
absolute paths, or arbitrary app-server RPC to the LAN.

The first Android MVP is used on the same trusted-owner network as the Mac. A
hosted relay would add an external operator, data retention, deployment, and
availability boundary without solving a current cross-NAT requirement.

## Decision

### Transport and listener

QuotaArc uses a direct HTTPS connection from Android to the Collector.

- The Collector still defaults to `127.0.0.1`.
- A non-loopback listener requires both `--allow-lan` and a concrete IP passed
  with `--host`.
- Wildcard listeners (`0.0.0.0` and `::`) are rejected.
- The server uses Node's HTTPS implementation with TLS 1.3, bounded headers,
  request timeouts, bounded connections, and no CORS or redirects.
- QuotaArc never modifies the macOS firewall. Allowing the signed Collector
  binary through the firewall remains an explicit user action.
- v1 has no broadcast or multicast discovery. The endpoint is transferred in
  the pairing bundle, which avoids publishing Collector presence on the LAN.

A sanitized relay remains a possible later transport for cross-NAT access. It
requires a separate ADR and must preserve end-to-end device authentication.

### Server identity

`quotaarc device tls-init --host <collector-ip>` creates a private TLS key and a
self-signed certificate with the selected IP or hostname in its subject
alternative name.

- The state directory and private key are restricted to the current user.
- The pairing bundle carries the uppercase SHA-256 fingerprint of the leaf
  certificate.
- Android accepts only that exact certificate, checks its validity period, and
  retains the platform hostname verifier so the certificate SAN must also
  match the configured origin.
- There is no trust-all manager and no hostname-verifier override.
- Replacing the TLS key or certificate requires a new pairing bundle.

### Collector and device identity

The private device registry contains one stable random `collectorId` and
device records. It is stored in a current-user-owned private directory and
written atomically with mode `0600`.

Each device record contains:

```text
deviceId
safe label
SHA-256 verification key
scopes
createdAt
revokedAt
```

The one-time CLI output contains a token in this form:

```text
qa1.<deviceId>.<256-bit random secret>
```

The plaintext token and secret are never stored by the Collector. Android
wraps the token with an Android Keystore AES-GCM key. Non-secret connection
metadata contains the endpoint, `collectorId`, and certificate fingerprint.

`quotaarc device issue` is the v1 offline pairing ceremony. It outputs the
complete pairing bundle once on the local terminal after the TLS certificate
already exists. There is intentionally no unauthenticated remote pairing
endpoint in v1. Rotation is issue-new-then-revoke-old; revocation is performed
locally with `quotaarc device revoke --id <deviceId>` and is checked on every
request.

### Request authentication and replay protection

The device derives `verificationKey = SHA-256(secret UTF-8)` and signs:

```text
UPPERCASE_METHOD
fixed_path
epoch_seconds
base64url_random_nonce
SHA-256(empty body)
```

with HMAC-SHA256. It sends:

```text
Authorization: QuotaArc-HMAC <deviceId>:<base64url signature>
X-QuotaArc-Timestamp: <epoch seconds>
X-QuotaArc-Nonce: <base64url nonce>
```

The Collector accepts a small bounded clock skew, compares signatures in
constant time, and rejects a nonce already used by that device inside the
window. All v1 requests have an empty body. Authentication does not provide the
TLS server identity; the certificate pin and SAN check do.

Device scopes are fixed to:

```text
summary.read
refresh.write
```

Read and refresh requests have separate per-device rate limits.

### Fixed API surface

Only these routes exist:

```text
GET  /v1/health
GET  /v1/summary
POST /v1/refresh
```

- `health` returns the strict v1 health contract, stable `collectorId`, server
  time, and effective capabilities.
- `summary` returns only a value accepted by the shared
  `QuotaArcSummary` validator.
- `refresh` runs one bounded Collector collection. Concurrent requests share
  one in-flight operation and receive strict refresh receipts.
- Every successful response includes `X-QuotaArc-Collector-Id`, which must
  match the pairing bundle.
- Query strings, fragments, request bodies, unknown methods, and unknown paths
  are rejected.
- Responses are at most 256 KiB. Error bodies contain only a normalized code,
  safe generic message, and retryability; they contain no exception, path,
  command, prompt, credential, or app-server value.
- `/v1/usage` remains deferred. Android detail views use the model and project
  groups already present in the shared summary contract.

### Android activation and cache behavior

A connection is not persisted merely because its draft parses. Android must:

1. validate every pairing field;
2. establish pinned TLS with hostname verification;
3. authenticate `health` and confirm `collectorId`;
4. fetch and strictly validate a v1 summary;
5. only then persist the encrypted credential and activate the repository.

The persistent snapshot envelope is bound to the stable `collectorId`.
Switching identities atomically discards the previous identity's snapshot
before recording a result, so one Collector's last-good data cannot appear
under another connection.

A manual refresh arriving during periodic work is preserved as one coalesced
manual follow-up. It is never silently downgraded to the periodic fetch.

## Commands

For a same-LAN Collector whose concrete address is `192.0.2.10`:

```text
quotaarc device tls-init --host 192.0.2.10
quotaarc device issue \
  --label "Xiaomi 14" \
  --endpoint https://192.0.2.10:8443
quotaarc serve \
  --allow-lan \
  --host 192.0.2.10 \
  --port 8443
quotaarc device list
quotaarc device revoke --id <deviceId>
```

The documentation address is illustrative; the user must select the Mac's
actual private interface address and keep the certificate SAN, pairing
endpoint, and listener address identical.

## Verification gates

Automated tests must cover:

- strict health, pairing, refresh, and error contracts;
- registry permissions, absence of plaintext device secrets, issue/list/revoke;
- missing, invalid, replayed, expired, wrong-scope, and revoked requests;
- rate limits, unknown routes, forbidden query/body, response limits, and no
  redirect/raw-RPC behavior;
- TLS certificate identity and explicit LAN opt-in;
- concurrent refresh single-flight;
- Android signature vectors, TLS pin/hostname behavior, error mapping,
  response limits, Collector identity switching, and manual-refresh priority;
- packaged CLI smoke for TLS initialization and device lifecycle.

These tests prove the repository implementation, not the physical environment.
Xiaomi 14 installation, macOS firewall behavior, network changes, HyperOS
widget cropping, reboot/process death, battery saver, and overnight Doze remain
real-device release gates.

## Consequences

- The first connected Android MVP requires no hosted relay and never exposes
  raw Codex interfaces.
- Pairing requires deliberate local CLI-to-phone transfer, which is less
  convenient than QR pairing but removes an unauthenticated network endpoint.
- Self-signed certificate rotation is intentionally visible and requires
  re-pairing.
- HMAC nonces are process-local; after a Collector restart the short timestamp
  window remains the replay bound. Persisted nonce history can be added if a
  future threat model requires restart-spanning replay rejection.
- Stable Collector identity and revocation survive Collector restarts.
