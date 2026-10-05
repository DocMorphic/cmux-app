# Remaining work for iOS parity

Updated 2026-10-05. **The goal is active and full parity is unverified.** This is
the current completion checklist; dated entries in [PARITY.md](PARITY.md) preserve
the detailed evidence and history. A passing fixture or signed APK does not close
a physical workflow gate. These are work areas, not equal-sized progress units.

Development now targets `main` at the user's request. Implement features in larger
batches; broad regression and signed builds belong at integration milestones and
final acceptance. Scheduled APK builds are opt-in via `CMUX_AUTOMATIC_PREVIEWS=true`
and currently disabled; the upstream watcher is active on main with review issue #2.

## Completion gates

| Area | What remains | Evidence required to close it |
| --- | --- | --- |
| Account, pairing and connections | Verify the recent native authorization, legacy ticket and rejected-token recovery changes against actual hosts; finish entry-source eligibility/default selection comparison and real network acceptance of the optional relay hint; exercise account/team changes, saved routes, Iroh/Tailscale/SSH recovery and network transitions. | Pixel/Mac runs covering first pairing, reuse, expiry, revocation, logout, host restart, phone process death and Wi-Fi/mobile-data transitions. Preserve existing credentials and workspaces. Record host capabilities and APK/source versions. |
| Terminal and input | Finish real Gboard/hardware-keyboard, TUI, selection/copy/paste, resize, background/foreground and reconnect acceptance. | Visible Pixel output/input checks against disposable Mac terminals, including independent input/output lanes and recovery without lost or duplicated commands. Recheck the recent protocol changes, even where an older build passed. |
| Workspace, task, search and browser flows | Finish physical acceptance of sidebar/navigation, task creation/attachments/drafts, notifications/search destinations, browser gestures/dialogs/downloads; check large-list paging/autoscroll and slow hosts. | Successful end-to-end Mac operations plus lifecycle/rotation and failure recovery. Verify drafts, selections and nested destinations survive the lifecycle events supported on iOS. |
| Files, Changes and content viewers | Finish the remaining format/menu comparisons and modal/binary preview restoration, including native errors outside RPC and broader real-route retry acceptance, directory/rename/read/export/decoder failure coverage, live panel-kind changes, and broader main Files/direct-tap/transport restoration, forced browser-parent recreation with binary content, rendered-Markdown reflow and real-route/process recovery, Save process-death/large-write recovery and remote-file freshness semantics, video acceptance and zoom across aspect-ratio changes. | An explicit supported-format matrix checked against pinned iOS code, visible rendering and file actions on Pixel, and restoration tests that verify the displayed content. |
| Background notifications | Configure and deploy the Android push path. FCM/HPKE, worker and reply code exists but production delivery is disabled/unconfigured. The proposed private Firebase plus Mac-forwarder setup still needs provisioning. | Real registered-device delivery with the app foregrounded, backgrounded and process-dead, plus Doze, token rotation, tap/reply routing and account revocation. Document infrastructure and any demonstrated platform differences. |
| UI and accessibility | Finish screen-by-screen iOS comparison, keyboard insets, dynamic text, TalkBack, gestures/haptics and performance on Pixel. | Matched-state screenshots and interaction checks for all main screens and sheets; usable enlarged text and accessibility traversal; measured investigation of any remaining freezes/ANRs. |
| Release and updates | Verify an upgrade while signed in; finish notice-feed configuration, cold-start/cookie checks and What's New; carry an upstream review through a port and chosen preview milestone; scheduled previews remain opt-in during feature-first development. | Stable-signer upgrade preserving account/pairing/settings, actual configured feed and update checks, and one observed upstream-change-to-review/build cycle. The watcher has succeeded on main and created review issue #2; port/build acceptance remains. Scheduled APK builds require `CMUX_AUTOMATIC_PREVIEWS=true` (currently disabled). |
| Final source audit | Finish the broad upstream delta inventory and reconcile every remaining iOS behavior with Android; establish the exact upstream version for the parity release. | A requirement-to-source/test/physical-evidence mapping with no unexplained omissions. Scoped audits at newer commits do not advance the global pin. Document only evidenced, unavoidable platform differences. |

## Current delivery and next actions

- Media previews now have seek/time controls, ±10-second skips, speed, mute and
  fullscreen with saved bookmarks/options and guarded player replacement. Four
  control-policy tests passed; main and the new Android fixture compiled. Combined
  viewer integration has run; see the dated results in `CONTENT_PREVIEW_LIFECYCLE.md`.
  Playback/audio, video layouts, interruptions, embedded tracks/PiP/routing,
  accessibility and physical acceptance remain open. Build 616 excludes this
  batch; see `CONTENT_PREVIEW_LIFECYCLE.md`.

- Native text/Markdown previews now stream readable prefixes with strict UTF-8,
  progress, EOF-gated Copy Contents, exact remote Copy path, Latest/End tail
  following and reading/selection retention. Thirty-three JVM checks passed and
  main/instrumentation Kotlin compiled. The growing-document Android UI fixture
  now passes after correcting its disabled-action selector. Live routes, large-document
  performance, Markdown reflow and physical acceptance remain open. Build 616
  excludes this batch; see `CONTENT_PREVIEW_LIFECYCLE.md`.

