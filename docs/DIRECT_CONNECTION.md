# Direct connection mode

Reference: cmux `4c5272e9153eca2033c9f40ac749f0c3a5bcb291`.

## Direct port audit and legacy reconnect correction (2026-10-05)

The previous optional-port finding came from the shared candidate model, not the
current editor/dial path. Further source inspection at
`186cec79781256867ad4516f0802118738bd2393` resolves it:

- `MacComputerDetailView.parseDirectAddress` uses `CmxIrohLocalSocketAddress`,
  which requires an explicit UDP port. Its Add/Edit alert says a port is required.
- `MobileIrxRuntimeComposition+Dial` filters out candidates whose optional port is
  missing or zero and fails Direct when no valid candidate remains. It does not
  discover or invent a port.
- `CmxIrohDirectDialCandidate` explicitly documents that v2 rejects missing ports.

Android's required-port editor and socket-address representation match this scoped
behavior; no optional-port change is needed. This corrects the inference in the
previous checkpoint, without claiming a full Direct UI/runtime audit.

Follow-up review also found that the saved-peer refresh change rejected historical
saved native rows whose authenticated build tag has not yet been recorded.
The saved connector now retains their verified device ID and captured account,
and lets them reconnect only to their literal endpoint. The live directory's
device and supported build are still checked, followed by the existing host
admission. Only a complete saved device/build target can select a replacement
endpoint. A missing device, conflicting identity, retired account, unsupported
live build, or changed endpoint in this legacy path fails before dialing.

**35 focused JVM tests passed** (27 native runtime and eight Direct settings),
including the new legacy paths; main and instrumentation Kotlin compile. Physical upgrade,
legacy-row enrichment and reconnect acceptance remain open. Evidence:
`captures/runtime/legacy-saved-peer/`, including exact upstream source hashes.

## Saved native peer refresh (2026-10-05)

The scoped iOS `MobileShellComposite+ReconnectRoutes.swift` and
`MobileShellComposite+ConnectionMethod.swift` at
`186cec79781256867ad4516f0802118738bd2393` resolve saved-route refreshes by
account, device and build, and retain the pairing's own method. Android previously
required the saved Iroh endpoint ID to remain in the current directory, so a
re-registered Mac could be visible but fail to reconnect from its saved row.

The saved-record connector now supplies the verified device/build and captured
team to a separate native reconnect entry. It selects the unique current
directory record for that identity. Account, build audience, directory permission,
peer admission and authenticated host compatibility checks remain enforced.
Unstored/fresh pairing still requires its literal endpoint ID. Missing/ambiguous
identities and sibling builds cannot substitute. The current directory endpoint
owns the pooled connection; changing it retires the old wire. Per-build Direct
addresses and Tailscale-only selection remain authoritative through the same
existing method dispatch.

The foreground reconnect key now follows device/build too, including older codes
whose missing identity hints can be supplied by their validated saved record.
This allows a directory update to wake reconnect without aliasing a reused old
endpoint to a different computer. Stored locators, stable origins and ticket
bindings remain intact; endpoint refresh is resolved at dial time.

**47 focused JVM tests passed** (25 native runtime and 22 saved Tailscale).
They cover old-versus-current endpoint behavior, wire retirement,
Direct preference preservation, ambiguous/missing/sibling/account rejection and
reconnect keys, alongside saved Tailscale admission regressions. Main and Android
test Kotlin compile. Initial failures were an omitted port in the new Direct
fixture and an existing close assertion racing transport retirement. The latter
now waits for the actual closed state with a two-second bound; it still checks
that revocation prevents workspace requests and connection publication.

Evidence: `captures/runtime/saved-peer-refresh/`, including failed and final checks
and upstream source hashes. No APK or device run is claimed. Physical endpoint
rotation, saved ticket workflow, network transitions and UI acceptance remain
open. The initially noted optional-port question is resolved in the correction
above: the current iOS editor and v2 transport require an explicit port too.
This scoped change does not close the full route-policy audit.

## Optional remote relay hint (2026-10-05)

The scoped upstream audit at `186cec79781256867ad4516f0802118738bd2393`
confirmed that `MobileIrxRuntimeComposition+Dial.swift` passes an optional remote
relay hint. `Packages/Shared/CmuxIrxTransport/Sources/CmuxIrxTransport/IrxEndpoint.swift`
then forwards that optional value and private addresses to native `EndpointAddr`.
Its automatic endpoint still requires usable local relay credentials before
binding. Missing remote metadata and missing local credentials are different
conditions. Source copies and hashes: `captures/runtime/transport-route-audit/`.

