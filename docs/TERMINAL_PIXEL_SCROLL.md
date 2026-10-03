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
