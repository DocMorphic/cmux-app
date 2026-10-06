# Terminal shortcuts and input source audit

Reference: cmux `4c5272e9153eca2033c9f40ac749f0c3a5bcb291`.

## Shortcut-row geometry follow-up — 2026-10-06

Scoped comparison of `TerminalInputTextView.swift`,
`AccessoryEdgeFadeScrollView.swift` and `AccessoryEdgeFadeTests.swift` at
`c2715faa02c260b07012bc0b386597cfb333021d`, against the previously audited
`204a11dfcc76280205e50406ab94270a1c152155`. The newer iOS input view defers layout
correction during tracking/drag/deceleration and preserves either resting edge
across size changes. Its leading fade ramps over the first 24 points, rather than
turning on abruptly. This scoped port does not advance the global parity pin or
close the remaining terminal replay, sizing and external-host delta audit.

Android now retains the leading/trailing edge or absolute middle offset across
shortcut content and viewport changes. `TerminalToolbarScrollAnchor` ignores
unmeasured bounds and leaves the initial saved offset to Compose. Touch contact
is observed before drag slop without consuming pointer events; active contact,
drag and fling defer correction. Once interaction ends, its latest position wins
and only an invalid offset is clamped. Coalesced focus/reader movement also wins
over a stale edge. Admission checks immediately before `scrollTo` avoid taking
over a newly started gesture or applying an obsolete geometry snapshot.

The row still uses Compose's ordinary horizontal scrolling and overscroll. A
24 dp leading fade ramps with the actual scroll offset and mirrors to the logical
leading side in RTL. It draws against the existing opaque toolbar background;
UIKit's glass-mask overscan is not transplanted into Material's button rendering.
No input bytes, shortcuts, modifier semantics or remote viewport requests change.

The newer iOS arrow-pad accessibility label/hint was also compared with Android's
existing label, directional custom actions and resumed-owner/input admission.
Those controls were already implemented; no duplicate pad behavior was added.

Verification: **17 focused JVM tests passed**, zero failures/errors/skips:
seven geometry cases, seven existing toolbar cases and three arrow-repeat cases.
The geometry checks include saved/unmeasured state, both resting edges, content
growth, shrinking bounds, middle offsets, interaction deferral, coalesced changes
and density tolerance. Main compilation passed in the same 38-second invocation;
the final main/instrumentation compilation passed in 5 seconds.

Two `TerminalToolbarGeometryTest` Android cases are **compiled but not run**:
resting-end retention through viewport shrink/custom-action insertion, and held
contact deferral/release. They belong in the next terminal integration batch with
the existing arrow-pad runtime cases. Actual gestures/fling, fade pixels, RTL,
enlarged text, iOS screenshots and physical Pixel/Mac acceptance remain open.
Evidence: ignored local `captures/runtime/toolbar-geometry-source/`. No APK or
emulator was created for this source checkpoint; Gradle was stopped afterward.

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

## Android hardware layout follow-up — 2026-10-02

Rechecked `TerminalKeyEncoder`, `TerminalHardwareKeyResolver` and
`TerminalInputTextView` at audited candidate
`204a11dfcc76280205e50406ab94270a1c152155`. iOS special/control mappings remain
consistent with the existing Android path. Ordinary Unicode composition is
provided through UIKit's text-input system; Android must respect its own active
key character map. This narrow audit does not advance the broad parity pin.

Android previously removed every Alt flag before `getUnicodeChar`, preventing
right-Alt layout characters and accents from being interpreted. The hardware
adapter now uses a distinct nonzero right-Alt mapping when present. Left Alt,
Ctrl+Alt, explicit toolbar Alt/Control/Command and unmapped right-Alt keys preserve
the existing terminal escape/control behavior. Navigation remains terminal input,
including application-cursor mode and readline Alt+Left/Right.

Android's [key character map format](https://source.android.com/docs/core/interaction/input/key-character-map-files)
supports separate right-Alt mappings. Its [generic map](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/data/keyboards/Generic.kcm)
also supplies Alt character levels and combining accents. Tests use the actual
Android virtual keyboard map rather than mocking Unicode lookup. Right-Alt
character selection is an Android policy; it is not an iOS hardware-key protocol.

Repeated dead accents emit the previous accent instead of silently losing it.
A different second accent emits the first and retains the new pending accent;
incompatible base characters retain both characters. Navigation and explicit
terminal modifier chords cancel the pending accent so Ctrl+C cannot accidentally
become accented text. Physical keyboard layouts, manufacturer-specific key maps
and the Pixel/Mac workflow still require acceptance.

### Verification

All **seven focused JVM checks passed**, zero failures/errors/skips. All **six
final Android tests passed in 40.53 seconds**, API 37 / 16 KB, zero skips:

- Three actual-key-map cases verify right-Alt characters, left/explicit Alt and
  Ctrl+Alt behavior, dead accents, repeated/different accents with real modifier
  presses, incompatible bases, key-up handling, navigation and cursor modes.
- The full NativeScreen flow sends exactly `ç`, `é`, Alt+Left, an application-mode
  Up and Ctrl+C through the real IME endpoint/input queue/framed RPC client, all
  to the original terminal. A live output event changes the Ghostty cursor mode;
  the next Up uses normal CSI encoding. Its final screenshot was inspected.
- Existing early hardware-focus and direct IME composition/pause/target-switch
  regressions pass on the same final build.

The initial run passed five cases; the new flow timed out after its fixture
returned an obsolete replay snapshot during keyboard resizing. The fixture now
advances its snapshot and sequence with the output event, matching a real host's
current state. That flow passed alone in 16.827 seconds. Final review added the
modifier-only guard so pressing Right Alt again does not erase a pending accent;
all six cases above then passed together. Original failure diagnostics remain
under `captures/runtime/hardware-layout/` beside `modifier-build.txt`,
`modifier-android.txt`, JVM XML and the final screenshot.

Both APKs build and pass 16 KB ZIP alignment; all five native libraries pass
LOAD/RELRO checks. SHA-256:

- Debug: `390efda4fffbae6244b9232bd3b997812e58b665d6fd738a2b931e6cf4eab7d2`
- Test: `1b143547596026f9242c3f7a1efa29e49c8c2c917ee0aefd40f61af8ab826fc4`

This verifies synthetic events using Android's real virtual key map and a
loopback Mac fixture. It does not claim physical keyboard layout, Pixel/Mac or
latency acceptance. No signed release changed; the existing emulator was stopped.
