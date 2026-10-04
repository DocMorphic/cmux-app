# Sidebar in the on-device browser

## Architecture

The separate `:browser` Activity now uses `NativeWorkspaceShell` at the same
840 dp width / 480 dp height breakpoint as the main screen. The main process
provides the global Mac/SSH workspace and notification display projection through
the existing same-UID, presentation-bound Messenger service. The browser receives
no pairing records, SSH credentials, RPC clients, or filesystem authorities.
This continues the scoped iOS shell reference in `WORKSPACE_SIDEBAR.md`; it does
not advance the global upstream parity pin.

The projection shares main-screen workspace sorting, search metadata, group
ordering and notification grouping policies. Computer selection, separate search
queries, unread filtering, local group expansion and notification expansion are
available while the webpage remains mounted. The controller reads only while the
sidebar is visible and the Activity is foreground. It starts with up to 100 rows,
loads additional pages on request and refreshes the visible window. Each page has
a 192 KiB UTF-8 JSON budget and a consistent revision; stale responses and mixed
revisions are rejected. A missing scoped computer yields an empty list and an
explanation rather than silently broadening to All Computers.

Rows contain opaque salted keys. The host issues a one-use navigation ticket only
for a transmitted destination that still resolves. On Activity return it checks
the presentation owner and destination again. Pairing replacement, removed rows,
SSH endpoint/key/generation replacement and changed account/team cannot redirect
an old selection to a new authority. The main provider keeps the captured owner
even when Compose updates its callbacks. Search/tab/computer/unread presentation, notification expansion and workspace
collapse overrides are adopted back into the parent on an accepted return.

A retained feed lease keeps all authorized native computer feeds active only
while the browser sidebar is visible. It is separate from the browser's own host
lease. Closing, hiding, backgrounding and process exit release/deactivate the
appropriate lease; the stable session salt survives parent Activity recreation.
SSH sidebar exits suppress the old browser route's completion callback so it
cannot clear a newly selected destination.

## Remaining parity and verification scope

This is the browser's global **navigation** sidebar. Its workspace rows and group
headers reuse existing components. Compound machine filtering, independent
workspace/notification searches and unread filters, sort mode and computer-order
editing are now shared with the main screen. Settings, Computers and New Task
entry points return to the existing main-screen flows. Notification rows, read/unread gestures and menus, pull refresh and confirmed bulk
read now share the main feed. Mac workspace pin/rename/read/delete and group
pin/rename/ungroup/delete use the owned mutation protocol described below. Mac
Move to Group shares the main-screen move queue and anchored submenu, and
Customize uses the shared workspace editor and save policy. Global Mac/SSH
workspace creation and creation inside an existing Mac group use the shared
parent flows. New Workspace Group now creates in place with the Mac default name.
SSH Close now uses the shared feed with kind-specific confirmation.
Title wrapping and preview-line preferences now share the main settings.
Selection styling now shares the main rows and captures the browser owner.
Workspace changes now open the shared viewer above the live browser.
Drag ordering and modal restoration still need parity work. Main-screen controls remain implemented separately; this does not establish
that the separate browser has every iOS sidebar affordance.

Physical Pixel/Mac acceptance, actual account/team replacement during live
browser use, real native/SSH feed integration with the new browser sidebar,
large-text/accessibility review and authenticated process recovery remain open.
The compact browser stays stacked. Signed build 571 includes the changes through
New Workspace Group; SSH Close, display preferences, selection styling and changes sheets below await the next signed batch.

## Verification

Evidence for this batch is under ignored `captures/runtime/browser-global-sidebar/`.
The JVM suite covers page byte limits/order, invalid revisions, issued-row and
one-use ticket gates, lost destinations, display-only serialization, search
isolation, hidden/background polling, stale responses, missing computer scope,
and stable keys across host recreation.

The first runtime batch passed the existing compact pane-return and rotation
checks, but failed two new sidebar checks (132.758 s). The unread marker was
concatenated into the notification's text; it is now a separate decorative node
with Read/Unread semantics. The other failure occurred when the test's bare
`ComponentActivity` was recreated on return after resizing; unlike `MainActivity`,
that harness cannot reconstruct its injected Compose content. Device lifecycle
logs confirmed destruction/recreation at return. The wide tests now configure
window size in an outer rule before launching the harness and restore it after
cleanup. They do not claim parent Activity recreation coverage.

### Final batch — 2026-10-04

- **33 JVM checks passed:** 17 sidebar/protocol/controller checks, nine shared
  ordering checks and seven shared search checks.
- **Four Android checks passed in 73.398 s** on the sole existing
  API37/16KB AVD: wide sidebar hide/show with an unsent webpage draft and validated
  workspace return; live paused-parent updates, stale destination rejection and
  notification return; existing compact browser/pane return; compact rotation
  preserving the draft, page history and host lease.
- Wide harness: 2400×1600 at the original 420 dpi, established before Activity
  launch. Compact checks: 1080×2400 / 420 dpi. Display settings restored afterward.
  The captured final wide screenshot was inspected; this uses a generated sidebar
  provider and private HTTP fixture, not a physical Mac/Pixel or live SSH account.
- Installed app hash matched the built APK; source hashes matched the frozen
  sources; crash buffer was empty. The emulator was stopped and reaped. No new AVD
  was created, no physical device was connected, and no signed build was dispatched.
- App APK SHA-256: `0f019c891717d33d5df0ff99bae3fb2d06c1363b40f982a3b199df52edd32418`.
- Test APK SHA-256: `032dcf80ba67700afe7ee41e303b904fe827a89161f95f87200913a159933690`.
- Evidence: `verification.json`, `source-hashes.json`, `android.log`,
  `sidebar-final.png/xml`, `display-before.json`, `display-after.json`, and
  `crash-final.log` under the ignored batch directory.

Build history is retained there: initial compile found an incorrect named
parameter (18 s); the first JVM run exposed swapped name/device fixture arguments
(1m32s); corrected APK/JVM build passed (1m33s); IPC-bound check build passed
(27 s); window-test setup build passed (18 s); accessibility/harness repair build
passed (40 s). No emulator ran during these builds.

## Shared filters, sorting and return state — 2026-10-04

Scoped iOS reference remains `0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`:
`WorkspaceListFilterState.swift`, `WorkspaceListFilterControls.swift` and
`MobilePrimarySearchCoordinator.swift` in `Packages/iOS/CmuxMobileShellUI`.
Their shared session state, independent search scopes and composable read/machine
filter are applied to the browser's separate process.

The browser now sends both queries and both unread filters plus opaque selected
machine keys. It adopts all of them back together. A dedicated final state message
runs when leaving the browser, including after hiding the sidebar, so an edit does
not depend on another visible feed poll before return. Removed machine choices
are pruned using the same policy as the main screen; a scoped title picker clears
hidden machine-filter choices. Pruned choices do not reappear on a later poll.

The shared filter menu exposes Last Opened, Custom Order and Recent Activity for
All Computers. The existing computer-order sheet supports drag and accessibility
moves. Sort writes run in the main process against the existing local preference
store, behind the presentation/owner check. Order submissions must contain exactly
the issued/current computer keys; stale, missing, duplicate and foreign keys are
rejected. No host RPC is issued for local sorting. Browser save requests are
serialized, and action failures remain visible across successful feed refreshes.
The order sheet shows save progress/errors and resets its draft after a rejection.

Machine-filter metadata reuses the bounded computer display list rather than
repeating names/build labels. Every IPC page retains its byte/revision checks.
The schema is internal to the same installed APK's two processes.

