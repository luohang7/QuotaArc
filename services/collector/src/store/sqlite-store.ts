import { DatabaseSync } from "node:sqlite";

import { prepareCollectorStorePath } from "./filesystem-security.js";
import { applyStoreMigrations } from "./migrations.js";
import {
  STORE_SCHEMA_VERSION,
  type CollectorStoreOptions,
  type CursorUpsertResult,
  type EventOccurrenceInput,
  type FileCursorInput,
  type FileIdentity,
  type LastGoodSource,
  type LastGoodSourceName,
  type OfficialQuotaSnapshot,
  type StoredEventOccurrence,
  type StoredFileCursor,
  type StoreCounts,
} from "./types.js";

type SqlRow = Record<string, null | number | bigint | string | Uint8Array>;

export class CollectorStore implements Disposable {
  readonly database: DatabaseSync;
  readonly path: string;

  private readonly now: () => Date;
  private readonly secureStoreArtifacts: () => void;
  private savepointCounter = 0;

  constructor(path: string, options: CollectorStoreOptions = {}) {
    if (path.length === 0) throw new Error("Collector store path is required");
    const preparedPath = prepareCollectorStorePath(path);
    this.path = preparedPath.databasePath;
    this.now = options.now ?? (() => new Date());
    this.secureStoreArtifacts = () => preparedPath.secureArtifacts();
    const busyTimeoutMs = options.busyTimeoutMs ?? 5_000;
    this.database = new DatabaseSync(this.path, {
      enableForeignKeyConstraints: true,
      enableDoubleQuotedStringLiterals: false,
      allowExtension: false,
      timeout: busyTimeoutMs,
      readBigInts: true,
      returnArrays: false,
      allowBareNamedParameters: false,
      allowUnknownNamedParameters: false,
      defensive: true,
    });
    try {
      this.database.exec(`
        PRAGMA foreign_keys = ON;
        PRAGMA synchronous = NORMAL;
        PRAGMA temp_store = MEMORY;
      `);
      runWithSqliteBusyRetry(
        () => this.database.exec("PRAGMA journal_mode = WAL"),
        busyTimeoutMs,
      );
      this.secureStoreArtifacts();
      applyStoreMigrations(this.database, () => this.nowIso());
      this.secureStoreArtifacts();
    } catch (error) {
      try {
        this.secureStoreArtifacts();
      } finally {
        this.database.close();
      }
      throw error;
    }
  }

  get schemaVersion(): number {
    const row = this.database.prepare("PRAGMA user_version").get() as
      | SqlRow
      | undefined;
    return numberFromInteger(row?.user_version, "user_version");
  }

  getAppliedMigrations(): Array<{ version: number; appliedAt: string }> {
    const rows = this.database
      .prepare(
        "SELECT version, applied_at FROM schema_version ORDER BY version",
      )
      .all() as SqlRow[];
    return rows.map((row) => ({
      version: numberFromInteger(row.version, "schema_version.version"),
      appliedAt: textFromSql(row.applied_at, "schema_version.applied_at"),
    }));
  }

  transaction<T>(operation: () => T): T {
    this.assertOpen();
    if (this.database.isTransaction) {
      const savepoint = `quotaarc_store_${++this.savepointCounter}`;
      this.database.exec(`SAVEPOINT ${savepoint}`);
      try {
        const result = operation();
        assertSynchronousTransactionResult(result);
        this.database.exec(`RELEASE SAVEPOINT ${savepoint}`);
        return result;
      } catch (error) {
        this.database.exec(`ROLLBACK TO SAVEPOINT ${savepoint}`);
        this.database.exec(`RELEASE SAVEPOINT ${savepoint}`);
        throw error;
      }
    }

    this.database.exec("BEGIN IMMEDIATE");
    try {
      const result = operation();
      assertSynchronousTransactionResult(result);
      this.database.exec("COMMIT");
      this.secureStoreArtifacts();
      return result;
    } catch (error) {
      if (this.database.isTransaction) this.database.exec("ROLLBACK");
      this.secureStoreArtifacts();
      throw error;
    }
  }

