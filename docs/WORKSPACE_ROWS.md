# Workspace row presentation

**Delivery:** development is on `main`. See [PIXEL_INSTALL.md](PIXEL_INSTALL.md)
for the latest signed artifact and its verification scope; historical delivery
statements below describe their dated checkpoints.

## Measured workspace content and stable swipe intent (2026-10-05)

Scoped source: `WorkspaceListUpdatePlan.swift` and `WorkspaceListTableCoordinator.swift`
at `186cec79781256867ad4516f0802118738bd2393`. The iOS plan classifies measured-height
and native-action changes as geometry, while same-height content can refresh during
a gesture. These references were read from the pinned upstream checkout; global
parity pins remain unchanged.

`NativeWorkspaceRow` now separates stateless drawing from menus, confirmations and
interaction ownership. `WorkspaceMeasuredContent` measures a changed visual model
in an unplaced, noninteractive probe only while geometry is held. Equal-height
updates enter the existing content slot immediately; height-changing descriptions,
wrapped titles, chips or display preferences retain the previous visual model.
Release takes the latest target directly. Width, density, font-scale and layout
direction changes invalidate the old geometry. The probe creates no dialogs,
background jobs or gesture owners, and its action tags/inputs are suppressed.

Both main and routed-browser lists provide their scroll/hold/swipe state to rows;
a standalone row also holds its own swipe geometry. The revealed read action and
its accessibility action keep their original intent across unread-state refreshes.
Execution checks current membership and capabilities. Revoked cached read/delete
controls cannot dispatch; losing close/edit support retires open confirmations.
Swipe ownership lasts until the closing displacement reaches zero, including a
cancelled gesture that never drew a displaced frame, and cannot clear a newer owner.

**Verification status:** main and instrumentation Kotlin compile; three new Android
cases are queued with the existing swipe, row-presentation and viewport cases for
the next combined milestone. They cover equal-height preview refresh, description/
wrapped-title deferral and latest-on-release, stable read intent, revoked cached
actions and close confirmation retirement. No APK or emulator run is claimed for
this source batch. Compilation evidence: `captures/runtime/workspace-height-actions/`.

**Still open:** exact runtime geometry/semantics and performance acceptance of the
new measurement path; group/header/footer and status/empty chrome reconciliation;
full scroll/fling/drag combinations, TalkBack and physical Pixel smoothness. The
previous batch's 14 passing runtime checks predate this implementation.

## Combined workspace/PDF milestone (2026-10-05)

One shared debug build and the sole existing API37/16 KiB AVD exercise the view
options, native/browser drag and stable viewport anchors, gesture-held rows and
PDF interaction together. The initial run found a test selector using the merged
semantics tree for a child title; it now selects the actual unmerged title node.
The enlarged-font case now changes and restores the emulator's system font scale
and measures the rendered heading, after screenshot inspection exposed that a
local density override had not enlarged popup text. No physical phone is changed.

Final run: **14/14 Android tests passed in 66.575 s**, including all 11 workspace
cases and three PDF cases. Enlarged system text was visibly confirmed and restored
to 1.0; crash buffer was empty and no ANR/crash events appeared. The existing AVD
was stopped/reaped. Full row-height/action/status/footer reconciliation,
TalkBack traversal, scroll/fling timing and physical smoothness remain open.
Evidence: `captures/runtime/workspace-pdf-milestone/`; earlier failed evidence is
preserved alongside the corrected run. Global upstream pins are unchanged.

## Gesture-held workspace membership source batch (2026-10-05)

The native and routed-browser lists now retain rendered workspace order and
membership while scrolling/flinging, holding a row or showing swipe controls.
Surviving rows immediately receive the newest models. Release applies the newest
snapshot directly, then the stable-anchor effect preserves the reading position;
intermediate polls are not replayed. Removed rows remain dimmed in their previous
slots until release and have no touch/accessibility actions. Account/host action
validation remains with the existing controllers; presentation is not authority.

Row callbacks check live membership and composition lifetime, including cached
clicks and pending confirmation callbacks. Workspace/group dialogs close when a
row is removed. The browser's notification/group/menu actions also check current
admission. Removed held/swiped rows cancel their interaction; existing routed
drag-revision validation remains in force. SSH rows now use their exact qualified
row keys for shared swipe/context ownership. When virtualization disposes a swipe
owner, it clears only its own ownership so updates cannot remain held indefinitely.
Empty-state content waits until the retained rows have gone away.

Four presentation-policy cases plus the eight anchor cases pass, including a
50,000-row reorder, surviving-content refresh, removal/reappearance and coalescing
multiple snapshots. Main/instrumentation compilation is checked for the batch.
`WorkspacePresentationRuntimeTest` adds real open-swipe/live-update geometry,
removed-row cached-click rejection and recycled swipe ownership cases. These are
**compiled but not runtime-verified**; execute with the viewport, swipe, drag,
Mac/SSH order/filter and routed-browser cases at the combined milestone.

The initial instrumentation compilation caught a fixture constructor argument
in the notification-list slot; it was corrected to the named workspace argument.
Logs and source receipts: `captures/runtime/workspace-presentation/`.
No APK, emulator or physical session was started for this source batch.

**Remaining reconciliation:** this buffers body-row membership/order, not all
geometry. Surviving models can still change row heights during a gesture, native
swipe action sets update immediately, and connection-status/footer changes are
not yet in the same transaction. Finish a shared rendered-height/action model
without delaying height-neutral content or allowing stale actions. Runtime
scroll/fling/drag timing, TalkBack and physical smoothness remain unverified.

## Stable viewport anchor source batch (2026-10-05)

Scoped source: `WorkspaceListUpdatePlan.swift` and
`WorkspaceListTableCoordinator.swift` at
`186cec79781256867ad4516f0802118738bd2393`; global pin unchanged.
The native and separate-browser workspace lists now choose an unmoved visible
row before an idle structural update. The common subsequence is calculated in
O(n log n): inserted, deleted and moved identities cannot become anchors merely
because they survived. A notification moving the first visible workspace to the
top instead anchors a stable neighbor at its previous pixel offset. A row with
one physical pixel visible can anchor; zero-height/edge-only rows cannot.

Absolute-top updates request the new first row, so newly inserted workspaces or
status banners are visible. Native SSH/Mac status-prefix keys come from the same
filtered collections as the rendered rows, preserving destination indices when
connection status changes. The browser empty-state prefix is keyed as well.
Live row placement animations are removed, matching iOS's unanimated geometry
commits. The effect does not request scrolling during scrolling/flinging, held
row gestures or an open swipe action. It leaves saved list restoration alone on
the first composition.

**Scope limit:** this implements idle structural anchoring, not iOS's full
reconciliation coordinator. The newer checkpoint above buffers body membership
and order; height-changing/native-action-changing updates still need a separate
rendered-versus-current geometry model. Local drag/drop animation and
actual Compose remeasurement timing also need runtime acceptance. Do not close
list smoothness, physical parity or gesture-deferral gates from policy checks.

All eight focused JVM checks passed (0.162 s); main/instrumentation compilation
and checks completed in 44 s. Evidence is recorded under
`captures/runtime/workspace-viewport/`. The new `WorkspaceViewportRuntimeTest`
exercises actual native/browser lazy-list bounds through a first-visible-row
move, removal, status insertion and top insertion. Those Android cases are
queued for the next combined milestone, alongside existing drag/autoscroll,
filter/order and browser checks. No APK/emulator or physical session is required
for the source batch.

## View-options card source batch (2026-10-05)

