# Workspace selection audit

Audited 2026-09-30 against upstream
`4c5272e9153eca2033c9f40ac749f0c3a5bcb291`. Fresh-open default selection and local
browser workspace-lifetime protection are implemented. Persisted last-tab memory
is now wired into workspace navigation; see the integration checkpoint below.
General delayed-pane selection and browser discovery are now integrated.
Workspace/pane identity now survives Activity recreation, including offline panes
and existing startup/restore intent. Android task saved state now restores the
workspace/pane after real process death, with fresh owner admission and inventory.
**Nested detail state, interrupted creation and physical acceptance remain open.**

## Reference code

- `Packages/iOS/CmuxMobileShell/Sources/`: `MobileShellComposite.swift` selection
  synchronization, last-tab restore and `WorkspaceAbsenceAuthority.swift`.
- `Packages/iOS/CmuxMobileShellModel/Sources/CmuxMobileShellModel/`:
  `MobileWorkspacePreview.swift`, `MobileTerminalPreview.swift`,
  `MobileWorkspaceLastTabStore.swift`.
- Upstream `MobileWorkspacePreviewDefaultSurfaceTests.swift` and
  `WorkspaceDetailViewDefaultSurfaceSelectionTests.swift` cover default routing.

Use targeted `git show`/`git ls-tree` in the pinned upstream checkout. Broad search
through its partial clone can trigger substantial lazy fetching.

## Required selection policy

1. Preserve the selected workspace and terminal while its owner is degraded;
   absence in an incomplete/disconnected inventory is not deletion.
2. On workspace open, restore the last displayed tab before current-selection,
   Mac-focus or default heuristics. Restore may succeed, wait for panel discovery,
   or be unavailable. An interim fallback must not overwrite a waiting restore.
3. Keep a newly created terminal selected while starting, scoped to the owning
   Mac/build/workspace. Release the pin on readiness, confirmed disappearance,
   scope change or bounded expiry. An existing selected terminal is retained when
   ready, or when no ready terminal exists.
4. Retain active Simulator/browser streams; resolve explicit Mac surfaces to a
   matching Simulator stream first, then browser stream, then the raw surface.
5. With no retained explicit terminal, prefer the Mac-focused non-terminal pane.
   When there are no terminals, use the first non-terminal in host surface order
   if nothing is focused. The fallback is not a separately grouped browser list.
6. If still unresolved, select the first Simulator panel, then browser panel,
   then the preferred terminal. Terminal preference is ready+focused, ready,
   focused, first. The pinned `MobileWorkspacePreview+RemoteMapping.swift` maps
   `Terminal.isReady` (`is_ready`) with `nil` defaulting to true. Android now
   preserves the field and applies the same default for absent/null values.
7. Reevaluate when delayed panel discovery or Simulator inventory arrives.

## Persistent last-tab memory

Upstream persists a device-local map with five equal tab kinds: terminal,
Mac surface, browser stream, Simulator stream and local browser (`local` ID).
The key uses canonical owning Mac/build identity plus the RPC workspace ID,
independent of aggregate list-row IDs. Android should additionally isolate
account/team scope, consistently with its existing multi-account stores.

The store is bounded to 512 workspaces by a monotonic last-update sequence.
Recording an unchanged tab performs no write and does not bump recency. Unknown
tab kinds are ignored individually, without losing other entries. Explicit
selection cancels pending restoration; derived selection churn does not.

Terminal/Mac-surface restoration waits when inventory is empty; a missing entry
in populated inventory is unavailable. A remembered unready terminal waits when
another terminal is ready. Browser restoration waits for initial discovery or a
known browser pane; Simulator restoration requires a matching descriptor. A
local-browser restore can reopen its retained page or a default page after process
restart; upstream does not persist WebView history.

## Android continuation

`NativeWorkspaceSelection` now resolves fresh workspace opens through the focused
non-terminal, spatial non-terminal fallback for terminal-less workspaces, available
Simulator descriptors, discovered browser streams and readiness/focus terminal
preference. Explicit terminal/browser/surface routes remain exact and reject missing
targets. Changes keeps its dedicated route. `NativeScreen` uses the resolver for
workspace routes; notification routing retains its separate behavior.

Wire browser surfaces and discovered browser streams are different inventories.
The resolver takes a separate discovered-browser list; an unfocused browser surface
alone does not override a ready terminal. Discovery is now connected to visible
workspace refresh and pending empty-workspace/default selection. Simulator
descriptors are available in workspace snapshots. A matching Simulator or browser
descriptor promotes the selected raw surface to its streaming view, preserving
focus and host order; Simulator descriptors take priority on an ID collision.

