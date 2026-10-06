# Windows development and device checks

The Android project builds from the same branch on Windows and macOS. The remote
host remains the user's cmux Mac: the pinned upstream cmux app is Swift/AppKit
and macOS-only. Windows host control was considered and explicitly removed from
the continuation scope. Windows support here means development and testing.

## Build from PowerShell

Install JDK 17, Android SDK platforms 37.0 and 36, build-tools 36.0.0 and platform-tools.
Set the paths for the current machine; do not copy the handoff Mac paths:

The commands below fetch all five current native dependencies. The Ghostty
placeholder bridge requires the checkpoint described in
[GHOSTTY_VT_ANDROID.md](GHOSTTY_VT_ANDROID.md#app-build-dependency);
`36637832054` is an older incompatible checkpoint. The simulator decoder is
described in [SIMULATOR_STREAMING.md](SIMULATOR_STREAMING.md).
Windows builds consume the verified arm64 artifacts; the native-source build
scripts currently run on Linux/macOS.

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-17'
$env:ANDROID_HOME = 'C:\path\to\android-sdk'
$env:Path = "$env:ANDROID_HOME\platform-tools;$env:Path"
gh run download 36539047261 --repo DocMorphic/cmux-app --name cmux-iroh-android-arm64 --dir build/iroh-android
gh run download 36539507313 --repo DocMorphic/cmux-app --name cmux-graphics-path-android-arm64 --dir build/graphics-path-android
gh run download 36642877666 --repo DocMorphic/cmux-app --name cmux-ghostty-android-arm64 --dir build/ghostty-vt-android
gh run download 36653485468 --repo DocMorphic/cmux-app --name cmux-simulator-video-android-arm64 --dir build/simulator-codecs-android
gh run download 37454996751 --repo DocMorphic/cmux-app --name cmux-cloud-terminal-android-arm64 --dir build/cloud-terminal-android
.\gradlew.bat --no-daemon --max-workers=1 :app:testDebugUnitTest
node --test bridge/*.test.mjs
python scripts/verify-viewer-assets.py
```

`gradlew.bat` uses the same committed
wrapper JAR/properties as `./gradlew` on macOS. The main build uses AGP 9.1.1,
Gradle 9.3.1 and Kotlin/Compose compiler 2.4.20; SDK 36 preserves only the
Android 8 fingerprint adapter. See [ANDROID_TOOLCHAIN.md](ANDROID_TOOLCHAIN.md). The build explicitly selects
build-tools 36.0.0, matching the SDK installed by CI. `.gitattributes` keeps source
and vendored assets LF on both hosts (the Windows launcher uses CRLF), preserving
the pinned upstream hashes even with Git's Windows `core.autocrlf=true` default.
No release signing configuration
changes are needed. Use the existing GitHub workflow/secrets for signed milestones;
local versionCode 2 is not an upgrade over published build 494.

The app requires pinned Iroh, rebuilt graphics-path, Ghostty, simulator-video and Cloud dependencies. Move old
checkpoint directories aside before downloading the replacements above. Gradle
verifies their receipts and hashes, including the new 16 KiB RELRO build marker.
Cloud also verifies adapter/source hashes and its dependency notice inventory;
its receipt validation runs inside Gradle and does not require Python on Windows.
Use the manual `cloud-native.yml` workflow to regenerate an expired Cloud artifact.
See [IROH_V2.md](IROH_V2.md#android-native-module) and
[graphics-path](../third_party/androidx-graphics-path/README.md) to reproduce
expired checkpoints via Linux/macOS or the native CI jobs. The current native APK is arm64;
the older x86-64 emulator evidence below predates this dependency. Use the Pixel
or an arm64 target for current runtime checks until x86-64 native support is built.

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

The historical Windows run used Android 17 x86-64 package
`system-images;android-37.0;google_apis;x86_64`; it cannot run the new arm64 native APK.
Check `emulator -accel-check` before
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

The user subsequently explicitly approved native mobile pairing on the original
Mac. It is enabled and the cmux 0.64.25 panel reached Iroh Ready. That host exposes
V2 account/Iroh pairing, with no legacy Tailscale QR/TCP route. See the corrected
plan and evidence in [IROH_V2.md](IROH_V2.md); do not reapply the historical handoff
restriction or silently substitute a legacy host.

The prepared test path is: connect the Pixel by USB-C and authorize debugging;
install a verified milestone; sign in to the same cmux account/team, enroll the
Android installation and connect to the authorized Mac through Irx. Then open a
test workspace, exchange a harmless unique terminal marker, interrupt/restore
the phone connection and verify no duplicate input, and trigger/open a test
notification. Record device/cmux/APK versions and results at each stage. Never
put credentials or private pairing URLs in logs, commits or PR text.
