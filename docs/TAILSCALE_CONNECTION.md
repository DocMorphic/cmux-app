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
deprecation; callbacks provide loss/change invalidation. The later readiness
checkpoint below adds the bounded startup wait and replaces synchronous queries
inside callbacks with callback-provided observations.

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

## Computer Details and shared routing integration (2026-09-29)

Computer Details now offers Iroh, Tailscale Only and Direct. Selecting Tailscale
Only with no grant leaves that Mac disconnected and shows an Add Tailscale
Connection action. It does not automatically launch a scanner or dialog. The
Routes section preserves the native Iroh identity and lists distinct authorized
Tailscale coordinates, with add, edit and confirmed removal actions. The optional
automatic-Iroh private-address section is hidden in Direct and Tailscale modes.

The pairing dialog accepts a QR scan, pasted Tailscale code or numeric IP/TCP-port
entry. Its explicit Connect action verifies the selected Mac and exact build
before saving. An edit must authenticate the replacement and atomically save it
while removing the captured old route. Failed verification/commit retains the
old route and editor. Stale edits and sibling-build replacements are rejected.
Duplicate grant sources for the same coordinate are shown once and removed
together. Adding or editing from Details never replaces the native pairing row or
changes the chosen connection method. Pairing dismisses the keyboard on Connect.

`NativeCredentialStore.revisions` shares change notifications across store
instances. The backend reads exact account/team/device/build grants into the
captured dial intent. Only Tailscale mode includes these grants in the connection
key; changing unrelated credentials or adding routes while Iroh is selected does
not replace a live Iroh session. Empty Tailscale intent fails before acquisition.
Tailscale candidate transport tries only captured authorized coordinates over a
validated VPN; it cannot fall back to Iroh, a relay or the default network. A
candidate must complete host identity and authenticated workspace probes before
the shared pool exposes its first lease. Revocation during this probe closes the
candidate and prevents publication.

Method/route changes publish per-Mac connection keys, retiring matching leases and
pending handshakes. The foreground connection effect, feed handles and background
notification workers observe those keys. A changed method wakes a disconnected
Mac's retry instead of waiting through the old backoff; unrelated Macs retain
workers and leases. Native checks and power consumers use the same pool/intent.

### Integration verification

- **86 JVM cases passed:** 18 runtime, 8 connection settings, 8 pooled connection,
  20 authorization, 15 feed, 5 Tailscale candidate transport and 12 computer removal
  cases. New coverage includes no-grant behavior, per-Mac route invalidation,
  candidate admission/removal, immediate offline feed wakeup, captured route
  fallback, atomic editing, duplicate deletion and numeric manual entry.
- Debug and instrumentation APKs build. The first combined attempt failed on a
  Kotlin test invocation of a function stored in a Java list. Explicit indexed
  access fixed the test compilation. No production change was required.
- The first **14-case Android 17 emulator run** passed 13 cases in 28.065 seconds.
  Its one failure queried an unmerged text child for the button's disabled state.
  Correcting that selector produced **4/4 Tailscale UI passes in 9.584 seconds**.
- The screenshot showed keyboard/dismissal animation still in flight. Explicit
  keyboard dismissal was added, and all **4 Tailscale UI cases passed again in
  9.221 seconds** on that final application build. The other ten cases (two real
  Keystore/persistence cases, four Direct UI and four Details cases) had passed in
  the initial run and were not repeated for the isolated keyboard change.
  The screenshot helper was then updated to wait for the window dismissal to
  settle; its single targeted case passed in **3.868 seconds**. The resulting
  screenshot was inspected with no keyboard or dialog overlay remaining.
- The persistence fixture uses separate credential preferences and the real
  Android Keystore: grants reload through another store instance, revisions
  propagate, native pairing/selection survive route changes, changed-login commits
  fail, and clearing notifies readers. UI connection callbacks and Mac responses
  remain simulated; these runs do not establish physical VPN/Mac traversal.