- Image viewer source comparison now implements iOS 3× tapped-point double-tap,
  centroid-anchored pinch, minimum-scale paging tolerance and long-press
  Share/Save/Copy Image using the shared retained action owners. Image/PDF decoder
  errors are readable. Six geometry tests passed and instrumentation compiled;
  new/adjusted gesture/menu tests await the next combined Android milestone.
  Different-aspect-ratio restoration, animation, accessibility and physical
  acceptance remain open. See `CONTENT_PREVIEW_LIFECYCLE.md`.
- Signed milestone build 616 passed CI at `f72f036` (Actions run 37352608676),
  without preview publication. It excludes the newer image interaction batch.
  Local packaging verification passed; six CI Android 17 ART probes passed.
  Signed-616 launch/upgrade and physical acceptance remain open.

- The Files/Save/export integration milestone passed 18 Android checks on the
  existing API37/16 KB AVD: real Save picker and Share chooser, exact read-only
  provider bytes, restored-action confirmation/no replay, fresh failed-Save
  recovery, retained Files previews/transfers and reconnect, and image clipboard
  bytes. A screenshot-found local text error was fixed; the strengthened recovery
  case passed separately in 9.104 s. Screenshots reviewed, settings unchanged,
  no successful-run crashes/new ANRs, sole AVD stopped. See
  `CONTENT_PREVIEW_LIFECYCLE.md` for evidence and the first assertion failure.
- Durable Share/Open/Copy Image preparation and persistent background Save writes
  are implemented, including fresh-launch Save recovery, exclusive ownership and
  conservative cleanup. Earlier focused JVM batches passed 23 export, 35 recovery
  and 27 transfer checks. Android integration now exercises a real destination
  write and new-owner receipt restoration. Actual process killing/reboot, separate
  main/browser-process concurrency, notification Cancel/shared grants and large or
  slow providers remain open. Malformed/orphan storage and empty lock reclamation
  remain implementation work. No physical Pixel was available.
- Upstream monitoring has succeeded on main and created bot review issue #2.
  The complete-tree fallback and expanded release-script coverage pass 16 policy
  tests; the live inventory detects 1,060 relevant paths at `1012a019`. Detection
  does not close the source audit. See `UPDATES.md`; no app build was triggered.
- Share/Open/Copy Image and gallery-row Share now have Activity-owned preparation
  and one-time system presentation, with cancellation and cache leases for active
  exports. Main Kotlin compiled and 17 focused JVM checks passed. Real Activity
  rotation/chooser/clipboard and process-death behavior remain unverified; no
  APK or emulator run. See `CONTENT_PREVIEW_LIFECYCLE.md`.
- Remote Save/Share/Open now re-stat and stream the current Mac file, including
  when a preview exceeds its size limit. Save streams directly to its durable
  copy with preparation progress/cancellation. Main Kotlin compiled and 26
  focused JVM checks passed. Actual toolbar/chooser/picker, network changes and
  large-file behavior remain pending. The subsequent export-owner batch implements
  retention through recreation; its Android runtime acceptance remains pending. See `CONTENT_PREVIEW_LIFECYCLE.md`.
- Save recovery now has durable private copies, atomic phase records, integrity
  checking before destination writes, and cancellable progress. Main Kotlin
  compiled and 17 focused JVM checks passed. Actual Android
  process/picker/provider recovery and background completion remain pending.
  The subsequent remote-actions batch implements current-loader materialization
  identified by the iOS comparison. See `CONTENT_PREVIEW_LIFECYCLE.md` for scope.
- Latest source batch adds Files/gallery/direct-preview reconnect retention,
  retained folder listings, and typed folder/direct-path failures. Main Kotlin
  compiled and 27 focused JVM cases passed. Android/Pixel route, visible reading
  state and rotation/reconnect acceptance for this batch remain pending; no APK
  build or emulator run was performed. See `CONTENT_PREVIEW_LIFECYCLE.md`.
- Build **616** at `f72f036` is the latest signed development download. CI and local
  packaging checks passed; signed-616 runtime/upgrade remains open. Earlier signed-out
  596 → 606 upgrade/reboot evidence does not verify this APK. [PIXEL_INSTALL.md](PIXEL_INSTALL.md) is authoritative for downloads.
- Rejected-token recovery passed **109 focused JVM tests** and is now in build
  606. Actual Mac rejection/recovery acceptance remains open. Continue batching
  features into signed milestones rather than signing every small commit.
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
  ten debug Activities are confirmed excluded from signed 606.
- Main terminal Files now retains its gallery/download owner through recreation.
  68 JVM checks, one actual NativeScreen Files test (24.449 s) and nine Files
  regressions (76.590 s) passed. PDF pixels/page/bounds and an in-flight transfer
  retained their owner without duplicate fetches; closing deleted private files.
  Screenshots inspected; settings unchanged; sole AVD stopped. This is newer than
  signed 606. Next: native Markdown-panel retention, direct-tap and long-text route
  recreation, pending Save results and revocation UI. See
  [CONTENT_PREVIEW_LIFECYCLE.md](CONTENT_PREVIEW_LIFECYCLE.md) for exact scope.
