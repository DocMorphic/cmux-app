# Codex laptop handoff — 2026-09-28

**Continuation update:** work returned to the original Mac on 2026-09-28 at the
user's request. Windows commit `9849010` was pulled without conflicts. It adds
portable Gradle setup, LF-preserved asset hashes, remote POSIX path semantics,
and a focused runtime runner. See `WINDOWS_DEVELOPMENT.md` and the follow-up in
`SYNTAX_CHECKPOINT.md`. The pause and outstanding four-test statements below
describe the original handoff, not the user's current instruction to continue.
The user has since explicitly approved enabling native mobile pairing, and the
Mac panel reached Iroh Ready. The four outstanding viewer fixtures now pass on
the physical Pixel (10.981 s), including visible Mermaid/Vega pixel checks; see
`SYNTAX_CHECKPOINT.md` for precise scope and APK hashes.
Read [IROH_V2.md](IROH_V2.md): the live host requires Iroh, superseding the old
Tailscale-first plan. Historical listener-approval restrictions below are resolved.

Read this first, then `PARITY.md`, `ANDROID_TESTING.md`, `RESEARCH.md`, and
`PIXEL_INSTALL.md`. This is a continuation of an existing Android implementation,
not a request to scaffold another prototype. The dated sections of the other
documents are historical; newer verified entries supersede older pending claims.

**Delivery update — 2026-10-04:** signed development build **563** is now verified.
See [PIXEL_INSTALL.md](PIXEL_INSTALL.md) and the latest checkpoint below for its
source, download, packaging, ART and signed-out upgrade evidence. The goal remains
active; physical/authenticated acceptance, feed/push configuration and broader
parity remain open. Older delivery sections below are historical.

## Latest checkpoint — browser workspace creation (2026-10-04)

New Workspace in the browser now uses the existing parent creation flows:
single-Mac direct creation, multi-computer selection, SSH cmux-tui/tmux/shell
choices and creation inside an existing Mac group. Opaque one-use destinations
bind the exact owner, and the main coordinator rechecks permission at send time.
Open menu choices cannot switch to a replaced SSH endpoint; availability labels
and status dots update when a Mac reconnects. See [BROWSER_SIDEBAR.md](BROWSER_SIDEBAR.md).

**85 focused JVM checks passed.** The two new Android scenarios passed their final
run in **67.261s** on the existing API37/16KB emulator. An existing Settings/
Computers/New Task navigation scenario passed earlier in the batch. The new
checks cover offline/reconnected Mac choices, single-Mac creation, owning-group
routing and capability revocation, disabled SSH types, endpoint replacement and
return/lease release. These are generated Mac/SSH inputs and captured parent
creation destinations; framed-RPC coordinator tests are separate. They do not
prove authenticated MainScreen, actual remote creation or physical Pixel/Mac use.

Source/installed APK hashes matched; screenshots inspected, display restored,
crash buffer empty and emulator stopped/reaped. Pixel absent/untouched; no new
AVD or signed build. Build **563** remains the latest verified signed download.
Next: capture the New group dialog's owner through confirmation and share that
flow with the browser, then SSH actions, ordering/selection and the remaining
physical/network, configured push/notice, legacy-ticket and upstream audit work.
PR #1 remains draft and the full goal remains active.

## Earlier checkpoint — browser workspace customization (2026-10-04)

The browser sidebar now opens the shared Customize Workspace editor for the exact
owning Mac/workspace. It preserves full editable metadata, offline discovery,
read-only oversized descriptions, conflicting-Mac-edit handling, partial-save
rebasing and explicit retry. Host-owned editor tickets and baselines bind saves;
caller permission is rechecked before each coordinator read/write. Lost capability
or workspace ownership closes the editor and cancels an in-flight save. The modal
lives outside the sidebar layout and keeps its feed lease when that sidebar hides.
See [BROWSER_SIDEBAR.md](BROWSER_SIDEBAR.md) for source references and limits.

**67 focused JVM and three Android checks passed**; runtime **73.967s** on the sole
existing API37/16KB emulator. The new scenarios verify a >2,800-character initial
description, partial rename/description rejection and retry without duplicate
rename, colliding workspace IDs on two Macs, unsaved and saving editor revocation,
unchanged webpage load/draft and lease release. The existing compact browser
customization scenario also passed. Source/installed hashes matched, screenshots
were inspected, display settings restored and crash buffer empty. Emulator
stopped/reaped; Pixel absent/untouched. No new AVD or signed build dispatched.

This is generated-host browser evidence plus separate framed-RPC coordinator
checks. Live Mac/Pixel, authenticated MainScreen, actual editor resize/rotation,
process recovery and accessibility acceptance remain open. Signed **563** remains
the latest verified download. Next: global/in-group workspace creation in the
browser, SSH actions, drag/order and selection refinements, followed by the
remaining physical/network, configured push/notice, legacy-ticket and upstream
audit work. PR #1 remains draft and the full goal remains active.

## Earlier checkpoint — browser Move to Group (2026-10-04)

The browser now uses the shared anchored Move to Group submenu, including current
membership, icons and Remove from Group. Choices load through bounded IPC pages
and bind the exact pairing, workspace order and group metadata. The browser waits
for the shared move queue's Mac acknowledgement; stale menus, cancelled callers,
full queues and changed group anchors cannot dispatch dependent moves. See
[BROWSER_SIDEBAR.md](BROWSER_SIDEBAR.md) for source references and the Android/JVM
serialization bug caught and fixed during runtime verification.

**108 JVM checks passed. Three distinct Android scenarios passed on the corrected
app:** existing workspace/group actions and restoration in the 123.919s batch,
then the new group-move scenario in a **34.053s** focused retry. Test-only repairs
handled Compose's disabled parent semantics and a stale accessibility handle;
the production app APK was unchanged between those batches. Source/installed
hashes matched, the menu screenshot was inspected, display settings restored and
crash buffer empty. The existing API37/16KB emulator was stopped/reaped; no new
AVD. Pixel absent/untouched. This uses generated browser hosts and separate RPC
peers, not live Mac/Pixel or authenticated MainScreen acceptance.

Signed **563** is still the latest verified download; this feature awaits the
next signed batch. Next: browser workspace customization, global/in-group creation,
SSH actions, drag/order and selection refinements. Physical/network/process
recovery, configured push/notice feed, legacy tickets and the broader upstream
audit remain open. PR #1 remains a draft and the full goal remains active.

## Earlier checkpoint — browser Mac workspace/group actions (2026-10-04)

The browser sidebar now exposes the shared Mac workspace pin/unpin, rename,
read/unread and confirmed delete controls, plus group pin/unpin, rename, ungroup
and delete. Issued opaque keys and explicit verbs cross IPC; the host resolves
current exact pairings and capabilities. A caller guard is checked inside the
coordinator mutation lock. Group account authority, pinned-ungroup restrictions,
confirmation dialogs, duplicate prevention, cancellation and error retention are
preserved. See [BROWSER_SIDEBAR.md](BROWSER_SIDEBAR.md) for scope and source refs.

**82 JVM and three Android scenarios passed**; runtime **125.758s** on the sole
API37/16KB emulator. The new scenario covers rename rejection/retry, read state,
workspace delete confirmation/cancel, group pin/rename/unpin and ungroup while
preserving the web page/draft. It cancels group Delete; positive group-delete
dispatch has separate JVM/framed-RPC evidence. Source and installed APK hashes
matched, the screenshot was inspected, display settings restored and crash buffer
empty. Emulator stopped/reaped; no new AVD. Pixel absent/untouched.

This is generated-host browser evidence and separate RPC-peer coverage, not live
Mac/Pixel or authenticated MainScreen acceptance. Signed **563** remains the latest
verified download; this feature awaits the next batched signed build. Next: browser
Move to Group and workspace customization, global/in-group creation, SSH actions,
drag/order and selection refinements. Physical/network/process recovery, configured
push/notice feed, legacy tickets and the broader upstream audit remain open. PR #1
stays draft and the goal remains active.

## Earlier checkpoint — browser expansion and collapse restoration (2026-10-04)

The browser now retains expanded notification history when old anchors disappear,
shares expansion and workspace-collapse state with the main screen, and reuses
its current query on reattachment. Exact pairings and captured account/team scope
bound retained history and accepted hand-back. See
[BROWSER_SIDEBAR.md](BROWSER_SIDEBAR.md) for the design, source reference and limits.

**48 JVM checks passed.** Three distinct Android scenarios passed across two runs:
filter/search return passed in the initial batch; restoration and notification
actions passed a **71.866s** focused retry after repairing test lifecycle/keyboard
handling. The initial **157.666s** batch had two harness failures, retained with
screenshots and logs. The app APK was unchanged between runs. Installed app/test
hashes and final sources matched; display settings were restored, crash buffer
empty, and the single API37/16KB emulator stopped/reaped. No new AVD; Pixel absent.

The runtime exercises production browser Activity/service code with generated
sources and a parent harness. Signed-in MainScreen/physical Mac/Pixel integration,
parent recreation, process death and accessibility remain unverified. Signed **563**
remains the latest verified download and predates this change; no signed build was
dispatched for this individual feature. Next: browser workspace/group actions,
global workspace creation and selection behavior. Production push/notice setup,
legacy tickets, live recovery and the wider upstream audit remain open. PR #1
stays draft and the goal remains active.

## Earlier checkpoint — signed browser-sidebar batch 563 verified (2026-10-04)

Signed build **563** at `2b8aa38158b0ea17912eaa01810e8cec2a4cd54d` is the latest
verified download. It includes the browser sidebar, filters/sorting/search,
Settings/Computers/New Task navigation and shared notification actions below.
[PIXEL_INSTALL.md](PIXEL_INSTALL.md) records the artifact, hashes and exact scope.
CI passed JVM/APK/packaging/Android 17 ART checks (Gradle **8m54s**). Independent
checks confirmed artifact provenance, stable signer, 14 assets, 19 native alignment
checks and exclusion of eight debug fixtures. CI and local arm64 ART accepted the
release class with 1,394 methods.

The existing API37/16KB emulator upgraded **554 → 563** without clearing data,
retained first-install time and cold-launched MainActivity in **1,694 ms**. Sign-in
screenshots were inspected, the crash buffer was empty and `pageSizeCompat=0`.
This is signed-out emulator evidence, not authenticated migration, physical
acceptance or a performance benchmark. Display settings were unchanged; the
emulator was stopped/reaped and no new AVD created. The Pixel was absent/untouched.
Local receipts: `captures/runtime/release-2b8aa38/` (ignored).

The scoped expansion audit confirmed that iOS retains expansion through surviving
notification members. The browser currently resets prior projection membership
and omits expansion/collapse state from parent hand-back. The next implementation
and acceptance checks are in [BROWSER_SIDEBAR.md](BROWSER_SIDEBAR.md). No expansion
fix is claimed here. Workspace/group actions, global workspace creation, selection,
live account/network acceptance, configured push/notice feed, legacy tickets and
the broader upstream audit remain open. PR #1 stays draft; the goal remains active.

## Earlier checkpoint — shared browser notification presentation and actions (2026-10-04)

The browser now uses the main feed's display-only notification row renderer:
relative time, nested metadata, history disclosure, read state, swipe, long-press
Open/read menus and the custom accessibility read action. It adds pull refresh
and confirmed Mark All Read, including search-hidden notifications. Mutations
resolve issued keys to exact live pairings; bulk confirmation binds the computer
scope. The shared coordinator validates pairing and caller permission inside its
mutation lock. Failed updates remain visible without automatic resubmission.

**82 JVM and three Android checks passed on the final app** (runtime **99.891 s**,
sole API37/16KB AVD). The final screenshot confirms disclosure/divider placement.
Earlier test gesture/accessibility-cache failures, repairs, build times, scoped
iOS references, hashes and verification limits are recorded in
[BROWSER_SIDEBAR.md](BROWSER_SIDEBAR.md). App/test/source hashes matched, display
settings were restored and the emulator stopped/reaped. The Pixel was absent and
untouched; no new AVD was created.

