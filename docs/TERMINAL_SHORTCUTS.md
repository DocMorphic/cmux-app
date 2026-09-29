# Terminal shortcuts and input source audit

Reference: cmux `4c5272e9153eca2033c9f40ac749f0c3a5bcb291`.

## Keyboard encoding audit

The pinned iOS `TerminalHardwareKeyResolver` registers UIKit key commands for
navigation, Alt+Left/Right/Delete, Tab/Shift+Tab and Control characters. It calls
the stateless `TerminalKeyEncoder`; `TerminalInputTextView` sends the resulting
bytes through `onEscapeSequence`. This path does not forward hardware key-release
events or call Ghostty's key-event encoder. Android's analogous layer is
`TerminalHardwareInput` / `TerminalKeyEncoding`, with Android key-layout lookup,
dead-key composition, additional function keys and application-cursor support.

Consequently, adding a Ghostty key-release/Kitty encoder only to Android's raw
transport would not by itself prove iOS input parity. The current iOS source does
not justify making that a prerequisite for its standard keyboard workflow.
Physical keyboard layouts and terminal-mode behavior still need runtime checks;
this source comparison is not evidence that every Android combination works.
Input-latency markers are a separate capability-gated measurement protocol, not
keypress acknowledgements or a reason to retry ambiguous writes.

## Configurable toolbar

Sources audited:

- `Packages/iOS/CmuxMobileTerminal/Sources/CmuxMobileTerminal/TerminalAccessoryConfiguration.swift`
- `Packages/iOS/CmuxMobileTerminal/Sources/CmuxMobileTerminal/TerminalInputAccessoryAction.swift`
- `Packages/iOS/CmuxMobileTerminalKit/Sources/CmuxMobileTerminalKit/TerminalAccessoryLayoutReducer.swift`
- `Packages/iOS/CmuxMobileTerminalKit/Sources/CmuxMobileTerminalKit/CustomToolbarAction.swift`
- `Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/TerminalShortcutsSettingsView.swift`
- `Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/CustomToolbarActionEditorView.swift`

The Android toolbar now has a fixed customize affordance and a scrollable,
configurable region. Settings also opens the editor. All four modifiers, paste,
navigation, control keys, punctuation, launchers, Files and font-size buttons can
be hidden and reordered. The initial arrangement follows iOS; Android's existing
explicit Backspace/Delete buttons remain available at the end. Files is hidden
by default because the terminal already offers its Files chip/menu.

The editor supports held-row dragging with edge scrolling and accessibility
Move up/down actions. Custom actions have stable UUIDs, a label, literal text,
and a Run after typing toggle. Saving or editing does not send anything. Tapping
the toolbar button normalizes LF to CR, clears armed modifiers, and uses the
existing ordered input queue. That queue retains its failure/uncertain-delivery
pause and does not retry a macro automatically. The shipped Claude/Codex/Ollama
payloads match the pinned iOS source, including their arguments and Return
behavior; they are sent only on an explicit tap.

Edits retain an action's position and visibility. Deletion removes its layout
references. Reset restores the built-in arrangement and visibility (except
Files), keeps custom actions, and shows them after the built-ins. An explicitly
empty enabled set stays empty after reload. Unknown/duplicate stored IDs are
discarded, missing built-ins append, and malformed action storage is reported
instead of silently overwritten by normal edits.

Storage is one versioned JSON preference in the existing `native_display`
preferences. It is local to this Android installation, as the iOS configuration
is local UserDefaults. This is not an iOS settings importer or an account sync
protocol. Custom text is bounded to the native input frame's 16 KiB budget and
labels to 128 characters; there are at most 128 custom actions. The editor
creates text actions, matching the official editor. It does not expose a
key-combo editor that the official UI also does not expose.

## Verification

The seven layout/codec/macro JVM tests and seven existing modifier/encoding tests
passed (14 total), and the main/test debug APKs built. Native ELF LOAD/RELRO and
ZIP 16 KB alignment checks pass.

- The existing direct-keyboard and sticky-modifier app cases passed on the Android
  17 4 KiB emulator with the new configurable bar.
- The new complete toolbar workflow passed on Android 17's 16 KiB kernel in
  **17.484 seconds**. It creates a command without sending it, drags the modifier
  rows, hides Command, sends exactly `pwd` + CR with Ctrl armed, verifies Ctrl is
  cleared, edits the command to Unicode text without Return, reloads persisted
  IDs/text, resets while retaining the custom action, and deletes it in the UI.
- Four existing app-to-Ghostty adapter cases also passed on the 16 KiB kernel,
  including captured Vim, replay/gap recovery and styled underline pixels.
- The app reports `pageSizeCompat=0`; `getconf PAGE_SIZE` returns 16384.
- The initial custom-action attempts did not pass: programmatic scroll helpers
  stalled or resolved a new dialog before its first composition. Original logs,
  thread dumps and failure screenshots remain in `captures/runtime/toolbar/`.
  The final fixture uses bounded real swipe gestures, explicit test-clock
  advancement and a wait for the editor window; it preserves all assertions.
- Final evidence: `runtime-16k-final.log`, prior four adapter outcomes in
  `runtime-16k.log`, earlier keyboard/modifier outcomes in `runtime-initial.log`,
  and inspected `terminal-custom-shortcut.png` / `terminal-shortcut-settings.png`
  under `captures/runtime/toolbar/` (ignored). The emulator was stopped afterward.

APK SHA-256 values:

- Main: `f9c5a39c3ac1ea22d75d4f9167c91ef61869f7636bc673aaf75fc0ed85ca3131`
- App test: `2e0979e48bd104ba7897e52fac8764facb34ad750c1ac9685e8220bfa0f6856f`

Physical Pixel/Mac acceptance, exact iOS visual comparison, per-terminal zoom
policy and broader keyboard-layout/terminal-mode checks remain open. The Pixel
was not visible to adb during this work and was not changed. Published signed
build 157 remains the last verified published main app at this checkpoint.