- Final main APK SHA-256:
  `67d498948efae66f6a724224b579c6f2e236d245b881899b112120e7a997714d`.
  Final instrumentation APK SHA-256:
  `102c293f6a1fe8220437ac63fe4f824357f2246d58014394cc1a0cb805e22db1`.
  Native ELF LOAD/RELRO and APK ZIP 16 KB alignment checks pass.
- Logs, JVM XML, screenshots, APK receipts and alignment output are kept in ignored
  `captures/runtime/tailscale-settings/`. Screenshots are from a standalone test
  Activity; its system bars are not a full-app visual reference.

ADB still detects no physical Pixel. Its last installed checkpoint remains
`f0dfc7f`; signed published build 157 is unchanged. No real Mac route or phone
power setting was changed. The emulator was stopped after verification.

## Saved Tailscale sessions independent of Iroh (2026-09-29)

The native saved-computer path now uses `NativeSavedTailscaleRuntime`, an
account/team-owned TCP connection pool that starts independently of Iroh key
loading, enrollment, broker discovery and endpoint readiness. The facade tries
this owner before waiting for Iroh when the exact Mac/build selects Tailscale.
Iroh and Direct retain their existing discovery path. The upstream reference is
`MobileShellComposite+ReconnectRoutes.swift` at the pin above: explicit Tailscale
selection resolves device-bound saved grants without an Iroh fallback.

The owner requires a current verified account/team, exact Mac/build settings and
matching encrypted numeric-route grants. It authenticates host identity and the
workspace request before publishing a lease. Foreground, feed, notifications,
Computer Details checks and Mac Power share those leases. Iroh startup failure or
retry cannot retire them. Account changes, local route removal, method changes,
corrupt settings and shutdown still invalidate them, including a pending host
probe. Shutdown also interrupts a connection waiting for initial account scope.

Independent per-device/build keys wake foreground/feed/notification reconnects
when local routes change, even with an empty discovery directory. Changes to one
build leave a sibling build's worker alone. Details route management and Check
are available while Iroh is unavailable under the current account. No permission
is inferred from directory failure, a QR's claimed identity or an absent grant.
Older locators missing identity/build resolve only from one matching scoped saved
computer. The separate legacy QR entry point still needs identity/method unification.

This does **not** establish fully offline cold-start authentication: initial
account/team verification and access-token refresh retain their existing account
service requirements. Nor does it prove real Android VPN callback behavior.

### Independent-owner verification

- First regression run: **67 JVM cases passed**. A close-while-waiting case and an
  exact-build feed wakeup case were then added; the final **69 cases passed**,
  with zero failures, errors or skips: 12 independent owner, 18 existing runtime,
  16 feed, 8 shared pool and 15 Computer Details checks.
- Fixtures keep Iroh startup pending or repeatedly fail its broker while TCP RPC
  framing and token attachment operate against a simulated Mac. They verify
  shared leases/checks/power, no-grant rejection, scope/token races, probe
  revocation, changed host, method round trips, corrupt storage and closure.
- Main and instrumentation debug APKs built together. ELF LOAD/RELRO and APK ZIP
  16 KB alignment checks passed. Main SHA-256:
  `17144137e91334a850b12219c580b7bf0aa8dae9d59ac9f77cbde28385083127`.
  Test SHA-256:
  `2bcc914d36186d938550853d2f5cca4d67ebdeefab3488ab5d2ce97242e468eb`.
- Logs, XML and APK/alignment receipts are in ignored
  `captures/runtime/tailscale-independent/`. No Android instrumentation was rerun
  for this runtime checkpoint. The prior UI/Keystore evidence is recorded above.
- ADB still reports no Pixel. No physical install, Mac route mutation or phone
  power change occurred. Last installed Pixel checkpoint remains `f0dfc7f` and
  published signed build 157 remains unchanged.

## Tunnel readiness and callback lifecycle (2026-09-29)

