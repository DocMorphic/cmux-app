# Private web-notice engine experiment

This standalone Android test project evaluates GeckoView for the iOS notice
contract: private storage, independent page contexts, and HTTP-only web-session
cookie seeding. It is **not a dependency of cmux-app**, not included by the root
Gradle settings, and not a replacement APK for the user's signed-in app.

The experiment is unfinished. Do not adopt the engine merely because it starts:
the stock pinned AAR fails this project's 16 KB RELRO gate for ten libraries.

## Pinned build

- GeckoView `157.0.20260924084938` from Mozilla's official Maven repository.
- AGP 9.1.1, Gradle 9.3.1 (distribution checksum pinned), JDK 17, compile SDK 37,
  build tools 36.0.0, target 36/min 26, arm64 only.
- Independent wrapper: the delivered app keeps AGP 8.13.2 / Gradle 8.13 / SDK 36.

The first attempt using the root toolchain failed dependency metadata checks:
GeckoView requires compile SDK 37 and its AndroidX Core dependency requires AGP
9.1+. No metadata gate was disabled and no dependency was forced to an older API.
See the [official AGP compatibility table](https://developer.android.com/build/releases/agp-9-1-0-release-notes).

```sh
JAVA_HOME=/path/to/jdk17 ANDROID_HOME=/path/to/android-sdk \
  ./notice-spike/gradlew -p notice-spike assembleDebug assembleDebugAndroidTest \
  --max-workers=1 --no-daemon
adb -s DEVICE install -r notice-spike/build/outputs/apk/debug/cmux-notice-engine-experiment-debug.apk
adb -s DEVICE install -r notice-spike/build/outputs/apk/androidTest/debug/cmux-notice-engine-experiment-debug-androidTest.apk
adb -s DEVICE shell am instrument -w -r \
  -e class io.github.docmorphic.cmuxapp.noticespike.PrivateCookieProbe \
  io.github.docmorphic.cmuxapp.noticespike.test/androidx.test.runner.AndroidJUnitRunner
```

Run only after building, on the existing AVD. The package is
`io.github.docmorphic.cmuxapp.noticespike`; it has no access to cmux-app's account
storage. Do not uninstall or clear the real app. The test uses a loopback server,
synthetic `notice_probe=a/b` values, and generated page-context identities.
Cleartext is enabled **only in this isolated experiment** for its loopback fixture.

## Cookie routes

The default route uses the documented `browser.cookies.set` with the store ID
reported by `browser.tabs.query`. The corrected fixture waits for each exact
navigation before probing. On the existing API 37 / 16 KB arm64 emulator:

- Both private tabs report `firefox-private`, despite different session contexts.
- The API accepts both HTTP-only cookie writes.
- Neither context sends the cookie on its real HTTP request. The acceptance test
  therefore **fails**, as it should. This is not a viable seeding implementation.

The pinned AAR's `omni.ja` implementation confirms that its public cookie parser
sets `privateBrowsingId` / `userContextId`, without copying the session's
`geckoViewSessionContextId`.

An experimental route can be selected with `-e cmux_cookie_api scoped`. It uses a
bundled privileged extension to read the selected tab's actual origin attributes
and seed only that private context. Its API accepts only the loopback fixture,
two fixed synthetic values and an owned `about:blank#notice-...` private tab.
There are no content scripts, external-message endpoints or arbitrary-code APIs.
Runtime configuration files and remote debugging are disabled. This relies on
Gecko internals and needs validation against every adopted engine update.

The scoped route passed **1 test in 14.675 s** on API 37 / 16 KB arm64 with
`pageSizeCompat=0`: the observed attributes contained `privateBrowsingId=1` and
distinct `geckoViewSessionContextId` values; the real requests carried exactly
`notice_probe=a` and `notice_probe=b`. No Gecko preference or page-size check was
relaxed. The corrected public route failed in 18.995 s with no cookie on either
request. Its failure is retained as evidence that the simpler API is insufficient.
The scoped route's debug/test build passed in 8 s after the standalone toolchain
had been established. These are headless session/request checks, not visual or
physical-device acceptance and not proof of all storage-lifetime properties.

Each run exports observations to the experiment package's external files directory
as `private-cookie-probe.json`, including on an assertion failure. Check the
instrumentation summary, not only adb's exit status. A failed earlier fixture
mistook the initial `about:blank` completion for the requested navigation; that
log is retained separately from the corrected public-API failure.

## Adoption gates

Before integrating: pass native LOAD/RELRO and ZIP alignment, verify HTTPS/Secure
cookie handling and page-script exclusion, cookie/localStorage/IndexedDB/cache
lifetime through close and process death, unrelated context preservation, account
replacement, cancellation and update compatibility. Then connect navigation and
theme policy, actual rendering and bounded preload. This experiment does not
establish any of those untested behaviors.

The initial arm64 debug APK is 196,989,177 bytes, with 175,111,152 bytes of native
libraries. That is a separate experiment APK, **not measured growth of cmux-app**.
APK ZIP alignment passed; three native libraries passed LOAD/RELRO and ten failed
RELRO. Runtime startup does not waive the failed packaging gate.

Mozilla's POM identifies MPL 2.0 and source revision
`8eb25af4acf031ab1e06abf1a912275083c820ed`. Production adoption must include the
engine's full bundled third-party notices and source/license obligations, not only
the top-level MPL link. No engine binary is committed or published by this project.

Evidence is ignored under `captures/runtime/notice-gecko/`. See
[the main web-notice checkpoint](../docs/WHATS_NEW_WEB.md).
