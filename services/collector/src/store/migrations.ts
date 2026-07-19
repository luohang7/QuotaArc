import type { DatabaseSync } from "node:sqlite";

import { STORE_SCHEMA_VERSION } from "./types.js";

interface Migration {
  version: number;
  sql: string;
}

const migrations: readonly Migration[] = [
  {
    version: 1,
    sql: `
      CREATE TABLE schema_version (
        version INTEGER PRIMARY KEY CHECK (version > 0),
        applied_at TEXT NOT NULL
      ) STRICT;

      CREATE TABLE meta (
        key TEXT PRIMARY KEY,
        value_json TEXT NOT NULL CHECK (json_valid(value_json)),
        updated_at TEXT NOT NULL
      ) STRICT;

      CREATE TABLE file_cursor (
        source_id INTEGER PRIMARY KEY,
        device_id TEXT NOT NULL,
        inode TEXT NOT NULL,
        path TEXT NOT NULL,
        size_bytes INTEGER NOT NULL CHECK (size_bytes >= 0),
        mtime_ns INTEGER NOT NULL CHECK (mtime_ns >= 0),
        byte_offset INTEGER NOT NULL CHECK (
          byte_offset >= 0 AND byte_offset <= size_bytes
        ),
        parser_version INTEGER NOT NULL CHECK (parser_version > 0),
        parser_state_json TEXT NOT NULL CHECK (json_valid(parser_state_json)),
        updated_at TEXT NOT NULL,
        UNIQUE (device_id, inode),
        UNIQUE (path)
      ) STRICT;

      CREATE TABLE normalized_event (
        event_id INTEGER PRIMARY KEY,
        event_key TEXT NOT NULL UNIQUE,
        kind TEXT NOT NULL,
        occurred_at TEXT,
        payload_json TEXT NOT NULL CHECK (json_valid(payload_json)),
        created_at TEXT NOT NULL
      ) STRICT;

      CREATE TABLE event_occurrence (
        source_id INTEGER NOT NULL REFERENCES file_cursor(source_id)
          ON DELETE CASCADE,
        occurrence_key TEXT NOT NULL,
        event_id INTEGER NOT NULL REFERENCES normalized_event(event_id)
          ON DELETE RESTRICT,
        line_number INTEGER NOT NULL CHECK (line_number > 0),
        byte_offset INTEGER NOT NULL CHECK (byte_offset >= 0),
        PRIMARY KEY (source_id, occurrence_key)
      ) STRICT;

      CREATE INDEX event_occurrence_event_id
        ON event_occurrence(event_id);

      CREATE TABLE official_quota_snapshot (
        snapshot_id INTEGER PRIMARY KEY,
        collected_at TEXT NOT NULL,
        collected_at_ms INTEGER NOT NULL CHECK (collected_at_ms >= 0),
        value_json TEXT NOT NULL CHECK (json_valid(value_json)),
        created_at TEXT NOT NULL
      ) STRICT;

      CREATE INDEX official_quota_snapshot_collected_at
        ON official_quota_snapshot(collected_at_ms DESC, snapshot_id DESC);

      CREATE TABLE last_good_source (
        source_name TEXT PRIMARY KEY CHECK (
          source_name IN ('quota', 'accountUsage', 'localUsage')
        ),
        collected_at TEXT NOT NULL,
        value_json TEXT NOT NULL CHECK (json_valid(value_json)),
        updated_at TEXT NOT NULL
      ) STRICT;
    `,
  },
];

export function applyStoreMigrations(
  database: DatabaseSync,
  nowIso: () => string,
): void {
  database.exec("BEGIN IMMEDIATE");
  try {
    // The version must be read after acquiring the write reservation. Another
    // process may have completed the first migration while this connection was
    // waiting for BEGIN IMMEDIATE.
    const currentVersion = readCurrentVersion(database);
    if (currentVersion > STORE_SCHEMA_VERSION) {
      throw new Error(
        `Collector store schema ${currentVersion} is newer than supported ${STORE_SCHEMA_VERSION}`,
      );
    }

    for (const migration of migrations) {
      if (migration.version <= currentVersion) continue;
      database.exec(migration.sql);
      database
        .prepare(
          "INSERT INTO schema_version (version, applied_at) VALUES (?, ?)",
        )
        .run(migration.version, nowIso());
      database.exec(`PRAGMA user_version = ${migration.version}`);
    }
    database.exec("COMMIT");
  } catch (error) {
    if (database.isTransaction) database.exec("ROLLBACK");
    throw error;
  }
}

function readCurrentVersion(database: DatabaseSync): number {
  const versionRow = database.prepare("PRAGMA user_version").get();
  const rawVersion = versionRow?.user_version;
  const currentVersion =
    typeof rawVersion === "bigint"
      ? Number(rawVersion)
      : typeof rawVersion === "number"
        ? rawVersion
        : 0;
  if (!Number.isSafeInteger(currentVersion) || currentVersion < 0) {
    throw new Error("Collector store schema version is not a safe integer");
  }
  return currentVersion;
}
