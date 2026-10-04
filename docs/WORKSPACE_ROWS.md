# Workspace row presentation

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
