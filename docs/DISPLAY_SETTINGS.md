# Workspace display and scrollback settings

## Source contract

Scoped reference: `0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`.
Reviewed `MobileDisplaySettings.swift`, `MobileSettingsView.swift`,
`WorkspaceRow.swift`, `MobileWorkspacePreview+Display.swift` in
`Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI`, plus shared
`MobileTerminalScrollbackPreference.swift` and render-grid emission constants.
The global parity pin is unchanged.

- Workspace titles use one line by default and unlimited wrapping when enabled.
- Preview lines offer one or two, default two, reserving the chosen height.
- Nonblank trimmed descriptions precede activity, reserving two lines.
- Activity prefers the host preview, then first terminal name, then workspace name.
- Scrollback choices are 1,000 / 4,000 / 10,000 / 20,000 rows; default 4,000.
  Stored/requested depths clamp to 0–20,000. This controls cold screen-anchored
  history hydration, not the host terminal's own history limit.

## Android implementation — 2026-10-04

Settings writes to the existing `cmux-display` private preferences. The production
Mac workspace list observes changes, including from a reopened UI. Wrong-typed
stored values use defaults, and numeric values clamp to their supported range.
The replay effect reads the latest depth when history is absent; changing the
setting does not tear down a live terminal or send input. Legacy non-screen-anchor
RPCs retain their shape. Previously both RPC and render-grid decoding silently
capped history at 10,000; both now accept the 20,000 option. Local grid history
remains bounded at 20,000 and evicts the oldest row when more output arrives.

SSH providers have separate history mechanisms; this setting describes Mac native
terminal hydration. No claim is made that changing it reconfigures a remote tmux
or shell's history. Haptic, legal/support and remaining Settings/source/visual parity still require
their own audit and implementation. Alternate-screen controls are covered by
the later checkpoint below.

## Verification

- 26 focused JVM cases pass: RenderGrid (11), terminal delivery/RPC (5),
  TerminalStreamMirror (10). The new history check renders the oldest/newest
  rows of a 20,000-row snapshot and verifies oldest-row eviction on new output.
  The RPC check verifies all choices, default/clamping and legacy wire shape.
- Debug/test assembly passed in 1m 36s; final description/preview alignment
  rebuilt in 53s. Later test-only builds passed; the final one took 18s.
- The real-row Android test passes: measured Text layouts verify wrapping,
  truncation and one/two preview lines; remount retains settings; corrupt stored
  types/ranges resolve safely. Its isolated component screenshot was inspected.
- The earlier production-screen test passed in 21.98s under the Compose test
  dispatcher (the original claim of normal Android dispatch was incorrect):
  actual Settings controls, persisted selection, back to the workspace, an actual
  framed RPC requesting 20,000 history rows, then rendered terminal text.
  `production-settings-final.png` shows the selected controls in the real screen.
- Existing workspace delete/cancel/action regression passed in the initial run.
  These are three distinct selected Android cases passing across runs, not a
  claim that every intermediate run passed or that the full suite was rerun.
- Pinned engine packaging passes; final crash buffer is empty. No release APK
  or signed milestone was built. The existing emulator was stopped and reaped.

### Retained failures and reliability limits

The first compile caught a duplicate local `displayPreferences` name; the new
observed value is named `displayState`. The initial integration test used a
helper that expected two notifications without seeding them. That fixture error
was corrected; the final test does not depend on a notification count.

An emulator Digital Wellbeing ANR obscured the first component screenshot.
It was dismissed, that background app was force-stopped for later emulator tests,
and unobstructed screenshots were inspected. It was not a cmux crash.

The original integration test in NativeFlowTest then stalled in Espresso's next-
frame idling path with StandardTestDispatcher (captured SIGQUIT stack; main Looper
idle). The live run was explicitly stopped after diagnosis, not counted as a pass.
That replacement test used the Compose rule's default unconfined test dispatcher
and completed Settings navigation; it did not use normal Android dispatch. Two later runs reached a blank terminal and timed out: the preserved
method list has event subscribe/unsubscribe traffic but no viewport/replay request.
Adding failure diagnostics alone preceded the final passing run; production code
was unchanged. **See the investigation below for the corrected test setup and narrower diagnosis.** Do not infer reliable startup from one
pass, or call this a physical-device acceptance result.

Evidence under ignored `captures/runtime/display-settings/`: original/final build
logs, retained JVM XML, `runtime.txt`, `runtime-final.txt` (aborted idling run),
`held-trace.txt`, `runtime-dispatcher.txt`, `runtime-render.txt`,
`terminal-failure.txt`, final `runtime-diagnostics.txt`, and inspected screenshots.
The final test writes fixed-label RPC diagnostics and peer failures if it fails
again, so the next run can distinguish transport, cancellation and presentation.

Physical Pixel/Mac acceptance remains open; this change is not in signed build
517 and does not complete the full app goal. No physical device was visible in ADB.


