# Xiaomi 14 connection and acceptance

Date: 2026-07-20

This runbook connects one Xiaomi 14 to a same-LAN QuotaArc Collector and
records the physical-device evidence required by Phase 2. A passing repository
CI run does not replace these steps.

## Security model

- Codex credentials and session files remain on the Mac.
- Android receives only the strict sanitized v1 snapshot.
- The Collector listens on loopback unless LAN access is explicitly enabled.
- LAN mode binds one concrete Mac interface address, never a wildcard address.
- Android pins the paired TLS certificate and still checks the certificate
  subject alternative name against the HTTPS origin.
- Device requests use a device-scoped HMAC credential with timestamp and nonce
  replay protection.
- Do not configure router port forwarding or expose the v1 listener to the
  public internet.

See [ADR 0003](adr/0003-direct-pinned-tls-device-transport.md) for the complete
decision.

## Prerequisites

- Node.js 24 or newer and pnpm 11 or newer;
- an OpenSSL CLI whose `req` command supports `-addext`;
- JDK 17 plus an Android SDK with `adb`, Platform 36, and Build Tools 36;
- `jq` for creating and inspecting negative-test bundles;
- a Xiaomi 14 on the same trusted LAN as the Mac;
- the repository checked out locally, with every command below started from
  its root unless the step says otherwise.

Establish absolute paths once in the shell that will run the acceptance
commands:

```bash
export QA_REPO_ROOT="$(pwd -P)"
export QA_COLLECTOR="$QA_REPO_ROOT/services/collector/dist/package/quotaarc.mjs"
test -f "$QA_REPO_ROOT/package.json"
command -v node pnpm openssl jq adb
openssl req -help 2>&1 | grep -q -- -addext
```

## 1. Build the Collector

From the repository root:

```bash
pnpm install --frozen-lockfile
pnpm run ci
pnpm --filter @quotaarc/collector bundle
```

The runnable single-file CLI is:

```text
services/collector/dist/package/quotaarc.mjs
```

The Mac must have a signed-in Codex installation. `doctor` and one live
collection should pass before opening a phone listener:

```bash
node services/collector/dist/package/quotaarc.mjs doctor
node services/collector/dist/package/quotaarc.mjs collect --once
```

Do not paste live output into an issue. It is sanitized by contract, but quota
and activity values are still personal account data.

## 2. Select the Mac LAN address

The phone and Mac must be on the same local network. On a typical Wi-Fi Mac:

```bash
ipconfig getifaddr en0
```

If this returns nothing, identify the active interface in macOS Network
settings. The examples below use `192.168.1.23`; replace every occurrence with
the actual address.

The selected address must remain consistent in all three places:

```text
TLS certificate subject alternative name
pairing bundle endpoint
Collector --host listener
```

## 3. Create the TLS identity once

```bash
node services/collector/dist/package/quotaarc.mjs \
  device tls-init \
  --host 192.168.1.23
```

The command refuses to overwrite an existing identity. Keep the generated
private state directory private. Do not delete the certificate or key merely
to retry a connection: replacing them invalidates existing pairings.

If the Mac's stable address changes, deliberately create a new TLS identity
and re-pair every device. Never overwrite the active default state. Build the
replacement in a new permanent private directory:

```bash
export QA_REPO_ROOT="$(pwd -P)"
export QA_COLLECTOR="$QA_REPO_ROOT/services/collector/dist/package/quotaarc.mjs"
export QA_NEW_LAN_IP=192.168.1.24
export QA_NEW_STATE=/absolute/private/path/quotaarc-new-ip
test ! -e "$QA_NEW_STATE"
mkdir -m 700 "$QA_NEW_STATE"
umask 077

QUOTAARC_DEVICE_STATE_DIRECTORY="$QA_NEW_STATE" \
  node "$QA_COLLECTOR" device tls-init --host "$QA_NEW_LAN_IP"
QUOTAARC_DEVICE_STATE_DIRECTORY="$QA_NEW_STATE" \
  node "$QA_COLLECTOR" device issue \
    --label "Xiaomi 14 new LAN identity" \
    --endpoint "https://${QA_NEW_LAN_IP}:8443" \
    > "$QA_NEW_STATE/xiaomi14-pairing.json"
QUOTAARC_DEVICE_STATE_DIRECTORY="$QA_NEW_STATE" \
  node "$QA_COLLECTOR" serve \
    --allow-lan --host "$QA_NEW_LAN_IP" --port 8443
```

