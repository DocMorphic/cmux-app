Current snapshot follow-up: [Snapshot capture](SNAPSHOT_CAPTURE.md) records the
2026-10-25 application storage-error projection, one aggregate coherent deadline,
refresh-only ordering and 270/270 owned-emulator checkpoint. The historical
checkpoint below keeps its earlier scope; full parity and physical/UI acceptance
remain open.

Current local-authority follow-up: [Access-only session authority](ACCESS_ONLY_SESSION.md)
records the 2026-10-25 live-verification receipt, restoration cancellation and
initial v2 control credential split. The earlier evidence below retains its
scope; full parity and live-device acceptance remain open.

# Application token misses and presentation clearing

The application accessors now distinguish an absent session from a recoverable
access miss. This follows the release coordinator at audited source
`a5911a1f92f3eeb11c4d7f3e2ef89204718c5009`.

| Result | Application outcome |
| --- | --- |
| Resolved access | Return the token |
| No access, surviving refresh | Retryable network error; preserve presentation |
| Unavailable encrypted storage | Retryable network error; preserve ciphertext and presentation |
| No access/refresh in available storage | Unauthorized; retire current presentation |
| Current sign-in, published current flow, relevant validation, or sign-out capture owns a miss | Retryable error; leave that transition in control |
| Canceled, replaced, or expired phase | Preserve the classified failure; do not clear from a late result |

SDK optional and resolved-token interfaces remain separate. Coherent credential
captures continue to classify their own misses without the application clear
side effect. Pending credentials stay outside ordinary/worker views and a pending
root cannot be purged by an ordinary application miss.

A committed clear removes the current document's cached user/profile/selection
and resets the environment's token hint, then clears the model and advances its
generation. Tokens, drafts, saved pairing records, key alias and the pending email
challenge are preserved by this presentation operation. Definitive SDK refresh
rejection still owns its separate credential/draft removal. A foreign environment
cache is an unknown verdict and remains untouched; complete authentication-project
reset/scoping remains open.

## Ownership, encryption and cancellation

The read phase must win before clearing starts. Its owner captures the atomic
auth epoch and published-model generation. The final clear additionally captures
the encrypted document revision. Caller/producer cancellation, epoch/generation
changes, reentrant document writes and new transitions reject the final commit.
A final preference commit and model publication serialize with transition
registration. Encryption runs before that short commit gate. No credential IO
runs under the auth-work tracking monitor.

Sign-in registration, including orphaned completion registration, uses one lock
order. Current-flow ownership compares the flow's volatile generation and epoch;
a published current flow remains protected until it finishes. Stale flows still
remain tracked for actual work but cannot indefinitely claim presentation.
Sign-out capture wakes waiters outside its monitors.

A caller can cancel its wait while an uncooperative encryption operation remains
tracked. Fresh sign-in announces ownership before joining that old work. The old
producer's final write is rejected, and only then can the new exchange continue.
Cache clearing does not simulate sign-out or cancel an existing email challenge.
A clear that has already committed publishes that same clear atomically against
new writer registration; cancellation after that linearization point cannot undo
an authorized commit.

The token-work phase now retains the operation's result, checks completion
ownership/cancellation and its hard elapsed deadline, and only then returns or
throws the result. This also applies to failures: a late unauthorized failure
cannot beat an already-expired deadline and begin clearing. Prompt/plain phases
retain their existing admission policy. The application read remains 60 seconds;
local cache IO has no new hard-bound claim. Actual OS scheduling, suspend,
protected-storage behavior and the original full timer/finish race remain open.

## Executed source

`scripts/generate-native-application-token-reference.py` executes 13 complete
unchanged original release declarations: public access/force access and their
without-state-clear helpers, empty-token classification, release dev fallback,
current transition ownership, clear/generation advancement, both token-phase
overloads, and the client's resolved-access/refresh adapters. The 464 cases cover
optional results, typed failures, availability, seven transition states,
late outcomes, actual child-task cancellation and planned phase retirement.
Regeneration is byte-identical. Corpus SHA-256:
`366d206a1915ba2ad0cf5dd0d957ce7379efc5af68f89704ab410edc91439e84`.

Stack replies, availability, clock expiry and phase finish ownership are inert.
The original inner task executes its cancellation/deadline checks after success
and failure. Its waiter awaits that task without executing the original timer
race. Cache/apply/recovery callbacks are inert counters. This proves the stated
release control flow; it does not prove original Keychain/UserDefaults/cache
backends, the complete phase finish/high-water cleanup, scheduler behavior,
debug mock/password fallback or iOS-runtime equivalence. The reference host is
macOS 26.5.2 / CoreFoundation 5026.5 with Swift 6.3.3.

Android owner/revision fences are additional protections for its encrypted
asynchronous writes. Their race behavior is tested separately; no equality claim
is made for every original MainActor interleaving or Android storage failure.

## Verification

Full JVM: 3,478 total in 466 suites, 3,474 passed and four existing SSH skips,
zero failures/errors. Owned API36: 254/254 in 388.728 seconds: 14 new methods
(the source reference and 13 real encrypted-state cases), one existing draft
ownership race and 239 regressions. The 60-second phase returned at 60012ms with
ciphertext and pairing preserved and no selection PATCH. The shared two-second
read returned at 2093ms with the published account preserved. Crash log empty,
no preflight ANR, emulator settings/window restored and owned exit 0.

The full JVM suite executed in build-v4. Build-v5/v6 reused byte-identical JVM
XML and rebuilt the Android fixtures after verifying Kotlin method mangling and
adding same-epoch replacement coverage. Final debug APKs are build-v6. Full-v3
exposed a saved-Tailscale fixture using one FIFO response queue for parallel user
and membership reads; separate queues fixed the fixture while retaining its
compatibility assertions. The failed run remains separate evidence. Production
saved-Tailscale code was unchanged.

Source/APK/result bindings: `captures/recovery-20261024/application-token-checkpoint.json`.
No physical-device or release-install claim is made by this checkpoint.

Full parity remains active. Complete caller/phase admission and high-water
reconciliation, authentication-project reset/scoping, access-only account/team/
terminal authority, callback/form/backend/reporting, remaining metadata/image/
demo/identity/worker and diagnostic/team-error UI, actual OS/locked storage/death,
physical Pixel/Mac/provider/helper/Firebase, matched full UI/accessibility and
signed delivery remain open. No real account, pairing, signing data, global pin
or frozen signed722 changes in this checkpoint.
