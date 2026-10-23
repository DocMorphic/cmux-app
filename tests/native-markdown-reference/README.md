# Native Markdown reference

Run `python3 scripts/generate-native-markdown-reference.py /path/to/cmux` on
macOS with Command Line Tools. The generator reads five whole pure source files
from the exact Git revision in `References.json`, checks their hashes and compiles
them unchanged. It also extracts the unchanged `renderedInline` method from the
hash-guarded original native view file, without compiling that file's SwiftUI view.
No network request, UIKit/SwiftUI layout, or upstream Testing suite runs here.

The fixture records 32 document parses and 20 Foundation inline parses. Documents
cover every block kind, table boundaries, fences/unterminated code, Unicode number
markers, whitespace and Swift's CRLF Character behavior. Adjacent equal inline
runs are compared after coalescing. The original URL attributes remain in the
fixture; the Android test explicitly retains its existing representable-link
policy rather than claiming every native URL scheme is implemented.

`NativeMarkdownDocumentTest` uses the existing ICU4J test dependency for numeric
grapheme classification. Production uses Android ICU, also exercised with Arabic,
CJK and keycap markers in the Android runtime test. Whole full-transport-sized
paragraph/code tests verify that the 8 KiB timeline preview cap is not reused.
The result does not establish matching platform Unicode behavior for every
possible input or native font/layout/accessibility/physical-host parity.

The production Feed formatted reader uses a native lazy list of selectable block
rows, with per-row horizontal code/table scrolling. Parsing and inline preparation
run off the UI thread; lifetime-bound jobs cannot update retired rows. Saved state
contains coordinates/selection only, and native hierarchy saving is disabled to
keep the message out of the Activity Bundle. Matched original iOS UI, true process
death/Activity recreation, reading reflow, TalkBack, older APIs, real source/link
routes and physical Mac/Pixel acceptance remain separate gates.
