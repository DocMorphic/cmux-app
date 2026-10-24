# Captured-session signout teardown

This ports the normal bounded server tail from original cmux revision
`a5911a1f92f3eeb11c4d7f3e2ef89204718c5009`, in AuthCoordinator.signOut,
StackAuthClient, StackClientApp.likelyValidAccessToken/revokeSession and the SDK
APIClient. Four whole original files are bound in
`captures/recovery-20261023/signout-teardown-source-bindings.json`; source library
reference run40060677846 previously passed146 coordinator tests in18 suites.
This checkpoint does not advance global source adoption pins.

## Production path and ordering

MainActivity's default connector is null, so NativeScreen uses its process-owned
NativeAppConnections. That graph's Sign out entry now invokes
`NativeAccount.signOutAndRevoke`. The existing `NativeAccount.signOut` remains the
local retirement primitive; isolated injected-connector screens and their tests
continue to own that local-only account lifecycle. They do not send synthetic
credentials to the production account service.

Both entrypoints preserve durable local logout. The server entry captures the
stored access/refresh strings under the same credential transaction, after
recording logout intent and clearing the auth model but before retiring the
readable token projection. Capture is a raw read, with no network refresh. A
failed capture supplies an empty pair; it never later loads live credentials.
The captured pair stays in memory only and has a redacted string representation.

A shared capture barrier blocks fresh exchange ownership snapshots until capture,
local retirement, cancellation of old token work and registration of the actual
teardown job finish. That snapshot is atomic with the capture gate. The job is
registered in the document-shared NativeAuthWork registry before the gate opens.
A fresh sign-in joins its bounded attempt before starting a new exchange; normal
auth cancellation does not abort the ephemeral revoke just because fresh login
starts. Explicit teardown cancellation is available for its lifecycle owner.
Actual jobs, including cancellation-ignoring work, remain tracked until terminal.

The tail first cancels/joins existing tracked teams/post-sign-in phases, waiting
at most five seconds. It then gives the network sequence a separate five-second
budget, matching the original's separate hook-wait and server-tail budgets.
Local authentication is already gone during both waits. The network sequence is:

1. Resolve likely-valid access from an ephemeral captured pair. Freshness follows
   the original expiration policy: per-client60–120second refresh lead, capped
   at half token lifetime and floored at20seconds, with original integer-ms
   boundaries. A failed mint falls back to the captured access value.
2. Run the signed-out hook with that resolved access and captured refresh. The
   production Android hook attempts the existing private-helper maintenance
   recovery using its encrypted per-receipt key/proof ledger. Its guarded HTTP
   client is cancellation-aware. It never borrows a later account's bearer.
3. Revoke the captured Stack session with DELETE `/api/v1/auth/sessions/current`.
   Both token headers come from the captured/ephemeral pair. Empty successful
   responses need no JSON. Only exact `invalid_access_token` refreshes/retries
   the ephemeral pair once. Network failures use the original idempotent retry
   delays, under the shared deadline. Neither mint nor revoke writes live tokens.

Cancellation is checked between every leg, including after a mint or hook that
returns after cancellation. OkHttp calls cancel with their coroutine. A thrown
hook error cannot undo local logout or prevent best-effort Stack revocation;
source hooks report their own failures through a nonthrowing callback contract.
Conditional deletion callbacks still require the matching readable login and
start no tail if that conditional retirement does not apply.

## Verification scope and remaining gates

JVM policy/HTTP cases cover gate nesting and cancelled waiters, freshness limits,
redaction, ordering/fallback, deadlines, late hooks, tracked-effect joining,
exchange ordering, DELETE headers/body/nonce, one rejected-access refresh,
empty/non-JSON success, network retries and request cancellation. HTTP fixtures
use explicit IPv4 loopback for disconnect tests: localhost's alternate IPv6
route caused the initial fixture retry failures and was not listening.

Android runtime fixtures use real encrypted Keystore documents and production
account/HTTP methods. They check the actual default five-second timeout and
request drain, refresh-only mint with the live session staying empty, fresh login
joining an old hung DELETE, unavailable capture never falling back to replacement
credentials, and unmatched conditional ownership. Provider/browser callbacks and
server replies in those cases are synthetic and local.

This is not full signout or app parity acceptance. The real private helper HTTPS
revoke, Firebase token/unregistration behavior and post-sign-in side effects
outside the tracked registry still require integration proof. Native APNs push
unregister is not an Android backend contract; Android uses its existing private
helper proof protocol, whose live phone/backend acceptance remains open. Complete
SDK rate-limit/backoff and metadata equivalence, hanging local crypto/filesystem
bounds, actual OS unlock/process death, full UI/accessibility/source adoption and
physical Pixel/Mac/provider acceptance remain open. The network deadline assumes
cancellation-aware operations; a callback/provider that ignores cancellation
cannot be declared bounded merely because a coroutine timeout fired.

No phone/Mac install, live account or frozen signed release is changed here.

## Checkpoint evidence

Full JVM:3266 total,3262 passed,4 existing SSH skips,0 failures in443 suites;
`captures/recovery-20261023/signout-teardown-build-v2.log`. Final instrumentation
build: `signout-teardown-build-v3.log` in that folder. Accepted owned API36 cohort:
67/67 in151.144s, crash empty, no preflight ANR, settings/window restored, emulator
exit0; `signout-teardown-runtime-v1` in that folder. Offline default5000ms budget
completed in5017ms with HTTP calls drained; fresh exchange joined the old attempt
for4999ms and preserved the fresh login. Five new account/HTTP cases accompany
the prior62-case logout/auth/restore/helper/deletion/terminal/settings/UI cohort.

The first two focused JVM attempts each had one disconnect-fixture routing
failure: localhost selected IPv6 although the mock server was listening on IPv4.
Explicit loopback fixed the fixture; no production timeout increase was used.
Aggregate APK hashes, ten tested-source bindings, source file hashes, attempts
and open gates are in `signout-teardown-checkpoint.json` in that folder.
