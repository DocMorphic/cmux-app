# Composer photo and video selection

Implemented 2026-10-06. Native terminal and New Task Photos actions now use
Android's photo/video picker. Emulator integration is recorded below; physical
Pixel acceptance remains pending.

## Upstream behavior

Scoped comparison at `186cec79781256867ad4516f0802118738bd2393`:

- `TerminalComposerView.swift`: the Photos filter is unset; images use the bounded
  image preparer, other returned media use the file path, and unreadable selections
  do not stop later items. The attachment menu also offers Paste.
- `TaskComposer/TaskComposerAttachmentPickerModifier.swift`: Photos allows all
  supported library media; Files imports arbitrary items.
- `TaskComposer/TaskComposerAttachmentStager.swift`: image preparation is separate
  from file staging. Imported videos/movies/general items retain their file bytes;
  Live Photos have an explicit iOS file representation.

All paths are in `Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/`.
These scoped reads do not advance the global parity pins.

## Android changes

- Both Photos actions use `PickMultipleVisualMedia` with `ImageAndVideo`.
  Files continues to use `OpenMultipleDocuments`. This grants access to selected
  content without asking for broad photo-library permission. AndroidX supplies a
  document-picker fallback where the photo picker is unavailable.
  [Android photo picker documentation](https://developer.android.com/training/data-storage/shared/photo-picker).
- Provider MIME metadata is read on the IO dispatcher. Only declared image media
  are decoded/downsampled. Videos and unknown media take the existing bounded file
  path, retain their bytes/name, and use existing encrypted draft storage. A task's
  failed image conversion can still fall back to its general file path as before.
- Native terminal non-image staging checks the Mac's attachment capability before
  opening/reading payload bytes. Existing count, file-size and aggregate budgets
  remain authoritative; the app checks the count even if a fallback picker ignores
  its selection limit.
- Native picker preparation now shares the recoverable provider-read helper used
  by composer paste. An unreadable selected item does not discard subsequent
  readable items. Cancellation, account/terminal change and storage/budget errors
  still stop the batch. Launch captures account session, terminal and draft
  generation; guards recheck the selected terminal before/after provider work.
- Native Add Attachment now includes **Paste attachment**. It consumes attachment
  clipboard items through the composer staging path; plain clipboard text receives
  a message to paste into the editor and is never sent as terminal input by this
  menu action. The existing toolbar paste action retains its separate behavior.
- Opening task Photos/Files clears prompt focus, matching the native picker change
  from the dictation batch.

## Feature-batch evidence (before integration)

Main Kotlin compilation passed initially. The first instrumentation compilation
caught a fixture mistake: `Intent.setClipData` returns Unit, so it cannot chain
`addFlags`. The fixture now assigns `clipData` inside `apply`; **main and instrumentation
compilation passed** on the follow-up. The result is recorded in `captures/runtime/composer-media-picker/`.

**22 focused JVM cases passed**: 5 recoverable provider/ownership cases,
9 task attachment cases, and 8 terminal draft cases. These support error and
storage-ownership behavior; they do not prove picker UI/media decoding.

Android cases originally queued for the combined milestone:

- `NativeFlowTest#photoLibraryStagesVideoAndImageAfterUnreadableSelectionWithoutSending`
- `NativeTaskAttachmentsTest#photoLibraryKeepsVideoBytesAndLaterImageWhenOneSelectionIsUnreadable`

Both use content-provider URIs and the platform-selected picker contract action,
including its fallback. They assert exact video bytes, encrypted payloads, image
classification, preserved prompt, unfocused editor and absence of remote upload
before Send/Create. The native case also checks that attachment-menu Paste cannot
execute plain clipboard text. The video payload is deliberately opaque fixture
bytes: this is a routing/preservation check, **not playback evidence**.

The existing native/task photo-and-file picker retry cases were updated to the
new contract. The older task fixture used `file://`, which lacked provider MIME
metadata; it now uses the app's FileProvider like a real picker result.

No APK build or emulator/Pixel run in this feature batch. The next combined run
should include these two cases, the two adjusted picker/retry cases, three queued
preview-lifetime cases, and three queued dictation UI cases. Also recheck debug
DEX method size after the NativeScreen additions.

Remaining: real Pixel photo/cloud-provider selection, selection-limit UI, denied
or revoked grants, rotation/process death while the picker is open, actual video
upload/playback and provider-format coverage. Android Live/Motion Photo behavior
has not been established. SSH's photo picker still supports images only; its
non-image transport capability needs a separate upstream comparison/implementation.
These gaps are not declared unavoidable platform differences.

## Combined integration — 2026-10-06

Both media-selection cases and both native/task picker retry cases now have
passing evidence on the existing Android 17 / 16 KiB AVD. Native image/video chips
and live terminal output were inspected together. Picker and menu-paste failures
now appear beside the composer instead of replacing terminal content with the
global reconnect state. The task retry preserves operation identity, attachment
IDs, repeated uploads and encrypted-file cleanup after success.

The older task fixture had omitted host capability discovery; production's
authenticated workspace-mutation guard correctly rejected its request. Calling
`hostStatus()` during fixture connection fixes the setup. Coordinate taps also
wait for button bounds to settle after IME movement; the final single case passed
in 13.009 s. No production authorization check was relaxed.

Evidence: `captures/runtime/composer-integration/verification.json` and companion
logs/screenshots. Video bytes are still synthetic: physical selection and actual
playback remain open. Signed release and global upstream pins are unchanged.
