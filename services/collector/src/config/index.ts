import { homedir } from "node:os";
import { join } from "node:path";

export interface CollectorConfig {
  codexBinary?: string;
  codexHome: string;
  activeSessionsDirectory: string;
  archivedSessionsDirectory: string;
  fixtureFile?: string;
  deviceStateDirectory: string;
  deviceRegistryFile: string;
  tlsCertificateFile: string;
  tlsPrivateKeyFile: string;
}

export function resolveCollectorConfig(
  environment: NodeJS.ProcessEnv = process.env,
  userHome = homedir(),
): CollectorConfig {
  const codexHome = environment.QUOTAARC_CODEX_HOME ??
    environment.CODEX_HOME ??
    join(userHome, ".codex");
  const config: CollectorConfig = {
    codexHome,
    activeSessionsDirectory: join(codexHome, "sessions"),
    archivedSessionsDirectory: join(codexHome, "archived_sessions"),
    deviceStateDirectory: join(userHome, ".quotaarc"),
    deviceRegistryFile: join(userHome, ".quotaarc", "devices.json"),
    tlsCertificateFile: join(userHome, ".quotaarc", "collector-cert.pem"),
    tlsPrivateKeyFile: join(userHome, ".quotaarc", "collector-key.pem"),
  };

  if (environment.QUOTAARC_CODEX_BINARY) {
    config.codexBinary = environment.QUOTAARC_CODEX_BINARY;
  }
  if (environment.QUOTAARC_FIXTURE_FILE) {
    config.fixtureFile = environment.QUOTAARC_FIXTURE_FILE;
  }
  if (environment.QUOTAARC_DEVICE_STATE_DIRECTORY) {
    config.deviceStateDirectory = environment.QUOTAARC_DEVICE_STATE_DIRECTORY;
    config.deviceRegistryFile = join(config.deviceStateDirectory, "devices.json");
    config.tlsCertificateFile = join(
      config.deviceStateDirectory,
      "collector-cert.pem",
    );
    config.tlsPrivateKeyFile = join(
      config.deviceStateDirectory,
      "collector-key.pem",
    );
  }
  if (environment.QUOTAARC_DEVICE_REGISTRY_FILE) {
    config.deviceRegistryFile = environment.QUOTAARC_DEVICE_REGISTRY_FILE;
  }
  if (environment.QUOTAARC_TLS_CERTIFICATE_FILE) {
    config.tlsCertificateFile = environment.QUOTAARC_TLS_CERTIFICATE_FILE;
  }
  if (environment.QUOTAARC_TLS_PRIVATE_KEY_FILE) {
    config.tlsPrivateKeyFile = environment.QUOTAARC_TLS_PRIVATE_KEY_FILE;
  }
  return config;
}
