import {
  createHash,
  randomBytes,
} from "node:crypto";
import {
  constants,
  lstat,
  mkdir,
  open,
  rename,
  stat,
  unlink,
} from "node:fs/promises";
import { dirname, join } from "node:path";
import {
  DEVICE_CAPABILITIES,
  containsUnsafeClientText,
  type DeviceCapability,
} from "@quotaarc/contracts";

const REGISTRY_FORMAT_VERSION = 1;
const deviceIdPattern = /^[A-Za-z0-9_-]{12,64}$/u;
const collectorIdPattern = /^qac_[A-Za-z0-9_-]{22,64}$/u;
const encodedKeyPattern = /^[A-Za-z0-9_-]{43}$/u;

interface StoredDevice {
  deviceId: string;
  label: string;
  verificationKey: string;
  scopes: DeviceCapability[];
  createdAt: string;
  revokedAt: string | null;
}

interface RegistryDocument {
  formatVersion: 1;
  collectorId: string;
  devices: StoredDevice[];
}

export interface IssuedDevice {
  collectorId: string;
  deviceId: string;
  label: string;
  token: string;
  scopes: DeviceCapability[];
  createdAt: string;
}

export interface ListedDevice {
  deviceId: string;
  label: string;
  scopes: DeviceCapability[];
  createdAt: string;
  revokedAt: string | null;
}

export interface ActiveDevice {
  collectorId: string;
  deviceId: string;
  verificationKey: Buffer;
  scopes: ReadonlySet<DeviceCapability>;
}

export interface DeviceRegistryOptions {
  now?: () => Date;
  randomBytes?: (size: number) => Buffer;
  allowCreateParent?: boolean;
}

/**
 * A small file-backed device registry. Only verification keys are persisted;
 * the one-time pairing token is returned to the issuing CLI and cannot be
 * reconstructed later.
 */
export class FileDeviceRegistry {
  readonly #path: string;
  readonly #now: () => Date;
  readonly #randomBytes: (size: number) => Buffer;
  readonly #allowCreateParent: boolean;

  constructor(path: string, options: DeviceRegistryOptions = {}) {
    if (path.length === 0) throw new Error("device_registry_path_required");
    this.#path = path;
    this.#now = options.now ?? (() => new Date());
    this.#randomBytes = options.randomBytes ?? randomBytes;
    this.#allowCreateParent = options.allowCreateParent ?? false;
  }

  async collectorId(): Promise<string> {
    return (await this.#readDocument(false)).collectorId;
  }

  async issue(
    label: string,
    scopes: readonly DeviceCapability[] = DEVICE_CAPABILITIES,
  ): Promise<IssuedDevice> {
    const normalizedLabel = normalizeLabel(label);
    const normalizedScopes = normalizeScopes(scopes);
    return this.#withLock(async () => {
      const document = await this.#readDocument(true);
      const deviceId = encode(this.#randomBytes(12));
      const secret = encode(this.#randomBytes(32));
      const verificationKey = digestSecret(secret);
      const createdAt = validNow(this.#now).toISOString();
      const stored: StoredDevice = {
        deviceId,
        label: normalizedLabel,
        verificationKey: encode(verificationKey),
        scopes: normalizedScopes,
        createdAt,
        revokedAt: null,
      };
      document.devices.push(stored);
      await this.#writeDocument(document);
      return {
        collectorId: document.collectorId,
        deviceId,
        label: stored.label,
        token: `qa1.${deviceId}.${secret}`,
        scopes: [...stored.scopes],
        createdAt,
      };
    });
  }

  async revoke(deviceId: string): Promise<ListedDevice> {
    if (!deviceIdPattern.test(deviceId)) throw new Error("device_id_invalid");
    return this.#withLock(async () => {
      const document = await this.#readDocument(false);
      const device = document.devices.find((candidate) =>
        candidate.deviceId === deviceId
      );
      if (!device) throw new Error("device_not_found");
      if (device.revokedAt === null) {
        device.revokedAt = validNow(this.#now).toISOString();
        await this.#writeDocument(document);
      }
      return publicDevice(device);
    });
  }

  async list(): Promise<{ collectorId: string; devices: ListedDevice[] }> {
    const document = await this.#readDocument(false);
    return {
      collectorId: document.collectorId,
      devices: document.devices.map(publicDevice),
    };
  }

  async activeDevice(deviceId: string): Promise<ActiveDevice | null> {
    if (!deviceIdPattern.test(deviceId)) return null;
    const document = await this.#readDocument(false);
    const device = document.devices.find((candidate) =>
      candidate.deviceId === deviceId
    );
    if (!device || device.revokedAt !== null) return null;
    return {
      collectorId: document.collectorId,
      deviceId,
      verificationKey: Buffer.from(device.verificationKey, "base64url"),
      scopes: new Set(device.scopes),
    };
  }

  async #withLock<T>(operation: () => Promise<T>): Promise<T> {
    await this.#ensureParent();
    const lockPath = `${this.#path}.lock`;
    const deadline = Date.now() + 5_000;
    let lock: Awaited<ReturnType<typeof open>> | null = null;
    while (lock === null) {
      try {
        lock = await open(
          lockPath,
          constants.O_CREAT | constants.O_EXCL | constants.O_WRONLY |
            constants.O_NOFOLLOW,
          0o600,
        );
      } catch (error) {
        if (!isErrorCode(error, "EEXIST") || Date.now() >= deadline) {
          throw new Error("device_registry_lock_failed");
        }
        await new Promise((resolve) => setTimeout(resolve, 25));
      }
    }
    try {
      return await operation();
    } finally {
      await lock.close().catch(() => undefined);
      await unlink(lockPath).catch(() => undefined);
    }
  }

