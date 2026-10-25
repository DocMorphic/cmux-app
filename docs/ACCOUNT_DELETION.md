# Account deletion

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

## Upstream contract

Reviewed cmux at audited candidate `204a11dfcc76280205e50406ab94270a1c152155`:

- [AccountDeletionClient](https://github.com/manaflow-ai/cmux/blob/204a11dfcc76280205e50406ab94270a1c152155/Packages/Shared/CmuxAuthRuntime/Sources/CmuxAuthRuntime/Coordinator/AccountDeletionClient.swift)
  sends `DELETE /api/account` to the cmux backend with the current Bearer access
  token and `X-Stack-Refresh-Token`. This is separate from local device reset.
- [MobileSettingsAccountSection](https://github.com/manaflow-ai/cmux/blob/204a11dfcc76280205e50406ab94270a1c152155/Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/MobileSettingsAccountSection.swift)
  asks for destructive confirmation, prevents duplicate submission, and uses the
  normal sign-out owner after confirmed deletion.
- [DeleteAccountFailureKind](https://github.com/manaflow-ai/cmux/blob/204a11dfcc76280205e50406ab94270a1c152155/Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/MobileSettingsDeleteAccountFailureKind.swift)
  distinguishes expired authorization, partial deletion, remaining server cleanup,
  definite connection failure, timeout and unknown completion.
- [Backend route](https://github.com/manaflow-ai/cmux/blob/204a11dfcc76280205e50406ab94270a1c152155/web/app/api/account/route.ts)
  authenticates the current account and manages server deletion checkpoints.
  In particular, `account_delete_retryable` can occur before deleting user data;
  Android therefore says data **may** already be deleted rather than asserting
  every partial failure deleted all cmux data.

This is a narrow contract audit; it does not advance the broad app parity pin.

## Android behavior

Settings groups Sign out and Delete Account under Account. Deletion requires a
fresh confirmation explaining that the account and cmux data are permanently
deleted. Cancel and dismissed/recreated confirmations send no request. Local
**Erase All Data on This Device** remains a separate Android reset action.

A confirmed operation belongs to `NativeAppConnections`, which already survives
Activity recreation through the connection ViewModel. Navigation away from
Settings does not hide the eventual outcome: completion/failure presentation
lives outside the Settings branch. Controls cannot submit a second request while
one is active or its result is awaiting acknowledgement.

The request pins the published user, session generation, shared credential epoch
and login when the confirmation opens. The same binding must still be current
when confirmation is accepted, when the process-owned operation starts, across
credential resolution, and after HTTP completion. A stale callback is rejected
even before recomposition or identity-stream delivery. Current tokens resolve
the live access/refresh pair with up to three refresh-rotation brackets; this path
does not reuse the saved-pair fast path intended for Cloud/web authentication. The client requires HTTPS outside loopback tests,
disables redirects and automatic connection retries, bounds responses to 64 KiB,
and uses separate 60-second credential-capture and HTTP deadlines inside a
125-second controller bound. The absolute capture deadline includes bootstrap;
that read-only wait remains outside the token-work registry to avoid joining
itself during sign-in quiescence. A 401
is never transparently resent as another DELETE. Error response text is not
shown, and tokens are never persisted in the deletion receipt or diagnostics.

Response handling follows the audited client:

| Result | Android action |
| --- | --- |
| 2xx with no pending/cleanup flag, including empty 204 | Record completion and sign out through the app's sign-out path |
| 2xx `deletionPending: true` | Explain that completion is unknown; preserve the session |
| 2xx `cleanupIncomplete: true` | Explain remaining server cleanup; sign out after acknowledgement |
| 401 | Explain expired sign-in; sign out after acknowledgement |
| `account_delete_retryable` or legacy `account_stack_delete_failed_after_data_delete` | Explain incomplete deletion; allow a newly confirmed retry |
| `account_delete_failed` or other definitive rejection | Show failure and preserve the session |
| 408, unclassified 5xx, lost response, oversized response or interrupted request | Preserve uncertainty; never claim deletion or resend automatically |
| DNS/connect/TLS failure before request headers | Explain the connection failure |

Timeout advice explicitly preserves uncertainty. Each retry requires returning
to the confirmation action. A saved result is not a background job: boot, process
restart, account refresh and WorkManager never issue deletion requests.

## Persistence and teardown

Before acquiring credentials or starting HTTP, the controller commits an
account-encrypted version2 receipt containing a random operation ID, login,
user id, session generation, shared epoch, process incarnation and result. These
are authority metadata; access/refresh tokens are never recorded. Descriptions
redact the account and login. Version1 records remain readable, but production
projects their unbound outcome as UNKNOWN and never signs out from them. If this write fails, no request starts. Results update only
the same operation and bound account incarnation. Unknown completion survives process restart;
`PROCESSING` restored without its live owner becomes `UNKNOWN`, not a resend.
If result persistence fails after sending, the retained processing receipt also
restores as unknown. Confirmed completion and incomplete-cleanup results are
retained for the UI to handle after restart. A different process first verifies
the published user against the receipt user and retained login; it cannot reuse
process-local generation/epoch numbers. Pending verification preserves the raw
receipt and prevents resubmission. A verified different user cannot consume it.

Sign-out compares the captured published identity, epoch and login inside the
credential transaction. A check made before entering the transaction cannot
authorize clearing a replacement account. Definitive SDK rejection may have
already removed both tokens and the stored login; a bound receipt survives that
removal long enough to retire only its still-published old account. Explicit
local logout removes the receipt while preserving pairing records. The sign-in completion owner retires credential writers under that transaction,
fencing later writes by generation/epoch before clearing pending email/OAuth
state. A rejected stale conditional sign-out returns without clearing the
replacement account’s challenge. Credentials retire before stopping
notification/connection owners and clearing drafts.
An old result cannot sign out a later login or a new same-user generation. Stored login replacement and explicit sign-out prune old receipts. A same-login
generation change retires result authority and presentation; a new explicit
confirmation may replace that stale record only after validating its new binding. A local acknowledgement/sign-out storage failure offers a
retry of local cleanup; it does not send another DELETE.

## Acceptance boundary

Implementation verification uses loopback HTTP and generated fixture credentials.
It must never call the production deletion endpoint as a test or delete the
user's account. Physical confirmation/cancellation and full NativeScreen lifecycle
acceptance remain separate from the component and process-kill checks below.
Production account deletion can only be exercised by a deliberate user action on
an account the user intends to delete. No real account was changed in this work.


## Verification — 2026-10-02

**38 JVM checks passed**, zero failures/errors/skips: seven HTTP contract cases,
six controller/persistence cases, sixteen existing account-team cases and nine
email sign-in cases. New cases cover native auth headers, accepted/pending/cleanup
responses, partial deletion codes, rejected/ambiguous failures, redirects,
credential validation, account replacement before and after sending, timeout,
lost response, oversized bodies, cancellation, duplicate admission, persisted
processing/completion, reconstruction, stale acknowledgement and storage failure.

**Final ten Android tests passed in 66.541 seconds**, API 37 / 16,384-byte pages, zero
failures/skips. Four new component cases use real encrypted storage and the real
HTTP client against loopback. They exercise confirmation/cancel, saved-state UI
recreation before and after submission, no duplicate DELETE, partial deletion,
a newly confirmed retry, cleanup acknowledgement/sign-out, unknown completion,
controller reconstruction, token refresh and rejecting a stale sign-out. The
full NativeScreen test opens/cancels the actual Settings confirmation, verifies
no deletion receipt, then exercises normal sign-out. Three existing team UI tests
and two encrypted-draft/token-refresh regressions pass in the same run.

The full-screen confirmation and unknown-result captures were inspected. The
first partial-result capture preceded dialog painting despite passing UI
assertions; the capture helper now also waits for the platform window transition.
The partial/retry/cleanup case passed again in **9.994 seconds**, and the corrected
partial-result pixels were inspected. Final review then made email challenge
invalidation and credential retirement atomic. Two additional concurrency checks
verify that an in-flight exchange cannot republish during retirement, while a
stale sign-out preserves the replacement challenge. All 38 JVM and all ten Android
cases above passed on the final build, and its final confirmation/partial/unknown
captures were inspected. These checks do not claim actual OS process-kill or
physical Pixel acceptance.

Both final APKs built and passed 16 KB ZIP alignment; all five native libraries
passed LOAD/RELRO checks. SHA-256:

- Debug: `0cf37dc93abb666b66fc3204c4aa2996247c822d487d000943211b05be1b4813`
- Test: `84ff17d206049006359f02756fedf519e7fb3ff4a443c1b1f7d1bcdc70df7757`

## Process-death follow-up — 2026-10-02

The three-case account process suite passed in **56.968 seconds**, API 37 / 16 KB,
zero failures/skips. Two deletion cases use the real encrypted store, production
deletion controller/client and account UI in a dedicated debug process:

- After receiving exactly one DELETE, the loopback server withholds its response.
  The test kills the UI process. A fresh process restores the durable in-flight
  receipt as unknown, displays the uncertainty dialog and retains sign-in after
  acknowledgement. No second DELETE is sent; the dialog pixels were inspected.
- After an HTTP 204 is durably recorded, a fixture flag defers outcome display.
  The test kills the process before acknowledgement. On ordinary presentation in
  a new process, the receipt signs out through `NativeAccount.signOut`. A second
  kill/relaunch remains signed out, cannot open deletion confirmation and sends
  no additional DELETE. This tests the completion/presentation crash window.

The instrumentation process remains alive as the fixture server; separate child
PIDs prove actual OS process death. No production account is deleted. These tests
do not cover Android saved-task restoration, force-stop/Doze, production network
delivery, the full NativeScreen owner or physical Pixel/Mac acceptance.
[Harness](ANDROID_TESTING.md#account-process-death-harness) and
[final APK hashes, initial selector failures and evidence](ACCOUNT_RESTORATION.md#process-death-follow-up--2026-10-02)
record the exact scope. No production source or signed release changed.

Ignored evidence: `captures/runtime/account-deletion/` contains JVM XML, both
Android logs, build/alignment logs, device receipts and inspected captures. The
single existing AVD was reused and stopped. No real account was deleted, no
production DELETE was sent, no phone was installed, and no signed release changed.

## Identity-bound deletion checkpoint — 2026-10-24

Both production front doors (NativeAppConnections and injected NativeScreen)
construct nativeAccountDeletionController with NativeDeletionAuthority. The
raw controller constructor remains the local fixture seam, including the older
debug process harness. Old process-kill evidence above does not establish actual
OS process-death behavior for the new account binding. New controlled restoration
cases must be distinguished from a real process kill.

The dialog captures its binding at presentation and keys local confirmation state
by the identity stream. Controller admission checks that binding again inside the
receipt transaction. Request capture joins bootstrap outside token work, then
resolves credentials under one remaining deadline. Credential timeout and
definitive rejection retain their distinct source roles.
Other pre-request auth/network or credential-read failures use the original
generic presentation. The connection message is reserved for a definite local
transport failure of the DELETE itself. Receipt-write failure retains its
specific Android storage presentation; these pre-request failures send no DELETE.
A fresh generation retires queued requests, late completions, result
presentation and local signout authority. Orphaned pending tokens cannot authorize
a deletion confirmation even without a live sign-in lease. Existing same-session
rotation remains
admitted. NativeLocalLogout explicitly retires the version2 receipt.

Whole source hashes are in
captures/recovery-20261023/deletion-identity-source-bindings.json at audit candidate
a5911a1f92f3eeb11c4d7f3e2ef89204718c5009. Twelve whole original files include
currentTokens, coordinator/client/result/error contracts, original tests and the
Settings account section and failure presentation. The prior [original auth
reference run](https://github.com/DocMorphic/cmux-app/actions/runs/40072945627) passed
198 tests, including currentTokens return/missing-refresh/transient-failure cases;
compilation of AccountDeletionClientTests is not claimed as execution of that
suite. Global implemented/reviewed pins remain unchanged.

Full account metadata, raw-token/access-only backend consumers, source team
recovery/eligibility, signout identity-stream capture timing and broader
source/UI/accessibility acceptance remain open. Real provider/helper HTTPS,
physical Pixel/Mac and actual OS death/unlock require separate evidence. No live
account deletion, phone/Mac install or signed-release change is part of this
checkpoint. The user's account/pairing/signing data and signed722 are preserved.

### Checkpoint verification

Final debug app/test APKs build. Full JVM:3,334 total,3,330 passed,four existing SSH
skips,zero failures in449 suites. Thirteen new cases exercise stale confirmation,
queued dispatch, noncooperative late completion, same-user generation replacement,
prior-process verification, different-user refusal, legacy uncertainty, definitive
SDK clearing, storage failure/races, redaction, rotation and credential-error
presentation with no DELETE. Existing controller/HTTP regressions also pass.

Final owned API36 runtime:116/116 in 182.774s. Nine new account/Keystore/local-HTTP/
Compose cases exercise resolved headers, identity replacement during refresh,
definitive refresh rejection and conditional model retirement, late DELETE and
atomic stale sign-out refusal, controlled encrypted prior-process restoration
without resubmission, stale dialog callbacks, actual identity-stream dismissal,
bootstrap joining and orphan pending tokens. The previous107 regressions pass.
Published identity changes and prior-process receipts are controlled fixtures;
this is not actual external service deletion or OS process death.

Crash log empty, no preflight ANR, restored settings/window, emulator exit0.
The confirmation text/actions are legible and the retired dialog is absent in
inspected images. The plain component host overlaps system bars and is not a
NativeScreen layout acceptance result. No original/native UI equivalence is
inferred from these component captures. The full default 60-second capture and
combined 120-second budget are implemented but have no exhaustive hang/sleep/default
expiry acceptance in this checkpoint. Exact response bridge/localization, full
NativeScreen/physical confirmation and broader gates remain open.

Evidence under captures/recovery-20261023: deletion-identity-build-v8.log,
deletion-identity-jvm-v8, deletion-identity-runtime-v2 and aggregate
deletion-identity-checkpoint.json. The first 116-case runtime passed in 193.459s;
final source review then corrected generic pre-request auth-failure presentation.
Final rebuilt source passed JVM and the entire 116-case cohort again. Initial
compile failures and subsequent edge-case additions remain in attempt logs.
Fourteen implementation/test/debug-fixture files, both installed APKs, twelve
whole original files and the unchanged signed722 hash bind the aggregate.