Scoped reference: `WorkspaceListViewOptionsPopover.swift` at upstream
`186cec79781256867ad4516f0802118738bd2393`; global parity pin unchanged.
The shared main/browser workspace menu now leads with **Sort Computers By** and
three illustrated radio choices. The diagrams show the last-opened computer
rising, ranked computer sections with drag grips, and an interleaved activity
timeline. Selection uses theme-aware outlines/checkmarks; labels can wrap with
font scaling and the card scrolls. Single-computer scope still hides sorting.

Sort, read-state and machine selections keep the card open so the underlying
feed updates live. **Custom Order** now only selects the mode in both hosts;
**Edit Computer Order** is the explicit editor entry and dismisses the Android
popup before showing its sheet. Compound machine choices and exact stable/nightly
identities remain available. Accessibility exposes a sort heading and selected
radio roles, without announcing the decorative miniature shapes separately.

Main and instrumentation Kotlin compilation passed. Updated Android cases cover
persistent selection, explicit editor entry, main Mac/SSH order/filter behavior
and the separate browser's shared state. They are **compiled, not runtime-verified**
for this batch; run them at the next combined milestone, with compact/wide and
enlarged-font screenshots and TalkBack traversal. No new APK, emulator or physical
phone session was used. Evidence: `captures/runtime/workspace-view-options/`.

The source audit also identified live-feed viewport requirements:
iOS anchors to the first stable visible row during inserts/removals/moves, avoids
anchoring moved rows, preserves absolute-top behavior and defers geometry during
gestures. Android's existing lazy-list stable keys alone do not prove those rules.
Idle structural anchoring now has the newer source checkpoint above; full gesture
reconciliation and runtime/physical acceptance remain open.

## Latest checkpoint — adaptive workspace sidebar (2026-10-04)

The workspace shell now places the real Mac/SSH workspace or notification list
beside the active detail in sufficiently wide, tall windows. The sidebar can be
hidden, its visibility restores per account/team, and the existing renderer moves
between layouts without being recreated. Sidebar search Back keeps the detail
open; hiding/folding commits the query. Native/SSH destination switching retires
the previous route, and same-host SSH workspace changes use the new target.

**18 focused JVM and six Android checks passed** on the sole API37/16KB AVD
(**51.848 s wide + 110.830 s compact**). Checks include renderer identity, unsent
input, search/navigation, saved-screen restoration and the separate phone browser
reopening flow. An initial unsent-command test failure and the strengthened Send
assertions are documented in [WORKSPACE_SIDEBAR.md](WORKSPACE_SIDEBAR.md), alongside
source references, build times, hashes, evidence and exact verification limits.
The AVD and private fixtures are stopped/reaped. The Pixel was absent and untouched.

Signed **554** is still the download; these changes await a batched signed build.
PR #1 remains draft and the goal active. **Next:** extend the separate browser
Activity's owned protocol for a global sidebar; complete selection/accessibility
refinements, OS window/process recovery and physical Pixel/Mac acceptance. Push/
notice configuration, legacy tickets and the wider upstream audit remain open.

## Earlier checkpoint — All Computers sorting (2026-10-04)

All Computers now offers **Last Opened**, **Custom Order**, and **Recent Activity**.
Mac and SSH rows share one display projection. Last Opened ranks the foreground
Mac, then phone-side use timestamps, then localized name/identity. Custom Order
ranks exact computer/build identities ahead of that fallback, retains absent
computer priority slots and provides a drag editor with accessibility move actions.
Choices persist locally; unknown future modes remain readable without being
rewritten by unrelated timestamp changes. Single-computer scope keeps its host's
order and hides the cross-computer sort controls. Controls are not hidden merely
because only one computer is available.

Recent Activity ranks pinned rows/blocks first, then activity, with missing times
last and stable ties. Unfiltered groups remain atomic, ranked by their newest
member, preserving member order, anchor/header behavior, collapse and durable
empty groups. Search/filter results flatten and retain pinned-first behavior.
Every row action still receives its original source snapshot; sorting sends no
workspace move RPC. Spatial drag is disabled for All Computers Recent Activity.
SSH inventories do not currently supply activity timestamps; the projection leaves
those missing rather than substituting connection time. Scoped iOS reference:
aggregation/recency/sort-store/computer-order sheet at `0fc35d6`; global pin unchanged.

**18 JVM tests and eight Android checks passed.** Main/test APK build: **1m56s**.
Android completed in **108.332s** on the sole existing API37/16KB AVD with the real
private SSH/cmux-tui/tmux fixture. Checks cover drag/accessibility ordering, sort
controls with no machine-filter choices, Mac/SSH custom and recent ordering,
saved-screen restoration, single-Mac sidebar order, no move RPC, existing filters,
workspace drag/autoscroll and real SSH pane search/input. Grouped activity ordering
has JVM coverage; the main-screen runtime fixture used flat rows. Source/installed
APK hashes matched; screenshots inspected and final crash buffer empty.
Evidence: `captures/runtime/workspace-sort/verification.json`.

The sole AVD and private fixture were stopped/reaped. The Pixel was absent from
ADB and untouched. Signed **554** remains the current download; this batch is
committed for the next signed milestone. PR #1 remains draft; goal active.
**Next:** regular-width sidebar/navigation. The scoped iOS layout policy uses
stacked navigation when either dimension is compact; phone landscape width alone
must not enable a tablet sidebar. Full production process/network recovery,
broader upstream audit, physical Pixel/Mac acceptance, configured push/notice feed
and legacy tickets remain open. Saved-screen checks do not prove process death.

## Earlier checkpoint — compound workspace filters and SSH pane search (2026-10-04)

The workspace filter now combines unread state with multiple exact computer
identities, across native Macs and SSH hosts. Stable/nightly siblings remain
independent, and UUID case normalization does not change opaque device IDs.
Filter state is scoped to login/team and saved-screen restoration. Machine
selections are pruned when unavailable, when fewer than two computers remain,
or when the title picker scopes to one computer; unread state is retained.
Search uses the same filter, explicit filters flatten group presentation, and
filtered lists disable drag reordering. The menu uses the existing filter icons
and reports selected state for accessibility. Filter-empty messages distinguish
machine, unread and combined scope; **Show All** clears the filters and returns
to All Computers. Source snapshots remain intact for row actions.

SSH search now indexes displayed live terminal names from cmux-tui and tmux,
including custom pane names and window titles. It preserves field boundaries,
excludes ended tabs, and updates when inventory names change. Projected SSH
terminal metadata uses those same names instead of repeating the workspace title.
Scoped reference: `WorkspaceListView.swift`, `MobileWorkspaceListFilter.swift`,
`WorkspaceListFilterControls.swift`, and `WorkspaceListFilterEmptyRow.swift` at
`0fc35d6`; global parity pin unchanged.

**12 JVM tests and five Android checks passed.** The Android run took **85.06s**
on the sole existing API37/16KB AVD with the private real SSH/cmux-tui/tmux fixture.
Checks cover independent sibling selections, combined filters, saved-screen
restoration, Show All, hidden-filter cleanup, existing retry recovery, pane-only
search (`0:cat`) with the keyboard visible, and opening/input through both SSH
workspace kinds without Mac creation/close/move RPC. Source hashes and installed
APK digest matched. Menu/search screenshots were inspected; crash buffer empty.
One fixture channel logged BrokenPipeError after closing; all five cases passed.
Evidence: `captures/runtime/workspace-filters/verification.json`.

Initial JVM/main/test build: 1m49s; adding Show All required a 1m10s build; final
explicit test callback/pane-query assertions required a 19s test-APK update.
The emulator and private fixture were stopped/reaped; no new AVD. The Pixel was
absent from ADB and untouched. Signed **554** remains the current download; no
new signed milestone was dispatched. PR #1 remains draft and the goal is active.