  async #ensureParent(): Promise<void> {
    const parent = dirname(this.#path);
    if (this.#allowCreateParent) {
      await mkdir(parent, { recursive: true, mode: 0o700 });
    }
    const info = await stat(parent);
    if (!info.isDirectory()) throw new Error("device_registry_parent_invalid");
    if ((info.mode & 0o077) !== 0) {
      throw new Error("device_registry_parent_not_private");
    }
    const uid = typeof process.getuid === "function" ? process.getuid() : null;
    if (uid !== null && info.uid !== uid) {
      throw new Error("device_registry_parent_not_owned");
    }
  }

  async #readDocument(allowCreate: boolean): Promise<RegistryDocument> {
    await this.#ensureParent();
    let encoded: string;
    try {
      const info = await lstat(this.#path);
      if (!info.isFile() || info.isSymbolicLink()) {
        throw new Error("device_registry_file_invalid");
      }
      if ((info.mode & 0o077) !== 0) {
        throw new Error("device_registry_file_not_private");
      }
      const uid = typeof process.getuid === "function" ? process.getuid() : null;
      if (uid !== null && info.uid !== uid) {
        throw new Error("device_registry_file_not_owned");
      }
      const handle = await open(
        this.#path,
        constants.O_RDONLY | constants.O_NOFOLLOW,
      );
      try {
        encoded = await handle.readFile("utf8");
      } finally {
        await handle.close();
      }
    } catch (error) {
      if (!isErrorCode(error, "ENOENT") || !allowCreate) throw error;
      const created: RegistryDocument = {
        formatVersion: REGISTRY_FORMAT_VERSION,
        collectorId: `qac_${encode(this.#randomBytes(18))}`,
        devices: [],
      };
      await this.#writeDocument(created);
      return created;
    }
    if (Buffer.byteLength(encoded) > 256 * 1024) {
      throw new Error("device_registry_too_large");
    }
    return parseRegistry(encoded);
  }

