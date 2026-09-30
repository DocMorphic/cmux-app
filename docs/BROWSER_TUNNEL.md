# Mac browser tunnel — 2026-09-30

Status: native tunnel client and SOCKS/router implemented, with JVM and Android
socket checks. The phone-local WebView does **not yet route through the Mac**.
Production owner/capability wiring, isolated browser networking/storage and
physical browser acceptance remain open. Signed 284 predates this work.

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

The fixture generator compiles the two **unchanged** pinned Swift wire sources
and the unchanged `CmxLoopbackHost.swift` classifier. Minimal type declarations
satisfy its unused route/endpoint overloads; the host classifier is not modified:

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

## SOCKS/router checkpoint

`BrowserSocksProxy` binds only `127.0.0.1`, accepts SOCKS5 no-auth CONNECT for
domain, IPv4 and IPv6 destinations, and confirms the exit before returning
success. It reads handshake fields exactly so pipelined TCP bytes remain intact.
The relay keeps one bounded chunk per direction, applies backpressure, propagates
half-close independently, and aborts both directions on failure/cancellation.
Stopping the proxy closes accepted sockets and joins their borrowed backend
lifetimes. A 15-second handshake deadline and a 256-connection cap bound local
resources, including incomplete handshakes; excess sockets close immediately.
Mac feature lanes have their separate 32-lane limit, released on failure or close.

`MacBrowserRouter` implements the reviewed per-destination policy. Only a Mac
policy denial **before** the connection callback permits non-loopback fallback.
Refusals, timeouts, cancellation, and failures after bytes may have transferred
never replay a request through the direct backend. `MacBrowserLaneBackend` looks
up its provider on each open, retains the RPC client's scoped callback through
the relay, and refuses excess lanes instead of waiting behind browser traffic.
The production provider still needs to enforce the exact account/Mac/build and
freshly negotiated capability; this layer is not yet wired into `NativeScreen`.

`NioBrowserSocket` uses Android's API 26
[asynchronous socket channels](https://developer.android.com/reference/java/nio/channels/AsynchronousSocketChannel)
for accepted sockets and direct exits. Waiting socket I/O does not park a thread
per connection. Direct DNS uses a separate bounded executor, and direct connects
have a 15-second deadline. Mac-bound names are passed unchanged to the Mac backend
without phone DNS. The socket adapter is also used by the test echo exit; that
does not establish real Mac/Iroh tunnel acceptance.

The updated fixture contains **69 Swift-generated loopback classifications**,
including IPv6 embedded IPv4, legacy decimal/octal/hex IPv4, trailing dots and
numeric overflow behavior. One initial mismatch showed Darwin's single-part
IPv4 truncation; Android now matches it rather than using Java's different numeric
resolver to choose the network. No host destination permission is inferred from
this classifier; the admitted Mac continues to enforce its own exit policy.

Verification: **18 JVM tests passed** (five proxy, three routing/provider, ten
existing wire/lifetime tests). Real local TCP checks transfer 196,613 binary
request bytes plus a response suffix through fragmented/pipelined handshakes,
domain/IPv4/IPv6 request forms and both half-closes. Other checks cover refusal
mapping, rejected authentication/commands/addresses, capacity and timeout release,
provider cancellation and proxy shutdown with a parked relay.

**One Android 17 / API 37 / 16 KiB runtime test passed in 0.152 seconds**, through
the actual Android asynchronous channels with a 131,099-byte binary request and
response tail after client half-close. It uses generated data and a local echo
server, without account/terminal/WebView access. The owned emulator used host GPU
mode and was stopped afterward. No UI, browser-origin, HTTP(S), WebSocket or
live Mac behavior is claimed by this byte-stream test.

- Debug APK SHA-256: `4dd93a0b0311c82ac46203bebac3abe5f3d3bb5e90f72534c0ef0fac7d22eff8`.
- Test APK SHA-256: `ffa83ca147e34209b615a18d32ffc8d2338f23318e53531b6b5317d50575e68b`.
- Ignored evidence: `captures/runtime/browser-proxy/`.
- Pixel APK and signed 284 are unchanged; no production proxy starts yet.

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

Then integrate the tested proxy/router with the owner-bound provider and
availability UI, reconnect/session retirement, ten-second policy refresh, profile
lifecycle and real browser acceptance. Do not silently redirect a Mac
localhost page to phone localhost when its connection is unavailable.
