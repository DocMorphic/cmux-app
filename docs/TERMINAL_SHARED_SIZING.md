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

## Required integration

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
The revised APK has not run: Pixel remains locked. A private keyboard hierarchy
is saved only if the test reaches the installed Gboard; it may contain personal
keyboard suggestions and must not be committed or published. The earlier failed
run did not reach this capture, so stale screenshot files pulled from its shared
device directory are not new evidence.