`TailscaleReadiness` now gives the VPN up to ten seconds to become provable,
matching the pinned iOS `CmxSystemTailscaleRouteAuthority` default. It waits through
no tunnel, incomplete capability/link callbacks, ambiguous eligible tunnels and a
blocked network. Invalid peer/port input and a route back to this phone fail
without consuming the readiness deadline. A deadline failure tells the user to
open Tailscale, connect the phone and retry. Computer Details preserves this
specific suggested action in its address-free report. Multiple route hints do
not restart the deadline when the VPN itself never became ready.

The Android authority registers before waiting and owns that same monitor through
DNS resolution, proof binding, socket connection and subsequent IO. Its callback
cache combines capabilities, link properties, availability/loss and blocked
status. It does not query synchronous ConnectivityManager properties inside
callbacks; those calls can race the delivered callback data according to the
[Android NetworkCallback reference](https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback).
Fresh synchronous checks remain at the socket IO boundary to catch a network
change before its callback is delivered.

Only usable-tunnel content changes advance the proof generation. Duplicate
callbacks leave a connection intact; loss, blocking, changed interface/addresses
or a second eligible tunnel invalidate it permanently, including a change back
to the original configuration. Late link/capability events cannot resurrect a
lost Network without a new availability event. The proof and Network token are
captured from the same monitor, and DNS cannot outlive that generation.

Readiness remains cancellable. While awaiting network events, captured consent,
account/grant authority and transport closure are checked at most every 200 ms.
The explicit QR resolver receives the actual attempt permission, so replacing its
confirmation also stops a pending wait. Failed or cancelled preparation releases
the callback registration, including cancellation during the IO dispatcher return.
Successful peer-only resolution releases its temporary monitor; the socket owns
and eventually closes the monitor for its own proof. No default-network fallback
or credential transmission is introduced.

### Readiness verification

All **78 JVM cases passed** with no failures/errors/skips: 10 readiness/callback
cache cases, 22 pairing authority, 7 saved candidate transport, 6 path proof,
5 real local TCP authority, 13 independent saved runtime and 15 Computer Details
checks. New cases exercise callback completion order, ambiguity, blocking,
generation changes, missing tunnel timeout, caller cancellation, consent
replacement, closure before socket creation, no repeated readiness wait across
route hints, and the sanitized Details advice. The real TCP tests retain their
injected authority observations; they do not establish Android VPN traversal.

Production Android sources compile in the JVM run. No APK or instrumentation
rerun was performed for this feature commit. The latest built APK remains the
`1bfc378` checkpoint recorded above, while the Pixel still has `f0dfc7f` and signed
published build 157 is unchanged. Logs/XML are retained locally in ignored
`captures/runtime/tailscale-readiness/`. ADB still lists no physical Pixel; real
VPN callback ordering, cold/warm startup and Mac reconnect acceptance remain open.
The ten-second limit covers tunnel readiness, not a whole DNS/TCP/RPC exchange.

## Live Tailscale transport diagnostics (2026-09-29)

`MobileTransportDiagnostics` now separates route, transport encryption and
optional transport RTT. The Iroh adapter preserves its direct/private/relay
classification and QUIC verification. The TCP socket reports Tailscale only when
its live Android authority revalidates the tunnel generation, permission and
actual socket endpoints. It reports **Tailscale VPN (TCP)** with encryption
**Managed by VPN**. A socket without this authority reports TCP and Not Verified;
it cannot infer a VPN or QUIC connection from an address range.

This is deliberately not a claim that cmux verified the VPN application's
identity or cipher. The authority proves network/interface/socket properties;
VPN encryption is owned by the external VPN. Mac identity and authenticated
account access remain separate checks. Tailscale TCP has no transport RTT value
in this implementation; the measured RPC response time is still shown separately.
No TCP latency estimate is relabeled as native QUIC RTT.

Candidate transports forward diagnostics only from their active connection and
recheck captured permission afterward. The socket checks authority before and
after projecting diagnostics; retirement during the read closes the socket and
rejects the result. Closed leases cannot read or share stale connection data.
Reports contain only fixed labels, booleans and durations, with no coordinates,
computer names, tokens, terminal content or server error text.

