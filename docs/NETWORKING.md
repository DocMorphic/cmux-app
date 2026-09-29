# Android networking parity

Reference: cmux `4c5272e9153eca2033c9f40ac749f0c3a5bcb291`:

- `Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/MobileIrohSettingsView.swift`
- `Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/MobileIrohConnectionCheckSection.swift`
- `Packages/Shared/CMUXMobileCore/Sources/CMUXMobileCore/CmxIrohSettingsSnapshot.swift`

## Connection Check

Settings now exposes a bounded check against the selected, connected Mac. It
verifies the saved Mac device/instance identity through `mobile.host.status`, then
performs an authenticated `mobile.workspace.list` read. It sends no terminal input
or workspace mutation. A ten-second overall deadline bounds the check; ordinary
caller cancellation propagates. A borrowed UI lease cannot read diagnostics after
release, and changing the selected connection discards its old UI result.

The existing pinned native FFI exposes live selected-path snapshots. Android now
reads those on an admitted connection and projects them inside the Iroh module to
an address-free route enum and optional RTT. Only a uniquely selected native path
is attributed. IP paths distinguish public direct routes from LAN/private VPN,
including IPv4 private/link-local/loopback, IPv6 private/link-local/loopback and
100.64.0.0/10. Numeric parsing performs no DNS lookup. Absent, conflicting or
malformed selected-path evidence reports no live route. RTT overflow is unknown.

The screen shows route, native encryption evidence, identity, account access,
transport RTT and authenticated RPC response duration. Legacy TCP is explicitly
“Not Reported” for route/encryption: the application cannot infer an external
VPN's security or claim native QUIC from TCP success.

The share action opens Android's chooser with a report containing only fixed
labels, booleans and durations. No computer name, peer ID, network address, relay
URL, credential, terminal content or raw exception text enters that report.
Failures use fixed suggested actions; they do not echo server error bodies.
This report is a check result, not a continuously sampled connection monitor.

## Remaining networking requirements

The new check does **not** complete the iOS Networking screen. These requirements
remain part of the goal and have not been replaced by status-only UI:

- Verify the signed relay catalog, policy sequence/expiry and fallback rules.
- Automatic / selected managed / custom relay preferences, enforced in endpoint
  creation and credential renewal without accidental provider fallback.
- Custom relay add/edit/remove/test, synchronized non-secret configuration and
  device-local protected secrets.
- “Never Use Relays” enforcement and per-Mac private address configuration.
- Confirmed networking reset with the upstream preference/address preservation
  semantics, and applicable debug route constraints.
- Relay policy and reachability stages, managed/custom provider attribution and
  separately shared IT allowlist. The current check does not claim those stages.
- Per-computer detail-screen placement and checks that can discover/dial an offline
  or unselected Mac, matching upstream navigation. The current entry checks the
  already connected Mac from Settings.
- Real-Mac route transitions, physical Pixel sharing/check acceptance and broader
  lifecycle/accessibility checks.

The current runtime still uses authenticated V2 broker relay credentials and
permits direct-path promotion after Irx admission. A configured relay URL is not
evidence that a session's selected route is relayed; the native path snapshot is.

## Verification

Evidence for this checkpoint is kept in ignored `captures/runtime/connection-check/`.
No physical phone setting or real account has been changed by the implementation.

Eight focused JVM tests passed with zero failures/errors/skips. They cover the
borrowed-connection workflow, identity mismatch before authenticated reads,
account rejection/error-body exclusion, honest legacy transport reporting,
deadline versus caller cancellation, closed-lease fencing, numeric private/public
path classification, ambiguous selections and RTT overflow.

The first build hit the existing main composable's JVM method-size ceiling.
Moving diagnostics wiring into `NativeConnectionCheckSettings` resolved the
compiler error; the original build log is retained. The settings heading color
also now uses the existing Settings muted color.

The combined UI run passed **3 tests in 4.804 seconds**: pending/duplicate-check
behavior and sharing, late-response isolation through the actual Settings wiring
when switching Macs, and disabled/failure states. The rendered check was inspected
from `connection-check-private-route.png`; its values are controlled fixture
values. The isolated test activity's system-bar styling is not the full app shell.

The native module then passed **1 test in 0.384 seconds** against an actual admitted
QUIC loopback peer. It queried the real FFI selected-path snapshot, classified the
loopback path as private, read its RTT and verified addresses/session identifiers
do not enter the diagnostic DTO. This is native Android runtime evidence, not a
physical Mac relay/direct transition check. The emulator is Android 17 arm64 with
a 4 KiB kernel. Native ELF and 16 KiB APK ZIP alignment checks pass.

| Artifact | SHA-256 |
| --- | --- |
| Main debug APK | `46707082a9b2658a2e1ca49ff6825bfb4be88def8e16d593956b4daed6ffcb11` |
| App test APK | `85e7523af1f32894b27179f0afe694231b9115f90c4747aca8ab911c93657a40` |
| Native test APK | `e323e3b99fd6542961a0b1aa3a4d3ade4f1774079162852dccc89f177a40df60` |

The receipt, build failure/fix logs, JVM XML, both runtime logs, alignment output
and screenshot are under the ignored evidence directory above. The phone was not
used or changed. Published signed build 157 remains unchanged; full parity is open.
