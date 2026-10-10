# Task prompt focus, selection and IME paste

## Scoped source comparison — 2026-10-10

Compared the iOS task composer at
`f4b1509054949eaad5d695569ad443c4c18ed68d`, specifically
`TaskComposerInitialFocusCoordinator.swift`, `TaskComposerPromptEditor.swift`,
`TaskComposerPromptEditorCoordinator.swift` and the sheet's focus integration.
Source hashes are retained in ignored
`captures/runtime/task-prompt/source-review.json`. This does not advance the
global parity pin or establish complete task-composer parity.

## Implementation

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

## Accepted integration evidence

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
