# Launch announcement preloading — 2026-10-04

> Follow-up: [extension recovery](NOTICE_RECOVERY.md) replaces the earlier
> app-restart requirement with scoped cleanup and recovery on a new page attempt.

Source contract inspected at upstream `0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`:
`Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/WorkspaceShellView.swift`
(`preloadAndPresentWhatsNew` and actual sheet appearance), `MobileWhatsNewSheet.swift`
and `MobileWhatsNewWebPageLoad.swift`. This does not advance the global parity pin.

`NativeWhatsNewPresentation` now owns the launch renderers outside the sheet.
After the initial feed attempt, it starts all eligible web pages before waiting
for any outcome. Each production renderer receives the ten-second full-load
limit; session exchange, cookie seeding and navigation are inside that limit.
Native pages wait for the same gate. Failed pages stay unseen and can be opened
with a fresh archive exchange; unchanged failures are not retried in a launch
loop. A changed page or account can start a new attempt.

Before staging, after preloading and at actual sheet appearance, the owner checks
current eligibility, full page content and visibility. Only live, loaded web pages
are admitted. A competing modal/background transition cancels unpresented loads;
account replacement retires all old pages. Cancellation cannot promote a late
result. Already appeared pages retain their session while the activity recreates
or resumes; theme changes update the retained renderer. Dismissal and ViewModel
retirement close the pages. The sheet attaches the exact preloaded Gecko session
and does not repeat the account exchange.

The Settings archive shares the same renderer view and keeps its separate
20-second load limit and explicit fresh-page Retry. Native session information
stays in the existing broker; this change adds no credentials to saved state.

## Verification

Evidence is in ignored `captures/runtime/notice-launch/`. Exact build and runtime
results are recorded below when complete.

The first new test fixtures did not satisfy the existing fresh-feed requirement:
a no-loader refresh never enables cached web launch entries, and an empty JSON
object is not a valid feed. The corrected synthetic feed includes the required
`visibleEntryIds`. These were fixture corrections; the production feed gate was
preserved. Failed runs remain in `build-2.log` and `build-3.log`.

## Remaining scope

No Android notice feed is configured. Real HTTPS/native-account exchange,
partitioned browser storage cleanup, transparent extension-loss recovery and
physical Pixel/Mac acceptance still require evidence. No new signed milestone
is implied by these unsigned/debug fixture checks. Build 494 remains the last
verified signed APK. The bundled Gecko engine's package-size cost is documented
in [NOTICE_RENDERER.md](NOTICE_RENDERER.md).

### Build and deterministic checks

`build-4.log`: debug, instrumentation APK and unsigned release built successfully
in 2 min 55 s. All **52 focused JVM tests passed**, with zero skips/failures.
Seven new cases cover concurrent deadlines and failed-page markers; competing-modal
cancellation; account replacement and stale appearance; same-ID URL replacement;
renderer retirement before appearance; retained page/theme/dismissal; and remote
retraction during preload without another reconciliation callback. The packaged
engine/source/license/extension checks pass for both debug and unsigned release.

The first archive pixel assertion failed while the test polled raw screenshots
without advancing Compose's controlled frame clock. An unchanged isolated rerun
retained the same error-view frame. The fixture now advances Compose while
waiting and checks that Retry starts its second exchange. Both production web-flow
cases then passed in 40.131 s, without a production-code change. Visual review
found that the archive screenshot could still catch the loading indicator over
already painted content; the final capture additionally waits for that indicator
to disappear. The failed runs and observed screenshots are retained.

### Final runtime evidence

- Final production web-flow run: **2 tests passed in 34.954 s** on the existing
  API 37 / 16 KB arm64 AVD (`runtime-final.txt`). Launch waits for an offscreen
  exchange, leaves the marker untouched while pending, displays the same loaded
  session with one exchange, acknowledges on appearance and retires on dismissal.
  Archive deliberately fails its first exchange, retries with exactly a second
  exchange, renders the document, and never writes the launch marker.
- Both final screenshots (`completed/launch.png`, `completed/archive-retry.png`)
  passed a visible-color pixel check and were visually inspected with completed
  content and no loading indicator. They are synthetic local pages, not official
  cmux account content or proof of physical-device acceptance.
- All **four existing notice UI cases passed** in the earlier six-case run
  (`runtime.txt`); that run's archive fixture failed as documented above. The
  production app bytes were unchanged by the subsequent test-clock corrections.
- Unsigned release ART verified `NativeScreenKt` with 1,320 methods
  (`art-release.txt`). Debug and unsigned release package checks passed. Native
  engine binaries are unchanged from the prior 19-library alignment checkpoint;
  no new alignment or cold-launch claim is made here.
- Final test-only build passed in 18 s. Exact debug/test/release sizes and hashes
  are in `packages.json`. The existing emulator was stopped and reaped; no AVD was
  added and no Pixel data or signed milestone was changed.