The runtime uses generated host mutation callbacks; coordinator tests use generated
RPC peers. Physical Mac/Pixel read acknowledgements, authenticated integration,
parent recreation/process recovery and TalkBack event delivery remain unproven.
**Next:** verify the batched signed milestone, then notification group expansion
stability/parent hand-back, workspace/group actions, global workspace creation and
selection refinements. Production push/notice configuration, legacy tickets and
the broad upstream audit remain open. Signed **554** is still the last verified
download until a new batch passes. PR #1 stays draft and the full goal is active.

## Earlier checkpoint — browser Settings, Computers and New Task (2026-10-04)

The routed browser's wide sidebar now exposes cmux-logo Settings, Manage
Computers and New Task. They return through issued, one-use navigation tickets to
the same existing main-screen handlers. Action availability/account ownership is
rechecked before selecting and before executing a returned callback. New Task
requires the draft repository, appears on Workspaces and opens the composer
without sending a workspace-creation request.

**28 JVM and three Android checks passed** (runtime **63.117 s**, sole API37/16KB
AVD; build **1m43s**). The new runtime check covers all three return handlers,
availability revocation/restoration, notification-tab visibility and repeated
exit/reopen lease release. Existing unsent-page draft and stale-navigation checks
also passed. The wide controls screenshot was inspected. Exact source references,
hashes and receipts are in [BROWSER_SIDEBAR.md](BROWSER_SIDEBAR.md).

The runtime host uses generated callbacks; actual NativeScreen destination pages,
real account/native/SSH feeds, parent recreation and physical Pixel/Mac acceptance
remain unverified by this batch. The Pixel was absent and untouched. Display
settings were restored and the emulator stopped/reaped; no new AVD was created.
**Next:** browser remote row/group mutations and full notification actions/UI,
then main-screen integration and device acceptance. Recovery, push/notice
configuration, legacy tickets and the upstream audit remain open. Signed **554**
is unchanged; these sidebar changes await a batched signed build. PR #1 stays
draft and the full goal remains active.

## Earlier checkpoint — browser filters, sorting and return state (2026-10-04)

The separate browser sidebar now shares compound machine/unread filtering,
independent workspace/notification searches and unread states, all three sort
modes, and the computer-order editor. Final state is adopted on return even after
hiding an active search. Sort writes are serialized, validated against the current
owner/computer set, and persisted through the existing local preference store;
action failures remain visible across successful feed refreshes.

**45 JVM tests and five Android tests passed** (runtime **78.018 s**, sole API37/
16KB AVD). A focused repeat of the new controls scenario passed in **45.243 s**
with a stronger rendered-order assertion and inspected screenshot. App and JVM
sources were unchanged for that repeat. These checks use the production browser
Activity/service/proxy/projection with generated two-Mac snapshots, a private HTTP
fixture and local preferences. See [BROWSER_SIDEBAR.md](BROWSER_SIDEBAR.md) for
source references, hashes and receipts. Display settings were restored and the
emulator stopped/reaped. No Pixel was connected or modified.

**Remaining:** browser remote row mutations, New Task/settings entry points,
full notification presentation/actions and selection refinements. Real main-screen
account/native/SSH integration, parent recreation and physical Pixel/Mac acceptance
still need verification. Production recovery, push/notice configuration, legacy
tickets and the wider upstream audit remain open. PR #1 stays draft; the goal is
active. Signed **554** remains the latest download and excludes these sidebar
changes, which await a batched signed build.

## Earlier checkpoint — global sidebar in the separate browser (2026-10-04)

The on-device browser now has a global workspace/notification navigation sidebar
in wide windows. It receives bounded display pages from the main process and uses
one-use, owner-validated navigation tickets. The projection shares sorting,
search and group policies; its feed lease is active only while the sidebar is
visible and foreground. Hiding/showing retains the webpage and its unsent draft.

**33 JVM and four Android checks passed** (runtime **73.398 s**, sole API37/16KB
AVD). The runtime checks used the production browser Activity/service/proxy with a
generated sidebar provider and private HTTP fixture. They verified live parent
updates, stale selection rejection, workspace/notification returns, host release,
rotation/history and unsent webpage state. Initial failures and their fixes,
exact hashes, screenshots and verification limits are in
[BROWSER_SIDEBAR.md](BROWSER_SIDEBAR.md). Display settings were restored and the
emulator stopped/reaped; no Pixel was connected or modified.

This adds global browser navigation, not every sidebar control. Remote row
mutations, compound machine filters, sort/order editing, New Task/settings entry
points, full notification presentation/actions, independent unread-filter state
and full two-tab query hand-back, and selection styling remain. Next verify the
provider against real main-screen/native/SSH feeds, parent Activity recreation
and the physical Pixel/Mac workflow. The wider goal, push/notice configuration,
legacy tickets and upstream audit remain open. PR #1 stays draft; signed **554**
remains the latest download and excludes this work.

## Earlier checkpoint — adaptive workspace sidebar (2026-10-04)

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

## Earlier checkpoint — signed build 554 and upgrade verification (2026-10-04)

[PIXEL_INSTALL.md](PIXEL_INSTALL.md) links signed development build **554**, source
`0da7bb50590b972f0d4d9dbae42851677e874fdc`, Actions run `37215611518`.
The accumulated main-feed/SSH creation, lock/layout fixes, empty-workspace and
browser restoration changes now have a verified signed artifact. CI passed
app/Ghostty JVM tests, APK assembly, packaging and Android 17 ART (Gradle 10m3s).
Independent download checks matched artifact/source/digests, signer, all 14 viewer
assets, 19 native alignments and eight excluded debug fixture activities. Native
payloads match build 546; both x86_64 CI and local arm64 ART accepted the release.

The sole API37/16KB AVD upgraded **546 → 554** without uninstall and retained its
first-install time. Android's transient package-update Activity intercepted the
initial launch; that diagnostic is retained. Explicit cold launch after it cleared
reached MainActivity in **1,658 ms**, with the sign-in screen visually checked,
`pageSizeCompat=0` and an empty crash buffer. The baseline was signed out: this
does not verify authenticated migration or the physical Pixel/Mac browser flow.
Settings were unchanged. The existing AVD was stopped/reaped; no new AVD. The
Pixel was absent from ADB and untouched. Evidence: `captures/runtime/release-0da7bb5/`.

The next scoped iOS audit is recorded in [WORKSPACE_ROWS.md](WORKSPACE_ROWS.md):
pane-name search, combined machine/unread filters and local Last Opened / Custom
Order / Recent Activity behavior, including stable-build identities and grouped
ordering. The implementation of Last Opened includes recent-use timestamps,
which supersedes an older alphabetical-only source comment. These remain next
steps, with broader parity, cold/network recovery, physical acceptance and
push/notice configuration still open. Global parity pin unchanged; PR #1 remains
a draft and the goal remains active. No GitHub release was published.

## Earlier checkpoint — empty SSH workspaces and phone-browser restoration (2026-10-04)

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

## Earlier checkpoint — primary creation and SSH kinds (2026-10-04)

A single-Mac plus tap now creates directly; holding opens the creation/group
menu. Multiple Macs still show the target chooser. New Task uses its existing
separate entry point. The plus observes feed readiness through Compose so the
verified feed becoming available updates the enabled state.

The existing SSH workspace screen now offers the iOS kind order, labels and
icons: cmux-tui, tmux, shell. Unsupported choices include a reason. A shared
creation operation validates the captured saved route/login, uses the existing
connection manager and returns the exact created terminal target. Shells are
pinned to the validated transport. Host changes retire open menus even when the
kinds are identical. This prepares the shared operation for the main chooser;
**SSH hosts/rows are not yet integrated into All Computers**.

Six focused JVM tests passed. The first 11-case Android run had three failures:
two direct-tap readiness failures and an ambiguous New Shell test selector.
After binding the button to the observed feed state, waiting for the enabled
control/request in the startup tests, and scoping the SSH assertion to its
popup, **all 11 final Android checks passed in 98.577s**. The live SSH case used
a private loopback fixture with real cmux-tui/tmux, created all three kinds,
sent and observed terminal text for each, and rejected a mismatched saved route.
Menu screenshots inspected; final crash buffer empty; source hashes matched.
Evidence: `captures/runtime/workspace-kind-menus/verification.json`.

The existing emulator and private SSH fixture were stopped/reaped; no new AVD.
The Pixel remained absent and untouched. Signed build **546** remains the
current download; no signed milestone was dispatched for this feature commit.

**Next:** integrate the verified SSH targets/creation path into the main chooser
and unified feed with login/host-scoped navigation and no creation replay on
restoration. See [WORKSPACE_ROWS.md](WORKSPACE_ROWS.md) for source findings and
integration points. Compound filters/sort, wide layout, broader upstream audit,
legacy tickets, push/feed configuration and physical acceptance remain open.
Goal active; PR draft; schedules still await merge to main.

## Earlier checkpoint — Mac creation target chooser (2026-10-04)

All Computers now offers the visible paired Macs as workspace-creation targets,
with display/build labels and connection dots. A selected-computer view offers
only its Mac. Creation uses that exact owner's verified feed connection;
Stable/Nightly, account/team, replaced-pairing and stale-menu checks prevent
retargeting. Plain legacy list-only responses refresh without guessing a created
workspace, while task/spec parsing remains strict. Changing the computer filter
or leaving the list retires delayed navigation. See [WORKSPACE_ROWS.md](WORKSPACE_ROWS.md).

**39 focused JVM tests passed.** The initial nine Android checks passed in
72.691s. Visual review found a missing status dot; the final implementation uses
the existing connection-dot component and also guards an in-flight filter change.
**All 10 final Android checks passed in 78.546s**, including background-Mac RPC
routing, same-device Stable/Nightly targets, stale account/pairing/menu rejection,
legacy response handling, delayed navigation and new-terminal/empty-workspace
startup. Final debug/test builds succeeded; screenshots inspected, source hashes
unchanged during verification and crash buffers empty. Evidence:
`captures/runtime/workspace-create-targets/verification.json`.

The sole existing AVD was stopped/reaped. The Pixel remains absent from ADB, so
physical browser/Mac acceptance is still pending and phone data was untouched.
Signed build **546 remains the current download**; no new signed milestone was
dispatched. Commit the coherent feature now and batch it into the next APK.

**Next:** integrate saved SSH targets/kind submenus and the iOS single-computer
primary tap/long-press interaction, then compound filters/sort and wider layouts.
Android currently retains its single-computer menu and New Task item; offline
creation targets remain disabled. Broader upstream audit, legacy tickets,
configured push/notice feed and physical acceptance remain open. Goal active;
PR still draft and scheduled updates remain dormant until merge to main.

## Earlier checkpoint — signed build 546 and upgrade verification (2026-10-04)

[PIXEL_INSTALL.md](PIXEL_INSTALL.md) now links signed development build **546**
from source `2a2c928b5626a23d08921827e34360ebb4e5517a`. It includes the accumulated
changes badges, swipe/context/drag menus, group moves and group actions/creation,
plus the browser recreation fix. CI passed its full test/build, packaging and
Android 17 ART gates. Downloaded archive/APK digests and source/run metadata
matched; independent verification confirmed the stable signer, 14 viewer assets,
19 native alignments and eight excluded debug fixture activities. All native
payloads match build 537. Local arm64 ART also accepted the release class.

The existing 16 KB API 37 emulator upgraded 537 → 546 without uninstall,
retaining its original first-install time. Cold start reached the inspected
sign-in screen in 1,147ms, with no compatibility/ANR dialog and an empty crash
buffer. This is a single signed-out launch, not a performance or authenticated
migration claim. Device settings stayed unchanged; the sole AVD was stopped and
reaped. Evidence: `captures/runtime/release-2a2c928/`. The Pixel was absent.

**Next:** implement the All Computers new-workspace target chooser, then iOS view
options with compound machine filters and sort modes. Exact source findings and
regular-width layout constraints are recorded at the end of
[WORKSPACE_ROWS.md](WORKSPACE_ROWS.md). Legacy tickets, broader source parity,
configured push/notice feed and live Pixel/Mac acceptance remain open. The new
update schedules are still dormant until the workflow changes reach main.
Goal active; no release published and no PR merged.

## Earlier checkpoint — group actions and creation (2026-10-04)

