# Task prompt focus, selection and IME paste

## Scoped source comparison — 2026-10-10

Compared the iOS task composer at
`f4b1509054949eaad5d695569ad443c4c18ed68d`, specifically
`TaskComposerInitialFocusCoordinator.swift`, `TaskComposerPromptEditor.swift`,
`TaskComposerPromptEditorCoordinator.swift` and the sheet's focus integration.
Source hashes are retained in ignored
`captures/runtime/task-prompt/source-review.json`. This does not advance the
global parity pin or establish complete task-composer parity.

## Initial implementation — checkpoint e2e26809

- One initial keyboard-focus request belongs to each task presentation. It waits
  for resumed lifecycle, a placed editor and window focus, then rechecks current
  draft ownership after a frame. User focus, dismissal or hidden/disabled
  presentation consumes the request; returning from Options does not steal focus.
- The prompt now retains `TextFieldValue` selection and composition separately
  from durable draft text. Unchanged text keeps the same editor value; external
  replacement clamps both UTF-16 selection endpoints and retires composition.
  Selection-only changes do not mark the draft dirty.
- Opening sheets/pickers freezes selection before clearing focus, avoiding the
  legacy TextField's blur collapse. Saved editor state remains owned by the draft
  across Options and saved-state restoration. Manual viewport/scroll retention
  and actual Activity/process/physical-device restoration remain unverified.
- Explicit IME Paste first consumes eligible attachments. Plain text is read only
  on the paste action, without coercing content URIs, and committed through the
  input connection's ordered batch after finishing composition. Retired owners
  cannot read/stage new clipboard content. Hardware and system-menu handling
  retain their existing paths.

## Runtime failures corrected

The initial six-case run passed four: Options collapsed a selection and IME text
paste returned false. Freezing selection fixed the first failure. A synthetic
paste-key fallback and a subsequent delegate-first attempt still raced an
immediately preceding IME selection; each eight-case run passed seven.

Inspection of the exact installed Compose UI 1.9.1 AAR's
`RecordingInputConnection.performContextMenuAction` bytecode showed that Paste
dispatches a synthetic key while returning false. The accepted implementation
bypasses that delegate context action and batches ordered composition/commit
operations directly. Tests now cover immediate `setSelection` followed by Paste
and pending composing text followed by Paste as Plain Text.

The first repair also exposed a missing required callback in the debug attachment
preview fixture; its explicit no-editor callback is now supplied. Original build
failures and all runtime attempts remain in the ignored capture directory.

## Initial accepted integration evidence — checkpoint e2e26809

**12 JVM cases passed**, zero failures/errors/skips: six new
`TaskComposerPromptTest` cases and six `TaskAttachmentStagingTest` cases.

**Eight Android cases passed in 93.581 seconds** on the existing Android 17/API37
16 KB AVD:

- Initial focus waits for resume and does not return after Options or pause/resume.
- Selection survives agent changes, Options and `StateRestorationTester`, then
  Unicode input replaces the selected word without damaging the emoji suffix.
- Prompt canvas and dock resize with the actual emulator IME.
- Full attachments block both pickers; overflowing clipboard selection stages the
  remaining prefix while ordinary text paste remains available at capacity.
- Model/effort/quoted Unicode prompt reach the local fixture's task creation RPC.
- Hardware/IME attachment paste, immediate-selection text paste, composing-text
  completion and disabled/oversized/retired paste regression checks pass.

The final app build succeeded in 28 seconds. Exact APK SHA-256 values:

| APK | SHA-256 |
| --- | --- |
| Debug | `44434c4990ff74def07f9ce5d4ab82676f43c1fffd4b884b1560e42e5a8e94f6` |
| Instrumentation | `0fe8c4f986b833d9b5df4914f5ec2e30d8e1cca8a95b32d9ddfbe070e2ea174e` |

`instrumentation-acceptance.log`, `verification.json`, final JVM XML, dependency
bytecode and three fresh PNG captures are retained under ignored
`captures/runtime/task-prompt/`. The keyboard canvas, model selection and
model/effort captures were visually inspected. Incorrect internal screenshot
paths initially returned text errors; those files are retained as unavailable
receipts and are not treated as images.

The final run had **zero new crash/ANR records**, with an empty crash buffer.
Earlier emulator startup System UI ANRs are retained. Gradle was observed stopped
before the accepted boot; the sole AVD was stopped afterward, its previous sleep
timeout restored, and no Gradle/emulator process remained. An unused early boot
was aborted before testing after the stop check had yielded prematurely. No extra
AVD was created. Emulator memory was actually 4,062,416 KiB; the 1536 MiB request
is clamped by this emulator and must not be reported as actual memory use.

No physical Pixel, live Mac/account workflow, signed upgrade or new signed release
was exercised. Signed delivery 641 remains unchanged. Matched iOS screenshots,
manual prompt viewport retention, physical IME/provider behavior and broader
task/workspace acceptance remain open.

## Subsequent native editor and viewport port — 2026-10-10

