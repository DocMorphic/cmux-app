# On-page PDF text selection

## Source and scope — 2026-10-06

The scoped iOS `ChatArtifactPDFView.swift` at
`186cec79781256867ad4516f0802118738bd2393` embeds PDFKit's vertically continuous
PDFView. Apple's [PDFView documentation](https://developer.apple.com/documentation/pdfkit/pdfview)
and [document interactions](https://developer.apple.com/documentation/pdfkit/document-interactions)
cover selection, select-all, clearing and clipboard copying. This is a source/API
comparison; it is not a matched-device observation. Global parity pins are unchanged.

## Implementation

Long press now selects a word on the rendered page. The selection is highlighted
in place and has two draggable handles, replacing the long-press word dialog.
The existing Page text dialog and accessible read/copy action remain available.

- Handle movement follows extracted reading-order offsets across page boundaries;
  crossing handles retains the selected range in document order. Holding a handle
  near the viewport edge scrolls the document. The handle overlay is a sibling of
  the page scroller, so its drags do not go through the page's pinch recognizer.
- Glyph baseline endpoints pass through the existing crop/rotation transform.
  They place handles and resolve movement along rotated/skewed glyph advances.
  Bidi direction reverses run edges where appropriate. Normalized ligature runs
  retain their whole glyph span, and drag endpoints do not split surrogate pairs.
- Copy selection, Select page, Select all and Clear selection wrap at narrow
  widths. Android Back clears an active selection before dismissing the viewer.
  Page accessibility actions can start selection without a long press; handle
  actions move the endpoint backward or forward through text boundaries.
- Saved state contains only four integers (two page/UTF-16-offset positions).
  Text and carets are reloaded from the owned document after recreation. Disposing
  the preview cancels selection/copy work; delayed extraction cannot publish into
  a cleared or replaced selection. Search/link/page navigation clears selection;
  ordinary scrolling and zoom preserve it.
- Selection uses the existing compatibility extractor on all supported Android
  versions so offsets, highlights and copied text use one representation.
  Its extraction permission checks and bounded text cache remain in effect.
  Copy reads pages sequentially, supports cancellation between pages and inserts
  page separators without duplicating existing trailing newlines.

Clipboard assembly currently rejects more than 250,000 UTF-16 code units, with
an actionable failure and no silent truncation. This is conservative envelope
headroom chosen by the implementation, **not a measured universal Android limit
or a proven unavoidable difference**. Large-copy behavior still needs physical
acceptance. No extra PDF dependency, OCR, or parser renderer was added.

## Verification

Main and instrumentation Kotlin compilation passed. **16 focused JVM cases
passed**: five selection, six compatibility text and five text-model cases.
They cover crossing/cross-page ranges, word lookup, glyph bounds, surrogate and
ligature endpoints, rotated baselines, RTL edges and existing search offsets.
The first run caught a receiver-shadowing error in the word BreakIterator setup;
explicitly using the extracted page text corrected it. The final test run took
five seconds; final accessibility/select-all compilation also took five seconds.
Local receipts and original failure are in `captures/runtime/pdf-selection/`.

Two Android cases are compiled and **not yet executed**:

1. Real two-page extraction, forward/reverse copied range, page highlights and
   rejection after the document closes.
2. Long press and actual handle drag across pages, Activity recreation, visible
   blue-highlight pixels, then clipboard text matching both fixture pages.

Run these with the queued four content-bounds cases, expanded fit parser case,
and PDF navigation/gesture regression checks at the next viewer integration
milestone. Pixel was absent; no emulator, APK or signed build was produced.

## Remaining acceptance

Real gesture/autoscroll and saved-state behavior are unverified until the queued
Android runs pass. Select-all and cancellation, empty/intervening scanned pages,
large/complex and mixed-direction documents, clipboard limits, touch targets,
TalkBack traversal, enlarged text, rotation/zoom during selection, process death,
physical Pixel/Mac routes and matched iOS visuals remain open. Ligatures and
normalized/bidi groups use conservative glyph spans; this does not prove exact
PDFKit character selection for every font or text layout.

## Combined content-fit and selection integration — 2026-10-06

**All 16 distinct Android cases have passing evidence** on the sole existing
Android 17 / API 37 / 16 KiB emulator (1080×2400, density 420). This combines the
four content-bounds extraction cases with twelve PDF text/navigation/rendering
cases, including the two new selection cases and visible FitB fitting.

- The initial run passed 15/16 in **73.434 seconds**. Its selection check expected
  the end handle to remain visible even though the drag placed that endpoint
  below the viewport. A diagnostic rerun failed the same expectation; before and
  after screenshots retained the selected text and reading position. The UI
  hierarchy also exposed stale pre-layout bounds before recreation.
- The stronger fixture injects a real touch drag and holds at the viewport edge,
  exercising autoscroll until the second-page selection is visible. It then
  recreates, checks blue highlight pixels, and verifies the exact copied text
  from both pages. This case passed unassisted in **28.141 seconds**. Its restored
  screenshot visibly shows the selected second line and end handle.
- Visible FitB fitting shows the complete green graphics rectangle, centered
  and magnified, before and after recreation, with working return history.
  The extraction cases cover white vectors, images and glyphs, clipped nested
  forms and outer strokes, crop/rotation, blank/error paths and lazy resolution.
  Existing XYZ/FitR/pinch/scroll, links, search/copy and reopening checks passed.

One app/test build took **62 seconds**. Diagnostic and stronger-gesture test-only
builds took **17 and 16 seconds**. The app APK hash stayed unchanged across the
follow-ups: `a8b75b5a63bdd859d2077bf470ed51653e84e897453c963dbc4222d63d203bbd`.
Final test APK hash: `1d2ca907b502a312f39b9f361cc699c6d537129759370e4123b6d514ee1cd4cb`.
No app implementation change was required by this milestone.

DEX checks found 739 PDF-related methods (largest 6,256 code units) and 3,511
NativeScreen methods (largest 13,156), with none rejected by the known ART
size/register threshold. Recorded initial/final logcat searches found no
crash/ANR markers. Screenshots were inspected. Complete logs, failures, per-case
results, APK metadata and DEX receipts are in the ignored local
`captures/runtime/pdf-selection-content-integration/verification.json` and siblings.
The emulator and Gradle were stopped; no new AVD or signed promotion.

This closes the listed fixture checks. Select-all/cancellation and broader
font/layout/rotation/accessibility behavior, clipboard limits, process death,
physical Pixel/Mac acceptance and matched iOS visuals still require verification.
The Pixel was absent. These results do not establish full PDFKit or app parity.
