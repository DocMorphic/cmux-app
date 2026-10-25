# Credential-free current-session checks

Current-session validation now compares the published account, session generation,
shared writer epoch and local incarnation without reading encrypted credentials.
Storage availability is a credential-capture verdict, not evidence that a
previously published account was replaced. Snapshot current checks still reject
active transitions; deletion dispatch keeps its writer preflight. Credentials and
receipt mutations retain strict storage reads and exact receipt checks.

An initial orphaned pending exchange cannot prime published local authority from
its cached user. Live exchanges fence earlier owners through the shared epoch
before publication; a completed same-user sign-in advances model generation.
Same-user foreground revalidation keeps it. Synchronous sign-out capture still
blocks dispatch, and conditional local sign-out verifies the owner inside its
credential transaction. No unavailable read becomes an empty mutation baseline.

## Deletion outcomes while storage is unavailable

Both production controller entry points now use the strict document reader.
Unknown storage can no longer look like an absent receipt during reconciliation.
The controller retains its running state or received result only while its bound
published account remains current. A replacement account or same-user new
generation discards it, including while the document cannot be read.

A result actually received by this process remains visible if saving it fails.
When storage recovers, the same in-memory result can be acknowledged against its
unchanged processing receipt. A fresh controller with only a saved PROCESSING
receipt restores UNKNOWN, never success and never an automatic resend. This is
distinct from a controlled reconstruction or an actual OS process kill.

A received success can show the existing local sign-out retry while encrypted
cleanup is unavailable; that retry only performs local cleanup. It sends no new
DELETE and cannot sign out a replacement. A pre-request credential failure keeps
the original generic failure role when storage is unavailable at capture start.
Availability is captured once: a later unavailable read can instead retain the
original unauthorized classification. These two timings have separate fixtures. If the initial encrypted receipt cannot be
written, Android still sends nothing and shows its explicit storage failure.

The original iOS Settings flow does not have Android's encrypted receipt write
prerequisite. This remains an Android behavior difference to reconcile before
claiming complete account-deletion parity; the new current-owner checks and
received-result retention do not by themselves close that gate.

## Original comparison

Immutable comparison pin: `a5911a1f92f3eeb11c4d7f3e2ef89204718c5009`.
`scripts/generate-native-current-owner-reference.py` executes two complete,
unchanged release declarations from
`Packages/Shared/CmuxAuthRuntime/Sources/CmuxAuthRuntime/Coordinator/`:

- `TokenProviding.swift::isAuthenticatedSessionCurrent`.
- `AuthCoordinator.swift::sessionTokenTransitionIsActive`.

The 512 cases vary authentication, validation, restoration, sign-out capture,
current/stale sign-in generation and sign-out epoch, current session generation,
and nil/empty/same/different current users. The captured snapshot uses a nonempty
production account identity. The Android evaluator rejects every attempted
storage, bootstrap or access-token read during a current check. Independent
regeneration is byte-identical. These methods run against inert model/flow values;
they do not execute actual original phase admission, SDK, Keychain, timers or UI.

The complete coordinator, token/current-token methods, deletion coordinator and
client, Settings section and failure kinds were reviewed separately. A source
sign-in flow captures generation before waiting and advances it on successful
publication; sign-out advances it before credential teardown. Those distinctions
must remain visible in the larger phase/admission reconciliation. The pure
reference does not prove all production Android transition wiring is equivalent,
including retired flows and post-publication hooks.

The next clock audit has a concrete target: the coordinator defaults to
`ContinuousClock`, while `NativeAuthWork` currently defaults to `System.nanoTime`.
Apple documents that [ContinuousClock advances during system sleep](https://developer.apple.com/documentation/swift/continuousclock);
Android documents that [nanoTime uses uptime while elapsedRealtime includes deep sleep](https://developer.android.com/reference/android/os/SystemClock).
Their sleep behavior differs; no clock correction or real sleep acceptance is
included in this checkpoint.

## Verification

Final full JVM/build-v2: 3,510 total in 471 suites, 3,506 passed, four existing SSH
skips, zero failures/errors; both debug APKs build. The targeted cohort passes 45
tests. Six new owned-emulator methods cover the original reference on ART,
zero-key-read ownership, initial storage failure, unavailable capture after a
saved receipt, availability changing during refresh, received success/local cleanup
retry and same-user replacement.
The final full owned API36 run passes 291/291 in 488.963 seconds, including six
new methods and 285 regressions. The focused cohort passes 6/6 in 5.051 seconds.
Both runs use identical source inputs and byte-identical installed APKs, have
empty crash logs and no preflight ANR, restore settings/window and observe owned
emulator exit 0. Final full-v2 JVM XML is unchanged after test-only build-v3;
that build preserves the main APK bytes. The 512-case reference runs on JVM
and ART. These checks cover component semantics and do not prove complete native
Settings UI, actual OS sleep or process-death acceptance.

Evidence: `captures/recovery-20261025/current-owner-checkpoint.json`.

The first focused runtime completed five
methods with four passes and one assertion failure. That fixture expected generic
failure after storage became unavailable during an already admitted refresh. The
original captures availability once, so its later empty read is unauthorized.
The expectation now preserves that source classification, and a separate case
fails the first post-receipt read to cover initially unavailable capture. No
production classifier, deadline or ownership assertion was loosened. The first
reference-generation attempt also stopped at an incorrect expected matrix count;
the Cartesian matrix contains 512 cases, not 1,024.

Disposable account-encrypted stores, controlled key failures and loopback HTTP
are used throughout. No production endpoint, real deletion, physical phone,
Mac/helper/pairing or signing data is touched. Existing stored-pair and native
membership contracts remain separate. The signed722 artifact and global pins
are unchanged.

Full parity remains active: original nested phase admission/finish/high-water,
real locked storage/OS sleep/process death, deletion receipt/UI differences,
authentication-project reset/scoping, remaining backend/schema/metadata/form/
callback/identity/worker/diagnostic behavior, matched full UI/accessibility and
physical Pixel/Mac/provider/helper/Firebase/Cloud/VPN acceptance and signed
delivery remain open.
