import path from "node:path";

import type { ProjectIdentity } from "./types.js";

export interface NormalizeProjectOptions {
  /**
   * Optional pure resolver supplied by a higher layer, for example to map a
   * Codex worktree to its main repository. The indexer itself performs no I/O.
   */
  canonicalize?: (normalizedAbsolutePath: string) => string;
}

export function normalizeProject(
  cwd: unknown,
  options: NormalizeProjectOptions = {},
): ProjectIdentity | null {
  if (typeof cwd !== "string" || cwd.trim() === "") {
    return null;
  }

  const absolute = path.resolve(cwd);
  const normalized = path.normalize(absolute);
  const resolved = options.canonicalize
    ? path.normalize(options.canonicalize(normalized))
    : normalized;

  return {
    canonicalPath: resolved,
    safeBasename: safeProjectBasename(resolved),
  };
}

export function safeProjectBasename(canonicalPath: string): string {
  const basename = path.basename(canonicalPath) || "root";
  const sanitized = basename
    .replace(/[\u0000-\u001f\u007f]/gu, "")
    .replace(/[\\/]/gu, "-")
    .trim();

  return (sanitized || "project").slice(0, 120);
}

export function publicProjectIdentity(
  project: ProjectIdentity,
): Pick<ProjectIdentity, "safeBasename"> {
  return { safeBasename: project.safeBasename };
}