### Diagnostic verification

- **43 focused JVM cases passed**, with no failures/errors/skips: 10 connection
  reports/projections, 15 Computer Details runtime checks, 8 real local TCP
  authority, 8 candidate transport and 2 Iroh transport cases. New cases cover
  TCP versus VPN/QUIC labels, absent TCP transport RTT, revoked authority during
  projection, candidate forwarding, safe exports and unavailable Iroh routes.
- Main and instrumentation debug APKs built together, also incorporating the
  preceding VPN-readiness checkpoint. Native ELF LOAD/RELRO and APK ZIP 16 KB
  checks passed. Main SHA-256:
  `f64e63eb813b4e07a33dfef926a33eb8ca43e0c33551e8e4741407cca061aefb`.
  Instrumentation SHA-256:
  `f5bf13a922a034f16d176ea11c74f9f4e564c46d3a4b87087f6acbd9dfe7e260`.
- All **4 Connection Check Android UI cases passed in 8.422 seconds** on the
  Android 17 emulator. The new case verifies Tailscale/VPN labels, separate
  identity/account results, no QUIC claim or transport RTT, and sharing exactly
  the displayed report. Existing cases cover disabled/pending actions, failures,
  late results after changing Macs and the Iroh private-route display.
- The Tailscale report screenshot was inspected with no clipped labels. Its
  white status bar belongs to the standalone test Activity, not the main app.
  Report values are fixture data. These UI cases do not prove VPN traversal.
- Evidence is retained in ignored `captures/runtime/tailscale-diagnostics/`.
  The emulator was stopped afterward. ADB still detected no physical Pixel;
  last Pixel install remains `f0dfc7f`, published signed build 157 is unchanged,
  and real VPN/Mac diagnostic acceptance remains pending.

## Generic QR pairing preserves an existing native computer (2026-09-29)

The foreground QR flow previously called `rememberMac`, which replaced any saved
row with the same device/build. A successful Tailscale scan could therefore erase
the native locator even though the new route grant had been saved correctly.
`NativePairingPersistence` now handles the authenticated write under a captured
account/team scope. It verifies the login inside the credential transaction and
requires a matching saved QR grant for the reported canonical device/exact build.

If exactly one fully scoped native row already owns that Mac/build, the write
retains its code, name and origin, leaving the saved selection and draft/
notification references intact. It selects that existing locator instead of
writing a new QR row. The foreground closes its temporary QR connection and
reconnects through the retained computer's chosen connection method. Adding a
route does not change that method. The route remains in the encrypted grant store.
This follows the pinned iOS persistence model's exact-scope/build matching and
retention of existing reconnect routes when authority is unchanged.

Native rows from another user/team and sibling builds are retained. UUID spelling
is canonicalized for comparison; opaque device IDs remain case-sensitive. An
unscoped or internally inconsistent native row is not guessed or overwritten;
the UI asks to reconnect that native computer and add its route from Details.
Multiple matching native rows also reject the merge. Account/team permission is
checked before and after the encrypted write, outside the credential lock to
avoid inversion with account refresh.

All **45 focused JVM cases passed**: 11 new pairing-persistence cases, 22 route
authorization cases and 12 computer-removal cases. Coverage includes native
identity/name/origin preservation, selection, retained grants, UUID aliases,
other owners/builds, absent/mismatched grants, changed login, ambiguous/unscoped
rows, inconsistent native locators and standalone QR update behavior. The first
compile attempt exposed a missing Flow `first` import in the foreground effect;
adding it produced the passing final run. Production Android sources compile.
Logs/XML are retained in ignored `captures/runtime/tailscale-pairing-merge/`.
No APK or Android instrumentation was run for this feature commit. The last APK
remains the `67702b0` diagnostics checkpoint above; the Pixel still has `f0dfc7f`
and signed published build 157 remains unchanged. No physical Pixel was detected.

