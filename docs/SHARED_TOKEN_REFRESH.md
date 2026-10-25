Current local-authority follow-up: [Access-only session authority](ACCESS_ONLY_SESSION.md)
records the 2026-10-25 live-verification receipt, restoration cancellation and
initial v2 control credential split. The earlier evidence below retains its
scope; full parity and live-device acceptance remain open.

## Completion-wait follow-up — 2026-10-25

[Auth completion waits](AUTH_COMPLETION_WAITS.md) moves preflight and sign-out
completion/server waits onto the production continuous clock. Read-only joins
and structured server cancellation retain different ownership. Larger registry,
finish/high-water and caller/UI error-composition comparisons remain open.

## Current-owner follow-up — 2026-10-25

[Current-session owners](CURRENT_SESSION_OWNERS.md) records credential-free
snapshot/deletion owner checks and retained deletion outcomes during unavailable
storage. This supersedes the older current-owner open item below. Complete
phase/transition wiring and Android's extra deletion-receipt admission behavior
remain open; this is not complete account-deletion or UI/device parity.

## Cloud credential follow-up — 2026-10-25

[Cloud credential context](CLOUD_CREDENTIAL_CONTEXT.md) adds fresh Cloud pair
capture, optional-team ownership and bootstrap-inclusive deletion credential
capture. Native membership and stored snapshot admission remain separate.
Credential-free snapshot/deletion current-owner checks and whole deletion
confirmation/client behavior under unreadable storage remain open; direct
credential-capture coverage does not close those gates.

Current application-layer follow-up: [Application token state](APPLICATION_TOKEN_STATE.md)
records the 2026-10-24 miss classification, presentation clearing, writer fences
and 254/254 owned-emulator checkpoint. The evidence below retains its earlier
scope; full parity and live-device acceptance remain open.

Follow-up: [Ordinary SDK token consumers](ORDINARY_TOKEN_CONSUMERS.md) records
the later optional/resolved adapter separation, live request headers and ordinary
shared-resolver adoption. The earlier checkpoint below keeps its original scope.

# Shared token refresh ownership and cooldown

The pinned original SDK uses one TokenRefreshCoordinator attempt per token store,
with a shared exchange and result for concurrent callers. Android previously used
a mutex on each NativeAccount object. Forced callers could refresh repeatedly,
separate account objects could exchange concurrently, and failures were not shared.

## Ownership, cancellation and commit

NativeCredentialStore now shares NativeTokenRefresh per encrypted document and
account environment. Ownership includes the login incarnation, refresh token and
shared auth epoch. Token-only and fresh-token fast paths remain in the account
reader; callers that need a refresh join one attempt. A concurrently stored usable
mint can satisfy an older observation without another exchange. The result is
shared, while proactive fallback remains an individual reader decision. A forced
reader cannot reuse its rejected token because another reader permits fallback.

Waiters cancel independently. Only the last cancelled waiter retires the attempt
and cancels the exchange. A retired attempt cannot publish a late result. New
owners retire old waiters; saved credential mutations and durable local logout
also notify the registry without requiring a new request or waiting for HTTP.
That signal retires ownership synchronously, then delivers results and cancels
jobs on the dispatcher, avoiding credential/auth-work lock nesting.

The exchange remains an actual NativeAuthWork job through response handling,
encrypted credential mutation and final commit. Interactive sign-in quiesce can
join a cancelled provider that has not actually returned. A replacement refresh
attempt may run after retirement; the old actual job remains tracked. An explicit
shared-refresh admission mode permits that source behavior while the retired
attempt's commit fence prevents stale writes. Caller and producer jobs remain
separate, so cancelling one caller does not cancel a peer's exchange.

The default shared budget is 30 seconds, measured from attempt creation. Android
production reads elapsedRealtime, including system suspend. The timer uses the
original absolute deadline, and the producer checks elapsed time before applying
a result, at the final preference commit, and before returning it. A late timer
cannot authorize a late write. The final commit guard holds the registry monitor
only for SharedPreferences.commit; encryption and credential reads occur outside
that monitor. The credential monitor still protects document transactions.

Unknown encrypted storage stays a typed unavailable verdict. Compare-and-write
checks the captured login/refresh/epoch; definitive 400/401 rejection clears only
that owner. A fresh account or signout wins against old success, rejection and
failure results. The existing cipher, key alias, account and pairing data remain.

## Rate limits and safe observation

