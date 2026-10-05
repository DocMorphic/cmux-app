# Remaining work for iOS parity

Updated 2026-10-05. **The goal is active and full parity is unverified.** This is
the current completion checklist; dated entries in [PARITY.md](PARITY.md) preserve
the detailed evidence and history. A passing fixture or signed APK does not close
a physical workflow gate. These are work areas, not equal-sized progress units.

## Completion gates

| Area | What remains | Evidence required to close it |
| --- | --- | --- |
| Account, pairing and connections | Verify the recent native authorization, legacy ticket and rejected-token recovery changes against actual hosts; finish entry-source eligibility/default selection comparison and real network acceptance of the optional relay hint; exercise account/team changes, saved routes, Iroh/Tailscale/SSH recovery and network transitions. | Pixel/Mac runs covering first pairing, reuse, expiry, revocation, logout, host restart, phone process death and Wi-Fi/mobile-data transitions. Preserve existing credentials and workspaces. Record host capabilities and APK/source versions. |
| Terminal and input | Finish real Gboard/hardware-keyboard, TUI, selection/copy/paste, resize, background/foreground and reconnect acceptance. | Visible Pixel output/input checks against disposable Mac terminals, including independent input/output lanes and recovery without lost or duplicated commands. Recheck the recent protocol changes, even where an older build passed. |
| Workspace, task, search and browser flows | Finish physical acceptance of sidebar/navigation, task creation/attachments/drafts, notifications/search destinations, browser gestures/dialogs/downloads; check large-list paging/autoscroll and slow hosts. | Successful end-to-end Mac operations plus lifecycle/rotation and failure recovery. Verify drafts, selections and nested destinations survive the lifecycle events supported on iOS. |
| Files, Changes and content viewers | Finish the remaining format/menu comparisons and modal/binary preview restoration, including forced browser-parent recreation with binary content, rendered-Markdown/Save state, video acceptance and zoom across aspect-ratio changes. | An explicit supported-format matrix checked against pinned iOS code, visible rendering and file actions on Pixel, and restoration tests that verify the displayed content. |
| Background notifications | Configure and deploy the Android push path. FCM/HPKE, worker and reply code exists but production delivery is disabled/unconfigured. The proposed private Firebase plus Mac-forwarder setup still needs provisioning. | Real registered-device delivery with the app foregrounded, backgrounded and process-dead, plus Doze, token rotation, tap/reply routing and account revocation. Document infrastructure and any demonstrated platform differences. |
| UI and accessibility | Finish screen-by-screen iOS comparison, keyboard insets, dynamic text, TalkBack, gestures/haptics and performance on Pixel. | Matched-state screenshots and interaction checks for all main screens and sheets; usable enlarged text and accessibility traversal; measured investigation of any remaining freezes/ANRs. |
| Release and updates | Verify an upgrade while signed in; finish notice-feed configuration, cold-start/cookie checks and What's New; activate and exercise upstream-monitor and preview workflows after an authorized main-branch merge. | Stable-signer upgrade preserving account/pairing/settings, actual configured feed and update checks, and one observed upstream-change-to-review/build cycle. Scheduled workflows are currently dormant on the feature branch. |
| Final source audit | Finish the broad upstream delta inventory and reconcile every remaining iOS behavior with Android; establish the exact upstream version for the parity release. | A requirement-to-source/test/physical-evidence mapping with no unexplained omissions. Scoped audits at newer commits do not advance the global pin. Document only evidenced, unavoidable platform differences. |

## Current delivery and next actions

- Build **596** is the latest independently verified download. CI, local packaging,
  nine arm64 ART classes, signed-out 589 → 596 upgrade and reboot passed. [PIXEL_INSTALL.md](PIXEL_INSTALL.md) is authoritative for downloads.
- The newer rejected-token recovery source passed **109 focused JVM tests** and
  is not in build 596. Batch its Android verification with the next coherent
  feature milestone; do not build and sign for each small commit.
- Token recovery is committed/pushed as `d8edb8b`. Route ordering now has 12
  Swift-reference cases within 104 passing JVM checks; the revised chooser test
  passed Android execution in the optional-relay batch. That batch also passed
  39 JVM and 25 Android checks. Next: real Mac/Pixel connection acceptance,
  entry-source route policy, and remaining content lifecycle gaps.
- Changes preview retention now passed 29 JVM, seven Android regression checks,
  and a strengthened one-test PDF/image recreation check. See
  [CONTENT_PREVIEW_LIFECYCLE.md](CONTENT_PREVIEW_LIFECYCLE.md). Next viewer work:
  browser-parent recreation, rendered-Markdown/Save state, video acceptance and zoom across aspect-ratio changes.
- Short/mixed PDF navigation and image zoom/pan recreation are now verified by a
  further seven Android checks (79.850 s), including real pinch/pan/reset and file
  paging. An initial one-finger pan failure was fixed before the final pass.
- Browser view retention and routed Changes rotation now passed four Android checks
  (79.876 s), including exact DOM/history and real picker upload. See
  [LOCAL_BROWSER.md](LOCAL_BROWSER.md) for the debug-host versus production-route
  scope, ninth debug fixture, and remaining parent/process/picker checks.
- Text/media recreation and native text clipping now passed seven Android checks
  (94.922 s), including exact search/selection/reading state, paused/playing media
  bookmarks, Go-to-line drafts and a toolbar pixel assertion. See
  [CONTENT_PREVIEW_LIFECYCLE.md](CONTENT_PREVIEW_LIFECYCLE.md). Rendered-Markdown,
  pending Save, process death, video/aspect-ratio and physical acceptance remain;
  ten debug Activities must be excluded from the next signed APK.
- The Pixel was absent during the latest batch. One USB/unlock request is pending
  for real Mac/Pixel acceptance; continue independent development while awaiting it.
- Use only the existing AVD and stop it after testing. No extra virtual devices.

## How completion is recorded

For each row, link the exact source revision, checks and runtime evidence in the
relevant detailed document. Record failed attempts and the scope of the final
pass. Update this checklist when evidence changes; do not turn unresolved items
into exclusions or claim a percentage based on test/build counts. PR #1 remains
a draft until the full release criteria are satisfied; merging or public release
requires the user's authorization.
