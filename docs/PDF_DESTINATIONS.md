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

## Content bounding-box destinations — 2026-10-06

FitB, FitBH and FitBV now have distinct parsed modes. Opening one resolves the
page's geometric marks on IO and caches the small result for that document.
The shared viewport fits both dimensions, width, or height of that rectangle,
retaining the explicit top/left axis for FitBH/FitBV. Blank pages use their full
page bounds. Existing return history and saved viewport behavior are reused.

`PdfContentBounds` uses the already bundled PDFBox-Android graphics stream
engine. It includes white painted shapes (not just nonwhite pixels), glyph
outlines, stroked paths/caps/joins/dashes, transformed image extents, form and
transparency-group content, and clipping. It preserves the parent path across
nested forms, transforms the resulting marks through crop/rotation, and does
not decode image pixels. Text clipping is applied at the text object's end.
This follows the callbacks in the pinned [PDFGraphicsStreamEngine](https://github.com/TomRoush/PdfBox-Android/blob/v2.0.27.0/library/src/main/java/com/tom_roush/pdfbox/contentstream/PDFGraphicsStreamEngine.java)
and font transforms in [PageDrawer](https://github.com/TomRoush/PdfBox-Android/blob/v2.0.27.0/library/src/main/java/com/tom_roush/pdfbox/rendering/PageDrawer.java).

Operation/glyph/nesting bounds prevent unbounded recursive extraction. Parser
errors propagate instead of returning partial bounds that might crop content;
the viewer then keeps the current reading location and shows its existing link
failure. Graphics are measured only when following a content-fit link, not while
extracting every visible annotation. No additional parser dependency or renderer
was introduced.

**Verification:** main/instrumentation compilation and 20 focused JVM cases
passed in 25 seconds: ten destination, five page-coordinate, five text-model
cases. Four Android fixtures cover vector/image/text marks, clipped nested forms
and outer strokes, crop/rotation/blank/error paths, lazy resolution and explicit
axis preservation. The existing annotation parser fixture also includes all
three content-fit expressions. A fixture compile caught the PDFBox setter overload;
using `setContents(PDStream)` corrected it. Final main/instrumentation compilation
passed in four seconds. Logs and JVM receipts are in the ignored local
`captures/runtime/pdf-content-bounds/`. These Android checks are queued for the next
combined viewer milestone; they have not been executed in this batch.

Remaining: visible content-fit pixels/history/recreation on Android, fonts and
complex shading/pattern/soft-mask/optional-content examples, physical Pixel and
matched iOS views. Geometric image extents include transparent image areas;
curves can have conservative control-point bounds; unbounded shadings use the
current clip. These approximations need comparison against PDFKit before this
area can be called full parity. Selection handles/cross-page selection and
high-zoom quality remain open. No APK, emulator, or signed release is claimed.

## Content-fit runtime acceptance — 2026-10-06

The queued four content-bounds extraction cases and expanded FitB/FitBH/FitBV
parser case now pass on the existing API37 / 16 KiB AVD. A new visible FitB case
also passes: the complete graphics rectangle is magnified and centered, survives
Activity recreation, and retains a working return bookmark. Its screenshot was
inspected. Existing XYZ/FitR/pinch/scroll and search/copy cases passed in the same
milestone. See [the combined evidence](PDF_SELECTION.md#combined-content-fit-and-selection-integration--2026-10-06)
for the 16 distinct passing cases, initial selection-fixture failure, correction,
APK hashes and remaining acceptance. Complex content bounds, high-zoom quality,
physical routes and matched iOS visuals remain open.

## Visible-area detail rendering — source checkpoint, 2026-10-06

Magnified pages now request a separate raster of the visible page region when
the fitted preview no longer supplies enough pixels. The original preview stays
visible while detail is prepared; the detail overlays the same page coordinates
below selection/search highlights. Scroll offsets, destination insets, centered
mixed-width pages and horizontal pan feed the page-to-viewport mapping.

The planner uses quarter-octave render scales and an overscanned 128-pixel grid
to reuse a region across small movements. Each detail bitmap is capped at four
million pixels and 4096 pixels per edge. Large viewports reduce detail density
to stay within this budget; a detail pass that cannot improve the preview is
skipped. These are implementation memory budgets, not proven unavoidable Android
limits, and the existing document zoom range is unchanged.

Rendering uses the existing platform PDF renderer with an explicit point-to-pixel
scale/translation matrix. Requests are delayed 80ms during movement; obsolete
requests are cancelled and cannot publish. Cancellation is checked after acquiring
the document monitor and after rendering; the synchronous native render itself
cannot be interrupted. Unpublished bitmaps are recycled on failure/cancellation.
The displayed image is retained until replaced and left to runtime memory
management afterward, avoiding recycling a bitmap still used by a render-thread
frame. Offscreen/preview-resolution pages release their retained detail reference.
No new PDF parser, dependency or persistent raster cache was added. The Android
[render matrix contract](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer.Page#render(android.graphics.Bitmap,android.graphics.Rect,android.graphics.Matrix,int))
explicitly supports rendering a page portion into a destination bitmap.

**Six JVM geometry checks passed**, and main/instrumentation Kotlin compilation
passed in **23 seconds**. Coverage includes viewport coverage, grid reuse,
page-edge clipping, tall pages, large displays, invalid geometry and allocation
bounds. Two new Android checks are **compiled but not executed**: native glyph
pixels compared against an independent whole-page raster for all four crop/rotation
cases, and oversized/retired/cancelled region behavior with renderer reuse.
Visible sharpness, overlay alignment through pinch/pan/history/recreation, peak
memory and real Pixel/Mac acceptance remain for the next combined viewer run.
No APK or emulator was created; Gradle was stopped. Local evidence:
`captures/runtime/pdf-detail-regions/verification.json` and compile/test log.

## Detail-rendering integration — 2026-10-06

**Four Android cases passed in 18.481 seconds** on the sole existing API37 /
16 KiB emulator: crop/rotation glyph pixels, oversized/closed/cancelled requests
with renderer reuse, visible detail before/after Activity recreation, and XYZ
destination position/zoom/history before/after recreation.

The new screen-pixel fixture displays fine vertical lines at 4× document zoom.
Its visible red-channel standard deviation was 98.468 versus 82.388 for an
independently magnified strip from the fitted preview, both before and after
recreation; the 419×419-pixel frame also checks placement and scale. This exceeds
the fixture's 10% contrast-improvement threshold. Four screenshots were inspected.
These are synthetic contrast/geometry checks, not a general image-quality score.

The initial combined build failed in the new test's Canvas overload; replacing
that test-only call with a matrix draw corrected compilation. The final combined
APK build passed in 39 seconds. No production repair was needed. Debug APK SHA-256:
`a9ea39b7768a84981b881ea3a78985225ce1c9a1f56dedb6d225c13f3d95f36c`;
test APK: `5a6f1f9fa0490cc7bd46e26b096801b7caef610cb4c2585085daa67bd23928fa`.
No crash/ANR markers were found in the captured logs. Gradle was stopped before
the emulator run; the emulator was then stopped and reaped. No new AVD or signed
release was created. Local evidence: `captures/runtime/pdf-detail-integration/`.

Broad pinch/pan/overlay alignment, peak memory, large-font/accessibility, complex
PDFs and physical Pixel/Mac acceptance remain open.