Group headers now use the iOS context-menu order/icons without a visible
ellipsis: Pin/Unpin Group, Rename Group, New Workspace in Group, Ungroup (Keep
Workspaces), Delete Group (Close Workspaces). Destructive actions have explicit
Mac confirmations. Pinned groups cannot be ungrouped; owner/capability changes
retire pending actions. Creation and deletion target the owning Mac even from
the multi-Mac list. See [WORKSPACE_ROWS.md](WORKSPACE_ROWS.md).

The current Android connector uses account authentication without attach
tickets. Group discovery and execution now require the host's
`workspace.mutations.account_auth.v1` plus the operation capability, matching
the scoped iOS authority policy for an account-only connection. Legacy attach-
ticket support remains a parity item. Plain group creates accept a valid legacy
list-only success without guessing a created ID; task/spec parsing stays strict.
A delayed create cannot take over navigation after the user leaves the list.

**37 JVM tests and 11 distinct Android checks passed across focused runs.** The
initial nine Android checks passed in 98.704s. A follow-up added visible-pixel
confirmation capture and two creation cases; those two fixtures initially
removed the existing group anchor and incorrectly expected the new anchor as a
separate row. Retaining the existing anchor corrected the fixture; both cases
passed in 22.837s against the same production APK. The confirmation pixel check
passed in the preceding run. This is not a single green 11-case invocation.

Menu, painted confirmation and created-terminal screenshots inspected; crash
buffers empty; sole existing AVD stopped/reaped. Evidence and source/APK hashes:
`captures/runtime/group-actions/verification.json`. No physical Pixel was visible
to ADB. Signed build **537 remains the last verified delivery**.

**Next:** build and verify one signed milestone for the accumulated workspace/
group batch, including R8/API37 ART and packaging checks; then resume physical
Pixel/Mac acceptance when the device is available. Selected/sidebar/wide UI,
broader upstream changes, legacy tickets, live account/network flows and push/
feed configuration remain open. Goal active.

## Earlier checkpoint — workspace menu and browser restoration (2026-10-04)

Workspace context menus now follow the scoped iOS source order and icons:
**Pin/Unpin, Customize, Rename, Mark as Read/Unread, Move to Group, Delete**.
Pane creation uses the pane picker or empty-workspace buttons; Changes opens
from its summary badge. Unsupported rows no longer retain an invisible menu
that blocks their next tap. The existing drag and shared delete confirmation
paths remain covered. See [WORKSPACE_ROWS.md](WORKSPACE_ROWS.md).

The updated lifecycle checks also found and fixed a production browser bug:
Activity recreation could move an already-open local tab into the Mac proxy
presentation during the feed's temporary reconnect. That same live tab now
retains its chosen presentation. New surfaces still resolve their network, and
owner validation remains required. A separate debug restoration process now
uses its own WebView directory, as required by Android.

**27 distinct Android checks passed across focused runs**, including menus,
creation, Changes/customization restoration, local browser routing, actual
process death and real system-picker multipart uploads. The initial 25-case
run had six failures: four stale navigation/wait assumptions, the production
browser restoration bug, and the debug WebView directory collision. The next
11-case run left only the browser bug; isolating it identified the wrong
Activity/network path. After its fix, all four browser lifecycle/process checks
passed in 68.813s. A final three-case lifecycle run passed in 63.3s with stronger
DOM and visible-pixel assertions: the restored page and upload response both
rendered correctly. This is not a claim of a single green 27-case invocation.

Debug/test builds succeeded; the final test-only build took 17s. Screenshots
were inspected, final crash buffers were empty, and the sole existing AVD was
stopped/reaped. Evidence and hashes:
`captures/runtime/workspace-menu-parity/verification.json`.
No physical Pixel was visible to ADB; phone data was not touched.
Signed build **537 is unchanged**.

**Next:** group-header action/presentation parity, including owning-Mac group
creation, ungroup/delete confirmations and fresh capability checks. Live
Pixel/Mac acceptance, broader upstream parity and push/feed configuration remain
open. The goal remains active.

## Earlier checkpoint — Move to Group submenu (2026-10-04)

Workspace menus now have the iOS-style **Move to Group** picker: ordered group
names/icons, checked and disabled current membership, collapsed destinations,
and a separate Remove from Group action. It uses the complete owning Mac snapshot
and existing normalized move queue. Search and multi-Mac views no longer hide
group moves just because dragging is unavailable. Owner replacement retires the
picker, and capability/connection/window/queue constraints still gate admission.
See [WORKSPACE_ROWS.md](WORKSPACE_ROWS.md).

**14 JVM tests and eight distinct Android checks passed across two runs.** The
initial Android run passed 7/8 in 70.781s; the new multi-Mac test matched both its
search editor and the identically named row. After narrowing that test selector,
the filtered background-Mac move/removal RPC flow passed in 25.311s against the
same production APK. Other checks covered held-pointer drag, virtualized reorder,
current-group state, removal, owner replacement and rejected move recovery.
Debug/test assembly passed in 1m38s; the test-only rebuild took 27s.

Screenshots inspected; crash buffers empty; sole existing AVD stopped/reaped.
Evidence and hashes: `captures/runtime/workspace-group-picker/verification.json`.
No physical Pixel was visible to ADB. Signed build **537 is unchanged**.

**Next:** exact parent workspace menu ordering/icons/shortcut placement and group
header actions/presentation. Broader upstream parity, live Pixel/Mac account and
network acceptance, and push/feed configuration remain open. Goal active.

## Earlier checkpoint — long-press workspace menus (2026-10-04)

The workspace ellipsis is removed. Holding a row opens its owning menu; continued
vertical movement dismisses it and starts the existing reorder/autoscroll path.
The menu is visible while the original Android touch is still held, and that
same OS-level pointer can continue into a drag. Read/reorder/menu accessibility
actions share the row node. Menus and simple dialogs retire when the Mac/account
owner changes, including an account change at the same origin/workspace ID.
Group headers gain coordinated context handling; read-only groups cannot leave
an invisible menu blocking their next tap. See [WORKSPACE_ROWS.md](WORKSPACE_ROWS.md).

**18 distinct Android checks pass across the final focused runs.** The full run
passed 13/15 in 149.132s; its two remaining assertions observed Save/Resume before
asynchronous UI transitions finished. After fixing those test waits, both cases
and three real process-restoration cases passed (5/5, 144.538s) against the same
production APK. The preceding six-case gesture gate passed in 47.531s. This is
not a claim of one green 18-test invocation. Final main APK assembly passed in
39s; the test-only synchronization rebuild took 20s. No new JVM count is claimed.

Original gesture failures, the intentionally stopped initial run, source/APK
hashes and screenshots are recorded in
`captures/runtime/workspace-context-menu/verification.json`. Held-menu and actual
app row screenshots inspected; final crash buffer empty; sole AVD stopped/reaped.
No physical Pixel was connected. Signed build **537 is unchanged**.

**Next:** match exact iOS context-menu contents/icons/grouping and Move to Group
submenu, then the group-header presentation audit. These are remaining parity
work, not unavoidable Android differences. Broader source parity, live account/
network/Pixel acceptance and push/feed configuration remain open. Goal active.

## Earlier checkpoint — workspace swipe actions (2026-10-04)

Workspace rows now reveal read/unread and Delete actions with horizontal swipes,
including full swipes, partial action buttons, RTL handling, Back/tap dismissal
and one open row per owning list. Delete still uses the shared Mac confirmation;
no close request is sent by the gesture alone. TalkBack exposes read/delete and
the complete action menu. Capability discovery follows the owning Mac, with fresh
coordinator checks before rename/pin/read/delete mutations. See
[WORKSPACE_ROWS.md](WORKSPACE_ROWS.md).

**30 coordinator JVM tests and nine Android checks passed** (72.636s). The Android
suite includes real row gestures alongside held drag/virtualized auto-scroll,
colliding workspace IDs under separate owner keys, capability withdrawal and the
existing framed RPC read/delete flows. It caught two defects before the final
pass: stale read-state callbacks and a double RTL direction conversion. Both are
fixed; original diagnostics and source/APK hashes are retained in
`captures/runtime/workspace-swipe/verification.json`. Final APK assembly passed
in 1m11s. Confirmation/reveal screenshots inspected, crash buffer empty, sole
AVD stopped/reaped. Physical Pixel/Mac and spoken TalkBack acceptance remain open.

Signed build **537 is unchanged**; this feature and changes badges join the next
signed batch. Long-press context menus still need coordination with reordering;
the visible action button remains. Broader parity, production network/account
checks and push/feed configuration remain open. The goal remains active.

## Earlier checkpoint — workspace changes badges (2026-10-04)

Workspace rows now show the owning Mac's additions/deletions, or a file count
for binary-only changes. The independent accessible badge opens the Changes
viewer without selecting a terminal. See [WORKSPACE_ROWS.md](WORKSPACE_ROWS.md).
A capability-gated per-connection cache batches 64 IDs, coalesces real activity,
reuses successful results for 15 seconds and stops trailing refreshes when idle.
Removed workspaces and retired connections cannot publish stale badges;
authorization failures clear them and retire the owning monitor.

**37 JVM tests and six Android checks passed** (34.506s). Four affected Android
checks passed again after the final caption adjustment (32.112s). Final debug
and test APK assembly succeeded in 47s. The real Activity fixture verified the
summary RPC, owning-workspace Changes navigation and absence of terminal replay.
Screenshots were inspected, both crash buffers were empty, and the sole AVD was
stopped. Initial timing-test and authorization-retirement failures are retained
with their fixes in `captures/runtime/workspace-changes-chip/verification.json`.

No physical Pixel is visible to ADB, so live Mac/Pixel acceptance remains pending.
Signed build **537 is unchanged**; these badges are for the next signed batch.
Context/swipe/drag actions, broader source parity, production account/network
checks and push/feed configuration remain open. The goal remains active.

## Earlier checkpoint — signed build 537 and upgrade gate (2026-10-04)

[PIXEL_INSTALL.md](PIXEL_INSTALL.md) now points to signed development build **537**,
source `3921de252ce312693c653c4362c21b088e8d307e`. CI passed app/Ghostty JVM suites,
APK assembly, packaging and Android 17 / 16 KB ART; Gradle took 9m40s. Independent
local checks matched the CI APK hash, stable signer, 14 viewer assets, 19 aligned
native libraries and eight excluded debug activities. Local arm64 ART accepted
1,339 NativeScreenKt methods. No CI JVM count is inferred from logs.

The existing emulator upgraded 517 → 537 without uninstall, preserving its
first-install timestamp, and cold-launched to sign-in in 2,777ms without a
compatibility warning or ANR dialog. License text rendered and Done dismissed it;
its 650ms 99th-percentile frame bucket still reflects emulator jank, not a physical
performance claim. Final crash buffer empty; sole AVD stopped/reaped; settings
unchanged. This baseline was signed out, so authenticated migration is unverified.
No physical Pixel was connected. Evidence is in `captures/runtime/release-3921de2/`.

The accumulated source fixes through workspace draft owner isolation are now in
the signed APK. The changes-summary row chip and gestures, live Pixel/Mac workflow,
production account/network graph, broader source/UI parity and push/feed provider
configuration remain open. PR #1 is still a draft; the goal remains active.
Earlier sections naming build 517 describe historical checkpoints.

## Earlier checkpoint — workspace draft process restoration (2026-10-04)

The separate-process Android test exposed an owner-isolation bug: a saved workspace
editor could reopen its unsent draft under a replacement login. Targets now retain
login/account/team/Mac/workspace identity and validate it before rendering or Save.
Owner-keyed editor state prevents a newly opened form from consuming the previous
owner's saved draft. Legacy unscoped targets are discarded. See
[WORKSPACE_CUSTOMIZATION.md](WORKSPACE_CUSTOMIZATION.md).

**16 JVM tests and four Android cases pass** (125.861s): three whole-process
restoration/owner/conflict checks plus the existing offline Activity/reconnect
flow. The pre-fix APK reproduced the changed-login defect; an earlier emulator
Launcher ANR was diagnosed separately. The final crash buffer was empty, the
restored offline form was inspected, and the sole AVD was stopped/reaped.
No Pixel was connected; signed build 517 is unchanged.

