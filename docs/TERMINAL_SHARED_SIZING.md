# Shared terminal sizing and explicit detach

## New NIGHTLY contract found — 2026-10-03

The physical terminal UI check failed before keyboard entry: phone geometry was
67 × 47, while the viewport-free host replay remained 67 × 35. The exact-equality
check had previously passed on the stable host. The new test selected NIGHTLY
explicitly because both stable and NIGHTLY are now saved; it never silently falls
back between builds. The disposable workspace was closed, receipt removed and
normal phone sleep setting restored to 0. Gboard was not exercised by that run.

The installed NIGHTLY source (`0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`)
introduces shared sizing. The default `smallest` policy takes component-wise
minima across counting participants, including the Mac's natural viewport.
This explains why a correctly reported phone viewport may differ from the PTY
grid. The revised check requires the exact phone viewport in the sole mobile
participant, and requires both the published size state and actual render grid to
equal the minimum of counting viewports. It retains exact equality on older hosts
without `size_state`. Runtime verification of that revised check awaits unlock.

Authoritative source files inspected locally:

- `docs/shared-terminal-sizing.md`
- `Sources/TerminalController.swift`, viewport/replay handlers and apply governor
- `Sources/TerminalController+SharedSizing.swift`, reducer and mobile events
- `Packages/iOS/CmuxMobileShell/Sources/CmuxMobileShell/MobileShellComposite+TerminalSizing.swift`
- `Packages/iOS/CmuxMobileShellModel/Sources/CmuxMobileShellModel/MobileTerminalSizingSurface.swift`

This source audit is specific to shared sizing. It does not advance the repository's
entire iOS parity pin or assert that all intervening upstream changes were reviewed.

## Implemented foundation, not yet integrated

`TerminalSizing.kt` decodes the host grid, participants, owners, policies and detach
metadata. Its per-surface state reducer follows iOS generation ordering, participant
replacement, viewport reassertion and network recovery. Explicit detach survives
replay, network events and connection replacement until a successful user-initiated
reattach. Unknown detach reasons conservatively require explicit reattachment.

Six focused JVM tests passed (zero failures/errors/skips), in a successful 17 s
Gradle invocation. Cases cover distinct phone/host dimensions, malformed ownership
and policy, stale/new-participant generations, explicit detach persistence,
network recovery/reassertion and unknown reasons. Notices attribute the upstream
adaptation. This model is not wired into the app yet and is not in signed build 397.

## First foreground integration (`0f9cddc`) — 2026-10-03

The foreground Android session now subscribes to the two sizing topics only when
`terminal.shared_sizing.v1` is advertised. It retains explicit detach across
Activity/connection replacement within the selected account/Mac. Sizing events
update admission synchronously before the bounded, lossy display-event flow;
subscription IDs and replaced connection checks isolate consumers. Malformed
sizing metadata cannot break terminal replay; malformed detach metadata with a
surface ID conservatively stops traffic.

RPC admission is checked again immediately before writing, including after token
lookup, waiting for the wire, or control repair. Consuming leases wrap both native
terminal lanes, gating legacy and identified input and output reads. The foreground
input owner discards pending units on explicit detach. Late replay and network
recovery cannot reopen traffic. Only an acknowledged explicit reattach on the same
connection and detach revision reopens it; the normal replay pipeline then restores
output. A new detach while reattach is pending wins.

The terminal displays its negotiated grid and a read-only participant dialog.
Detached terminals show actor/time when provided, plus **Reattach** and **Reattach
as viewer**. Toolbar, composer and direct keyboard admission reflect detachment.
The dedicated subscription is cleaned up through its borrowed event-session scope.
These screens compile but have not yet been visually or physically accepted.

On capable hosts, viewport/replay/reattach now identify Android with the supported
`unknown` device kind and actual `Build.MODEL`; older hosts keep their legacy
request shape. Upstream has no Android enum. Its automatic same-user mobile
exclusion for latest/priority/fixed policies specifically names iPhone/iPad;
`unknown` does not establish identical policy behavior. The planned counts override
UI and an upstream Android-kind change remain necessary for full parity. No host
policy has been silently changed.