## Attach investigation — 2026-10-04

The retained failure semantics contain “This terminal was disconnected. Reattach
before continuing.” The missing viewport was a local traffic-admission rejection,
not an unanswered subscription: all subscription RPCs completed successfully.
Temporary stage logs confirmed nonzero geometry and completion of the terminal
subscription. They also showed a UI effect continuing on an IO worker under the
Compose test rule. Inspection of the cached ui-test 1.9.1 bytecode confirms its
default `UnconfinedTestDispatcher`; supplying a regular dispatcher is not a way
to replace the rule's test recomposer. The earlier “normal Android dispatch” label
was wrong. Temporary production logging was removed after this diagnosis.

`NativeDisplayRuntimeTest` replaces that integration case. It launches the existing
debug-only `NativeLifecycleTestActivity` with ActivityScenario and UI Automator,
without a Compose rule or test recomposer. The production NativeScreen therefore
uses the Android main dispatcher and frame clock. The component-only row/layout
checks stay in `NativeDisplaySettingsTest`.

The first real-dispatch run rendered the terminal, requested 20,000 rows, and
replayed again after Activity recreation. It failed afterward because the test
incorrectly expected the workspace list; the retained pane had already restored
the terminal. This failed run is preserved, not counted as a passing test.

This narrows the earlier failures to invalidated local ownership in the test
execution context; it does not prove the absence of a rare production race.
Physical Pixel/Mac acceptance and full shared-connection-graph acceptance remain
open. The fixture uses the real screen and socket RPC but injects a local
connector, so it does not establish account, Iroh or push acceptance.

Evidence: ignored `captures/runtime/terminal-attach/` contains the first repeated
failure, temporary stage-log build/run/failure, cached-library dispatcher
inspection, and real-dispatch runtime results/screenshots. Signed build 517 is
unchanged. No physical Pixel appeared in ADB during this investigation.


Final verification: `final-runtime.txt` reports **OK (2 tests), 43.388s**:
real Activity Settings → 20,000-row replay/render → automatic reconnection after
recreation → workspace reopen → actual `terminal.paste` command delivery, plus
the component row/wrap/preview/remount check. The first and recreated terminal
screenshots show rendered fixture output. The Settings screenshot caught menu
dismissal before the selected label repainted; persisted-value assertions and
actual replay parameters establish the choice, not that transitional screenshot.
The crash buffer was empty. Debug/test assembly succeeded (68s; final test-only
rebuild 19s). The final app APK is byte-identical to the prior checkpoint:
`3271534b51a5ab8d5bed7efe757b4de8f637e6cbbe0985e1eb07d468bd0e6c2d`.
The test APK SHA-256 is
`fab672138ee572438e353f96e08b7351f5f95abdf9719a05ff1600fd092ddade`.
An intermediate rebuild was interrupted to correct the test's input selector;
a compiled-bytecode check then caught a stale RPC assertion before it ran. The
final rebuild contains the actual `terminal.paste` assertion. These were test
setup corrections, not app changes or passing runs. The sole AVD was stopped
and reaped. No additional emulator, signed APK or release was created.


## Alternate-screen notice and full-height preference — 2026-10-04

Reviewed at the same scoped iOS reference `0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`:
`MobileDisplaySettings.swift`, `MobileSettingsView.swift`,
`AltScreenNoticeButton.swift`, `WorkspaceDetailView.swift`, and the terminal
package's `GhosttySurfaceView.swift` (`alternateScreenSizingEnabled` and
`viewportSnapshot`). The global upstream parity pin remains unchanged.

Android now offers the two Terminal settings from iOS:

- **Full-Screen Sizing Notice** defaults on. An orange triangle appears only
  for the selected alternate-screen terminal. Its anchored, scrollable popup
  contains the iOS explanation and **Don't Show Again** action. Back/outside
  dismissal leaves the preference on; permanent dismissal persists across
  recreation, and Settings can turn it back on. A surface/connection change or
  return to primary screen retires the popup. Android uses Material's anchored
  popup for platform focus, back handling and constrained scrolling.
- **Use Full Terminal Height** defaults off. When on, alternate-screen capacity
  follows the keyboard-independent path already used by primary/shared grids.
  When off, an unshared alternate-screen terminal still reports the settled
  visible viewport. As in iOS, shared sizing keeps its keyboard-independent
  report regardless of this preference, so one phone's keyboard does not resize
  the shared grid. Both controls use observed `cmux-display` preferences;
  wrong-typed values fall back to the iOS defaults.

