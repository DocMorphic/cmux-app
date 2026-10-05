# Attach-ticket compatibility audit

## Combined connection-route Android milestone (2026-10-06)

The eight `NativeTicketPairingRuntimeTest` cases passed in **30.332 seconds** on
existing `cmux_api37_16k` (Android 17/API37, 16 KiB pages, font scale 1.0). Source
was `a803fe0` plus the recorded one-line screenshot capture in that test. One
combined debug/test APK build took 72 seconds. This closes the queued chooser
and Keystore checks from the following source batches:

- External authorized Tailscale reuse is visibly explained; revoking the captured
  grant prevents confirmation from producing a connection attempt.
- Directory enrichment survives Keystore reload without renewing the raw ticket,
  changing its primary route, writing identical snapshots or dropping the native
  locator after empty discovery. Denied admission performs no write.
- Fresh in-app numeric confirmation and external native confirmation, cancellation,
  public-code versus secret-ticket saved-state behavior, ViewModel recreation,
  ticket persistence and Forget cleanup remain passing.

All three fresh chooser screenshots were inspected: route labels, explanations
and buttons are readable without clipping at font scale 1.0. These are isolated
Android dialogs, not a screen-by-screen iOS parity result. After installation,
System UI showed an ANR before instrumentation; its screenshot/XML and event log
were retained, the observed Close app action was used and launcher recovery was
confirmed. **No new crash/ANR events occurred during tests**, and the final crash
buffer was empty. The emulator was stopped and reaped; no AVD was created.

Evidence: `captures/runtime/connection-route-milestone/` (build/runtime logs,
command, source/APK hashes, pre-test failure evidence, screenshots and receipt).
Debug APK SHA-256: `479275ca7369eea1fc5a4af46a75343b63db9270739d753ef1cb4cea74964930`.
No live account, Mac/Pixel route or signed-upgrade acceptance was performed.

## Account-directory enrichment and fresh-entry correction (2026-10-06)

The scoped follow-up read includes the caller of `supportedRoutes`, not just that
helper. At `186cec79781256867ad4516f0802118738bd2393`,
`MobileShellComposite.swift:10758` calculates
`hasFreshExplicitTailscaleAuthorization` from this ticket's exact destinations.
It does not derive a Direct-only allowlist from the stored method when that
fresh authorization exists. A caller-supplied Direct-only allowlist remains
strict. The previous checklist entry saying every fresh in-app ticket must obey
the stored Direct setting was incorrect; Android's explicit-entry behavior is
consistent with this caller. No new restriction was added. Saved reconnects and
external links continue to enforce their captured method.

`MobileShellComposite+PresenceRouteSync.swift:262` also shows authenticated route
enrichment of an existing pairing, with account/instance checks and no destructive
empty-route update. Android now observes the current account directory and saved
record revisions, retaining a native locator for an existing owned Tailscale row
only when one exact device/build match exists. Hidden, unowned, pre-tag, wrong-
account and ambiguous rows cannot be enriched. Duplicate identical directory
entries coalesce; distinct conflicting entries do not select an arbitrary peer.

The write preserves the primary route, ticket context, raw grants, name, stable
origin and local history. It is idempotent and never creates a new pairing.
Empty/unavailable discovery never removes a retained native route, so a later
outage cannot restore Automatic's raw fallback. The actual native connection
still requires the backend's live permission and host-identity checks. Tailscale
Only retains its own granted path; discovery creates no address grant.

A pending reconnect may adopt a change limited to its native locator. A historical
row can also gain explicit scope metadata if its original grant independently
proves the same owner and its origin is unchanged. Changes to ticket, name, owner
or other saved fields still retire that captured selection.
Other captured menu actions keep their strict current-row check. Existing explicit
fresh Tailscale sessions are not forcibly changed just because discovery enriches
the row; saved raw clients retain their existing retirement checks.

**55 focused JVM tests passed**, zero failures/errors/skips: nine directory-
upgrade cases, six retained-route cases, 22 persistence cases, three reconnect
list cases and 15 ticket-pairing cases. Main/instrumentation Kotlin compilation
passed (final run 17 seconds). No APK build or emulator run for this batch.
Local source receipts and verification are under
`captures/runtime/directory-route-upgrade/`. The Keystore reload, unchanged-snapshot,
ticket-preservation and empty-discovery test subsequently passed in the combined
Android milestone above. Real upgrade/reconnect, directory
outage, history preservation and method changes on Mac/Pixel remain acceptance
work. The global upstream pins are unchanged.

## External tickets using stored address grants (2026-10-06)

External legacy tickets can now offer an exact numeric Tailscale destination
already authenticated for this account/team and Mac. The chooser applies the
saved build's method: Direct offers the native lane; Tailscale offers an existing
address grant; Automatic admits a legacy raw destination only without a native
identity in the ticket or owned saved records. Native candidates resolve their
build through the account directory. A native proposal opened during discovery
remains available, but confirmation waits for a resolvable identity and captures
its current method before connection.

A directory-resolved native build disambiguates shared numeric destinations.
Without that evidence, conflicting build grants cannot choose a sibling's method.
Tickets cannot create grants from device claims, hostnames or endpoint lists.
Sorting still uses upstream priority/ID ordering. The confirmation explicitly
says when it is reusing an authorized destination; external confirmation never
calls fresh-address authorization and cannot consume unrelated pending consent.

