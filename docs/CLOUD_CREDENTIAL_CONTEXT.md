# Cloud credential context and optional team routing

## Current-owner follow-up — 2026-10-25

[Current-session owners](CURRENT_SESSION_OWNERS.md) records credential-free
snapshot/deletion owner checks and retained deletion outcomes during unavailable
storage. This supersedes the older current-owner open item below. Complete
phase/transition wiring and Android's extra deletion-receipt admission behavior
remain open; this is not complete account-deletion or UI/device parity.

Cloud requests use the original coordinator's live coherent pair and optional
selected team. Initial v2 control and mobile web snapshots retain their separate
stored-pair fast path. A Cloud request must never substitute that fast path for
fresh SDK resolution or combine credentials with a team selected during capture.

## Contracts

| Boundary | Behavior |
| --- | --- |
| Coherent pair | Capture storage availability once; bracket live SDK access with refresh reads; retry at most three crossing rotations; never clear presentation |
| Cloud context | Pin session, shared writer epoch, routing generation and optional resolved team around the single token phase |
| Cloud credentials | Unauthorized becomes nil/CloudNotSignedIn; transient failures and unavailable storage remain retryable |
| Native Mac membership | Remains a distinct verified NativeTeamScope; Cloud scope cannot supply a native membership or continuation proof |
| Cloud lifetime | Published account and selected optional team own the controller; a native proof publication fences an in-flight context without closing an otherwise unchanged backend owner |
| No selected team | Catalog, plan, workspace preferences, drafts and VPN ownership support the personal/nil-team route |
| Cached rows | Do not supply fresh membership for routing fallback; remembered selection remains presentation/routing preference, subject to backend authorization |
| Empty successful SDK user id | Cloud retains the original backend-read behavior while authenticated native snapshots and Mac membership remain unavailable |
| Access-only cached presentation without receipt | An in-memory Cloud presentation owner can show the missing-refresh outcome; it writes no task receipt or refresh authority |
| Legacy refresh receipt | Bind the receipt created by either restore or an earlier local reader to the retained cached account, with epoch/generation, user-cache and writer fences |
| Restore/foreground | Cloud controller can exist during cached restoration; list/tunnel activation waits for authenticated restoration to finish; ordinary revalidation does not discard the owner |
| VPN binding | Does not resolve SDK credentials, a device identity or enroll a peer |
| VPN enrollment | Capture credentials once before device identity; map credential errors to nil; nil cannot send enrollment |
| VPN retirement | Retained credentials can only revoke the captured browser peer; replacement-account credentials are never substituted |

`NativeCoherentTokens.kt` contains the pair/context policy and the separate
`currentTokens` operation. The latter includes its read-only bootstrap wait in
one absolute budget, then captures under token ownership. Bootstrap work stays
tracked until actual retirement but is excluded from sign-in token joining and
cannot block a new token reader after timeout. Both stages use the work clock.
The token phase receives the original absolute deadline, including its late
success/failure check; it does not restart the budget after bootstrap.

Account-deletion credential capture uses this bootstrap-inclusive operation while
retaining its identity, local login, shared epoch, pending exclusion and late-write
guards. Initially unavailable storage at that capture boundary projects to a
retryable error without treating an unreadable document as an empty store.
The surrounding confirmation/client still has storage-dependent owner checks;
the whole deletion flow under unreadable storage is an open reconciliation.
Strict SDK/store adapters remain strict. This is distinct from Cloud context,
whose original coherent phase does not add a bootstrap wait.

The Cloud owner registry tracks two lifetimes: account/optional-team changes
retire the backend owner, while native team-proof changes advance the context
routing guard. The latter must not unnecessarily close Cloud connections or an
open VPN permission attempt. Published model generations are observable even
when an empty successful user keeps the native identity stream nil.

Existing journal, draft, known-daemon, last-tab and destination encodings for
nonempty teams retain their stored values. Optional team owners use the same
existing nullable Cloud formats. This change does not grant cached membership
authority to Iroh, legacy Mac routes or native terminal input.

## Original source evidence

The immutable comparison pin is
`a5911a1f92f3eeb11c4d7f3e2ef89204718c5009`.
`scripts/generate-native-cloud-context-reference.py` executes nine complete
unchanged release declarations across 323 cases:

