# cmux-app

An **unofficial Android companion project** for [cmux](https://cmux.com). The release goal is feature parity with the official iOS companion on a Pixel 6a.

The app opens on the direct cmux connection path: same-account sign-in, official QR parsing, framed mobile RPC, workspace and notification feeds, browser streams, and a styled terminal with scrollback. The previously tested [Mac helper](bridge/README.md) remains available in the app. The direct path is under build and physical-device validation; it is not yet a parity release. The [parity tracker](docs/PARITY.md) records every feature and its acceptance check.

For the current physical device check, use the [Pixel 6a install guide](docs/PIXEL_INSTALL.md).

**Continuing on another laptop:** start with [HANDOFF.md](docs/HANDOFF.md) for the
working branch, source research, implementation map, unfinished checks, release
state, setup commands, and next steps. The latest feature work is on
`feature/local-mac-bridge`, ahead of the last signed APK.

## What was researched

The [official iOS companion](https://cmux.com/ios) pairs with a Mac running cmux, shows live terminal workspaces, lets users control terminal sessions, and forwards agent notifications. The [iOS guide](https://cmux.com/docs/ios) says the terminal stream is direct over a private network such as Tailscale; cmux's servers handle account and device metadata and, if enabled, push notification delivery. The [public source](https://github.com/manaflow-ai/cmux/tree/main/ios) also shows pairing, attach tickets, multiplexed RPC, workspace lists, terminal rendering, browser surfaces, and more. The detailed source review is in [RESEARCH.md](docs/RESEARCH.md).

## Open the Android project

Install Android Studio with Android SDK 36 and JDK 17 or newer, then open this directory. For a command line build:

```bash
./gradlew :app:assembleDebug
```

On Windows, use `.\gradlew.bat :app:assembleDebug` from PowerShell. See
[Windows development](docs/WINDOWS_DEVELOPMENT.md) for setup and the focused
handoff runtime runner shared by Windows and macOS.

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions
produces a stable signed release APK at manually dispatched milestones or
eligible non-draft PR builds. Draft feature commits intentionally skip the build
job; see [build cadence](docs/ANDROID_TESTING.md#build-cadence).

## Project choices

- Kotlin, Jetpack Compose, one Android app module.
- `io.github.docmorphic.cmuxapp` is a temporary independent app ID.
- The native path follows the cmux mobile RPC protocol and the optional Mac helper remains available during migration.
- The optional Mac helper binds loopback by default; it requires an explicit flag to listen on Tailscale.
- The launcher and in-app logo come from the official cmux iOS assets; see [NOTICE.md](NOTICE.md) for attribution and license details.

This project is not affiliated with Manaflow or the cmux team.
