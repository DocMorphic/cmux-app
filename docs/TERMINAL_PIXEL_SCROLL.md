# Local terminal pixel scrolling

Checkpoint: 2026-09-29. Reference: pinned upstream
`4c5272e9153eca2033c9f40ac749f0c3a5bcb291`, particularly
`GhosttySurfaceView+LocalPixelScroll.swift` and the row-space/history contract in
`MobileTerminalRenderGrid.swift`.

## Behavior

Screen-anchored primary terminals now retain fractional cell motion through drag
and Android fling decay. Each motion delta is divided by the fitted cell height,
so a sub-cell movement reaches the local viewport immediately. Remote wheel paths
(alternate screens and compatibility host viewports) still accumulate whole rows
and retain their 450 ms momentum limit. Touch, input, viewport/surface replacement,
and mode changes keep the existing cancellation behavior.

`TerminalScrollViewport` supplies the leading row, clipped fraction and one extra
bottom row. The production painter clips both edges to the fitted terminal area,
including letterboxing during keyboard resize. The cursor stays hidden while
reading history. File/path hit testing uses the same fraction and can address the
partially visible final row; Files observation includes those visible edge rows.
“Latest” and input reset both the integer and fractional position.

Producer history growth rebases a held position only inside the same surface,
epoch, dimensions, screen and row-space identity. The fractional position and
visible content therefore survive ordinary output and a compatible replay.
Row-space replacement, reflow, screen/epoch replacement or history regression
invalidates the anchor; retained-history bounds clamp positions after eviction.
Legacy frames without a row-space identity retain bounded distance from the bottom
without assuming that a history-count change proves the same absolute row space.

## Verification

- 34 focused JVM cases passed: 4 viewport/anchor, 4 motion, 10 render grid,
  6 interaction and 10 stream mirror cases.
- Main and instrumentation APKs built. Only the instrumentation APK was rebuilt
  for the later app-screen fixture and its stronger visual assertion; production
  sources did not change between those runs.
- Ten Android 17 emulator cases passed in **29.952 seconds**: three bitmap/font
  checks, three actual gesture checks, and four app/RPC checks (local history,
  alternate-screen wheel/click, host viewport responses and raw VT recovery).
- The first app screenshot was stale despite passing semantics/RPC checks. The
  local history test now waits for distinctive history background pixels in the
  actual terminal capture and saves the Compose root directly. That stronger
  test passed in **23.660 seconds**. Its screenshot was inspected: history is
  visible, the first row is partially clipped, the cursor is hidden and “Latest”
  is present. The blue history backgrounds belong only to the fixture.
- The bitmap test separately verifies both partial edges, cursor suppression,
  letterbox clipping and canvas save/restore balance. Real gesture tests assert
  fractional local deltas, integer remote wheel deltas and cancellation.
- The app-screen fixture confirms no scroll RPC for local primary history,
  “Latest” returning to live output, and no mouse RPC when history is tapped.
- ELF LOAD/RELRO and ZIP 16 KB alignment checks pass.
- Main APK SHA-256: `c1e35644fbe4dc4f79de97dd9989b5507c0f07c48688fef1ea296d28a5f4bb05`.
- Test APK SHA-256: `8d342fbfbbad3e800d431b64a69e4fd644ccc979e9cfcf952bd0b9a2464f25ae`.
- Evidence is in ignored `captures/runtime/terminal-pixel-scroll/`. The emulator
  was stopped afterward. ADB still listed no physical Pixel; no phone or real Mac
  setting changed, and the last Pixel install remains `f0dfc7f`.

## Remaining acceptance

Pixel/Mac drag/fling, keyboard resize, running-output reading, and TUI acceptance
remain required. This adds smooth local primary scrolling; it does not finish
Ghostty rendering fidelity, inline graphics, full terminal modes or the broader
terminal parity checklist. Published signed build 157 is unchanged.

## SSH and Cloud local history (2026-10-07)

Scoped source: upstream `b9c0111a67bf8daadc2e87bff402254bfd9a26fd`,
`Packages/iOS/CmuxMobileTerminal/Sources/CmuxMobileTerminal/GhosttySurfaceView.swift`,
especially `ownsLocalPrimaryScreenScroll` and `flushPendingScrollIfNeeded`.
Locally emulated primary screens with history and no mouse capture use the pixel
path; TUI wheel events use accumulated whole rows and bounded momentum. This
review does not advance the global upstream pin.

The shared Android `SshShellScreen` previously forced every drag/fling onto the
whole-row path. It now selects pixel motion for local primary history. Mouse
capture and alternate screens keep the existing ordered remote wheel encoding.
Non-finite deltas are rejected before either route. Ended primary history remains
readable. Motion stops at a history boundary and is disabled during reconnect.
Changing the emulator owner, grid dimensions, active screen or local/remote
ownership clears the held offset; normal output retains its fractional component.
The debug text inspection uses the same clipped-edge rows as the painter.

At this checkpoint the SSH/Cloud Ghostty snapshot exposed only retained history size,
not a monotonic producer row-space identity. Its held position therefore remains
a bounded distance from the bottom; preserving the exact content anchor while
new output grows or evicts history is still open. The Mac render-grid anchor
described above already has a stronger producer identity contract.

Verification: **eight JVM checks passed**, and the final **five Android terminal
cases passed in 25.798 s**. The actual screen test holds a finger down, moves it
three pixels and verifies the painted row boundaries move three pixels (within
one pixel of raster rounding); it also verifies no remote bytes and clears the
held position on alternate-screen entry/exit. Existing cases cover mouse capture,
ordered wheel/click bytes, focus lifecycle, arrow modes and ended-shell history.
Screenshots were inspected. The three workbook cases also passed in the initial
combined run, closing the previous pending ODS paging recheck.

