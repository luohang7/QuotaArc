import {
  chmodSync,
  closeSync,
  constants,
  fchmodSync,
  lstatSync,
  openSync,
  realpathSync,
  statSync,
  type Stats,
} from "node:fs";
import { basename, dirname, join, resolve } from "node:path";

const PRIVATE_DIRECTORY_MODE = 0o700;
const PRIVATE_FILE_MODE = 0o600;
const SQLITE_SIDECAR_SUFFIXES = ["-wal", "-shm"] as const;

export type CollectorStoreSecurityErrorCode =
  | "PARENT_DIRECTORY_MISSING"
  | "PARENT_DIRECTORY_NOT_PRIVATE"
  | "PARENT_DIRECTORY_NOT_OWNED"
  | "PARENT_PATH_NOT_DIRECTORY"
  | "STORE_FILE_INVALID"
  | "STORE_FILE_NOT_OWNED"
  | "STORE_FILE_PERMISSIONS";

export class CollectorStoreSecurityError extends Error {
  readonly code: CollectorStoreSecurityErrorCode;
  readonly path: string;

  constructor(
    code: CollectorStoreSecurityErrorCode,
    path: string,
    message: string,
    options?: ErrorOptions,
  ) {
    super(message, options);
    this.name = "CollectorStoreSecurityError";
    this.code = code;
    this.path = path;
  }
}

export interface PreparedCollectorStorePath {
  readonly databasePath: string;
  secureArtifacts(): void;
}

/**
 * Resolves and prepares a file-backed Collector store without changing its
 * parent directory. The caller owns directory creation and must provide a
 * current-user-only directory; this prevents a library open from silently
 * changing permissions on an arbitrary user-selected directory.
 */
export function prepareCollectorStorePath(
  requestedPath: string,
): PreparedCollectorStorePath {
  if (requestedPath === ":memory:") {
    return {
      databasePath: requestedPath,
      secureArtifacts() {},
    };
  }

  const absolutePath = resolve(requestedPath);
  const requestedParent = dirname(absolutePath);
  let parentPath: string;
  try {
    parentPath = realpathSync(requestedParent);
  } catch (cause) {
    throw new CollectorStoreSecurityError(
      "PARENT_DIRECTORY_MISSING",
      requestedParent,
      `Collector store parent directory must already exist: ${requestedParent}`,
      { cause },
    );
  }

  assertPrivateParentDirectory(parentPath);
  const databasePath = join(parentPath, basename(absolutePath));
  createPrivateFileIfMissing(databasePath);
  secureRequiredFile(databasePath);

  return {
    databasePath,
    secureArtifacts() {
      secureRequiredFile(databasePath);
      for (const suffix of SQLITE_SIDECAR_SUFFIXES) {
        secureOptionalFile(`${databasePath}${suffix}`);
      }
    },
  };
}

function assertPrivateParentDirectory(path: string): void {
  const stats = statSync(path);
  if (!stats.isDirectory()) {
    throw new CollectorStoreSecurityError(
      "PARENT_PATH_NOT_DIRECTORY",
      path,
      `Collector store parent path is not a directory: ${path}`,
    );
  }

  const currentUid = process.getuid?.();
  if (currentUid !== undefined && stats.uid !== currentUid) {
    throw new CollectorStoreSecurityError(
      "PARENT_DIRECTORY_NOT_OWNED",
      path,
      `Collector store parent directory must be owned by the current user: ${path}`,
    );
  }

  const mode = stats.mode & 0o777;
  if (
    (mode & 0o077) !== 0 ||
    (mode & PRIVATE_DIRECTORY_MODE) !== PRIVATE_DIRECTORY_MODE
  ) {
    throw new CollectorStoreSecurityError(
      "PARENT_DIRECTORY_NOT_PRIVATE",
      path,
      `Collector store parent directory must have current-user-only rwx permissions (0700): ${path}`,
    );
  }
}

function createPrivateFileIfMissing(path: string): void {
  let descriptor: number | undefined;
  try {
    descriptor = openSync(
      path,
      constants.O_CREAT |
        constants.O_EXCL |
        constants.O_RDWR |
        constants.O_NOFOLLOW,
      PRIVATE_FILE_MODE,
    );
    fchmodSync(descriptor, PRIVATE_FILE_MODE);
  } catch (cause) {
    if (!isNodeErrorWithCode(cause, "EEXIST")) throw cause;
  } finally {
    if (descriptor !== undefined) closeSync(descriptor);
  }
}

function secureRequiredFile(path: string): void {
  const stats = lstatSync(path);
  assertSecureableStoreFile(path, stats);
  chmodSync(path, PRIVATE_FILE_MODE);

  const securedStats = lstatSync(path);
  assertSecureableStoreFile(path, securedStats);
  if ((securedStats.mode & 0o777) !== PRIVATE_FILE_MODE) {
    throw new CollectorStoreSecurityError(
      "STORE_FILE_PERMISSIONS",
      path,
      `Collector store file permissions could not be restricted to 0600: ${path}`,
    );
  }
}

function secureOptionalFile(path: string): void {
  let stats: Stats | undefined;
  try {
    stats = lstatSync(path);
  } catch (cause) {
    if (isNodeErrorWithCode(cause, "ENOENT")) return;
    throw cause;
  }
  if (!stats) return;

  assertSecureableStoreFile(path, stats);
  try {
    chmodSync(path, PRIVATE_FILE_MODE);
  } catch (cause) {
    // Another SQLite connection can remove an unused WAL/SHM file between the
    // lstat and chmod. A later creation is protected by the private parent and
    // will be checked on the next store open or write.
    if (isNodeErrorWithCode(cause, "ENOENT")) return;
    throw cause;
  }

  try {
    const securedStats = lstatSync(path);
    assertSecureableStoreFile(path, securedStats);
    if ((securedStats.mode & 0o777) !== PRIVATE_FILE_MODE) {
      throw new CollectorStoreSecurityError(
        "STORE_FILE_PERMISSIONS",
        path,
        `Collector store file permissions could not be restricted to 0600: ${path}`,
      );
    }
  } catch (cause) {
    if (isNodeErrorWithCode(cause, "ENOENT")) return;
    throw cause;
  }
}

function assertSecureableStoreFile(
  path: string,
  stats: Stats,
): void {
  if (!stats.isFile() || stats.isSymbolicLink()) {
    throw new CollectorStoreSecurityError(
      "STORE_FILE_INVALID",
      path,
      `Collector store artifacts must be regular files, not links or special files: ${path}`,
    );
  }

  const currentUid = process.getuid?.();
  if (currentUid !== undefined && stats.uid !== currentUid) {
    throw new CollectorStoreSecurityError(
      "STORE_FILE_NOT_OWNED",
      path,
      `Collector store artifacts must be owned by the current user: ${path}`,
    );
  }
}

function isNodeErrorWithCode(error: unknown, code: string): boolean {
  return (
    error instanceof Error &&
    "code" in error &&
    (error as NodeJS.ErrnoException).code === code
  );
}
