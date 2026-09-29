# Direct connection mode

Reference: cmux `4c5272e9153eca2033c9f40ac749f0c3a5bcb291`.

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
discovery-independent reconnect and full iOS visual/interaction comparison remain open. The full companion goal is active. The production direct-backend fixture
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
