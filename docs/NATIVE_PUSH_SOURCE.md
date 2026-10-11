# Native Android push source — 2026-10-11

## Selected architecture

The user selected **private Firebase plus a custom cmux Mac build**. Limit the Mac
extension to the real phone producer and authenticated host/account/settings
state. Existing Android terminal/workspace functionality does not require this
extension. Firebase resources and live delivery remain unprovisioned/unverified.

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

1. Install the native hook after real eligibility/authentication and before the
   Apple recipient gate. Preserve explicit dismiss IDs; map empty reply to `none`.
2. Derive account/team/build/epoch from the native owner. Maintain heartbeat and
   immediate auth/settings transitions; supervise the helper and preserve original
   event/expiry through uncertain receipts/restarts.
3. Assemble journal/dispatcher/forwarder/sealer/enrollment in the owning helper.
   Provision storage/sealing keys and authenticated HTTPS enrollment.
4. Provision private Firebase, Android configuration and limited provider
   credentials; build/sign the custom Mac extension and Android milestone. No
   Firebase project ID or credentials have been supplied yet.
5. Verify actual Mac/Pixel foreground/background/process-dead/Doze delivery,
   token rotation, tap/reply, dismissals and account revocation.

This batch is **not a running custom Mac producer or production push service**.
No APK, signed release, Pixel installation, listener or Firebase deployment was
made. Signed 642 remains latest; full parity stays open.
