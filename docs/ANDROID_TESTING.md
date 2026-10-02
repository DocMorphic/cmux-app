# Android runtime checks

## Opt-in physical terminal acceptance

`LiveNativeTerminalCheck` uses the existing signed-in account and requires exactly
one admitted Iroh Mac. It creates a disposable workspace, sends one `printf`
command only to its new terminal, decodes actual output with `TerminalStreamMirror`,
disconnects/reconnects and verifies the same output without resending input. It
requires distinct underlying event streams to exclude reuse of the same shared
connection through a different lease. It
closes only the workspace identified by that invocation's creation response.
It does not clear credentials, pairings, preferences or existing workspaces.

Run only the named test on the intended physical device after installing its
matching instrumentation APK:

```sh
adb -s DEVICE shell am instrument -w -r \
  -e class io.github.docmorphic.cmuxapp.LiveNativeTerminalCheck \
  -e cmux_live_terminal_fixture true \
  io.github.docmorphic.cmuxapp.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Default suite execution skips this test. `CMUX_LIVE_TERMINAL_REPORT` in logcat
contains booleans, a request count and output mode, never terminal content or
account/host identifiers. Failures give a fixed stage and cleanup state. Creation,
input and close are not automatically retried after an uncertain acknowledgement;
if creation was attempted but closure is unverified, inspect the Mac for a leftover
test workspace before deciding how to clean it up. Do not rerun blindly.

This check establishes native control-RPC, replay decoding and fresh-connection
acceptance. It does not establish UI pixels, Gboard, output-lane streaming, network
switching, notifications or full parity. Even a replay can resume a hibernated
agent in upstream cmux, so this check never replays arbitrary existing terminals.

## Build cadence

Commit feature work as it is completed. Run focused checks for changed behavior;
full Android builds, emulator regression batches and signed APK publication happen
at integration milestones. GitHub Actions skips the build job for draft PRs and
does not build every branch push. Trigger `Android build` manually for a milestone;
ready-for-review PRs build automatically on opening, reopening and updates.
A source commit can therefore be newer than the most recently verified APK.

`scripts/check-handoff-runtime.py --serial DEVICE --install` checks foreground
focus and records exact results and fresh screenshots. Repeatable `--case
Class#method` selects only affected handoff cases. The Markdown fixture checks
painted Mermaid labels and Vega bars as well as DOM assertions. See
`SYNTAX_CHECKPOINT.md` for current results. A system ANR modal, locked screen, or
stale screenshot cannot establish visual acceptance.