The first verification batch passed **45 JVM tests** (25 sidebar, nine sorting,
four filtering and seven search) and **five Android tests in 78.018 s**. Build
and APK packaging passed in 1m34s. The new browser test uses the production sidebar
projection with generated two-Mac snapshots: compound filtering, saved mode/order,
both search scopes, both unread filters and return after hiding an active search.
The other checks cover browser draft retention, stale destination rejection,
shared-editor drag/accessibility actions and sort controls without machine choices.
This remains fixture evidence, not live Mac/account/SSH or physical Pixel proof.

The initial order screenshot was taken while rows still appeared in the prior
order despite the persisted preference and underlying list having changed. A
focused follow-up now also requires the displayed row positions to match before
capturing the editor; its outcome is recorded below. Initial verification and
frozen-source receipts are under `captures/runtime/browser-sidebar-controls/`.

The focused repeat passed **one Android test in 45.243 s**, after a **20 s**
test-APK-only build. It now requires Mac B's rendered reorder handle to be above
Mac A's, and the inspected `computer-order-verified.png/xml` shows that order.
App and JVM sources were unchanged from the five-test batch; this is a repeat of
one of those five scenarios, not a sixth distinct test. Installed app/test hashes
and frozen source hashes matched. Display settings were restored, the crash
buffer was empty, and the sole emulator was stopped and reaped.

- App APK SHA-256: `96945c1f34bd1a88691af2510b70529d327dc62f4d05e60e604b1b067c51dddf`.
- Final test APK SHA-256: `5f7dae661303b3cac1dd108c95d85d4c8beca24d698ba4e0a5f092cd2017ec27`.
- Receipts: `verification.json`, `verification-visual.json`,
  `source-hashes-visual.json`, `build.log`, `test-build-visual.log` and runtime
  logs under the ignored `captures/runtime/browser-sidebar-controls/` directory.

No physical Pixel was connected or modified. Real NativeScreen account/native/SSH
integration and parent recreation remain unverified by this fixture. Signed build
554 is unchanged; PR #1 remains draft and the full parity goal remains active.

## Shared Settings, Computers and task entry points — 2026-10-04

Scoped iOS reference: `WorkspaceListView.swift` (`settingsMenu`, `devicesButton`,
`presentComputers`), `WorkspaceListView+Toolbar.swift`, and
`MobilePrimaryTabScaffold.swift` at `0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`.
Settings opens the full settings presentation, Computers uses the shared root
entry point, and the primary task action belongs to Workspaces.

The routed browser's wide sidebar now exposes the cmux-logo Settings button,
Manage Computers, and New Task. NativeScreen supplies their availability and
handles them with the same existing Settings, Computers and task-draft flows as
the main sidebar. Task availability requires the current draft repository. New
Task is absent on Notifications and while the search editor occupies the primary
navigation controls. Opening it only presents the composer; no creation RPC is
sent by this action.

Only an opaque key and one of three whitelisted action kinds cross IPC. Actions
must have been issued in the current display snapshot before a one-use selection
ticket can be prepared. The main process re-resolves the key and account/team
owner both when selected and when the returned callback executes. Removed action
availability, changed account authority and foreign presentation keys invalidate
old callbacks. Duplicate action kinds/keys and action/row key collisions are
rejected. Empty computer/workspace lists still expose available global actions.
Search/filter state uses the existing final hand-back before navigation.

Verification evidence for this checkpoint is kept under the ignored
`captures/runtime/browser-sidebar-actions/` directory; results follow below.

The debug/test APK build passed in **1m43s**. **28 sidebar JVM tests** passed,
including issued one-use action tickets, wire rejection, and callback-time
owner/availability revalidation. **Three Android tests passed in 63.117 s** on the
sole API37/16KB AVD. The new scenario traversed all three action return handlers,
revoked/restored New Task while the browser was open, checked its absence on
Notifications, reopened the browser between actions, and verified lease release
and absence of workspace-creation requests. The existing browser draft-retention
and live-parent/stale-destination cases also passed.

The inspected `actions.png/xml` shows the logo Settings button, computer button,
and bottom New Task control in the wide sidebar. These checks exercise production
Activity/service/proxy/action projection with generated host callbacks. They do
not enter the actual NativeScreen Settings, Computers or composer pages, and do
not prove a live account/native/SSH workflow, parent recreation or process death.
The Pixel was absent and untouched. Installed app/source hashes matched; display
settings were restored, the crash buffer was empty and the sole emulator was
stopped/reaped. No new AVD or signed build was created.

- App APK SHA-256: `5e56b616c326fa64ba29bb53699b9d2feff474f141bb63f9bbe7013e32a01cf3`.
- Test APK SHA-256: `02d90642355fb4eb542c19b38a35fde535fd258240f4efaee0d3cdfb24454c6c`.
- Evidence: `verification.json`, `source-hashes.json`, `build.log`, `android.log`,
  `actions.png/xml`, display-state receipts and crash logs in the batch directory.

Next: complete remote workspace/group mutations and full notification actions/
presentation inside the browser sidebar, then verify main-screen integration and
physical Pixel/Mac acceptance. Signed 554, draft PR #1 and the active goal are
unchanged; this checkpoint does not establish full iOS parity.

## Shared notification rows and actions — 2026-10-04

Scoped iOS reference remains `0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`:
`NotificationFeedRow.swift`, `NotificationFeedRowPresentation.swift`,
`NotificationFeedListRow.swift` and `NotificationFeedView.swift` in
`Packages/iOS/CmuxMobileShellUI`. These define leading swipe/context-menu read
changes, nested metadata suppression, relative times, group disclosure below the
latest row and a confirmation before Mark All Read.

The main feed and routed browser now render the same display-only
`NativeFeedRowValue`/`NativeFeedRowContext` through `NativeFeedRow`. The browser
shows time, source/computer provenance, nested history, read-state semantics,
long-press Open/read menus, leading swipe and the custom accessibility read action.
Title-only history retains its source, and changed connection availability keeps
the computer label; these correct two mismatches in the previous main renderer.
Disclosure now precedes expanded children. The browser also provides pull refresh
and confirmed Mark All Read, including notifications hidden by search.

Only issued opaque notification keys, explicit read booleans and a hash of the
captured computer scope cross the mutation protocol. Both the presentation and
host resolve the current account, exact pairing and notification. Bulk confirmation
cannot silently broaden after a computer selection or pairing change. The feed
coordinator checks exact pairings and the caller's permission predicate inside
its mutation lock before sending. Existing source-revision acknowledgement logic
still owns read-state updates; the browser does not optimistically invent success.
Offline computers retain unread state, with partial failure reported by the shared
coordinator. Browser notification requests reject concurrent duplicate actions,
retain errors across feed polls, and are cancelled when the presentation exits or
the caller's request times out. No mutation is automatically retried.

Verification evidence is retained under the ignored
`captures/runtime/browser-sidebar-notifications/` directory; results follow below.

The first build passed in **1m38s**, with **82 JVM tests**: 34 sidebar, 37 feed
coordinator, nine feed-model and two shared-row presentation checks. The initial
Android batch ran three scenarios in **96.046 s**: filters/sorting/query return
and global Settings/Computers/task navigation passed, while the new notification
scenario timed out after its left-edge gesture invoked Android Back. The window
log and failure image record the browser leaving. The test now starts the swipe
inside the row and waits for its authoritative read state before reversing it.
Its wide-layout exit also uses Android Back, matching that layout's controls.
Test-only rebuilds took **20 s** and **21 s**.

The first focused retry stopped at launch (**38.365 s**): pixels showed the loaded
page, but the UIAutomator tree still held the initial Browser title/loading node.
The test now reads the stable pane-picker node through `UiObject2.text`, which
refreshes it from the accessibility provider, while still asserting the exact
loaded and draft titles. That diagnostic is retained in
`focused-launch-failure.log`, `focused-failure.png/xml` and `focused-runtime.log`.
It does not establish reliable TalkBack event delivery; that remains an explicit
accessibility acceptance item. App/JVM sources were unchanged during these test
repairs. Final focused results follow below.