- Shared Save now retains its result handler and independent bytes across Activity
  recreation and loading-preview replacement. 17 JVM and three Android picker
  checks (47.142 s) passed, with exact saved bytes, cancellation cleanup and failed
  destination retry state. Fourteen viewer regressions also passed (158.618 s);
  screenshots inspected, no run crashes/new ANRs, settings unchanged, AVD stopped.
  Save process death,
  large/interrupted writes, provider transitions, remote-file freshness comparison
  and full physical routes remain open; see `CONTENT_PREVIEW_LIFECYCLE.md`.
- Native file/Markdown panels now retain admitted downloads across recreation.
  60 JVM and four Android checks passed: Raw Markdown search/reading position,
  exact content/owner with one fetch, a pending transfer, panel-removal cleanup
  and a visible closed-panel message, plus existing panel/Files regressions.
  Screenshots inspected, no run crashes/new ANRs, settings unchanged, AVD stopped.
  Shared rendered Markdown recovery is verified by the next checkpoint. Full
  file-panel visual restoration and descriptor refresh remain; compare actual feed-loss retention
  against iOS. Process death and physical workflows remain open. Details in
  `CONTENT_PREVIEW_LIFECYCLE.md`; signed 606 unchanged.
- Rendered Markdown now saves scroll/zoom/block anchors through recreation,
  mode toggles and renderer termination. Seven JVM tests and two Android tests
  (33.480 s) passed, including real pinch, late layout changes, paragraph geometry,
  painted text and two retries followed by raw fallback. Three Markdown regressions
  (23.465 s) passed on the identical production APK, with Mermaid/Vega pixel checks.
  Screenshots inspected; all 14 assets matched; no new run ANRs/app crashes; sole
  AVD stopped and reaped. Next: different-width/text reflow, full native panel
  rendered/transport recovery, process death/refetch and physical acceptance.
  Signed 606 is unchanged; details in `CONTENT_PREVIEW_LIFECYCLE.md`.
- Native panel feed-loss recovery now passed 58 JVM and four Android panel checks
  (63.287 s), plus two existing Files/panel regressions (39.722 s). Completed
  rendered documents keep reading state without refetch; interrupted transfers
  use a fresh verified feed; title changes/revocation delete old bytes. Fourteen
  screenshots inspected, settings unchanged, no new successful-run ANRs/crashes,
  14 assets verified; sole AVD stopped/reaped. Signed 606 is unchanged. Follow up
  the documented intermittent JVM two-Mac startup timeouts and early Raw-menu
  interaction; typed panel failures/manual retry and physical routes remain open.
- Typed file/Markdown preview failures now passed 31 JVM checks, seven Android
  panel checks (99.758 s) and nine Files regressions (86.445 s). Eight new
  failure/recovery screenshots inspected; retries load fresh bytes and
  non-retryable cases show specific explanations. All 14 assets matched;
  settings unchanged, no successful-run crashes/new ANRs, sole AVD stopped/reaped.
  See `CONTENT_PREVIEW_LIFECYCLE.md` for the fixture correction, pre-test boot ANRs,
  rounding display issue and remaining transport/local-storage/physical scope.
  Signed 606 remains unchanged.
- Local preview output now distinguishes ENOSPC/EDQUOT from other local IO errors,
  and ambiguous rounded size-limit messages include exact byte counts. 19 JVM and
  five Android checks (10.210 s) passed, including a real proxy-file ENOSPC write;
  size UI inspected, 14 assets matched, successful-run crash/ANR checks clear,
  settings unchanged, sole AVD stopped/reaped. Directory/rename/read/export/decoder
  and physical storage acceptance remain open. See `CONTENT_PREVIEW_LIFECYCLE.md`
  for the corrected restricted-device test and boot ANRs. Signed 606 unchanged.
- Explicit panel Retry now uses a freshly verified connection after feed
  replacement; failed/interrupted selection survives offline recreation and
  revoked/stale callbacks cannot reopen it. 16 JVM checks and three changed
  Android cases (28.731 s) passed; four screenshots inspected, no successful-run
  crashes/new ANRs, settings unchanged and sole AVD stopped/reaped. Broad
  regression is deferred to integration milestones per the updated user request.
  Details and the initial interruption failure are in `CONTENT_PREVIEW_LIFECYCLE.md`.
- The Pixel was absent during the latest batch. One USB/unlock request is pending
  for real Mac/Pixel acceptance; continue independent development while awaiting it.
- Use only the existing AVD and stop it after testing. No extra virtual devices.

## How completion is recorded

For each row, link the exact source revision, checks and runtime evidence in the
relevant detailed document. Record failed attempts and the scope of the final
pass. Update this checklist when evidence changes; do not turn unresolved items
into exclusions or claim a percentage based on test/build counts. The user authorized main integration on 2026-10-05. Source integration is not
full-parity or release acceptance; production release promotion still requires
authorization.
