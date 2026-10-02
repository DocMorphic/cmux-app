# Mac browser tunnel — 2026-09-30

## Physical NIGHTLY discovery and native HTTP — 2026-10-02

The physical Pixel authenticated to the staged official NIGHTLY host through
its existing account directory without adding or replacing a saved pairing.
The directory contained two computers and exactly one `nightly` match; both
host device identity and build tag were verified. The read-only discovery case
passed **1 test in 11.755 s**. NIGHTLY advertises `browser.tunnel.v1` and
`terminal.input.exactly_once.v1`; native browser-port listing succeeds. This first
connection reported the **Relay** route. Its loopback-only browser policy is
retained (`allowsNonLoopbackHosts=false`).

A second run passed **1 test in 7.253 s** using a disposable HTTP server bound
only to Mac `127.0.0.1` on an ephemeral port. The phone opened a native browser
lane to that port, sent one GET for its generated fixture path, and received HTTP
200 plus the exact generated body through EOF. An authenticated host-status RPC
still worked afterward. The server observed exactly one fixture request, closed
its listener and exited. This connection reported **LAN or Private VPN**; these
two runs do not prove network-switch continuity. Test APK SHA-256:
`9cf830a18bd978856833278716ed8b958e91710c68415533dcde5f0908e468a0`.

`LiveNativeBrowserCheck` now supports `cmux_live_discovered=true` together with an
explicit `cmux_live_build=nightly`. It selects one authorized directory entry,
connects through the production connector, and compares the host against that
expected identity. Missing/ambiguous matches fail; it never falls back to stable.
No account stores, user workspaces or terminal input are changed. Optional
`cmux_live_browser_fixture_port` and `cmux_live_browser_fixture_marker` must be
supplied together and target an owned Mac loopback fixture; the marker format is
`CMUX_BROWSER_` followed by 32 lowercase hex characters. Without those arguments,
the check only inspects identity, capabilities, inventory and listening ports.

This establishes real native-lane HTTP interoperability, **not** the full
WebView/SOCKS presentation, saved-pairing UI, HTTPS, file upload/download or browser
reconnect workflow. Those remain open. Debug production APK is unchanged at
`f72f05838b1e441c43bdc154840b3796b4d696f28ec6eaf2297c3435b969e1cb`;
signed build 385 is unchanged. No emulator, cloud configuration or persistent
phone setting was changed. Ignored logs: `captures/runtime/pixel-resume-20261002/nightly-*`.

Status: native tunnel client, SOCKS/router, owner/capability checks, dedicated
WebView process, production presentation, storage retirement and mode controls
are implemented. The dated checkpoints below distinguish fixture verification
from the still-outstanding real Mac/Pixel browser acceptance. Signed build 284
predates this work; debug fixture results do not establish signed delivery.

## Paired-Mac browser modes — 2026-10-02

