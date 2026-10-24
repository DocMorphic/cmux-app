Current snapshot follow-up: [Snapshot capture](SNAPSHOT_CAPTURE.md) records the
2026-10-25 application storage-error projection, one aggregate coherent deadline,
refresh-only ordering and 270/270 owned-emulator checkpoint. The historical
checkpoint below keeps its earlier scope; full parity and physical/UI acceptance
remain open.

Current local-authority follow-up: [Access-only session authority](ACCESS_ONLY_SESSION.md)
records the 2026-10-25 live-verification receipt, restoration cancellation and
initial v2 control credential split. The earlier evidence below retains its
scope; full parity and live-device acceptance remain open.

Current application-layer follow-up: [Application token state](APPLICATION_TOKEN_STATE.md)
records the 2026-10-24 miss classification, presentation clearing, writer fences
and 254/254 owned-emulator checkpoint. The evidence below retains its earlier
scope; full parity and live-device acceptance remain open.

# Ordinary SDK token consumers

The audited source remains `a5911a1f92f3eeb11c4d7f3e2ef89204718c5009`.
Ordinary verified and token-only reads now use the same executed token-selection
policy as pending reads. All refreshes join the existing encrypted-document and
environment coordinator. The normal view still excludes pending credentials.
Owner/epoch checks and fast-read rechecks keep a concurrent account replacement
from returning its predecessor's token. A definitive refresh clear is accepted
only while the same epoch still has empty credentials and receipt. Replacement
immediately after rejection therefore remains typed cancellation rather than
masquerading as that original clear.

There are three separate interfaces:

| Android interface | Outcome |
| --- | --- |
| `sdkAccessToken` | Optional SDK access; a classified refresh miss yields null |
| `resolvedAccessToken` | Optional access; cancellation/session change throws cancellation, timeout throws the typed timeout, transient exchange failure yields null |
| Application `accessToken` | Keeps the application retry error when a refresh token survives an access miss |

SDK user, membership, selected-team and backend-creation preflights use ordinary
SDK access. Coherent/deletion/authenticated-pair captures use resolved access and
classify missing credentials within their own capture guards. Connection callers
retain the application retry bridge so a transient SDK null does not become a
permanent sign-in failure. Full application coordinator absent-token,
unavailable-storage and conditional published-state/cache clearing are still
open; that source layer has its own winning-phase and transition-owner policy.

A restored user now performs an ordinary SDK preflight, then reads the live
access/refresh headers under the captured receipt and epoch. It cannot combine
its preflight access with an earlier refresh snapshot. SDK user preflights
establish presence, while actual HTTP headers come from the later stored pair.
New stored-header reads run on Dispatchers.IO. CurrentUser team-list and
selection-PATCH requests snapshot the store directly, without adding another freshness check that the source does not perform. Exact
invalid-access-token retry still uses conditional forced refresh and preserves
the request's original refresh header, nonce and body.

Token-only SDK reads do not create a refresh owner or a coherent authenticated
pair. A fresh user/account/team/terminal authority model for all access-only
sessions remains separate work. Strict unreadable encrypted storage remains a
typed unknown verdict, preserving its ciphertext and preventing a network
request; the application coordinator's complete source error projection remains
open. The ordinary SDK path relies on its caller's phase; resolved/application
reads retain the existing 60-second token-touching phase. The actual shared
refresh attempt remains bounded at 30 seconds. No hard local-IO bound is claimed.

## Executed source

`scripts/generate-native-token-adapter-reference.py` executes the complete
unchanged original StackAuthClient `accessToken`, `forceRefreshAccessToken` and
`resolvedAccessToken` declarations. Sixty-four cases exercise optional/forced/
resolved adapters, classified failures, optional access and real child-task
cancellation. The Stack results are inert. The Android projection tests compare
all returned access/error outcomes. Actual Android routing, store and HTTP are
checked separately. Regeneration is byte-identical; corpus SHA-256:
`49bb2a462d92d8d4acf3185ba36bccf6e781c644857d52412c224b991f40382c`.

The earlier 110-case complete original resolver reference still proves selection,
getter order and fallback control flow with its stated inert seams. The adapter
reference does not execute the original shared refresh, Keychain, coordinator
state clearing, timers, transport or iOS runtime. Pure wrapper cancellation is
compared; production Android IO/phase/caller cancellation continues to abort
work. No standalone equivalence claim is made for canceled optional SDK IO.
The source host is macOS26.5.2 / CoreFoundation5026.5 with Swift6.3.3.

## Verification

Full JVM: 3,474 total in 464 suites, 3,470 passed and four existing SSH skips,
zero failures/errors. Both debug APKs built. Owned API36: 239/239 in 373.973
seconds, nine new methods and 230 regressions. The 64-case original adapter
corpus runs on ART. Seven actual account/Keystore/HTTP methods cover transient
SDK/typed null, one shared timeout with distinct caller outcomes, owner replacement,
token-only reads, separate restored-user token/user probes, the application retry
bridge and replacement during the post-rejection stored-token read. All seven
records verify encrypted storage and pairing-marker preservation. The stored-header test verifies
three user preflights for five HTTP requests across membership and selection,
including a stored pair whose access disappeared after presence preflight.

The real 60-second phase returned at 60007ms with ciphertext/pairing intact
and no PATCH. The shared two-second phase returned at 2098ms. Crash log empty,
no preflight ANR, settings/window restored, owned emulator exit0. Final build-v7
and runtime-v2 bind exact source, installed APK, asset and original-source hashes.
Evidence: `captures/recovery-20261024/ordinary-token-checkpoint.json`.

Earlier optional/typed token tests now require the source outcomes: null for
transient misses, cancellation for replacement, and a typed timeout for deadline
expiry. Their fallback, request-count, cooldown, ciphertext, owner and late-write
assertions remain. Targeted-v1 had a nullable snapshot compile error, corrected
before focused tests passed. Runtime-v1 passed its eight new methods but one
older cancellation-ignoring selection fixture timed out at250ms. Its fault
controller now follows the production SDK header path; all three injected
retirement cases use1000ms while keeping their timeout, retained actual-work,
newer-selection, request-count and no-late-HTTP assertions. Production phase and
60-second deadline code is unchanged.

Build-v5 hit an intermittent two-second timeout in the generic empty-membership
fixture. Its isolated nine-case run passed without changes; bounded path/method/
body-size/error-class transport diagnostics were added, retaining the budget and
assertions. Full build-v6/v7 passed. The timeout was not reproduced or declared
fixed. Review also added the deterministic rejection/replacement guard and case.
New header reads moved to IO, so the final app APK differs from runtime-v1 and is
independently bound. Earlier failed artifacts remain separate.

Full parity remains active. Application coordinator projection/conditional clear,
all access-only authority, callback/form/backend/reporting and caller-budget
paths, metadata/image/demo/reset/identity/worker and diagnostic/team-error UI,
iOS-runtime proof, actual OS/locked storage/death, physical Pixel/Mac/provider/
helper/Firebase, matched full UI/accessibility and signed delivery remain open.
No real account, pairing, signing material, global source pin or frozen signed722
changes in this checkpoint.
