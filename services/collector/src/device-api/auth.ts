import {
  createHmac,
  randomBytes,
  timingSafeEqual,
} from "node:crypto";
import type { IncomingHttpHeaders } from "node:http";
import type { DeviceCapability } from "@quotaarc/contracts";
import {
  FileDeviceRegistry,
  tokenVerificationKey,
  type ActiveDevice,
} from "./registry.js";

export const AUTHORIZATION_SCHEME = "QuotaArc-HMAC";
export const TIMESTAMP_HEADER = "x-quotaarc-timestamp";
export const NONCE_HEADER = "x-quotaarc-nonce";
export const EMPTY_BODY_SHA256 =
  "47DEQpj8HBSa-_TImW-5JCeuQeRkm5NMpJWZG3hSuFU";

const authorizationPattern =
  /^QuotaArc-HMAC ([A-Za-z0-9_-]{12,64}):([A-Za-z0-9_-]{43})$/u;
const noncePattern = /^[A-Za-z0-9_-]{22,64}$/u;

export interface AuthenticatedDevice {
  collectorId: string;
  deviceId: string;
  scopes: ReadonlySet<DeviceCapability>;
}

export interface RequestAuthInput {
  method: string;
  path: string;
  headers: IncomingHttpHeaders | Readonly<Record<string, string | undefined>>;
}

export type RequestAuthResult =
  | { ok: true; device: AuthenticatedDevice }
  | {
      ok: false;
      status: 401 | 403;
      code:
        | "auth.required"
        | "auth.invalid"
        | "auth.expired"
        | "auth.replay"
        | "auth.scope_denied";
    };

export interface DeviceRequestAuthenticatorOptions {
  now?: () => Date;
  allowedClockSkewMs?: number;
}

/**
 * HMAC request authentication binds the credential to method, fixed path,
 * timestamp, nonce, and empty body digest. A valid request nonce can be used
 * only once inside the accepted clock window.
 */
export class DeviceRequestAuthenticator {
  readonly #registry: FileDeviceRegistry;
  readonly #now: () => Date;
  readonly #allowedClockSkewMs: number;
  readonly #seenNonces = new Map<string, Map<string, number>>();