  setMeta(key: string, value: unknown): void {
    assertKey(key, "meta key");
    const valueJson = canonicalJson(value);
    this.transaction(() => {
      this.database
        .prepare(
          `INSERT INTO meta (key, value_json, updated_at)
           VALUES (?, ?, ?)
           ON CONFLICT(key) DO UPDATE SET
             value_json = excluded.value_json,
             updated_at = excluded.updated_at`,
        )
        .run(key, valueJson, this.nowIso());
    });
  }

  getMeta<T = unknown>(key: string): T | undefined {
    assertKey(key, "meta key");
    const row = this.database
      .prepare("SELECT value_json FROM meta WHERE key = ?")
      .get(key) as SqlRow | undefined;
    return row
      ? parseStoredJson<T>(row.value_json, `meta.${key}`)
      : undefined;
  }

  upsertFileCursor(input: FileCursorInput): CursorUpsertResult {
    validateCursorInput(input);
    const parserStateJson = canonicalJson(input.parserState);

    return this.transaction(() => {
      const pathOwner = this.findCursorRowByPath(input.path);
      const pathOwnerMatches =
        pathOwner?.device_id === input.deviceId &&
        pathOwner.inode === input.inode;
      let replacedSource = false;

      if (pathOwner && !pathOwnerMatches) {
        this.database
          .prepare("DELETE FROM file_cursor WHERE source_id = ?")
          .run(bigintFromSql(pathOwner.source_id, "file_cursor.source_id"));
        replacedSource = true;
      }

      const existing = this.findCursorRow(input);
      const reset =
        existing !== undefined &&
        (input.size <
          bigintFromSql(existing.size_bytes, "file_cursor.size_bytes") ||
          input.byteOffset <
            bigintFromSql(existing.byte_offset, "file_cursor.byte_offset") ||
          input.parserVersion !==
            numberFromInteger(
              existing.parser_version,
              "file_cursor.parser_version",
            ));

      if (existing) {
        const sourceId = bigintFromSql(
          existing.source_id,
          "file_cursor.source_id",
        );
        if (reset) {
          this.database
            .prepare("DELETE FROM event_occurrence WHERE source_id = ?")
            .run(sourceId);
        }
        this.database
          .prepare(
            `UPDATE file_cursor
             SET path = ?,
                 size_bytes = ?,
                 mtime_ns = ?,
                 byte_offset = ?,
                 parser_version = ?,
                 parser_state_json = ?,
                 updated_at = ?
             WHERE source_id = ?`,
          )
          .run(
            input.path,
            input.size,
            input.mtimeNs,
            input.byteOffset,
            input.parserVersion,
            parserStateJson,
            this.nowIso(),
            sourceId,
          );
      } else {
        this.database
          .prepare(
            `INSERT INTO file_cursor (
               device_id,
               inode,
               path,
               size_bytes,
               mtime_ns,
               byte_offset,
               parser_version,
               parser_state_json,
               updated_at
             ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
          )
          .run(
            input.deviceId,
            input.inode,
            input.path,
            input.size,
            input.mtimeNs,
            input.byteOffset,
            input.parserVersion,
            parserStateJson,
            this.nowIso(),
          );
      }

      this.removeOrphanEventsUnsafe();
      const cursor = this.loadFileCursor(input);
      if (!cursor) throw new Error("Cursor upsert did not persist a row");
      return { cursor, reset, replacedSource };
    });
  }

  loadFileCursor(identity: FileIdentity): StoredFileCursor | undefined {
    validateIdentity(identity);
    const row = this.findCursorRow(identity);
    return row ? mapCursor(row) : undefined;
  }

  loadFileCursorByPath(path: string): StoredFileCursor | undefined {
    assertPath(path);
    const row = this.findCursorRowByPath(path);
    return row ? mapCursor(row) : undefined;
  }

  listFileCursors(): StoredFileCursor[] {
    const rows = this.database
      .prepare(
        `SELECT source_id,
                device_id,
                inode,
                path,
                size_bytes,
                mtime_ns,
                byte_offset,
                parser_version,
                parser_state_json,
                updated_at
         FROM file_cursor
         ORDER BY source_id`,
      )
      .all() as SqlRow[];
    return rows.map(mapCursor);
  }

  replaceOccurrences(
    identity: FileIdentity,
    occurrences: readonly EventOccurrenceInput[],
  ): void {
    validateIdentity(identity);
    this.transaction(() => {
      const source = this.findCursorRow(identity);
      if (!source) {
        throw new Error(
          `Cannot replace occurrences for unknown source ${identity.deviceId}:${identity.inode}`,
        );
      }
      const sourceId = bigintFromSql(
        source.source_id,
        "file_cursor.source_id",
      );
      this.database
        .prepare("DELETE FROM event_occurrence WHERE source_id = ?")
        .run(sourceId);

      const seenOccurrences = new Set<string>();
      for (const occurrence of occurrences) {
        validateOccurrence(occurrence);
        if (seenOccurrences.has(occurrence.occurrenceKey)) {
          throw new Error(
            `Duplicate occurrence key ${occurrence.occurrenceKey} in source replacement`,
          );
        }
        seenOccurrences.add(occurrence.occurrenceKey);

        const payloadJson = canonicalJson(occurrence.event.payload);
        this.database
          .prepare(
            `INSERT INTO normalized_event (
               event_key, kind, occurred_at, payload_json, created_at
             ) VALUES (?, ?, ?, ?, ?)
             ON CONFLICT(event_key) DO NOTHING`,
          )
          .run(
            occurrence.event.eventKey,
            occurrence.event.kind,
            occurrence.event.occurredAt,
            payloadJson,
            this.nowIso(),
          );

        const eventRow = this.database
          .prepare(
            `SELECT event_id, kind, occurred_at, payload_json
             FROM normalized_event
             WHERE event_key = ?`,
          )
          .get(occurrence.event.eventKey) as SqlRow | undefined;
        if (!eventRow) throw new Error("Event upsert did not persist a row");

        const storedOccurredAt = nullableTextFromSql(
          eventRow.occurred_at,
          "normalized_event.occurred_at",
        );
        if (
          eventRow.kind !== occurrence.event.kind ||
          storedOccurredAt !== occurrence.event.occurredAt ||
          eventRow.payload_json !== payloadJson
        ) {
          throw new Error(
            `Conflicting content for global event key ${occurrence.event.eventKey}`,
          );
        }

        this.database
          .prepare(
            `INSERT INTO event_occurrence (
               source_id, occurrence_key, event_id, line_number, byte_offset
             ) VALUES (?, ?, ?, ?, ?)`,
          )
          .run(
            sourceId,
            occurrence.occurrenceKey,
            bigintFromSql(eventRow.event_id, "normalized_event.event_id"),
            occurrence.lineNumber,
            occurrence.byteOffset,
          );
      }

      this.removeOrphanEventsUnsafe();
    });
  }

  loadOccurrences(identity: FileIdentity): StoredEventOccurrence[] {
    validateIdentity(identity);
    const source = this.findCursorRow(identity);
    if (!source) return [];
    const sourceId = bigintFromSql(
      source.source_id,
      "file_cursor.source_id",
    );
    const rows = this.database
      .prepare(
        `SELECT o.source_id,
                o.occurrence_key,
                o.line_number,
                o.byte_offset,
                e.event_id,
                e.event_key,
                e.kind,
                e.occurred_at,
                e.payload_json
         FROM event_occurrence o
         JOIN normalized_event e ON e.event_id = o.event_id
         WHERE o.source_id = ?
         ORDER BY o.line_number, o.byte_offset, o.occurrence_key`,
      )
      .all(sourceId) as SqlRow[];

    return rows.map((row) => ({
      sourceId: bigintFromSql(row.source_id, "event_occurrence.source_id"),
      eventId: bigintFromSql(row.event_id, "normalized_event.event_id"),
      occurrenceKey: textFromSql(
        row.occurrence_key,
        "event_occurrence.occurrence_key",
      ),
      lineNumber: numberFromInteger(
        row.line_number,
        "event_occurrence.line_number",
      ),
      byteOffset: bigintFromSql(
        row.byte_offset,
        "event_occurrence.byte_offset",
      ),
      event: {
        eventKey: textFromSql(row.event_key, "normalized_event.event_key"),
        kind: textFromSql(row.kind, "normalized_event.kind"),
        occurredAt: nullableTextFromSql(
          row.occurred_at,
          "normalized_event.occurred_at",
        ),
        payload: parseStoredJson(row.payload_json, "normalized_event.payload"),
      },
    }));
  }

  removeMissingSources(
    presentIdentities: readonly FileIdentity[],
  ): number {
    return this.transaction(() => {
      this.database.exec(`
        CREATE TEMP TABLE IF NOT EXISTS present_source_identity (
          device_id TEXT NOT NULL,
          inode TEXT NOT NULL,
          PRIMARY KEY (device_id, inode)
        ) STRICT;
        DELETE FROM present_source_identity;
      `);
      const insert = this.database.prepare(
        `INSERT OR IGNORE INTO present_source_identity (device_id, inode)
         VALUES (?, ?)`,
      );
      for (const identity of presentIdentities) {
        validateIdentity(identity);
        insert.run(identity.deviceId, identity.inode);
      }
      const removed = this.database
        .prepare(
          `DELETE FROM file_cursor
           WHERE NOT EXISTS (
             SELECT 1
             FROM present_source_identity present
             WHERE present.device_id = file_cursor.device_id
               AND present.inode = file_cursor.inode
           )`,
        )
        .run().changes;
      this.removeOrphanEventsUnsafe();
      return numberFromInteger(removed, "removed source count");
    });
  }

  removeOrphanEvents(): number {
    return this.transaction(() => this.removeOrphanEventsUnsafe());
  }

  saveOfficialQuotaSnapshot<T>(
    collectedAt: string,
    value: T,
  ): OfficialQuotaSnapshot<T> {
    assertTimestamp(collectedAt, "quota snapshot collectedAt");
    const valueJson = canonicalJson(value);
    return this.transaction(() => {
      const createdAt = this.nowIso();
      const result = this.database
        .prepare(
          `INSERT INTO official_quota_snapshot (
             collected_at, collected_at_ms, value_json, created_at
           ) VALUES (?, ?, ?, ?)`,
        )
        .run(collectedAt, Date.parse(collectedAt), valueJson, createdAt);
      return {
        snapshotId:
          typeof result.lastInsertRowid === "bigint"
            ? result.lastInsertRowid
            : BigInt(result.lastInsertRowid),
        collectedAt,
        value,
        createdAt,
      };
    });
  }

  loadLatestOfficialQuotaSnapshot<T = unknown>():
    | OfficialQuotaSnapshot<T>
    | undefined {
    const row = this.database
      .prepare(
        `SELECT snapshot_id, collected_at, value_json, created_at
         FROM official_quota_snapshot
         ORDER BY collected_at_ms DESC, snapshot_id DESC
         LIMIT 1`,
      )
      .get() as SqlRow | undefined;
    return row
      ? {
          snapshotId: bigintFromSql(
            row.snapshot_id,
            "official_quota_snapshot.snapshot_id",
          ),
          collectedAt: textFromSql(
            row.collected_at,
            "official_quota_snapshot.collected_at",
          ),
          value: parseStoredJson<T>(
            row.value_json,
            "official_quota_snapshot.value",
          ),
          createdAt: textFromSql(
            row.created_at,
            "official_quota_snapshot.created_at",
          ),
        }
      : undefined;
  }

  saveLastGood<T>(
    source: LastGoodSourceName,
    collectedAt: string,
    value: T,
  ): LastGoodSource<T> {
    assertLastGoodSource(source);
    assertTimestamp(collectedAt, "last-good collectedAt");
    const valueJson = canonicalJson(value);
    const updatedAt = this.nowIso();
    this.transaction(() => {
      this.database
        .prepare(
          `INSERT INTO last_good_source (
             source_name, collected_at, value_json, updated_at
           ) VALUES (?, ?, ?, ?)
           ON CONFLICT(source_name) DO UPDATE SET
             collected_at = excluded.collected_at,
             value_json = excluded.value_json,
             updated_at = excluded.updated_at`,
        )
        .run(source, collectedAt, valueJson, updatedAt);
    });
    return { source, collectedAt, value, updatedAt };
  }

  loadLastGood<T = unknown>(
    source: LastGoodSourceName,
  ): LastGoodSource<T> | undefined {
    assertLastGoodSource(source);
    const row = this.database
      .prepare(
        `SELECT source_name, collected_at, value_json, updated_at
         FROM last_good_source
         WHERE source_name = ?`,
      )
      .get(source) as SqlRow | undefined;
    return row
      ? {
          source: textFromSql(
            row.source_name,
            "last_good_source.source_name",
          ) as LastGoodSourceName,
          collectedAt: textFromSql(
            row.collected_at,
            "last_good_source.collected_at",
          ),
          value: parseStoredJson<T>(
            row.value_json,
            "last_good_source.value",
          ),
          updatedAt: textFromSql(
            row.updated_at,
            "last_good_source.updated_at",
          ),
        }
      : undefined;
  }

  getCounts(): StoreCounts {
    const row = this.database
      .prepare(
        `SELECT
           (SELECT COUNT(*) FROM file_cursor) AS file_cursors,
           (SELECT COUNT(*) FROM normalized_event) AS events,
           (SELECT COUNT(*) FROM event_occurrence) AS occurrences,
           (SELECT COUNT(*) FROM official_quota_snapshot) AS quota_snapshots,
           (SELECT COUNT(*) FROM last_good_source) AS last_good_sources`,
      )
      .get() as SqlRow | undefined;
    if (!row) throw new Error("Unable to read Collector store counts");
    return {
      fileCursors: numberFromInteger(row.file_cursors, "file cursor count"),
      events: numberFromInteger(row.events, "event count"),
      occurrences: numberFromInteger(row.occurrences, "occurrence count"),
      quotaSnapshots: numberFromInteger(
        row.quota_snapshots,
        "quota snapshot count",
      ),
      lastGoodSources: numberFromInteger(
        row.last_good_sources,
        "last-good count",
      ),
    };
  }

  close(): void {
    if (this.database.isOpen) this.database.close();
  }

  [Symbol.dispose](): void {
    this.close();
  }

  private findCursorRow(identity: FileIdentity): SqlRow | undefined {
    return this.database
      .prepare(
        `SELECT source_id,
                device_id,
                inode,
                path,
                size_bytes,
                mtime_ns,
                byte_offset,
                parser_version,
                parser_state_json,
                updated_at
         FROM file_cursor
         WHERE device_id = ? AND inode = ?`,
      )
      .get(identity.deviceId, identity.inode) as SqlRow | undefined;
  }

  private findCursorRowByPath(path: string): SqlRow | undefined {
    return this.database
      .prepare(
        `SELECT source_id,
                device_id,
                inode,
                path,
                size_bytes,
                mtime_ns,
                byte_offset,
                parser_version,
                parser_state_json,
                updated_at
         FROM file_cursor
         WHERE path = ?`,
      )
      .get(path) as SqlRow | undefined;
  }

  private removeOrphanEventsUnsafe(): number {
    const removed = this.database
      .prepare(
        `DELETE FROM normalized_event
         WHERE NOT EXISTS (
           SELECT 1
           FROM event_occurrence occurrence
           WHERE occurrence.event_id = normalized_event.event_id
         )`,
      )
      .run().changes;
    return numberFromInteger(removed, "removed orphan event count");
  }

  private nowIso(): string {
    const value = this.now().toISOString();
    assertTimestamp(value, "store clock");
    return value;
  }

  private assertOpen(): void {
    if (!this.database.isOpen) throw new Error("Collector store is closed");
  }
}

export function openCollectorStore(
  path: string,
  options: CollectorStoreOptions = {},
): CollectorStore {
  return new CollectorStore(path, options);
}

export { STORE_SCHEMA_VERSION };

function mapCursor(row: SqlRow): StoredFileCursor {
  return {
    sourceId: bigintFromSql(row.source_id, "file_cursor.source_id"),
    deviceId: textFromSql(row.device_id, "file_cursor.device_id"),
    inode: textFromSql(row.inode, "file_cursor.inode"),
    path: textFromSql(row.path, "file_cursor.path"),
    size: bigintFromSql(row.size_bytes, "file_cursor.size_bytes"),
    mtimeNs: bigintFromSql(row.mtime_ns, "file_cursor.mtime_ns"),
    byteOffset: bigintFromSql(
      row.byte_offset,
      "file_cursor.byte_offset",
    ),
    parserVersion: numberFromInteger(
      row.parser_version,
      "file_cursor.parser_version",
    ),
    parserState: parseStoredJson(
      row.parser_state_json,
      "file_cursor.parser_state",
    ),
    updatedAt: textFromSql(row.updated_at, "file_cursor.updated_at"),
  };
}

function validateIdentity(identity: FileIdentity): void {
  assertKey(identity.deviceId, "deviceId");
  assertKey(identity.inode, "inode");
}

function validateCursorInput(input: FileCursorInput): void {
  validateIdentity(input);
  assertPath(input.path);
  assertNonNegativeBigInt(input.size, "cursor size");
  assertNonNegativeBigInt(input.mtimeNs, "cursor mtimeNs");
  assertNonNegativeBigInt(input.byteOffset, "cursor byteOffset");
  if (input.byteOffset > input.size) {
    throw new Error("Cursor byteOffset must not exceed file size");
  }
  if (!Number.isSafeInteger(input.parserVersion) || input.parserVersion <= 0) {
    throw new Error("Cursor parserVersion must be a positive safe integer");
  }
}

function validateOccurrence(occurrence: EventOccurrenceInput): void {
  assertKey(occurrence.occurrenceKey, "occurrence key");
  assertKey(occurrence.event.eventKey, "event key");
  assertKey(occurrence.event.kind, "event kind");
  if (
    !Number.isSafeInteger(occurrence.lineNumber) ||
    occurrence.lineNumber <= 0
  ) {
    throw new Error("Occurrence lineNumber must be a positive safe integer");
  }
  assertNonNegativeBigInt(occurrence.byteOffset, "occurrence byteOffset");
  if (occurrence.event.occurredAt !== null) {
    assertTimestamp(occurrence.event.occurredAt, "event occurredAt");
  }
}

function assertPath(path: string): void {
  if (typeof path !== "string" || path.length === 0) {
    throw new Error("Private source path is required");
  }
}

function assertKey(value: string, label: string): void {
  if (
    typeof value !== "string" ||
    value.length === 0 ||
    value.length > 512
  ) {
    throw new Error(`${label} must be a non-empty string of at most 512 characters`);
  }
}

function assertNonNegativeBigInt(value: bigint, label: string): void {
  if (typeof value !== "bigint" || value < 0n) {
    throw new Error(`${label} must be a non-negative bigint`);
  }
}

function assertTimestamp(value: string, label: string): void {
  const match =
    typeof value === "string"
      ? /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d+)?(?:Z|[+-](\d{2}):(\d{2}))$/u.exec(
          value,
        )
      : null;
  if (!match) {
    throw new Error(`${label} must be an ISO-8601 timestamp with timezone`);
  }

  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const hour = Number(match[4]);
  const minute = Number(match[5]);
  const second = Number(match[6]);
  const offsetHour = match[7] === undefined ? 0 : Number(match[7]);
  const offsetMinute = match[8] === undefined ? 0 : Number(match[8]);

  const validCalendar =
    month >= 1 &&
    month <= 12 &&
    day >= 1 &&
    day <= daysInMonth(year, month) &&
    hour >= 0 &&
    hour <= 23 &&
    minute >= 0 &&
    minute <= 59 &&
    second >= 0 &&
    second <= 59;
  const validOffset =
    offsetHour >= 0 &&
    offsetHour <= 14 &&
    offsetMinute >= 0 &&
    offsetMinute <= 59 &&
    (offsetHour !== 14 || offsetMinute === 0);

  if (!validCalendar || !validOffset || Number.isNaN(Date.parse(value))) {
    throw new Error(`${label} must be an ISO-8601 timestamp with timezone`);
  }
}

function daysInMonth(year: number, month: number): number {
  if (month === 2) {
    const leapYear =
      year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
    return leapYear ? 29 : 28;
  }
  return month === 4 || month === 6 || month === 9 || month === 11
    ? 30
    : 31;
}

function runWithSqliteBusyRetry(
  operation: () => void,
  timeoutMs: number,
): void {
  const deadline = Date.now() + Math.max(0, timeoutMs);
  const waitState = new Int32Array(new SharedArrayBuffer(4));
  while (true) {
    try {
      operation();
      return;
    } catch (error) {
      if (!isSqliteBusyError(error) || Date.now() >= deadline) throw error;
      const remainingMs = deadline - Date.now();
      Atomics.wait(waitState, 0, 0, Math.min(10, remainingMs));
    }
  }
}

function isSqliteBusyError(error: unknown): boolean {
  return (
    error instanceof Error &&
    /database is (?:busy|locked)/iu.test(error.message)
  );
}

function assertLastGoodSource(
  source: string,
): asserts source is LastGoodSourceName {
  if (
    source !== "quota" &&
    source !== "accountUsage" &&
    source !== "localUsage"
  ) {
    throw new Error(`Unknown last-good source ${source}`);
  }
}

function bigintFromSql(
  value: SqlRow[string] | undefined,
  label: string,
): bigint {
  if (typeof value === "bigint") return value;
  if (typeof value === "number" && Number.isSafeInteger(value)) {
    return BigInt(value);
  }
  throw new Error(`Corrupt ${label}: expected an integer`);
}

function numberFromInteger(
  value: SqlRow[string] | undefined,
  label: string,
): number {
  const result =
    typeof value === "bigint"
      ? Number(value)
      : typeof value === "number"
        ? value
        : Number.NaN;
  if (!Number.isSafeInteger(result)) {
    throw new Error(`Corrupt ${label}: expected a safe integer`);
  }
  return result;
}

function textFromSql(
  value: SqlRow[string] | undefined,
  label: string,
): string {
  if (typeof value !== "string") {
    throw new Error(`Corrupt ${label}: expected text`);
  }
  return value;
}

function nullableTextFromSql(
  value: SqlRow[string] | undefined,
  label: string,
): string | null {
  return value === null ? null : textFromSql(value, label);
}

function parseStoredJson<T>(
  value: SqlRow[string] | undefined,
  label: string,
): T {
  const text = textFromSql(value, label);
  try {
    return JSON.parse(text) as T;
  } catch {
    throw new Error(`Corrupt ${label}: invalid JSON`);
  }
}

function canonicalJson(value: unknown): string {
  const active = new Set<object>();

  function normalize(current: unknown, path: string): unknown {
    if (
      current === null ||
      typeof current === "string" ||
      typeof current === "boolean"
    ) {
      return current;
    }
    if (typeof current === "number") {
      if (!Number.isFinite(current)) {
        throw new Error(`Cannot persist non-finite number at ${path}`);
      }
      return Object.is(current, -0) ? 0 : current;
    }
    if (Array.isArray(current)) {
      if (active.has(current)) throw new Error(`Cannot persist cycle at ${path}`);
      active.add(current);
      const result = current.map((item, index) =>
        normalize(item, `${path}[${index}]`),
      );
      active.delete(current);
      return result;
    }
    if (typeof current === "object") {
      if (active.has(current)) throw new Error(`Cannot persist cycle at ${path}`);
      const prototype = Object.getPrototypeOf(current);
      if (prototype !== Object.prototype && prototype !== null) {
        throw new Error(`Cannot persist non-JSON object at ${path}`);
      }
      active.add(current);
      const source = current as Record<string, unknown>;
      const result: Record<string, unknown> = {};
      for (const key of Object.keys(source).sort()) {
        result[key] = normalize(source[key], `${path}.${key}`);
      }
      active.delete(current);
      return result;
    }
    throw new Error(`Cannot persist ${typeof current} at ${path}`);
  }

  const normalized = normalize(value, "$");
  const serialized = JSON.stringify(normalized) as string | undefined;
  if (serialized === undefined) throw new Error("Value is not JSON serializable");
  return serialized;
}

function assertSynchronousTransactionResult(value: unknown): void {
  if (
    typeof value === "object" &&
    value !== null &&
    "then" in value &&
    typeof (value as { then?: unknown }).then === "function"
  ) {
    throw new Error("Collector store transactions must be synchronous");
  }
}
