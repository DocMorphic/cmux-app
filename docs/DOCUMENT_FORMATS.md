# Content formats and offline Word preview

## Privacy/RTF Android integration — 2026-10-10

The combined four-case Android 17/API37 16 KiB integration **passed in 28.205 s**
on the existing `cmux_api37_16k` AVD. Both Privacy cases and both RTF cases passed.
RTF's shared real file route, Unicode/styles/fields, blocked fetch, raster pixels,
vector structure and restored reader location were exercised. The final restored
marker region had **3,180 dark text pixels and 14,730 white paper pixels**; the
inspected screenshot visibly contains paragraph 80 and `RTF_FINAL_MARKER`.
The inspected first-page capture shows styled text, Japanese, the raster picture
and two vector pictures. This is fixture evidence, not matched Quick Look or
physical Mac/Pixel acceptance.

The first attempt failed one of four cases in 49.998 s with a System UI ANR
dialog covering the viewer. System UI/keyguard and Google Play Services SIM
broadcast ANRs were present before testing. After dismissing the observed dialog,
the same APKs passed four cases in 26.311 s, but screenshot inspection found a
blank restored frame despite its DOM/scroll assertions. That capture was rejected
as visual restoration evidence. The test now waits for the native loading state,
WebView visual-state callback and platform frames, then requires actual marker
ink/paper pixels before saving the accepted screenshot. No production renderer
change was required. The final run has an empty crash buffer and zero new
crash/ANR event records.

Application source: `2bcd70bc`; only the instrumentation capture/paint checks
changed for this run. Debug APK SHA-256:
`ccc4ec369c2f9a464c1cdf281eba093d3ce1c7f2bdb514df93e702b6165f9035`.
Final test APK SHA-256:
`aca934a007ff348fd18772c9761a09ae367014e71a61ae7499450cff14871848`.
The debug APK contains all 21 pinned vendor assets and the expected 22 native
libraries. Initial debug/test assembly took 86 s; the capture-only test update
took 13 s and the final paint-check test update 36 s. Gradle was stopped and its
process exit checked before each emulator launch. The sole AVD was stopped/reaped
and its prior screen timeout restored after testing. The requested 1536 MiB was
clamped by the emulator to 4096 MiB (guest MemTotal 4,062,848 KiB), not a 1536 MiB
running guest. No extra AVD was created.

Evidence, APK hashes, three attempts, obstructed/blank/accepted captures, viewport
pixel receipts and before/after system logs are retained under ignored
`captures/runtime/privacy-rtf/`, including `verification.json`. Signed 641 remains
the last verified signed download and predates these features. Advanced RTF,
actual remote paths, matched iOS, process death and physical acceptance stay open.

## Offline RTF preview — 2026-10-10

RTF binary artifacts now use the shared in-app reader instead of requiring an
external application. Routing recognizes `.rtf`, `application/rtf`, `text/rtf`
and `application/x-rtf`, while preserving image/PDF/media/Markdown/wire-text
precedence. This matches the scoped iOS router at
`f4b1509054949eaad5d695569ad443c4c18ed68d`: recognized binary content is a Quick Look
candidate with runtime admission, and wire text remains the text route. This
does not advance the global pin or establish actual Quick Look layout parity.

