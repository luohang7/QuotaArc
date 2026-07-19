# QuotaArc research and validation

Date: 2026-07-19

## Executive conclusion

The project is feasible, with one important scope split:

1. The Collector and Xiaomi 14 widget are ready to enter implementation.
2. The Watch S4 simulator app is ready to enter implementation.
3. Watch S4 real-device delivery cannot be promised until Xiaomi grants the
   required debugging/install access.

QuotaArc must keep three kinds of data visibly separate:

| Data | Source | Authority |
|---|---|---|
| Account quota and reset time | `codex app-server` | Official account state |
| Account daily and lifetime Token activity | `codex app-server` | Official aggregate |
| Model/project/input/output/cache breakdown | Local session JSONL | Local estimate |

Local Token counts must never be converted into an assumed subscription quota
percentage. Quota forecasting will use a time series of official
`usedPercent` samples; local Token trends will be shown separately.

## Project name

The chosen name is **QuotaArc**.

It describes the quota arc used in phone and round-watch UI, while leaving room
for future providers beyond Codex.

Initial collision screening found no exact `QuotaArc` repository on
[GitHub](https://github.com/search?q=%22QuotaArc%22&type=repositories), and no
exact npm or PyPI package at the time of checking. This is a practical naming
screen, not a trademark opinion.

Names rejected during screening:

- `CodexPulse`: several existing repositories and an existing product
- `CodexGauge`: existing Codex quota dashboard projects
- `QuotaPulse`: existing quota tools, including a phone/watch-shaped product

## Codex account data

### Official surface

OpenAI's current app-server documentation lists:

- `account/rateLimits/read`
- `account/rateLimits/updated`
- `account/usage/read`

The rate-limit response can contain a compatibility bucket plus
`rateLimitsByLimitId`, dynamic primary/secondary windows, credits, plan type,
and spend-control state. The usage response contains an account summary and
daily Token buckets.

Primary source:
[OpenAI Codex app-server README](https://github.com/openai/codex/blob/main/codex-rs/app-server/README.md#auth-endpoints)

### Local live verification

Verified against the installed binary:

```text
Codex CLI: 0.145.0-alpha.18
Binary: /Applications/ChatGPT.app/Contents/Resources/codex
```

The stable schema was generated locally with:

```text
codex app-server generate-json-schema --out <temporary-directory>
```

Both methods below were then called over the documented JSONL/stdio handshake,
without enabling the experimental API:

```text
account/rateLimits/read  -> success
account/usage/read       -> success
```

Observed response capabilities:

- multiple rate-limit IDs were present;
- the standard `codex` bucket exposed one 10,080-minute window in this sample;
- another named model bucket was present;
- credits metadata was present for the standard bucket;
- account usage returned lifetime summary fields and 10 daily buckets.

This disproves the assumption that `primary` is always five hours and
`secondary` is always one week. QuotaArc will:

1. prefer `rateLimitsByLimitId` when available;
2. retain opaque `limitId`;
3. render the backend `limitName` when provided;
4. label windows from `windowDurationMins`;
5. tolerate missing primary or secondary windows;
6. calculate display remaining as `clamp(100 - usedPercent, 0, 100)`;
7. save the collection timestamp because historical snapshots can otherwise
   appear current.

### Compatibility strategy

The app-server is a local product integration surface and evolves with Codex.
The Collector will therefore:

- detect the installed Codex version at startup;
- generate or validate against versioned fixtures during development;
- capability-test optional methods;
- keep the last successful snapshot when a new version temporarily breaks a
  method;
- expose a clear `sourceStatus` and `stale` reason;
- never expose arbitrary app-server RPC remotely.

## Local Codex usage data

### Local corpus inspection

The current machine provided a useful real-world parser corpus:

```text
Session JSONL files: 23
Total size: approximately 496 MiB
Largest file: approximately 478 MB
Observed CLI versions: 0.141.0 through 0.145.0-alpha.18
Observed providers: openai and custom
```

The files are under:

```text
${CODEX_HOME:-~/.codex}/sessions/YYYY/MM/DD/*.jsonl
```

Archived sessions must also be supported:

```text
${CODEX_HOME:-~/.codex}/archived_sessions/*.jsonl
```

Relevant observed records:

- `session_meta`: thread identity, provider, initial `cwd`
- `turn_context`: `turn_id`, model, effort, current `cwd`
- `event_msg` with `payload.type == "task_started"`
- `event_msg` with `payload.type == "token_count"`
- `event_msg` with `payload.type == "task_complete"`

Observed Token fields:

- `input_tokens`
- `cached_input_tokens`
- `output_tokens`
- `reasoning_output_tokens`
- `total_tokens`
- `cache_write_input_tokens` on newer local files

The schema did change across the inspected versions. Version 0.145 added
locally observed fields including `cache_write_input_tokens` and
`spend_control_reached`. Parsing must be lenient and versioned.

### Token accounting rule

Token-count events contain repeated absolute totals. They cannot be summed
directly.

OpenAI's Symphony specification recommends:

- prefer absolute thread totals;
- take deltas from the previously observed absolute total;
- ignore `last_token_usage` as a dashboard total.

Source:
[OpenAI Symphony session metrics](https://github.com/openai/symphony/blob/main/SPEC.md#135-session-metrics-and-token-accounting)

The local corpus also contained counter decreases in one long session. QuotaArc
will treat a decrease as a new accounting segment rather than subtracting a
negative delta:

```text
if current >= previous:
    delta = current - previous
else:
    delta = current
```

This behavior needs fixtures for compaction, resume, fork, and copied parent
history before it is accepted as complete.

### CC-Switch findings

CC-Switch does read Codex session JSONL and derives deltas from
`total_token_usage`. It also scans active and archived sessions, persists
per-file synchronization state, and deduplicates session-derived rows against
proxy-derived rows.

Useful pinned references:

- [Codex session scanner](https://github.com/farion1231/cc-switch/blob/613fef70bc7d5e35299b4131935f738c85765b35/src-tauri/src/services/session_usage_codex.rs)
- [Usage database schema](https://github.com/farion1231/cc-switch/blob/613fef70bc7d5e35299b4131935f738c85765b35/src-tauri/src/database/schema.rs)
- [Usage aggregation](https://github.com/farion1231/cc-switch/blob/613fef70bc7d5e35299b4131935f738c85765b35/src-tauri/src/services/usage_stats.rs)

CC-Switch's quota lookup is not the model to copy: it reads a Codex access
token and calls a private ChatGPT backend endpoint. QuotaArc will use the
official app-server account methods instead and will not read or transmit
Codex OAuth credentials.

### CodexBar findings

CodexBar has the more relevant long-running scanner design:

- byte-offset continuation for append-only files;
- inode/device ID, size, and modification-time checks;
- parser-version cache invalidation;
- truncation and replacement recovery;
- fork/subagent history handling;
- project normalization, including Codex worktrees.

Useful pinned references:

- [App-server quota fetcher](https://github.com/steipete/CodexBar/blob/1d307430a13de0a385e2b5ab30fa7ed79d363408/Sources/CodexBarCore/UsageFetcher.swift)
- [Scanner cache and tail parsing](https://github.com/steipete/CodexBar/blob/1d307430a13de0a385e2b5ab30fa7ed79d363408/Sources/CodexBarCore/Vendored/CostUsage/CostUsageScanner%2BCacheHelpers.swift)
- [Project normalization](https://github.com/steipete/CodexBar/blob/1d307430a13de0a385e2b5ab30fa7ed79d363408/Sources/CodexBarCore/Vendored/CostUsage/CostUsageScanner.swift)

QuotaArc will reimplement the required behavior and will not copy source without
the corresponding license and attribution review.

### Local database caveat

The local `~/.codex/logs_2.sqlite` currently has a trigger that ignores all new
rows in its `logs` table. It is therefore not usable as the primary usage
source on this machine. This is also a good product-design reason not to depend
on internal diagnostic logs.

QuotaArc will use session JSONL plus app-server data. It will not modify the
Codex log database.

### Meaning of local metrics

The UI will use explicit labels:

```text
Official account activity
Local session estimate
Estimated at published API prices
```

For OpenAI-shaped usage:

```text
new input      = max(input_tokens - cached_input_tokens, 0)
cache read     = cached_input_tokens
output         = output_tokens
reasoning      = reasoning_output_tokens
processed      = new input + cache read + output
```

`input_tokens` already includes cached input in the observed accounting model,
so cached input must not be added to input a second time.

Project paths are private. APIs for phone/watch will return an alias or basename
by default, never an absolute local path.

## Xiaomi 14 / HyperOS

The phone app can use the normal Android widget stack:

- Kotlin
- Jetpack Glance
- WorkManager
- DataStore or an equivalent small persistent cache
- a normal Compose activity for details and configuration

Glance translates widget content to `RemoteViews`. Android's current guidance
allows a widget update period of up to once every 30 minutes, or WorkManager
updates as often as every 15 minutes, while warning against minute-level
background refreshes.

Sources:

- [Manage and update Glance widgets](https://developer.android.com/develop/ui/compose/glance/glance-app-widget)
- [Create a Glance widget](https://developer.android.com/develop/ui/compose/glance/create-app-widget)
- [WorkManager periodic work](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)
- [Doze and standby](https://developer.android.com/training/monitoring-device-state/doze-standby)

Design implications:

- show `updatedAt` and stale state;
- show an absolute reset time as well as a relative countdown;
- recalculate countdown text during actual widget updates;
- provide manual refresh;
- do not promise minute-accurate background countdowns;
- validate HyperOS process killing and battery settings on the actual Xiaomi 14.

## Xiaomi Watch S4

### Verified capabilities

Xiaomi's current Vela documentation confirms:

- Watch S4 has a 466×466 circular screen;
- `system.fetch` is supported;
- local storage is available;
- `system.interconnect` communicates with the paired Android app;
- package name and signing identity must match for interconnect.

Sources:

- [Vela fetch support](https://iot.mi.com/vela/quickapp/en/features/network/fetch.html)
- [Vela interconnect](https://iot.mi.com/vela/quickapp/en/features/network/interconnect.html)
- [Vela storage](https://iot.mi.com/vela/quickapp/en/features/data/storage.html)
- [Watch screen and safe-area design](https://iot.mi.com/vela/quickapp/en/guide/design/multi-screens.html)

The watch app should refresh on entry and explicit user action, then cache the
last successful snapshot. Background periodic polling is not an MVP
requirement.

### Real-device gate

Xiaomi's documentation states that Watch S4 real-device debugging requires:

- a Xiaomi-provided OTA;
- a beta Xiaomi Wear app;
- access currently limited to specific partners.

Source:
[Vela real-device debugging](https://iot.mi.com/vela/quickapp/en/tools/devicedebug/start.html)

Therefore:

- simulator completion is an implementation milestone;
- real Watch S4 completion is a separately gated milestone;
- watch-face complications/cards are not in the MVP;
- release packaging is not proof of store availability.

### Interconnect documentation conflict

The interconnect guide requires matching Android and Vela package names and
signatures, while a general manifest page has historically used different
package guidance. Before freezing the application ID or release key, QuotaArc
must run Xiaomi's minimal interconnect demo and obtain confirmation from
Xiaomi.

## Current development environment

Read-only checks found:

- macOS satisfies the current AIoT-IDE macOS requirement;
- Node.js and OpenSSL are installed;
- Android Studio, Android SDK/ADB, AIoT-IDE, and a usable Java runtime were not
  found.

Tool installation is a Phase 0 task. It is not part of this research pass.

## Security boundary

The Collector will follow these rules:

- app-server stays local and uses stdio or a protected local socket;
- no API returns prompts, assistant messages, tool arguments, local absolute
  paths, access tokens, cookies, or auth files;
- no mobile or watch package contains OpenAI credentials;
- remote clients receive only a versioned, sanitized read model;
- each device credential is read-only, revocable, and rate-limited;
- the default Collector listener is loopback-only;
- LAN, relay, and phone-to-watch transport require an explicit security design
  and test before release.

## Remaining validation gates

1. Install Android Studio and confirm a Glance widget on the Xiaomi 14.
2. Install AIoT-IDE and run a 466×466 Vela simulator project.
3. Ask Xiaomi whether this account/device can receive the Watch S4 debugging
   OTA and third-party app access.
4. Run the official interconnect demo before freezing package names/signing.
5. Select and prove the production transport from Collector to phone.
6. Build redacted fixtures for forks, copied parent history, truncation,
   partial lines, archived moves, and counter resets.
