# Helper worker cancellation after a remote commit

The helper maintenance worker now has a scoped operation constructor matching
the existing receive worker's injection pattern. Its public two-argument
WorkManager constructor still calls `PhoneHelperMaintenanceWork.run`.
Scheduling is factored into an internal `enqueue` function used by `recover` and
the runtime check. The unique work name, KEEP/REPLACE decision, network constraint,
linear backoff and production recovery/HTTP logic retain their existing behavior.

This makes the actual worker and scheduling boundary executable against
disposable encrypted stores and a local helper, without invoking the real FCM
token provider or changing any real account/pairing data.

## Actual cancellation boundary

`PhoneHelperWorkerRecoveryTest` runs real WorkManager, the production worker,
`PhoneHelperMaintenanceRecovery`, `PhoneHelperHttp`, Android Keystore and a local
Node/CryptoKit helper. It completes actual TLS enrollment and pins its verified
receipt before maintenance begins.

`scripts/push-maintenance-worker-fixture.mjs` uses the production enrollment HTTP
handler, enrollment/maintenance services and encrypted SQLite registrations. A
fixture-only response-write gate withholds a successful renewal ACK **after**
the production maintenance service has committed it. The fixture does not
replace finish/abort logic or turn a received ACK into a synthetic failure.

The runtime exercises two phases:

| Phase | Evidence |
| --- | --- |
| New token replaces a worker whose first renewal committed | The challenge is already in the encrypted ledger; REPLACE stops the actual old worker and closes its held HTTPS response; the next worker recovers the committed ACK using the exact saved request/proof, then commits and verifies the latest token/generation |
| Logout replaces another committed renewal | Actual local account sign-out retires delivery trust; recovery resolves the saved renewal ACK and revokes its next generation; remote registration and local helper trust are absent at completion while pairing remains |

The helper records ten ordered maintenance events and exactly four mutations:
three renewals and one revoke. Both aborts return the already-committed ACK for
the original request ID. Recreating recovery/store readers does not invent a
second mutation or restore an old account's trust. The injected adapter's shared
mutex records a maximum of one active pass. Actual HTTP calls drain before test
cleanup, the prior WorkManager delegate is restored and UUID preferences/keys
are removed.

WorkManager may delete an old WorkSpec during REPLACE. The test therefore checks
the actual worker's stopped flag and the helper's closed response in addition to
the nullable old WorkInfo; absence of a database row alone is not cancellation
proof.

## Scope of the controlled seams

The worker receives an isolated storage/token-provider adapter. That adapter
performs ledger-first encrypted transactions and account projection repair; it
does not execute the default `PhoneFcmTokens.setup` permission/Firebase adapter or
the application observer. Enqueue/worker/recovery/HTTP and helper protocol paths
are actual implementations. The test advances only WorkManager's injected
scheduler clock for retry admission. HTTP, crypto challenges, offers and
operation/cleanup expiry use real time and their existing bounds.

The helper binds localhost, exposes a fixture-only control port and uses fresh
TLS credentials trusted only by the test client. Its encryption/phone identities
are published synthetic fixtures; the copied Android test asset is byte-identical
to the existing JVM enrollment vector. No Firebase request, physical phone/Mac
pairing window, real account or signing credential participates. Actual OS
process death, device sleep and live provider delivery are not established.

## Verification

Both debug APKs build. Runtime-v2 passes 11/11 in 10.027 seconds: the new
two-phase worker scenario and ten existing encrypted-storage regressions. It
records the four real helper commits, both canceled/closed responses and exact
ACK recovery. The crash log is empty, preflight has no ANR, settings/window are
restored, and both the owned emulator and helper process exit 0.

Full JVM/build-v4 passes 3,529 total in 473 suites: 3,525 passed, four existing SSH
skips, zero failures/errors. Final JVM XML is retained. Both APKs remain
byte-identical to the passing runtime pair. One JVM-only account-profile fixture
changed after that runtime; its old snapshot is retained and its class is absent
from both APKs' DEX. Relevant production/Android runtime sources remain unchanged.

Evidence: `captures/recovery-20261025/helper-worker-checkpoint.json`.
Earlier attempts are preserved:

- Runtime-v1 passed ten storage cases and reached one real remote commit. The
  new test failed by requiring a replaced WorkSpec to remain queryable. The
  corrected observation uses the actual stopped worker plus closed HTTPS
  response, without relaxing any recovery, deadline or authority assertion.
- Full JVM-v3 completed with one failure in an existing profile-cache fixture.
  Its counted user-response queue can retain a 503 when one concurrent failed
  read cancels its sibling. That test now routes by path and current phase,
  preserves its cache/membership/selection assertions and adds request-count
  checks. The failed arrival trace was not captured, so its precise ordering is
  not declared proven. Full-v4 passes without changing production team logic.

## Remaining work

Default application/permission/Firebase observer and WorkManager recovery must
still be verified as one composed path, including callback timing and persisted
retry scheduling. This does not close complete registration-hook or iOS/Android
backend equivalence. See [Registration ownership](PUSH_REGISTRATION_OWNERSHIP.md).

The broader completion gates in `REMAINING_WORK.md` remain authoritative: Chief
and Feed behavior/UI, account/connection/terminal lifecycle, Files/viewers,
Cloud, analytics/billing contracts, full navigation/accessibility, current-source
reconciliation and signed delivery all require their remaining evidence. Cold
notification taps and updated native terminal/provider workflows still need the
Pixel and real Mac. Real account, pairing, signing data, global pins and frozen
signed722 are preserved; full parity remains active.