The fresh-node focused run passed in **38.501 s** after a **20 s** test-only
build. Visual inspection then found the browser divider above the history toggle
instead of below it. The projection now identifies a head row with disclosure,
and the browser places one divider after the disclosure, matching the shared main
feed and scoped iOS reference. This final app/test build passed in **34 s** and
all **82 JVM tests passed again**.

**All three Android scenarios passed on the final build in 99.891 s.** The new
scenario verifies grouped history, rejected and successful menu read changes,
swipe unread, cancellation and confirmation of bulk read with hidden search
results, exact computer scope, pull refresh, unchanged page load count and an
unsent webpage draft. Existing filters/sort/two-query hand-back and all three global
navigation entry points passed in the same final batch. `history-final.png/xml`
was inspected and confirms the corrected divider placement. These are three
distinct scenarios; earlier repeats do not add distinct coverage.

- Final app APK SHA-256: `2e640535790de9cf8da077b9112263aa97c99c7ca9498a307449a5dc68e7f70e`.
- Final test APK SHA-256: `234d178487e04917b46bf1a0d63996f72131b20ebd2ed0e71fc18a69d5cfb5bc`.
- Authoritative receipts: `verification.json`, `source-hashes-final.json`,
  `build-final.log`, `final.log`, `display-before-final.json`,
  `display-after-final.json`, `crash-final.log`, `history-final.png/xml`.
- Installed app/test hashes and frozen sources matched. Display settings were
  restored; the crash buffer was empty. The sole emulator was stopped/reaped.
  No new AVD was created and no physical Pixel was connected or modified.

Runtime uses the production browser Activity/service/proxy/shared row renderer
with generated mutation callbacks. The production feed coordinator is separately
tested against generated RPC peers. This does not prove live Mac/Pixel read
acknowledgements, authenticated main-screen integration, parent recreation,
process recovery, large-font behavior or TalkBack event delivery. Notification
expansion stability across changing groups and expansion-state hand-back to the
parent remain open, as do workspace/group actions and global workspace creation.
The full goal remains active; PR #1 remains draft. Signed 554 stays the last
verified download until the next batched signed build completes verification.

### Earlier expansion restoration audit (2026-10-04; before implementation)

At the same scoped iOS ref, `NotificationFeedProjection.swift:219–236` retains
previous group identity and transfers expansion through surviving notification
members when retention removes an anchor. Android's `NativeFeedProjection.build`
already implements that rule for the main feed, but the routed host currently
passes an empty previous projection on every read. Its query only retains opaque
group anchors, so removal of the oldest event can collapse visible history.

`NativeSidebarPresentation`, `NativeScreen.sidebarInitial` and `sidebarAdopt`
also omit notification expansion and workspace collapse overrides. The browser
controller only reconciles machine filters from the authoritative response;
expansion reconciliation must likewise reach its query before subsequent polls
and final hand-back. Browser sessions have one active entry, but the native host
outlives an individual presentation. Any retained projection must have explicit
presentation/account/team ownership and must reject replaced pairings.

The next implementation needs evidence for anchor removal with surviving members,
explicit collapse surviving refresh, no expansion resurrection after all members
vanish, and main → browser → main restoration. It must also cover a replacement
pairing and changing computer/search scopes. Workspace collapse overrides need
their own hand-back. Exercise changing history in the real Activity/service path,
including return to the parent, while preserving the browser page and unsent
input. These are identified gaps and acceptance checks, not completed features.


## Notification history and workspace collapse restoration (2026-10-04)

The browser now retains notification projection membership in the main-process
`NativeSidebarHistory`, owned by the feed session and captured account/team.
It applies changed expansion intent once and uses the shared feed reconciliation
to retain expansion when an anchor disappears. Repeated in-flight queries cannot
undo the newly selected anchor. Every snapshot carries the reconciled opaque
expansion keys; both the controller and host session adopt them. Only display
keys cross IPC. Previous groups must match current exact pairings, including when
a credential changes while its stable origin stays the same.

`NativeSidebarPresentation` now carries projection and local workspace-collapse
state within the main process. Initial browser presentation maps those states to
opaque keys. Accepted return revalidates current pairings and group IDs, restores
the main feed projection, and merges valid collapse overrides into the existing
persisted preferences. Main-process history survives host recreation with the feed
session and clears with it. Browser OPEN reuses an existing entry query instead
of reinitializing it on reattachment.

**48 JVM checks passed:** five new history/hand-back/controller tests, 34 sidebar
checks and nine shared feed-model tests. They cover surviving-anchor membership,
explicit collapse, stale repeated polls, host recreation, main/browser/main
projection return, collapse overrides, owner replacement, exact-pairing replacement
with unchanged stable origin, scope/search pruning and authoritative wire state.
The first build failed after **1m28s** because the new test fixture omitted required
workspace constructor fields. After correcting the fixture, app/test assembly and
the focused JVM set passed in **38s**. Subsequent test-only builds were **5s**,
**13s** (including strengthened pairing tests), and **4s**; app sources were unchanged.

The first three-scenario Android run took **157.666s**: filter/search return passed;
restoration passed retention and return assertions but the harness stalled on
reopening because its Compose clock was not being pumped. The notification-action
scenario reached its final return with the search editor active, so Android Back
hid the keyboard instead of leaving. Failure screenshots/logs are retained.
The test now waits for the parent lifecycle when reopening and explicitly closes
an active search editor before exit. Both repaired scenarios passed in **71.866s**
on the same app APK. This establishes **three distinct passing scenarios across
the two runs**, not a green first batch or five distinct tests.

The new Activity/service scenario starts with expanded history and a collapsed
workspace group, removes the oldest notification, verifies remaining history stays
expanded, collapses/reopens it, changes the group override, returns, and reopens
with both states restored. It also checks that sidebar actions preserve the web
page's unsent draft and load count before leaving. The group fixture contains its
anchor workspace only; this test checks disclosure state and the returned override,
not additional group-child layout. The retained-history and reopened-group
screenshots were visually inspected. Existing group-row layout tests are separate.

Evidence: `captures/runtime/browser-sidebar-restoration/` (ignored), including
first-run failures, final logs, source/APK verification and screenshots.
App APK SHA-256: `3cfcb39618b15456a22870fe4fbfce1c97b752488b64dd4b70fbf68a51040f94`.
Final test APK SHA-256: `a500473e679a75b6f6259bbc6e8c8447e95751d98a75d50cce20a770b816a67c`.
Installed package hashes matched. Main app sources matched across both Android
runs; all final source hashes matched. Display settings were restored and the
crash buffer was empty. The sole API37/16KB emulator was stopped/reaped; no new
AVD was created and the Pixel was absent/untouched.

Runtime sources and mutation callbacks are generated fixtures. Actual signed-in
MainScreen integration, physical Mac/Pixel behavior, parent recreation, process
recovery and accessibility remain unproven. Build 563 is still the latest verified
signed APK and predates this restoration change. Next: remaining browser workspace/
group actions, global workspace creation and selection behavior, followed by live
integration and the broader parity gates. The goal remains active.


## Mac workspace and group mutations in the browser (2026-10-04)

Scoped references at `0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`:
`WorkspaceListTableCoordinator+Actions.swift`, `WorkspaceListView+Actions.swift`
and `WorkspaceListView+Table.swift` in `Packages/iOS/CmuxMobileShellUI`. The browser
now uses the existing workspace row menus/swipe controls and group context menus
for pin/unpin, rename, read/unread, confirmed workspace deletion, and group
pin/unpin/rename/ungroup/delete. Group rows support direct long-press presentation
without the main list's drag coordinator. Existing main-list callers retain their
original coordinated hold behavior. Ungroup remains unavailable for pinned groups.