**Next:** implement Last Opened / Custom Order / Recent Activity ordering and
regular-width sidebar policy. Full process/network recovery, broader upstream
audit, physical Pixel/Mac acceptance, push/notice configuration and legacy tickets
remain open. Saved-screen checks do not prove full process-death restoration.

## Earlier audit — search, compound filters and ordering (2026-10-04)

Read-only audit at upstream `0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`:
`WorkspaceListView.swift`, `WorkspaceListFilterState.swift`,
`WorkspaceListFilterControls.swift`, `MobileWorkspaceListFilter.swift`,
`MobileWorkspaceAggregation.swift`, `MobileWorkspaceRecencyOrder.swift`, and
`MobileWorkspaceSortStore.swift`. The search/filter/sort steps were implemented in the checkpoints above.
The global parity pin remains unchanged; regular-width layout remains pending.

1. Index live SSH pane names alongside workspace title, preview and host name.
   Mac search already indexes terminal and group names. Build metadata from the
   same admitted inventory used to open a row; exclude dead/replaced panes.
2. Compose unread and a multi-select set of exact computer identities. An empty
   set means all computers; a bare device ID must not match tagged sibling builds.
   Share filter state between list/search. Hide machine controls below two
   computers and clear their now-hidden selection; also clear the machine
   dimension when the title picker scopes to one computer. Preserve unread.
3. Add local **Last Opened**, **Custom Order**, and **Recent Activity** options in
   All Computers. Single-computer scope retains its host's order. Last Opened
   uses foreground first, then last-used time, then name/device/build tie breaks.
   `MobileWorkspaceSortMode.swift` has an older alphabetical-only comment; the
   aggregation implementation and UI copy include last-used time. Custom Order
   ranks explicit computer priorities ahead of foreground and retains offline
   slots. Neither choice sends workspace move RPCs.
4. Recent Activity sorts pinned first, then newest activity, with missing times
   last and stable ties. The unfiltered list ranks complete group blocks by their
   newest member, preserving member order, anchors, collapse and durable empty
   headers. Search or explicit filters use flat rows. Compute a display projection
   without rewriting source order; retain exact source ownership for all actions.
5. Verify build identity isolation, hidden-filter recovery, stable ties, group
   pin/recency order, offline priority, single-computer scope and live SSH search.
   Then run targeted UI cases on the sole existing AVD, with a batched build.

Local audit receipt: `captures/runtime/release-0da7bb5/next-source-audit.json`.
Regular-width sidebar policy remains a separate follow-up. Physical Pixel/Mac
acceptance and broad upstream review are still open.

## Latest checkpoint — empty SSH workspaces and phone-browser restoration (2026-10-04)

A cmux-tui workspace now has a durable destination independent of its panes.
Empty rows open the shared waiting view with New terminal and New browser actions.
Restoration creates no pane; explicit terminal creation targets the captured
workspace and opens the returned terminal. New panes arriving in an otherwise
idle waiting view are selected without sending a creation command. Workspace
session/registry/resource checks reject replaced owners.

Remembered SSH targets are now resolved after successful provider discovery,
rather than falling back against the main feed's potentially incomplete cache.
A failed discovery retains the pending preference. Independent on-device browser
selection uses the same encrypted local-tab preference as native workspaces.
Back returns to the list while keeping the page; reopening restores it. Selecting
a terminal from the browser replaces that preference. Saved navigation retains
only destination/preference data, with no mutation replay. A remembered local tab
can reconstruct a fresh browser when no in-memory surface exists; this is not a
claim that URL/history/cookies survive a complete app process death.

**26 JVM tests passed; five distinct Android workflows passed across the two
runs.** The initial five-case run passed four cases (168.902s); its browser case
waited in UI Automator before Compose had advanced the Activity transition.
The test now uses the lifecycle synchronization already used by other browser
checks and also verifies switching back to a terminal. That case passed in
**50.794s**. Only that test file changed after the broader run; production source
hashes and the installed main APK hash matched. Initial main/test build: 1m36s;
test update: 20s. Final crash buffer empty. Screenshots inspected for the empty
workspace and the restored real page served through the private SSH connection.
Evidence: `captures/runtime/ssh-workspace-restore/verification.json`.

The sole AVD and private SSH/cmux/tmux/HTTP fixture were stopped/reaped. The Pixel
is absent from ADB and untouched. Signed **546** remains the latest verified
download; the next delivery step is one signed milestone for the accumulated
workspace changes. PR #1 remains a draft and the goal remains active.

**Remaining:** SSH pane metadata search; compound machine/read filters and sort;
regular-width sidebar policy; broader upstream source audit; full process/network
recovery and physical Pixel/Mac acceptance. Linked-browser mode and page state
across cold starts need broader verification. Push/notice configuration and
legacy tickets remain tracked separately. The global parity pin is unchanged.

## Earlier checkpoint — SSH pane memory and connection races (2026-10-04)

Reopening a main-feed SSH row now restores its last successfully displayed
terminal or streamed-browser target. It uses the existing encrypted, bounded
512-entry preference store, scoped to the login, SSH endpoint/key/jump route,
and stable workspace identity. Rename and pause preserve preferences; owner
replacement does not. cmux selections resolve against live resource identities
and are recaptured after numeric renumbering. Missing or malformed choices fall
back to the first listed live pane. Only an admitted, current host session can
write a displayed selection. Scoped iOS reference: `MobileWorkspaceLastTabStore.swift`
at `0fc35d6`; the global parity pin is unchanged.

The first Android run exposed two additional defects. A new tmux pane was ended
by an unrelated window's layout update. Then a fresh SSH handshake deadlocked:
Main held the host-store monitor while publishing to the connection observer,
and an IO transport guard held the connection monitor while waiting for the
host store. The captured Android stack is retained. Connection checks now share
the store monitor. tmux layout updates affect their own window; inventory replies
can retire only attachments present when the inventory request began. New-window
notices refresh layouts. Three deterministic regressions failed before these fixes.

**39 JVM tests and seven Android checks passed** on the final source. Android
completed in **141.589s** against the private real SSH/cmux-tui/tmux fixture on
the existing API37/16KB AVD. Both second-pane reopen paths preserved terminal
identity and accepted input after saved-screen restoration. Reconnect/pause,
window/split creation, shared-feed navigation/search and encrypted storage checks
also passed. Streamed-browser selection resolution has JVM coverage; no new
physical browser acceptance is claimed. Source hashes matched and the final
crash buffer was empty. Main/test APK build: **58s**. The initial failed/hung run
was deliberately stopped and is not counted as a pass.
Evidence: `captures/runtime/ssh-last-tabs/verification.json` and its adjacent logs.

The fixture and sole AVD were stopped/reaped, including their owned processes.
ADB has no Pixel attached; the phone and its sign-in were untouched. Signed
build **546** remains the latest download. No signed build was dispatched.

**Next:** restore independent on-device SSH browsers on row reopen/cold launch,
handle remembered choices through incomplete discovery, add direct empty-workspace
pane creation and pane metadata search. Compound filters/sort, wider-layout policy,
broader upstream audit and physical Pixel/Mac browser/reconnect acceptance remain
open. Push/notice configuration and legacy tickets remain tracked separately.
The goal is active and PR #1 remains a draft.

## Earlier checkpoint — shared Mac/SSH feed (2026-10-04)

Existing cmux-tui workspaces, tmux sessions and live phone shells now appear in
All Computers with the same row presentation as Macs. SSH preview text names
the workspace kind/session. The computer selector includes saved SSH hosts,
retains a selected SSH host across saved-screen restoration, scopes creation
and search to that host, and excludes unrelated Mac notifications. Add Computer
still explicitly opens Mac pairing when SSH hosts are saved.

