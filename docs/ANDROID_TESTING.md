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
adb shell am instrument -w -r -e class io.github.docmorphic.cmuxapp.NativeFlowTest io.github.docmorphic.cmuxapp.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Check the instrumentation output for `OK (4 tests)`; the `adb` exit code alone
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