Rows advertise a bounded set of explicit operations. The browser sends only an
issued opaque row key, an operation enum and a rename title when needed. Rename
validation rejects empty names and names longer than 4,096 characters with useful
messages. Commands cannot select an RPC method, Mac, native ID or filesystem scope.
The service checks active presentation, owner, workspace tab, visibility, issued
row and advertised operation; host resolution rechecks the current exact pairing,
row, live capability and availability. The coordinator checks the caller's guard
inside its owning mutation lock before sending. Group operations retain the account
mutation capability requirement; they do not loop over workspace-close requests.

Workspace and notification requests share the per-presentation mutation lock.
Duplicate writes are rejected, retirement/cancellation cancels pending jobs, and
timeouts report an unconfirmed result without automatic replay. Errors survive
read polls. Success uses the existing authoritative owner refresh. The webpage
stays mounted while changing other workspaces; deleting its own owning workspace
can retire its existing browser session and still requires live acceptance.

**82 focused JVM tests passed:** five mutation protocol/host/controller tests,
34 sidebar checks, five restoration checks and 38 coordinator tests. New checks
cover strict verbs/title bounds, issued versus untransmitted rows, capability
withdrawal, colliding IDs on different Macs, credential replacement at an unchanged
origin, offline state, group account authority, pinned ungroup rejection, duplicate/
hidden/background/tab rejection, error persistence and caller withdrawal before RPC.
The first app/test build passed in **57s**. After adding clear name-validation copy,
the final build and the same focused checks passed in **10s**.

**All three Android scenarios passed in 125.758s** on the sole API37/16KB AVD:
workspace/group actions, notification read actions and expansion/return restoration.
The new browser Activity/service scenario verifies pinning, rejected rename plus
explicit retry, marking a workspace read, workspace-delete cancellation and
confirmation, group pin/rename/unpin, hidden pinned Ungroup, cancellation of both
group destructive dialogs, and confirmed ungroup preserving the anchor and child
workspaces. It checks unchanged page load count and an unsent web draft while
mutating a different workspace. Group-delete confirmation is cancelled in this
runtime case; group-delete dispatch/authority has JVM coverage, including the
existing framed-RPC coordinator checks. Browser swipe input itself is not added to
this runtime case; the reused swipe component's earlier evidence is in
[WORKSPACE_ROWS.md](WORKSPACE_ROWS.md).

Evidence: `captures/runtime/browser-sidebar-mutations/` (ignored), including build,
JVM/runtime receipts, source/installed-package verification and the visually
inspected post-ungroup screenshot. App SHA-256:
`de8d674aa433b39c366ce71b6335b7dfd13188c87cbd281b3f90d52033506178`.
Test APK SHA-256:
`008a1e4d978b78953b8b5b22f5e788bfc8ef1fdd50fa86ac5e7c53eede83c51f`.
Installed app/test hashes and frozen source hashes matched, display settings were
restored, the crash buffer was empty and the existing emulator stopped/reaped.
No new AVD was created. The Pixel was absent and untouched.

The runtime uses generated source/mutation callbacks; coordinator tests use
separate generated RPC peers. No physical Mac mutation, authenticated MainScreen
integration, parent process recovery or TalkBack delivery is established here.
Move to Group, workspace customization, global/in-group creation, SSH actions,
drag reordering, changes previews and selection/display refinements remain to be
connected in the browser. Signed 563 predates this batch; no signed build was
dispatched for this individual feature. The broad audit and full goal remain open.

## Browser Move to Group — 2026-10-04

The workspace popup now opens the shared anchored group submenu: owning-Mac
choices in host order, group icons, a checked/disabled current group, Back and
Remove from Group. It follows `groupMoveSubmenu` in
`Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/WorkspaceListTableCoordinator+Actions.swift`
at scoped reference `0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`; the global
upstream pin remains unchanged.

Menus load on demand in pages of at most 100 choices and 192 KiB UTF-8. The
browser assembles every page before showing the choices; it does not truncate
the group list. Sidebar refreshes compute eligible workspace IDs once per Mac,
without computing a full move policy for every row. Anchor rows, unsupported or
offline Macs, ambiguous window inventories and saturated queues do not offer moves.

Only display names/icons, checked/enabled flags and opaque keys cross IPC. The
menu revision binds the exact pairing, complete workspace order/membership/pins,
windows and group metadata. The presentation records which enabled destinations
were actually sent. A selection must still match both records and the current
owning-Mac snapshot. The revision hashes explicit primitive fields: Android's
`JSONObject.wrap` does not serialize arbitrary Kotlin records like the JVM JSON
library does.

The main process sends the proposal through `NativeWorkspaceMoves.submit`, sharing
normalization, optimistic order, the three-operation queue and rollback with the
main screen. The browser awaits the Mac acknowledgement; queue admission alone
is not success. Cancellation cancels its queued task, earlier failures propagate
without dependent writes, and presentation permission is checked at the send
boundary. The coordinator compares captured group anchors inside its mutation
lock in addition to its existing owner, order, pin, destination and window checks.
It does not normalize an already-normalized move a second time at that boundary:
the pinned Swift fixtures show that this is not an idempotent operation for every
host topology. Failed actions stay visible and are not automatically resubmitted.

### Verification and repairs

- **108 JVM checks passed:** 38 coordinator, 13 queued-move, nine group-menu,
  four pinned Swift parity, five browser group-menu/protocol, five browser
  mutation and 34 sidebar checks. Coverage includes acknowledgement/rejection,
  cancellation before queued dispatch, caller withdrawal, predecessor failure,
  queue capacity, anchor replacement with otherwise matching order/membership,
  colliding IDs across Macs, stale pairing/order/anchor/menu revisions, disabled
  or unissued choices, UTF-8 page budgets, and all-page collection.
- **Three distinct Android scenarios passed on the corrected production APK.**
  Existing workspace/group actions and expansion restoration passed in the
  123.919s batch; the new group-move scenario passed its final **34.053s** retry.
  It covers host rejection/explicit retry, a group pin changing while the menu is
  open, moving into that pinned group, disabled current membership, Back and
  Remove from Group, unchanged page-load count/draft, and route lease release.
- The first 132.666s runtime batch caught the real Android JSON revision bug
  (two old scenarios passed; the new stale-menu assertion failed). The corrected
  batch then exposed a test querying the enabled text child instead of the
  disabled Compose menu-item parent. A 33.907s focused retry passed the move and
  removal assertions but hit a stale cached toolbar accessibility node. The
  final test reacquires that toolbar node. Both repairs changed only the test;
  app APK hashes were identical across the corrected batch and focused retries.
- Build logs also retain the initial duplicate header/footer test-selector
  failure and the rejected attempt to re-normalize moves at the send gate. The
  latter failed an existing noncontiguous pinned Swift fixture. The final send
  gate compares captured anchors, preserving the original move intent.
- Sources were frozen during each build/runtime run. The final source and
  installed app/test hashes matched, screenshot `menu-final.png` was inspected,
  display settings restored, crash buffer empty, and the sole existing API37 /
  16KB emulator stopped/reaped. No new AVD, physical-device changes or signed
  build dispatch.

This is production browser Activity/service/proxy code with generated workspace
mutation callbacks. The move queue/coordinator use generated framed RPC peers.
Physical Mac/Pixel, authenticated MainScreen integration, process death and
TalkBack event delivery remain separate acceptance work. Evidence is under
ignored `captures/runtime/browser-sidebar-moves/`, including all failed runs,
source manifests, `second-apks.json`, final screenshot/XML and `verification.json`.

