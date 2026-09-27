# Android runtime checks

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
as the production screen. Complete task recovery and persisted task drafts remain
separate work; this check does not prove recovery after app process death.


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
The failure footer and retry button remain visible above the keyboard. Persisted
multi-draft storage, recovery after process death and completed-operation recovery
remain open; mounted-composer retry handling does not establish those behaviors.