Verification: **35 focused JVM tests passed**, zero failures/errors/skips
(6 reducer, 5 sizing-session, 11 retained-input, 11 RPC transport, 2 event-session).
The final debug APK and instrumentation APK build passed in **54 s**. Cases include
both native lane forms, RPC aliases, detach during token lookup, pending-input
discard, explicit detach across replacement, stale acknowledgements/events,
malformed metadata, consumer isolation and negotiated Android identity. The runner
also passed Python syntax compilation; no new device execution is claimed.

At that checkpoint, remaining work included editable policies, counts/priority/fixed controls, participant
removal, iOS bounds presentation, retention across switching away to another Mac,
and background notification-reply connections. The current admission binding
covers the foreground terminal session; it must not be described as global
cross-session detach enforcement. Real detach/reattach and Gboard checks are pending.
Signed build 397 and the installed Pixel app are unchanged by this source work.

A repeatable physical runner is available:

```sh
python3 scripts/check-live-terminal-ui.py --serial DEVICE_SERIAL --build nightly --gboard
```

It requires an unlocked physical phone, preserves app data, refuses an outstanding
ownership receipt, installs only the built test APK, runs the actual MainActivity
check, and restores the original sleep setting in `finally`. Install the matching
debug APK with `adb install -r` before checking new production behavior. Review the
saved terminal screenshots before calling a pass visual acceptance.

## Editable size sheet — 2026-10-03

The read-only dialog from `0f9cddc` is now an editable bottom sheet, adapted from
the installed NIGHTLY's `TerminalSizeSheet.swift` and
`MobileTerminalSizingPresentation.swift` at the same `0fc35d6` reference. It has:

- All five policies, preserving inactive fixed/priority options.
- Fixed columns/rows with iOS limits (20–300 and 5–120), applied explicitly.
- Priority drag ordering, menu and accessibility move actions, stable ordering for
  unranked participants, deduplicated priority keys, and preserved disconnected keys.
- Current owner/participant dimensions and this phone's counts toggle, including
  **Use automatic rule** to clear the override with JSON null.
- Individual disconnect actions and confirmed **Disconnect Others**, bound to the
  IDs shown at confirmation. Newly attached devices are not swept into that batch.
- Pending/error presentation without optimistically claiming the Mac changed.

Mutations carry the foreground lease's client ID, original workspace/surface,
current natural viewport and the existing viewport generation. Selection/admission
is rechecked just before writing and after acknowledgement; geometry changes also
invalidate a pending counts report. No mutation is automatically resent. A failed
multi-disconnect stops the batch and tells the user to review partial results.
Policy/disconnect replies describe the Mac's self ID: only their size state is
applied, so they cannot overwrite this phone's participant identity.

Verification on this source:

- **29 JVM tests passed**, zero failures/errors/skips: 6 reducer, 6 controls/RPC,
  6 session/identity and 11 RPC transport tests.
- Debug and instrumentation APK builds passed in **35 s** after the final fixes.
- **3 emulator UI tests passed in 11.002 s**: fixed limits, policy selection,
  counts/reset, accessible and long-press priority moves, cancellation/confirmation,
  and pending/failure/offline admission. The actual priority screenshot was reviewed:
  all three participants, toggle, owner, menu handles and destructive action visible.
- The first run's screenshot showed a System UI ANR from emulator startup and was
  rejected. A separate ambiguous test selector was fixed. The final run used a clean
  boot of the same AVD with host graphics, after building; no ANR event/dialog was
  observed. The emulator was then stopped and its process exit verified. No new AVD.

These UI tests use deterministic participant snapshots; RPC tests use an in-memory
wire. They do **not** prove Mac policy negotiation, real participant disconnection,
Pixel layout, TalkBack, or the complete iOS bounds/chip presentation. The Pixel
was locked and later disconnected; no app/data change was made there. Signed build 397 remains the
last delivered signed APK. Physical acceptance and cross-Mac/background reply
retention are still open.

Tested APK SHA-256 values:

- Debug: `75a7cc7eb3a877b4c4a11570b853c1afe2ae3d66e21734eae3bae6fa63746cde`
- Instrumentation: `e7ec9bd3886f9e17388a7960cde2ea2c3e600987077da367be9e6df65e5c29d3`

Ignored evidence: `captures/runtime/sizing-controls-final-build.txt`,
`sizing-controls-ui-final.txt`, and `sizing-controls-screenshots-final/priority.png`.

## Cross-Mac retention and direct notification replies — 2026-10-03

