# Notice content-process failure acceptance — 2026-10-04

This follows [partition cleanup](NOTICE_PARTITIONS.md) and verifies the delivered
renderer after an actual Gecko content-process failure. The existing production
`onCrash` and `onKill` handlers retire the page. The archive now says that the page
could not be loaded instead of attributing every failure to internet connectivity.

## Fault injection and acceptance

`NativeNoticeCrashFixture` lives only in `androidTest`. It loads Mozilla's
`about:crashcontent` using the test-only bypass flag so the production navigation
allowlist does not prevent deliberate fault injection. The production policy and
engine settings remain unchanged. It forwards the actual termination callback to
the original production delegate before recording the callback type. It does not
invoke a callback itself or simulate page closure.

Mozilla uses the same URI in its [content delegate tests](https://github.com/mozilla-firefox/firefox/blob/main/mobile/android/geckoview/src/androidTest/java/org/mozilla/geckoview/test/ContentDelegateTest.kt),
whose crash-callback test excludes isolated processes. The exact bundled 157 AAR's
bytecode and the actual device behavior were inspected; current upstream examples
are not treated as proof of this binary's behavior.

Two Android tests exercise different requirements:

- `NativeNoticeRendererTest.contentCrashRetiresPrivateStateAndFreshAttemptUsesSameRuntime`
  seeds a synthetic HttpOnly cookie and localStorage through the production
  renderer, triggers the native crash, waits for the real termination callback,
  and checks retirement. A fresh renderer completes a new exchange using the same
  engine and Android host process. Reopening the **exact old context** finds no
  cookie or localStorage value. The new session receives only its new cookie.
- `NativeNoticeFlowTest.archiveContentCrashShowsRetryAndRendersFreshPageWithoutAcknowledging`
  displays an actual archive page, crashes its content process, checks the visible
  error and Retry action, taps Retry, and checks rendered pixels afterward. It
  requires a different renderer and exactly one additional exchange. The archive
  does not write a launch acknowledgement, and Back remains functional.

## Results

Final debug/test builds pass (`build.log`, `build-2.log`). The two final Android
cases pass in **52.278 seconds**, on the existing API 37 / 16 KB arm64 AVD. Both
native failures are visible as SIGSEGV in isolated content processes in logcat;
the production app process survives. Both reach **`onKill`** on this engine/device
configuration. This is not claimed as runtime coverage of `onCrash` itself.

The initial run passed the existing extension-recovery case but failed these two
new cases because the fixture waited only for `onCrash` (75.776 seconds, one pass /
two failures). The revised fixture records either documented termination callback;
it still requires all retirement, cleanup, fresh-session and UI assertions. The
initial logs remain available and are not counted as passes.

Evidence is under ignored `captures/runtime/notice-content-crash/`: runtime logs,
`device/renderer.json`, native crash logs, inspected callback bytecode, and before /
after Retry screenshots. The recovered screenshot was visually inspected. Debug
engine packaging checks pass. DEX inspection confirms that the injector class is
present in the test APK and absent from the app APK.

The existing emulator was stopped after testing. No additional AVD, physical
Pixel install, app-data reset, release build or signed milestone was made here.
The last unsigned-release/ART gate is the preceding `786264d` checkpoint; the only
production change since it is error wording. Build 494 remains the last signed APK.

## Remaining scope

Content-process termination is separate from loss of the Android app process or
Gecko's main runtime. Whole-process recreation, real HTTPS/native-account exchange,
physical Pixel/Mac acceptance, Android feed configuration, push and the broader
parity audit remain open. The stored-data test here covers cookies/localStorage;
IDB/cache partition cleanup is covered separately by NOTICE_PARTITIONS.md, not
by this content-crash test.
