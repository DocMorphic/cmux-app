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


## Terminal-bell feedback — 2026-10-04

At scoped iOS ref `0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`,
`GhosttySurfaceView+Artifacts.swift::handleBell` requests a warning haptic.
Android now gets the bell from the pinned Ghostty VT parser's
`GHOSTTY_TERMINAL_OPT_BELL` callback. It does not scan raw bytes: an OSC-terminating
BEL byte must not ring. Native code only records a bounded pending flag under
its registry lock; Kotlin drains it afterward. A byte batch containing repeated
bells produces one tactile signal, avoiding an unbounded vibration queue.
Clipboard, title and filesystem effects stay disabled, and Mac mirrors still
never send protocol replies back to the host.

The Mac byte-stream mirror drains and discards replay effects, then delivers
bells only from new accepted output. Existing surface, sequence-overlap and
output-lane checks govern delivery. Plain SSH, tmux and cmux-tui output drain the
same parser effect; tmux/cmux-tui snapshots discard it. The UI observes transient
signals only while showing that terminal, checks the RESUMED lifecycle and live
Haptic Feedback preference, and queues nothing for navigation/resume. The legacy
Termux parser adapter also exposes the same drain contract for mirror tests.

The reviewed `MobileTerminalRenderGridFrame` model, coding implementation and
`MobileTerminalRenderGridEvent` wrapper carry no bell field. The scoped iOS
`TerminalOutputTransportSelection.swift` still selects screen-anchored grids
when available. Android retains that selection: grid-only and hybrid alternate
grid output cannot infer a bell from text cells. This checkpoint does not invent
a bell event or claim a host protocol capability that was not observed.

Native source commit `cf2b5e9d9c0496258aae43d98708264f5fc5ebea` was rebuilt by
native-only Actions run **37186428500**, which passed module packaging and 16 KB
alignment checks. Artifact **11297610335** contains the core/JNI checkpoint;
**11296884618** contains its runtime test APK. The library hash is
`05d68220bda298a6ba83c3edd62555aced61af462dae6d6f510002d689ec3c22`.
The original core source remains `edefce7785c9f439966c68588db1edbd6b435203`.
No signed app milestone was dispatched by this native-only run.


Verification: C syntax checks passed with NDK r28c and `-Wall -Wextra -Werror`.
Debug/test APKs built in 1m58s. **20 JVM cases passed** (mirror 11, ownership 3,
output-lane 6). On the existing Android 17 / 16 KB AVD, **11 native-engine cases
passed in 0.675s**, covering bell parsing/draining plus existing query replies,
close/concurrency, bounds and rendering. **Four app cases passed in 30.859s**:
real Ghostty mirror replay/OSC/overlap; lifecycle/owner suppression; cmux-tui wire
output through the shared SSH UI; and the production NativeScreen with a real
Activity/main dispatcher and emulator-local Mac RPC endpoint. The last verifies
live output, duplicate suppression, OSC terminators, preference changes,
background/resume and recreation with replay. Signals were recorded through an
injected actuator; this does not prove tactile hardware behavior or real Mac/Iroh.

An initial installation was rejected because an older emulator native-test
package used a different CI debug signature. The shell continued to stale APKs:
the old native ten-test pass and app class-not-found errors are retained as
`stale-*-runtime.txt` and **excluded** from final evidence. Only the disposable
emulator's `io.github.docmorphic.cmuxapp.ghostty.test` package was replaced; app
installs preserved data. The rerun required successful installs before testing.

All 19 APK ELF libraries pass alignment verification; 16 KB zip alignment passes.
The crash buffer is empty. The single AVD was stopped/reaped, no physical Pixel
was connected, and signed build 517 is unchanged. Live plain-SSH/tmux bell and
Pixel/Mac/tactile acceptance remain open. A cache-only audit found the iOS bell
notification declaration and emitter in 810 cached iOS sources; 623 sources were
missing, so broader bell/notification UI semantics are not declared complete.
Evidence is under ignored `captures/runtime/terminal-bell/`.

Debug APK SHA-256:
`6b55cd81e43fb1fc679f4a5c5ea4dc6e8b8ecdae9c65b29fed30a6ebf1f20395`.
Test APK SHA-256:
`ece4a8558c3b2f97df0956ccd15c4c6d91b7a90ca89df9bd2a6fdc7935dfaf08`.