The new registry intentionally creates a new `collectorId`. Test and Save the
new bundle, verify the new ID and a successful refresh, and migrate every
device before retiring the old listener. Keep the old private state intact
until rollback is no longer needed; then revoke its devices and retain it only
in an encrypted backup or securely dispose of it.

The migration is not complete until the new state path is persistent in the
actual Collector launch configuration. Add this exact environment variable to
the shell wrapper or service that starts and manages the Collector:

```bash
export QUOTAARC_DEVICE_STATE_DIRECTORY=/absolute/private/path/quotaarc-new-ip
node /absolute/path/to/QuotaArc/services/collector/dist/package/quotaarc.mjs \
  device list
node /absolute/path/to/QuotaArc/services/collector/dist/package/quotaarc.mjs \
  serve --allow-lan --host 192.168.1.24 --port 8443
```

Until that launch configuration is installed, prefix **every** later
`device issue`, `device list`, `device revoke`, and `serve` command with
`QUOTAARC_DEVICE_STATE_DIRECTORY="$QA_NEW_STATE"`; an unprefixed command would
silently return to the old default registry. Stop and restart the new listener
once with the persistent configuration and verify that its `collectorId` and
certificate fingerprint are unchanged before retiring the old state.

The new directory is now production state. Delete only
`xiaomi14-pairing.json` after transfer, not the certificate, private key, or
registry.

## 4. Issue the Xiaomi 14 pairing bundle

```bash
export QA_MAIN_PAIRING_FILE="$(
  mktemp "${TMPDIR:-/tmp}/quotaarc-main-pairing.XXXXXX"
)"
chmod 600 "$QA_MAIN_PAIRING_FILE"
umask 077
node services/collector/dist/package/quotaarc.mjs \
  device issue \
  --label "Xiaomi 14" \
  --endpoint https://192.168.1.23:8443 \
  > "$QA_MAIN_PAIRING_FILE"
export QA_ORIGINAL_MAIN_DEVICE_ID="$(
  jq -r '.deviceToken | split(".")[1]' "$QA_MAIN_PAIRING_FILE"
)"
printf 'Record original main deviceId: %s\n' "$QA_ORIGINAL_MAIN_DEVICE_ID"
jq . "$QA_MAIN_PAIRING_FILE"
```

The displayed JSON contains the device token and can never be recovered from
the registry. Transfer it directly to the phone, paste the complete JSON into
the QuotaArc setup screen, and record only the non-secret original
`deviceId` in the acceptance receipt. Then remove the temporary file and any
clipboard note or message used for transfer:

```bash
rm -f "$QA_MAIN_PAIRING_FILE"
unset QA_MAIN_PAIRING_FILE
```

The Collector registry stores the derived verification key, not the printed
token or token secret. That key is still authentication-equivalent: keep the
registry private, never commit it, and include it only in encrypted backups.

## 5. Start the Collector listener

```bash
node services/collector/dist/package/quotaarc.mjs \
  serve \
  --allow-lan \
  --host 192.168.1.23 \
  --port 8443
```

The startup receipt must show:

```text
status = listening
endpoint = the paired HTTPS origin
collectorId = the pairing bundle identity
certificateSha256 = the pairing bundle fingerprint
lanEnabled = true
```

QuotaArc never changes the macOS firewall. If macOS asks whether the signed
Node/QuotaArc process may accept incoming connections, allow only the intended
local-network use. A timeout from the phone is not by itself proof of an
authentication or certificate failure; first check listener address, Wi-Fi
isolation, and firewall state.

Leave this terminal running during device validation. `Ctrl-C` or `SIGTERM`
closes the HTTPS server and Collector resources.

## 6. Build and install Android

The shared verification command builds debug and minified release variants:

```bash
pnpm android:verify
```

For a debug install:

```bash
adb devices
export QA_XIAOMI_SERIAL=<exact-serial-from-adb-devices>
adb -s "$QA_XIAOMI_SERIAL" shell getprop ro.product.manufacturer
adb -s "$QA_XIAOMI_SERIAL" shell getprop ro.product.model
adb -s "$QA_XIAOMI_SERIAL" install -r \
  apps/android/app/build/outputs/apk/debug/app-debug.apk
```

Record the two properties and confirm that the exact serial is the Xiaomi 14
before using any later ADB command. Keep `QA_XIAOMI_SERIAL` set for sections 8
and 9.

In the app:

