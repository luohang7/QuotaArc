import { constants } from "node:fs";
import { access } from "node:fs/promises";
import { delimiter } from "node:path";
import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { createInterface, type Interface } from "node:readline";

type JsonRpcId = number;

interface JsonRpcResponse {
  id: JsonRpcId;
  result?: unknown;
  error?: {
    code?: number;
    message?: string;
    data?: unknown;
  };
}

interface PendingRequest {
  resolve(value: unknown): void;
  reject(error: Error): void;
  timer: NodeJS.Timeout;
  generation: number;
}

const STOP_GRACE_MS = 250;

export interface CodexAppServerClientOptions {
  binary?: string;
  args?: string[];
  requestTimeoutMs?: number;
  environment?: NodeJS.ProcessEnv;
}

export interface OfficialAccountRead {
  rateLimits: unknown;
  usage: unknown | null;
  usageError: string | null;
  usageErrorCode: number | null;
}

export class CodexRpcError extends Error {
  readonly code: number | undefined;

  constructor(message: string, code?: number) {
    super(message);
    this.name = "CodexRpcError";
    this.code = code;
  }
}

/**
 * Minimal JSONL/stdio client for the documented Codex app-server account
 * methods. It intentionally does not expose the generic request method outside
 * this module.
 */
export class CodexAppServerClient {
  readonly #options: Required<Pick<CodexAppServerClientOptions, "requestTimeoutMs">> &
    Omit<CodexAppServerClientOptions, "requestTimeoutMs">;
  #process: ChildProcessWithoutNullStreams | null = null;
  #reader: Interface | null = null;
  #generation = 0;
  #nextId = 1;
  #pending = new Map<JsonRpcId, PendingRequest>();
  #startPromise: Promise<void> | null = null;
  #stopPromise: Promise<void> | null = null;

  constructor(options: CodexAppServerClientOptions = {}) {
    this.#options = {
      ...options,
      requestTimeoutMs: options.requestTimeoutMs ?? 8_000,
    };
  }

  async start(): Promise<void> {
    if (this.#stopPromise) {
      await this.#stopPromise;
    }
    if (this.#startPromise) {
      return this.#startPromise;
    }
    if (this.#process) {
      return;
    }

    this.#startPromise = this.#start();
    try {
      await this.#startPromise;
    } catch (error) {
      await this.stop();
      throw error;
    } finally {
      this.#startPromise = null;
    }
  }

  async readOfficialAccount(): Promise<OfficialAccountRead> {
    await this.start();
    const rateLimits = await this.#request("account/rateLimits/read");

    try {
      const usage = await this.#request("account/usage/read");
      return {
        rateLimits,
        usage,
        usageError: null,
        usageErrorCode: null,
      };
    } catch (error) {
      return {
        rateLimits,
        usage: null,
        usageError: safeErrorMessage(error),
        usageErrorCode: error instanceof CodexRpcError ? error.code ?? null : null,
      };
    }
  }

  async stop(): Promise<void> {
    if (this.#stopPromise) {
      return this.#stopPromise;
    }

    const child = this.#process;
    const reader = this.#reader;
    const generation = this.#generation;
    this.#reader = null;
    this.#process = null;
    reader?.close();
    this.#rejectGeneration(
      generation,
      new CodexRpcError("Codex app-server stopped"),
    );

    if (!child) {
      return;
    }

    const stopping = terminateChild(child);
    this.#stopPromise = stopping;
    try {
      await stopping;
    } finally {
      if (this.#stopPromise === stopping) {
        this.#stopPromise = null;
      }
    }
  }

  async #start(): Promise<void> {
    const binary = await resolveCodexBinary(this.#options.binary);
    const child = spawn(binary, this.#options.args ?? ["app-server"], {
      env: this.#options.environment ?? process.env,
      stdio: ["pipe", "pipe", "pipe"],
    });
    const generation = this.#generation + 1;
    this.#generation = generation;
    this.#process = child;
    child.stderr.resume();

    const reader = createInterface({ input: child.stdout });
    this.#reader = reader;
    reader.on("line", (line) => this.#onLine(line, generation));
    child.once("error", (error) => {
      this.#onExit(child, reader, generation, error);
    });
    child.once("exit", (code, signal) => {
      this.#onExit(
        child,
        reader,
        generation,
        new CodexRpcError(
          `Codex app-server exited (${signal ?? `code ${code ?? "unknown"}`})`,
        ),
      );
    });

    await this.#request(
      "initialize",
      {
        clientInfo: {
          name: "quotaarc",
          title: "QuotaArc Collector",
          version: "0.1.0",
        },
        capabilities: {
          experimentalApi: false,
        },
      },
      false,
    );
    this.#write({ method: "initialized" });
  }

  async #request(
    method: string,
    params: unknown = null,
    ensureStarted = true,
  ): Promise<unknown> {
    if (ensureStarted) {
      await this.start();
    }

    const id = this.#nextId++;
    const generation = this.#generation;
    const promise = new Promise<unknown>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.#pending.delete(id);
        reject(
          new CodexRpcError(
            `Codex app-server request timed out: ${method}`,
          ),
        );
      }, this.#options.requestTimeoutMs);
      timer.unref();
      this.#pending.set(id, { resolve, reject, timer, generation });
    });

    this.#write({ id, method, params }, generation);
    return promise;
  }

  #write(message: object, generation = this.#generation): void {
    const child = this.#process;
    if (
      !child ||
      child.stdin.destroyed ||
      generation !== this.#generation
    ) {
      throw new CodexRpcError("Codex app-server is not running");
    }
    child.stdin.write(`${JSON.stringify(message)}\n`);
  }

  #onLine(line: string, generation: number): void {
    let message: JsonRpcResponse;
    try {
      message = JSON.parse(line) as JsonRpcResponse;
    } catch {
      return;
    }

    if (typeof message.id !== "number") {
      return;
    }
    const pending = this.#pending.get(message.id);
    if (!pending || pending.generation !== generation) {
      return;
    }

    clearTimeout(pending.timer);
    this.#pending.delete(message.id);
    if (message.error) {
      pending.reject(
        new CodexRpcError(
          sanitizeDiagnostic(message.error.message ?? "Codex app-server error"),
          message.error.code,
        ),
      );
      return;
    }
    pending.resolve(message.result);
  }

  #onExit(
    child: ChildProcessWithoutNullStreams,
    reader: Interface,
    generation: number,
    error: Error,
  ): void {
    reader.close();
    if (this.#process === child && this.#generation === generation) {
      this.#reader = null;
      this.#process = null;
    }
    this.#rejectGeneration(generation, error);
  }

  #rejectGeneration(generation: number, error: Error): void {
    for (const [id, pending] of this.#pending) {
      if (pending.generation !== generation) {
        continue;
      }
      clearTimeout(pending.timer);
      pending.reject(error);
      this.#pending.delete(id);
    }
  }
}

