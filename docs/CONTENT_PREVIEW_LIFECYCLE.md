# Changes preview lifecycle

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
