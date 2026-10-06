# cmux-app

An **unofficial Android companion project** for [cmux](https://cmux.com). The release goal is feature parity with the official iOS companion on a Pixel 6a.

The app opens on the direct cmux connection path: same-account sign-in, selected-team computer discovery, framed mobile RPC, workspace and notification feeds, browser streams, and a styled terminal with scrollback. The previously tested [Mac helper](bridge/README.md) remains available in the app. The direct path is under physical-device validation; it is not yet a parity release. The [parity tracker](docs/PARITY.md) records every feature and its acceptance check.

For the current physical device check, use the [Pixel 6a install guide](docs/PIXEL_INSTALL.md).

**Signed download:** [build 616 APK artifact](https://github.com/DocMorphic/cmux-app/actions/runs/37352608676/artifacts/11364767698)
from `f72f036` is the current signed development checkpoint. Newer source work on
`main`, including push setup and Mac forwarding controls, is not included in that
APK. Signed-in upgrade, physical Pixel/Mac acceptance and configured push remain
open. See the [install guide](docs/PIXEL_INSTALL.md) for hashes and exact verification
scope, and [remaining work](docs/REMAINING_WORK.md) for source progress.

**Current host compatibility:** inspected cmux 0.64.25 uses Iroh-only pairing.
Android now wires Iroh/V2 discovery and admitted RPC connections into the app and
background feeds. Earlier Pixel/Mac checks verified account enrollment and native
traffic; newer terminal/settings work still needs physical acceptance. Legacy
Tailscale QR routes remain available for older hosts.
See [the connection migration](docs/IROH_V2.md).

**Continuing on another laptop:** start with [HANDOFF.md](docs/HANDOFF.md) for the
working branch, source research, implementation map, unfinished checks, release
state, setup commands, and next steps. The latest feature work is on
`main`; the signed checkpoint is identified above. Source commits are grouped
into larger feature batches before broad regression and signed milestone builds.

## What was researched

The [official iOS companion](https://cmux.com/ios) pairs with a Mac running cmux, shows live terminal workspaces, lets users control terminal sessions, and forwards agent notifications. The [iOS guide](https://cmux.com/docs/ios) says the terminal stream is direct over a private network such as Tailscale; cmux's servers handle account and device metadata and, if enabled, push notification delivery. The [public source](https://github.com/manaflow-ai/cmux/tree/main/ios) also shows pairing, attach tickets, multiplexed RPC, workspace lists, terminal rendering, browser surfaces, and more. The detailed source review is in [RESEARCH.md](docs/RESEARCH.md).

## Open the Android project

Install Android Studio with Android SDK platforms 37.0 and 36, build-tools 36.0.0 and JDK 17.
The wrapper pins Gradle 9.3.1; the build uses AGP 9.1.1 and Kotlin/Compose compiler 2.4.20.
SDK 36 is used only by the Android 8 fingerprint adapter; see [toolchain notes](docs/ANDROID_TOOLCHAIN.md). The app now includes its
native Iroh, rebuilt graphics-path, Ghostty VT, simulator-video and Cloud dependencies.
Obtain the five reviewed checkpoints below (requires GitHub CLI access), or
reproduce them from pinned source as described in [IROH_V2.md](docs/IROH_V2.md#android-native-module):

```bash
gh run download 36539047261 --repo DocMorphic/cmux-app --name cmux-iroh-android-arm64 --dir build/iroh-android
gh run download 36539507313 --repo DocMorphic/cmux-app --name cmux-graphics-path-android-arm64 --dir build/graphics-path-android
gh run download 36642877666 --repo DocMorphic/cmux-app --name cmux-ghostty-android-arm64 --dir build/ghostty-vt-android
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

## Project choices

- Kotlin and Jetpack Compose, with separate Iroh transport and Ghostty VT native modules.
- `io.github.docmorphic.cmuxapp` is a temporary independent app ID.
- The native path follows the cmux mobile RPC protocol and the optional Mac helper remains available during migration.
- The optional Mac helper binds loopback by default; it requires an explicit flag to listen on Tailscale.
- The launcher and in-app logo come from the official cmux iOS assets; see [NOTICE.md](NOTICE.md) for attribution and license details.

This project is not affiliated with Manaflow or the cmux team.