The first run caught stale gesture state after initial grid resize. Keeping one
scroll-state object per emulator and resetting its value on grid/mode changes
fixed that regression. The pixel assertion then needed corrections for the
horizontal letterbox and antialiased boundary pixels; original failures and
diagnostic logs remain in ignored `captures/runtime/ssh-pixel-scroll/` alongside
the passing run, screenshots, builds and APK hashes. Temporary diagnostic logging
was removed before the final app build.

The existing API 37/16 KB emulator ran with 1536 MiB and two virtual cores. No
crash/ANR events appeared; emulator and Gradle were stopped. No new AVD or physical
device install. Physical Pixel, real SSH/Cloud sessions and running-output
content anchoring remain open. No signed release; published APK 616 is unchanged.

## Holding SSH/Cloud content during output (2026-10-07)

The pinned Ghostty C API at `edefce7785c9f439966c68588db1edbd6b435203`
already supports `ghostty_terminal_grid_ref_track` and
`ghostty_tracked_grid_ref_point`. A tracked reference follows actual terminal
page mutations, including pruning of earlier rows, and explicitly expires when
its content is discarded. The JNI owner now retains one reference to the first
partially visible history row plus its fractional clip. This replaces distance-
from-bottom arithmetic for SSH/Cloud history. Reading live or graphics snapshots
does not replace the anchor; Latest and input release it.

Grid reflow, screen activity (including primary→alternate→primary in a single
byte batch), emulator replacement and a discarded referenced row invalidate the
hold. Pixel metric changes without grid reflow keep it. Pins are owned and freed
under the native registry lock; no pointers cross JNI. The view resolves current
content position on output revisions and before the next gesture delta.

The iOS reference for this behavior is `LocalPixelScrollHeldRebase.swift` at
`b9c0111a67bf8daadc2e87bff402254bfd9a26fd`: undocked history holds content while
live-bottom state follows new output. The Android implementation uses Ghostty's
tracked cell contract because its pinned public scrollbar lacks those iOS
cumulative row-push counters. This does not advance the global source pin.

Verification:

- **15 JVM checks passed** (eight scroll checks and seven native snapshot decoder
  checks), and debug/app-test/native-test APKs built successfully.
- **14 Ghostty Android checks passed in 0.369 s**, including retained content
  through growth and detected pruning, fractional position, expiration, reset,
  reflow, close and primary→alternate→primary inside one output batch. The first
  pruning fixture did not fill Ghostty's minimum page capacity; it now uses an
  80-column grid and requires an observed drop in retained rows before checking
  that the tracked content and fractional position survived.
- **Six app Android checks passed in 46.152 s**. A held finger remains in place
  while 33 new lines arrive: the visible text and painted boundaries stay the
  same. The next 2px motion continues from that anchor. Replacing the emulator
  clears the old position and the next gesture scrolls the replacement's content.
  Wheel/click/focus routing and alternate-screen behavior still pass.
- Screenshots inspected; native/app ELF LOAD/RELRO and APK ZIP alignment pass
  for 16 KB pages. No crash/ANR events appeared on the existing API37 emulator
  (1536 MiB, two cores). Gradle and emulator were stopped; no new AVD or phone
  install. Only the emulator's standalone Ghostty test package was replaced
  after its previous CI signature differed from this local test build.

GitHub initially rejected pushes/workflow dispatch with HTTP 500. The native
core and binding were built locally with verified Zig 0.16.0 and NDK r28c; later
pushes succeeded. No cloud build is claimed for these commits. Evidence and
original failure logs are in ignored `captures/runtime/terminal-content-anchor/`.
Physical SSH/Cloud sessions and replay continuity remain open; replacing an
emulator intentionally starts at live output. No signed release; APK 616 remains
the last published release.


## SSH/Cloud input reveals the prompt — 2026-10-07

Compared `GhosttySurfaceView.handleUserProducedInput` and its scroll-generation
fence at cmux `b9c0111a67bf8daadc2e87bff402254bfd9a26fd`. This scoped comparison
does not advance the global parity pin. The iOS surface returns to live output
for explicit typing/paste and cancels deceleration.

Android now handles this in the shared SSH/Cloud input lane: accepted keyboard
text, direct clipboard text, uploaded image paths and submitted composer text
release held history and stop motion. Empty input, attachment/text staging,
rejected delivery, retired views and mouse/wheel packets do not reveal the
prompt. A delayed Cloud submission acknowledgement cannot override a newer
local scroll. The screen callback uses the current display/motion after replacement.

Verification: both APKs built; **12 input Android checks passed in 0.554 s**,
including upload ordering, rejection/retirement and delayed acknowledgement.
**Two screen checks passed** in the preceding 14-case run: direct clipboard and
IME image paste return held history to live output; composer staging preserves
history until Send. That initial run failed two new lower-level test assertions
because they awaited successful queue completion after deliberately causing a
queue error. Corrected tests wait for the expected failure state before checking
that no reveal/input occurred; production code did not change after that run.

The first emulator boot, concurrent with compilation, logged system-service
ANRs before app testing. It was stopped and restarted after Gradle stopped;
that boot completed in 18.443 s and the test run logged no crash/ANR events.
Only the existing API37 AVD (1536 MiB, two cores) was used. Logs, including failed
attempts, are retained in ignored `captures/runtime/input-follow-prompt/`.
No physical-device acceptance or signed release is claimed; APK 616 remains
the published release.
