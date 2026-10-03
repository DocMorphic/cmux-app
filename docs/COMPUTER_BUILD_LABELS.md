# Computer build labels and foreground presence

## Source scope

Scoped reference: cmux `0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`.
The global parity pin remains unchanged.

- `Packages/iOS/CmuxMobileShell/Sources/CmuxMobileShell/MacBuildChannel.swift`
  and its tests define Stable, Nightly, RC, Staging, DEV and tagged DEV labels.
- `MobileShellComposite+PairedMacAliases.swift` resolves live presence first,
  then a stored tag. `PresenceMap.swift` restricts tagged rows to their exact
  instance and legacy untagged rows to a sole instance on that device.
- `PresenceClient.swift`, `PresenceServiceConfiguration.swift`,
  `PresenceSnapshot.swift`, `PresenceDevice.swift`, `PresenceInstance.swift`
  and `PresenceUpdate.swift` define the authenticated snapshot/delta stream.
- `Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/LocalizedMacBuildLabel.swift`,
  `WorkspaceMacBuildLabelResolver.swift`, `WorkspaceListView+MacSelection.swift`
  and `WorkspaceMacTitlePickerMenuButton.swift` supply the picker presentation.
  Add Computer is offered when its action exists, captured for one opening.

## Android behavior

`NativeMacBuildLabel` translates the same bundle-component/tag rules. A default
tag with a Nightly bundle is Nightly; unknown bundle channels are not inferred
from a suffix. Custom tags produce DEV labels. Without presence, the source's
canonical tag fallback applies; in that fallback only, default means Stable.
An unidentifiable row has no subtitle. Labels never select a transport, admit
a build or change device/pairing identity.

`NativeMacPresenceRuntime` subscribes to the official production-auth presence
endpoint with a bearer access token and explicit current team header. Redirects
are disabled. One subscription is shared by foreground Activity owners; stopping
the last owner, signing out, changing account/team generation or closing the
runtime retires it. No token or presence map is persisted. Every callback is
checked against its original team scope, and the screen checks scope again before
using metadata. Fixture screens with an injected connector do not start this
network client.

Every connection requires a team-matching snapshot before deltas. Snapshots
replace the map; online/offline/routes events update display metadata, while seen
ticks cannot invent a computer. UUID case is canonicalized; instance tags stay
separate. Duplicate snapshot identities and mismatched device rollups are
rejected. The projection reads build metadata only: it does not import advertised
routes, create saved computers or assert that a connection is authorized.

Parsing is bounded to 4,096 instances and 2,097,152 UTF-16 units per incoming
text frame (2 MiB for binary frames), with an
eight-frame receive queue. Overflow retires the subscription and reconnects for
a fresh snapshot. A 15-second initial-frame timeout, five-minute stream deadline,
30-second WebSocket ping, capped exponential backoff and HTTP 429 Retry-After
handling bound retries. HTTP 401 requests a refreshed token. Team/background
cancellation interrupts waits and discards late token completion. Failed streams
clear the live metadata and use the existing tag fallback. Failure of this
optional display stream does not block terminal connections.

The picker captures build subtitles together with names, checkmarks and actions,
so live updates appear on the next opening. Add Computer uses the iOS label,
plus affordance and separator; a missing callback hides it on the next opening.
The production Android shell provides its existing computer/pairing navigation
callback. Menu actions retain their current account/pairing admission checks.

## Verification scope

Production presence-service interoperability on the physical Pixel and Mac is
still pending. This implements the build-label consumer of presence, not the
entire upstream presence feature set (reconnect-route updates,
workspace presence announcements and presence-triggered push recovery). Those
must be audited individually rather than inferred from this subscription.
Row online/last-seen display and verified history were subsequently added in
[Computer presence rows](COMPUTER_PRESENCE_ROWS.md).
Included in [signed build 468](PIXEL_INSTALL.md), with CI, independent packaging
checks and a signed-out emulator upgrade verified. Physical acceptance remains open.


### Local test evidence — 2026-10-03

Fifteen JVM tests passed with no failures/errors/skips: seven label/reducer/retry
cases, four local WebSocket runtime cases and four existing saved-pairing guards.
The label table includes 21 upstream/channel edge examples. Runtime fixtures
verify the actual HTTP path and auth/team headers, frame delivery, background
shutdown, scope replacement, rejected old callbacks, 401 token refresh,
wrong-team restart and a delayed token from a retired login.

The first Android run passed eight of nine cases. The remaining existing
workspace test asserted filter replacement immediately after clicking a Mac,
before its asynchronous handshake completed. Its assertion now waits for the
committed filter, matching the already-tested pending-switch contract.
The run also captured an emulator System UI ANR dialog over the menu; a subsequent
cold boot showed the same dialog before tests started. Both initial screenshot
and UI dump are retained. Activity manager reported no last ANR, so the OS stall's
cause is not established. Closing the emulator's System UI dialog restored a
usable launcher without wiping app data. The targeted two-case rerun passed in **29.141 seconds**, verifying the
corrected workspace wait and recapturing the menu. All nine distinct Android
cases have passing results across these runs on API 37 / 16 KB.

The clean menu screenshot was visually inspected: Stable/Nightly subtitles,
selection mark, Add Computer separator/plus and text render without clipping or
a system overlay. This is an isolated component fixture using the app theme and
Surface; it is not a full-shell or physical-device visual comparison.

Debug/test builds passed in 42 seconds; the final instrumentation-only build took
15 seconds. Packaged attribution matches its source. Evidence is under
`captures/runtime/computer-build-labels/` (ignored). No new AVD or signed APK was
created. The existing emulator was stopped after verification; the Pixel was
absent from ADB.

| Artifact | SHA-256 |
| --- | --- |
| Debug APK | `4cd9d6dc6ff073ce4faec6f2aedf1f0195c5095a2563474c6f80b2e5cdc80865` |
| Initial test APK | `d5ca9e6bda1c13c473d0da647e3424916b4284749a9d8e0d54ecc3c2072e396b` |
| Final test APK | `50396d2fd66b1f3de93f62b00f6da89a9f9e4640309e8d1b65dc664b5fd74338` |
