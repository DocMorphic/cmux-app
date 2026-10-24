# Access-token expiry and refresh decisions

The pinned original SDK treats token-only sessions differently from sessions
that can refresh. A token-only read may use a token until actual expiry. A
normal read with a refresh token uses a jittered 60–120 second lead, capped at
half the token lifetime and floored at 20 seconds. A proactive refresh failure
may fall back to the observed access token while it remains valid. A forced
refresh after server rejection never returns that rejected token.

The old Android reader returned the stored access token when no refresh token
existed, even when it was expired or a forced refresh had been requested. It
also used a fixed 60-second freshness predicate and threw on every proactive
refresh failure, including when the observed token remained valid.

## Production behavior

NativeAccount.accessToken now reads credentials strictly: unavailable encrypted
storage raises the existing sanitized unknown-state error instead of becoming
an absent session. Fast reads recheck the captured access, refresh, login,
pending-verification state and shared auth epoch before returning. Refresh writes
and rejection cleanup are conditional on the same login, refresh and epoch;
transient fallback additionally requires the observed access token to remain
stored. Failed crypto reads never supply fallback authority.

The reader applies the original token-only expiry and proactive freshness
predicates, including numeric/absent expiry and short token lifetimes. A forced
read without a refresh token declines the stored access token. Proactive
transport/server failures preserve a still-valid observed token; forced refresh
and actual expiry cannot use that fallback. HTTP 400/401 are definitive refresh
rejections; other failures preserve credentials. The existing per-account mutex
still serializes that object's reads.

Refresh now uses the account's existing injected NativeOAuthClient and its
cancellation-aware OkHttp request. It no longer calls a separate hard-coded
HttpURLConnection from NativeAccount. OAuth refresh follows raw HTTP status
and the original x-hexclave project/publishable/access headers. JSON API status
and known-error override headers do not convert an OAuth refresh response into
a different HTTP verdict. Cancellation checks and conditional writes prevent a
late mint or rejection from replacing or clearing a newer account.

## Original source evidence

`scripts/generate-native-token-validity-reference.py` reads whole APIClient.swift
from original commit `a5911a1f92f3eeb11c4d7f3e2ef89204718c5009` via Git. It executes
the unchanged JWTPayload/decode/expiry/freshness declaration block with Swift and
emits 16 rows into `app/src/test/resources/native-token-validity-reference.json`.
Whole-file and declaration hashes are embedded. Android JVM tests consume those
actual outputs for expiry and both 60/120 second leads; additional millisecond
boundary cases test actual expiry separately from proactive lead time.

## Remaining requirements

This ports token-reader decisions and refresh transport, not the entire SDK or
auth coordinator. The source's shared per-store refresh attempt/waiter registry,
30-second shared elapsed deadline, cancellation ownership, rate-limit cooldown
and reporting still require reconciliation. The current per-account mutex does
not establish coalescing across NativeAccount objects or sharing one failed
exchange across forced callers. Source backend admission/session snapshots,
access-only account/team/terminal flows, complete metadata and real post-sign-in
effects remain open. A token-reader result alone is not membership authority.

The raw-token isSignedIn/taskSession consumers still require the separate
coordinator/admission audit. This checkpoint does not change those APIs into
verified account proof. Native UI, full source adoption, actual OS process death/
unlock, physical Pixel/Mac/provider and all broader PARITY.md/REMAINING_WORK.md
gates remain open. Fixture HTTP is not a live account or Firebase test.

No phone/Mac install, live account, signing material, global source pin or frozen
signed release is changed by this development checkpoint.

## Checkpoint verification

The final debug app and instrumentation APKs build. Full JVM: 3,276 tests in
444 suites, 3,272 passed, four existing SSH skips and zero failures. Source
reference comparisons cover 16 rows for expiry and both 60/120 second leads;
local HTTP tests distinguish OAuth raw status from JSON API overrides.

Owned API 36 runtime: 82/82 in 147.232s, empty crash log, no preflight ANR,
settings/window restored and emulator exit 0. Seven new encrypted account/HTTP
cases accompany the prior 75-case auth/callback/logout/helper/terminal/settings/
UI cohort. Cancellation drained its actual tracked work; late mint/rejection and
a fast read after account replacement preserved the newer credentials.

Evidence under `captures/recovery-20261023`: `access-token-build-v1.log`,
`access-token-jvm-v1`, `access-token-runtime-v1` and aggregate
`access-token-checkpoint.json`. The aggregate binds eight tested source/script/
fixture files, both APKs, the pinned whole SDK file/declarations and open gates.
The frozen signed722 hash remains unchanged.
