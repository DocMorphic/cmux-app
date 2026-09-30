# Mac browser tunnel — 2026-09-30

Status: native protocol/client foundation implemented and JVM-tested. The
phone-local WebView does **not yet route through the Mac**. No APK or physical
browser acceptance is claimed for this checkpoint. Signed 284 predates it.

## Audited upstream contract

This partial refresh targets `204a11dfcc76280205e50406ab94270a1c152155`;
the broader implemented reference remains the earlier pin in `PARITY.md`.
Reviewed files in that exact upstream tree:

- `Packages/Shared/CmuxIrxTransport/Sources/CmuxIrxTransport/IrxProtocol.swift`
- The same module's `Tunnel/IrxTunnelWire.swift`, `IrxTunnelClient.swift`,
  `IrxTunnelHost.swift` and `IrxTunnelDestinationPolicy.swift`.
- `Packages/iOS/CmuxMobileRPC/Sources/CmuxMobileRPC/MobileTunnelLaneConnection.swift`
- `Packages/iOS/CmuxMobileShell/Sources/CmuxMobileShell/MobileShellComposite+MacBrowserTunnel.swift`
  and `MobileMacBrowserNetwork.swift`.
- `Packages/iOS/CmuxMobileBrowser/Sources/CmuxMobileBrowser/BrowserServerRoute.swift`
  and its route tests.

The negotiated capability is `browser.tunnel.v1`. An authenticated, admitted
Iroh connection opens an independent `tcp_connect` lane with `host` and `port`.
A length-prefixed JSON response precedes raw TCP bytes. Its version is integer
1; statuses are connected, denied, refused, host_unreachable,
network_unreachable, timed_out, unresolved, busy and failed. Only connected
enables byte transfer. The client reply deadline is 15 seconds. Half-closing the
sending direction must preserve incoming response bytes.

An independent `listening_ports` lane returns version 1, a list of port/address
pairs and Boolean `allowsNonLoopbackHosts`. The Mac retains authority over
destination resolution and policy. The Android parser accepts canonical
loopback literals without phone DNS; it retains multiple addresses for one port.

Upstream chooses Mac-only routing for loopback destinations. Other destinations
use the phone directly when the Mac advertises loopback-only policy. With the
Mac's non-loopback opt-in, those destinations try the Mac first and fall back
directly only on policy denial, never ordinary refusal/timeout. A page stays
bound to its original computer through reconnect. Each Mac network bounds
concurrent tunnel lanes to 32 and refreshes its port/policy listing after ten
seconds. iOS mirrors loopback listeners to compensate for its proxy bypass.

The newer iOS route has a nonpersistent website data store per computer.
Cookies/storage are shared among that computer's browsers, isolated from other
computers and local browsing, and last for the app session. This supersedes the
older persistent local-browser contract described in `LOCAL_BROWSER.md` when
the new routed-browser path is used.

## Android implementation and evidence

`BrowserTunnel.kt` implements strict handshake/status/listing decoding, bounded
raw reads/writes, half-close, access checks before/after suspended operations,
and cleanup on failure. Handshake reads consume exactly the requested frame;
they cannot swallow the first raw TCP bytes. Failed writes are never replayed.
An independent deadline closes a reply reader even if its started read is slow
to observe cancellation.

`IrxMobileRpcTransport` opens feature lanes on the admitted session and retires
both stream halves on closure. `IrxDuplexLane.finishSending()` calls native
`SendStream.finish()` while preserving its receive half. `MobileRpcClient`
exposes a scoped use function, so closing a borrowed lease cancels its tunnel
without closing the shared control connection. Callers must negotiate the
capability before use; production WebView/session integration is still pending.

The fixture generator compiles the two **unchanged** pinned Swift wire sources:

```sh
python3 scripts/generate-browser-tunnel-fixtures.py /path/to/cmux-upstream
```

Its JSON fixture covers descriptors, every status and a mixed IPv4/IPv6 port
listing. **33 focused JVM tests passed**, comprising ten tunnel tests and 23
transport/lease/event-session regressions. Tunnel cases cover every reply
fragment size, binary tails, refusal statuses, half-close, partial writes,
noncancellable reply timeout, revocation during read, invalid/coerced fields,
lease cancellation and unsupported legacy routes. These are protocol and fake
transport tests; real JNI/QUIC/browser runtime acceptance remains open.

## Next integration decisions

Android's [ProxyController](https://developer.android.com/reference/androidx/webkit/ProxyController)
override applies to all WebViews in a process. Its completion callback must
precede loading. [ProxyConfig.Builder](https://developer.android.com/reference/androidx/webkit/ProxyConfig.Builder)
supports SOCKS and removal of implicit loopback bypass rules. That may avoid
iOS's same-port mirrors, but it needs runtime proof with HTTP, HTTPS, WebSocket,
redirects and service workers.

A global proxy switch in the current main process is insufficient: retained
pages or service workers could reach the wrong computer. Establish process or
equivalent network isolation together with per-computer session storage before
wiring the routed WebView. Android WebView profiles alone do not establish
per-profile proxy isolation. Preserve existing artifact viewers' network scope.

Then implement the owner-bound provider and availability UI, reconnect/session
retirement, bounded SOCKS relay, policy refresh, strict fallback semantics,
profile lifecycle and real browser acceptance. Do not silently redirect a Mac
localhost page to phone localhost when its connection is unavailable.
