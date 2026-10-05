# PDF annotation parser

Dependency: `com.tom-roush:pdfbox-android:2.0.27.0`, the version documented by
[PdfBox-Android](https://github.com/TomRoush/PdfBox-Android). Source tag
`v2.0.27.0`: `45da92629dad5b3c9887eceefecc89a1423f5457`.
Maven Central AAR SHA-256: `30277f879cfd571db2a137582c95516a0d4ea6778e945519bc58ca93d57d88c7`.
License and NOTICE are retained here and in the app's Open-source licenses.

`PdfAnnotationLinks` reads link metadata: direct destinations, named destinations,
GoTo actions and permitted external URIs. It does not invoke examples, extract
embedded files, run scripts, render with PDFBox, or load fonts. Android PdfRenderer
continues to render pages and provide text/search. The platform link extractor
only admits GoTo actions in `Page::IsGotoLink`, omitting direct `/Dest` annotations:
[AOSP source](https://android.googlesource.com/platform/packages/providers/MediaProvider/+/refs/heads/main/pdf/framework/libs/pdfClient/page.cc).
The retained runtime fixture exercises that omitted representation unchanged.

Bouncy Castle's old `jdk15to18` transitives are excluded; the app's `jdk18on` family
is consistently pinned to 1.86. Apache's current listed 2026 path-traversal issues
are in the embedded-file extraction **examples** module, which is not used here;
see [Apache's advisory scope](https://pdfbox.apache.org/security.html).
This is not a general assertion that arbitrary PDFs are cheap or safe to parse.

The parser opens only the app's existing private preview file. Its scratch store
uses up to 8 MiB of memory and 128 MiB total backing storage, in the preview's
private parent directory. The document owner closes both parsers. A metadata
failure retains platform link fallback and reports incomplete links. Bounds use
crop origin, rotation and renderer dimensions; destinations use the target page's
geometry. X/zoom navigation, password-protected PDFs, full text-selection parity,
large/hostile-document resource acceptance and older-Android runtime verification
remain separate completion gates.
