# Android networking parity

Reference: cmux `4c5272e9153eca2033c9f40ac749f0c3a5bcb291`:

- `ios/cmuxPackage/Sources/cmuxFeature/MobileIrxSettingsController.swift`
- `ios/cmuxPackage/Sources/cmuxFeature/MobileIrxRuntimeComposition+Settings.swift`
- `ios/cmuxPackage/Sources/cmuxFeature/MobileIrxRuntimeComposition+Dial.swift`
- `ios/cmuxPackage/Sources/cmuxFeature/MobileIrohV2LocalPathStore.swift`
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

## Active V2 contract correction (2026-09-29)

The previous checklist incorrectly treated every control in the shared/legacy
Networking view as an active iOS capability. Tracing its injected
`MobileIrxSettingsController` changes that conclusion:

- Relay preference accepts only Automatic. Selected/custom relays and their
  add/remove operations throw `unsupported`; custom-relay testing is incomplete.
- Path preference accepts only its already active value (Automatic or the debug
  force-relay setting), so the shared “Never Use Relays” control cannot change the
  active V2 runtime through this adapter.
- Managed relays come from V2 broker credentials; policy sequence/expiry shown by
  this runtime come from the directory revision/permission expiry. The legacy
  signed catalog verifier is not part of this active settings path.
- Per-Mac private paths, disable-on-reset, snapshot refresh and diagnostic report
  operations are implemented. Separate direct-only dial intents also exist in
  the V2 dialing code and remain a distinct Android follow-up.

Android should match this active contract. Legacy signed catalog/custom-relay
controls are reference code, not evidence of a working current iOS feature. This
correction does not remove private paths, active runtime settings or remaining
connection checks from the goal.

## Per-Mac private addresses

Settings supports add/edit, enable/disable, confirmed remove and confirmed reset.
Reset disables all paths in the current scope while retaining their addresses,
matching the active iOS adapter. The editor accepts one numeric IP and UDP port
per line (up to eight); it rejects DNS names, missing/invalid ports, loopback,
wildcard, multicast, link-local/scoped and upstream-forbidden coordinates.
Publicly shaped numeric IPs remain eligible, as upstream permits them: the
coordinate does not establish the Mac's identity.

The atomic file is in Android's private no-backup directory, keyed by the full
V2 identity: environment, project, team, user, device, namespace and build. Paths
are keyed again by the Mac's exact device ID and build tag, capped at 64 records.
Saved input is revalidated on read. Corrupt storage is not silently overwritten;
Settings reports a load/save failure while dialing ignores those optional hints
and keeps the authenticated relay route available.
No route coordinates are uploaded to account/discovery services or included in
connection reports. Mapped IPv6 addresses may normalize to their equivalent IPv4
socket form through the platform parser.

On the next native connection attempt, the backend reads enabled paths for that
exact Mac and supplies them with the existing relay hint to the native endpoint.
Account/directory permission checks, endpoint-key authentication, Irx admission
and post-admission NAT authorization remain in place. Editing does not terminate
an existing terminal or notification lease. The UI states that changes take
effect on the next connection. This is automatic routing with extra coordinates;
a separate direct-only endpoint is not implemented by this checkpoint.

## Remaining active networking requirements

- Active V2 runtime snapshot/status, credential relay attribution, directory
  revision/permission expiry, refresh and applicable debug route constraints.
- Separate direct-only endpoint/intents, preserving upstream admission and
  endpoint-wide relay-policy isolation.
- Per-computer detail-screen placement and checks that discover/dial an offline
  or unselected Mac. The existing Connection Check checks the connected Mac.
- Applicable reachability stages, diagnostic log export/clear, and shared IT
  allowlist. Do not claim tests for stages the active adapter reports unavailable.
- Real-Mac custom-address reachability and route transitions, physical Pixel
  settings/share acceptance and broader lifecycle/accessibility checks.

A configured relay or private coordinate is not evidence that a session selected
that route; the live native path snapshot is.

## Connection Check verification

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

## Private-address verification

Evidence is in ignored `captures/runtime/private-addresses/`. The combined main
and instrumentation builds succeeded. **19 JVM tests passed** with no failures,
errors or skips: eight address/store cases and eleven account/runtime cases,
including rejection of edits from the wrong team or a retired login. Coverage
includes address limits and canonicalization, forbidden numeric/DNS inputs,
exact Mac/build selection, disabled paths, reset preservation, storage corruption
and every identity-scope field.

The two Android UI cases exercise the actual Settings section with controlled
storage: invalid input cannot save, valid IPv4/IPv6 paths can be enabled and
reopened, reset requires confirmation and keeps coordinates, remove deletes the
record, and a failed write keeps the editor/draft available for retry. The first
run passed both cases in 13.363 seconds. The rendered editor was inspected; this
isolated activity does not represent the production app's system-bar styling.

On the final APK, the edit/reset/remove case passed. The save-retry case reached
activity teardown, which timed out waiting for DESTROYED (last state STOPPED);
concurrent emulator events record a Google Play Services AutofillService ANR.
The affected test then passed alone in **2.501 seconds**, including teardown.
The failed run and event log are retained. The final screenshot also verifies the
enabled switch's settled appearance and accessible label. An initial install
attempt during cold boot was rejected by Android; installation succeeded after
`sys.boot_completed=1`. No test assertion was weakened to obtain the retry pass.

Native ELF and 16 KiB APK ZIP alignment checks pass. No generated FFI or native
library was changed. The Pixel was not used, no native listener/account was
changed and no signed release was published. Custom-coordinate Mac reachability
and route selection are **not established by these fixture tests**.

| Artifact | SHA-256 |
| --- | --- |
| Main debug APK | `5f1d6c4b4f59e2b54076771d0dc93386e8a4b672821ee59148f8b3d2a4722e60` |
| App test APK | `91f2dfa218e3065f915d516f44324bb63bbf5f96b9d128e314cd6d5d3f86f4b1` |
