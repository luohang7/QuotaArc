# @quotaarc/contracts

QuotaArc's sanitized mobile v1 read model and fixed device-transport
contracts.

The package exports TypeScript types plus `validateQuotaArcSummary`,
`assertQuotaArcSummary`, and `parseQuotaArcSummary`. The canonical JSON Schema
is exported as `@quotaarc/contracts/schema/v1/summary.schema.json`.

Phase 2B also exports strict runtime parsers and JSON Schemas for:

- Collector health and stable identity;
- refresh receipts;
- normalized device API errors;
- the one-time offline pairing bundle.

These contracts do not expose a generic HTTP method, path, request body, or
raw Codex RPC capability.

Contract choices:

- `turn` is intentionally absent from mobile v1.
- source status is `ok | stale | unavailable | unsupported | error`;
- source collection time and sanitized error state are independent;
- global `stale` is true exactly when at least one source is `stale`;
- timestamps include an ISO-8601 timezone and daily buckets use `YYYY-MM-DD`;
- Token counts are non-negative JavaScript-safe integers;
- model and project breakdowns use opaque `id` plus client-safe `label`.
