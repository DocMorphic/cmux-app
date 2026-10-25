# Continuous preflight and server teardown waits

`NativeAuthWaits` binds read-only completion watchers and structured server tails
to the same continuous clock and kernel timer used by production auth phases.
They keep different ownership contracts:

| Wait | At its deadline |
| --- | --- |
| Previous-flow and token-work preflight | Cancel the join watcher and return timeout; actual operations retain their registered owners |
| Canceled post-sign-in effects before sign-out | End the bounded join, then start the separate server-tail budget; late effects remain tracked |
| Server teardown | Cancel its operation and join that actual child before the tail retires |

The previous-flow wait and subsequent token-work preflight share one absolute
network deadline. A flow cannot spend a complete network budget waiting for its
predecessor and then restart that budget for token cleanup. The later snapshot
still sees cleanup work created while the preceding flow retires. Completed/empty
joins need no timer and do not invent an extra late-result expiry verdict.

Server teardown preserves a separate budget after the post-sign-in-effect join.
Its captured-credential mint, sign-out hook and revocation keep cancellation
checks between legs. An operation ignoring cancellation remains owned until it
really returns; its later legs cannot start after cancellation. Timer setup
failure also cancels and joins this child. Caller cancellation preserves the
structured join while releasing its kernel registration.

`NativeAuthWork` continues tracking actual work, not these read-only watchers.
Its independent uptime retry damper and token-phase deadline policy are retained.
Local-first account retirement, conditional writes, published account generation,
pairing and signing data are unchanged.

## Original mapping

The immutable comparison pin is
`a5911a1f92f3eeb11c4d7f3e2ef89204718c5009`.
Complete source review includes `AuthCoordinator+SignInTokenWorkPreflight.swift`,
`AuthCoordinator+PostSignInTimeout.swift`, `AuthCoordinator.swift`,
`AuthPhaseTimeout.swift` and the tracked-work/deadline types under
`Packages/Shared/CmuxAuthRuntime/Sources/CmuxAuthRuntime/Coordinator/`.

The original preflight snapshots and cancels validation/token/exchange work,
then races its completions against the coordinator clock. Post-sign-in-effect
joins stop waiting at their deadline. The server tail instead uses structured
`withTaskGroup`: canceling the losing operation still joins it on group exit.
The Android watcher uses cooperative Job joins; structured tails own their child.
Completion and timer winners are settled before public continuation delivery.

A preliminary local probe executes the complete unchanged original preflight
methods, tracked-work type and race actor using real Swift Tasks, controlled
completion gates and an actor clock. The enclosing coordinator type is renamed;
method declarations/bodies remain unchanged. Twelve role/action rows include
token, validation, exchange and three empty-work returns. This is original-only
Foundation evidence, not a new shared Android execution reference or iOS-runtime
proof. Its cancellation rows report timedOut while the Swift Task is marked
canceled; Android retains canceled-Job propagation. Complete caller/UI/diagnostic
error composition remains an open comparison rather than an equivalence claim.

## Verification

Eight new JVM cases cover continuous advancement without coroutine-clock
advancement, shared preflight budget, actual-job retention, structured timeout and
caller cancellation, timer failure, separate effect/server budgets and completed
joins. The corrected focused cohort passes 53 methods. Full JVM/build-v4 passes
3,527 total in 473 suites, 3,523 passed with four existing SSH skips and zero
failures/errors; both debug APKs build. The focused API36 cohort passes 6/6 in
1.466 seconds. Its post-effect/server fixture returns at 404ms for separate
200ms budgets, starts the server leg at 204ms and retains the old effect until
release. The full API36 cohort passes 303/303 in 556.94 seconds, including six new
methods and 297 regressions. Full/focused runs bind identical source inputs and
installed app/test APK hashes, have empty crash logs and no preflight ANR,
restore settings/window and observe owned emulator exit 0. Final full-v4 JVM XML
is unchanged. All 22 packaged native library hashes remain unchanged, and APK
ZIP alignment passes.

Evidence: `captures/recovery-20261025/waits-checkpoint.json`. Six Android methods use the production kernel
wait and disposable encrypted store to verify completion/preflight deadlines,
sign-in admission, separate server budgets and structured retirement. No real
account, production provider or server deletion is involved.

Earlier evidence is retained. The first focused run failed two new fixture cases:
its fake clock sent one wake to one consumer, leaving other expired waiters
asleep. It now publishes time to every due waiter. Full-v3 then had one existing
membership/cache fixture failure with a nil initial scope. Code review identified
that its FIFO mock queue could serve a profile body to the concurrent team-list
request. URL-based responses remove that ordering hazard and preserve the
atomic-persistence/continuation assertions, with exact request counts added. The
failed run did not capture enough transport context to prove its precise arrival
order; other intermittent fixture failures are not declared fixed. No deadline
or production authority assertion was relaxed.

## Remaining work

Complete original phase finish/high-water reconciliation, nested currentTokens/
deletion phase binding and registry admission remain open. The Android preflight
still retains additional whole-flow/teardown ownership compared with the original
snapshot categories, and its post-sign-in-effect snapshot needs complete registry
mapping. Caller/UI cancellation-error composition also remains open. These timer
changes do not close those larger behavior gates.

Outer controller/HTTP budgets, synchronous IO bounds, actual device suspend/Doze,
locked storage/process death, Android deletion-receipt differences, remaining
backend/schema/metadata/form/callback/identity/worker/diagnostic behavior, matched
full UI/accessibility, physical Pixel/Mac/provider/helper/Firebase/Cloud/VPN and
signed delivery remain required. Global pins and frozen signed722 are preserved;
full parity stays active.
