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
| Computers | `MobilePairedMac`, `MacComputerListSection` | Missing | Discover, choose, forget, and reconnect multiple Macs |
| Pairing | `CmxPairingQRCode`, `CmxAttachTicketCompactCoder` | QR scan and current v2 Tailscale code path coded; unverified on phone; v3 Iroh parse only | Scan official Mac QR, validate version/identity, authorize exact route, revoke |
| Transport | `CmxNetworkByteTransport`, `MobileCoreRPCSession` | Persistent framed RPC coded for TCP; unverified on phone; reconnection and Iroh missing | Persistent framed RPC over authorized Tailscale; Iroh route; reconnect without duplicate input |
| Workspaces | `MobileSyncWorkspaceListResponse`, `DeviceTreeView` | Native list coded; grouping and mutations missing | Live hierarchy, add/rename/close/reorder/group, status and selection |
| Terminal | `MobileTerminalRenderGridFrame`, `GhosttySurfaceView` | Styled render-grid drawing coded; VT fallback, scrollback, and full fidelity missing | Stream render grid or VT bytes; colors, cursor, Unicode, alternate screen, scrollback, resize |
| Input | `TerminalInputTextView`, `MobileTerminalInputResponse` | Text and a few keys | Soft and hardware keyboard, modifiers, paste, image/file input, shortcuts, safe retry |
| Notifications | `NotificationFeedView`, `CmuxAppDelegate` | Native in-app feed and read sync coded; background delivery missing | Feed, unread counts, actions, deep links, Android background delivery, read sync |
| Browser | `CmuxMobileBrowser`, `MobileBrowserFrameEvent` | Missing | Show browser panels; navigate, scroll, tap, type, handle dialogs and downloads |
| Search | `MobilePrimarySearchCoordinator` | Workspace text filter | Search workspaces and notifications with matching navigation |
| Changes | `CmuxMobileChanges` | Missing | View changed files and diffs from the active workspace |
| Tasks and agents | `CmuxAgentChatUI`, task composer in `CmuxMobileShellUI` | Missing | Create and navigate tasks; handle agent prompts and attachments |
| Settings | `MobileSettingsView` | Disconnect only | Account, computers, notification, display, network, diagnostics, reset |
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