**Next:** current-source signed/cold-start gate, changes-summary row chip and
context/swipe/drag parity. Physical Pixel/Mac workflow, production account/team
and Iroh graph, broader source parity, and push/feed provider configuration remain
open. The goal is active.

## Earlier checkpoint — workspace row layout and status (2026-10-04)

[WORKSPACE_ROWS.md](WORKSPACE_ROWS.md) aligns the row structure with the scoped
iOS source: unread gutter/color rail, visible pin, title-line activity/status,
larger text with explicit line heights and tighter spacing. The large Mac avatar
and Mac-name caption are removed from the row body. Connected rows use today's
local time or an older month/day date, including the legacy `preview_at` fallback;
connection problems show Reconnecting/Disconnected. No relative-time timer is added.

**17 JVM tests and four Android cases pass** (56.636s); the two affected layout
cases pass again after the final line-height adjustment (17.153s). Screenshots
inspected, crash buffers empty, sole AVD stopped/reaped. Initial test-accessor and
pixel-rounding failures are retained in the evidence receipt. No Pixel was
connected; signed build 517 is unchanged.

**Next:** whole-process workspace editor restoration (the existing
`WorkspaceProcessRestorationTest` / `NativeProcessRestoreTestActivity` provides an
isolated-process harness), current-source signed/cold-start gate, changes-summary
row chip and gesture/action parity. Physical Pixel/Mac workflow, broader source
parity, production account/network graph and push/feed provider configuration
remain open. Overall goal remains active.

## Latest checkpoint — offline workspace editor and Activity restoration (2026-10-04)

