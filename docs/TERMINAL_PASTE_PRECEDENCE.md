# Terminal attachment paste precedence — 2026-10-04

## Source contract

The scoped iOS reference remains `0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`.
[TerminalInputTextView.swift](https://github.com/manaflow-ai/cmux/blob/0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc/Packages/iOS/CmuxMobileTerminal/Sources/CmuxMobileTerminal/TerminalInputTextView.swift)
lines 1180–1214 clear modifiers before Compose, Paste, Files and Zoom. Its
`handlePasteAction` at lines 1276–1303 prioritizes an image over clipboard strings;
the successful image branch returns without forwarding accompanying text.
The source was read directly from the existing upstream Git cache.

## Changes

`TerminalPasteContent.fromClipboard` now consumes a clip containing content URIs
as attachments, excluding captions in the same item and separate text items.
The shared classifier serves native/SSH toolbar Paste and the direct terminal's
system/hardware Paste paths. A document URI also takes precedence over its text
fallback, consistent with the existing composer system-Paste behavior.

Android retains its supported ordered batch of up to ten clipboard items; iOS's
direct terminal uses the primary image. This platform adaptation does not turn
attachment captions or URI fallback text into additional remote input. Text-only
clips and web links keep their existing classification. Provider lookup failure
still fails this direct classifier without falling back to text; composer system
Paste retains its separate partial-provider handling.

SSH Paste, Files, Zoom and the Keyboard/Compose switch clear armed modifiers.
Native Zoom now clears them too; native Paste, Files and Compose already did.
Existing destination guards, image preparation, transfer order, file capability
checks and explicit Send behavior remain in charge of delivery.

## Verification

Nine distinct Android cases pass across two runs on the existing API 37 / 16 KB
arm64 emulator:

- Three `TerminalRichInputTest` cases cover attachment/caption classification,
  text-only Unicode and links, rejected Intent/local-path coercion, bounded batches,
  IME type/owner guards and idempotent grant cleanup.
- Three new SSH screen cases verify image paste followed by delayed typing without
  captions or Enter, composer prompt preservation with no upload before Send, and
  clearing armed modifiers on Paste, Zoom, Files and Compose.
- Three existing SSH image screen cases cover actual system photo picking,
  composer navigation/draft retention, uploaded pixels and direct IME ordering.

The initial run passed eight of nine cases in 76.521 s. The remaining test looked
for a Files toolbar shortcut which is hidden by default; it now uses the visible
Files header button, which calls the same production handler. That corrected case
passed in 29.332 s. These are nine distinct passing cases across runs, not a new
all-green nine-case run. Native Zoom's reset compiled but was not separately
exercised in a native-account screen test.

Debug/test APK assembly passed in 57 seconds, the test-only rebuild in 19 seconds,
and debug notice-engine packaging passed. No new JVM, unsigned release or signed
build was run for this checkpoint. The existing emulator was stopped afterward.

Evidence: ignored `captures/runtime/terminal-paste-precedence/`. This checkpoint
uses the existing emulator and local fixtures. It does not establish physical
Pixel/Mac acceptance. Build 494 remains the last signed milestone; the last
unsigned-release/ART gate is `786264d`.
