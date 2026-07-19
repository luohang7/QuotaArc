import { containsUnsafeClientText } from "./security.js";
import { isIsoTimestamp } from "./validation.js";

export const DEVICE_CAPABILITIES = [
  "summary.read",
  "refresh.write"
] as const;

export type DeviceCapability = (typeof DEVICE_CAPABILITIES)[number];

export interface CollectorHealth {
  schemaVersion: 1;
  collectorId: string;
  generatedAt: string;
  capabilities: DeviceCapability[];
}

export interface RefreshReceipt {
  schemaVersion: 1;
  collectorId: string;
  requestId: string;
  acceptedAt: string;
  completedAt: string;
  status: "refreshed" | "coalesced";
  summaryGeneratedAt: string;
}

export interface DeviceApiError {
  schemaVersion: 1;
  error: {
    code: string;
    message: string;
    retryable: boolean;
  };
}

export interface DevicePairingBundle {
  pairingVersion: 1;
  endpoint: string;
  collectorId: string;
  certificateSha256: string;
  deviceToken: string;
  scopes: DeviceCapability[];
}

type JsonObject = Record<string, unknown>;

const collectorIdPattern = /^qac_[A-Za-z0-9_-]{22,64}$/u;
const requestIdPattern = /^qar_[A-Za-z0-9_-]{16,64}$/u;
const errorCodePattern = /^[a-z0-9][a-z0-9_.-]{0,79}$/u;
const certificateSha256Pattern = /^[A-F0-9]{64}$/u;
const deviceTokenPattern = /^qa1\.[A-Za-z0-9_-]{12,64}\.[A-Za-z0-9_-]{32,128}$/u;
const capabilitySet = new Set<string>(DEVICE_CAPABILITIES);

export class DeviceContractValidationError extends Error {
  constructor(readonly issues: readonly string[]) {
    super(`Invalid QuotaArc device contract: ${issues.join("; ")}`);
    this.name = "DeviceContractValidationError";
  }
}

export function parseCollectorHealth(value: unknown): CollectorHealth {
  const issues: string[] = [];
  const object = exactObject(
    value,
    ["schemaVersion", "collectorId", "generatedAt", "capabilities"],
    "$",
    issues
  );
  if (object) {
    if (object.schemaVersion !== 1) issues.push("$.schemaVersion must equal 1");
    collectorIdAt(object.collectorId, "$.collectorId", issues);
    timestampAt(object.generatedAt, "$.generatedAt", issues);
    capabilitiesAt(object.capabilities, "$.capabilities", issues);
  }
  if (issues.length > 0) throw new DeviceContractValidationError(issues);
  return value as CollectorHealth;
}

export function parseRefreshReceipt(value: unknown): RefreshReceipt {
  const issues: string[] = [];
  const object = exactObject(
    value,
    [
      "schemaVersion",
      "collectorId",
      "requestId",
      "acceptedAt",
      "completedAt",
      "status",
      "summaryGeneratedAt"
    ],
    "$",
    issues
  );
  if (object) {
    if (object.schemaVersion !== 1) issues.push("$.schemaVersion must equal 1");
    collectorIdAt(object.collectorId, "$.collectorId", issues);
    if (
      typeof object.requestId !== "string" ||
      !requestIdPattern.test(object.requestId)
    ) {
      issues.push("$.requestId must be a normalized request identifier");
    }
    timestampAt(object.acceptedAt, "$.acceptedAt", issues);
    timestampAt(object.completedAt, "$.completedAt", issues);
    timestampAt(object.summaryGeneratedAt, "$.summaryGeneratedAt", issues);
    if (object.status !== "refreshed" && object.status !== "coalesced") {
      issues.push("$.status must equal refreshed or coalesced");
    }
    if (
      typeof object.acceptedAt === "string" &&
      typeof object.completedAt === "string" &&
      isIsoTimestamp(object.acceptedAt) &&
      isIsoTimestamp(object.completedAt) &&
      Date.parse(object.completedAt) < Date.parse(object.acceptedAt)
    ) {
      issues.push("$.completedAt must not be before acceptedAt");
    }
  }
  if (issues.length > 0) throw new DeviceContractValidationError(issues);
  return value as RefreshReceipt;
}

