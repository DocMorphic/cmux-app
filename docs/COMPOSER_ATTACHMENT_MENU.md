# Terminal attachment-source menu

Implemented 2026-10-06; emulator integration is recorded in
[the layout/menu milestone](TERMINAL_COMPOSER_LAYOUT.md#combined-layoutmenu-integration--2026-10-06).

## Scoped comparison

Read at iOS revision `186cec79781256867ad4516f0802118738bd2393`:

- `TerminalComposerView.swift`: the attachment menu offers Photos, conditional
  Files and Paste. Staging loops handle images/files separately, continue past
  unreadable items, and show local attachment feedback. Clipboard attachment
  import consumes the attachment content without also inserting captions.
- `MobileShellComposite+SSHPaste.swift`: SSH image paste uploads via SFTP to
  `~/.cmux/uploads/` and inserts a quoted remote image path.
- `MobileShellComposite+ComposerFileAttachments.swift`: general file attachment
  staging requires the foreground Mac's capability; upload uses that Mac's native
  task uploader. These reads do not prove a general standalone SSH file contract.

Receipts: `captures/runtime/composer-attachment-menu/source-review.json`.
Global upstream pins are unchanged.

## Changes

`ComposerAttachmentMenu` is shared by native and SSH terminals. Native offers
Photos, Files when the host advertises support, and Paste attachment. SSH offers
Photos and Paste attachment when image upload is available. Merely opening the
menu does not read the clipboard. The popup closes when its owner leaves or
attachment actions become unavailable.

SSH attachment-menu Paste stages supported images using the existing ordered
input lane. Ordinary clipboard text prompts the user to paste into the editor;
it does not execute a terminal command or overwrite the draft. Attachment captions
and URI fallback text are consumed along with the attachment selection. Unsupported
files report a local message while later supported images can still stage. Raw
terminal paste retains its whole-batch unsupported-content rejection.

Before opening another SSH image provider, the staging lane checks the current
10-item draft limit, including earlier queued additions. Input admission also
checks that the account-owned draft binding is still active. Retired accounts
cannot open new providers or send more terminal input through the old lane.

Native clipboard provider and capability failures now use composer-local feedback,
matching Photos/File preparation. A failed or unsupported item does not replace
terminal output with the global reconnect state or discard later readable items.
The send/upload paths and account/target admission checks remain authoritative.

## Cases prepared for the combined milestone

New cases:

- `SshImageInputScreenTest#attachmentMenuConsumesOnlyImagesAndNeverExecutesClipboardText`
- `SshTerminalInputTest#composerSkipsUnsupportedFilesWithoutOpeningThemButDirectPasteRejectsWholeBatch`
- `SshTerminalInputTest#fullDraftRejectsNextImageBeforeOpeningItsProvider`

Updated cases:

- `SshImageInputScreenTest#systemPhotoPickerCancelsThenStagesARealImageForAnImagesOnlySend`
  now selects Photos from the menu before using the real system picker.
- `NativeFlowTest#photoLibraryStagesVideoAndImageAfterUnreadableSelectionWithoutSending`
  now also pastes a missing provider followed by readable media and checks that
  the terminal remains displayed with the unchanged prompt and no upload.

Main and instrumentation compilation passed (35 s). Results are in `captures/runtime/composer-attachment-menu/compile.log`.
At the feature commit no APK or runtime check had run. The subsequent combined
milestone above now records passing Android evidence for these five cases.
Physical Pixel acceptance remains open. Standalone SSH general files, physical provider
and permission behavior, large-font/TalkBack/menu positioning, real media playback
and the broader parity gates remain open.

Integration corrections explicitly key menu expansion to terminal/connection
ownership, so replacement at the same composition location closes the old popup.
Provider I/O and access-denied errors now avoid displaying raw paths or URIs.
App validation messages remain actionable; cancellation and retired-owner failures
still propagate instead of becoming ordinary unreadable-item messages.
