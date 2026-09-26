# Android parity tracker

Reference: [cmux iOS](https://cmux.com/ios) and
[`manaflow-ai/cmux`](https://github.com/manaflow-ai/cmux) at
`4d3385b9d7ac80a9bbdf5c886cc276849b1e4fa0` (2026-09-26).
The upstream code changes frequently, so update this pin before a release.
The iOS implementation lives mainly in `ios/cmuxPackage`,
`Packages/iOS`, and `Packages/Shared/CMUXMobileCore`.

The target is the real iOS companion workflow on a Pixel 6a (Android 17).
A feature is complete only after the Android behavior is implemented, covered
by a focused automated check where practical, and exercised against the Mac.
UI resemblance alone does not count.

| Area | iOS source / contract | Android status | Acceptance check |
| --- | --- | --- | --- |
| Account | `MobileAuthComposition`, `MobileRootAuthGate` | OTP sign-in and encrypted token refresh coded; unverified on phone | Sign in with the Mac's cmux account; restore session after restart; sign out |
| Computers | `MobilePairedMac`, `MacComputerListSection` | Encrypted saved-Mac list, select, forget, and reconnect coded; unverified on phone | Discover, choose, forget, and reconnect multiple Macs |
| Pairing | `CmxPairingQRCode`, `CmxAttachTicketCompactCoder` | QR scan, deep link, and current v2 Tailscale code path coded; unverified on phone; v3 Iroh parse only | Scan official Mac QR, validate version/identity, authorize exact route, revoke |
| Transport | `CmxNetworkByteTransport`, `MobileCoreRPCSession` | Framed RPC, VPN-bound 100.64/10 route resolution, and reconnect coded; unverified on phone; Iroh missing | Persistent framed RPC over authorized Tailscale; Iroh route; reconnect without duplicate input |
| Workspaces | `MobileSyncWorkspaceListResponse`, `DeviceTreeView` | Native list, sections, previews, colors, compact toolbar, computer picker, unread filter, workspace/terminal/browser creation, rename, pin/read, close, group create/rename/pin/ungroup, and move RPCs coded; full hierarchy, drag reorder, and phone QA missing | Live hierarchy, add/rename/close/reorder/group, status and selection |
| Terminal | `MobileTerminalRenderGridFrame`, `GhosttySurfaceView` | Styled render-grid, delta continuity, bounded local scrollback, and actual viewport reporting/clear coded; VT fallback, full fidelity, and phone resize QA missing | Stream render grid or VT bytes; colors, cursor, Unicode, alternate screen, scrollback, resize |
| Input | `TerminalInputTextView`, `MobileTerminalInputResponse` | Native multiline paste/submit, encrypted per-Mac/terminal drafts, pending-send guards and acknowledgement reconciliation verified with an emulator fixture; modifier/navigation/control toolbar and hardware keys coded; photo/file picker, encrypted attachments, image paste and chunked file upload verified with an emulator fixture; rich keyboard paste, complete mode handling, and phone QA missing | Soft and hardware keyboard, modifiers, paste, image/file input, shortcuts, safe retry |
| Notifications | `NotificationFeedView`, `CmuxAppDelegate` | Native in-app feed and read sync coded; opt-in foreground connection and Android alerts coded but unverified on phone; server push fallback missing | Feed, unread counts, actions, deep links, Android background delivery, read sync |
| Browser | `CmuxMobileBrowser`, `MobileBrowserFrameEvent` | JPEG/PNG stream, navigation, tap, scroll, text, and dialog RPC paths coded; downloads and phone QA missing | Show browser panels; navigate, scroll, tap, type, handle dialogs and downloads |
| Search | `MobilePrimarySearchCoordinator` | Workspace text filter | Search workspaces and notifications with matching navigation |
| Changes | `CmuxMobileChanges` | Changed-file list and bounded unified diffs coded; file content actions and phone QA missing | View changed files and diffs from the active workspace |
| Tasks and agents | `CmuxAgentChatUI`, task composer in `CmuxMobileShellUI` | Built-in Claude/Codex/OpenCode/Shell templates and new-workspace task RPC coded; model/effort, attachments, task recovery, chat UI, and phone QA missing | Create and navigate tasks; handle agent prompts and attachments |
| Settings | `MobileSettingsView` | Account, saved-computer, background notification, terminal size, and connection status controls coded; full network diagnostics and reset missing | Account, computers, notification, display, network, diagnostics, reset |
| Device behavior | iOS lifecycle, accessibility, background push | Keyboard resizing verified with an Android 17 emulator fixture; visible terminal text exposed to accessibility; broader lifecycle and phone QA missing | Rotation, keyboard, process death, offline recovery, screen reader, battery |
| Delivery | iOS release checks | Native debug/release builds and stable signing verified in CI; Pixel run pending | Stable signed APK, upgrade in place, reproducible CI, Pixel acceptance run |

## Automated evidence (2026-09-27)

30 JVM tests pass. Three Compose instrumentation flows pass on an Android 17
(API 37) ARM64 emulator using the Pixel 6a display profile. It checks unread
filtering, terminal output appearing, keyboard-driven viewport reduction,
exactly one paste/submit request, and viewport cleanup on return to workspaces.
The composer flow also checks multiline preservation, separate terminal drafts,
encrypted persistence before sending, a disabled send action while an
acknowledgement is pending, retained text after rejection, explicit retry, and
insertion without submission. JVM checks cover delayed acknowledgements,
newer edits, Mac isolation, interrupted-send restoration, and sign-out cleanup.
The attachment flow checks the Android document-picker callback, 2048-pixel
image preparation, encrypted payloads and restored metadata, image acknowledgement,
file retention after rejected text, stable upload IDs on explicit retry, and cleanup.
The picker result is intercepted with local fixture files; cloud document
providers and a real Mac are not exercised. JVM checks also cover multi-chunk
byte fidelity, shell quoting, stopping on connection changes, attachment removal,
and resource budgets. Screenshots were inspected with and without the keyboard. This caught and
fixed terminal background overdraw, skipped render updates, and a false
cancellation error during resize. It uses a local RPC fixture; live Mac auth,
pairing, real terminal applications, and the physical Pixel remain unverified.
See [ANDROID_TESTING.md](ANDROID_TESTING.md) for reproduction.

## Work order

1. Make pairing usable and durable: encrypted credential storage, QR scanner,
   state restoration, and connection recovery. The helper remains an interim
   transport while the native cmux account and pairing path is validated.
2. Port the official pairing/ticket/frame/RPC contracts and replace the helper
   with the Mac's mobile endpoint. Verify against a pinned cmux build.
3. Render the terminal stream with a real terminal engine and implement the full
   input contract. Use vim, htop, Claude Code, and Codex as acceptance cases.
4. Complete workspaces, notifications, browser, search, changes, tasks,
   settings, accessibility, and background behavior.
5. Run the parity checklist on the Pixel and ship a stable signed APK.

The current Android build is not a parity release. Keep this table honest as
implementation and physical-device verification progress.
