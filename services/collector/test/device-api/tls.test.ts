import assert from "node:assert/strict";
import {
  chmod,
  mkdtemp,
  rm,
  symlink,
} from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import {
  certificateSha256,
  generateSelfSignedTlsIdentity,
  requireCertificateHost,
} from "../../src/device-api/index.js";

test("certificate readers reject symlinks and group-writable identities", async () => {
  const temporary = await mkdtemp(join(tmpdir(), "quotaarc-tls-reader-"));
  const certificateFile = join(temporary, "collector-cert.pem");
  const privateKeyFile = join(temporary, "collector-key.pem");
  const linkedCertificate = join(temporary, "linked-cert.pem");
  try {
    const generated = await generateSelfSignedTlsIdentity({
      host: "127.0.0.1",
      certificateFile,
      privateKeyFile,
    });
    assert.equal(
      await certificateSha256(certificateFile),
      generated.certificateSha256,
    );
    const verificationTime = new Date();
    await requireCertificateHost(
      certificateFile,
      "127.0.0.1",
      verificationTime,
    );

    await symlink(certificateFile, linkedCertificate);
    await assert.rejects(
      certificateSha256(linkedCertificate),
      /tls_certificate_invalid/u,
    );

    await chmod(certificateFile, 0o620);
    await assert.rejects(
      certificateSha256(certificateFile),
      /tls_certificate_invalid/u,
    );
    await assert.rejects(
      requireCertificateHost(
        certificateFile,
        "127.0.0.1",
        verificationTime,
      ),
      /tls_certificate_invalid/u,
    );
  } finally {
    await rm(temporary, { recursive: true, force: true });
  }
});