Detach state is now stored per login/user/team/Mac/build/surface within the retained
feed ViewModel. Returning to Computers or selecting another Mac no longer erases
it. Only published size/participant state is reset on connection replacement; an
explicit detach still requires reattach. Account replacement and explicit clear
retire every remembered owner. Binding/clearing and selection updates share one
monitor, with observer removal outside it and subscription-generation checks on
old callbacks. UUID device IDs are canonicalized consistently between foreground
and feed owners; opaque IDs remain unchanged.

Secondary feed leases now consult the same owner's retained detach state before
terminal writes. Foreground and secondary direct notification attempts check that
admission before preparation and again before delivery. A prepared attempt whose
terminal becomes detached returns unavailable without a terminal write; it never
switches selection or opens another connection to circumvent the detach.

**The encrypted reply relay remains available.** A fresh upstream audit at
`0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc` found:

- `MobileShellComposite.sendRemoteTerminalPaste` checks `terminalAllowsTraffic`
  before both exactly-once and legacy direct delivery.
- `MobilePushCoordinator.applyPendingReplyIfReady` may relay a notification reply
  when direct delivery is unavailable or returns false. Detach ends the terminal
  view; it is not an account-wide revocation of an explicit notification reply.
- Android retains its existing stricter duplicate protection: an uncertain direct
  write cannot become a relay retry. A provably unwritten attempt can use the relay.
  No relay code, durable reply format, provider configuration or account grant was
  changed in this checkpoint.

The retention is in memory, including Activity recreation; it is not a new persisted
terminal permission database. The iOS sizing dictionary is likewise initialized
in its composite and cleared by its account reset. Account/membership checks remain
necessary independently of the sizing cache.

**68 focused JVM tests passed**, zero failures/errors/skips, in a **22 s** Gradle
invocation that compiled the production sources. Breakdown: 6 sizing reducer,
6 sizing controls, 9 sizing session/identity, 11 retained input, 19 feed coordinator,
5 direct reply and 12 outbox tests. New evidence covers A → Computers → B → A,
colliding surface IDs across Mac/build/team, stale callbacks, account reset,
canonical owner keys and secondary direct-reply admission. An initial new test
fixture lacked a terminal in its workspace listing; that fixture was corrected
before the passing run.

No APK was rebuilt or installed for this logic checkpoint. The last sheet UI
acceptance and APK hashes above belong to `f02b7a0`; physical Mac/Pixel acceptance,
live provider delivery and bounds/chip fidelity remain open. The Pixel is currently
disconnected, and no emulator was started for these checks.

## Settled viewport chrome (2026-10-03)

The Android terminal now suppresses its sizing decoration until all of these are
true: the selected connection is ready, its viewport report succeeded, the host's
self participant reports that exact local viewport, and the rendered grid matches
the authoritative shared size. Keyboard/font/viewport changes, connection or
surface replacement, detach and reconnect invalidate the confirmation. A matching
phone/shared size has no decoration. The header's **Terminal size** menu still
opens the controls when sizes match.

The overlay follows the painter's rectangle: faint diagonal hatch in unused
space, borders only on sides facing that space, and cut-edge gradients where a
render rectangle extends beyond the viewport. Chip placement tries below, beside,
then above the grid; otherwise it uses a compact top-right size pill. The full
label includes the owner and whether the actual rendering is scaled. Theme-derived
text and borders meet 4.5:1 and 3:1 contrast respectively. Only the chip handles
touches. Source audit: the installed NIGHTLY revision
`0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`, specifically `TerminalSizingChromeGate`,
`TerminalSizingBoundsGeometry`, `TerminalSizingBorderEdges`,
`TerminalSizingChipPlacement` and `GhosttySurfaceView+SharedSizing`.

**20 focused JVM tests passed**, zero failures/errors/skips: 5 chrome geometry,
gate/contrast tests, 6 sizing controls and 9 sizing session tests. Production and
instrumentation APKs built successfully in the same 37-second invocation. The
initial unscoped Gradle invocation also selected Ghostty's test task, which had no
matching tests; the corrected invocation explicitly targeted `:app:testDebugUnitTest`.