- Final app APK SHA-256: `42dac2be66e8950c47538133c21227ebe18daa02f0ae07ba5bca8aeb96f60ef2`.
- Final test APK SHA-256: `c9f25be96367687991dfaa29f98d79d572a078f17d0ee745d79c5044d2d309bf`.

## Browser workspace customization — 2026-10-04

The workspace context menu now includes Customize when the owning Mac advertises
both workspace actions and metadata, including retained offline snapshots. It
uses `NativeWorkspaceCustomizationSheet` and the existing name/description/color/
pinned save policy. The scoped reference remains
`WorkspaceListTableCoordinator+Actions.swift` and
`WorkspaceShellView+WorkspaceActions` at
`0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`; no global pin advance.

The editor requests full metadata from the main process rather than editing the
sidebar row's display-limited subtitle. Only editor values and opaque workspace/
editor keys cross the 192 KiB JSON boundary. Descriptions beyond the shared 4 KiB
editing limit are explicitly read-only and use a bounded preview; editing another
field does not overwrite that Mac description. Exact pairing/workspace closures
and the current baseline remain in a single host-owned editing session. Forged or
outdated baselines/tickets cannot redirect a save or bypass conflict handling.
Partial results replace that host baseline and the shared sheet's baseline/display
values together. Explicit retries skip fields already acknowledged on the Mac.

The service serializes saves with other sidebar mutations and cancels them when
the browser session ends or the client removes the editor. The feed coordinator
checks the caller in its owning lock before every read and field write. Sidebar
polls advertise whether the active editor's owner/workspace/capabilities still
exist; revoked editors close and cancel their save. Disappearing filtered rows do
not revoke an otherwise valid editor. Discovery can remain offline, but writes
still require a verified live connection.

The modal is composed at the Activity root, outside the adaptive sidebar. The
controller continues its foreground feed lease while that modal is open even if
the sidebar is hidden. Closing the modal releases that extra lease when the
sidebar is absent. Background saves are denied. Controller checks cover these
state transitions; an actual resize/rotation of this new modal remains runtime
acceptance work, as do parent recreation and process death.

### Verification

- **67 JVM checks passed:** seven customization-policy, seven framed-RPC
  customization/coordinator (including caller withdrawal between fields), six
  new sidebar customization/protocol/lifetime checks, 34 sidebar, five sidebar
  mutation, five group-menu and three existing editor-target checks.
- **Three Android scenarios passed in 73.967s.** Two new wide scenarios exercise
  the production browser Activity/service/proxy with generated Mac sources:
  colliding workspace IDs, the complete >2,800-character description, a landed
  rename followed by rejected description, retained edits and retry with one
  rename/two description attempts, untouched other Mac, unsaved capability
  revocation, cancellation of a held save, unchanged browser load/draft, and
  release on return. The existing compact browser customization/retry check
  also passed. Color/pin behavior uses the existing sheet and separate policy/RPC
  checks; the new wide scenario does not claim a live color/pin Mac workflow.
- Source/installed app and test hashes matched. The editor and preserved-page
  screenshots were inspected. Display settings were restored, crash buffer
  empty, and the existing API37/16KB emulator stopped/reaped. No new AVD or
  physical-device changes. Builds passed in 1m2s, 7s (runtime fixture) and 12s
  (editor lifetime adjustment); no runtime failure in this batch.
- Evidence: ignored `captures/runtime/browser-sidebar-customization/`, including
  build logs, frozen source hashes, `final.log`, `editor.png/xml`, `page.png`,
  display/crash receipts and `verification.json`.

This does not establish live Mac/Pixel, authenticated MainScreen, actual modal
resize/rotation, TalkBack or process-recovery acceptance. No signed build was
dispatched; signed 563 predates this feature and the preceding browser mutation
batches. Global/in-group creation, SSH mutations, drag and selection refinements
remain browser parity work.

- App APK SHA-256: `9e6b1c4295e2770a281fbfc8415c26f3feab458ed1ba63d86a0992b66ccf58bc`.
- Test APK SHA-256: `2476594ba4c1c40e379ba1abbca8fb9283dc96e3f9b85830b38f507db6b88a1d`.

## Browser workspace creation — 2026-10-04

Scoped iOS reference: `WorkspaceListNewWorkspaceMenu.swift` and
`WorkspaceListView+Actions.swift` in `Packages/iOS/CmuxMobileShellUI` at
`0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`. The browser now has the New Workspace
button: one Mac creates directly, several computers open a destination menu,
and SSH hosts expose the shared cmux-tui/tmux/shell kind rows with unavailable
reasons. Existing group headers expose New Workspace in Group when the owning
Mac advertises account-authorized group creation. Search/unread filtering does
not change the computer receiving a create. A scoped computer selection limits
the menu; a disappeared scope does not silently broaden it.

Creation uses the existing one-use sidebar return ticket and the main screen's
Mac/SSH creation flows. The browser closes its presentation and the parent starts
the chosen flow, which handles connection/progress/errors and routes to the
returned workspace. It does not run a second creation implementation in the
browser process. A legacy Mac response without a created ID refreshes its list
without guessing which workspace to select. Other browser mutations remain in
place; creation intentionally navigates to the created destination.

Only bounded display names, availability, kind/reason values and opaque keys cross
IPC. Issuance is limited to enabled menu destinations and transmitted group rows.
The key binds the Mac pairing/build/account or SSH session/endpoint/key/jump host.
Resolution checks current availability, group existence/capabilities and busy
state again. SSH creation destination keys hash explicit primitive endpoint fields so
Android JSON behavior cannot erase a host/port/username change. The returned
callback rechecks its captured owner; the Mac coordinator now also checks caller
permission inside its mutation lock immediately before sending either create.

Menus snapshot their labels/choices when opened and consult current eligibility
before a tap. A changed SSH endpoint cannot silently replace the chosen target.
Notifications have no creation menu. The wire enforces the existing 192 KiB budget
on menu metadata even when the page has no workspace rows, with a visible error
rather than an unreceivable message.

New workspace **group** creation is still pending: the existing main-screen
New group dialog needs its owner captured through confirmation before it can be
safely shared with the browser. That remaining action is distinct from creating
a workspace inside an existing group, implemented above. SSH close/actions,
drag/order, changes previews and selection/display refinements remain open.

### Verification

- **85 JVM checks passed:** 39 coordinator, three plain-create response checks,
  five new sidebar creation/wire/issuance checks, 34 sidebar checks and four SSH
  kind-policy checks. Coverage includes empty Mac lists, scoped/filtered menus,
  exact owner/group, offline/busy/revoked/replaced targets, one-use and
  transmitted-row gates, disabled kinds and oversized metadata rejection.
- The initial Android batch took **100.717s**: the existing Settings/Computers/
  New Task navigation check passed; the new tests failed on harness timing and
  accessibility scope. One queried the toolbar behind a modal popup; the other
  reopened before the refreshed computer projection arrived. Corrections wait
  for observable UI state and dismiss the popup before querying the toolbar.
- Both new scenarios then passed in **63.581s** on the unchanged app APK.
  Screenshot review found stale connection copy after a Mac recovered. The menu
  now keeps captured destination choices while reading current connection status.
  The strengthened final repeat passed **two tests in 67.261s**, including the
  disappearance of stale Not connected copy. The existing navigation pass
  predates that UI-only status correction; this is three distinct scenarios
  across the batch, not a fully green initial run.
