# Remaining work for iOS parity

Updated 2026-10-07. **The goal is active and full parity is unverified.** This is
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
| Agent Feed | Finish quote geometry, continuous-scroll expanded quotes and remaining composer visual details, broader composer/reader lifecycle and routed sidebar navigation; verify the new source-specific rows, Markdown, swipe triage and Display settings; physically verify all decision/reply families and event/reconnect/revocation behavior. | Scoped source mapping and evidence in [AGENT_FEED.md](AGENT_FEED.md); main-tab transport/UI implementation and fixture checks are not full Feed acceptance. |
| Account, pairing and connections | Verify edited/replacement-grant reconnect on Pixel/Mac; verify the implemented legacy pre-tag identity adoption and authenticated legacy-to-native upgrade on the physical devices (see ATTACH_TICKETS.md). Verify native authorization, ticket/rejected-token recovery, saved-method guards and optional relay hints against actual hosts; exercise account/team changes, Iroh/Tailscale/SSH recovery and network transitions. | Pixel/Mac runs covering first pairing, reuse, expiry, revocation, logout, host restart, phone process death and Wi-Fi/mobile-data transitions. Preserve existing credentials and workspaces. Record host capabilities and APK/source versions. |
| Terminal and input | Finish real Gboard/hardware-keyboard, TUI, selection/copy/paste, resize, background/foreground and reconnect acceptance. | Visible Pixel output/input checks against disposable Mac terminals, including independent input/output lanes and recovery without lost or duplicated commands. Recheck the recent protocol changes, even where an older build passed. |
| Workspace, task, search and browser flows | Finish physical acceptance of sidebar/navigation, task creation/attachments/drafts, notifications/search destinations, browser gestures/dialogs/downloads; check large-list paging/autoscroll and slow hosts. | Successful end-to-end Mac operations plus lifecycle/rotation and failure recovery. Verify drafts, selections and nested destinations survive the lifecycle events supported on iOS. |
| Files, Changes and content viewers | Finish the remaining format/menu comparisons and modal/binary preview restoration, including native errors outside RPC and broader real-route retry acceptance, directory/rename/read/export/decoder failure coverage, live panel-kind changes, and broader main Files/direct-tap/transport restoration, forced browser-parent recreation with binary content, rendered-Markdown reflow and real-route/process recovery, broader Save/provider recovery and remote-file freshness semantics, video acceptance and zoom across aspect-ratio changes. | An explicit supported-format matrix checked against pinned iOS code, visible rendering and file actions on Pixel, and restoration tests that verify the displayed content. |
| Background notifications | Configure and deploy the Android push path. FCM/HPKE, worker and reply code exists but production delivery is disabled/unconfigured. The proposed private Firebase plus Mac-forwarder setup still needs provisioning. | Real registered-device delivery with the app foregrounded, backgrounded and process-dead, plus Doze, token rotation, tap/reply routing and account revocation. Document infrastructure and any demonstrated platform differences. |
| Cloud machines and terminals | Port the configured newer iOS Cloud controller, WireGuard/daemon bridge, machine UI and common workspace integration; the API foundation alone is insufficient. Audit optional system-VPN/private-port behavior. | Source-to-behavior mapping, fixture transport and UI evidence, then authorized real account/Pixel terminal and lifecycle workflows. See CLOUD_COMPANION.md; do not confuse Cloud machine access with Android background push. |
| UI and accessibility | Finish screen-by-screen iOS comparison, keyboard insets, dynamic text, TalkBack, gestures/haptics and performance on Pixel. | Matched-state screenshots and interaction checks for all main screens and sheets; usable enlarged text and accessibility traversal; measured investigation of any remaining freezes/ANRs. |
| Release and updates | Verify an upgrade while signed in; verify the configured Android notice feed across cold-start/offline restart, cookie exchange and What's New; carry an upstream review through a port and chosen preview milestone; scheduled previews remain opt-in during feature-first development. | Stable-signer upgrade preserving account/pairing/settings, actual configured feed and update checks, and one observed upstream-change-to-review/build cycle. The watcher has succeeded on main and created review issue #2; port/build acceptance remains. Scheduled APK builds require `CMUX_AUTOMATIC_PREVIEWS=true` (currently disabled). |
| Final source audit | Finish the broad upstream delta inventory and reconcile every remaining iOS behavior with Android; establish the exact upstream version for the parity release. | A requirement-to-source/test/physical-evidence mapping with no unexplained omissions. Scoped audits at newer commits do not advance the global pin. Document only evidenced, unavoidable platform differences. |

## Current delivery and next actions

- Feed previews now place See more inline, preserve Markdown and whole graphemes,
  and keep expansion reachable at large font sizes. The composer thread layout
  was fixed after integration testing exposed an intrinsic-measurement failure.
  **Four Markdown JVM checks and four Android scenarios passed**, including full
  quote/reader expansion. Final screenshots were inspected. TalkBack/RTL,
  continuous expanded scrolling and physical acceptance remain open; see
  `AGENT_FEED.md`. Emulator/Gradle stopped, no signed release.

- Feed plan rows now approve directly using the advertised mode and expose all
  source modes in a menu. Reply/revision sheets quote the parent event and keep
  drafts and modal targets through Compose saved-state restoration, waiting for
  an authorized snapshot reload. Reader mode/viewport are saveable; bodies reload.
  **40 JVM cases and five Android scenarios passed**; a stronger native-bounds
  pixel check also verified visible expanded text after early screenshots caught
  late painting. See `AGENT_FEED.md` for receipts and limits. Continuous-scroll
  expanded quotes, remaining visual details and real process/device acceptance
  remain open. Emulator and Gradle stopped; no signed release.

- Feed questions now use iOS-style inline paging, full-width options, custom
  answers, current-page height, saved drafts and complete-answer gating. Request
  changes reject stale answers, and plan revision now sends source-correct
  `manual` mode plus feedback. **34 focused JVM tests** and all three distinct
  Android scenarios have passing evidence (the initial nested-pager selector
  failure was corrected and only that case rerun). Screenshots reviewed; no
  ANR/crash events. The AVD and Gradle are stopped. See `AGENT_FEED.md` for exact
  scope, receipts and remaining composer/reader/sidebar/physical requirements.

- Agent Feed now has source-specific row content, native inline Markdown, brand
  avatars/compact times/quote treatment, Done / Needs Input swipes, accessible
  context navigation and persistent tab/quote display options. **30 focused JVM
  cases pass** and Android test sources compile. Runtime/visual acceptance of
  these new rows was initially pending; the subsequent inline-question batch
  above now supplies initial fixture/visual evidence. Bubble
  geometry, composer/reader presentation/restoration and physical gates remain.

- The upstream audit found the missing **Agent Feed** primary tab. Android now
  implements its verified-Mac subscription/snapshot pipeline, main-tab timeline,
  separate search, read/triage state, permission/question/plan decisions, exact-event
  terminal replies and versioned full-message reader. **78 focused JVM checks**
  passed, debug/test APKs built, and the main-shell Feed flow passed one Android
  case in 108.102 s after a startup-ANR retry of the same APKs. The sole AVD is
  stopped. See `AGENT_FEED.md` for remaining presentation,
  lifecycle, sidebar and physical acceptance gates; no signed milestone yet.

- A real default-process Save worker was killed after a partial 1 MiB write and
  recovered a complete **32 MiB** export in a new process, with exact SHA-256,
  destination reopening and private-copy cleanup verified. The recovery stage
  passed in 28.71 s; no replacement work was enqueued by the verifier. This uses
  an isolated generated provider; external-provider permission changes,
  force-stop/reboot and physical workflows remain open. See
  `CONTENT_PREVIEW_LIFECYCLE.md`. Release checks now exclude all debug component
  kinds and verify all 17 packaged viewer assets. AVD stopped/reaped; no signed build.

- Pending Save and Cancel now pass after an actual isolated preview-process kill
  behind Android's real file picker: **two cases, 55.148 s**, with a new PID,
  Android-restored Bundle/task and exact 204,800-byte saved output after deletion
  of the original. This verifies the existing production Save path; it does not
  close interrupted worker writes, provider revocation or physical/browser-parent
  acceptance. Startup/first-attempt failures are preserved in
  `CONTENT_PREVIEW_LIFECYCLE.md`. The sole AVD was stopped/reaped; no signed build.

- Workbook previews now retain supported inline/shared rich-text formatting,
  including mixed colors, explicit style removal, underline/strike and super/subscript.
  **15 Node checks and the expanded Android viewer case (7.703 s) passed**;
  rendered glyph pixels, internal links and saved-position restoration were
  verified. The sole AVD was stopped/reaped. See `DOCUMENT_FORMATS.md` for source
  mapping, limits and remaining format/physical comparison work. No signed build.

- Terminal composition now preserves selected text and selection direction during
  IME surrounding deletion; hardware Backspace removes a selection or a complete
  grapheme locally. **Six Android checks passed in 24.136 s** on the existing
  Android 17 / 16 KiB AVD, which was stopped/reaped afterward. See
  `TERMINAL_SHORTCUTS.md` for source mapping, APK hashes and the pretest System UI
  ANR baseline. Physical Gboard/keyboard and Pixel/Mac acceptance remain open.

