# cmux Android research

Reviewed 2026-09-26 against public cmux source at `manaflow-ai/cmux` commit `e7f1c40bf0d05b6fdaf8c48d2e834897640a3cc5`. This is a source and documentation review; the TestFlight iOS app was not installed or run here.

## What the iOS app does

| Capability | Evidence | Android target |
| --- | --- | --- |
| Mac pairing and computer selection | [iOS docs](https://cmux.com/docs/ios), [iOS source](https://github.com/manaflow-ai/cmux/tree/main/ios) | QR/manual pair, save paired Mac, reconnect |
| Live workspaces and terminal input | [iOS product page](https://cmux.com/ios), [workspace UI source](https://github.com/manaflow-ai/cmux/tree/main/Packages/iOS/CmuxMobileShellUI) | Workspace list, terminal view, input bar |
| Private direct networking | [iOS docs](https://cmux.com/docs/ios) | Tailscale route first; no public port exposure |
| Agent notifications | [iOS docs](https://cmux.com/docs/ios) | In-app feed first; Android push later |
| Terminal-grade controls | [iOS product page](https://cmux.com/ios) | Ctrl/Alt/Esc/Tab/arrows and hardware keyboard |
| Browser and other surfaces | [iOS package tree](https://github.com/manaflow-ai/cmux/tree/main/Packages/iOS) | Follow after terminal MVP |

The official app is a **companion**, not a terminal process running locally on the phone. The Mac owns workspaces and PTYs. The phone renders and controls them.

## Connection model found in source

1. The phone signs in with the same account as the Mac. The [iOS README](https://github.com/manaflow-ai/cmux/blob/main/ios/README.md) names Stack Auth sign-in and QR/manual pairing.
2. The current [pairing QR codec](https://github.com/manaflow-ai/cmux/blob/main/Packages/Shared/CMUXMobileCore/Sources/CMUXMobileCore/CmxPairingQRCode.swift) has a v2 Tailscale route form and a v3 Iroh peer identity form. Codes intentionally contain no bearer token. The parser in this project only recognizes those minimal forms; it is not a pairing implementation.
3. The Mac issues an attach ticket and route. Shared types live in [CMUXMobileCore](https://github.com/manaflow-ai/cmux/tree/main/Packages/Shared/CMUXMobileCore).
4. The [mobile RPC client](https://github.com/manaflow-ai/cmux/blob/main/Packages/iOS/CmuxMobileRPC/Sources/CmuxMobileRPC/MobileCoreRPCClient.swift) uses a persistent byte transport, sends authorized requests, and subscribes to server events. The [frame codec](https://github.com/manaflow-ai/cmux/blob/main/Packages/Shared/CMUXMobileCore/Sources/CMUXMobileCore/MobileSyncProtocol.swift) uses a four-byte big-endian length prefix and an 8 MiB default frame cap.
5. Terminal output and rendering have separate protocol models and fidelity requirements. A plain text terminal is insufficient for full-screen programs, colors, cursor movement, and scrollback.

## Constraints and decisions

- The Android app must pass the same-account and host pairing checks. A QR route by itself does not authorize terminal access.
- The first real transport should target the documented Tailscale path. Iroh requires a separate QUIC implementation or reusable native core.
- iOS push uses Apple's push service; Android needs its own notification path. In-app notifications can be implemented before background push.
- The Mac and Android client must be tested together against a pinned cmux build because the mobile protocol is evolving.
- Upstream cmux is [GPL-3.0-or-later](https://github.com/manaflow-ai/cmux/blob/main/LICENSE). Keep attribution and source availability when porting code.
- The product needs a Mac running cmux plus an Android phone on the same Tailscale network for end-to-end validation.

## Local Mac verification (2026-09-26)

- Installed cmux is `0.64.25 (106)` and its sidebar shows a signed-in account.
- Tailscale is running on the Mac; an Android peer is online in the same tailnet.
- The `Open Mobile Pairing` command exists in cmux's command palette.
- Mobile Pairing currently says **Enable iOS pairing** is off. Settings → Mobile says enabling it also starts Iroh networking; same-account devices can connect automatically, while the QR is for Tailscale pairing.
- The ordinary `cmux` CLI refuses its control socket when launched outside a cmux terminal. A custom Mac helper based on the CLI would have to run inside cmux or use a separately supported API.

## Sources

- [cmux site](https://cmux.com/)
- [official iOS page](https://cmux.com/ios)
- [official iOS guide](https://cmux.com/docs/ios)
- [cmux GitHub repository](https://github.com/manaflow-ai/cmux)
- [iOS app directory](https://github.com/manaflow-ai/cmux/tree/main/ios)
- [shared mobile core](https://github.com/manaflow-ai/cmux/tree/main/Packages/Shared/CMUXMobileCore)
