# Native Mac sender key lifecycle — 2026-10-11

## Implemented boundary

Helper enrollment now persists the exact public native Mac descriptor from the
authenticated pairing offer inside its encrypted registration record. Delivery
checks both that descriptor and the current native phone pin before preparation,
queue admission and provider authorization, including after awaited OAuth. A valid
replacement Mac descriptor denies the old enrollment; a missing or invalid current
descriptor leaves delivery unavailable. Existing records remain readable, but a
legacy record without this binding cannot authorize production delivery or renewal.
Fresh pairing establishes the binding; loading storage never invents one.

Token renewal and provider retirement preserve the binding. Maintenance policy
reads public trust metadata by exact recipient and registration ID so it can recover
an already committed renewal's receipt after the original generation has advanced.
The maintenance protocol still checks phone proof and the exact durable receipt
and resulting registration. This metadata lookup grants no operation and returns
no token. Changed Mac or phone keys deny renewal and receipt recovery. Authenticated
cleanup remains available with fresh matching native account/Mac identity.

The Swift owner publishes the actual custom bundle's public descriptor before each
fresh host observation and child launch. Actor IO stays off the main actor, and an
unchanged canonical descriptor avoids a file rewrite. Stop, invalidation or a newer
observation during publication prevents transmitting the stale observation. The
running helper reads the current private descriptor file for each authorization.

Original notification captures commit to encrypted storage before this potentially
slow preparation. A full 128-record store submits its persisted originals to free
capacity while retaining pending admissions; it does not evict unexpired work.
Stop awaits preparation, and a stopped owner cannot start a late helper.

## Reproduced failures and repair evidence

Behavioral regression commits were committed before each repair:

| Commit | Observed failure |
| --- | --- |
| `1536ccde` | Rotation during held OAuth and rotation across helper restart each contacted the injected provider once instead of zero times. |
| `000c36a1` | A running owner continued publishing its old native descriptor until the child restarted. |
| `2e791fb9` | A committed token renewal returned HTTP 403 when its exact finish proof was retried, instead of recovering the HTTP 200 receipt. |
| `53da1f11` | While host preparation was held, the newly admitted original was absent from durable storage. |

The same focused checks pass after repair. Complete Node 22.16.0 and 26.8.2 helper
runs report **192 checks: 191 passed, one existing optional external reference
decryptor check skipped, zero failures**. The real local TLS/SQLite helper verifies
receipt equality before and after restart and rejects recovery after Mac rotation.

Actual Swift owner → production Node helper checks pass on both runtimes, including
descriptor replacement without child restart and durable capture/joined stop during
held preparation. Nine current recovery cases pass, including SIGKILL, team changes,
acknowledged source identity and full-store backpressure. Current patched Swift,
project and Bash parsing, compiled auth projection/payload/receipt checks, four
installer cases and eight tagged launch cases pass. Installer scope remains
**17 changed files / seven audited originals** at
`f4b1509054949eaad5d695569ad443c4c18ed68d`.

```sh
node --test push/*.test.mjs
python3 scripts/check-native-push-owner.py --node /absolute/path/to/node
python3 scripts/check-native-push-recovery.py --node /absolute/path/to/node
python3 scripts/check-native-push-hooks.py --upstream /absolute/path/to/upstream-cache --node /absolute/path/to/node
```

Preserved red/green logs and current receipts are ignored under
`captures/runtime/push-native-mac-key/`. Accounts, keys, tokens and provider
transports in these checks are synthetic. No real Google/FCM request occurred.

## Acceptance still open

This batch changes the Mac owner/helper, not the Android protocol; configured
signed APK **650** remains the verified preview. Full tagged Mac compilation,
actual user Keychain rotation, native pairing/reenrollment, signed-in Pixel upgrade,
FCM/Doze/token/tap/reply/revocation workflows and full iOS parity remain unverified.
Full Xcode, trusted HTTPS and a connected physical device are required for the next
live integration pass. The official cmux app must remain running and unchanged;
the custom Mac build uses a distinct tag and bundle.
