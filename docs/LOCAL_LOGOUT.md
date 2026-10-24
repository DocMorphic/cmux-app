# Local logout while encrypted storage is unavailable

The original pinned `AuthCoordinator.signOut` first cancels active exchanges,
advances ownership, clears local credentials and presentation, then performs
bounded best-effort push/session teardown. The original offline test requires
local authentication to be gone while server revocation is still hanging.
These originals are at `a5911a1f92f3eeb11c4d7f3e2ef89204718c5009` under
`Packages/Shared/CmuxAuthRuntime` (Coordinator/AuthCoordinator.swift and
Tests/CmuxAuthRuntimeTests/AuthCoordinatorSignOutOfflineTests.swift).

Android previously threw from explicit Sign out when its encrypted credential
read failed. The root kept its signed-in state even though the user requested
logout. Strict conditional retirement was correct for a delayed deletion
callback, but explicit user intent needed a separate path.

## Implemented local behavior

An explicit `NativeAccount.signOut()` commits the non-secret
`auth_logout_pending` preference before attempting an encrypted read. It
advances the document-shared account epoch and clears the authentication model
without invoking the encrypted-cache callback. An unavailable read leaves the
original ciphertext and intent intact; it cannot replace that document with an
empty baseline. If the durable intent cannot be committed, logout reports a
failure instead of promising persistence it cannot guarantee.

Every account read and mutation replays pending logout before exposing an old
account. Replay removes access/refresh tokens, the login incarnation, pending
exchange, drafts and cached user/profile. Saved Mac records remain. The normal
primary-store hooks capture encrypted helper cleanup proof before pruning old
account pins and stage its retirement. New ciphertext and intent removal commit
in the same preference transaction. An empty store acknowledges the intent
without generating a read key. Failed replay encryption retains the original
document and pending intent for a later attempt.

A fresh sign-in must pass that readable transaction before writing new tokens.
The intent is already removed when the new tuple is committed, so a later retry
cannot erase the fresh login. A transaction whose callback signs out reentrantly
cannot re-save its earlier snapshot: account epoch changes reject that snapshot.
Sign-in completion also captures the shared epoch, checks it after quiescence,
at phase start, token writes and user publication, and updates its own capture
after its successful credential write. This fences another account object's
parked exchange or user result without rejecting the winning writer itself.

Conditional `signOut(expectedLogin)` still requires a readable matching login.
Unknown storage cannot prove that a delayed deletion receipt owns the current
account; it does not create an unconditional logout intent. Foreground recovery
and startup/read transactions retry pending logout. The root Sign out action
publishes its local verdict and treats optional teardown failures separately,
so a cleanup failure does not turn a successful durable logout into an error.

Malformed intent metadata is an unavailable verdict, not an inferred request
to erase account data. Ordinary reads/mutators preserve the ciphertext. An
explicit user logout can replace that malformed metadata with an actual intent.
The existing Keystore alias and cipher format are unchanged. The new preference
contains a boolean, never account tokens, identity or pairing material.

## Remaining source and device gates

This describes the local-authority/storage primitive, not full original signout
equivalence. The production process-owned path now adds the captured/ephemeral
server tail and tracked-effect joining described in [signout teardown](SIGN_OUT_TEARDOWN.md).
Live helper/backend and effects outside that registry remain open. Local account
retirement is still synchronous;
metadata commit, encrypted replay and optional UI teardown do not yet have a
proved bound for a provider or filesystem operation that hangs instead of
throwing. Actual OS protected-data/unlock, process death and physical-device
behavior remain acceptance gates. Fixture reconstruction reloads disk-backed
preferences but is not an Android process-kill test.

The native helper ledger test proves retained encrypted cleanup key/proof and
a staged revoke action, not successful live HTTPS revocation. Full SDK/backoff,
account/backend admission, complete user metadata, live Firebase SDK callback/rotation
acceptance after the [durable callback recovery](FCM_CALLBACK_RECOVERY.md) fixture
checkpoint, source adoption, native UI/accessibility and real Pixel/Mac/provider
acceptance remain open alongside every broader PARITY.md/REMAINING_WORK.md gate.
No phone/Mac install, live account or frozen signed release is changed here.

## Checkpoint evidence

Final full JVM:3256 tests,3252 passed,4 existing SSH skips,0 failures across441
suites; `captures/recovery-20261023/local-logout-build-v3.log`. Final instrumentation
build: `local-logout-build-v4.log` in that folder. Owned API36 runtime:62/62 in
141.508s, crash empty, no preflight ANR, settings/window restored, emulator exit0;
`local-logout-runtime-v2` in that folder. This includes the six new encrypted
logout cases, the new primary-helper proof case, four account deletion cases,
root Sign out and the prior50-case auth/restore/terminal/settings/storage cohort.

The setup-only v1 attempt was intentionally stopped before instrumentation to
correct a test-host omission: OAuth delivery must follow claiming the browser
launch. No product fix was inferred, and the product APK was unchanged. The
accepted run uses that corrected fixture. Aggregate hashes, tested-source
bindings and limitations are in `local-logout-checkpoint.json` in that folder.