Android now preserves that distinction. `NativeIrohBackend` no longer rejects an
otherwise eligible Mac just because both Mac and directory relay hints are absent.
`IrxDialTarget` allows a null automatic hint and retains enabled private candidates;
identity-only targets reach native dialing without an invented relay. Supplied
hints retain HTTPS validation, exact peer identity validation remains, and Direct
strips relay hints and still requires explicit candidates. Mode-specific limits
remain eight automatic hints / sixteen Direct candidates. Candidate lists are
snapshotted before dialing. Binding credentials, account fences, peer admission,
timeouts and post-admission NAT authorization remain in the existing runtime.

**39 JVM tests passed**, zero failures/errors/skips: eight new dial-target cases,
21 runtime cases, eight connection-setting cases and two native transport cases.
CI's existing batched APK job now runs `:iroh:testDebugUnitTest` too. Debug app,
app instrumentation and Iroh instrumentation APKs built successfully.

**25 Android checks passed** on the sole API37 arm64 / 16 KB AVD:

- Nineteen native admission/Direct/status cases, **6.550 seconds**, including a new
  real local QUIC admission and duplex exchange using the production automatic
  target policy with no remote relay hint. Endpoint binding in this new case is
  a local fixture; it does not prove production relay readiness. Existing checks
  verify that automatic binding rejects absent/expired credentials and that
  Direct strips relays, admits peers and handles retirement/cancellation.
- Six app cases, **38.470 seconds**: the five ticket chooser/rotation/state/
  Keystore cases and the production Direct-backend fixture. The revised route
  chooser passed native-default, explicit Tailscale and explicit native selection.
  Both chooser screenshots were inspected; no compatibility or ANR overlay.

Crash buffer empty; display/sleep settings unchanged; emulator stopped/reaped.
No new AVD was created. Logs, source/APK hashes and receipts are retained in
`captures/runtime/optional-relay/`. Debug APK SHA-256:
`bf13d1a9a00b1499864d1d4869d7885e8fd5d475aa518b9e423eedd2126a2e12`.
This debug batch includes the token-recovery and route-ordering source. It does
not establish real account token rejection/recovery, automatic production relay
binding or physical Pixel/Mac acceptance. Signed **596** remains the latest
verified download and excludes this batch. Full parity remains open.

## Active iOS contract

`MacComputerDetailView.connectionMethodSection` presents Iroh, Tailscale Only
and Direct per computer/build. Direct has a separate ordered list of numeric
IP/UDP-port entries with labels and individual enabled flags. It is distinct
from optional private-address hints supplied to automatic Iroh connections.
No enabled address means disconnected, with no automatic relay fallback.

`MobileIrxRuntimeComposition+Dial` records the peer's dial intent and replaces an
existing session when that intent changes. It refreshes authenticated discovery,
checks Mac registration and scope, and selects a separate direct endpoint using
the same enrolled installation identity. It supplies at most sixteen enabled
coordinates, a nil relay hint, and performs normal Irx admission. Automatic
connections authorize NAT discovery after admission; explicit Direct does not.

`IrxEndpointSupervisor` binds this endpoint with the minimal preset, disabled
relays/port mapping, deferred NAT traversal and zero initial remote stream credit.
It needs no relay credentials and does not await relay readiness. Later credential
rotation cannot add relays. Account authority and Mac admission still apply;
Direct is not offline or unauthenticated pairing.

## Android native endpoint checkpoint

`IrxEndpointRuntime` now has an immutable `DIRECT_ONLY` path mode alongside its
existing default `AUTOMATIC` mode. Direct binds a relay-disabled endpoint without
credentials or a relay readiness wait. It strips relay hints, requires one to
sixteen explicit coordinates, retains peer identity/admission and caller authority
checks, and skips automatic NAT discovery. Credential rotation cannot enable
relays on it. Closing retires admitted sessions and rejects later dials/updates.

Seven new Android tests exercise the production wrapper over real loopback QUIC:
credential-free binding; unchanged automatic credential requirements; enrolled
peer identity/admission and duplex data with an ignored relay hint; missing,
excessive or malformed coordinates; retired authority; authority loss during
admission; endpoint closure; and canceled admission followed by a successful dial.
These are seven test methods; some cover more than one condition.

All eighteen native Android cases pass (`OK (18 tests)`, 9.444 seconds): seven
new Direct cases, ten existing admission cases and one endpoint-status case.
Thirty-six focused JVM regressions pass: thirteen runtime, fifteen computer action
and eight private-address cases. The native instrumentation APK passed ELF
LOAD/RELRO and ZIP 16 KB alignment checks. Evidence and the APK hash are retained
locally in ignored `captures/iroh/direct-endpoint/`.

## Android settings and connection ownership