1. open **Setup**;
2. paste the complete pairing JSON;
3. choose **Test connection**;
4. confirm the test succeeds without saving;
5. choose **Save connection**;
6. confirm the secret draft is cleared and **Details** shows a validated
   snapshot;
7. add the QuotaArc widget and test compact and medium sizes.

The next section gives reproducible physical checks for malformed pairing,
wrong certificate pin, hostname mismatch, bad authentication, and revocation.
Redirect, oversized-response, identity-mismatch, and invalid-summary injection
are deterministic Android automated gates because the production Collector
cannot emit those responses by configuration.

### 6.1 Run physical negative pairing cases

Keep the currently active Collector running and record its `collectorId` from
the Details screen. First use **Test connection** with the isolated valid
bundle to prove that Test alone saves nothing. Then use **Save connection** for
each deliberately corrupted or revoked bundle: every Save must fail and leave
the recorded ID and snapshot unchanged. Never choose Save for the still-valid
isolated bundle because that would intentionally switch the active Collector.

Prepare a separate private state directory and a valid negative-test listener.
Replace the example address first:

```bash
export QA_MAC_LAN_IP=192.168.1.23
export QA_REPO_ROOT="$(pwd -P)"
export QA_COLLECTOR="$QA_REPO_ROOT/services/collector/dist/package/quotaarc.mjs"
export QA_NEG_STATE="$(mktemp -d "${TMPDIR:-/tmp}/quotaarc-neg.XXXXXX")"
chmod 700 "$QA_NEG_STATE"
umask 077

QUOTAARC_DEVICE_STATE_DIRECTORY="$QA_NEG_STATE" \
  node "$QA_COLLECTOR" device tls-init --host "$QA_MAC_LAN_IP"
QUOTAARC_DEVICE_STATE_DIRECTORY="$QA_NEG_STATE" \
  node "$QA_COLLECTOR" device issue \
    --label "Xiaomi 14 negative test" \
    --endpoint "https://${QA_MAC_LAN_IP}:8445" \
    > "$QA_NEG_STATE/valid.json"

jq '
  .certificateSha256 |=
    if startswith("0") then "1" + .[1:] else "0" + .[1:] end
' "$QA_NEG_STATE/valid.json" > "$QA_NEG_STATE/wrong-pin.json"

jq '
  .deviceToken |=
    if endswith("A") then .[0:-1] + "B" else .[0:-1] + "A" end
' "$QA_NEG_STATE/valid.json" > "$QA_NEG_STATE/bad-token.json"

printf '%s\n' '{"pairingVersion":1,"endpoint":' \
  > "$QA_NEG_STATE/malformed.json"
```

Start the isolated listener in the same shell as a background process. Its
receipt and any later diagnostics remain in the private state directory:

```bash
QUOTAARC_DEVICE_STATE_DIRECTORY="$QA_NEG_STATE" \
  node "$QA_COLLECTOR" serve \
    --allow-lan \
    --host "$QA_MAC_LAN_IP" \
    --port 8445 \
    > "$QA_NEG_STATE/server.log" 2>&1 &
export QA_NEG_SERVER_PID=$!
sleep 1
kill -0 "$QA_NEG_SERVER_PID"
sed -n '1,20p' "$QA_NEG_STATE/server.log"
```

Use **Test connection** for `valid.json`. It must succeed while the active
Collector ID remains unchanged. For each remaining file, use **Save
connection** and capture the rejected result:

| Bundle | Required result |
|---|---|
| `valid.json` | Test succeeds and saves nothing |
| `malformed.json` | Save fails with `pairing.invalid`; active Collector unchanged |
| `wrong-pin.json` | Save fails with `tls.certificate_mismatch`; active Collector unchanged |
| `bad-token.json` | Save fails with `auth.invalid`; active Collector unchanged |

Then revoke the valid negative-test credential and repeat Test:

```bash
export QA_NEG_DEVICE_ID="$(
  jq -r '.deviceToken | split(".")[1]' "$QA_NEG_STATE/valid.json"
)"
QUOTAARC_DEVICE_STATE_DIRECTORY="$QA_NEG_STATE" \
  node "$QA_COLLECTOR" device revoke --id "$QA_NEG_DEVICE_ID"
```

Use **Save connection** with the same `valid.json`. It must now fail with
`auth.invalid`, while the pre-existing active Collector and last-good snapshot
remain unchanged.