Raw HTTP 429 from OAuth refresh now produces only Retry-After and body-kind
metadata. Classification precedes JSON body decoding, so an oversized HTML
throttle cannot bypass cooldown by hitting the JSON size limit. Only an unknown
content type needs a prefix, bounded to 64 bytes. Byte whitespace follows Swift
Unicode scalar rules, including NEL and excluding JVM control separators.
Retry-After accepts finite nonnegative seconds or an HTTP date, capped
at one day while parsing. The actual cooldown clamps an explicit value to 1–600
seconds. Missing/invalid headers use exponential 5-second to 5-minute backoff,
with 0.5–1.0 jitter. Calls during cooldown share a transient result and start no
HTTP exchange. Success or definitive rejection resets the streak; only a success
after a rate limit emits recovery.

The process-local NativeRefreshReporting observer receives the fixed refresh
endpoint, body kind, parsed retry seconds, chosen cooldown and streak. It receives
no body, token, account id, query or remote host. Observer failure cannot change
auth behavior. This is the producer contract, with no automatic logging/upload;
full app telemetry/consent/reporting integration remains a separate open gate.

## Reference and remaining acceptance

The original TokenRefreshCoordinator.swift, TokenRefreshAttempt.swift,
TokenRefreshClock.swift, APIClient.swift and RateLimitObservation.swift are read
whole from `a5911a1f92f3eeb11c4d7f3e2ef89204718c5009`. The reference workflow now
runs the original SDK refresh/deadline/lifecycle/rate-limit tests in addition to
the existing original coordinator/push tests. The SDK package is seeded with the
original auth-runtime dependency lock; CI checks that resolved pins match it.
The first SDK run passed lifecycle/deadline/rate-limit fixtures but failed two
legacy signup integrations (`refreshTokenIntegration`, `concurrentGetAccessToken`)
with `network_error` while contacting their separate Stack test backend. The
fixture reference now explicitly excludes those two backend-dependent methods,
with original source unchanged. Their server acceptance remains open; they are
not treated as Android failures or waived whole-app requirements. Final [reference run 40072945627](https://github.com/DocMorphic/cmux-app/actions/runs/40072945627)
passed 198 auth/push tests in 19 suites and 33 selected SDK tests in five suites.
The copied SDK lock remained byte-identical to the original auth-runtime lock,
and the source-clean check passed. Source library tests do not establish Android
or whole-app parity.

This checkpoint does not close complete SDK/coordinator/backend admission,
access-only team/terminal flows, user metadata, real post-sign-in effects or
app telemetry routing. Physical Pixel/Mac/provider, actual OS process death,
protected-data unlock/deep-sleep scheduling and native UI/accessibility/source
adoption remain acceptance gates. A coroutine delay is not proof of exact alarm
scheduling during Doze: hard elapsed checks reject a late response, while actual
wake delivery still needs device evidence. A hanging local crypto/filesystem
operation can delay a subsequent strict read; no local-IO bound is claimed.

No phone/Mac install, live account, global source pin, signing material or frozen
signed release is changed by this development checkpoint.

## Checkpoint verification

Final full JVM: 3,290 tests across 445 suites, 3,286 passed, four existing SSH
skips, zero failures. Thirteen new coordinator/parser cases cover shared success/
failure, individual/last waiter cancellation, retired actual work, the default
30-second deadline, delayed timer registration, response-before-timer ordering,
final-commit expiry, owner retirement, concurrent stored mint and cooldown signals.
Local HTTP cases cover raw 429 metadata, large/unknown-type HTML and privacy.

Final owned API 36 runtime: 90/90 in 189.034s. Eight new actual account/crypto/
HTTP cases accompany the prior 82-case cohort. The real default deadline completed
in 30032ms with one request drained and original ciphertext preserved.
Both cancelled encryption and elapsed expiry rejected the final commit; sign-in
quiesce joined actual blocked encryption. Crash log empty, no preflight ANR,
settings/window restored and emulator exit 0.

The first 90-case run had four harness failures: one checked the registry before
asynchronous waiter detachment, two old preflight assertions treated the public
waiter as the actual exchange job, and one email-focus tap raced IME resize.
The instrumentation now waits for actual detachment, checks tracked work while
retaining the no-HTTP-before-release guard, and settles the real IME before
tapping. The target production APK is byte-identical across both runs.

The first focused JVM attempt had one fixture units mismatch (seconds versus
milliseconds); production backoff was unchanged. The first SDK CI run recorded
the two backend signup failures described above. Final source reference and
Android evidence are separate and remain scoped accordingly.

Evidence under `captures/recovery-20261023`: `shared-refresh-build-v4.log`,
`shared-refresh-build-v5.log`, `shared-refresh-jvm-v4`, `shared-refresh-runtime-v2`,
`original-sdk-refresh-ci-v2` and aggregate `shared-refresh-checkpoint.json`. The
aggregate binds eleven tested source/test files, both APKs, eleven whole original
source/test files, locked dependencies, attempts and remaining gates.
