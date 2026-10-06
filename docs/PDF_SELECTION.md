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
