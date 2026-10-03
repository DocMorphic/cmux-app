# Terminal zoom

Reference: cmux `4c5272e9153eca2033c9f40ac749f0c3a5bcb291`:

- `CmuxMobileTerminal`: `MobileTerminalFontPreference`,
  `MobileTerminalZoomPreference`, `GhosttySurfaceView` pinch/zoom handling, and
  `MobileTerminalZoomControlOverlay`.
- `CmuxMobileRPC/MobileTerminalSetFontEvent` and
  `CmuxMobileShell/MobileShellComposite.handleTerminalSetFontEvent`.
- `CmuxMobileShellUI/GhosttySurfaceRepresentable` live font stream lifecycle.

## Behavior

The mounted Android terminal starts at size 10, accepts sizes 8–28, and supports
one-point toolbar steps. Two-finger pinch uses the iOS cumulative scale threshold
of 0.15 and changes at most one point for each accepted sample. Its pointer owner
survives font, viewport, and replay changes. A pinch consumes pointer events until
all fingers lift so it cannot turn into a terminal mouse click or scroll gesture.
Live sizing belongs to the selected Mac/workspace/terminal view. It survives a
connection replacement for that same view; leaving and reopening the view resets
it. It is not persisted on every gesture.

The centered zoom controls offer Reset to default, Set as default, and Restore
built-in. Only Set as default writes the explicit saved baseline. Reset uses that
baseline or 10; Restore clears it and applies 10. The baseline does not silently
become a launch preference. The controls fade after 2.5 seconds without action,
extended by Android's accessibility timeout recommendation, and temporarily hide
the Files chip. Host-pushed changes update the readout if visible without showing
the controls. The previous global percentage setting is retired.

All terminal output modes subscribe to `terminal.set_font`. Payload decoding
rejects nonnumeric/nonfinite sizes and incorrectly typed scopes. Surface scope
takes precedence; otherwise workspace scope must match, otherwise the currently
mounted view receives the change. Events still pass the existing authenticated
client, stream ID, and replay-generation fences. Sizes are bounded before they
reach layout. A new viewport goes through the existing resize/replay ownership
path, retaining the last painted frame while replacement output arrives.

Android converts the numerical size to accessibility-scaled `sp`; iOS uses
points. This is not a claim of identical font metrics, glyph rasterization, or
UIKit glass. The controls use the terminal foreground/background colors and
Android components. Physical Pixel visual/accessibility acceptance remains open.

## Verification — 2026-09-30

- 12 focused JVM tests pass: seven zoom/decoder/scope tests, four scroll-motion
  tests, and the viewport test.
- Debug and instrumentation APKs build; all packaged native LOAD/RELRO segments
  and APK ZIP alignment pass the 16 KB checks.
- Android 17 with a 16 KiB kernel: the full zoom UI/RPC case passes in **3.977 s**.
  It rejects unrelated scopes, receives a matching host font event, observes
  changed viewport columns, holds the same two fingers down through six separately
  composed resize steps, checks no mouse/scroll RPC was emitted, saves/resets/
  clears the baseline, accepts workspace/global events, hides the controls, and
  verifies a reopened view starts at 10. The earlier single continuous pinch
  version also passed; the final test adds the intervening reflow checks.
- Four related Files/folder-tap, alternate-screen input, and local history-scroll
  cases pass in **10.229 s** with the new default metrics and gesture layer.
- The final app reports `pageSizeCompat=0`. Emulator stopped after testing.
- Local screenshot and original logs: `captures/runtime/zoom/` (ignored).
  The initial zoom screenshot was visually inspected.
- Final debug APK SHA-256:
  `b623261596819b32ad5de886a9a7e790913161123176e6fbc9d2c2801483de8e`.
- Final instrumentation APK SHA-256:
  `ab158e332d6c070cd156632040596eee5517eb1f07c8e3ba47f5f6b5bc3d0399`.

These are local emulator checks. The Pixel was not visible to ADB or the Mac's
USB inventory, so no physical installation or acceptance is claimed. Signed
integration run `36645287501` uses the preceding toolbar commit `3c80608`; it does
not contain this zoom feature. Do not describe that artifact as a zoom release.
