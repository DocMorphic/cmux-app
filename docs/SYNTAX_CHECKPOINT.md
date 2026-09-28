# Raw syntax checkpoint evidence — 2026-09-28

Portable record of the last local run before the laptop handoff. This is a
summary of observed results, not replacement raw logs or a new test run.

## Subsequent Windows result, reported on return to Mac (2026-09-28)

The user relayed the other Codex session's results after commit `9849010`:
308 JVM cases and four helper cases passed; debug/test APKs built; all 14 vendor
hashes matched both the checkout and debug APK. The four outstanding Android
methods returned **OK (4 tests) in 94.197 seconds**. The original Windows logs
and screenshots were not transferred with that commit, so this is an attributed
result, not a new Mac run or independently inspected Windows evidence.

The Windows screenshot reportedly showed a blank Mermaid diagram despite passing
DOM assertions. A stronger pixel test and runner changes remained uncommitted on
Windows, and their follow-up was interrupted by System UI ANR. The Mac pulled
`9849010` and is independently adding visible label/bar checks; visual acceptance
remains open until those pass and the actual screenshot is inspected. The older
Mac screenshot at `captures/artifacts/markdown-rendered.png` does show both nodes
and chart bars, which does not disprove the reported Windows rendering failure.

The original results below are retained as history. Physical Pixel/Mac acceptance
and publication of an APK newer than build 157 remain outstanding.

## Resumed Mac visual gate (2026-09-28)

The recreated Markdown check inspects screenshot pixels inside both Mermaid
label bounds and both Vega bar bounds, requires foreground window focus, and
retains the last frame and geometry even on failure. The renderer is unchanged.
The runner supports selecting one case, reads the full window dump for focus,
removes stale fixture captures, and retains failed-run evidence.

Debug/test APK assembly succeeded in 23 seconds; all 14 vendor asset hashes
matched inside the APK. The focused emulator run **failed** in 88.833 s: a System
UI ANR modal covered the rendered document, and the focus/paint gate timed out.
The saved screenshot was inspected and confirms the modal. Evidence:
`captures/handoff/20260928T111334Z/`. No clean visual rerun is claimed.

The Pixel 6a connected and authorized ADB (Android 17); debug/test APKs were
installed. Its first run stopped at preflight because the screen was locked/off,
before any test executed. The user was asked to unlock it. Evidence:
`captures/handoff/20260928T111736Z/`. This is not a physical fixture pass or a
native Mac connection.

## Physical Pixel fixture acceptance (2026-09-28, 11:32 UTC)

After the user unlocked the Pixel 6a (Android 17/API 37), all four handoff
methods completed: **OK (4 tests), 10.981 seconds**. Evidence is retained in
`captures/handoff/20260928T113205Z/`, including instrumentation output, result
metadata, fresh screenshots and Markdown paint geometry. All three screenshots
were visually inspected: syntax spans and numbered text are visible; Mermaid
shows both Phone/Mac labels and the connecting arrow; Vega shows both blue bars.
Label ink counts were 1,117/829 pixels and bar counts 12,267/24,104. The clipboard
preview overlays the lower text capture, not the diagram/chart bounds.

The runner recorded HEAD `82cf4c7`; installed APKs were assembled earlier from
`9849010` plus the stronger visual test/runner changes later committed as
`5c08be3`. The subsequent signing codec/native pipeline was not in these APKs.
Debug APK SHA-256: `97833079fd43f18437f8f639bd526d76669947a48b44578118e714fa421f93a7`.
Test APK SHA-256: `ae5939ac5cff3216b7e1195da640619012b6774c69bf69ff908b76db1cfe1e6f`.
Temporary USB keep-awake was restored to its original value (0) after the run.

This closes the four fixture reruns and proves visible rendering on this Pixel.
It does not identify the Windows blank-diagram cause, establish a native Mac
connection, or publish a new signed APK. Earlier failures remain below as history.

## Build and static verification

- Full JVM XML results re-read during handoff: 51 report files, **307 tests,
  0 failures, 0 errors, 0 skipped**.
