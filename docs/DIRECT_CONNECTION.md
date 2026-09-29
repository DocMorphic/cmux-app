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

## Remaining wiring and acceptance

This checkpoint is the native transport prerequisite, not an exposed Direct
selector. The production backend still uses its automatic endpoint. Integration
must add per-account/team/device/build method and address storage, a separate
backend endpoint sharing the enrolled key, intent-aware connection reuse and
invalidation, and the iOS-style method/address controls. A save must not reuse an
old automatic lease after selecting Direct or silently fall back when its address
list is empty/unreadable. Tailscale authorization and saved route management also
remain separate work.

Real LAN/VPN Mac and Pixel acceptance is pending. ADB detected no physical phone;
no account, Mac setting or native registration changed. No full app APK was
assembled for this transport-only checkpoint. The last app APK is the Forget
integration (`41ac206`), the last Pixel installation remains `f0dfc7f`, and the
published signed APK remains build 157. The emulator was stopped after testing.
