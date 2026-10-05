# Attach-ticket compatibility audit

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