The instrumented `NativeFlowTest` runs the production Compose screens and framed
RPC client against an emulator-local TCP peer. It covers workspace filtering,
terminal navigation, keyboard viewport resizing, paste/submit delivery, and
viewport cleanup. A second flow checks multiline drafts, separate terminal
drafts, encrypted persistence before sending, rejection and explicit retry,
and insertion without submission. A third flow intercepts the system document
picker result with local photo/file fixtures, exercises the real import and
image preparation, checks encrypted staging/restoration, and verifies image
acknowledgement plus explicit file retry after a rejected message. It does not
automate a cloud document provider. A fourth flow switches between the composer
and native direct keyboard, verifies keyboard visibility and viewport resizing,
and drives Android InputConnection composition/commit/delete/key APIs. It checks
ordered RPC bytes, rejection followed by explicit resume without replaying queued
input, preserved composer drafts, and invalidation of old input connections.
It does not drive a real CJK keyboard candidate picker or a live terminal app.
Two `RenderGridRenderingTest` checks use the device's ICU tables and production
Canvas painter. They cover combining accents, CJK, flags, emoji modifiers/ZWJ,
ambiguous/narrowed widths, identical pixels for mixed versus separately placed
clusters, palette backgrounds, invisible text and a wide underline cursor.
`terminal-unicode-rendering.png` records the actual rendered result.
A fifth flow selects a raw-byte-only host, sends fragmented UTF-8 and ANSI
output through framed RPC, checks duplicate suppression and alternate-screen
restoration, forces a byte-sequence gap and verifies replay recovery, then
scrolls into local history and back without a viewport change. It also confirms
that parser device-query responses do not become terminal-input RPCs.
Three further flows cover terminal touch and copying: the iOS-style View as Text
sheet, native Android selection handles and selected-text copying, Copy All,
long-press/menu entry, screen-anchored primary scroll isolation, alternate-screen
wheel/click RPCs, and applying an older host's viewport-anchored scroll response.
`terminal-text-selection.png` shows the native selection handles. Geometry and
scroll queue JVM tests also cover coalescing during a delayed acknowledgement,
prefetch cadence, failure without replay, and cancellation when leaving a surface.
Two search/notification flows verify separate workspace and notification queries,
search submission/clearing, group/computer/description metadata, accent/width
matching, retained filters after returning from a terminal, and moved-surface
routing with a read acknowledgement. Already missing destinations are hidden from the feed. A destination that
disappears between display and opening reports an error and is not marked read. `notification-search.png` and `notification-unavailable.png`
record these states. JVM tests additionally check late edits after cancellation,
128-scalar/512-byte input bounds, duplicate/nullable feed fields, ambiguous
surface ownership, and row headline/preview derivation.
Two alert-navigation flows switch from one saved fixture Mac to another, verify
exact workspace/terminal RPC targets, reject a superseded handshake while its
initial feed is still pending, reopen the same route after consumption,
and reject a forgotten computer without sending a read or terminal request.
The routing regressions also cover session capture during recomposition: old
cleanup must not close the new client, a route must not execute twice when a
handshake finishes, and keyboard/focus changes after RPC must run on the main
thread.
Four `NativeNotificationDeliveryTest` checks exercise real Keystore encryption,
NotificationManager and PendingIntent identity (including colliding string hashes
and identical IDs across Macs), read/forget cleanup, late-feed suppression,
intent validation, sibling-installation storage, host-identity mismatch rejection,
and foreground-service start/stop with immediate connection status.
The persisted destination is a random opaque ID; Android intents contain no
pairing route, credential, workspace or terminal ID. JVM ledger checks cover
per-Mac baselines, persistence reconstruction, unacknowledged post retry, bounded
history/route eviction and retained moved-surface provenance. A socket test
exercises the actual event-driven feed worker and disconnect handling.
These do not yet prove delivery during physical-device sleep, boot recovery,
notification-shade taps through Activity process death, or real tailnet reconnects.
Two further feed flows check Today/Yesterday sections, collapsed and expanded
same-pane history, read/unread long-press actions, leading-edge swipe, unread
filtering, confirmed/cancelled bulk read, duplicate Mac-local IDs on two computers,
computer search, exact cross-Mac terminal targets and query restoration. The bulk
action includes retained notifications hidden by search, within the selected computer
scope (or all saved computers when All Computers is selected). Screenshots include
`notification-history-collapsed.png`, `notification-history-unread.png`, and
`notification-multiple-macs.png`. JVM tests cover the 2,000-item aggregate cap,
300-item projection window, computer selection and live-destination filtering before
the global cap, moved/ambiguous destinations, sibling-installation isolation,
DST/day boundaries, two-hour grouping, stable expansion
anchors, revision-based read-state reconciliation, offline snapshots, partial bulk
failures and a replacement Mac at the same route. Feed identities are cached and
bound to pairing code, device ID and cmux instance tag.
A further two-Mac flow checks the computer picker, scoped unread badges, hidden
search results, exact bulk-read scope, saved selection, and returning to All
Computers. `notification-computer-scope.png` records the selected-Mac state.
A coordinator regression ensures a captured scope for a forgotten Mac cannot
widen to all computers when a bulk action is confirmed.
`NativeLifecycleTest` uses a debug-only Activity host around the production
NativeScreen. It disconnects the fixture Mac, stops/resumes and recreates the real
Activity, and checks retained offline rows, tab, committed query, unread filter
and expanded history, including the selected computer. A second case loads
beyond the initial 300-row window,
disconnects, recreates the Activity, and checks that the additional rows remain
available. `notification-rotation-offline.png` records the offline state.
This is Activity-recreation coverage, not a force-stop/process-death test. The
fixture Activity and injected connector are absent from the release APK.
Revision tests distinguish a complete list from read-acknowledgement watermarks,
reject responses below the revision known when their request began, preserve
floors through pause and resume, bound retries for stale hosts, and cancel a
pending delayed refresh. An event arriving during a request permits valid
in-flight progress and schedules a trailing fetch for the newer revision. The
background worker socket test sends an invalidation followed by a stale list and
checks that only the subsequent fresh list is delivered.
The peer uses synthetic data and never connects to a real cmux account.
This check does not prove account sign-in, Tailscale routing, or compatibility
with the live Mac; those remain in [PIXEL_INSTALL.md](PIXEL_INSTALL.md).

