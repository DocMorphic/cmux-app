# Composer system paste — 2026-10-04

## Source contract and implementation

The scoped iOS reference is `0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`:
[MobilePasteInterceptingTextView.swift](https://github.com/manaflow-ai/cmux/blob/0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc/Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/MobilePasteInterceptingTextView.swift)
routes attachment Paste actions from the system edit menu and keyboard into the
composer stager. [MobilePasteboardAttachments.swift](https://github.com/manaflow-ai/cmux/blob/0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc/Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/MobilePasteboardAttachments.swift)
classifies images/files and materializes attachments, leaving ordinary text paste
to the native editor. An attachment paste consumes its captions/fallback text.

Android previously handled the attachment picker and IME `commitContent`, but its
value-based Compose text fields used the ordinary text-only Paste action. The
shared `RichContentEditor` now supplies a modifier to native terminal, SSH and New
Task composers. It replaces just the Paste menu item when clipboard MIME metadata
indicates attachment content, handles Ctrl/Meta+V and Shift+Insert, and intercepts
IME context-menu paste actions. Copy, Cut, Select All and ordinary text paste retain
the existing native behavior. No new dependency or text-field implementation was
introduced. The pinned Compose foundation 1.9.1 source was inspected because its
new `contentReceiver` only applies to the state-based text-field overload; see
[Android's clipboard overview](https://developer.android.com/develop/ui/compose/touch-input/copy-and-paste).

`ComposerClipboardPaste` reads only the clipboard description when advertising the
menu. Provider MIME lookup and delivery happen on an explicit paste. Content URIs
become attachments in clipboard order, and accompanying caption/URI fallback text
is not inserted. The existing app-owned encrypted staging and destination checks
remain in charge of decoding, size limits, upload and cleanup. Native terminal
file capability and SSH's images-only restriction still apply. Plain text uses
the native selection/composition connection. Closed or replaced owners cannot
paste into the new draft. Disabled and oversized attachment attempts report errors
and consume the action instead of inserting provider URIs as prompt/terminal text.

## Verification

All **nine selected Android cases** pass across the following runs on the existing
API 37 / 16 KB arm64 AVD:

- Five `RichComposerEditorTest` cases: real floating-toolbar image/file paste,
  captions excluded, hardware/IME attachment paste, Unicode selection replacement,
  disabled/oversized/stale-owner rejection, existing IME composition and ownership.
- Two `NativeTaskAttachmentsTest` cases: keyboard image insertion and **real system
  Paste**, both retaining the prompt, staging encrypted attachment bytes, creating
  the task through the loopback RPC fixture and checking exact uploaded image bytes.
- Existing SSH composer image/draft/navigation/send-order regression.
- Existing native terminal direct-image ordering and unchanged-composer regression.

The initial run passed seven and failed the two native-menu tests (103.496 s).
A screenshot showed Paste present; the test's accessibility lookup did not include
all interactive windows. The corrected helper temporarily enables interactive
window retrieval, restores its previous flags, and clicks the actual floating
menu node. Both failed cases then passed in **31.291 seconds**. No production menu
or assertion was relaxed for that rerun. These are nine distinct passing cases
across runs, not a new all-green nine-case run.

Final debug/test assembly passed in **1m 28s**; the test-only helper rebuild passed
in **18s**. The initial compile failure was an unnecessary `nativeKeyEvent` import,
removed because the property is a member in the installed API. Debug engine package
checks pass. No new JVM, release or signed build was run for this checkpoint.

Evidence is retained under ignored `captures/runtime/composer-system-paste/`:
build/runtime logs, package hashes, source API excerpts, the observed native toolbar,
and final menu/attachment screenshots. The screenshots were inspected. The task
screenshot shows the attachment chip; decoded/uploaded pixels were asserted from
the actual staged bytes. It is not a thumbnail-render timing check.

The existing AVD is stopped. No new virtual device, physical phone installation or
live account/remote Mac action occurred. The Android tests use local fixtures;
they do not establish physical Gboard/keyboard, external provider grant lifetime,
large or partially unreadable multi-item pastes, or drag-and-drop acceptance.
Those remain follow-ups alongside the broader parity goal. Build 494 remains the
last signed milestone, and the last unsigned-release/ART gate is `786264d`.