For a real hostname-verifier failure, create a certificate for the
documentation-only IP `192.0.2.1`, then deliberately mutate only the pairing
origin to the Mac's actual address:

```bash
export QA_HOST_STATE="$(mktemp -d "${TMPDIR:-/tmp}/quotaarc-host.XXXXXX")"
chmod 700 "$QA_HOST_STATE"

QUOTAARC_DEVICE_STATE_DIRECTORY="$QA_HOST_STATE" \
  node "$QA_COLLECTOR" device tls-init --host 192.0.2.1
QUOTAARC_DEVICE_STATE_DIRECTORY="$QA_HOST_STATE" \
  node "$QA_COLLECTOR" device issue \
    --label "Xiaomi 14 hostname test" \
    --endpoint https://192.0.2.1:8446 \
  | jq --arg endpoint "https://${QA_MAC_LAN_IP}:8446" \
      '.endpoint = $endpoint' \
  > "$QA_HOST_STATE/wrong-host.json"
```

Start this listener on the real interface in the same shell:

```bash
QUOTAARC_DEVICE_STATE_DIRECTORY="$QA_HOST_STATE" \
  node "$QA_COLLECTOR" serve \
    --allow-lan \
    --host "$QA_MAC_LAN_IP" \
    --port 8446 \
    > "$QA_HOST_STATE/server.log" 2>&1 &
export QA_HOST_SERVER_PID=$!
sleep 1
kill -0 "$QA_HOST_SERVER_PID"
sed -n '1,20p' "$QA_HOST_STATE/server.log"
```

Using **Save connection** with `wrong-host.json` must fail with
`tls.handshake_failed`; the paired fingerprint is correct, so this exercises
the platform hostname verifier and verifies that the candidate is not
persisted. Stop both negative listeners afterward:

```bash
kill "$QA_NEG_SERVER_PID" "$QA_HOST_SERVER_PID"
wait "$QA_NEG_SERVER_PID" "$QA_HOST_SERVER_PID" 2>/dev/null || true
```

The JSON files contain credentials: keep the directories private, never commit
or upload them, and securely dispose of them after recording the evidence.

### 6.2 Re-run synthetic protocol gates

The production server has no redirect, oversized, identity-mutation, or
invalid-summary mode. Those failure paths are exercised without weakening the
release server:

```bash
apps/android/gradlew -p apps/android \
  :data:testDebugUnitTest \
  --tests \
  dev.quotaarc.android.data.api.PinnedHttpsQuotaArcDeviceApiTest \
  --tests \
  dev.quotaarc.android.data.connection.QuotaArcConnectionManagerTest
```

Attach the XML test report and the matching GitHub `android` job URL. Do not
claim these synthetic cases as physical-device observations.

## 7. Revoke or rotate a device

List records without revealing credentials:

```bash
node services/collector/dist/package/quotaarc.mjs device list
```

Revoke the exact device ID:

```bash
node services/collector/dist/package/quotaarc.mjs \
  device revoke \
  --id <deviceId>
```

The next phone request for that exact credential must fail authentication.
Rotation is:

1. issue a new device bundle;
2. test and save it on the phone;
3. revoke the old device ID.

### 7.1 Verify a real Collector identity switch

Issuing twice from one registry does **not** change `collectorId`. Use two
private state directories, two certificates, and two ports:

```bash
export QA_A_STATE="$(mktemp -d "${TMPDIR:-/tmp}/quotaarc-a.XXXXXX")"
export QA_B_STATE="$(mktemp -d "${TMPDIR:-/tmp}/quotaarc-b.XXXXXX")"
export QA_REPO_ROOT="$(pwd -P)"
export QA_COLLECTOR="$QA_REPO_ROOT/services/collector/dist/package/quotaarc.mjs"
export QA_MAC_LAN_IP=192.168.1.23
chmod 700 "$QA_A_STATE" "$QA_B_STATE"
umask 077

# The original one-time main bundle should already have been disposed of.
# Issue a private, dedicated bundle that can return the phone to the live
# main Collector on 8443 after the A/B isolation test.
node "$QA_COLLECTOR" device issue \
  --label "Xiaomi 14 acceptance return" \
  --endpoint "https://${QA_MAC_LAN_IP}:8443" \
  > "$QA_A_STATE/main-return.json"

QUOTAARC_DEVICE_STATE_DIRECTORY="$QA_A_STATE" \
  node "$QA_COLLECTOR" device tls-init --host "$QA_MAC_LAN_IP"
QUOTAARC_DEVICE_STATE_DIRECTORY="$QA_B_STATE" \
  node "$QA_COLLECTOR" device tls-init --host "$QA_MAC_LAN_IP"

QUOTAARC_DEVICE_STATE_DIRECTORY="$QA_A_STATE" \
  node "$QA_COLLECTOR" device issue \
    --label "Xiaomi 14 Collector A" \
    --endpoint "https://${QA_MAC_LAN_IP}:8451" \
    > "$QA_A_STATE/pairing.json"
QUOTAARC_DEVICE_STATE_DIRECTORY="$QA_B_STATE" \
  node "$QA_COLLECTOR" device issue \
    --label "Xiaomi 14 Collector B" \
    --endpoint "https://${QA_MAC_LAN_IP}:8452" \
    > "$QA_B_STATE/pairing.json"

test "$(
  jq -r .collectorId "$QA_A_STATE/pairing.json"
)" != "$(
  jq -r .collectorId "$QA_B_STATE/pairing.json"
)"
```

