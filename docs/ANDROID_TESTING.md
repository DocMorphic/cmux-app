# Android runtime checks

The instrumented `NativeFlowTest` runs the production Compose screens and framed
RPC client against an emulator-local TCP peer. It covers workspace filtering,
terminal navigation, keyboard viewport resizing, paste/submit delivery, and
viewport cleanup. A second flow checks multiline drafts, separate terminal
drafts, encrypted persistence before sending, rejection and explicit retry,
and insertion without submission. A third flow intercepts the system document
picker result with local photo/file fixtures, exercises the real import and
image preparation, checks encrypted staging/restoration, and verifies image
acknowledgement plus explicit file retry after a rejected message. It does not
automate a cloud document provider. A fourth flow switches between the composer
and native direct keyboard, verifies keyboard visibility and viewport resizing,
and drives Android InputConnection composition/commit/delete/key APIs. It checks
ordered RPC bytes, rejection followed by explicit resume without replaying queued
input, preserved composer drafts, and invalidation of old input connections.
It does not drive a real CJK keyboard candidate picker or a live terminal app.
Two `RenderGridRenderingTest` checks use the device's ICU tables and production
Canvas painter. They cover combining accents, CJK, flags, emoji modifiers/ZWJ,
ambiguous/narrowed widths, identical pixels for mixed versus separately placed
clusters, palette backgrounds, invisible text and a wide underline cursor.
`terminal-unicode-rendering.png` records the actual rendered result.
A fifth flow selects a raw-byte-only host, sends fragmented UTF-8 and ANSI
output through framed RPC, checks duplicate suppression and alternate-screen
restoration, forces a byte-sequence gap and verifies replay recovery, then
scrolls into local history and back without a viewport change. It also confirms
that parser device-query responses do not become terminal-input RPCs.
Three further flows cover terminal touch and copying: the iOS-style View as Text
sheet, native Android selection handles and selected-text copying, Copy All,
long-press/menu entry, screen-anchored primary scroll isolation, alternate-screen
wheel/click RPCs, and applying an older host's viewport-anchored scroll response.
`terminal-text-selection.png` shows the native selection handles. Geometry and
scroll queue JVM tests also cover coalescing during a delayed acknowledgement,
prefetch cadence, failure without replay, and cancellation when leaving a surface.
Two search/notification flows verify separate workspace and notification queries,
search submission/clearing, group/computer/description metadata, accent/width
matching, retained filters after returning from a terminal, and moved-surface
routing with a read acknowledgement. Missing destinations stay in the feed and
are not marked read. `notification-search.png` and `notification-unavailable.png`
record these states. JVM tests additionally check late edits after cancellation,
128-scalar/512-byte input bounds, duplicate/nullable feed fields, ambiguous
surface ownership, and row headline/preview derivation.
Two alert-navigation flows switch from one saved fixture Mac to another, verify
exact workspace/terminal RPC targets, reopen the same route after consumption,
and reject a forgotten computer without sending a read or terminal request.
Four `NativeNotificationDeliveryTest` checks exercise real Keystore encryption,
NotificationManager and PendingIntent identity (including colliding string hashes
and identical IDs across Macs), read/forget cleanup, late-feed suppression,
intent validation, sibling-installation storage, host-identity mismatch rejection,
and foreground-service start/stop with immediate connection status.
The persisted destination is a random opaque ID; Android intents contain no
pairing route, credential, workspace or terminal ID. JVM ledger checks cover
per-Mac baselines, persistence reconstruction, unacknowledged post retry, bounded
history/route eviction and retained moved-surface provenance. A socket test
exercises the actual event-driven feed worker and disconnect handling.
These do not yet prove delivery during physical-device sleep, boot recovery,
notification-shade taps through Activity process death, or real tailnet reconnects.
The peer uses synthetic data and never connects to a real cmux account.
This check does not prove account sign-in, Tailscale routing, or compatibility
with the live Mac; those remain in [PIXEL_INSTALL.md](PIXEL_INSTALL.md).

Debug builds use the `io.github.docmorphic.cmuxapp.debug` package. Their encrypted
credentials and preferences are separate from the release app. The test clears
only debug credentials and drafts when it finishes.

With a running Android emulator and JDK 17:

```sh
./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest
```

On a Mac with limited RAM, build before starting the emulator:

```sh
./gradlew --no-daemon --max-workers=1 :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e class io.github.docmorphic.cmuxapp.NativeFlowTest,io.github.docmorphic.cmuxapp.RenderGridRenderingTest,io.github.docmorphic.cmuxapp.NativeNotificationDeliveryTest io.github.docmorphic.cmuxapp.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Check the instrumentation output for `OK (18 tests)`; the `adb` exit code alone
does not distinguish failed tests. Screenshots are saved to the debug app's
external files directory and can be retrieved after the test:

```sh
adb pull /sdcard/Android/data/io.github.docmorphic.cmuxapp.debug/files/screenshots captures/emulator
```

CI runs the JVM tests, builds the app and instrumentation APKs, and verifies the
release signature. Emulator tests are run separately; compilation is not a
runtime pass. Espresso 3.7.0 is explicit because earlier transitive versions use
an input API removed in Android 17; see the [AndroidX Test release notes](https://developer.android.com/jetpack/androidx/releases/test).

The flow was exercised on an ARM64 Android 17 (API 37) emulator with the Pixel
6a display profile. Its cold boot initially hit System UI and Google Play
services startup failures; the completed run used a recovered, idle emulator.
Do not count runs with an ANR dialog, process crash, timeout, or `FAILURES!!!`
as passing. Inspect the saved screenshots as well as the assertions.


## Captured Vim regression

The JVM suite includes `terminal/vim-session.json`, captured from a local Vim
9.1 process attached to a real PTY. It contains opening, inserting text into a
Unicode document, and exiting. The test feeds it to the production native parser
in seven-byte pieces and checks edited text, the alternate screen and restoration
of the primary screen. Recreate the fixture on a Mac with `/usr/bin/vim`:

```sh
python3 scripts/capture-vim-fixture.py
```

The script creates and removes its own temporary directory and disables Vim
configuration, swap and history files. This checks genuine Vim output but does
not verify live input latency, cmux authentication or a physical Pixel connection.
