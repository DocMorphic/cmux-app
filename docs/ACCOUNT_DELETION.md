# Account deletion

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

The request uses a current access/refresh pair from one captured login, refreshing
the access token when needed. Account
changes during credential acquisition or the HTTP exchange cannot publish for or
sign out the replacement login. The client requires HTTPS outside loopback tests,
disables redirects and automatic connection retries, bounds responses to 64 KiB,
and uses a 60-second HTTP deadline inside a 65-second overall operation. A 401
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
account-encrypted receipt containing only a random operation ID, login incarnation,
version and status. If this write fails, no request starts. Results update only
the same operation and login. Unknown completion survives process restart;
`PROCESSING` restored without its live owner becomes `UNKNOWN`, not a resend.
If result persistence fails after sending, the retained processing receipt also
restores as unknown. Confirmed completion and incomplete-cleanup results are
retained for the UI to handle after restart.

Sign-out compares the captured login inside the credential transaction. The email
challenge lock surrounds that transaction, using the same lock order as sign-in
publication. An in-flight email-code exchange cannot restore credentials between
session retirement and challenge invalidation; a rejected stale sign-out leaves
the replacement account’s challenge intact. Credentials retire before stopping
notification/connection owners and clearing drafts.
An old result cannot sign out a later login. Account replacement/sign-out prunes
old deletion receipts. A local acknowledgement/sign-out storage failure offers a
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
