# Notification registration ownership and readiness

A maintenance queue containing older helper registrations could delay a ready
renewal for the current phone token. `PhoneHelperMaintenanceRecovery` previously
took the first eight rows before checking their retry times. Eight historical
rows in backoff therefore hid an eligible ninth row. Even when the historical
rows were ready, they consumed the whole pass before a current renewal could run.

The pass now filters by the persisted retry floor before applying its existing
eight-operation budget. Eligible current renewals run before historical cleanup.
Sorting preserves order within each category. A renewal already marked aborting
stays on its existing uncertain-operation cleanup path. Request IDs, signed
proofs, ledger/account ownership, retry floors, operation/cleanup deadlines and
the pass limit are preserved. This does not grant notification consent, migrate
account trust or change helper enrollment.

## Source and production paths

The comparison pin remains
`a5911a1f92f3eeb11c4d7f3e2ef89204718c5009`.

| Boundary | Original iOS source | Android production |
| --- | --- | --- |
| Composition | `MobileAuthComposition` installs `DeferredSignInHook` to call `PushRegistrationService.syncTokenIfPossible` | `NativeAppConnections` binds the account callback to team refresh; helper recovery is also driven by application/token/account observers |
| Account changes | The coordinator owns a distinct post-sign-in hook task | `PhoneFcmTokens.observe` observes task-session changes, then launches application-owned recovery |
| Helper changes | The upstream actor owns its API registration and cleanup state | `PhoneHelperMaintenanceWork.observe` observes primary-account revisions, reconciles the separate encrypted ledger and schedules constrained WorkManager work |
| Token callback | An accepted APNs token is cached and synchronized when consent allows | A content-free callback marker fences SDK reads; only an accepted FCM SDK read updates the durable token revision |
| Readiness versus history | `register` and `syncTokenIfPossible` upload the current token before historical DELETE attempts | Ready current helper renewals now precede eligible historical cleanup; backoff rows do not consume the pass budget |
| Sign-out authentication | The API DELETE uses a captured account/token pair and never falls back to the next live account | Cleanup uses retained encrypted helper-registration identity/proof material; its captured Stack pair is not the helper credential |
| Uncertain mutation | A late successful POST is reconciled with captured credentials, then the current account is registered | Durable challenge/finish/abort state settles an earlier helper mutation before another generation can replace it |

Application startup calls `PhoneFcmTokens.observe`, which starts the helper
maintenance observer. Startup/resume/boot, token callbacks, notification
preferences and login changes converge through token recovery. Its finally path
also recovers enrollment and maintenance. Separate workers serialize actual FCM
SDK operations and helper network work. Helper recovery commits the durable
ledger before repairing its account projection. Ordinary recovery keeps existing
scheduled work; changed work replaces it.

Consequently, absence of a direct Android push `onSignedIn` callback does not
establish absence of registration recovery. The observer-driven path is also not
proof of the original explicit hook's complete timing, cancellation, retry or UI
contract. Team refresh and push registration remain separate effects in the
original and must not be equated through enum or callback names.

The original API and private Android helper use different registration protocols
and consent/identity scopes. This change applies the demonstrated readiness
ordering to Android's existing helper protocol. It does not assert equivalent
backend/account-switch behavior, nor label every existing difference an
unavoidable platform limitation.

## Executed original behavior

`scripts/audit-upstream-push-registration.py` builds a local Swift 6 executable
from the unchanged original push actor, SQLite cleanup store, retry/redirect
policy, deadline/registry types and registration protocol. It also compiles the
complete original post-sign-in timeout extension, with only its enclosing
coordinator type renamed, and the unchanged `DeferredSignInHook`.

Five Foundation cases assert:

- A timed-out hook remains owned until its actual completion and sign-out join.
- A bounded sign-out join can finish while retaining the still-running hook.
- The deferred hook reaches the actual push service; a late POST after sign-out
  is followed by a DELETE using the captured old owner.