The prompt now uses the installed Foundation 1.9.1 `BasicTextField` with saved
`TextFieldState` and an explicit saved `ScrollState`, retaining the same Material
decoration, typography, placeholder and rich-content interception. The obsolete
value-only selection helper has been removed. See the
[official input-state documentation](https://developer.android.com/develop/ui/compose/text/user-input).

The scoped iOS coordinator/TextView preserves a drag-owned offset after layout,
clamps it to the current content extent, and releases it when typing or selection
changes. `TaskComposerPromptViewport` now applies that policy on Android, tracking
drag and fling and preserving the transition frame between them. Unrelated
keyboard/window/model layout cannot replace the user's chosen viewport.

`TaskComposerPromptBinding` avoids assigning unchanged text, accepts external draft
changes with clamped UTF-16 selection, and publishes native edits before save,
submit, stop/disposal and attachment admission. A submit reads one current draft
snapshot for command/prompt/model/effort/group/attachments and recovery, instead
of a previously rendered payload. Ownership and live editability remain gates.
Same-turn IME text plus image admission flushes before staging disables input.

**37 focused JVM cases passed**: nine prompt/binding, seven viewport, nine
submission and 12 model cases. Android runtime evidence follows below;
the following failed runs must not be treated as passes:

- The original legacy-editor viewport case failed on Options return (14.862 s).
- Explicit scroll state alone passed 22/24 integration cases (244.836 s), but
  still followed the caret after the picker changed keyboard/window layout.
  Attachment feedback initially asserted before staging finished; its check now
  waits for the actual final feedback.
- The first policy integration passed 21/24 cases (216.978 s). Its new image
  assertion omitted the expected uploaded-file path suffix and was corrected.
  A response-feedback timeout remains recorded; the isolated response case passed
  on the diagnostic rerun without a production response change.
- The three-case diagnostic rerun passed the response and same-turn image-upload
  cases (32.130 s). Its trace showed the remaining viewport comparison captured
  a frame during a legitimate fling (offset 1928) versus its final offset 1711;
  inspected captures showed line 31 versus line 28, rather than a jump to the
  caret. The check now waits for actual scroll/tracking idle and three stable
  painted frames, retaining the same viewport pixel assertions.

An opt-in debug trace (`cmux.prompt.viewport.trace`) reports offsets and selection
indices without prompt contents. Runtime checks clear it after their fixtures.
The scroll-idle semantics property adds no spoken label. Sources, exact APKs,
attempt logs, XML and captures are retained under ignored
`captures/runtime/task-viewport/`; the global source pin and signed 641 are
unchanged. Physical/matched iOS, actual Activity/process restoration, font/width
reflow and broader task/workspace acceptance remain open.


### Scroll-idle integration and dock-action follow-up

The scroll-idle integration passed **22 of 24** cases in **246.883 seconds**,
including the real drag, picker/options return, saved-state viewport, typing,
last-edit submission and same-turn IME image-upload checks. Its two failures were
initial focus/Options (no Done node after a coordinate tap) and response feedback
(timeout after a coordinate submit). An isolated response rerun then reached the
feedback assertion but timed out after the second coordinate submit (21.731 s).
No production response handling was changed. These are recorded failures, not an
accepted 24-case suite. Its final last-ANR record matched the pre-run baseline;
the baseline retained the startup System UI ANR dismissed before instrumentation.

Prompt fixtures now use the existing `openTaskOptions` accessibility-action helper
and wait for the real sheet. The response/retry-ownership case invokes the enabled
create button's real semantic action, avoiding a stale dock coordinate during IME
animation. Its diagnostic catcher now includes assertion errors; the previous
`Exception` catcher did not record `ComposeTimeoutException`. Touch-based model,
attachment and keyboard-resize checks remain in the integration selection.
The unchanged debug APK SHA-256 is
`96397508ec9beee3ee4f5a7a00efd65d536b4ba43d744905bf3e9b73dade8a98`.
The follow-up test APK is
`bd7433a32b86b38bbffb6eda6314b6946bafdc76a60fb3c361d6735db9f4ebcc`;
its build succeeded in 35 seconds. The final follow-up **passed all 12 cases in
156.391 seconds**: all four prompt and seven model/response cases plus the real
keyboard/canvas/Options touch check. The app APK is byte-identical to the 22/24
run; only the instrumentation actions/diagnostics changed. This is a focused
follow-up, not a claimed single passing 24-case suite.

Inspected scroll-idle captures show the manual viewport beginning at line 28,
retained after agent and Options changes and saved state; typing shows the final
line 100 and appended marker at the caret. Captures compare the same width and
settled top content, allowing the IME's different editor heights. All original
logs and images remain under `captures/runtime/task-viewport/`.


The final follow-up's crash buffer was empty and its last-ANR record matched the
pre-run baseline. The fresh full task form was visually inspected for the actual
new editor decoration and model/effort dock. Verification metadata and exact
attempt logs are retained in `verification.json`. The original sleep timeout was
restored; Gradle and the sole emulator were stopped and observed reaped with zero
matching processes. No additional AVD was created. The requested 1536 MiB memory
is still clamped to 4096 MiB by this emulator. No physical Pixel/live Mac account,
actual Activity/process restoration, matched iOS or signed upgrade was exercised;
those gates and the remaining feature matrix stay open. Signed 641 is unchanged
at this local checkpoint.
