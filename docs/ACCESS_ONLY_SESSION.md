Current snapshot follow-up: [Snapshot capture](SNAPSHOT_CAPTURE.md) records the
2026-10-25 application storage-error projection, one aggregate coherent deadline,
refresh-only ordering and 270/270 owned-emulator checkpoint. The historical
checkpoint below keeps its earlier scope; full parity and physical/UI acceptance
remain open.

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

# Access-only account and local session authority

At audited upstream `a5911a1f92f3eeb11c4d7f3e2ef89204718c5009`, the launch
probe accepts SDK access or refresh credentials. Successful live user validation
publishes the user and runs membership refresh. The published identity and
verified membership determine team scope. APIs that explicitly capture both
tokens still require a refresh token.

Android previously restored an access-only user but left its local session
incarnation unavailable. Account/team and foreground guards then reported signed
out. A bare SDK token read still cannot create that local incarnation. A
successful live user request can now establish it through an encrypted write.

| Boundary | Contract |
| --- | --- |
| Bare SDK access/resolved reads | Preserve raw access-only state; do not invent a receipt or refresh owner |
| Successful live user validation | Create or retain a local receipt and bind its verified user cache; publish the model only if the validation remains current |
| Local session ownership | Exact receipt, no pending exchange, and either a surviving refresh token or access with a matching verified-user cache |
| Account/team | Live identity restoration and membership verification remain required; cached rows alone cannot admit connections |
| Local foreground persistence | Workspace tabs, task drafts, pairing/route/visibility/history/ticket mutations use the local session owner plus their existing account/team/record guards |
| Initial v2 control access | Production callback uses an authenticated pair, with scope checked before and after; forced control access uses the public force accessor |
| Full credential snapshots | Session/refresh/browser/push/deletion contracts still require their original refresh credentials |
| Provider queues and maintenance | Their explicit refresh conditions remain; complete access-only background lifecycle acceptance remains open |

The encrypted verification checks caller cancellation, auth epoch, model
generation, current sign-in and sign-out capture ownership. Its final commit also
checks the document revision and serializes against writer registration.
Encryption does not hold the auth-work monitor or writer-registration gate. A
blocked write stays tracked as actual validation work after its waiter ends;
cancellation or replacement cannot authorize its late commit. Restoration has
an independently tracked lifetime, so a canceled caller can leave while the
owning restoration finishes retiring behind the shared storage monitor. Coalesced continuation waiters retain the original noncancelable wait for the owning run. Production
restoration runs on IO; the standalone model can inject its dispatcher. Actual
work and its revalidation ownership remain until that retirement. This adds no
hard bound to synchronous Android storage IO. After access-only verification
has committed the exact user cache, model publication binds that local owner
without repeating the encrypted cache write outside the phase.

The cache adapter retains interactive completion's existing staging behavior:
it may prepare the profile cache before removing a refresh-backed pending
receipt. That internal staging never makes pending credentials available to
ordinary local or refresh-authority readers.

`NativeSessionOwnership.kt` describes a local incarnation, not a refresh token or
proof of a remote route. Clearing access-only cached presentation removes that
local authority even when a token remains. Refresh-backed sessions retain the
existing legacy receipt migration. A reconstructed access-only account can show
cached presentation, but it must revalidate the live user and membership before
new team authority is available. An SDK user with an empty id retains the source
model outcome and cannot create an authenticated account identity.

## Source mapping

The immutable source files used for this comparison are:

- `Coordinator/AuthCoordinator+SessionPriming.swift`: `checkExistingSessionBody`
  accepts access or refresh; `validateCachedSession` applies a successful user.
- `Coordinator/AuthCoordinator.swift`: `applySignedInUser` publishes identity,
  saves user presentation, refreshes teams and tolerates membership failure.
- `Coordinator/AuthCoordinator+TeamScopes.swift`: `authenticatedTeamScope` uses
  the published identity, current membership generation and a valid member id.
- `Coordinator/AuthCoordinator+Tokens.swift`: authenticated session/refresh
  snapshots retain their required credentials and transition checks.
- `ios/cmuxPackage/Sources/cmuxFeature/MobileIrxRuntimeComposition+Lifecycle.swift`:
  initial v2 control access uses an authenticated session snapshot; forced
  access uses the public force accessor. Android now uses that accessor split and checks current scope before and after
  each credential read. Access-only initial control access is denied; a stored
  authenticated pair can use the existing fast path. Real control-server and
  route-family acceptance remain required.

Coordinator paths above are under
`Packages/Shared/CmuxAuthRuntime/Sources/CmuxAuthRuntime/`. The prior application
reference executes its stated release declarations, not this entire restore,
backend or UI lifecycle. This checkpoint makes no new whole-original execution
or iOS-runtime equivalence claim.

## Verification

Full JVM: 3,484 total in 467 suites, 3,480 passed, four existing SSH skips,
zero failures/errors. The owned API36 full run completed 264 methods in 393.791
seconds: 262 passed and two fixtures failed. One reused a workspace key across
two receipt rows and mistook a no-op save for a write. The other required eight
HTTP requests even though an immediate ineligible primary reply could cancel its
optional peer before HTTP. Distinct keys and a two-request reply barrier correct
these fixtures without relaxing counts, budgets or authority assertions.

The final focused run passed all 17 methods in both affected classes in 14.46
seconds. The remaining 247 methods passed in the full run; the main APK is
byte-identical and the final build-v10 retains full-v8 JVM XML byte-for-byte.
Across the two source-bound cohorts, all 264 unique methods pass. This is not a
single full green 264-method run. Both runs have empty crash logs, no preflight
ANR, restored settings/window and owned emulator exit 0. The full run's real
60-second phase completed at 60017ms with ciphertext and pairing preserved and
no PATCH; its shared two-second phase completed at 2097ms.

The ten new runtime cases cover absent/existing receipts, cold reconstruction,
failed/malformed/ineligible users, pending exclusion, real user-request
replacement, canceled encryption, writer ownership, reentrant same-epoch
replacement, production v2 control credential routing, and a mounted team
selector with actual local HTTP/PATCH. The inspected selector image has both
rows, the selected check and action controls visible without clipping. Terminal
input admission and logout retirement are local checks, not terminal wire
input/output or complete screen/accessibility proof.

Earlier build/runtime attempts remain in the checkpoint. They include the
source-coalesced waiter regression that corrected the detach boundary, the
cancellation-at-admission edge case, and an unreproduced two-second JVM HTTP
fixture timeout. Its budget/assertions remain unchanged; bounded diagnostics
were added. Evidence: `captures/recovery-20261024/access-only-checkpoint.json`.

Full parity remains active. Route-family initial/continued credential behavior,
full caller/phase admission and high-water reconciliation, authentication-project
reset/scoping, remaining callbacks/forms/backend/reporting and metadata/image/
demo/identity/worker/diagnostic UI remain open. Actual OS/protected-storage/death,
real Pixel/Mac/provider/helper/Firebase, matched full UI/accessibility and signed
delivery acceptance remain required. This work uses disposable encrypted stores,
mock HTTP and the owned emulator; it does not alter the user's installed app,
Mac pairing, real account or signing data, global pins or frozen signed722.