The account-owned SSH inventory observes the existing providers and connections;
it never routes SSH rows through Mac RPC or creates a second SSH connection
manager. Connected hosts refresh when the list returns or the app foregrounds.
A selected idle host auto-connects only when its existing pause/failure policy
allows it. Retry and pull-to-refresh are explicit connection requests. Cached
rows survive replacement-connection discovery until the new list is ready.
Host removal/route edits retire observers and row actions. Close confirmations
capture their displayed target/content; cmux-tui content changes reject a stale
confirmation, and errors persist through background inventory refreshes.

Scoped source: `MobileSSHComputers.swift`, `MobileSSHWorkspaceProviders.swift`,
`MobileShellComposite+SSHComputers.swift`, `WorkspaceListView+MacSelection.swift`
and `SSHWorkspaceListPanel.swift` at `0fc35d6`. The broad parity pin is unchanged.

**23 JVM tests passed; 19 distinct Android checks passed across the verified
runs.** The initial 19-case run had three failures: reconnect rows briefly
vanished, a close rejection was overwritten by an inventory refresh, and search
results moved offscreen when the test Activity's keyboard panned the window.
The first two were fixed in the feed. The broader rerun passed 18 cases; the
remaining search failure was traced with screenshots to the generic test
Activity, which now uses the production manifest's `adjustResize` behavior.
All three affected feed workflows then passed in **78.402s**, including search
with the keyboard visible, real SSH terminal input/output, saved selection,
paused reconnect/retry, stale-close rejection, correct cmux/tmux deletion and
Add Computer. Mac peers received no SSH terminal/create/close RPC. Existing
Mac selector/creation/deletion, SSH creation and drag checks passed in the
broader run. Final source hashes matched and the crash buffer was empty.
Evidence: `captures/runtime/main-ssh-feed/verification.json`; initial diagnostics
and screenshots are retained. The last main/test build succeeded in 1m4s.

The sole existing AVD and private SSH/cmux/tmux fixture were stopped/reaped.
The Pixel remains absent from ADB and untouched. Signed build **546** is still
the latest download; this feature has not dispatched another signed milestone.

**Remaining:** preserve the last selected SSH pane when reopening a row, offer
main-feed pane creation for an empty SSH workspace, and expand SSH search to
pane metadata. Main-feed ordering currently places Mac sources before SSH
hosts; the iOS compound machine/read filters and sort modes still need work.
Wide layouts, broader source audit, legacy tickets, push/notice configuration
and physical Pixel/Mac acceptance remain open. Goal active; PR draft; scheduled
updates still await merge to main.

## Earlier checkpoint — SSH creation in All Computers (2026-10-04)

The main plus chooser now includes saved SSH hosts beside native Macs. Mixed
lists open a host's cmux-tui/tmux/shell submenu; a single SSH host opens its kinds
directly. A selected Mac remains scoped to that Mac. Fresh login, host route,
provider availability and session checks reject stale menu actions. SSH-only
accounts can reach this chooser without a Mac pairing, and the existing shared
trust/biometric prompt host handles connection admission.

An account-owned coordinator retains one explicit creation through Activity
recreation. Saved state contains a waiter ID and completed destination, never a
create command. Restoring a pending operation into a new process reports its
uncertain outcome without resending it. Leaving or changing the account/filter
retires delayed navigation; completing a request opens the exact returned SSH
terminal. Back returns to the main workspace list.

**14 JVM tests and 12 Android tests passed** (149.274s for Android). The real
private SSH/cmux-tui/tmux fixture created all three kinds from the main mixed
chooser, accepted and echoed terminal input, preserved each terminal across
saved-state recreation, created no duplicate sessions, and sent no workspace
creation to the Mac peer. A separate SSH-only/no-Mac path passed. Existing Mac
chooser routing, SSH screen creation, same-kind different-host selection and
stale authority/route guards passed. The first build caught a malformed enum
branch in empty-state text; it was fixed before runtime verification. Final
build succeeded, screenshots inspected, source hashes matched, crash buffer
empty. Evidence: `captures/runtime/main-ssh-creation/verification.json`.

The existing AVD and private fixture were stopped/reaped, including their owned
cmux/tmux processes. No new AVD, physical phone changes or signed milestone.
Signed build **546** remains the latest download. The Pixel was absent from ADB;
the physical browser/deadlock check remains pending.

**Next:** unify existing SSH workspace rows and computer selection into the main
feed. This checkpoint integrates creation/navigation only; existing SSH inventory
is still opened from Computers. Compound filters/sort, wide layouts, broader
upstream audit, legacy tickets, push/feed configuration and physical acceptance
remain open. Goal active; PR draft; schedules await merge to main.


Scoped iOS reference: `0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`,
`WorkspaceRow.swift`, `WorkspaceUnreadDot.swift`,
`MobileWorkspacePreview+Display.swift`, `MobileMacConnectionStatus+Display.swift`
and `MobileSyncWorkspaceListResponse.swift`.

## Row content

The scoped iOS row consists of the retained unread gutter, workspace color rail
and text column. It does not render a Mac avatar or Mac-name caption inside the
row. Android now uses that structure; the owning Mac remains in the row's
accessibility context and the computer-selection/status surfaces. The earlier
customization checkpoint's avatar-plus-rail layout is superseded.

A pinned workspace shows the existing filled pin asset at the title baseline.
The pin is decorative because the row already exposes its pinned accessibility
state. The activity/status label now shares the title line. Headline/subheadline
sizes are 17sp/15sp with explicit 22sp/20sp line heights, text blocks have
4dp spacing, and row vertical padding is 8dp. This avoids inheriting Material
body text's taller line height when overriding only font size.
Descriptions reserve two lines; the Preview Lines and Wrap Workspace Titles
preferences still control their respective content. The 3dp color rail, 5dp
vertical inset, 10dp text gap and retained unread gutter align read/unread rows.

## Static activity and connection status

The owning feed's state determines the trailing label: `Reconnecting` or
`Disconnected` when the link is not connected. A healthy connection adds no
connection-status label. This describes the link rather than claiming the Mac
itself is offline.

For connected rows, `last_activity_at` takes precedence over legacy `preview_at`.
The parser now retains that fallback field. No real timestamp means no trailing
label. Epoch sentinels at or before one second, nonfinite values and out-of-range
values do not produce a date. An explicitly present sentinel does not fall back
to the preview time, matching the scoped iOS derivation.

Activity on the current local calendar day shows a time; older activity shows
numeric month/day without a year. Android's locale pattern generator formats
these values. The day comparison uses the local timezone, including dates near
midnight. Rows do not run a live relative-time timer.

## Verification — 2026-10-04

**17 focused JVM tests pass**: four row timestamp/status policies and 13 existing
customization checks. **Four Android checks pass in 56.636s**: row pin/status/rail
alignment, US/German date and time formatting, display preference wrapping and
reserved line counts, and the real Activity/local-peer offline customization
recovery flow. After inspecting screenshots, explicit line heights were added;
the two affected layout/preference checks passed again in **17.153s**. The final
form and wrapped-row screenshots were inspected, crash buffers were empty, and
the sole existing AVD was stopped/reaped.

The first test compilation used an unavailable DpRect width accessor, and the
first runtime layout assertion omitted density rounding. Both diagnostics are
retained. The corrected test checks the exact expected physical-pixel rail width.
The final APK build passed in 56s. Main debug SHA-256:
`c525ca1bc1cd803ab1d025e9ea2e6b40a3731409d12605171c8c2cccd1a53fc1`.
Local receipt: `captures/runtime/workspace-row-presentation/verification.json`.
No Pixel was connected; signed build 517 is unchanged.

