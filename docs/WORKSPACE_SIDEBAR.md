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

The separate `RoutedBrowserActivity` in the `:browser` process still presents its
own full-screen phone browser. It currently receives only the current workspace's
menu over the owner-checked service protocol. Full sidebar parity there requires
a bounded global feed/navigation snapshot plus validated actions through that
protocol. Preserve process isolation and do not give the browser account
credentials or start an independent account connection as a shortcut.

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
