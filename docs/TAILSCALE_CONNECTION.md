# Tailscale connection work

Reference: upstream cmux `4c5272e9153eca2033c9f40ac749f0c3a5bcb291`.
This is an intermediate transport checkpoint, not completed Tailscale-only parity.

## iOS contract studied

The active Detail view offers Iroh, Tailscale Only and Direct. Tailscale Only
without a usable device-bound grant remains disconnected and offers Add Tailscale
Connection. Scanning/pasting a code explicitly authorizes an exact numeric peer
and port for that app session. It does not authenticate the QR's claimed Mac
identity. After authenticated pairing, a separately scoped saved-route grant
permits future reconnects. Externally opened codes do not automatically create
that in-app authorization.

Relevant upstream types are `CmxUserTailscalePairingAuthorization`,
`CmxLegacyTailscaleAuthorizationEvidence`, `CmxTailscalePeerAddress`,
`CmxTailscaleRouteProofValidator`, `CmxSystemTailscaleRouteAuthority`,
`MacComputerDetailView` and `TailscalePairingRegressionTests`.

The iOS path validates one active tunnel, numeric destination, remote peer rather
than self, interface/self-address continuity, and actual local/remote endpoints.
A generation change invalidates the proof. Credentials require another check at
the write boundary. IPv4 peers use 100.64.0.0/10, excluding 100.100.0.0/24,
100.100.100.0/24, 100.115.92.0/24 and 100.115.93.0/24. IPv6 peers use
fd7a:115c:a1e0::/48, excluding fd7a:115c:a1e0::53.

## Android transport checkpoint (2026-09-29)

The existing QR/TCP connector previously selected the first VPN and accepted any
100.64/10 answer. It now requires exactly one visible VPN with a named interface
and Tailscale peer addresses assigned locally. This observes network properties;
it does not cryptographically identify the VPN application or authenticate a Mac.

Numeric parsing is strict and shared with QR validation. It supports IPv4 and
IPv6, canonicalizes IPv6, and rejects service ranges, ambiguous IPv4 spellings,
zones, IPv4-mapped IPv6, loopback and public addresses. Routes cannot contain
userinfo, path, query or fragment decorations. Existing well-formed `.ts.net`
names remain compatible: they resolve on the selected VPN once, and only a
numeric remote peer is retained for that connection. Names are not saved as new
authorization grants by this change.

A TCP socket is created by the selected Android Network's SocketFactory. Before
connect, after establishment, before every write and around reads, the authority
checks fresh network properties. Actual local endpoint must be one of the
captured tunnel addresses; remote address and port must match the selected peer.
A changed/lost interface, changed self addresses or ambiguous eligible tunnels
retire the socket. A VPN callback closes it to interrupt a pending read; a
synchronous check also rejects a write before a queued callback is delivered.
Retirement is permanent for that connection, and observers are released on close.
The selected Network binds routing, including during an underlying-network change;
there is no default-network TCP fallback.

Platform references:
[Network and its bound SocketFactory](https://developer.android.com/reference/android/net/Network),
[LinkProperties](https://developer.android.com/reference/android/net/LinkProperties),
[ConnectivityManager.NetworkCallback](https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback).
The implementation retains fresh `allNetworks` snapshots despite that API's
deprecation; callbacks provide loss/change invalidation. It does not yet implement
iOS's bounded wait for tunnel readiness during startup.

## Verification and limits

Twenty-five focused JVM tests passed, with zero failures/errors/skips:

- Six Tailscale address/path-proof cases.
- Six QR parser cases, including IPv6 and malformed/decorated endpoints.
- Five real local TCP socket authority cases: valid duplex traffic, rejection
  before opening TCP, established endpoint rejection before payload, rejection
  of credential-shaped fixture bytes before a callback, and loss interrupting a
  pending read without allowing revival.
- Eight existing RPC framing, cancellation, timeout and no-replay regressions.

The socket tests inject authority observations; they do not impersonate an Android
VPN or claim physical Tailscale traversal. Production Android sources compiled
as part of the passing JVM run. Logs and XML are retained locally in ignored
`captures/runtime/tailscale-path/`. No APK was rebuilt for this checkpoint,
consistent with batching feature commits before the next device build.

ADB still listed no attached Pixel. Physical validation of Android VPN callbacks,
IPv4/IPv6 sockets, MagicDNS, tunnel loss/reconnect and real Mac pairing remains
pending. The Pixel's last installed checkpoint remains `f0dfc7f`; the latest
previously built debug APK is `45ff44c`. Published signed build 157 is unchanged.
No real Mac, pairing grant, VPN setting or phone power setting was changed.

## Next integration work

- Add explicit ephemeral pairing authorization and captured account/session
  ownership; enforce authenticated expected-device/build identity.
- Persist and migrate device/build/account/team-bound route grants. Existing
  saved QR rows are not equivalent to those grants, and the legacy connector's
  account/team lifecycle still needs that integration.
- Present Tailscale Only, Add Connection, edit/remove route actions in Details;
  preserve native Iroh identity while adding a Tailscale route to the same Mac.
- Route UI, feed, notifications and checks through the same captured method and
  authority; wake disconnected retry loops immediately on route/method changes.
- Add readiness waiting and verify actual Android VPN event ordering on a phone.

The Iroh/Direct selector therefore still exposes only those implemented modes.