The preference store, restore controller, created-terminal startup pins, refresh
ordering and delayed discovery are integrated in the checkpoints below. Local
browser memory is one of the five persisted tab kinds. Activity recreation now
retains the selected workspace/pane in the existing session. Android task saved
state now restores that destination after process death, as recorded below. An
unacknowledged creation interrupted by destruction remains unresolved.

Continue with detail views/overlays, physical deep-link acceptance, interrupted
creation recovery and physical Macs/builds. Keep additions outside the large `NativeScreen` method,
which has already hit Kotlin's JVM bytecode size limit. Follow with Pixel/Mac
acceptance.

## Default-routing verification (2026-09-30)

- **31 JVM tests passed**: nine selection/readiness cases, five existing surface
  cases, four workspace parity cases and 13 local-browser navigation cases.
- Debug and instrumentation APKs build successfully.
- **12 Android 17 / 16 KiB tests passed in 22.773 seconds**: four full-screen
  selection cases (ready/focused terminal, focused Mac pane and explicit terminal
  choice, non-terminal spatial order, exact focused-browser stream), six local
  browser routing regressions, file/Markdown/unknown-surface navigation, and the
  existing Simulator surface-view route through a borrowed RPC client.
- The previous panel test expected the first file despite a focused canvas.
  It now asserts the focused canvas first, then explicitly selects the file,
  preserving its subsequent text/Markdown preview and Mac-focus checks. Captured
  text and Markdown screenshots were inspected and contain the expected content.
- Ignored evidence: `captures/runtime/workspace-selection/`; final build log:
  `/tmp/cmux-workspace-selection-final-build.log`. The owned emulator was stopped.
  No physical Pixel was visible to ADB and no Pixel account/data was touched.
- Debug APK SHA-256: `451570cc2f8899b98424d7e694092bafa2dbf4ffdba59efd07fb55bcaae2f21d`.
- Test APK SHA-256: `d5d91759a4d3930080a225ee5c98da782d66308857ebdcc99cc218609f2710b3`.

Signed build 261 predates this change. No new signed milestone was dispatched.

## Last-tab storage and restore foundation (2026-09-30)

At this foundation checkpoint it was not yet wired into `NativeScreen`. The later
navigation integration below supersedes that limitation.

`NativeWorkspaceLastTabs` supports all five upstream kinds and at most 512 entries.
Unchanged writes do not alter recency. Loading isolates malformed/unknown entries;
unknown kinds remain stored but cannot be selected. Recency overflow rebases the
bounded map while preserving order. Keys use account/team, canonical Mac device
and normalized build tag, and RPC workspace ID. Route/name changes do not change a
known Mac's key. Anonymous pairings use their origin; unverified unscoped accounts
use the login incarnation. This follows the pinned `CmxMacAppInstanceIdentity`
normalization, including trimming build-tag whitespace and treating empty as nil.

`NativeCredentialStore.lastWorkspaceTab` and `rememberWorkspaceTab` use its existing
Keystore-encrypted state. The shared storage lock covers login validation and the
whole read/modify/commit operation, so concurrent store instances merge writes.
Unchanged selections skip encryption/commit/revision changes. Retired login calls
cannot read/write preferences or recreate signed-out account state. Account/team
scope belongs in the computed key; UI callbacks must still check their captured
authorization/navigation scope before choosing that key.

`restoreWorkspaceTab` returns restored, waiting, or unavailable. It distinguishes
empty hydration from populated missing panes, waits for a remembered unready
terminal when a ready sibling exists, and distinguishes undiscovered browser
inventory (`null`) from a successful empty list. A remembered Mac browser surface
stays a raw surface until its stream descriptor is discovered. Simulator restore
requires its descriptor; local browser restore requests a view-owned reopen.
Matching active streams are retained; a different active stream wins over memory.
`NativeWorkspaceTabRestoration` owns one pending key/tab, keeps it across waiting
results, and disarms it on resolution/cancellation/new opens.

Verification:

- **23 JVM tests passed**: six bounded-store/key cases, eight restore cases and
  nine default-selection regressions.
- Debug/instrumentation APKs built. **Three Android 17 / 16 KiB storage tests passed
  in 0.125 seconds**: encrypted round trips across store instances for all five
  kinds and idempotent writes; login replacement/sign-out fencing; and concurrent
  store-instance writes preserving every tab and unrelated account update.
- Android tests use disposable, separately named preferences and the real
  Keystore adapter. They do not exercise screen restoration, process death or a
  real cmux account. The owned emulator was stopped after testing.
- Ignored evidence: `captures/runtime/workspace-tab-memory/`; final build log:
  `/tmp/cmux-workspace-tab-memory-final-build.log`.