  constructor(
    registry: FileDeviceRegistry,
    options: DeviceRequestAuthenticatorOptions = {},
  ) {
    this.#registry = registry;
    this.#now = options.now ?? (() => new Date());
    this.#allowedClockSkewMs = options.allowedClockSkewMs ?? 120_000;
    if (
      !Number.isSafeInteger(this.#allowedClockSkewMs) ||
      this.#allowedClockSkewMs < 30_000 ||
      this.#allowedClockSkewMs > 300_000
    ) {
      throw new Error("auth_clock_skew_invalid");
    }
  }

  async authenticate(
    request: RequestAuthInput,
    requiredScope: DeviceCapability,
  ): Promise<RequestAuthResult> {
    const authorization = singleHeader(request.headers, "authorization");
    const timestamp = singleHeader(request.headers, TIMESTAMP_HEADER);
    const nonce = singleHeader(request.headers, NONCE_HEADER);
    if (!authorization || !timestamp || !nonce) {
      return { ok: false, status: 401, code: "auth.required" };
    }
    const authorizationMatch = authorizationPattern.exec(authorization);
    if (
      !authorizationMatch ||
      !/^\d{10,13}$/u.test(timestamp) ||
      !noncePattern.test(nonce)
    ) {
      return { ok: false, status: 401, code: "auth.invalid" };
    }

    const now = validNow(this.#now).getTime();
    const timestampMillis = normalizeTimestampMillis(timestamp);
    if (
      timestampMillis === null ||
      Math.abs(now - timestampMillis) > this.#allowedClockSkewMs
    ) {
      return { ok: false, status: 401, code: "auth.expired" };
    }

    const deviceId = authorizationMatch[1]!;
    const providedSignature = Buffer.from(authorizationMatch[2]!, "base64url");
    const device = await this.#registry.activeDevice(deviceId);
    if (!device) {
      // Missing, unknown, and revoked device ids are indistinguishable to a
      // remote caller.
      return { ok: false, status: 401, code: "auth.invalid" };
    }
    const expected = requestSignature({
      verificationKey: device.verificationKey,
      method: request.method,
      path: request.path,
      timestamp,
      nonce,
    });
    if (
      providedSignature.length !== expected.length ||
      !timingSafeEqual(providedSignature, expected)
    ) {
      return { ok: false, status: 401, code: "auth.invalid" };
    }
    if (this.#isReplay(device, nonce, timestampMillis, now)) {
      return { ok: false, status: 401, code: "auth.replay" };
    }
    if (!device.scopes.has(requiredScope)) {
      return { ok: false, status: 403, code: "auth.scope_denied" };
    }
    return {
      ok: true,
      device: {
        collectorId: device.collectorId,
        deviceId: device.deviceId,
        scopes: device.scopes,
      },
    };
  }

  #isReplay(
    device: ActiveDevice,
    nonce: string,
    timestampMillis: number,
    now: number,
  ): boolean {
    const nonces = this.#seenNonces.get(device.deviceId) ?? new Map();
    for (const [seen, expiresAt] of nonces) {
      if (expiresAt < now) nonces.delete(seen);
    }
    if (nonces.has(nonce)) return true;
    if (nonces.size >= 512) return true;
    nonces.set(nonce, timestampMillis + this.#allowedClockSkewMs);
    this.#seenNonces.set(device.deviceId, nonces);
    return false;
  }
}

export interface SignedRequestHeaders {
  [name: string]: string;
  Authorization: string;
  "X-QuotaArc-Timestamp": string;
  "X-QuotaArc-Nonce": string;
}

export function signDeviceRequest(
  token: string,
  method: string,
  path: string,
  options: {
    now?: Date;
    nonce?: string;
  } = {},
): SignedRequestHeaders {
  const { deviceId, key } = tokenVerificationKey(token);
  const now = options.now ?? new Date();
  if (!Number.isFinite(now.getTime())) throw new Error("clock_invalid");
  const timestamp = String(Math.floor(now.getTime() / 1_000));
  const nonce = options.nonce ?? randomBytes(18).toString("base64url");
  if (!noncePattern.test(nonce)) throw new Error("auth_nonce_invalid");
  const signature = requestSignature({
    verificationKey: key,
    method,
    path,
    timestamp,
    nonce,
  }).toString("base64url");
  return {
    Authorization: `${AUTHORIZATION_SCHEME} ${deviceId}:${signature}`,
    "X-QuotaArc-Timestamp": timestamp,
    "X-QuotaArc-Nonce": nonce,
  };
}

function requestSignature(input: {
  verificationKey: Buffer;
  method: string;
  path: string;
  timestamp: string;
  nonce: string;
}): Buffer {
  const canonical = [
    input.method.toUpperCase(),
    input.path,
    input.timestamp,
    input.nonce,
    EMPTY_BODY_SHA256,
  ].join("\n");
  return createHmac("sha256", input.verificationKey)
    .update(canonical, "utf8")
    .digest();
}

function singleHeader(
  headers: IncomingHttpHeaders | Readonly<Record<string, string | undefined>>,
  name: string,
): string | null {
  const value = headers[name] ??
    headers[Object.keys(headers).find((candidate) =>
      candidate.toLowerCase() === name
    ) ?? ""];
  return typeof value === "string" ? value : null;
}

function normalizeTimestampMillis(value: string): number | null {
  const numeric = Number(value);
  if (!Number.isSafeInteger(numeric)) return null;
  const millis = value.length === 13 ? numeric : numeric * 1_000;
  return Number.isSafeInteger(millis) ? millis : null;
}

function validNow(now: () => Date): Date {
  const value = now();
  if (!Number.isFinite(value.getTime())) throw new Error("clock_invalid");
  return value;
}
