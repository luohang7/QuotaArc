export {
  CollectorStore,
  STORE_SCHEMA_VERSION,
  openCollectorStore,
} from "./sqlite-store.js";

export {
  CollectorStoreSecurityError,
  type CollectorStoreSecurityErrorCode,
} from "./filesystem-security.js";

export type {
  CollectorStoreOptions,
  CursorUpsertResult,
  EventOccurrenceInput,
  FileCursorInput,
  FileIdentity,
  LastGoodSource,
  LastGoodSourceName,
  NormalizedEventInput,
  OfficialQuotaSnapshot,
  StoredEventOccurrence,
  StoredFileCursor,
  StoreCounts,
} from "./types.js";
