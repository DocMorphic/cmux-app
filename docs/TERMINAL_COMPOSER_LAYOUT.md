# Terminal composer field layout

Implemented 2026-10-06. Emulator integration is recorded below; physical Pixel
and full visual/accessibility acceptance remain open.

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

## Feature-batch checks (before integration)

Main and Android-test Kotlin compilation succeeded in 36 s. No APK, emulator,
physical device run, signed release or new AVD in this batch.

Two component cases originally compiled and were queued (now passed below):

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

## Combined layout/menu integration — 2026-10-06

The initial combined run passed **all 12 cases in 113.809 s** on the sole existing
Android 17 / 16 KiB AVD. It covered the field's 14-line height cap, contained
bottom-aligned 48 dp send target, read-only dictation/send-status semantics,
clipboard staging and limits, real system photo selection/cancellation, native
and SSH dictation Send/IME visibility, rich image input, and native multiline
draft navigation plus rejected-send retry. No failures in this initial run.

Inspected captures show the new rounded field with inline arrow and failure
indicator, cyan/green attachment previews, preserved multiline text, and visible
terminal content beneath local attachment feedback. Native failure-state capture
also shows the visible keyboard. Initial SSH captures exposed missing navigation
padding in the standalone test host; the real native screen already supplies
that inset. The fixture now supplies it too. These captures are not physical
Pixel or complete iOS matched-state visual acceptance.

Source review also found that an open menu could survive replacement of an SSH
terminal in the same composition. The menu now remembers expansion by explicit
terminal/connection owner. Screenshot review found raw provider paths in errors;
I/O and access-denied messages now give a readable selection/copy retry action,
while explicit app validation messages such as size limits remain intact.
All **six focused provider/ownership JVM tests passed** after that correction.

Initial build: 53 s; correction build plus six JVM cases: 58 s. DEX measurement
remained 3,511 methods, largest 13,156 code units, below the exclusive 16,383
threshold. Corrected debug APK SHA-256:
`c524265a15e633c258773803110384609203879d02253a8eeb92a98a97ec9b1e`.
Logs, exact case lists, hashes and screenshots are under
`captures/runtime/composer-layout-menu-milestone/`. Global upstream pins and the
signed release remain unchanged.

The focused correction run passed **5/5 cases in 64.298 s**, including the new
menu-owner/unavailable transition and the existing sequential unreadable-image
case. Together the runs cover **14 distinct Android cases**, with no failures
in either run. Corrected native screenshot shows the readable provider error
and terminal grid without the transient Android clipboard preview covering them.
Corrected SSH screenshots reserve navigation space and retain visible IME after
Send; their standalone Activity system-bar colors are not MainActivity evidence.
No crash/ANR markers were found in collected logs. Gradle and the sole AVD are
stopped/reaped. No physical-device action or signed release occurred. Exact
source/APK hashes and case-to-run mapping are in the local `verification.json`.
