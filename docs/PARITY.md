# Android parity tracker

Reference: [cmux iOS](https://cmux.com/ios) and
[`manaflow-ai/cmux`](https://github.com/manaflow-ai/cmux) at
`4c5272e9153eca2033c9f40ac749f0c3a5bcb291` (2026-09-27), refreshed on 2026-09-28.
The upstream code changes frequently, so update this pin before a release.
The iOS implementation lives mainly in `ios/cmuxPackage`,
`Packages/iOS`, and `Packages/Shared/CMUXMobileCore`.

The target is the real iOS companion workflow on a Pixel 6a (Android 17).
A feature is complete only after the Android behavior is implemented, covered
by a focused automated check where practical, and exercised against the Mac.
UI resemblance alone does not count.

## Signed milestone 474 (2026-10-03)

The four Computers features below are included in
[signed build 474](PIXEL_INSTALL.md), source c45920e. Full app/Ghostty JVM suites,
debug/test/release assembly, helper/update-policy tests and CI packaging gates
passed. Independent checks verified the downloaded artifact and stable signer.
The existing 16 KB emulator upgraded 468 → 474 in place and cold-launched cleanly.
Its baseline was signed out; physical and authenticated migration checks remain open.

A scoped [Mac compatibility audit](MAC_COMPATIBILITY_AUDIT.md) found that iOS row
warnings depend on remote/cached policy and authenticated connection admission,
not just cosmetic labels. Android's own version cannot be passed blindly into an
iOS marketing-version tier. The audit records the next implementation work and
does not claim that the missing policy has been ported.

## Local computer visibility (2026-10-03)

Computers and the saved reconnect screen now expose exact Mac/build visibility
switches and hidden rows. Hiding retains the pairing and remote account binding,
retires that computer's active/pending route and removes its workspaces and feed
snapshots. Stable/Nightly siblings and other owners remain independent. Unhide
works offline; discovery and late handshakes cannot silently revive hidden rows.
Paused feeds explicitly prune hidden cached snapshots without dialing survivors.

**33 JVM tests and five distinct Android cases passed** across the recorded runs.
The final two runtime cases passed in 14.532s; earlier UI test timing failures and
their corrections are preserved in the evidence. Both production Android screen
captures were visually checked. [Source and verification](COMPUTER_VISIBILITY.md).
Included in signed build 474. Physical acceptance, configured push presentation,
full cloud-backup/coalescing behavior, warnings, refresh and visual parity remain.

## Combined Computers management (2026-10-03)

Settings, Workspaces and the saved reconnect screen now open one dedicated
Computers destination. Native Mac method groups and existing SSH hosts appear
together, with Pair Mac/Add SSH actions and Done. Mac rows open details without
switching the active workspace; legacy TCP requires an explicit Reconnect.
The existing SSH editor, keys, shells, workspaces and confirmed removal are reused.

**Four Android cases passed in 26.817 seconds**, including a production navigation
journey against the local RPC fixture and the existing independent Details-button
regression. The combined-screen screenshot was reviewed.
[Source, evidence and remaining scope](COMPUTERS_MANAGEMENT.md).
Included in signed build 474. Hidden rows are covered above; warnings, refresh/layout parity,
nested destination restoration and physical acceptance remain open.

## Saved computers through discovery outages (2026-10-03)

The reconnect picker retains saved Macs during loading/errors/empty discovery,
adds only unambiguous new device/build records, and avoids duplicate discovery
rows. It keeps the list visible during an attempt with Cancel, spinner and failure
feedback. Saved rows use their original pairing and recheck current account and
pairing authority on tap, before dialing, and inside the final save transaction.
A late handshake cannot recreate a removed pairing; successful verification
retires the attempt guard so enriched records can reconnect later.

**47 JVM and seven distinct Android cases passed** across the recorded runs,
including real local RPC/terminal reconnect and removal during a pending dial.
The final two-case follow-up verified replay after legacy build-tag enrichment;
an earlier invalid ownership-change fixture timed out and is documented.
The Android picker screenshot was visually checked.
[Source comparison, evidence and limits](RECONNECT_COMPUTERS.md).
Included in signed build 474. Local visibility is covered above. Full iOS
disconnected/Computers layout, setup help, version warnings and physical acceptance remain
open.

## Computer method sections and endpoint captions (2026-10-03)

Saved computer rows now appear once under their configured Iroh/Tailscale/Direct
method, ordered by available last-seen history. Exact device/build endpoint
captions distinguish similarly named Macs; confirmed-offline later duplicates
carry Older pairing. Reconnect rows reuse captions and any exact saved duplicate
marker. Connection-setting read failures are shown explicitly. The projection
uses existing scoped settings and routes without changing connection authority.

**23 JVM and seven Android cases passed**, and the production-row screenshot was
visually checked. [Source comparison and evidence](COMPUTER_LIST.md). Included in
signed build 474. The management follow-up above integrates navigation and SSH;
full layout, version warnings and physical acceptance remain open; saved/offline reconnect
reconciliation is covered above. This does not complete the full Computers screen.

## Computer connection versus presence (2026-10-03)

Saved computer rows now distinguish this phone's Connected/Reconnecting/Not
connected state from the Mac's separate heartbeat. Reconnect rows show online or
relative last-seen state and omit cached workspace counts; both use build badges
and accessible status indicators. Verified host handshakes save display history
outside pairing records, inside the existing encrypted account state, with
current-login/route checks and Forget pruning.

**46 JVM and seven Android cases passed**, including host identity verification,
real encrypted history reload, sign-out rejection and existing switch/routing
regressions. Two component screenshots were visually checked. [Source and evidence](COMPUTER_PRESENCE_ROWS.md).
Included in signed build 468. Route captions/grouping/older-pairing labels are
covered by the follow-up above. Live Pixel/Mac acceptance, full Computers layout
and the remaining presence consumers are still open.

## Computer build subtitles and presence metadata (2026-10-03)

Picker rows now show the iOS Stable/Nightly/RC/Staging/DEV build labels. A new
foreground subscription reads the official team-scoped presence stream for bundle
metadata, with snapshot-first parsing, bounded buffering, retry/backoff, token
refresh and cancellation across account/team/background changes. Saved tags are
the fallback; legacy untagged rows use presence only when a single instance is
unambiguous. This data is display-only and cannot authorize a pairing or route.
Add Computer uses the iOS label, separator and per-opening availability.

**15 JVM and nine distinct Android cases passed** across the recorded runs; the
clean component screenshot was visually checked. An older integration assertion
was corrected to wait for the asynchronous Mac switch, and an emulator System UI
dialog was cleared before visual recapture. [Source, implementation and verification scope](COMPUTER_BUILD_LABELS.md).
Included in signed build 468. Production service/Pixel acceptance and the other
presence consumers (route updates, workspace announcements and push recovery)
remain unverified or unaudited; online/last-seen row display is covered above.
This does not close full presence parity.

## Stable open computer menu (2026-10-03)

The computer picker now keeps its rows, checkmarks and actions fixed until it
closes, matching the scoped iOS menu review. Toolbar progress stays live and the
next opening uses fresh state. Taps recheck account scope, current connector
permission and exact saved pairing; forgotten/replaced routes cannot be selected
from an old row. Four JVM and eight Android checks passed, including existing
switch, workspace and notification flows. [Source and evidence](MAC_SWITCH_RECOVERY.md#stable-open-computer-menu--2026-10-03).

Included in signed build 468. Physical acceptance and broader source/visual parity remain open. Build labels
and Add Computer mapping are covered by the follow-up above.

## Pending computer picker and cancellation (2026-10-03)

The toolbar picker now retains the current workspace/notification filter until
the new Mac is connected, with progress and an accessible pending target. Choosing
All Computers cancels an in-flight switch and restores the authorized original
Mac while keeping the All filter. Late successful callbacks cannot replace that
connection. This follows the reviewed iOS `WorkspaceListView.swift` selection and
cancellation paths. The route baseline alone does not establish an iOS promise
to reopen an exact prior terminal pane; that is no longer treated as an assumed
missing requirement.

**12 JVM and 14 Android cases passed**. The Android checks include deliberate
late dial completion, terminal routing, and existing workspace/notification
picker regressions. [Source comparison and evidence](MAC_SWITCH_RECOVERY.md#pending-picker-selection-and-cancellation--2026-10-03).
Physical acceptance and broader source/visual parity remain open. Included in
signed build 463.

## Launch pairing and saved reconnect (2026-10-03)

Launch links now hold the screen's saved foreground/feed reconnect until their
pairing decision is resolved. Dismissal releases reconnect; failed approved
pairing can restore the still-authorized saved Mac even before a live connection
exists. Successful pairing selects the verified Mac's filter. Unresolved Iroh
lookups offer Cancel and expire after 30 seconds. Pasted Iroh links now use the
same account, build-tag and directory checks as opened links.

**18 JVM and 15 distinct Android cases passed** across the focused runs, including
real local RPC destinations and five Android process-restoration scenarios.
[Source scope, evidence, initial test failures and limitations](PAIRING_STARTUP.md).
The iOS development-only injected startup task runner was reviewed, not copied
into production. Physical acceptance, the broader source audit and the full goal
remain open. Included in signed build 463.

## Failed computer switch recovery (2026-10-03)

Manual computer switches now retain the previous verified route and filter. If
the new dial fails, Android restores that still-authorized saved pairing rather
than indefinitely retrying the new selection. Newer selections supersede late
failures, route normalization retains intent, and sign-out/team changes retire
the baseline. Eight JVM and five Android recovery cases passed, including real
local RPC terminal routing after rollback and a superseded connection attempt.
[Source comparison, ownership, evidence and remaining limits](MAC_SWITCH_RECOVERY.md).
The Pixel was absent from ADB; physical acceptance, exact pane restoration and
the wider startup/switch audit remain open. Included in signed build 463.

## Stable computer colors per app instance (2026-10-03)

The `0fc35d6` Mac-switch/aggregation source review found a visible mismatch:
Android recomputed colors from physical device IDs, changing existing slots as
discovery changed and sharing a slot across Stable/Nightly. It now retains
additive assignments per exact app instance in the screen ViewModel, preserves
refresh/transient-empty state, clears on account changes and prunes team changes
to a still-admitted foreground instance. All computer/workspace/detail consumers
use the same keys, with custom colors retaining precedence.

Twelve JVM checks and four existing emulator editor/UI checks passed; attribution,
source scope, screenshots and limits are recorded in
[Computer appearance](COMPUTER_APPEARANCE.md#app-instance-color-stability--2026-10-03).
Physical multi-Mac/Activity-recreation acceptance remains open. Included in signed
build 463; the whole-parity upstream pin is unchanged.

## ANR stack recovery (2026-10-03)

Android 11+ ANR exits now recover filtered main/monitor-owner and other thread
stacks from available OS traces. Bounded parsing excludes names, source paths,
lock addresses and raw trace contents; retained frames survive OS trace eviction
and respect Clear Logs. Seven new JVM checks and 32 existing checks passed.
The Android suite passed four cases, including an actual disposable-process ANR
with the expected blocked main thread and lock-owner method, plus Java/native
crash regressions and export/clear checks. The initial test event-timestamp error
and separate launcher startup ANR are documented in the
[design and evidence](CRASH_DIAGNOSTICS.md#anr-thread-stacks-android-11).

Included in signed build 463. Physical Pixel
acceptance, configured push, native Iroh tracing and the wider source audit remain open.

## SSH keyboard and composer image integration (2026-10-03)

Direct keyboard images, clipboard images and Android photo selection now feed the
SSH image uploader. The shared screen orders uploads with later text/raw mouse
bytes, retains account/terminal drafts across navigation, fences changed routes
and verified server keys, and provides explicit recovery after unconfirmed input.
Composer image sends preserve concurrent edits, remove accepted images
individually and never press Enter for an images-only submission. A separator
keeps composer image paths distinct from following paths/captions.
Verification: 23 JVM checks and 18 distinct Android cases passed across the
combined and final focused runs, including real plain-SSH/SFTP image delivery.
[Ownership, source comparison and evidence](DIRECT_SSH.md#ssh-keyboard-and-composer-images-2026-10-03).
Physical acceptance and the broader goal remain open. Included in signed build 456.

## SSH image upload foundation (2026-10-03)

The iOS SSH image destination/filename contract now has a tested SFTP backend:
one pinned channel, private file permissions, collision handling, cancellation
and no replay after an uncertain publication. Two JVM and seven real SFTP Android
checks passed; the debug/test APK build passed. That checkpoint covered the backend; the UI integration is recorded above.
Physical acceptance remains open, so SSH image paste is not yet marked complete.
[Source contract, integration work and evidence](DIRECT_SSH.md#ssh-image-upload-backend-2026-10-03).
The broad source pin remains unchanged. Included in signed build 456.

## SSH terminal mouse and scrolling (2026-10-03)

SSH screens now route captured clicks/wheel events through Ghostty's current
mouse protocol, map alternate-scroll gestures to the current cursor-key mode,
retain ordinary primary history locally, and send requested focus reports across
window/activity transitions. All three SSH providers preserve raw mouse bytes in
their existing bounded queues; query replies remain suppressed for mirrors.
Targeted iOS source comparison used `0fc35d6`; the wider pin remains unchanged.

Verification: 13 JVM, four native Android and eight app Android checks passed.
All six packaged native libraries and the debug APK passed the 16 KB alignment
gates. [Protocol, test corrections and evidence](DIRECT_SSH.md#terminal-mouse-wheel-and-focus-input-2026-10-03).
Physical SSH/Pixel acceptance remains open. Included in signed build 456.

## Terminal background input ownership (2026-10-03)

The targeted iOS input-session review at `0fc35d6` now has an Android lifecycle
counterpart: temporary pauses retain composition/focus; backgrounding retires old
IME connections and clears native-terminal/SSH composer focus without deleting
drafts. Stopped editors reject input, and old connections stay retired after
resume. The shared browser keyboard endpoint uses the same lifecycle admission
check without adopting the terminal-specific focus policy.

A baseline Android test reproduced late input acceptance after activity stop.
Four new lifecycle tests and six existing rich-input, immediate hardware-focus
and browser checks passed. An older direct-keyboard test needed its pre-absorption
resize assertion and frame-clock wait updated; it then passed its full exact-byte,
Unicode, pause/resume and target-switch flow. [Evidence and APK hashes](TERMINAL_INPUT_DELIVERY.md#background-input-ownership--2026-10-03).
Included in signed build 456; verification on the physical Pixel and the wider
source audit remain open.

## Latest signed integration checkpoint — build 468 (2026-10-03)

[Signed build 468](https://github.com/DocMorphic/cmux-app/actions/runs/37135587173)
at `acfae3e` combines stable computer menus, build subtitles and foreground
presence, plus connection/heartbeat/last-seen rows and encrypted verified history.
It includes all build 463 features. Full CI passed. The downloaded artifact
matched CI's hash and passed independent signer, viewer-asset, native LOAD/RELRO,
16 KB ZIP, manifest/debug-fixture and attribution checks. The existing emulator
upgraded 463 → 468 without clearing data, preserved its original install time,
and cold-launched to the visually reviewed sign-in screen with no compatibility
or ANR dialog and an empty crash buffer. A recurring emulator System UI dialog
had been cleared before the baseline capture and upgrade; its cause is unknown.
[Artifact, hashes and evidence](PIXEL_INSTALL.md).

The baseline was already signed out. Authenticated migration and current physical
Pixel/Mac acceptance remain unverified. Configured push, broader Iroh diagnostics,
remaining presence consumers and the remaining source/visual audit are open.

## Previous signed integration checkpoint — build 463 (2026-10-03)

[Signed build 463](https://github.com/DocMorphic/cmux-app/actions/runs/37131181771)
at `6fc1433` combines ANR stack recovery, app-instance colors, switch recovery,
launch pairing and picker cancellation with all build 456 features. Full CI passed.
The exact downloaded artifact passed independent signer, viewer-asset, native
LOAD/RELRO, 16 KB ZIP, manifest/debug-fixture and attribution checks. The existing
emulator upgraded 456 → 463 without clearing data, preserving its original install
time, and cold-launched to the visually reviewed sign-in screen. There was no
compatibility/ANR dialog and the crash buffer was empty.
[Artifact, hashes and evidence](PIXEL_INSTALL.md).

The baseline was already signed out. This verifies packaging and signed-out
upgrade, not authenticated migration or physical Pixel/Mac acceptance. Configured
push, broader Iroh diagnostics and the remaining source/visual audit are open.

## Previous signed integration checkpoint — build 456 (2026-10-03)

[Signed build 456](https://github.com/DocMorphic/cmux-app/actions/runs/37124887007)
at `ac3b20d` combines the background-input, SSH mouse/focus and SSH image/composer
work above with all build 448 features. Full CI passed; the downloaded APK matched
the CI hash and passed independent signer, viewer-asset, native/16 KB ZIP, manifest,
debug-fixture and packaged attribution checks. The existing emulator upgraded
448 → 456 without clearing data, retained its original install time and cold-launched
to the visually reviewed sign-in screen without a compatibility or ANR dialog.
The crash buffer was empty. [Artifact, hashes and evidence](PIXEL_INSTALL.md).

The baseline was already signed out; this verifies packaging and signed-out
upgrade only. Physical Pixel/Mac workflows, authenticated migration, configured
push, ANR stack recovery and the remaining source audit are still open.

## Previous signed integration checkpoint — build 448 (2026-10-03)

[Signed build 448](https://github.com/DocMorphic/cmux-app/actions/runs/37117513871)
at `b63b875` combines the Java/native crash-stack additions, connection-timeout
recovery and foreground banner reconciliation with all build 441 features. Full
CI passed; the exact downloaded artifact passed independent stable-signer, 14
viewer asset, six native/16 KB ZIP, manifest/debug-fixture and attribution checks.
The existing emulator upgraded 441 → 448 without clearing data, preserved its
original install time and cold-launched to the visually reviewed sign-in screen.
The baseline was already signed out. [Artifact, hashes and evidence](PIXEL_INSTALL.md).

The baseline emulator System UI ANR is recorded separately from the successful
upgraded launch. This is packaging and signed-out upgrade evidence; physical
Pixel/Mac acceptance, authenticated migration, configured push, ANR stack recovery
and the remaining upstream source audit are still open.

## Previous signed integration checkpoint — build 441 (2026-10-03)

[Signed build 441](https://github.com/DocMorphic/cmux-app/actions/runs/37112575050)
at `55e8dde` includes the recent browser capability/fallback and live-inventory
fixes, pane menu icons, bounded terminal text capture and one-time SSH key setup,
as well as build 434's workspace close confirmations and diagnostics. Full CI
passed; the exact downloaded artifact passed independent signer, packaged viewer,
16 KB native/ZIP, manifest and attribution checks. The existing emulator upgraded
434 → 441 and cold-launched to the reviewed sign-in screen, preserving its
installation time. It was already signed out. See [install evidence and checksums](PIXEL_INSTALL.md).

A reusable verifier now enforces these APK-content checks for future builds.
Older signed-build references below describe their original checkpoints.
Physical acceptance, configured push, authenticated release migration, ANR stack
capture and the remaining source audit are still open.

## Local Java/Kotlin crash stacks (2026-10-03)

Android now captures bounded local exception-stack metadata through the default
uncaught-exception handler, then delegates to Android's previous handler. Crash
writes bypass the ordinary diagnostic queue and locks; complete records publish
atomically. Exports retain code symbols and numeric app version while excluding
exception messages, source paths and thread names. The existing two-member ZIP,
private storage and shared clear cutoff remain in use. Startup/export maintenance
retains the newest 32 complete records. See [design and evidence](CRASH_DIAGNOSTICS.md).

Verification: eight new JVM checks plus fifteen existing storage/history checks
passed. Debug/test APKs and release Kotlin compilation passed. All six Android
crash/history/Settings checks passed in 34.262s on API 37 / 16 KB, including a real
uncaught exception, preserved system crash reporting, message/thread/path omission,
repeat export, clear, and native-abort regression. An initial fixture launch timed
out behind Android's preceding crash dialog; cleanup now verifies and terminates
the known fixture process before the next launch. No production handler behavior
was weakened to make this pass.

The targeted source is upstream `MobileDebugLogCrashCapture.swift` at `0fc35d6`.
This adds Java/Kotlin stack capture to the existing OS reason summaries. Native
stacks are covered below. ANR stacks, native Iroh detailed tracing, broader event coverage and physical
acceptance are still open; signed build 441 predates this change.

## Foreground notification reconciliation (2026-10-03)

The retained-terminal path now follows `0fc35d6` iOS recovery's foreground banner
cleanup. NativeScreen uses the already verified client on each lifecycle START,
without redialing or restarting terminal subscriptions. It reconciles actual
posted IDs and applies handled results only while that foreground login, Mac and
connection remain current. Stop cancels the attempt; the next Start retries.

Fourteen JVM checks and two full Android lifecycle/RPC/banner checks passed
(33.1s, API 37 / 16 KB). The Android checks cover same-ID sibling isolation,
unhandled-banner retention, no request without banners, late-response retirement,
repeat foreground catch-up, and unchanged terminal replay count with the service
disabled. Runtime testing corrected an initial Compose-state trigger and an
unsaved-sibling fixture; an instrumentation-startup ANR was followed by a passing
run of the same APKs after boot settled. [Evidence and boundaries](NOTIFICATION_DISMISS.md#foreground-catch-up-on-retained-connections-2026-10-03).
Physical acceptance and configured push remain open. The whole-parity source pin
is unchanged; build 441 predates this addition.

## Connection deadline recovery (2026-10-03)

Targeted inspection of upstream
[`MobileShellComposite+ConnectionRecovery.swift`](https://github.com/manaflow-ai/cmux/blob/0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc/Packages/iOS/CmuxMobileShell/Sources/CmuxMobileShell/MobileShellComposite+ConnectionRecovery.swift)
confirms that unsuccessful recovery must stand down when a newer connection owns
the shell. The stored-Mac dial changes separately bound candidates by deadlines.
Android uses Compose effect cancellation and identity-checked feed handles rather
than that Swift state machine. This review exposed an Android timeout bug: a
child dial/RPC `TimeoutCancellationException` was treated as permanent cancellation,
leaving the screen connecting or its computer feed without a running monitor.

Both connection owners now check whether their coroutine is still active before
handling a local timeout as a retryable failure. Genuine account/route/screen
cancellation and explicit supersession still propagate; a retired attempt cannot
publish an error or schedule a retry. The screen closes its failed handshake
lease, shows the existing actionable connection error, and uses its existing
2–30 second backoff. The feed publishes Offline and retains its existing refresh
wake-up/10-second reconnect behavior. Terminal mutation replay policy is unchanged.

Verification: the new feed timeout regression failed before the fix because the
monitor never published Offline. Afterward, all **26 JVM tests** passed (21 feed
coordinator and five refresh tests; zero failures/skips). They include recovery
on refresh and a delayed timeout from a retired owner while its replacement is
already connected. Debug/test APK assembly and release Kotlin compilation passed
in 1m 6s; the final test-only rebuild passed in 31s.

All **three Android checks passed in 43.046s** on the existing API 37 / 16 KB
emulator. They exercise the production screen through a dial deadline, an actual
framed host-status request left unanswered for its normal deadline, successful
automatic retry into the workspace, closure of the timed-out lease, and disposal
before a late timeout (no retry, saved pairing or network request afterward).
Evidence is ignored at `captures/runtime/connection-timeout/`. Debug SHA-256:
`cced02a7c4e8b3aeb0137fe79af98a8afb95a7559ff107eaa6aac945cd43ff3b`;
test SHA-256: `4fede277ff143c697d3a5c49de4027e0f8ae2b30ac795b10f5e2c7ab56140fa3`.
This is targeted recovery evidence, not full upstream reconnect-contract or
physical Pixel/Mac acceptance. Signed build 441 predates this fix. The broad
source pin remains unchanged.

## Native crash stack recovery (2026-10-03)

Android 12+ native-crash history now includes filtered code frames recovered from
available OS tombstones. The parser validates process identity and selects only
the crashing thread; retains bounded relative PCs, build IDs, known library names
and code symbols; and excludes paths, absolute addresses, registers, memory and
raw diagnostic text. APK-mapped libraries are supported. Previously recovered
frames survive OS trace eviction, while Clear Logs prevents old imports returning.
Missing or rejected traces leave their reason summaries intact.

Verification: 32 focused JVM checks passed; debug/test APKs and release Kotlin
compiled. Three Android checks passed in 13.328s on the existing API 37 / 16 KB
emulator, including real native SIGABRT recovery and Java capture regression.
The filtered export was pulled and inspected. See [limits and evidence](CRASH_DIAGNOSTICS.md).
No additional AVD was created. Pixel browser acceptance, ANR stacks, native Iroh
tracing, broader source review and configured push remain open. Signed build 441
does not yet contain the Java/native stack additions.

## One-time SSH key setup (2026-10-03)

Targeted review of upstream `SSHKeyInstaller.swift` and
`SSHComputerEditorView.swift` at `0fc35d6` identified a remaining SSH setup gap.
The Android computer editor now copies/shares the selected public key and offers
one-time password installation, followed by a fresh key-only login before success.
Jump hosts still use keys. The fixed command preserves existing authorized keys,
including a final entry without a newline, avoids duplicates and corrects file
permissions. Passwords are not persisted or restored, and failed or interrupted
writes are never automatically retried. Host-identity decisions and biometric
signing use the existing scoped prompt broker.

Cancellation, route/key/account retirement and restored editor state retain the
same ownership checks; the restored UI reports an interrupted attempt without a
password or automatic retry. See [implementation and evidence](DIRECT_SSH.md#one-time-public-key-installation-2026-10-03).
**Verification:** 13 new installation checks, 14 existing transport checks and
four host-editor checks passed on API 37 / 16 KB. The editor screenshot was
inspected. This is a targeted source-parity addition. Signed build 441 includes this change. The broad upstream pin and physical
acceptance remain unchanged.

## Recent terminal text capture (2026-10-03)

Targeted review of iOS `TerminalTextSheetView.swift` and
`TerminalTextSnapshot.swift` at `0fc35d6` confirmed the local immutable snapshot,
5,000-line default budget, trailing-blank trimming, empty state and native copy
workflow. Android already provided those behaviors, but its capture traversed
and materialized every scrollback viewport before discarding older rows. Since
capture runs on the terminal owner's thread, that created unnecessary UI work.

Capture now reads newest output first and stops as soon as the retained line
budget is filled. It stores only retained plain-text lines, handles overlapping
partial oldest viewports without duplicates, and trims blank tail rows before
applying the budget. Internal blank lines still count. Reads stay on the owner's
thread so concurrent output or replay cannot move rows during capture. Finding
the last nonblank output can still require scanning blank tail viewports; this
change does not claim that capture is asynchronous or constant-time for a
completely blank history.

The initial regression failed against the old implementation: a seven-line copy
started reading at the oldest viewport of a 20,024-line buffer. That old traversal
requires 835 viewport reads; the passing test now verifies exactly one. Five
new snapshot checks and six existing terminal-interaction tests passed. Coverage
includes partial viewports, blank tail pages, internal blank lines, exact-budget
output, blank histories and alternate-screen isolation. Two Android checks passed
in **5.431 seconds**: a real Ghostty buffer exceeding 5,000 history lines verifies
exact Unicode output for seven-line and default-budget copies, unchanged live
viewport and immutable text; the existing production UI test verifies Copy All,
native long-press selection/copy and reopen without new replay or input RPCs.
The selection screenshot was inspected. Debug/test APK assembly and release
Kotlin compilation passed in **31 seconds**.

Evidence: ignored `captures/runtime/text-capture/`. The existing emulator was
stopped afterward. Physical Pixel acceptance, configured push and the wider
source audit remain open; signed build 434 predates this change.

## Pane menu iconography and utility grouping (2026-10-03)

Targeted inspection of `TerminalPickerMenuContent.swift` and
`MobileSurfacePreview+Presentation.swift` at `0fc35d6` showed that Android's
plain-text pane menus were missing iOS's pane-kind and action glyphs. The shared
Mac and SSH menus now include terminal, browser, simulator and surface-kind
icons; workspace/tab creation, directional split and utility icons; and a
separate utility section. Selection uses a vector checkmark. The glyphs reuse
existing assets where available, with project-authored vectors for the remaining
shapes. Android's menu layout places the glyph before the label and its selection
mark after it. Decorative images have no separate spoken label; row selected
semantics and action names remain the accessibility source.

Surface-kind metadata now crosses the existing browser-process menu protocol,
so its surface glyphs can match the main screen. Unknown/missing kinds use a
generic surface icon. This metadata does not alter navigation identity or action
permissions. Copy Debug Logs remains DEBUG-only.

Debug/test APK assembly and release Kotlin compilation passed in **1m 53s**.
Three existing UI checks passed in **38.684 seconds** on the existing API 37
emulator: native grouped selection, live browser capability/disabled-state
transitions, and real SSH/Chrome grouped creation selecting exact terminals.
Native, browser-process and SSH menu screenshots were visually inspected.
Evidence: ignored `captures/runtime/picker-icons/`. No new AVD was created.
Physical acceptance and the wider source/UI audit remain open; signed build 434
predates these menu changes and the whole-parity pin remains unchanged.

## Live browser menu inventory and permissions (2026-10-03)

The separate browser Activity now receives current workspace rows, creation
permissions and SSH picker targets even while the parent Activity has stopped
producing frames. The previous capability-only observer left renamed, added and
removed panes stale until the parent redrew. A regression reproduced this on the
previous code with **Missing: Renamed shell** before the fix.

`RoutedBrowserMenu` provides one owner-scoped snapshot for publication and for
checking a returned action. Native Mac sources read the current authoritative
workspace list and connection capabilities. SSH sources read the current cmux-tui
tree, tmux windows or shell state. Creation handlers recheck permissions and use
the current section/pane targets. A source belonging to another workspace cannot
replace the displayed menu or apply a returned action. Existing account,
connection, destination and presentation identity checks remain in force.

A removed workspace retires its presentation permanently, including when removal
precedes the browser service attachment. A later attachment receives that closure;
route preparation and returned actions are rejected. Retirement received during
WebView setup survives completion of the earlier opening request.

Verification uses the existing API 37 emulator, with no new AVD. Eight production
browser checks passed in **44.097 seconds**, covering inventory updates, creation
revocation/restoration, capabilities, replacement-workspace rejection, rotation,
retired-owner cleanup and late pane/creation results. Nine existing real
SSH/tmux/Chrome flows passed in the first integration run. The new tenth flow
observed its remotely created Screen 2 immediately, but its first assertion clicked
the first of two identically titled terminal rows. Saved semantics confirmed the
wrong row was selected. After selecting the row under Screen 2 explicitly, the
new flow passed and opened the exact returned terminal identity. Its menu
screenshot was inspected. A final **five-test pass in 27.496 seconds** combines
that SSH flow, pre-attachment retirement and three replacement/late-result checks.
All four plain-SSH screen checks also passed, including the browser round trip
and workspace creation. There are **23 distinct passing Android checks** across
these runs; this is not a full application or physical-device acceptance run.
Debug/test APK assembly and release Kotlin compilation passed; the final build
took **31 seconds**. Evidence is ignored at `captures/runtime/browser-live-menu/`.

Physical Pixel acceptance, configured push and the wider source/UI audit remain
open. The Pixel was absent from ADB. Signed build 434 predates this change and the
capability/fallback change; the whole-parity reference pin is unchanged.

## SSH browser picker restoration and tmux acceptance (2026-10-03)

An independent phone browser opened over a linked cmux-tui browser could return
to the wrong mode when selecting that same linked tab. The workspace selection
had not changed, so its restoration effect did not run. The return handler now
explicitly reopens the remembered on-device page for that unchanged selection,
using the live provider row and the existing per-panel browser preference.
Explicit Streamed selection still clears that preference and returns to Chrome.

The production SSH/Chrome suite now contains **nine** flows. All nine passed on
the existing API 37 emulator in **104.64 seconds** after the fix. Added coverage:

- Real tmux New Window, a split in the first window while viewing the second,
  and New Workspace from the separate browser process. Each operation selects
  its exact created pane; typed input reaches that split, and the original tmux
  and cmux-tui workspaces remain intact.
- Linked phone page → independent New Browser → original linked phone page,
  preserving the original URL and surface. Selecting the already-active New
  Browser is a no-op; returning retires the independent page. A strengthened
  focused rerun passed in **27.848 seconds**, additionally checking an actual
  button click and server-observed event after restoration. Menu and restored
  page screenshots were inspected.

Initial test iterations corrected parent-process navigation and accessibility
field targeting before reproducing the real wrong-mode bug. Debug/test APKs
built successfully. Evidence is ignored at `captures/runtime/ssh-browser-actions/`;
run the full suite with `scripts/check-ssh-transport.py --cmux-browser`.
The Pixel was absent. This proves these emulator fixture flows, not physical
acceptance or completion of the remaining upstream parity audit.

## Plain SSH shortcut browser and creation actions (2026-10-03)

The **New Shell** shortcut in SSH Computers now exposes New Browser and New
Workspace through the same grouped picker used by the mixed workspace route.
Its browser runs through the host's SSH network and exposes the current terminal
plus New Workspace. A plain shell has no New Tab/Window/Screen action. The shell
stays composed behind the browser so returning retains the PTY and unsent text.
Creation selects the new shell on the same host and keeps previous shells alive;
callbacks check the current account, host, shell and busy state. Reconnect uses
the same guarded selection path and retains the old screen when connection fails.

Verification: debug and instrumentation APKs built; all **four** production SSH
shell UI checks passed on the existing API 37 emulator in **42.431 seconds**.
The added check exercises a real SSH-routed WebView in the browser process,
Back restoring its page, explicit terminal selection closing the browser while
preserving the draft, and creation from both menus followed by commands in the
exact new PTYs. The remote fixture reports three live shells. Existing checks
cover ANSI/query rendering, Files round trips, closure/account retirement, and
failed/successful reconnect. Picker and created-terminal screenshots were reviewed.
The first new test incorrectly expected explicit pane selection to retain the
page; its corrected assertions cover the existing Back-versus-pane contract.
Evidence: ignored `captures/runtime/ssh-plain-final/`; runner:
`scripts/check-ssh-transport.py --shell-ui`.

The Pixel was absent from ADB, so this is emulator evidence. Signed build 421
does not include this change. Physical SSH acceptance and the broader goal remain
open; this checkpoint does not advance the whole-parity upstream pin.

## Upstream delta audit in progress (2026-10-03)

The metadata comparison from the whole-parity baseline `4c5272e` to installed
NIGHTLY reference `0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc` contains **331 files**
under `ios`, `Packages/iOS`, and `Packages/Shared/CMUXMobileCore`: 184 additions
and 147 modifications. Major groups are ShellUI (82), Shell (78), SSH (44),
TerminalKit (25), Tunnel (15), and RPC (14). This is an inventory, not a claim that
331 implementations have been reviewed. The whole-parity pin above remains
unchanged until the remaining source contracts and acceptance gates are audited.
Raw inventory is ignored at `captures/runtime/ios-parity-delta-0fc35.tsv`
(SHA-256 `1fa1594cffc6f82ef79a8fdac22eaa1b88cf2316e63f9089fca9faad1abd3fd5`).

Targeted source findings in this pass:

- [`TerminalPickerMenuContent.swift`](https://github.com/manaflow-ai/cmux/blob/0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc/Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/TerminalPickerMenuContent.swift)
  and its value model use current terminal rows, a selected checkmark, and separate
  terminal/surface/simulator/browser sections. Android's terminal title menu had
  omitted terminal selection. It now provides those grouped rows, live inventory
  updates and selected accessibility semantics, routing through the existing pane
  selection callbacks in ready and starting-terminal views. The shared-picker
  follow-ups below extend this to streamed browsers, Mac surfaces and on-device
  browsers. SSH menus, feedback utility entries, and the remaining picker contract still
  need review and implementation as needed.
- `TerminalReplayQueryFilter.swift` and `Data+TerminalQueryReplies.swift` prevent
  historical output from generating new PTY input. Android's tmux/cmux-tui mirrors
  construct Ghostty without a reply callback; its plain SSH shell owns one live
  PTY and retains the same emulator across view changes. Source inspection does
  not establish new runtime coverage or justify adding a second parser blindly.
- **Picker capability and legacy-browser fallback (2026-10-03):** exact
  `TerminalPickerMenuValue.swift`, `TerminalPickerMenuContent.swift`,
  `WorkspaceDetailView+Surfaces.swift`, `SurfaceFallbackCardView.swift` and
  `MobileSurfacePreview+Presentation.swift` establish the contract. Android now
  carries capability-known and streaming-support state through terminal, surface,
  streamed-browser and on-device-browser menus. The disabled **Update cmux on your
  Mac to stream browser panes** hint appears only after an authoritative
  unsupported snapshot; reconnecting with unknown support removes it.
  Raw browser surfaces move into **Mac Surfaces** when streaming is unsupported,
  preserving their host IDs and selected state. They open a browser surface card
  with the blue globe, matching explanatory copy and **Open on Mac** action.
  Focus still requires `surface.focus.v1` and the existing current-workspace,
  current-connection mutation checks. Selecting the card does not request a
  browser stream. Supported Macs retain the separate **Mac Browsers** rows;
  missing raw surfaces are not invented from a stale stream inventory.
  The separate browser process receives both initial and live state updates.
  A source-state observer publishes changes while the parent Activity is paused,
  without waiting for that screen to redraw. The observer ends with its registry
  entry and cancellation during Activity recreation does not abandon an already
  launched browser. A newer context received during WebView setup wins over the
  older opening snapshot. Streaming mode is disabled when unsupported, and the
  return handler rechecks current support before applying a stream action.
  Four capability/row tests and ten workspace-selection regressions passed.
  Five Android checks passed on the existing API 37 emulator: grouped terminal
  picker, real framed-RPC legacy fallback/focus, live cross-process capability
  transitions, rotation retaining page/lease, and retired-owner cleanup. The
  final live-menu rerun passed in **10.9 seconds**. The initial run exposed stale
  state while the parent was paused; after the observer fix, an assertion was
  corrected to inspect the disabled menu item's accessibility ancestor rather
  than its enabled Text child. Saved XML confirms that hierarchy. No disabled
  state was relaxed. Menu and fallback-card pixels were reviewed. Debug/test
  APK assembly and release Kotlin compilation passed; the last production build
  took **27 seconds**, and the final test-only build took **19 seconds**.
  Evidence: ignored `captures/runtime/browser-picker-capabilities/`. The Pixel
  was absent; physical legacy-Mac acceptance and the wider source/UI audit remain
  open. Signed build 434 predates these changes; the whole-parity pin is unchanged.
- [`MobileWorkspaceCloseConfirmation.swift`](https://github.com/manaflow-ai/cmux/blob/0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc/Packages/iOS/CmuxMobileShell/Sources/CmuxMobileShell/MobileWorkspaceCloseConfirmation.swift)
  centralizes destructive Mac/tmux/cmux-tui confirmation while allowing
  phone-owned shell closure directly. Android now shares one confirmation model
  and dialog across `NativeScreen`, mixed `SshWorkspacesScreen`, and legacy
  `SshTmuxScreen`. SSH confirmations name the workspace **and current host**,
  use **End Session** for tmux and **Close Workspace** for cmux-tui, and explain
  the effects on other devices. The paired-Mac dialog uses **Delete Workspace? /
  Delete** and explicitly says it closes the workspace on the Mac. Existing
  availability guards and workspace/window targeting remain in place.
  Eight real mixed SSH UI checks and four dedicated tmux checks passed on the
  existing API 37 / 16 KB emulator. The mixed test now cancels and confirms both
  persistent workspace kinds, verifies the last tmux session disappears from
  inventory, and preserves the independent phone-owned shell. Its fixture was
  corrected to forward tmux stderr over SSH (while retaining its local log), as
  real SSH does; the suppressed no-server diagnostic had prevented empty-list
  discovery after the final session ended. No production error handling was
  relaxed. One paired-Mac UI test against the framed RPC fixture also passed:
  cancel sends no close request; confirmation sends exactly one request with
  the selected workspace/window IDs, removes that workspace after refresh, and
  retains the other workspace group. All three dialog screenshots were visually
  reviewed. Evidence is ignored at `captures/runtime/workspace-close/`. The Pixel
  was absent from ADB; physical acceptance remains pending. These changes
  postdate signed build 428, and this targeted check does not establish
  missing-entrypoint or whole-app parity.
- The exact `TerminalPickerMenuContent.swift` / `WorkspaceDetailView.swift`
  sources expose **Copy Debug Logs** only in DEBUG builds. The action prepends
  a snapshot of visible terminal text to a bounded recent log and copies it to
  the clipboard. Android now has that action in the shared native and SSH
  pickers, including the on-device browser. It captures the currently visible
  grid (including local scroll position) only when tapped. Routine logging uses
  fixed RPC/SSH operation and outcome labels, local correlation numbers and
  elapsed timings; credentials, addresses, commands, payloads and server error
  text cannot enter this recorder. The synchronized ring retains at most 4,000
  entries / 96,000 characters and reports eviction. Clipboard text contains the
  installed build identity, OS and process, and is marked sensitive; nothing is
  submitted. Browser copies request the main-process ring through the existing
  same-UID, current-presentation-validated service, then append browser-process
  diagnostics. Cancellation preserves the existing clipboard.
  The durable logging/export and process-history follow-ups below extend this
  debug-menu path. Crash stack capture, native Iroh detailed traces and broader rendering/input telemetry
  remain pending; this is not complete diagnostics-taxonomy parity.
  Twenty focused JVM checks passed (buffer bounds/concurrency and RPC behavior),
  as did four clipboard/menu/browser-process UI checks and 14 real SSH transport
  checks, including handshake timeout, jump-host teardown and authentication.
  The visible-text copy is capped at 32,000 characters with an explicit truncation
  marker; all three menu tests passed again with that bound asserted and the
  popup pixels captured and reviewed. Debug/test APK assembly and release Kotlin
  compilation passed; generated release `BuildConfig.DEBUG` is false. The Pixel
  was absent from ADB. Ignored evidence: `captures/runtime/debug-logs/`. Signed
  build 428 predates it.
- **Persistent diagnostics (2026-10-03):** exact `AppLog.swift` and
  `MobileSettingsView.swift` sources define always-on typed events, a verbose
  opt-in, one ZIP with exactly `cmux-diagnostics/app-events.log` and
  `cmux-diagnostics/networking.log`, and confirmed clearing. Android now installs
  a recorder at application startup in both app processes and exposes **Export
  Logs**, **Verbose Connection Log**, and **Clear Logs** in Settings. The initial
  instrumentation covers lifecycle, settings, native RPC, SSH and browser route
  preparation. Verbose mode adds operation-start records; debug builds always
  include those records. Raw Iroh traces and the rest of iOS's event taxonomy
  remain separate work.
  Events enter a bounded queue, then a background writer coalesces consecutive
  equivalent entries within each batch. Overflow and write failures produce
  counted gap records when storage recovers. App/network files retain up to
  5 MB per active generation, three archives, and 12 MB total per domain. They
  reopen across process launches and record the installed build at startup.
  Export streams these generations into two ZIP members under a shared file
  lock. Private logs live outside Android backup; only completed cached ZIPs
  receive read-only FileProvider grants. Up to three export snapshots are kept,
  with older snapshots pruned after 24 hours on the next export. Interrupted
  partial exports are removed on the next attempt; cancellation removes its
  unshared result.
  A shared boot-count/monotonic-time clear barrier prevents records queued in
  another process from restoring pre-clear history. Debug clipboard snapshots
  apply the same cutoff, while post-clear activity continues to be recorded.
  Clearing removes log generations and cached exports without changing the
  verbose preference. Deletion/write errors are reported instead of showing a
  successful operation. Browser return flushes its admitted diagnostics before
  normal presentation teardown, within the existing bounded return deadline.
  Fourteen focused JVM checks passed for storage/reopen/rotation, verbose
  preservation, clear barriers, overflow, cancellation and the memory ring;
  debug/test assembly and release Kotlin compilation passed. Eight Android
  checks passed on the existing API 37 / 16 KB emulator: settings export/read
  grants, clear/cancel, failed toggle persistence, clipboard bounds/cancellation,
  real main/browser ZIP records, and clearing the browser clipboard history
  through the main process while retaining post-clear activity. The disk barrier
  exposed an off-main-thread toast in the Compose test dispatcher; clipboard and
  settings actions now explicitly use the main dispatcher, and all eight checks
  passed afterward. Settings pixels were reviewed. Evidence is ignored at
  `captures/runtime/durable-diagnostics/`. The Pixel was absent from ADB. This
  does not advance the whole upstream parity pin or prove native crash capture
  or physical-device acceptance. Signed build 428 predates these changes.
- **Previous process failure summaries (2026-10-03):** inspected exact
  [`MobileDebugLogCrashCapture.swift`](https://github.com/manaflow-ai/cmux/blob/0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc/Packages/iOS/CmuxMobileDiagnostics/Sources/CmuxMobileDiagnostics/MobileDebugLogCrashCapture.swift).
  iOS DEBUG writes fixed fatal-signal records and Objective-C exception stacks,
  then preserves the previous crash handlers. Android now reads its own
  [system exit history](https://developer.android.com/reference/android/app/ActivityManager#getHistoricalProcessExitReasons(java.lang.String,%20int,%20int))
  on API 30+, without replacing ART/native signal handling. Startup, foreground
  return and Export Logs recover Java crashes, native crashes and ANR reasons
  for exactly the app and `:browser` processes. External services, WebView child
  processes, normal exits, fixture processes, OS descriptions and trace streams
  are excluded. The schema contains only a fixed role/reason, death timestamp,
  PID and numeric exit status/signal. Failure to query the OS adds a fixed
  `EXIT_HISTORY FAILURE` marker and still allows export of existing logs.
  The query requests at most 32 recent OS records; an atomic shared snapshot
  retains at most 64 failure summaries and merges repeat/cross-process reads.
  They appear once in `app-events.log` inside the existing two-member ZIP.
  Confirmed clearing also empties that snapshot and persists a wall-clock death
  cutoff, including for late OS reports. The cutoff never moves backwards.
  If the device clock is manually moved backwards after clearing, new reports
  dated before that cutoff are suppressed until the clock catches up; ordinary
  event logging continues to use the independent boot/monotonic barrier.
  Android's history is a bounded OS buffer and may be absent. API 26–29 have no
  recovery through this API. Summaries are retrospective metadata, not stacks,
  and do not establish the app version that crashed. Safe stack/tombstone
  capture was open at this checkpoint; see the later crash-stack sections above.
  Live ANR acceptance and full iOS diagnostics parity remain open.
  Verification: seven focused history tests and eight existing storage tests
  passed; debug/test APK assembly and release Kotlin compilation passed. Two
  emulator-only checks passed in **1.729 seconds**, triggering a real Java crash
  (`am crash`) and native SIGABRT in the disposable browser process, reading
  the OS reports, checking their role/reason/signal, exporting each once across
  repeat reads, and confirming clear prevents re-import. The initial fallback
  test looked for a networking event in the app-events member; its fixture was
  corrected to use a workspace event. No production filtering was relaxed.
  Evidence is ignored at `captures/runtime/exit-history/`. The existing API 37
  emulator was used; the Pixel was absent. Signed build 428 predates this work.
- `MobilePushCoordinator.swift` now awaits explicit reconnect before retrying a
  pending notification route, cancelling replaced retries. Android's targeted
  recovery implementation and verification are recorded below; this is not a
  claim of complete push-coordinator parity or configured push delivery.

The browser route, authentication gate, SSH, shell/model and runtime changes in
the inventory still require contract-by-contract comparison. Earlier dated port
checkpoints below are evidence for their stated scope, not a full delta audit.

### SSH grouped terminal picker (2026-10-03)

Targeted source review at `0fc35d6` covered `TerminalPickerMenuContent.swift`,
`TerminalPickerMenuValue.swift`, `MobileShellComposite+SSHTerminals.swift`, and
the tmux/cmux-tui provider layout mappings. Android's mixed SSH workspace route
now groups terminals by tmux window or cmux-tui screen, separates pane groups,
shows pane subtitles and the selected row, and labels SSH browser rows **Browsers**.
The picker is used by terminal and streamed-browser views. Section actions are
Split Right/Down for tmux and New Tab/Split Right/Down for cmux-tui. New Window,
New Screen and New Workspace select the terminal created by the operation.
The cmux screen actions target its active pane; tmux section actions split the
window's active pane without selecting a different pane on a desktop client.

tmux creation now requests and validates the returned pane ID, then resolves it
in refreshed inventory. It retains server/session creation guards and does not
guess a newly created terminal from a list difference or retry uncertain writes.
Picker mutations disable input while pending; late results cannot navigate after
leaving the original selection. The selected cmux row is resolved against the
current owner generation. Plain shell views now expose View as Text and the
available feedback action even when they have no browser callback.

Initial assembly passed in **1m** with **6 JVM checks** (two layout cases and four
tmux inventory/guard cases). The first eight-case real SSH workflow run passed
six cases but exposed two test input attempts during terminal replacement. Input
is now disabled during creation/reconnect and the creation checks await a changed
terminal identity. A missing test extension import was fixed; final assembly and
the same six JVM checks passed in **17s**. The full repeated Android suite passed
**8 tests in 83.326s**, using real isolated tmux/cmux-tui processes behind the
loopback SSH fixture. This covers menu creation/splits/selection, exact original
terminal return, mixed workspaces, owner isolation/restart, connection recovery,
and restored selection with explicit disconnect preserved. Both grouped-menu
screenshots were inspected. The emulator and fixture were stopped afterward.

Evidence: `captures/runtime/ssh-picker/`, `ssh-picker-build.txt`,
`ssh-picker-build-final.txt` and `ssh-picker-build-retry.txt` (ignored).
Debug APK SHA-256: `48fa570c6c8d7c13f26a216b401770d556e7a54adbcdf2bf69d1027e5331fb72`;
test APK: `96c38e7b140055185bceb2e63e468b269964c65bd77ed09c7d1cb953a64ef556`.

The browser follow-up below carries the menu through the separate browser Activity
and verifies streamed-browser switching. Plain-shell shortcut creation/browser
actions, debug-log utilities and physical Pixel acceptance still remain. These
changes are not yet in signed build 421. The whole-parity pin remains unchanged.

#### Grouped picker in the on-device SSH browser

The separate browser process now receives serialized SSH section/row/action
metadata, retains the linked browser's checkmark, and uses **Browsers** rather
than Mac Browsers. A phone-local browser checks New Browser. Context refreshes
carry current topology and action availability. Returned creation requests have
a bounded typed representation; the main process checks current section/action
permission, the request ID, live network, and exact local surface before calling
the owning SSH route. The route reuses the same guarded operations as its terminal
menu. No SSH connection or credentials enter the browser process. The in-process
browser fallback uses the same presentation. tmux workspace metadata includes all
current picker terminals so a returned selection can resolve beyond the original
pane.

Debug/test assembly passed in **53s**, with **4 JVM tests** covering layout,
metadata round trips, action revocation and malformed requests. The seven-case
real SSH/cmux-tui/private-Chrome suite passed all six existing browser interaction,
mode restoration, process/connection/daemon recovery and uncertain-input checks.
Its new picker test initially used an incomplete terminal test ID; a focused
rerun then caught a test querying the main Compose tree during the Activity handoff.
After correcting those assertions and waiting for the known browser Activity,
test assembly passed in **16s** and the focused test passed **1 test in 19.118s**.
Thus all **7 selected Android checks pass across the recorded runs**.

The new flow switches from a streamed browser to its exact terminal, enters the
phone-rendered browser, inspects grouped sections, creates a screen, reopens the
browser with updated topology, creates a tab, then creates a workspace. Each
creation checks the selected terminal's exact identity, and the original browser
resource remains intact. The final browser menu screenshot was visually reviewed.
Evidence is ignored under `captures/runtime/ssh-browser-picker/` and
`captures/runtime/ssh-browser-picker*-build*.txt`. Debug APK SHA-256:
`8f15ed96b8cee7d49d9bab81f8e5a34167027401ec0a52582f9256d1937fcfd1`;
test APK: `678dbfea6506f11910825dfc33a39e7c9cc0670763f3ec061be654afa05cc301`.

The emulator and owned SSH/Chrome fixture were stopped. The Pixel was absent and
signed build 421 is unchanged. tmux creation from the separate browser, linked-to-
independent New Browser switching, and physical SSH acceptance still need dedicated
runtime checks; the generic Mac picker and full upstream delta audit are not newly
certified by this SSH run.

### Browser return ownership (2026-10-03)

Review after the SSH browser work found that creation results checked their local
surface, but pane selection, Back, restart and fallback handling could still act
on a replacement destination before Compose observed the navigation change. The
browser Activity return handler now applies one ownership check to every action:
the exact surface object and full account/team/computer/workspace key must still
be current. A missing/retired/mismatched registration can only leave its own
destination; an already replaced destination is untouched. An open, current,
registered presentation is required to route, create, change mode or restart.
The asynchronous parent Back handler also rechecks ownership after releasing its
host. Close removes its own local surface before invoking the external callback.

Three JVM checks cover replacement objects with equal IDs, changed scopes,
missing registrations, retired networks, workspace mismatch and closed surfaces.
The first build caught a syntax error in the new unit test; after correction,
debug/test assembly and the three checks passed in **13s**. The real browser
Activity suite passed **9 tests in 51.509s**. Three new cases deliberately keep an
old composition visible while navigation installs another generated account and
workspace, then return a pane selection, creation request or Back from the old
Activity. They verify no route or creation callback, the exact replacement still
current and open, and the old host released once. Six existing checks cover
normal page/pane returns, mode switching, terminal/browser creation, retirement
cleanup and process-death reopening with retained cookies.

Evidence: `captures/runtime/browser-return/instrumentation.txt`,
`captures/runtime/browser-return-build.txt`, and `browser-return-build-final.txt`
(ignored). Debug APK SHA-256:
`dd5d6b96815a8aec2bf07320dc7c09c313540983c513a4f54c5cc4a381f79eb7`;
test APK: `d736e037e0aa12645c4f220c73b348c5db4355e8ff997d15e107e9eab9c3ed9f`.
The existing emulator was shut down; the Pixel was absent. Signed
build 421 is unchanged. This establishes the tested browser ownership behavior,
not completion of the remaining iOS parity or physical-device gates.

### Public feedback composer (2026-10-03)

Targeted upstream review covered `MobileFeedbackEmailClient.swift`,
`MobileFeedbackRoute.swift`, `WorkspaceDetailView.swift` and
`web/app/api/feedback/route.ts` at `0fc35d6`. Ordinary feedback is a multipart POST
to `/api/feedback`, carrying email, message, version/build, bundle, build type, OS,
hardware model and locale. The server limits messages to 4,000 characters.
The separate internal `@manaflow.ai` + active Mac + `dogfood.v1` route sends a
diagnostic bundle through `dogfood.feedback.submit`; that route is **not yet
ported**. Android currently uses the public route, including for internal accounts.

Android now offers Send Feedback from Settings and the shared pane menus, including
the separate on-device browser Activity; the existing SSH terminal menu also has
the action. The sheet pre-fills the known email in the main process and asks for
it in the credential-free browser process. It identifies the unofficial Android
build, validates email/message, shows build/device metadata, prevents concurrent
Send taps, retains the draft on failure, allows explicit retry, and cancels its
call when dismissed or the account owner changes. It sends no credentials, terminal
text, logs or attachments. The client disables redirects and automatic retries.
Cancellation after dispatch cannot establish that the server did not receive a
request. The lifecycle follow-up below adds retained drafts and request ownership;
the initial composer closed on Activity recreation.

The first build caught an unavailable generated BuildConfig; stamps now read the
installed package metadata. Final debug/test assembly passed in **1m 8s**, with
**4 JVM tests** covering the exact public field set, input limits, HTTP rejection/
redirect handling and cancellation without retry, using MockWebServer only.
The final test APK built in **22s**; **7 Android tests passed in 27.648s**: four
composer flows (validation/single dispatch, failure/retry, cancel/reopen, owner
change), production terminal-menu/Settings cancellation with preserved sign-in,
terminal keyboard/resize regression, and the separate browser process lifecycle.
The composer screenshot was visually reviewed; its fixture background is not the
production workspace. No message was sent to cmux's real feedback inbox, so live
delivery remains unverified. No Pixel was attached. The single emulator is stopped.

Ignored evidence: `captures/runtime/feedback-public-build-final.txt`,
`feedback-public-test-build.txt`, `feedback-public-ui.txt`, `feedback-composer.png`.
Debug SHA-256 `b837f3f98550bebf01fc57c0027bacc4e5e71d2d4930f669942b9f790dd2e3dd`;
test SHA-256 `e085f4255a3423ef155d28ee3e317128c4e1a5f428b040105a51814bc753a4bc`.
Signed build 411 remains unchanged; GitHub confirmed artifact `11261931650`
unexpired, and the README now points to that verified signed checkpoint.

#### Feedback lifecycle follow-up

The composer now uses an Activity ViewModel and SavedStateHandle. Draft, reply email,
failure and success receipt survive view/Activity recreation. A pending HTTP call
belongs to the retained controller and continues once across recreation. Cancel
or changing account ownership clears the draft and cancels that call; late results
cannot close a newer composer. Saved process state contains bounded draft and
status data, never a command to resend. Restoring a snapshot taken during Send
produces an uncertain-result message and requires explicit user action to retry.
Successful state clears the message and retains only the receipt until acknowledged.

Debug/test assembly passed in **1m 3s**, including **8 JVM tests** (four controller
and four HTTP client checks). All **three real Activity recreation tests** passed:
draft/email without dispatch, a held request retained exactly once, and failed
draft/error retained until explicit retry. Four existing composer tests and the
separate-browser lifecycle check also passed in the initial Android run. Its one
Settings reopening assertion ran before the StateFlow render; after adding a
bounded wait for the actual sheet, test assembly passed in **26s** and that test
passed in **3.974s**. All **9 selected Android checks** therefore pass across these
runs. Restored pending-process state is covered at the serialized-controller level;
this is not a claim of a new physical process-death test or live inbox delivery.
The debug-only lifecycle fixture is excluded from release sources.

The single emulator was stopped. The Pixel remained disconnected, and signed build
411 is unchanged. Evidence: `captures/runtime/feedback-lifecycle-build.txt`,
`feedback-lifecycle-ui.txt`, `feedback-lifecycle-test-build.txt`, and
`feedback-lifecycle-root-ui.txt`. APK SHA-256: debug
`4997036f3e917ed2c3d9d80d386677781fe430b8d6e489ed6163cae13582b5ae`, final test
`21a60272697e2c0461ec1744c5ed401d48f76024d1a4c2c6c1e39e2bd77047fa`.

### On-device browser picker (2026-10-03)

The pinned iOS `TerminalPickerMenuValue.swift` explicitly checks the linked Mac
browser row when its page is shown on the phone. Only an independent phone-local
browser checks New Browser. Android now follows this in both the in-process local
browser and the browser Activity that uses a separate process and Mac proxy.
Both use the common grouped picker; simulator rows retain their section across
the process boundary and duplicate simulator browser entries are omitted.

New Workspace and New Terminal return an explicit creation intent to the main app.
The main app binds that intent to the current login, resolves the owning saved Mac,
waits for its foreground connection and a current authoritative workspace snapshot,
then invokes the existing guarded creation flow. A pane-less workspace can create
a workspace or terminal. A linked on-device Mac tab can also request New Browser;
an independent local browser keeps its existing selected New Browser action.
The browser process receives presentation rows and an enablement flag, not account
credentials; the main process rechecks the current destination and account before
dispatch. These ephemeral creation intents are not restored as mutations after
process death.

Debug/test assembly passed in **1m 3s**. Ten local/native checks passed in the
initial selection (all eight `LocalBrowserRoutingTest` cases, the terminal picker,
and mixed native-pane navigation). The initial routed checks exposed harness
errors: UiAutomator matched the label child, and this Compose menu's Selected
semantics export as Android **checked**, not selected; the return-to-parent path
also needed the fixture's Compose idle synchronization. The original hierarchy
and screenshot confirmed the correct checked row before correcting the tests.
The final test APK assembled in **16s** and **all four routed checks passed in
28.373s**, covering linked panel mode return, New Terminal/New Browser action
delivery and host release, and retained page/back/reopen/pane selection. Thus all
**14 selected checks** pass across these runs; this was not a clean first run.
Linked and independent local picker screenshots were visually reviewed, including
the distinct checkmark placement and visible creation entries.

The single emulator is stopped. No physical Pixel was connected; signed build 411
is unchanged. This does not establish physical Mac/Pixel acceptance or complete
SSH/feedback/picker parity. Ignored evidence is under `captures/runtime/`:
`local-picker-parity-build.txt`, `local-picker-parity-ui.txt`,
`local-picker-parity-test-build.txt`, `local-picker-parity-routed-final-ui.txt`,
and `on-device-{linked,local}-picker.png`. Debug SHA-256
`5c81727fcd4c803787e5664a523f358b6419a6e83801851c207e6a35ac2f5d97`;
final test APK SHA-256
`74c48861bc13c8a831a60774d0397a6e7019d4134fd4e0d5ac1f65ae85507fa9`.

### Shared picker for native panes (2026-10-03)

Ready/starting terminals, streamed Mac browsers and Mac surfaces now share one
live-inventory picker. It groups terminals, surfaces, simulators and browsers,
omits duplicate simulator browser rows, and gives the current pane both a visible
checkmark and Android selected semantics. Streamed browsers retain their changing
page title and mode control, and expose the picker while disconnected as well.
Mac surfaces and streamed browsers now use the same guarded New Workspace and New
Terminal callbacks as terminal views. View as Text, Files and terminal sizing
remain terminal-specific actions. The on-device browser follow-up above extends
this shared picker further; SSH menus and feedback utility parity remain.

Debug/test assembly passed in **1m 11s**. **9 emulator tests passed in 24.834s**:
production-screen navigation from terminal to browser to surface and back,
selected-row semantics and stream shutdown, creating a terminal in the browser's
workspace, creating a workspace from a surface, existing live inventory grouping,
pending-creation guards, terminal-menu workspace startup, browser navigation and
viewport continuity, disconnected mode admission, and long terminal title controls.
The surface picker screenshot was visually reviewed: section labels, current-pane
checkmark and creation actions are visible without clipping. These loopback fixture
results do not establish physical Pixel/Mac acceptance. The existing emulator was
stopped; no Pixel was connected, and signed build 411 remains unchanged.

Ignored evidence: `captures/runtime/shared-pane-picker-build.txt`,
`shared-pane-picker-ui.txt`, and `shared-pane-picker-surface.png`.
APK SHA-256: debug
`1528c4ecb57c36eeb2989c01ea1a99ef4d399e025643e7c3a4014dc609c9f8d1`, test
`874f06f6c68110a01ddaa9fe423c09445c2e4df16dd95bac949d67a86ce94573`.

### Notification route recovery (2026-10-03)

A failed notification lookup now retains its route and offers Retry/Cancel.
Retry reconnects the selected Mac and waits for a newly established client before
resolving the target; it cannot reuse the failed client. A newer notification
replaces the pending target, and account changes invalidate the old lookup.
The route also requires a current authoritative workspace snapshot. If an explicit
tab no longer exists, Android reports it unavailable instead of opening a sibling
terminal or marking the notification read. Workspace-only notifications retain
their existing fallback selection.

Navigation is committed before acknowledging the notification as read. A failed
read acknowledgment reports an error without repeating navigation. Cancelling
recovery clears the pending navigation. These changes concern opening existing
notifications; Firebase configuration, token registration and end-to-end remote
push delivery remain outstanding. iOS's complete timeout and coordinator contract
has not been certified by this targeted port.

Debug/test assembly passed in **39 seconds**, with **3 JVM tests** passing.
**6 Android flow tests passed in 10.444 seconds** on the existing API 37 / 16 KiB
emulator: retained retry waits for a fresh handshake, replacement during a held
handshake opens only the new target, missing explicit tabs never open siblings,
wrong-login routes never navigate or mark read, saved-Mac switching supports
reopening a route, and forgotten-Mac routes never use the current Mac. These use
the production screen with loopback RPC fixtures, not a physical Mac/Pixel or
remote push delivery. The emulator was stopped afterward; no Pixel was connected.
Signed build 411 is unchanged.

Ignored evidence: `captures/runtime/notification-route-retry-build.txt` and
`notification-route-retry-ui.txt`. APK SHA-256: debug
`eddf30aba71458b2724a94cc30836bd59284cb2cca377e1ceee81d002d486f76`, test
`a8b39a9cb5a5725c836bf88fd7f93b45e6e5733ec5682b87bd871b3c32c3194e`.

### Terminal picker verification

Debug and test assembly passed in **42 seconds**. **Three emulator checks passed
in 8.682 seconds**: live picker inventory/selection and pane routing, existing
starting-terminal sibling selection, and native terminal keyboard/input. The
initial screenshot caught the popup before it was drawn, so it is not visual
acceptance. The fixture was corrected to use the app's surface colors and wait
for Android's exported popup tree before capture; final test assembly passed in
13 seconds. The corrected picker check passed in **7 seconds**, and the final
popup screenshot was visually reviewed: section labels, current terminal checkmark,
and pane actions are visible without clipping. The single existing API 37 / 16 KiB
emulator is stopped. Evidence is ignored under `captures/runtime/terminal-picker-*`.
Final local APK SHA-256: debug
`95e57164eaffc3187763135b74c6fba5a1774b621a6bbd903a540b40ada6e8ac`, test
`71ee34cec95dfdd0a14234703e93766a01d2d94b8032f79a041c4fb1082e8061`.
Signed build 411 and the disconnected Pixel installation are unchanged.

### Terminal picker creation actions (2026-10-03)

New Workspace and New Terminal are now present in both ready and starting-terminal
title menus. They reuse the existing workspace creation/startup and terminal
creation flows. Workspace creation was extracted from the list's menu so both
entry points share account/Mac/current-navigation checks, partial-response merge,
created-terminal selection and lazy-startup handling. Creation targets the Mac
shown in the terminal pane; the list's selected-computer filter remains its own
creation admission rule.

While either workspace or terminal creation is awaiting its reply, both picker
creation actions and the list's workspace creation action are disabled. The
handlers also reject a repeated creation, preserving single dispatch even if an
old callback runs. Entering creation finishes direct IME composition and clears
keyboard/focus/scroll motion through the existing mechanisms.

Debug/test APK assembly passed in **49 seconds**. **11 emulator tests passed in
68.181 seconds**, covering both workspace entry points, partial response retention,
new-terminal picker creation, disabled repeated/cross-kind creation during a held
reply, startup preparation/timeout/retry, sibling/disappearance/late-navigation
behavior, grouped picker callbacks, and the native keyboard/input flow. The menu
capture was visually inspected; its component fixture shows the creation entries
disabled because it supplies no creation callbacks. Actual enabled creation and
pending-state behavior are exercised by the production-screen tests. This does
not establish physical Pixel/Mac acceptance. The one existing emulator is stopped;
the Pixel was absent and signed build 411 is unchanged.

Ignored evidence: `captures/runtime/terminal-picker-creation-build.txt`,
`terminal-picker-creation-ui.txt`, and `terminal-picker-creation.png` in the same
directory. Final APK SHA-256:

- Debug: `d778825e15ef23b2eeb994ff8d5c66e57bfe0b9c0d8c72c04f4b8c14267fad3f`
- Test: `bf764e54182db29e9b9df84cec7ad324ce0983982f4a1d5b941bf948f024ad64`

## Recent checkpoints

**NIGHTLY shared-sizing controls implemented, live acceptance pending (2026-10-03):**
the foreground session handles sizing/detach events, gates traffic and provides
explicit reattach. The size sheet now edits all five policies, fixed dimensions,
priority order and this phone's counts override, plus individual/confirmed batch
disconnection. **29 focused JVM tests and 3 emulator UI tests passed**; the clean
priority-screen capture was visually reviewed. No new emulator was created, and
the existing one is stopped. Detach state now also survives cross-Mac navigation, with matching direct-reply
admission on secondary feed connections; **68 focused JVM tests passed** for that
logic checkpoint. Settled viewport gating, themed bounds/hatch decoration and
prompt-safe size chip placement are now implemented; **20 focused JVM tests and
2 emulator UI tests passed**, with both theme captures reviewed.
Shared grids now use iOS-style top-left placement, width fitting and display-only
pinch/pan. **36 focused JVM tests passed**, including 189 reference cases generated
by the pinned Swift implementation, and **10 emulator UI checks passed**, including
the full native terminal flow. Primary-screen keyboard absorption/sliding and
top-row reveal are now implemented with **16 focused JVM tests** and 315 pinned
Swift reference cases, plus **10 emulator UI checks** covering real keyboard entry,
continuous top reveal, shifted-grid pinch focus and native terminal regressions.
Unshared alternate-screen target capacity and three-frame geometry settling are
implemented with **12 focused JVM and 9 emulator UI tests passed**. The subsequent
presentation transaction now retains the actual old frame until matching resize
acknowledgement, redraw and recording, with the iOS five-second silence fallback;
**13 JVM and 14 emulator UI checks passed**, including pixel checks. Physical
Mac/Pixel acceptance and performance/accessibility checks remain open. Content
measurement now has a bounded scan, shares visible rows with drawing and protects
image placements below the cursor; **14 JVM and 12 emulator checks passed**,
including real image pixels through keyboard layout and frame retention.
Terminal accessibility now exposes primary history and keyboard top reveal through
Android's standard scroll action, plus labeled older/newer/latest actions; remote
terminal paths expose wheel actions. Manual TalkBack and physical acceptance remain
open. **12 focused JVM and 10 emulator UI checks passed**, including the exported
Android accessibility action. Android uses the supported
`unknown` kind plus its model; upstream's automatic exclusion rules still need an
Android kind for identical behavior. Signed build 411 includes these changes;
the installed Pixel app is unchanged. [Contract, evidence and remaining work](TERMINAL_SHARED_SIZING.md).
An opt-in physical scenario now covers policy controls, counts/priority and
explicit detach/viewer/normal reattach on the owned fixture; its test APK compiles,
but the new live checks await the Pixel and have not passed yet.

**Signed build 411 verified (2026-10-03):** full CI passed at `28265f8`;
downloaded signer, 14 packaged viewer hashes, six native libraries and 16 KB ZIP
alignment were independently checked. The existing emulator upgraded 397 → 411
without clearing app data and cold-launched the sign-in UI; its baseline was
already signed out, so authenticated migration remains pending. The emulator is
stopped and the Pixel was absent. [Current download and limits](PIXEL_INSTALL.md#current-signed-development-apk--build-411-2026-10-03).

**Signed build 397 delivered (2026-10-03):** full CI passed at `8f1ce42`,
including the production account/credential deadlock fix. Downloaded APK signer,
14 viewer assets, six native libraries and 16 KB ZIP alignment were independently
verified. The existing emulator upgraded 385 → 397 without clearing data and
cold-launched the sign-in UI; it is now stopped. No additional AVD was created.
The Pixel retains the verified debug build; signed-in release migration and live
push remain open. [Previous download and limits](PIXEL_INSTALL.md#previous-signed-development-apk--build-397-2026-10-03).

**Physical checklist UI passed (2026-10-03):** **1 test, 29.055 s**, using actual
MainActivity and the saved NIGHTLY Mac. Add/edit/state changes, touchscreen drag,
manual status, workspace reopen and swipe delete were checked against authoritative
Mac snapshots. Screenshots were inspected; login retained, test workspace removed,
and sleep setting restored. This closes the described physical Todo UI gate;
Gboard composition and TalkBack remain separate checks.
[Evidence and scope](TODO.md#physical-checklist-ui-acceptance--2026-10-03).

**Physical native pairing and browser UI passed (2026-10-03):** the Pixel/NIGHTLY
journey passed **1 test in 35.604 s**: production pairing, New Browser, On Android,
Mac-loopback WebView rendering, page navigation, Back/Forward and workspace reopen.
Both screenshots were reviewed; login and earlier pairings survived, and exact
fixture cleanup and sleep restoration were verified. The prior ANR was traced to
an account/credential lock inversion and fixed in `1c62eab`; **23 focused JVM tests**
passed. A separate test lifecycle issue caused by consumed pairing URI matching
was resolved without changing production behavior. No fixture receipt remains.
[Evidence and exact limits](BROWSER_TUNNEL.md#physical-pairing-and-browser-ui-acceptance--2026-10-03).
The physical debug app and signed build 397 contain the fix. HTTPS, transfers, network handoff,
process-death recovery, direct physical keyboard composition and live push remain
open. Historical checkpoints below retain their original scope; later verified
results supersede earlier pending statements.

**Real identified-input duplicate suppression verified (2026-10-02):** the
Pixel/NIGHTLY check passed in 10.71 s: retry identical bytes/identity after a new
connection, require DUPLICATE, send the next sequence and require APPLIED, then
require exactly one original output after an ordered shell fence. Fixture cleanup
was verified. The initial failed run exposed ID casing normalization in the new
test; preserving the original wire ID fixed it. Production code/APK is unchanged.
[Scope and remaining recovery cases](NATIVE_RUNTIME_CHECKPOINT.md#physical-identified-input-retry-after-reconnect--2026-10-02).

**NIGHTLY native interoperability verified (2026-10-02):** Android discovered
and authenticated the intended nightly host without changing saved pairings.
A real native browser lane returned the exact body of an owned Mac loopback HTTP
fixture (1 test, 7.253 s). A separate terminal run verified identified input with
an APPLIED acknowledgement, live GRID output and fresh reconnect (1 test, 10.56 s).
Fixtures were removed and the temporary HTTP listener closed. See
[browser scope](BROWSER_TUNNEL.md#physical-nightly-discovery-and-native-http--2026-10-02)
and [terminal scope](NATIVE_RUNTIME_CHECKPOINT.md#physical-nightly-identified-input-acknowledgement--2026-10-02).
Saved-pairing UI, full WebView workflow, real duplicate-input suppression and live
push remain open. Production APK and signed build 385 are unchanged.

**Physical resize and Todo RPC verified (2026-10-02):** the real MainActivity
test passed in 21.902 s with exact host grids 67×47 → 67×24 with Gboard → 67×47
after reopening, actual composer output, inspected settled screenshots, retained
login and verified fixture removal. A separate Todo RPC check passed in 13.24 s:
add/edit/reorder/state/status/remove and exact snapshot retention across a fresh
native connection, followed by verified cleanup. No production change or emulator
was needed. [Resize scope](NATIVE_RUNTIME_CHECKPOINT.md#settled-pixel-viewport-and-reopen--2026-10-02)
and [Todo scope](TODO.md#physical-native-rpc-acceptance--2026-10-02) supersede their
earlier pending RPC/resize statuses. Direct physical Gboard input, Todo UI,
nightly capabilities, live push and broader acceptance remain open.

**Signed build 385 delivered (2026-10-02):** source `7f8cefd` passed the full CI
pipeline and retained the existing release signer. It includes deferred-terminal
startup, dark-theme system bars and the push retry-scheduling fix. Independent
checks passed all 14 packaged viewer hashes, six native-library checks and 16 KB
alignment. The existing emulator upgraded 376 → 385 without clearing data and
reached the sign-in screen after a fresh launch; the emulator is stopped.
[Download, checksum and limits](PIXEL_INSTALL.md#previous-signed-development-apk--build-385-2026-10-02).
Authenticated migration, settled Pixel resizing, newer host capabilities, live
push and full physical/UI acceptance remain open. PR #1 remains a draft.

**Push retry scheduling fixed (2026-10-02):** a reproduced WorkManager dependency
bug let an older retry block a new urgent push. Fresh messages now wake independent
processing, duplicates retain one pending job, and queued priority/expiry survive
reconstruction. Fifteen JVM checks, one real WorkManager test and five Android
ingress regressions passed on the existing 16 KB emulator; native/ZIP gates passed.
[Failure, evidence and delivery limits](PUSH_DELIVERY.md#push-scheduling-follow-up-2026-10-02).
The emulator is stopped. Live push setup and physical acceptance remain open;
signed build 376 and the Pixel installation are unchanged.

**Physical MainActivity composer/reopen checked (2026-10-02):** the unlocked
Pixel passed one test in 16.529 s: real workspace creation/lazy startup, Gboard
visibility, reduced terminal area, composer Send, rendered output and reopening
with login preserved and fixture cleanup verified. Screenshots were reviewed.
A stronger host-grid resize check failed before keyboard entry; its generated
fixture was subsequently removed and verified. The revised nonblocking probe
builds but awaits the reconnected Pixel. Settled resize remains open.
[Evidence and limits](NATIVE_RUNTIME_CHECKPOINT.md#physical-mainactivity-composer-and-reopen--2026-10-02).

**Live terminal streaming verified on Pixel (2026-10-02):** the extended physical
check passed in 11.542 s, including a real native input-lane command, five live GRID
events carrying its output without a replay read, simultaneous control RPC,
fresh-connection replay and verified test-workspace cleanup. This host does not
advertise identified-input support, so ACK/deduplication acceptance remains open.
[Evidence and exact scope](NATIVE_RUNTIME_CHECKPOINT.md#physical-live-grid-output-and-native-input-lane--2026-10-02)
distinguish this from UI/Gboard, raw-byte lanes and network-switching acceptance.
Production APK and signed build 376 are unchanged; no emulator was started.

**Lazy terminal startup fixed and physically checked (2026-10-02):** a new live
test exposed creation waiting forever for a deferred Mac terminal. Android now
prepares only the selected pending terminal using a viewport-free replay while
input remains disabled. The final Pixel/Mac test passed in 6.731 s: creation, one input,
real GRID output, a fresh native reconnect, retained output and verified test
workspace cleanup. All seven startup UI regressions passed on the existing
16 KiB emulator in 86.741 s; it is stopped. The fixed debug app is installed on the
Pixel; signed build 376 is unchanged. [Failure, source contract, evidence and
limits](NATIVE_RUNTIME_CHECKPOINT.md#lazy-terminal-startup-and-live-reconnect--2026-10-02)
leave physical UI/input-lane, browser and live push acceptance open.

**Signed build 376 delivered (2026-10-02):** source `420327d` passed the full CI
test/build pipeline and was signed with the existing certificate. Independent
checks verified 14 packaged viewer assets, six native libraries, 16 KB alignment
and the disabled-until-configured FCM manifest. The existing emulator upgraded
369 → 376 without data clearing or changing its first-install timestamp. Cold
MainActivity launch reached sign-in in 991 ms; the emulator is stopped.
[Download, checksum and verification scope](PIXEL_INSTALL.md#current-signed-development-apk--build-376-2026-10-02).
Authenticated migration, current physical Pixel/Mac acceptance and live push
delivery remain open. Earlier signed-build references below are historical.

**Simulator large-text recovery fixed (2026-10-02):** recovery content now scrolls
in short viewports, shared toolbar buttons reserve separate 48 dp targets, and
worker failures disable toolbar/text input consistently with video touches.
All 10 v2/legacy Android checks passed (84.843 seconds, API 37 / 16 KB, zero skips),
including a measured 320×280 dp viewport at 200% Compose text size, actual recovery
tap, decoded video and restored input. Screenshots and native/ZIP gates passed.
[Exact scope and physical/accessibility gaps](SIMULATOR_STREAMING.md#large-text-recovery-and-input-controls-2026-10-02).
The emulator is stopped; signed build 369 is unchanged at this checkpoint.

**Simulator host rotation verified in the Android fixture (2026-10-02):** the
production v2 viewer passed real HEVC portrait → H.264 landscape → HEVC portrait
decoding on one lane, pixel/letterbox checks and touch cancellation/remapping.
All four viewer tests passed (77.14 seconds, API 37 / 16 KB, zero skips), with six
native-library and both ZIP alignment gates passing. No production code changed.
[Evidence and physical/UI boundaries](SIMULATOR_STREAMING.md#dynamic-host-rotation-checkpoint-2026-10-02).
The emulator is stopped; signed build 369 is unchanged.

**FCM receive path checkpoint (2026-10-02):** added encrypted, account-scoped
queueing and background delivery with fresh membership checks, SDK banner
suppression and opt-out/login cleanup. Thirteen focused JVM tests and 12 Android
checks passed (72.806 seconds, API 37 / 16 KB, zero skips). All six native libraries
and both APK ZIP alignment gates pass after updating the new DataStore dependency
to 1.2.1. No Firebase project, token registration or sender is configured; live
provider, Doze/process-death and physical acceptance remain open.
[Implementation, evidence and remaining setup](PUSH_DELIVERY.md#fcm-receive-path-checkpoint-2026-10-02).
Signed build 369 is unchanged; the emulator is stopped.

**Browser download gate prepared (2026-10-02):** rechecked the Mac download
delegate and both iOS browser interfaces at audited candidate `204a11d`. Streamed
downloads follow Mac save preferences; the reviewed phone-local wrapper has no
download delegate. Added a loopback-only fixture with unique generated downloads
and a saved-file hash verifier, plus an explicit Pixel/Mac acceptance procedure.
The local HTTP/verifier checks passed; physical cmux download acceptance remains
open. [Source contract, procedure and evidence](BROWSER_DOWNLOADS.md).
No APK build, emulator or Mac settings change was needed.

**SSH Files mutation outcomes (2026-10-02):** refresh failures now preserve the
original mutation error, successful mutations with failed refreshes report that
the change completed, and unconfirmed upload publication asks users to check the
folder before retrying. Fourteen focused JVM tests and nine real SFTP/picker tests
passed (131.497 seconds, API 37 / 16 KB, zero skips). The new case loses a real
publication reply and verifies the warning in the Files screen, original bytes,
no automatic repeat and a separate copy only after an explicit second upload.
[Evidence and boundaries](DIRECT_SSH.md#sftp-publication-replies-and-refresh-outcomes-2026-10-02).
Signed build 369 is unchanged; the emulator and fixture are stopped.

**Signed build 369 checkpoint (2026-10-02):** source `59f279c` passed the full
CI pipeline and was packaged with the existing release signer. Independent
checks verified all 14 viewer assets and five native libraries, 16 KB alignment,
and an in-place emulator upgrade from 363 with unchanged first-install time.
Cold MainActivity launch and its sign-in screen passed; the emulator was stopped.
[Download, checksum and exact scope](PIXEL_INSTALL.md#current-signed-development-apk--build-369-2026-10-02).
The baseline was signed out; physical Pixel/Mac acceptance, authenticated migration
and live Android push-provider delivery remain open. Historical checkpoint notes
below describe the signed version available at the time of each check.

**SSH lost-reply and input-readiness fixes (2026-10-02):** preserved unconfirmed
delivery warnings, fixed stale size tracking after cancelled resize, and exposed
actual pointer authority to the page's enabled/accessibility state. All 29 focused
JVM tests and six final real SSH/cmux-tui/Chrome tests passed (94.736 seconds,
API 37 / 16 KB, zero skips), plus APK alignment and screenshot inspection. The
new case loses a successful click reply and proves reconnection does not duplicate
either the command or page callback. [Evidence, intermediate failures and limits](DIRECT_SSH.md#lost-click-replies-resize-cancellation-and-pointer-readiness-2026-10-02)
retain physical acceptance and broader input interruption cases as open work.
Signed build 363 is unchanged; the emulator is stopped.

**SSH daemon restart and recovery explanation (2026-10-02):** browser recovery
now displays workspace errors, including instructions to start a stopped desktop
service. Five integrated tests passed (82.118 seconds, API 37 / 16 KB, zero skips).
The new daemon case verifies no implicit desktop restart, restoration of the same
registry/tab/content on a new generation, preserved page state and no stale-input
replay. APK alignment and screenshots passed. [Evidence and initial navigation timeout](DIRECT_SSH.md#desktop-daemon-restart-and-browser-recovery-errors-2026-10-02)
retain hard crashes, unknown delivery and physical acceptance as separate work.
Signed build 363 is unchanged; the emulator is stopped.

**SSH Chrome process replacement (2026-10-02):** four real SSH/cmux-tui/Chrome
checks passed (63.5 seconds, API 37 / 16 KB, zero skips). The new case terminates
the private browser, verifies the visible error and discarded stale tap, then
confirms fresh pixels/input from a replacement process in the same cmux tab,
without replaying earlier input. APK alignment and screenshots were checked.
[Evidence and limits](DIRECT_SSH.md#chrome-process-replacement-recovery-2026-10-02)
leave host daemon restart, unknown delivery and physical acceptance open.
Production code and signed build 363 are unchanged; the emulator is stopped.

**SSH browser provider registration recovery (2026-10-02):** the real SSH,
cmux-tui and private Chrome suite now verifies provider detachment/re-registration
with the same page and SSH connection. Stale-image clicks are discarded, prior
clicks are not replayed, and new input works after recovery. All three integrated
checks passed (54.732 seconds, API 37 / 16 KB, zero skips), plus APK alignment
and screenshot inspection. [Evidence and initial assertion correction](DIRECT_SSH.md#external-browser-provider-registration-recovery-2026-10-02)
leave Chrome process/daemon restart, unknown delivery and physical acceptance
open. Production code and signed build 363 are unchanged; the emulator is stopped.

**Signed integration build 363 (2026-10-02):** the accumulated source at
`47f63aa` passed full app/Ghostty JVM tasks, all APK assembly, helper/update-policy
tests, viewer hashes and signing/alignment gates in CI. Independent signed-APK
verification confirmed the original signing identity, 14 packaged viewer hashes,
five native libraries and absent debug fixture activities. The existing Android
17 / 16 KB emulator upgraded signed 284 → 363 without clearing data; installed
APK hash, unchanged first-install time, native page compatibility and cold launch
to sign-in were verified. [Download, evidence and limits](PIXEL_INSTALL.md#previous-signed-checkpoint--build-363-2026-10-02)
leave authenticated upgrade, physical Pixel/Mac acceptance and live push provider
delivery open. No phone was changed; the emulator is stopped and PR #1 is a draft.

**Hardware layout input (2026-10-02):** fixed right-Alt character/dead-accent
lookup while preserving left-Alt terminal shortcuts. Repeated accents survive
modifier presses; explicit control chords cancel pending accents. Seven JVM and
six final Android checks passed (40.53 seconds, API 37 / 16 KB, zero skips),
including full-screen IME-to-RPC text and live application/normal cursor modes.
APK/alignment checks and screenshot inspection passed.
[Source audit, fixture correction and limits](TERMINAL_SHORTCUTS.md#android-hardware-layout-follow-up--2026-10-02)
retain physical keyboard layouts and Pixel/Mac acceptance as open. No signed
release changed; the emulator is stopped.

**Account process-death acceptance (2026-10-02):** three tests now kill and restart
a dedicated emulator process with real encrypted storage and production account
components. Cached display restores without authority, fresh membership enables
selection, interrupted deletion restores uncertainty without resending, and a
durable completed deletion signs out across two restarts. All three final tests
passed (56.968 seconds, API 37 / 16 KB, zero skips), including four verified PID
transitions and screenshot inspection. [Evidence and limits](ACCOUNT_RESTORATION.md#process-death-follow-up--2026-10-02)
leave full NativeScreen, physical acceptance and force-stop/Doze work open.
Production source and signed release are unchanged; the emulator is stopped.

**Account display restoration (2026-10-02):** encrypted account name/email/team
details now restore before refresh, with cached-only controls disabled until
membership verification. Reconnect reconciles removed teams; sign-out, account
replacement and changed server identity retire stale profiles and authority.
All 51 focused JVM and nine Android checks passed (59.424 seconds, API 37 / 16 KB,
zero skips), plus both APK/alignment gates and account screenshot inspection.
[Contract and evidence](ACCOUNT_RESTORATION.md) retain actual OS process-kill and
physical Pixel/Mac acceptance as open work. No signed release changed.

**Account deletion (2026-10-02):** added iOS-style confirmation and native
backend deletion, with distinct pending/partial/cleanup/authorization outcomes.
Account-encrypted receipts prevent automatic replay across restart; old results
cannot sign out a replacement login. Successful deletion uses the normal app
sign-out owner, and Sign out now appears in the Account settings group. All 38
JVM and ten final Android checks passed (66.541 seconds, API 37 / 16 KB, zero skips);
one screenshot-timing rerun also passed (9.994 seconds). APK/alignment gates and
confirmation/failure pixel inspection passed. [Contract and evidence](ACCOUNT_DELETION.md)
retain actual OS process-kill and physical confirmation/cancellation acceptance
as open work. No real account, production endpoint or signed release was changed.

**Android notification badges/settings (2026-10-02):** newly created connection
and reply-sending channels now exclude badges. Existing Android channel choices
are preserved, with settings links for changing ongoing-category badges. iOS's
absolute APNs count is documented separately from Android's active-notification
badge model. Sixteen notification/worker regressions passed; all three new Android
settings checks passed after an API 37 accessibility-selector correction
(18.53 seconds, zero skips). Both APK/alignment gates passed.
[Evidence and platform boundary](PUSH_DELIVERY.md#android-launcher-badges-and-notification-settings-2026-10-02)
retain physical launcher/native-screen acceptance and live provider delivery as
open work. No signed release changed.

**Direct notification Reply (2026-10-02):** replies can use the current native
terminal queue or another already-connected Mac's verified feed channel without
dialing or changing selection. Durable versioned packet fences prevent a worker,
restart or older app build from relaying a possibly applied direct write. A
provably unwritten attempt retains the same ciphertext for relay fallback;
partial paste reports that submission is still needed. All 61 JVM and 23 Android
cases passed (118.321 seconds, API 37 / 16 KB, zero skips), plus APK/alignment gates.
[Implementation, initial test failures and final evidence](PUSH_DELIVERY.md#direct-notification-reply-delivery-2026-10-02)
leave physical terminal effects, native-screen/lifecycle acceptance and live push
provider delivery open. No signed release changed.

**Foreground notification presentation (2026-10-02):** resumed native screens
now suppress new feed/push banners for the displayed terminal (or workspace-only
notifications), scoped to login and paired Mac. Pause, disposal, screen-off and
keyguard prevent stale suppression; late feed/push copies stay quiet without
marking Mac notifications read. All 14 JVM and 19 Android cases passed (103.39
seconds, API 37 / 16 KB, zero skips), along with APK/alignment gates.
[Evidence and direct-reply audit](PUSH_DELIVERY.md#foreground-notification-presentation-2026-10-02)
retain native-screen/physical acceptance, live push integration and direct Reply
as open work. The direct/relay paths have distinct host duplicate tracking;
unknown direct outcomes need a durable fence before that path is enabled.

**Android notification Reply action (2026-10-02):** authenticated push banners
now expose native text Reply, backed by account-encrypted one-use action grants,
atomic outbox enqueue/consumption and a manifest receiver that persists background
work. Local enrollment IDs retire stale actions/packets after forget/re-pair or
key changes. Feed-first banners gain Reply without resurrecting cleared or
submitted alerts. All 35 JVM checks and 24 Android cases passed (111.978 seconds,
API 37 / 16 KB, zero skips), including actual shade input/Send, ciphertext opening,
intent tampering and final screenshot inspection. Both APK/alignment gates passed.
[Evidence and remaining integration](PUSH_DELIVERY.md#android-notification-reply-actions-2026-10-02)
leave live push-provider setup, the foreground direct-send lane, physical delivery
and lifecycle acceptance open. No signed release changed.

**Authenticated notify/dismiss ingress (2026-10-02):** added pinned-peer payload
opening, strict expiry/content/identity checks, bounded encrypted replay/dismiss
state and a provider-neutral Android notification delivery entry point. Mac
notification IDs remain distinct from local fallback IDs; routes commit before
banners appear. All 28 JVM checks passed, including real Swift/CryptoKit vectors;
13 Android regression cases passed, followed by four final delivery cases after
the route-ordering fix (18.5 seconds, API 37 / 16 KB, zero skips). Both APK/alignment
gates passed. [Evidence and remaining integration](PUSH_DELIVERY.md#authenticated-notifydismiss-ingress-2026-10-02)
leave provider registration/delivery, RemoteInput, foreground suppression, badge
behavior and physical acceptance open. No signed release changed.

**Background reply scheduling and failure notices (2026-10-02):** added a
persistent WorkManager send chain, current-account/team/Mac validation, exact-body
retry recovery, boot/startup recovery and private content-free failure notices.
Unconfirmed receipts now survive delayed jobs for up to seven days without
extending send/acceptance windows. All 22 focused JVM checks and 16 Android cases
passed (72.271 seconds, API 37 / 16 KB, zero skips), plus both APK/alignment gates.
[Evidence and remaining integration](PUSH_DELIVERY.md#persistent-background-reply-work-and-private-notices-2026-10-02)
leave Reply action/push admission, physical delivery and Android background fault
acceptance open. No signed release changed; the push-provider choice is pending.

**Persistent encrypted reply outbox (2026-10-02):** added exact-request
restoration, bounded queue/receipt storage, persisted retry cooldown and a scoped
serial drain. Credential transactions retire forgotten/account-replaced work;
key changes never silently re-encrypt a reply ID. Late acceptance after local
expiry updates only its matching retained receipt. All 21 focused JVM checks and
nine Android cases passed (40.118 seconds, API 37 / 16 KB, zero skips), plus both
APK/alignment gates. [Evidence and integration still required](PUSH_DELIVERY.md#persistent-encrypted-reply-queue-2026-10-02)
leave notification actions, production background scheduling, failure notices,
push metadata and physical Pixel/Mac acceptance open. No signed release changed.

**Encrypted reply relay transport (2026-10-02):** added Mac-addressed HPKE
reply preparation and a bounded HTTPS sender with immutable retry bodies,
server cooldowns, ownership checks and cancellation. Nineteen focused JVM checks
passed, including unknown HTTP outcomes; pinned Apple CryptoKit opened both
Android reply fixtures and the upstream TypeScript relay accepted/deduplicated
them. [Evidence and integration boundaries](PUSH_DELIVERY.md#encrypted-reply-relay-sender-2026-10-02)
explicitly leave notification actions, persistent/background reply lifecycle,
failure notices and physical delivery open. No APK/emulator or signed release was
needed for this transport checkpoint; no production relay was contacted.

**Authenticated push/reply peer keys (2026-10-02):** the opted-in notification
service now performs optional `phone_push.keys.exchange` alongside feed monitoring
on capable, admitted hosts. Phone keys and bounded Mac peer associations are
account-encrypted; response account/instance/build checks preserve the distinction
between directory and physical Mac IDs. Login/forget transactions retire trust.
All 21 focused JVM checks and eight Android cases passed (38.042 seconds, API 37 /
16 KB, zero skips), plus both APK/alignment gates. [Evidence and remaining delivery work](PUSH_DELIVERY.md#authenticated-mac-key-exchange-and-encrypted-phone-key-storage-2026-10-02)
distinguish protocol/storage fixtures from production pairing, push and inline
Reply. No signed release changed; the delivery-provider choice remains pending.

**Mac-to-Android alert dismissal and reconnect catch-up (2026-10-02):** added
`notification.dismissed` events and bounded `notification.reconcile` requests
for actual delivered banners. Login/Mac admission fences, quiet-baseline event
races and durable stale-feed suppression are covered. Android's generated group
summary is excluded from alert counts and orphan cleanup. All 21 focused JVM
checks and seven Android notification cases passed (37.971 seconds, API 37 / 16 KB,
zero skips); both APK/alignment gates passed. [Evidence and scope](NOTIFICATION_DISMISS.md#mac-to-android-live-dismissal-and-reconnect-catch-up-2026-10-02)
retain the initial summary-count failure and distinguish component fixtures from
physical Pixel/Mac acceptance. Numeric icon badges, suspended/process-dead push
and inline reply remain open; no signed release changed.

**Android alert dismissal sync (2026-10-02):** system swipes now persist an
encrypted, account/Mac-scoped dismissal and send the official `notification.dismiss`
RPC through admitted saved-Mac connections. Confirmed delivery removes only its
rows; failures retain them and account/forget mutations prune them. Six Android
notification tests passed (34.3 seconds), including a real shade swipe and stale
action rejection after login change; 16 JVM checks and both APK/alignment gates
passed. [Evidence and limits](NOTIFICATION_DISMISS.md) distinguish isolated sender
and system-UI checks from physical Pixel/Mac delivery. Inline reply and push remain
open; no signed release changed.

**Push encryption compatibility (2026-10-02):** added the upstream authenticated
HPKE v2 envelope codec. Android opens two Apple CryptoKit vectors; Apple opens two
Android-generated envelopes. Nine focused JVM cases pass without skips, including
identity/tamper/size rejection and signing regressions. The codec is not yet wired
to notification delivery. [Delivery decision and remaining work](PUSH_DELIVERY.md)
record the APNs-only upstream service and the pending choice of a private Firebase
project/helper or official backend extension. No APK or emulator run was needed.

**Full unsigned SSH browser sequences (2026-10-02):** removed the signed-64-bit
restriction on remote frame IDs and pointer tokens. Exact JSON parsing and numeric
serialization preserve the full iOS UInt64 range; local renderer IDs fence old
presentation callbacks across reattachment. Thirty-five JVM checks and all five
Android rendering/input component cases passed (34.362 seconds, API 37 / 16 KB,
no skips), including maximum-token pixels and guarded taps. Both APKs and alignment
checks passed. [Evidence and limits](DIRECT_SSH.md) distinguish this fixture
coverage from live host/device acceptance. No signed release or parity pin changed.

**SSH HTTPS/WSS and certificate-error recovery (2026-10-02):** verified public
HTTPS and secure WebSocket text/binary echoes in the real On Android WebView.
Fixed missing error feedback for provisional TLS failures and Retry targeting the
previous page. The private untrusted peer received no HTTP data, including after
Retry. One secure workflow (20.377 seconds), three navigation regressions (19.442
seconds), and eight JVM cases passed; both APK builds and 16 KB checks passed.
[Evidence and limits](DIRECT_SSH.md) retain the initial failure and exclude other
TLS fault modes, paired-Mac routing and physical acceptance. No signed release changed.

**SSH-routed WebSocket browser (2026-10-02):** the real On Android WebView passed
server-message, Unicode text, binary data and live-update checks through an
SSH-only hostname, plus browser-close channel cleanup without ending SSH (one
workflow, 13.813 seconds, API 37 / 16 KB, no skips). [Evidence and scope](DIRECT_SSH.md)
explicitly exclude secure WebSocket/TLS, large-message faults, paired-Mac routing
and physical acceptance. Test APK only; production code and signed release unchanged.

**Live SSH browser reconnect (2026-10-02):** closing the actual client transport
now has integrated coverage: a replacement connection restores the same browser
resource/URL and existing DOM pixels, a completed click is not replayed, and a
fresh click succeeds. Both browser workflows passed together (51.279 seconds,
API 37 / 16 KB, no skips). [Evidence and boundaries](DIRECT_SSH.md) distinguish
this from provider/daemon restart, unknown-delivery recovery and physical-device
acceptance. Only the test APK changed; no signed release was published.

**Integrated Android SSH/Chrome browser (2026-10-02):** the real workspace route,
SSH transport, cmux-tui provider, Chrome pixels/click/keyboard input, address-bar
navigation, routed On Android mode and mode restoration now pass together on the
API 37 / 16 KB emulator (one full workflow, 35.223 seconds, no skips). Eighteen
focused JVM checks and both APK builds/alignment checks also passed. The external
provider still reports URL-based streamed titles; this is recorded as an open
metadata gap. [Evidence and remaining boundaries](DIRECT_SSH.md) include HTTPS,
provider reconnect and physical Pixel/Mac acceptance. No signed release changed.

**Real browser-provider HTTP startup (2026-10-02):** resolved the private Chrome
fixture's stalled cookie-key initialization using Chromium's mock-keychain test
option. The actual cmux-tui/CDP test now defaults to HTTP and passes all frame,
DOM input, navigation, resize and detach assertions (3.469 seconds, no skips).
[Evidence and isolation boundary](DIRECT_SSH.md) preserve the original network
log and exclude production Keychain changes. HTTPS, Android/SSH integration and
physical Pixel/Mac acceptance remain open; no APK changed.

**Real SSH browser provider/key input (2026-10-02):** a published cmux-tui
process and private Chrome now pass decoded-frame, guarded-click, text, delete,
Control-A/F6 event, navigation and pointer-authority recovery after resize checks. Fixed Android
keyboard tokens that the SSH mapper dropped. Nineteen JVM tests passed without
skips; the live-provider case took 8.470 seconds. [Exact evidence](DIRECT_SSH.md)
records that the successful pages were generated local files: Chrome's loopback
HTTP startup remains unresolved. Android SSH/renderer integration, browser/OS
shortcut effects and physical-device acceptance remain open. No APK was built.

**Paired-Mac browser mode switching (2026-10-02):** added the Streamed / On
Android picker, iOS capability explanations, fresh admission checks and remembered
phone-page restoration. Explicit return selects the linked tab; authoritative Mac
inventory retires pages for deleted panels. Twenty-five JVM checks passed;
incremental Android runs passed nine browser regressions/route cases and then both
new mode UI checks (21.524 seconds). [Exact evidence](BROWSER_TUNNEL.md) preserves
the initial two test failures, corrections and final APK identities. Alignment and
screenshot checks passed. Live Mac/Pixel, full-screen and visual acceptance remain
open; no signed APK changed.

**SSH browser mode switching (2026-10-02):** Streamed / On Android now follows
per-panel phone-page preferences and returns to the exact linked streamed tab.
Live workspace inventory reaches the routed browser; removed owners/tabs retire
cached pages. Thirty-two JVM checks passed. Incremental Android runs covered five
component checks, four existing SSH browser checks and the new page-reopen/mode
check (35.802 seconds after an opening-race fix and test-clock correction).
[Exact run boundaries and evidence](DIRECT_SSH.md) retain the failed five-test run.
16 KB checks passed and screenshot inspected. Native Mac mode UI, live CDP,
workspace-route and physical-device acceptance remain open; no signed APK changed.

**Streamed SSH browser renderer (2026-10-02):** existing browser rows now
resolve stable browser/workspace identities and open the shared renderer through
an SSH-only adapter. Guarded frame acknowledgements, PNG validation, viewport,
input and attachment retirement are integrated. Thirty JVM checks and ten Android
checks passed (73.315 seconds, API 37 / 16 KiB), including all seven native Mac
browser regressions. Fixed a stale initial viewport and an invisible IME endpoint
intercepting center taps; keyboard focus remains functional. Debug/test APKs and
16 KB validation passed; screenshot inspected. Mode switching, live CDP and
physical/workspace-route acceptance remain open. [Evidence](DIRECT_SSH.md).

**Streamed SSH browser protocol (2026-10-02):** added separate guarded browser
attachments, frame/token presentation tracking, CDP input/navigation, resize and
lease detach. Twenty-five JVM checks passed, including the pinned cmux-tui binary's
browser-tab state/attach/resize/detach lifecycle. No CDP provider or Android pixels
were exercised. Browser selection, renderer and mode-switch integration remain
open; SSH streamed-browser rows are still unavailable. [Evidence](DIRECT_SSH.md).

**SSH on-device browser (2026-10-02):** terminal title menu now opens the
existing isolated browser through the selected SSH computer. Direct TCP streams,
SOCKS server-side hostnames, localhost routing, reconnect and owner/route retirement
are implemented with no Mac/phone fallback. Twenty-six JVM checks and four Android
cases passed (32.453 seconds, API 37 / 16 KiB); the real localhost page screenshot
was inspected. Full workspace browser navigation, cmux-tui streaming/mode switching,
SSH HTTPS/WebSocket/upload and physical acceptance remain open. [Evidence](DIRECT_SSH.md).

**SSH Files presentation (2026-10-02):** matched the reviewed iOS Add menu,
file/folder/link indicators, dates, folder chevrons, selectable paths, centered
title and bottom transfer display. Added pull to refresh, long-press actions,
locale-aware natural filename sorting and generated photo names. Nine JVM checks
and all eight Android SFTP/browser/picker cases passed (108.982 seconds, API 37 /
16 KiB); browser and photo-preview captures inspected. Swipe-delete, broader media,
physical-device and final visual acceptance remain open. [Evidence](DIRECT_SSH.md).

**Real SSH upload pickers (2026-10-02):** fixed stale picker callbacks that
retained the initial null folder and silently dropped selections. All eight
SFTP/browser cases passed in 102.598 seconds on API 37 / 16 KiB, including actual
Android document/photo picker cancellation/reopening, multiple documents, duplicate
names, exact transfer bytes and a visible image preview. [Evidence](DIRECT_SSH.md)
retains the failing runs and Android 17 package/Done selector correction. Physical
Pixel, multi-photo/video, photo naming, export, cloud providers, process restoration
and final UI acceptance remain open. No signed APK or broad pin changed.

**SSH Files insertion and interruptions (2026-10-02):** inserted paths now use
shell quoting, matching iOS; uploads verify known source size before publication.
Five JVM cases, six SFTP/browser cases and three shell UI cases passed on the same
API 37 / 16 KiB APKs. Tests cover cancelled transfers, local cleanup, source failure,
size changes, a surviving connection and quoted-path delivery with the draft
preserved. [Exact evidence](DIRECT_SSH.md) retains the initial emulator launcher
ANR, successful identical-APK rerun and cancellation/publication boundaries.
System picker, media/export and physical/visual acceptance remain open.

**Plain SSH folder tracking (2026-10-02):** Files now starts at the shell's
reported directory via passive OSC 7 observation, with remote home fallback.
Seven parser and four path JVM tests pass; three real SSH shell UI checks pass
in 76.967 seconds on API 37 / 16 KiB, including special-character folder browsing,
preserved draft/session and forgotten directory after reconnect. Screenshot and
alignment evidence are in [DIRECT_SSH.md](DIRECT_SSH.md). Physical Pixel and final
visual acceptance remain open; no signed APK or broad upstream pin changed.

**SSH Files browser (2026-10-02):** terminal Files now opens a direct SFTP sheet
with navigation, transfers, previews, folder creation, rename, confirmed deletion
and path actions. Four JVM checks, three real SFTP/browser checks and three shell
UI checks passed on the final API 37 / 16 KiB APKs. Opening Files preserves the
unsent composer draft and PTY. [Exact evidence and boundaries](DIRECT_SSH.md) retain
picker/media/fault/physical acceptance; plain-shell directory reporting is covered above.
The source audit also corrects the prior task list: reviewed iOS SSH UI does not
expose workspace/pane/tab rename or move actions. Native Mac action requirements
are unchanged. No signed release or broad upstream pin advanced.

**cmux-tui owner recovery and layout actions (2026-10-01):** saved selections can
restart the phone-owned session and recover the original terminal/history;
discovery and missing desktop owners remain read-only. New Screen, New Tab and
both split directions select the created terminal. The saved idle policy is sent
only to capable servers; 0.13.4 still lacks that capability. Fifty-seven focused
JVM checks and six real Android SSH workspace checks passed (171.110 seconds,
API 37 / 16 KiB). [Exact evidence](DIRECT_SSH.md) includes the initial timeout,
inspected captures and outstanding server-version, files/browser, visual and
physical-device acceptance. No signed release or broad upstream pin changed.

**cmux-tui installer/phone-owned creation (2026-10-01):** New cmux Workspace
starts only `cmux-android`, installing a verified pinned platform binary when one
is absent. Listing does not install; activation refuses to replace an existing
file. Fifty-four JVM checks, four mixed-workspace Android checks and a live Android
HTTPS/SFTP installation check passed against private Mac fixtures. [Exact evidence and limits](DIRECT_SSH.md) retain
server-version gaps, owner-stop restoration, other-platform/Pixel acceptance and
remaining workspace/browser/files/UI work. The signed release is unchanged.

**Mixed SSH workspaces (2026-10-01):** the Computers Workspaces entry now combines
existing cmux-tui owners, tmux and plain shells, with input/history and provider
creation/end actions. Forty-seven JVM checks and three real Android SSH workspace
checks passed on API 37 / 16 KiB, covering reopen, reconnect, saved-runtime recovery
and persisted Disconnect. [Exact evidence and remaining work](DIRECT_SSH.md) include
installer/owned-session creation, remaining actions, SSH browser/SFTP, final iOS UI
parity and physical Pixel acceptance. Signed delivery remains unchanged.

**cmux-tui provider/renderer (2026-10-01):** account/transport-owned host and
session providers now publish live inventory and support guarded creation/end
actions. The shared terminal screen can display the cmux-tui Ghostty mirror with
input, server metadata and visibility/geometry lifecycle. Forty-five focused JVM
checks, three Android renderer component checks and ten native Ghostty checks passed.
The binding now supports the server's 1×1 grid after a verified native rebuild.
[Exact evidence and scope](DIRECT_SSH.md): mixed workspace navigation and real
cmux-tui-over-Android-SSH/Pixel acceptance remain open; no signed APK changed.

**cmux-tui discovery/inventory (2026-10-01):** the SSH adapter now locates existing
owners without starting them, verifies named/hashed socket identity and parses
the ordered workspace hierarchy. Durable selections reject ambiguous or replaced
targets. Forty focused JVM checks passed, including the production discovery
commands and recovery of terminal/history after a real 0.13.4 owner restart.
[Exact scope and remaining work](DIRECT_SSH.md) include provider/UI integration,
Android SSH/device acceptance and the still-open server-version geometry gap.
No APK or physical installation changed.

**cmux-tui relay foundation (2026-10-01):** bounded request/event framing,
capability/session checks, initial replay, terminal input, resize leases and
detach fences are implemented. Twenty-eight focused JVM checks passed, including
real cmux-tui 0.13.4 and tmux processes. Reattachment preserved the durable terminal
and history. A separate two-client probe confirmed that 0.13.4 fails to restore
desktop geometry after phone release; it also lacks the idle-close capability.
[Evidence and remaining integration](DIRECT_SSH.md) distinguish the relay core
from unimplemented cmux-tui inventory/UI/installer and Android/device acceptance.
No APK or physical installation changed.

**Shell retry and tmux cleanup (2026-10-01):** ended plain shells reconnect from
their terminal screen; failed connection keeps the old screen, while success
opens a fresh PTY without replaying input. Abandoned-group cleanup checks current
attachment inside tmux. Control clients confirm window membership when another
group's deletion broadcasts a close notification. Twenty-six focused JVM checks,
three shell and four real SSH/tmux Android checks passed on the same APKs (API 37 /
16 KiB). [Exact results and remaining work](DIRECT_SSH.md). Signed 284 and the
physical Pixel installation remain unchanged.

**tmux saved selection and target binding (2026-10-01):** saved Android state now
reattaches the exact pane through a fresh SSH runtime and preserves Disconnect.
Capture/input commands bind session/window/pane at server execution, and pending
input chunks cannot continue into a replacement attachment. Twenty-five focused
JVM checks and four real SSH/tmux Android checks passed (API 37 / 16 KiB, 58.451
seconds). Actual OS process-death/Pixel acceptance, stale-target UI cases, atomic
phone-group collection, cache bounds and mixed-provider integration remain open.
[Evidence and scope](DIRECT_SSH.md). Signed 284 is unchanged.

**tmux visible recovery (2026-10-01):** the open pane now recovers after an SSH
drop, retaining its remote session and output. Explicit Disconnect stays paused
until Reconnect; progress/failure feedback is visible. Split actions target the
original session/window/pane and reject a pane moved elsewhere. Twenty-four focused
JVM checks and three real SSH/tmux Android checks passed (API 37 / 16 KiB, 50.212
seconds). Cold-process selection, further capture/input races, failed-attach and
foreground fault acceptance, plain-shell retry, mixed providers, cache bounds and
physical-device parity remain open. [Exact evidence](DIRECT_SSH.md). Signed 284 and
the Pixel installation remain unchanged.

**tmux workspace UI (2026-10-01):** saved hosts now discover existing tmux
sessions and open panes through the shared Ghostty terminal screen. New workspace,
new terminal, splits and confirmed ending are implemented, with server/session
identity guards, phone-group cleanup and original window-selection preservation.
Pane labels follow topology updates. Fifteen focused JVM checks and two real
SSH/tmux Android UI checks passed (API 37 / 16 KiB, 30.598 seconds); final captures
were inspected. The mixed shell/tmux/cmux-tui tree, pane-move/reconnect/cold-start
acceptance, idle/cache policy, SSH Files/browser/media, physical-device and full
visual/accessibility parity remain open. [Receipts](DIRECT_SSH.md). Signed 284 and
the Pixel install are unchanged.

**tmux control foundation (2026-10-01):** raw non-PTY exec streams, bounded
control parsing, ordered replies, history/mode seeding, live output, server pane
geometry and grouped-session teardown are implemented. Twelve JVM checks pass,
including a real tmux 3.7c process that preserves the original selected window
and session; fourteen Android transport checks pass. Android adds restoration of
bracketed-paste mode enabled before attach. The control client is not yet wired
into the Android workspace list/renderer; real tmux over Android SSH, mixed-provider
navigation, discovery/actions, reconnect and physical acceptance remain open.
See [layer-specific receipts and limits](DIRECT_SSH.md). Signed 284 is unchanged.

**Plain SSH shells (2026-10-01):** saved hosts now open account-owned PTYs with
Ghostty rendering, terminal-query replies, keyboard/composer, toolbar, text paste,
scrollback, zoom and Text view. Navigation retains the same shell; explicit close
releases its channel and leaves the shared transport usable. Account retirement
rejects input. Channel cleanup runs off Main to prevent Android's network-thread
restriction from corrupting SSH packet state. **Two shell UI, thirteen transport,
four mirror and nine native Ghostty checks passed** on API 37 / 16 KiB. Native
alignment/package gates passed. Mixed tmux/cmux-tui providers, SSH Files/browser,
media paste, mouse forwarding, idle enforcement and physical acceptance remain
open. Signed 284 and the Pixel are unchanged. [Evidence](DIRECT_SSH.md).

**SSH host UI and account ownership (2026-10-01):** Computers/Settings now open
saved SSH hosts, add/edit/delete, connect/disconnect and key/jump-host selection.
Root prompts show unknown/changed host fingerprints and explicit trust decisions.
The shared runtime is scoped to a login incarnation and retires connections/prompts
on account change. Platform biometric presentation is wired to the exact prepared
Signature; hardware, enrollment, rotation and API 26 runtime acceptance remain
unverified. **Fifteen JVM and four Android 17 / 16 KiB fixture UI checks passed.**
The final Android check verifies that restoring a stale host draft cannot overwrite
a newer route; saves compare and write atomically and exclude concurrent key deletion.
Full-app account/navigation restoration, idle policy, mixed terminal/workspace
providers, SSH Files/browser and physical acceptance remain open. No new signed
release or Pixel installation. See [DIRECT_SSH.md](DIRECT_SSH.md).

**SSH Keys UI (2026-10-01):** signed-in Settings now opens generated/imported key
management with optional biometric policy, public-key copy/share, rename and
confirmed deletion. Private-key/passphrase inputs are excluded from saved screen
state, the import window blocks screenshots, and file reads are bounded before
decoding. **Three JVM and three Android 17 / 16 KiB UI checks passed**, followed by
one focused check after correcting the list screenshot wait. List/generate captures
were inspected. System picker/share integration, real process restoration,
biometric presentation and physical acceptance remain open; saved SSH Computers
and terminal integration are next. See [DIRECT_SSH.md](DIRECT_SSH.md).

**Update workflow (2026-10-01):** upstream iOS CI/release policy was reviewed at
`15aa32c`. New workflows track upstream mobile/shared changes in one review issue,
batch main Android previews after five relevant commits or three hours, and
promote a verified APK manually. Nine policy tests and Actionlint passed; a live
comparison correctly marked its capped inventory incomplete. These schedules
remain inactive until the workflows reach main. See [UPDATES.md](UPDATES.md).

**SSH connection coordinator (2026-10-01):** explicit and automatic opens now
share in-flight/live connections; view cancellation leaves shared work alive.
Trust questions queue/coalesce, stale answers are rejected, disconnect persists
pause, and failures wait for explicit retry. **25 focused JVM and 13 Android 17 /
16 KiB transport checks passed.** Account/UI wiring, idle closure, biometric
presentation and mixed workspace providers remain open. See
[DIRECT_SSH.md](DIRECT_SSH.md). Signed 284 and the physical Pixel are unchanged.

**SSH transport checkpoint (2026-10-01):** the production host store and vault
now connect through a scoped SSH transport with strict trust decisions, nested
jump channels, exec, PTY input/resize and SFTP. Owner/route/key/pin changes retire
sessions; silent jump peers have bounded reads and dropped targets close the
whole route. **Eleven Android 17 / 16 KiB transport checks and twenty focused JVM
checks passed.** Connection coalescing/retry, prompt and Computers/key UI,
biometrics, mixed workspace providers and SSH Files/browser integration remain
outstanding. See [DIRECT_SSH.md](DIRECT_SSH.md) for exact evidence and limits.
Only one local emulator is retained following the user's storage request.
Signed 284 and the physical Pixel installation are unchanged.

**SSH private-key foundation (2026-10-01):** generated P-256 Keystore keys,
encrypted OpenSSH Ed25519/ECDSA import storage, guarded signing leases, rename
and recoverable deletion now have **eight passing Android checks on API 26 and
eight on API 37 / 16 KiB pages**. Sixteen host-store JVM checks also passed.
JSch 2.28.0 and BC 1.86 are now app dependencies with complete packaged notices.
This code is not yet connected to the SSH session manager or Computers UI.
Biometric UI, physical hardware/lock behavior and actual process-death acceptance
remain open. See [DIRECT_SSH.md](DIRECT_SSH.md). Signed 284 and the Pixel are unchanged.

**Direct SSH source audit (2026-09-30):** the newer candidate exposes SSH computers
and key management in normal signed-in iOS navigation. Mixed plain-shell, tmux and
cmux-tui workspaces, SFTP and SSH browser forwarding are substantial outstanding
Android requirements. [DIRECT_SSH.md](DIRECT_SSH.md) records the exact sources,
superseded PRD decisions, server-version caveats and implementation/acceptance
gates. This is source evidence, not Android functionality or proof of an iOS binary
release. The subsequent opt-in engine experiment passed **five runtime checks on
API 26 and five on API 37 / 16 KiB pages**, including Keystore signing, encrypted
key imports, host-key refusal, PTY/exec, SFTP and nested forwarding teardown.
Production host metadata now has an atomic Android storage adapter, scoped dial
plans and versioned trust decisions; **16 JVM tests passed**, including corruption,
write failures, concurrent saves and stale-answer rejection. Private-key storage
has since advanced in the checkpoint above. Session/UI integration, host-storage
Android runtime, biometric and physical acceptance remain open. The engine
experiment itself adds no dependency to the delivered app.
The broad implemented reference, signed 284 and Pixel installation are unchanged.

**Files navigation restoration (2026-09-30):** gallery and terminal-path sheets
retain nested folders/current preview and gallery controls within their admitted
terminal owner. Reconnect refetches content; session routes wait for a fresh scan
and cannot silently switch to another session. **14 JVM and six Android 17 / 16 KiB
tests passed**, including the production Files sheet after real process death and
a stale-session request regression. Scroll/zoom/document selection, oversized task
state fallback and physical acceptance still need work. See
[WORKSPACE_SELECTION.md](WORKSPACE_SELECTION.md). Signed 284 and the Pixel are unchanged.

**Changes detail restoration (2026-09-30):** the selected diff path and collapsed
folders now survive RPC-client replacement and Android task restoration, within
the admitted login/account/team/Mac/build/workspace. Fresh inventory resolves the
path after reordering; a removed file shows a notice instead of opening its former
neighbor. **11 JVM and four Android 17 / 16 KiB tests passed**, including actual UI
process replacement. Diff scroll/expansion, preview overlays, nested Files and
physical acceptance remain open. See [WORKSPACE_SELECTION.md](WORKSPACE_SELECTION.md).
This source/debug change postdates signed 284; the Pixel installation is unchanged.

**Signed input-delivery build 284 (2026-09-30):** full CI passed at `0db3c17`.
The downloaded release matches the stable certificate and passes all native
LOAD/RELRO and ZIP 16 KiB checks. Upgrade from 274 and sign-in-screen launch were
verified on Android 17 with compatibility mode off. This packages the retained
input/composer changes; physical capability acceptance and this signed package’s
native account workflow remain pending. The newer event-stream fixes below are
source-only. See [PIXEL_INSTALL.md](PIXEL_INSTALL.md).

**Terminal event-lane ownership (2026-09-30):** terminal-scoped native event
frames now require a matching `payload.surface_id` before entering the merged
queue. Wrong-terminal payloads and forged local-marker bytes are refused.
Malformed optional events no longer close control RPC; a failed optional reader
can restart for a new subscription without reopening on liveness reassertions.
**26 focused JVM tests passed**; these source changes are not in signed 284.
Native/physical event-lane acceptance and the broader event/replay audit remain open.
See [UPSTREAM_REFRESH_2026_09_30.md](UPSTREAM_REFRESH_2026_09_30.md).

**Identified-input production integration (2026-09-30):** wire identities/ACKs,
both native lane types, bounded sender and retained foreground session now share
all five input methods. Fresh admission negotiates support; legacy hosts retain
their existing path. Immutable target/payload snapshots, ordered media/mouse input,
reconnect fencing and explicit pause/resume are implemented. **97 focused JVM
and nine Android runtime tests passed**, including lost-reply Activity recreation
and real JNI/Iroh ACK lanes. Physical identified-capability acceptance remains open. The composer lifecycle
follow-up passed 32 focused JVM and six Android tests: text/image sends now settle
across Activity recreation through the retained queue. Its emulator run recovered
from a System UI ANR and is not performance evidence. See
[TERMINAL_INPUT_DELIVERY.md](TERMINAL_INPUT_DELIVERY.md). Signed 284 includes this work.

**Pixel native recovery (2026-09-30):** debug `1d5958f` was installed in place,
preserving sign-in. The unlocked phone loaded the Mac's workspaces, executed a
composer command, automatically restored the same terminal after real process
termination, accepted direct typing afterward, and fetched/rendered a Markdown
file from the Mac. Normal screen sleep was restored. Signed 274 is a separate
package; these checks do not cover the newer identified-input foundation. See
[PIXEL_INSTALL.md](PIXEL_INSTALL.md).

**Signed integration build 274 (2026-09-30):** the accumulated navigation,
startup/order, Activity/process recovery and entry-route fixes passed full CI at
`1d5958f`. The downloaded release matches the stable signing certificate and passes
all five native libraries' LOAD/RELRO checks and ZIP 16 KiB alignment. Upgrade over
261 and launch were verified on API 37 with `pageSizeCompat=0`; the sign-in screen
was inspected without a compatibility warning. This establishes package readiness,
with physical Pixel/Mac acceptance still outstanding. See
[PIXEL_INSTALL.md](PIXEL_INSTALL.md). The upstream refresh found substantial changes
at `204a11d`; [UPSTREAM_REFRESH_2026_09_30.md](UPSTREAM_REFRESH_2026_09_30.md)
records the incomplete inventory and prioritizes a protocol/feature audit before
advancing the implemented reference. The goal remains incomplete.

**Pairing/notification entry lifetime (2026-09-30):** pending and consumed entry
routes now survive Android task restoration without rereading the original pairing
link. Confirmation survives process death; newer links and notification taps take
priority. Signed-out links wait for sign-in, Iroh lookup waits for the current
account directory, and notification routing waits for initial saved-Mac admission.
The installed manifest now also registers the Android scheme and fixed iOS dev
scheme. **24 JVM tests passed; 22 Android lifecycle/delivery tests passed**, then
**three resolver tests passed after the manifest-only change**. Real UI-process
replacement and restored OS bundles were verified through MainActivity. Physical
URL/directory/system-shade acceptance remains open. See
[ENTRY_ROUTES.md](ENTRY_ROUTES.md). Signed build 261 predates this checkpoint.

**Android task process recovery (2026-09-30):** saved-instance state now restores
workspace/pane or Changes after actual UI-process death, after account/Mac/build
admission and fresh inventory. Startup tickets retain their original deadline;
restoration never repeats creation. Back cancels pending reconnect routing.
**36 JVM tests passed**. A **53-test Android 17 / 16 KiB batch passed** before the
final legacy cached-owner adjustment; **all twelve process tests passed again on
the final APKs in 113.162 seconds**. Restored OS bundles and changed UI PIDs were
verified, and the restore screenshot was inspected. Nested details/overlays,
unacknowledged creation and physical acceptance remain open; entry-route lifetime
is covered by the subsequent checkpoint above. See [WORKSPACE_SELECTION.md](WORKSPACE_SELECTION.md). The Pixel is
still unavailable to ADB; signed build **261** is unchanged.

**Workspace/pane Activity recreation (2026-09-30):** the selected workspace,
terminal/browser/Mac/Simulator pane and Changes destination now survive Activity
recreation in the scoped session, including offline panels. Existing startup
tickets/deadlines and remembered-browser intent are preserved. Changes refresh no
longer switches to a terminal. **26 JVM and 41 Android 17 / 16 KiB tests passed**.
This preceded process recovery above; nested detail/overlay state, interrupted-
creation recovery and physical acceptance remain open. See
[WORKSPACE_SELECTION.md](WORKSPACE_SELECTION.md). Signed build 261 is unchanged.

**Empty workspaces and delayed panes (2026-09-30):** empty rows now open into a
waiting view with creation controls; late known panes and separately discovered
browsers resolve automatically. Browser discovery populates the terminal menu and
upgrades a matching raw Mac panel without stealing explicit selection. Host-driven
changes to an interim fallback preserve remembered-tab intent. **50 JVM and 39
Android 17 / 16 KiB tests passed**; the waiting screenshot was inspected. The Pixel
was not visible to ADB/USB, so physical acceptance and current-screen/process
restoration remain open. See [WORKSPACE_SELECTION.md](WORKSPACE_SELECTION.md).
Signed build 261 is unchanged.

**Workspace refresh ordering (2026-09-30):** foreground/feed reads now share scoped
publication ordering and mutation boundaries. Older responses cannot remove a
newly created terminal or restore a deleted pane. Late create callbacks use newer
validated inventory; explicit navigation to known panes remains available during
a pending create. **39 JVM and 34 Android 17 / 16 KiB tests passed**, after fixing
one navigation regression found in the first runtime batch. Physical acceptance,
current-screen restoration and general delayed-pane discovery remain open. See
[WORKSPACE_SELECTION.md](WORKSPACE_SELECTION.md). Signed build 261 is unchanged.

**Created-terminal startup (2026-09-30):** new terminals remain selected while
starting, with readiness-gated output/input, a 30-second deadline, iOS-style timeout
recovery and explicit Retry. Partial create results preserve other workspaces;
late replies cannot interrupt newer navigation. **35 JVM and 27 Android 17 /
16 KiB tests passed**, including the real timeout and existing task/input/tab flows.
This preceded the refresh-ordering checkpoint above. Physical acceptance and
current-screen restoration remain open. See [WORKSPACE_SELECTION.md](WORKSPACE_SELECTION.md). Signed build 261
is unchanged.

**Remembered-tab navigation (2026-09-30):** persistent memory is wired into workspace
opening and explicit pane changes for terminal, Mac surface, browser stream,
Simulator and local browser selections. Waiting restores preserve memory; late
results and lifecycle changes are fenced. Browser discovery stays separate from
ordinary workspace surfaces. Local Close clears memory, including empty-workspace
cases. **33 JVM and 23 Android 17 / 16 KiB tests passed** after correcting two
browser inventory regressions. This preceded the startup checkpoint above. General delayed discovery,
true process-death and physical acceptance remain open. See
[WORKSPACE_SELECTION.md](WORKSPACE_SELECTION.md). Signed build 261 predates this work.

**Last-tab memory foundation (2026-09-30):** the bounded five-kind preference map,
account/Mac/build keys, encrypted atomic storage adapter and pending restore
decisions are implemented. **23 JVM and three Android 17 / 16 KiB storage tests
passed**. This foundation preceded the UI integration recorded above. See
[WORKSPACE_SELECTION.md](WORKSPACE_SELECTION.md).

**Fresh workspace selection (2026-09-30):** default opening now respects focused
non-terminal panes, host spatial order when no terminal exists, Simulator
descriptors and terminal readiness/focus. Explicit pane opens remain exact.
**31 JVM and 12 Android 17 / 16 KiB tests passed**, including existing browser,
file/Markdown and Simulator regressions. Persisted last-tab memory, delayed browser
discovery integration and selection synchronization remain open. Signed build 261
predates this change. See [WORKSPACE_SELECTION.md](WORKSPACE_SELECTION.md).

**Workspace lifetime (2026-09-30):** confirmed removal on a connected owning Mac
retires local browser pages and pending creation; disconnected/incomplete snapshots
preserve them. Invalid inventory cannot masquerade as deletion. **37 JVM and six
Android 17 / 16 KiB routing tests passed**. Signed build 261 predates this fix.
Full persisted tab/default-selection parity is still open and now source-audited
in [WORKSPACE_SELECTION.md](WORKSPACE_SELECTION.md). See [LOCAL_BROWSER.md](LOCAL_BROWSER.md)
for runtime evidence; physical Pixel/Mac acceptance remains open.

**Signed build 261 and browser lifecycle acceptance (2026-09-30):** the browser
milestone passed full CI at `3b0f6fd`. Its downloaded APK passes stable-signature,
native/ZIP 16 KiB checks and Android 17 emulator launch. Three further emulator
tests verify actual system-picker selection and multipart upload bytes, local-tab
Activity recreation, and stale-picker-result disposal followed by a successful
new selection. Settled keyboard dismissal is checked through Android insets.
The production debug APK is unchanged by this test-only follow-up. Physical
Pixel/Mac and remaining parity work are still open. See [PIXEL_INSTALL.md](PIXEL_INSTALL.md)
and [LOCAL_BROWSER.md](LOCAL_BROWSER.md).

**Immediate direct-keyboard focus (2026-09-30):** selecting direct mode now
immediately focuses the terminal grid while the native editor mounts. A regression
reproduced lost early hardware keys before the fix; exact-byte and existing
IME/pause/target-switch checks now pass (two Android 17 / 16 KiB tests). Composer
draft retention also passes. Physical retest and signed delivery remain open; see
[TERMINAL_INPUT_DELIVERY.md](TERMINAL_INPUT_DELIVERY.md).

**Mac browser tunnel foundation (2026-09-30):** reviewed the newer `204a11d`
contract and implemented native connection/listing lanes, raw transfer, half-close
and lease cleanup. 33 focused JVM tests pass with unchanged Swift wire fixtures.
The SOCKS/router follow-up passes 18 JVM tests (including 69 Swift loopback
classifications) and an Android 17 / 16 KiB asynchronous socket round trip with
half-close. Session owner binding, fresh capability/route admission, ten-second
policy refresh and independent cancellation now pass 40 focused JVM checks.
A dedicated-process WebView adapter now passes two Android runtime checks for
SOCKS/localhost/HTTPS/WebSocket/service-worker routing and per-owner storage,
including process restart. Production navigation now opens the isolated browser
through a bound main-process service, with owner-specific feed/connection holds
and storage retirement. Sixteen focused JVM checks pass. Four distinct Android
checks have passing evidence across runs: presentation/Back/reopen/pane selection,
retirement/cleanup, and two existing browser regressions. The four-case batch had
one System UI ANR interruption; its focused rerun passes with the same APKs.
Three additional native runtime checks now pass through real JNI/Iroh: large
bidirectional bytes/half-close, rejected/cancelled lane isolation and a generated
HTTP server reached through the coordinator, owner network and SOCKS proxy.
Generated peers do not prove live account/Mac interoperability, Activity/feed-hold
lifetime, startup stability or physical acceptance. The integration APK is now
installed on the Pixel; a live read-only check verifies identity/account/native
access but confirms stable Mac 0.64.25 lacks `browser.tunnel.v1`. Physical browser
tunneling requires a newer compatible host; the official nightly candidate is now
signature-verified, staged separately and launched, with its live account/pairing
and capability still unverified. See
[BROWSER_TUNNEL.md](BROWSER_TUNNEL.md). Signed 284 predates this source work.
The routed browser's rotation reload/state-loss regression is now fixed: a clean
three-case API 37/16 KiB batch verifies page-local state and history across both
rotations, workspace return/reopen/pane selection, and owner-retirement cleanup.
This is emulator evidence; physical rotation, chooser and process-death checks
remain open.
Two additional routed presentation checks pass on API 37/16 KiB: real Android
document-picker cancel/reopen, complete multipart file upload through the proxy,
connection-hold lifecycle, and address entry. They use a generated host; physical
Mac/Pixel and native Iroh upload acceptance remain open.
A focused browser-subprocess kill/reopen check also passes: the old connection
hold releases, the parent returns, and a new process restores the committed URL
and same-owner cookie. Whole-app process death and live physical feed-hold races
are still unverified; this does not claim recovery of unsaved DOM/history.

**Phone-local browser navigation (2026-09-30):** `New Browser` is now integrated
into workspace actions and terminal/Mac-surface pickers. Connected, capable Macs
receive one create request; offline/unsupported/rejected/unknown outcomes open the
local pane. Request cancellation, scoped ownership, Back/restore, Close-to-terminal
and empty-workspace reopening are implemented. **17 JVM tests and five full
NativeScreen/RPC Android 17/16 KiB tests passed**. Later picker/recreation evidence
is recorded above. Physical Pixel/Mac, landscape and broader accessibility
acceptance remain open. Signed build 261 includes this milestone. See [LOCAL_BROWSER.md](LOCAL_BROWSER.md).

**Phone-local browser pane (2026-09-30):** a separate WebView now implements the
iOS-style address/history/reload/stop/close controls, same-pane popup/form handling,
persistent cookies/storage, remount and renderer/network recovery. Seven Android
17/16 KiB tests passed with painted page pixels. The later navigation checkpoint
above exposes this pane in the main app.
Signed build 257 predates this work. See [LOCAL_BROWSER.md](LOCAL_BROWSER.md).

**Phone-local browser foundation (2026-09-30):** Android's resolver matches 103
cases exported from the unmodified pinned Swift implementation, on both JVM and
Android 17/16 KiB. Local surface/store state now has scoped identity, once-consumed
commands and stale-view fencing. Ten JVM tests and the complete Android runtime resolver
corpus pass. The pane checkpoint above adds WebView/UI; routing remains open.
See [LOCAL_BROWSER.md](LOCAL_BROWSER.md).

**Signed build 257 (2026-09-30):** the accumulated Simulator milestone passed full
integration CI at `9753083`. The downloaded APK's stable certificate, all five
native libraries, ZIP alignment and Android 17/16 KiB launch were independently
verified. This supersedes signed build 248; both Simulator viewers are included.
The physical Pixel remains unavailable to ADB. Full parity is incomplete; the
next phone-local browser work is audited in [LOCAL_BROWSER.md](LOCAL_BROWSER.md).
Download/checksum details: [PIXEL_INSTALL.md](PIXEL_INSTALL.md).

**Legacy Simulator pane (2026-09-30):** the image/RPC session is now integrated
with workspace navigation, ownership indicators, touch/text/hardware controls,
background/reconnect handling and a serialized transition to v2. Thirty-five
focused JVM tests and eight Android 17/16 KiB tests passed (five legacy checks
plus three v2 regressions), including painted PNG/JPEG pixels, host image rotation,
ownership restrictions, input non-replay and delayed stop/handoff. Physical
Mac/Pixel acceptance and full accessibility/theme checks remain open. See
[SIMULATOR_STREAMING.md](SIMULATOR_STREAMING.md).

**Simulator v2 pane (2026-09-30):** the video decoder, connection owner, workspace
simulator inventory/routing, aspect-fit touch, text/buttons, persistent quality,
device selection and recovery overlays are now integrated. The Linux decoder
artifact passes real 16 KiB runtime checks. Twenty focused JVM checks and three
final Android pane tests pass, including actual pixels and a borrowed RPC route.
A UI-dispatcher startup race was found and fixed with an explicit publication
gate. Dynamic v2 rotation/accessibility/theme acceptance
and real Mac/Pixel verification remain required; see
[SIMULATOR_STREAMING.md](SIMULATOR_STREAMING.md). Signed build 248 does not include
these simulator changes.

**Simulator video decoder (2026-09-30):** the production presenter now renders
AVC/HEVC into an Android Surface, using low-latency hardware where supported and
a pinned native software fallback. The initial MediaCodec-only emulator test
exposed buffering incompatible with the host's two-frame credit window; the
fallback fixes that without acknowledging unseen frames. Seventeen focused JVM
and four Android 17 16 KiB tests pass, including actual frame pixels and the
first-frame flow-control boundary. Later checkpoints above add pane/lifecycle
integration and Linux verification; legacy and physical acceptance remain open. See
[SIMULATOR_STREAMING.md](SIMULATOR_STREAMING.md).

**Signed build 248 (2026-09-30):** the combined zoom/panels/Todo milestone
passed CI at `ecdccb0`. The downloaded APK has the existing stable certificate,
passes native/ZIP 16 KB checks, and launches on Android 17's 16 KiB emulator with
`pageSizeCompat=0`. This supersedes signed build 244. Pixel installation and full
native workflow acceptance remain open; see [PIXEL_INSTALL.md](PIXEL_INSTALL.md).

**Simulator streaming prerequisite (2026-09-30):** v2 binary wire, admitted
Iroh lane, presentation/input session and pure lifecycle policy are implemented.
22 focused JVM checks pass, including byte-for-byte comparison with fixtures
exported by the unmodified upstream Swift codec. This protocol-only checkpoint
preceded the viewer/decoder work above; legacy and physical acceptance remain open. See
[SIMULATOR_STREAMING.md](SIMULATOR_STREAMING.md). The browser audit also clarified
that the existing Android remote browser implements the iOS `.browserStream`
RPC path; the separate phone-local `.browser` WebView mode was added in the later
browser checkpoints above.

**Native Todo checklist (2026-09-30):** add/edit, three item states, drag ordering,
swipe deletion, automatic/manual status, progress and offline read-only display
are implemented from the pinned iOS model. Eight Todo JVM tests and four Android
17 16 KiB UI/RPC tests pass, including rejected-delete rollback and lost-add-reply
reconnect without resending. Final screenshots and native/ZIP alignment were
checked. This is fixture evidence; physical Mac/Pixel acceptance remains open.
See [TODO.md](TODO.md).

**Mac surface inventory and panels (2026-09-30):** nonterminal descriptors are
preserved and navigable, panel-only workspaces open, file/Markdown panels use the
separate exact-file `panel.artifact.v1` scope, and fallback cards can focus their
surface on the owning Mac. Eleven focused JVM and three 16 KiB Android UI/RPC
tests pass, including painted Markdown and multi-Mac routing. Todo controls were
added in the later checkpoint above. Both simulator panes are now implemented;
the additional iOS phone-local browser path was added in the later checkpoints. See
[MAC_SURFACES.md](MAC_SURFACES.md).

**Signed build 244 (2026-09-30):** integration CI passed at `3c80608`, the signed
artifact was downloaded, its certificate matches the stable key, and native/ZIP
16 KB alignment and Android 17 16 KiB emulator launch were independently checked.
This supersedes older build-157 delivery statements. It does not include later
zoom/panel work and is not installed on the Pixel. See
[PIXEL_INSTALL.md](PIXEL_INSTALL.md#signed-integration-build-244--2026-09-30).

**Terminal zoom (2026-09-30):** per-view live sizing, pinch and toolbar steps,
saved-default/reset/restore controls, and scoped `terminal.set_font` events are
implemented. Twelve focused JVM tests and five Android UI/RPC cases pass on the
16 KiB Android 17 emulator, including held fingers across repeated resizes and
no accidental remote mouse/scroll input. Native/ZIP alignment passes. Pixel
acceptance and exact font/glass appearance remain open; this feature is not in
the signed integration run at `3c80608`. See [TERMINAL_ZOOM.md](TERMINAL_ZOOM.md).

**Terminal shortcut customization (2026-09-30):** the bar now follows the iOS
configurable order and supports hiding/reordering all modifiers, navigation,
paste, Files, zoom and launcher buttons. Custom text actions can be added,
edited, deleted and retained through reset. The final real UI/RPC workflow passes
on Android 17's 16 KiB kernel; 14 focused JVM tests pass. Earlier keyboard and
modifier regressions also pass. Current Linux native GVI2 artifacts are verified
on both 4 KiB and 16 KiB kernels (18 native tests on each), and four app adapter
tests pass on 16 KiB. Pixel acceptance remains open. See
[TERMINAL_SHORTCUTS.md](TERMINAL_SHORTCUTS.md) and
[GHOSTTY_VT_ANDROID.md](GHOSTTY_VT_ANDROID.md).

**Ghostty app integration (2026-09-29):** Android byte/hybrid streams now use the
pinned native Ghostty core. Replays materialize replacements before retiring old
owners; disposal closes native state, and pure-grid sessions allocate no parser.
Copied snapshots feed the Canvas painter, including five colored underline
styles, faint decorations and hollow cursors. All 23 focused JVM and 10 Android
emulator cases pass, including captured Vim and production raw-stream recovery/
scrollback. APKs build and native/ZIP 16 KB checks pass; screenshots were inspected.
CI native preparation and portable artifact generation are wired, with the first
remote native job still pending. The Pixel disconnected before installation.
Inline graphics, performance, remaining terminal fidelity and physical acceptance
remain open. See [GHOSTTY_VT_ANDROID.md](GHOSTTY_VT_ANDROID.md).

**Android Ghostty binding prerequisite (2026-09-29):** a separate native module
now owns terminal/render resources, returns copied bounded snapshots and rejects
stale handles. It preserves grapheme clusters, rich styles, modes and scrollback;
history reads restore the live viewport. An upstream header/implementation unit
mismatch was corrected in the binding's explicit byte-budget API. Three JVM and
seven Android emulator cases pass; AAR/test APK build and 16 KB checks pass.
The module is not yet wired into the app. Production renderer/replay lifecycle,
images, CI preparation and physical acceptance remain open. See
[GHOSTTY_VT_ANDROID.md](GHOSTTY_VT_ANDROID.md).

**Upstream Android terminal-core prerequisite (2026-09-29):** the pinned Ghostty
VT C library now has a reproducible Android arm64 build and synthetic runtime
probe. The unmodified library failed RELRO alignment; an isolated common-page-size
patch fixes it. Both native artifacts pass 16 KB ELF checks, and 100 terminal
lifecycles pass on the Android 17 emulator (4 KB runtime pages). The app still
uses its existing renderer/parser; native binding, glyph/image integration and
physical acceptance are unfinished. See [GHOSTTY_VT_ANDROID.md](GHOSTTY_VT_ANDROID.md).

**Pixel installation checkpoint (2026-09-29):** debug app `5d24946` is installed
in place, and the installed APK hash matches the tested artifact. The phone was
then in use with another app open; no new UI acceptance was performed and its
normal sleep setting remains unchanged. This supersedes older installation
references to `f0dfc7f`. Published signed build 157 remains unchanged.

**Local terminal pixel scrolling (2026-09-29):** screen-anchored primary
terminals now retain fractional drag/fling motion, paint both partial edge rows,
and share geometry with artifact taps. Verified row-space/history growth keeps
the text being read stable across output/replays; remote wheel paths remain whole
rows. All 34 focused JVM and 10 Android emulator cases passed. A stale screenshot
led to a stronger actual-pixel app check, which passed its focused rerun; the
scrollback screenshot was inspected. APKs and native/ZIP 16 KB checks pass.
Pixel/Mac acceptance and broader terminal fidelity remain open. See
[TERMINAL_PIXEL_SCROLL.md](TERMINAL_PIXEL_SCROLL.md).

**Historical pairing repair (2026-09-29):** a verified native reconnect now
consolidates exact account/team/device/build duplicates while keeping old draft,
selection and notification references. Notification history is combined before
pruning, and posted Android pending intents retain their UUIDs. A QR cannot choose
between conflicting old native routes. All 70 focused JVM and 9 Android 17
emulator tests passed; main/test APKs build and native/ZIP 16 KB checks pass.
Cross-device endpoint presentation aliases and physical acceptance remain open.
See [PAIRED_COMPUTER_RECORDS.md](PAIRED_COMPUTER_RECORDS.md).

**Scoped pairing records and stable upgrades (2026-09-29):** authenticated rows
now store account/team ownership and a stable origin. A standalone QR-to-native
upgrade preserves drafts, notification links/unread history and selection;
identical QR codes saved by two teams remain isolated. Saved dialing carries its
captured owner, and local removal preserves another team's row/grant. All 75
focused JVM and 8 Android 17 emulator tests passed on the final checkpoint; APKs
build and native/ZIP 16 KB checks pass. Broader historical presentation aliases, standalone
QR-only Details parity and physical acceptance remain open. See
[PAIRED_COMPUTER_RECORDS.md](PAIRED_COMPUTER_RECORDS.md).

**QR attachment preserves native identity (2026-09-29):** an authenticated generic
Tailscale scan now retains an existing scoped native Mac/build row and its chosen
connection method, selection and draft/notification origin. A matching route grant
is required; other owners/builds survive and ambiguous/unscoped rows are not
replaced. All 45 focused JVM cases pass and production sources compile. No APK
or Android instrumentation run was performed for this feature commit. Standalone
legacy QR record/alias migration and physical acceptance remain open. See
[TAILSCALE_CONNECTION.md](TAILSCALE_CONNECTION.md).

**Live Tailscale diagnostics (2026-09-29):** Connection Check now projects a
validated TCP/VPN route separately from Iroh route/encryption data. Tailscale
shows “Managed by VPN”; raw TCP cannot claim VPN or QUIC encryption. Reports omit
addresses and credentials, reject retired authority, and keep RPC timing separate
from unavailable TCP transport RTT. All 43 focused JVM and 4 Android 17 emulator
UI cases passed. Main/test APKs build with both readiness and diagnostics changes;
native/ZIP 16 KB checks pass. The report screenshot was inspected; values are
fixtures and physical VPN/Mac acceptance remains pending. See
[TAILSCALE_CONNECTION.md](TAILSCALE_CONNECTION.md).

**Tailscale tunnel readiness (2026-09-29):** startup now waits up to ten seconds
for callback-proven VPN readiness; the same monitor survives through socket IO.
Generation changes retire old proofs, cancellation/revoked consent stop pending
preparation, and missing VPN failures carry actionable Tailscale advice in Details.
All 78 focused JVM cases pass and production sources compile. This feature commit
does not rebuild the APK or rerun instrumentation; Android VPN/Mac acceptance and
live TCP route diagnostics remain open. See
[TAILSCALE_CONNECTION.md](TAILSCALE_CONNECTION.md).

**Independent saved Tailscale sessions (2026-09-29):** the native computer path
now connects through a separate account/team-owned TCP pool before Iroh readiness.
Simulated Iroh startup/broker failures leave shared Tailscale terminal/feed/check/
power leases intact. Local route keys wake only affected Mac/build consumers;
account, grant and method changes still retire stale connections. All 69 focused
JVM cases pass; main/test APKs build and pass native/ZIP 16 KB alignment. Android
instrumentation was not rerun for this checkpoint and ADB still sees no Pixel.
Initial account/team verification remains required; fully offline cold start is
not established. Legacy QR identity/method unification and physical VPN/Mac
acceptance remain open. See [TAILSCALE_CONNECTION.md](TAILSCALE_CONNECTION.md).

**Tailscale Details/routing integration (2026-09-29):** Computer Details now
provides Tailscale Only plus targeted add/edit/remove routes, with no automatic
fallback when a grant is absent. Successful pairing preserves native identity;
route edits are authenticated and atomically replace the old grant. UI/feed/service
reconnects wake on per-Mac route changes. Eighty-six JVM cases pass; fourteen
Android UI/storage cases have passing evidence across the documented runs. Main
and test APKs build, and native/ZIP 16 KB checks pass. Physical acceptance remains
pending. The discovery dependency was subsequently removed in the checkpoint
above; legacy QR identity/method unification remains unfinished. See
[TAILSCALE_CONNECTION.md](TAILSCALE_CONNECTION.md).

**Tailscale pairing authorization checkpoint (2026-09-29):** the production QR
flow now requires visible confirmation or an encrypted, account/team/device/build
scoped saved-route grant. A successful authenticated workspace request is required
before saving permission. Numeric endpoints remain pinned across reconnects, and
account/team or grant retirement fences tokens and sockets. Legacy unscoped QR
rows require confirmation once. Fifty-four focused JVM cases pass; Android sources
compile. No APK/device run was performed for this feature commit. Tailscale-only
selection and merging routes into native computer identities remain open. See
[TAILSCALE_CONNECTION.md](TAILSCALE_CONNECTION.md).

**Tailscale transport prerequisite (2026-09-29):** the existing QR/TCP path now
validates numeric IPv4/IPv6 peers and one VPN with Tailscale interface addresses,
checks both socket endpoints and revalidates before writes. Tunnel changes retire
connections; invalid proof cannot transmit a payload. Twenty-five focused JVM
cases pass. Android sources compile, but physical VPN behavior remains unverified
and no APK was rebuilt for this feature commit. Tailscale-only selection and
account/device-bound saved-route grants remain open. See
[TAILSCALE_CONNECTION.md](TAILSCALE_CONNECTION.md).

**Direct connection settings integration (2026-09-29):** Computer Details now
has Iroh/Direct selection and labeled, individually enabled numeric addresses.
Settings persist per account/team/device/build. Routing changes retire that Mac's
old leases and pending handshakes; empty or unreadable Direct settings cannot fall
back to relays. The backend uses a separate relay-disabled endpoint with the same
enrolled key. Fifty-eight focused JVM and seventeen Android cases pass, with a
five-case rerun after the final isolated toggle styling change. The production
backend fixture uses real local QUIC with simulated account authority. Physical
acceptance remains pending; evidence is in [DIRECT_CONNECTION.md](DIRECT_CONNECTION.md).
Tailscale-only selection and authorized saved route management remain open.

**Native computer removal integration (2026-09-29):** Details now confirms
selected-team, exact-build removal through the active V2 control path, then
clears captured local pairing/selection and appearance state. Local save failures
have a local-only retry; unconfirmed server outcomes retain pairing rows. The
Details presentation survives directory-row removal and stops only the target's
foreground reconnect. Eighty-five focused JVM cases pass, including twelve new
flow/storage cases. Debug and instrumentation APKs build; the main APK passes ELF and ZIP
16 KB alignment checks. All twelve Android 17 emulator UI cases pass on the final APK. ADB did not detect
the Pixel, so installation/physical acceptance remain pending. Details are in
[COMPUTER_REVOCATION.md](COMPUTER_REVOCATION.md). No real Mac has been revoked.

**Keep-awake computer indicators checkpoint (2026-09-29):** connected computer
rows and selector entries now show the iOS-style orange cup only for confirmed
enabled state. Read-only observers reuse each UI feed connection and clear state
on disconnect/pause. Feed and power subscriptions are explicitly removed before
releasing a shared connection. Thirty-seven JVM and eight Android UI cases pass
on the final build. The Pixel disconnected before installation, so it remains on
`f0dfc7f`; physical acceptance is pending. See
[MAC_POWER_INDICATORS.md](MAC_POWER_INDICATORS.md). Signed published build 157 is
unchanged.

**Computer connection presentation checkpoint (2026-09-29):** Details now shows
this phone's connection status, the verified foreground role and workspace count,
scoped to each saved Mac/build. Settings rows show their own connection status.
Unknown counts remain unknown until a real snapshot arrives. Twenty-six JVM and
eight emulator UI cases passed; a final Settings row layout adjustment has build
and alignment verification but was not in that UI run. Physical acceptance is
pending. See [COMPUTER_CONNECTION.md](COMPUTER_CONNECTION.md) for exact artifacts
and coverage. Signed published build 157 is unchanged.

**Local computer appearance checkpoint (2026-09-29):** Computer Details now edits
name, palette/custom RGB color, computer/utility symbols and emoji, with independent
Auto resets. Scoped atomic storage updates computer rows, the selector, workspace
avatars/search, task options and notification labels without changing connection
identity or dialing. Twenty-eight JVM cases and ten Android UI cases pass across
two runs; an initial JUnit test-declaration failure was fixed and the four new
cases rerun. Physical acceptance remains open. Following the active iOS composition
confirmed local storage; the dormant backup decorator is not wired into this pin. See
[COMPUTER_APPEARANCE.md](COMPUTER_APPEARANCE.md) for evidence, Android icon/picker
differences and the correction to the initial backup interpretation. Signed build 157 is unchanged.

**Mac Power checkpoint (2026-09-29):** Computer Details now has the iOS-style
Keep Mac Awake control, gated by exact live Mac identity and `caffeine.control.v1`.
It handles confirmed state/events, duplicate prevention and ambiguous mutations
with read-only reconciliation. Thirty-eight focused JVM checks and six emulator
UI cases pass; an initial emulator hang is documented. The verified debug APK is
installed on the Pixel, but the phone remained asleep, so live Mac power
acceptance is pending. No Mac power or phone sleep setting was changed. Row
indicators, appearance and broader detail parity remain open. See
[MAC_POWER.md](MAC_POWER.md). Signed build 157 is unchanged.

**Per-computer detail checkpoint (2026-09-29):** native computer rows now have
Details without changing the active workspace. Checks refresh discovery, resolve
the exact Mac/build, borrow and release their own connection, and can run without
opening that Mac first. Private-address editing is per computer; the all-computer
reset moved to Networking. Thirty JVM checks and seven emulator UI cases pass
(the final legacy-QR compatibility adjustment has JVM/build evidence). Appearance,
direct-only/route controls, account revoke and physical navigation
acceptance remain open; see [COMPUTER_DETAILS.md](COMPUTER_DETAILS.md).

**Live Networking / Pixel checkpoint (2026-09-29):** Settings now has a
Networking page with actual endpoint/home-relay state, broker relay metadata,
directory permission/revision and authenticated refresh. Nineteen JVM checks,
three UI cases and one native FFI case pass. The latest debug APK is installed on
the Pixel: sign-in/workspaces survived, relay refresh and home-relay attribution
were verified against the real Mac, and Connection Check returned verified Iroh,
Mac identity/account access, LAN/private-VPN route, 14 ms RTT and 75 ms RPC time.
Android's safe-report chooser opened without sending anything. Normal phone sleep
was restored and the foreground notification service remained active. Full route
transition/private-coordinate acceptance and broader parity remain open; see
[NETWORKING.md](NETWORKING.md). Signed build 157 is unchanged.

**Private-address implementation (2026-09-29):** Settings now edits per-Mac
numeric IP/UDP coordinates, enables/disables them, and confirms removal/reset.
Storage is local, excluded from backup and scoped to the full enrolled identity;
enabled hints are supplied on the next authenticated native connection. Nineteen
JVM checks and both Android UI cases pass (one final-run teardown timeout is
recorded, followed by a successful focused rerun). The
active iOS V2 adapter supports these paths but rejects legacy custom-relay and
relay-selection controls. The corrected contract and remaining direct-only/live
acceptance work are tracked in [NETWORKING.md](NETWORKING.md).

**Connection Check implementation (2026-09-29):** Settings verifies the selected
Mac identity and authenticated RPC access, reads the actual selected native path
and RTT, and offers a report without names, addresses, credentials or terminal
content. Eight focused JVM checks, three emulator UI cases and one native QUIC
path test pass. This does not complete the iOS Networking
screen: active runtime settings, private routes and remaining
connection stages are tracked in [NETWORKING.md](NETWORKING.md).

**Team creation implementation (2026-09-29):** Settings now includes Create Team
with name validation, pending-state duplicate-submit protection and the production
cmux creation route. Known-created teams remain selectable if the later refresh
or selection fails; uncertain POSTs are never automatically repeated. Sixteen
account-controller JVM checks and three emulator UI cases pass; pending/error
dialog screenshots were inspected. Live-account creation has not been performed.
See [TEAM_CREATION.md](TEAM_CREATION.md) for the upstream contract and acceptance.

**Uncertain-input checkpoint (2026-09-29):** 26 focused JVM checks and one
Android 17 UI case passed. The controlled host loses a composer acknowledgement;
the app automatically reconnects without resending, preserves the draft/warning,
and accepts a new explicit command. The native input queue also preserves its
no-replay/explicit-resume behavior after lane repair. These are fixture checks;
real-Mac uncertain-send timing remains open. See the runtime checkpoint for exact
evidence and the initial emulator startup failure.

**Latest physical checkpoint (2026-09-29):** authenticated Mac/Pixel terminal,
Files and notification checks are recorded below. A controlled 37.98-second
network outage recovered automatically 28.0 seconds after restoration with all
six output markers. Unsent draft retention was also verified in a separate run;
input submitted during uncertain delivery remains unverified. The long-title
header fix is installed and exercised on the Pixel, including Keyboard/Compose,
menu and Back. Sign-in survived the upgrade and the background service restarted.
Temporary phone network/sleep settings were restored. See
[NATIVE_RUNTIME_CHECKPOINT.md](NATIVE_RUNTIME_CHECKPOINT.md) for exact scope and
APK hashes. The goal remains active; signed build 157 is unchanged.

**Terminal resize follow-up (2026-09-29):** the last painted frame now stays visible
while a same-terminal resize waits for replay; changing terminal/client resets it.
20 focused JVM checks and both new grid/byte pixel checks passed. The existing
keyboard/input and byte-recovery cases also passed in a separate emulator run.
Inspected screenshots show Gboard and retained/settled output. See the resize
section of [NATIVE_RUNTIME_CHECKPOINT.md](NATIVE_RUNTIME_CHECKPOINT.md) for the
failed attempts, corrected fixture, final evidence and APK hashes. Physical Mac/
Pixel acceptance and complete terminal fidelity remain open.

**Latest combined runtime checkpoint (2026-09-28):** 441 JVM tests passed;
15 native-module and 16 app tests passed in separate Android 17 emulator runs.
This run found and fixed shared RPC disconnection when a screen cancelled during
a frame write. Direct/composer images, task uploads and native terminal/artifact
lanes now have emulator runtime evidence. A new combined debug APK is prepared;
physical Pixel/live Mac acceptance and full rendering parity remain open.
See [NATIVE_RUNTIME_CHECKPOINT.md](NATIVE_RUNTIME_CHECKPOINT.md) for the failure,
fix, exact test scope, APK hashes, screenshot limits and next device work.

**2026-09-28 live-host correction:** the installed cmux 0.64.25 pairing panel is
Iroh-only; the pinned active `MobilePairingModel` confirms v2 publishes no legacy
Tailscale QR/TCP route. The user approved enabling pairing; the panel reached
Iroh Ready. Native Android connectivity requires **CmuxIrxTransport** and V2
enrollment/discovery. Legacy TCP fixtures do not establish compatibility with
this host. See [IROH_V2.md](IROH_V2.md) for the revised first milestone.

**Physical viewer check (2026-09-28):** all four outstanding syntax/text/Markdown
fixture methods passed on the actual Pixel 6a, Android 17, in 10.981 s. Screenshot
pixel assertions and visual inspection confirm both Mermaid labels/arrow and Vega
bars. See [SYNTAX_CHECKPOINT.md](SYNTAX_CHECKPOINT.md) for APK hashes and evidence.
Live Mac connection and broader device acceptance remain open.

**Native transport checkpoint (2026-09-28):** the exact Iroh fork built for Android
arm64; two crypto/QUIC tests and five Irx admission/framing tests passed on the
physical Pixel. The module is isolated, with no current Mac enrollment or app
connection yet. See [IROH_V2.md](IROH_V2.md) for build pins and test scope.

**Main native packaging/account scope (2026-09-28):** the main debug APK now
includes the verified arm64 Iroh/JNA libraries and their notices; APK alignment,
signature and native hash checks pass. Account membership and Settings team
selection are implemented with 10 local network tests. A combined run with 25 V2
checks passed all 35 cases. This new APK has not been installed or connected to
the Mac. Other native ABIs and live account/device acceptance remain open. The next checkpoint below connects the account scope to the endpoint/RPC owner.

**Account discovery integration (2026-09-28):** production app wiring now owns
V2 discovery, the protected installation key, lazy native endpoint and shared
Mac RPC connections in one account/team lifetime. The Computers screen selects
fresh directory entries; saved native locators contain no credentials and are
checked against current scope before dial. UI, aggregate feeds and background
notifications use independently closable leases on one admitted Mac connection.
The full JVM run passed 365 tests (61 suites). This is local/build evidence;
Pixel enrollment, relay traversal and actual Mac traffic are still unverified.

**Recovery follow-up (2026-09-28):** bounded forced-token/API-ticket recovery
now handles explicit authentication rejection. Native dial timeouts remain
reconnectable without swallowing actual caller cancellation. Revocation remains
terminal; fresh sessions can recover expired challenges, with server rate-limit
delays honored. See [IROH_V2.md](IROH_V2.md) for test scope and remaining recovery
work. This follow-up has no new APK or live-device claim.

| Area | iOS source / contract | Android status | Acceptance check |
| --- | --- | --- | --- |
| Account | `MobileAuthComposition`, `MobileRootAuthGate`, `AuthCoordinator` | OTP sign-in/encrypted token refresh coded; verified team membership and Settings selection implemented with login/team generation guards, HTTP cancellation and explicit token refresh tests; team creation through the cmux backend and verified selection implemented; confirmed account deletion with durable result handling is implemented (see [ACCOUNT_DELETION.md](ACCOUNT_DELETION.md)); encrypted display-only account/team restoration is implemented (see [ACCOUNT_RESTORATION.md](ACCOUNT_RESTORATION.md)); component process-kill recovery is verified; full NativeScreen lifecycle and live phone account QA remain open | Sign in with the Mac's cmux account/team; switch teams without stale authority; restore session; sign out |
| Computers | `MobilePairedMac`, `MacComputerListSection`, `V2ControlService` | Saved-Mac list/select/forget coded; V2 account-session directory pagination, pushed changes/revocation, lease expiry and relay renewal implemented with local network fixtures; selected-team discovery now feeds a Computers screen and scoped saved locators; live account discovery remains unverified | Discover, choose, forget, and reconnect multiple Macs on the actual account |
| Pairing | `MobileIrohV2InstallationStore`, `V2ControlService`, legacy `CmxPairingQRCode` | Scoped protected native identity verified on Pixel; signed V2 enrollment implemented with local network fixtures; legacy QR/deep-link codecs retained; actual account enrollment remains open | Enroll Android against current Mac/account, validate key/scope, admit and revoke; QR only for hosts exposing the legacy route |
| Transport | `CmuxIrxTransport`, `MobileCoreRPCSession`, legacy `CmxNetworkByteTransport` | Iroh library, QUIC and admission verified on Pixel; V2 control service implemented; native endpoint owner and RPC control/event adapters compile, with stream isolation and RPC lifecycle JVM checks. Account owner/computer picker wiring and shared foreground/background RPC leases are implemented; control-stream replacement, read-only resend and whole-connection closure observation are implemented; lifecycle-aware diagnostic keepalive and positive silence evidence are implemented; real relay/repair/background verification and specialized lane consumers remain open. optional event-stream isolation and relay-preserving direct-path authorization now follow iOS. dedicated render-grid input stream integrated with lease lifecycle and readiness checks. dedicated duplex terminal output/input and replay barriers now integrated. native authorized artifact downloads integrated with before-byte fallback and EOF validation. Last full JVM checkpoint: 425 tests; combined main/test debug APKs built, native loopback methods not run. Subsequent HTTP-to-WebSocket restoration is implemented with 33 focused checks; not yet in that APK | Current Mac Irx connection with control/events/terminal/artifact lanes, reconnect without duplicate input |
| Workspaces | `MobileSyncWorkspaceListResponse`, `DeviceTreeView` | Native list, sections, previews, colors, compact toolbar, computer picker, unread filter, workspace/terminal/browser creation, rename, pin/read, close, group create/rename/pin/ungroup, and move RPCs coded; anchor hierarchy, durable empty headers, saved collapse state, and bounded drag reordering coded; numeric unread badges and common group icon equivalents coded; arbitrary custom SF Symbols and phone QA pending | Live hierarchy, add/rename/close/reorder/group, status and selection |
| Terminal | `MobileTerminalRenderGridFrame`, `GhosttySurfaceView` | Authoritative grid colors/styles, Unicode cells, revision continuity and local pixel scrollback verified with JVM/emulator checks; native Ghostty now owns byte/hybrid parsing, replay replacement and cleanup, with captured Vim, rich underline pixels, gap recovery and app scrolling checks; dedicated native input/output lanes and mouse/wheel RPC paths implemented. Ordinary Kitty image decoding, crops, alpha, z layers and pixel scrolling now pass Android checks through the production byte mirror/painter. Unicode placeholder fragments now use the native Ghostty resolver and pass software/hardware pixel checks, including image-only redraw. Unchanged pixels are now reused by generation with measured lower snapshot allocations and retained geometry updates. Broader graphics/glyph/input-mode fidelity, performance and physical resize/TUI acceptance remain open. The pinned screen-anchor grid has no image payload; see [GHOSTTY_VT_ANDROID.md](GHOSTTY_VT_ANDROID.md). | Stream render grid or VT bytes; colors, cursor, Unicode, alternate screen, scrollback, resize |
| Input | `TerminalInputTextView`, `MobileTerminalInputResponse` | Native multiline paste/submit, encrypted per-Mac/terminal drafts, pending-send guards and acknowledgement reconciliation verified with an emulator fixture; direct IME keyboard, Unicode composition, repeated deletion, ordered input and explicit recovery from rejected delivery verified with an emulator fixture; modifier/navigation/control toolbar and hardware keys coded; photo/file picker, encrypted attachments, image paste and chunked file upload verified with an emulator fixture; dedicated native input stream for render-grid terminals now implemented with framed UTF-8, readiness and no replay after uncertain writes; direct IME image paste and toolbar clipboard attachments implemented with ordered preparation and grant cleanup; IME image attachments wired into terminal/task Compose editors; configurable toolbar, drag/hide/reset, persistent custom commands and exact modifier-free macro delivery now verified with JVM and Android 17 16 KiB runtime checks; keyboard-mode/physical-layout checks, latency measurement markers and phone QA remain open (see [TERMINAL_SHORTCUTS.md](TERMINAL_SHORTCUTS.md)) | Soft and hardware keyboard, modifiers, paste, image/file input, shortcuts, safe retry |
| Notifications | `NotificationFeedView`, `CmuxAppDelegate` | Native in-app feed, workspace/source/preview/time rows, read sync, provenance-based moved-terminal navigation and per-Mac view state coded; search/navigation verified with emulator fixtures; encrypted per-pairing alert identity and exact terminal routes, independent saved-Mac workers, read/forget cleanup and host-identity checks coded; combined saved-Mac feed, day/history grouping, unread filter, pull refresh, read/unread gestures and confirmed bulk read coded; offline snapshots and revision guards tested with loopback peers; event/mutation revision floors, bounded refresh retries and Activity-recreation retention coded; computer picker, scoped unread badge/bulk actions and live-destination filtering before the global cap coded; server push fallback and phone QA pending | Feed, unread counts, actions, deep links, Android background delivery, read sync |
| Browser | `CmuxMobileBrowser`, `MobileBrowserFrameEvent` | JPEG/PNG stream, bottom navigation/address/loading controls, direct IME and hardware input, live viewport updates, ordered/coalesced scrolling, frame validation and stale-dialog fencing coded; seven browser-view checks and three browser momentum gesture checks have passing emulator evidence across initial/focused runs, including watchdog/lifecycle recovery and momentum/cancellation; width-fit geometry, local pinch zoom/pan and repeated-tap click counts also verified in a clean 13-case browser emulator run; download behavior and phone QA pending | Show browser panels; navigate, scroll, tap, type, handle dialogs and downloads |
| Search | `MobilePrimaryTabScaffold`, `MobilePrimarySearchCoordinator` | Independent workspace/notification queries, bounded Unicode editing, group/computer/description and notification metadata matching, submit/clear and result navigation verified with JVM/emulator fixtures; cross-computer notification search and exact target navigation verified with emulator fixtures; iOS 26 primary-tab structure with separate Search control, cancel/submit lifecycle, unread badge and floating New Task entry coded; cross-computer workspace aggregation coded; phone QA pending | Search workspaces and notifications with matching navigation |
| Changes | `CmuxMobileChanges` | Collapsible directory tree, path-stable diff pager, numbered/wrapped hunks, grapheme-safe emphasis, line/hunk copy, persistent pinch font, refresh/retry and progressive 6,000→24,000→96,000-line loading coded; identity-checked hidden-context expansion and chunked content transfer coded; model/request checks passed; Before/After image, PDF, media and file-action previews coded; ten Changes cases passed in an Android 17 emulator run; rendered Markdown now shares the original cmux web assets; unified viewer/text controls coded; phone QA, raw syntax/streaming and document-format parity remain open | View changed files and diffs from the active workspace |
| Terminal Files | `TerminalArtifactFilesSheet`, `ChatArtifactFolderView`, `ChatArtifactViewerDestination` | Scoped RPC/paging/search store, Session/In view sheet, filters/sort, list/three-column grid, thumbnails, folder navigation, swipe previews and file actions coded; terminal menu/counted chip and direct relative/absolute path taps capability gated; 285 JVM checks and all 12 combined Android 17 Files/shared-preview runtime cases passed; row Share, folder-tap preference and rendered Markdown have passing runtime evidence; unified viewer/text controls coded; raw syntax/streaming, remaining menu fidelity, broader document formats and physical acceptance remain open | Browse terminal/session files, folders and previews without crossing authorization scopes |
| Tasks and agents | Task composer in `CmuxMobileShellUI` | Editable Claude/Codex/OpenCode/Shell and custom templates, agent icons, remembered Mac/agent/directory defaults, live model/effort choices, Mac/folder/name/group task options, scoped discovery/cache, encrypted saved drafts with stable retry IDs, completed-operation refresh/start-again recovery and new-workspace task RPC coded; task attachment import/storage/upload/retry, full-height prompt canvas and compact keyboard dock coded; offline composition and first-handshake draft adoption verified with emulator fixtures; remaining UI fidelity and phone QA pending | Create and navigate tasks; handle agent prompts and attachments |
| Settings | `MobileSettingsView` | Account, saved-computer, background notification, terminal size, connection status, Open Folders on Tap and Show Missing Files controls coded; local reset with confirmation, platform-owned erase and emulator acceptance implemented; live native route/RTT, Mac identity/account checks and address-free report sharing implemented; scoped private-address editing/reset and native dial hints implemented; active V2 status/home-relay/credential refresh and real-Pixel checks implemented; per-computer Iroh/Direct preferences, native endpoint selection and routing invalidation implemented; Tailscale-only route authorization/editing is implemented; discovery-independent saved reconnect is implemented; legacy route unification and physical acceptance remain open (see TAILSCALE_CONNECTION.md and NETWORKING.md) | Account, computers, notification, display, network, diagnostics, reset |
| Device behavior | iOS lifecycle, accessibility, background push | Keyboard resizing verified with an Android 17 emulator fixture; visible terminal text exposed to accessibility; broader lifecycle and phone QA missing | Rotation, keyboard, process death, offline recovery, screen reader, battery |
| Delivery | iOS release checks | Native debug/release builds and stable signing verified in CI; real Pixel native pairing, terminal/grid resize/reconnect and routed HTTP browser workflow verified; authenticated signed-release migration and remaining feature gates open | Stable signed APK, upgrade in place, reproducible CI, Pixel acceptance run |

## First authenticated Pixel/Mac acceptance (2026-09-29)

The top table contains cumulative implementation history. Its older “phone QA”
notes are superseded **only for these bounded checks** by source `3ca2d22`:

- Email sign-in and native account connection reached the real Mac workspace list.
- Android created a separate workspace; composer input executed a shell command.
- Actual Gboard key taps executed `pwd` and displayed the Mac result.
- Keyboard show/hide and terminal reopen succeeded after the viewport generation
  fix, with no persistent resizing banner.
- Force-stop/relaunch retained sign-in; manually reopening the workspace recovered
  terminal history. Automatic selected-screen restoration/network-outage recovery
  were not established.
- The detected-folder Files chip browsed the Mac repository and displayed an exact
  116-byte `.gitignore` preview.
- A Mac `cmux notify` appeared in the foreground notification feed with the expected
  unread count; tapping it opened the corresponding test terminal.

See [native runtime checkpoint](NATIVE_RUNTIME_CHECKPOINT.md) for artifact hashes,
source comparison, evidence and limits. This does not close full terminal fidelity,
all artifact formats/actions, long-term background/Doze behavior, server push, multiple-Mac/team behavior,
or full iOS parity. Signed release build 157 remains unchanged.

## Background notification acceptance (2026-09-29)

The Pixel received a real Mac notification as an Android system alert while cmux
was backgrounded on the launcher. Tapping a second delayed alert reopened the
correct native terminal. This verifies the opt-in foreground service and system
notification route on Android 17. The user’s notification permission and background
setting are enabled. Doze, boot and network recovery, plus server push while the
app process is stopped, remain separate open gates. Details and evidence are in
[NATIVE_RUNTIME_CHECKPOINT.md](NATIVE_RUNTIME_CHECKPOINT.md).

## Local reset parity (2026-09-29)

Settings now includes **Erase All Data on This Device**, following the pinned iOS
`MobileSettingsResetSection`: an explicit destructive confirmation, a description
of local data removal and preservation of remote account/computer data. Android's
`ActivityManager.clearApplicationUserData()` handles the process stop and complete
app-data reset; workers cannot repopulate a manually emptied credential store.
The row prevents a second request while reset is pending, reports rejection, and
requires fresh confirmation for a retry. Android also resets runtime permissions
and URI grants; the dialog explains the app closure and permission reset.

Three Android 17 UI tests passed for cancel, single confirmed request, and false/
throwing platform failures. A separate emulator-only acceptance runner seeded an
encrypted account, Android Keystore key, notification setting, internal/cache/
external files and notification permission, then pressed the real confirmation.
Android terminated the process. A new instrumentation process verified every seed
was absent, the key was removed and notification permission was revoked (**1 test
passed**). The interrupted erase process itself is not counted as a passing test.
No physical-device account data was reset.

Reproduce on an explicitly selected disposable Google emulator after installing
both APKs:

```sh
python3 scripts/check-local-reset.py --serial emulator-5554 --adb "$ANDROID_HOME/platform-tools/adb"
```

The runner and test both reject physical devices, and the platform test skips by
default without its explicit phase argument. Evidence is in ignored
`captures/local-reset/20260929T103259Z` (receipt and both process logs); debug APK
SHA-256 `580c33e5160ea1bcc111030aa27b24d803f12f5d1e8baeeac3c9183966e04231`.
[Android reset contract](https://developer.android.com/reference/android/app/ActivityManager#clearApplicationUserData()).

## Native 16 KiB runtime acceptance (2026-09-29)

Source `8525b12` passed all four native graphics/terminal/artifact tests on Google's
Android 17 arm64 16 KB system image, revision 7. The emulator kernel reports 16384
bytes per page; package manager reports `pageSizeCompat=0`, and the app launches
without the RELRO warning. APK LOAD, RELRO and ZIP alignment checks also pass.
See [NATIVE_RUNTIME_CHECKPOINT.md](NATIVE_RUNTIME_CHECKPOINT.md) for hashes,
evidence and the bounded acceptance scope. This closes the actual 16 KiB kernel
check, beyond the earlier Pixel 4 KiB kernel's package-compatibility check.

## Terminal modifier follow-up (2026-09-29)

The pinned iOS `TerminalInputModifierState`, `TerminalKeyEncoder`, and
`TerminalInputTextView` define one active accessory modifier and a strict 400 ms
double-tap window for locking it. Android now exposes Ctrl, Alt, Cmd and Shift
with one-shot/locked states, a blue active fill and outlined lock, and accessibility
state descriptions. Switching modifiers clears the previous lock. Terminal or
client changes, opening the composer/Files, and pasting clear accessory state.

Cmd text implements the iOS readline mappings (A/E/K/U/W/L/C/D), Cmd+Left/Right
move to line boundaries, and Cmd+Backspace deletes to the start of the line.
Alt+Left/Right use ESC-b/ESC-f and Ctrl+/ sends 0x1F. IME deletion batches encode
each requested delete with the active modifier, then consume a one-shot state;
editing uncommitted composition does not consume it. Hardware VT modifier
combinations remain distinct from the iOS accessory special-key behavior.

Seven focused JVM tests passed, including all four modifier states, the exact
double-tap boundary, exclusivity, Command mappings, Unicode commits and special
keys. Two Android 17 emulator cases passed together in 36.1 seconds: the new
Compose/IME modifier workflow and the existing direct-input regression. They
verify exact RPC bytes, repeated deletion, composition, locked-state persistence,
composer/terminal reset, stale input-connection rejection and recovery after a
rejected send. The locked-button screenshot was inspected with Gboard visible
and no system dialog. Evidence: `captures/runtime/modifiers/` (Git-ignored).
See [NATIVE_RUNTIME_CHECKPOINT.md](NATIVE_RUNTIME_CHECKPOINT.md) for current APK
hashes. This does not close
hardware-layout, complete terminal-mode, toolbar-customization or physical Pixel/
Mac acceptance work.

## Direct keyboard images and clipboard attachments (2026-09-28)

The pinned iOS `TerminalInputTextView` routes an image paste directly to
`terminal.paste_image`; `MobilePasteboardAttachments` stages composer attachments.
Android now accepts `image/*` through the direct terminal IME's `commitContent`,
including temporary read grants held until import/delivery finishes or is cancelled.
Hardware and context-menu paste share clipboard classification: images take precedence
over captions, content-backed documents remain attachments, and ordinary text/web
links remain text. Local URI and Intent coercion cannot become terminal input.

Direct image preparation reserves a position in the existing input queue before
reading the provider, so following keystrokes cannot overtake the paste. Direct
file paste uses the host's attachment capability and shell-quoted returned path.
Toolbar paste in composer mode stages attachments in the existing encrypted draft
repository. Captured Mac/terminal, account generation and connection are checked
around imports/uploads. At most four rich operations wait; dropped, rejected,
completed and cancelled operations release their grants. Unconfirmed delivery
pauses input, discards queued work and requires explicit resumption without replay.

Verification: 14 focused JVM tests passed (9 input queue, 5 terminal drafts);
Android instrumentation compiled. New `TerminalRichInputTest` methods cover MIME
advertisement, stale/disabled IME rejection, mixed clipboard classification,
bounds and cleanup. New `NativeFlowTest.keyboardImageReachesExactTerminalBeforeFollowingKeysWithoutChangingComposerDraft`
checks the actual screen's image RPC, decoded image pixel, target and ordering.
These four new device methods have **not run**. Local evidence is under
`captures/input/rich-paste/`. No APK was rebuilt or published for this checkpoint.

At this checkpoint, Compose terminal/task IME delivery was still missing; the
follow-up below implements it. Actual Gboard permission-grant behavior, clipboard
UI on the Pixel and authenticated live-Mac image/file acceptance remain unverified.

Android API reference: [image keyboard content and grant lifecycle](https://developer.android.com/develop/ui/views/touch-and-input/image-keyboard).
The [Compose content receiver](https://developer.android.com/develop/ui/compose/touch-input/copy-and-paste)
requires a state-based field. The follow-up below uses the platform input interceptor
instead for IME images; no text-state migration is needed for that path.

## Composer and New Task keyboard images (2026-09-28)

`RichContentEditor` wraps each existing Compose input connection with
`InterceptPlatformTextInput`. It advertises images to the keyboard, delegates
text/selection/composition to the original editor, and rejects rich input after
connection close, session cancellation or a change of draft/connection owner.
Availability changes restart the platform session so its advertised types refresh.
The installed Compose 1.9.1 sources confirm that value-based fields use this same
platform session API (`LegacyPlatformTextInputServiceAdapter`); rebuilding the
editors around separate text state would add unnecessary synchronization risk.

Terminal composer images stage in the captured encrypted draft until Send/Insert.
New Task images share the existing photo preparation, encryption, preview and
upload flow. Task imports now reserve preparation synchronously, reject batches
that exceed the remaining slots before importing any item, classify clipboard
items individually, and check the exact editor lease after asynchronous reads.
Temporary IME grants are released when accepted imports finish, fail or cancel;
rejected images release their grant immediately. Direct terminal input shares the
same grant acquisition/release implementation.

Verification: 35 focused JVM checks passed (9 queue, 5 terminal drafts, 12 task
drafts, 9 task attachments), and Android instrumentation compiled. New tests:

- `RichComposerEditorTest`: actual Compose connection preserves selection/text
  around an image and rejects stale, disabled and closed input owners (2 methods).
- `NativeFlowTest.composerKeyboardImageStagesUntilSendAndPreservesItsText`:
  actual screen stages the image, then sends its decoded pixel and draft text.
- `NativeTaskAttachmentsTest.keyboardImageStagesWithPromptAndUploadsWhenTaskIsCreated`:
  actual New Task prompt stages/preserves its image bytes and uploads on submission.

These four methods are **compiled only**, pending Pixel execution. Local evidence:
`captures/input/composer-images/`. No new APK was packaged/published. Existing
clipboard toolbar/menu actions work separately; rich images through the Compose
selection popover and drag-and-drop are not implemented by this IME interceptor.
Real Gboard temporary grants and live Mac workflows still require device acceptance.

## Source audit: Files and removed GUI chat (2026-09-28)

Upstream [PR #10576](https://github.com/manaflow-ai/cmux/pull/10576), merged
2026-08-23, removed the iOS GUI agent-chat screen, transcript and composer. The
current reference commit retains artifact previews, the terminal Files gallery,
Markdown support and artifact RPCs under `mobile.chat.*`. Those RPC names do not
imply that the removed GUI chat is part of the current iOS companion.

Earlier dated entries below that list “agent chat” or “chat UI” as a parity gap
were mistaken. This audit supersedes those statements. The actual outstanding
scope is terminal Files (Session/In view, search, filters, sorting, folders,
previews and actions), along with the other open areas in the table. Haskell and
PureScript highlighting also belongs to artifact code viewing, not a chat screen.
No existing GUI chat implementation or verification is being claimed.

## Raw-code syntax highlighting (2026-09-28)

Raw Files/Changes viewing now uses the exact Highlightr dependency pinned in the
iOS Package.resolved: Highlightr 2.3.0 at
`05e7fcc63b33925cd0c1faaa205cdd5681e7bbef`. Its unmodified highlight.js 11.11.1
bundle contains 192 languages, including Haskell (also used for PureScript by
cmux), and both Xcode palettes. The three asset hashes are recorded in
`assets/raw-code/manifest.json`; `scripts/sync-raw-code-highlighter.py` reproduces
them. The MIT/BSD notices are bundled and visible in Open-source licenses.
All 57 extension mappings match the pinned iOS policy, and all 35 mapped language
IDs are available in the bundle.

The policy matches iOS: highlight recognized languages through 1,500,000 bytes;
automatically detect unknown languages only below 256,000 bytes. Larger files
show the expandable Highlighting off pill with the file size and threshold.
They retain the raw viewer and its controls.

An off-screen WebView runs only the bundled tokenizer and styles, with networking,
file/content access and navigation blocked. The source is passed as JSON data.
Generated HTML is converted to contiguous UTF-16 style ranges; CR/CRLF are
preserved and the decoded text must match the original exactly before anything
is applied. The displayed view remains the native selectable buffer. Attributes
supply foreground color and bold/italic monospaced traits, preserving the user's
font size and avoiding theme backgrounds that would cover search highlights.
Page cancellation closes the worker, a deadline bounds waiting, and an engine
failure leaves plain text available. A shared coroutine lock serializes workers.

All **307 JVM cases passed**, and debug/test APK assembly succeeded. The final
11-case Android run recorded **7 passes, 3 failures, and 1 started without a
result** before interruption. Four of the five new syntax cases passed; the
late-coloring case failed on a null clipboard read. Two existing text cases failed
on clipboard/gutter checks. A System UI ANR dialog was observed over the app; its
role in these failures is not yet confirmed by a rerun. See
[SYNTAX_CHECKPOINT.md](SYNTAX_CHECKPOINT.md) for exact outcomes and
[HANDOFF.md](HANDOFF.md) for continuation steps. Runtime verification remains
incomplete. This does not prove large-file performance, incremental remote
viewing, complete lifecycle/fault recovery or physical Pixel/Mac acceptance.
Signed build 157 remains the current download.

## Unified viewer menu and native text controls (2026-09-28)

Files and Changes now share one ellipsis-circle Viewer actions menu, following
`ChatArtifactViewerActionsMenu`. It contains Share/Save/Open, Copy Contents for
text, Copy Image for images, raw-text controls and the Markdown Raw/Rendered
choice. The previous separate Markdown chips are removed. Copy Contents uses the
upstream 4 MiB availability threshold; the original text is copied without gutter
numbers or a rich-text transformation. Android clipboard delivery failures are
reported in the viewer.

Raw text uses one native selectable TextView buffer with two-axis scrolling.
Selection can cross lines, preserving Unicode and original newline sequences.
The logical-line gutter is enabled by default, remains independent of copied
text, and numbers logical lines rather than each wrapped visual row. The viewer
adds case-insensitive literal search, highlighted matches, current/total count,
wrapping previous/next navigation, Go to line (clamped to loaded line bounds),
Top and End. The line index uses UTF-16 offsets, including a trailing empty line.

Word wrap and font size are saved independently for code, log and plain text,
using the pinned iOS extension set (including Haskell/PureScript). Logs default
to no wrap; code/plain text default to wrap. The size starts at 15 sp and is
clamped to 8–28, with both a slider/reset control and a two-finger pinch gesture.
Android's system font scaling is applied to the displayed size.

All **305 JVM cases passed**, with zero failures/errors/skips. The first combined
19-case Android run passed 14 cases. The remaining failures led to fixture fixes
for sheet-window lookup and asynchronous layout, and production corrections for
wrapped measurement (including the first layout), the hardware-cached gutter and
pinch accumulation. Focused reruns now provide passing evidence for all **19
distinct Android cases across runs**; this is not a clean 19-case run on the final
source. The last three-case run passed search/jumps and selection/copy/gutter;
its remaining preference/pinch case passed the focused rerun in **7.803 seconds**
with a gesture wide enough for Android's scaling threshold.

The assertions inspect actual native text, clipboard bytes, viewport movement,
saved per-kind preferences, rendered line layouts and on-screen gutter pixels.
The numbered-view screenshot was inspected. Original failures, corrected runs,
build logs and the screenshot are retained in `captures/artifacts/`. One emulator
launch hit a process-start timeout before instrumentation; its exit record is
retained, and a warm launch of the unchanged APK succeeded before testing.

This does not complete the entire artifact viewer: raw syntax highlighting,
incremental remote text display and large-file layout/performance, zoom/scroll
anchoring, broader document formats, remaining menu-icon/interaction fidelity
and physical Pixel/Mac acceptance remain open. Text is currently displayed after
the existing bounded download completes. The signed download remains build 157.

## Shared Markdown renderer and Files runtime follow-up (2026-09-28)

Files and Changes now render Markdown with the same bundled shell, marked,
highlight.js, GitHub CSS, Mermaid and Vega libraries as iOS. The 11 original
assets are copied without modification from the pinned cmux revision; their
SHA-256 hashes are recorded in `assets/markdown-viewer/manifest.json` and
`scripts/sync-markdown-viewer.py` reproduces the copy. Third-party notices and
MIT/BSD licenses are bundled and visible in Open-source licenses.

The Android host supplies the local bridge, lazy diagram loading, Rendered/Raw
selection and the exact 1,500,000-byte rendered-mode threshold. It preserves
same-page fragments and opens activated HTTP(S), mail and telephone links through
Android. Local Markdown links and relative images remain unresolved, matching
the inspected iOS host. Raw HTML is handled by the original sanitizer. CSP and
request interception prevent implicit network loads; remote images retain the
original per-image consent UI. The image transport permits HTTPS on port 443,
rejects private/reserved addresses and mixed DNS answers, uses those validated
addresses for TLS, permits at most three same-host redirects, and bounds decoded
image data to 8 MiB with an image MIME allowlist. It uses OkHttp/Okio with system
TLS verification and cancels outstanding image calls on closure.

The renderer follows the app's dark palette through a scoped Android theme,
scales text with the system font setting, and releases its WebView/bridge/jobs
when leaving the page. Renderer process loss has two bounded recovery attempts
before falling back to raw source. Remote-image live-network acceptance,
renderer-recovery injection, full zoom/accessibility equivalence, unified viewer
menu placement and advanced raw-text functionality remain open; these are not
claimed as completed or unavoidable platform limitations.

Verification:

- All **300 JVM cases passed**. After the host integration correction, the seven
  Markdown policy cases passed again. Debug and instrumentation APKs assembled.
- The first 17-case Android 17 integration run passed 14 cases, including gallery
  Share's exact bytes, session scope and read-only URI after sheet closure, the
  other Files flows, and shared image/PDF/audio previews. Two Markdown cases
  exposed the local shell being blocked by the request interceptor. The folder
  preference fixture incorrectly expected `mobile.terminal.click`; the actual
  RPC is `mobile.terminal.mouse`. The positive and negative assertions now use
  the real method.
- With the shell allowance and fixture fixed, a clean five-case rerun passed in
  **58.231 seconds**, covering all three Markdown cases plus the two NativeScreen
  chip/path/folder-preference flows. One attempted rerun stopped in a startup ANR
  before any test cases; exit-info and its log are retained.
- Screenshot review then caught light CSS against the dark Android background.
  After the theme fix, all three Markdown cases passed in **31.091 seconds**,
  including computed text-contrast, visible Mermaid geometry, a Vega chart,
  tables, highlighted code, mode switching, the size threshold and inert active
  markup/unapproved remote images. The final screenshot was inspected.

These runs provide passing evidence for 17 distinct runtime cases across the
initial and focused runs, not a clean full-suite run on the final source. Logs,
initial failures and screenshots are in `captures/artifacts/`. Signed build 157
remains the current download; this combined debug checkpoint has not been
published as a new release. Physical Pixel/Mac acceptance remains pending.

## Gallery Share and folder-tap preference (2026-09-28)

Gallery and folder rows now offer Share for non-directories, disabled when the
file is missing or already being prepared. Missing folders disable Browse folder.
The file action captures the row's terminal/session authorization, stats and
streams the original bytes, and uses the host MIME type for the exported name.
As in `ChatArtifactFileActionStore`, sharing does not impose the inline-preview
size limit. Transfers remain chunked and validate size, offsets and EOF. Partial
files are removed on failure/cancellation. A successful share uses a narrowly
scoped FileProvider URI with read permission, and the exported file outlives the
row or sheet. Preparation errors have a dismissible alert.

Settings now expose Open Folders on Tap (default on) and Show Missing Files
(default off). Folder tapping follows `TerminalFolderTapPolicy`: the default
opens immediately without a classification stat; disabling it checks the path
for at most two seconds. Directories and infrastructure failures focus the
terminal, files and terminal-scope `forbidden` responses open the viewer. The
viewer keeps its terminal authorization and displays any refusal; it never
silently switches scope. New taps, terminal/render-source changes and teardown
cancel or invalidate older work. A delayed result rechecks the path before
opening or sending a terminal click; expired noncooperative requests cannot act.

All **293 JVM cases passed**, with zero failures/errors/skips, and Android test
sources compile. Added UI fixtures intercept the actual Share intent and check
its URI/grants/bytes after closing the sheet, and exercise the persisted folder
preference through NativeScreen. Those two new fixtures have **not run yet**;
the previous 12-case runtime result predates this feature. A focused policy rerun
covers normalized refusal codes and cancellation in addition to the original
classification, deadline and stale-result cases. Logs are under `captures/artifacts/`.

No APK was built for this feature commit. Signed build 157 remains the current
download. Richer text/Markdown/document previews, broader parity and physical
Pixel/Mac acceptance remain open.

## Terminal path taps and Files layout (2026-09-28)

Ported the pinned iOS terminal artifact hit tester, including wrapped paths,
sentence punctuation, bare filenames, relative paths and Unicode cell widths.
`scripts/generate-artifact-tap-parity.py` executes the unmodified Swift sources
and records their hashes with 1,217 tap cases, including 246 Unicode cases.
The JVM checks compare all 971 ASCII cases. Android instrumentation exercises
the same full set using the renderer's real ICU cell-width implementation.

Direct taps retain terminal authorization through stat, folders and previews;
relative names are resolved by the Mac against its terminal directory. Session
RPCs continue to require absolute paths. Taps outside the rendered grid cannot
open a path from the nearest clamped terminal cell, and opening a path does not
also send a terminal click. The current default opens folders, matching iOS's
default; its optional folder-tap preference is still to be implemented.

The Files header is centered, search uses a compact filled field, and filters
use capsules with a fixed trailing sort menu. The missing-file control is in
that menu. The combined build passed all **285 JVM cases**, with zero failures,
errors or skips. A clean Android 17 emulator run passed all **12 runtime cases**
in 117.523 seconds: gallery scopes/filter/grid/missing preference, remote search
and exact-path copy, nested folders, transfer retry/close, the full 1,217-case tap
reference, relative folder authorization, five shared image/PDF/audio preview
checks, and the actual NativeScreen chip-to-gallery/relative-path-to-preview flow.
The last flow also asserts that opening a file sends no terminal click.

The initial runtime run passed 11 of 12 cases; AAPT removed the compressed test
asset's `.gz` suffix, causing the remaining fixture to fail before comparison.
The fixture now packages plain JSON; production assets are unchanged. One earlier
JVM run had a loopback connection timeout, followed by clean 284- and 285-case
runs. Initial and passing logs are retained in `captures/artifacts/`, along with
the inspected `files-grid-polished.png`. These use local RPC fixtures; they do
not replace physical Pixel/Mac acceptance.

Signed build 157 remains the current download. This feature is not yet in a
published APK; row Share, richer previews and real Pixel/Mac acceptance remain
open.

## Terminal Files chip (2026-09-28)

Added the counted Files overlay on the terminal. The upstream count policy keeps
accepted session totals across transport failures, invalidates totals when the
bound session changes, distinguishes authoritative gallery-row totals from legacy
session totals, coalesces pending scans, and bounds generation-mismatch rearms.
A zero count keeps the previous chip for the upstream 3.5-second grace period;
positive observations cancel the hide and connection teardown removes it at once.
The missing-file setting also controls count scans. Accepted count reports now
trigger Files-gallery refresh; provisional per-frame counts do not.

`scripts/generate-artifact-count-parity.py` executes the unmodified pinned Swift
count state and path detector. Android matches 6,400 state transitions and 33
conservative path cases. The full JVM run passed **282 cases** with no failures,
errors or skips, and Android test sources compile. An existing browser recovery
test now waits for the actual delivery callback before asserting completion.
Runtime logs, initial failures and final passing output are under
`captures/artifacts/`. No APK was built; the current download is still build 157.

The path reference inputs involving parent components use nonexistent roots to
avoid the generator Mac's `/tmp` symlink affecting Foundation standardization.
Android's local fallback uses lexical normalization; complete cross-platform
symlink-sensitive count equivalence is not claimed. Host gallery totals remain
authoritative. Direct path taps, row Share, richer previews and Files runtime/
physical acceptance are still pending; the chip is not a claim of completed parity.

## Terminal Files UI integration (2026-09-28)

The terminal menu now opens Files when the connected Mac advertises terminal
artifacts. The sheet implements Session/In view selection, whole-session search,
All/Images/Code/Logs/Docs/Folders filters, stable Recent/Name/Size sorting,
collapsible provenance groups, missing-file preference, pull refresh, list and
three-column grid layouts, bounded thumbnail loading, and deferred new-file
updates while reading. Terminal output changes are coalesced into gallery refresh
requests while the sheet is visible. Folder navigation retains the selected scope
and validates each immediate child path. Long press offers Copy path and Browse
folder; the upstream row-level Share action remains to be added.

File selection captures its visible swipe order and authorization. Preview pages
stream to private temporary files and reuse image/PDF/media/text viewers and
Share/Save/Open/Copy Image actions. Exported copies survive preview closure.
Terminal/session chunks use upstream stat-size/offset/EOF validation; Changes
retains its separate fingerprint enforcement. Interrupted, corrupt and cancelled
downloads remove partial files. Folder and preview loads reject obsolete results.

The full JVM suite passed **276 cases** with zero failures/errors/skips. Four new
Android Files fixtures compile (scopes/filter/grid/missing, remote search/copy,
nested folder/preview scope and navigation, corrupt-transfer retry/close). They
have **not yet run**. Logs are retained in `captures/artifacts/`. No APK was built
for these feature commits, and signed build 157 remains the current download.

Remaining Files work includes runtime layout/interaction proof, the terminal Files
chip/count/path-hit flow, row Share, thumbnail/cache and richer text/Markdown/document
fidelity, and physical Pixel/Mac acceptance. The common Code extension mapping was
checked against Apple UTType on this Mac; complete iOS type-registry equivalence
has not been verified. No platform limitation is inferred from these open items.

## Terminal Files foundation (2026-09-28)

Added terminal scan and session gallery wire models, scoped stat/fetch/thumbnail/
folder RPCs, directory capability negotiation, and gallery page identity checks.
Snapshot merging preserves provenance, duplicate-path handling, stable reading
order and deferred refresh behavior. Eager paging follows upstream's 2,000
Referenced-row cap, rejects stale generations, and stops repeated cursors while
preserving a retry position. Immediate directory children cannot escape their
parent path. Fifteen focused JVM cases passed; Android test sources compile.

The per-connection/terminal Files store now binds Session only from a supported
terminal scan. Session and search states remain independent; search trims input
and debounces for 300 ms. Failed page loads retain readable rows and retry the same
cursor. Expired cursors fetch a fresh first page, preserving search order or
showing a deferred new-files count while scrolled. Live refresh retains loaded
history and rejects late replies after close, explicit refresh or generation
recovery. Selection can capture the sheet's session authorization while direct
terminal taps retain terminal authorization.

The full JVM suite passed 264 cases before the final generation-race guard. The
focused 25-case Files rerun then passed with zero failures/errors/skips, and
Android test-source compilation succeeded. Logs are retained in `captures/artifacts/`.

At this foundation checkpoint the Files screen was still pending. The subsequent
UI integration and its remaining verification work are recorded above.
No APK was built for this feature commit; build 157 remains the current download.

## Changed-file revision previews (2026-09-28)

Binary diff pages now use the upstream Before/After policy: modified and renamed
files offer both revisions, deleted files start at base, and added/untracked files
start at current. A rename's base request uses its old path. Revision changes cancel
and remove the prior temporary download. Exact stat/chunk fingerprint validation,
offset/EOF checks and the upstream 64 MiB preview / 512 MiB media limits apply.

Images support local pinch/pan and double-tap zoom, including Android-supported
animated formats. PDFs render lazily into a scrollable page list with page navigation
and per-page zoom. Media uses Android playback controls and pauses in the background.
File actions can share, save, open externally, and copy image content through the
existing narrowly scoped FileProvider. Exported snapshots survive the preview page
so a clipboard consumer or external picker does not lose its source on navigation.
Private partial downloads are removed on errors/cancellation. Text is selectable and
numbered; upstream advanced text/Markdown viewing and broader document preview
formats still need parity work and are not claimed complete.

Verification: 240 JVM tests passed, including seven preview policy, file lifecycle,
export, limit and cancellation cases. A clean Android 17 emulator run passed all ten
Changes cases in 103.843 seconds: tree/pager/copy, error recovery, continuation/font
persistence, context reuse, mismatched revisions, image revision switching/copy,
extensionless image MIME/bytes, PDF page rendering, and audio playback/cleanup.
The initial nine-case run had one fixture synchronization failure after refresh;
its original log is retained alongside the passing run. Test hosts now include the
same Surface and safe drawing insets as the app. This is a local RPC fixture run,
not physical Pixel or live Mac acceptance. A subsequent focused two-case image run
also passed after requiring the blue fixture's rendered screen pixels before capture.
The expanded-diff and image-preview screenshots were inspected. Local evidence is
in `captures/changes/`.

## Hidden-context expansion (2026-09-28)

Added leading, inter-hunk and trailing context bands using the upstream gap rules,
including zero-count hunks, no expansion for deleted files, and no trailing band
for an incomplete diff. Bands reveal 100 lines from the chosen edge, or the full
remaining run when it has at most 120 lines. Revealed context keeps old/new line
numbers and Copy Line; Copy Hunk applies only to actual diff hunks. Stable lazy-row
keys preserve scroll anchoring as context is inserted.

Current file content uses `mobile.workspace.changes.file_stat` and `file_fetch`
with `revision: current`, 3 MiB chunks, the upstream 5 MiB / 200,000-line expansion
limits, and exact filesystem identity comparison across diff/stat/chunks. A changed
identity reloads the diff instead of inserting a different revision. Ordered offsets,
base64 size, total size, EOF and nonempty intermediate chunks are checked. Cancellation
and page generations prevent late downloads from replacing refreshed content. The
transfer also accepts base-blob identities for the upcoming revision preview UI.

Verification: 233 JVM tests passed (13 new expansion/content-transfer cases), zero
failures/errors/skips. Production and Android test sources compile. Two additional
Android cases cover context reuse and revision mismatch; all five Changes UI cases
remain unrun until the combined Changes runtime milestone. No new APK was assembled;
At that point, signed build 150 was the current download and revision preview UI was still open; both features later shipped in build 157.

## Changes viewer foundation (2026-09-28)

Compared the pinned `CmuxMobileChanges` models, file tree, unified parser, pager,
line-copy actions, font preference, continuation policy and shell RPC decoder.
Replaced the flat file list/raw-text view with a collapsible directory tree and
path-keyed diff pager. Directory chains fold into a single row; directory/file
ordering preserves exact paths, including rename metadata, unknown statuses,
binary flags and approximate counts. Refresh, retry, empty and non-repository
states are distinct. Losing the parent connection now shows Back/Reconnect rather
than a blank changes screen.

The parser preserves CRLF content and newline markers, assigns old/new line
numbers, pairs adjacent replacements for grapheme-safe intra-line emphasis, and
builds line/hunk copy text without silently cutting lines at 5,000 characters.
Diff text wraps and is selectable. Pinching changes and persists font size in the
iOS 9–22-point range. Each page retains its scroll position; a seven-page cache and
nearest-first prefetch keep state independent of pager composition. Requests are
scoped to the connection/workspace, fenced against cancelled late responses, and
invalidated when a refreshed repository snapshot changes or removes files.

Continuation now follows the iOS budgets: default 6,000 lines with no explicit
initial override, then 24,000 and 96,000. Failed continuation keeps the current
content available for retry. A larger response that does not grow the transported
window stops offering further growth instead of repeatedly sending larger requests.

Verification: 220 JVM cases passed with zero failures/errors/skips, including 19
new tree/parser/Unicode/continuation/cache/cancellation cases. Production and
Android test sources compile. Three new Android cases are prepared for tree/pager
copy behavior, error recovery, and continuation/font persistence. **They have not
run yet.** Revision previews and hidden-context expansion remain to be implemented;
the combined changes runtime milestone will include them. No APK was assembled or
released for this feature commit; build 150 was then the current download. Local
logs and selected XML reports are retained under `captures/changes/`.

## Upstream refresh (2026-09-28)

Advanced the reference from `4d3385b` to `4c5272e` and inspected the production
changes in the mobile packages. Browser geometry, gestures, payloads and host RPC
handlers are unchanged. The browser store now rejects frames from retired decoders;
Android already fences subscriptions, cancels and joins the old collector before
replacement, and verifies stale-frame rejection in the runtime recovery case.
Other changes concern Apple Ghostty runtime/teardown ownership, a one-pixel workspace
anchor threshold, and Haskell/PureScript chat highlighting. Full Ghostty fidelity,
workspace phone scrolling and agent chat remain open in the corresponding areas.

## Browser image geometry and local lens (2026-09-28)

Matched pinned `BrowserStreamTransform`, `BrowserStreamContentView` and
`BrowserStreamTapClickCounter`. The page now preserves its aspect ratio, fits the
view width and centers vertically. Drawing and pointer input share the same
transform; letterbox taps are ignored. Both scroll axes use the width-fit scale,
with a page-center fallback when the scroll anchor is outside the image.

A unified recognizer arbitrates immediate taps, remote dragging/momentum, a local
1–4× pinch lens, and local panning while zoomed. Pinching or local panning does not
forward wheel/click input to the Mac. Pinching back to 1× clears the local pan and
restores remote scrolling. Repeated taps use the iOS 450 ms / 28-point chaining
thresholds and forward rising native click counts without delaying the first tap.

All 201 JVM cases passed, including seven new geometry/letterbox/coordinate/click
contract checks. Debug/test APKs assembled. Three new Android cases exercise pixel
geometry, click chaining, zoomed input alignment and zoom limits. All 13 browser,
lens and momentum cases passed together in 80.378 seconds. The generated four-color
fixture screenshot was inspected: it preserves the 2:1 page aspect ratio and centers
vertically, with black letterboxing. Pixel assertions and post-pan click coordinates
also passed. Evidence is in `captures/browser/lens-runtime.log`,
`lens-verification.json` and `browser-width-fit.png`. This is a focused emulator
run, not a full 87-case suite or physical Pixel/Mac run. The current signed phone
download, build 157, includes this work together with recovery, momentum and Changes.

## Browser momentum follow-up (2026-09-28)

Compared the pinned iOS `BrowserStreamContentView`, scroll phase policy and scroll
batcher. Android now uses native spline deceleration after a swipe and maps each
delta from view pixels to Mac page points. Drag and momentum phases stay ordered,
with movement coalesced only within the matching phase. A new touch, keyboard or
navigation input, address editing, viewport change, stream replacement, modal,
background or disposal cancels old motion. Pending scroll requests are discarded
before newer input; an already in-flight request remains ordered ahead of cancellation.

All 194 JVM cases passed, including five new phase/backlog/cancellation cases.
The combined debug/test APK build succeeded. The initial Android run passed all
seven browser-view cases, including both recovery cases, but the three isolated
gesture fixtures recorded no input. The fixtures now render content and finish
measured-size recomposition before freezing animation time. A focused rerun passed
all four affected cases: three gesture cases and actual browser-view/RPC scrolling.
This gives ten distinct cases with passing evidence across runs, not a full-suite
run. Checks cover natural momentum completion, new-touch/key cancellation,
viewport/stream replacement, coordinates, phase order, and both swipe directions.

A source review during this run caught and corrected an existing scroll-direction
error: iOS negates UIScrollView content-offset displacement, which already has the
opposite sign to finger motion. Android now sends finger displacement/velocity
without another negation. Both axes and directions are checked. The original
failed run and the passing rerun remain in `captures/browser/momentum-runtime-initial.log`
and `momentum-runtime-rerun.log`; `momentum-verification.json` records the tested
revision. The feature commits did not trigger release builds; the combined signed
build 150 now includes them. The source audit also identified iOS
width-fit image geometry, local pinch zoom, panning while zoomed, and immediate
rising Mac click counts for repeated taps. The subsequent geometry/lens section
above records their implementation and current verification status.

## Browser recovery follow-up (2026-09-28)

Matched the pinned iOS `BrowserStreamRecoveryPolicy`, `BrowserStreamStore` input
watchdog, and browser foreground/background lifecycle. A quiet page alone never
restarts a stream. Forwarded input without a displayed frame for 2.5 seconds arms
one recovery attempt; ordinary state events do not count as frame evidence. The
policy retains the upstream four-second restart backoff and clears evidence for a
new subscription. Android also preserves event order within a millisecond.

Recovery keeps the last image while resetting the new subscription's sequence
check. Requested subscription IDs are known before listening, so old events cannot
seed a replacement stream's high-water mark. Cleanup/start operations are serialized.
Backgrounding stops the stream and cancels recovery timers; foregrounding re-arms
it. Idle input stays usable, while outstanding input is discarded with an explicit
notice and never replayed. The parent shows a reconnecting view while replacing a
failed client. RPC timeouts now remain reportable without swallowing real coroutine
cancellation when the panel closes.

Initial focused verification passed all 189 JVM cases, including eight new
policy/controller, input-queue and cancellation checks. The two new Android cases
now also pass in the combined recovery/momentum run: unanswered-input recovery with
old-subscription frame rejection, and background/foreground stream lifecycle.
Focused local evidence is in `captures/browser/recovery-checks.log` and
`recovery-unit-tests.xml`; runtime evidence is in `momentum-runtime-initial.log`.
The current phone download is build 157. Geometry/lens verification is
recorded above; download behavior and physical Pixel/Mac testing remain open.

## Browser download source follow-up (2026-09-28)

At the refreshed pin, `BrowserNavigationDelegate` converts download navigation
into a `WKDownload` and forwards it to `BrowserDownloadDelegate` in
`Sources/Panels/BrowserPanel.swift`. That delegate saves a temporary file on the
Mac, then moves it to the Mac Downloads directory or opens an `NSSavePanel` when
“ask where to save” is enabled. The inspected mobile browser interface has no
file-transfer or save-panel RPC. This establishes the host-managed source path;
it does **not** prove that downloading through Android has passed live acceptance.
Verify link-triggered downloads and the Mac save preference with a real paired Mac.
No Android-specific download limitation is asserted.

## Browser source audit and implementation (2026-09-28)

Compared the pinned iOS `BrowserStreamPane`, `BrowserStreamSurfaceState`,
`BrowserStreamKeyboardPolicy`, `BrowserStreamInputView`, shared browser payloads,
and `Sources/TerminalController+MobileBrowser.swift`. The streamed-browser action
interface and Mac handler do not contain a download-transfer method. Download
behavior remains an open investigation, not an Android platform limitation.

Implemented the bottom navigation/address/reload/keyboard bar, live history and
loading state, address-edit preservation, measured viewport updates without stream
restart, committed Unicode and native key/modifier RPCs, bounded ordered input with
explicit recovery, live coalesced drag scrolling, stale-frame rejection and image
header checks, plus dialog response identity fencing. The later momentum/recovery
follow-ups above record their implementation and runtime evidence. Four initial Android fixture cases passed for remote navigation/address editing and
viewport updates without restart, direct IME Unicode/keys/modifiers and manual hide,
frame rejection/acknowledgement and cleanup, and late dialog replies. A rendered
fixture screenshot was inspected at `captures/browser/browser-bottom-controls.png`.
The flat blue page in that image is an intentionally generated frame fixture. The last focused check passed 181 JVM cases; an earlier 180-case run had
one notification-feed peer-connection timeout, followed by clean 180- and 181-case
runs. Original failure evidence is retained locally. The browser IME Go label was compiled and checked in the combined integration run.

## Automated evidence (2026-09-28)

Offline task editing now retains the composer across connection changes, allows
saved drafts without a pairing, preserves unresolved groups until an authoritative
handshake, and keeps local folder suggestions available offline. Explicit offline
submission saves edits without creating an uncertain-operation snapshot or queuing
work. Drafts opened during the first handshake adopt the verified Mac identity.
This follows the pinned iOS `TaskComposerSheet` connection warning and editing
policy. The existing options sheet now remains open when switching Macs.

Verification for this feature: `:app:testDebugUnitTest` and
`:app:compileDebugAndroidTestKotlin` succeeded with 173 JVM tests, zero failures,
errors, or skips. Four Android cases now have passing runtime evidence for offline
entry/persistence/reconnect, unpaired draft resume, group inventory and local folder
selection after disconnect, and first-handshake adoption. The combined run also
passed the existing terminal IME and primary-navigation cases. Its initial result
was 9/11: the group assertion ran before the UI applied the changed connection
state, and a task-options tap raced the system IME animation. Waiting for the
observable state and invoking the dock’s accessible action produced a clean
2/2 focused rerun, including the production Mac-switch flow. That is 11 distinct
cases with passing evidence across runs, not a clean full-suite run. Original logs
and the focused rerun are retained in `captures/browser/`. This milestone first shipped in signed build 143; the current download is build 157. No physical Pixel is attached to adb.


240 JVM tests pass. The Android suite now contains 97 cases (including ten changes-viewer/preview checks, seven browser checks, three browser lens checks, four offline-task checks, four task attachment/layout checks, three terminal momentum gesture checks and three browser momentum gesture checks): twenty-three Compose
flows, seven task model/submission controls checks, four template checks, five task destination checks, five task draft checks, six completed-task recovery checks, one
explicit two-process draft check, two workspace drag checks, four Activity-recreation
checks, four notification/service checks and two
renderer checks. Earlier checks have passing evidence across suite and focused
runs. The All Computers workspace flow passes separately; nine navigation,
notification, lifecycle and terminal-entry cases passed for the preceding primary
navigation changes, including a focused rerun confirming Search keyboard visibility. They run on Android 17
(API 37) ARM64 emulator using the Pixel 6a display profile. The checks cover unread
filtering, terminal output appearing, keyboard-driven viewport reduction,
exactly one paste/submit request, and viewport cleanup on return to workspaces.
The composer flow also checks multiline preservation, separate terminal drafts,
encrypted persistence before sending, a disabled send action while an
acknowledgement is pending, retained text after rejection, explicit retry, and
insertion without submission. JVM checks cover delayed acknowledgements,
newer edits, Mac isolation, interrupted-send restoration, and sign-out cleanup.
The attachment flow checks the Android document-picker callback, 2048-pixel
image preparation, encrypted payloads and restored metadata, image acknowledgement,
file retention after rejected text, stable upload IDs on explicit retry, and cleanup.
The picker result is intercepted with local fixture files; cloud document
providers and a real Mac are not exercised. JVM checks also cover multi-chunk
byte fidelity, shell quoting, stopping on connection changes, attachment removal,
and resource budgets. The direct-input flow calls Android InputConnection APIs
through the actual native keyboard view and RPC client, checking uncommitted
composition, UTF-16/code-point deletion, control/navigation/function keys,
rejected delivery without queued replay, explicit resume, preserved composer
drafts, and rejection of a stale keyboard connection after switching terminals.
The on-screen keyboard and resized terminal were visually inspected. This does
not prove live vim/htop behavior, physical keyboard layouts, or every Android IME.
The rendering checks compare actual pixels from mixed Unicode spans against
separate cell placements, exercise device ICU cluster boundaries and widths,
and verify invisible glyphs, indexed backgrounds, and a wide underline cursor.
JVM cases cover alternate-screen/full-frame fences, stale revisions, carried
burst scrollback, resize history invalidation, semantic theme colors and
accessible column gaps. The rendering bitmap was visually reviewed.
The raw-stream flow checks split UTF-8 and ANSI bytes, duplicate suppression,
alternate-screen restoration, forced-gap recovery, scrollback/Latest without a
viewport resize, and suppression of parser-generated input. JVM checks add
transport selection, hybrid transitions, malformed/overflowing sequences,
bounded pending output, grid-to-VT seeding and actual captured Vim output.
The touch/copy flows check an immutable local copy sheet, exact Copy All text,
Android selection handles and selected-text copying, menu/long-press entry,
suppression of remote scroll on a screen-anchored primary screen, alternate-screen
wheel direction, cell coordinates and workspace/surface/client scoping, and a
viewport-anchored host's returned scroll frame. JVM cases cover shared hit-test
geometry, slow-response coalescing, prefetch cadence, cancellation, uncertain
scroll delivery, copy history boundaries and trimming blank screen tails.
Search/navigation checks cover independent tab queries, submit/cancel semantics,
bounded scalars without broken surrogate pairs, accent/width folding for
notifications, group/computer/description/notification metadata matches, and
returning from a search result with the committed filter intact. Moved-terminal
notifications resolve to the live owner only with the host provenance flag;
unresolved or ambiguous workspace destinations do not mark a notification read. Decoder cases
cover nullable metadata, duplicate IDs and redundant row content. The emulator
exercises moved-surface RPC scoping and an unavailable destination, and captures
notification search and its error state. Additional alert checks use two local
fixture Macs for exact saved-pairing and terminal navigation, cancellation of a
superseded initial feed handshake, repeat opening and
forgotten-Mac rejection. Delivery tests exercise real Android notification and
PendingIntent identities, Keystore-backed destination restoration, per-Mac read
cleanup, sibling cmux installation identity, foreground-service lifecycle and
stale-feed suppression. The production feed worker's invalidation and disconnect
path runs against a framed TCP peer. The combined foreground feed independently follows every saved Mac while the UI
is started, retains the latest snapshot during a disconnect, and closes its feed
sessions when the UI stops. It merges at most 2,000 items, sorts deterministically,
and uses a 300-item row window with load more. Projection tests cover local day
boundaries, daylight saving, contiguous same-pane groups within two hours,
filter-before-grouping and expansion/anchor retention. Coordinator tests cover
read/unread revision fences, offline bulk-action failures and replacement Mac
identity. Emulator flows cover expandable history, the unread filter, long-press
and swipe actions, bulk confirmation/cancel, identical IDs from two Macs, computer
search, exact terminal navigation and bulk actions despite a narrower search.
Computer filtering and live-destination visibility are applied before the global
cap, preserving older valid rows. Source snapshots remain intact, including
hidden notifications. Bulk read targets the captured computer scope and includes
its retained rows hidden by search; an unknown/forgotten captured scope cannot
widen to all computers. Selection persists in the encrypted account store, is
restored on Activity recreation, and falls back to All Computers after removal.
All Computers now aggregates saved-Mac workspace/group snapshots. Workspace and
group keys and local group expansion include the owning pairing origin; search
includes the owning Mac and uses the same scope as the computer picker. Each
workspace action routes to that exact background session, re-resolves the latest
window/group scope, and refreshes the owning list after success or rejection.
Unavailable or forgotten owners never fall back to the foreground Mac. Workspace
snapshots advance independently of notification revision fences. Opening a row,
its changes or an exact browser/created terminal promotes the owning foreground
connection and revalidates the route and fresh destination before navigation;
workspace navigation never marks a notification read.

Workspace unread counts preserve the host's optional 64-bit count. Expanded
headers show the anchor's count; collapsed headers aggregate all members, and an
unknown contributor keeps the aggregate unknown. Unread states with unknown or
zero counts display the iOS minimum badge of 1. Read rows retain their gutter so
icons do not jump. TalkBack receives the state/count on the row; decorative
badges and glyphs are excluded from duplicate announcements. Overflowing remote
aggregates remain unknown rather than wrapping into a negative count.

The focused Android 17 unread flow passes with actual Compose controls and the
framed loopback RPC client: expanded/collapsed counts, legacy unknown counts,
read/unread refresh and search flattening. It passed together with three existing
hierarchy/drag regressions (four tests total, 80.235 seconds). Expanded/collapsed
screenshots were visually inspected. The same badge flow also passed at 150% system text size (62.47 seconds),
with expanded and collapsed screenshots reviewed. Other accessibility sizes and
physical Pixel behavior remain unverified.

Group headers use a folder or a common custom-symbol equivalent, a separate
collapse chevron, and a pin next to the name. Lucide sources, hashes, ISC/MIT
notices and the conversion script are committed. These are Android vector
shapes, not Apple's SF Symbol glyphs: unknown symbol names currently fall back
to a folder. Complete custom-symbol fidelity remains open. The existing cmux
brand logo remains the upstream asset.

Workspace hierarchy now preserves spatial host order, represents anchors only
through group headers, indents children, aggregates unread visibility on collapse,
and retains empty headers in their pin tier. Collapse overrides are persisted per
Mac/group. Long-press reordering and edge scrolling use explicit inside/outside
group slots, with accessible Move up/Move down actions. Only an unfiltered,
connected, single-window/single-computer list can reorder. Up to three predictions
may be pending; writes are serialized and scoped to the verified owner/window.
Rejection, owner removal, external order changes, and changed pin tiers invalidate
dependent predictions. Live titles, previews, created rows and deleted rows remain
current while order is predicted. A fresh move can proceed after a rejected chain.

The pinned unmodified Swift algorithms generate 46 reference snapshots, 2,434
rendered drops, and 20,360 direct proposals. JVM tests compare hierarchy, canonical
RPC intents, and predicted ordering against those reference results, including
empty/promoted groups, collapsed groups, noncontiguous membership, pins and
invalid targets. Two empty-group defects were found and corrected by this
comparison. Loopback RPC tests exercise delayed acknowledgements, intermediate
snapshots, the three-move bound, rejection, external pins, forgetting and stale
window/owner guards. On the Android 17 Pixel-profile emulator, the full hierarchy flow opens the
anchor, preserves collapse across navigation, drags a row through the actual RPC
client, verifies the new order and disables reordering in search. A held gesture
moves beyond recycled source rows using edge scrolling; an accessibility check
exercises Move down and its disabled state. The first runtime pass exposed a
conflict with LazyColumn touch scrolling; disabling touch scrolling while a row is
held fixes cancellation while retaining programmatic edge scrolling. All three
new checks pass in focused runs. Filtering/terminal keyboard entry, scoped search
and multi-computer workspace mutation/navigation also pass for this batch.
`workspace-hierarchy-reordered.png` was visually reviewed. Initial interrupted
or failed diagnostic runs are not passes. Physical Pixel testing remains pending.
Connection cleanup captures the specific composed client; route effects capture
the same session as their keys and commit keyboard/navigation changes on the
Android main thread after revalidating the route.
Legacy alert identities without device/installation binding are retired on
upgrade; saved accounts, pairings and terminal drafts are retained.
The pinned iOS implementation retains feed snapshots in memory and clears them
with account state; it does not persist the feed to disk. Android follows that
model: a ViewModel retains snapshots and expansion across Activity recreation,
while saved tab/query/filter and loaded-row-window state is restored and a new process fetches fresh
content. Opaque Android alert routes remain encrypted on disk independently.
System-shade taps through actual process death, boot/sleep reliability, server
push fallback and physical-device navigation remain unverified or incomplete.
Screenshots were inspected with and without the keyboard. This caught and
fixed terminal background overdraw, skipped render updates, and a false
cancellation error during resize. It uses a local RPC fixture; live Mac auth,
pairing, real terminal applications, and the physical Pixel remain unverified.
See [ANDROID_TESTING.md](ANDROID_TESTING.md) for reproduction.

## Navigation presentation

The primary scaffold follows the pinned iOS 26 structure: Workspaces and
Notifications in a rounded tab group, a separate round Search control, a
floating New Task action on Workspaces, and Settings through the cmux logo.
The selected tab and unread badge have explicit Android accessibility semantics.
Search temporarily replaces the tab group with an editor and cancel button;
submit/result navigation commits its scope, while cancel/app Back clears it.
Committed filters survive tab/terminal navigation and Activity recreation.
The Android implementation uses Compose-drawn translucent surfaces and native
Android focus/IME handling. Apple's system glass material is not reproduced by
this implementation; final material, typography and physical-device visual QA
remain open.

## Work order

1. Make pairing usable and durable: encrypted credential storage, QR scanner,
   state restoration, and connection recovery. The helper remains an interim
   transport while the native cmux account and pairing path is validated.
2. Port the official pairing/ticket/frame/RPC contracts and replace the helper
   with the Mac's mobile endpoint. Verify against a pinned cmux build.
3. Finish terminal rendering and the full input contract, including remaining
   Ghostty fidelity, kinetic scrolling, live mouse reporting and inline graphics. Use vim, htop, Claude Code, and Codex as acceptance cases.
4. Complete workspaces, notifications, browser, search, changes, tasks,
   settings, accessibility, and background behavior.
5. Run the parity checklist on the Pixel and ship a stable signed APK.

The current Android build is not a parity release. Keep this table honest as
implementation and physical-device verification progress.

## Task model and effort contract

Task creation probes `mobile.task.models.list` for the selected provider and
fetches the cmux `/api/agent-models` catalog concurrently. A cold catalog can
populate the picker while Mac discovery is pending; nonempty discovered Mac
results (including default-only metadata) take priority. Cache identity includes
the exact pairing origin and provider, and account clearing or forgetting a Mac
fences stale refreshes. Transient failures preserve previously usable data.
Discovery retries while the composer is open with the iOS 500 ms–15 s capped
backoff; cancellation, provider unavailability, unsupported/disabled RPCs, authorization
failures, account mismatch and invalid requests stop it. Structured RPC error
codes are preserved without closing an otherwise usable connection.

The model menu captures its presented choices so a delayed Mac catalog cannot
replace an option while the user taps it. A chosen model survives delisting.
Efforts come only from the selected model or the Mac's Default metadata; a
Default model choice adds no explicit model argument. Claude `--effort`, Codex
`-c model_reasoning_effort=`, and OpenCode `--variant` values are quoted with the
upstream provider algorithm. Prompts remain in `CMUX_TASK_PROMPT`.

The command port is compared with 695 results from the pinned unmodified Swift
provider implementation, including repeated/existing flags, quoted arguments,
end-of-options, command boundaries, redirects, Unicode and embedded apostrophes.
JVM checks cover discovery completion order, default-only host results, stale
refreshes, forgetting/sign-out, owner cancellation, timeouts, and selected model/
effort reconciliation. This does not verify the installed agent CLIs on the Mac.
Task attachment flows, full composer layout parity and agent chat remain open.
Editable templates, saved drafts and completed-operation recovery are described below.

The Android 17 Pixel-profile emulator passes all five task controls flows and the
existing primary navigation/search/composer-entry flow together (six tests,
175.423 seconds). The five task flows use the production Compose/RPC path with a
synthetic Mac and injected catalog. They verify explicit model/effort submission,
Default, plain Shell, older-host fallback, and prompt/operation-ID retention
after a timeout followed by explicit retry. The final screenshots were inspected.
This remains separate from actual
Mac agent launch, task recovery and physical Pixel acceptance.


## Task submission identity and response handling

Task submission now compares the effective `workspace.create` parameters and
exact Mac pairing origin. Prompt or directory whitespace that produces the same
request keeps the retry ID. An effective command/environment/destination change
gets a distinct ID; reverting an unsent edit returns to the submitted baseline.
This follows `MobileTaskSubmissionIdentity` and `MobileTaskSubmissionSnapshot`
at the pinned upstream revision. Caller JSON mutations cannot alter the baseline.

The task composer requires a nonempty `created_workspace_id` identifying a unique
workspace in the returned list. Incomplete or malformed responses keep the prompt
and retry identity visible. A valid partial creation response updates existing
rows and appends new workspaces without dropping unrelated rows. Group metadata
is retained until a full list refresh, matching the iOS merge path. Navigation selects
the returned workspace and terminal, clearing any prior browser selection.
Requests are fenced to their original client and Mac: a late response after
connection replacement cannot navigate or apply a workspace list to a new session.

The initial submission batch covered a mounted composer session. Durable drafts
and capability gating are described below. Completed-operation reconciliation is
covered in the recovery section; physical-Mac agent launch remains required.


## Saved task drafts

The task composer owns a durable draft identity independent of its view or RPC
connection. Its Drafts sheet lists other drafts newest first, supports resuming,
deleting and starting a new draft, and saves the active draft before switching.
Leaving edited content offers Save Draft, Delete Draft and Keep Editing. Empty
selection-only drafts are omitted; a submitted untitled Shell keeps its retry
record. The collection retains the newest 20 nonempty drafts, matching iOS.

Prompt and directory text, template identity/name/raw command, explicit model metadata,
Default-model effort metadata and the last composed request are encrypted with
the Android Keystore account store. Autosaves are ordered and conflated, background
and disposal request a flush, and task creation waits for a durable request/ID
write before any RPC. Restoring equivalent content reuses the persisted ID;
changing effective parameters resolves a different operation. Successful creation
removes only its own draft. A failed local save prevents transmission.

Account login incarnations and per-editor leases fence stale writers. Sign-out
removes task drafts, and a delayed old token refresh cannot overwrite credentials
or clear a newer account's collection. Delayed model reconciliation is also fenced
to its originating provider. A resumed draft selects its saved computer and waits
for that exact pairing connection before mounting the composer. Task creation
requires the host's `workspace.task_create.v1` capability.

Remaining task work includes attachments, offline composer editing, draft rebinding after a pairing code changes, full
composer styling and agent chat. Saved drafts remain bound to the saved pairing;
they are not automatically redirected to a different or renewed pairing. Physical
Pixel/Mac verification remains required.


## Completed-operation task recovery

A normalized `already_completed` creation error preserves an immutable copy of
the accepted request and immediately retires its normal retry ID. The anchor and
fresh identity are stored with the encrypted draft. Equivalent requests cannot
use Create Task. Refresh Workspaces refreshes the full workspace/group listing,
then explicitly reconciles using the old operation ID and exact original request.
A successful response follows the existing validated workspace/terminal navigation
and removes only the recovered draft. No automatic retry is sent.

If reconciliation again returns `already_completed`, Refresh Again remains
available and Start Again opens the same duplicate-task confirmation as iOS.
Only confirmation submits the new operation ID. A list-refresh error, disk-save
error or unrelated RPC error cannot authorize that action. Replacing the client
or editor prevents a late result from installing recovery or navigating.

Late discovery preserves the submitted model/effort snapshot during recovery.
Effective edits detach the recovery controls; reverting to equivalent content
restores them. A genuinely different submitted request clears the old anchor.
Cold restoration preserves the anchor and retired identity and requires refresh
again, matching iOS's restored recovery phase. Older encrypted drafts remain
readable. Persistence and RPC checks use the same account/editor/origin guards
as normal task submission. Physical Pixel/Mac verification is still required.


## Editable task templates and successful defaults

The Agent menu lists editable shipped Claude, Codex, OpenCode and Shell templates
and custom templates, with Edit Agents opening the list and add/edit form. Names,
icons, raw commands and default directories are editable. Built-in identities
remain protected from deletion even after renaming or changing their commands;
custom entries can be removed. Provider/model discovery follows the actual command.
A blank command opens a plain shell. Nonblank scripts retain their exact source,
and the task prompt is passed through `CMUX_TASK_PROMPT`.

The three upstream agent images are copied unchanged from the pinned iOS source;
paths and SHA-256 hashes are recorded in `TASK_TEMPLATE_ASSETS.json`. Ten symbol
choices use the app's attributed Lucide equivalents. Custom emoji retain one ICU
grapheme. These symbol shapes are Android equivalents rather than Apple glyphs.
The editor shares the main window's Android keyboard and system-bar insets.

Template edits commit to the encrypted account store before updating the UI.
Errors retain the form; account changes fence stale writes. Unsaved editor fields
survive Activity recreation. Successful task creation remembers the template,
exact paired Mac, and up to 20 recent directories per Mac. A new task selects
the last successful Mac only while that pairing is still saved. Directory defaults
prefer the template, then the selected/recent workspace (focused terminal first),
then the last successful directory, then `~`. Explicitly typed paths survive agent
changes. Saved drafts retain their own Mac, template and directory. Deleted-template
restoration falls back to an available template and clears the old submission anchor.

Task attachments, offline composer editing, full composer
visual parity, agent chat and physical Pixel/Mac acceptance remain separate work.


## Task destinations and workspace options

Task Options follows the pinned iOS name field and Machine/Directory/Workspace
group card order. Explicit names override prompt-derived titles; generated titles
retain 60 grapheme clusters rather than splitting emoji. Group selection waits
for authoritative inventory, retains missing selections until explicitly resolved,
and checks current membership again after the durable save and before sending.
The wire field is `group_id`, gated by `workspace.create_in_group.v1`.

Changing the Mac durably reassigns the draft before reconnecting. It preserves
the prompt, name and typed directory, clears the previous Mac's group, and retains
retry/recovery anchors with their original pairing origin. Identical group IDs
on two Macs do not carry selection across computers.

The folder picker uses `mobile.directory.list` in 50-entry pages and debounced
`mobile.directory.search`. It supports Home/Computer, parent navigation, recent
locations, unreadable-folder states, explicit retries and local suggestions when
remote search is unsupported. Responses require bounded, ordered, nonduplicate
pages; changed page inventories keep the earlier page and report an error. Stale
queries cannot replace newer results. Search coverage limitations remain visible.
Suggestion ranking matches 115 outputs generated from the pinned unmodified Swift
implementation, including source priority, recency, fuzzy matching and byte order.

These controls use Android full-screen navigation and attributed vector symbols.
Full composer visual parity, task attachments, offline editing, agent chat, pairing
renewal and physical Pixel/Mac acceptance remain open.

The five destination checks and production-screen Mac-switch flow pass together
on the Android 17 emulator (six cases, 158.872 seconds). Options and folder screens
were visually inspected with system bars and the search keyboard visible. The
full 63-case instrumentation suite was not rerun for this batch.


## Terminal momentum

Android native spline decay, whole-row fractional carry and the upstream 450 ms
alternate/legacy momentum limit are implemented. New touches, explicit input,
Latest, View as Text, geometry/surface/mode replacement and disposal cancel motion.
Confirmed local-primary history stops at its bounds. Typing drops queued scrolls;
late scroll-response grids cannot replace the display after explicit input.

The initial implementation passed 163 JVM tests and compiled both debug APKs;
the integrated batch now passes 172 JVM tests. Four added
JVM checks cover carry/reversal, animated delivery and its deadline, cancellation/
history bounds and dropping queued motion. Three Android gesture checks now pass in the integration batch described below.
Build 138 includes this change. Pixel/Mac verification and pixel-precise rendering
remain open.


## Task attachments

The pinned iOS task-attachment contract is now implemented: 10 attachments,
8 MiB prepared images, 32 MiB files (including empty files), and 64 MiB total.
Photos, document selection, clipboard file URIs, previews and removal feed the
account-scoped encrypted task draft. Staged payloads survive repository reload;
deleting the draft or signing out removes its files. File viewing uses a narrow,
non-exported FileProvider and a temporary cache export created only on Open.

Uploads use 3 MiB chunks, stable upload IDs and the task submission's operation ID.
The created workspace receives CMUX_TASK_ATTACHMENTS and the upstream prompt
suffix. Attachment identity stays in the local retry snapshot and is stripped
from the workspace.create wire request. Accepted-operation reconciliation skips
uploads; an explicit new task gets a new operation ID. Shell tasks retain but do
not upload their draft attachments. Capability and session guards gate delivery.

34 focused JVM checks pass (9 attachment contracts, 9 submission identity checks,
12 task draft checks and 4 terminal attachment regressions). Four Android checks
now pass for picker/import/retry, encrypted restoration/sign-out, preview/external-
viewer cleanup/removal, and keyboard-dock layout. Build 138 includes this feature. Rich IME attachment paste and real Mac/Pixel testing remain
open; clipboard content-URI import does not claim rich IME coverage.


## Task composer canvas and keyboard dock

The pinned `TaskComposerLayout.swift` now supplies the main Android composition:
a full-height borderless prompt; a centered name/directory title; back and draft
icons; and a compact dock with task options, attachment selection, horizontally
scrolling agent/model/effort pills and a circular submit action. Edge fades show
that further choices can be scrolled into view. Model loading/error states remain
visible; effort choices are hidden when the host supplies none. Task Options owns
folder browsing and selection, replacing the old directory form on the canvas.

The dock remains above the Android IME while the prompt area shrinks. Visual
surfaces remain 38 dp, with Android 48 dp activation targets and a matching scroll
viewport. Icons and accessibility descriptions identify each action; long choices
scroll without displacing the fixed options or submit controls. Drafts, recovery,
agent templates, model/effort submission and destination checks use the new UI.


### Combined integration evidence

All 172 JVM tests pass. The initial seven new runtime cases passed together
(`OK (7 tests)`, 63.230 seconds). The 38-case regression batch passed 36 cases;
two obsolete UI assertions expected a fixed New Task title and an empty disabled
effort picker. Both now assert the upstream behavior instead. The final focused
12-case batch passed together (`OK (12 tests)`, 132.686 seconds), including those
corrections, all seven model/effort cases, four attachment/layout cases and the
production navigation/search flow. Together these runs provide passing evidence
for 45 distinct Android cases; the full 70-case suite was not rerun.

Two diagnostic runs stalled inside Compose 1.9.1's repeated performScrollTo helper
on the compact dock and were explicitly stopped; they are excluded from passing
run totals. The test helper now performs one scroll action, advances the controlled
dispatcher, verifies visibility, and clicks the actual button. The final clean run
uses that helper. The dock's scroll viewport also matches its 48 dp touch targets.

Inspected emulator captures: `captures/task-composer/task-composer-keyboard.png`
and the production-flow `task-composer-canvas.png`. Physical Pixel/Mac acceptance,
rich IME attachments, offline task editing, agent chat, Iroh, full Ghostty/inline
image fidelity and other open rows above remain unverified or incomplete.


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


## Signed integration build 143 (2026-09-28)

[GitHub run 36356036737](https://github.com/DocMorphic/cmux-app/actions/runs/36356036737)
succeeded at `636b9ffc93937077f93a13b08cd3818a30f597f3`. It includes offline task
editing and the browser controls/input/viewport milestone. The package/version,
stable signing certificate, new browser/offline feature code, absence of test
fixtures, existing license assets, task FileProvider scope and agent-image pixels
were verified in the signed artifact.

Served SHA-256: `90e7c08b6247a8b513f8f85c92338ac065968d1023f95ef8fe3d04426c0b739f` (9070804 bytes).
The file was replaced atomically on the existing tailnet download server, then
fetched back over HTTP and matched against the verified artifact. The local receipt
is `captures/releases/636b9ff-verification.json`; runtime evidence and the inspected
fixture screenshot are in `captures/browser/`.

This is an integration preview. The full iOS parity goal and physical Pixel/Mac
acceptance are still open.

## Signed integration build 150 (2026-09-28)

[GitHub run 36358173423](https://github.com/DocMorphic/cmux-app/actions/runs/36358173423)
succeeded at `6aba8c2977f3b47516d03cc77671cfaa8f0bdd22`. This combined milestone
includes browser stream recovery/lifecycle, native momentum/cancellation, corrected
wheel direction, aspect-preserving width fit, local pinch/pan and native repeated
click counts. The feature code passed 201 JVM cases and a clean 13-case Android 17
browser integration run before packaging. The upstream reference was refreshed to
`4c5272e9153eca2033c9f40ac749f0c3a5bcb291`.

Verified the release package/version, unchanged signing certificate, recovery/
momentum/lens feature classes, instrumentation-fixture exclusion, license assets,
agent-image pixel equality and FileProvider scope. The existing tailnet APK server
was updated atomically and its HTTP response matched the verified artifact.

SHA-256: `a07857531b1a0d1eb7921a63746c36b4fe4c8780c0017c1100070b4c33586b23` (9103572 bytes).

Local receipt: `captures/releases/6aba8c2-verification.json`. Runtime logs, geometry
unit checks and the inspected generated-image screenshot are in `captures/browser/`.
This remains an integration preview; full iOS parity and physical Pixel/Mac
acceptance are not complete.


## Signed integration build 157 (2026-09-28) — current download

[GitHub run 36360218971](https://github.com/DocMorphic/cmux-app/actions/runs/36360218971)
succeeded at `d53abe9b05fc5912dc820738a725360096e7aa80`. This combined Changes
milestone includes the tree/pager/numbered diffs, continuation/copy/font controls,
revision-checked hidden-context expansion, Before/After image/PDF/media previews,
and file actions. The source passed 240 JVM cases, a clean ten-case Android 17
Changes run, and a focused two-case rendered-image/MIME rerun. The upstream remote
HEAD was rechecked and still matches `4c5272e9153eca2033c9f40ac749f0c3a5bcb291`.

Verified package/version, the unchanged signer, Changes and earlier feature classes,
instrumentation-fixture exclusion, licenses, agent asset pixels and FileProvider
scope. The tailnet server was updated atomically; its full HTTP response matched
the signed artifact. Local receipt: `captures/releases/d53abe9-verification.json`.

SHA-256: `a847a2f9465cac4ac62883b6401217fac4624bd1f3b85223fbbfb127b1cd1e33` (9218260 bytes).

The current download is build 157. Evidence and inspected fixture screenshots are
in `captures/changes/`. This is an integration preview: advanced artifact/text
viewing, terminal Files gallery, remaining terminal/transport/settings/notification parity,
and physical Pixel/Mac acceptance remain open.