- The Mac scenario verifies a disabled offline destination, reconnect and owning
  Mac selection, group capability revocation/restoration, scoped group creation,
  single-Mac primary action, unchanged browser draft/load before navigation, and
  lease release. The SSH scenario uses an isolated actual host registry/session
  with generated options: cmux/tmux/shell ordering, disabled tmux reason, changed
  endpoint while its old menu remains open, and explicit Shell selection from
  the replacement endpoint. It captures the parent destination without dialing
  or creating a real SSH workspace. No credentials are loaded by that fixture.
- Final frozen source and installed app/test hashes matched. Screenshots were
  inspected, display settings restored, crash buffer empty, and the sole existing
  API37/16KB emulator stopped/reaped. Pixel absent/untouched. No new AVD, signed
  build dispatch, merge or release. Initial compilation failures were incorrect
  new test constructor arguments; corrected app/test/JVM build passed in 25s,
  test repair in 14s, and final status-display build in 15s.

Evidence: ignored `captures/runtime/browser-sidebar-creation/`, including failed
build/runtime logs, before-status receipts/screenshots, final source manifest,
`verification.json`, `final.log` and `computers.png` / `ssh.png`.

- Final app APK SHA-256: `cba373adb2de99f4baa64ddc69811bffb0c0ed040dfff2a569d797cf52f6c8ee`.
- Final test APK SHA-256: `0c909345a5e385d62e317fe7c26cd42afabc0e1d22c512ce6d9bd64cb1f4b52c`.

Live Mac/Pixel, authenticated MainScreen, real SSH/native create progress and
failure recovery, parent recreation/process death and TalkBack acceptance remain
unverified by this batch. Signed 563 predates this feature and preceding sidebar
mutation batches; the full goal remains active.

## New Workspace Group — 2026-10-04

A closer source check supersedes the previous plan to share the Android naming
dialog. At scoped iOS reference `0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`,
`WorkspaceListNewWorkspaceMenu.swift` calls
`WorkspaceShellView+WorkspaceActions.createWorkspaceGroupIfConnected()`, which
calls `MobileShellComposite+WorkspaceActions.createWorkspaceGroup()` without a
title. It sends `workspace.group.create` with empty parameters; the Mac supplies
the default name. There is no name-confirmation dialog in that iOS flow.

Android now follows that behavior. The main screen's extra dialog and retained
name draft are removed. Both menus use New Workspace Group and a folder-plus
icon derived from the existing licensed folder asset. Single-Mac long press opens
the option; the multi-computer chooser includes it outside SSH kind submenus.
The main menu captures the foreground pairing and disables an old group choice
if that identity changes while the menu is open. The main handler rechecks its
login/team, exact pairing, selected computer and foreground connection.

The browser keeps its webpage mounted while creating a group. Under All
Computers, the destination is the current foreground Mac; a scoped Mac view uses
that selected Mac. Search, unread and machine filters do not choose another owner.
A scoped SSH or missing computer has no group action. Discovery requires the
specific group-create and account-mutation capabilities plus a connected source.
The opaque group key binds the pairing and foreground-versus-scoped intent;
changing the foreground Mac cannot redirect an already open menu choice.

CREATE_GROUP is an explicit sidebar mutation, with no caller-supplied title,
Mac identifier or RPC method. It uses the existing serialized, cancellable
mutation path. A queued request also checks that the browser's computer selection
has not changed. The main-process coordinator checks its exact verified handle,
current capability and caller permission under its owning lock, then sends the
unnamed create. Its existing authoritative refresh runs after success or rejection.
Errors stay visible, and only an explicit user retry sends another request.

This creates an empty group and refreshes the sidebar; it does not navigate to
or invent a workspace. Existing workspace creation, including creation inside a
group, continues to use the separate parent navigation flow documented above.

### Verification

- **88 JVM checks passed:** 41 coordinator, five browser creation, five mutation,
  three new group and 34 sidebar checks. New tests cover empty create parameters
  on the exact Mac, authoritative refresh after success/rejection, separate
  group-create capability and account authorization, changed pairing/caller,
  foreground versus scoped selection, busy/offline/revoked targets, mutation-only
  wire permission and a rejected caller-supplied title.
- The initial **91.483s** Android run passed the production main-menu component
  check. Both new browser scenarios passed their action assertions and then
  failed cleanup looking for a compact Back button in the wide layout. Changing
  those two exits to Android Back fixed the harness. The two browser checks
  passed in **53.628s** on the identical app APK; these are three distinct passing
  scenarios across two runs, not a green first batch.
- Main-menu coverage: foreground pairing replacement disables an existing group
  choice, explicit reopening dispatches once, single-Mac hold exposes the action,
  and capability removal hides it. Browser coverage: foreground changes cannot
  retarget an old menu; rejection/manual retry stays on the page; the other Mac
  remains unchanged; scoped creation uses the selected Mac; revocation disables
  the option; a held create is rejected after changing computer scope; subsequent
  explicit creation on the new scope succeeds. Unsent page state/load count,
  visible created group and release on return are checked.
- First app/test/JVM build passed in **1m15s**, main-menu owner refinement in
  **36s**, and test-only exit repair in **13s**. Sources were frozen during each
  run. Final app/test installed hashes and source hashes matched; screenshots
  inspected, display restored, crash buffer empty, emulator stopped/reaped. Pixel
  absent/untouched. No new AVD, merge or release.

Evidence is under ignored `captures/runtime/browser-new-group/`, including build
logs, the failed first run, frozen manifests, final receipts and `groups.png`.

- App APK SHA-256: `c68b4a2f92b6ee2611747d48969a3a47a38aa309e153a3ab2756da390fa731ca`.
- Test APK SHA-256: `e4e6b4436af1a88c8e31d8354a8fc82c325e31bf67bbb9015c69e3b59affb41c`.

These fixtures do not establish real Mac mutations, authenticated MainScreen,
Pixel integration, process recovery, or TalkBack delivery. The broader parity
and physical acceptance work remains open.

## SSH workspace Close — 2026-10-05

Reference: `0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`, scoped reads of
`Packages/iOS/CmuxMobileShell/Sources/CmuxMobileShell/MobileWorkspaceCloseConfirmation.swift`
and `Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/WorkspaceShellView+WorkspaceActions.swift`.
iOS directs SSH close to the SSH runtime. Persistent tmux/cmux-tui workspaces ask
for confirmation; a phone-owned shell closes immediately. Android's main feed
previously added a shell question; the shared row now supports the same nullable
confirmation policy and the browser transmits only the display kind.

The host advertises only Close for eligible SSH rows. Issuance, account ownership,
foreground/visible state and mutation serialization use the existing sidebar
protocol. No SSH endpoint, credential or arbitrary operation crosses IPC.
SSH row keys now hash explicit primitive fields (Android JSON wrapping cannot be
relied upon for Kotlin endpoint/UUID objects), including host, endpoint, key,
jump host, generation, registry and cmux terminal/content identity list. A changed
endpoint or confirmation membership retires the old choice. Renames keep the
underlying owner. Mac mutations and navigation remain separate dispatch branches.

`NativeSshWorkspaceFeed.submitClose` awaits the provider result, holds duplicate
admission until completion, retains error reporting and passes a caller/owner
check into the owning provider queue. cmux-tui checks before starting and after
its suspending identity reads, including the final read for a workspace with no
terminals. tmux checks before the linked-session detach/kill sequence. Once the
first destructive operation is dispatched, its existing provider-owned completion
semantics apply; the UI never implicitly retries an uncertain close. Shell removal
uses the captured phone-owned shell ID. Real remote identity/content checks remain
in the existing providers.

### Verification

- **66 JVM checks:** 4 new SSH host/wire/confirmation cases, 11 cmux provider cases
  (including queued revocation, revocation after lookup, completion after dispatch
  and empty-workspace final-read revocation), 4 tmux inventory cases, 8 SSH feed
  cases, 34 sidebar cases and 5 mutation cases. No failures/errors/skips.