- Debug APK SHA-256: `040bde1b768e001ce779fb8539bb1e78ac311843ebc679f1e8c4a36d9091a580`.
- Test APK SHA-256: `463c5b13d7a5b27dc595eaf8ba5a0b07aed417da8a4200635edb150276be5859`.

### Integration steps identified at the foundation checkpoint

1. Record committed route/default opens, explicit terminal/surface/stream picks,
   task-created terminals, and local-browser opens. Disarm pending restore on
   explicit selection. Do not record an interim derived fallback while waiting.
2. On a default workspace open, consult memory before other defaults; local-browser
   restore must directly reopen retained/default local state without a Mac create
   RPC. Existing one-shot local restore is insufficient after process death.
3. Feed pending restores fresh owning snapshots even while a terminal is visible.
   Cross-Mac/account navigation, notifications, Back and sign-out must retire old
   pending work. Preserve selected pages across degraded/missing inventory.
4. Wire the currently unused `MobileRpcClient.browserPanels(workspaceId)` read to
   scoped discovery. Pinned `MobileBrowserListResponse` requires a `panels` array;
   descriptors use `panel_id`, `workspace_id`, optional URL/title, page dimensions,
   navigation/loading booleans and optional dialog. Reject wrong-workspace or
   malformed results without converting them into confirmed empty discovery.
   Capability gate is `browser.stream.v1`; an old client response cannot update a
   new owning session. See `MobileShellComposite+BrowserStream.swift`.
5. Integrate readiness/created-terminal pins and delayed surface promotion without
   stealing an explicit selection. Verify full-screen restoration for every kind,
   delayed results, Account/Activity/process changes and multiple Macs/builds.

Signed build 261 predates this foundation. No new signed build was dispatched.

## Last-tab navigation integration (2026-09-30)

`NativeFeedSession` now owns `NativeWorkspaceTabNavigation`. The main screen records
committed displayed panes, explicit picker choices and phone-local browser opens.
Explicit picks disarm pending restoration, including choosing the same interim
fallback. Back/navigation/account scope changes retire old pending work. Default
workspace opens consult persistent memory before Mac focus/defaults. A transient
fallback never replaces a still-waiting remembered tab.

Pending restoration uses a unique ticket and exact account/Mac/build/workspace key.
`NativeWorkspaceTabRecovery` reads fresh owning workspace inventory and, for a
remembered browser stream, the separate `mobile.browser.list` inventory. The read
is capability gated and validates descriptor types, unique IDs and workspace
ownership. Invalid reads preserve the pending intent. Results check both the ticket
and current navigation/authorization before publication. Activity lifecycle
`repeatOnLifecycle(STARTED)` cancels in-flight restoration while stopped and retries
the read on return, without replaying mutations or input.

Discovered browser panels are retained separately for the visible owning workspace
and merged into its presentation. Ordinary workspace surface refreshes cannot
erase a valid discovered stream; late retired tickets cannot populate that cache.
Leaving the workspace clears the presentation cache. Remembered local browsers
reopen retained/default phone state without a Mac creation request. Empty workspace
rows with persisted local-tab memory remain tappable. Closing the local pane clears
that memory; when no Mac pane exists, Close returns directly to the workspace list.
The list decrypts the bounded preference snapshot once per account revision.

The first compile exceeded the JVM method-size limit again. Settings composition
was extracted to `NativeSettingsLayout`, preserving its existing section order and
callbacks; subsequent debug/instrumentation builds succeeded.

Verification:

- **33 JVM tests passed**: six navigation/discovery cases, six store/key cases,
  eight restore cases and 13 local-browser navigation regressions.
- **23 Android 17 / 16 KiB tests passed in 40.187 seconds**: ten new full-screen
  restoration cases, six local-browser regressions, four default-routing cases
  and three real Keystore storage cases. The new cases cover explicit terminal
  memory across Back and a fresh Activity, Mac-surface recreation, readiness waits,
  explicit selection of the interim fallback, exact browser discovery, late
  discovery after a new selection, background/return, Simulator descriptor choice,
  local-browser reopening without creation and local Close in empty workspaces.
- The initial 23-test run had two browser failures: a subsequent workspace listing
  discarded the separately discovered panel before streaming started. The scoped
  discovery presentation cache fixes this; the complete 23-test rerun passed.
  Original failure output is retained alongside the successful run.
- Ignored evidence: `captures/runtime/workspace-tab-ui/`; final build log:
  `/tmp/cmux-workspace-tab-ui-final-build.log`. Owned emulator stopped afterward.
- Debug APK SHA-256: `bc2562be57427c0c4884aeba10999defcd5a3c4e924ee2fa18d3ffcded76cf62`.
- Test APK SHA-256: `ef2ef9f72fd550b01a2244745010e0a5ad50c31c906dfbb417041627ee92d9c4`.

