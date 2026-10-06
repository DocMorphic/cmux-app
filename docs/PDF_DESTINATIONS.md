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

At this batch, Fit/FitB/FitH/FitBH/FitV/FitBV/FitR retained the
previous fitted-width fallback rather than implementing all fit modes; XYZ null
coordinate retention still needs work. Document-wide continuous zoom, arbitrary
zoom beyond the renderer limits, precise cross-rotation anchor restoration,
selection handles/cross-page selection, physical Pixel interaction and iOS visual
comparison remain open. No new APK, device result or signed release is claimed.

## Shared document zoom and fit destinations — 2026-10-06

The follow-up implementation replaces the fixed-height, independently zoomed
page boxes with one saved document scale and horizontal pan. Lazy page heights
now grow with magnification, so the entire magnified page remains reachable by
vertical scrolling. Pages use the widest page as the fitted-width reference;
mixed-size pages therefore share one document-point scale. Pinch anchors use
the visible page and point under the fingers. Double tap centers that point;
vertical swipes stay with the list and magnified horizontal pans use the document
transform. Foundation's non-touch transform path is retained. Saved width changes
rescale the vertical offset instead of keeping an obsolete pixel offset.

Fit, FitH, FitV and FitR are now distinct destination modes. The resolver uses
both viewport dimensions, centers page/rectangle fits, and adds leading space
when centering needs it. Full-page layout prevents tall magnified rectangles
from being clipped by an unscaled page box. A fixed-size return control in the
page toolbar avoids changing the fitting viewport after the first link.
History now includes destination leading space as well as scroll, zoom and pan.

XYZ and applicable fit destinations with null coordinates retain the current
point through an inverse conversion to PDF user space and the destination page's
crop/rotation transform. That parser lookup runs on IO; new navigation or a zoom
gesture cancels a pending lookup. Unknown destination types and invalid FitR
rectangles are ignored. The destination semantics are based on Adobe's
[destination specification](https://opensource.adobe.com/dc-acrobat-sdk-docs/library/pdfmark/pdfmark_Actions.html)
and [PDF Reference, table 8.2](https://opensource.adobe.com/dc-acrobat-sdk-docs/pdfstandards/pdfreference1.6.pdf).

**Verification:** main and instrumentation Kotlin compilation passed in 76 seconds.
All **24 JVM cases passed**: eight destination geometry, five page-coordinate,
six shared zoom and five text-model cases. They include viewport centering,
mixed page dimensions, magnified rectangle height and retained coordinates across
different page rotations. An initial compile caught two implicit Compose receiver
references; explicit captured widths corrected them. Evidence is in
`captures/runtime/pdf-document-zoom/`; Gradle was stopped.

The Android fixture now checks parsed Fit/FitH/FitV/FitR requests and null-coordinate
retention across crop/rotation. It is compiled, **not executed**. Run it with XYZ
pixel magnification/recreation/return, search/copy and the existing short/mixed
PDF navigation checks. Add visible pinch/pan, tall rectangle, mixed-page scale,
rotation and enlarged-text review to the same media/PDF integration milestone.
No new emulator or APK was produced for this feature batch; ADB showed no Pixel.

Still open: **FitB/FitBH/FitBV content bounding boxes** retain the fitted-width
fallback; their geometry must not be inferred from text alone. Selection handles
and cross-page selection, fit-mode reflow across rotation, high-magnification
raster quality, renderer limits (0.125–8 fitted scale and bounded bitmap memory),
physical Pixel behavior and iOS visual comparison are not closed by compilation
or pure geometry checks. Shared document zoom and null-coordinate retention are
implemented but still require the queued runtime acceptance.

## Combined viewer integration — 2026-10-06

**13 distinct Android cases now have passing evidence** across the media/PDF
milestone's initial and focused follow-up runs, on the sole existing Android 17
emulator (API 37, 16 KiB, 1080×2400, density 420). This includes the eight original
PDF extraction/search/link cases, the new rectangle/gesture case, two PiP cases,
short/mixed PDF navigation and Changes image/PDF restoration.

- XYZ and FitR rendered pixel sizes/positions survive recreation. FitR shows the
  complete tall rectangle, centered; pinch close keeps its focal point, pinch
  open magnifies it again, and one-finger vertical scrolling remains available.
- Direct/named/GoTo destinations, fit modes and retained coordinates across
  crop/rotation pass extraction checks. Search highlighting/copy, return history,
  short pages, and retained Changes revision/file/page state pass their runs.
- The first 12-case run had one failed return-control accessibility assertion.
  Moving the description from its decorative icon onto the actionable button
  makes the enabled/disabled state belong to the queried control. Four focused
  cases then passed, including visible PiP frames and the new FitR pixel check.
- Extending that FitR case to real gestures exposed a final-finger-lift bug.
  A trace showed an unspecified centroid becoming a NaN scroll offset. The
  implementation now preserves one page-point anchor throughout the gesture,
  rejects nonfinite input and ignores pointer-up events without an active point.
  Temporary tracing was removed. The final three-case run passed in **36.837 s**:
  stable focal-point/zoom/scroll pixels, XYZ recreation/return, and short pages.
  The earlier intermediate failures remain in the evidence; they are not counted
  as successful runs.

Builds took 64, 29, 17 (test APK), 34, 23 (diagnostic APK) and 25 seconds. Final
DEX checks measured 724 PDF methods, largest 5,904 code units, and 3,511 native
screen methods, largest 13,156; none meet/exceed the 16,383 rejection threshold.
Final logcat contains no crash/ANR markers. Screenshots were inspected, not just
DOM/state assertions. Full logs, screenshots, APK hashes and per-case results are
in `captures/runtime/media-pdf-integration/verification.json` and its siblings.
Gradle and the emulator were stopped; no additional AVD was created.

This closes the listed fixture checks, not full PDFKit or physical parity.
Bounding-box fit modes, selection handles/cross-page selection, broad rotation,
large-text/TalkBack, high-zoom quality and real Pixel/Mac routes remain open.
No signed release was promoted.