Run A and B as background processes from the same shell. B deliberately serves
the checked-in, sanitized `Collector B fixture`, making cache leakage visible:

```bash
QUOTAARC_DEVICE_STATE_DIRECTORY="$QA_A_STATE" \
  node "$QA_COLLECTOR" serve \
    --allow-lan --host "$QA_MAC_LAN_IP" --port 8451 \
    > "$QA_A_STATE/server.log" 2>&1 &
export QA_A_SERVER_PID=$!
QUOTAARC_DEVICE_STATE_DIRECTORY="$QA_B_STATE" \
  node "$QA_COLLECTOR" serve \
    --allow-lan --host "$QA_MAC_LAN_IP" --port 8452 \
    --fixture \
    "$QA_REPO_ROOT/services/collector/fixtures/acceptance-collector-b.json" \
    > "$QA_B_STATE/server.log" 2>&1 &
export QA_B_SERVER_PID=$!
sleep 1
kill -0 "$QA_A_SERVER_PID" "$QA_B_SERVER_PID"
sed -n '1,20p' "$QA_A_STATE/server.log"
sed -n '1,20p' "$QA_B_STATE/server.log"
```

On the Xiaomi 14:

1. Test and save A; capture the Details `collectorId`, generated time, and
   widget.
2. Test and save B; the Details ID must change immediately. The quota label
   must be `Collector B fixture` and official daily activity must be `222`.
3. Refresh the app and widget. No A snapshot may appear under B's ID.
4. Save A again and refresh. The B-only label and values must disappear; no B
   snapshot may appear under A's ID.
5. Test and save `main-return.json`. Details must return to the original main
   Collector ID recorded before section 6.1, and a refresh against the still
   running main listener on port 8443 must succeed before A or B is stopped.

Capture the main, A, and B pairing IDs and the A/B app/widget states without
capturing any device token.

Complete the main credential rotation only after step 5 succeeds. Replace the
first placeholder with the original non-secret ID recorded in section 4:

```bash
export QA_ORIGINAL_MAIN_DEVICE_ID=<recorded-original-main-device-id>
export QA_RETURN_DEVICE_ID="$(
  jq -r '.deviceToken | split(".")[1]' "$QA_A_STATE/main-return.json"
)"
test "$QA_ORIGINAL_MAIN_DEVICE_ID" != "$QA_RETURN_DEVICE_ID"
node "$QA_COLLECTOR" device list
node "$QA_COLLECTOR" device revoke --id "$QA_ORIGINAL_MAIN_DEVICE_ID"
node "$QA_COLLECTOR" device list
```

The final list must show only the original ID revoked; the active return
credential must remain unrevoked.

Stop both isolated Collectors after recording the switch evidence:

```bash
kill "$QA_A_SERVER_PID" "$QA_B_SERVER_PID"
wait "$QA_A_SERVER_PID" "$QA_B_SERVER_PID" 2>/dev/null || true
```

Both state directories contain TLS private keys, authentication-equivalent
registry keys, and plaintext one-time pairing JSON. Do not upload or commit
either directory or either `pairing.json`; keep them private and securely
dispose of them after recording sanitized evidence.

## 8. Phase 2 physical-device matrix

Record the time, app version, commit, Collector version, macOS version,
HyperOS version, network, and evidence location for every row.

