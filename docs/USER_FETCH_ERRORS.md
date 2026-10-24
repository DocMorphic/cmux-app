Follow-up: [Token resolution and pending shared refresh](TOKEN_RESOLUTION.md)
records the later proactive/forced selection and private pending-owner integration.
The checkpoint and remaining work below describe their earlier scope.

# User-fetch error outcomes

The SDK's missing-user outcomes are separate from transport success and failure.
Android user verification and restored-user reads now apply that distinction
before the app chooses an error message or recovery action. The source is audit
candidate `a5911a1f92f3eeb11c4d7f3e2ef89204718c5009`; global source pins remain
unchanged.

| Source result | getUser(.throw) | Interactive completion |
| --- | --- | --- |
| No resolved access token | USER_NOT_SIGNED_IN; no user HTTP request | Generic server error |
| Malformed/non-object successful user JSON | nil | AuthError.unauthorized / session-expired message |
| HTTP/known/network failure | USER_NOT_SIGNED_IN | Generic server error |
| Anonymous/restricted user | USER_NOT_SIGNED_IN | Generic server error |
| Exact concrete SDK RATE_LIMITED error | Same error escapes | Rate-limit message |

The source's known schema, OTP and unauthorized errors lose those specific roles
when they arise inside getUser. They must not produce an email-validation, code
or session-expired message at that later stage. Non-SDK errors spelling
RATE_LIMITED do not receive the SDK's special escape behavior. Parent task
cancellation remains cancellation in the Android app flow.

The interactive coordinator leaves already-written pending credentials available
after a user-fetch failure, without publishing a signed-in account. An orphaned
pending restore treats successful missing-user JSON as unauthorized and clears
only that matching receipt; transient HTTP failure preserves its credentials.
The existing owner, epoch, phase, publication and pairing guards remain in place.
The restored cached-session reader retains its separate nil/unavailable/rate
outcomes. User JSON parsing now runs on Dispatchers.Default when the caller is
on the UI dispatcher; timing/performance equivalence is not established here.

## Executed source

`scripts/generate-native-user-fetch-reference.py` produces 252 cases: 36 input
plans across SDK throw/null modes, client throw/null adapters, team/selection
adapters and interactive completion. It executes 18 complete original
declarations, including getUser/handleNoUser, user/team initialization and
getters, listTeams, the app adapters/mapping extension and coordinator
completeSignIn. Whole original models, Errors, AuthError, display-safe mapping,
failure-detail formatting and validation-action sources compile in the same
module, with module imports flattened. Regeneration is byte-identical.

Token presence and HTTP responses/errors are supplied by inert seams. Original
coordinator receipt state, phase timing and publication are inert. The corpus
does not execute token freshness resolution, original transport/refresh, actual
ownership races, timers, anonymous signup or UI rendering. The measured host is
macOS26.5.2 / CoreFoundation5026.5, not an iOS runtime.

The Android corpus checks user/nil/error projections, request admission and
display-safe roles. Original diagnostic strings derive those expected roles;
the Android failure-detail copy/export UI is not asserted equal to those strings.
Real HTTP and disposable encrypted-account checks exercise production callers
separately. This does not establish the original team's logs-only failure UI or
complete app UI parity.

## Verification

Full JVM: 3,470 total in 461 suites, 3,466 passed, four existing SSH skips,
zero failures/errors. The suite executed in build-v2; build-v3/v4 reused identical
JVM results with unchanged main/unit inputs. Both debug APKs build. Owned API36:
220/220 in 352.36 seconds, five new methods and 215 prior regressions. The
252-case original corpus executes on ART. Real account/Keystore/HTTP checks cover
three interactive outcomes and orphaned nil-versus-failure cleanup, preserving
pairing markers and ciphertext. The source's ineligible-user error role is also
checked through the existing live Compose sign-in flow.

The real 60-second phase returned at 60,009ms with ciphertext/pairing intact and
no PATCH. The shared two-second fixture returned at 2,065ms, after entering both
HTTP stages. Crash log empty, no preflight ANR, restored settings/window, owned
emulator exit0. The receipt binds 95 local inputs, 47 original files, exact
installed APKs and the source-reference asset hash.

Runtime-v1 exposed the pre-existing 300ms/190ms deadline fixture: it could expire
before membership HTTP started. Its 2000ms/1250ms replacement keeps each transfer
below the budget and their sum above it, with the same timeout, request-count and
publication assertions. Runtime-v2 exposed a draft test observing the underlying
disabled editor before async save/resume completed; runtime-v3 waits for enabled,
populated resumed text. Production deadline/editor code and the app APK are
unchanged across these attempts. Earlier failed evidence is retained separately.

Evidence: `captures/recovery-20261023/user-fetch-checkpoint.json`.
Next resolver audit: `captures/recovery-20261023/user-fetch-next-token-resolution.json`.

Full parity remains active. Token-freshness/preflight resolution and its shared
refresh coordinator, pending/verified owner domains, callback failure handling,
direct OAuth/form/backend protocols, observer/reporting and diagnostic UI,
remaining metadata/image/demo/reset/identity/worker work, iOS-runtime proof,
actual OS/locked-storage/death and physical Pixel/Mac/provider/helper/Firebase,
matched UI/accessibility and signed delivery remain open. Real account, pairing
and signing data, source pins and frozen signed build722 are unchanged.