### Remaining selection work

- Browser discovery currently services remembered-stream restoration. General
  discovery for fresh empty workspaces and uncertain create outcomes still needs
  integration, including default promotion when descriptors arrive later.
- Created-terminal startup and shared refresh ordering are implemented in the
  checkpoints below. Late default-pane discovery and live multi-Mac/build
  convergence remain open. The resolver is not yet the full iOS selection
  synchronizer.
- Physical Pixel/Mac, true OS process-death, broader multi-Mac/build and
  accessibility acceptance remain open. Fixture restoration of a Simulator
  descriptor proves selection; its actual stream is verified by the earlier
  Simulator tests, not the synthetic host used here.
- Signed build 261 predates this integration. No new signed build was dispatched.


## Created-terminal startup checkpoint (2026-09-30)

The pinned iOS `CreatedTerminalSelection` and `MobileShellComposite` keep a new
terminal selected for up to 30 seconds even when a sibling is ready. Readiness,
confirmed disappearance, another explicit selection or owner changes release the
pin. Expiry shows “The new terminal did not finish starting.”; the
`WorkspaceDetailView` recovery banner's Retry explicitly creates a new terminal.
Late readiness clears the banner without selecting that terminal again.

Android now implements this flow:

- `NativeTerminalStartup` scopes pins to the same account/team/Mac/build/workspace
  key used by tab memory. Its elapsed-realtime deadline includes device sleep;
  foreground lifecycle restart uses the remaining time. It never resends a create.
- Create responses open their exact terminal from the validated returned workspace
  without waiting for another listing. New workspace and task responses merge
  partial inventories without discarding unrelated rows and arm the same pin.
- Starting terminals keep their title, Back and pane controls, with a visible
  waiting pane and disabled keyboard. Replay/viewport attachment, native input
  lanes, queued input, composer delivery and artifact polling require readiness.
- Validated snapshots release a ready/disappeared pin. Without a pin, an unready
  selected terminal yields to the normal fallback when ready siblings exist.
  Failed or malformed workspace-list reads preserve the last selection.
- Read polling while starting or showing a timeout is lifecycle-bound. The timeout
  banner appears above the pane, offers one explicit Retry, and clears on a late
  success. Changing to a sibling cancels the pin immediately.
- Delayed create results are fenced by account/owner and navigation generation,
  including leaving a screen and returning to the same screen before the reply.
  Task creation keeps its existing request connection-token guard and additionally
  checks the active composer/account session.

Verification:

- **35 JVM tests passed**: 11 startup/deadline/navigation cases, nine default
  selection cases, six remembered-tab navigation cases and nine task submission
  cases.
- **27 Android 17 / 16 KiB tests passed**: seven new startup cases, ten remembered
  tab regressions, four default-routing cases, three task flows and three keyboard/
  composer flows. The main 24-test run took **83.707 seconds**; the three input
  regressions took **11.597 seconds**. All passed on their first runtime run.
- New full-screen cases verify no terminal RPC before readiness, attachment after
  readiness, explicit sibling choice, actual 30-second timeout and late recovery,
  explicit Retry with an exact request count, confirmed disappearance, a delayed
  create after navigating away, and partial new-workspace responses. The startup
  screenshot was visually inspected.
- Evidence (ignored): `captures/runtime/terminal-startup/`, including test XML,
  build/runtime logs, screenshot and APK metadata. Owned emulator stopped.
- Debug APK SHA-256: `764ef008992280c1c193b060058a493f8604038871f3fde6a0741ba544f02e1c`.
- Test APK SHA-256: `43c9dbd932376abe6e38ba4df558694035f573f7ab94c98ec40fd73b68cedaf2`.

These are emulator fixture results, not physical Mac/Pixel acceptance. ADB still
reported no physical device. No user account data or phone settings changed.
Full Activity/process restoration of the current navigation/creation state,
landscape/accessibility acceptance and physical host convergence still need work.
Refresh ordering is addressed by the next checkpoint. Signed build **261** is
unchanged and predates this feature.


## Shared workspace refresh ordering (2026-09-30)

`NativeWorkspaceSnapshots` coordinates foreground and feed inventories across
separate clients for the same login/account/team/Mac/build. It tracks when a read
started and the last snapshot actually accepted for publication. A late response
cannot replace a newer accepted inventory. A malformed or failed newer read does
not discard an earlier valid snapshot.

Workspace, terminal and browser creation, workspace/group actions, checklist
mutations and explicit surface focus invalidate in-flight inventories before and
after their RPC. Failed or cancelled mutations also invalidate reads: an absent
acknowledgement is not proof that nothing changed. Reads wait for mutations, then
retry superseded inventories up to three times within a 30-second bound. Mutations
are never replayed by this mechanism. A read timeout becomes a recoverable I/O
error without cancelling the owning feed monitor.