export function parseDeviceApiError(value: unknown): DeviceApiError {
  const issues: string[] = [];
  const object = exactObject(value, ["schemaVersion", "error"], "$", issues);
  if (object) {
    if (object.schemaVersion !== 1) issues.push("$.schemaVersion must equal 1");
    const error = exactObject(
      object.error,
      ["code", "message", "retryable"],
      "$.error",
      issues
    );
    if (error) {
      if (
        typeof error.code !== "string" ||
        !errorCodePattern.test(error.code)
      ) {
        issues.push("$.error.code must be a normalized error code");
      }
      if (
        typeof error.message !== "string" ||
        error.message.length === 0 ||
        error.message.length > 240 ||
        containsUnsafeClientText(error.message)
      ) {
        issues.push("$.error.message must be safe client text");
      }
      if (typeof error.retryable !== "boolean") {
        issues.push("$.error.retryable must be a boolean");
      }
    }
  }
  if (issues.length > 0) throw new DeviceContractValidationError(issues);
  return value as DeviceApiError;
}

export function parseDevicePairingBundle(value: unknown): DevicePairingBundle {
  const issues: string[] = [];
  const object = exactObject(
    value,
    [
      "pairingVersion",
      "endpoint",
      "collectorId",
      "certificateSha256",
      "deviceToken",
      "scopes"
    ],
    "$",
    issues
  );
  if (object) {
    if (object.pairingVersion !== 1) issues.push("$.pairingVersion must equal 1");
    if (
      typeof object.endpoint !== "string" ||
      !isHttpsOrigin(object.endpoint)
    ) {
      issues.push("$.endpoint must be an HTTPS origin");
    }
    collectorIdAt(object.collectorId, "$.collectorId", issues);
    if (
      typeof object.certificateSha256 !== "string" ||
      !certificateSha256Pattern.test(object.certificateSha256)
    ) {
      issues.push("$.certificateSha256 must be 64 uppercase hexadecimal characters");
    }
    if (
      typeof object.deviceToken !== "string" ||
      !deviceTokenPattern.test(object.deviceToken)
    ) {
      issues.push("$.deviceToken must be a QuotaArc v1 device token");
    }
    capabilitiesAt(object.scopes, "$.scopes", issues);
  }
  if (issues.length > 0) throw new DeviceContractValidationError(issues);
  return value as DevicePairingBundle;
}

function exactObject(
  value: unknown,
  expectedKeys: readonly string[],
  path: string,
  issues: string[]
): JsonObject | undefined {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    issues.push(`${path} must be an object`);
    return undefined;
  }
  const object = value as JsonObject;
  const expected = new Set(expectedKeys);
  for (const key of expectedKeys) {
    if (!Object.hasOwn(object, key)) issues.push(`${path}.${key} is required`);
  }
  for (const key of Object.keys(object)) {
    if (!expected.has(key)) issues.push(`${path}.${key} is not allowed`);
  }
  return object;
}

function collectorIdAt(value: unknown, path: string, issues: string[]): void {
  if (typeof value !== "string" || !collectorIdPattern.test(value)) {
    issues.push(`${path} must be a normalized Collector identifier`);
  }
}

function timestampAt(value: unknown, path: string, issues: string[]): void {
  if (!isIsoTimestamp(value)) {
    issues.push(`${path} must be an ISO-8601 timestamp with timezone`);
  }
}

function capabilitiesAt(value: unknown, path: string, issues: string[]): void {
  if (!Array.isArray(value) || value.length === 0) {
    issues.push(`${path} must be a non-empty capability array`);
    return;
  }
  const unique = new Set<string>();
  for (const [index, capability] of value.entries()) {
    if (
      typeof capability !== "string" ||
      !capabilitySet.has(capability)
    ) {
      issues.push(`${path}[${index}] is not a supported capability`);
      continue;
    }
    if (unique.has(capability)) {
      issues.push(`${path}[${index}] must be unique`);
    }
    unique.add(capability);
  }
}

function isHttpsOrigin(value: string): boolean {
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    return false;
  }
  return (
    url.protocol === "https:" &&
    url.username === "" &&
    url.password === "" &&
    (url.pathname === "/" || url.pathname === "") &&
    url.search === "" &&
    url.hash === ""
  );
}
