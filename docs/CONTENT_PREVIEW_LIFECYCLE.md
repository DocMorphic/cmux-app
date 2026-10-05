# Changes preview lifecycle

## PDF navigation and image transform restoration — 2026-10-05

Short final PDF pages now get enough trailing layout space to reach the top of
the continuous viewer. Next/Previous and the page label therefore follow the
selected page instead of remaining on a sliver of the preceding page. The
spacing is recalculated from the viewport and final page dimensions.

Zoom and pan are now saved per artifact. Pan uses bounded viewport fractions
rather than storing old pixel dimensions. Real touch testing exposed a separate
one-finger pan failure in the existing gesture path: double-tap zoom worked but
the drag did not move the image. The viewer now claims touch transforms in the
initial pointer pass, after the gesture crosses touch slop. At minimum zoom it
leaves one-finger swipes to the containing file pager. Foundation's transform
modifier remains for its non-touch input handling; keyboard/mouse acceptance was
not part of this run.

**Seven Android checks passed in 79.850 s**, covering:

- Short/deleted PDF: select page two, verify `2 / 2` and disabled Next, then return
  to page one with Previous. Existing PDF rendering/base-revision checks pass.
- Four mixed-height pages (40, 40, 600, 80 points): navigate forward and backward
  through every page, check labels/visible destinations and boundary buttons,
  with one file fetch.
- A striped image: prove initial colors, double-tap zoom, one-finger pan,
  Activity recreation with the same zoomed/panned pixels, revision, artifact and
  fetch count; then reset, pinch out, reset again, and swipe into the next file.
- Retain the tall PDF page's `2 / 2` label, exact visible bounds and green pixels
  across recreation without another download. Closing the owner clears files.
- Existing retry, audio playback/release, image clipboard/export and extensionless
  image MIME checks.

Final screenshots inspected; no crash entries, no new ANR events during testing,
and unchanged device settings. The existing AVD was stopped/reaped. Each cold
boot had a System UI startup ANR before testing; the affected system component
was restarted and the fresh UI was checked before running. The first seven-test
attempt had one actual app gesture failure (pan) and six passes; its screenshot
and output are retained. The final pass follows the gesture fix.

Reference: `ChatArtifactPDFView.swift` at upstream
`186cec79781256867ad4516f0802118738bd2393`, path
`Packages/iOS/CmuxAgentChatUI/Sources/CmuxAgentChatUI/Artifacts/ChatArtifactPDFView.swift`,
SHA-256 `cdf21836a120538deb88667ee37a66b215d106f52107ece600e9b1a4f456eefc`.
That implementation uses PDFKit's vertical continuous mode and automatic scaling.
This scoped check does not establish every Quick Look/PDFKit behavior. The actual
Compose Foundation 1.9.1 source was also inspected from its
[official source artifact](https://dl.google.com/dl/android/maven2/androidx/compose/foundation/foundation/1.9.1/foundation-1.9.1-sources.jar)
while diagnosing consumed touch events. Global parity pins remain unchanged.

Evidence: `captures/runtime/viewer-navigation/` (source/APK hashes, failed attempt,
final instrumentation output, screenshots, device settings, event/crash logs,
upstream excerpt and source artifact). Signed **596** remains the verified APK;
this source has not yet been published in a signed build. No Pixel was attached.

**Still open:** browser-parent recreation, media position, text viewer and Save
picker restoration; PDF zoom and image focal-point behavior across aspect-ratio
changes; process death, accessibility and physical Pixel/Mac acceptance. The
short-PDF navigation and same-size image zoom/pan recreation issues described in
the earlier checkpoint below are now fixed and verified within the scope above.

## Retained binary preview — 2026-10-05

The Changes presentation now owns the selected Before/After revision, active
transfer, progress/error and private downloaded artifact. Activity recreation
reattaches to the same artifact instead of resetting the revision and downloading
again. The stable artifact key also lets the existing PDF scroll state restore.
Only the selected binary page loads content; neighboring pager items retain their
text-diff prefetch behavior. The owner retains one active artifact and at most 32
revision choices. It retains no Activity or view.

Selecting another file, returning to the file list, refreshing repository data,
forcing a diff refresh, or closing the presentation cancels the old transfer and
retires its private directory. Request identities prevent a late cancelled
transfer from publishing over or deleting its replacement. Retry creates a new
request; choices are revalidated against the changed-file policy.

### Reference and scope

Inspected `Packages/iOS/CmuxMobileChanges/Sources/CmuxMobileChanges/UI/FileDiffBinaryView.swift`
at upstream `186cec79781256867ad4516f0802118738bd2393` (SHA-256
`06a7858e203e966503b2908098ad3b3632e59bd5b5e74d9e9678e940981919a9`).
It binds the selected revision and passes it into the inline preview. This is a
scoped reference check, not an audit of every iOS preview cache/lifecycle path;
the global parity pin is unchanged.

### Verification

- **29 JVM tests passed:** retained controller 6, preview files 7, Changes store 7,
  content transfer 6 and routed sidebar Changes 3. Includes cancellation races,
  refresh, retry, revision policy, bounded choices and cleanup.
- **7 Android tests passed in 71.973 s** on the existing `cmux_api37_16k` AVD:
  new retained preview test, five existing image/PDF/audio/retry/clipboard tests,
  and the existing text-diff recreation/landscape/sign-out test.
- The new test was then strengthened after screenshot review showed that a
  partially visible second PDF page could satisfy the original pixel assertion.
  **The strengthened test passed in 28.727 s:** a tall second page retains the
  `2 / 2` label, exact visible bounds and green pixels across recreation, without
  another file fetch. The renamed image retains Before, the old path, red pixels,
  the same owner/artifact and fetch count. Closing Changes leaves no preview files.
  Production code was unchanged between these passes.
- Both final screenshots were visually inspected. Final run: no crash entries or
  ANR events; screen/sleep settings unchanged; emulator stopped and reaped.
- Three earlier attempts were obstructed by boot-time System UI/Pixel Launcher
  ANR dialogs. Logs/screenshots are preserved. The launcher was restarted and
  one abandoned synthetic preview directory from the interrupted process was
  removed before the clean seven-test run. The final cold boot and stronger test
  had no ANR event. The first instrumentation build also had a test-only
  PdfDocument Closeable mismatch, corrected with explicit try/finally cleanup.

Local evidence: `captures/runtime/changes-preview-retention/`, including original
failures, `initial-seven-pass/`, final instrumentation output, JVM XML, source/APK
hashes, device settings and screenshots. Synthetic peer fixtures only; no physical
Pixel/Mac acceptance was performed. Signed **596** remains the verified download;
this change is newer and has not been published as a signed APK.

### Remaining work

- Browser-parent recreation and process-death recovery remain unverified.
- Image zoom/pan, media position, text viewer state and an open Save picker need
  their own restoration work and tests; this change does not retain those views.
- The initial short-page PDF screenshot exposed a separate navigation issue:
  after Next page reaches the scroll limit, page two can dominate the viewport
  while the label/buttons still use the partially visible first page's index.
  Fix the current-page calculation or final-page alignment, and verify navigation
  for short/mixed page sizes. The stronger retention fixture deliberately uses
  tall pages to test a definite selected-page transition; it does not resolve
  that short-page navigation issue.
- Full format/UI/accessibility and physical Mac/Pixel validation are still open.
