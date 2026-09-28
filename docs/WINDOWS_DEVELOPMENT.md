# Windows development and device checks

The Android project builds from the same branch on Windows and macOS. The remote
host remains the user's cmux Mac: the pinned upstream cmux app is Swift/AppKit
and macOS-only. Windows host control was considered and explicitly removed from
the continuation scope. Windows support here means development and testing.

## Build from PowerShell

Install JDK 17, Android SDK platform 36, build-tools 36.0.0 and platform-tools.
Set the paths for the current machine; do not copy the handoff Mac paths:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-17'
$env:ANDROID_HOME = 'C:\path\to\android-sdk'
$env:Path = "$env:ANDROID_HOME\platform-tools;$env:Path"
.\gradlew.bat --no-daemon --max-workers=1 :app:testDebugUnitTest
node --test bridge/*.test.mjs
python scripts/verify-viewer-assets.py
```

`gradlew.bat` is the standard Gradle v8.13.0 launcher and uses the same committed
wrapper JAR/properties as `./gradlew` on macOS. The build explicitly selects
build-tools 36.0.0, matching the SDK installed by CI. `.gitattributes` keeps source
and vendored assets LF on both hosts (the Windows launcher uses CRLF), preserving
the pinned upstream hashes even with Git's Windows `core.autocrlf=true` default.
No release signing configuration
changes are needed. Use the existing GitHub workflow/secrets for signed milestones;
local versionCode 2 is not an upgrade over published build 157.

At a combined runtime milestone, build before starting the emulator:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 :app:assembleDebug :app:assembleDebugAndroidTest
adb devices -l
python scripts/check-handoff-runtime.py --serial emulator-5554 --install
```

Choose the actual serial deliberately. The runner installs only the separate
`.debug` app and test package, checks foreground focus, runs the four exact
handoff methods, and requires `OK (4 tests)`. It records source/device metadata,
instrumentation output and PNGs under ignored `captures/handoff/`. PNG bytes use
Python subprocess pipes, avoiding binary corruption from older PowerShell text
redirection. Inspect the screenshots; a successful fixture run does not prove
the real account, VPN, Mac, Windows host, or physical Pixel workflow.

For Android 17 x86-64, check the SDK package list: current package naming uses
`system-images;android-37.0;google_apis;x86_64`. Check `emulator -accel-check` before
launch. Use a cold boot without saving/restoring snapshots when diagnosing ANRs.
Never dismiss an ANR by relabeling the test as passed. Do not stop unrelated apps
to free memory; build and run the owned emulator sequentially on constrained hosts.

## Windows continuation checkpoint (2026-09-28)

Cloned the existing branch at handoff commit `6711ef4`. Windows 11 / AMD x86-64,
Temurin 17 and the same Gradle wrapper run the project without changing signing.
All four helper tests pass on the installed Node 24.13.1 (CI remains Node 22).
The first JVM run passed 306 of 307 cases; `TerminalArtifactCountTest` failed
because `Paths.get` interpreted remote Mac paths with Windows filesystem rules.
The detector now classifies root aliases lexically with POSIX components while
preserving the original path for host resolution. The unchanged 33-case Swift
path fixture and 6,400 count transitions pass, along with an added path regression.
The full rerun passes **308 JVM cases, zero failures/errors/skips**.

Windows checkout conversion initially broke two raw-syntax hashes. With LF
checkout rules, all **14** raw-code/Markdown vendor hashes match their manifests.
CI now checks these hashes before building. These changes do not establish the
four pending Android runtime cases or physical-device acceptance. The latest
published signed APK remains build 157 until a separate verified milestone.

## Real Mac acceptance gate

The existing `docs/HANDOFF.md` restriction still applies: the native listener on
58465 was left off after an automatic approval review rejected enabling it.
The detailed rejection reason was not retained. Cloning, setting up Windows, or
permission to continue development does not authorize bypassing that restriction.

The prepared test path is: connect the Pixel by USB and authorize debugging;
connect Pixel and Mac to their existing tailnet; install a verified signed
milestone; sign in to the same cmux account; after explicit approval/user action
enable Mobile pairing on the Mac and scan its Tailscale QR locally. Then open a
test workspace, exchange a harmless unique terminal marker, interrupt/restore
the phone connection and verify no duplicate input, and trigger/open a test
notification. Record device/cmux/APK versions and results at each stage. Never
put credentials or private pairing URLs in logs, commits or PR text.
