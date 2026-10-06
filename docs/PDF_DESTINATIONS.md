# PDF destination navigation

## Scoped comparison — 2026-10-06

`ChatArtifactPDFView.swift` at `186cec79781256867ad4516f0802118738bd2393`
uses PDFKit's vertically continuous PDFView with automatic scaling. PDFKit
destinations carry a page, point and zoom, and PDFView exposes navigation
history. See Apple's [PDFDestination](https://developer.apple.com/documentation/pdfkit/pdfdestination)
and [currentDestination](https://developer.apple.com/documentation/pdfkit/pdfview/currentdestination).
Android's [native destination](https://developer.android.com/reference/android/graphics/pdf/content/PdfPageGotoLinkContent.Destination)
also carries X/Y in points and zoom. These are platform API semantics, not an
observed iPhone result. The global implementation/review pins are unchanged.

## Feature batch

Previously the Android reader retained only a destination page and Y coordinate,
reset every page to fitted zoom, and offered no return to the reading location.

- Direct, named and GoTo annotation destinations retain transformed X as well as
  Y. XYZ destinations retain their requested zoom; zero/absent zoom preserves the
  source page's reading magnification. Native extraction fallback carries X and
  zoom too. Existing crop/rotation coordinate conversion remains in use.
- The viewport converts absolute document zoom using screen density and fitted
  page width. X aligns to the leading edge where page bounds allow; Y combines
  the page transform with list scrolling. Additional last-page padding lets a
  destination on a short final page reach the viewport top.
- PDF zoom supports 0.125–8 times the fitted page size. Below fitted size it stays
  centered and one-finger gestures belong to page scrolling. Image previews keep
  their existing minimum of 1 and maximum of 8.
- Following an internal link saves a reading bookmark with page, scroll offset
  relative to width, scale and pan. Back to previous location restores it.
  The most recent 32 entries and the active destination survive saved-state
  recreation. They remain scoped to the preview's existing private file identity.
- Search result restoration no longer automatically jumps away from a restored
  reading position or link destination. Query changes and Previous/Next match
  explicitly request navigation, guarded against results from an earlier query.

## Verification and open work

Main and instrumentation Kotlin compilation passed (final run: 8 seconds), and
**19 JVM cases passed**: five destination, six zoom-transform, three page-coordinate
and five text-model cases. This coverage exercises XYZ alignment, preserved magnification, zoom out,
coordinate/zoom bounds and density/width conversion, alongside existing image
transforms, PDF geometry and link-model checks. Compilation/results and the
scoped source receipt are saved locally under
`captures/runtime/pdf-destinations/` (ignored by Git).
Gradle was stopped afterward; no emulator was started.

Queued Android coverage includes direct/named/GoTo coordinate and zoom extraction,
pixel-measured XYZ magnification before/after recreation, and returning to the
previous location. The existing search/link/copy test now also recreates after
following a link and uses the return bookmark. These checks are compiled but
**not yet run**; they belong to the combined media/PDF integration milestone.

This is not full PDFKit parity. Fit/FitB/FitH/FitBH/FitV/FitBV/FitR retain the
previous fitted-width fallback rather than implementing all fit modes; XYZ null
coordinate retention still needs work. Document-wide continuous zoom, arbitrary
zoom beyond the renderer limits, precise cross-rotation anchor restoration,
selection handles/cross-page selection, physical Pixel interaction and iOS visual
comparison remain open. No new APK, device result or signed release is claimed.
