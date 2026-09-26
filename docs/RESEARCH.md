# cmux Android research

Reviewed 2026-09-26 against public cmux source at `manaflow-ai/cmux` commit `4d3385b9d7ac80a9bbdf5c886cc276849b1e4fa0`. This is a source and documentation review; the TestFlight iOS app was not installed or run here.

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
2. The current [pairing QR codec](https://github.com/manaflow-ai/cmux/blob/main/Packages/Shared/CMUXMobileCore/Sources/CMUXMobileCore/CmxPairingQRCode.swift) has a v2 Tailscale route form and a v3 Iroh peer identity form. Codes intentionally contain no bearer token. This Android project parses both forms and connects over v2 Tailscale routes. Iroh transport remains to be implemented.
3. The Mac issues an attach ticket and route. Shared types live in [CMUXMobileCore](https://github.com/manaflow-ai/cmux/tree/main/Packages/Shared/CMUXMobileCore).
4. The [mobile RPC client](https://github.com/manaflow-ai/cmux/blob/main/Packages/iOS/CmuxMobileRPC/Sources/CmuxMobileRPC/MobileCoreRPCClient.swift) uses a persistent byte transport, sends authorized requests, and subscribes to server events. The [frame codec](https://github.com/manaflow-ai/cmux/blob/main/Packages/Shared/CMUXMobileCore/Sources/CMUXMobileCore/MobileSyncProtocol.swift) uses a four-byte big-endian length prefix and an 8 MiB default frame cap. Android implements that framed RPC contract with a VPN-bound socket and Stack account token.
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

### Composer contract checked against the pinned source (2026-09-27)

`MobileShellComposite.sendRemoteTerminalPaste` sends `terminal.paste` with
`workspace_id`, `surface_id`, `client_id`, literal `text`, and `submit_key`.
The Mac's `TerminalController.v2MobileTerminalPaste` accepts `return` for
submission and `none` for insertion. It chooses the agent-specific submit key
on the Mac, including Ctrl+Enter for a multiline Claude prompt. Sending the
same composed block through `terminal.input` can split newlines into separate
submissions; Android now uses the dedicated paste method.

The iOS composer captures the target and text before awaits, prevents
overlapping sends, keeps failed drafts, and reconciles an acknowledgement with
the original terminal's draft. Android now follows those behaviors, additionally
scoping stored drafts by pairing and workspace. Drafts are encrypted with
Android Keystore. Pending delivery is saved before transmission; restored
pending drafts show an unconfirmed-delivery notice and are never auto-sent.

References at `4d3385b9d7ac80a9bbdf5c886cc276849b1e4fa0`:

- `Packages/iOS/CmuxMobileShell/Sources/CmuxMobileShell/MobileShellComposite.swift`
- `Sources/TerminalController.swift`, `v2MobileTerminalPaste`

### Website and repository links

- [cmux site](https://cmux.com/)
- [official iOS page](https://cmux.com/ios)
- [official iOS guide](https://cmux.com/docs/ios)
- [cmux GitHub repository](https://github.com/manaflow-ai/cmux)
- [iOS app directory](https://github.com/manaflow-ai/cmux/tree/main/ios)
- [shared mobile core](https://github.com/manaflow-ai/cmux/tree/main/Packages/Shared/CMUXMobileCore)

### Terminal attachments (2026-09-27)

At the pinned upstream revision, `MobileShellComposite.submitComposer` sends
images using `terminal.paste_image` and acknowledges each image separately.
Files use `mobile.task.attachment.upload` with `task.attachments.v1`, 3 MiB
chunks, and each attachment UUID as both `operation_id` and `upload_id`.
The resulting absolute paths are POSIX single-quoted and prepended to the
captured message. Files remain staged until that message is acknowledged.
See `MobileShellComposite+ComposerFileAttachments.swift`,
`MobileShellComposite+TaskAttachments.swift`, and
`TerminalComposerAttachmentInsertion.swift` in the official source.

Android now follows those contracts, captures the original target, checks
connection identity between awaited operations, and never automatically replays
a send. Pending payloads are AES-GCM encrypted in app-owned, backup-excluded
files; encrypted draft metadata retains their IDs across process death. The
system document picker grants access only to selected photos/files, following
[Android's Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files).

Images use the upstream 2048-pixel maximum dimension and PNG/JPEG preparation.
The current Android control transport caps frames at 8 MiB, so encoded image
bytes are kept below 5 MiB before base64 expansion. This is an implementation
constraint to revisit with transport parity, not an unavoidable Android limit.
Per-terminal caps are 10 attachments/32 MiB; global caps are 20/64 MiB, matching
the upstream terminal composer. A single file is limited to 32 MiB. Task
composer attachments, rich keyboard image paste, and shared-file intents remain
separate work.
