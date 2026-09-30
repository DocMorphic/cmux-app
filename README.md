# cmux-app

An **unofficial Android companion project** for [cmux](https://cmux.com). The release goal is feature parity with the official iOS companion on a Pixel 6a.

The app opens on the direct cmux connection path: same-account sign-in, selected-team computer discovery, framed mobile RPC, workspace and notification feeds, browser streams, and a styled terminal with scrollback. The previously tested [Mac helper](bridge/README.md) remains available in the app. The direct path is under physical-device validation; it is not yet a parity release. The [parity tracker](docs/PARITY.md) records every feature and its acceptance check.

For the current physical device check, use the [Pixel 6a install guide](docs/PIXEL_INSTALL.md).

**Signed download:** [build 248 APK artifact](https://github.com/DocMorphic/cmux-app/actions/runs/36650296593/artifacts/11069984729)
includes native Ghostty rendering, configurable shortcuts, terminal zoom, file/
Markdown panels and Todo controls at `ecdccb0`. Signature, 16 KB packaging and
emulator launch are verified; physical acceptance remains pending. See the
install guide for source, checksums and package identity.

**Current host compatibility:** inspected cmux 0.64.25 uses Iroh-only pairing.
Android now wires Iroh/V2 discovery and admitted RPC connections into the app and
background feeds. Earlier Pixel/Mac checks verified account enrollment and native
traffic; newer terminal/settings work still needs physical acceptance. Legacy
Tailscale QR routes remain available for older hosts.
See [the connection migration](docs/IROH_V2.md).

**Continuing on another laptop:** start with [HANDOFF.md](docs/HANDOFF.md) for the
working branch, source research, implementation map, unfinished checks, release
state, setup commands, and next steps. The latest feature work is on
`feature/local-mac-bridge`, ahead of the last signed APK.

## What was researched

The [official iOS companion](https://cmux.com/ios) pairs with a Mac running cmux, shows live terminal workspaces, lets users control terminal sessions, and forwards agent notifications. The [iOS guide](https://cmux.com/docs/ios) says the terminal stream is direct over a private network such as Tailscale; cmux's servers handle account and device metadata and, if enabled, push notification delivery. The [public source](https://github.com/manaflow-ai/cmux/tree/main/ios) also shows pairing, attach tickets, multiplexed RPC, workspace lists, terminal rendering, browser surfaces, and more. The detailed source review is in [RESEARCH.md](docs/RESEARCH.md).

## Open the Android project

Install Android Studio with Android SDK 36 and JDK 17. The app now includes its
native Iroh, rebuilt graphics-path and Ghostty VT dependencies.
Obtain the three reviewed checkpoints below (requires GitHub CLI access), or
reproduce them from pinned source as described in [IROH_V2.md](docs/IROH_V2.md#android-native-module):

```bash
gh run download 36539047261 --repo DocMorphic/cmux-app --name cmux-iroh-android-arm64 --dir build/iroh-android
gh run download 36539507313 --repo DocMorphic/cmux-app --name cmux-graphics-path-android-arm64 --dir build/graphics-path-android
gh run download 36642877666 --repo DocMorphic/cmux-app --name cmux-ghostty-android-arm64 --dir build/ghostty-vt-android
./gradlew :app:assembleDebug
```

Gradle verifies all three native receipts and every listed hash before compiling. Move
any older checkpoint directories aside before downloading these replacements.
If artifacts expire, use reviewed new `native_only`, `graphics_only` and `ghostty_only` workflow
runs or the documented source builds (see also
[Ghostty build notes](docs/GHOSTTY_VT_ANDROID.md#app-build-dependency) and
[graphics-path source notes](third_party/androidx-graphics-path/README.md)).
These builds require 16 KiB LOAD and RELRO alignment. Current native APKs target **arm64**, including Pixel 6a;
x86 emulator and other ABI support remains to be added. CI builds the pinned
native source on a cache miss and validates it again through Gradle.

On Windows, use `.\gradlew.bat :app:assembleDebug` from PowerShell. See
[Windows development](docs/WINDOWS_DEVELOPMENT.md) for setup and the focused
handoff runtime runner shared by Windows and macOS.

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions
produces a stable signed release APK at manually dispatched milestones or
eligible non-draft PR builds. Draft feature commits intentionally skip the build
job; see [build cadence](docs/ANDROID_TESTING.md#build-cadence).

## Project choices

- Kotlin and Jetpack Compose, with separate Iroh transport and Ghostty VT native modules.
- `io.github.docmorphic.cmuxapp` is a temporary independent app ID.
- The native path follows the cmux mobile RPC protocol and the optional Mac helper remains available during migration.
- The optional Mac helper binds loopback by default; it requires an explicit flag to listen on Tailscale.
- The launcher and in-app logo come from the official cmux iOS assets; see [NOTICE.md](NOTICE.md) for attribution and license details.

This project is not affiliated with Manaflow or the cmux team.