- Debug and instrumentation APKs built. The first build caught an old test helper
  dereferencing the now-nullable confirmation; it now explicitly requires the
  persistent-kind confirmation it tests.
- First Android batch: close/confirmation/retry passed; endpoint-change scenario
  stopped at readiness because its title lookup was stale. Screenshot pixels
  showed the loaded browser while accessibility still reported the initial title.
  The new tests use the stable toolbar accessibility identifier for readiness.
- Final **2 browser checks passed in 67.684s** on the existing API37/16KB arm64
  emulator: shell immediate close; cancel/confirm tmux and cmux-tui; rejected close
  and explicit retry; colliding host session IDs stay separate; endpoint change
  while a close is held rejects the old request; reopening targets the new endpoint;
  going offline removes the menu action. Page load count and unsent draft stay
  unchanged, and returning releases the browser/sidebar leases.
- Separate production MainScreen/feed test over a generated loopback SSH server,
  real cmux-tui 0.13.4 and tmux passed in **14.61s**. It rejects a confirmation whose
  workspace contents changed, then closes the confirmed cmux workspace and tmux
  session without a Mac workspace-close RPC. The private fixture was terminated
  cleanly, its directory removed and ADB reverse removed.
- Source and installed APK hashes matched; final screenshot inspected; display,
  timeout and stay-awake restored/unchanged; final crash buffer empty; emulator
  stopped/reaped. No new AVD. Pixel absent and untouched.

Debug APK SHA-256: `7a73685e73b65c548c55b699c17ef18f32c2476ef1deb98050667e468df6929f`.
Final test APK SHA-256: `6ad9ed11b9210fb237764399843a58115dafa34b216f652881e0d261613cd28d`.
App APK unchanged across the test-only repair. Evidence:
`captures/runtime/browser-ssh-close/` (ignored), including build logs, JVM receipts,
initial/final Android logs, real-SSH log, screenshots, source/installed hashes and
cleanup receipt. Browser callbacks were generated; the real SSH integration ran
separately through the main feed. Physical Mac/Pixel, real browser-to-SSH close,
authenticated migration, process death and accessibility acceptance remain open.
Signed 571 predates this feature; no signed build or release was dispatched.

## Workspace display preferences — 2026-10-05

Scoped iOS `WorkspaceRow.swift` at `0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`
uses `wrapWorkspaceTitles` and `previewLineLimit` for both ordinary and selected
workspace rows, reserving the chosen preview height. Android's main list already
uses `NativeDisplayPreferences`; the browser had been calling the shared row with
defaults. `NativeSidebarInput` now reads the main process's current display store,
including when the parent composition is paused. The projection carries only
`wrap_titles` and `preview_lines`, and `NativeWorkspaceRow` renders those values.
No browser-side SharedPreferences copy or unrelated terminal settings are sent.

Paging keeps the settings from the captured snapshot; later reads obtain current
values. Absent fields retain previous defaults (no wrapping, two preview lines).
Line counts outside 1–2 are rejected. The existing metadata byte budget applies.

Verification: **36 JVM checks** (2 new display/wire/paging cases, 34 sidebar
regressions) and **2 Android checks in 52.6s**, with no failures/skips. The browser
case uses isolated real SharedPreferences and the production Activity/service.
It changes preferences while the parent remains paused, verifies title height
increases and preview height decreases, hides the sidebar, changes back and checks
the original dimensions after reopening. Page-load count and unsent draft remain
unchanged; return releases leases. The main shared row/settings test checks actual
Compose line counts, ellipsis, reserved height, remount persistence and malformed
stored preferences. Both layout screenshots were visually inspected.

Debug APK SHA-256: `0a2dc5ef44830329ceb0fe98899146d7eda37f7d8987df73e0d9dacf17454035`.
Test APK SHA-256: `7d773384e3388bfbdd924e0e4b302ef97c94cdaa5ad9993e5af700cb0a49a2ff`.
Sources and installed hashes matched, display/settings restored, crash buffer empty
and the existing API37/16KB emulator stopped/reaped. No new AVD or signed batch;
Pixel absent and untouched. Evidence: `captures/runtime/browser-sidebar-display/`
(ignored). Generated feed; no physical/account/process-death acceptance.

