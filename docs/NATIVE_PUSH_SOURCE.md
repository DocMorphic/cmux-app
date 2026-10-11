# Native Android push source — 2026-10-11

## Selected architecture

The user selected **private Firebase plus a custom cmux Mac build**. Limit the Mac
extension to the real phone producer and authenticated host/account/settings
state. Existing Android terminal/workspace functionality does not require this
extension. The Gmail-owned dedicated Firebase project and Android client configuration now
exist. A private provider credential and real messaging-scope OAuth are verified;
live FCM delivery and production provider wiring remain unverified.
See [FIREBASE_SETUP.md](FIREBASE_SETUP.md).

## Production helper startup — 2026-10-11

The production CLI/configuration composition and owner-only local pairing control
are implemented. Explicit startup validates private files and exact helper/provider
identity, binds TLS and a private Unix socket, and consumes only the native owner's
private pipes. Source EOF/stop closes listeners and clears owned key buffers after
component shutdown. **122 focused checks pass on Node 22/26**, including six actual
runtime/CLI cases. No live recipient/provider delivery or full Mac app build is
established. See [PUSH_HELPER_RUNTIME.md](PUSH_HELPER_RUNTIME.md) for the configuration,
commands, original failed fixture expectation, evidence and exact native startup
point to inject next. Native descriptor publication, keys/TLS provisioning,
composition injection and encrypted producer persistence remain open.

## Composed helper lifecycle — 2026-10-11

`push/helper-service.mjs` now assembles the private native channel, encrypted
registration/source/outbox stores, forwarding scheduler, FCM sender and HTTPS
enrollment/maintenance server. Construction is passive; the owner explicitly
supplies credentials, TLS, descriptors and listener interface before `start()`.
Only a fresh authenticated native lease can issue a host-local offer or authorize
enrollment/maintenance. Persisted registrations and phone claims cannot establish
that authority. Offers bind the current native epoch and both public descriptors;
the HTTP endpoint exposes challenge steps, never offer issuance.

Stop fences admission immediately, clears native authority, closes owned network
connections and awaits active encryption/provider work before closing stores and
wiping owned keys. EOF retires the entire service. Startup failure/stop races also
close partial listeners/stores. Caller-owned private streams and credential
providers remain the owner's responsibility; close credentials after awaited stop.

**101 focused checks passed** on Node 26.8.2 (526.512 ms) and 22.16.0 (580.709 ms),
including nine new assembly cases. These use real local TLS, the actual enrollment
proof protocol, private source frames and encrypted SQLite stores, with an injected
test sealer/provider transport. They verify end-to-end component routing, original
expiry, epoch rejection, privacy fencing during OAuth, source EOF and awaited
shutdown during encryption/provider work. They do not send a real Firebase push.

```sh
node --test push/helper-service.test.mjs push/native-source*.test.mjs push/source-journal.test.mjs push/source-dispatcher.test.mjs push/forwarder.test.mjs push/enrollment*.test.mjs push/maintenance.test.mjs
```

Original logs: ignored `captures/runtime/push-native-owner/helper-suite-node26.log`
and `helper-suite-node22.log`. The later runtime checkpoint above closes production
CLI/owner control/provider composition. Helper keys/TLS, native composition
injection and encrypted producer persistence remain required. No production
listener, APK, emulator or physical test ran.

## Native private process supervisor — 2026-10-11

`NativePushSourceProcessSink.swift` now implements the injected sink using one
explicitly enabled, owner-configured child process. Construction/admission are
passive until `start()`. Fresh random channel key/session travel through private
pipes; command arguments/environment carry no channel key. The child receives a
minimal environment. DispatchIO performs pipe operations outside the main actor,
with bounded receipt decoding, one pending write/frame and completed-write/receipt
coordination. Diagnostics expose fixed phase names only.

Auth/privacy/master retirement closes IO and kills only the owned helper, then
waits for Foundation’s actual child-exit callback before replacement. Unexpected
exit/pipe failure clears live authority; fresh native observations are required
for recovery. Lost sequenced receipts retry the exact frame; a stalled bootstrap
retires its channel because bootstrap replay is not a sequenced helper message.
Explicit stop awaits child exit; owner release also terminates the private child.