[WORKSPACE_CUSTOMIZATION.md](WORKSPACE_CUSTOMIZATION.md#offline-discovery-and-save-admission--2026-10-04)
records the iOS capability-lifetime audit. Android now keeps Customize available
from the owning Mac's retained capability snapshot during an outage. Saves still
require its current verified connection; reconnection never replays drafts.

**13 JVM tests + one expanded Android flow pass** (41.571s). The real Activity
opens the editor offline, preserves all fields across recreation, retains the
draft after an offline save rejection, and applies the exact actions only after
reconnection and explicit Save. Pane-menu clearing still works. This uses a local
RPC fixture and retained runtime, not whole-process or live account acceptance.
Screenshots inspected; crash buffer empty; sole AVD stopped/reaped. No Pixel was
available, and signed build 517 is unchanged.

**Next:** broader workspace row/UI parity, whole-process/account transition
restoration, physical Pixel/Mac metadata/browser/recovery, production connection
graph acceptance and the current-source signed gate. Push/feed provider setup
remains open. Overall goal remains active.

## Latest checkpoint — browser workspace customization (2026-10-04)

[WORKSPACE_CUSTOMIZATION.md](WORKSPACE_CUSTOMIZATION.md#local-browser-process-integration--2026-10-04)
adds the shared editor inside the routed browser Activity, with saves forwarded
through its bound service to the owning-Mac coordinator. Page state and the
browser host lease survive save/retry. The current destination/pairing/capabilities
are rechecked; duplicate saves are rejected and editor/session retirement cancels
in-flight work. The direct browser view also receives the customization action.

**14 JVM + eight Android checks pass** (seven cases 94.815s, targeted IPC
cancellation 21.463s). Real browser Activity/service/proxy with local HTTP and a
fixture save callback verifies the process boundary; separate coordinator JVM
checks cover Mac RPC behavior. Screenshots inspected, crash buffers empty, sole
AVD stopped/reaped. No Pixel was available. Signed build 517 is unchanged.

**Next:** owning-Mac capability lifetime/offline entry audit, full Activity/process
restoration, physical Pixel/Mac metadata/browser/recovery and production connection
graph acceptance; broader source/UI parity and the current-source signed gate.
Provider-dependent push/feed configuration remains open. The overall goal is active.

## Latest checkpoint — workspace customization (2026-10-04)

[WORKSPACE_CUSTOMIZATION.md](WORKSPACE_CUSTOMIZATION.md) adds the iOS name,
pinned, description and optional color editor to workspace rows and the native
pane picker. Saves check owning-Mac capabilities, preserve untouched Mac edits,
rebase conflicts and retain unsent edits after partial failure. Truncated
Mac descriptions are read-only; descriptions use the 4,096-byte wire limit.

**11 JVM + four Android cases pass** (Android 70.401s), including production
coordinator/local RPC save and clear, partial failure/retry, duplicate-save
prevention and editor saved-state restoration. Final screenshots inspected,
crash buffer empty, sole AVD stopped/reaped. Earlier failed test attempts and
the stale-test-APK diagnosis are retained in the local evidence receipt.
Signed build 517 is unchanged; no physical Pixel appeared in ADB.

**Next:** routed-browser customization entry, offline entry-point audit and full
Activity/process restoration; physical Pixel/Mac browser/recovery and metadata
checks; broader source/UI parity and production connection-graph acceptance.
Current-source signed/cold-start verification and push/feed provider
configuration remain open. The overall goal remains active.

## Latest checkpoint — workspace-action failure policy (2026-10-04)

[DISPLAY_SETTINGS.md](DISPLAY_SETTINGS.md#workspace-action-failure-policy--2026-10-04)
completes the scoped toast call-site audit: iOS ordinary workspace/group action
failures are diagnostic-only. The legacy six-second banner component has no
producer in the shell action path. Android now records those failures without
replacing the global error; move rollback, cancellation and specialized
creation/input recovery remain intact.

**Eight JVM + one Android case pass** (runtime 30.351s), using a real Activity and
local RPC peer. Pin/close/group-pin/move rejection and subsequent terminal use
are verified; terminal rejection/resume still works. Screenshots inspected,
crash buffer empty, sole AVD stopped/reaped. The initial runtime attempt used an
incorrect terminal error-text assertion; its failure is retained and the test
was corrected without changing production code. Signed build 517 is unchanged.

**Next:** upstream workspace customization controls and remaining workspace UI;
physical Pixel/Mac browser/recovery and full production connection-graph
acceptance; broader upstream source/UI audit. No Pixel appeared in ADB.
Current-source signed/cold-start verification and push/feed provider
configuration remain open. The overall goal remains active.

## Latest checkpoint — independent Mac connection admission (2026-10-04)

[COMPUTER_CONNECTION.md](COMPUTER_CONNECTION.md#independent-mac-connection-admission--2026-10-04)
fixes a reproduced shared-pool bottleneck: one stalled Mac no longer blocks other
Macs during dial or host validation. Same-key callers still share a validated
wire; revocation/close handles all pending candidates, and pending keys count
toward the existing capacity limit.

**50 focused JVM tests pass** (13 pool, 21 Iroh runtime, 16 saved-Tailscale runtime),
including healthy-sibling RPC after the stalled Mac is revoked. Production
runtime classes use fixture transports; this does not establish live account,
Iroh/Tailscale or full production-graph acceptance. No emulator was started and
no APK installed. ADB showed no device; the physical browser check remains
pending. Signed build 517 is unchanged.

**Next:** physical Pixel/Mac browser/recovery and production connection-graph
acceptance; workspace-action failure presentation; broader upstream source/UI
audit. Current-source signed/cold-start verification and push/feed provider
configuration remain open. The overall goal remains active.

## Latest checkpoint — native selection haptic policy (2026-10-04)

[DISPLAY_SETTINGS.md](DISPLAY_SETTINGS.md#native-selection-haptics-and-toast-policy-audit--2026-10-04)
fixes the Haptic Feedback switch bypass in native TextViews/WebViews: Terminal
Text, file text, direct terminal input, browser and Markdown now share the live
preference. Detached views unsubscribe and reattach with current state.
**Two Android cases pass in 28.079s**, including real selection/copy with the
switch off. Screenshot inspected, crash buffer empty, sole AVD stopped/reaped.

The scoped iOS generic toast presenter is explicitly disabled in shipped builds;
an enabled generic overlay is not a missing shipped feature. The separate
workspace-action failure toast still needs an Android/source audit. Existing
feedback receipts/debug copy messages are not covered by that conclusion.

**Next:** production shared-connection-graph acceptance and workspace-action
failure presentation; physical Pixel/Mac browser/recovery and tactile/TalkBack
checks; broader upstream source/UI audit. No Pixel appeared in ADB. Signed 517
is unchanged; current-source signed/cold-start verification and push/feed
provider configuration remain open. The overall goal remains active.

## Latest checkpoint — terminal arrow pad (2026-10-04)

[DISPLAY_SETTINGS.md](DISPLAY_SETTINGS.md#terminal-arrow-pad--2026-10-04) adds
the missing iOS draggable arrow pad to native and SSH toolbars: immediate and
80ms repeats, shared modifier/input handling, live light haptics, accessible
single steps, and cancellation on disable/session change/background/disposal.
Real Android dispatch exposed Back intercepting the edge gesture; only the
48dp pad area is now excluded, and Back outside it is verified.

**Three JVM + four Android cases pass** (38.058s), including native RPC and SSH
encoding. Final debug/test build passes; pre-drag screenshot inspected, final
crash buffer empty, sole AVD stopped/reaped. A separate debug instrumentation
startup ANR in ART dex loading is documented, not counted as a pass. Signed
build 517 is unchanged; no physical Pixel appeared in ADB.

**Next:** generic toast/native-selection haptic parity; production shared
connection graph; Pixel/Mac browser/recovery and tactile/TalkBack acceptance;
broader upstream source/UI audit. Push/feed provider configuration and the
current-source signed-release/cold-start gate remain open. Goal remains active.

## Latest checkpoint — Legal, Support and About (2026-10-04)

[DISPLAY_SETTINGS.md](DISPLAY_SETTINGS.md#legal-support-and-about--2026-10-04)
adds the scoped iOS policy/support destinations, actual installed version and
build, and a current-session Copy Support Information report. Support opens an
email draft; reports exclude credentials, terminal text and host addresses.
Unavailable installation/vendor identifiers remain explicitly unavailable.
Debug source revisions distinguish development builds.

**Two JVM + two Android cases pass** (final runtime 17.223s). Final debug/test
build passes; the double-font-scale component screenshot was inspected, crash
buffers are empty, and the sole AVD is stopped/reaped. No email was sent and no
physical Pixel appeared in ADB. Signed build 517 is unchanged. This verifies
the components, not full Settings/iOS visual or physical account acceptance.

**Next:** remaining arrow-nub/toast/native-selection haptics; production shared
connection graph; Pixel/Mac browser/recovery and tactile acceptance; broader
upstream source/UI audit. Push/feed provider configuration and the final current-
source signed-release gate remain open. The overall goal remains active.

## Latest checkpoint — parser-backed terminal bells (2026-10-04)

[DISPLAY_SETTINGS.md](DISPLAY_SETTINGS.md#terminal-bell-feedback--2026-10-04)
adds Ghostty bell events to native byte streams and SSH terminals. Replay is
silent; duplicate/surface/output-lane checks remain authoritative. Only the shown,
foreground terminal can request the warning haptic, using the live global setting.
OSC-terminating BEL bytes do not ring. Bell bursts are coalesced per byte batch.
The pinned grid protocol carries no bell field; transport selection is unchanged.

Native-only CI **37186428500** passed and its verified checkpoint is installed
locally. **20 JVM + 15 Android cases pass** (11 native in 0.675s, four app in
30.859s), including production NativeScreen/Activity/RPC behavior and cmux-tui
output. All 19 APK ELF libraries and 16 KB zip alignment pass. A stale-test install
attempt is explicitly excluded from evidence. Crash buffer empty; AVD stopped.
Signed build 517 is unchanged. Physical Pixel/Mac/tactile and live plain-SSH/tmux
bell acceptance remain pending; no Pixel is visible to ADB.

**Next:** remaining arrow-nub/toast/native-selection haptics and Settings
legal/support; production shared connection graph and Pixel/Mac browser/recovery;
broader upstream source/UI audit. Push/feed configuration and the final current-
source signed-release gate remain open. The overall goal remains active.

## Latest checkpoint — Haptic Feedback preference (2026-10-04)

[DISPLAY_SETTINGS.md](DISPLAY_SETTINGS.md#haptic-feedback-preference--2026-10-04)
adds the default-on iOS **Haptic Feedback** switch, persisted and read at emission
time. App checklist, copy, fallback-focus failure, feedback submission and Compose
haptics share its policy. Feedback results are consumed once across recreation,
never replayed from saved errors/receipts, and uniquely identified for rapid retries.
Android system haptic settings remain in force.

Final debug/test build passes; **10 JVM + 10 Android cases pass** (runtime 126.526s).
The component screenshot was inspected and crash buffers are empty. The sole AVD
is stopped/reaped. No physical Pixel was visible in ADB; signed build 517 is
unchanged and the current source still needs its final signed-release gate.

**Next:** terminal-bell/arrow-nub/toast/native-selection haptic parity and physical
feel; Settings legal/support; production shared connection graph and Pixel/Mac
browser/recovery acceptance; broader source/UI audit. Push/feed provider config
remains open. The scoped iOS audit is partial, and the overall goal remains active.

## Latest checkpoint — alternate-screen terminal controls (2026-10-04)

[DISPLAY_SETTINGS.md](DISPLAY_SETTINGS.md#alternate-screen-notice-and-full-height-preference--2026-10-04)
adds the iOS **Full-Screen Sizing Notice** and **Use Full Terminal Height** controls.
The selected alternate-screen terminal gets an accessible warning popup with
persistent suppression and Settings restoration. Full-height mode opts into the
existing keyboard-independent viewport path; default and shared sizing retain
their previous policies.

Debug/test build and nine geometry JVM cases pass. Three Android cases pass
(75.239s); the real Activity/IME/RPC case was then strengthened with a bottom-row
footer and pixel assertions and passed again (56.646s). Screenshots confirm the
footer remains above the keyboard, and double-font-scale popup dismissal,
connection/surface retirement, recreation and preference persistence are covered.
No physical Pixel was visible in ADB. The single AVD is stopped. Signed build 517
is unchanged; this source has not had the final signed-release gate.

**Next:** remaining Settings haptics and legal/support parity; production shared
connection graph and physical Pixel/Mac browser/recovery acceptance; broader
upstream/source/UI audit. Push/feed configuration remains open. The goal remains
active. These fixtures do not establish real account/Iroh or push acceptance.

## Latest checkpoint — real-dispatch terminal acceptance (2026-10-04)

[DISPLAY_SETTINGS.md](DISPLAY_SETTINGS.md#attach-investigation--2026-10-04)
corrects the prior test's “normal Android dispatch” claim. The Compose rule used
an unconfined test dispatcher, and temporary logs showed a UI effect resuming on
an IO thread. The blank terminal had an explicit local disconnected/admission
error, not a stalled subscription. No speculative production change was retained.

The replacement test uses the existing real Activity, Android main dispatcher,
frame clock and UI Automator. **Two Android checks pass in 43.388s**, covering
Settings → 20,000-row replay/render, recreation/reconnect, workspace reopen,
command delivery, and component wrap/preview/remount behavior. Retained failures
and test setup corrections are documented. Debug app hash is identical to
`e012649`; only tests/docs changed. The sole AVD is stopped. Signed 517 is unchanged.

**Next:** physical Pixel/Mac browser and native connection recovery when ADB can
see the phone; complete the production shared-connection-graph acceptance and
remaining Settings/source parity. This fixture does not prove account/Iroh/push
acceptance or rule out a rare production ownership race. No physical Pixel was
visible. Push/feed configuration remains open. The goal remains active.

## Latest checkpoint — display and scrollback controls (2026-10-04)

[DISPLAY_SETTINGS.md](DISPLAY_SETTINGS.md) adds the iOS title-wrap and one/two-line
preview preferences, workspace descriptions/activity fallback, and 1,000/4,000/
10,000/20,000-row native terminal hydration (default 4,000). RPC and render-grid
limits now permit the largest option. Settings are persisted and observed live.

26 JVM cases pass. Three selected Android cases have passes across retained runs:
rendered row/remount behavior, workspace actions, and the final actual Settings →
terminal RPC/render flow (21.98s). Screenshots were inspected. Debug/test builds
and engine packaging pass. The sole AVD is stopped. Signed build 517 is unchanged.

**Next:** investigate the intermittent cold terminal attach exposed by this check.
Two runs reached a blank terminal with event subscriptions but no viewport/replay;
the final pass alone does not establish reliability. The test now captures RPC
and peer diagnostics on failure. An earlier Espresso/StandardTestDispatcher stall
and unrelated Digital Wellbeing ANR are documented separately. Pixel is absent
from ADB; physical browser/connection checks, feed/push and broader parity remain
open. The goal remains active.

## Latest checkpoint — responsive license dialog (2026-10-04)

[LICENSE_DIALOG.md](LICENSE_DIALOG.md) replaces synchronous asset loading and one
huge Text with off-main loading and bounded, lazily rendered sections. All 18
license assets remain complete; loading can be dismissed and failures retried.
One JVM and three Android tests pass, including traversal of the full packaged
license list. Actual MainActivity open/close/reopen and screenshots were checked.

The prior 3,019 ms frame did not recur in these two debug emulator openings;
first-open HWUI reported 761 ms and reopen's 99th-percentile bucket was 500 ms.
These are not controlled release benchmarks and remaining jank is not ruled out.
Debug/test assembly and engine packaging pass. The sole AVD is stopped; ADB still
shows no Pixel. No signed milestone was created; build 517 does not include this fix.

**Next:** physical Pixel/Mac browser and connection recovery; notice cold-start,
HTTPS/account acceptance and broader source parity. Push/feed configuration remain
open. The goal remains active; this checkpoint does not establish full parity.

## Latest checkpoint — signed development build 517 (2026-10-04)

[PIXEL_INSTALL.md](PIXEL_INSTALL.md) records source `7b01538`, successful CI run
37179259486 and signed artifact 11294177624. Independent package verification passes:
stable signer, all 19 native/16 KB checks, all 14 viewer hashes and eight excluded
debug activities. The verifier's obsolete fixture-count check was fixed and tested.
The existing arm64 Android 17 / 16 KB emulator upgraded 494 → 517 without resetting
app data, cold-launched to sign-in in 1,605 ms and had no crash or compatibility warning.
The baseline was signed out; authenticated migration is not claimed. The AVD is stopped.

Local app JVM reports show 1,544 passes/four opt-in skips; seven Ghostty results
were reused up to date. All 22 Python and 11 Node checks pass. Local unsigned
release and CI signed ART both accept 1,320 NativeScreenKt methods. The artifact
is 227,902,462 bytes (119,796,645-byte ZIP), including the bundled browser engine.

**Next:** fix the observed 3,019 ms opening stall in `OpenSourceLicensesDialog`
(synchronous concatenation and one huge Text), then continue physical Pixel/Mac,
notice cold-start/HTTPS/account acceptance and the full connection-graph audit.
Pixel is absent from both ADB and matching Mac USB inventory; the existing reconnect
question remains pending. Push/feed configuration and broader source parity remain
open. Build 517 is the current signed development APK; this does not complete the goal.

## Latest checkpoint — notice engine process death (2026-10-04)

[NOTICE_PROCESS_DEATH.md](NOTICE_PROCESS_DEATH.md) adds a real SIGKILL test of the
Android process owning the production notice engine, ledger and presentation UI.
The final case passes in 48.644 s across PIDs 3302 → 3597 → 3903: pending content
stays unseen, appearance persists acknowledgement, the archive renders after a
cold process restart, and the exact killed context has no cookies/localStorage
while the new page remains open. Screenshots were inspected.

Earlier startup/archive failures and the stronger probe ordering are retained.
The final run used compiled debug bytecode; reliable cold-start timing on Pixel
remains open. Final debug/test build and packaging pass. Only debug/test/docs
changed, the existing emulator is stopped, and no signed milestone was created.

**Next:** full production connection-graph/physical Pixel recovery, real HTTPS/native
account notice acceptance, cold-start performance, remaining storage/API parity
and broader source audit. ADB still shows no Pixel; do not repeat the pending
reconnect question. Android feed/push configuration remain open. Build 494 is the
last signed APK; the last unsigned-release/ART gate is `786264d`.

## Latest checkpoint — terminal attachment paste (2026-10-04)

[TERMINAL_PASTE_PRECEDENCE.md](TERMINAL_PASTE_PRECEDENCE.md) aligns direct and toolbar
attachment paste with iOS: captions/fallback text no longer accompany the attachment
as remote input. Android keeps ordered attachment batches. SSH Paste, Files, Zoom
and Compose clear armed modifiers; native Zoom now does too.

Nine distinct Android cases pass across two runs: eight initially, then the corrected
Files-button lookup case. Image upload ordering, no unintended caption/Enter,
composer staging and unchanged plain-text classification are covered. Debug/test
assembly and engine packaging pass. The existing emulator is stopped; no signed
milestone was created. ADB shows no physical Pixel, so browser/Mac acceptance remains
pending and the existing reconnect question should not be repeated.

**Next:** physical Pixel/Mac browser and connection recovery, remaining notice
process-death/HTTPS acceptance and broader source parity. Android feed and push
configuration remain open. Build 494 remains the last signed APK; the last
unsigned-release/ART gate is `786264d`.

## Latest checkpoint — Computers process-death recovery (2026-10-04)

[CACHED_COMPUTERS.md](CACHED_COMPUTERS.md#process-death-acceptance--2026-10-04)
adds actual SIGKILL/cold-launch evidence for saved account-scoped Computers rows.
Two Android cases pass in 38.747 s across four verified process transitions.
Saved names, hidden rows and update warnings survive with no live authority;
503 retains the read-only display, fresh membership enables selection, changed
membership replaces the displayed team, and 401 removes cached display across
another restart while preserving saved pairings.

Initial failures were UI lookup timing/scrolling; production cache/authorization
code is unchanged. The debug-only isolated harness uses real production components
and loopback HTTP. Debug/test builds and engine packaging pass; screenshots were
inspected. The existing emulator is stopped. No signed milestone was created.

**Next:** full production connection-graph/physical Pixel cold recovery, remaining
notice process-death and HTTPS/native-account acceptance, clipboard grant behavior
and the broader source audit. Android feed and push configuration remain open.
Build 494 remains the last signed APK; last unsigned-release/ART gate `786264d`.

## Latest checkpoint — real keyboard URI grants (2026-10-04)

[KEYBOARD_URI_GRANTS.md](KEYBOARD_URI_GRANTS.md) verifies the existing image-input
permission lifetime using a real platform keyboard and private provider in a
separate test APK/UID. Three Android cases pass in one final run (21.865 s): delayed
copy after editor removal, rejected/throwing receivers, and input-queue cancellation.
Actual provider reads succeed while owned and are denied before/after the grant.

The fixture is absent from app DEX/manifest, and the production APK is unchanged.
The original emulator keyboard was restored, the fixture keyboard disabled, and
the existing AVD stopped. Earlier fixture startup, readiness-probe and System UI
ANR failures are retained in the evidence. No new AVD, app or signed build here.

**Next:** physical Pixel/Mac browser and Gboard acceptance, clipboard grant behavior,
whole-process recreation, HTTPS/native-account acceptance and the broader source
parity audit. Android feed and push configuration remain open. Build 494 remains
the last signed milestone; the last unsigned-release/ART gate is `786264d`.

## Latest checkpoint — partial attachment providers (2026-10-04)

[COMPOSER_PARTIAL_PROVIDERS.md](COMPOSER_PARTIAL_PROVIDERS.md) brings ordered
partial attachment preparation to native terminal, SSH and New Task composers.
An unreadable provider no longer drops later readable files. Ownership changes,
cancellation and draft-write failures still stop the batch. New Task keeps a
partial-failure notice after successful items are added; direct terminal delivery
keeps its existing stop-on-error behavior.

Five JVM tests and 15 distinct Android cases pass across targeted runs. Two initial
test preconditions incorrectly expected a MIME lookup exception; Android returned
null. Both corrected task paste/upload flows pass, including a final 35.903 s run
checking the retained notice. Final debug/test assembly and engine packaging pass.
The single existing emulator is stopped; no new AVD or signed build was created.

**Next:** physical Pixel/Mac browser and provider acceptance, external grant
lifetimes, drag/drop, whole-process recreation, HTTPS/native-account acceptance
and the broader source audit. ADB still shows no physical Pixel; the reconnect
question remains pending. Android feed and push configuration remain open. Build
494 remains the last signed APK; the last unsigned-release/ART gate is `786264d`.

## Latest checkpoint — composer system Paste (2026-10-04)

[COMPOSER_SYSTEM_PASTE.md](COMPOSER_SYSTEM_PASTE.md) closes the shared edit-menu and
hardware attachment-paste gap in native terminal, SSH and New Task composers.
Images/files enter existing attachment staging; URI/caption text is not inserted.
Ordinary text preserves native selection and composition. Disabled, oversized and
retired-owner actions cannot send attachments to another draft.

Nine selected Android cases pass across two runs, including actual floating-menu
clicks and a complete pasted-image task upload to the loopback RPC fixture. The
initial seven-pass/two-failure run exposed a test window-lookup issue; the corrected
menu cases passed in 31.291 s. Debug/test builds pass, screenshots were inspected,
and the existing emulator is stopped. No signed milestone or release build here.

**Next:** physical keyboard/provider acceptance, remaining multi-item provider-failure
and drag/drop behavior, whole-process recreation, HTTPS/native-account acceptance
and the broader source audit. Android feed and push configuration remain open.
Build 494 is still the last signed APK; the last unsigned-release/ART gate is
`786264d`. Do not repeat the pending Pixel reconnect question.

## Latest checkpoint — content-process crash acceptance (2026-10-04)

[NOTICE_CONTENT_CRASH.md](NOTICE_CONTENT_CRASH.md) verifies an actual native content
crash through the production renderer and archive UI. The same Android app process
survives; the old private context is cleared; Retry performs a new exchange and
renders fresh content. The archive's error wording now covers rendering failures.
Two final Android cases pass (52.278 s), plus the earlier extension-recovery case in
the initial run. On the API 37 / 16 KB emulator the actual callback is `onKill`;
`onCrash` itself is not runtime verified. Debug/test build and packaging checks pass.
The existing AVD is stopped. No release or signed milestone was built this turn.

**Next:** whole-process recreation, real HTTPS/native-account exchange, physical
Pixel/Mac acceptance and broader parity. Android feed and push configuration remain
open. Build 494 remains the last signed milestone; the last unsigned-release/ART
check is `786264d`. Do not repeat the pending Pixel reconnect question.

## Latest checkpoint — partition cleanup and callback safety (2026-10-04)

[NOTICE_PARTITIONS.md](NOTICE_PARTITIONS.md) records actual browser partition
cleanup under two top-level sites. Both normal retirement and extension recovery
clear the retired context's cookies, localStorage, IndexedDB and Cache Storage
while preserving a separate context. A real disconnect crash exposed by the test
was fixed by revoking page eligibility immediately and deferring session teardown
until Gecko finishes its port callback.

Final checks: **52 JVM tests and five Android cases passed** (90.184 s). Final-source
debug/test and unsigned release builds, engine packaging and the Android 17 / 16 KB
release ART gate pass. The fixture is absent from both app APKs. The existing AVD
is stopped; no new virtual device or signed milestone was created.

**Next:** whole-runtime crash behavior, real HTTPS/native-account exchange and
physical Pixel/Mac acceptance. Android feed, push configuration and broader parity
remain open. Build 494 remains the last signed milestone. ADB currently shows no
Pixel; preserve app data and do not repeat the pending reconnect question.

## Latest checkpoint — extension recovery (2026-10-04)

[NOTICE_RECOVERY.md](NOTICE_RECOVERY.md) records recovery without an app restart.
Native acquisition receipts survive a bundled-extension restart in memory. Old
pages close; a new preparation clears their exact private contexts before creating
a fresh session. Clearing the old native delegate fixed a real lost-reconnection
failure. Final checks: 52 JVM, 11 Node and all five Android renderer/Compose cases
passed (76.339 s), including old-context cleanup and rejection of a late exchange.
The existing AVD is stopped. Debug packaging passes; the earlier unsigned release
predates the last delegate fix, so final-source release/ART remains a batch gate.

**Next:** actual browser partitioned-state cleanup, whole-runtime crash behavior,
real HTTPS/native-account exchange and physical Pixel/Mac acceptance. Android feed,
push configuration and broader parity are still pending. Build 494 remains the
last signed milestone. Check ADB when needed; do not repeat the pending reconnect
question merely because the Pixel is absent.

## Latest checkpoint — launch announcement integration (2026-10-04)

[NOTICE_LAUNCH.md](NOTICE_LAUNCH.md) records concurrent launch preloading, retained
renderers, current-account/content/visibility checks and acknowledgement only on
actual sheet appearance. The launch sheet uses the loaded session directly;
failed pages remain unseen without a retry loop. Archive Retry keeps its separate
fresh exchange. The production debug and unsigned release build, 52 focused JVM
checks, engine package checks and release ART gate pass. Consult the linked
checkpoint for precise Compose runtime results and retained fixture failures.

**Follow-up:** extension recovery is recorded above. Browser partitioned-state runtime,
real HTTPS/native-account exchange and physical Pixel/Mac acceptance. No Android
notice feed or new signed milestone was configured. Build 494 is still the last
verified signed APK. Push configuration and broader parity remain open. Do not
repeat the pending Pixel reconnect question; check ADB when needed.

## Latest checkpoint — production private notice renderer (2026-10-04)

[NOTICE_RENDERER.md](NOTICE_RENDERER.md) records the main GeckoView integration,
private cookie leases and retained archive detail renderer. It is wired to the
native account broker. Rotation/theme reuse the page; Retry creates a new exchange.
The actual production renderer passed two Android fixtures (26.436 s), including
cookie/script isolation, owner cancellation and scoped cleanup; six existing
UI/restoration cases also passed (52.128 s). Final focused JVM tests: 45 passed;
helper/extension tests: 9 passed. Release ART, 19-library alignment, package pin /
licenses and signed-out cold launch pass. The existing AVD is stopped.

A real integration bug was fixed: pinned Gecko 157 cookie expiry is milliseconds,
not seconds. Keep native validation and readback guards. The main APK now includes
Gecko and grows to about 241 MB debug / 228 MB unsigned release. There is still no
Android notice feed and no new signed milestone (494 remains current).

**Follow-up:** launch preloads and archive Compose testing are recorded above. Continue actual
partitioned-storage, extension-loss recovery and HTTPS/account acceptance. Current
extension-loss behavior closes all owned pages and requires an app process restart;
do not describe that recovery as complete. Physical Pixel/Mac browser acceptance,
push configuration and the full parity audit remain open. The Pixel was absent
from ADB; do not repeat the already pending reconnect question.

## Latest checkpoint — main toolchain and release runtime (2026-10-04)

[ANDROID_TOOLCHAIN.md](ANDROID_TOOLCHAIN.md) records the completed main migration
to AGP 9.1.1, Gradle 9.3.1, Kotlin/Compose 2.4.20 and compile SDK 37. Min 26 /
target 36 remain unchanged. A small SDK 36 library retains the API 26–27 fingerprint
fallback. Nine route lambdas were separated after the first assembled release
failed ART verification; the revised unsigned release passes the existing gate.
Final checks: 1,539 JVM passes / 4 explicit fixture skips, six Android runtime
cases passed, native/ZIP alignment and pinned assets passed, signed-out cold
launch passed. Archive rendering was visually checked after excluding a captured
window transition. The existing AVD is stopped with settings restored.

The later checkpoint above adds the production renderer and main engine dependency.
The toolchain migration remains its prerequisite; launch preload and broader
lifecycle/partitioned acceptance are still open.
Do not repeat the isolated engine experiments without a concrete failing case.
Physical Pixel/Mac browser acceptance, authenticated signed upgrade and push
configuration remain open. Build 494 remains the signed milestone; no new signed
CI build was dispatched. Scoped research does not advance the global parity pin.

## Immediate continuation — What's New

Build 494 / run **37154456102** completed successfully; no build is in progress.
Its stable artifact is **11285640563**. Independent source/run/hash/signature,
viewer/native alignment, manifest and NOTICE checks passed. Actual signed launch
reached sign-in, setup guide opened and Back returned; no crash or compatibility
warning. First-install time survived. The existing AVD holds stable494 and was
stopped/reaped with settings unchanged. Evidence: `captures/runtime/build494/`.
The Pixel was absent; no authenticated or physical upgrade claim is made.

Candidate 486 remains rejected for a release ART VerifyError, fixed by `555e4b8`.
Builds 488 and 491 failed before ART due to CI paths and data capacity. Fixes
`063c5a8` and `6e4a449` were verified by 494; do not continue polling old runs or
recommend their artifacts. Docs-only updates need no signed rebuild.

Next source implementation: [WHATS_NEW.md](WHATS_NEW.md). The model/catalog,
atomic file store, build metadata, retained UI owner, Settings archive/detail and
native launch sheet are implemented. 29 JVM tests and 7 successful Android UI
executions passed across portrait, landscape and 150% text. The first two UI
timeouts came from a System UI ANR; after observed recovery the unchanged tests
passed. Final source removes a live account-refresh gate so notices work offline;
debug/test APKs rebuilt successfully. No Pixel or signed upgrade was verified.
The existing emulator is stopped, original settings restored and all processes
reaped. Evidence and APK hashes: `captures/runtime/whats-new-ui/`.

Continue the **incomplete web announcement path** using [WHATS_NEW_WEB.md](WHATS_NEW_WEB.md).
The origin-confined native-to-web session broker and renderer-neutral load lifetime
are implemented and passed 16 JVM tests (10 exchange, 6 deadline/lifecycle). They
are not connected to a renderer and made no real account exchange. Synthetic
loopback tests establish no physical account/cookie acceptance.

The profile experiment is now recorded in `WHATS_NEW_WEB.md`: cookie isolation and
targeted clearing passed, but deletion after destroy threw, and profile names and
cookies were missing after restart despite directories remaining on disk. The
initial invalid persistence assertion failed; the revised probe records observations
and confirms only public-registry cleanup, not disk erasure. The standalone
`notice-spike` now evaluates GeckoView157 with its own newer toolchain. Public
cookie seeding fails for named private contexts; the scoped bundled extension
passes actual isolated-request checks; the extended script-exclusion check passed
in 7.363 s. Process-death storage absence passed on the exact same origin/context.
Public per-context cleanup clears web storage but leaves private cookies. The
combined scoped extension cleanup now passes (17.719 s): A is empty and B stays
intact. Capture an owned private lease before close, require the tab gone before
clearing explicit private-cookie attributes, and use the public web-storage clear.
The public-only failure remains reproducible. The native checker
was overly broad: NATIVE_ALIGNMENT.md records the Bionic whole-LOAD exemption,
19 passing Python checks and actual old-JNA negative control. All 13 Gecko libraries
pass the corrected gate. The main-toolchain prerequisite is now complete as
recorded above, without an extra AVD. Continue production renderer/cookie seeding,
theme, navigation and the existing 10 sec / 20 sec lifetimes in launch/archive UI,
including partitioned cleanup, account lifecycle, package/licenses and acceptance. Current native catalog has no web pages or configured feed; the
explicit archive placeholder is temporary, not acceptable as finished parity.

Two-host private cleanup now passes (1 test, 13.292 s): A's cookie and all three
storage values vanish at both `127.0.0.1` and `127.0.0.2`, while B retains its
values at both. This verifies top-level cross-host visits, not embedded third-party
partitions. Evidence: `captures/runtime/notice-multi-origin/`; experiment packages
removed and existing AVD stopped. Continue partition/account/lease
lifecycle checks before renderer integration.

Basic HTTPS/Secure transport now passes (1 test, 14.611 s) with actual TLS requests,
untrusted-certificate rejection before fixture CA trust, no Secure cookie on HTTP,
correct per-context cookies after returning to HTTPS, and empty page-script cookie
strings. The existing HTTP regression also passes (1 test, 7.567 s). The separate
fixture temporarily changes only its own Gecko DNS/CA state and restores it; never
copy those test controls or its synthetic key into production. Experiment packages
removed and AVD stopped; evidence `captures/runtime/notice-https/`. No Pixel or real
account exchange acceptance. The main renderer remains unfinished.

Native fitting now passes 4 portrait, 3 large-text and 1 landscape UI checks;
see the follow-up in WHATS_NEW.md and captures/runtime/whats-new-fitting. The
initial System UI ANR caused two appearance timeouts; unchanged tests passed
after observed recovery. Settings restored, AVD stopped/reaped.
Debug replay/suppression and physical modal/lifecycle/
TalkBack and signed-upgrade acceptance remain open. Signed494 is still current;
no APK assembly, emulator or signed build ran for the web-core checkpoint.
All processes are stopped/reaped, the Pixel remains absent, and the previous
reconnect request is pending. Keep the goal active and do not repeat that request.
Batch the next signed milestone after coherent implementation work.

## User's objective and working preferences

Latest local feature: [DIAGNOSTIC_FAILURES.md](DIAGNOSTIC_FAILURES.md) adds typed
failure codes to existing debug/durable logs and RPC connection/disconnection.
Twenty-three JVM tests, debug assembly and release Kotlin compilation passed;
no emulator was needed. Raw native Iroh telemetry and broader taxonomy remain open.

Previous local feature: [EMPTY_WORKSPACES.md](EMPTY_WORKSPACES.md) adds the shared
Mac/SSH empty-state scaffold and owner-scoped 30-second Mac retry. Thirty JVM and
two Android tests passed; normal portrait screenshots were reviewed. Exact source
mapping, hashes and remaining live acceptance are recorded there. No Pixel was
available for this checkpoint. Signed build 494 now includes this and onboarding.

Previous local feature: [ONBOARDING.md](ONBOARDING.md) implements all five introduction
scenes with durable milestones, explicit completion, Settings replay, saved page/
method/draft state, connection selection and the scoped Keep Mac Awake offer.
The first-run gate requires a verified account; offline replay is informational
and routes connection to Settings. Existing pairing/help, notification service,
foreground connection and ownership checks are reused. No FCM configuration is
claimed. See [ONBOARDING_AUDIT.md](ONBOARDING_AUDIT.md) for the scoped source mapping.

Signed build 494 includes this tour and the preceding compatibility/Computers/setup-guide
features. Next validate the authenticated first-run and
replay workflow against the Mac/Pixel, including account changes, QR cancellation,
keep-awake and app restart. Where the phone remains unavailable, continue the
remaining source/visual audit and Android push integration plan. The delivery
choice in PUSH_DELIVERY.md is still pending; do not configure a provider without
that choice and credentials. Physical/native/browser/keyboard/accessibility and
signed-in upgrade checks remain pending; preserve Pixel app data. The goal is
active; do not repeat the already-pending Pixel reconnect request.

- Deliver a fully functioning unofficial Android companion matching the official
  cmux iOS app's UI and behavior, including native pairing, workspaces, terminal
  rendering/input, files, browser, notifications, and settings.
- Target phone: **Google Pixel 6a, Android 17**. The user previously signed into
  cmux and connected Tailscale, and tried the original helper-based app. That is
  not proof of current native-protocol acceptance. Physical USB/debugging access
  was requested but not established in this session.
- The user says they obtained permission to use upstream. Preserve GPL and
  third-party attribution, keep the project described as unofficial, and use the
  real cmux logo/assets already in the repository.
- Commit and push coherent features regularly. Use focused checks between commits;
  build/test/publish APKs at combined milestones, not for every feature commit.
- Be precise about coded vs tested vs shipped. Never call the app complete because
  fixtures pass, promise zero defects, or give an unsupported completion estimate.
- The previous session spent too long polishing individual features before a
  complete live workflow was proven. Prioritize a usable, installed native path:
  **pair → workspace → terminal input/output → reconnect → notification**.
  Preserve the full parity goal; do not redefine a helper prototype as completion.
- The old Codex goal is paused on the original laptop. This handoff packages work;
  it does not resume that goal or start work on another machine automatically.

## Repository and delivery state

| Item | Value |
| --- | --- |
| Private repository | https://github.com/DocMorphic/cmux-app |
| Working branch | `feature/local-mac-bridge` |
| Draft PR | https://github.com/DocMorphic/cmux-app/pull/1 |
| PR title at handoff | Android cmux companion: native pairing and mobile RPC (draft) |
| Original checkout | `/Users/dharmaydave/me/cmux-app` |
| Last commit before the handoff feature | `6d8a213acd610b4ede1bff45132c35fd414c7ca7` |
| Last signed APK | Build **157**, source `d53abe9b05fc5912dc820738a725360096e7aa80` |
| Successful release workflow | https://github.com/DocMorphic/cmux-app/actions/runs/36360218971 |
| APK SHA-256 | `a847a2f9465cac4ac62883b6401217fac4624bd1f3b85223fbbfb127b1cd1e33` |
| APK size | 9,218,260 bytes |
| Signing certificate SHA-256 | `1118815b3831ae18005306c10bb0953a6fc2e9e20d14407223da52cda971abd4` |

The source branch is substantially newer than build 157. Recent Files, shared
Markdown, native text controls, and raw syntax work are **not in that APK**.
No release APK was built or published for this handoff. Keep PR #1 a draft until
the acceptance criteria justify changing it. Inspect current remote state after
cloning; Git history is the source of truth for the handoff commit SHA.

Old temporary download, only while the original Mac/server and tailnet are up:
`http://100.121.78.98:58467/app-release.apk?build=157`. It is not a portable or
permanent release URL. Source APK was `dist/native-d53abe9/app-release.apk`;
served copy was `dist/native-00de40d/app-release.apk`.

Clone the actual work branch:

```sh
gh repo clone DocMorphic/cmux-app -- --branch feature/local-mac-bridge
cd cmux-app
git status --short
git log -5 --oneline
```

Authenticate GitHub on the new laptop normally if necessary. Do not copy tokens
from this laptop. Reuse the existing PR, and attach it to the new Codex task if
that tool is available.

## What transfers through Git, and what does not

All implementation source, tests, vendored assets, provenance, licenses, scripts,
and handoff documentation are committed. `docs/SYNTAX_CHECKPOINT.md` preserves
the current verification facts in portable form.

Ignored/local files do not transfer: `captures/` screenshots and raw logs,
`dist/` APKs (the APK extension is ignored), Gradle caches/build outputs, SDK/AVD,
temporary upstream clones, `local.properties`, pairing/account state, helper
token, and release keystores. Recreate builds and emulator evidence as needed;
do not claim to have inspected old screenshots after a fresh clone. Original
evidence remains on the old laptop. Do not force-add ignored secrets or APKs.

Release signing uses existing GitHub Actions secrets
`CMUX_APP_RELEASE_KEYSTORE_BASE64` and `CMUX_APP_RELEASE_PASSWORD`. A local release
build requires `CMUX_APP_RELEASE_KEYSTORE` and `CMUX_APP_RELEASE_PASSWORD`; use CI
instead of exporting/replacing the private key. Debug has an independent package
suffix `.debug`. Preserve the existing release signer and increasing versionCode
so upgrades work; the build currently takes versionCode from `GITHUB_RUN_NUMBER`
or defaults to 2 locally.

## Source research and how to recover it

- Product: https://cmux.com ; iOS: https://cmux.com/ios ; guide:
  https://cmux.com/docs/ios . Primary code: https://github.com/manaflow-ai/cmux .
- Latest audited upstream pin:
  **`4c5272e9153eca2033c9f40ac749f0c3a5bcb291`**, Sep 27, refreshed Sep 28.
  Recheck upstream drift before a release, but preserve reproducible pins for
  parity comparisons and assets. Upstream is GPL-3.0-or-later; see `LICENSE` and
  `NOTICE.md` plus bundled third-party notices.
- iOS code is primarily `ios/cmuxPackage`, `Packages/iOS`, and
  `Packages/Shared/CMUXMobileCore`. Original sparse clone:
  `/tmp/cmux-official-source` (not transferred).
- Clone upstream separately with `--filter=blob:none --no-checkout`, configure
  sparse paths, then check out the pin. Use `git ls-tree -r --name-only PIN` to
  locate an exact file, then `git show PIN:path` for sparse/unmaterialized sources.
  Broad `git grep` against a partial tree triggered expensive lazy downloads.
- Upstream PR **#10576**, merged Aug 23, removed the iOS GUI agent-chat screen,
  transcript, and composer. Do not rebuild that removed feature as a parity gap.
  Retained `mobile.chat.artifact.*` names support the terminal Files gallery and
  previews. Older local docs mentioning missing GUI chat are superseded.
- Raw syntax dependency: `raspu/Highlightr`, version 2.3.0 at
  **`05e7fcc63b33925cd0c1faaa205cdd5681e7bbef`**, pinned by upstream
  `ios/cmuxPackage/Package.resolved`. Old clone: `/tmp/cmux-highlightr`.
  `scripts/sync-raw-code-highlighter.py <Highlightr-checkout>` reproduces the
  unmodified highlight.js 11.11.1 full 192-language bundle and Xcode dark/light
  styles. Manifest hashes and MIT/BSD licenses are committed.
- Markdown uses a **different**, upstream shared 11-asset bundle (including a
  limited highlight.js common build, Mermaid, Vega, CSS, and shell), reproduced by
  `scripts/sync-markdown-viewer.py`. Do not substitute it for the full raw-code
  highlighter. Hashes are under the respective assets directories.
- iOS source names to begin with: `CmxPairingQRCode`,
  `CmxAttachTicketCompactCoder`, `MobileCoreRPCSession`, `DeviceTreeView`,
  `MobileTerminalRenderGridFrame`, `GhosttySurfaceView`, `TerminalInputTextView`,
  `NotificationFeedView`, `CmuxMobileBrowser`, `CmuxMobileChanges`,
  `TerminalArtifactFilesSheet`, `ChatArtifactFolderView`,
  `ChatArtifactViewerDestination`, `ChatArtifactViewerActionsMenu`.

## Architecture map

Production Kotlin: `app/src/main/java/io/github/docmorphic/cmuxapp/`.
JVM tests: `app/src/test/`; Android fixtures: `app/src/androidTest/`.

| Area | Entry points / important files |
| --- | --- |
| App shell, primary navigation | `MainActivity`, `NativeScreen`, `NativePrimaryNavigation`, `NativeSearch` |
| Account, pairing, transport | `NativeCredentials`, `PairingCode`, `TailscaleRoute`, `NativeConnector`, `MobileFrameCodec`, `MobileRpcClient` |
| Workspace state and hierarchy | `NativeFeed*`, `NativeWorkspace*` |
| Terminal rendering | `RenderGrid`, `RenderGridView`, `TerminalGridPainter`, `TerminalGlyphLayout`, `TerminalDisplay`, `VtTerminal`, `GridVtReplay`, `TerminalStreamMirror` |
| Terminal input/scroll | `TerminalKeyboardView`, `TerminalInputQueue`, `TerminalComposerDelivery`, `TerminalHardwareInput`, `TerminalKeyEncoding`, `TerminalTransport`, `TerminalScrollMotion`, `TerminalViewport` |
| Notifications | `NativeNotification*` (feed, ledger, delivery, service, receiver) |
| Browser | `NativeBrowserView`, `BrowserPageSurface`, `BrowserInputQueue`, `BrowserInteraction`, `BrowserPageGeometry`, `BrowserStreamRecovery`, `BrowserScrollMotion` |
| Tasks/drafts/attachments | `NativeTaskComposerView`, `Task*`, `AttachmentFiles`, `ComposerAttachment` |
| Changes | `NativeChangesView`, `Changes*` |
| Terminal Files | `ArtifactGallery*`, `ArtifactFilesSheet`, `ArtifactRpc`, `TerminalArtifact*`, `ArtifactPathSheet` |
| Shared previews | `ArtifactFilePreview`, `ArtifactPreviewFiles`, `ArtifactShare`, `ChangesPreviewContent`, `MarkdownPreview`, `MarkdownRemoteImages`, `ArtifactText*`, `ArtifactSyntax*` |
| Legacy helper | `bridge/server.mjs`, `BridgeClient`, `BridgeScreen`, `BridgePairingStore` |

The native path is the intended product. The helper polls plain text and exists
as an optional older route; it cannot prove native protocol or full terminal
parity. Vendored Termux terminal emulator sources live in `third_party/termux`.

## Current implementation, known gaps, and prioritization

The table at the top of `PARITY.md` is the detailed area-by-area checklist.
Much of the app is implemented and fixture-tested: account/QR parsing and framed
RPC, workspace hierarchy/mutations, styled grid and VT terminal fallback, direct
IME/composer/attachments, feed and Android foreground-service notifications,
browser controls/input/gestures, task drafts/composition, Changes, Files, previews.
That is not a completed real-device acceptance run.

1. **Close the handoff test gap without starting a redesign.** Reproduce the four
   outstanding syntax/text/Markdown cases below on a responsive emulator or
   authorized test device. Preserve strong assertions; investigate failures.
2. **Prove the primary native workflow on the Pixel and real cmux Mac.** Follow
   the account/identity/Tailscale handshake, workspace, terminal input/output,
   reconnect, and notification path. Inspect current cmux version/capabilities.
   Gather actual errors before changing protocol or proposing workarounds.
3. **Build/publish a combined signed integration APK**, using the existing CI
   signing setup; verify source SHA, package/version, certificate, artifact hash,
   upgrade behavior, and exercised feature path. Do not promise stale build 157
   contains current source. CI success alone is not Pixel acceptance.
4. **Complete remaining parity by source comparison.** Iroh transport is missing
   (v3 QR parse only); v2 Tailscale is implemented. Complete Ghostty/inline graphics
   and pixel scrolling, input modes/rich IME paste, notification server-push
   fallback, browser downloads acceptance, settings network diagnostics/reset,
   Files incremental remote viewing and large-file performance, richer document
   previews, lifecycle/accessibility/battery checks, and UI detail remain open.
5. Source audit says browser downloads currently save on the **Mac** or invoke a
   Mac save dialog; inspected mobile RPC has no download transfer/save-panel
   endpoint. Verify live behavior/upstream changes before declaring an Android
   platform limitation or inventing a new endpoint.

Do not treat arbitrary SF Symbol differences, incomplete implementation, or
fixture-only validation as objectively unavoidable platform differences.

## Exact interrupted feature: raw syntax highlighting

This handoff commits the previously uncommitted implementation, explicitly with
runtime verification incomplete. See `SYNTAX_CHECKPOINT.md` for all 11 outcomes.

- Ported iOS policy: 57 extensions → 35 IDs; `.hs` and `.purs` → Haskell.
  Recognized files highlight through 1,500,000 bytes; unknown language automatic
  detection is strictly below 256,000 bytes. Oversize raw files retain text and
  show an expandable upstream-style Highlighting off pill.
- `ArtifactSyntaxHighlighter` runs bundled JS/CSS in an off-screen WebView;
  displayed text remains a native selectable TextView. No external resources,
  native JS bridge, file/content access, or network loads. Source is JSON data;
  escaped highlighted DOM is converted to UTF-16 foreground/style runs.
- Preserve CR/CRLF, Unicode, and literal markup. Validate unchanged original text,
  contiguous complete ranges, bounds, opaque colors, and style flags before
  applying spans. Late results are fenced by document identity/cancellation.
- `ArtifactSyntaxSpan` changes foreground and monospace bold/italic only; retain
  selection, font size, search background, and viewport. Shared Mutex serializes
  workers; 15-second worker/parse timeout and cleanup exist. Cancellation/renderer
  fault paths were reviewed, not fault-injected; no full recovery claim.
- No worker cache yet (iOS caches its Highlightr actor). Do not optimize based on
  timing from the old severely swapping host without representative measurements.
- Exact iOS files: `Packages/iOS/CmuxAgentChatUI/Sources/CmuxAgentChatUI/Artifacts/`
  `ChatArtifactSyntaxHighlightPolicy.swift`, `ChatArtifactSyntaxHighlighter.swift`,
  `ChatArtifactTextViewCoordinator.swift` (EOF/generation fences and preserving
  selection/scroll when applying styles), and highlighting status-pill policy.

### Last verification, and what to do next

The full JVM result is **307 tests, 0 failures/errors/skips**. Debug and Android
test APK assembly succeeded. The mapping and all three vendor hashes were checked;
all 35 mapped language IDs exist in the 192-language engine. The new five-case
Android syntax fixture compiled and four cases passed. One failed at a clipboard
read; the broader run had three failures and an unfinished final case.

The first Android launch crashed before tests due to BIND APPLICATION ANR. After
cold boot, the 11-case run recorded **7 passes, 3 failures, 1 started/no result**.
A screenshot showed a **System UI isn't responding** modal covering and dimming
the app. Keyguard was false. It plausibly explains null clipboard access and
gutter-pixel failure, but this has NOT been proven with a rerun. Never relabel
these as passing. The old emulator and instrumentation are no longer running;
all old exec session IDs are invalid. Start a fresh environment on the new laptop.

After confirming foreground focus and no system modal, rerun these exact methods
(class prefix `io.github.docmorphic.cmuxapp.`):

```text
NativeArtifactSyntaxTest#nativeLateColoringPreservesSelectionSearchFontViewportAndClipboard
NativeArtifactTextTest#searchWrapsAndJumpsToMatchesAndLineControlsMoveActualViewport
NativeArtifactTextTest#selectionAndCopyContentsPreserveNewlinesUnicodeAndExcludeLineNumbers
NativeMarkdownPreviewTest#sharedRendererDisplaysTablesCodeMermaidAndVegaAndSwitchesToRaw
```

The syntax selection case reached/passed its text, selection, font, viewport, and
search-span assertions before failing on `primaryClip!!`; it still counts as a
failed test. It saves `files/artifact-syntax.png` only after all assertions. The
old local `captures/artifacts/artifact-syntax.png` is **invalid** (58-byte missing
file error, not a PNG). Capture it afresh after a pass, verify magic, and inspect.
The oversize test uses small content with large declared size: policy/UI proof,
not large-file performance. Do not silently expand assertions into broader claims.

## Implementation lessons worth preserving

- Files requests retain immutable terminal/session authorization; keep relative
  paths for Mac resolution. Never broaden access scopes when opening descendants.
  Changes has separate fingerprint/revision validation; do not conflate the two.
- File sharing exports original streamed bytes with read-only FileProvider grants,
  cleans cancelled partials, and lets exported snapshots survive sheet closure.
- Path tap parity: 1,217 Swift reference cases (246 Unicode) matched Android's
  actual ICU widths. Count state parity: 6,400 transitions plus 33 path cases.
  Generation scripts are committed; preserve their upstream provenance.
- Native raw viewer: one continuous selectable buffer, UTF-16 line starts, exact
  newline/Unicode copy, logical line numbers, literal non-overlapping search.
  Wrap/font persist independently for CODE/LOG/PLAIN; default 15 sp, bounds 8–28,
  logs default no-wrap. Don't alter source text to implement the gutter.
- HorizontalScrollView ignores child width for wrapping: constrain TextView
  measurement and set wrap width before the first measure, not only onSizeChanged.
  Gutter drawing needs Canvas save/restore around super.onDraw and invalidation
  on ancestor scrolling, otherwise hardware-cached line numbers disappear.
- Pinch accumulates native font locally and cancels child gesture on start.
  Test spans must exceed Android's threshold (used ±15% → ±40% of screen width).
  Await actual layout/scroll and a distinct reopened native view in fixtures.
- Sheets are separate windows: `findArtifactTextInWindows()` uses WindowInspector,
  not only Activity decorView. Test hosts need Surface and safe drawing insets.
- Shared Markdown needs a scoped dark ContextThemeWrapper; legacy Activity theme
  otherwise yields wrong CSS. Allow initial data/about main-frame loading, then
  block unauthorized navigation/resources. Relative images/local Markdown links
  remain unresolved in the inspected iOS host too.
- Remote Markdown images require per-image consent. Transport uses validated DNS
  addresses for the actual HTTPS connection, port 443, private/reserved-address
  rejection, same-host redirect limit 3, image MIME allowlist, decoded 8 MiB cap.
  Live HTTPS success, crash recovery, full zoom/accessibility remain unverified.
- Terminal input queues must not replay uncertain keystrokes after timeouts.
  Retain ownership/generation guards on reconnect, activity changes, and late RPCs.

## Build/test operations on the new laptop

Requirements: JDK **17**, SDK platforms **37.0 and 36**, build-tools **36.0.0**, platform-tools,
Node **22** for helper tests, Python 3 for source generators. Wrapper: Gradle 9.3.1;
AGP 9.1.1; built-in Kotlin/Compose compiler 2.4.20. Android min 26, target 36,
compile 37 except the API 26–27 fingerprint adapter (compile 36). See
[ANDROID_TOOLCHAIN.md](ANDROID_TOOLCHAIN.md). Android
17/API 37 was used for emulator acceptance; choose an image for the new CPU.
Set JAVA_HOME and ANDROID_HOME for that machine; do not copy old absolute paths.

```sh
node --test bridge/*.test.mjs
./gradlew --no-daemon --max-workers=1 :app:testDebugUnitTest
# At a combined runtime checkpoint:
./gradlew --no-daemon --max-workers=1 :app:assembleDebug :app:assembleDebugAndroidTest
adb -s DEVICE install -r app/build/outputs/apk/debug/app-debug.apk
adb -s DEVICE install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s DEVICE shell am instrument -w -r -e class 'FULL_CLASS#METHOD' \
  io.github.docmorphic.cmuxapp.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Use comma-separated fully qualified selectors for a focused batch. Inspect the
instrumentation **`OK (N tests)` / failure summary**, not merely adb's exit code.
For captured fixtures, use `adb exec-out run-as io.github.docmorphic.cmuxapp.debug
cat files/NAME.png`, then check the resulting file is a PNG before viewing it.

The old Mac has only 8 GiB RAM and reached ~8.7 GiB swap during failed runs.
Emulator + Gradle together made timeouts/ANRs worse. Stop the owned emulator
before Gradle; run one worker, then launch emulator after builds finish. Do not
kill other user apps/agents. A stale `cmux_ready` snapshot had startup failures;
cold boot with `-no-snapshot-load -no-snapshot-save` was used, but the system modal
still appeared. Do not reuse those timings as an app performance benchmark.

CI `.github/workflows/android.yml` deliberately skips draft PR builds and has no
push trigger. A combined release checkpoint can be dispatched with:

```sh
gh workflow run android.yml --ref feature/local-mac-bridge
```

The workflow tests helper/JVM, assembles debug/test/release APKs, verifies signature,
and uploads `cmux-app-stable-signed-apk` and `cmux-app-preview-apk`. It does **not**
run Android instrumentation or real-device acceptance. Workflow skip ≠ pass.

## Mac/phone access and outstanding authorization

- User authorized the old helper on the original Mac's Tailscale address, port
  **58466**, using a private pairing token. APK server used **58467**. Their live
  status must be checked; do not assume moving the repository moves a service.
- The **native cmux listener on 58465 remained OFF** after an earlier automatic
  approval review rejected enabling it. Do not enable it or bypass the restriction
  from the new laptop based solely on permission to clone/continue development.
  Prepare the concrete native test path and explain the existing review block
  before requesting the needed approval/user action. The original rejection's
  detailed text is not retained in this handoff; do not invent a reason.
- Helper needs to run inside a cmux terminal because cmux restricts its socket
  ancestry. Read `bridge/README.md`; don't make a public proxy to work around it.
- Never print, commit, or transfer `~/.config/cmux-app/bridge-token`, private
  pairing URLs, account tokens, or `/tmp/cmux-helper-terminal.log` (may contain
  credentials). Re-pair through the app as appropriate. The published checksum
  and certificate fingerprint are public integrity metadata, not private keys.
- A different laptop may not have access to the original Mac, tailnet, Pixel,
  accounts, or signing secrets. Inspect available prerequisites; continue
  independent repository work while asking only for genuinely missing access.

## Completion standard

Keep code, parity tracker, and evidence aligned. Record exact source/build tested,
test counts and failures, device/OS/cmux versions, and remaining limits. Finish
with an installable signed APK verified on the user's Pixel against their Mac,
not only an emulator-local peer. Keep committing and pushing the same project.