Captured grants, account scope and method epochs are rechecked at confirmation.
The Tailscale transport also checks them before dialing/token acquisition, after
asynchronous authentication and during live I/O. Revoked clients are closed by
the existing retirement loop. For a grant originally saved from a DNS or multi-
route code, successful exact-address authentication can save an alias for the
public ticket locator. The alias transaction verifies that the original grant
still exists, preserves its exact numeric destination/build, and leaves the
original grant intact. The current session remains dependent on that original
admission. This does not resolve a hostname again or broaden ticket coverage.

**78 focused JVM tests passed**, with zero failures/errors/skips: ten external
route-selection cases, 47 transport-authority cases, 15 ticket-pairing cases and
six retained-route cases. Main/instrumentation Kotlin compilation passed (final
run: 17 seconds). Results and source hashes are recorded locally under
`captures/runtime/external-ticket-grants/`. A new Compose test covers the reuse
message and revoked confirmation; it subsequently passed in the combined Android
milestone above. No APK was built for this source batch. Real external Intent,
Mac/Pixel connection and full route-policy acceptance remain open.

## Retained native pairing alongside a Tailscale ticket (2026-10-06)

Accepting a Tailscale ticket now retains a previously authenticated native locator
for the same account, team, device and build. Incoming ticket data cannot create
that alternative. Automatic and Direct select the retained native locator;
Tailscale selects the primary raw route, subject to its existing exact grant and
method checks. The raw ticket remains bound only to its covered primary route.

After a native reconnect authenticates, its selected locator becomes primary,
retaining the computer's stable origin/history and retiring the raw ticket context.
Revoking the raw grant does not erase the independently authenticated native route.
Malformed, cross-scope and ambiguous alternatives are rejected; saved menu actions
also retire when the retained locator changes. Computer Details is available for
raw-primary rows with a valid retained native identity.

**85 focused JVM tests passed**, zero failures/errors/skips, including six new
retention cases, 22 persistence cases, 13 ticket-store cases, 42 authority cases
and two saved-route policy cases. Main/instrumentation Kotlin compilation passed
(32 seconds). No APK build or emulator run in this batch. Local logs, original
fixture failures, final XML and source hashes are under
`captures/runtime/retained-native-route/`. Real reconnect/UI acceptance remains
open, along with the implementation gaps below.

## Saved-method comparison and legacy reconnect guard (2026-10-06)

Scoped source: `MobileShellComposite+ConnectionMethod.swift`,
`MobileShellComposite+ReconnectRoutes.swift`, `MobileShellComposite+RouteSelection.swift`
and `supportedRoutes` in `MobileShellComposite.swift`, at
`186cec79781256867ad4516f0802118738bd2393`. Global parity/review pins are unchanged.

| Rule in the inspected iOS source | Android finding |
| --- | --- |
| A per-pairing method defaults to Automatic; explicit build tags never inherit a sibling's settings. | Existing native settings and dial intents use exact device/build keys; absent settings default to IROH (the UI's Automatic). |
| Saved Direct reconnects use only Iroh and enabled user addresses; empty/unreachable addresses cannot fall back to raw Tailscale. | Existing native backend enforces this. The legacy raw connector bypassed method settings; this batch adds the missing gate. |
| Tailscale Only uses exact locally granted raw routes, without Iroh fallback. | Existing native/saved Tailscale runtime enforces this. The legacy connector now also captures the saved method. A method choice alone creates no grant. |
| Automatic retains an exact granted legacy route only while the pairing has no authenticated Iroh route. | Legacy raw reconnect now rejects a separately retained native identity for the same owner/device/build. Ticket acceptance now retains an existing native route; account-directory enrichment is implemented in the newer entry above; runtime acceptance remains open. |
| Fresh in-app numeric entry can authorize its exact Tailscale address ahead of the stored method, including Direct when no caller-supplied Direct allowlist is present. | Existing entry policy and consent authority preserve this. The new saved-route gate does not turn fresh entry into a saved reconnect. |
| External links cannot create new numeric address authority, but supported-route selection can use an already stored exact Tailscale grant under its applicable method. | Fresh external authority is denied. The chooser now admits independently stored exact grants under captured per-build methods; see the newer entry above. |

`TailscaleConnector` now captures the exact build's connection intent before a
saved reconnect. `TailscalePairingAuthority` requires that admission before dialing,
after token/authentication waits and during transport I/O. Its existing retirement
loop also closes blocked saved clients. Method epochs prevent A→B→A from reviving
an old client; settings read/recovery errors deny delivery. Pre-tag grants never
inherit a tagged sibling's preference. The native-identity lookup is cached by
credential revision, so streaming reads do not rescan saved records on every
frame. Grant/account/host checks remain required separately.

**52 focused JVM tests passed**, zero failures/errors/skips: 42 authority cases,
eight connection-store cases and two policy cases. New cases prove rejection
before dial/token acquisition, revocation during authentication, live retirement
across a method round trip, fresh-entry distinction and owner/build isolation.
Main/instrumentation Kotlin compilation passed (33 seconds); no APK or emulator
was started. Evidence/source receipts: `captures/runtime/saved-route-policy/`.
Real saved-method changes on Mac/Pixel still need acceptance.

### Remaining route-retention and chooser work found in this comparison

- Scoped Tailscale-only Computer Details is now implemented with explicit saved
  owner/build identity, saved-route checks and shared power controls; see
  `COMPUTER_DETAILS.md`. Main raw-primary reconnect still needs selection of
  edited/replacement grant sources with exact ticket coverage. The new Android
  Details case and physical workflow acceptance remain pending.
- Implement explicit legacy pre-tag identity adoption without choosing an
  arbitrary sibling or losing history. This batch requires an exact stored build;
  a missing build remains unchanged until authenticated identity can establish it.