- Automatic browser recovery now passes through the real routed Activity/service/
  SOCKS proxy. Integration found WebView's generic (-1) mapping for tunnel failures,
  now covered by the bounded GET retry. **Three Android cases passed in 39.069 s**
  and three JVM classification cases passed; cookies, history, manual Retry,
  rendered pixels and host release were checked. Failed attempts and a corrected
  fixture timing assumption are documented in `LOCAL_BROWSER.md`. The sole AVD
  was stopped/reaped; actual Pixel/Mac and SSH/Iroh recovery remain open.

- Routed browser GET connection failures now prepare the existing computer route
  and retry once, with Stop/replacement cancellation and no automatic POST replay.
  **Four Android checks passed together in 21.498 seconds**, including visible
  recovery and a bounded persistent failure. The original two failed checks and
  fixes are recorded in `LOCAL_BROWSER.md`. The sole AVD was stopped/reaped.
  Real proxy/SSH/Iroh and Pixel/Mac recovery remain unverified; no signed build.

- Embedded browser video now supports fullscreen with renderer controls and
  lifecycle cleanup. **Two Android checks passed together in 21.602 seconds** on
  the existing Android 17 / 16 KiB AVD, including visible playback, Back, retained
  draft and no reload through recreation. Screenshots were inspected and the
  emulator stopped. Actual routed-process/proxy and physical acceptance remain
  open; see `LOCAL_BROWSER.md`. No signed release was made.

- Real browser-parent recreation with a binary Changes PDF now passes on the
  existing Android 17 / 16 KiB AVD (**one case, 19.344 seconds**). Android relaunch
  events, visible page-two pixels, unchanged request counts, retained artifact/
  sheet ownership and the unsent browser draft establish this specific path.
  Test settings were restored and the emulator stopped/reaped. Media/PiP and Save
  from this route, process death and physical Pixel/Mac acceptance remain open.
  See `LOCAL_BROWSER.md` for evidence and the earlier unsuccessful attempts.

- PiP expansion and return now have Android 17 / 16 KiB runtime coverage for
  position, French audio/subtitles, speed, mute, source recreation, visible video
  and private-copy cleanup. Integration found and fixed backward keyframe snapping
  on resume and black-on-black labels in the expanded/fullscreen player. Two media
  cases passed together; the strengthened visible-frame follow-up also passed.
  The existing sole AVD was stopped and reaped. Browser-process PiP, account
  retirement, wider lifecycle and physical Pixel acceptance remain open. See
  `MEDIA_PICTURE_IN_PICTURE.md` for failures, final evidence and limitations.

- Cloud machine rows and workspace recovery now receive live connection failures,
  including attach/create/input/output errors, with stale-owner rejection and
  current-tunnel subscriptions. Retry includes these failures while preserving
  healthy connections. **47 JVM tests passed**; the new machine-row Android UI
  case subsequently passed in the combined media/Cloud integration run on the
  existing Android 17 / 16 KiB AVD. See `CLOUD_COMPANION.md`; real transport/physical
  acceptance and autonomous-recovery comparison remain open.

- Cloud explicit reconnect now waits for a newer verified catalog before attaching,
  retains the selected renderer and scopes creation failures to their machine.
  Attachment/creation user copy no longer includes raw native diagnostics.
  **32 JVM checks and two Android terminal checks passed** (16.516 s on the existing
  Android 17 / 16 KiB AVD). The emulator was stopped and reaped; no Pixel or real
  Cloud network was used. Real link recovery, service/process recovery and the
  broader iOS audit remain. See `CLOUD_COMPANION.md` for exact evidence and limits.

- Cloud integration now has Android 17 / 16 KiB runtime evidence for the real
  Rust/JNI and WireGuard libraries, machine UI, onboarding, visibility and common
  terminal renderer/input. A repeated terminal check found a closed-Ghostty focus
  callback; retirement now publishes ENDED before releasing the native display,
  with a passing regression. The 15 selected cases have passing results across
  the initial batch and targeted follow-ups, not one clean final full-suite run.
  Four fixture screenshots were inspected; full-shell insets and physical Cloud/
  VPN acceptance remain open. The existing sole AVD was stopped. Details and
  failed attempts are recorded in `CLOUD_COMPANION.md`.

- Cloud machine rows now expose connection failures, immediate retry, lifecycle
  status colors, long-press/overflow actions and non-executing swipe reveals with
  confirmed deletion. Refresh also reaches failed connections, retaining healthy
  links. Stable failure copy replaces raw diagnostics in management/waiting views.
  **41 JVM checks passed**; Android UI sources compile, including two new gesture/
  retry checks. Physical gesture/accessibility/RTL and real recovery acceptance
  remain. See `CLOUD_COMPANION.md` for source mapping and verification limits.

- Accounts with no computers can now reach Cloud from the computer picker and
  return to Workspaces, matching the iOS first-computer tab scaffold. Cloud has
  account-scoped saveable tab state. Locked sizes open their specific plan;
  returning from pricing refreshes machine limits. **25 JVM checks passed**;
  main/Android tests compile. Two new UI checks remain unrun. Native Android
  billing, actual first-machine creation and physical navigation/restoration
  acceptance remain. See `CLOUD_COMPANION.md` for the source mapping and limits.

- Saved VPN restoration is implemented: reuse the existing peer/configuration only
  after the same user/team/login is verified and VPN consent remains granted.
  Sticky service recovery waits for verification; recorded disconnects, account
  replacement and expired permission cannot silently restore. **34 JVM checks
  passed**; debug/test APKs built and all 22 native libraries plus ZIP alignment
  passed 16 KiB checks. Physical service/process recovery, offline startup and
  Tailscale transitions remain unverified; no Pixel is currently visible in ADB.
  See `CLOUD_COMPANION.md` for evidence and recovery limits.

- VPN cleanup drains multiple bounded batches automatically, preserves active
  routes and supports retry while connected. Disconnect intent is persisted before
  awaited shutdown; storage failure still stops the local VPN. The recovery
  checkpoint above extends the earlier 28-check cleanup verification.

- Cloud system VPN is now wired through the official WireGuard backend, protected
  foreground service, shared-account lifetime, status controls and OS consent in
  Cloud management/onboarding. **22 JVM checks passed**; debug/test APKs built;
  all 22 packaged native libraries and APK ZIP alignment pass 16 KiB checks.
  The two backend Android checks and live consent/private-web/Tailscale/background
  flows remain unrun (no Pixel in ADB). Restoration and cleanup scheduling
  are implemented above; exact UI/runtime acceptance remains. See `CLOUD_COMPANION.md`.

- The system VPN lifecycle controller and native account adapter now implement
  explicit enrollment, serialized install/stop, late-operation fencing and durable
  account-owned cleanup. **22 JVM checks passed**; main/Android test compilation
  passed at that checkpoint. Service, notification, app-lifetime binding and
  consent/settings UI are now integrated; physical acceptance remains. See
  `CLOUD_COMPANION.md` for tested behavior and remaining integration details.

- System VPN now has private-route validation and an encrypted profile/peer-cleanup
  journal. **19 JVM checks passed** and main/Android test compilation passed.
  Tests also fixed padded WireGuard public keys in terminal fallback config.
  The official WireGuard AAR passed the supported-ABI 16 KiB gate and is now
  integrated as recorded above. Device acceptance remains.
  See `CLOUD_COMPANION.md` for the precise boundary and implementation sequence.

- Cloud now has the iOS-style three-page first-visit introduction and Cloud basics
  replay, with saved completion/page state, persistence failure handling and
  responsive layouts. Android-specific key/VPN copy reflects actual capabilities.
  Main and Android UI tests compile; the three new UI checks remain unrun without
  a device. System VPN and first-computer entry are now implemented above;
  private-port acceptance, native billing and first-run runtime checks remain open. See `CLOUD_COMPANION.md` for verification scope.

- Cloud composer text now uses the shared encrypted draft repository, partitioned
  by login/user/team/machine/terminal. Sends persist a pending marker before input
  and wait for native transport admission before clearing the submitted revision.
  Interrupted delivery retains text with an unconfirmed warning and never replays
  automatically. **35 JVM checks passed**; Android test compilation passed. The
  renderer fixture and real encrypted-storage/process recovery remain unrun.
  Continue Cloud onboarding/source audit and physical acceptance.

- Cloud now uses the bounded per-workspace last-tab store and a destination-only
  Android saved-activity checkpoint. Restoration waits for a fresh catalog, honors
  account/hidden-machine ownership and yields to explicit navigation. Removed
  terminals fall back safely; only the active Cloud view regains input admission.
  **53 JVM checks passed**, main/Android test sources compile. Real Android process
  restoration remains open; persisted composer implementation is recorded above.
  See `CLOUD_COMPANION.md`.

- Cloud visibility now persists separately per user/team and is controlled in
  Computers. Hidden hosts leave navigation/create menus while remaining available
  to re-enable; hiding the open host closes its terminal and input path. Only a
  successful machine inventory prunes saved IDs. **28 JVM checks passed**, main
  and Android test sources compile; the management fixture remains unrun. Next:
  terminal selection/process restoration, onboarding and live acceptance. See
  `CLOUD_COMPANION.md` and `captures/runtime/cloud-computer-visibility/`.

