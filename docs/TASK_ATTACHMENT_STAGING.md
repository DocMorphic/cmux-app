# New Task attachment selection

Scoped source comparison at iOS commit
`f4b1509054949eaad5d695569ad443c4c18ed68d`:
`TaskComposerSheet+Attachments.swift` and `TaskComposerAttachmentStager.swift`.
The global parity pin is unchanged.

## Behavior ported

- Photos, Files and attachment paste stage the selected prefix that fits the
  remaining count, rather than rejecting the whole selection. An unreadable
  item still occupies its selection position, as in the iOS prefix loop.
- A full list reports the ten-item limit before launching either picker.
  Recognized attachment paste is consumed without inserting URI/caption text;
  ordinary text paste continues through the native editor.
- Only count/aggregate-size append rejections are recoverable within a batch.
  A later smaller item can fit after an aggregate rejection. Successful items
  retain order and persist before appearing; final feedback retains the limit
  and any unreadable-item report.
- Draft admission validates the candidate list before writing ciphertext, and
  validates again before publishing. Cancellation, changed ownership, invalid
  metadata and persistence errors stop staging rather than becoming a limit.
- Task attachment clipboard paste accepts a selection larger than ten and
  bounds it to the task prefix. Other composers keep their existing ten-item
  clipboard rejection. The task attachment popup closes when its owner or
  editing availability changes.

## Verification — 2026-10-10

Six new `TaskAttachmentStagingTest` JVM cases and 15 existing attachment/provider
checks passed: **21 cases**, zero failures/errors/skips. Coverage includes the
selected prefix, full list without opening providers, exact aggregate boundary
and subsequent smaller append, disk failure, cancellation/owner retirement and
invalid metadata. Main and Android test Kotlin compiled.

Two additional production-screen cases compiled in `NativeTaskAttachmentsTest`:

- `overflowingClipboardUsesRemainingSlotsAndFullDraftStillAllowsTextPaste`
- `fullDraftReportsLimitBeforeLaunchingEitherPicker`

At the original attachment-only checkpoint, these Android cases had not run.
That batch created no APK, emulator, Pixel/Mac run or signed release. Signed
milestone 641 is unchanged. Logs, source excerpts/hashes and final test XML are
retained under ignored `captures/runtime/task-attachment-limits/`.

## Subsequent task-prompt integration — 2026-10-10

Both new attachment cases now **passed** in the eight-case Android integration
recorded in [TASK_COMPOSER_PROMPT.md](TASK_COMPOSER_PROMPT.md), along with prompt
canvas/keyboard, model/effort and rich-editor paste regressions. Six staging JVM
cases also passed again alongside six new prompt cases. Initial task focus and
selection retention are now implemented and covered by those emulator checks.
Manual scroll/viewport retention, matched physical UI and full task-composer
acceptance remain open; no Pixel/Mac or signed-release acceptance is claimed.