The initial real process fixture exited SIGTRAP before any capture. Regression
commit `f337e279` preserves that state. The original crash report identifies a
Swift actor assertion in an actor-inherited legacy DispatchIO callback. Explicit
`@Sendable` IO/termination/cleanup closures now cross into main-actor Tasks only for
state updates. The same process scenario passes after the fix.

Actual Swift-owner/Node-child checks pass on **Node 26.8.2 and 22.16.0**: passive
startup, one real encrypted-journal capture with original expiry/Unicode body,
privacy retirement/reap/replacement, refreshed-authority recovery, awaited stop,
oversized receipt rejection, stalled-bootstrap retirement, and owner-release
cleanup independently verified against the child PID. Fixture process groups are
isolated and cleaned on success/failure. No user cmux process is targeted.

```sh
python3 scripts/check-native-push-process.py --node /absolute/path/to/node
```

Original evidence: ignored `captures/runtime/push-native-owner/` (initial crash/
red result, both final process build/behavior logs and earlier queue/mapping logs).
The installer now wires **nine changed files** against the same four original
source pins. Syntax/plist parsing, actual payload/auth projection, queue/private
pipe checks and four installer cases still pass. No full custom app build or
production composition injection is established. The fixture helper stores events
but does not send FCM; the production runtime above now supplies entrypoint/provider
composition. Keys/TLS/native installation and enrollment acceptance remain required.
Unacknowledged owner captures remain in memory;
encrypted producer persistence through Mac app death stays open. Signed 642 and
the global parity pin are unchanged.

## Native owner receipt queue — 2026-10-11

`NativePushSourceDeliveryQueue.swift` now supplies the bounded receipt/replay
state for the future Mac process sink. It performs no IO or account bootstrap.
Atomic admission accepts one native host/control or one host/capture batch, at
most 128 captures and 8 MiB of capture bodies. A rejected batch changes neither
host state nor its capture prefix. Only the latest host observation is retained;
its original 30-second lease is never extended by replay.

Each private connection begins with an owner-provisioned session/key bootstrap.
The queue retains the exact authenticated pending frame until a receipt, even
past its expiry when the acknowledgment is uncertain. A received nonack permits
fresh native host state at the unused sequence, then replay of the unchanged
capture at the next sequence. Capture/duplicate/expired/retired acknowledgments
release exactly one entry; stale duplicate receipts are harmless. Invalid receipt
shape/boolean/sequence/kind/size cannot advance or release entries.

Disconnect/unknown authority clears live controls/session but keeps original
capture bodies. Reconnect requires refreshed native authority. Account binding
changes, sign-out, master forwarding disable or tightened privacy require transport
retirement before a fresh connection. Presence alone preserves admitted/in-flight
alerts. Expired events are not renewed, and expired/future host observations cannot
authorize startup or replay.

Ten compiled fixture case groups passed in Swift language mode 6, plus actual
Swift queue/private-pipe exchanges on Node 26.8.2 and 22.16.0. In those exchanges
the helper's real adapter rejected the first capture after its host lease expired;
the Swift queue consumed that nonack, refreshed host state at the unused sequence,
then captured the unchanged original event into a real encrypted SQLite journal.
The child consumed all five receipts and exited zero; EOF retired helper authority.
The typed payload/auth mapper checks still pass. The installer now adds the queue
and frame encoder with explicit PBX membership: **eight changed files** against
the same four audited original hashes. Edited Swift syntax/plist parsing and four
installer cases pass; these are not a whole-app build.

Evidence: ignored `captures/runtime/push-native-owner/check/` (compiled binaries,
case output, original compiler/behavior logs, patched source/hash receipt) and
`queue-node22.log`. Reproduce with the expanded `check-native-push-hooks.py`, then:

```sh
node scripts/native-push-delivery-queue-check.mjs captures/runtime/push-native-owner/check/queue-cases.json captures/runtime/push-native-owner/check/queue-fixture
```

The concrete native process supervisor, helper assembly and production injection
remain required. Unacknowledged owner entries are currently in memory; persistence
through **Mac app process death** needs an encrypted producer spool or integration
with the native queue. The helper journal is durable after capture receipt. No
user account, Firebase/provider request, production process/listener, APK or full
custom app build was exercised. Signed 642/global parity pin are unchanged.