The initial debug/test build passed in 1m36s. Nine existing JVM cases passed:
keyboard layout (five) and viewport geometry fence (four). The three selected
Android checks passed together in **75.239s**: the real Activity/IME/RPC flow,
notice ownership/suppression/restoration at double font scale, and the existing
terminal-picker regression. The Activity check verifies smaller viewport rows
when the keyboard opens in default mode, restoration after hide, and unchanged
48-row capacity in full-height mode; it also verifies suppression across
recreation, re-enabling through Settings, and hiding on return to primary mode.
The popup and settings screenshots were inspected. The original legacy screenshot
had only blank lower fixture rows because the four lines of text at its top had
shifted out of view. A footer fixture and pixel assertion were then added to
verify actual bottom-row rendering above the keyboard; results follow below.

These checks use the production screen and RPC with an emulator-local connector.
They do not establish physical Pixel/Mac, real account/Iroh, or signed-release
acceptance. Haptics, legal/support, broader upstream/source/UI parity and the
production connection graph still need work. Push/feed configuration remains
open. No additional AVD or signed milestone was created; signed build 517 is
unchanged. Runtime evidence is under ignored
`captures/runtime/terminal-sizing-preferences/`.


The strengthened runtime case passed in **56.646s** after a 26s test-only build.
Pixel assertions find bright footer glyphs in the terminal's bottom band in both
modes, and the final screenshots were inspected: default mode resizes the grid;
full-height mode keeps 48 rows and slides the footer above the dock/IME. The
fixture footer is opt-in, so existing tests retain their original replay data.
Both crash-buffer captures are empty. The sole AVD was stopped and reaped.

Debug APK SHA-256: `fdc3d930bbc17679138cda25c248c9a58f447a7dbae9354b06322824099bc899`.
Test APK SHA-256: `79d8644eeb6a3e6838554dd6f6742c7f69894cf3426db0fa3fe80f784033b01a`.


## Haptic Feedback preference — 2026-10-04

Scoped reference: `0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`, especially
`MobileHapticFeedback.swift`, `MobileDisplaySettings.swift`, `MobileSettingsView.swift`,
`WorkspaceDetailView.swift`, the checklist/fallback/text-sheet views, terminal
arrow-nub and bell handlers, and toast presentation. The cache-only audit inspected
806 of 1,433 iOS Swift source blobs; 627 were missing locally. Relevant toast files
were subsequently read explicitly. This is a bounded audit, not an exhaustive
upstream review, and does not advance the global parity pin.

Android now has the iOS **Haptic Feedback** setting, default on, persisted in
`cmux-display`. Reading its default does not write a preference; wrong-typed
values fall back to on. The policy reads the current preference at emission time,
so switching off also suppresses an action already in flight. All explicit app
haptic calls route through this policy, as does Compose's `LocalHapticFeedback`.
Android's system/device haptic policy remains in force; no vibration permission
or ignore-system-setting flags were added. Keyboard and notification vibration
are separate system settings.

Covered event wiring: checklist add/state change (light), checklist failure
(error), terminal Copy All and debug-log copy (success), fallback Open on Mac
failure (error), feedback submission success/error. Feedback completions are
owner-scoped and consumed once by the retained controller; saved receipts and
errors do not replay a haptic after process restoration. No real feedback message
is sent by the tests. The pinned iOS checklist and fallback sources still contain
direct UIKit generator calls; Android applies the intended shared off switch to
those events as well.

Platform mappings: light → `CLOCK_TICK`; success → `CONFIRM` on API 30+, otherwise
`VIRTUAL_KEY`; warning → `LONG_PRESS`; error → `REJECT` on API 30+, otherwise
`LONG_PRESS`. Hardware determines the tactile result. Terminal-bell callbacks,
arrow-nub tick parity, generic toast parity, native Android selection gestures,
and physical Pixel tactile acceptance remain follow-ups; the new setting alone
does not establish full haptic or app parity.


Verification: debug and test APKs build successfully. **10 JVM cases and 10 Android
cases pass** on the final source (Android runtime **126.526s**). The earlier
Android run also passed all ten in 114.41s. Review then added a distinct request
ID for identical immediate failures, so a conflated UI frame cannot lose the next
completion; a focused regression covers that identity. The final build took 30s.
The test harness was also corrected to respect status-bar insets in its screenshot.

Runtime checks cover the persisted/default/corrupt-value preference, off/on/remount,
actual terminal Copy All with the setting off, Compose haptic delegation,
checklist action/failure routing, feedback success/failure/retry/cancellation,
account switching, disabling during a pending request, and Activity recreation
before and after completion. The inspected screenshot is a component harness,
not whole-Settings or iOS visual acceptance. Both crash buffers are empty. The
single existing AVD was stopped and reaped; no physical Pixel was visible in ADB.
No new AVD or signed milestone was created. Signed build 517 is unchanged.

Evidence: ignored `captures/runtime/haptics/verification.json`, build/runtime
logs, source audit, JVM XML and screenshots. Debug APK SHA-256:
`5d7842f3794e7f936575daf85e11d654d1eadc499159ed3f57d35adb6169a8f3`.
Test APK SHA-256:
`4809e6219d36b8e6d868cd66667f4d4486cf5475e253caac6ef793a5b37c17b9`.
