# Coherent authenticated credential capture

## Cloud credential follow-up — 2026-10-25

[Cloud credential context](CLOUD_CREDENTIAL_CONTEXT.md) adds fresh Cloud pair
capture, optional-team ownership and bootstrap-inclusive deletion credential
capture. Native membership and stored snapshot admission remain separate.
Credential-free snapshot/deletion current-owner checks and whole deletion
confirmation/client behavior under unreadable storage remain open; direct
credential-capture coverage does not close those gates.

The audited iOS coordinator serves a complete stored pair before awaiting
bootstrap. Otherwise it checks the published identity, rejects an active
transition, and captures a coherent pair under one network deadline. It checks
identity/generation again before the final transition guard. Refresh-only
capture has its own contract and never resolves access.

Android previously let strict unavailable-storage failures escape the fallback
as generic server errors, tested transition before identity, and gave each
resolution its own phase without bounding the complete three-attempt capture.
The application adapter now matches these snapshot boundaries.

| Boundary | Behavior |
| --- | --- |
| Stored pair | Requires the published owner and exact document incarnation; bypasses read-only validation and access resolution |
| Missing published identity | Unauthorized before the ordinary transition/storage verdict |
| Authenticated fallback during transition | Retryable network error |
| Unavailable storage | Strict store/SDK reads still throw; the snapshot adapter projects unavailable token reads through availability captured before capture |
| Coherent pair | Up to three refresh brackets around live SDK access resolution, under one 60-second token phase |
| Failed coherent read | Surviving refresh or initially unavailable storage is retryable; available storage without refresh is unauthorized; no presentation clear |
| Final owner and transition | Identity/session generation wins over a simultaneous transition after successful capture |
| Refresh-only | Requires identity and no transition, captures availability and refresh, then checks the owner; same-owner token rotation alone does not retire the snapshot |
| Android pending exchange | Private orphan-recovery credentials remain retryable and cannot expose an unverified pair |

`NativeAuthenticatedOwner` still includes Android's local login receipt and
shared writer epoch. Token rotation does not change that owner. A replacement
account object or pending exchange cannot borrow a cached identity. Strict
encrypted-storage availability is distinct from an absent document. The
snapshot boundary can retain an already published identity during an unavailable
read without granting a successful snapshot or deleting ciphertext.

The aggregate phase surrounds coherent token work after bootstrap. The stored
fast path, bootstrap wait and refresh-only accessor do not acquire a newly
invented token phase. The production snapshot callback now invokes the SDK
resolver directly inside that phase, avoiding a second nested application phase;
the shared SDK exchange keeps its separate attempt deadline and ownership.

Cancellation can retire a snapshot waiter while synchronous actual work remains
tracked. A separate SDK waiter can keep a shared exchange alive and allow it to
persist for the surviving session after the snapshot times out. Canceling the
last SDK waiter retires that attempt and prevents its late response from writing.
No new hard bound to synchronous Android encrypted-storage IO is claimed.

## Original source evidence

`scripts/generate-native-snapshot-reference.py` extracts seven complete unchanged
release declarations from immutable upstream
`a5911a1f92f3eeb11c4d7f3e2ef89204718c5009`:

- `AuthCoordinator+Tokens.swift`: authenticated session and refresh snapshots,
  stored fast path, public and inner coherent pair, and empty-read classifier.
- `AuthCoordinator.swift`: current session-token-transition property.

Both paths are under
`Packages/Shared/CmuxAuthRuntime/Sources/CmuxAuthRuntime/Coordinator/`.
The 471-case corpus covers identity, available/missing/unavailable credentials,
writer/revalidation/capture roles, environment-clear fast-path exclusion,
rotation and mutations at bootstrap/storage/resolution boundaries, including
unavailable reads combined with a newly started transition. The shared
Kotlin test fixture compares outcomes and semantic phase/bootstrap/resolution
counts. Atomic Android document reads can combine separate original SDK reads;
raw read-call counts are not presented as equivalent. The environment-clear
flag is an injected fast-path seam; production project reset/scoping remains an
open gate.

The original phase seam calls the complete original coherent operation once.
Availability, SDK replies, bootstrap and mutations are inert MainActor seams;
the timer, phase admission/finish/high-water machinery and original storage are
not executed by this new reference. Prior original phase and SDK references
retain their separate scope. macOS Foundation execution is not iOS-runtime proof.

## Verification

Full JVM: 3,491 total in 468 suites, 3,487 passed, four existing SSH skips,
zero failures/errors. The final full build-v5 compiles both debug APKs and the
shared test-only reference helper. Owned API36 runtime-v2 passed all 270 methods
in 451.604 seconds: six new methods and 264 regressions. The 471-case original
reference regenerated byte-identically and passes on both JVM and ART.

Virtual-time tests cover one aggregate deadline across three rotations,
bootstrap preceding that budget, late success/failure expiry, cancellation
behind blocked storage, identity/transition ordering and refresh-only rotation.
Real encrypted-store/HTTP tests cover unavailable storage, absent identity,
replacement during refresh, cancellation and retained versus last SDK waiters.
The combined unavailable-storage/transition case uses a real encrypted reader
with an injected transition at availability capture; it preserves the typed
store exception, ciphertext and identity while returning the source network
classification at the application boundary.

The actual capture budgets of 1000ms and 60000ms returned at 1008ms and 60008ms.
A separate SDK waiter retained each shared HTTP exchange after the snapshot
waiter ended, then received the fresh token for the surviving owner. The fixture
extends SDK/HTTP limits to 90000/80000ms so the production capture deadline is
the observed winner; production attempt/transport defaults are unchanged.
Canceling the last snapshot/SDK waiter retires the actual attempt and leaves
ciphertext unchanged. The prior real team deadline returned at 60017ms with no
PATCH and ciphertext/pairing intact; its shared two-second budget returned at
2079ms. Crash log empty, no preflight ANR, settings/window restored, owned
emulator exit 0.

Earlier attempts remain recorded. Focused-v1 caught the AGP9 Kotlin source-set
configuration; the shared fixture belongs to test Kotlin directories only.
Focused-v2 passed 24 methods and the initial 469-case reference. Full-v3/v4
passed the then-current 3491-method JVM suite. Runtime-v1 was deliberately
force-stopped on the owned emulator when review found an extra refresh-only
transition guard; it restored settings/window and exited the emulator normally,
and is not a completed pass. Two combined source cases expanded the reference
to 471, the guard was corrected, and the encrypted-store edge case was added
before the final full build-v5/runtime-v2. Evidence:
`captures/recovery-20261024/snapshot-checkpoint.json`.

Full parity remains active. Full route-family admission/continuation, coherent
Cloud team context and deletion storage-error projection, original phase
admission/high-water reconciliation, authentication-project reset/scoping,
remaining callback/form/backend/reporting, metadata/image/demo/identity/worker/
diagnostic UI, actual OS/protected-storage/death, physical Pixel/Mac/provider/
helper/Firebase, matched complete UI/accessibility and signed delivery remain
open. Real account/pairing/signing data, global pins and frozen signed722 remain
unchanged.