- `AuthCoordinator+Tokens.swift`: public/inner coherent pair, Cloud context and
  empty-read classifier.
- `AuthCoordinator+CurrentTokens.swift`: public capture and inner Cloud-token
  capture, including bootstrap deadline and writer/session guards.
- `AuthCoordinator.swift`: current token-transition property.
- `AuthCoordinator+TokenTouchingPhaseTimeout.swift`: both token-phase overloads.

Those files live under
`Packages/Shared/CmuxAuthRuntime/Sources/CmuxAuthRuntime/Coordinator/`.
The reference has inert SDK/storage/bootstrap/clock/finish seams and uses real
child-task cancellation. It checks the original inner late-deadline logic, not
the original timer race, phase admission/high-water reconciliation or Keychain.
It does not execute UIKit or establish iOS-runtime equivalence.

Production composition is reviewed separately in `MobileCloudComposition.swift`
and `CMUXMobileRootScene.swift` under
`ios/cmuxPackage/Sources/cmuxFeature/`, and `CloudAPITokenSource.swift`,
`CloudVMService.swift` and `CloudSystemVPNController.swift` under
`Packages/iOS/CmuxMobileCloud/Sources/CmuxMobileCloud/`. The Root scene uses a
published user and optional resolved team for Cloud visibility, defers startup
reads while restoring, and retains the foreground tunnel only for an account
that owns machines. Those contracts are not replaced by the native Mac team
scope. The service and VPN closures have different nil/error policies.

## Verification

Final full JVM: 3,506 total in 470 suites, 3,502 passed, four existing SSH skips,
zero failures/errors. Full owned API36 runtime: 285/285 in 501.136 seconds,
including 15 new methods and 270 regressions. The affected cohort separately
passes 15/15 in 50.874 seconds using byte-identical app/test APKs and source
inputs. The 323-case reference executes on both JVM and ART, and independent
regeneration is byte-identical. Final full-v10 XML remains unchanged.

The integrated runtime cohort exercises production Cloud account/HTTP wiring,
personal catalog and plan UI, retry, both legacy receipt reader orders,
unavailable storage, account/team retirement and browser-only retained revocation
using disposable encrypted stores and controlled local HTTP. The 1,000ms capture
returned at 1,008ms with another SDK waiter retained, no backend request and the
account scope preserved. The existing default 60-second team phase returned at
60,007ms with ciphertext/pairing intact and no selection PATCH. Production
transport/SDK defaults are unchanged.

Both runs have empty crash logs, no preflight ANR, restored settings/window and
owned emulator exit 0. Loaded-empty Cloud images were visually inspected; final
full-run pixels are byte-identical to the inspected focused images. Manual retry
and personal plan access have semantic UI checks; the captured retry image is
the final loaded state. Neither image establishes matched iOS UI equivalence.
The local checkpoint is `captures/recovery-20261025/cloud-context-checkpoint.json`.

Earlier runtime failures and their repairs remain recorded. Three UI fixtures
started MockWebServer lazily on the main thread; request origins are now built
before entering the UI thread. The pure source fixture needed its disposable
encrypted marker. The legacy failure showed that another local reader may have
created the receipt before restore; the binding covers both reader orders and
requires the original cached account epoch. No deadline or authority assertion
was relaxed. A later existing two-second membership fixture failure is retained
with bounded request/status/exception diagnostics; its cause is not assumed and
its deadline/assertions remain unchanged.

Full parity remains active. Credential-free snapshot/deletion current-owner
checks still require reconciliation with the original published-model identity
checks, including the whole deletion confirmation/client under unreadable storage.
Whole original phase admission/finish/high-water and
clock behavior across real OS sleep, protected-storage/lock and process death,
authentication-project reset/scoping, remaining backend/metadata/callback/form/
identity/worker/diagnostic behavior, complete Cloud schema/error/localization and
matched full UI/accessibility remain open. Actual Pixel/Mac/native terminal,
provider/helper/Firebase, Cloud/VPN/platform acceptance and signed delivery are
still required. Controlled browser-peer responses and fake device identity do
not prove real VPN installation, real provisioning or terminal wire I/O. Existing
real account/pairing/signing data, global pins and frozen signed722 are preserved.
