# Content formats and offline Word preview

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
| XLSX | Quick Look candidate, runtime admission | **New offline workbook reader**, sheet selection and bounded grid navigation | Runtime formatting/navigation/restoration and Pixel checks; charts/drawings/conditional formatting and exact Quick Look layout remain open |
| Legacy DOC, RTF, XLS, ODS, PPTX, Pages, Keynote, Numbers and other Quick Look content | Quick Look candidate when recognized, runtime admission | External Open/Share/Save fallback | In-app format implementation and matched format checks remain open; these are not declared unavoidable platform differences |
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

Next integration batch: run that case on the existing API37/16 KiB AVD, inspect
the capture, add/exercise zoom and saved-position restoration with visible
content, then check real Files/Changes/attachment paths and actions on the Pixel.
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

The reader does **not** yet render charts, drawings/images, conditional formatting,
pivots, rich text runs or every Excel style variant. A workbook containing charts
or drawings displays an Open-original notice. The large-sheet window controls
are an Android implementation choice whose UI/interaction parity is unverified.
These gaps are tracked as remaining work, not unavoidable platform differences.
Legacy XLS, XLSB, XLSM, ODS and Numbers are not newly advertised or routed merely
because the parsing library can read them.

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

`WorkbookPreviewRuntimeTest` is queued with the Word case for the next integration
batch. It checks formatted DOM and painted title pixels, internal sheet links,
cell jumps and restoration after composition state recreation, and saves
`workbook-formatted.png`. It must pass and its capture must be inspected before
claiming visible workbook rendering/restoration. Broad physical and actual iOS
Quick Look comparisons remain open.
