import { X509Certificate } from "node:crypto";
import { spawn } from "node:child_process";
import { constants, type Stats } from "node:fs";
import {
  chmod,
  lstat,
  mkdir,
  open,
  unlink,
} from "node:fs/promises";
import { dirname } from "node:path";
import { isIP } from "node:net";

export interface GeneratedTlsIdentity {
  certificateSha256: string;
  subjectAlternativeName: string;
}

export async function generateSelfSignedTlsIdentity(options: {
  host: string;
  certificateFile: string;
  privateKeyFile: string;
  opensslBinary?: string;
}): Promise<GeneratedTlsIdentity> {
  const host = normalizeHost(options.host);
  await Promise.all([
    ensurePrivateParent(dirname(options.certificateFile)),
    ensurePrivateParent(dirname(options.privateKeyFile)),
  ]);
  await Promise.all([
    assertMissing(options.certificateFile),
    assertMissing(options.privateKeyFile),
  ]);
  const subjectAlternativeName = isIP(host) === 0
    ? `DNS:${host}`
    : `IP:${host}`;
  const command = options.opensslBinary ?? "openssl";
  try {
    await spawnChecked(command, [
      "req",
      "-x509",
      "-newkey",
      "rsa:3072",
      "-sha256",
      "-nodes",
      "-days",
      "825",
      "-subj",
      "/CN=QuotaArc Collector",
      "-addext",
      `subjectAltName=${subjectAlternativeName}`,
      "-addext",
      "keyUsage=digitalSignature,keyEncipherment",
      "-addext",
      "extendedKeyUsage=serverAuth",
      "-keyout",
      options.privateKeyFile,
      "-out",
      options.certificateFile,
    ]);
    await Promise.all([
      chmod(options.privateKeyFile, 0o600),
      chmod(options.certificateFile, 0o600),
    ]);
    return {
      certificateSha256: await certificateSha256(options.certificateFile),
      subjectAlternativeName,
    };
  } catch (error) {
    await Promise.all([
      unlink(options.privateKeyFile).catch(() => undefined),
      unlink(options.certificateFile).catch(() => undefined),
    ]);
    throw error;
  }
}

export async function certificateSha256(path: string): Promise<string> {
  return (await readCertificate(path)).fingerprint256.replaceAll(":", "");
}

export async function requireCertificateHost(
  path: string,
  host: string,
  now = new Date(),
): Promise<void> {
  if (!Number.isFinite(now.getTime())) throw new Error("clock_invalid");
  const certificate = await readCertificate(path);
  const validFrom = Date.parse(certificate.validFrom);
  const validTo = Date.parse(certificate.validTo);
  if (
    !Number.isFinite(validFrom) ||
    !Number.isFinite(validTo) ||
    now.getTime() < validFrom ||
    now.getTime() > validTo
  ) {
    throw new Error("tls_certificate_expired");
  }
  const matched = isIP(host) === 0
    ? certificate.checkHost(host)
    : certificate.checkIP(host);
  if (!matched) throw new Error("tls_certificate_host_mismatch");
}

async function readCertificate(path: string): Promise<X509Certificate> {
  let handle: Awaited<ReturnType<typeof open>>;
  try {
    handle = await open(
      path,
      constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK,
    );
  } catch {
    throw new Error("tls_certificate_invalid");
  }
  let bytes: Buffer;
  try {
    const before = await handle.stat();
    validateOpenedCertificate(before);
    bytes = await handle.readFile();
    const after = await handle.stat();
    validateOpenedCertificate(after);
    if (bytes.byteLength !== after.size) {
      throw new Error("tls_certificate_invalid");
    }
  } finally {
    await handle.close();
  }
  try {
    return new X509Certificate(bytes);
  } catch {
    throw new Error("tls_certificate_invalid");
  }
}

function validateOpenedCertificate(info: Stats): void {
  if (
    !info.isFile() ||
    info.size <= 0 ||
    info.size > 128 * 1024 ||
    (info.mode & 0o022) !== 0
  ) {
    throw new Error("tls_certificate_invalid");
  }
  const uid = typeof process.getuid === "function" ? process.getuid() : null;
  if (uid !== null && info.uid !== uid) throw new Error("tls_file_not_owned");
}

async function ensurePrivateParent(path: string): Promise<void> {
  await mkdir(path, { recursive: true, mode: 0o700 });
  const info = await lstat(path);
  if (!info.isDirectory() || info.isSymbolicLink()) {
    throw new Error("tls_parent_invalid");
  }
  if ((info.mode & 0o077) !== 0) throw new Error("tls_parent_not_private");
  const uid = typeof process.getuid === "function" ? process.getuid() : null;
  if (uid !== null && info.uid !== uid) throw new Error("tls_parent_not_owned");
}

async function assertMissing(path: string): Promise<void> {
  try {
    await lstat(path);
  } catch (error) {
    if (isErrorCode(error, "ENOENT")) return;
    throw error;
  }
  throw new Error("tls_identity_already_exists");
}

function normalizeHost(value: string): string {
  const host = value.trim().toLowerCase();
  if (
    host.length === 0 ||
    host.length > 253 ||
    host === "0.0.0.0" ||
    host === "::" ||
    (
      isIP(host) === 0 &&
      host !== "localhost" &&
      !/^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)*[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/u.test(host)
    )
  ) {
    throw new Error("tls_host_invalid");
  }
  return host;
}

function spawnChecked(command: string, args: string[]): Promise<void> {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, {
      stdio: ["ignore", "ignore", "ignore"],
      env: {
        PATH: process.env.PATH,
        LANG: "C",
      },
    });
    child.once("error", () => reject(new Error("openssl_unavailable")));
    child.once("exit", (code, signal) => {
      if (code === 0 && signal === null) {
        resolve();
      } else {
        reject(new Error("tls_generation_failed"));
      }
    });
  });
}

function isErrorCode(error: unknown, code: string): boolean {
  return (
    typeof error === "object" &&
    error !== null &&
    "code" in error &&
    (error as { code?: unknown }).code === code
  );
}