## Native producer hooks and preserved admission — 2026-10-11

`scripts/apply-native-push-patch.py` now installs a bounded extension into an
**exact private checkout** at `f4b1509054949eaad5d695569ad443c4c18ed68d`.
It verifies four original file hashes before writing six files: PhonePushClient,
MacAuthComposition, the authenticated team projection, explicit Xcode membership,
and the two `push/mac-native/` sources. It refuses collisions and changed source;
`--check` makes no edits. Simulated interrupted replacement verifies rollback and
staging cleanup. Review/rebase the patch when its pinned upstream files change.

The native producer captures the actual admitted PhonePushPayload after native
eligibility/authentication and before Apple recipient discovery. Explicit dismiss
IDs, routing, text, empty-reply-to-none mapping and original 120-second expiry
are preserved. Account/team authority uses the authenticated session and verified
team resolution, including a verified teamless account; cached or unresolved
membership cannot authorize the helper. The epoch changes on session/team binding
changes and actual auth retirement, including A → B → A. Unknown authority emits
an authenticated `invalidate` control without falsely asserting logout.

The patch observes native team scopes, publishes ten-second host heartbeats, and
fences the sink synchronously before auth transition awaits. Disabling forwarding
or tightening content privacy also invalidates the sink before queued controls
can wait behind captures. Bootstrap must finish before absence of a session can
be asserted as signed-out. The concrete sink must discard stale queued host
controls and publish fresh native authority before replaying exact capture bodies.
An `offer` is atomic for its whole batch; only durable receipts release captures.
The injected sink defaults to nil: this patch alone starts no helper/listener.

Regression commit `39689e3d` reproduced two dropped-alert cases: the Mac became
active before delayed preparation, or during encryption. Both failed on the old
forwarder and pass with the fix. `phoneEligible: true` is native admission already
completed, so later presence changes do not undo it. Current forwarding, identity,
privacy, registration and expiry still fence preparation and provider IO.

| Check | Result | Evidence scope |
| --- | --- | --- |
| Source/channel/journal/dispatcher/forwarder, Node 26.8.2 | 66 passed, zero failures/skips; 500.324083 ms | Includes unknown-state control and the two admission regressions |
| Same focused suite, Node 22.16.0 | 66 passed, zero failures/skips; 427.534 ms | Supported helper runtime |
| Patch installer | Four passed | Pin/collision refusal, check mode and simulated rollback |
| Patched Swift and Xcode project | Swift syntax and plist parse passed | Not a full target typecheck/build |
| Actual upstream auth projection | Eight cases passed in Swift language mode 6 | Compiled against inert coordinator storage; cached/unresolved/generation/membership/teamless/sign-out |
| Actual upstream payload plus mapper | Compiled/executed in Swift language mode 6; validated on Node 22/26 | Literal payload mapping, leases/expiry, six rejected inputs, unknown recovery, team ABA, logout and three encrypted journal records |

Commands:

```sh
node --test push/native-source*.test.mjs push/source-journal.test.mjs push/source-dispatcher.test.mjs push/forwarder.test.mjs
python3 -m unittest discover -s scripts/tests -p test_native_push_patch.py -v
python3 scripts/check-native-push-hooks.py --upstream /absolute/path/to/pinned/upstream-cache --node /absolute/path/to/node
node scripts/native-push-hooks-check.mjs captures/runtime/push-native-hooks/check/cases.json
python3 scripts/apply-native-push-patch.py --target /absolute/path/to/private/cmux-checkout --check
```

Original logs, exact patched files/hashes, auth/mapping outputs and red/green logs:
ignored `captures/runtime/push-native-hooks/`. Public fixtures only; no credentials
or actual cmux account were loaded by these checks. There is no whole app build,
helper process owner, actual auth lifecycle, FCM request, APK or physical acceptance
in this batch. No running user cmux app was changed. Signed 642 is unchanged.

## Authoritative source

Scoped upstream commit: `f4b1509054949eaad5d695569ad443c4c18ed68d`. The global parity
pin is unchanged.