## Remaining row parity

Workspace rows now use long-press menus instead of a visible action button,
with coordinated swipe/drag behavior and consolidated accessibility actions.
The group header's action button and exact context-menu contents/icons/submenus
still need the remaining presentation audit. The changes-summary chip now has its own list cache
and opens the existing Changes viewer (see the checkpoint below). Selected/sidebar styling, wider-screen layouts, dynamic type/TalkBack,
production multi-Mac behavior and physical Pixel comparison still need checks.
These are remaining work, not unavoidable Android differences.

## Changes-chip implementation reference — 2026-10-04

This reference informed the implementation below. At the scoped revision
above, the source path is `MobileShellComposite+WorkspaceChanges.swift`, supported
by `WorkspaceChangesSummaryFetchPolicy`, `WorkspaceChangesSummaryRefreshSchedulePolicy`,
`WorkspaceChangesSummaryRefreshScope` and the pruning extension. The list requests
`mobile.workspace.changes.summary` with `workspace_ids` (up to 64 unique IDs);
`force: true` is sent only for a forced pass. Successful results are reused for
15 seconds. Scheduling debounces real events/list changes by 250 ms, coalesces
full snapshots or changed workspace IDs, ignores group-only changes, and permits
one fetch at a time with an accumulated trailing pass. Trailing expiry waits at
least five seconds and is armed only while real events are recent; it must not
become continuous Git polling on idle Macs.

Connection/capability admission precedes fetching. Foreground workspace membership
prunes cached chips and pending requests; a connection reset cancels jobs and
clears their state. Failed requests retain the last published chip. Successful
summaries remove chips for non-repositories or zero changed files. The decoder
requires typed workspace identity and `is_repo`, drops malformed entries separately,
and tolerates missing or malformed count fields. The Android adaptation should
also restrict response IDs to the requested owning-Mac batch and reject late
responses after owner/client retirement.

`WorkspaceRow.swift` puts the chip beside the preview with an eight-point minimum
gap; it is visible only with a positive changed-file count. A tappable chip has a
44-point minimum target and opens Changes without opening the terminal row.
`WorkspaceChangesChipLabel.swift` uses caption2 semibold monospaced digits, a
secondary capsule at 12% opacity, horizontal/vertical padding 6/3 and a three-point
count gap. `WorkspaceChangesChipTextPolicy` uses green additions and red deletions;
binary-only changes (both line counts zero) show singular/plural file count text.
The accessibility label includes files, additions and deletions.

Android now integrates a separate per-owner summary state with its feed
coordinator's workspace/event and lifecycle boundaries. It does not derive the
row badge from the full-viewer cache. The checks below cover batching/expiry,
coalescing, retirement/late responses, multiple Macs sharing workspace IDs,
binary/zero counts, chip-versus-row actions and accessible labels. Live Mac/Pixel
acceptance remains separate.

Android integration detail: `NativeFeedRefresh.run` reconciles workspaces and
notifications every 30 seconds as well as on events. Its conflated `Unit` signal
does not preserve which event caused a fetch. Do not schedule a changes-summary
request on every call to `refreshWorkspaces`: notification-only events and idle
reconciliation would turn that into extra Git polling. Keep separate summary
demand for initial connection, real workspace events, explicit user refresh and
workspace mutations, then prune it against each accepted workspace snapshot.

## Changes-chip implementation — 2026-10-04

`WorkspaceChangesSummarySession` is confined to the foreground feed dispatcher
and owns a child job per verified Mac connection. It implements 64-ID batches,
15-second reuse, 250ms event debounce, one in-flight fetch and coalesced trailing
demand. Expiry timers have a five-second minimum and stop renewing after real
workspace activity goes idle. Repeating an unchanged 30-second feed snapshot does
not count as activity. New workspace membership, workspace events, workspace
mutations and explicit refresh request summaries. Removed workspaces prune cached
counts, pending demand and expiry times. Disconnect, pause, unpair and account/route
retirement cancel the session and clear its chips.

The host must advertise `workspace.changes.v1`. Responses are restricted to IDs
requested from the current owning client, with late/disallowed replies ignored.
Malformed rows are individually dropped, invalid counts become zero, and explicit
non-repository/zero-file responses remove an old chip. Ordinary fetch failures
retain the last good chip. A request deadline does not cancel later batches.
Authorization failures retire the owning monitor and clear chips through its
normal disconnect path, rather than silently closing a client behind the monitor.

Android currently reads complete workspace inventories. A list event whose new
inventory changes groups but leaves workspace records equal is treated as group
only; group actions do not directly request Git summaries. This is a legacy-list
adaptation, not a claim that Android has implemented the iOS sync delta protocol.

The row renders green additions/red deletions, or file count for binary-only
changes, in an 11sp capsule with explicit 13sp line height and tabular digits.
The 44dp target has a separate accessible Changes action and opens the owning
workspace's Changes viewer without selecting a terminal. The caption uses the
existing diff colors; the preview row uses top alignment as in the scoped iOS
source. A first screenshot caught Material's inherited taller line height; the
explicit caption metrics correct it.

### Verification

Eight summary-policy/RPC tests and 29 feed coordinator tests passed (**37 JVM
tests**). Six Android checks passed in 34.506s, covering independent badge/row
actions, binary counts, real Activity navigation through a framed local peer,
existing row presentation and display preferences. Four affected checks passed
again in 32.112s after the caption adjustment. The runtime fixture verified the
summary request, owning-workspace Changes viewer and no terminal replay. Final
debug/test APK assembly passed in 47s; the preceding JVM/build gate took 1m19s.

The initial timing assertion observed trailing work too early; its corrected
window proves eventual quiescence. A separate initial authorization test exposed
a real monitor-retirement bug, fixed with a scoped failure signal. Both original
logs are retained. Final screenshots inspected, crash buffers empty, sole AVD
stopped. The ignored `captures/runtime/workspace-changes-chip/verification.json`
records source/APK hashes and copied JVM results. No physical Pixel was connected;
live Mac acceptance remains pending. Signed build 537 is unchanged.

## Swipe and action discovery — 2026-10-04

Reference: `WorkspaceListTableCoordinator.swift` at the scoped iOS revision above,
leading/trailing swipe configuration and context menu dispatch. The leading blue
action toggles read/unread; the trailing red Delete action requests the shared
Mac close confirmation. Both allow full swipes. Confirmation cancellation keeps
the authoritative row, and no delete request is sent until confirmation. iOS
gates these actions independently with `workspace.read_state.v1` and
`workspace.close.v1`; rename/pin use `workspace.actions.v1`.

Android now provides partial reveals with action buttons, full-swipe activation,
logical leading/trailing handling in RTL, Back/tap dismissal, and one revealed
row per list. Disclosure uses the owning Mac's stable capability snapshot; the
feed coordinator rechecks its current verified handle before mutation. Read and
Delete also appear as TalkBack custom actions, with a Show workspace actions
entry for the complete menu. Horizontal touch-slop recognition leaves vertical
scrolling and the parent's long-held reorder gesture in control of those motions.

Android uses a 104dp reveal and a 65%-width full-swipe threshold (at least one
action width); these are Android interaction measurements, not claims about
UIKit's private thresholds. The row is never optimistically removed by a swipe.
Long-press context presentation still needs coordination with the existing drag
gesture; the visible action button is retained until that work is complete.