**2 emulator UI tests passed in 4.578 seconds** on the existing API 37 / 16 KB
AVD after the final accessibility adjustment. Checks cover dark/light hatch pixels,
real touch dispatch through the decoration, chip taps, compact placement and its
accessible action. Both theme captures were visually reviewed: the prompt is
uncovered and the chip sits below the grid. Captures include the chip's touch
ripple; these are interaction captures, not a full-screen iOS visual parity proof.
No Android ANR dialog was present. The final APK build passed in 28 seconds; the
emulator was stopped afterward. No new virtual device or system image was created.

Final local APK SHA-256 values (not a new signed release):

- Debug: `cf87973f36352ea86d3b82201c258f011b854e7785cb4f8cf283a54e24454f2d`.
- Instrumentation: `42b5e86c26843f2b5d370c129c0482f89f6413adf9449ee79ae9082941eb236a`.

Ignored evidence: `captures/runtime/sizing-chrome-build.txt`,
`sizing-chrome-final-build.txt`, `sizing-chrome-ui-final.txt`, and
`sizing-chrome-screenshots-final/{dark,light}.png`.

At that checkpoint Android still fitted both axes and centered the grid; the
shared-grid layout integration below replaces that behavior for negotiated shared
sizes. The preceding cut-edge tests alone did not prove clipped rendering. Physical
policy/detach/reattach acceptance and live viewport confirmation remain pending;
the Pixel was disconnected during this checkpoint.

## Shared-grid display zoom and placement (2026-10-03)

The native terminal now uses one `TerminalSharedGridLayout` for its painter,
artifact and terminal taps, scroll-cell mapping and sizing decoration whenever the
host's shared size matches the rendered grid. Smaller grids render at 1:1,
left-aligned and top-pinned when at least a row shorter than the viewport. A natural
grid's sub-row remainder stays above it, keeping the last row at the dock.

An oversized grid fits the viewport **width**, retaining the host's exact columns
and rows. A tall display starts bottom-pinned. Two-finger pinching magnifies up to
1:1 around the fingers, and moving both fingers pans to reveal clipped rows and
columns. Pan offsets clamp to the overflowing grid; no empty interior gap is
introduced. These gestures only change the display transform: they do not change
the font preference, natural viewport report, replay keys or PTY dimensions.
Geometry changes during a gesture cancel display zoom without switching into a
font resize. The non-shared font-zoom path and SSH remain available. Taps in unused
native-terminal space open the keyboard without sending a clamped mouse click to
an unrelated grid cell.

`scripts/generate-terminal-layout-fixtures.py` compiles the unmodified
`TerminalGridFit.swift` and `TerminalLetterboxGeometry.swift` at the exact NIGHTLY
revision above. The checked-in compressed fixture includes both source hashes and
**189 reference cases** covering natural/letterbox/oversized grids, width fit,
initial offsets, zoom focus and pan clamping. Android matches those outputs to
0.001 pixels at density 1. The Swift files are read with `git show`; the upstream
checkout is not modified.

**27 focused JVM tests passed**, including all 189 reference cases, invalid
gesture/resize handling, corner reachability, focus-preserving cell mapping and the
existing sizing-chrome, scroll, font-zoom and artifact-hit regressions. An initial
boxed-float assertion distinguished `-0.0` from `0.0`; the Android origin expression
was corrected to match the upstream subtraction. The first instrumentation compile
used an obsolete touch-injection helper; it was updated to this project's
`updatePointerTo` API before building successfully.

The final regression run passed **36 JVM tests** (the 27 above plus 9 native sizing
session tests), with zero failures/errors/skips, and **10 emulator UI tests in
28.449 seconds**. The UI run includes real two-finger pinch/pan, unchanged font and
grid dimensions, transformed tap coordinates, small-grid/chip placement, both
theme overlays, and six full `NativeScreen` flows: legacy font zoom, keyboard
resize/input, alternate-screen scrolling/mouse input, terminal file taps, and
grid/byte replay retention across resize and terminal changes. The small-grid and
zoomed/panned coordinate-label screenshots were visually reviewed. These are
synthetic emulator peers, not live Mac/Pixel acceptance.