- Cloud workspace/terminal creation now uses the native daemon and common list,
  picker and routed-sidebar menus. Workspace IDs appear immediately; starter and
  new-terminal selection follow catalog confirmation. Synthetic unassigned rows
  create a real workspace, and failures never automatically repeat mutations.
  **100 focused JVM checks passed**, main/Android test compilation passed; the
  Android menu fixture remains unrun. Next: hidden-machine persistence, terminal
  restoration, onboarding and live acceptance. See `CLOUD_COMPANION.md`.

- Cloud computer selection now uses the common dropdown and survives sidebar
  return/loading/disconnect without broadening to other hosts. Source lists,
  refresh and creation menus respect the Cloud scope; retired Retry callbacks
  cannot reset a replacement account. **84 JVM checks passed**, main/Android test
  compilation passed; the selector fixture remains unrun. No APK rebuild/emulator.
  Next: workspace/terminal creation, hidden-machine persistence, terminal process
  restoration and live acceptance. See `CLOUD_COMPANION.md`.

- Cloud workspaces now mount in the shared list/sidebar and use the common Ghostty
  surface, toolbar, keyboard and composer through account-owned machine hosts.
  **92 JVM checks passed**; debug/test APKs built and 16 KiB ZIP alignment passed.
  The real-renderer fixture compiled but has not run (ADB has no device).
  Next: creation, full computer-picker/scope return, hidden-machine persistence,
  restoration and live Android/native acceptance. See `CLOUD_COMPANION.md` and
  `captures/runtime/cloud-ui-integration/`. Earlier mounting gaps below are
  historical checkpoints superseded by this entry; no full parity claim.

- Cloud's single-slot terminal attachment owner now serializes blocking attaches,
  fences old send/resize/detach by native token, delivers ordered output, buffers
  early input and debounces resize repaint. **25 focused JVM checks passed**, with
  main/instrumentation compilation. It still needs account/renderer/composer and
  navigation mounting; live Android terminal behavior is unverified. See
  `CLOUD_COMPANION.md` and `captures/runtime/cloud-terminal-attachment/`.

- The account-owned Cloud workspace catalog now loads native snapshots with legacy
  list fallback and projects daemon hierarchy into shared workspace/terminal models.
  Running machines wait for the tunnel; disconnects retain rows, failures back off,
  and stale reads cannot resurrect removed/account-retired rows. **22 focused JVM
  checks passed** with main compilation. Shared list/filter/sidebar mounting and
  terminal selection/output/input are the next implementation work; no live transport
  or Pixel acceptance is claimed. See `CLOUD_COMPANION.md`.

- Cloud tunnel lifecycle is mounted in the retained account owner: foreground
  shells with machines keep the tunnel across tabs; background/account changes
  retire links and input before asynchronous native cleanup. Startup is bounded
  to 30 seconds with explicit retry and late-handle disposal. Cloud-first registry
  initialization shares the computer-discovery ID with corruption guards.
  **37 focused JVM checks passed**, main/app/Iroh test sources compile; Android
  identity and live transport checks remain unrun (no connected device). Common
  workspace/terminal mounting is next. See `CLOUD_COMPANION.md` and
  `captures/runtime/cloud-lifecycle/`.

- Cloud native packaging is integrated: both libraries, verifier AAR, keep rules,
  portable Gradle receipt checks, in-app notices and cached/reusable CI checkpoint.
  Native run `37454996751` passed with **756 artifact hashes**, **410 notice components**
  and no missing texts. Debug/test APKs built (1m17s); all **21 native libraries**
  pass alignment and APK ZIP alignment passes. **749 notice assets** match their
  receipt; Cloud binary bytes match after reproducing AGP symbol stripping.
  The real-library initialization/coexistence test compiled but has not run; no
  ADB device was present and a Pixel request is pending. No emulator/signed release.
  Next: account-owned tunnel/session mounting, common workspaces and renderer/input,
  plus physical initialization/DNS/WSS gates. See `CLOUD_COMPANION.md`.

- Cloud's Android DNS/TLS adapter is implemented in the exported native build:
  process-lifetime JNI context, one-time initialization, Android WSS certificate
  verification and the matching verifier AAR. **26 focused JVM checks passed**;
  native run `37452130548` at `e3322507` **passed**. Downloaded hashes and 16 KiB
  alignment are verified. Packaging/notices are completed by the newer checkpoint
  above; device DNS/TLS checks and mounting the account tunnel owner remain open.

- Cloud attach/approval now has an account-owned handshake: parallel native connect
  and bounded invitation polling, prompt cancellation and late-handle disposal.
  Explicit tunnel enrollment uses the existing Iroh registry ID and encrypted Cloud
  identity. **18 JVM checks passed**; main and Iroh instrumentation compilation passed.
  This foundation is not mounted; the Android identity-read case was not run.
  Continue Android initialization, tunnel leases and workspace/renderer integration.

- Cloud's Android C/JNI adapter and Kotlin handle/output ownership now exist,
  including catalog, attachment, input, resize acknowledgments and ordered replay.
  **8 focused JVM checks passed**, main compilation and NDK C checks passed.
  These use a fake native boundary; real Android runtime remains unverified.
  Hosted run `37447383957` failed on a missing Zig PATH; fixed follow-up
  `37447939408` then found Android PTY compile gaps. The builder now patches
  those exported-source cfg/libc cases. Subsequent runs found missing Ghostty source
  layout and errno accessors in two crates; fixed in the driver. Run `37450983946`
  at `819b6943` **passed**, including native/JNI linking, C exports and 16 KiB
  alignment. Downloaded artifacts match all receipt hashes and passed the local
  alignment check. The dependency audit identified required separate DNS and TLS
  initialization and a WSS trust-store adapter; their implementation is recorded above.
  The new native dependency is now packaged, but not mounted in the terminal flow.
  Continue with account-owned tunnel/attachment lifecycle and workspace/renderer
  integration. See `CLOUD_COMPANION.md`.

- Cloud terminal identity/configuration is implemented: an encrypted no-backup
  installation identity, X25519 key, atomic resolve, and server/fallback wg-quick
  completion. **25 focused JVM checks passed** with main compilation (24 seconds).
  The factory is not mounted until the tunnel owner exists; Android Keystore and
  native route checks still need integration coverage. Upstream's Rust/Ghostty
  build lacks an Android target mapping; adapt it before linking the C ABI and
  verifying 16 KiB compatibility. No APK/emulator or live enrollment.
  See `CLOUD_COMPANION.md`.

- Cloud machine management is now mounted in a third primary tab, with a
  retained account/team ViewModel, machine lifecycle menus, delete confirmation,
  size/plan/resource creation controls and real controller actions. **18 focused
  JVM checks passed**, main/instrumentation compilation passed. Three Android UI
  cases are queued, not run. Tutorial, native plan sheet, tunnel/Cloud workspace
  integration, tablet layout and actual account/Pixel acceptance remain open;
  no live Cloud action or APK/emulator. See `CLOUD_COMPANION.md`.

- Cloud machine state now has an owned controller: retained rows, bounded
  refresh/provisioning polling, per-machine lifecycle actions, account retirement
  and durable creation retry identities. **29 focused JVM checks passed** and
  main compilation passed (39 seconds). UI construction, hidden-machine state,
  tunnel/attachment ownership and real account/device acceptance remain open;
  no live Cloud operation, APK or emulator. See `CLOUD_COMPANION.md`.

- Cloud is enabled by the newer iOS production composition and projects its
  machines into the common workspace UI. Android now has the machine/tunnel/
  attach API contracts, typed decoding, bounded cookie-free HTTP and an exact
  account/team adapter. **15 focused JVM checks passed**, with main compilation;
  no live Cloud requests or APK/device run. The controller, WireGuard/Rust bridge,
  navigation/screens and live acceptance remain open. See `CLOUD_COMPANION.md`.

- Shared sizing now sends a stable per-install phone UUID across viewport,
  replay, counts and reattach, following newer iOS device-priority identity.
  Names are bounded/sanitized; the UUID is stored outside backups and persists
  independently of login. **19 focused JVM checks passed**, main compilation
  passed (36 seconds); no APK/device run. Physical priority/upgrade acceptance
  remains. See `TERMINAL_SHARED_SIZING.md`.

- The Cloud composition audit is recorded in `CLOUD_COMPANION.md`; Android's
  API foundation does not yet provide the actual machine/terminal UI. This belongs
  to the broad delta audit, and the global upstream pin remains unchanged.

- Composer Send now requires explicit `submitted: true` before clearing its draft.
  A refused, absent or malformed submit acknowledgment preserves text/files and
  never retries automatically; Insert without Enter retains its existing behavior.
  **18 focused JVM checks passed** and main/instrumentation compilation passed in
  one 28-second run. No APK/device run; include composer acknowledgment UI in the
  next terminal integration batch. See `TERMINAL_INPUT_DELIVERY.md`.

