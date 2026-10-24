# SDK request, error and retry behavior

The Android SDK paths share `NativeSdkTransport`: team reads/selection, email
sign-in, user verification/restoration and captured-session revocation. The
reference is the SDK at audit candidate
`a5911a1f92f3eeb11c4d7f3e2ef89204718c5009`; global implementation/review pins
are unchanged.

## Request lifetime

Requests send only `x-hexclave-*` SDK headers. Authenticated requests snapshot
both access and refresh tokens. One uppercase UUID nonce, body and refresh-token
header survive all retries of that logical request. Authentication retries
replace only the access header. Android identifies its client as
`cmux-android@0.2.0`; the original identifies the Swift SDK as `swift@1.0.0`.
POST/PATCH/PUT receive an empty JSON object when no explicit body is supplied.
SDK clients disable local response caching and automatic OkHttp retries and use
30-second connect/read/write timeouts under the existing phase deadlines.

Only effective status 401 with the exact known error `invalid_access_token`
admits one authentication retry. Bare 401s, body-only codes, other statuses,
different case and empty known-error headers do not. The rejected access value
reaches the production refresh owner; a delayed rejection can reuse a different,
unexpired token already installed by another waiter. Existing owner, generation,
mutation, phase-retirement and cancellation checks still fence all HTTP attempts
and publication. Interactive and orphaned pending sessions use a receipt-owned
refresh seam without admitting unverified credentials as a signed-in account.
A late refresh cannot write after its receipt/phase is replaced or cancelled;
definitive refresh rejection clears only the matching pending session, while
transient failure preserves it and falls through to the original rejected-access
error. The old separate `userId()` HTTP path now uses verified account
restoration.

Socket/response-transfer failures retry GET/HEAD/OPTIONS/PUT/DELETE at most five
times, with 1/2/4/8/16-second delays. POST and PATCH are not replayed for network
failures. A successful access refresh resets the network retry budget. HTTP and
known errors are typed separately from socket IO and cannot enter that retry
loop. Cancellation or a changed owner while waiting prevents another attempt.

## Response contract

Modern response headers take precedence over legacy `x-stack-*` aliases, even
when the modern value is empty or invalid. A valid signed 64-bit actual-status
header governs the response independently of physical HTTP status; invalid or
overflowing values fall back to the physical status. A known-error header,
including an empty one, is an error even on effective 2xx. Body codes alone do
not invent a known error. Otherwise unsuccessful effective statuses become
`http_error`.

Known errors use the source's message/error fallback, diagnostics and typed
error factory. Body details override header diagnostics; the specialized source
error types discard or narrow details where the source does. Email-verification
recovery requires the exact code and the measured Foundation Boolean projection,
rather than treating every nonzero number as true.

SDK response transfers finish before status classification, including 429s.
An interrupted transfer follows network retry rules; a completed effective 429
returns immediately without HTTP retries. The reporting observation contains body
kind, sanitized path and bounded Retry-After metadata; error diagnostics also
retain the source status and request ID. Retry-After retains its
source precision; later coordinators own deliberate backoff. A known code
`RATE_LIMITED` is distinct from an actual 429 and does not invent 429 metadata.
Only that exact code escapes the restored-user error collapse as a rate limit.

The cmux team-creation backend, direct OAuth authorization/token exchange and
refresh exchange keep their separate protocols. Their remaining audit is not
covered by the generic SDK request contract.

## Executed reference and limits

`scripts/generate-native-sdk-transport-reference.py` executes 82 cases using the
complete unchanged original sendRequest/sendWithRetry, Retry-After and response
header declarations, plus whole Errors.swift and RateLimitObservation.swift.
The session, token store, refresh result and sleep clock are inert seams. The
reference records logical nonces, token snapshots, rejected access, backoff,
request headers/bodies and error details. Independent regeneration is identical.

The JVM and Android corpus fixture preserves raw response header values to
match the inert Foundation HTTPURLResponse; actual HTTP carrier behavior is
exercised separately with loopback servers. This does not establish URLSession
DNS/redirect/socket behavior, real token-refresh coalescing in the original SDK,
observer routing, complete getUser/adaptor error projections or token-freshness
preflight, or iOS-runtime equivalence. The measured
Foundation host is macOS 26.5.2 / CoreFoundation 5026.5. No iOS Simulator SDK is
installed.

The source comment's assertion that legacy request aliases trigger provider
429s was not reproduced by the read-only public-header probe: both prefixes
returned the same unauthenticated known error. Header alignment follows the
verified source request contract; it is not evidence of a live authentication
failure or provider recovery.

## Verification checkpoint

Full JVM: 3,467 total in 460 suites, 3,463 passed, four existing SSH skips,
zero failures/errors. Both debug APKs build. Owned API 36: 215/215 in 363.715
seconds, 14 new methods plus 201 prior regressions. The same 82-case original
transport reference executes on ART; the 368-case byte corpus remains covered.
Real isolated account/Keystore/loopback tests include interactive and orphaned
pending recovery, replacement receipts, failed refresh ownership, interrupted
429 transfers and cancellation. The real 60-second phase returned at 60,024ms
with ciphertext/pairing intact and no selection PATCH. Crash log empty, no
preflight ANR, settings/window restored, owned emulator exit0.

The receipt binds 87 local inputs, 44 original files, exact installed debug APKs
and the transport fixture asset hash. The original reference regenerates
identically. Earlier failed builds/tests and runtime-v1's two recovery failures
are retained separately; final evidence is JVM/build-v9 and runtime-v2. The
orphan test's historical `refreshedExpiredAccessBeforePublication` record field
names a server-rejected opaque fixture token; it does not prove timed-expiration
preflight.

Evidence: `captures/recovery-20261023/sdk-transport-checkpoint.json`.
Next audit: `captures/recovery-20261023/sdk-transport-next-user-projections.json`.

Full app parity remains active. Complete getUser/adaptor error/freshness
projection, teams/restore refresh-failure projection, direct OAuth/form/backend
contracts, Retry-After edge parsing, observer/callerReports and consent-aware
reporting, remaining token/identity/worker/reset consumers, image/demo UI,
iOS-runtime equivalence, actual locked storage/OS/death, physical Pixel/Mac/
provider/helper/Firebase, matched full UI/accessibility and signed delivery
remain open. These tests use disposable fixtures. Real account, pairing and
signing data, global source pins and frozen signed build 722 are unchanged.
