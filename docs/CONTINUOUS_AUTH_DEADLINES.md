# Continuous deadlines for native authentication phases

## Completion-wait follow-up — 2026-10-25

[Auth completion waits](AUTH_COMPLETION_WAITS.md) moves preflight and sign-out
completion/server waits onto the production continuous clock. Read-only joins
and structured server cancellation retain different ownership. Larger registry,
finish/high-water and caller/UI error-composition comparisons remain open.

Production `NativeCredentialStore.authWork` now reads `SystemClock.elapsedRealtime`
and waits on an absolute Linux `CLOCK_BOOTTIME` timerfd. The time remaining in a
request includes device suspend. An expired token phase still rejects late
success and late failure before returning its result. Same-phase retry gating
retains the actual operation until it retires; the public timeout does not join
cancellation-ignoring work.

The timer and the clock must use the same timebase. Changing only the timestamp
read would leave a relative coroutine delay waiting for the remainder of its
uptime budget after wake. The process-owned scheduler uses one kernel timerfd
and one eventfd for concurrent registrations, arms the earliest absolute deadline,
and wakes its worker when registrations change. Last-waiter cancellation or
completion retires the worker and closes descriptors. It holds only monotonic
deadlines and completion callbacks, never account credentials or pairing data.

`NativeDeadlineScheduler` separates queue ownership from the Android libc binding.
`NativeContinuousDeadline` binds LP64 timer/poll structures for the app's existing
arm64-only distribution. It reuses the already packaged JNA 5.17.0 Android runtime;
the app declares that existing dependency directly. The timer is nonwaking,
process-owned and requires no alarm permission or persisted system alarm. Timer
setup failure is a typed retryable failure that cancels the operation and settles
its caller instead of leaving a spinner pending.

Request deadlines and retry suppression use different clocks in the original.
The retry damper remains on Android uptime; sleeping for 30 seconds does not
release a timed-out exclusive operation's slot early. Explicit supersession and
actual-job retirement retain their existing contracts. Prompt/exchange phases
keep their race winner without receiving the token phase's extra late-result
verdict. Pure JVM callers retain injectable clocks and waits.

## Source and platform mapping

Immutable source comparison: `a5911a1f92f3eeb11c4d7f3e2ef89204718c5009`:

- `AuthCoordinator.swift` defaults to `ContinuousClock()` and records timed-out
  slot expiry using Dispatch uptime.
- `Clock+AuthTokenDeadline.swift` captures an absolute end, reads expiry and
  remaining duration from the same clock, and sleeps until that end.
- `AuthTokenDeadline.swift` documents why a response delivered ahead of its timer
  after wake must not publish after the budget.
- `AuthCoordinator+TokenTouchingPhaseTimeout.swift` retains its tracked operation,
  deadline race and post-finish expiry verdict.
- `AuthPhaseTimeoutRegistry.swift` uses uptime for the separate retry damper.

These files are under
`Packages/Shared/CmuxAuthRuntime/Sources/CmuxAuthRuntime/Coordinator/`.
They were read as complete pinned sources. This checkpoint introduces no new
whole-original execution reference; earlier source-reference cases remain
regressions with their documented seams and limits.

Apple documents that [ContinuousClock advances during system sleep](https://developer.apple.com/documentation/swift/continuousclock),
while [Dispatch uptime excludes sleep](https://developer.apple.com/documentation/dispatch/dispatchtime).
Android documents that [elapsedRealtime includes deep sleep and nanoTime uses uptime](https://developer.android.com/reference/android/os/SystemClock).
The [Linux timerfd contract](https://man7.org/linux/man-pages/man2/timerfd_create.2.html)
provides absolute BOOTTIME timers without the wake-alarm clock's capability.
[Bionic exposes timerfd from API19](https://android.googlesource.com/platform/bionic/+/234dc36ac/libc/include/sys/timerfd.h);
the app's minimum API is 26. Listener-based Android alarms are not used because
their [lifetime is bound to running components](https://developer.android.com/reference/android/app/AlarmManager).

## Verification

Full JVM/build-v4: 3,519 total in 472 suites, 3,515 passed, four existing SSH
skips, zero failures/errors. Both APKs build. All 22 native library hashes remain
identical to the prior checkpoint; ELF LOAD/RELRO and APK ZIP alignment pass.
The six-case focused API36 run passes in 47.37 seconds. The full API36 run passes
297/297 in 559.119 seconds, including six new methods and 291 regressions.
Both runs bind identical source inputs and installed app/test APK hashes, have
empty crash logs and no preflight ANR, restore settings/window and observe owned
emulator exit 0. The final 472-suite full JVM XML is unchanged.
Evidence: `captures/recovery-20261025/continuous-checkpoint.json`. The earlier v1 focused JVM checks and v3 full build passed their
then-current sources. Review before v4 added the explicit uptime retry clock and
kept a failed timer's still-retiring operation gated; no source timeout or
assertion was loosened.
Android fixtures inspect the actual kernel timer's clock ID, shared descriptor,
absolute expiry, cancellation cleanup, store clock, currentTokens bootstrap budget
and continued delivery after a stopped Activity. They use a disposable encrypted
document and no production account or network. Controlled clock advancement is
separate from actual device suspend; stopped Activity delivery does not prove
deep-sleep or Doze acceptance.

## Remaining work

This change covers phases run through production `NativeAuthWork.run`. Other
timeout wrappers still need reconciliation, including sign-in/preflight joins,
sign-out teardown and outer controller/network budgets. Complete original nested
phase admission, finish/high-water cleanup and post-publication flow wiring remain
open. No new hard bound is placed on synchronous storage IO. Actual Pixel suspend,
process death and wider provider/terminal lifecycle acceptance remain required.

Android's additional deletion-receipt prerequisite, authentication-project reset/
scoping, remaining backend/schema/metadata/form/callback/identity/worker/diagnostic
behavior, matched full UI/accessibility, real Pixel/Mac/helper/Firebase/Cloud/VPN
and signed delivery remain open. Full parity stays active. Real account, pairing
and signing data, global pins and frozen signed722 are preserved.
