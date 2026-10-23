# Markdown policy reference

Run `python3 scripts/generate-markdown-policy-reference.py /path/to/cmux` on
macOS with Command Line Tools. The generator reads five whole source files from
the exact Git revision in `References.json`, verifies their SHA256 hashes and
compiles them unchanged with this small caller. It never calls the image fetcher
or opens a URL. Foundation/Network/Security are used from the local macOS SDK.

The resulting JVM fixture contains 30 actual native outcomes: navigation (12),
approval (7), exact approved-request scope (4), revocation (1) and placeholder
opening (6). `MarkdownViewerPolicyTest` compares Android against those results.
The caller is not upstream's Testing suite, a UIKit/WebKit host, an Android
runtime or a live network acceptance test. Global source pins do not change.

The separately hash-pinned Android asset manifest contains all twelve unchanged
shared Markdown assets, including the current shell and sanitizer. Android's
controller owns its native approval record across rerenders/theme changes and
retires it with the shell. Its WebView navigation adapter uses Android's
`hasGesture` and frame flags; the pure source decision vectors do not establish
equivalence of every Android/WebKit navigation classification.
