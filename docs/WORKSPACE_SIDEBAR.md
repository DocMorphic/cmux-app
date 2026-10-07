# Adaptive workspace navigation

## Reference and Android mapping

Scoped iOS source at `0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`:

- `Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/WorkspaceShellView.swift`
- `Packages/iOS/CmuxMobileWorkspace/Sources/CmuxMobileWorkspace/MobileWorkspaceShellLayoutPolicy.swift`

The iOS regular layout uses one navigation split view, a hideable workspace or
notification sidebar, and an active detail pane. Its sidebar width is 320–440
points, ideally 380. Either compact size class selects stacked navigation.

Android uses a 380 dp sidebar at window width **at least 840 dp** and height
**at least 480 dp**. This maps to expanded width and noncompact height in the
[Android window size guidance](https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes).
It intentionally keeps short landscape-phone windows stacked. Dimensions come
from window configuration, not the content height reduced by the IME. This is an
Android breakpoint adaptation, not a claim that SwiftUI points and size classes
are identical to Android's. The global upstream parity pin is unchanged.

## Implementation

`NativeWorkspaceShell` moves its sidebar and detail compositions between layouts
with `movableContentOf`. Layout changes and hiding the sidebar do not create a
second detail or replace the existing AndroidView renderer. Hidden content is
removed rather than left at zero size with active focus and accessibility nodes.
Sidebar visibility is saved and scoped to the account/team owner. An empty detail
has a selection prompt and a way to show a previously hidden sidebar.

The real Mac/SSH workspace and notification lists supply the sidebar. Filters,
sorting, search and row actions retain their original source ownership. The title
has its own row at sidebar width; workspace/notification destinations, search and
New Task use a plain bottom bar. Compact navigation keeps the phone tab capsule.
Workspace-level back buttons become a sidebar toggle when the sidebar is hidden;
nested file and changes navigation retain their own controls.

Sidebar search Back cancels search before a detail back handler can dismiss the
active pane. Hiding the sidebar or changing to a stacked detail commits the query
and dismisses its editor. Selecting an SSH destination retires native pane input;
a validated native route retires the SSH route. SSH workspace changes on the same
host key the detail by target, preventing the previous destination's local state
from being reused for a different workspace. Switching sidebar tabs retains the
detail and pending route. Merely filtering to an SSH computer no longer leaves an
already open SSH workspace.

## Scope still open

The separate `RoutedBrowserActivity` now receives a bounded global display
projection and owner-validated navigation tickets. It uses the same adaptive
shell while preserving process isolation. See [BROWSER_SIDEBAR.md](BROWSER_SIDEBAR.md)
for the protocol, verification and remaining browser-specific affordances.

Physical Pixel/Mac acceptance, actual OS window recreation during a live session,
large text/accessibility review, selection styling and wider UI refinement remain.
In-composition reflow and saved-screen restoration are distinct from process death.
The new shell does not establish production network/Iroh recovery or push delivery.

## Verification — 2026-10-04

- **18 JVM tests:** layout thresholds (2), search (7), SSH creation/navigation (9).
- **Six Android checks:** three wide checks in **51.848 s**, three compact checks
  in **110.830 s**, on the sole existing API37/16KB AVD. Real private SSH,
  cmux-tui and tmux providers were combined with a synthetic native Mac RPC peer.
- The shell check counted AndroidView creation/release and composition disposal:
  one renderer, no release/disposal during reflow, hiding/showing, tabs and search.
  The integration check retained terminal identity and an unsent draft, cancelled
  sidebar search with Back, committed the query on reflow, switched cmux/tmux/Mac
  destinations, and restored sidebar visibility and the selected SSH pane.
- Compact checks covered main-feed search/open/input, last-selected cmux/tmux
  panes after saved-screen restoration, and reconstruction/reopening of the
  separate on-device browser followed by terminal input. No extra remote workspace
  or browser creation was needed for the browser restoration check.
- The wide display was temporarily 2000×1400 at 320 dpi. In-composition reflow used
  supplied window configuration; this is not an OS window-recreation test. Compact
  checks ran at the original 1080×2400 / 420 dpi. Display settings were restored.
- Initial wide tests passed; the initial compact run had two passes and one
  failure with a tmux command still in its draft. The test helper now waits for an
  enabled Send, invokes its click semantics, and confirms draft consumption before
  checking output. The original missed submission's exact cause was not traced;
  the final six-test batch passed. No claim of physical touchscreen verification.
- Initial APK/JVM build: 1m48s; test-helper build: 24s; search handling build: 1m19s;
  final Back-handler ordering/test build: 32s. No emulator was running during builds.
- Installed APK and source manifests matched. Final crash buffer empty. Wide pane
  and restored browser screenshots inspected. Fixture logs contain channel-close
  BrokenPipe errors; the final tests passed. The AVD and fixture processes were
  stopped and reaped; the Pixel was absent from ADB and untouched.

Evidence: `captures/runtime/workspace-sidebar/verification.json`, runtime logs,
source hashes and screenshots (local, ignored by Git).
Debug APK SHA-256: `914f8c52f7a52baa6ee30e12bd06807577fcb5e3abb1c89df81f7921dcebf71a`.
Signed build **554** remains the current download; this batch awaits the next
signed milestone. The draft PR and overall parity goal remain open.


