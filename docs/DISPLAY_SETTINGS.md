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
or shell's history. Haptic, alternate-screen notice, legal/support and remaining
Settings/source/visual parity still require their own audit and implementation.

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
- The final production-screen test passes in 21.98s using normal Android dispatch:
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
The new integration test uses normal Android dispatch and completes Settings
navigation. Two later runs reached a blank terminal and timed out: the preserved
method list has event subscribe/unsubscribe traffic but no viewport/replay request.
Adding failure diagnostics alone preceded the final passing run; production code
was unchanged. **The cause of those intermittent cold-attach failures remains
unproven and is the next investigation.** Do not infer reliable startup from one
pass, or call this a physical-device acceptance result.

Evidence under ignored `captures/runtime/display-settings/`: original/final build
logs, retained JVM XML, `runtime.txt`, `runtime-final.txt` (aborted idling run),
`held-trace.txt`, `runtime-dispatcher.txt`, `runtime-render.txt`,
`terminal-failure.txt`, final `runtime-diagnostics.txt`, and inspected screenshots.
The final test writes fixed-label RPC diagnostics and peer failures if it fails
again, so the next run can distinguish transport, cancellation and presentation.

Physical Pixel/Mac acceptance remains open; this change is not in signed build
517 and does not complete the full app goal. No physical device was visible in ADB.