Selection remains pending. Scoped iOS `WorkspaceListRowModel.swift` selects only in
sidebar navigation and compares the exact workspace ID (or group's live anchor).
`WorkspaceRow.swift` uses an accent title and a 14pt rounded accent fill at 0.14
opacity. Android's main/shared workspace row currently lacks selected input. The
browser selection must bind its captured presentation workspace and exact Mac/SSH
owner, not merely the parent's mutable foreground computer. Mac and SSH browser
workspace keys use different namespaces; do not create SSH networks to infer a
highlight. Implement the shared selection visual/accessibility state with tests
for colliding IDs, owner replacement, groups and compact-vs-sidebar layout next.

## Selected workspace and group anchor (2026-10-05)

Scoped iOS source: `WorkspaceRow.swift`, `WorkspaceListRowModel.swift` and
`WorkspaceGroupHeaderRow.swift` at
`0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`. Workspace selection uses accent title,
14dp radius, 14% accent fill and 10dp inset; group anchor selection uses primary
8% fill with 6dp radius. Both are restricted to sidebar navigation.

`NativeSidebarSelection` matches the exact Mac record/workspace or SSH connection
identity and workspace descriptor. Cmux matches stable key/resource when available,
otherwise the captured generation plus workspace number; tmux uses its server /
session / creation identity and Shell its owned ID. The group match additionally
requires its anchor to exist in current inventory. `NativeRoutedSidebarHost` binds
a projection to each browser presentation, keeping all authority in the parent;
only `RoutedSidebarRow.selected` crosses IPC, defaulting false for older messages.
Main-screen SSH selection follows `onDisplayed` without recreating its route.

Verification:

- 40 JVM checks: four new selection cases, 34 existing sidebar checks and two
  display-preference cases. Covers colliding Mac/SSH IDs, replacement pairings,
  endpoint/key/jump/registry changes, stable versus recycled workspace identities,
  empty/missing anchors, search, independent browser bindings, legacy defaults and
  wire privacy/round trip. No failures/errors/skips.
- Two Android scenarios passed in **33.863s** on the existing API37/16KB arm64
  emulator. Shared rows verify selected semantics, blue/gray pixels and compact
  removal. The actual browser Activity/service receives a generated two-Mac feed:
  a selected workspace becomes a selected group anchor, pairing replacement removes
  selection, and hide/restore retains page count and draft and releases leases.
- First runtime: row test passed; browser stopped on a stale UIAutomator node during
  pairing replacement. Specific stale-node reacquisition fixed the test only;
  final app APK remained unchanged. Final source/installed hashes match; screenshots
  inspected, crash buffer empty, original display/settings restored, emulator reaped.
- App APK: `b4740a16bf69142634ea8135fb45947422e1fd3ef3b2fc9734349c0ec46d57f9`.
  Test APK: `2bfd18e584248d0cc52b3153310d06f4ba2b7c63b3fd53ba299b48328d43f8cb`.
  Evidence: `captures/runtime/sidebar-selection/` (ignored).

No physical/account/process-death acceptance is inferred. Native/SSH call sites
compile and share tested identity policy, but this batch does not exercise their
full authenticated navigation. Signed 571 predates this feature; no signed build
or release dispatched. Drag ordering and changes previews remain next.

## Workspace changes sheet and live browser retention (2026-10-05)

Scoped iOS `WorkspaceListView.swift` presents `WorkspaceChangesSheet` with a large
sheet; `WorkspaceListView+Table.swift` and `WorkspaceListRowModel.swift` supply the
changes capability/chip/action, and `WorkspaceRow.swift` renders the independent
44pt button. Reference: `0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`.

Android now supplies `WorkspaceChangesChip` in each eligible browser workspace row.
Only a row actually issued in the current paged snapshot may open a preview. The
main process resolves its opaque row key against live exact-pairing inventory and
issues a one-use ticket to the unexported `RoutedChangesActivity`. That transparent
host keeps the original browser Activity/WebView mounted underneath the changes
sheet. Its ViewModel owns a feed lease and navigation state; disposal releases the
lease. No arbitrary RPC method or file-content payload is proxied through Binder.

`WorkspaceChangesAccess` captures the workspace and checks ownership before and
after each response. The feed coordinator requires the exact admitted, verified
connection and changes capability for every read, and rejects results from a
retired/replaced connection. Reads use IO without entering the mutation queue. The
existing `ChangesStore`, `ChangesContent`, continuation and content-transfer code
provide the viewer. Main-list chips now open this shared sheet without replacing
the selected pane. Legacy explicit changes routes remain supported.

Verification:

- 50 JVM checks: three new sidebar/ownership cases, 34 sidebar protocol/controller
  checks, seven changes-store and six content-transfer cases. No failures/skips.
- Two chip UI cases and the production NativeScreen/TCP fixture test passed in the
  first Android batch. The latter opens the owning Mac's files without terminal
  replay and dismisses back to the workspace list.
- Final browser scenario passed in **27.219s**: production Activity/service/sheet,
  generated two-Mac feed with identical workspace IDs; B file/diff calls and visible
  `fresh-B`; close back to unchanged page count/draft; A capability revocation closes
  its sheet and removes its chip; no workspace navigation and all three leases
  released. The first browser attempt needed main-process virtual-clock pumping
  and also reported an ActivityScenario teardown failure; only the test changed.
- Source and installed APK hashes matched, screenshots inspected, crash buffer
  empty, display/settings restored, sole existing emulator stopped/reaped. Evidence:
  `captures/runtime/sidebar-changes/` (ignored), including original failed output.
- App APK: `ab2024101e1675676f952a1fbb9eb4a36db252120056c19deff7feea9dac6430`.
  Test APK: `84bf460af4fd89114b845a2af05f1557d8485363bac6d19958be12421cf6e1ce`.

The new routes reuse expansion/content-preview code, but this batch does not
exercise every such UI path through the new sheet. Modal restoration/rotation,
main-list presentation retention, process recovery, real Mac/Pixel and account/team
replacement remain acceptance work. Drag ordering remains open. Signed 571 predates
this change; no new signed build or release was dispatched.


## Changes sheet recreation and diff retention (2026-10-05)

`WorkspaceChangesPresentation` owns the selected-file navigation, `ChangesStore`
and its seven-page cache/scroll positions. NativeFeedSession and RoutedChangesModel
retain this presentation independently of Compose/Activity recreation. The shared
sheet no longer refreshes/disposes the store whenever composition is replaced.
Dismissal and owner disposal close it; current account/workspace/capability checks
continue to gate rendering and every read. Main-list admission errors are caught
at the click boundary and displayed through the existing screen error state.

Verification:

- 10 JVM checks passed: ChangesStoreTest (7), RoutedSidebarChangesTest (3).
- Two Android scenarios passed in **77.514s** on the existing API37/16KB emulator.
  NativeScreen with a real TCP RPC fixture preserves presentation identity,
  selected README.md and a visible scrolled line across recreation and landscape
  rotation, with no extra files/diff reads. Explicit close clears it; sign-out plus
  recreation removes it without another read or terminal replay.
- The production browser changes Activity recreates while B's selected diff is
  open. No extra B files/diff request or early lease release occurs. Dismissal
  preserves the browser page/draft; A capability revocation dismisses its sheet;
  all three leases release. The injected parent Activity is not recreated.
- First main-list attempt failed on a stale accessibility node; the test reacquires
  only on StaleObjectException with a bounded deadline. Two intermediate test
  compiler failures while adapting the lookup are saved alongside original logs.
  A later review added tap-time error handling; final tests use that exact app.
- App APK SHA-256:
  `acf8ff126b27a9632d69fab97ec13bc5266c5fb3b60729fd01e0520df036ea96`.
  Test APK SHA-256:
  `8741c5cb5d14701209866a72b613f77df2bfe04415ee3ae964bd557cf649c163`.
  Source/installed hashes match. Screenshots inspected, crash log empty, original
  display/settings restored; sole emulator stopped/reaped, Pixel absent/untouched.
  Evidence: `captures/runtime/changes-sheet-restoration/` (ignored).

No process-death recovery, browser-parent rotation, full binary/content-preview
modal restoration, real Mac/Pixel or account/team acceptance is inferred. Signed
571 predates this change. Browser drag ordering and the broader parity gates remain.


## Browser drag ordering and shared long-press gestures (2026-10-05)

Scoped iOS references at `0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`:
`WorkspaceListView+DragDrop.swift` and `WorkspaceListDropProposalPolicy.swift`.
The Android browser now has a stable lazy-list gesture surface, held context
menus, lifted row/drop indicator, before/after and group-center drops (8dp inset),
edge autoscroll with further-page requests, and Move up/down accessibility actions.

Snapshots advertise a stable drag revision and per-row accessibility availability.
The separate revision hashes exact pairing identity, rendered keys, workspace
order/group/pin/window state and group anchors/collapse state; preview updates do
not invalidate a gesture. Only already-issued source/destination rows are admitted
across the byte-bounded paging exchange. IPC contains opaque keys/placement only.
The main process reconstructs the current list and applies the existing move
normalizer; arbitrary IDs, cross-Mac destinations and stale orders cannot redirect
it. Unchanged drops do not send a mutation. Filters, host capability/availability,
known window, pin/recency and three-pending-move rules gate admission. The existing
feed-owned move queue provides prediction, serialized sends, rollback and epoch
validation. Drag requests do not take the sidebar's single ordinary-mutation slot;
this allows the existing three-move queue to operate. Cancellation/retirement and
query/owner changes are rechecked before sending.

Verification:

- **90 JVM checks passed**, including six new drag cases and 13 existing move-queue
  cases. Coverage includes scoped colliding Mac IDs, collapsed-group membership,
  whole-group moves, window/filter/recency/pin/offline/queue admission, revision
  freshness versus preview updates, owner guards, wire privacy and issued paging.
- **Four Android cases passed in 113.114s** on the existing API37/16KB arm64 AVD.
  Shared UI semantics use the latest revision and reject a retained action after
  its row permission is removed. Production browser/service testing uses generated
  mutations with OS-level down/hold/move/up gestures: a rejected group drop leaves
  membership unchanged, retry joins the group, dragging its header reorders the
  whole group, and page load count/draft remain intact. Existing group-menu stale
  revision/retry and workspace/group mutation/confirmation scenarios also pass.
- Initial JVM run had one incorrect single-computer filter assumption; the revised
  test selects a subset of two available computers. Original diagnostics retained.
- App APK: `6a712984c99fb3362862ada4e6b5d11df5cf75d93aa46ba0358b17747f0e41e2`.
  Test APK: `dd51517ffc33d9d5d4516c36a99cff2ea1e7f1c2a0ae19f68197af0526225c79`.
  Source/installed hashes matched, screenshots inspected, crash log empty, display
  settings restored, sole emulator stopped/reaped, Pixel absent and untouched.
  Evidence: `captures/runtime/browser-sidebar-drag/` (ignored).

Physical/authenticated Mac integration, large-list autoscroll/paging, slow-host
pipelining in the browser and accessibility with actual TalkBack remain acceptance
work. Signed 571 predates this batch; no release/merge is implied by these checks.
