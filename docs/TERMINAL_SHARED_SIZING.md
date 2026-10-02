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
