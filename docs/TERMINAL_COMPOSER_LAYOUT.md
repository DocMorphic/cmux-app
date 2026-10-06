# Terminal composer field layout

Implemented 2026-10-06. Main and instrumentation compilation passed; visual and
runtime acceptance of this layout is queued for the next combined milestone.

## Source comparison

Scoped iOS revision: `186cec79781256867ad4516f0802118738bd2393`.

- `TerminalComposerView.swift`: bottom-aligned attachment and mic controls, one
  rounded field containing an inline send control, 1–14 text lines, arrow/spinner/
  failure states and corresponding accessibility labels.
- `MobileComposerFieldContainer.swift`: 20-point corner radius, leading text
  inset, bottom-aligned trailing control in the same field shell.
- `MobileComposerIconButton.swift`: shared circular leading controls.

Source receipts are in `captures/runtime/composer-field-layout/source-review.json`.
This scoped read does not advance the global implemented/reviewed upstream pins.

## Android implementation

`TerminalComposerField` now provides native and SSH terminals with the same rounded
field, bottom-aligned inline arrow, spinner while sending, and failure indicator.
The editor grows to 14 lines and then scrolls. The placeholder is Message. Existing
transport callbacks, draft ownership, keyboard shortcuts and rich-content input
wrappers still own behavior outside the shared layout. Native rich-content errors
now use the local composer message, consistent with picker/paste-menu failures.

The attachment control uses a project-authored paperclip vector. Attachment and
microphone controls have 40 dp circular visuals in 48 dp Android touch targets;
the inline send target is also 48 dp. This is an implementation choice for Android
accessibility, not a proven unavoidable platform difference. The field uses the
app's colors and translucent fill/border; no claim of exact Liquid Glass rendering
or full visual parity is made.

Send controls expose Send, Sending and Send failed accessibility descriptions.
Native UI checks now address `native.composer.send`; SSH retains `ssh.shell.send`.
Text editing tags and rich-content interception remain on the editor itself.

## Checks and next integration run

Main and Android-test Kotlin compilation succeeded in 36 s. No APK, emulator,
physical device run, signed release or new AVD in this batch.

Two new component cases compile and remain queued:

- `TerminalComposerFieldTest#multilineFieldGrowsToFourteenLinesAndSendStaysInsideAtBottom`
- `TerminalComposerFieldTest#dictationAndSendStatusKeepEditorAndButtonSemanticsIndependent`

At the next combined milestone, run these with the affected native/SSH dictation
Send checks and rich image-paste checks. Inspect empty, multiline, sending and
failed states; verify IME visibility, clipboard selection, Ctrl/Cmd+Enter, large
font scaling, narrow viewport and TalkBack on actual device. Recheck DEX method
size after packaging. A successful compilation does not establish these outcomes.

## SSH file-path finding

The scoped `MobileShellComposite+ComposerFileAttachments.swift` read gates general
file staging on the foreground Mac's `task.attachments.v1` capability. Uploads
require a foreground Mac ID and use the native task-attachment uploader with stable
attachment IDs. That source alone does not establish a standalone SSH file-send
contract. Android's SSH composer remains image-only pending the complete route
comparison; no speculative native RPC was added to its SSH input lane.
