# Keyboard URI grant acceptance — 2026-10-04

## Contract and test boundary

The production `RichContentEditor` and `TerminalPasteContent.receiveImage` request
the keyboard's temporary URI grant before accepting image content. The owned
`TerminalPasteContent` releases that grant once its consumer finishes or cancels.
[Android's InputContentInfo contract](https://developer.android.com/reference/android/view/inputmethod/InputContentInfo)
ties this permission to the object and provides explicit release; it must not be
replaced with broad file access or a permanently exported provider.

Earlier editor tests used same-app files or `commitContent` without the grant flag.
`KeyboardUriGrantTest` adds an actual platform `InputMethodService` and private
provider in the **separate instrumentation APK/UID**. The keyboard commits its
fixed generated PNG through the system input connection with
`INPUT_CONTENT_GRANT_READ_URI_PERMISSION`. No manual `grantUriPermission`, shell
permission adoption or fake permission token is used. The tests check different
UIDs, denied access before acquisition, real provider byte reads, and denial after
release. The fixture is platform-only Java because Android test APKs omit runtime
dependencies already supplied by the target app in the instrumentation process.

The tests refuse to change keyboards on a physical device. They save the emulator's
selected/enabled keyboard state, enable the fixture, and restore the original
selection and remove the fixture from enabled keyboards after each case. The
provider serves only a generated yellow image; no credentials or user files are
read. All fixture components live under `androidTest`; app DEX/manifest checks
verify they are absent from the app package.

## Runtime cases

- Remove the editor while its consumer still owns the accepted content, then use
  production `AttachmentFiles.prepare` to copy/decode the provider image. Check
  actual yellow pixels, close content twice, and verify access is denied.
- Decline content and throw from an accepting callback. Both paths must release
  the acquired grant without changing prompt text.
- Reserve a production `TerminalInputQueue` action before reading provider bytes;
  cancel the queue and verify that the grant is revoked and prompt text unchanged.

All **three cases pass in one final run (21.865 s)** on the existing API 37 / 16 KB
arm64 emulator. Test APK assembly passes (final rebuild 19s). Independent checks
confirm the original keyboard was restored and the fixture keyboard disabled.
The existing emulator is stopped; no new AVD was created. App DEX and manifest
contain no fixture classes/components, and the app APK hash is unchanged from
`2599fb2`'s tested debug package. No new JVM, app, release or signed build was run.
Evidence is retained under ignored `captures/runtime/keyboard-uri-grants/`:
build/runtime logs, initial failure context, inspected emulator screenshots,
keyboard restoration state, APK hashes and manifest/DEX checks.

Initial diagnostic runs are preserved: the first fixture process failed on a
missing Kotlin runtime class; the Java version fixed that startup issue. A later
run timed out behind an observed emulator System UI ANR dialog. After dismissing
the dialog, queue cancellation passed; two remaining cases exposed a fixture
readiness check that treated Android's fallback input connection as an active
image editor. The final probe checks lifecycle and advertised image MIME support.
The final three-case run includes the corrected probe; the diagnostic failures
are not counted as passing evidence.

This is real Android URI-grant coverage using a controlled keyboard/provider. It
does not prove physical Gboard, clipboard replacement/revocation, drag/drop,
cloud-provider streaming, process death or a live Mac upload. Existing ownership,
composer staging and RPC-upload cases provide separate coverage. No production
feature, permission, dependency, global parity pin or signed milestone changed.
