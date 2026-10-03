# Local computer visibility — 2026-10-03

## Source contract

Scoped upstream revision: 0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc.
Reviewed DeviceTreeView.swift, ComputerVisibilityToggle.swift and
HiddenComputerRow.swift in CmuxMobileShellUI, and
MobileShellComposite+HiddenMacs.swift in CmuxMobileShell.
The global parity pin remains unchanged.

The iOS control hides an exact stored computer/build locally. It retains the
pairing and account binding, removes that computer's workspaces/feed, and
retires its foreground connection if active. Stable/Nightly siblings remain
independent. Hidden rows have an offline unhide switch. Management places hidden
rows in their own section; the disconnected shell also exposes their switches.
The iOS implementation additionally has alias coalescing, user-wide legacy marker
migration and cloud-backup refresh infrastructure.

## Android implementation

- Computers has a Show [name] on this phone switch for each Mac and an
  alphabetically sorted Hidden Computers section with avatar, name/build and an
  off switch. Hidden rows do not select/dial a Mac. The reconnect screen retains
  an unhide path even when every saved computer is hidden.
- Visibility is stored separately from immutable pairing records in the existing
  encrypted local account state. Keys are exact stored origins and their aliases;
  equivalent saved routes are matched only by canonical device, exact tag and
  owning account/team. An untagged legacy pairing is never a wildcard for tagged
  siblings. Android does not import or invent legacy iOS UserDefaults markers.
- Current login, saved pairing and caller's team/connector admission are checked
  in the serialized credential mutation. A stale/replaced/removed row cannot
  create a preference or revive account state. Successful hide clears only that
  target's stored foreground hint/selection. Unhide requires no network request.
- Visible snapshots drive the workspace picker, aggregation, task/browser state,
  notification feed and reconnect list. Paused feeds explicitly prune hidden cached
  sources without dialing retained computers. Discovery cannot add a hidden saved
  device/build back as an available computer. The saved record remains available
  for management and existing Forget cleanup; deleting it prunes its markers.
- Hiding the active or pending target closes/retires its foreground route, input,
  pane and reconnect state. Current-record checks also filter hidden computers
  from feed/browser access, terminal traffic, notification workers, dismissal
  outbox and background reply admission. Showing one permits the existing feed
  policy again; it does not select a foreground workspace by itself.
- Before/after saved-connection admission and transactional pairing persistence
  reject hidden records. A late host handshake cannot silently unhide or enrich
  a hidden record. Identical routes belonging to a different account/team are
  not blocked by another scope's marker.

No remote account binding is revoked by these controls. Existing remote Forget
remains a separate confirmed action. Android account sign-out clears this local
encrypted state, including its visibility preferences. Android's origin/alias
schema is different from iOS's per-user/team hidden pairing IDs; full cloud
backup/coalescing migration parity has not been established by this work.

## Remaining acceptance

Physical Pixel/Mac verification is pending. Configured push delivery and its
hidden-computer presentation still need end-to-end acceptance; the foreground
and notification-worker changes are not proof of FCM delivery behavior.
Version warnings, swipe keep-awake, exact refresh/layout parity and management
rotation restoration remain open. Hidden labels retain Android styling rather
than the iOS row animation. Build 468 does not contain this feature.

## Verification

33 JVM tests passed with zero failures/errors/skips: seven visibility cases,
22 pairing persistence cases, three reconnect projections and one paused-feed
pruning regression. These cover exact build and account/team isolation (even
identical routes), stale login/row/admission rejection, unchanged pairing bytes,
alias preservation, Forget cleanup, discovery filtering and late-handshake
rejection. The feed test connects two local peers, pauses, removes one then all
snapshots, and verifies that retention does not dial survivors.

Initial Android run: five tests, one failure at the first immediate hidden-section
assertion. The follow-up waits for the section's semantic node; that transition
then passed, as did hiding the active Mac, retaining encrypted pairings/login,
returning to the all-hidden reconnect screen and accessing its unhide control.
The follow-up reached a later timeout after tapping the name before waiting for
the visible reconnect row. The final test explicitly waits for Your Computers
and the on switch before reconnecting. Original failure logs are retained.

The management and all-hidden reconnect screenshots were visually inspected:
connected foreground status remains visible after hiding the other computer,
visible and hidden switches render separately, and management/unhide controls
remain accessible when every computer is hidden. These are production Android
screens with an injected local RPC connector, not proof of native Iroh or exact
iOS visual parity.

The final two-case Android run passed in **14.532 seconds** on the existing
API 37 / 16 KB AVD. It verifies background versus active hiding, retained login
and pairings across store reload, offline unhide followed by explicit successful
reconnect, and cancellation/retirement of a delayed handshake without losing the
saved record. The other three cases (management return/selection and both
combined SSH management cases) passed on this final production APK in the
preceding run. Thus five distinct Android cases have passing results across the
recorded runs; this is not a claim that the two earlier full batches were green.

Initial build: 1m 11s. Ownership/attribution checkpoint: 21s. Runtime screenshot
checkpoint: 41s. Feed-pruning follow-up with 33 JVM checks: 49s. Final test-only
build: 17s. Packaged attribution exactly matches the plain source asset. On one
follow-up boot the initial UI dump raced shared storage readiness; a second dump
showed a clean launcher. No new AVD was created. The existing emulator was stopped
and all build/test handles were reaped. The Pixel remained absent from ADB.

Evidence: ignored captures/runtime/computer-visibility/, including all original
and corrected test logs, JVM XML, launcher dumps, screenshots and APK hashes.
No signed APK was produced for this individual feature.


## Signed delivery follow-up

Included in [signed build 474](PIXEL_INSTALL.md), with CI, independent packaging
checks and a signed-out 468 → 474 upgrade on the existing API 37 / 16 KB emulator.
Earlier build-468/pending-delivery statements describe the feature checkpoint.
Physical acceptance and the remaining source/visual scope are still open.