| Source | SHA-256 | Requirement |
| --- | --- | --- |
| `Sources/TerminalNotificationStore.swift` | `b35f6a4498a6a29102e1a37a8b9a9a8ff4fc8fe7b225066520e61b0f99db9c03` | `deliverNotificationSideEffects` decides phone eligibility, including focused-surface suppression. It can forward with `effects.record == false`. |
| `Sources/Cloud/PhonePushClient.swift` | `e895f51016156d18f11d4aa7d8b721252ff6759a80552b277ae3a7b45596c3df` | `forward` applies mode/presence gating; `enqueue` requires authenticated identity before Apple recipient discovery. Capture Android before that Apple gate. `forwardDismissed` supplies explicit IDs and requires forwarding enabled/authentication. |

General history misses some eligible alerts, redacts content and does not encode
phone eligibility. Never set unconditional `phoneEligible: true` on feed events.
Account identity must come from cmux's native authenticated owner. Invalidate
authority on sign-out, team/account transitions and private channel loss.

## Implemented handoff

- `NativePushSourceFrame.swift`: CryptoKit HMAC with exact length-prefixed
  domain/session/sequence/body framing.
- `native-source.mjs`: authenticated sequencing, typed messages, host lease
  projection and locally owned helper identity. Lost receipts replay without
  recapturing. Unavailable storage remains unacknowledged; requests reach the
  encrypted journal with original content and expiry.
- `native-source-channel.mjs`: bounded, explicitly started private child-pipe
  NDJSON. Bootstrap provisions only ephemeral channel session/key. Reads pause
  until receipt completion. Shutdown invalidates the live host. Diagnostics and
  receipts contain no event/token/key/account/original error text.
- `check-native-push-source.py`: reproducible CryptoKit vectors and actual Swift
  child/Node pipe round trip with durable encrypted captures.

See [channel contract](../push/README.md#private-native-source-channel) for owner
bootstrap, retry and shutdown rules. The owner supervises stream errors after
closure. These modules open no network port or production process.

## Verification

| Runtime | Focused checks passed | Failures / skips | Runtime |
| --- | ---: | --- | ---: |
| Node 26.8.2 | 63 | 0 / 0 | 485.499875 ms |
| Node 22.16.0 | 63 | 0 / 0 | 480.709458 ms |

These include the new 15 adapter/eight channel cases and existing journal,
source-dispatcher and forwarder checks. Coverage includes real CryptoKit Unicode
vectors, tampering, leases/identity substitution, sequence conflicts/lost receipts,
storage/capacity/reopen, unchanged expiry, privacy revocation, history suppression,
explicit dismissals while active, fragmented/bounded input, pipe failures, output
backpressure and immediate authority retirement. The final check explicitly
verifies EOF retires authority even while a receipt write remains pending.

The macOS Swift 6.4 build succeeded. Both Node versions accepted the actual Swift
producer's bootstrap and authenticated host/notify/dismiss frames over private
pipes. The child awaited each receipt, including two real encrypted-journal
captures, and exited zero. EOF left host authority unavailable. Regenerated
vectors match committed JSON; SHA-256:
`c6ebec56c8442934e0290be86fe74cd50aac60c34fe8dd08deac4753ad038f2a`.

Original evidence: ignored `captures/runtime/push-native-source/` (both Node logs,
pinned source snapshots, Swift build/vectors, stream logs and source-hash receipt).
Public fixture keys only; no production account/provider contact. Adapter fanout
cases inject a synthetic sealer/sender and do not establish new HPKE/FCM evidence.

## Required next integration

1. Assemble the production helper entrypoint with journal/dispatcher/forwarder/
   sealer/enrollment, keys and HTTPS. Inject the implemented process sink into the
   native composition and add encrypted unacknowledged-producer persistence.
2. Apply the reviewed hook patch in a private checkout and perform the upstream
   tagged full-app build and actual auth/settings/capture acceptance. Syntax and
   fixture compilation do not establish whole-app integration.
3. Finish correct-account dedicated Firebase provisioning (see FIREBASE_SETUP.md), register Android
   clients and provision limited provider credentials. Build/sign the custom Mac
   extension and configured Android integration milestone.
4. Verify actual Mac/Pixel foreground/background/process-dead/Doze delivery,
   token rotation, tap/reply, dismissals and account revocation.

The goal remains active; this is not a running custom Mac producer or production
push service. Signed 642 remains latest and full parity stays open.