The full-screen checks caught failures that the isolated components did not:
Android rejected generated code in the large screen method, and subsequent runs
hit a null sizing-snapshot path on an older-host fixture. `NativeTerminalContent`,
`TerminalGridPresentation` and `TerminalSizingSurfaceView` now provide smaller
composition boundaries; the final full-screen checks exercise the absent sizing
snapshot successfully. Reattach errors are shown inside the detached view. Initial
absent sizing revisions/reconnect flags now use their default values in effect
keys, avoiding an unnecessary clear/replay cycle when the first empty sizing
snapshot arrives. Test viewport readers also distinguish actual size reports from
clear requests; a clear request contains no columns or rows. Earlier failed runs
remain in local diagnostics and are not counted as acceptance.

The final production/test APK build passed in **51 seconds**. SHA-256:

- Debug: `51b45dca0e3550edb2dfc86b40c70cd317671e89c0a6e20971f4c2cf529acc62`.
- Instrumentation: `b7ede680fec0199c894d0da73b60ace19196e68a1f1ba03a474553f97d79dd1b`.

Evidence is ignored under `captures/runtime/`: `shared-grid-regression-build.txt`,
`shared-grid-final-build.txt`, `shared-grid-final-ui.txt`, and
`shared-grid-screenshots-final/`. The existing emulator was shut down after the
checks; no new AVD was created. The 138 MB temporary bytecode dump and extracted
DEX were removed after diagnosis, retaining the original failure logs. No physical
phone was connected or changed. Signed build 397 remains the last delivered release.

At this shared-grid checkpoint, keyboard-independent primary geometry remained
open. The following checkpoint implements it. The alternate-screen keyboard
transaction fence and physical Mac/Pixel policy/detach/viewer-reattach checks remain
pending; local geometry tests do not prove those host/device workflows.

## Primary keyboard absorption and top reveal (2026-10-03)

Primary screens now retain their keyboard-independent PTY grid. The visible view
size and unconsumed keyboard inset are sampled together in the layout callback;
the primary report adds that overlap back instead of reporting each smaller
keyboard-animation frame. Shared grids use the same stable container. Unshared
alternate screens continue reporting the visible container. Screen-mode changes
are published separately from mutable terminal snapshots so entering/leaving an
alternate screen is observable even when the underlying display object is reused.

The renderer measures the lower of the cursor row and last non-whitespace row,
including content drawn below the cursor. Blank rows and letterbox space absorb
the keyboard intrusion first. Remaining intrusion slides the render up enough
to keep the last content row visible. Primary scrollback and the hidden-top-row
reveal form one continuous axis: scrolling past the oldest stored row reveals
the clipped top without fabricating history or sending a remote wheel event.
The line path consumes that reveal zone before sending remaining wheel input.
Dismissal clamps away any held reveal without inventing a compensating scroll.

Painting, mouse/file hits, scrolling and sizing chrome all use the shifted
geometry. Scroll gesture ownership now depends on grid/cell dimensions, not the
moving origin, so a reveal can continue through multiple Compose frames without
cancelling its own drag. Shared-grid pinch focus is converted back into the
keyboard-independent coordinate space; zooming preserves the cell under the
fingers while the keyboard has shifted the renderer.

Reference evidence adds the unmodified `TerminalKeyboardViewport.swift` and the
blank absorption / primary and line scroll-reveal functions from
`TerminalLetterboxGeometry.swift` at `0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`.
The generated fixture now contains **315 cases**: the prior 189 shared-grid cases,
36 keyboard absorption/reveal cases and 90 cases checked against both Swift scroll
paths. Source hashes remain embedded in the fixture.

**16 focused JVM tests passed**, zero failures/errors/skips, covering the new
keyboard behavior, both scroll paths, all Swift reference cases, paired inset
measurements, content below the cursor, and existing scroll geometry/motion.
The native keyboard test now requires the view to shrink while its primary PTY
row count stays unchanged, then verifies exactly one submitted command. File and
mouse tests target explicit cells in the new top/bottom-pinned geometry.

**10 emulator UI tests passed in 25.701 seconds** on the existing API 37 / 16 KiB
AVD: two keyboard-layout checks, two shared-grid checks, and six native flow checks
covering keyboard submission, alternate mouse/scroll, zoom, file hits, raw output
screen changes/recovery and local primary scroll. Continuous drags reveal the
oldest rows without remote scroll; a real two-finger pinch preserves its focused
cell while the grid is shifted above the keyboard. Prompt-visible, top-revealed,
keyboard-dismissed and full native/Gboard captures were visually reviewed.

Final debug/test APK build passed in 52 seconds. SHA-256:

- Debug: `796c81e5270753a8fcd7982f12c58d5a75f36f0209816bc372afd21cb7b1eb48`
- Test: `f8b068e7036d064e5115f6c1cce9220c696fd3b3f9bb388c69d45db9e929dfce`

Ignored local evidence: `captures/runtime/keyboard-layout-build.txt`,
`keyboard-layout-final-build.txt`, `keyboard-layout-ui-final.txt` and
`keyboard-layout-screenshots-final/` in that directory. The existing emulator was
stopped after verification; no new virtual device was created. Content-bottom
measurement still needs bounded-cost/performance and graphics-content coverage.

This is not complete keyboard parity. The next section records the subsequent
viewport fence/target implementation and the later presentation transaction.
Physical Pixel/Gboard and Mac shared-policy/reattach acceptance, hardware keyboard
checks and accessibility/performance coverage remain pending. No physical phone
was connected for this checkpoint, and signed build 397 remains unchanged.

## Alternate-screen viewport targets and settling (2026-10-03)

`TerminalViewportGeometryFence` retains the committed size across transient layout
passes and requires three quiet Compose frames before committing an ordinary
geometry change. New mounts seed their first valid capacity; invalid/zero layouts
cannot erase it. Replaced surface/connection owners get independent fences.

Unshared alternate-screen terminals use Android's announced IME animation target.
The paired visible height and current keyboard overlap reconstruct the natural
height; subtracting the target overlap prepares the final TUI capacity before the
animation ends. Intermediate pane heights do not become PTY resize requests. A
reversed animation replaces its target. Shared-size sessions and primary screens
continue reporting their keyboard-independent capacity. The shorter prepared
alternate grid stays seated above the live keyboard dock during its movement.