This checkpoint fixes QR attachment to an existing scoped native computer. It is
not full paired-computer storage unification: standalone legacy QR rows still
need scoped records, stable identity when later upgraded to native discovery,
and alias handling for their existing drafts/notifications. That work and
physical QR/reconnect acceptance remain open.

## Scoped records and QR-to-native upgrades (2026-09-29)

[PAIRED_COMPUTER_RECORDS.md](PAIRED_COMPUTER_RECORDS.md) implements explicit owner
metadata and stable origins for standalone QR records. Authenticated native
upgrades preserve the draft and notification namespace. UI/feed/service access,
saved dialing and local removal enforce the row's owner, including two teams
saving the same QR. Seventy-five JVM and eight Android emulator cases pass on the
final checkpoint; both APKs build and pass native/ZIP 16 KB checks. Physical
acceptance, ambiguous historical coalescing and standalone QR-only Details remain
open; this is not a claim of completed companion parity.

## Remaining integration and acceptance

- Finish ambiguous historical row coalescing with draft/notification alias
  preservation, and compare standalone QR-only Details/method behavior with iOS.
  Normal scoped QR-to-native upgrades now retain their existing origin; generic
  QR attachment and targeted Details pairing preserve native identity/method.
- Exercise the implemented readiness, route diagnostics and failure advice on the
  physical Pixel/Mac VPN. TCP transport RTT is not available; RPC response timing
  is reported separately.
- Verify Android VPN event ordering, real QR camera results, IPv4/IPv6/MagicDNS,
  edits/removal/reconnect during terminal and notification activity, and complete
  Mac/Pixel acceptance. Finish full iOS layout/interaction comparison.

## Scoped raw Computer Details (2026-10-06)

Explicitly owned saved Tailscale rows with a known device/build now expose Details
and diagnostics without native discovery. Main raw-primary reconnect with newly
edited/replacement grants still needs integration and exact ticket coverage.
See [Computer Details](COMPUTER_DETAILS.md) for checks and remaining scope.

## Reconnect after editing a saved address (2026-10-06)

The main saved-connection path now captures the current authorized addresses for
an explicitly owned Mac/build, newest first. It no longer requires the grant
source derived from the original public pairing code. Historical unscoped or
pre-tag rows retain their original-source requirement. The method policy still
rejects raw TCP for Direct and for Iroh when an owned native identity is present.

Each attempt captures the account, saved row, method/recovery revision and exact
grant. Removal, hiding, replacement, logout or a method change fences admission
and live I/O; the existing authority retirement loop closes invalid clients.
Another captured address is tried only after a transport IOException. VPN
readiness, account/identity denial, invalid tickets and cancellation stop the
attempt. Saved reconnect cannot consume an unrelated pending fresh confirmation.

A saved attach ticket accompanies only its original grant source. An edited or
additional source requests a new manual session ticket after verifying the host;
no old bearer is copied to the replacement address. Hosts without manual-ticket
support retain the existing account-authenticated fallback. The old public code,
origin, selection, drafts and encrypted ticket record stay intact; the removed
address grant is never recreated. Persisting a successful reconnect can use the
replacement only with the captured, still-current owned row. Fresh pairing still
requires its exact source grant.

Focused verification and limitations are recorded in `REMAINING_WORK.md` and local
`captures/runtime/edited-tailscale-reconnect/`. Pixel/Mac edited-address, cold-start,
foreground/feed and notification-service acceptance remain pending. This batch
does not advance the global upstream pin or claim complete connection parity.

Verification: 94 focused JVM tests passed with no failures/errors/skips;
main and instrumentation Kotlin compilation passed (36 seconds final run).
Coverage includes replacement ordering and ownership, pre-tag restrictions,
transport fallback, ticket omission and retention, suppressed fresh consent,
authentication/live-I/O revocation, host mismatch, durable reconnect/history,
existing method policy and ticket/pairing regressions. The initial new test's
constructor-call compile error is preserved in the local evidence. No APK was
built and no Android runtime test was executed for this batch.