Publication checks cover connection startup, workspace and notification routing,
periodic/event refreshes, task/group refreshes, checklist results and remembered
tabs. Browser discovery checks the inventory again after its separate awaited
RPC. Create callbacks use an already accepted newer snapshot if available; they
cannot resurrect a workspace whose subsequent confirmed snapshot removed it.
Direct foreground mutations also recheck the exact current connection and account
before transmission.

An explicit open of an already known workspace pane can use that owner's cached
row while another mutation is pending. This is navigation, not a new authoritative
inventory, and cannot prove absence or retire other panes. The create response
still cannot override that newer navigation. This distinction fixed a regression
found by the delayed-create runtime test.

Verification:

- **39 JVM tests passed**: 11 snapshot ordering/account/lifetime cases, 17 feed
  coordinator regressions and 11 terminal startup cases.
- **34 Android 17 / 16 KiB tests passed**: a 30-test batch in **100.67 seconds** and
  four notification/multi-Mac/reordering cases in **11.656 seconds**.
- Two new full-screen cases delay a foreground read across terminal creation and
  delay browser discovery across a newer feed-confirmed workspace deletion. They
  verify that the new terminal remains selected and a deleted browser never starts
  streaming. Existing cases cover startup/timeout/Retry, default and remembered
  tabs, task creation, checklist rejection and lost acknowledgements, panel focus,
  terminal input/resize, notification ownership and colliding workspace IDs.
- The initial 30-test run had one failure: the read barrier blocked navigation to
  another known workspace while a create was waiting. The cached-pane navigation
  fix above resolved it; the complete 30-test rerun passed. The original failure
  log is retained.
- Ignored evidence: `captures/runtime/workspace-order/` (initial and final runtime
  logs, build log, JVM XML and APK metadata). Owned emulator stopped afterward.
- Debug APK SHA-256: `0a324d3f9abe187aae824ae90a0356a5e7abac7e437aea41d4bdb1923b07e710`.
- Test APK SHA-256: `6277a3afad6c4ed0d6effb1e9af9c0aae8575ef38fe021b5b1f54279faf62a13`.

This checks client request ordering and mutation boundaries; it does not prove
freshness of data cached internally by a host. Physical Mac/Pixel acceptance,
current-screen restoration across Activity/process recreation and general delayed
pane discovery remain open. No physical device was visible to ADB, no phone data
or settings changed, and signed build **261** remains the latest signed release.


## Empty workspaces and delayed browser discovery (2026-09-30)

Every workspace row now opens, including a freshly created workspace whose panes
have not arrived. The waiting screen retains Back, New terminal and New browser;
connection loss shows reconnect status. It attaches to a late terminal, Mac pane,
Simulator or discovered browser using the existing default policy. No terminal
output/input is attached before the selected terminal becomes ready.

Visible workspaces now discover browser panels even without a remembered browser.
Discovery is capability-gated and scoped to login, owning Mac/build, workspace,
client and foreground lifecycle. The terminal menu exposes discovered browsers.
A matching descriptor upgrades the selected raw Mac panel, with Simulator priority;
unrelated late browsers never replace a ready terminal or an explicit selection.
Malformed/failed discovery preserves the previous cache, while validated empty
results can retire it. Browser inventory remains separate from wire surfaces.

Pending recovery and general browser discovery publish validated known panes
before waiting for the browser RPC. They own visible-workspace polling so an
unrelated periodic read does not continually supersede slow discovery. Host events
still refresh immediately. Snapshots are checked again after the browser request;
a deleted workspace or changed session cannot receive a late browser result.

A host refresh may replace the interim fallback while a remembered tab is waiting.
That derived change now updates the fallback marker without cancelling remembered
intent. Explicit picker actions still cancel it, including selecting the current
fallback. Starting a terminal cancels default discovery so a delayed fallback
cannot invalidate the create response and its startup pin.

The outer screen layout was extracted into a non-inline composable after the first
build exceeded Kotlin's per-method JVM bytecode limit. The corrected debug and
instrumentation builds succeed.

Verification:

- **50 JVM tests passed** across navigation, default selection, remembered-tab
  resolution, terminal startup and shared workspace snapshot ordering.
- **39 Android 17 / 16 KiB tests passed in 115.648 seconds** on the final APKs:
  nine delayed-pane cases, seven startup cases, eleven remembered-tab cases,
  four default-routing cases, two ordering cases and six local-browser cases.