- A rotated current token is registered before historical cleanup, and cleanup
  failure does not undo current registration readiness; later recovery clears
  the durable old-token obligation.
- A late account-A POST is repaired in POST-A, DELETE-A, POST-B order, leaving B
  as the acknowledged owner.

These cases use the original scripted URLProtocol, provider and clock helpers,
fresh UUID UserDefaults suites and real disposable SQLite files. Complete original
`TokenProviding` and session-snapshot declarations are extracted; the real
coordinator conformance is omitted. Auth error cases, diagnostics and the minimal
coordinator owner are explicit seams. No production actor method is replaced.
The source audit does not execute `MobileAuthComposition`, Stack SDK, UIKit,
APNs, Firebase, Android workers or a physical device.

Reproduce with a fresh output directory:

```sh
python3 scripts/audit-upstream-push-registration.py \
  --upstream captures/source-audit-20261020/upstream \
  --out captures/recovery-20261025/upstream-push-audit-fresh
```

The receipt binds whole original files, generated compilation units, generator
and compiler version. The local upstream package's SDK dependency is absent and
the installed Command Line Tools lack the Swift Testing module. The first audit
attempt compiled the production push files but could not compile the original
test suite. The Foundation executable replaces that unavailable test harness;
it does not claim the original Swift Testing suite passed. A second wrapper
attempt had a malformed key-path expression; the corrected third attempt passes
the five cases without changing the original production declarations.

## Android verification

Two JVM regressions failed before the fix because the current remote token stayed
unchanged behind the old rows. After the fix, they verify an acknowledged renewal
ahead of eight deferred cleanups, readiness ahead of nine eligible cleanups,
exact request ordering, retained retry floors and the eight-operation budget.
Existing maintenance cases retain coverage of uncertain ACK recovery, aborts,
logout, failed ledger/account writes and newer physical pairing authority.

Full JVM/build-v3 passes 3,529 total in 473 suites: 3,525 passed, four existing SSH
skips, no failures/errors. Both debug APKs build. The two new ART cases construct
actual maintenance requests from isolated encrypted ledgers, persist retry
results and read them back through new store objects. Their transport seam
returns Retry, so they do not claim a remote acknowledgement. They also check
pairing preservation and absence of token/key identifiers in plaintext
preferences. The existing eight storage/retirement cases run alongside them.

API36 runtime-v1 passes all 10/10 in 2.655 seconds, with all 2,067 local inputs
and installed app/test APK hashes bound. Both new records preserve the encrypted
pending operations and pairing; one ready renewal is attempted past eight
deferred rows, while the ready-history case admits exactly eight operations with
the renewal first. The transport seam returns Retry in both cases, so these
records prove selection/persistence, not delivery or a remote ACK. The run has
an empty crash log, no preflight ANR, restores settings/window and observes owned
emulator exit 0. Prior broad runtime totals are not rerun claims.

Evidence: `captures/recovery-20261025/helper-readiness-checkpoint.json`, the
retained failing JVM-v1 XML, full JVM-v3 XML, runtime-v1 receipts and original
source audit-v3. No deadline, authority or pass-limit assertion was relaxed.

## Remaining obligations

Follow-up: [Helper worker recovery](PUSH_WORKER_RECOVERY.md) executes actual
WorkManager cancellation and real TLS ACK recovery against the production local
helper, with an isolated token/storage adapter. It adds evidence beyond the Retry
transport seam here; the default observer/Firebase and physical gates stay open.

Complete registration-hook, observer/worker scheduling and cancellation parity
is not closed by this readiness fix. Other preflight/registry/caller/presentation
and nested currentTokens/deletion behavior remains open. No physical phone or
Mac pairing/helper/Keychain operation is performed here. Real token-provider
callbacks, suspended/process-dead delivery, locked storage, notification taps,
terminal wire IO, full navigation/accessibility, real Pixel/Mac/helper/Firebase/
Cloud/VPN acceptance and signed delivery remain required. Real account, pairing,
signing data, broad upstream pins and frozen signed722 are preserved. Full app
parity remains active.