- Verify authenticated legacy-to-native enrichment and reconnect in the real
  account flow, including foreground retry, hidden/forgotten rows, directory
  outages, drafts/notifications, ticket binding and strict per-build methods.
- Verify real external-link route reuse and cold discovery. The implemented
  chooser/transport checks above have focused evidence, not physical acceptance.

These are concrete implementation gaps, not unavoidable Android differences or
closed parity gates. The scoped comparison is not a full connection audit.

## Android chooser milestone — 2026-10-05

At `132b6f6`, all six `NativeTicketPairingRuntimeTest` cases passed within the
combined 11-case media/pairing run (86.408 seconds, existing API37/16 KB AVD).
The revised in-app Tailscale-only and external native-only chooser screenshots
were inspected. Confirmation/cancellation, memory-only unconfirmed-ticket
recreation, text-state filtering and isolated Keystore persistence/forget also
passed. These are fixture checks; real Intent routing and Mac/Pixel connections
remain open. Evidence: `captures/runtime/media-connection-milestone/`.

## Pairing entry source and route eligibility (2026-10-05)

Source comparison at `186cec79781256867ad4516f0802118738bd2393` found that
`connectPairingInput` passes `userEnteredPairingCode: true`, while external URL
handling defaults to false. `connectPairingURLResult` creates exact numeric
Tailscale authorizations only for in-app entry. `supportedRoutes` gives those
destinations precedence over Iroh for a fresh mixed ticket; without that entry
authority, fresh pairing uses the native lane. The source regression
`externallyOpenedQRCodeDoesNotMintInAppTailscaleAuthorization` covers the external
case. These Swift tests were inspected, not executed in this batch.

Android now carries IN_APP/EXTERNAL_LINK into ticket proposal policy. Public
external Tailscale links give scan/paste-in-cmux guidance and cannot open an
authorizing confirmation. Legacy external mixed tickets offer only native
choices, still checked against the current account/team directory. In-app mixed
tickets offer their numeric Tailscale choices in priority/ID order; native routes
are not a fallback for that selection. Hostnames cannot grant fresh exact-address
authority. The public parser remains compatible with historical locator strings;
the entry policy determines whether they can authorize a new connection.

Any legacy ticket containing a self-dialing or declared debug-loopback route is
rejected as a whole, using the existing byte-based `BrowserLoopbackHost` classifier
shared with browser routing. This follows `CmxLoopbackHost` rather than silently
dropping a local route and accepting another route from the same ticket. Android
production pairing does not expose iOS's simulator-only loopback exception.

New entry retires a pending confirmation. The saved-state key for in-app public
confirmation changed so an older version's source-ambiguous pending link cannot
restore as in-app authority. Bearer-bearing ticket proposals remain memory-only;
account/team ownership, saved-route admission and ticket persistence still use
their existing guards. Saved Direct/Tailscale method handling is a separate layer.

**35 focused JVM tests passed** (15 ticket entry/lifecycle, eight launch routes,
12 Swift ordering references). Coverage checks entry-specific choices, attempted selection of a
filtered route, exact-address ordering, hostname rejection, mixed local routes,
account/directory ownership and public launch handling, plus the existing Swift
ordering fixtures. Main and instrumentation Kotlin compile. The revised chooser
fixtures are compiled but await the next combined Android milestone: in-app
Tailscale-only confirmation and external native-only confirmation. No physical
connection, Intent-to-screen runtime check or new APK is claimed. Evidence:
`captures/runtime/pairing-entry-policy/`, including exact upstream source hashes.
Full saved-method/default-selection and real Mac/Pixel acceptance remain open.