A primary-navigation flow checks the two icon tabs and selected semantics,
notification badge, detached Search control, automatic search focus and visible system IME, cancel and
submit behavior, app Back cancellation, separate committed tab queries, the
floating New Task entry, and Settings through the cmux logo. The task button is
disabled without a live client and stays absent while Search is presented.
`primary-navigation-workspaces.png`, `primary-navigation-notifications.png`, and
`primary-navigation-search.png` record these states. Search uses Android IME and
focus handling; the round cancel control clears the current scope, while submit
and result navigation retain its committed filter. The editor is not reopened
automatically after Activity recreation.

An All Computers workspace flow renders identical remote workspace IDs from two
Macs, searches by computer, renames a background Mac's workspace, opens its exact
terminal, preserves the committed filter on return, and switches between a named
Mac and All Computers. It verifies the other Mac receives neither the mutation
nor a terminal request, and that opening a workspace does not mark notifications
read. `workspaces-all-computers.png` records the combined list. JVM checks cover
owner-qualified row/group keys, local expansion, collapsed-group search, unread
filtering, post-rejection refresh, stale notification revisions, forgotten owners,
and blocking retained-row actions until a reconnecting host's identity is verified.

Compose checks explicitly use `StandardTestDispatcher`, following the
[Android testing guidance](https://developer.android.com/blog/posts/whats-new-in-the-jetpack-compose-december-release).
This queues resumptions from the real socket worker onto the test scheduler;
the legacy unconfined dispatcher could run ripple animations on that worker.

Debug builds use the `io.github.docmorphic.cmuxapp.debug` package. Their encrypted
credentials and preferences are separate from the release app. The test clears
only debug credentials and drafts when it finishes.

With a running Android emulator and JDK 17:

```sh
./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest
```

On a Mac with limited RAM, build before starting the emulator:

```sh
./gradlew --no-daemon --max-workers=1 :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
adb shell am force-stop io.github.docmorphic.cmuxapp.debug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am start -W -n io.github.docmorphic.cmuxapp.debug/io.github.docmorphic.cmuxapp.MainActivity
adb shell am instrument -w -r -e class io.github.docmorphic.cmuxapp.NativeFlowTest,io.github.docmorphic.cmuxapp.NativeWorkspaceDragTest,io.github.docmorphic.cmuxapp.RenderGridRenderingTest,io.github.docmorphic.cmuxapp.NativeNotificationDeliveryTest,io.github.docmorphic.cmuxapp.NativeLifecycleTest io.github.docmorphic.cmuxapp.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Check the instrumentation output for `OK (28 tests)`; the `adb` exit code alone
does not distinguish failed tests. Screenshots are saved to the debug app's
external files directory and can be retrieved after the test:

```sh
adb pull /sdcard/Android/data/io.github.docmorphic.cmuxapp.debug/files/screenshots captures/emulator
```

CI runs the JVM tests, builds the app and instrumentation APKs, and verifies the
release signature. Emulator tests are run separately; compilation is not a
runtime pass. Espresso 3.7.0 is explicit because earlier transitive versions use
an input API removed in Android 17; see the [AndroidX Test release notes](https://developer.android.com/jetpack/androidx/releases/test).

The flow was exercised on an ARM64 Android 17 (API 37) emulator with the Pixel
6a display profile. Its cold boot initially hit System UI and Google Play
services startup failures; the completed run used a recovered, idle emulator.
APK replacement after snapshot restore also produced a focus timeout in the
restored MainActivity while Android's PackageUpdateActivity was active. Before
instrumentation, wait for the app's MainActivity (not PackageUpdateActivity) to
resume and verify that no system dialog is covering it. Do not count runs with an ANR dialog, process crash, timeout, or `FAILURES!!!`
as passing. Inspect the saved screenshots as well as the assertions.


## Captured Vim regression

The JVM suite includes `terminal/vim-session.json`, captured from a local Vim
9.1 process attached to a real PTY. It contains opening, inserting text into a
Unicode document, and exiting. The test feeds it to the production native parser
in seven-byte pieces and checks edited text, the alternate screen and restoration
of the primary screen. Recreate the fixture on a Mac with `/usr/bin/vim`:

```sh
python3 scripts/capture-vim-fixture.py
```

The script creates and removes its own temporary directory and disables Vim
configuration, swap and history files. This checks genuine Vim output but does
not verify live input latency, cmux authentication or a physical Pixel connection.


## Workspace hierarchy and move reference

Recreate the Swift reference fixture using a local official cmux checkout that
contains revision `4d3385b9d7ac80a9bbdf5c886cc276849b1e4fa0`:

```sh
python3 scripts/generate-workspace-parity.py /path/to/cmux
./gradlew :app:testDebugUnitTest --tests '*NativeWorkspace*'
```

The generator uses `git show` to extract eight unmodified algorithm files at the
pinned revision into a temporary directory, compiles them with Swift 6 and small
DTO adapters, then records their SHA-256 hashes alongside the expected results.
It neither changes the upstream checkout nor runs any upstream build scripts.
The compressed JSON is committed; Linux CI consumes it without Swift. The DTO
adapter includes the upstream unread-state implementation and optional wire counts.

The reference covers 46 snapshots, 2,434 rendered source/destination slots and
20,360 direct proposals. Separate loopback tests verify move serialization,
rejection/retry, owner/window guards, and optimistic reconciliation. Runtime
checks in `NativeWorkspaceDragTest` and the hierarchy flow in `NativeFlowTest`
exercise actual touch input, accessibility actions, grouping and RPC dispatch.


Hierarchy validation (2026-09-27): 105 JVM tests passed, including the reference
comparisons and accessible whole-group step behavior. Three focused emulator
checks pass for long-held auto-scroll across recycled rows, accessible movement,
and the production hierarchy/anchor/collapse/RPC flow. Three existing flows also
pass for filtering/terminal entry, scoped search and multi-computer workspaces.
The earlier interrupted held-pointer run and two failed gesture runs are excluded;
they exposed a touch-scroll conflict fixed by disabling LazyColumn's touch
scrolling only while a row is held. Programmatic edge scrolling remains active.
The inspected screenshot is `captures/emulator/screenshots/workspace-hierarchy-reordered.png`.
These fixture peers do not prove a real cmux Mac or Pixel session.


Unread badge validation (2026-09-27): 108 JVM tests pass. Four focused Android 17
emulator tests passed together (`OK (4 tests)`, 80.235 seconds): unread counts
and read/unread RPC refresh; hierarchy/anchor/collapse/reordering; held drag
auto-scroll; and accessible move/disabled actions. The unread flow checks an
expanded anchor count of 2, collapsed total of 5, legacy unknown count semantics,
root count of 12, and group totals after the child is marked read and unread.
Search flattens members with their individual counts. Expanded and collapsed
screenshots were inspected at `captures/emulator/screenshots/workspace-counts-expanded.png`
and `workspace-counts-collapsed.png`. The full 29-case instrumentation suite was
not rerun in this batch. These results use a synthetic RPC peer, not the Pixel
or a real cmux host. The same flow also passed at 150% text size (62.47 seconds) and both screenshots
were reviewed; other accessibility sizes remain separate QA items.

Recreate the pinned Lucide Android vectors with:

```sh
python3 scripts/import-workspace-icons.py
```

The script fetches only the pinned SVG files and license, records hashes in
`third_party/lucide/workspace-icons/PROVENANCE.json`, and preserves the complete
license in the app. Unknown custom SF Symbol names currently use a folder
fallback; vector shape equivalence is not exact Apple glyph parity.

## Task model and effort checks

Rebuild the provider-command reference from the pinned official checkout:

```sh
python3 scripts/generate-task-command-parity.py /path/to/cmux
./gradlew :app:testDebugUnitTest --tests '*TaskModelsTest' --tests '*TaskCommandTest' --tests '*MobileRpcClientTest'
```

The generator compiles the unmodified `MobileTaskAgentProvider.swift`, records
its source SHA-256, and compares 695 command rewrites. The JVM suite checks host
and catalog decoding, default-only host metadata, discovery completion order,
cache isolation, forgetting/sign-out and stale generations, explicit selections
across delisting, effort reconciliation, timeouts and cancellation. Framed RPC
coverage verifies that error codes survive decoding and do not close a usable
connection; permanent errors stop discovery while transient errors retry.

`NativeTaskModelsTest` uses the real Compose composer and RPC client with a
local fixture Mac and injected catalog. It covers explicit model/effort command
parameters, frozen open menus during host replacement, Default without a model
flag, older hosts using the catalog without invented efforts, and plain Shell.
The test harness includes the production screen's status/navigation/IME insets.
No installed agent CLI or native Pixel connection is exercised by these tests.

Task controls validation (2026-09-27): all 121 JVM tests pass. The Android 17
Pixel-profile emulator passed six focused checks together (`OK (6 tests)`,
175.423 seconds): the five task controls flows plus primary navigation/search/
composer entry. The suite now contains 34 instrumentation checks; it was not
rerun in full. The test catalog is injected, and the Mac is a loopback peer.
The production catalog URL was separately fetched and returned schema version 1;
that read-only check does not prove an installed Mac agent or native Pixel pairing.

The timeout check verifies the actual composer coroutine returns from Creating
to an editable retained prompt after a request timeout, sends no automatic retry,
and reuses the operation ID only after an explicit unchanged retry. Task controls
and prompt screenshots were reviewed at `captures/emulator/screenshots/task-model-options.png`
and `task-model-effort.png`. The harness paints the same dark system-bar background
as the production screen. At `141d0f6`, complete task recovery and persisted drafts remained separate work;
that check alone does not prove recovery after app process death.


## Task submission and navigation checks

The task submission batch passes all 129 JVM tests. Eight focused Android 17
emulator checks have passing results across runs: the seven `NativeTaskModelsTest`
cases passed in the initial mixed run, and
`NativeFlowTest#taskCreationOpensExactTerminalAndPreservesExistingWorkspaces`
passed after correcting its fixture setup and UI label expectations (`OK (1 test)`,
14.776 seconds). The initial mixed run failed only the new navigation test before
creation because its helper expected two notification fixtures; subsequent test
assertions were corrected for the terminal header and the existing group anchor.
The full 37-case instrumentation suite was not rerun.

New checks retain the prompt and operation ID after an incomplete creation response,
reuse the ID after whitespace-only edits, rotate it for a different effective task,
and reject late success after replacing the RPC client. The navigation check follows
`workspace.create` through replay of the exact returned terminal, visible terminal
output, and return to all three workspaces, including the existing group header.
The fixture now returns a real created-workspace listing and retains it in later
list refreshes. This remains synthetic-peer testing, not a Mac CLI launch.

Screenshots `task-create-rejected.png`, `task-created-terminal.png` and
`task-created-workspaces.png` were reviewed under `captures/emulator/screenshots`.
The failure footer and retry button remain visible above the keyboard. At `cb1d96f`, persisted multi-draft storage, process-death recovery and completed-
operation recovery remained open; mounted-composer retries alone did not establish
those behaviors.

## Encrypted task drafts and process recovery

The saved-draft batch passes 140 JVM tests. A focused Android 17 emulator run
passed 16 checks together (`OK (16 tests)`, 254.928 seconds): all five
`NativeTaskDraftsTest` cases, all seven `NativeTaskModelsTest` cases, the three
task/navigation flows, and composer background/Activity recreation. The suite
now contains 45 unique instrumentation cases; it was not rerun in full.

The initial 15-check run passed 12 cases. Its failures exposed stale model
metadata crossing an agent change and assertions made before asynchronous UI
updates. Provider-scoped reconciliation and waits for the relevant discovery/UI
state fixed those failures; the 16-check rerun includes those same cases.

Checks cover encrypted cold reload, independent drafts and deletion, raw Unicode
prompts, explicit/default model and effort snapshots, save-before-send failures,
unsupported hosts, stale editor callbacks, sign-out/new-account writes, and old
token refreshes. The cross-Mac flow saves drafts on two fixture Macs, reconnects
to the first draft's Mac, sends exactly one creation there, and retains the other
draft. Activity recreation retains the open composer, draft ID, prompt and path.

Process recovery was tested in two separate instrumentation invocations, with
the debug app force-stopped between them. Seed passed in process 12735
(`OK (1 test)`, 13.714 seconds); verification passed in process 12815
(`OK (1 test)`, 7.600 seconds). The first process durably saves the request before
a simulated timeout. The second decrypts it, restores prompt/model/effort,
reuses the exact operation ID and command, and removes the successful draft.
This case is skipped without an explicit phase argument; run both phases in
order without clearing app data between them:

```sh
adb shell am instrument -w -r -e draftPhase seed \
  -e class io.github.docmorphic.cmuxapp.NativeTaskDraftProcessTest \
  io.github.docmorphic.cmuxapp.debug.test/androidx.test.runner.AndroidJUnitRunner
adb shell am force-stop io.github.docmorphic.cmuxapp.debug
adb shell am instrument -w -r -e draftPhase verify \
  -e class io.github.docmorphic.cmuxapp.NativeTaskDraftProcessTest \
  io.github.docmorphic.cmuxapp.debug.test/androidx.test.runner.AndroidJUnitRunner
```

These tests use a loopback fixture Mac and injected model catalog. They establish
Android persistence and routing behavior, not physical Pixel pairing or an
installed Mac agent launch. Completed-operation recovery, offline editing and
the remaining task-composer parity work are tracked in `PARITY.md`.

After correcting the Drafts sheet's status/navigation icon contrast in system
light mode, the final source rebuilt successfully with all 140 JVM tests passing.
The affected save/switch/resume/delete flow passed again (`OK (1 test)`, 30.871
seconds). Draft list, restored composer, leave dialog and Activity-recreated
composer screenshots were inspected in `captures/emulator/screenshots`.

## Completed-operation recovery

All 142 JVM tests pass. Seven focused Android 17 emulator checks pass together
(`OK (7 tests)`, 78.213 seconds): six recovery cases and the production screen's
refresh/reconcile/terminal navigation flow. The seven existing model/submission
checks also passed in the initial mixed run. That initial run passed 12 of 13;
its new production-screen assertion ran before the asynchronous encrypted save
finished. Waiting for an enabled recovery action fixed the assertion. The final
seven-case run includes that case, final banner styling and a new delayed-model
discovery regression. The full 52-case instrumentation suite was not rerun.

Recovery coverage includes exact old-request replay after a workspace refresh,
success cleanup without deleting another draft, repeated missing responses,
cancel/confirm Start Again with a fresh identity, effective edits and reversion,
save/refresh errors, replaced clients and late provider metadata. The real RPC
fixture verifies normalized `already_completed` errors, authoritative workspace
and group refresh, navigation to the exact returned terminal, and preservation
of the refreshed workspace inventory. The original request is retained while
recovery is active; discovered default effort cannot silently detach the gate.

The process test's completed-recovery mode passed in two separate processes:
seed PID 12416 (`OK (1 test)`, 12.900 seconds), then force-stop and verify PID
12493 (`OK (1 test)`, 5.896 seconds). It verifies the encrypted original request
and retired normal identity survive process death, Create Task stays disabled,
Start Again is unavailable until reconciliation, and explicit refresh reuses the
original ID/command before successful cleanup. Run the two phases with:

```sh
adb shell am instrument -w -r -e draftPhase seed -e completedRecovery true \
  -e class io.github.docmorphic.cmuxapp.NativeTaskDraftProcessTest \
  io.github.docmorphic.cmuxapp.debug.test/androidx.test.runner.AndroidJUnitRunner
adb shell am force-stop io.github.docmorphic.cmuxapp.debug
adb shell am instrument -w -r -e draftPhase verify -e completedRecovery true \
  -e class io.github.docmorphic.cmuxapp.NativeTaskDraftProcessTest \
  io.github.docmorphic.cmuxapp.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Final `task-recovery-still-missing.png`, `task-recovery-confirm.png` and
`task-recovered-terminal.png` were inspected under `captures/emulator/screenshots`.
These are fixture-Mac/emulator checks. Physical Pixel/Mac acceptance remains open.


## Editable task templates and remembered defaults

All 151 JVM tests pass. Nine template checks cover editable protected built-ins,
custom scripts and exact prompt/environment handling, emoji and JSON restoration,
per-Mac history limits and Unicode path identity, selected/recent workspace and
focused-terminal directory preference, typed-directory preservation, actual-command
provider detection, model reset, plain shells, invalid storage and old-account fences.

Four `NativeTaskTemplatesTest` flows exercise real encrypted storage and Compose
controls: add a custom script/emoji/default directory and submit exact RPC data;
rename a protected built-in and delete a custom shell; retain a typed path and
selection through composer reconstruction; and retain a failed-save form while
rejecting writes from a signed-out account. The cross-Mac task flow also verifies
that the next task selects the successful Mac and agent. A real Activity recreation
check retains the unfinished template form and saves exactly one entry afterward.

The initial 16-case focused run passed (353.771 seconds), including seven existing
model/submission checks, draft switching and two completed-operation regressions.
Screenshots exposed a keyboard/window-inset problem in the original separate
Dialog editor, which was then moved into the main inset-aware window. A subsequent
run completed the four template checks and cross-Mac flow, but its recreation test
was interrupted while scrolling to the next input during IME resize; that run is
not counted as a full pass. The recreation test then passed, but the same
scroll-before-focus issue interrupted a template case in that mixed run. Both
form test helpers now request input focus directly instead of scrolling a still
focused previous input into competing positions during IME resize. The final
six-case run passed together (`OK (6 tests)`, 86.948 seconds): all four template
flows, unfinished-form Activity recreation, and remembered Mac/agent selection.
Template form/list, restored draft and remembered-selection screenshots were
visually inspected; the header and system/keyboard insets remain visible.

Custom-template completed-task recovery passes in two separate app processes
(12643 → 12729; seed 13.129 seconds, verify 5.492 seconds). The encrypted template,
raw command, directory, model/effort and original operation ID survive the restart.
Normal creation stays disabled until explicit reconciliation, which uses the
original request and removes the recovered draft after success. Reproduce with
`NativeTaskDraftProcessTest` and both `-e customTemplate true` and
`-e completedRecovery true`, running `-e draftPhase seed`, force-stopping only the
debug package, then running `-e draftPhase verify` without clearing its data.

The Android suite now contains 57 cases; it was not rerun in full for this batch.
These tests use loopback fixture Macs and an Android 17 Pixel-profile emulator.
Physical Pixel/Mac validation and installed agent CLI execution remain pending.

## Task destinations, folder browsing and workspace options

All 159 JVM checks pass. Eight new cases cover strict folder DTOs and pagination,
byte ordering and large counts, stale requests and page changes, bounded search
coverage, 115 reference rankings, grapheme-safe titles and explicit group/name
parameters, missing-group inventory states, and durable Mac reassignment with
origin-bound retry/recovery anchors.

Regenerate the ranking fixture with Swift 6 and the pinned upstream repository:

```sh
python3 scripts/generate-task-directory-parity.py /path/to/cmux
```

The generator runs the unmodified upstream `MobileTaskDirectorySuggestion.swift`
at `4d3385b9d7ac80a9bbdf5c886cc276849b1e4fa0`. The fixture records that source hash;
it covers selected Unicode, recency, source, fuzzy and exact-path cases, not every
possible Unicode normalization difference between platforms.

`NativeTaskDestinationsTest` exercises the real framed RPC client, encrypted store
and task screens: pagination and exact selected path/name/group submission; stale
search cancellation with local suggestions and partial-coverage copy; permission
and unsupported-method errors with retry; restored/missing group resolution; and
a group disappearing during the durable save. A production `NativeFlowTest` case
switches two saved Macs whose group IDs collide, retains typed fields and submits
only to the selected Mac after explicitly choosing its group.

Diagnostic runs are excluded from passing evidence. The first four folder checks
failed in setup because a window mutation ran off Android's UI thread; setup and
failure cleanup were corrected. A later run passed the concurrent group-removal
check but stalled in Compose's `performScrollToNode`; a captured thread dump located
the wait there. That run was stopped, and the deterministic pagination check now
uses explicit lazy-list indices before asserting the exact folder and RPC path.

The final six-case destination/production-flow run passes together (`OK (6 tests)`,
158.872 seconds) on the Android 17 Pixel 6a emulator profile. Screenshots were
reviewed for the name/options cards, chosen folder, permission failure and search
with the keyboard visible. The suite now contains 63 cases; it was not rerun in
full for this batch. `NativeTaskDraftProcessTest` also accepts `-e taskDestination
true` with its existing seed/verify, custom-template and completed-recovery modes.
Physical Pixel/Mac verification remains required.


## Terminal momentum

The initial feature commit passed 163 JVM checks; the integration batch now passes
172. `TerminalScrollMotionTest` exercises a frame clock with actual decay; the
queue regression verifies that explicit input removes pending wheel delivery.
All three `NativeTerminalMomentumTest` cases now pass for real touch-release
inertia, the line-path deadline, new-touch/input cancellation and surface/mode
replacement. Existing alternate-screen touch, viewport-anchored scrolling, raw-VT
history and direct-keyboard production flows also pass in the integration batch.
The feature is included in signed build 138.


## Task attachments

Focused JVM invocation (34 passed):

```sh
./gradlew :app:testDebugUnitTest --tests '*TaskAttachmentsTest' --tests '*TaskDraftsTest' --tests '*ComposerDeliveryTest' --tests '*TaskSubmissionTest' :app:compileDebugAndroidTestKotlin
```

The nine new JVM checks cover size/count limits, empty files, attachment-only draft
restoration, retention at the 20-draft limit, ordered retry identity, exact prompt
and environment fields, multi-chunk retry, reconciliation without re-upload,
capability/connection changes and a new operation after explicit Start Again.

All four `NativeTaskAttachmentsTest` cases now pass: actual document-picker
callbacks and image preparation with upload/retry; encrypted payload restoration
and stale-session fencing after sign-out; preview/external-viewer cleanup/removal;
and prompt/keyboard-dock layout. They are part of the combined integration batch
below. No APK is published per feature commit; signed build 138 combines these
features with terminal momentum and the composer UI.


## Task composer integration milestone

All 172 JVM cases pass; debug and instrumentation APKs assemble. The new gesture
and attachment batch passes seven cases (63.230 s). A 38-case regression run has
36 passes and two obsolete-UI assertion failures; both are resolved by the final
12-case run (132.686 s), which passes all model/effort checks, all four attachment/
layout checks, and the production navigation/search flow. There are 45 distinct
runtime cases with passing evidence across these runs, not a full 70-case run.

The new attachment test intercepts the real document-picker result, stages a
2400×1200 photo down to 2048×1024, adds an empty file, checks encrypted metadata and
payloads, rejects creation, then retries with unchanged task/upload identities.
Preview testing now intercepts ACTION_VIEW, reads the FileProvider URI, checks the
read grant and confirms that returning removes the exported file. The layout test
checks the prompt's available height and submit/options visibility above the IME.

Initial diagnostic logs are retained separately: the regression run's obsolete
assertions and two stopped performScrollTo loops are not presented as passing
runs. The controlled coroutine dispatcher requires an explicit clock advance
after a scroll action; TaskComposerTestActions uses one bounded action followed
by visible-button clicking. The final 12-case run is clean.


Attachment recovery also passes in two distinct instrumentation processes
(12465 → 12653; seed 18.928 s, verify 6.029 s). The checks preserve encrypted file
bytes/identity, custom template, directory/name/group, model/effort and completed
operation identity. Explicit reconciliation opens the recovered task using its
original operation ID and sends zero attachment uploads in the verification
process. This is fixture-Mac evidence, not physical Pixel/Mac acceptance.


## Signed integration build 138 (2026-09-28)

[GitHub run 36353416757](https://github.com/DocMorphic/cmux-app/actions/runs/36353416757)
succeeded at `86678870ba2a0837d5bc559b6a9da5e7b23c510a`. The signed APK includes
task attachments, the composer canvas/dock and terminal momentum. Package/version,
unchanged signing certificate, feature classes, absence of instrumentation fixtures,
license assets and agent-image pixel equality were verified. The release FileProvider
is non-exported and exposes only its task-preview cache directory with per-URI grants.

The APK served to the phone matches SHA-256
`93b43169fba7a6d8a4549a7127fa0d16461523068cee4e3bb8a6bb3ce55357df`
(9052151 bytes). The download was fetched back over HTTP and matched the
verified artifact. Detailed local receipt: `captures/releases/8667887-verification.json`.
The full app parity goal and physical Pixel/Mac acceptance remain open.

## Account process-death harness

`NativeAccountProcessDeathTest` launches the debug-only, non-exported
`NativeAccountProcessTestActivity` in `:account_restore_test`. It refuses physical
devices, uses a unique encrypted fixture store, and accepts only a validated
loopback port. The instrumentation process hosts MockWebServer and survives while
the test sends SIGKILL to the distinct account UI process. A PID marker and
ActivityManager observations verify death and a different PID after relaunch.

The child uses the production account team controller, profile cache, deletion
controller/client and Compose account components. Initial fake credentials are
seeded once; relaunch cannot recreate a signed-out session. The test parent never
reads or modifies the child's SharedPreferences while it is live. No production
authentication/deletion endpoint, personal store or native Mac connection is used.

Cases cover cold account restoration and revalidation, process death during an
unanswered DELETE, and death after the completed receipt is durable but before
its UI handles sign-out. For that last case only, a fixture flag defers outcome
presentation until relaunch; the deletion request and receipt are real production
code. These are cold-launch process-death checks, not Android saved-task bundle,
force-stop/Doze delivery or full NativeScreen acceptance.

Run after installing the debug and Android-test APKs on the existing emulator:

```sh
adb -s emulator-5554 shell am instrument -w -r \
  -e class io.github.docmorphic.cmuxapp.NativeAccountProcessDeathTest \
  io.github.docmorphic.cmuxapp.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Status code `2` records process-transition evidence; test starts/results still use
`1`/`0`. Require the final `OK (3 tests)` and inspect all status codes for failures.