Computer Details now exposes Iroh and Direct choices. Direct provides labeled
numeric IP/UDP-port entries, individual enable switches, add/edit/remove actions,
and a warning when no address is enabled. An edited address keeps its enabled
state. Duplicate or invalid addresses retain the editor for correction. Failed
writes leave the previous saved mode active; pending writes disable repeat input.
The initial checkpoint exposed only Iroh/Direct; Tailscale Only and authorized
route editing are now implemented in [TAILSCALE_CONNECTION.md](TAILSCALE_CONNECTION.md).

The no-backup settings file is scoped by application, project, user and team; its
entries use canonical UUID device identity plus exact build. Iroh and Direct
addresses remain separate from optional automatic-mode private-address hints.
Settings persist across process restart, while routing epochs are local to the
current process. Read failure blocks dialing, including existing leases, rather
than silently choosing Iroh. Explicit reload is available from Details.

The backend owns separate automatic and direct native endpoints using the same
enrolled key. Direct ignores relay metadata and credentials, while Iroh retains
its authenticated relay and optional private-address behavior. Both endpoints
close on account-owner retirement. The runtime includes each Mac's captured method,
enabled coordinates and routing epoch in its connection key and permission fence.
Changing one Mac's effective route retires only its leases and pending handshake;
changing an address label leaves sessions intact. An A→B→A change cannot revive
an old lease, even if state-flow delivery skips the intermediate value. A corrupt
file/recovery boundary retires all affected settings-owner leases.

The normal UI, feed, notifications and connection-check callers share this same
runtime path, so their next acquisition uses the saved method. No-address Direct
fails before endpoint acquisition, with no Iroh fallback. An existing foreground
session follows the existing disconnect/retry path after retirement. Forget now
clears this Mac/build's method and direct addresses alongside appearance before
the captured pairing-store commit. Other Mac/build preferences remain.

## Remaining acceptance

Physical LAN/VPN Mac and Pixel acceptance is pending, including switching modes
while terminals and background notifications are active. Tailscale-only selection
and authorized route editing now have implementation and focused evidence; its
saved reconnect now runs independently of Iroh discovery. Physical acceptance
and full iOS visual/interaction comparison remain open. The full companion goal
is active. The production direct-backend fixture
uses local QUIC and simulated account authority; it is not proof of live server
admission or physical network traversal.


## Settings integration verification (2026-09-29)

Fifty-eight focused JVM cases pass: eight settings/persistence, seventeen runtime
(including four new routing cases), fifteen computer actions, twelve Forget flow
and six shared-connection cases. Coverage includes per-build isolation, save/read
failure, unknown methods, address validation, duplicate/size limits, UUID aliases,
rapid method changes, canceled pending handshakes, label-only edits and recovery
without reviving stale connections.

Seventeen Android 17 emulator cases passed in **35.133 seconds**: one production
backend/local QUIC fixture, four new connection-settings UI cases and twelve
existing Details/Appearance/Forget cases. The backend uses an isolated encrypted
identity with no relay metadata or credentials and a local native server. It
verifies framed duplex control traffic and rejection after an intent change.
The first run passed sixteen cases and failed one fixture assumption that the
server exposed a single socket; it exposes IPv4 and IPv6. Selecting the fixture's
explicit IPv4 listener fixed the test. No production routing change was needed.

After that run, the toggle's touch highlight was clipped to its circular shape.
The final APK passed all five Direct backend/settings cases in **10.828 seconds**.
The preceding twelve existing UI cases were not repeated for this isolated visual
adjustment. Screenshots of the final section were inspected; its standalone test
Activity's white system bars are not a full-app layout reference.

The first two build attempts exposed test-source compilation issues (a Kotlin
collection/function invocation and use of a module-internal test helper). Both
were fixed; main and instrumentation APK builds now pass. Native ELF LOAD/RELRO
and APK ZIP 16 KB checks pass. No native library was rebuilt in this integration.

- Final main APK SHA-256: `c2eb9d026bdc4bffc11b90d9fd1921ecdc8b76ff3cfeb858af4d9d7678e82596`.
- Test APK SHA-256: `ae52c00691431d230f75400af5810392ce1cb6188f41699b86d07e3d986481ac`.
- Build logs, JVM XML, instrumentation output, screenshots, alignment reports and
  receipts are retained in ignored `captures/runtime/direct-settings/`.

ADB detected no Pixel for installation. Its last installed checkpoint remains
`f0dfc7f`, and published signed build 157 is unchanged. No real Mac registration,
connection preference or phone power setting was changed. The emulator was stopped
when verification ended.

The separate Tailscale transport prerequisite and remaining authorization work
are tracked in [TAILSCALE_CONNECTION.md](TAILSCALE_CONNECTION.md).