## Legal, support and About — 2026-10-04

Scoped reference: `0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`,
`MobileSettingsLegalSupportSection.swift`, the About/copy sections of
`MobileSettingsView.swift`, `MobileDebugInformation.swift`, and `AppVersionInfo.swift`.
The broader upstream parity pin remains unchanged.

Settings now opens [Privacy Policy](https://cmux.com/privacy-policy) and
[Terms of Service](https://cmux.com/terms-of-service) through Android's browser
handler. Both destinations resolved during this audit. Support opens an email
composer addressed to `feedback@manaflow.com` with subject `cmux Android support`,
following the scoped iOS implementation; it neither sends mail nor attaches a
report. Missing handlers show a selectable destination. Decorative arrows are
excluded from accessibility semantics. The UI identifies these as cmux service
links and identifies the Android app as an unofficial companion.

About displays the installed package version and build number. Debug builds also
show a short source revision, with `+` for tracked or untracked local changes;
source archives without Git omit it. CI uses its validated `GITHUB_SHA`. Copy
Support Information reads the current account/team/connection at click time,
then copies a typed report with build, Android version/model, coarse transport
and UTC time. No email, credentials, terminal content, host addresses or logs
enter the report. There is no equivalent installation analytics ID or iOS vendor
ID in this implementation, so those fields remain `<unavailable>`; this does not
create identifiers. Values are bounded and stripped of control characters.
The clip is marked sensitive to suppress Android's content preview. The copied
label lasts two seconds, resets on repeated copy, and reports clipboard failure
with a retry. This action does not add a haptic absent from the scoped iOS action.

Debug/test APKs build successfully (initial 1m25s, final 28s). **Two JVM cases
and two Android cases pass**; the final Android run took **17.223s** after
accessibility and source-marker refinements (initial runtime 17.287s). The
inspected screenshot at double font scale is readable without clipped labels.
Tests cover intent destinations, absent handlers, current-session copying,
sensitive clipboard metadata, disconnected transport, retry and copied-label
expiry. Both crash buffers are empty. The sole existing AVD was stopped/reaped;
ADB showed no physical Pixel. Tests use injected intent and clipboard sinks:
no support email is sent, and email deliverability is not claimed. Component checks do not establish
whole-Settings/iOS visual parity or physical Pixel/Mac acceptance. Signed build
517 is unchanged; current-source signed release verification remains pending.

Evidence: ignored `captures/runtime/support-settings/verification.json`, build
and runtime logs, JVM XML and component screenshots. Debug APK SHA-256:
`9920ee0cd8cefd61a089c584c8d84808ac9980de679ca452ba54edc3f9a6f8ac`.
Test APK SHA-256:
`965eea5387644fe1f0e6d6ab6a583582f981ba6c1073e2bf56e3d3f399da3376`.


## Terminal arrow pad — 2026-10-04

Scoped source reference `0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`:
`TerminalArrowNubView.swift`, `TerminalArrowNubDirection.swift`,
`TerminalArrowRepeatService.swift`, and the docking/`handleNubArrow` portions of
`TerminalInputTextView.swift`. The iOS control was missing from Android, rather
than merely missing haptics.

Both native and shared SSH terminal toolbars now pin a draggable arrow pad beside
the scrolling shortcuts. It matches the 8-point radial dead zone, dominant-axis
direction (vertical for ties), immediate emission followed by 80ms repeats, and
light feedback per arrow through the global live haptic policy. The visible
circle is 28dp, the dot is 12dp, displacement clamps to 6dp on each axis, and
recentering animates over 150ms. Android keeps a 48dp touch area around the
circle. The pad uses the existing toolbar action handler, preserving one-shot
and sticky modifiers, application-cursor encoding, composition finalization and
ordered input admission. It does not bypass the terminal's input queue.

Release, cancellation, an extra pointer, entering the dead zone or changing
direction cancels the previous repeat. Input disable, terminal/connection
replacement, disposal and losing RESUMED also retire the gesture; returning to
the foreground does not resume held input. The native owner includes connection
and workspace/surface identity; SSH uses its terminal instance. Four accessible
actions provide one arrow at a time without starting repeat. These are component
semantics checks until physical TalkBack acceptance is performed.

The first real-dispatch run exposed Android Back intercepting the pad's
left-edge drag. System UI recorded the test's `(63, 2085)` touch inside its
78px Back region, with no exclusion, and displayed the Back overlay while both
haptics and input counts stayed zero. The fix uses Android's
[gesture exclusion API](https://developer.android.com/develop/ui/views/touch-and-input/gestures/gesturenav)
for only the enabled pad's 48dp rectangle. The runtime check also exercises Back
elsewhere on the same screen edge.

A diagnostic rerun was killed before any test started by an emulator startup
ANR. The retained stack shows ART inflating/loading APK dex on the main thread,
with about 27 seconds waiting to be scheduled and 1.27 seconds of CPU time;
System UI and Google services were also busy. A subsequent run on the same
booted emulator reached the test and reproduced the Back-interception failure
with zero feedback and zero inputs. The startup ANR is retained separately and
is not a passing result or evidence about the gesture handler. Physical and
current-source signed cold-start acceptance remain required.

Verification: **three JVM cases and four Android cases pass**, with the final
Android run taking **38.058s** after the 24s debug/test build. Cases cover
dead-zone/direction/repeat timing, preference changes during a drag, owner
replacement, disable/background/resume, accessible single-step actions, SSH
one-shot Alt then ordinary arrow encoding, and production NativeScreen/main
dispatch/RPC input. The native fixture received three right arrows with three
haptic requests, then five up arrows with haptics disabled. No new arrows were
sent after release or background/resume. Back outside the pad returned to the
workspace list. The endpoint and tactile actuator are fixtures.

The pre-drag screenshot from the passing run was inspected and shows the pad
inside the existing toolbar without clipped controls. The post-resume screenshot
is dimmed during the Activity transition and is retained, not used as visual
acceptance. Final crash buffer is empty; the earlier startup ANR remains recorded
above. The only AVD was stopped/reaped, no Pixel was visible, and signed 517 is
unchanged. Full physical tactile/TalkBack/Mac acceptance, complete toolbar/iOS
visual parity and current-source signed-release acceptance remain pending.

Evidence: ignored `captures/runtime/arrow-nub/verification.json`, scoped iOS
sources, build/test logs, JVM XML, System UI diagnostics and screenshots.
Debug APK SHA-256:
`bd4796e5553a12d38c5868765e609f6fc3642ae03edd027e75fa28d4b10d3b7b`.
Test APK SHA-256:
`0b01a48bcdf27e58fd89c6927fabda88642ebff7833ce1e69352beb1d5a8f360`.


## Native selection haptics and toast policy audit — 2026-10-04

The scoped `ToastCenter.swift` at
`0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc` explicitly sets
`shippedEnabled = false`. Presentation returns before queueing or showing a
notice when disabled; only a DEBUG gallery environment opt-in changes that
default. The generic toast overlay and its presentation haptics therefore are
not a missing shipped feature to enable on Android. The separate
`WorkspaceActionToast.swift` defines bottom placement, a dismiss button and a
six-second timer. A subsequent call-site audit below found that the current
workspace-action handler does not populate it. Existing Android feedback
receipts and debug-only copy messages are not declared visually equivalent by
this limited audit.

The Android haptic switch previously gated explicit events and Compose, but
not framework TextView/WebView selection feedback. `NativeViewHaptics` now binds
the native view's haptic-enabled flag to the same `cmux-display` preference.
Android documents this flag as controlling standard feedback, including default
long-press feedback; see [haptics APIs](https://developer.android.com/develop/ui/views/haptics/haptics-apis).
It does not change the system setting, ask for vibration permission, or force
feedback through ignore-setting flags. A view that was intrinsically silent
remains silent. Missing or malformed preference values use the same default-on
policy as the existing Compose gate.

The binding reads on creation and attachment, observes only while attached, and
unregisters on detach. Reusing a detached view reads the current setting before
it is interacted with again. It is installed in the Terminal Text sheet, native
file text view, direct terminal input endpoint, local browser WebView, and
Markdown WebView. The invisible syntax-highlighter WebView has no interactive
selection surface. Keyboard-app and notification haptics remain system-owned.

Verification: **two Android cases pass in 28.079s**. Tests instantiate all five
production view types, verify live off/on/off flags and disabled framework
feedback results, and exercise real long-press selection, native Copy, clipboard
contents, Copy All, and preference changes while the text sheet remains open.
The lifecycle case verifies detached views stop receiving updates, reattachment
refreshes state, corrupt/missing values default on, and an intrinsically silent
view remains silent. The inspected screenshot confirms the text/copy UI remains
readable. These checks establish policy and native selection behavior, not
physical vibration or Chromium's end-to-end gesture behavior.

The initial 44s build produced the debug APK but test compilation rejected a
local lateinit-variable reference in cleanup. Correcting test cleanup produced
the test APK in 24s; production source was unchanged between those builds.
Final crash buffer is empty. The sole existing AVD is stopped/reaped and no Pixel
was visible. No extra AVD or signed milestone was created; signed 517 remains
the last published build.

Evidence: ignored `captures/runtime/native-view-haptics/verification.json`,
source excerpts, build/runtime logs, crash buffer and screenshot.
Debug APK SHA-256:
`f882d4e80d6871e67c580e6441f67c07b55cf1cefaf8c0b8e36a760706e20b74`.
Test APK SHA-256:
`6e4c06b0334941752ca280a954a264c4d27da515adfddbe427ad048fe6cfbe01`.

## Workspace-action failure policy — 2026-10-04

At scoped iOS revision `0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`,
`WorkspaceShellView+WorkspaceActionFailure.swift` handles failed ordinary
workspace/group mutations by writing a private diagnostic and returning. The
shell's `WorkspaceActionToast` overlay exists, but the only assignment to its
content in the `WorkspaceShellView*.swift` files clears it. The component's
six-second timer is therefore insufficient evidence of a visible shipped toast.
`WorkspaceShellView+WorkspaceActions.swift` routes rename, pin/read state,
close, move, group actions and simple creation through this diagnostic-only
handler. Customization sheets instead return typed errors to their own save UI;
task-composer creation also keeps a separate result path.

Android now follows that ordinary-action policy: failed workspace/group menu
operations and simple creation record a fixed diagnostic classification without
replacing the shell's global error. Rejected moves still roll back their
optimistic ordering and reconcile with the owner. Cancelling an owner still
propagates cancellation. Successful menu actions also leave unrelated errors
intact. Connection, task-composer, terminal-creation, browser-creation and terminal
input recovery paths retain their existing presentation. No general toast layer
is enabled. Diagnostic records exclude peer error text, workspace names and
request parameters.

### Verification

Eight focused JVM cases pass: two diagnostic/cancellation cases and six
optimistic move/reconciliation cases. A real Activity/Android-main test using a
local framed RPC peer passes in **30.351s**: workspace pin, close, group pin and
move are rejected, the list stays usable, rejected close retains the workspace,
and rejected move restores the original placement. Opening its terminal still
works. A terminal-input rejection displays the existing **Typing paused…**
recovery message, and **Resume typing** re-enables input. Both screenshots were
inspected; the crash buffer is empty. The sole existing AVD was stopped/reaped.

The first runtime attempt passed the workspace checks but incorrectly expected
raw peer error text in the terminal. Its failed result is retained; only the
test assertion changed to the queue's existing generic recovery message. Main
and test builds succeeded (83s, final test-only rebuild 17s). Evidence, original
logs, XML and source hashes are in ignored
`captures/runtime/workspace-action-failures/verification.json`.

Main APK SHA-256:
`c900c3ec3fc362498d771076f26cc43a79765cda1db1e00b44e8d487b119f3e8`.
Final test APK SHA-256:
`1453b1fc69a8ed20e03a96d3526686445d5a658314e9ecbb4530c64ef65e0049`.

This verifies fixture-backed shell behavior, not live Mac mutations or the full
production account/network graph. ADB showed no Pixel. Signed build 517 is
unchanged. The audit also found upstream workspace customization controls that
need a separate Android/source comparison; it does not establish complete
workspace UI parity or complete the overall goal.