- `/tmp/cmux-syntax-build.log`: successful full JVM/debug/test APK build (53 s).
- `/tmp/cmux-syntax-pill-build.log`: latest debug/test APK assembly successful
  (21 s), including the five-case syntax fixture and status pill.
- All 57 upstream extension mappings matched; all 35 language IDs were present
  in the 192-language highlight.js 11.11.1 bundle. Three vendored asset hashes
  matched the pinned source manifest.
- No production/test source changes were made after that last APK assembly before
  handoff. Documentation and provenance packaging do not establish new runtime
  verification. No signed APK was published for this change.

## Android results

First launch failed before cases ran: BIND APPLICATION ANR. Cold boot then ran
the three classes below on Android 17/API 37. The cold-run log has **no terminal
instrumentation result**, so the run must not be described as a successful suite.

| Class | Method | Recorded result |
| --- | --- | --- |
| NativeArtifactSyntaxTest | pinnedEngineSupportsHaskellPureScriptXcodePalettesAndAutomaticDetection | PASS |
| NativeArtifactSyntaxTest | realViewerAutomaticallyHighlightsPureScriptWithNativeSpans | PASS |
| NativeArtifactSyntaxTest | activeMarkupQuotesCrLfAndUnicodeRemainLiteralCode | PASS |
| NativeArtifactSyntaxTest | nativeLateColoringPreservesSelectionSearchFontViewportAndClipboard | FAIL: null primaryClip at clipboard assertion |
| NativeArtifactSyntaxTest | oversizedFileExplainsHighlightingLimitAndKeepsRawTextAvailable | PASS |
| NativeArtifactTextTest | wrappingFontAndPinchPersistPerTextKindAcrossFiles | PASS |
| NativeArtifactTextTest | searchWrapsAndJumpsToMatchesAndLineControlsMoveActualViewport | FAIL: visible gutter pixel wait |
| NativeArtifactTextTest | selectionAndCopyContentsPreserveNewlinesUnicodeAndExcludeLineNumbers | FAIL: clipboard expected text, received null |
| NativeMarkdownPreviewTest | renderedMarkupCannotExecuteScriptsOrLoadRemoteImagesBeforeConsent | PASS |
| NativeMarkdownPreviewTest | largeMarkdownKeepsRawContentAndDisablesRenderedMode | PASS |
| NativeMarkdownPreviewTest | sharedRendererDisplaysTablesCodeMermaidAndVegaAndSwitchesToRaw | STARTED; no recorded result |

Totals: **7 passes, 3 failures, 1 incomplete**. Instrumentation status 0 marks
each listed pass; -2 marks each failure. The foreground screenshot showed a
System UI ANR modal covering/dimming the app. This is a plausible common cause
of the clipboard and pixel failures, **not a verified diagnosis**. Dismiss any
system modal on a responsive test device and rerun the four affected methods.
Do not weaken assertions or count pre-clipboard assertions as a whole-test pass.

The oversize fixture declares >1.5 MB but contains small text. It verifies policy
and UI, not large-file runtime performance. Renderer-crash/cancellation behavior
has not been fault-injected. Physical Pixel/real Mac acceptance is outstanding.

## Original evidence locations (old laptop only; ignored by Git)

- `/tmp/cmux-syntax-cold-runtime.log`: original partial run and failure stacks.
- `/tmp/cmux-syntax-runtime.log`: pre-test process crash.
- `/tmp/cmux-syntax-{build,compile,fixture-build,pill-build}.log`: builds.
- `captures/artifacts/syntax-instrumentation-exit-info.txt`: startup exit reason.
- `captures/artifacts/syntax-webview-logcat.txt`: renderer diagnostic evidence.
- `captures/artifacts/syntax-cold-screen.png`: inspected ANR-modal screenshot.
- `captures/artifacts/syntax-system-dialog.xml`: empty; dump never yielded UI.
- `captures/artifacts/artifact-syntax.png`: invalid 58-byte missing-file error;
  not screenshot evidence. Recreate after the late-coloring fixture passes.

All old emulator/instrumentation processes were gone at the handoff inspection.
The Git clone carries reproducible fixtures and this result record, not the old
ignored evidence files, emulator, APKs, credentials, or temporary clones.