**Delivery:** signed **596** includes native RPC auth, shared/saved Tailscale
admission and Mac-wide ticket gates. Token recovery, route ordering and optional
relay changes are newer source. The route chooser now has passing Android
acceptance in the optional-relay batch; see
[DIRECT_CONNECTION.md](DIRECT_CONNECTION.md#optional-remote-relay-hint-2026-10-05).
[PIXEL_INSTALL.md](PIXEL_INSTALL.md) is authoritative for signed downloads.
Physical acceptance remains open.

## Pairing route order (2026-10-05)

The explicit legacy-ticket confirmation now orders supported choices by ascending
route priority, then route ID, matching the pinned iOS `routeSortsBefore`
comparator. Previously it used incoming array order. ID comparison normalizes to
NFC and compares Unicode scalars; plain JVM UTF-16 ordering differs for supplementary
characters. Equal keys retain input order. Sorting happens before supported-route
filtering and public-code deduplication, without modifying the decoded ticket.
No route is dialed or authorized by sorting: the user still confirms a choice,
then account/team/host identity and the existing route-specific gates apply.

`scripts/generate-route-order-fixtures.py` extracts the complete comparator
unchanged from `MobileShellComposite+Helpers.swift` at upstream
`186cec79781256867ad4516f0802118738bd2393`. It runs that comparator in Swift against
synthetic id/priority fields. Twelve exported cases cover signed priority limits,
permutations, ties, duplicate IDs, prefixes, canonical equivalents, combining
marks and supplementary Unicode. This is a comparator reference, not a simulated
connection or a full iOS route-policy implementation.

**104 focused JVM tests passed**, zero failures/errors/skips: twelve Swift reference
cases, ten ticket confirmation/account/lifecycle cases, thirteen ticket-store
cases, thirty-nine manual pairing cases, twenty-two saved Tailscale cases and
eight rejected-token recovery cases. Two new ticket-choice cases check default
order, unsupported routes, deduplication, unchanged tickets and absence of an
implicit connection. Native/Tailscale tests now select by type rather than row
position. The updated Compose chooser test checks the initial native explanation,
explicit Tailscale selection and explicit native selection; Android test Kotlin
compilation passed. That revised UI case has **not yet run on Android**.
Evidence: `captures/runtime/route-order/`. No APK build or emulator was started;
build **596** remains the latest verified download and excludes these changes.

### Route policy follow-up

The inspected `NativeIrohBackend`, `NativeMacDialIntent` and
`NativeSavedTailscaleRuntime` retain strict Direct/Tailscale behavior. This comparator
change does not alter those saved methods. The Android explicit chooser and iOS
entry-source-specific QR policy are different layers: iOS in-app manual entry
can authorize exact Tailscale coordinates, while external URL receipt cannot.
A complete comparison of those entry flows remains open; this checkpoint does
not claim that all iOS route eligibility/default-selection behavior is matched.

The following relay difference was subsequently fixed and verified with the
scope described in [DIRECT_CONNECTION.md](DIRECT_CONNECTION.md#optional-remote-relay-hint-2026-10-05).
The original finding was: upstream
`ios/cmuxPackage/Sources/cmuxFeature/MobileIrxRuntimeComposition+Dial.swift`
passes `record.relayURLs.first ?? directory.relayURLs.first` as an optional hint.
Android `NativeIrohBackend` throws if both are absent and `IrxEndpointRuntime.dial`
also requires a non-null relay for Automatic. Investigate the upstream endpoint
supervisor and binding/admission contract before changing this; missing remote
relay metadata must be distinguished from missing local relay credentials.
Direct already supports a null relay and a separate relay-disabled endpoint.
The additional primary-source file and hash are retained under
`captures/runtime/transport-route-audit/`. No change to this relay behavior or
claim of native runtime acceptance is made here. Global parity pins are unchanged.

## native RPC auth and routing race (2026-10-05)

Android now models RPC authorization as a transport property. Only
`IrxMobileRpcTransport` declares transport admission: its session factory returns
`IrxClientSession` only after peer identity/ALPN/host admission succeeds. Its RPC
frames omit the entire `auth` object and never request a Stack token, including
status, ticket-scoped leases and future verbs. Default TCP/Tailscale transports
retain account auth and applicable ticket context. Host capability strings cannot
change this local transport classification. Native write/repair admission guards
remain in place; auth omission does not bypass transport retirement.

Reviewed `MobileCoreRPCClient.swift` at upstream
`186cec79781256867ad4516f0802118738bd2393`: Iroh selects `transportAdmission`, and
`requestDataWithAuth` removes `auth` before requesting either credential. The
Android endpoint/session admission and native write guards were inspected too.
Exact reference hashes and selected source copies are in the evidence directory.
Saved native tickets retain selection/lifecycle context; their tokens do not need
to participate in native initial admission under this protocol.

A preference change while directory discovery waits now re-enters the dedicated
saved-Tailscale runtime. It cannot fall through the native backend's older TCP
path and bypass manual-ticket acquisition. A regression changes Iroh to Tailscale
during the wait and verifies ticket acquisition with no backend dial.

**115 focused JVM checks passed**, no failures/errors/skips: six authorization,
21 saved-Tailscale, 21 native runtime, 37 pairing authority, 13 pool, eight ticket
RPC and nine control-repair cases. The initial revocation fixture failed because
Kotlin delegation bypassed its overridden `write` via `writeWithGeneration`; the
fixture now guards both, as the inspected production transport does. Original
failure XML/log and final passing evidence are retained in
`captures/runtime/native-rpc-auth/`. The first repair-suite wildcard matched no
class; the final run explicitly included `MobileControlRepairTest`.

No Android runtime or physical native acceptance is claimed for this change.
Batch 589 still builds source `cba6c6e`, before this checkpoint; poll its existing
run instead of starting another. Signed 581 remains the verified delivery until
589 completes and is checked. Next: legacy mutation gates, physical native pairing
and recovery, production push/notice configuration and UI/accessibility acceptance.
Goal active; global parity pins unchanged.

## saved-ticket initial Tailscale handshake (2026-10-05)

Saved encrypted context is now loaded and validated before connection dispatch.
A captured admission binds the context to its saved public route, account/team,
ticket revision and host/build. The Tailscale authority checks that binding before
dialing, verifies the host against both the grant and saved record, and uses the
context on its first protected workspace request. It does not replace a saved
selection with a newly acquired manual Mac ticket. Expired context retains the
selection while the token policy falls back to account auth.

Saved-record admission also participates in the transport/token/frame guards and
periodic retirement, so forgetting/replacing a ticket can close a pending or live
connection. Keystore context checks remain cached by credential-store revision.
Native Iroh saved records now fail early when context is missing or stale, but
still apply it after existing native admission; native transport-auth semantics
remain a separate open review.

**100 focused JVM checks passed**, zero failures/errors/skips: 37 Tailscale
authority, 20 shared saved-Tailscale, 13 ticket store, 22 pairing persistence and
eight RPC context checks. Six new authority cases cover first-request auth,
expiry, pre-dial binding/retirement, host/build mismatch, retirement during token
lookup, and retirement after connection. Evidence:
`captures/runtime/saved-ticket-handshake/`. No Android runtime/physical acceptance
is claimed and no emulator was started.

Signed batch 589 remains tied to `cba6c6e`, before this and the shared-ticket
checkpoint; poll <https://github.com/DocMorphic/cmux-app/actions/runs/37253661827>
without starting another build. Signed 581 is the latest verified delivery.
Next: native transport-auth review, the preference-change routing race, legacy
mutation gates, physical recovery and UI/accessibility/push acceptance. Goal active.

## shared Tailscale ticket admission (2026-10-05)

Computer Details' Add/Edit Tailscale probe now invokes the same manual ticket
request with the current account profile. The native-directory saved-Tailscale
runtime also acquires a ticket after matching host/device/build and confirming
which captured numeric route actually connected. The candidate transport exposes
that selected route only while connected and admitted.

The connection pool accepts an admission-time ticket context. Workspace and
compatibility validation use a temporary non-owning lease; successful publication
keeps the context with the pool entry and supplies it to each foreground, feed or
power lease. The wire's default context stays unchanged. One live entry performs
one acquisition; after its final lease closes, reconnect acquires a fresh ticket.
Malformed/denied tickets never publish an entry, and grant revocation during the
request closes the candidate before workspace access.

**108 JVM checks passed**, zero failures/errors/skips: 20 saved-Tailscale, 21 native
runtime, eight candidate transport, 13 pool, eight ticket RPC, 31 pairing authority
and seven manual request cases. Evidence: `captures/runtime/shared-manual-ticket/`.
These are framed fixture tests, not Android runtime or physical Mac acceptance.
No local emulator or APK build was started in this checkpoint.

Signed batch **589**, source `cba6c6ec10e36871f4fcf78d116ef1673ae68a64`, is running
at <https://github.com/DocMorphic/cmux-app/actions/runs/37253661827>. It contains the
preceding pairing work, not this checkpoint. Poll that exact run; do not dispatch
a duplicate. Signed **581** remains the latest verified delivery until all 589
gates pass. Next: native transport auth, legacy mutation gates, remaining saved-
ticket initial-handshake behavior, and complete physical pairing/recovery checks.
Audit the backend routing fallback when connection preferences change mid-admission.
Push/notice configuration and UI/accessibility acceptance also remain open.
Goal active; no global reference pins advanced.

## manual legacy Tailscale tickets (2026-10-05)

The legacy Tailscale connector now requests `mobile.attach_ticket.create` after
consent/grant and host identity checks, before protected workspace admission. It
requests a 3,600-second Mac ticket with `target: ticket_only`, explicitly omits
any existing attach token from that request, validates returned host/account
hints, and replaces advertised routes with the exact admitted numeric peer.
Selection, expiry, token and compatibility metadata are preserved. Reconnects
reacquire a session ticket on the pinned route; these manually acquired tickets
are not persisted as scanned ticket records.

Fallback matches the reviewed iOS RPC code/message allowlist. It returns to the
existing account-authenticated workspace check; no synthetic device identity is
needed because this path already verified host status. Malformed successful
responses, authentication/network failures and cancellation do not trigger this
fallback or try another route after the ticket request failed. An account change
before admission prevents workspace access and grant promotion.

**46 focused JVM checks passed**, zero failures/errors/skips: seven request cases,
31 Tailscale authority cases and eight RPC context cases. The framed tests verify
parameters/auth, route restriction, fallback, identity mismatch, cancellation,
grant timing and pinned reconnect. Evidence: `captures/runtime/manual-attach-ticket/`.
The codec test wildcard matched no class; no codec suite rerun is claimed. No
Android runtime or physical device check was performed for this checkpoint.

The Computer Details route editor and the native-directory saved-Tailscale path
still need this acquisition behavior. Native transport-auth review, legacy group
mutation gates, physical recovery, push/notice infrastructure and UI/accessibility
acceptance remain open. Signed 581 remains the latest verified delivery until a
new batch completes all delivery gates. Goal active; PR #1 open/draft.

## legacy ticket input and Android checks (2026-10-05)

Legacy ticket links and pasted input now reach an explicit route chooser. Native
routes require a current account/team directory match; Tailscale routes require
consent and verified host identity. Tailscale ticket context is installed before
the first authenticated workspace request. Successful pairing saves the selected
public route and encrypted ticket context in one transaction. Native connections
receive a scoped ticket view after existing transport admission.

Pending tickets survive Activity recreation in ViewModel memory. Bearer-bearing
links and drafts are excluded from saved-state Bundles, public pairing records
and route hashes. Cancelled or account-changed attempts cannot silently reconnect
without their context. Unsubmitted legacy/invalid drafts are deliberately not
restored; public pairing drafts are. Account-capable Mac mutations omit ticket
auth per request without changing sibling connection views.

**92 JVM checks and six Android checks passed** (Android **35.268 seconds**).
Android coverage includes explicit route choice/cancellation, Activity recreation,
saved draft handling, real Keystore storage/Forget, and onboarding restoration.
Four production classes passed arm64 ART loading. The installed debug APK matched
the tested build; source hashes matched; screenshots were inspected and the crash
buffer was empty. Emulator settings were restored and the sole API37/16KB AVD
was stopped and reaped. Evidence: `captures/runtime/attach-ticket-input/`.

These are component/storage checks, not a live authenticated Pixel/Mac pairing
acceptance result. Signed **581** remains the latest verified delivery and predates
this work. Next: manual ticket acquisition with exact-route constraints, native
transport auth review, legacy group mutation gates, and complete physical pairing/
recovery acceptance. Production push/notice configuration, modal/accessibility
work and the broad upstream audit also remain. PR #1 is open/draft; goal active;
global parity pins unchanged.

## Storage and caller-local RPC context (2026-10-05)

`NativeAttachTicketStore` saves the minimal selection/token/expiry context under
`attach_ticket_contexts` in `NativeCredentialStore`'s existing AES-GCM/Android
Keystore transaction. Tokens and raw ticket URLs never enter `PairedMac.code`,
origin hashes or its string representation. The public record carries a random
`attach_ticket_revision`; the credential entry binds it to account, team, stable
origin, canonical device, build and exact public route string. Installation
requires a current visible, owned and route-authorized record, matching ticket
identity/account hints and coverage of every selected public route. Iroh endpoint
identity must match; Tailscale comparisons reuse the existing canonical public
route parser without resolving or dialing addresses.

Replacement changes the revision and prunes the old secret. Menu captures,
reconnect writes and captured Forget cleanup compare that revision, preventing
an old operation from overwriting or deleting a replacement. Ordinary reconnect
retains context only for the same route; route replacement drops it. Credential
transactions, local Forget and native Forget prune detached/misbound/duplicate
entries. An attached revision with missing or malformed context fails on read,
instead of becoming an unscoped connection. Persisted context requires all five
canonical fields, including explicit nullable expiry, so a missing expiry cannot
silently widen a token lifetime. Expired or tokenless tickets still retain their
selection; the existing policy determines whether a token can be sent.

`MobileRpcClient.withAttachTicket` transfers a caller's owned connection handle
into a ticket view. The context travels with each request through all lease layers;
the pooled transport's default and sibling clients remain unchanged. Closing a
view releases its underlying handle once. Its admission callback is retained in
the pending request and checked before token lookup, before a frame write and by
existing resend/repair paths. `NativeAppConnections.connectSaved` creates this
view for records carrying a ticket revision and rechecks account/team, visibility,
route and revision. Keystore reads are repeated only when the credential-store
revision changes; current team admission is checked on every request.

**105 JVM tests passed**, no failures/errors/skips: 13 storage lifecycle cases,
seven framed ticket/lease cases, nine ticket-policy, 13 pool, nine control-repair,
22 pairing-persistence, 12 Forget, four menu-capture, seven visibility and nine
terminal-sizing cases. The first 75 checks also passed. The new storage tests use
JSON state through the production lifecycle functions; they do not instrument
Android Keystore. The RPC cases exercise actual framing through a fixture wire,
including sibling ticket isolation, one-handle close, per-request omission and
retirement during access-token lookup with no frame sent. Evidence/logs/XML/source
hashes: `captures/runtime/attach-ticket-storage/` (ignored).

**The legacy paste/scan producer is still pending.** No existing record acquires
credentials automatically. The install API must be called only after verified
account/host/route admission. Complete UI ticket selection, exact-route/manual
acquisition, native transport-auth review and capability-aware mutations next.
The saved-connection view currently attaches after ordinary route admission;
historical hosts needing a ticket during that initial handshake also need explicit
integration. A JVM pass does not establish old-host, physical pairing, lifecycle
performance or Android encrypted-storage acceptance. No new APK/device run;
signed 581 predates this work. Goal active and global references unchanged.

## Codec implementation and Swift fixtures (2026-10-05)

`MobileAttachTicketCodec` now decodes full/compact JSON and the ancient `pair`
and payload-bearing `attach` URLs. It preserves workspace/terminal selection,
canonical-versus-alias token precedence, optional expiry, UUID device spelling,
route ordering/IDs/priorities, endpoint shape and current/historical hint metadata.
Compact tickets discard token/name/expiry fields just like Swift. Expired full
attach tickets remain structurally readable; their token policy still refuses an
expired bearer. Ancient `pair` URLs enforce expiry at input. Unknown compatibility
is normalized to zero only for URL input (and compact DTOs), not raw full RPC data.

`PairingTicketJson` provides strict bounded JSON without Android/JVM `org.json`
coercion differences. Duplicate decoded keys, invalid Unicode/UTF-8, ambiguous URL
query keys, credential query parameters, decorated authorities/paths/fragments,
nonstandard JSON and oversized input fail with fixed redacted errors. Deliberate
input limits are 100,000 URL characters, 65,536 decoded bytes/JSON characters,
16 nesting levels, 256 entries per JSON collection, 32 routes, 1,024-character
ordinary fields and 4,096-character tokens. URL/hint fields have their separate
8,192/2,048-character bounds. Swift does not impose all these resource limits or
reject every ambiguous URL shape; Android applies them at its untrusted boundary.
No decoded ticket or path hint by itself authorizes a connection. Hint structural
safety is not freshness: future consumers must still verify current observation,
expiry, active provider/profile and managed-relay admission before dialing.

The committed synthetic fixtures are generated with:

```sh
python3 scripts/generate-attach-ticket-fixtures.py \
  --source /path/to/cmux
```

The generator reads 33 exact files at
`186cec79781256867ad4516f0802118738bd2393`: 32 shared Swift dependencies and
`CmxAttachTicketInput.swift`. It compiles the unchanged wire-declaration prefix of
`CmxTransport.swift` before `public protocol CmxByteTransport:`; all other sources
are unchanged complete files. The fixture JSON records original/compiled hashes,
the extraction boundary and runner hash. Only synthetic identities, addresses and
tokens are used. It executes the upstream URL parser plus compact/full codecs on
this Mac, not the complete upstream test suite or an iOS runtime.

**84 JVM tests passed**, zero failures/errors/skips. The 59 interop cases include
26 Swift acceptances and 33 rejections; applicable cases also compare raw RPC
results, including missing compatibility. Coverage includes old hint forms,
current public/private/LAN/VPN hints, source/profile mismatches, one-hour lifetimes,
IPv4/IPv6/mapped-address exclusions, relay URL restrictions, compact DTO typing,
full-token aliases, fractional dates and ancient expiry. Six boundary tests cover
JSON/URL ambiguity, size/depth/number limits, redaction and unchanged v2/v3 UI
admission. Nine policy, four framed-request/lease and six parser regressions pass.
An intermediate generator assertion incorrectly expected fractional ISO dates to
fail; actual Swift accepted them, and the fixture expectation was corrected.
Original logs, final XML, source hashes and receipt are retained in
`captures/runtime/attach-ticket-codec/` (ignored).

**Legacy pairing is not connected to the UI yet.** Next integrate encrypted scoped
persistence, public-locator separation, exact-route acquisition, transport-admitted
auth and capability-aware mutations. Do not store raw ticket URLs in the existing
public code/origin fields or enable parsing while silently dropping scope/token.
No APK or Android runtime run was done for this codec; Pixel absent, no emulator
started. Signed build 581 predates both the codec and RPC policy. Global parity
pins remain unchanged; live old-host and physical Mac/Pixel acceptance remain open.

## RPC policy implementation (2026-10-05)

`MobileAttachTicketContext` now carries bounded workspace/terminal selection,
optional token and expiry with a redacted string representation. It implements
the reviewed iOS terminal/workspace coverage, alias-conflict checks, inclusive
expiry, Mac-wide mutation admission and per-method token selection. Feed/status/
unknown requests omit attach context. Panel-only browser verbs require a Mac-wide
ticket; terminal management verbs use terminal coverage.

`MobileRpcClient` replaces its unused raw-token constructor option with this typed
context. Every non-status request still requires account auth. Borrowed clients
retain the same context, and the explicit internal `OMIT` policy applies to one
request without changing later requests or other leases. Mutation callers must
separately verify account-capability admission before choosing omission. Existing
production connectors still provide no ticket, and group capability gates are
unchanged. This does **not** enable legacy pairing URLs yet.

**53 JVM tests passed**, no failures/errors/skips: nine context-policy cases, four
framed RPC/lease cases, 25 existing RPC/pool/socket cases and 15 terminal-sizing
regressions. The first 37 passed before explicit omission was added. A missed
positional argument in the sizing call site caused an intermediate compilation
failure; naming that argument fixed it, and the final 38 plus 15 checks passed.
Evidence: `captures/runtime/attach-ticket-policy/`, including original logs,
final XML and source hashes. The new transport tests exercise encoded frames
through the production client with a fixture transport; they are not physical
network or Android-runtime acceptance. No new APK was built, emulator launched or
Pixel data touched. Signed 581 predates this change.

Next: decode and validate the older URL grammars with source-generated fixtures,
keep ticket credentials out of public route strings, integrate encrypted scoped
persistence and exact-route acquisition, then connect capability-aware mutation
calls and verify end-to-end behavior. Current code also needs an explicit review
of iOS transport-admitted auth omission before a ticket is used over native Iroh.

## Scope (2026-10-05)

This section records the earlier source audit. The checkpoints above supersede
its implementation status; live legacy compatibility remains unverified.
The five files below were read at `204a11dfcc76280205e50406ab94270a1c152155`.
Their Git blobs are unchanged at `186cec79781256867ad4516f0802118738bd2393`.
The broad implemented/reviewed references remain unchanged. Original sources and
blob comparisons are in ignored `captures/runtime/attach-ticket-audit/`.

Paths are relative to `Packages/iOS/` in the upstream cmux repository:

| Source | Observed contract |
| --- | --- |
| `CmuxMobileRPC/Sources/CmuxMobileRPC/CmxAttachTicketInput.swift` | Accepts ancient `pair` links, current plain attach v2/v3 links, and base64url `payload` attach links. Payload decoding selects compact keys (`v`) or full Codable keys (`version`). Encoded tickets are structurally validated; this entry point does not reject them merely because they have expired. Ancient `pair` links have their own expiry check. Unknown compatibility becomes version zero. |
| `CmuxMobileRPC/Sources/CmuxMobileRPC/MobileCoreRPCAttachTicketCoverage.swift` | Empty trimmed workspace ID covers all workspaces/terminals. A scoped workspace must match the requested workspace. A scoped terminal must match the requested terminal; a supplied conflicting workspace rejects it. Camel-case `workspaceID`/`terminalID` parameters are detected as ignored aliases. |
| `CmuxMobileShell/Sources/CmuxMobileShell/MobileShellWorkspaceMutationTicketPolicy.swift` | Fresh account-mutation capability admits Mac-wide workspace mutations. Otherwise a nonempty auth token, unexpired ticket and empty trimmed workspace ID are all required. The account-authorized path omits narrowing ticket context. |
| `CmuxMobileShell/Sources/CmuxMobileShell/MobileShellComposite+ManualAttachTicket.swift` | For routes admitted by the existing Stack-auth route policy, requests `mobile.attach_ticket.create` with `ttl_seconds: 3600`, `scope: mac`, `target: ticket_only`. Constrains returned routes to the exact requested route. Only specified unsupported/unavailable RPC failures allow a synthetic-ticket fallback; malformed successful responses fail. Synthetic IDs are placeholders until authenticated host status supplies identity. |
| `CmuxMobileShell/Sources/CmuxMobileShell/MobileManualAttachTicketCreateResponse.swift` | Decodes a `ticket` field with ISO-8601 dates. |

The decoded ticket is not, by itself, proof of host identity or authorization.
The age rules at QR input and at mutation authorization serve different purposes;
retaining an old scanned route must not grant an expired token mutation authority.

### Wire and request follow-up

Also inspected `CMUXMobileCore`'s `CmxTransport.swift`, compact coder and its three
DTOs. These five source files are unchanged between the two references above:

- Full tickets require `version`, `workspaceID`, `macDeviceID` and `routes`.
  Canonical `auth_token` takes precedence over `authToken`; blank present tokens
  fail validation. Version must equal 1, routes must be nonempty and their endpoint
  shape must match their kind. Expiry is inclusive (`expiresAt <= now`); missing
  expiry never expires. These are structural checks, not route authorization.
- Compact tickets omit tokens, names and expiry. Historical `e`/`n` keys are
  ignored. `u` maps to email if it contains `@`, otherwise user ID. Missing route
  IDs are synthesized by kind occurrence; missing priority is zero. An explicit
  endpoint `t` wins; otherwise the decoder chooses URL, peer, then host/port from
  present keys. Historical peer path hints decode but are not emitted by current
  compact encoding. Decoding them must not silently authorize a new network route.

Read `MobileCoreRPCClient.requestDataWithAuth` and its per-method coverage dispatch
at `204a11d`, then inspected its exact diff to `186cec7`:

- Transport-admitted connections remove request auth. Bearer-authorized connections
  always require Stack auth for non-status requests; attach tokens are supplemental.
  Covered, unexpired tokens are included only under the `whenCovered` policy.
  Account-authorized Mac mutations can explicitly omit the token. Expired tokens
  are omitted when Stack fallback is allowed.
- Conflicting terminal aliases or ignored camel-case selection keys prevent
  attaching a scope token. Notification feed requests omit scoped attach context.
  Browser panel-only methods can carry a token only for a Mac-wide ticket.
- The latest diff adds terminal coverage for `mobile.terminal.reattach`,
  `mobile.terminal.size_policy.set` and `mobile.terminal.participant.disconnect`;
  it adds token omission for `feed.list`, `feed.text`, `feed.permission.reply`,
  `feed.question.reply` and `feed.exit_plan.reply`. It also adds optional combined
  authenticated host status in workspace-list responses. That new response path
  still needs a separate Android compatibility review.

The pairing input test diff changes only a fixture hostname. This inspection does
not mean the Swift tests were executed.

### Current host follow-up

Read the ticket record creation/lookup/resource tracking in
`Sources/Mobile/MobileAttachTicketStore.swift`, the scope dispatch in
`MobileHostService+TicketAuthorization.swift`, and account verifier in
`MobileHostAuthorizationSupport.swift` at `186cec7`. The store mints random tokens,
retains records in memory, prunes expired records and records workspaces/terminals
created under a token. Those created resources can be allowed by later scoped
requests. Mac-wide group mutations still require an empty ticket workspace ID.

The host policy permits account-wide feed reads with a scoped ticket; agent feed
replies carrying only a request ID reject a scoped ticket. The iOS client therefore
omits that supplemental context for those account-authorized feed calls. Blindly
adding the Android constructor's token to every request would break this behavior.

The account verifier independently compares the freshly known local user with the
verified remote Stack user. Its token-to-user cache lasts 60 seconds, refreshes
ahead inside 15 seconds and bounds verification to 10 seconds. Ticket possession
cannot replace that account check. The main service's `authorizationError` verifies
the account before `ticketAuthorizationResultIfNeeded` applies a current record's
scope. A missing, unknown or expired token leaves the account gate authoritative;
the capability advertises this behavior. This observation applies to this current
host source, not to historical hosts lacking the capability.

Read the host test cases for workspace-scoped actions, account-wide notification
reads and agent-feed reply scope. They call the scope helper directly; their test
fixtures omitting Stack credentials do not demonstrate successful unauthenticated
RPC. They have not been executed here. Full URL/input tests, token acquisition call
sites and refresh/retirement lifecycle remain before Android integration.

## Android state at `fc2a3fa`

- `PairingCodeParser` accepts plain attach v2/v3 only. It has no ancient `pair`,
  compact payload or full-key ticket model/decoder, and rejects credential-named
  query fields. It is currently a bounded public route preview.
- `MobileRpcClient` already has an optional `attachToken` constructor argument and
  can send `auth.attach_token` beside the account token, except on host-status
  requests. Production connectors do not supply a ticket. This transport field
  alone does not implement ticket validation, expiry, request coverage or refresh.
- Group mutations require `workspace.mutations.account_auth.v1` plus the operation
  capability. Removing this gate without implementing the ticket policy would be
  incorrect for the older hosts in scope.

## Required implementation and evidence

1. Finish URL/validation tests, historical host behavior, token acquisition call
   sites and refresh behavior. The reviewed current code does not establish the
   complete ticket lifecycle or old-host interoperability.
2. Add bounded decoding with unchanged Swift-generated fixtures for compact/full
   payloads and ancient pairing URLs. Cover bad versions, duplicate fields,
   missing/invalid identities, dates, routes and oversized input. Preserve the
   current v2/v3 behavior and route-admission rules.
3. Carry ticket credentials only in the encrypted credential store and scoped
   connection state. Do not use the existing public route-string persistence or
   diagnostic output to store/log a bearer-bearing URL. Check replacement,
   revocation, expiry, restart and account/team ownership explicitly.
4. Integrate ticket coverage, account-capability precedence and exact-route manual
   refresh across main and independently borrowed/feed clients. A scoped ticket
   must not authorize another workspace/terminal or a Mac-wide group mutation.
   Do not add fallback after malformed or unauthenticated responses.
5. Verify exact request auth/parameters and failure behavior with generated host
   fixtures, then exercise a supported real Mac connection. Keep live acceptance
   separate from parser/unit evidence. Availability of a genuinely ticket-only
   host has not been established.

The remaining integration work is open. The RPC policy above postdates build 581.
