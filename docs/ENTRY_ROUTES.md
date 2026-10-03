# Pairing and notification entry routes

Checkpoint: 2026-09-30, following `4a92c5b` workspace process restoration.

## Route lifetime

`MainActivity` saves a bounded `NativeLaunchRoutes` value alongside Android's
saved-instance state. It includes an explicit empty value after consumption.
A restored task therefore does not reread its original pairing URI after the
user handled it. A fresh recognized intent delivered through `onNewIntent`
replaces the pending entry route; launcher reentry and unrelated intents leave
an unresolved route alone. Notification and pairing routes are mutually exclusive.
A late acknowledgement for an older value cannot clear a newer route.

The installed manifest now registers `cmux-android://attach` and the fixed
`cmux-ios-dev.cmux.ios://attach` scheme, alongside the existing stable iOS schemes.
Previously the parser accepted those two schemes but Android would not dispatch
an implicit URL to the app. Dynamically suffixed development schemes can still be
parsed by the in-app scanner/paste path; they are not wildcard manifest schemes.

Only validated public pairing coordinates or an opaque notification route UUID
are saved. Credentials remain in their existing encrypted stores. Notification
intents still carry only the UUID; their destination comes from the encrypted
notification ledger. Malformed, oversized or unknown-version saved route state
is discarded. Existing saved notification state from the previous Activity
format is accepted without rereading an old pairing intent.

This follows Android's separation of
[Activity intent delivery](https://developer.android.com/reference/android/app/Activity#onNewIntent(android.content.Intent))
and [saved UI state](https://developer.android.com/topic/libraries/architecture/saving-states).
Saved-instance state covers system task restoration after the save/stop lifecycle;
it is not a durable global launch history or a guarantee after data clearing.

## Admission and visible confirmation

Pairing links received while signed out wait for sign-in. A Tailscale link stages
the existing explicit confirmation dialog; receiving or restoring the link does
not authorize access. The dialog itself now survives Activity/process recreation.
Once staged, the launch route is consumed, so dismissing the restored dialog does
not make it reappear on the next task restoration. A new recognized link can
replace the prompt; a notification tap dismisses it and takes navigation priority.

An Iroh link waits for the current account scope and its ready computer directory.
It matches endpoint, optional device, optional build and account/team hints before
selecting the directory's canonical computer code. An incomplete or previous-scope
directory is not evidence that the Mac is missing. A ready directory with no unique
matching Mac produces the existing unavailable message. Selection still passes
through the connector's admission and fresh authenticated connection checks.

Notification routing likewise waits for initial account admission before judging
whether the saved Mac is available. A pending entry route takes priority over the
workspace checkpoint. Once a notification has been handled and the user chooses
a different terminal, subsequent process restoration restores that newer choice.

## Verification

The private debug process fixture now subclasses `MainActivity`, retaining its
real intent and saved-state lifecycle while replacing only the Mac connector with
an emulator-local peer. Its emulator guard runs before the production Activity's
`onCreate`. Fixture setup clears synthetic credentials and disables the background
service; these tests must never run on the physical Pixel.

- **24 JVM tests passed**: eight route lifetime/admission cases, ten workspace
  checkpoint cases and six pairing parser cases.
- **22 Android 17 / 16 KiB tests passed in 162.456 seconds** before the final
  manifest-only registration: seventeen real-process/entry cases and five existing
  notification delivery regressions. All twelve previous process recovery cases
  now run through the production Activity lifecycle.
- The five added runtime cases check consumed original links versus selected-pane
  recovery; pending confirmation, dismissal and two process deaths; newer links,
  repeated links and same-PID `onNewIntent`; signed-out deferral through launcher
  reentry and process death; and notification priority followed by a different
  terminal selection surviving process death.
- After the manifest registration, **three installed-manifest resolver tests
  passed in 0.022 seconds** on API 37 with a verified 16,384-byte page size. All
  eight fixed pairing schemes resolve to MainActivity, unrelated schemes/hosts
  do not, and notifications retain their explicit Activity target without a
  public scheme registration. Both final APKs built successfully.
- Ignored evidence: `captures/runtime/entry-routes/` includes build/runtime logs,
  JVM XML and separate hashes for the lifecycle batch and final manifest build.
  No runtime failures occurred. The owned emulator was stopped after testing.
  The Pixel remained absent from ADB; no phone data/settings were changed.

## Remaining acceptance

Physical Pixel/Mac URL delivery, live Iroh directory startup and real system-shade
taps still need account/device acceptance. These route fixes do not supply FCM or
server push. The broader foreground/background, Doze and boot-delivery work remains
tracked in `PARITY.md`. Signed build 261 predates this checkpoint.

Final debug APK SHA-256: `e62b09d6cf6c5272e2ab6326b6fbc50cb6031e12416949f94571451dc7859c4d`.

Final test APK SHA-256: `d563869063fd5c0e8c8808b9d7a05206af5c0e8004023f5c6e0d2661c7971e86`.
