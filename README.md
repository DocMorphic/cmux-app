# cmux-app

An **unofficial Android companion project** for [cmux](https://cmux.com). The goal is to attach to terminals running on a Mac, view workspaces and agent notifications, and send terminal input from Android.

The current app is a **research build**. It opens an offline UI preview and validates the current minimal cmux pairing QR formats. It does **not** sign in, pair, connect to a Mac, stream a terminal, or send keystrokes yet. See [the implementation plan](docs/PLAN.md) before treating it as a usable companion.

## What was researched

The [official iOS companion](https://cmux.com/ios) pairs with a Mac running cmux, shows live terminal workspaces, lets users control terminal sessions, and forwards agent notifications. The [iOS guide](https://cmux.com/docs/ios) says the terminal stream is direct over a private network such as Tailscale; cmux's servers handle account and device metadata and, if enabled, push notification delivery. The [public source](https://github.com/manaflow-ai/cmux/tree/main/ios) also shows pairing, attach tickets, multiplexed RPC, workspace lists, terminal rendering, browser surfaces, and more. The detailed source review is in [RESEARCH.md](docs/RESEARCH.md).

## Open the Android project

Install Android Studio with Android SDK 36 and JDK 17 or newer, then open this directory. For a command line build:

```bash
./gradlew :app:assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`. It is a preview APK, not a connected cmux client.

## Project choices

- Kotlin, Jetpack Compose, one Android app module.
- `io.github.docmorphic.cmuxapp` is a temporary independent app ID.
- Pairing QR parsing is isolated from future auth and transport code.
- No cmux source is copied into this app. Any future port of cmux implementation must honor its GPL-3.0-or-later license and notices.

This project is not affiliated with Manaflow or the cmux team.