The paired-Mac streamed browser now offers **Streamed / On Android**, following
[the iOS mode picker at audited candidate 204a11d](https://github.com/manaflow-ai/cmux/blob/204a11dfcc76280205e50406ab94270a1c152155/Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/SSHFiles/WorkspaceDetailView+SSH.swift).
The phone option remains visible but disabled with “Update cmux on this Mac” for
an old host, or “Not available on this connection” for a disconnected connection
or transport without tunnel lanes. A manual switch rechecks fresh network
admission, rather than trusting the capability displayed when the menu opened.
Account login, saved Mac and selected workspace/panel are checked before opening.

On Android loads the current streamed HTTP(S) URL through the existing Mac-bound
network and remembers the phone page for that panel. A remembered page restores
during temporary disconnection, preserving its Mac routing; legacy routes do not
automatically restore that mode. The routed browser can return to its linked
streamed panel (or the first available panel for an unlinked page), forgetting the
phone preference on an explicit mode switch. Older hosts' generic local-browser
fallback keeps no mode picker, matching iOS. Full WebView history across remounts
is not retained. Fresh authoritative Mac inventory also retires active and cached
phone pages for deleted browser tabs; offline and incomplete inventories do not.

**Verification:** 25 focused JVM tests passed without skips (six browser-mode,
thirteen navigation and six network/owner tests). Both APKs built. On the existing
API 37 / 16,384-byte-page emulator, the first 11-case run passed all seven existing
streamed-browser regressions and two production routed-presentation checks,
including exact second-panel selection and preference removal. Its two new mode
UI cases failed: one awaited the wrong RPC name; the other did not wait for each
simulated availability transition to be evaluated. Both test synchronizations
were corrected, preserving the assertions. Those two checks then passed in
**21.524 seconds** on the final APKs, covering reasons, revocation between display
and tap, current-page handoff, and disconnected remembered-mode restoration.
This is incremental evidence, not an all-green 11-case run on the final APK.
The last production change was an additional login/selected-panel guard in
`NativeScreen`; the tested browser components were unchanged between these runs.

Final APK SHA-256:

- Debug: `b1f6b5838b1dc2577274322dd1e548308ee3abdb6540bfc747b67fb57ca0bb93`
- Instrumentation: `05da695ae160365065c3c2509626f4d7d0759756b49385bd41dbddacaef7719d`

Packaged native LOAD/RELRO segments and both APK ZIP alignments pass 16 KB checks.
The routed-page screenshot from the first run was inspected. Build, JVM XML,
failed and passing runtime logs, APK receipts and screenshot remain in ignored
`captures/runtime/ssh-audit/mac-browser-modes-*`. The single existing emulator was
stopped after testing; no AVD or physical-device state was added or changed.

Real Mac/Pixel mode switching, capability transitions, full-screen integration,
visual/accessibility acceptance and signed delivery remain open. No signed release
or broad upstream implementation reference was advanced.

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
capability before use; the feed accessor now does so. WebView integration is
still pending.

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
The session provider described below enforces account/Mac/build identity and
freshly negotiated capability. The WebView still uses its original phone network.

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

## Owner and policy checkpoint

`NativeFeedSession.browserNetworks` now retains networks by login, account/team
generation, saved origin, canonical Mac device and build. `NativeScreen` updates
the authorized set alongside browser navigation. Sign-out, team changes, forgetting
a Mac or replacing its identity retire the corresponding network, close its proxy
and cancel its pending listing and both Mac/direct relays. Route changes for the
same owner retain the network and resolve the new saved route on the next request.
Creating the registry does not start a listener; the future WebView adapter must
explicitly request and prepare a network.

`NativeFeedCoordinator.browserAccess` runs admission checks on its owning
dispatcher. It requires the exact current saved Mac, verified host identity, a
live client, newly negotiated `browser.tunnel.v1` and a transport supporting
feature lanes. Cached source capabilities cannot authorize a reconnecting client.
Availability distinguishes disconnected, old Mac and a route without lanes.
Disconnected pages remain bound to their original Mac. Relay/listing operations
are cancelled immediately when their handle retires, without holding the feed
mutation mutex or closing another consumer's shared connection.

`NativeMacBrowserNetwork.prepare` keeps its proxy port through ordinary reconnects,
refreshes policy after ten seconds and always refreshes before loopback navigation.
Failed refreshes retain the last confirmed listing/policy; a Mac-only destination
never falls back to phone localhost. If its listener dies, preparation tries to
rebind the previous port before requesting another. The existing 32-lane limit
wraps the admitted provider. No iOS-style same-port mirrors are enabled; Android's
loopback proxy bypass must still be disabled and verified in the WebView adapter.

**40 focused JVM tests passed**: five new owner/network tests, 17 feed regressions,
three routing tests, five SOCKS tests and ten wire tests. The new tests use actual
loopback SOCKS sockets plus simulated admitted RPC transports. They cover the
ten-second boundary, forced loopback refresh, policy retention across disconnect,
same-port reconnect, route replacement, login/team/build/Mac isolation, live
capability/route checks, stale cached capability rejection, pending listing and
direct-traffic cancellation, independent Mac relay retirement and feed refresh
while relays stay open. All have zero failures, errors or skips.

This checkpoint compiles production Kotlin and runs JVM tests; it does not build
or install a new APK. The connected Pixel's app, account and sleep settings remain
unchanged. Neither WebView behavior nor live Mac browser traffic is established
by these checks.

## Dedicated WebView process

`RoutedBrowserEnvironment` configures an immutable network/storage binding in
the app's `:browser` process. It refuses configuration in the main process,
changing the owner/storage key, or changing the proxy port in an initialized
process. A replacement owner requires a new process. Initialization failure
also requires process restart, rather than creating a partly configured WebView.

The opaque 32-hex storage ID becomes a validated directory suffix before loading
WebView. The future presentation owner must generate and retain one ID per Mac
for the current app session, and retire/delete those directories at session end.
No account identifier or credential is part of the directory name.
`ProcessGlobalConfig`'s startup support is checked first; unsupported WebViews
report an update requirement. Runtime proxy support is checked afterward.

The environment installs a SOCKS rule, removes implicit localhost/link-local
bypass, and waits for the completion callback before allowing a WebView to load.
There is no browser-side DIRECT fallback. The existing Mac router remains the
only authority for choosing Mac versus phone exits. This uses
[`androidx.webkit:webkit:1.17.1`](https://developer.android.com/jetpack/androidx/releases/webkit#1.17.1),
whose published AAR metadata was checked against the project's compile SDK.
The API 26 startup-compatibility branch remains unverified on an API 26 device.

`RoutedBrowserTestActivity` is a non-exported, debug-only emulator fixture in the
dedicated process. It exercises the production environment and kills its own
process after destroying its WebView, so browser services cannot survive into
another owner's configuration. It never reads account stores or real Mac data.
Its IPC copies message metadata before suspension because Android recycles
handled messages. This test Activity is not the production browser presentation.

**Two Android 17 / API 37 / 16 KiB tests passed in 34.056 seconds**, with WebView
145.0.7632.218. These use real WebViews and TCP sockets with generated local
servers standing in for two Macs; they do not use real Iroh/QUIC lanes or a Mac.
The checks establish:

- SOCKS routing of localhost, IPv4/IPv6 loopback, `app.localhost` and a hostname
  which the phone cannot resolve. A separate server at the original phone port
  receives no routed requests, including after the owner proxy stops.
- HTTP redirects, fetch, WebSocket exchange and a service worker's own fetch all
  reach the selected simulated Mac.
- Cookies, localStorage, Cache Storage and service-worker registrations are
  isolated between A and B at the same URL. Restarting A's process with A's
  existing storage ID restores its data; a new app-session storage ID starts empty.
- An initialized process rejects a different owner/proxy binding. A main-process
  WebView retains its phone network and does not receive either routed owner's
  cookie or localStorage item. Pre-existing phone-only fixture cookies are allowed.
- HTTPS bytes pass through SOCKS. The debug fixture accepts only its generated
  server certificate's exact fingerprint; a separate process without that pin
  cancels the TLS error and sends no HTTP request to the server. This is a tunnel
  test, not a public-PKI trust-chain test. Production TLS-error handling remains
  unchanged and never uses the fixture's pin exception.

Earlier runs found and fixed recycled IPC-message metadata, an incorrect empty
phone-cookie assumption, missing WebSocket close-handshake handling, and the test
waiting for a generic error callback after explicit TLS cancellation. Later
host-GPU runs timed out on a blank page; another was interrupted when host memory
pressure made the emulator unresponsive. Those blank-page timeouts have not been
proved to have a single cause. A software-graphics cold boot then had System UI
and application startup ANRs, before any test began. After the emulator recovered
and its System UI dialog was dismissed, the same final APKs passed both tests.
Failure/interruption logs are preserved; no broader stability claim is made.

The passing emulator used `-gpu swiftshader -feature -Vulkan -memory 1536 -cores 2`
and was stopped afterward. Recheck stability during production presentation
integration and on the Pixel; do not launch tests while emulator startup is still
unresponsive.

- Debug APK SHA-256: `53849d279c5a747d7cf96ced2214b90fd1340f8f5cc47ad5569b2b2f696487f3`.
- Test APK SHA-256: `9a1824627d4644066136131d965cfa95eaf24a5958c77eeec00a08f4774d95f5`.
- Evidence: ignored `captures/runtime/browser-webview/`.
- Pixel installation/account and signed 284 are unchanged.

## Production presentation integration — 2026-09-30

The local-browser navigation now opens a non-exported `RoutedBrowserActivity`
in `:browser` when the selected Mac's availability binds browsing to that Mac.
Older hosts and routes without native lanes retain the existing phone-local
fallback, following the inspected upstream availability policy. A disconnected
supported host stays bound to its Mac; localhost never falls back to the phone.

A non-exported, same-UID Messenger service stays in the main process. It owns the
presentation's opaque request ID, proxy preparation, workspace metadata and
surface snapshots. Credentials and RPC clients do not cross the IPC boundary.
The Activity binds the service while alive. A feed hold keeps the exact owning
Mac in the coordinator when the main screen stops, and a shared connection
handle keeps its native endpoint alive. Browser foreground changes update the
native probe activity independently of the main screen. Holds release on return,
process death, surface closure or owner retirement; main ViewModel disposal can
wait for the browser's final hold.

Only one routed browser process may exist at a time. Registration terminates an
old process before installing the next immutable proxy/storage binding. Each
Mac network has a random storage ID for this app session. Reopening the same
network reuses it; another Mac, retired admission, account/build identity or a
new main-process session gets another ID. Retirement closes sockets, closes the
presentation, then deletes only validated retired `cmux_browser_<32 hex>` WebView
data/cache directories. Default WebView/account files remain outside that
namespace; cleanup does not follow symbolic links. An independent admission
watch retires even idle browser networks while the main screen's Compose effects
are stopped. Re-admission creates a fresh network/storage ID.

The production browser reuses the existing address field, history controls,
file chooser, external-scheme handling, renderer recovery and TLS-error rejection.
Explicit address loads, back/forward and reload await network preparation.
Browser-owned redirects and forms retain their original request/body; they are
already routed through the immutable proxy and refresh policy asynchronously.
A changed proxy port requires a new browser process. Page snapshots return to
the original account/workspace-owned surface; stale presentation callbacks cannot
revive a closed surface. Back preserves the local tab and committed URL; selecting
a Mac pane or closing the browser removes the local tab.

### Verification

Sixteen focused JVM tests pass: network policy/admission/retirement, surface
snapshot lifecycle, and exact storage cleanup (including symlinks). Debug and
instrumentation APKs build. Four distinct Android checks now have passing evidence
on the API 37 / 16 KiB emulator:

- Production Activity/service/proxy: generated localhost page, in-page navigation,
  main Activity stopped while a host lease is retained, snapshot return, Back,
  reopening the committed URL, pane selection and one release per presentation.
- Owner retirement: child process closes, host lease releases and its WebView data
  directory disappears.
- Existing phone-local history/reload/close and new-window/link/script/POST checks
  continue to pass after the shared navigation hook change.

These tests use a generated TCP server and instrumented host-lease callbacks;
actual NativeFeedSession/Iroh endpoint lifetime still needs live integration
acceptance. They never read or clear account stores and are emulator-only.

The final four-case run passed three cases; a System UI ANR interrupted the
retirement case. The same final APKs then passed its focused rerun in **9.103 s**.
This is four distinct passing checks across runs, not a clean four-case batch or
startup/performance evidence. Earlier runs exposed missing test-clock advances
when crossing processes; the final harness pumps the parent Compose clock until
the child launches and after returning. Visual inspection found and fixed the
browser header's status-bar overlap. The final screenshot shows separated system
bars, page header, address controls and visible generated page content.

- Debug APK SHA-256: `ea82c343fdcfba536d18859b991c1b461e13b105730ebf82dc7cc46ec9cde70e`.
- Test APK SHA-256: `78c6563583f1d5daee098968c7eabae983cf8a33b01722a7bdfdc9f38c63c138`.
- Evidence: ignored `captures/runtime/browser-webview/presentation-*` and
  `production-layout.png`; failed/interrupted runs are retained.
- Pixel installation/account and signed 284 remain unchanged. The owned emulator
  was stopped after testing.

## Native JNI/Iroh browser acceptance — 2026-09-30

`NativeBrowserTunnelTest` passes **three Android runtime checks in 6.000 s** on
API 37 / 16 KiB pages. Both generated endpoints bind loopback with relay disabled;
real packaged JNI, QUIC admission and production browser adapters carry the data.
No saved credentials are read/cleared and no real Mac is contacted.

- Listing descriptors and fragmented listing replies match the wire schema. A
  fragmented TCP status coalesced with raw bytes preserves all following bytes.
  Concurrent 196,731-byte upload and 262,259-byte download remain exact across
  bounded chunks; an additional response tail arrives after client half-close.
  The same control RPC connection remains usable afterward.
- Denied/refused/busy replies remain distinct failures. Closing a borrowed client
  during an unacknowledged open cancels the waiter and resets that native stream;
  it releases its lease once while the owner control connection remains usable.
- `NativeFeedCoordinator` verifies the generated host identity/capability and
  completes its authenticated subscription/workspace/notification requests.
  `NativeMacBrowserNetwork` prepares the listing and SOCKS proxy; a SOCKS request
  for `project.localhost` reaches a generated HTTP server through the actual
  native TCP-connect lane, preserving the complete 98,328-byte HTTP body.

The first run passed two cases and timed out before the coordinator became ready:
its fixture had no synthetic access token for authenticated RPCs, and was also
missing subscription replies. Both fixture omissions were fixed; the final
three-case batch is clean. This does not prove live account admission, NAT/relay,
Mac implementation interoperability or the Activity/feed-hold lifecycle.

- Production APK remains the `9efef27` integration APK (SHA-256
  `ea82c343fdcfba536d18859b991c1b461e13b105730ebf82dc7cc46ec9cde70e`).
- Evidence and test APK hash: ignored `captures/runtime/browser-webview/native-lanes-*`.
- The owned emulator was stopped; Pixel and signed 284 remain unchanged.

## Physical capability check — 2026-09-30

The production integration APK was installed on the Pixel in place and its
installed hash verified. `LiveNativeBrowserCheck` passed in **5.768 s** using the
existing real account and saved Mac. Identity and authenticated workspace access
were verified over the native transport (`LAN or Private VPN`). The Android
transport supports browser lanes, but the live host does **not** advertise
`browser.tunnel.v1`; no unsupported listing/TCP request was sent.

The Mac is **0.64.25 / 106**, matching the current
[stable release](https://github.com/manaflow-ai/cmux/releases/tag/v0.64.25).
This makes a newer compatible host necessary for physical tunnel acceptance.
An official candidate is
[nightly build 3669077704801](https://github.com/manaflow-ai/cmux/releases/download/nightly/cmux-nightly-macos-arm64-3669077704801.dmg),
updated 2026-09-30 09:21 UTC, source
`0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc` from
[successful upstream run 36690777048](https://github.com/manaflow-ai/cmux/actions/runs/36690777048).
The official DMG has now been downloaded and its app staged under ignored
`captures/host-nightly/3669077704801/`. Deep/strict code-signature verification
passed; Gatekeeper accepted its notarized Manaflow, Inc. Developer ID
(`7WLXT3NR37`). The DMG SHA-256 is
`bf6002f026e967c6c87a1439c1ce0a9523d1bfad6e96ed305f25834eaec2633b`.
The staged app's bundle ID is `com.cmuxterm.app.nightly`, with a separate keychain
group and bundle-specific session snapshot. It was launched with
`CMUX_DISABLE_SESSION_RESTORE=1`; the stable installation remains running and
unchanged. This does not imply all configuration directories are isolated.

**Account follow-up (2026-10-02):** Mac accessibility briefly recovered. The
separate `com.cmuxterm.app.nightly` process is running, and its Account settings
show a signed-in account, active team and Sign Out control. Nightly account sign-in
is now verified; no credentials were entered or changed during this inspection.
A pre-existing quit confirmation was cancelled, preserving its workspace.
Navigating to Mobile settings then failed with ScreenCaptureKit error `-3812`,
including a fresh app binding attempt. No listener/pairing toggle was changed.
Native Mobile readiness, phone enrollment for this separate build and advertised
browser capability remain unverified; the user has been asked to report the
Mobile pane's status. Account sign-in alone does not prove those three gates.

**Native listener follow-up (2026-10-02):** app UI access recovered. The
nightly Mobile pane initially showed “Enable iOS pairing” off. Using the user's
existing approval for native mobile pairing, that toggle was enabled through the
app UI. Its status moved from “Starting the pairing listener…” to reachable Iroh
addresses, with **zero attached devices**. The preferred port 58465 was in use;
cmux selected **60215** automatically. No preferred-port edit was needed.

This verifies the nightly app's listener setting/startup status. It does not
verify Android enrollment, an authenticated Android-to-nightly connection,
`browser.tunnel.v1`, or `terminal.input.exactly_once.v1`. The Pixel is currently
absent from ADB, so the next step is discovery/pairing from the existing signed-in
Android app, followed by the explicitly nightly-selected native checks. The
listener remains enabled for that test. No new Android build was required.

The live check now accepts `-e cmux_live_build nightly`: it requires exactly one
eligible saved Iroh pairing with that build tag, then verifies the host identity.
It fails instead of falling back to stable or choosing between multiple matches.
Without a selector it retains the single-saved-Mac requirement.

The updated instrumentation APK compiled and installed successfully. Its first
physical `nightly` run refreshed the existing account/team, then failed at saved
Mac selection with `NoSuchElementException`: no eligible saved nightly Iroh
pairing matched. No host connection or browser request was made by this check.
This is pending pairing, not a passing nightly acceptance test. The normal debug
Activity was reopened afterward; no account data or sleep settings were changed.
Evidence: ignored `captures/runtime/browser-webview/pixel-nightly-live.txt`.

See [PIXEL_INSTALL.md](PIXEL_INSTALL.md) for the installed checkpoint. The explicit
live check performs only host reads through normal authenticated app connections,
and exports no credential, host identifier, address or workspace contents.

## Browser rotation retention — 2026-09-30

The routed browser Activity now handles orientation, screen-size/layout and
keyboard configuration changes in place. Its existing WebView redraws instead
of being destroyed and loading the current URL again. This follows Android's
[WebView state guidance](https://developer.android.com/develop/adaptive-apps/cookbook/webview-state).
The change is limited to the dedicated routed browser Activity; it does not
claim preservation through process death or every Activity recreation.

Before the fix, the regression lost its page-local draft flag on landscape
rotation and displayed the original `Next` title. The fixed APK passes a clean
**three-test presentation batch in 44.040 s** on API 37 / 16 KiB pages:

- A page-local JavaScript draft flag survives portrait → landscape → portrait.
  Its resize handler changes the browser title, proving the retained page sees
  the new viewport. The server receives no second `/next` request during rotation.
  Back/Forward history remains usable and the host lease stays held until return,
  then releases once. The landscape screenshot was visually inspected.
- The existing workspace return/reopen/terminal-pane test passes.
- Owner retirement still closes the presentation and removes its own storage.

Earlier attempts include a System UI ANR, an accessibility-query issue in the
first fixture, and a snapshot-restored emulator run interrupted after system
service failures. A later attempt was stopped at the user's pause request.
Those interrupted runs are not passes or startup/performance evidence. The final
batch used a cold-booted emulator and the corrected title-based fixture.

- Debug APK SHA-256: `47f149051072568a6809e3b91dfeeb883917c7da9a820ec1bae604eef72639a6`.
- Test APK SHA-256: `a6c736d87519b66cb20c9492c94091e6a2ae296209d4efd3aeb09ddbd09e5f27`.
- Evidence: ignored `captures/runtime/browser-webview/rotation-*`, particularly
  `rotation-baseline2-test.txt`, `rotation-resume-test.txt` and
  `rotation-resume-landscape.png`.
- Pixel installation and signed release 284 are unchanged. The owned emulator
  was stopped after verification.

## Routed file picker and address entry — 2026-09-30

Two further presentation checks pass in a clean **38.873 s** API 37 / 16 KiB
batch using the production Activity, bound service, WebView and proxy with a
generated host. No production changes were required for these checks.

- A generated Downloads text file is selected through Android's real document
  picker after first opening and cancelling that picker. The multipart POST
  reaches the generated host through `NativeMacBrowserNetwork`/SOCKS; filename,
  method, content type and the complete file payload (Unicode and line breaks
  included) match. The connection hold survives both picker visits, its foreground
  probe follows the browser's visibility, and the hold releases once on returning
  to the workspace. The generated document is deleted in cleanup. The uploaded
  page screenshot was visually inspected.
- Editing the actual address input and pressing Enter commits the new URL and
  causes exactly one request for that page. Early fixture attempts targeted the
  description node inside Compose's `EditText`; that node cannot receive text.
  Hierarchy evidence identified the real editable parent, and the final selector
  verifies its focus/text before submission. This was a test-selector issue.

Production APK remains `aae915f`'s rotation build, SHA-256
`47f149051072568a6809e3b91dfeeb883917c7da9a820ec1bae604eef72639a6`.
Final test APK SHA-256:
`4ac9a5b4186428b41663cf36f9b723663c67bd62bfa571d47bb0eaf3c058eab7`.
Ignored evidence: `captures/runtime/browser-webview/chooser-*` and
`address-focused.xml`. These are generated-host emulator checks, not physical
Mac/Pixel upload or native Iroh interoperability acceptance. The emulator was
stopped afterward; Pixel and signed release 284 remain unchanged.

## Browser subprocess death — 2026-09-30

A focused presentation test passes in **24.661 s** on API 37 / 16 KiB pages.
It navigates to a second page, waits for its committed snapshot, then kills only
the app-owned `:browser` process (with explicit UID/name/PID checks). The main
process survives, the old host hold releases once, and the workspace can reopen
the retained tab. The new browser PID loads the committed URL and sends that
same owner's saved cookie. Returning releases the new hold once as well.
This uses the production Activity/service/proxy with a generated host; no new
production code was needed. The first attempt missed a parent Compose test-clock
advance after the Activity result. Adding the existing return-test idle barrier
made the full check pass; the failed attempt is retained as evidence.

The expected scope agrees with upstream source at
`0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`:
[MobileBrowserView](https://github.com/manaflow-ai/cmux/blob/0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc/Packages/iOS/CmuxMobileBrowser/Sources/CmuxMobileBrowser/MobileBrowserView.swift)
restores the committed URL when its view remounts, while
[BrowserServerRoute](https://github.com/manaflow-ai/cmux/blob/0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc/Packages/iOS/CmuxMobileBrowser/Sources/CmuxMobileBrowser/BrowserServerRoute.swift)
uses a computer-private, non-persistent store lasting for the app session.
This check does not claim retention of unsaved DOM/history across process death,
or durable routed cookies after the entire app quits.

Production APK is unchanged from the rotation checkpoint. Test APK SHA-256:
`339d46924b99df195e5fe2e4411b7c2962fd11dd4bfadb008f932f7a04a16b6d`.
Ignored evidence: `captures/runtime/browser-webview/process-death-*`.
The emulator was stopped; Pixel installation and signed release 284 are unchanged.

## Remaining integration

Exercise a real Mac/Pixel website (HTTP/HTTPS, reconnect and account retirement). Verify physical keyboard/IME, physical file picking/rotation,
whole-app process-death return and lifecycle races with live feed holds. The earlier
isolated adapter tests use generated TCP hosts and do not prove real native-lane
acceptance. API 26 WebView startup compatibility is still unverified.