Treat each row as an independent case. Start every row with the Xiaomi paired
to the active main Collector on port 8443, its credential active, Wi-Fi on,
battery/idle controls reset, and one successful refresh. Restore that baseline
before moving to the next row. The revocation row is intentionally last; to
repeat later rows afterward, issue, Test, and Save a fresh main bundle first.

| Case | Procedure | Required observation |
|---|---|---|
| Initial pairing | Test, then save a fresh bundle | Failed tests save nothing; successful save immediately shows the same `collectorId` |
| Compact widget | Resize to the smallest supported size | Lowest remaining bucket, reset time, and freshness are visible without cropping |
| Medium widget | Resize to medium | All current buckets and today's local activity remain reachable and readable |
| Manual refresh | Tap widget refresh and app refresh | Loading is visible, then success, stale fallback, or a normalized error |
| Phone offline | Disable Wi-Fi, refresh | Last-good snapshot remains visible and phone cache is marked fallback/offline |
| Collector stopped | Stop the server, refresh, restart it | Cache survives; a later refresh recovers without re-pairing |
| Collector identity switch | Execute section 7.1 with independent A/B state and distinct fixture data | Details shows the new `collectorId`; no snapshot from the previous identity appears |
| Process death | Stop the app, relaunch it | Encrypted connection and last-good snapshot recover; refresh works |
| Reboot | Reboot phone and unlock | App and widget restore cached state; periodic work is registered again |
| Battery saver | Enable HyperOS battery saver | No request storm; freshness honestly reflects delayed work |
| Doze | Run an overnight idle sample | Actual update times are recorded, including delays; no minute-level loop |
| Light/dark | Switch system theme | Text, arc/surface colors, and error states remain legible |
| Accessibility | Enable large text/TalkBack | Controls have meaningful labels and critical state is not color-only |
| Credential revoked | Revoke the current device, refresh | Authentication failure appears; no last-good data is replaced |

Android force-stop has platform-specific semantics: background work is not
expected to run while the package remains force-stopped. The recovery check is
to relaunch the app, confirm stored state, and confirm scheduling resumes.

## 9. ADB evidence helpers

WorkManager diagnostics can be requested for the debug application:

```bash
adb -s "$QA_XIAOMI_SERIAL" shell am broadcast \
  -a androidx.work.diagnostics.REQUEST_DIAGNOSTICS \
  -p dev.quotaarc.android.debug
adb -s "$QA_XIAOMI_SERIAL" logcat -d -s WM-DiagnosticsWrkr
```

For a controlled Doze exercise, restore the device after the sample:

```bash
adb -s "$QA_XIAOMI_SERIAL" shell dumpsys battery unplug
adb -s "$QA_XIAOMI_SERIAL" shell dumpsys deviceidle force-idle
adb -s "$QA_XIAOMI_SERIAL" shell dumpsys deviceidle unforce
adb -s "$QA_XIAOMI_SERIAL" shell dumpsys battery reset
```

Do not leave a daily-use phone in forced idle or a simulated unplugged battery
state.

Capture:

- screenshots for compact/medium, light/dark, cached/offline, and auth states;
- WorkManager diagnostics before and after reboot;
- Collector startup and safe request receipts without tokens;
- exact manual-refresh timestamps;
- overnight widget `updatedAt` observations and battery impact.

## 10. Acceptance receipt

Use this receipt in the release evidence:

```text
Commit:
Android versionName/versionCode:
Collector package checksum:
Mac model/macOS:
Mac LAN address:
Xiaomi 14 model/HyperOS:
Test start/end:
Evidence root:
Original main deviceId (non-secret):
Initial pairing — result / evidence location:
Malformed pairing Save — result / evidence location:
Wrong-pin Save — result / evidence location:
Wrong-host Save — result / evidence location:
Bad-token Save — result / evidence location:
Revoked-token Save — result / evidence location:
Compact widget — result / evidence location:
Medium widget — result / evidence location:
Manual refresh — result / evidence location:
Phone offline/recovery — result / evidence location:
Collector stopped/recovery — result / evidence location:
Collector identity switch — result / evidence location:
Process death — result / evidence location:
Reboot — result / evidence location:
Battery saver — result / evidence location:
Overnight Doze — result / evidence location:
Light/dark — result / evidence location:
Accessibility — result / evidence location:
Credential revoked — result / evidence location:
Known deviations:
Reviewer:
```

Any blank physical-device row remains an open release gate. It must not be
inferred from emulator, JVM, lint, APK, or CI evidence.
