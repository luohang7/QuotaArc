import { readFile } from "node:fs/promises";
import type {
  AccountUsageData,
  LocalUsageData,
  Period,
  QuotaData,
  SourceResult,
} from "./model.js";
import type { CollectorPorts } from "./collect.js";

interface FixtureDocument {
  quota: SourceResult<QuotaData>;
  accountUsage: SourceResult<AccountUsageData>;
  localUsage:
    | SourceResult<LocalUsageData>
    | Partial<Record<Period, SourceResult<LocalUsageData>>>;
}

export async function fixtureCollectorPorts(file: string): Promise<CollectorPorts> {
  let document: FixtureDocument;
  try {
    document = JSON.parse(await readFile(file, "utf8")) as FixtureDocument;
  } catch {
    throw new Error("fixture_invalid");
  }
  if (!document || typeof document !== "object") {
    throw new Error("fixture_invalid");
  }

  return {
    async readQuota() {
      return document.quota ?? { status: "unavailable" };
    },
    async readAccountUsage() {
      return document.accountUsage ?? { status: "unavailable" };
    },
    async readLocalUsage(period) {
      if (isSourceResult(document.localUsage)) {
        return document.localUsage;
      }
      return document.localUsage?.[period] ?? {
        status: "unavailable",
        code: "fixture_period_missing",
      };
    },
  };
}

function isSourceResult(
  value: FixtureDocument["localUsage"],
): value is SourceResult<LocalUsageData> {
  return typeof value === "object" &&
    value !== null &&
    "status" in value;
}