## Retained sidebar and Feed state — 2026-10-07

Scoped source recheck: `WorkspaceShellView.swift` at
`186cec79781256867ad4516f0802118738bd2393`. Its compact Feed navigation path
returns to the Feed destination; regular layout retains a sidebar and shared
detail. Android previously moved the live composition during reflow but discarded
sidebar-local saved state when that composition was absent for a frame. Feed also
lost inline question choices when switching primary tabs.

`NativeWorkspaceShell` now owns a saved-state holder for its sidebar, inside the
existing account/team owner key. Hiding it or opening a compact detail still
removes its composition, focus and accessibility nodes; returning restores its
saveable viewport and controls. Moving a still-visible detail during reflow keeps
the same renderer. `NativeScreen` independently retains the Feed's saveable state
across primary-tab switches under its account scope. Existing computer/request
keys continue to separate destinations and invalidate changed questions.

Verification on the sole API 37 / 16 KiB AVD, headless at 1,536 MiB:

- Three shell cases passed in **16.363 s**. The new case scrolls to a lazy list's
  draft, edits it, hides the sidebar and verifies composition disposal, restores
  the saved screen while hidden, then shows the same viewport/draft. Compact
  detail/list navigation also preserves it. Replacing the account resets both
  the scroll position and text. Existing checks retain one AndroidView renderer
  through reflow/tab/search and restore sidebar visibility with an empty detail.
- The main `NativeScreen` Feed/socket-RPC scenario passed in **29.944 s**. It
  chooses a question answer, switches to Notifications and back, verifies the
  choice without a submission, then explicitly sends the expected answer. It
  also covers permission and terminal reply RPCs, opens the target terminal and
  returns with Back to the selected Feed tab and its search result.
- The wide shell harness used 2400×1600 at 420 dpi; compact integration used the
  original 1080×2400 at 420 dpi. Before/after settings match. Boot and final
  ANR/crash event logs are empty. The Feed screenshot was visually inspected.
- Debug/test build passed in 1 minute; the viewport-test refinement built in
  4 seconds. No JVM logic changed, so no unrelated JVM suite was repeated.
- Evidence: ignored `captures/runtime/sidebar-state-retention/`, including the
  scoped source, logs, screenshot and APK receipt. Emulator/Gradle stopped and
  the emulator process reaped. No new AVD, signed release or physical-device test.

Saved-screen restoration is not proof of actual process death during an
authenticated session. Pixel/Mac, broader owner changes, accessibility and full
visual parity remain open. The source recheck also confirms unresolved navigation
work: Feed in the separate browser sidebar, embedded Cloud content in the wide
sidebar (implemented in the follow-up below), and the source's Feed-replaces-Notifications display option. These are
implementation requirements, not unavoidable Android limitations.

## Cloud in the shared sidebar — 2026-10-07

Scoped source: `WorkspaceShellView.swift` at
`186cec79781256867ad4516f0802118738bd2393`, particularly `splitSidebar` and its
Cloud `makeEmbeddedView()` branch. Android now puts Cloud in the existing wide
sidebar instead of replacing the entire workspace shell. The detail composition
and its existing controller ownership remain in place. Compact layout can select
Cloud's sidebar content ahead of an existing detail, then return to that detail
with Back. Cloud's existing saved-state holder still owns its introduction and
sheet state across primary-tab changes.

The Cloud header/introduction expose the shared sidebar toggle. The introduction
chooses its layout from its actual available width, so a tablet's narrow sidebar
gets one column. Embedded Cloud does not consume Back intended for the visible
detail; compact Cloud and modal introduction replay keep their own Back behavior.
Visible detail notification tracking now also remains active beside wide Cloud.

Verification on the existing API 37 / 16 KiB AVD, headless at 1,536 MiB:

- **Eight Android cases passed in 258.131 seconds**: two new Cloud/sidebar
  scenarios, three Cloud onboarding regressions and three existing shell cases.
- The new fixture verifies the same AndroidView instance and unsent draft through
  Workspaces/Cloud and sidebar hide/show, compact Cloud hiding the detail and
  Back returning to its retained draft. The second verifies introduction progress
  across reflow, accessible VPN copy, wide Back reaching the detail and compact
  Back paging/exiting without marking the introduction complete.
- Existing cases cover introduction save failure/retry, saved-screen restoration,
  replay, sidebar viewport/draft restoration, account reset and renderer retention.
- Wide display: 2400×1600 / 420 dpi. Original 1080×2400 / 420 dpi restored. Boot,
  mid-run and final ANR/crash event logs contain no entries. Cloud sidebar and
  introduction screenshots were inspected. Emulator and Gradle stopped/reaped.
- The initial build caught missing catalog fixture arguments; those were fixed.
  Final debug/test APK build passed in 1m06s. Only indentation changed afterward.
  No extra AVD, native rebuild or signed release.

Evidence: ignored `captures/runtime/cloud-sidebar/`, including original build
failure, passing build/runtime logs, source excerpt, screenshots and receipt.
These tests use the production shell and Cloud UI with a fixture catalog and
placeholder AndroidView detail. They do not verify real Cloud transport, an
authenticated main-screen Mac/Pixel workflow, OS window recreation, process death
or full visual/accessibility parity. Separate-browser Feed and the
Feed-replaces-Notifications option remain implementation work.
