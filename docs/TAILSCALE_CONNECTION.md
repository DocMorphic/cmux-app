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

## Account and saved-route authorization checkpoint (2026-09-29)

The production QR connector now uses `TailscalePairingAuthority`. The visible
Connect confirmation creates an in-memory authorization captured to the current
verified account/team/login generation. Receiving a deep link, reading a saved QR
row, retrying or opening a background connection cannot create this authorization.
A replacement confirmation invalidates an older pending attempt.

After confirmation, numeric QR routes stay exact. For compatibility with existing
Android `.ts.net` codes, the selected VPN resolves a hostname once per confirmed
attempt. That numeric answer stays pinned across failed retries. Another visible
confirmation is required to resolve it again. This DNS compatibility behavior is
an Android extension to the numeric-only iOS authorization evidence; it must still
be exercised on the physical VPN. Multiple QR route hints are tried independently,
and only the successfully authenticated numeric route becomes a saved grant.

Before promotion, the connector obtains host status, checks any previously known
Mac/build identity, and successfully executes the authenticated workspace-list
RPC. Host status alone never creates a grant. The grant stores exact user, team,
canonical Mac device ID, build, numeric peer and TCP port, plus its source QR
fingerprint and unique incarnation. It is stored within the existing Android
Keystore-encrypted credential transaction. The login is checked inside that
transaction; team/connection permission is checked outside the storage lock to
avoid inversion with account refresh, then checked again before returning a
session. A scope change during the commit cannot expose the connection to the new
scope, even if the already-authorized old-scope record was committed.

Reconnects require the matching saved grant and use its numeric coordinate without
DNS. The token provider checks the captured authority before and after obtaining
an access token. The socket authority checks it again at the write boundary and
around reads. Account/team observations and periodic registry cleanup close retired
clients, including blocked reads. Replacing/removing a grant cannot revive a
connection carrying its previous incarnation. Same-user, same-team relogin may
reuse persisted permission; a different user or team may not.

Legacy saved QR rows have no trustworthy account/team authorization metadata and
are deliberately not silently migrated. Those connections need one explicit
scan/paste and Connect confirmation. The connection error explains this; native
Iroh pairings are unaffected. Tailscale saved-list/background callers now filter
through the same grant checks. The local pairing removal clears its source grant;
confirmed native computer removal clears matching user/team/device/build grants
in the same credential transaction as pairing cleanup. Shared host matching now
normalizes UUID case only, retaining case sensitivity for opaque device IDs.

### Authorization verification

All **54 focused JVM cases passed**, including seventeen new authority cases,
the preceding twenty-five transport/parser cases, and twelve existing computer
removal cases. The authority fixture drives real `MobileRpcClient` framing/token
attachment against a simulated Mac transport and an atomic in-memory credential
store. It covers missing confirmation, consumed/retired consent, wrong account or
team, token-acquisition races, authenticated promotion, pinned DNS retries,
replacement confirmation, changed host/build, UUID aliases, failed storage,
corrupt grants, scope changes during probing, grant removal and relogin isolation.
It also verifies exact scope/build cleanup. No real token or Mac mutation is used.

The initial thirteen new authority cases passed. Four additional regression cases
were then added for pinned DNS retries, replacement confirmation, UUID aliases and
scoped cleanup; the final 54-case run passed without failures/errors/skips.
Production Android sources compile. This checkpoint did not build/install an APK
or run Android instrumentation; encrypted-store and UI/device acceptance remain
pending. Logs and XML are retained in `captures/runtime/tailscale-authorization/`.
ADB still detected no Pixel. Published signed build 157 remains unchanged.

## Next integration work

- Present Tailscale Only, Add Connection, edit/remove route actions in Details;
  bind a targeted scan to the expected native device/build, and preserve native
  Iroh identity while adding a Tailscale route to the same Mac.
- Unify route grants with the per-computer connection method used by the UI,
  feed, notifications and checks. The present authority integration covers the
  existing QR/TCP flow; it does not add Tailscale to the Iroh/Direct picker yet.
- Wake disconnected retry loops immediately on route/method changes.
- Add readiness waiting and verify Android VPN event ordering, Keystore grant
  persistence, pairing UI and physical Mac/Pixel behavior.

The Iroh/Direct selector therefore still exposes only those implemented modes.
