# Saved computers during discovery outages — 2026-10-03

## Source and intended behavior

Reviewed cmux `0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`:
`Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/DisconnectedWorkspaceShellView.swift`.
The returning-user screen uses the same saved Mac snapshots as Computers, with
presence, last seen, tap-to-reconnect, a per-attempt spinner, re-entry guard and
explicit failure guidance. Discovery enriches the rows; its absence does not
remove a saved pairing. It also has hidden-computer switches, setup help and the
full Computers management entry point; those require further Android integration.

## Implemented

- `NativeReconnectComputers` retains saved row snapshots through loading, errors
  and an empty directory. Matching canonical device/build discovery rows are not
  duplicated; other builds remain separate. Repeated identical discovery records
  collapse, while conflicting new records for one identity are not selectable.
  A legacy pairing with no build tag is not silently merged into a guessed build.
- The picker shows Your Computers and, when needed, Other computers. Saved names,
  routes, build labels, presence/history and older-pairing markers remain intact.
  Reconnecting a saved row uses its original route through the existing connector;
  discovery metadata cannot replace its stored route or grant permission.
- The current account/login/team generation, exact saved pairing and connector
  permission are checked on tap. A discovered row must still be the unique exact
  record in the current team's ready directory. Outgoing team state is omitted
  from the picker while a new team's discovery is loading.
- The list remains visible during a user-selected reconnect, with a spinner and
  Cancel. Repeat row taps cannot start another attempt. Failed startup connects
  with no retained pane or workspace snapshot also expose the list; retained
  workspaces and notifications keep their existing navigation behavior.
- Cancel retires the foreground attempt through the existing effect cancellation.
  Successful pairing selects the verified computer, then returns to Workspaces.
  Selecting the same failed route explicitly retries it immediately. Existing
  Settings and Details remain accessible for correcting an undialable method.
- Credential revisions refresh the saved list. A captured reconnect is rechecked
  before dialing, after the handshake and in the credential save transaction.
  Forget/replacement during the attempt cannot recreate the old record. The
  attempt comparison is cleared after success or route normalization so later
  reconnects can use legitimately enriched pairing records.

The saved/discovery projection itself starts no network calls. Existing feed and
foreground connection policies remain in effect; this change does not establish
iOS's complete refresh/visibility/background-reconnect policy.

## Remaining scope

This closes the directory-only reconnect-list gap. It does not implement the
complete iOS DisconnectedWorkspaceShellView layout, hidden-computer switches,
setup-help presentation, or full Computers layout parity. The subsequent
[management destination](COMPUTERS_MANAGEMENT.md) integrates navigation and SSH.
Version warnings, presence consumers and real Pixel/Mac acceptance
remain open. Build 468 is still the latest signed milestone and lacks this change.


## Verification and fixture correction

**47 JVM tests passed**, zero failures/errors/skips: three reconnect projections,
six computer-list cases, four current-pairing guards, twelve switch recovery cases
and twenty-two pairing persistence cases. The transactional guard rejects removed,
replaced and ambiguous captured records without modifying the credential JSON;
a harmless name change remains valid.

The first Android batch passed **seven cases in 45.753 seconds**: two picker UI
cases, two production reconnect/removal journeys, the existing pending-switch and
cancel/late-client regressions, and automatic retry after a dial deadline.
The production reconnect reached the exact local RPC peer, selected its verified
computer and opened a terminal. Removal during a delayed connection produced the
expected failure without recreating the pairing or exposing its workspace.

Review then added retirement of the captured pairing after successful verification
or route normalization. The first stronger follow-up passed removal but timed out
waiting for replay after its fixture manually changed account ownership while a
terminal was open. That changes the workspace snapshot/input owner and retires the
old pane context; it did not model harmless pairing enrichment. The original
failure output is retained. The corrected fixture has the authenticated host
supply a build tag missing from the legacy saved row, then drops its TCP sockets
without changing account ownership. The final **two-case run passed in 21.446
seconds**, proving replay resumes after that enrichment and reconfirming the
late-removal guard. Seven distinct Android cases have passing results across the
recorded runs; the final two exercised the final production APK.

The full Android picker fixture screenshot was visually inspected: the saved
Studio row remains visible under Discovery unavailable, with Stable badge, Last
seen 2 minutes ago, endpoint and grey status dot; controls fit and no system
overlay is present. It establishes this Android layout, not complete iOS visual
parity or production service acceptance.

Initial build: 1m 4s. Transactional guard/JVM/debug/test checkpoint: 48s. Final
production follow-up build: 46s. Corrected instrumentation-only build: 27s.
Final packaged attribution matches the source asset. The same API 37 / 16 KB AVD
was reused and shut down after verification. The recurring emulator System UI
ANR dialog appeared before testing on the first and corrected boots, was closed
using its observed button, and clean launcher dumps were retained. The middle
boot had a clean launcher. No new AVD was created; the Pixel was absent from ADB.

Evidence: ignored `captures/runtime/reconnect-computers/`, including JVM XML,
all build/test logs (including the failed follow-up), pretest UI dumps, screenshot
and hashes. No signed release build was dispatched for this feature.

| Artifact | SHA-256 |
| --- | --- |
| Initial seven-case debug APK | `ac18f5297dbe171a7386c47a45bd64b6e01b3381906e2ac104b792f79666b735` |
| Initial seven-case test APK | `6d634936f2b914d5daaccc8835fabb5c720833c9adb1481344329266da4f475b` |
| Final debug APK | `cfa4ab8094bd962118a7b50cae7d9eaa3f6bcb8de71ea74b1d23908f30c57b51` |
| Corrected final test APK | `f7420762ab0f14a8d5f013d0b3ffc8ab5a0ec68fd91cb533c80feb0d51bb5ddb` |