The unmodified MIT-licensed rtf.js 3.0.9 bundles (RTFJS/WMFJS/EMFJS, including
js-codepage 1.15.0) are vendored offline and SHA-256 pinned. The npm tarball's
published SHA-512 integrity was independently checked before copying. Original
licenses/copyrights are available in the app's licenses sheet. Sources:
[renderer and license](https://github.com/tbluemel/rtf.js),
[document/render API](https://github.com/tbluemel/rtf.js/blob/master/GETTING_STARTED.md).
No document code, network import callback, remote image or font download is enabled.

- The shared reader prepares an independently owned `document.rtf` snapshot,
  with cancellation/lease cleanup and a 16 MiB actual-byte limit. The scanner
  checks RTF version, group/escape structure, numeric parameters, 128-level depth,
  100,000 controls/groups, 256 pictures and 8 MiB aggregate binary payloads. It
  skips literal binary bytes correctly rather than treating their braces as RTF.
  Font/image layout parameters are bounded before the decoder.
- WMF/EMF decoder references are wrapped before RTFJS loads. Actual record lengths
  and an 8,192-record/8 MiB budget are checked before vendor decoding. Generated
  DOM has a 100,000-node budget and allows only the renderer's passive text/image/
  SVG elements. Handlers, active URLs and external CSS/SVG resources are removed.
- Authored text, codepage/Unicode, character/paragraph styles, safe absolute
  links and embedded raster/vector pictures use the renderer. External field
  imports receive an error without IO. WebView serves only the bundled assets
  and the owned snapshot; outgoing links use the existing gesture gate.
- Reader scroll/zoom restoration and original Open/Share/Save reuse the shared
  Office/content lifecycle. The preview's notice explains that some formatting
  and embedded objects may be omitted. Tables, advanced lists/objects/fields,
  page geometry, broader metafile variants and exact Quick Look rendering still
  require work/comparison; they are not declared unavoidable platform differences.

**21 focused JVM cases passed**: ten new RTF budget/routing/snapshot cases plus
six existing Office package and five reader-state/routing checks. Main and
Android-test Kotlin compiled in the same successful **51 s** run. Eight actual
renderer/DOM/vector-budget checks passed on Node 26.8.2 and 22.16.0, with zero
failures/skips. Seven signed-package-verifier cases pass, including missing/
corrupt RTF inventory detection; all **21 pinned vendor assets** match. The
locked jsdom test tooling installed with lifecycle scripts disabled and was also
reinstalled from the lock offline; it is not shipped to Android. The milestone
workflow installs these test dependencies before its existing Node checks.

The strengthened restored-position/final-marker Android case compiled again in
20 s after its final change. Gradle is stopped; no emulator was started.

The initial DOM assertion counted four nested SVG viewports instead of two
authored vector pictures; the assertion now checks top-level pictures while
retaining actual rect/format/text/link checks. The first package-verifier fixture
failed because its synthetic inventory still had five directories; it now
includes RTF and verifies every listed directory. No renderer failure was hidden.
Local logs/fixture source are retained in `captures/rtf-*` and
`scripts/generate-rtf-fixture.py`.

At the initial feature checkpoint two Android cases were compiled but unexecuted: the real file route, Unicode/
formatted content, safe fields, denied fetch, vector structure and actual raster
pixels/capture; and restored scroll position plus final-marker geometry/capture.
The combined integration above now passes them with inspected raster/restoration
captures. Actual Mac/Pixel flows and matched Quick Look remain open. No signed
release was created for this feature; signed 641 predates RTF.

## Embedded workbook pictures — 2026-10-10

Scoped iOS `Artifacts/ChatArtifactPreviewRouter.swift` was rechecked at
`f4b1509054949eaad5d695569ad443c4c18ed68d`, SHA-256
`e20371818593f22f028a474c608dc11b71f1b373d31dfdc36d2af880596c599c`.
It still routes recognized content after image/PDF/media/Markdown/text to Quick
Look, with separate runtime admission. It does not define a custom worksheet
renderer or prove every Office format/variant displays. The global pin is unchanged.

The existing Android XLSX reader now projects embedded PNG/JPEG/GIF/BMP pictures
from worksheet/drawing/image relationships in the already validated, owned ZIP:

- One-cell, two-cell and absolute anchors retain typed EMU offsets/extents,
  cropping, rotation and horizontal/vertical flips. Descriptions remain literal
  image alternative text. Eligible hyperlinks use the existing gesture/link policy.
- Current-window image geometry follows actual rendered cells, including hidden
  rows/columns and wrapped rows. Sparse prefix sums locate anchors beyond the
  window without iterating a million rows. Pictures are clipped to the cell area;
  rotation affects intersection and range bounds. Image-only sheets and pictures
  beyond existing cells expand the navigable range without synthesizing cell data.
- Raster signature admission is bounded to 16 MiB per image and 32 MiB of unique
  picture resources per worksheet, with at most 256 drawing objects. Repeated
  references reuse Blob URLs; page exit revokes URLs, and retired DOM callbacks
  cannot change the current worksheet's notice. External image imports are not
  fetched. Only local Blob images were added to the existing CSP; script, frame,
  object and network isolation remains in the shared Office controller.
- Unsupported charts, grouped/vector/tiled objects and failed images retain an
  Open-original notice. ODS drawing support remains open. The original workbook
  remains the export/Open/Share/Save payload.

The anchor/rectangle projection follows the documented
[worksheet drawing types](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.drawing.spreadsheet.worksheetdrawing?view=openxml-3.0.1)
and [two-cell anchors](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.drawing.spreadsheet.twocellanchor?view=openxml-3.0.1).
This is an Android implementation batch, not a claim that its layout matches
the actual iOS Quick Look surface. Complex effects, shapes, charts, all image
variants and exact off-window automatic row sizing still require work/comparison.

**47 focused Node checks passed**, zero failures/skips: 16 picture projection
checks plus the existing workbook/ODS checks. These include real fixture bytes,
namespace/reference admission, anchor geometry, crop/rotation/flips, hidden/sparse
axes, actual-window geometry, image-only/final-row reachability and bounds.
JavaScript syntax and all 18 pinned vendor hashes passed. Main and Android-test
Kotlin **compiled in 25 seconds** at the initial feature checkpoint; Gradle was
stopped afterward. The Android runtime integration below supersedes that
compile-only checkpoint.

The original fixture generator is `scripts/generate-workbook-pictures-fixture.py`.
`pictures.xlsx` adds a generated two-color PNG, cropped/flipped, two-cell,
absolute/rotated, later-window and image-only-sheet cases, plus a rejected external
image. SHA-256:
`fc0eed20ebd9a101e4ccae56ff148055b1bf2dcfd315f469e8512ae8e696fbe4`.
The five-case `WorkbookPreviewRuntimeTest` integration now **passed in 45.795
seconds** on the existing `cmux_api37_16k` AVD. The new case requires actual
crop/flip colors, later-window navigation, saved-position restoration and
image-only-sheet pixels. The four existing cases cover XLSX formatting/navigation
and ODS repeated cells, internal links, visibility/dimensions/styles and rich
runs. Inspected captures show the cropped/flipped image, later/restored page,
image-only sheet, ODS text/styles and restored destination. Two-cell dimensions
and rotation are DOM checks in this case; their complete visual accuracy remains
open.

The original integration failed **3/5 in 40.761 s**. Settling Compose plus
WebView's visual callback reduced this to **1/5 in 48.438 s**. The picture failure
capture visibly contained the expected colors, but `scrollIntoView` moved
`visualViewport.offsetLeft/offsetTop` while `scrollX/Y` stayed zero. The native
pixel calculation now subtracts those offsets from DOM rectangles. That run
passed the picture case, but failed **1/5 in 42.441 s** because one ODS capture
still showed the old loading shell although its DOM was ready. ODS checks now
wait up to 15 seconds for the same required ink/background pixels, saving each
latest capture and geometry. No pixel threshold was relaxed and no production
viewer change was required. A transient missing `Context` import caused an
11-second test compilation failure; restoring it built successfully in 8 s.
The final test build succeeded in 19 s.

Final debug APK SHA-256:
`125648b5e0c3a1d1e983e94ccf0635ed6664e59f37dae09072041ada4d3fc5d7`;
test APK:
`e492ecf4e561dc9ba80b31c2d3dc325a77eba3dde7108b378b7c06a444df76f9`.
Evidence is ignored in `captures/workbook-{pictures-integration,paint-settle,
viewport,painted-cells}-*.log`, their APK hash files and corresponding
`captures/runtime/` directories. The final selected event log contains a Google
Play Services SIM-state broadcast ANR; it contains no cmux app crash/ANR entry.
Earlier selected logs were empty. This is not a claim of a clean system log or
physical performance acceptance. Gradle and the existing emulator were stopped
and reaped between builds/runs; no additional AVD was created. Signed 641 still
predates workbook pictures. Physical Mac/Pixel, actual remote routes and matched
iOS acceptance remain open.

## Scoped iOS comparison — 2026-10-06

Reviewed `ChatArtifactPreviewRouter.swift`, its router tests and
`ChatArtifactViewerModel.swift` in `Packages/iOS/CmuxAgentChatUI` at
`c2715faa02c260b07012bc0b386597cfb333021d`. This scoped comparison does **not**
advance the project's global parity pin.

The iOS router selects image wire kinds first, then PDF, media types, Markdown,
text and Quick Look candidates. Quick Look candidates must also pass
`QLPreviewController.canPreview` after downloading. Its router tests explicitly
include DOCX, XLSX, PPTX, Pages, Keynote and Numbers; a router test alone does not
prove that every variant of those formats renders on a phone.

| Format | iOS route | Android implementation | Remaining evidence/work |
| --- | --- | --- | --- |
| Images | Image viewer | ImageDecoder/BitmapFactory, animation, zoom and image actions | Explicit codec/animation/metadata matrix and Pixel acceptance; SVG/HEIC/TIFF variants need comparison |
| PDF | Native PDF viewer | PdfRenderer/PDFBox reader, search, selection, destinations and zoom detail | Broader font/layout/gesture and physical acceptance |
| Audio/video | Media viewer using UTType | Android media route by MIME or known extension | Codec/container matrix, real playback/PiP and lifecycle acceptance |
| Markdown | Rendered Markdown | Pinned upstream HTML/libraries with Android adapters | Broader real-route reflow/recovery and physical acceptance |
| Text/source | Text viewer | Raw text, highlighting, search and reader controls | Broader encodings/large-file/physical acceptance |
| DOCX | Quick Look candidate, runtime admission | **New offline Word renderer** in the shared preview component | Runtime rendering, text selection, zoom/restoration, real-route actions and Pixel acceptance |
| XLSX | Quick Look candidate, runtime admission | Offline workbook reader, sheet selection, bounded grid navigation and new embedded raster-image projection | New picture runtime checks, broader formatting/navigation/restoration and Pixel checks; charts/other drawings/conditional formatting and exact Quick Look layout remain open |
| PPTX | Quick Look candidate, runtime admission | Offline PowerPoint renderer with slide navigation and saved position | Rendering/lifecycle evidence below; exact Quick Look layout and unsupported format variants remain open |
| ODS | Quick Look candidate when recognized, runtime admission | In-app workbook preview with visibility, basic styles, dimensions, navigation and saved position | Broader authored formatting, charts/drawings and matched Quick Look/Pixel acceptance remain open |
| RTF | Quick Look candidate, runtime admission for recognized binary content | Offline rich-text reader with styled paragraphs, safe links and raster/vector pictures; two Android raster/restoration cases pass with inspected captures | Broader tables/lists/objects/fields, layout, process death, actual routes and matched Quick Look/Pixel acceptance remain open |
| Legacy DOC, XLS, PPT, Pages, Keynote, Numbers and other Quick Look content | Quick Look candidate when recognized, runtime admission | External Open/Share/Save fallback | In-app format implementation and matched format checks remain open; these are not declared unavoidable platform differences |
| Unknown binary/archive | Binary unless recognized as content | External Open/Share/Save | Exact eligibility/menu comparison still open |

## DOCX implementation

- Files, Changes and composer attachments share `filePreviewRoute` and
  `FilePreviewContent`. DOCX is recognized by extension or normalized MIME after
  the existing image/PDF/media/text precedence. Existing Open/Share/Save ownership
  and freshness behavior is reused; exports use the original document.
- Bundled **docx-preview 0.4.1** (`c533f383069008d4a1e05c6171ef8777fd078385`)
  and **JSZip 3.10.2** (`020fa3384c994f923123141bbeebae0c3a44dd0d`)
  render text/style, tables, images, headers/footers and footnotes/endnotes.
  Explicit and saved page breaks are enabled. Pagination remains the library's
  HTML approximation; automatic Word layout, fonts, fields and unsupported
  constructs are **not** established as equivalent to Quick Look.
- `app/src/main/assets/docx-viewer/manifest.json` records npm tarball URLs,
  SHA-512 integrity and SHA-256 hashes for the two unmodified bundles. Tarball
  integrity was verified before extraction. The local shell/CSS/adapter are
  app-owned code. `scripts/verify-viewer-assets.py` now verifies 16 pinned assets.
  Licenses are included in Settings → Open-source licenses.
- A cancellable IO pass reads the ZIP, checks actual lengths/CRCs and rebuilds an
  owned STORED ZIP. Limits: 64 MiB original and total expanded content, 16 MiB
  per part, 8 MiB per XML part, 2,048 entries, 200,000 XML elements and depth 256.
  Duplicate/ambiguous/traversal part names, malformed XML and DTDs/entities are
  rejected. The WebView only sees verified, regenerated ZIP metadata. Failed or
  cancelled preparation removes partial content and releases the cache lease.
- A unique local HTTPS origin serves only bundled assets and the owned snapshot.
  All other intercepted requests receive 403. CSP disallows remote images/fonts,
  frames, objects, form submission and inline scripts. Embedded HTML/altChunks
  are disabled; macros are not executed. Supported external links require a
  user gesture and open through Android. No document upload or service is used.
- Native pinch zoom, pan and text selection remain available. Initial layout fits
  document pages; the shared viewport binding saves only coordinates/zoom and
  restores after layout. The controller handles renderer death (one retry), a
  30-second render deadline and disposal; document resources are leased until the
  preview leaves composition. Unleased stale snapshots can be pruned on later
  previews. Unsupported/failed documents keep original file actions available.

## Source checkpoint evidence

**20 JVM checks passed, zero failures/errors/skips:** six new `DocxPackageTest`
cases plus seven `ChangesPreviewFilesTest` and seven `ArtifactPreviewFilesTest`
regressions. Cases cover exact Unicode contents/CRC/length reconstruction,
snapshot independence, archive/part/expansion/count limits, XML/entity/complexity
rejection, path validation, cancellation cleanup and route precedence. Main code
compiled in the same 30-second invocation. Instrumentation compilation passed in
5 seconds; JavaScript syntax and all 16 asset hashes passed.

`DocxPreviewRuntimeTest` is **compiled but not run** at this checkpoint. Its small
synthetic fixture contains styled Unicode text, table, embedded red PNG,
header/footer, footnote, two pages, external/active links and an HTML chunk. It
checks rendered content, blocked active/network content and actual painted image
pixels, retaining `docx-rich-preview.png` on success. This is a first render gate,
not a substitute for the broader matrix or Pixel acceptance.

The subsequent integration below closes this first render gate. Next: add/
exercise Word zoom and saved-position restoration with visible content, then
check real Files/Changes/attachment paths and actions on the Pixel.
No APK, emulator, physical-device install or signed promotion was performed for
this source checkpoint.

## XLSX and shared Office reader — 2026-10-06

The shared Kotlin implementation is now `OfficePreviewPackage`,
`OfficeFilePreview` / `OfficeWebController`, and `OfficeReaderState`. Word and
workbooks use separate bundles and asset allowlists, retaining the same bounded,
owned ZIP preparation and local request isolation. Their exported originals are
unchanged. Word's previous archive/route tests remain in `DocxPackageTest`.

Rechecked iOS `ChatArtifactQuickLookView.swift` and the model's `.quickLook`
branch at the scoped revision above: cmux hands the temporary file to a system
`QLPreviewController`, with a runtime admission check. The source does not define
a custom workbook UI, so our sheet/grid controls still require comparison against
the actual iOS Quick Look surface.

XLSX now routes to the workbook reader in Files, Changes and composer attachments.
The vendored, unmodified **SheetJS Community Edition 0.20.3** bundle comes from
its [official distribution](https://docs.sheetjs.com/docs/getting-started/installation/standalone/).
The manifest records the exact distribution URL and SHA-256; all 17 pinned viewer
assets are now checked by the existing verification script and milestone CI.
The package license is exposed in Settings. No npm runtime dependency or remote
document service was added.

Implemented behavior:

- Visible worksheet selection, excluding hidden/very-hidden sheets, with actual
  row/column coordinates and hidden-row/column handling.
- Saved author-formatted cell values, including currency, percentages, dates,
  errors and cached formula results. Missing formula caches show the formula
  text. Formulas/macros are never executed and external data is never fetched.
- Merged cells, including merged regions crossing a displayed window or hidden
  rows/columns; malformed/overlapping merges fail the affected range.
- Font size/family/bold/italic/underline/strike, supported font/fill colors,
  alignment/wrapping, column widths, row heights and ordinary cell borders.
  Cell style IDs and borders are read from the verified workbook XML; SheetJS's
  pinned style/value model supplies the other properties.
- At most 100 visible rows × 32 visible columns enter the DOM at once. Previous/
  next controls and a cell-address jump can reach the entire declared range,
  including the last Excel row/column, without allocating a giant HTML table.
- Internal worksheet/cell links and gesture-gated external HTTP(S)/mailto/tel
  links. Unsupported active links render as plain cell text. Workbook strings
  enter the DOM via `textContent`, not generated HTML.
- Native zoom/scroll/selection, selected sheet/window and viewport restoration;
  only bounded coordinates are saved, not workbook contents. The workbook starts
  at normal text scale while Word retains page-fit behavior. Runtime restoration
  evidence is still pending.

At this checkpoint the reader did not render charts, drawings/images, conditional
formatting, pivots or every Excel style variant. Rich text runs were subsequently
implemented below, and the picture batch above now projects embedded raster images.
Unsupported charts/other objects still display an Open-original notice.
The large-sheet window controls
are an Android implementation choice whose UI/interaction parity is unverified.
These gaps are tracked as remaining work, not unavoidable platform differences.
At this checkpoint legacy XLS, XLSB, XLSM, ODS and Numbers were not newly
advertised merely because the parsing library can read them. ODS is now routed
by the later implementation below; the other formats remain open.

Checkpoint verification: **23 JVM checks passed** (three location/routing cases
plus the 20 previous archive/file cases), with zero failures/errors/skips. **Ten
Node model tests passed on Node 22.16 and 26.8** using a real XLSX fixture and the shipped SheetJS bytes.
They cover styles, date1904, number formats, formula caches/errors, literal cell
text/unsafe links, hidden content, merge clipping/rejection, bounded huge ranges
and reaching the final fixture cell. The existing milestone CI glob includes
the new Node test file.

Main code compiled with the JVM checks in 31 seconds. Android instrumentation
compilation passed in 26 seconds after correcting a test-only
`StateRestorationTester` import. Both reader adapters passed JavaScript syntax
checks. No APK was built and no emulator was started at this checkpoint.

`WorkbookPreviewRuntimeTest` was queued with the Word case at this source
checkpoint and has now passed in the integration below. It checks formatted DOM and painted title pixels, internal sheet links,
cell jumps and restoration after composition state recreation, and saves
`workbook-formatted.png`. Its passing result and inspected capture establish
these fixture rendering/restoration behaviors. Broad physical and actual iOS
Quick Look comparisons remain open.

## Office viewer integration

2026-10-06: **both Android runtime cases passed** on the sole reused
`cmux_api37_16k` AVD (API37, arm64, 16 KiB pages). The initial pair completed in
**21.653 seconds**, with zero skips. Word's image pixels, styled Unicode text,
table, headers/footers, footnote and two pages rendered; the fixture's active
link, embedded HTML and external fetch were blocked. Workbook formatting,
hidden content, sheet-link navigation, final-cell jumps and selected-sheet/
window restoration across composition state recreation passed.

Screenshot inspection caught a real workbook layout defect despite the first
green run: automatic table sizing shrank authored column widths and wrapped
currency/dates. The table now has an explicit sum of column widths, and cells
wrap only when their saved style requests it. The regression checks the fixture's
108 CSS-pixel currency column and exactly one rendered text line, and waits for
the native loading indicator to disappear before capture. The first strengthened
assertion incorrectly expected 110 pixels; the fixture/parser proved 108, and
the corrected workbook case passed in **13.068 seconds**. This is **two distinct
runtime cases**, with a targeted workbook rerun, not three independent cases.

Inspected `docx-rich-preview.png`, the initial workbook capture and corrected
`workbook-formatted.png`. The corrected title, currency, date and total are
legible on single lines. The captures use `FilePreviewContent` in a fixture
Activity; they do not prove actual Files/Changes/attachment routing, process-death
recovery, physical gestures, accessibility, or matched iOS screen layout.

Builds: 70-second combined app/test APK build, 29-second layout-fix app/test build
and 16-second test-only assertion correction. All 17 pinned assets matched
inside the final app APK, and the packaged local workbook CSS/JS matched source.
No recorded app crash/ANR markers. Evidence and original failure output are under
ignored local `captures/runtime/office-integration/`.

Final local debug APK SHA-256:
`5725209c13fe3c17159e3ac81c0deb8419344d70ab48c7f002b335c8d4e0d861`.
Test APK SHA-256:
`1ef96228b81ff98449a466210c17f7dcada10b56039d4bb97d64cc754690e2bc`.
Gradle was stopped before each emulator run; the existing AVD's awake setting was
restored and its process stopped/reaped afterward. No new AVD, physical Pixel
install or signed promotion. Format/layout gaps listed above remain open.


## Workbook rich text — 2026-10-07

Rechecked `ChatArtifactPreviewRouter.swift` at scoped upstream
`186cec79781256867ad4516f0802118738bd2393`: content recognized through UTType is
still a Quick Look candidate after the image/PDF/media/Markdown/text routes.
This does not establish the current App Store renderer's output or advance the
project's global parity pin. The Android workbook reader previously flattened
all text within a cell to one style; it now preserves supported authored runs.

The implementation reads shared-string and inline-string XML from the already
validated Office package. It follows the SpreadsheetML
[shared-string structure](https://learn.microsoft.com/en-us/office/open-xml/spreadsheet/working-with-the-shared-string-table)
and [run properties](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.spreadsheet.runproperties?view=openxml-3.0.1).
Only direct SpreadsheetML run/text/property children are projected; phonetic and
foreign markup are excluded. Text enters spans through `textContent` and typed
CSS properties, never generated HTML. The reconstructed run text must equal the
existing formatted cell value; otherwise that value remains the plain fallback.
OOXML escaped literals are decoded once. No network or dependency was added.

Supported run overrides include bold/italic, explicit false values, underline,
double underline, strike, font name/size, supported RGB/theme/indexed colors,
and superscript/subscript. Missing properties inherit the cell presentation.
Decorations are placed on the text spans so explicit underline/strike removal
can actually take effect. Existing hyperlink admission and internal navigation
remain shared by rich and plain labels. Accounting underline uses ordinary
underline placement; advanced font/theme variants and exact Quick Look layout
still require comparison. Charts, drawings, conditional formatting, pivots and
other formats remain open work.

The reader limits a cell to 4,096 runs and a displayed range to 16,000 spans.
Beyond those limits the saved cell text remains readable without run formatting.
The existing 100-row/32-column window remains in effect. No workbook contents
are added to saved Android state.

Verification: **15 Node checks passed**, zero failures/skips. Five new cases
cover literal text/whitespace, excluded phonetic/foreign markup, explicit style
removal, font/color/vertical properties, one-pass escaping, limits and a real
XLSX containing shared and inline runs. JavaScript syntax and all **17 pinned
vendor asset hashes** pass. The deterministic fixture generator is
`scripts/generate-workbook-rich-runs-fixture.py`; it derives `rich-runs.xlsx`
from the existing authored fixture, leaving the baseline untouched.

The expanded `WorkbookPreviewRuntimeTest` **passed in 7.703 seconds** on the
existing API 37 / 16 KiB AVD. It uses the production Office preparation and
WebView path, checks real parsed run styles and literal script-looking text,
verifies red/green glyph pixels, follows a rich-text internal link, and restores
the selected sheet and final cell after Compose saved-state recreation. Existing
currency, column width, title pixels and hidden-row checks also passed. The
rich-run screenshot was inspected. This is generated-fixture evidence, not
physical Pixel/Mac, process-death or iOS Quick Look comparison evidence.

Builds succeeded; local logs and screenshots are under
`captures/runtime/workbook-rich-runs/`. A System UI startup ANR was dismissed
before testing and retained in the event baseline. No new ANR/crash appeared
during the check; the crash buffer was empty. Gradle and the sole emulator were
stopped, and the emulator process was reaped. APK SHA-256:

- Debug: `995998754418d82a075043439e7ca1ee38c529e27c8061569bdb4248e28d80ef`
- Test: `adb9c1a192a4eeb53a292d6c786e0eed59ef1193a489808ce3e7617acfa527d9`

No signed release changed. Full format and physical parity remain unverified.

## Offline PowerPoint preview — 2026-10-07

Rechecked `ChatArtifactPreviewRouter.swift` at scoped cmux revision
`186cec79781256867ad4516f0802118738bd2393`: recognized non-text content such as
PPTX is a Quick Look candidate, followed by runtime admission. Android's shared
Files/Changes/composer preview now routes PPTX extension or MIME to an offline
presentation viewer, preserving image/PDF/media/text precedence and original-file
Open/Share/Save. Legacy PPT and Apple Keynote remain external; this is not a claim
that all Quick Look formats are implemented.

The unmodified standalone browser bundle of
[`@aiden0z/pptx-renderer` 1.3.0](https://github.com/aiden0z/pptx-renderer/tree/0cf5c194b4db2cf9f2531b6a7e24a61338caabf1)
is pinned by npm SHA-512 integrity and asset SHA-256. It includes JSZip, ECharts
and its font decompressor. Apache-2.0, MIT, MPL-2.0 and ECMA notices/source links
are available in Open-source licenses. All 18 vendored viewer assets are covered
by the source and packaged-APK verification scripts.

The reader renders one slide at a time, with previous/next controls, a validated
slide-number jump and in-deck links. The existing native WebView zoom/selection
and viewport binding are reused. Only a bounded slide index and viewport enter
saved state; older eight-field workbook locations still restore. Text, shapes,
images, tables and charts are handled by the renderer; the exact feature scope
and fidelity depend on the deck. Slide/node errors expose original-file actions
instead of claiming complete rendering. No document service or upload is used.

The existing Office ZIP snapshot checks apply before parsing, with additional
JavaScript entry/expanded/media limits, two concurrent reads and deferred slide
parsing. One unique local origin serves only bundled assets and its owned ZIP;
remote requests, frames, objects and workers are blocked. Embedded bitmap/font
URLs are allowed. The optional PDF.js fallback is disabled, so EMF content that
requires an embedded PDF preview remains unsupported. Full EMF/WMF, animation,
video behavior, notes, exact fonts and uncommon OOXML features require further
comparison; these are open format gaps, not unavoidable platform differences.

### PowerPoint verification

**25 JVM checks passed** (Office state/routing 5, ZIP preparation 6, Changes file
snapshots 7, artifact snapshots 7), and **five signed-verifier Python checks
passed**. All 18 vendored hashes matched both source and the tested debug APK;
the packaged PowerPoint adapter HTML/CSS/JS also matched source.

The initial three-format Android batch passed in **38.838 seconds**: PPTX,
DOCX and XLSX. The generated, self-authored PowerPoint fixture exercises Unicode
text, an embedded red image, a table, a two-column chart, internal links, rejection
of a JavaScript link/remote fetch, previous/next and validated slide jumps, and
selected-slide restoration with Compose's saved-state harness.

Inspection found the initial screenshot helper could capture a previous slide
or loading frame despite the new DOM assertions. The test now waits for the
WebView visual-state callback and two frame callbacks before capture, and verifies
painted colored pixels inside the chart canvas in addition to the image pixel
check. The strengthened PPTX case passed in **20.871 seconds**. Final screenshots
visibly show the red image, the table and Q1/Q2 bars at 100/175, and restored
slide 3. No production change was needed for that screenshot timing correction.
The initial captures/logs are retained rather than presented as final evidence.

Builds: 1m 30s for main/test APKs and JVM checks; 20s and 25s for test-only
strengthening. The sole existing API 37 / 16 KiB AVD ran headless at 1,536 MiB.
Both boots and test runs have empty crash/ANR event logs. Emulator and Gradle
were stopped, and no signed release was published. Evidence, fixtures, JVM XML,
APK hashes and screenshots: ignored `captures/runtime/presentation-preview/`.

This proves these fixture behaviors in the shared preview component. Actual
Files/Changes/composer remote routes, OS process death, physical zoom/selection,
TalkBack, broad format fidelity and matched iOS Quick Look screenshots remain
open. Neither passing DOM checks nor this small deck establish full PPTX parity.

## OpenDocument spreadsheets — 2026-10-07

ODS files now enter the shared workbook preview by extension or normalized MIME,
with existing image/text/PDF precedence preserved. This reuses the pinned,
unmodified SheetJS 0.20.3 bundle and the existing local-only document origin;
no new library, remote document service or original-file rewrite was added.
The official [format documentation](https://docs.sheetjs.com/docs/miscellany/formats/)
describes ODS support as focused on data extraction, not complete formatting.

The viewer handles the ODS model without assuming OOXML package directories.
It shows saved values (including number display and cached formulas), repeated
cells, merged regions, Unicode and internal sheet links. Existing sheet selection,
bounded range navigation, zoom/selection and saved sheet/window position apply.
Literal cell strings remain text and unsupported link protocols remain inert.
An ODS notice explains that some formatting, charts and drawings are omitted;
the existing original Open/Share/Save actions remain available.

ZIP admission now recognizes an ODS MIME part plus spreadsheet body and package
manifest. CRC/size/name/XML/DTD checks still apply. A streaming XML pass bounds
row/cell repeats, spans and repeated text before JavaScript decoding: up to
200,000 materialized cells and 67,108,864 UTF-16 code units of repeated
text/attributes under the default limits. Empty repeated tails retain coordinates without being charged as populated
cells. Legacy UOS aliases, ambiguous/invalid counts, overflow and oversize spans
are rejected. Failed admission releases the owned snapshot. The original file is
kept for export. Limits can exclude larger valid files and are not a parity claim.

`scripts/generate-ods-fixture.py` creates the self-authored deterministic test
workbook with two sheets, Unicode, currency, a cached formula, repeated values,
a merge, internal/blocked links and XML-looking literal text. No user documents
or third-party workbook content are used.

At this initial checkpoint the remaining ODS work included authored formatting
and hidden content (partly addressed below), charts/drawings, encrypted files,
broader LibreOffice variants, real Files/Changes/attachment routes, OS process
recovery and matched iOS/Pixel acceptance. These are unfinished implementation
and verification, not declared Android platform limitations.

Verification for this batch: **17 JVM cases** (six ODS admission/routing, six
existing Office-package and five reader-state cases), **17 Node model cases**
(including two ODS cases) and **two Android workbook cases passed in 21.690 s**,
with no skipped cases. Both debug/test APK builds passed; a final 16-second
incremental build included the completed repetition checks after the initial
2m 40s integration build. All 18 vendor hashes matched the debug APK, and both
modified workbook adapters matched packaged bytes.

The Android run covered ODS displayed values/merge/repeated cells, literal text,
blocked links/network requests, internal-link navigation and saved-state reload
of sheet/window, plus the existing XLSX rich-format/range-restoration regression.
Paint checks waited for WebView visual state and checked ink on the displayed
cells. The two ODS screenshots were inspected: the first shows the merged title,
currency and repeated rows; the second shows Details at B2 after restoration.
Long text is clipped by the existing fixed-width cell presentation, so the latter
screenshot alone does not prove that the complete cell string is visible; the
DOM assertion separately verifies its value. These are component/saved-state
fixtures, not OS process death or a live remote-file route.

The sole existing API37/16KiB emulator ran headlessly with 1536 MiB. Crash/ANR
logs were empty; Gradle and the emulator were stopped and reaped. Evidence and
APK hashes are in ignored `captures/runtime/ods-preview/`. No physical-device
install, new AVD, signing change or signed release was produced.

## ODS visibility and presentation metadata — 2026-10-07

The bundled SheetJS parser does not populate ODS visibility, row/column dimensions
or most cell-style metadata. The local viewer now projects these from the already
validated `content.xml` and `styles.xml`, using the bundled CFB ZIP reader and
browser XML parser. This is an app-owned adapter; vendor bytes remain unchanged.
The implementation follows the [OASIS ODF 1.3 schema](https://docs.oasis-open.org/office/OpenDocument/v1.3/OpenDocument-v1.3-part3-schema.html),
including sections 19.619 (cell defaults), 19.621 (group visibility), 19.754
(row/column visibility) and 20.416 (table display).

- Hidden sheets are omitted from the picker, restored hidden sheet indices fall
  back to a visible sheet, and links cannot open hidden sheets.
- Collapsed/filtered rows and columns and hidden row/column groups are omitted
  while retaining original coordinates. Repeated definitions stay as intervals;
  navigation skips an entire hidden interval without expanding every row.
- Named, automatic, inherited and default styles are resolved by family. Explicit
  cell styles take precedence over row defaults, then column defaults. Cyclic or
  excessively deep inheritance fails the preview instead of looping.
- Supported presentation includes ordinary font size/family/weight/style, text
  color, cell fill, underline/strike, alignment/wrapping, simple borders, absolute
  row heights and column widths. Typed color/length/enum values are mapped to
  allowlisted CSS properties; document markup and arbitrary CSS are not injected.
- The existing viewport state and range navigation remain shared with XLSX.
  Restoring a preview reloads its metadata before displaying cells. Paging now
  seeks visible coordinates in both directions and disables controls when only
  hidden tail rows/columns remain. Previous paging can cross a large hidden
  interval directly instead of landing on successive empty ranges.

The fixture generator now also creates `open-document-styled.ods`, containing
an inherited white-on-blue heading, absolute dimensions, filtered repeated rows,
a hidden column/group/sheet and a row-default footer style. Basic presentation
support does not close the complete ODS format gate: rich text runs, percentage
font inheritance, automatic/minimum row sizing, advanced borders and formatting,
charts/drawings, encrypted documents and broad LibreOffice/Quick Look comparisons
remain unfinished. The earlier clipping and physical/live-route limits still apply.

Verification: **17 JVM checks** and **25 Node checks passed**. The initial
48-second debug/test build was followed by **three passing Android cases in
38.306 s**: styled ODS visibility/dimensions/inheritance/restoration, original ODS
data/link/restoration and XLSX rich-style/range restoration. The styled screenshot
was inspected and shows the inherited white-on-blue heading, correctly sized
columns, missing filtered rows/hidden column/group, and green row-default footer.
That boot had a Play services ANR before instrumentation and no new test-time
crash/ANR event.

Screenshot review also found the Next columns button enabled when only a hidden
column remained. Paging was corrected, a regression for hidden tails and reverse
paging over a million-row hidden interval passed in Node, and the Android fixture
now asserts the final disabled state. The final debug/test build passed in 18s.
All 18 vendor hashes and the four workbook shell/adapter files matched the final
APK. **The final Android recheck is pending:** two attempts on the second boot
ended with `Process crashed` before any test started. Event logs show app startup
ANRs along with startup ANRs in system/Google apps and activity-service dump
timeouts. The second attempt followed confirmed termination of the first and
used unchanged APKs. These failures do not establish that cold startup is healthy
or that the final paging fix has passed on Android.

Evidence is retained in ignored `captures/runtime/ods-presentation/`: initial
passing results/screenshot, final build and Node results, both failed attempts,
event/system logs and APK hashes. The sole existing emulator was stopped/reaped;
Gradle is stopped. No new AVD, phone install or signed release was produced. Next
run the already compiled `WorkbookPreviewRuntimeTest` against the final APK when
the emulator is stable (or use the physical-device acceptance path), then continue
the broader format/real-route/Pixel gates.

Follow-up verification on 2026-10-07: all three `WorkbookPreviewRuntimeTest`
cases passed with the final paging correction during the combined SSH scrolling
run. The inspected styled ODS screenshot shows the disabled next-column and
next-row controls, authored dimensions/colors and omitted hidden content. The
combined run took 53.122 s and had two SSH scrolling failures; those are recorded
separately and do not turn this into an all-green run. Workbook cases each
returned success. Evidence: `captures/runtime/ssh-pixel-scroll/runtime.txt` and
`ods-styled.png`. This closes the pending ODS fixture recheck, not the physical,
live-route or complete format-fidelity gates.


## ODS rich cell text — 2026-10-08

The shared workbook viewer now projects nested ODF text spans, paragraph breaks,
explicit spaces/tabs/line breaks and per-span links from validated `content.xml`.
Named/inherited text styles are layered on the existing cell/row/column style.
Bold, italic, colors, font properties and explicit decoration resets survive
within a cell. Multiple links keep their own destinations; surrounding text and
blocked link schemes remain plain. Internal links use existing sheet/range
navigation and saved position. Numeric/formula values remain the saved display;
no formula execution or remote data fetch was added.

Whitespace follows ODF 1.3 section 6.1.2 across span boundaries, with explicit
text:s/tab/line-break interpreted separately; nested spans and links follow
6.1.7–6.1.8 in the [OASIS schema specification](https://docs.oasis-open.org/office/OpenDocument/v1.3/OpenDocument-v1.3-part3-schema.html).
Annotations and ruby glosses do not enter the cell body. Unsupported fields,
overdeep rich content or exceeded rich-text limits retain the existing parser's
saved-value fallback. The renderer uses text nodes and typed, allowlisted styles;
no document HTML/CSS is inserted. Vendor assets remain unchanged.

Repeated cells share projected metadata. Rich-text limits are 4,096 runs and
1 MiB of text per authored cell, 200,000 projected runs per workbook and the
existing 16,000 rendered runs per visible range. These are preview limits, not
claims of complete ODS fidelity. Percentage font inheritance, automatic/minimum
row sizing, advanced borders, field-specific rendering, charts/drawings and
matched Quick Look/physical acceptance remain open.

Internal ODF cell fragments are translated to the shared address form, including
quoted/dotted sheet names, URI escapes, absolute coordinates and current-sheet
references. Runtime testing also exposed the bundled parser skipping self-closing
empty rows. The adapter gives those rows explicit closing tags in its in-memory
validated archive and reparses through the same workbook validation. Original
files and vendor code remain unchanged; subsequent values keep their authored
coordinates. The rich fixture deliberately retains the empty row so its link
must land on B2 after restoration.

Verification: **31 Node checks and four Android workbook cases passed** (Android
21.488 s). The batch covers rich ODS styles/whitespace/links/restoration, existing
ODS data/visibility/dimensions and XLSX rich-style/navigation restoration. The
painted rich-cell screenshot was inspected. Initial Android runs caught the ODF
link-address mismatch and skipped empty row; both failures are retained with the
final pass in ignored `captures/runtime/ods-rich-text/`. No crash/ANR events were
logged. The sole API37 AVD used 1,536 MiB/two cores; emulator and Gradle stopped.
No physical-device verification or signed release; APK 616 remains published.
