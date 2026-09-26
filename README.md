# cmux-app

An **unofficial Android companion project** for [cmux](https://cmux.com). The goal is to attach to terminals running on a Mac, view workspaces and agent notifications, and send terminal input from Android.

The current app connects through an experimental [Mac helper](bridge/README.md) over Tailscale. It shows live workspaces and terminal text and sends terminal input. Its dark workspace and terminal screens follow the layout of the official iOS companion. It is still a research build and does not yet provide the official iOS app's native pairing, full terminal rendering, browser panes, or notifications. See [the implementation plan](docs/PLAN.md).

For the current physical device check, use the [Pixel 6a install guide](docs/PIXEL_INSTALL.md).

## What was researched

The [official iOS companion](https://cmux.com/ios) pairs with a Mac running cmux, shows live terminal workspaces, lets users control terminal sessions, and forwards agent notifications. The [iOS guide](https://cmux.com/docs/ios) says the terminal stream is direct over a private network such as Tailscale; cmux's servers handle account and device metadata and, if enabled, push notification delivery. The [public source](https://github.com/manaflow-ai/cmux/tree/main/ios) also shows pairing, attach tickets, multiplexed RPC, workspace lists, terminal rendering, browser surfaces, and more. The detailed source review is in [RESEARCH.md](docs/RESEARCH.md).

## Open the Android project

Install Android Studio with Android SDK 36 and JDK 17 or newer, then open this directory. For a command line build:

```bash
./gradlew :app:assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`. On the `feature/local-mac-bridge` branch it includes a manual Tailscale connection to the experimental helper.

## Project choices

- Kotlin, Jetpack Compose, one Android app module.
- `io.github.docmorphic.cmuxapp` is a temporary independent app ID.
- Pairing QR parsing is isolated from future auth and transport code.
- The optional Mac helper binds loopback by default and is on a separate development branch until live testing is complete.
- The launcher and in-app logo come from the official cmux iOS assets; see [NOTICE.md](NOTICE.md) for attribution and license details.

This project is not affiliated with Manaflow or the cmux team.
