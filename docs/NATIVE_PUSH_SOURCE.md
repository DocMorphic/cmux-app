# Native Android push source — 2026-10-11

## Selected architecture

The user selected **private Firebase plus a custom cmux Mac build**. Limit the Mac
extension to the real phone producer and authenticated host/account/settings
state. Existing Android terminal/workspace functionality does not require this
extension. The intended Firebase account is davedharmay@gmail.com (user correction). The
initial empty Cloud project was created under the wrong cached account and shut
down; replacement provisioning awaits Gmail browser authorization. Live delivery
remains unconfigured/unverified.
See [FIREBASE_SETUP.md](FIREBASE_SETUP.md).

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

1. Implement the injected sink’s private child-process owner, bootstrap, bounded
   exact-frame receipts/replay and native lifecycle fencing. Assemble the
   journal/dispatcher/forwarder/sealer/enrollment service with its keys and HTTPS.
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
