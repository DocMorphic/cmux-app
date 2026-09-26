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
| Workspaces | `MobileSyncWorkspaceListResponse`, `DeviceTreeView` | Native list, sections, previews, colors, workspace/terminal/browser creation, rename, pin/read, and close RPCs coded; hierarchy, reorder, group actions, and phone QA missing | Live hierarchy, add/rename/close/reorder/group, status and selection |
| Terminal | `MobileTerminalRenderGridFrame`, `GhosttySurfaceView` | Styled render-grid, delta continuity, and bounded local scrollback coded; VT fallback and full fidelity missing | Stream render grid or VT bytes; colors, cursor, Unicode, alternate screen, scrollback, resize |
| Input | `TerminalInputTextView`, `MobileTerminalInputResponse` | Text composer, modifier/navigation/control toolbar, hardware key handling, and mode-aware arrows/paste coded; image/file input, complete mode handling, and phone QA missing | Soft and hardware keyboard, modifiers, paste, image/file input, shortcuts, safe retry |
| Notifications | `NotificationFeedView`, `CmuxAppDelegate` | Native in-app feed and read sync coded; opt-in foreground connection and Android alerts coded but unverified on phone; server push fallback missing | Feed, unread counts, actions, deep links, Android background delivery, read sync |
| Browser | `CmuxMobileBrowser`, `MobileBrowserFrameEvent` | JPEG/PNG stream, navigation, tap, scroll, text, and dialog RPC paths coded; downloads and phone QA missing | Show browser panels; navigate, scroll, tap, type, handle dialogs and downloads |
| Search | `MobilePrimarySearchCoordinator` | Workspace text filter | Search workspaces and notifications with matching navigation |
| Changes | `CmuxMobileChanges` | Changed-file list and bounded unified diffs coded; file content actions and phone QA missing | View changed files and diffs from the active workspace |
| Tasks and agents | `CmuxAgentChatUI`, task composer in `CmuxMobileShellUI` | Built-in Claude/Codex/OpenCode/Shell templates and new-workspace task RPC coded; model/effort, attachments, task recovery, chat UI, and phone QA missing | Create and navigate tasks; handle agent prompts and attachments |
| Settings | `MobileSettingsView` | Account and saved-computer controls coded; notification, display, diagnostics missing | Account, computers, notification, display, network, diagnostics, reset |
| Device behavior | iOS lifecycle, accessibility, background push | Missing | Rotation, keyboard, process death, offline recovery, screen reader, battery |
| Delivery | iOS release checks | Stable signing and CI verified on foundation build; current native build and Pixel run pending | Stable signed APK, upgrade in place, reproducible CI, Pixel acceptance run |

## Work order

1. Make pairing usable and durable: encrypted credential storage, QR scanner,
   state restoration, and connection recovery. The helper remains an interim
   transport while native cmux account auth is implemented.
2. Port the official pairing/ticket/frame/RPC contracts and replace the helper
   with the Mac's mobile endpoint. Verify against a pinned cmux build.
3. Render the terminal stream with a real terminal engine and implement the full
   input contract. Use vim, htop, Claude Code, and Codex as acceptance cases.
4. Complete workspaces, notifications, browser, search, changes, tasks,
   settings, accessibility, and background behavior.
5. Run the parity checklist on the Pixel and ship a stable signed APK.

The current Android build is not a parity release. Keep this table honest as
implementation and physical-device verification progress.