This adapts `TerminalViewportGeometryFence.swift`, `TerminalViewportInputs.swift`
and `TerminalAlternateScreenViewportTests.swift` from the same pinned iOS revision
`0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`. The integration uses Compose 1.9.1's
[IME animation target/source insets](https://developer.android.com/reference/kotlin/androidx/compose/foundation/layout/WindowInsets.Companion),
with its existing platform callback handling cancellation and completion. It does
not replace Compose's window-insets callback or guess an animation duration.

The physical `LiveNativeUiCheck` now checks keyboard-independent phone capacity
and an unchanged host grid for its primary-shell fixture. Its shared-size branch
still checks the exact mobile participant report and the negotiated minimum. This
updated physical check must run when the Pixel is available.

Verification: **12 focused JVM tests passed**, with zero failures/errors/skips
(four fence tests, five keyboard tests and three shared-layout tests, including the
315 pinned Swift math cases). Debug/test APK builds passed in **59 seconds**.
**Nine emulator UI tests passed in 24.57 seconds** on the existing API 37 / 16 KiB
AVD: alternate target/dismissal reports, primary keyboard submission, alternate
mouse/scroll, raw screen switching/recovery, retained frame during replay, two
keyboard layout checks and two shared-grid gesture checks. The new full-flow test
asserts that opening sends only the final smaller row count and dismissal restores
the initial row count, with no intermediate row counts sent to the fixture Mac.
Both alternate keyboard screenshots were visually reviewed. The emulator is
stopped and the Pixel was not connected; no physical test or signed release is
claimed by this checkpoint.

Local evidence: `captures/runtime/keyboard-fence-build.txt`,
`keyboard-fence-ui.txt`, and `keyboard-fence-screenshots/` in that directory.
Final APK SHA-256:

- Debug: `7c86c9e37238b8ebb294d216437a19ecaba83efe4082e26f37adb2348221d9fd`
- Test: `1d83f685bebcece777261bdb8b61bd31dac7e51a6bd72c9a9e33242e5ec72df5`

**Next at this checkpoint:** iOS also freezes the last good alternate-screen presentation until
all three conditions hold: transition ended, matching viewport acknowledged, and
post-acknowledgement redraw presented. The geometry fence/target work does not yet
implement that presentation transaction. Real Pixel/Gboard, interrupted animation,
rotation and Mac shared-policy/detach/reattach acceptance remain pending.

## Alternate-screen presentation transaction (2026-10-03)

`KeyboardTransitionPresentationFreeze` ports the pinned iOS transaction: an old
frame stays visible until the keyboard transition ends, the exact viewport report
is acknowledged, and output applied after that acknowledgement reaches a new
render submission. `TerminalKeyboardPresentation` binds it to one connection and
surface, the existing viewport generation and monotonically increasing grid
revision. Stale acknowledgements, pre-acknowledgement output and a draw using an
older composition revision cannot release the held frame. Reversed transitions
replace their target; unchanged grid sizes need no nonexistent resize/redraw.
Switching to primary/shared mode or a failed viewport report cancels the hold.
As in iOS, five seconds of silence after the transition also releases it; a valid
confirmation or post-confirmation output restarts that wait. Stale callbacks and
pre-confirmation output cannot extend it. A superseded timer cannot release a new
transition.

`TerminalKeyboardFrame` retains two Compose display lists. One preserves the last
accepted draw, including text, cursor and terminal images; the other records the
replacement. The old frame remains at its captured position in the stationary terminal pane;
the moving dock clips it without translating, reflowing or stretching its pixels. After recording the required live revision, the same Canvas pass
chooses the live layer atomically. Android therefore uses a completed display-list
record and same-frame layer selection; it does not claim an iOS/Metal presentation
callback. The renderer already retains bitmap references safely for display lists.
The extra layers are used only for unshared alternate screens and are disposed
with their composition. Accessibility text follows the accepted frame. Mouse/file
taps, scroll and pinch cannot act through replacement geometry while old pixels
remain visible; terminal keyboard input retains its existing ordered delivery.

Source: `KeyboardTransitionPresentationFreeze.swift`, its tests, and the overlay /
five-second silence fallback in `GhosttySurfaceView.swift` at the same
pinned upstream revision `0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`.
Android drawing uses the official [Compose graphics layer recording API](https://developer.android.com/develop/ui/compose/graphics/draw/modifiers).

**13 focused JVM tests passed**, zero failures/errors/skips: nine presentation
transaction/controller tests and four viewport-fence tests. **14 emulator UI tests
passed in 38.098 seconds** on the existing API 37 / 16 KiB AVD. The three new layer
checks compare pixels and accessibility text through pre-ack output, confirmed
redraw before animation end, stale surface callbacks and the actual silence timer.
A pixel-array comparison requires held text to remain at its original position.
The full native flow delays the post-resize replay, verifies retained pixels and
blocked mouse taps, then checks the new pixels after replay. The existing target
resize/dismissal, primary keyboard, alternate touch, byte recovery, grid/byte frame
retention on switch, shared zoom and keyboard reveal checks also pass.

The final APK build passed in **24 seconds**. Final held/released screenshots were
visually reviewed. Earlier captures exposed the incorrect bottom translation;
reading the complete iOS overlay implementation led to fixed-position retention
and its five-second silence fallback, both covered by the final run. The existing
emulator is stopped; no additional AVD was created.

Ignored local evidence: `captures/runtime/keyboard-freeze-verified-build.txt`,
`keyboard-freeze-ui-final.txt` and `keyboard-freeze-screenshots-final/` in that
directory. Final APK SHA-256:

- Debug: `e77fd11847b7d86dc9917cd7986d6be370d13aadc2c2920e5ac7b26525ee7fe0`
- Test: `993ff8aa05191a8e41d637cced934e752ad3cea97dd8d6c7df9f82f41d41589c`

Physical Pixel/Mac verification, image-heavy transition/performance measurements,
hardware keyboard checks and broader accessibility coverage remain open. No
physical device was connected, and no signed release was published for this work.

## Bounded content measurement and image protection (2026-10-03)

Primary keyboard blank-space absorption now includes renderable Ghostty image
placements as well as text and cursor rows. Placement offsets, source cell size,
fractional scroll clipping and viewport intersections determine the bottom edge;
virtual/missing/offscreen/empty placements do not create phantom content. The
measurement uses the same owned graphics snapshot cache as the painter and does
not copy image pixel payloads. An image below the cursor can therefore move above
the keyboard even when the lower terminal rows contain no text.

The text scan runs only when primary-screen keyboard intrusion is positive. It
starts at the lowest row, stops at the first content row or the known cursor
bottom, and shares its visible row list with the renderer. A cursor on the last
row needs no text scan. Work is capped at 131,072 units, charging UTF-8 text bytes
plus row/span/placement visits. Counting structural visits also bounds adversarial
empty spans. If that budget is exhausted, the result is unknown and blank-space
absorption is disabled conservatively; unmeasured content cannot be treated as
blank and covered by the keyboard.

The upstream reference caps its serialized viewport text at 128 KiB and throttles
native text reads on its output queue. Android's measurement consumes already-owned
spans, avoids the extra read when the keyboard is closed, and shares visible rows
when open. This bounds the additional scan; it does not claim that all JNI parsing,
row extraction or rendering fits a frame budget. Those broader timing measurements
and physical Pixel acceptance remain open.

**14 focused JVM tests passed**, zero failures/errors/skips, including bounded
large-buffer/empty-span/UTF-8 scans, cursor short-circuiting and the existing pinned
keyboard/shared-layout reference cases. **12 emulator checks passed in 23.679
seconds** on the existing API 37 / 16 KiB AVD. They verify a real Ghostty image below
the cursor remains above the keyboard, the renderer reads visible rows only once
per update with the keyboard open or closed, fractional-history image bounds,
and retained hardware image pixels surviving cache replacement until the resize
transaction releases them. Existing placeholder-image rendering, keyboard layout,
frame-hold/timeout, primary submission and raw screen/recovery checks also pass.

The final debug/test APK build passed in **18 seconds**. The placeholder and
fractional-history image captures were visually reviewed; the additional primary
image and retained-image checks use exact pixel assertions. The emulator is
stopped and the Pixel was absent. No signed release was published by this work.
Local evidence is under `captures/runtime/content-bottom-final-build.txt`,
`content-bottom-ui.txt` and `content-bottom-screenshots/` in that directory.
Final APK SHA-256:

- Debug: `b8cc55f295852f48d2b691a44f81e4f96a99e5477ea4b07479d28fa7b315174d`
- Test: `fac232631295265ad1ef081103d2dd2fb89b720fe570a124d9096afb7d7f8e7f`

## Full integration acceptance checklist

1. Subscribe to `mobile.terminal.size_state` and `mobile.terminal.detached`; decode
   sizing fields from replay, retain detach per account/Mac/terminal across reconnect,
   and reject events/results from replaced connection owners.
2. Gate viewport, replay, scroll, mouse and all input paths while explicitly
   detached. Network detach follows normal recovery. A late replay must never
   reopen traffic after an explicit detach.
3. Add the iOS-equivalent bounds/participant presentation and size sheet: policy,
   priority/fixed dimensions, counts override, disconnect participants/others.
   Mutations must be bound to the selected owned terminal and not auto-retried.
4. Add detached presentation with actor/time and explicit Reattach / Reattach as
   viewer. Only successful host acknowledgement reopens terminal traffic, then
   normal replay owns output recovery. Rejected/uncertain replies remain detached.
5. Check Android device identity against the host's device-kind vocabulary (`mac`,
   `iphone`, `ipad`, `tui`, `browser`, `unknown`); do not silently claim the Pixel is
   an iPhone or change shared-host policy as a test workaround.
6. Verify policy negotiation, explicit detach/no automatic reattach, viewer
   reattachment and input admission on an owned Mac/Pixel test workspace; review
   the actual UI. Keep older-host compatibility and account isolation.

No production subscription, host policy, user terminal or device identity has been
changed by this checkpoint. Raw source/diagnostics are ignored under
`captures/runtime/pixel-resume-20261002/`.

## Prepared physical Gboard check

`LiveNativeUiCheck` now accepts `cmux_live_build=nightly|stable` (exact selection)
and optional `cmux_live_gboard=true`. It launches the selected saved Mac through
the real pairing Intent, tracks Activity destruction directly, and writes a
receipt bound to the selected host/account before mutations. The Gboard branch
uses accessibility-labelled keys to type `echo cmuxgboard` in direct mode and
requires one exact output line, then restores compose mode before reopening.
No guessed key coordinates or framework text injection are used for that branch.
The existing composer portion still uses framework injection and is labelled so.

Both test APK builds passed (42 s initially, 19 s with shared-size assertions).
The revised check has not run: Pixel remains locked. A private keyboard hierarchy
is saved only if the test reaches the installed Gboard; it may contain personal
keyboard suggestions and must not be committed or published. The earlier failed
run did not reach this capture, so stale screenshot files pulled from its shared
device directory are not new evidence.
