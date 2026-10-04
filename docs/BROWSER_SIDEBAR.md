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
even when Compose updates its callbacks. Search/tab/computer/unread presentation
is adopted back into the parent on an accepted return.

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
read now share the main feed. Remote workspace/group mutations, global workspace
creation, expansion-state hand-back and selection styling still need parity work. Main-screen controls remain implemented separately; this does not establish
that the separate browser has every iOS sidebar affordance.

Physical Pixel/Mac acceptance, actual account/team replacement during live
browser use, real native/SSH feed integration with the new browser sidebar,
large-text/accessibility review and authenticated process recovery remain open.
The compact browser stays stacked. Signed build 554 predates these changes.

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

### Expansion restoration audit (2026-10-04; implementation pending)

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
