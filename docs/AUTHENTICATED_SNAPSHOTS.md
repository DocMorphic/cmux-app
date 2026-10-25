Current snapshot follow-up: [Snapshot capture](SNAPSHOT_CAPTURE.md) records the
2026-10-25 application storage-error projection, one aggregate coherent deadline,
refresh-only ordering and 270/270 owned-emulator checkpoint. The historical
checkpoint below keeps its earlier scope; full parity and physical/UI acceptance
remain open.

## Cloud credential follow-up — 2026-10-25

[Cloud credential context](CLOUD_CREDENTIAL_CONTEXT.md) adds fresh Cloud pair
capture, optional-team ownership and bootstrap-inclusive deletion credential
capture. Native membership and stored snapshot admission remain separate.
Credential-free snapshot/deletion current-owner checks and whole deletion
confirmation/client behavior under unreadable storage remain open; direct
credential-capture coverage does not close those gates.

The initial checkpoint below retained a typed storage failure at the snapshot
boundary. That application projection is superseded by the linked follow-up;
the encrypted store and SDK boundaries still retain strict unavailable reads.

# Authenticated account and credential snapshots

The original AuthCoordinator captures a published user/session generation with a
coherent access/refresh pair. Android previously called accessToken first and
then compared stored login/token strings. That could make a locally available
pair wait for network refresh and did not bind the credentials to a published
account incarnation.

## Source contracts and implementation

NativeAuthenticatedSnapshots serves a complete stored pair without awaiting
bootstrap or resolving access over the network. A stale access token remains
usable with its matching refresh token for backend authentication. The fast path
requires a primed identity and declines during interactive sign-in and signout
credential capture. Read-only launch/foreground revalidation does not block it.

Without a complete pair, the fallback joins bootstrap, declines an active token
transition, and brackets live access resolution with refresh reads. Up to three
crossing rotations are retried. A surviving refresh with no access is retryable;
missing identity/refresh or account replacement is unauthorized. Unavailable
storage remains a typed storage failure and never clears the encrypted document.

NativeAuthenticatedOwner binds the published NativeAuthIdentity, stored login and
shared document auth epoch. The latter fences separate account objects after an
interactive writer replaces their saved session. Both token-bearing snapshots
and storage observations redact descriptions. Current checks compare identity
and epoch rather than exact token values, allowing ordinary rotation within the
same session. Revalidation/interactive writers still deny current-session checks,
as in TokenProviding's original implementation.

The separate authenticatedRefreshTokenSnapshot contract requires no access
resolution. The iOS mobile cookie broker still requires a complete pair; refresh
only browser authentication has a distinct original contract and is not silently
substituted into that wire format.

## Shared writer ownership

The document's NativeAuthWork tracks sign-in leases before quiescence, provider
preflight and token exchange. Unverified user fetch remains transition-owned.
Publication retires the current-generation writer while final team hooks retain
the fast-path exclusion until flow completion. Signout capture blocks snapshots
at its synchronous start. Lease reads use atomic counters/volatile state, so they
do not acquire the auth-work monitor under the credential monitor. Cleanup is
idempotent and covers cancellation, deadlines and legacy orphan completion.
Orphaned pending credentials stay retryable without exposing their unverified
pair. Legacy sessions without a login receipt bind the receipt generated during
restore to the published snapshot owner without discarding pairing data.

## Consumer adoption

Cloud credentials and VPN setup capture the authenticated account and full pair,
then compare it with their admitted user/login/team owner. Mobile web handoff
adapts that same snapshot and checks its owner after the response before accepting
cookies. It follows the original broker by allowing the snapshot's saved-pair
fast path to start the request during revalidation. Cookie acceptance still
requires the session to be current. VPN retirement retains a narrowly scoped
captured pair for revocation after its live owner retires.

## Remaining gates

This is snapshot foundation and Cloud/mobile-web adoption, not complete backend
admission. Account deletion still needs coherent resolved-token capture and
identity pinned at confirmation, including durable receipt/completion handling.
Legacy raw-token isSignedIn, access-only Iroh/account flows, full team recovery
and user eligibility, signout identity-stream timing, complete metadata and other
identity consumers remain open. Refresh-only capture has no newly added Android
browser consumer. Physical Pixel/Mac/provider/helper HTTPS, actual OS process
death/unlock, matched native UI and TalkBack acceptance remain open. Existing
source pins, account/pairing/signing data and frozen signed722 remain unchanged.

Whole original source hashes are in
captures/recovery-20261023/authenticated-snapshot-source-bindings.json at audit
candidate a5911a1f92f3eeb11c4d7f3e2ef89204718c5009. The existing [original auth
reference run](https://github.com/DocMorphic/cmux-app/actions/runs/40072945627)
passed 198 tests, including the original snapshot
revalidation/token-pair suites. Original CI is library evidence, not device
acceptance or proof that every Android consumer uses this contract.

## Checkpoint verification

Final debug app and instrumentation APKs build. Full JVM:3,321 total,3,317 passed,
four existing SSH skips, zero failures across448 suites. Seventeen new cases
exercise fast/fallback capture, bootstrap, transitions, rotation, identity/epoch
fences, cancellation, refresh-only capture, redaction and lease lifetime.

Owned API36 runtime:107/107 in189.023s. Eight new real-account/encrypted-Keystore/
local-HTTP cases cover a complete stale pair during blocked launch refresh,
refresh-only capture without minting, shared provider-preflight/unverified-user
windows and replacement fencing, late same-user-generation handoff response,
signout capture before local clear, rotation continuity, unavailable storage,
legacy login-receipt restoration and orphan-pending preservation. The stored
pair returned in10ms with zero extra HTTP requests while refresh was blocked.
Browser/provider responses are controlled fixtures. The previous99 regressions
also passed; no actual external provider or physical-device result is inferred.

Crash log empty, no preflight ANR, settings/window restored, emulator exit0.
Evidence: authenticated-snapshot-build-v4.log, authenticated-snapshot-jvm-v4,
authenticated-snapshot-runtime-v1 and aggregate authenticated-snapshot-checkpoint.json
under captures/recovery-20261023. Nine implementation/test files, both installed
APKs and nine whole original files are bound in the aggregate. V1 rejected helper
return-type inference at compile time; V2/V3 built and passed JVM checks, then
legacy/pending edge cases were added before the final source-bound build/run.

The later [identity-bound deletion checkpoint](ACCOUNT_DELETION.md#identity-bound-deletion-checkpoint--2026-10-24)
now pins confirmation, dispatch, resolved credentials, receipt results and local
signout to an account incarnation. This extends consumer adoption; the remaining
backend, raw-token, metadata, OS/device and UI gates above remain open.
