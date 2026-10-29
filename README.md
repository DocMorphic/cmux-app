# cmux-app

An **unofficial Android companion for [cmux](https://cmux.com)**. Connect to your
Mac, open its workspaces and terminal panes, type commands, view output and
scrollback, and reconnect when you return to the app.

**[Download the Android APK — build 750](https://github.com/DocMorphic/cmux-app/releases/download/android-preview-750/app-release.apk)** ·
[Release notes](https://github.com/DocMorphic/cmux-app/releases/tag/android-preview-750) ·
[All downloads](https://github.com/DocMorphic/cmux-app/releases)

The APK is approximately **279 MB**. Open this page on your phone and tap the
APK link. You do not need to clone the repository, build the app, or have a GitHub
account to download it.

> **Preview status:** anyone can try this APK with their own cmux account and Mac.
> Build 750 has automated core-flow and signed-release startup checks. Normal cmux
> host support is implemented, but this exact build has not yet been verified in a
> live phone connection to the normal Mac app. Earlier real Mac/Pixel terminal
> checks used the separate custom Mac build. This is not an official cmux release.

## What you need

- An **Android 8.0 or newer phone with a 64-bit ARM processor** (`arm64-v8a`).
  The current APK does not include 32-bit ARM or x86 native libraries. Pixel 6a is
  the development phone; other compatible phones have not all been tested.
- A Mac running a current **normal cmux release** with mobile pairing support.
  [Download cmux for Mac](https://github.com/manaflow-ai/cmux/releases/latest).
- Your **own cmux account**, with the same account and team selected on both devices.
- Internet access on both devices. Keep cmux running and the Mac awake while
  accessing its terminals.

For the core connection, you do **not** need USB debugging, Android Studio, a
custom DEV Mac build, a separately deployed server, Firebase setup, or a Google
Play developer account. Account sign-in and discovery use cmux's existing services;
this app is not an offline replacement for those services.

## Get started

### 1. Install on your phone

1. Tap **[Download the Android APK](https://github.com/DocMorphic/cmux-app/releases/download/android-preview-750/app-release.apk)**.
2. Open `app-release.apk` from your browser's downloads or the Android Files app.
3. If Android asks, allow **Install unknown apps** for the browser or Files app
   opening this download, then tap **Install**. If you already have this project's
   release app installed, tap **Update** instead.

The app is distributed here as an APK, not through Google Play. Installing the
release APK does not require a computer or command-line tools.

### 2. Enable pairing in normal cmux on your Mac

1. Open the normal **cmux** Mac app and sign in to your cmux account.
2. Open cmux **Settings** and enable **Enable iOS pairing**. The Mac control keeps
   its upstream name; this Android companion uses the same native pairing path.
3. Select the same team you intend to use on the phone, and leave cmux running.

**`cmux DEV android-push` is not required for terminal/workspace access.** That
separate build was used for private Android push experiments. If you previously
paired with it, its saved entry and workspace sessions belong to that installation;
select normal cmux separately rather than expecting the dev entry to switch hosts.

### 3. Sign in and select your Mac on Android

1. Open the Android app and sign in with **your own account**. Use the same account
   and team as the Mac; email-code sign-in is available on the sign-in screen.
2. Open **Computers**, refresh if needed, and select your Mac's normal cmux
   installation.
3. Open a workspace, then its terminal pane. Tap **Keyboard** for direct input;
   use the terminal toolbar for special keys and the pane picker to switch panes.

Native Iroh discovery/transport handles the connection. The normal account-based
path does not require scanning a notification-helper QR code or setting up a
Tailscale network. A paired Mac still needs to be running, awake and reachable.

## Everyday use and updates

Return to the workspace list to switch workspaces. After an app switch or a
network interruption, reopen the app and let it reconnect. If it remains
disconnected, wake the Mac and select the saved Mac again under **Computers**.

To update, download a newer **release APK from this repository** and open it over
the installed app. Releases use the same signing certificate so Android can
update the app while retaining its data. **Do not uninstall the existing release
or clear its data to update.** Avoid installing a lower version code. A debug APK
built from source is a separate development app, not this release's update.

New source commits on `main` do not automatically update an installed phone app.
The download at the top points to the specific published build; check
[Releases](https://github.com/DocMorphic/cmux-app/releases) for later builds.

## Notifications and other optional features

The quick start above covers native Mac workspaces and terminals. Leave
**Push Alerts** off for this basic setup; **Pair Android Notifications** is not a
required pairing step for terminal access.

Build 750 includes Firebase client configuration for the maintainer's private
notification experiment. It does **not** provide an automatically enrolled public
Android push service. Background push, notification replies and the custom Mac
notification helper need separate setup and are outside this public quick start.
There is no need to configure them just to use terminals.

Cloud, billing and full iOS feature/UI parity are also outside this milestone.
See the [historical parity research](docs/PARITY.md) for feature-specific evidence
rather than assuming every exposed experimental feature is generally supported.

## Troubleshooting

| Problem | What to check |
| --- | --- |
| My Mac does not appear | Confirm the same account and team on both devices, **Enable iOS pairing** in Mac Settings, and that cmux is running on an awake Mac. Refresh **Computers**. |
| I see the dev app instead of normal cmux | The installations have separate identities and sessions. Open normal cmux, enable its pairing and select its entry on Android. |
| The Mac appears but cannot connect | Check internet access on both devices and the app's error message. If it asks for a Mac update, update normal cmux and retry the saved entry. |
| Android says the APK is incompatible | Check Android 8.0+ and a 64-bit ARM phone. Download `app-release.apk`, not a debug build or a source-code ZIP. |
| An existing installation will not update | Check that the APK is from this repository and has a higher version code. A different signing key or package cannot update the release app. Preserve its data while investigating the error. |
| I do not receive background push alerts | Public background push is not included in the basic setup; normal terminal access does not require the private notification helper. |

For an unresolved issue, [open a GitHub issue](https://github.com/DocMorphic/cmux-app/issues)
with the phone model/Android version, Android app build, Mac cmux version, exact
error and reproduction steps. Keep passwords, sign-in codes and pairing codes out
of public reports.

## Build 750 verification

- **3,552 unit tests passed**, with four existing skips.
- **26 distinct core Android cases** have passing results across the recorded runs,
  covering native terminal lanes, input/keyboard behavior, saved-Mac selection,
  workspace routing and reconnect/recreation.
- The **exact signed APK** passed six Android runtime class checks and two cold
  starts on the owned ARM64 emulator, with an empty crash buffer.
- The release certificate, packaged assets and 16 KiB native alignment were verified.
  The public download was fetched without authentication and its SHA-256 matched.

These checks do not establish normal-Mac/physical-phone acceptance for this exact
build or compatibility with every Android device. Its production source is
[`40ae5ff7`](https://github.com/DocMorphic/cmux-app/tree/40ae5ff7cb7d5608d346b163da0babebf264cb19);
see the [verification manifest](https://github.com/DocMorphic/cmux-app/releases/download/android-preview-750/manifest.json).

<details>
<summary>Verify the download (optional)</summary>

Build 750: package `io.github.docmorphic.cmuxapp`, version `0.2.0`, version code
`750`, size **278,853,630 bytes**.

- [SHA256SUMS](https://github.com/DocMorphic/cmux-app/releases/download/android-preview-750/SHA256SUMS)
- APK SHA-256: `0df4f59e1d9bf5b4b2e3150764a54de1b0b5cb8952a0a2dab81438513fffec3e`
- Signing certificate SHA-256: `1118815b3831ae18005306c10bb0953a6fc2e9e20d14407223da52cda971abd4`

On a Mac, `shasum -a 256 app-release.apk` prints the APK checksum. The checksum is
for this exact build; a newer release has its own manifest and checksum.

</details>

## For developers

Building from source is optional. Start with [HANDOFF.md](docs/HANDOFF.md) for the
implementation map and current scope. Historical investigation and owner-specific
physical test records are in [RESEARCH.md](docs/RESEARCH.md),
[IROH_V2.md](docs/IROH_V2.md) and [PIXEL_INSTALL.md](docs/PIXEL_INSTALL.md).
[DAILY_USE.md](docs/DAILY_USE.md) records the delivery milestone. Those test histories
are not prerequisites for another person's basic installation.

<details>
<summary>Build from source</summary>

Clone the project, then prepare the dependencies below:

```bash
git clone https://github.com/DocMorphic/cmux-app.git
cd cmux-app
```

### Build prerequisites

Install Android Studio with Android SDK platforms 37.0 and 36, build-tools 36.0.0 and JDK 17.
The wrapper pins Gradle 9.3.1; the build uses AGP 9.1.1 and Kotlin/Compose compiler 2.4.20.
SDK 36 is used only by the Android 8 fingerprint adapter; see [toolchain notes](docs/ANDROID_TOOLCHAIN.md). The app now includes its
native Iroh, rebuilt graphics-path, Ghostty VT, simulator-video and Cloud dependencies.
Obtain the five reviewed checkpoints below (requires GitHub CLI access), or
reproduce them from pinned source as described in [IROH_V2.md](docs/IROH_V2.md#android-native-module):

```bash
gh run download 36539047261 --repo DocMorphic/cmux-app --name cmux-iroh-android-arm64 --dir build/iroh-android
gh run download 36539507313 --repo DocMorphic/cmux-app --name cmux-graphics-path-android-arm64 --dir build/graphics-path-android
gh run download 39410466355 --repo DocMorphic/cmux-app --name cmux-ghostty-android-arm64 --dir build/ghostty-vt-android
gh run download 36653485468 --repo DocMorphic/cmux-app --name cmux-simulator-video-android-arm64 --dir build/simulator-codecs-android
gh run download 37454996751 --repo DocMorphic/cmux-app --name cmux-cloud-terminal-android-arm64 --dir build/cloud-terminal-android
./gradlew :app:assembleDebug
```

Gradle verifies all five native receipts and every listed hash before compiling. Move
any older checkpoint directories aside before downloading these replacements.
If artifacts expire, use reviewed new `native_only`, `graphics_only`, `ghostty_only` and `video_only` workflow
runs or the documented source builds (see also
[Ghostty build notes](docs/GHOSTTY_VT_ANDROID.md#app-build-dependency) and
[graphics-path source notes](third_party/androidx-graphics-path/README.md)).
Cloud checkpoints can be regenerated with the manual `cloud-native.yml` workflow;
see [Cloud integration and build notes](docs/CLOUD_COMPANION.md). APK CI reuses the
source-matched checkpoint cache and verifies dependency notice completeness.
The simulator decoder uses [FFmpeg n9.0.2 source](https://github.com/FFmpeg/FFmpeg/tree/946fcce07b6dcd0331c8cc609192aeff5e1924f8),
licensed under LGPL-2.1-or-later; build instructions and attribution are in
[SIMULATOR_STREAMING.md](docs/SIMULATOR_STREAMING.md) and [NOTICE.md](NOTICE.md).

These builds require 16 KiB LOAD and RELRO alignment. Current native APKs target **arm64**, including Pixel 6a;
x86 emulator and other ABI support remains to be added. CI builds the pinned
native source on a cache miss and validates it again through Gradle.

On Windows, use `.\gradlew.bat :app:assembleDebug` from PowerShell. See
[Windows development](docs/WINDOWS_DEVELOPMENT.md) for setup and the focused
handoff runtime runner shared by Windows and macOS.

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions
produces a stable signed release APK at manually dispatched milestones or
eligible non-draft PR builds. Draft feature commits intentionally skip the build
job. Scheduled APK builds are opt-in during feature-first development;
see [build cadence](docs/ANDROID_TESTING.md#build-cadence).

Optional background-push client configuration is documented in
[Firebase configuration](docs/FIREBASE_CONFIGURATION.md). It requires an explicitly
selected Android client file and does not configure production delivery by itself.


The checked-in source does not include release signing keys or signed-in device
state. A normal debug source build does not require the maintainer's credentials.
Firebase is optional and remains unconfigured unless you explicitly supply a
client configuration; it is unnecessary for the native terminal connection.

</details>

## Project and license

- Kotlin and Jetpack Compose, with Iroh transport and Ghostty VT native modules.
- The native path follows the cmux mobile RPC protocol; the optional
  [legacy Mac helper](bridge/README.md) remains available for development.
- The launcher and in-app logo come from official cmux iOS assets.
- Licensed under [GPL-3.0](LICENSE); third-party attribution is in [NOTICE.md](NOTICE.md)
  and the in-app notices.

This project is **not affiliated with Manaflow or the cmux team**. Normal cmux
compatibility is a project implementation, not an official Android support claim.