async function terminateChild(
  child: ChildProcessWithoutNullStreams,
): Promise<void> {
  if (hasExited(child)) return;
  child.kill("SIGTERM");
  if (await waitForExit(child, STOP_GRACE_MS)) return;
  if (!hasExited(child)) {
    child.kill("SIGKILL");
  }
  await waitForExit(child, STOP_GRACE_MS);
}

function waitForExit(
  child: ChildProcessWithoutNullStreams,
  timeoutMs: number,
): Promise<boolean> {
  if (hasExited(child)) return Promise.resolve(true);
  return new Promise((resolve) => {
    const finish = (exited: boolean): void => {
      clearTimeout(timer);
      child.off("exit", onExit);
      child.off("error", onError);
      resolve(exited);
    };
    const onExit = (): void => finish(true);
    const onError = (): void => finish(true);
    const timer = setTimeout(() => finish(false), timeoutMs);
    timer.unref();
    child.once("exit", onExit);
    child.once("error", onError);
  });
}

function hasExited(child: ChildProcessWithoutNullStreams): boolean {
  return child.exitCode !== null || child.signalCode !== null;
}

export async function resolveCodexBinary(explicit?: string): Promise<string> {
  const configured = explicit ?? process.env.QUOTAARC_CODEX_BINARY;
  if (configured) {
    return configured;
  }

  const macAppBinary = "/Applications/ChatGPT.app/Contents/Resources/codex";
  try {
    await access(macAppBinary, constants.X_OK);
    return macAppBinary;
  } catch {
    // Fall through to PATH discovery.
  }

  const pathEntries = (process.env.PATH ?? "").split(delimiter);
  for (const entry of pathEntries) {
    if (!entry) {
      continue;
    }
    const candidate = `${entry}/codex`;
    try {
      await access(candidate, constants.X_OK);
      return candidate;
    } catch {
      // Keep searching.
    }
  }

  return "codex";
}

export function safeErrorMessage(error: unknown): string {
  if (error instanceof Error) {
    return sanitizeDiagnostic(error.message);
  }
  return "Unknown Codex app-server error";
}

function sanitizeDiagnostic(value: string): string {
  const home = process.env.HOME;
  let sanitized = home ? value.replaceAll(home, "<home>") : value;
  sanitized = sanitized.replace(
    /\b(?:sk-[A-Za-z0-9_-]+|Bearer\s+[A-Za-z0-9._~-]+)\b/gi,
    "<redacted>",
  );
  return sanitized.slice(0, 300);
}
