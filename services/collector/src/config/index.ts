import { homedir } from "node:os";
import { join } from "node:path";

export interface CollectorConfig {
  codexBinary?: string;
  codexHome: string;
  activeSessionsDirectory: string;
  archivedSessionsDirectory: string;
  fixtureFile?: string;
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
  };

  if (environment.QUOTAARC_CODEX_BINARY) {
    config.codexBinary = environment.QUOTAARC_CODEX_BINARY;
  }
  if (environment.QUOTAARC_FIXTURE_FILE) {
    config.fixtureFile = environment.QUOTAARC_FIXTURE_FILE;
  }
  return config;
}