- New runtime cases cover empty rows, late terminal/Mac panel arrival, browser-only
  discovery without wire surfaces, browser-menu selection, raw-panel promotion,
  explicit selection during discovery, leaving before discovery completes,
  terminal creation from the waiting view and a genuinely new empty workspace.
  A further remembered-browser case replaces the interim terminal via a host event
  before browser discovery succeeds and verifies that the remembered browser wins.
- The earlier nine new cases and 29 existing regression cases also passed before
  the final fallback-marker fix. No runtime failures were observed in this work.
- Ignored evidence: `captures/runtime/delayed-panes/` includes original compiler
  failure, successful builds, runtime logs, JVM XML, APK hashes and the inspected
  waiting-screen screenshot. The owned emulator was stopped afterward.
- Debug APK SHA-256: `286624bab6636886848dbf6ce4fc6665cdf503d1cbb5ebf225e9de32f36f9b0f`.
- Test APK SHA-256: `fec23c728b9214312f527f8dd552b297603ab3c51c8496d537fca559be5d9922`.

ADB and the Mac USB device tree still showed no physical Pixel despite the user's
connection report. No phone app data, sign-in or settings changed. Current-screen
restoration across Activity/process recreation, broader physical multi-Mac/build
acceptance and landscape/accessibility verification remain open. Signed build
**261** is unchanged and predates this checkpoint; no new signed build was launched.


## Workspace/pane retention across Activity recreation (2026-09-30)

`NativePaneNavigation` now belongs to `NativeFeedSession`. Its foreground workspace,
terminal, browser, Mac/Simulator surface, Changes workspace and cached workspace/
group inventory survive Activity recreation. The retained object contains
presentation models and Compose state, without an Activity, view, socket or input
lease. Connection and rendering resources are recreated normally; input remains
subject to the existing connected/current-owner/readiness checks.

Selection is scoped to login, admitted saved pairing, canonical Mac identity,
build tag and account/team. A different owner, pairing removal, sign-out or session
clear drops it. A team refresh generation or display-name change keeps it. Old
callbacks referencing a retired selection cannot mutate the new owner's selection.

Keeping the selected pane from the first recomposition also preserves the existing
remembered-tab restoration ticket and a created terminal's original startup ticket
and deadline. The implementation does not create another terminal on recreation.
Offline panels keep their selected workspace and reconnect status, with Mac actions
disabled. Terminal/browser streams reconnect to the same selected ID.

Changes has an independent destination: inventory refresh updates its workspace
metadata without choosing a default terminal, and closing it returns to the
workspace list. Confirmed removal still retires that destination. Two older
remembered-tab tests now expect the panel directly after recreation rather than
manually reopening the workspace.

Verification:

- **26 JVM tests passed**: five retained-owner cases, ten tab-navigation cases and
  eleven startup cases.
- **41 Android 17 / 16 KiB tests passed in 121.379 seconds**: nine new Activity
  cases, eleven remembered-tab cases, nine delayed-pane cases, seven startup
  cases, three local-browser lifecycle/picker cases and two snapshot-order cases.
- New cases check exact terminal and browser IDs, returning to the workspace list,
  offline Mac-panel identity with disabled actions, Simulator selection, Changes
  refresh/Back, empty-workspace arrival, unchanged startup ticket/deadline with
  exactly one create, retained browser-restoration intent and replaced login.
- The initial nine-case run had one test expectation failure. It waited for the
  internal fixture exception text, while the UI correctly retained the panel and
  displayed its existing reconnect message. An inspected diagnostic screenshot
  confirmed the panel and disabled Mac action. The corrected test additionally
  verifies a failed reconnect attempt before checking reconnect status and the
  disabled action. Original failed-run logs are retained.
- Ignored evidence: `captures/runtime/pane-activity/`, including build/runtime logs,
  JVM XML, the inspected offline screenshot and APK metadata. Emulator stopped.
- Debug APK SHA-256: `8df0dc06b353383615811e08c2490711961c497ec9f742a4af1bf2e60e2f5713`.
- Test APK SHA-256: `c521f2b0038cf18940d64a465e6ca9885964bd1def7ce076ce4fd710d2af789e`.

This verifies retained workspace/pane identity across Activity recreation. It does
not prove current-screen restoration after real OS process death, retention of
all nested Files/Changes detail, scroll/zoom/overlay state, or recovery of a create
whose acknowledgement was interrupted by destruction. Those remain required
follow-up work, alongside physical Pixel/Mac acceptance. No physical Pixel was
visible, phone data/settings were unchanged, and signed build **261** is unchanged.


## Android task restoration after process death (2026-09-30)

