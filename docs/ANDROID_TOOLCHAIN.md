# Android build toolchain — 2026-10-04

The main app now uses the toolchain required by the pinned GeckoView
candidate. This migration preceded the production integration recorded in
[NOTICE_RENDERER.md](NOTICE_RENDERER.md). GeckoView is now a main dependency;
synthetic certificate fixtures remain excluded.

## Pins and source configuration

- Gradle 9.3.1 with its distribution SHA-256 in the wrapper properties.
- AGP 9.1.1; compile SDK 37 (SDK package `platforms;android-37.0`).
- Kotlin Gradle and Compose compiler 2.4.20, using AGP's built-in Kotlin support.
- JDK 17, build-tools 36.0.0; min SDK 26 and target SDK 36 unchanged.
- Native Iroh/Ghostty/graphics/video source revisions and receipts unchanged.

These versions follow the official [AGP compatibility table](https://developer.android.com/build/releases/agp-9-1-0-release-notes)
and [Kotlin compatibility table](https://kotlinlang.org/docs/gradle-configure-project.html).
The explicit KGP buildscript dependency selects the compiler for
[AGP's built-in Kotlin](https://developer.android.com/build/releases/agp-9-0-0-release-notes#runtime-dependency-on-the-kotlin-gradle-plugin).
The obsolete Android Kotlin plugin and `kotlinOptions` blocks are removed;
the Java target supplies Kotlin's JVM target.

Iroh's externally generated Kotlin bindings use the Kotlin source directory set.
Its generated license assets use a typed task output wired through
`androidComponents.onVariants`, preserving `assets/licenses/iroh/` in the APK.
This fixes the first migration build's rejection of a Provider in the old source
set API. No source-set validation guard was disabled.

## Android 8 fingerprint fallback

The SDK 37 public stubs no longer expose `android.hardware.fingerprint`.
The existing API 26–27 branch therefore cannot compile in the SDK 37 app module.
Inspection of AndroidX Core 1.19.0's actual bytecode also showed its deprecated
`FingerprintManagerCompat` hardware/enrollment methods return false and its
authentication methods return without doing anything. Switching to that class
would not preserve the existing fallback when the newer Core dependency arrives.

`legacy-biometric` keeps the original platform implementation in a small Android
library compiled against SDK 36. Install that SDK package as well. Its API exposes
only Context, CancellationSignal, Signature and callbacks, and checks API 26–27
before accessing FingerprintManager. The app calls it only from that guarded
branch. The original exact Signature operation, help/failure UI, cancellation
signal and stale-activity callback guard remain. API 28+ retains the system
BiometricPrompt path. This is not a new authentication bypass or a raised minimum
Android version. Physical biometric authentication still needs device acceptance.

## Verification

Evidence and build diagnostics are retained under
`captures/runtime/main-toolchain/` (ignored). The initial migration attempts
exposed the source-provider and removed-fingerprint-API issues above; their failed
logs are separate from the final verification. Signed milestone 494 is unchanged.

The first successfully assembled release still failed the existing ART gate:
the generated root-route lambda in `NativeScreenKt` produced a `VerifyError`
(`copy-reference ... type=BooleanConstant`). JVM tests and four notice UI tests
passed on that candidate, demonstrating why neither substitutes for the release
runtime check. Nine existing route bodies now live in separate composable
`ColumnScope` lambdas created outside the dispatcher. Route ordering, callbacks
and lifecycle owners remain in place; the local-browser slot explicitly requires
the browser already admitted by the dispatcher. The revised release passes ART
loading/reflection of `NativeScreenKt` (1,314 methods).

SDK 37 also marks recent-task information nullable. Emulator process-restoration
fixtures now skip missing task records during lookup/cleanup, retain the observed
task ID during the stop/kill sequence, and require the same ID after restoration.

Native receipt/source pins are unchanged, but the new packaging toolchain strips
additional non-runtime symbol information. Whole-file hashes therefore differ
from milestone 494. All six libraries retain identical allocated ELF sections
(names, addresses, flags, types, sizes and content hashes), including executable
code and dynamic symbols. Both final APKs pass native and 16 KB ZIP alignment;
all 14 viewer assets and all three Iroh notice files match their pinned sources.
No notice experiment CA or test key is included in either main APK.

### Final local results

- Debug app, instrumentation APK and unsigned release assembled successfully after
  the route split (2 min 17 s). Optional SSH spike test APK also compiled during
  migration; it was not exercised.
- Final JVM run: **1,539 passed, 4 skipped**, no failures/errors. The skips require
  external npm/cmux-tui/Chrome/tmux fixtures; they are not runtime acceptance.
- Existing API 37 / 16 KB arm64 AVD: terminal process restoration **1 passed,
  15.451 s**; browser restoration through fresh discovery **1 passed, 12.212 s**;
  native notice UI **4 passed, 22.993 s**. These use synthetic local fixtures.
- Release ART class loading passed. Debug cold launch reached the signed-out
  screen without a crash (2.482 s); `pageSizeCompat=0`.
- The archive screenshot initially captured a window transition. Repeating that
  single test with animations temporarily disabled passed in **3.547 s** and
  produced a readable archive-detail capture. Original animation settings were
  restored. This is a repeat of one of the four UI cases, not a seventh case.
- The one existing emulator was stopped and reaped. No new AVD was created.

APK hashes, final XML counts, logs and screenshot paths are recorded in the ignored
`captures/runtime/main-toolchain/verification.json`. No physical Pixel, live Mac
browser flow, authenticated upgrade or legacy biometric operation was verified.
There is no new signed milestone: build 494 remains the last verified signed APK.
The private notice renderer still needs production integration, scoped lifecycle
and partitioned-state checks, notices/package review and device acceptance.