The first nine-case Android pass exposed two implementation defects: a local
function reference retained the old read-state action after a row update, and
relative Compose offsets applied a second direction flip in RTL. The updated
callback is a state-keyed lambda, and translated row position uses absolute
offset after one logical-to-physical conversion. The initial test API compilation
error and both runtime failures are retained in the evidence folder.

### Swipe verification

The final pass completed **nine Android tests in 72.636s**, covering full read/
unread gestures, delete confirmation/cancel, partial reveal/button/dismissal,
capability withdrawal, accessible actions, RTL and separate owner keys sharing
a workspace ID. Real row swipes did not trigger reordering; held vertical drag
and the existing virtualized auto-scroll/accessibility move tests still passed.
The existing full-screen framed RPC tests verified read-state aggregate refresh
and exact close workspace/window parameters. **30 coordinator JVM tests passed**,
including independent owner capability admission. Final debug/test APK assembly
passed in 1m11s. The initial Android run had two failures; both are described above.

The partial reveal control and full-app delete confirmation were inspected. The
isolated component fixture has no Surface-provided title color; its screenshot
is evidence for the revealed control, not the whole app's typography. The actual
app screenshot verifies its theme and confirmation. Final crash buffer empty,
sole AVD stopped/reaped. Hashes, logs and copied JVM results are recorded in
`captures/runtime/workspace-swipe/verification.json`. No physical Pixel was
connected; signed build 537 is unchanged.

## Long-press menu and drag coordination — 2026-10-04

The list owns one long-press recognizer. Holding a row opens that owning row's
menu without selecting a terminal or admitting a reorder. Moving vertically past
Android touch slop after the hold dismisses the menu and lifts the same row into
the existing reorder/autoscroll path. Reorder-disabled lists still allow menus.
During the original hold, the popup does not take window focus and ignores
outside-dismiss requests; after release it becomes focusable for keyboard and accessibility navigation. Removing/replacing a
row dismisses its presentation through the owning composition key, including
account replacement at the same origin and workspace ID. Rename/delete dialogs
also reset when that menu owner changes.

The workspace row's visible ellipsis is removed. Its semantic long-click and
Show workspace actions entry expose the same menu; read/delete and move up/down
actions now live on that row's accessible node. Captured move actions re-resolve
the current row and reject a removed/replaced owner before invoking the move
callback. Generic drag-list consumers can retain container-level move actions.
Group headers also receive long-press and consolidated move actions, while their
existing action button remains pending the broader group-menu presentation audit.

This change retains the existing Android menu contents (including pane-creation
and Changes shortcuts). Exact context-menu grouping/icons and the official
Move to Group submenu remain presentation work; they are not platform limitations.
The iOS behavioral reference remains `WorkspaceListTableCoordinator.swift` and
`WorkspaceListTableCoordinator+Actions.swift` at the scoped revision above.

The list disables scrolling at long-press admission, before a drag is lifted,
so its scroll recognizer cannot take the first drag movement. Edge scrolling
starts directly in the admitted gesture callback with an undispatched coroutine;
waiting for a later composition effect lost the early drag frames in the
virtualized-list check. A read-only group does not create an invisible menu that
could block its next tap. Group open/collapse callbacks also respect an active
context hold. Popup visibility checks wait for asynchronous window attachment
before asserting that the menu is on screen.

### Context-menu verification

The final production APK passed 13/15 Android cases in 149.132s. Two checks needed
explicit waits after Save/Resume: the edited name was still visible in the editor
while writes ran, and the error label persisted until the next UI update. With
those waits fixed, both cases plus three actual process-restoration cases passed
(5/5, 144.538s), for **18 distinct passing checks** against this production code.
This is a combined result, not one green 18-test invocation. The preceding
six-case gesture gate passed in 47.531s.

Coverage includes OS pointer hold → visible popup → drag, virtualized auto-scroll,
swipe regression, move/menu/read/delete semantics on one row, owner replacement
at an unchanged origin, rename dismissal, unsupported group behavior, framed-RPC
read/delete/rejected-group actions, offline editor recreation, and saved-draft
process death/conflict/account replacement. Earlier failed and stopped runs
remain in the local receipt. Main APK assembly passed in 39s; the final test-only
rebuild passed in 20s. No new JVM run is claimed.

Held-menu and actual app row screenshots without the ellipsis were inspected.
Final crash buffer empty, sole AVD stopped/reaped. Receipt:
`captures/runtime/workspace-context-menu/verification.json`. Physical Pixel/Mac
and spoken TalkBack acceptance remain pending; signed build 537 is unchanged.


## Move to Group submenu — 2026-10-04

The scoped iOS reference is `MobileWorkspaceGroupMoveMenu.swift` plus
`WorkspaceListTableCoordinator+Actions.swift` at
`0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`. Android now opens an anchored
**Move to Group** submenu with destinations in the owning Mac's group order,
optional group icons, a checked/disabled current group, and a separate
**Remove from Group** action. Collapsed and empty groups remain destinations;
anchors and missing rows have no picker, and unknown group membership cannot
expose removal. A single current group still has its disabled item and removal.
The picker offers Back to workspace actions and retires with the owning row,
including account replacement at the same origin/workspace ID.

The model accepts one complete `NativeFeedSource`, so aggregated and searched
rows never mix group IDs across Macs or compute order from a filtered list.
Menu discovery is independent of drag discovery: search, unread filters and
multiple visible Macs no longer suppress group moves. The existing connection,
capability, unambiguous window and pending-queue constraints still apply. All
selections go through `NativeWorkspaceMoves.enqueue`, which rechecks owner,
base order and current admission before using the shared normalization policy.
No direct RPC or automatic retry path was added.

Exact parent menu ordering/icons, pane-creation/Changes shortcut placement,
group header actions, physical Pixel/Mac and spoken accessibility acceptance
remain separate work. This scoped submenu change does not update the global
upstream parity pin or the signed delivery build.

### Group-picker verification

**14 JVM cases passed** (eight menu policies, six existing move queue tests).
The first Android run passed 7/8 in 70.781s. Its remaining test selected both the
search editor and the row with the same text; replacing that selector with the
row tag resolved it. The filtered background-Mac flow then passed in 25.311s on
the same production APK, verifying exact workspace/group/window parameters,
removal without a group ID, zero moves to the other Mac with a colliding ID and
zero terminal replay. Thus **eight distinct Android cases pass across runs**;
this is not a single green eight-case invocation.

The other cases exercise current checked/disabled state, collapsed destinations,
back to parent, removal, account replacement and capability withdrawal; existing
held-pointer, virtualized drag/accessibility and rejected RPC recovery checks
also pass. The submenu and full-app filtered row screenshots were inspected.
The component screenshot deliberately uses a partial-size Surface, not a full
Activity layout. Crash buffers are empty; the existing AVD was stopped/reaped.
Builds took 1m38s (JVM/main/test) and 27s (test selector correction only). Evidence:
`captures/runtime/workspace-group-picker/verification.json`. No physical Pixel
was visible. Signed build 537 remains unchanged.


## Workspace context-menu contents — 2026-10-04

`WorkspaceListTableCoordinator+Actions.swift` at the scoped iOS revision now
supplies the Android workspace menu order: **Pin/Unpin, Customize, Rename,
Mark as Read/Unread, Move to Group, Delete**. Each action has its corresponding
vector icon; Delete has the destructive tint and retains the shared Mac close
confirmation. Customize requires both workspace actions and metadata support.
The customization editor and pane picker's separate Customize Workspace labels
are unchanged. Group moves retain the submenu described above.

The old row-level New terminal/New browser/View changes shortcuts are removed
from this menu. Pane creation remains in the pane picker and the empty workspace view’s
creation buttons; Changes uses the list's independent summary badge. Regression
fixtures now enter through those real UI paths. Read-only rows with no available
context actions neither expose an empty menu nor allow its retained coordinator
key to block normal row selection. The shared hold-to-drag recognizer is retained.

