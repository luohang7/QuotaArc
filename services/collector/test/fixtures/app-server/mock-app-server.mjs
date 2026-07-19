import { createInterface } from "node:readline";

if (process.env.MOCK_TERM_MODE === "ignore") {
  process.on("SIGTERM", () => {
    // Exercise the client's bounded SIGTERM -> SIGKILL shutdown path.
  });
}

const reader = createInterface({ input: process.stdin });

reader.on("line", (line) => {
  const request = JSON.parse(line);
  if (request.method === "initialized") {
    return;
  }

  if (request.method === "initialize") {
    respond(request.id, { serverInfo: { name: "mock-codex", version: "1.0.0" } });
    return;
  }

  if (request.method === "account/rateLimits/read") {
    if (process.env.MOCK_RATE_MODE === "timeout") {
      return;
    }
    respond(request.id, {
      rateLimits: {
        limitId: "codex",
        limitName: null,
        primary: {
          usedPercent: 20,
          windowDurationMins: 300,
          resetsAt: 1_800_000_000,
        },
        secondary: null,
      },
      rateLimitsByLimitId: null,
    });
    return;
  }

  if (request.method === "account/usage/read") {
    if (process.env.MOCK_USAGE_MODE === "unsupported") {
      process.stdout.write(
        `${JSON.stringify({
          id: request.id,
          error: { code: -32601, message: "Method not found" },
        })}\n`,
      );
      return;
    }
    respond(request.id, {
      summary: {
        lifetimeTokens: 12_345,
        peakDailyTokens: 1_234,
      },
      dailyUsageBuckets: [{ startDate: "2026-07-18", tokens: 1_234 }],
    });
  }
});

function respond(id, result) {
  process.stdout.write(`${JSON.stringify({ id, result })}\n`);
}
