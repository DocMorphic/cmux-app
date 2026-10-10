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

These Android cases **have not run**. Execute them at the next integration
milestone together with the existing mixed-provider/menu/system-paste cases.
This batch created no APK, emulator, Pixel/Mac run or signed release. Signed
milestone 641 is unchanged. Logs, source excerpts/hashes and final test XML are
retained under ignored `captures/runtime/task-attachment-limits/`.

The scoped prompt source also shows an initial presentation-owned focus transfer
that Android's task composer does not yet implement. Initial focus, prompt
selection/scroll retention and matched physical UI remain pending; this attachment
batch does not close task-composer parity.