`NativeScreenResume` uses Compose saved-instance state to remember the current
workspace, pane kind/ID or Changes destination. It records the login incarnation,
account/team, canonical Mac/build and startup ticket/deadline. Saved state contains
no credentials, terminal output, rendering/input leases or mutation requests. A
normal cold launch still follows the existing launch behavior; this is Android
task restoration rather than an always-reopen preference.

Restoration waits for saved-Mac account admission and a fresh owning workspace
snapshot. Legacy saved rows without cached account fields may wait for admission,
but cannot bypass full account/team matching. Login replacement, owner/build
changes, pairing removal, confirmed workspace removal and explicit newer navigation
retire the destination. Back also clears the queued route, preventing a late
reconnect from reopening it. Browser discovery resolves the saved browser even
when fresh workspace wire surfaces omit it and the Mac now focuses a terminal.

An acknowledged newly created terminal retains its exact startup ticket and
original deadline. Elapsed time while the process is dead counts toward timeout.
The saved Android boot counter prevents a device reboot from reviving a pin when
elapsed clocks overlap; unknown boot identity expires it conservatively. The
counter is read using Android's existing
[Settings.Global.BOOT_COUNT](https://developer.android.com/reference/android/provider/Settings.Global#BOOT_COUNT)
API. A timeout shows the existing failure/fallback; restoration never retries a
terminal/workspace creation. Explicit Retry remains a separate user action.

The emulator-only debug fixture runs the UI in a dedicated private process. Tests
background that task, wait for Android's save/stop callbacks, kill only its process,
and bring the same Android task forward. They verify a changed UI PID and a real
restored saved-instance bundle while the instrumentation/fixture process stays
alive. This exercises actual process death, not just Activity recreation. Fixture
setup clears synthetic emulator credentials and must never run on a physical phone.

Verification:

- **36 JVM tests passed**: ten saved-state/owner/deadline cases, five retained-pane
  cases, ten tab-navigation cases and eleven startup cases. Both APKs built.
- **53 Android 17 / 16 KiB tests passed in 237.650 seconds** before the final legacy
  cached-owner lookup adjustment: twelve real-process cases plus the previous
  41 Activity, remembered-tab, delayed-pane, startup, local-browser and ordering
  regressions.
- After that adjustment and two added owner unit cases, **all twelve process
  tests passed again on the final APKs in 113.162 seconds**. They cover all five
  pane kinds, Changes, empty workspace/late arrival, replaced login, removed
  workspace, Back during gated reconnect, browser discovery without wire surfaces,
  and startup before/after its original deadline. The timeout case waits 31 real
  seconds with the UI process dead and verifies exactly one create.
- The initial debug fixture used an invalid hyphenated process suffix and failed
  APK installation. Changing it to `:restore_test` fixed installation. No runtime
  test failures were observed in these process-restoration batches.
- Ignored evidence: `captures/runtime/process-resume/`, including original build
  and runtime logs, final JVM XML, per-stage APK hashes and the inspected restore
  screenshot. The owned emulator was stopped. The Pixel still did not appear in
  ADB or wireless-debugging discovery; its installed app/data/settings were untouched.
- Final debug APK SHA-256: `6b6bfc1e20a3b34d6ee07e8948b67547f9b2129c88aa91d9327a39770605590e`.
- Final test APK SHA-256: `82da3a5713c2880401737193fc18059071d63782be7c9e1c39dabb5595139847`.

Nested Files/Changes detail, scroll/zoom/overlay state, unacknowledged ordinary
creation and physical Mac/Pixel workflows remain follow-up work. The retained pairing intent was subsequently fixed and
covered by the entry-route checkpoint in [ENTRY_ROUTES.md](ENTRY_ROUTES.md);
physical account/URL acceptance is still required.
Signed build **261** is unchanged and predates this work.

## Changes detail retention — 2026-09-30

`ChangesNavigationMemory` retains the selected relative path and collapsed tree
folders above the connection-scoped `ChangesStore`. Returning from a diff also
preserves collapsed folders. The parent screen saves these identifiers in Android
task state, tied to the login incarnation, account/team, canonical Mac/build and
workspace. A different owner, logout or explicit exit clears them; callbacks that
still hold an old owner's state cannot modify the newly bound state.

Restoration waits for admission and fresh file inventory. A new RPC store refetches
the selected diff by path, including when the inventory order changes. If that
path is gone, the existing “File no longer changed” notice appears without fetching
the stale path or substituting its former neighbor. The user can return to the list
and choose a current file. No diff contents or credentials enter saved state.
The encoded contribution is bounded to 49,152 characters, with at most 32 collapsed
folder paths; malformed or oversized saved state is discarded.

Verification:

- **11 focused JVM tests passed**: four navigation-memory cases cover round trip,
  every owner dimension, logout/exit and malformed/bounded state; seven existing
  store cases cover requests, cache and selection behavior. Both APKs built.
- **Four Android 17 / 16 KiB tests passed in 65.370 seconds**. The reconnect case
  replaces the RPC client and changes the inventory order. Three production-screen
  task-restoration cases verify the Changes route, retained detail/collapsed tree,
  and disappearance of the selected file. Each process case verifies a different
  UI PID and an Android-restored bundle. The route and retained-detail cases also
  assert that no terminal replay was dispatched.
- Ignored evidence: `captures/runtime/changes-retention/` (build, JVM XML, runtime
  log and APK hashes). Cold-boot installation initially reached Android before its
  package manager was ready; installation was repeated after boot. App launch then
  reported a timeout, but foreground state and the rendered sign-in screen were
  verified before instrumentation. The four-case run had no test failures. The
  owned emulator was stopped afterward; this is not performance evidence.
- Debug APK SHA-256: `a3bfc235e9c61b27b8a1799b6a31075c9ebf225c99284108e1258d12a587e974`.
- Test APK SHA-256: `a5e4fde14992b5c4611b430d68bddf7694dd9dc5d3afaefd3633ce1558468149`.

This covers selection and folder collapse. Diff scroll/expansion, file-preview
overlays, nested Files state and physical Mac/Pixel acceptance remain open.
Signed build 284 and the Pixel installation are unchanged.

## Terminal Files navigation retention — 2026-09-30

`TerminalFilesMemory` retains the gallery sheet or directly tapped terminal path
above the RPC client. Its owner includes the login incarnation, account/team,
canonical Mac/build, workspace and terminal. It waits for the restored terminal's
connection admission before binding; account/terminal changes and explicit exit
discard the old state. Late callbacks still reference their old state object.

Both entry paths retain nested folders and the current preview page by path. The
gallery also retains Session/In view, search text, icon/list mode, filter, sort and
folded sections. Returning through the nested back stack preserves those controls.
Explicit Done starts a fresh sheet on the next open. The show-missing preference
continues to use its existing preference store.

A new gallery store scans the current terminal first. Saved session routes are
usable only when the scan re-establishes that exact session ID; a different or
absent session returns to the file list without sending old preview requests or
rebinding old paths to the replacement session. Direct taps remain terminal scoped.
Folders reload through the new RPC client, and previews stat/download their content
again. Pager sibling paths retain their navigation order; a missing file uses the
existing preview error instead of selecting a neighbor.

Saved state contains navigation identifiers and labels, not downloaded bytes,
thumbnails, authorization tokens or RPC objects. Its encoded contribution is
bounded to 98,304 characters. If a very large pager exceeds this budget, only the
owning sheet and controls are saved and the nested stack reopens at its root.
Malformed state is discarded. Scroll/zoom/selection inside a rendered document,
interrupted exports and physical Mac/Pixel acceptance remain follow-up work.

Verification:

- **14 focused JVM tests passed**: four memory/ownership/codec cases and ten
  existing gallery-store cases. The memory cases include all owner dimensions,
  current pager path, nested routes, view controls, malformed state and oversized
  pager fallback. Both final APKs built.
- **Six Android 17 / 16 KiB tests passed in 63.312 seconds** on the final APKs.
  Replacing the RPC client preserves a nested preview's second page, reloads its
  bytes, retains its back stack, search and icon mode. A changed session cannot
  trigger saved stat/fetch requests; explicit selection uses the new session ID.
  Production MainActivity restores the Files sheet and second preview after actual
  UI-process death, with a new PID and an Android-restored bundle. Existing scope/
  filter/missing-file, nested-folder and direct-relative-path checks also pass.
- The initial attempt encountered a System UI ANR, a search-result test race and
  a kill before Android retained its saved state. The test now waits for the
  rendered result, and the process fixture waits for system_server's `mHaveState`
  and stopped Activity state instead of a fixed delay. The next run exposed a
  real stale-session read: `collectAsState` briefly retained its old flow value on
  client replacement. Keying the content by its new store fixed it; the final
  session-change test keeps its no-premature-read assertion.
- Ignored evidence: `captures/runtime/files-retention/`, including original failed
  runs, final JVM XML, APK hashes and the inspected final Files-grid screenshot.
  Final preflight and runtime were clear of the startup ANR. These emulator runs
  are not performance evidence. The owned emulator was stopped afterward.
- Debug APK SHA-256: `b1493dba222539b4f66b5d75ba9c5c1e9b3434adf6406f708571caca5a74f706`.
- Test APK SHA-256: `8ac6a73573ca931d782d4f842285863bdc7d407a838fb38b841a37dc1bf1cfc4`.

The Pixel installation and signed build 284 are unchanged.
