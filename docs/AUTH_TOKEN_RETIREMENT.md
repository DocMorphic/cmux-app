# Token work finishing across sign-out

The original token-phase completion policy is now executable independently of
the earlier token-reader fixtures. Those fixtures replaced the finish callback
with an inert seam; they did not verify the actual cleanup policy.

`scripts/audit-native-token-finish.py` compiles the complete unchanged
`AuthCoordinator+TokenTouchingPhaseTimeout.swift` extension at upstream
`a5911a1f92f3eeb11c4d7f3e2ef89204718c5009`, together with its original
deadline/phase/tracked-work types and race actor. Only the enclosing coordinator
type is renamed. A same-file trampoline reaches the private finish method.
Fourteen direct finish cases and eight complete phase cases execute with real
Swift Tasks. The SDK storage, presentation effects and clock are controlled
seams; this does not execute the iOS SDK, Keychain or UI.

Reproduce with a fresh receipt path:

```sh
python3 scripts/audit-native-token-finish.py \
  --upstream captures/source-audit-20261020/upstream \
  --out captures/recovery-20261025/original-token-finish-fresh.json
```

The audit refuses to overwrite a receipt. It records the complete original file,
generated compilation-unit and generator hashes, plus compiler version.

## What the complete original does

An unchanged sign-out epoch skips reconciliation, even if session generation or
the token-store write high-water changed. A changed sign-out epoch first joins
credential capture. Cancellation does not release that checked continuation;
capture release is explicit and idempotent. A newer write observed after capture
prevents all raw reads and conditional clears.

Otherwise it reads the raw refresh token. A missing token or the latest sign-in's
refresh token is retained. Another token is conditionally cleared by its exact
value and then read back. A failed conditional clear preserves a replacement.
Generation/high-water changes after these awaits can request presentation clear
when the raw read-back was nil and the coordinator remains authenticated. That
clear preserves the pending code. Every path entered after a changed sign-out
epoch ultimately throws cancellation, including an operation that originally
failed with another error.

The artificial read seams also expose a limit: if the second raw read captures
nil and a replacement appears before that await returns, the original still
uses the captured nil to decide presentation cleanup. This observation is not a
claim that the production SDK admits that interleaving or a requirement to
reproduce it unsafely on Android.

The eight full-phase cases retain actual phase ownership through capture waits,
check cleanup after successful and failed operations, propagate actual-task
cancellation and observe registry retirement at actual completion. They exercise
the original waiter/timer/cancellation methods rather than replacing the wait
with a direct task await. They do not fire the deadline or demonstrate iOS
scheduling or device sleep.

## Android producer mapping

Android does not bind a literal port of this asynchronous finish callback.
`NativeAuthWork.run` still has a synchronous optional finish seam. Its production
producers instead combine account cancellation, credential ownership and guarded
transactions:

| Boundary | Production authority |
| --- | --- |
| Sign-out begins | `beginSignOutCapture`, immediate pending-flow/work cancellation, then `completion.clearIf` under the credential monitor |
| Durable logout | `requestLocalLogout` persists intent and advances the shared auth epoch before replaying the local clear; pairing data survives |
| Refresh result | `NativeTokenRefresh` retains the actual job and checks its refresh owner, cancellation and deadline |
| Encrypted refresh commit | `applyRefresh` rechecks the epoch and shared-refresh commit fence after encryption, before preference commit |
| Interactive exchange write | `NativeSignInCompletion` checks writer/flow before writing; a canceled exchange's finally/cleanup conditionally retires only its pending receipt |
| Replacement before verification | A distinct pending receipt changes refresh ownership even when the refresh-token string is reused |
| Public operation result | Actual token-phase cancellation and late-result checks run before returning the saved success/failure |

The shared auth epoch advances for writes as well as logout. It must not be
treated as a dedicated sign-out epoch or substituted mechanically for the
original high-water mark. Likewise the interactive exchange's rollback is not
the refresh path's final preference-commit fence. Broad preflight/capture gates
currently prevent readers and new interactive flows from treating a retiring
writer as a usable account. Their exact source ownership mapping remains open.

`NativeTokenRetirementRuntimeTest` adds five ART cases using production account
and encrypted store objects, UUID fixture documents/keys and a local HTTP
provider. They cover a synchronous refresh that ignores cancellation until
released, sign-out while refresh encryption is parked, sign-out while exchange
encryption is parked, and late refresh success/rejection after direct-seeding an
unverified replacement with the same refresh string. The last two deliberately
seed the SDK storage boundary; they do not bypass production sign-in preflight
and then claim a second interactive flow reached it. Final ciphertext, cold
reads, published identity, request counts and actual registry retirement are
asserted. Refresh sign-out cases additionally count revisions to detect an
obsolete intermediate preference commit.

## Verification

The final original audit passes all 22 cases with Swift 6.3.3. Both debug APKs
build. Focused API36 runtime-v2 passes 5/5 in 0.524 seconds. Its five records
confirm cold encrypted reads, preserved pairing markers, unchanged replacement/
post-logout ciphertext, canceled actual writers and complete actual-job
retirement. The run binds all local source/resource/build inputs and installed
APK hashes, has an empty crash log and no preflight ANR, restores settings/window
and observes owned emulator exit 0. No production source changed in this audit;
the previous full JVM/runtime cohorts remain historical evidence, not reruns.

Evidence: `captures/recovery-20261025/token-retirement-checkpoint.json` and
`original-token-finish-v2.json`. Runtime-v1's five-method attempt is retained:
four passed and the exchange fixture timed out waiting for the outer wrapper
Job to become canceled. The registered operation is the inner coroutineScope
Job; its wrapper catches cancellation and need not be canceled itself. The
corrected fixture observes the registered actual writer before release (one
canceled writer in the final record). It retains its original timeouts and
rollback/no-user-fetch/no-publication assertions. No production fix was needed
for the five exercised races. A diagnostic logcat request made after the first
driver had already shut down its emulator timed out; it is not an app failure
or evidence of another running process.

## Open obligations

These source cases and producer tests do not prove all token-phase outcomes
equivalent. Complete registry admission, nested currentTokens/deletion phases,
presentation/error composition and callback ownership remain open. A decision
to add asynchronous reconciliation must use the actual producer/transaction
mapping and account for non-cancelable capture/cleanup, rather than adding a
callback with superficially similar names.

Locked storage, process death and actual Pixel sleep/Doze need device evidence.
Post-sign-in push registration is a separate original hook from team refresh;
Android's team callback and private notification-helper maintenance must not be
claimed to implement that complete hook. Full UI/accessibility, real
Pixel/Mac/terminal/provider/helper/Firebase/Cloud/VPN and signed delivery gates
remain open. Account, pairing, signing data, global pins and frozen signed722
are preserved; full parity remains active.
