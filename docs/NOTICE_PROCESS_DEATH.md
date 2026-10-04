# Notice engine process-death acceptance — 2026-10-04

This extends [content-process crash acceptance](NOTICE_CONTENT_CRASH.md). That
check preserves the Android owner and Gecko runtime. This check kills the Android
process that owns the production renderer, engine, presentation and file ledger.

## Scope and method

`NativeNoticeProcessActivity` is an unexported, emulator-only debug activity in
`:notice_process_test`. It uses `NativeWhatsNewCenter`, `NativeWhatsNewFileStore`,
`NativeWhatsNewPresentation`, `NativeNoticeArchiveOwner`, `NativeNoticeRenderer`
and `NativeWhatsNewHost` with a generated fixture ledger and loopback content.
Instrumentation and its HTTP server stay in a separate process so they can inspect
the on-disk ledger, send SIGKILL and verify different owner PIDs afterward.

The test starts a real private renderer and holds its cookie exchange. It checks
that no page request or acknowledgement happened, then kills the owner without
calling a page close method. A cold launch must start a fresh engine/exchange,
render actual content and acknowledge its appearance in the production file store.

The visible page receives a synthetic HttpOnly session cookie and writes a
persistent-cookie request (`Max-Age=86400`) plus localStorage. Server reports prove
those values existed before the next SIGKILL. The next cold launch waits for the
fresh feed attempt, checks zero unseen notices and no automatic exchange, then
later opens the real archive UI and renders with a new process-specific session cookie.

While the newly loaded archive page is still open, a debug-only probe reopens
the **exact context ID** from the killed process on the new runtime. This ordering
uses the production engine startup flow and prevents last-private-page cleanup
from making the assertion vacuous. Its request and script
report must find no old cookies or localStorage. Testing only the renderer's new
random context would not establish that the killed context lost its state.
The probe navigates only to its fixed loopback fixture. Production navigation
policy and credentials are unchanged. The probe keeps its private session alive until fixture cleanup; it starts only
after the production archive renderer has loaded.

## Results

The final strengthened Android case **passed in 48.644 seconds** on the existing
API 37 / 16 KB arm64 emulator (`android-verified.txt`). Actual owner PIDs were
**3302 → 3597 → 3903**; the instrumentation/server process survived throughout.
The old context probe ran while the fresh archive page remained open. Both pages
passed visible-pixel assertions, and both screenshots were visually inspected.
Evidence is retained under ignored `captures/runtime/notice-process/`.

- Initial build failed on a nullable Android RecentTaskInfo in test cleanup;
  the safe call was corrected. Debug/test assembly then passed in 59 seconds.
- The first Android run failed before reaching the held exchange (34.503 s), with
  browser startup overlapping emulator boot activity. The unchanged second run
  reached both kills and saved acknowledgement, but received no archive page report
  before the test timeout (56.282 s). Logs contain slow ART verification and browser
  process startup. These runs are retained as failures.
- After Android's `cmd package compile -m speed -f` returned Success for the debug
  app, the unchanged test passed in 24.69 s, including cold archive reopening.
  This establishes a pass under compiled-bytecode conditions, not proof that
  compilation alone caused the improvement or a physical-device performance result.
- Review then strengthened the privacy assertion to run before any new page
  closes; a post-dismissal probe could otherwise be cleared by the last-private-page
  lifecycle. The first revised run after reboot had no launch-sheet pixels
  (38.174 s); another reached the old-context privacy assertions but received no
  archive report (53.215 s). These are retained failures. The final harness probes
  only after the real archive renderer loads, preserving production engine startup
  order, and records renderer stages and fixture requests even on failure.
  Production behavior and deadlines were not changed.

The final debug/test build passed in 29 seconds. The intermediate diagnostic build
passed in 54 seconds and the earlier test-only rebuild in 18 seconds. Final debug
engine packaging and the unexported process activity manifest checks pass.
`verified-device/report.json` and `diagnostics-*.json` record exact fixture requests,
process IDs, loaded renderer states, and empty old-context cookie/localStorage
values. The final device setup compiled the target app's bytecode after installing
both APKs; `dex-compile-final.txt` records Success. No production deadline was relaxed.

The existing emulator is stopped and reaped. No new AVD, physical-device install,
JVM test run, release build or signed milestone was created. The previously seen
startup/load failures remain an open debug cold-start/performance limitation;
a passing final recovery run is not proof of reliable timing on the physical Pixel.
Production source files are unchanged by this checkpoint.

## Limits

This proves the components exercised by the fixture, not the full `MainActivity`
connection graph or a logged-in Pixel/Mac workflow. The killed owner is a debug
secondary Android process; the instrumentation/server process remains alive.
It is a hard kill, not an Android low-memory killer or force-stop delivery test.
The context probe covers cookies and localStorage, not post-process-death IndexedDB,
Cache Storage, disk-forensic erasure, notifications or push delivery.

No official Android notice feed or provider configuration is enabled here. Real
HTTPS/native-account acceptance, physical Pixel acceptance and broader source
parity remain open. Build 494 remains the last signed milestone; the last
unsigned-release/ART gate is `786264d`.
