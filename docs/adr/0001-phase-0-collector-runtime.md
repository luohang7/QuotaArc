# ADR 0001: Phase 0 Collector runtime and scope

- Status: accepted for Phase 0
- Date: 2026-07-19

## Context

QuotaArc needs a small, packageable local Collector before Android or Vela
clients consume its contract. The installed development machine already has a
recent Node.js runtime and the Codex app-server. Android and AIoT toolchains are
not installed yet.

The production transport from the Mac to Xiaomi 14 is deliberately unresolved.
Opening an unauthenticated LAN listener just to make the first demo work would
violate the documented security boundary.

## Decision

1. Use TypeScript, ESM, pnpm workspaces, and the installed Node.js runtime.
2. Keep the contract package independent from the Collector implementation.
3. Build Phase 0 as a headless CLI vertical slice:
   - `quotaarc doctor`
   - `quotaarc collect --once`
   - `quotaarc usage --period ... --group-by ...`
4. Keep app-server normalization and session parsing dependency-light and
   fixture-driven.
5. Treat turn identity as private index metadata. The mobile v1 contract exposes
   only day, model, and safe project labels.
6. Give each source its own status and timestamp. The top-level `stale` flag is
   only a summary of those independent states.
7. Keep all serving behavior loopback-only until a separate authenticated
   Collector-to-phone transport spike is accepted.
8. Defer quota forecasting until official sample persistence and its contract
   are implemented.

## SQLite packaging spike

Node's built-in `node:sqlite` is the first Phase 1 candidate because it avoids a
native third-party addon and is present in the installed runtime.

The Phase 0 persistence spike was completed on the installed Node 24.17.0
runtime. The compiled Collector now proves:

- database creation and a recorded, idempotent v1 migration;
- migration serialization across processes by taking `BEGIN IMMEDIATE` before
  reading `user_version`, so a waiter rechecks the version before running DDL;
- WAL mode, foreign keys, strict tables, and rollback-safe transactions;
- a POSIX/macOS storage boundary in which the caller must provide an existing,
  current-user-owned `0700` parent directory; the store rejects missing or
  group/other-accessible parents and never creates or chmods an arbitrary
  caller-selected directory;
- explicit creation, remediation, and verification of the database, WAL, and
  shared-memory files at `0600`;
- lossless bigint storage for inode, nanosecond mtime, size, and byte cursors;
- cursor identity based on device plus inode, independent of private path;
- normalized event content deduplicated separately from per-file occurrences;
- safe source replacement, truncation cleanup, missing-source removal, and
  orphan collection;
- official quota snapshots and per-source last-good JSON surviving close and
  reopen.

The focused SQLite suite exercises archive moves, inode replacement, truncation,
fork/multi-file event deduplication, transaction rollback, migration reopen,
simultaneous first-open migration from multiple Node processes, private
directory rejection, database/sidecar modes, strict calendar timestamps, and
last-good recovery without a third-party native dependency.

`node:sqlite` is therefore accepted for the Phase 0/early Phase 1 store on the
current Node 24 baseline. This is not yet the final production packaging
decision: clean-machine packaging on the minimum supported Node version,
backup/restore, migration upgrades beyond v1, corruption recovery, and corpus
performance still require their later release gates. POSIX ACL inspection and
non-POSIX permission models also remain packaging/release concerns; the current
decision is the explicit macOS mode-bit baseline.

## Consequences

- Phase 0 can run and test without Android Studio or AIoT-IDE.
- No mobile client is allowed to depend on implementation-only fields.
- A successful CLI probe is not evidence that phone transport or Watch S4
  real-device delivery is ready.
- Phase 1 must still add durable cursors, last-good snapshots, the read-only
  loopback API, performance measurements, and upgrade tests.