  async #writeDocument(document: RegistryDocument): Promise<void> {
    validateRegistry(document);
    await this.#ensureParent();
    const temporaryPath = join(
      dirname(this.#path),
      `.quotaarc-registry-${encode(this.#randomBytes(8))}.tmp`,
    );
    const handle = await open(
      temporaryPath,
      constants.O_CREAT | constants.O_EXCL | constants.O_WRONLY |
        constants.O_NOFOLLOW,
      0o600,
    );
    try {
      await handle.writeFile(`${JSON.stringify(document, null, 2)}\n`, "utf8");
      await handle.sync();
    } finally {
      await handle.close();
    }
    await rename(temporaryPath, this.#path);
  }
}

export function tokenVerificationKey(token: string): {
  deviceId: string;
  key: Buffer;
} {
  const parts = token.split(".");
  if (
    parts.length !== 3 ||
    parts[0] !== "qa1" ||
    !deviceIdPattern.test(parts[1] ?? "") ||
    !/^[A-Za-z0-9_-]{32,128}$/u.test(parts[2] ?? "")
  ) {
    throw new Error("device_token_invalid");
  }
  return {
    deviceId: parts[1]!,
    key: digestSecret(parts[2]!),
  };
}

function parseRegistry(encoded: string): RegistryDocument {
  let value: unknown;
  try {
    value = JSON.parse(encoded);
  } catch {
    throw new Error("device_registry_invalid");
  }
  validateRegistry(value);
  return value;
}

function validateRegistry(value: unknown): asserts value is RegistryDocument {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new Error("device_registry_invalid");
  }
  const object = value as Record<string, unknown>;
  if (
    object.formatVersion !== REGISTRY_FORMAT_VERSION ||
    typeof object.collectorId !== "string" ||
    !collectorIdPattern.test(object.collectorId) ||
    !Array.isArray(object.devices) ||
    Object.keys(object).some((key) =>
      !["formatVersion", "collectorId", "devices"].includes(key)
    )
  ) {
    throw new Error("device_registry_invalid");
  }
  const ids = new Set<string>();
  for (const candidate of object.devices) {
    if (
      typeof candidate !== "object" ||
      candidate === null ||
      Array.isArray(candidate)
    ) {
      throw new Error("device_registry_invalid");
    }
    const device = candidate as Record<string, unknown>;
    if (
      typeof device.deviceId !== "string" ||
      !deviceIdPattern.test(device.deviceId) ||
      ids.has(device.deviceId) ||
      typeof device.label !== "string" ||
      normalizeLabel(device.label) !== device.label ||
      typeof device.verificationKey !== "string" ||
      !encodedKeyPattern.test(device.verificationKey) ||
      !Array.isArray(device.scopes) ||
      !isTimestamp(device.createdAt) ||
      !(device.revokedAt === null || isTimestamp(device.revokedAt)) ||
      Object.keys(device).some((key) =>
        ![
          "deviceId",
          "label",
          "verificationKey",
          "scopes",
          "createdAt",
          "revokedAt",
        ].includes(key)
      )
    ) {
      throw new Error("device_registry_invalid");
    }
    normalizeScopes(device.scopes as DeviceCapability[]);
    ids.add(device.deviceId);
  }
}

function normalizeLabel(value: string): string {
  const normalized = value
    .replace(/[\u0000-\u001f\u007f]/gu, "")
    .trim();
  if (
    normalized.length === 0 ||
    normalized.length > 80 ||
    containsUnsafeClientText(normalized)
  ) {
    throw new Error("device_label_invalid");
  }
  return normalized;
}

function normalizeScopes(
  values: readonly DeviceCapability[],
): DeviceCapability[] {
  if (!Array.isArray(values) || values.length === 0) {
    throw new Error("device_scopes_invalid");
  }
  const allowed = new Set<string>(DEVICE_CAPABILITIES);
  const result = [...new Set(values)];
  if (
    result.length !== values.length ||
    result.some((scope) => !allowed.has(scope))
  ) {
    throw new Error("device_scopes_invalid");
  }
  return result;
}

function digestSecret(secret: string): Buffer {
  return createHash("sha256").update(secret, "utf8").digest();
}

function encode(value: Buffer): string {
  return value.toString("base64url");
}

function validNow(now: () => Date): Date {
  const value = now();
  if (!Number.isFinite(value.getTime())) throw new Error("clock_invalid");
  return value;
}

function isTimestamp(value: unknown): value is string {
  return (
    typeof value === "string" &&
    Number.isFinite(Date.parse(value)) &&
    /(?:Z|[+-]\d{2}:\d{2})$/u.test(value)
  );
}

function publicDevice(device: StoredDevice): ListedDevice {
  return {
    deviceId: device.deviceId,
    label: device.label,
    scopes: [...device.scopes],
    createdAt: device.createdAt,
    revokedAt: device.revokedAt,
  };
}

function isErrorCode(error: unknown, code: string): boolean {
  return (
    typeof error === "object" &&
    error !== null &&
    "code" in error &&
    (error as { code?: unknown }).code === code
  );
}