### Group actions research for the next implementation

The same iOS file uses a first group section containing Pin/Unpin Group, Rename
Group and New Workspace in Group, followed by Ungroup (Keep Workspaces) for
unpinned groups and Delete Group (Close Workspaces). Group creation discovery
uses `workspace.create_in_group.v1`; it is independent of group-action support.
`MobileShellComposite+WorkspaceActions.swift` confirms deletion is
`workspace.group.action` with `group_id` and `action: delete`, followed by an
owning-Mac authoritative refresh even after rejection. It must not be simulated
by a sequence of workspace closes. Android's RPC whitelist currently excludes
`delete`, and creation must be routed to the owning Mac, not the foreground one.
Group confirmations use “Ungroup Group?” / “Delete Group?” and explain that
ungrouping keeps workspaces while deletion closes them on the Mac.
`MobileShellComposite+WorkspaceCreateRequest.swift` confirms `workspace.create`
with `group_id`; it captures the creation connection before the request and
checks it again before applying a response. Legacy no-spec creates can omit
`created_workspace_id`, unlike task/spec creates. Android creation navigation
must preserve those distinctions. The visible group ellipsis, labels and destructive actions are
remaining parity work.


The separate-process restoration fixture now gives its WebView a stable test-only
data-directory suffix before MainActivity starts. A combined run exposed a real
fixture crash when the instrumentation process had already initialized its own
WebView. Android requires separate data directories for concurrent WebView
processes; see the [WebView API contract](https://developer.android.com/reference/android/webkit/WebView#setDataDirectorySuffix(java.lang.String)).
This change is confined to the emulator-only debug Activity, which is excluded
from signed release APKs. Production routed-browser isolation is unchanged.


The updated pane entry also exposed a production Activity-recreation bug in
`RoutedLocalBrowserWorkspaceView`: a tab that had selected the legacy local
presentation re-ran `requiresProxy()` during the feed connection's transient
reset. `NOT_CONNECTED` correctly binds a new browser to its Mac, but applying
that new-browser decision to an already-open local tab launched a different
Activity, storage profile and network path. The isolated failure screenshot
shows `RoutedBrowserActivity` with `ERR_SOCKS_CONNECTION_FAILED` for the fixture
URL after recreation.

The selected local presentation now retains its surface ID in saved UI state.
Recreation of that same live surface keeps its presentation; a new surface
(including after process death) has a fresh UUID and performs the usual network
check. The owning network must still exist before either path is admitted, and
existing workspace/account menu retirement remains in effect. This does not
introduce a direct-network fallback for a proxy-bound browser.

### Menu and lifecycle verification

Twenty-seven distinct Android checks pass across the focused runs recorded in
`captures/runtime/workspace-menu-parity/verification.json`. The initial 25-case
run passed 19; four failures needed the new pane/Changes entry paths or a wait
for asynchronous reconciliation, one exposed the separate-process WebView
fixture collision, and one exposed the production browser recreation bug.
The next 11-case run passed ten; isolated reproduction of the remaining browser
failure confirmed the unintended routed Activity. After the production fix,
all four browser lifecycle/process checks passed (68.813s).

A final three-case lifecycle run passed (63.3s) with actual screen-pixel checks
and DOM URL/title/form assertions. The restored page is visibly green; the
multipart response is visibly blue, so neither a blank WebView nor the previous
page can pass. The test saves the exact accepted frame. Earlier screenshots
captured before painting are retained but are not evidence of rendered content.
The system picker delivers the real fixture file's bytes, and a result for a
destroyed Activity is discarded before a fresh chooser succeeds.

The final main APK SHA-256 is
`8a7ca6879dc8c95c224e406465b45cda823abd8e16a31d6b091c1857dd91fbc3`;
the final test APK is
`a8129cb6c2d54465ed190e2e21c31b968e2b7c40d3a62e51800ee90cc79128a3`.
The installed emulator APK hash matched. Final crash buffers are empty, the
existing AVD is stopped/reaped, no new AVD was created, and no physical Pixel
was connected. Signed build 537 is unchanged. These emulator/framed-RPC checks
do not replace live Pixel/Mac browser and account acceptance.

## Group header actions — 2026-10-04

The group header now opens its coordinated context menu by holding the row,
without a visible ellipsis. Actions follow the iOS table coordinator's order:
Pin/Unpin Group, Rename Group, New Workspace in Group, then a separated destructive
section containing Ungroup (Keep Workspaces) and Delete Group (Close Workspaces).
The header preserves its anchor navigation, disclosure, unread/pinned indicators
and shared hold-to-drag/accessibility handling. Selected/sidebar/wide-layout
presentation remains part of the broader UI audit.

Ungroup is unavailable for pinned groups. Both destructive actions present the
iOS confirmation title and Mac-specific explanation; cancellation sends nothing.
An owner replacement dismisses the old owner's menu/dialog, capability withdrawal
retires pending actions, and pinning a group retires its pending ungroup dialog.
The coordinator independently resolves the current group and rejects pinned
ungroup attempts. Delete uses `workspace.group.action` with `action: delete`;
it never loops over workspace-close requests. Successes and rejections reconcile
the owning Mac's authoritative list.

New Workspace in Group has independent `workspace.create_in_group.v1` discovery.
It sends `workspace.create` with only `group_id` through the captured owner's
verified connection, even from the multi-Mac list. Navigation additionally checks
the login, team, saved Mac and navigation generation after the response. A known
created workspace is opened through the existing route/startup path; a legacy
list-only success refreshes the list without guessing a workspace ID. Task/spec
creation still requires an exact created ID. In-flight creation disables another
creation action, and the transport does not replay the mutation automatically.

### Account authority

The scoped upstream `MobileShellComposite+WorkspaceMutationCapabilities.swift`
and `MobileShellWorkspaceMutationTicketPolicy.swift` require Mac-wide authority.
The Android `NativeConnector` currently supplies Stack account authentication
without an attach ticket. Group discovery and execution therefore require
`workspace.mutations.account_auth.v1` in addition to the individual operation's
capability. Legacy ticket-only hosts cannot use these controls through the
current Android connector. Legacy attach-ticket support remains a parity item,
not an unavoidable Android platform difference. No token or transport policy
was weakened, and no credential was logged or changed for these tests.

### Group action verification

The focused JVM run passed 37 tests (34 coordinator, three legacy/exact response
parsing cases). Nine Android checks passed in 98.704s: all four new group-menu
cases, multi-Mac delete/create with colliding group IDs, unsupported-header tap,
hierarchy/anchor/collapse/drag, rejected mutations with preserved terminal-error
recovery, and an OS-held pointer continuing from menu into reorder.

A follow-up confirmed actual painted dialog pixels and added legacy-response and
delayed-navigation cases. Its two creation fixtures initially removed the old
anchor, making the new workspace appear through the group header by design;
the tests incorrectly expected a separate row. With the old anchor retained,
both cases passed in 22.837s using the same production APK. Eleven distinct
Android checks therefore pass across runs. Original failures and the pre-paint
screenshot are retained in the evidence folder; only the painted confirmation
is visual proof. Menu and created-terminal screenshots were also inspected.

Final crash buffers are empty, the sole existing AVD is stopped/reaped, and no
physical Pixel was connected. Evidence:
`captures/runtime/group-actions/verification.json`. The last verified signed
build is still 537; the next signed milestone must verify this accumulated batch.

## Next source audit — recorded during build 546 verification

These findings are from scoped iOS reference `0fc35d6` compared with Android
`2a2c928`; they are incomplete work, not platform exceptions. The broader parity
pin remains unchanged. Raw findings are retained in
`captures/runtime/release-2a2c928/next-source-audit.json`.

1. `WorkspaceListNewWorkspaceMenuValue.swift` asks for a computer when All
   Computers has more than one target. It distinguishes Macs from SSH computers,
   includes the SSH host identity in the menu value, and carries available SSH
   workspace kinds/reasons. Android's toolbar New workspace still directly uses
   the foreground client. Read the toolbar/projection/dispatch source before
   extending the new group creation ownership path to this chooser; also audit
   New Group discovery and plain-create legacy responses.
2. `MobileWorkspaceListFilter.swift` composes read state with a set of exact
   pairing identities. Empty means all computers; bare device IDs match only
   legacy untagged rows, and disappeared selections are pruned. Android currently
   has All/Unread plus one selected computer, without the separate compound
   machine filter. Preserve Stable/Nightly identity boundaries and search/filter
   composition when porting this behavior.
3. `MobileWorkspaceSortMode.swift` defines automatic, computerPriority and
   recentActivity. Activity sorting keeps each group contiguous and ranks it by
   its newest member; search/explicit filters flatten rows. Android has no sort
   control/store/projection yet. Read the store and ordering tests before adding
   this, preserving host sidebar order and drag admission.
4. `WorkspaceNavigationStyle.swift`, `WorkspaceShellView.swift` and
   `MobileWorkspaceShellLayoutPolicy.swift` define push versus sidebar navigation.
   iOS uses the compact stack if either dimension has a compact size class.
   Android currently has no sidebar branch. A wider phone in landscape must not
   become a sidebar solely due to width; read split selection/visibility and
   restoration contracts before implementing the tablet/window layout.

Build 546 now delivers the preceding row/group implementation. CI and local
signed-out upgrade verification are documented in `PIXEL_INSTALL.md`. The
upstream watcher remains absent on main (GitHub contents/workflow queries
returned 404), consistent with the activation requirements in `UPDATES.md`.


## Mac target chooser — 2026-10-04

The native workspace toolbar now offers the visible paired Macs under **New
Workspace** when All Computers contains several Macs. A selected-computer view
only offers that Mac. Each target carries its display name, build label and
connection status. Stable and Nightly remain distinct even on the same device.
Offline or replaced pairings cannot receive a create request from an old menu.

The menu captures its displayed identities and callbacks at opening, while
rechecking account/team, pairing and availability at action time. Account or
computer-filter changes dismiss the old menu. A create uses the exact owner's
verified feed connection, sends `workspace.create` with empty parameters, and
refreshes that owner's authoritative list after success or rejection. It does
not send the request to the foreground Mac or retry the mutation automatically.

Plain creates, including the existing in-pane path, accept valid legacy lists
without a created ID. Such responses refresh the list without guessing what to
open; task/spec creates remain strict. Known created workspaces use the existing
route/startup logic, including partial responses and delayed first terminals.
Leaving the list, changing its computer filter or changing the account/team
prevents a delayed reply from taking over navigation. Group creation shares
this navigation guard. New Group discovery now also requires account-mutation
authority, matching the existing account-only connector.

The scoped iOS `MobileShellComposite+WorkspaceCreateRequest.swift` only requires
Mac-wide mutation authority when `groupID` is present; plain creation therefore
does not require the group/account capability. The coordinator's owner/verified-
connection checks still apply to both operations.

This is the Mac target portion of the iOS menu contract. Saved SSH targets and
their shell/tmux/cmux-tui kind submenus still need integration with the unified
list. The iOS single-target primary tap/long-press behavior is also outstanding;
Android currently retains its single-target menu and New Task entry. Automatic
connection of an offline creation target is not implemented. Compound filters,
sort modes and regular-width navigation remain separate parity work. No broader
parity pin was advanced.

### Target chooser verification

39 focused JVM tests passed. Initial Android run: 9/9 in 72.691s. The final
implementation corrected the connection dots found during screenshot review and
added a delayed filter-change guard: 10/10 Android tests passed in 78.546s. The
runs cover owner routing, stale menus/accounts/pairings, Stable/Nightly identity,
legacy group responses, delayed settings/filter navigation, partial create
responses, terminal startup and late first panes. Screenshots show the target
menu with both connected dots and the selected Mac's terminal. Final crash
buffer empty; production/test source hashes matched the build snapshot.

Evidence: `captures/runtime/workspace-create-targets/verification.json`. Debug
and test APKs built; no new signed APK was requested. Signed 546 remains the
current download. Sole existing AVD stopped/reaped; Pixel absent and untouched.


## Creation gestures and SSH kinds — 2026-10-04

With one Mac target, tapping the workspace toolbar plus now creates directly;
holding it opens New Workspace / New Group options. Multiple Macs still open
the target chooser. The plus uses button and long-click accessibility semantics,
and disables while busy or without an admitted connected target. Its readiness
reads the Compose-observed feed state so completion of a background handshake
updates the control. The separate task-composer button remains available; New
Task was removed from the workspace-creation menu to match the scoped iOS menu.

The SSH workspace screen now has a plus menu in the same kind order and with
the same labels as `SSHWorkspaceKindDisplay.swift`: New cmux-tui Workspace,
New tmux Session, New Shell. Original vector icons identify each kind. Unprobed
hosts offer all kinds; completed probes disable unsupported kinds with a reason.
A supported platform without cmux-tui offers its existing pinned installer.
An unknown or unsupported probed platform does not promise installation.

`NativeSshSession.createWorkspace(expectedHost, kind)` validates the saved route
and live login, opens through the shared connection manager and returns the
exact created target. cmux-tui targets come from the phone-owned session and
match the returned workspace key; tmux targets retain the provider's confirmed
session/window/pane identity. Shells use the validated transport rather than
redialing by mutable host ID. The menu includes host identity even when two hosts
offer identical kinds, rechecks current availability/admission, and retires on
route changes. Delayed creation cannot replace a newer SSH navigation selection.

Scoped sources: `WorkspaceListNewWorkspaceMenu.swift`,
`WorkspaceListNewWorkspaceMenuValue.swift`, `SSHWorkspaceKindDisplay.swift`,
`MobileSSHHostProviders.swift` and `MobileSSHComputers.swift` at `0fc35d6`.

**Remaining integration:** the main All Computers feed still contains native
Mac rows, and its target chooser does not yet include SSH hosts. The SSH menu
and creation operation are exercised on the existing SSH workspace screen;
its older section creation controls remain during the unified-list work. Next,
project `NativeSshRuntime.state` / session hosts and connection statuses into
shared creation targets, pin the login and saved host, and route the returned
`SshWorkspaceTarget` without replaying creation during rotation or restoration.
The main computer selector and feed must also support SSH identities and rows.
This is outstanding app work, not an Android platform exception.

### Creation-kind verification

Six JVM tests passed. Initial Android run: 11 tests, three failures. Two were
direct-tap readiness failures; the button now reads the observed feed state and
the fixtures wait for the enabled control and submitted request. The third
assertion matched the menu and older section New Shell buttons simultaneously;
it is now scoped to the popup. Final Android run: **11/11 in 98.577s**, including
all three SSH kinds against private real cmux-tui/tmux and terminal input/output.
The stale-route guard rejected a mismatched expected host without another shell.

Menu screenshots inspected, source hashes unchanged during the final run and
crash buffer empty. The emulator and private SSH fixture were stopped/reaped.
Evidence: `captures/runtime/workspace-kind-menus/verification.json`. Physical
Pixel/Mac acceptance remains pending; signed delivery remains build 546.