- The terminal shortcut row now follows the newer iOS resting-edge and active-
  gesture geometry behavior, with a gradual leading fade. **17 focused JVM
  checks passed** and final main/instrumentation compilation passed. Two Android
  geometry cases are queued with existing arrow-pad cases for the next terminal
  integration batch; no APK/emulator. Physical gestures, RTL/dynamic text and
  matched iOS UI remain open. This scoped port does not advance the global pin.
  See [TERMINAL_SHORTCUTS.md](TERMINAL_SHORTCUTS.md#shortcut-row-geometry-follow-up--2026-10-06).

- Word and workbook previews now pass **two distinct Android runtime checks**
  on API37/16 KiB, including painted pixels, workbook navigation and composition
  state restoration. Screenshot review found and fixed shrunken spreadsheet
  columns; the strengthened workbook rerun passed. All 17 pinned assets matched
  inside the APK. Gradle and the sole reused emulator are stopped, with no new
  AVD, Pixel install or signed promotion. Real routes/actions, process recovery,
  Word zoom/restoration, broader formats and iOS/Pixel comparison remain open.
  See [DOCUMENT_FORMATS.md](DOCUMENT_FORMATS.md#office-viewer-integration).

- Offline XLSX preview now shares the Office reader with DOCX: sheet navigation,
  author-formatted/cached values, hidden rows/columns, merges, basic styles,
  bounded grid windows and saved reader location. **23 JVM checks and ten Node
  model checks passed**; 17 asset hashes matched. Visual/navigation/restoration
  cases for both Word and workbooks are queued for the next Android integration
  batch. Charts/drawings, broader Excel formatting, other Office formats and
  actual Quick Look/Pixel comparison remain open. No APK/emulator or signed
  promotion. See [DOCUMENT_FORMATS.md](DOCUMENT_FORMATS.md#xlsx-and-shared-office-reader--2026-10-06).

- Offline DOCX preview is implemented in the shared Files/Changes/attachment
  viewer, with bundled rendering, bounded archive preparation, local-only loading
  and existing file actions. **20 JVM checks passed**, main and instrumentation
  compilation passed, and all **16** pinned viewer assets matched. A rich-document
  visual/security fixture is compiled but awaits the next Android integration
  batch; no APK/emulator or signed promotion. Broader Office formats and
  Quick Look-equivalent layout remain open. See [DOCUMENT_FORMATS.md](DOCUMENT_FORMATS.md).

- Combined Android push-settings/storage milestone: **nine runtime tests passed
  in 25.383 seconds** on API37/16 KiB. Four setup/Mac-controls UI cases, two
  key/reply regressions and three real Keystore cleanup-hook cases now have device
  evidence. Three fixture captures inspected; no recorded app crash/ANR. One
  57-second combined APK build plus a 3-second test-only capture build. Gradle and
  the sole reused emulator are stopped; no new AVD or signed promotion. Actual
  WorkManager/Firebase/provider and physical Mac/Pixel gates remain open. See
  [PUSH_DELIVERY.md](PUSH_DELIVERY.md#android-settings-and-cleanup-storage-integration--2026-10-06).

- Source events now have an encrypted durable handoff through preparation and
  delivery admission, with identical-ciphertext recovery after an actual process
  kill in the acknowledgment gap. **24 Node checks passed on Node22/26**. Latest
  cached cmux producer recheck confirms that history alone misses phone alerts.
  The user was asked whether a custom Mac source hook/build is acceptable; that
  source decision is pending. Adapter/scheduler, provisioning and live delivery
  remain open. No APK/emulator/listener. See
  [PUSH_DELIVERY.md](PUSH_DELIVERY.md#durable-source-to-delivery-handoff--2026-10-06).

- The Mac helper components now have a composed forwarding pipeline with live
  host policy and encrypted per-event privacy/identity metadata across restarts.
  **49 Node checks passed on Node22/26**, including privacy tightening during OAuth,
  account/key retirement and atomic fanout. The actual authenticated source remains
  open: native phone alerts can occur without history insertion, so a feed diff
  cannot establish parity. Next: qualifying source/dismissals, durable replay,
  key/TLS/Firebase provisioning and test/device delivery. No APK/emulator/listener.
  See [PUSH_DELIVERY.md](PUSH_DELIVERY.md#forwarding-pipeline-and-durable-privacy-policy--2026-10-06).

- Android Settings now exposes the connected Mac's authenticated forwarding,
  away/always and privacy controls, with event/poll refresh, stale-state recovery
  and account/connection checks at RPC write admission. **Nine JVM checks passed**;
  main/instrumentation compile. One new Compose case awaits the next integration
  run. The official Apple test-alert RPC is not presented as Android delivery.
  Helper source/policy/provisioning, its test action and physical acceptance remain
  open. No APK/emulator. See
  [PUSH_DELIVERY.md](PUSH_DELIVERY.md#authenticated-mac-forwarding-controls--2026-10-06).

- Automatic Android helper maintenance is now wired to token/account/key changes,
  opt-out/logout and recovery. Durable proof/receipt sequencing settles an uncertain
  token update before the next token or removal; encrypted cleanup credentials
  survive account clearing within a bounded window. Settings shows update/repair
  status. **32 JVM checks passed**, including a real local HTTPS/CryptoKit/SQLite
  coordinator flow with lost receipts, newer tokens and logout; **36 Node checks
  passed on Node22/26**. Main/instrumentation sources compile. Keystore/WorkManager
  runtime checks, host provisioning/source/policy and forwarding/test controls,
  Firebase configuration and actual Pixel delivery remain open. No APK/emulator.
  See [PUSH_DELIVERY.md](PUSH_DELIVERY.md#automatic-android-registration-maintenance--2026-10-06).

- Existing helper registrations now have matching Android/Node renewal/removal
  protocols, with phone-key proof, atomic mutation/receipt storage and recovery
  after a host restart. FCM-retired tokens retain bounded encrypted phone trust
  for renewal without receiving alerts. **34 Node checks passed on Node22/26**
  and **19 JVM checks passed**, including real local HTTPS/CryptoKit/SQLite
  enroll → renew → remove. Automatic Android queue/token/logout wiring is the
  next step; production provisioning and device acceptance remain open. No APK
  or emulator. See
  [PUSH_DELIVERY.md](PUSH_DELIVERY.md#authenticated-registration-maintenance-protocol--2026-10-06).

- Native Settings now exposes push consent/readiness, per-Mac pairing progress,
  QR/paste offer review, endpoint/fingerprint confirmation with expiry, cancellation
  and retry. Account/token/key changes fence stale confirmation. **25 JVM checks
  passed**; main/instrumentation sources compile, with three new Compose cases
  awaiting execution. No APK/emulator run. The iOS status/toggle/repair structure
  was checked at the scoped reference in the linked evidence. Firebase/host
  provisioning, automatic renewal/revocation, Mac forwarding/privacy/test controls,
  UI/worker runtime acceptance and real Pixel delivery remain open. See
  [PUSH_DELIVERY.md](PUSH_DELIVERY.md#push-settings-and-offer-confirmation--2026-10-06).

- Android now persists initial helper enrollment before network writes, retries
  the same request/proof after lost responses, and commits a verified receipt with
  its helper pin. Startup/resume/boot recovery and a WorkManager worker are wired.
  **26 JVM checks passed**, including nine new restart/retirement/disk-failure
  cases and the existing real local TLS/CryptoKit/SQLite handshake. Main and test
  sources compile; no APK/emulator run. Android Keystore/worker restart acceptance,
  confirmation UI, automatic token renewal/revocation, production host provisioning
  and Firebase/Pixel acceptance remain open. See
  [PUSH_DELIVERY.md](PUSH_DELIVERY.md#durable-enrollment-recovery--2026-10-06).

- Helper enrollment now has production Kotlin/Node HTTPS transport with bounded
  requests, no redirects/ambient credentials, retry timing and admission checks.
  **13 JVM checks passed**, including a real local TLS/CryptoKit/SQLite handshake
  and repeated finish; **13 Node checks passed** on Node22/26 after fixing a
  timed-out upload capacity leak. Main/instrumentation compilation passed. No APK
  or emulator. Durable recovery was added in the subsequent checkpoint above;
  automatic renewal/revocation, setup UI and real
  hosting/Firebase/Pixel acceptance remain open. See
  [PUSH_DELIVERY.md](PUSH_DELIVERY.md#helper-https-transport--2026-10-06).

- Initial helper enrollment now has matching Node/Android challenge proofs, real
  HPKE key-possession checks, generation-safe registration commit and authenticated
  acknowledgment before local helper pinning. **16 Node checks passed** on Node22/26;
  **15 JVM checks passed**, including a CryptoKit-to-Kotlin fixture. HTTPS transport,
  durable recovery, automatic renewal/revocation and confirmation UI remain open.
  No live enrollment, APK or emulator run. See
  [PUSH_DELIVERY.md](PUSH_DELIVERY.md#helper-enrollment-handshake--source-checkpoint-2026-10-06).

- Android now keeps independent helper sender pins alongside native Mac keys;
  incoming helper pushes reuse delivery/replay handling, while replies remain
  encrypted for the official Mac. Helper retirement fences actions and queued
  replies, including restoration and direct/relay fallback. **54 focused JVM
  checks passed**; main/instrumentation compilation passed. The authenticated
  enrollment handshake/UI, helper service and Firebase setup remain open; no
  real enrollment/delivery or APK/emulator run occurred. See
  [PUSH_DELIVERY.md](PUSH_DELIVERY.md#independent-helper-sender-trust--source-checkpoint-2026-10-06).

- Android now has explicit-consent token acquisition, renewal and durable SDK
  deletion across logout, with generation checks for late callbacks and serialized
  provider operations. Startup/resume/boot/settings and WorkManager recovery are
  wired. **Ten JVM checks passed**; main/instrumentation compilation passed.
  Firebase configuration and the helper enrollment UI are absent, so cloud push
  remains disabled. No APK/emulator was created. Native SDK/Keystore/WorkManager
  runtime and real delivery remain open. See
  [PUSH_DELIVERY.md](PUSH_DELIVERY.md#android-token-lifecycle--source-checkpoint-2026-10-06).

- Zoomed PDF detail integration now passes **four Android checks in 18.481
  seconds**: crop/rotation pixels, cancellation/renderer reuse, visible fine-line
  contrast after magnification/recreation, and XYZ navigation/history. Four
  screenshots inspected; no recorded crash/ANR markers. The final combined APK
  build passed in 39 seconds after a test-only Canvas overload correction.
  Emulator stopped/reaped; no new AVD or signed promotion. Broad pinch/pan,
  overlay alignment, memory and physical acceptance remain open. See
  [PDF_DESTINATIONS.md](PDF_DESTINATIONS.md#detail-rendering-integration--2026-10-06).

- The PDF floating-menu integration now passes **three Android checks in 63.572
  seconds**: unchanged viewport/visible native actions, Select all across
  background/resume, exact copy/clear, cross-page drag/autoscroll/recreation and
  the existing search/copy flow. Four screenshots inspected; no recorded app
  crash/ANR. All actions fit onscreen, so overflow/large-font/TalkBack/high-zoom
  and physical acceptance remain open. One 56-second combined APK build; Gradle
  stopped before the sole existing emulator, which is now stopped/reaped. See
  [PDF_SELECTION.md](PDF_SELECTION.md#floating-menu-integration--2026-10-06).

- PDF selection now uses a native floating Copy/Select all/Select page/Clear menu
  instead of rows that change document height. It follows visible selection bounds,
  hides during scroll/handle drag/copy and retains accessible page actions.
  **11 JVM checks passed** and main/instrumentation compilation passed; the changed
  device test checks stable viewport geometry but is queued for the next combined
  viewer milestone. No APK/emulator was built or launched. See
  [PDF_SELECTION.md](PDF_SELECTION.md#floating-selection-controls--source-checkpoint-2026-10-06).

- Large push dismissals now split into deterministic provider-sized parts with
  complete ID coverage and atomic local queue admission. Late capacity, identity,
  storage or authorization failures cannot admit only a prefix. **61 Node checks
  passed** on Node22.16.0 and Node26.8.2, including actual Mac encryption sizing.
  Source subscription/replay, oversized notify handling and real cloud/Pixel
  delivery remain open. See
  [PUSH_DELIVERY.md](PUSH_DELIVERY.md#large-dismissal-admission-checkpoint--2026-10-06).

- Push event preparation and Mac CryptoKit sealing now feed the existing queue
  contract with redacted/bounded content, independently matched identity and
  original event ID/expiry. **50 Node checks passed** on Node22.16.0 and Node26.8.2,
  including the pinned upstream decryptor opening production-adapter envelopes.
  No cloud/phone delivery is claimed. Host subscription/policy, Keychain lifecycle,
  explicit helper trust/enrollment, oversized event handling, Firebase setup and
  Android token lifecycle remain integration work. See
  [PUSH_DELIVERY.md](PUSH_DELIVERY.md#event-sealing-checkpoint--2026-10-06).

- Push host registration storage and scheduling now integrate with the encrypted
  outbox/FCM sender: encrypted enrollment, token/key generations, exact retirement,
  scope revocation, live-policy checks, retry/expiry scheduling and awaited shutdown.
  **38 Node checks passed** on both Node22.16.0 and Node26.8.2. No cloud resource,
  listener, token enrollment or Android build was created. Firebase project choice,
  authenticated helper enrollment, host policy/subscription/key lifecycle, Android
  token lifecycle and real provider/Pixel/Doze acceptance remain open. See
  [PUSH_DELIVERY.md](PUSH_DELIVERY.md#registration-and-scheduling-checkpoint--2026-10-06).

- Combined PDF content-fit/selection milestone: **16 distinct Android cases now
  have passing evidence**. Initial run: 15/16 in 73.434 seconds. The selection
  fixture expected an offscreen endpoint to remain visible; screenshots retained
  selection/position. A stronger real edge-hold drag proves autoscroll, recreation,
  visible highlights and exact two-page clipboard text; it passed in 28.141 seconds.
  Content-fit pixels/history, four geometry-extraction cases and existing PDF
  navigation/zoom/search checks passed. One 62-second app/test build, then 17/16-second
  test-only builds with an unchanged app APK. Screenshots inspected; no recorded
  crash/ANR markers. Emulator/Gradle stopped, no new AVD or signed promotion.
  Broad selection/format/UI and physical acceptance remain open. See
  [PDF_SELECTION.md](PDF_SELECTION.md#combined-content-fit-and-selection-integration--2026-10-06).

- On-page PDF word/range selection now has highlights, draggable handles,
  cross-page ranges, edge scrolling, Copy/Select page/Select all/Clear, accessible
  endpoint actions and saved offsets. Glyph baselines follow crop/rotation.
  **16 focused JVM cases passed** and main/instrumentation compilation passed.
  Two Android extraction/drag/recreation/pixel/copy cases are compiled, not run;
  combine them with content-fit coverage at the next viewer milestone. See
  [PDF_SELECTION.md](PDF_SELECTION.md). Pixel, broad font/gesture/accessibility
  and large-copy acceptance remain open.

- PDF FitB/FitBH/FitBV now resolve geometric content bounds lazily, including
  vectors, glyph outlines and images, with clipped forms, rotation and explicit
  axis retention. Blank pages fit the page; extraction errors retain the current
  location and show the existing link error. **20 focused JVM cases passed**;
  Android extraction/pixel acceptance remains queued. See the new
  [content-bound feature batch](PDF_DESTINATIONS.md#content-bounding-box-destinations--2026-10-06).

- Connection checklist correction: pre-tag/raw-grant identity adoption,
  background successor refresh, explicit-confirmation upgrade and appearance
  recovery are already implemented. The 20-case identity milestone and later
  appearance milestone are recorded in ATTACH_TICKETS.md. Physical Mac/Pixel
  acceptance remains open. A scoped read at upstream `c2715faa02c260b07012bc0b386597cfb333021d`
  confirms `CMUXMobileRootScene.makeBackedUpPairedMacStore` still returns the local
  team-scoped store without constructing the dormant cloud-backup decorator.
  No cloud-restore behavior is inferred from the unused library. Global pins
  are unchanged.

- Combined media/PDF milestone: **13 distinct Android cases now have passing
  evidence**, including actual PiP video pixels/cleanup, PDF XYZ/FitR pixels,
  recreation/return, pinch focal-point retention, magnified vertical scrolling,
  crop/rotation extraction, search/copy, short pages and Changes restoration.
  Fixed return-control accessibility ownership and a pointer-up event with no
  valid centroid that reset PDF scroll. Final focused run: **3/3 passed**.
  Screenshots inspected; no final crash/ANR markers. Existing emulator and
  Gradle stopped; no new AVD or signed promotion. PiP browser/account/system
  controls, PDF bounding-box fit/selection and physical acceptance remain open.
  See [the integration evidence](PDF_DESTINATIONS.md#combined-viewer-integration--2026-10-06).

- PDF internal-link destinations now carry X/Y and XYZ zoom; the reader applies
  bounded magnification and keeps a saved return history. Restoring search
  results no longer overrides the reading location. Extraction, pixel zoom and
  link/return Android checks passed in the combined milestone above.
  Main/instrumentation Kotlin compilation and **19 focused JVM cases passed**.
  Follow-up shared document zoom makes magnified pages fully scrollable, and
  implements Fit/FitH/FitV/FitR plus null-coordinate retention through crop/rotation.
  Main/instrumentation compilation and **24 focused JVM cases passed** for that
  follow-up. The listed runtime cases now pass; FitB/FitBH/FitBV content bounding boxes,
  selection handles, high-zoom quality and physical checks remain open. See
  [PDF_DESTINATIONS.md](PDF_DESTINATIONS.md).

- Video picture-in-picture feature batch implemented in the shared media viewer,
  with dedicated main/browser playback Activities, independent private file
  lifetime, account retirement, system controls and paused return bookmarks.
  Main and instrumentation Kotlin compilation and **15 JVM cases passed**.
  Both new Android cases now pass, including visible PiP video and file cleanup.
  Browser handoff, broader lifecycle/track retention, system controls, account
  retirement and physical acceptance remain unverified. No additional AVD.
  See [MEDIA_PICTURE_IN_PICTURE.md](MEDIA_PICTURE_IN_PICTURE.md).

- Combined notice feed/UI milestone: **all 11 distinct Android cases now have
  passing evidence** across initial and focused follow-up runs; **22 JVM cases
  passed**. This covers regular/compact/scroll archive layouts, replay without
  acknowledgement, debug launch suppression, short/long/back sheet resizing,
  actual public-feed initialization and saved-ledger recovery with a failed
  fetch, plus visible local web rendering/retry. Fixed TLS cleanup on Main and
  a natural-height cache invalidation that left short sheets full-height.
  Enlarged-text/disabled-animation and landscape variants passed; screenshots
  inspected. Sole emulator and Gradle stopped; no additional AVD or signed
  promotion. Real OS offline/process restart, cookie exchange and physical
  acceptance remain open. See [NOTICE_LAYOUT_REPLAY.md](NOTICE_LAYOUT_REPLAY.md#combined-feedui-integration--2026-10-06).

- The Android-owned public notice feed retains the existing two native IDs, with
  no new release claim or production override. Endpoint maintenance and bounded
  anonymous transport are documented in [WHATS_NEW.md](WHATS_NEW.md#android-owned-announcement-feed--2026-10-06)
  and [feed maintenance](../distribution/README.md).

- Combined composer layout/menu milestone: **12/12 initial Android cases passed**;
  after review corrections **5/5 focused cases passed**, totaling **14 distinct
  passing cases**, plus **six provider/ownership JVM cases**. Native/SSH inline
  Send, multiline cap, IME/rich paste, retry, real photo picker and local provider
  error behavior were checked. Menu ownership now closes stale popups on terminal
  replacement; provider I/O/access errors no longer display raw paths. Screenshots
  inspected; no crash/ANR markers. DEX: 3,511 methods, largest 13,156 code units,
  none rejected. Existing AVD and Gradle stopped. Physical acceptance and the
  broader completion gates above remain open. See [the milestone](TERMINAL_COMPOSER_LAYOUT.md#combined-layoutmenu-integration--2026-10-06)
  and [attachment menu](COMPOSER_ATTACHMENT_MENU.md).

- Combined preview/dictation/media milestone: **all ten distinct queued Android
  cases now have passing evidence** across the initial and focused follow-up
  runs. Fixed visible keyboard return after dictation Send and kept attachment
  errors beside the composer while terminal output remains visible. The task
  fixture now performs authenticated host capability discovery before Create.
  DEX: 3,535 methods, largest 13,156 code units, none rejected. Existing AVD and
  Gradle stopped; no new AVD, Pixel action or signed-release promotion. See
  [the detailed milestone](CONTENT_PREVIEW_LIFECYCLE.md#composer-integration-milestone--2026-10-06).

- Native/SSH composers include live dictation, explicit Send focus and 14-line
  editors. Native/New Task Photos accept photo/video library selections and
  continue past unreadable providers. Preview snapshots survive send completion
  until dismissal or ownership revocation. Their feature batches passed 22, 22
  and 31 focused JVM cases respectively; queued Android integration is now done.
  Real speech/permissions/Gboard, video decoding/cloud selection, picker and
  process restoration, SSH non-image support and physical acceptance remain open.
  See [dictation](COMPOSER_DICTATION.md), [media picker](COMPOSER_MEDIA_PICKER.md)
  and [preview lifecycle](CONTENT_PREVIEW_LIFECYCLE.md).

- Combined composer/appearance milestone: **five distinct Android cases now
  have unassisted passing evidence**, covering appearance acknowledgement recovery,
  task PDF page/file retention through Activity recreation, Open export lifetime,
  native staged image/text previews plus send retry, and SSH preview without
  sending. Initial chooser/provider fixture failures were corrected; visual
  review also caught a loading-frame image capture, and the stronger native
  pixel-check case passed. One app APK build; two test-only rebuilds, unchanged
  app hash. Green PDF, cyan/blue images, text and chip screenshots inspected.
  No new crash/ANR events. Sole AVD stopped/reaped; no new AVD or physical-device
  claim. See [CONTENT_PREVIEW_LIFECYCLE.md](CONTENT_PREVIEW_LIFECYCLE.md#combined-composer-preview-milestone--2026-10-06).

- Native/SSH terminal attachment chips now open the shared full viewer, with
  exact terminal/account/binding ownership. Task and terminal chips use iOS-like
  thumbnail sizes, file labels and separate remove controls. **33 focused JVM
  checks passed**; main/instrumentation compilation passed. Native image/text
  and SSH visible-image checks passed in the combined milestone above (cases in
  [CONTENT_PREVIEW_LIFECYCLE.md](CONTENT_PREVIEW_LIFECYCLE.md#native-and-ssh-terminal-attachment-previews--2026-10-06)).
  Keyboard return, full visual/accessibility, process death and physical-device
  acceptance remain open.

- Task attachments now use the full shared image/PDF/text/Markdown/media viewer
  with filename/Done and Open/Share/Save. Exact staged bytes, retained ownership,
  cancellation/retry cleanup and cache leases replace the image-only popup.
  Preview remains available while task mutation is disabled. **30 focused JVM
  checks passed** and main/instrumentation compilation passed. The two-color
  PDF/Activity-recreation/disabled-editor and Open export-lifetime cases passed
  in the combined milestone above. Physical acceptance, process
  death and the full Quick Look format/visual matrix remain open. See
  [CONTENT_PREVIEW_LIFECYCLE.md](CONTENT_PREVIEW_LIFECYCLE.md#task-composer-attachment-viewer--2026-10-06).

- Authenticated legacy upgrades now preserve custom computer name/color/icon.
  Pairing commits queue the move; atomic appearance receipts make cross-file
  recovery safe through restart, failed acknowledgement and later edits/resets.
  Exact tagged customization wins, sibling builds stay separate, and Forget
  disarms queued work before cleanup. UUID spelling changes retain other edited
  fields. **67 focused JVM checks passed**; main/instrumentation compilation
  passed (16 s final run). The Android Keystore/appearance-reload case passed
  in the combined milestone above. See the dated appearance section in
  [ATTACH_TICKETS.md](ATTACH_TICKETS.md).

- NativeScreen compiler-size obstacle addressed: composition-local state and
  separate feed/terminal, foreground-effect and rendering groups reduce the
  largest debug project DEX method from **44,283 to 13,147 code units**. All
  3,497 associated methods are below ART's size/register guard. **Ten distinct
  Android screen cases have unassisted passing evidence** (initial nine passes
  and one stale filter-selector failure; corrected follow-up passed). No new
  compiler-size warning, crash or ANR; relevant screenshots inspected. One
  17 s app/test packaging build, then test-only rebuilds; sole AVD stopped/reaped.
  This is not a frame-time benchmark or Pixel/Mac acceptance. The new
  `scripts/check-native-screen-size.py` can check future DEX archives without
  packaging an APK. See [SCREEN_COMPOSITION.md](SCREEN_COMPOSITION.md).

- Combined identity-upgrade Android milestone: **20 distinct cases have
  unassisted passing evidence** (17 Details/Keystore cases, two corrected recovery
  flows, and the final explicit re-pair/build/reconnect/visible-terminal case).
  The screen test exposed and verified a same-locator restart fix. Initial stale
  external-link test failures and an IME-assisted run are preserved, not counted
  as clean passes. Final strengthened terminal case passed in 17.638 s and its
  output screenshot was inspected. One 96 s app/test build; subsequent builds
  changed only the test APK. Existing API37/16 KiB AVD stopped/reaped; no new AVD,
  no Pixel/Mac or signed-release acceptance. See `ATTACH_TICKETS.md` and local
  `captures/runtime/confirmed-upgrade-milestone/`. Next concrete observations:
  NativeScreen's compilation-size warning is addressed above; frame-time
  profiling and reconciliation of remaining obsolete raw-launch fixtures with
  actual entry policy remain.

- Fresh confirmation of an older saved Mac now takes the explicit pairing path
  and commits its new address grant and saved identity together. Existing
  unscoped ownership/history survives; revocation, removal, hiding, account
  changes and write failures cannot leave a partial upgrade. The admitted session
  switches to its new grant after commit. **108 focused JVM tests passed**;
  main/instrumentation Kotlin compiled (20 s final run). New Keystore case is
  compiled only. Confirmation UI/session continuity and actual Mac/Pixel upgrade
  remain queued; broader legacy metadata reconciliation remains. No APK/AVD run.
  See `ATTACH_TICKETS.md`, `TAILSCALE_CONNECTION.md` and local
  `captures/runtime/confirmed-legacy-upgrade/`.

- Background workspace feeds and the notification service now persist authenticated
  identity/locator upgrades and retire the captured session before subscribing or
  delivering data. Selection remains on the same computer; foreground reconnect
  follows the scoped successor's history. **104 focused JVM tests passed**;
  main/instrumentation Kotlin compiled (68 s). New Keystore reload case compiled
  only; no APK/AVD/device run. Real foreground/feed/service restart acceptance,
  fresh explicit confirmation against an old untagged grant and broader metadata
  reconciliation remain. See `ATTACH_TICKETS.md`, `TAILSCALE_CONNECTION.md` and
  `captures/runtime/background-identity-refresh/`.

- Old raw Tailscale grants now support authenticated foreground build learning:
  exact address/device and any provisional build are checked, the learned method
  is applied before ticket/workspace admission, and row plus grant commit together.
  History stays intact; the old ticket is discarded and the foreground restarts
  under the new binding before terminal input. **123 focused JVM tests passed**;
  main/instrumentation Kotlin compiled (21 s). New Android Keystore reload case
  is compiled only, queued for the next milestone. No APK/AVD/device run.
  Background-only adoption, fresh confirmation against an old grant, actual
  restart/upgrade and metadata reconciliation remain. See `ATTACH_TICKETS.md`,
  `TAILSCALE_CONNECTION.md` and `captures/runtime/legacy-raw-build/`.

- Combined legacy/Details milestone at `af1d085`: one debug/test APK build (74 s),
  **14/14 Android UI/Keystore tests passed in 80.669 s** on the existing API37 /
  16 KiB AVD. Previously queued raw-only Details and legacy-build reload cases
  are now executed, plus connection display, private-address, ticket and directory
  regressions. Seven screenshots reviewed; no new runtime crash/ANR, final crash
  buffer empty, sole emulator stopped/reaped. No Pixel/Mac or signed-release
  acceptance. See `ATTACH_TICKETS.md`, `COMPUTER_DETAILS.md` and local
  `captures/runtime/legacy-details-milestone/`. The subsequent old-grant build
  learning batch is documented above; its new runtime case remains queued.

- Sole-build directory enrichment now supplies provisional native routes for
  untagged native and raw saved Macs. Details/method selection and foreground
  reconnect use the exact scoped build; host authentication still supplies the
  final tag. Existing tickets cannot cross a provisional build boundary, even
  with an unchanged source. **104 focused JVM tests passed**; main/instrumentation
  Kotlin compile passed (20 s final run). An initial screen method-size failure
  was resolved by extracting the reconnect-key helper. No APK/AVD. The queued
  Details and Keystore tests passed in the combined milestone above;
  live old-raw-grant build adoption and Pixel/Mac recovery remain open. See
  `ATTACH_TICKETS.md` and `captures/runtime/legacy-directory-routes/`.

- Authenticated legacy build adoption now preserves a captured or sole owned
  untagged computer's draft/notification/selection origins, keeps sibling builds
  separate and removes ticket credentials whose old build binding has changed.
  58 focused JVM tests and main/instrumentation Kotlin compilation passed (23 s).
  The Keystore reload case subsequently passed in the combined milestone above.
  Pre-tag directory route enrichment, raw Tailscale handshake/grant adoption,
  metadata reconciliation and physical acceptance remain. See `ATTACH_TICKETS.md`.

- Saved raw-primary reconnect now uses current exact Mac/build grants after an
  address edit, with captured row/account/method admission and transport-only
  fallback. Old tickets are restricted to their original source; replacement
  destinations use authenticated manual-ticket/account admission. Cold-start
  persistence retains public locator, ticket and history without recreating an
  old grant. **94 focused JVM tests passed**, main/instrumentation Kotlin
  compilation passed (36 s final run). An initial test-constructor compilation
  error was corrected and logged. No APK/emulator; Pixel absent on ADB. Physical
  address-edit/reconnect/notification acceptance remains; see `TAILSCALE_CONNECTION.md`
  and `captures/runtime/edited-tailscale-reconnect/`.

- Scoped Tailscale-only rows now expose Computer Details, saved-route diagnostics
  and shared feed-owned Keep Mac Awake controls without native discovery. Remote-
  confirmed cleanup includes only the captured scoped identity. 46 focused JVM
  tests and main/instrumentation Kotlin compilation passed (20 s final run).
  The new Android UI case is queued for the next milestone; no APK/emulator.
  Main raw-primary reconnect with edited/replacement grants is implemented by
  the following checkpoint; legacy pre-tag adoption and physical acceptance remain. See `COMPUTER_DETAILS.md`.

- Combined connection-route Android milestone: one debug/test APK build (72 s),
  **8/8 ticket/Keystore cases passed in 30.332 s** on the existing API37 / 16 KiB
  AVD at `a803fe0` plus a test screenshot line. External reuse/revocation UI and
  directory-enrichment persistence checks are now executed. All three chooser
  screenshots were inspected. A pre-test System UI ANR was captured and dismissed;
  no new crash/ANR during tests, final crash buffer empty, emulator stopped/reaped.
  No physical or signed-release acceptance. See `ATTACH_TICKETS.md` and local
  `captures/runtime/connection-route-milestone/`. No new virtual device was created.

- Authenticated account discovery now enriches an existing owned Tailscale pairing
  with an unambiguous native identity for the exact device/build. It preserves
  ticket/grants/origin/history and never removes a native pin on an empty or failed
  discovery snapshot. Pending reconnects can adopt only the locator refresh.
  All 55 focused JVM cases pass and main/instrumentation Kotlin compile (17 s);
  the Keystore reload test subsequently passed in the combined milestone above.
  The surrounding upstream caller also corrected the prior Direct checklist:
  fresh in-app exact-address authorization can override the stored Direct choice;
  no new restriction is needed. See `ATTACH_TICKETS.md`. Real upgrade/reconnect
  acceptance and legacy pre-tag identity adoption remain open. Scoped Tailscale-only
  Details is implemented by the newer checkpoint above.

- External legacy tickets now reuse independently authenticated exact Tailscale
  destinations under captured account/build/method authority. External confirmation
  never mints fresh consent; raw transport checks the captured grant during auth
  and I/O. A DNS/multi-route source can acquire a public ticket alias only after
  authentication and atomic original-grant validation. Native cold-launch proposals
  remain available while discovery loads. All 78 focused JVM cases pass; main
  and instrumentation Kotlin compile (17 s). The Compose reuse/revocation check
  subsequently passed in the combined Android milestone above.
  See `ATTACH_TICKETS.md` and `captures/runtime/external-ticket-grants/`.

- Tailscale ticket acceptance now preserves an existing authenticated native
  locator for the same owner/device/build. Automatic/Direct select that native
  route without applying the raw ticket to it; authenticated promotion preserves
  origin/history and retires uncovered ticket context. Raw-grant removal does
  not erase independent native authority. All 85 focused JVM cases pass and
  main/instrumentation Kotlin compile (32 s); no APK/emulator. Physical reconnect
  acceptance, discovery-only legacy upgrade, truly Tailscale-only Details and
  external tickets using stored grants remain open. See `ATTACH_TICKETS.md`.

- Saved legacy Tailscale reconnects now enforce the per-build connection method
  and captured epoch before authentication and during I/O. Direct cannot use a
  retained raw route; Automatic cannot use it when an exact owned native identity
  is retained. Fresh explicit entry stays distinct. Fifty-two focused JVM cases
  pass; main/instrumentation compile, no APK/emulator. The scoped source comparison
  identified route-retention/legacy-upgrade, Tailscale-only Details and already-
  authorized external ticket gaps; see `ATTACH_TICKETS.md`. Physical acceptance
  and complete saved-route parity remain open.

- Combined notification/PDF milestone at clean `ca00fde`: one debug/test APK
  build (80 s), **10/10 Android cases passed in 41.576 s** on the existing API37 /
  16 KiB AVD. Covers two notification gesture/membership cases, three empty-state
  recovery cases and five PDF cases including the compatibility engine. Visible
  native PDF highlights and empty timeout UI were inspected. No new crash/ANR,
  empty crash buffer, font scale 1.0, AVD stopped/reaped. No physical/signed release
  acceptance. Evidence: `captures/runtime/feed-pdf-milestone/`. This supersedes
  the queued runtime checks in the next two source-batch entries.

- PDF text/search/word-copy controls now have a compatibility engine below API35
  using the existing pinned parser. API35+ keeps the native engine; rendering is
  still native on all versions. Crop/rotation geometry, normalized text offsets,
  Unicode word lookup and bounded page caching are implemented. Fourteen focused
  JVM checks pass; main/instrumentation compile. Two new Android cases passed in
  the milestone above, including forced compatibility mode on the existing
  AVD. Older-Android runtime/font coverage and full PDFKit selection/destination
  behavior remain open; see `CONTENT_PREVIEW_LIFECYCLE.md`.

- Notification rows now support revealed/full read swipes with stable intent and
  current action admission. The main feed reconciles all keyed rows and anchors
  live changes; history controls, browser notification geometry and native rich
  empty/retry content now use measured deferral. Fourteen focused feed JVM checks
  pass; main/instrumentation compile, and three new UI cases passed in the
  milestone above. This supersedes the remaining implementation gaps for native
  rich empty and browser notification-row geometry in the older checkpoint below.
  Runtime/route/recycling/large-font/accessibility/performance/physical acceptance
  remain open. See `WORKSPACE_ROWS.md`; no APK/emulator was started for this batch.

- Explicit Mac/SSH notices and browser progress/error/status/empty/load-more rows
  now share body membership deferral and viewport anchors. Their measured heights
  wait during gestures; cached actions check the current row. Group drawing follows
  measured deferral, and indentation is measured inside the workspace body.
  Group callbacks recheck capabilities/pinning. The native rich empty/recovery view,
  notification-row geometry, recycling, font/width changes and broader physical/
  performance acceptance remain open. Combined milestone: 21 unique Android cases
  passed (14 initial passes, then 7/7 on focused rerun after fixture corrections
  and a System UI boot-ANR interruption). Production APK was unchanged between
  runs; no new retry-run crash/ANR events. AVD stopped/reaped, no physical/signed
  acceptance. See `WORKSPACE_ROWS.md` and `captures/runtime/workspace-chrome/`.
  This supersedes the pending runtime status of the previous measured-row batch.

- Workspace body rows now distinguish equal-height content refresh from measured
  geometry changes during gestures. Shared main/browser hold state and standalone
  swipe state defer taller/shorter visual models until release; revealed read
  actions preserve their intent while callbacks check current capabilities.
  Cached revoked actions and pending close confirmations are retired. Source and
  instrumentation compile; three new Android cases await the next combined
  milestone. Group/header/footer/status geometry and runtime/performance/physical
  acceptance remain open. See `WORKSPACE_ROWS.md`.

- Combined workspace/PDF milestone: **14/14 Android tests passed in 66.575 s**
  on the existing API37/16 KiB AVD; eight focused PDF JVM tests also passed.
  This supersedes the queued Android status in the three workspace source batches
  and PDF source batch below. Direct/named/GoTo PDF links now use a pinned metadata
  parser after the native extractor omitted valid direct destinations. Actual
  enlarged text, workspace anchoring/gesture retirement and PDF link/search/
  recreation/copy paths passed. Debug APKs built; font scale restored, no final
  crash/ANR entries, AVD stopped/reaped. Physical acceptance and a new signed
  delivery remain pending; see `WORKSPACE_ROWS.md` and `CONTENT_PREVIEW_LIFECYCLE.md`.

- Main/browser workspace membership and order now wait through scroll/fling,
  row holds and open swipes. Surviving content stays current; removed rows are
  inert and cached callbacks check membership/lifetime. Recycled swipe owners
  release their hold; SSH ownership uses qualified row keys. Four new policy
  cases and eight anchor cases pass. Android swipe/retirement cases are queued
  for the combined milestone. Row-height/action changes and status/footer
  geometry still need full reconciliation; see `WORKSPACE_ROWS.md`.

- Native/browser workspace lists now select a stable visible neighbor for idle
  structural updates, include connection-status prefix indices, show new rows at
  absolute top and omit live placement animations. Eight focused policy cases
  cover moved/deleted rows, pixel boundaries and a 50,000-row feed; Android bounds
  cases are queued for the integration milestone. Full gesture-time geometry
  buffering and height/action reconciliation remain implementation work. See
  `WORKSPACE_ROWS.md`; no APK/emulator was started.

- Workspace view options now use the iOS-style illustrated sort tiles and stay
  open during sort/read-state/machine changes. Custom Order no longer opens its
  editor automatically in either the main or separate-browser sidebar. Main and
  instrumentation Kotlin compile; revised interaction cases await the next
  integration milestone, including enlarged text and TalkBack. The newer viewport
  checkpoint above starts anchoring; full gesture deferral remains open. See
  `WORKSPACE_ROWS.md`. No APK/emulator was used for this batch.

- Added the encrypted sender outbox with atomic local SQLite claims, crash/restart
  recovery, original ciphertext/expiry, bounded deduplication receipts, persisted
  backoff and exact-generation retirement intent. Local checks include SIGKILL
  and concurrent subprocesses. Credential-store key, scheduler, authenticated
  enrollment/policy store and live provider integration are still host work;
  see `PUSH_DELIVERY.md` and `push/README.md`. No Android build was needed.

- Added the FCM sender transport for the pending private-helper/backend choice:
  encrypted data-only delivery, explicit recipient/admission checks, expiry and
  size bounds, OAuth, retry timing and exact unregistered-token classification.
  Eleven local Node checks passed and join milestone CI. No cloud resources,
  registration or live push were created. Provider choice, authenticated helper
  enrollment/sender trust, durable outbox integration and Android token lifecycle remain open;
  see `PUSH_DELIVERY.md` and `push/README.md`.

- PDF interaction source now adds search/highlights, selectable page text and
  native word lookup, URL/internal links and accessibility actions on API35+.
  Query/selection coordinates restore through recreation. Eleven focused JVM
  checks passed; main/instrumentation Kotlin compiled. Two native-PDF/runtime UI
  checks are compiled but queued for the next combined milestone. Direct range
  handles/cross-page selection, older Android text support, link destination zoom,
  broad format handling and real iOS/Pixel UI acceptance remain open. No APK or
  emulator was started; see `CONTENT_PREVIEW_LIFECYCLE.md`.

- Export storage batch now reclaims aged malformed/missing journals, orphan
  payloads and partial receipts; preserves active/retryable saves; skips busy
  writers; and protects temporary exports across processes. One stable zero-byte
  range-lock file per store prevents new per-request lock-file growth. All 72
  focused JVM checks passed, including separate-process lock and killed-owner
  checks; main Kotlin compiled. Android process/provider acceptance and cleanup
  of historical empty lock files remain open. No new APK or emulator was used.
  See `CONTENT_PREVIEW_LIFECYCLE.md` and its retained failure evidence.

- Combined milestone at `132b6f6`: all 11 Android checks passed (86.408 s),
  covering media focus/session/track selection, visible subtitle restoration,
  playback lifecycle/controls and six pairing cases. Debug/test APKs built once
  for the batch; the existing AVD was stopped afterward. This supersedes the
  queued/compiled-only fixture status in the historical entries below. Physical
  acceptance and full parity remain open. See `CONTENT_PREVIEW_LIFECYCLE.md`.

- Follow-up reconnect review restores the historical saved-row path when no build
  tag is recorded: exact endpoint plus verified device/account only, without peer
  replacement. The source audit also resolves the Direct port question: iOS's
  current editor and v2 transport require a port, matching Android. Focused JVM
  checks and Kotlin compilation cover the correction; physical legacy upgrade
  and authenticated row enrichment remain open. See `DIRECT_CONNECTION.md`.

- Saved native connections can now follow a changed Iroh endpoint using their
  verified device/build within the current account/team directory. Fresh links
  still require their exact endpoint. Reconnect keys track the saved identity,
  old wires retire and Direct/Tailscale preferences remain in force. Focused
  runtime-policy tests and Kotlin compilation cover this batch; physical peer
  rotation and saved-ticket acceptance remain open. The optional field in iOS's
  shared Direct model does not mean its editor or v2 transport accepts a missing
  port; the follow-up above resolves that question. See `DIRECT_CONNECTION.md`.

- Pairing now preserves the in-app versus external-link boundary from the iOS
  source. External Tailscale links cannot create authorization; mixed external
  tickets offer native choices, while explicit in-app mixed tickets prefer only
  exact numeric Tailscale routes. Legacy tickets containing a self-dialing route
  are rejected as a whole. Focused JVM checks and main/instrumentation compilation
  pass; revised Android chooser cases and real Intent/Mac/Pixel connection checks
  await the next milestone. Full saved-method/default selection remains open.
  See `ATTACH_TICKETS.md` for the scoped source comparison and evidence.

- Media-session callbacks now connect platform/headset playback controls to the
  preview's existing player and audio-focus owner. Initial paused previews stay
  inactive; background/retired previews reject commands and released sessions
  cannot restart replacement views. Main/instrumentation Kotlin compile; the
  real-controller fixture is compiled but not run. Run it with tracks and focus
  at the next combined milestone, then verify real headset/system UI behavior
  and iOS equivalence. Routing/PiP and physical acceptance remain open.

- Alternate audio and embedded subtitle selection is implemented, with saved
  choices, subtitle Auto/Off and timed-text cues. Six selection-policy JVM tests
  passed and main/instrumentation Kotlin compile. Actual multi-track playback,
  rendered cues, rapid changes, saved playback position and recreation are queued
  with audio-focus checks for the next combined media milestone. Full styling,
  routing/PiP and physical acceptance remain open; see `CONTENT_PREVIEW_LIFECYCLE.md`.

- Media audio-focus ownership and output-disconnect handling are implemented.
  Paused previews leave other audio alone; transient focus recovery respects
  playback intent and user Pause, and speed changes defer until focus is granted.
  Ten focused JVM tests passed; main and Android tests compiled. The new platform
  focus fixture and existing media lifecycle cases are queued for the next combined
  device milestone. Actual headset/Bluetooth/call/audible-output acceptance and
  media-session/remote-control acceptance remain open (source implemented above).
  See `CONTENT_PREVIEW_LIFECYCLE.md`.
  The signed build at `19698a4` excluded this newer source batch and failed before
  building because GitHub could not acquire a hosted runner (run 37364121139).
  No new APK was produced; retry is deferred to the next combined milestone.

- Media previews now have seek/time controls, ±10-second skips, speed, mute and
  fullscreen with saved bookmarks/options and guarded player replacement. Four
  control-policy tests passed. Both focused Android media cases now pass after
  fixing delayed fullscreen-view foreground synchronization; combined integration
  evidence is in `CONTENT_PREVIEW_LIFECYCLE.md`.
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
  errors are readable. Six geometry tests and Android zoom/menu/action checks
  passed. Integration fixed a completed-Share busy-state leak; 14 export JVM
  checks and both Android export recovery cases passed.
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
  slow providers remain open. Malformed/orphan reclamation is implemented in the newer batch above;
  historical empty lock-file cleanup remains open. No physical Pixel was available.
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
