# Mac minimum-version compatibility

## Profile and source

The Android app keeps its own version (0.2.0), package and account identity.
For Mac protocol compatibility it explicitly selects the reviewed iOS **1.0.6
production** tier, named `iroh-v2-ios-1.0.6-prod-v1`. Both Android debug and release
use that profile. This does not identify Android as an official iOS build.

Source: cmux `0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`, specifically
MobileMacCompatPolicy, MobileMacAppVersion, MobileMacVersionCompatibility,
MobileMacCompatCenter, MobileHostStatusResponse, Shared.xcconfig and
web/data/mobile-mac-compat.ts. That source associates Mac 0.64.25 with the v2
backend, pairing opt-in and persistent Mac identity changes. Android already
uses that v2 transport. Using Android's 0.2.0 as an iOS selector would incorrectly
select no requirement.

The public `https://cmux.com/api/mobile-mac-compat` response fetched on 2026-10-03
matches the reviewed schema and selected production requirement: stable 0.64.25,
nightly 0.64.25-nightly.3522337919701. The local evidence copy is under
captures/runtime/mac-compatibility. This is an explicitly chosen compatibility
profile; it is not proof of physical interoperability with every newer Mac.
The global parity pin is unchanged.

## Behavior

- Complete bounded JSON parsing rejects malformed entries, duplicate/unordered
  tiers, reversed ranges, inconsistent production/legacy fields, wrong scalar
  types, overflowing integers, malformed unknown build kinds, trailing content,
  lenient Android JSON extensions and excessive nesting. A failed load retains
  the last good policy. A valid empty list deliberately removes version floors.
- Tier selection uses the greatest inclusive minimum; a maximum excluding the
  profile yields no constraint rather than falling back to an older tier.
- Numeric versions use one to three ASCII decimal, signed-64-bit components.
  Nightly build counters use unsigned 64-bit comparison. Stable and nightly
  grammars remain separate; missing/malformed constrained versions require an
  update. As upstream, a stable-only policy still requires a valid nightly
  version but imposes no nightly minimum on a valid reported nightly.
- Startup uses a validated cached response or the baked profile. A public,
  unauthenticated fetch runs asynchronously at startup and hourly while the
  shared connection runtime exists, including background service ownership.
  Foreground activation also refreshes, with five-minute attempt coalescing.
  Downloads are bounded to 128 KiB and 15 seconds; redirects are disabled.
- The cache key includes origin, explicit protocol profile, package, version,
  version code and installation update timestamp. Other build keys are pruned.
  No account token, host identity or version observation is sent to this API.
- Admission runs after expected device/build and account authorization checks,
  including a workspace-list account probe. It covers Iroh/direct pooled peers,
  saved Tailscale pooled peers, and explicit QR/Computer Details Tailscale pairing
  before promoting consent to a saved grant. Foreground, feed/service and diagnostic consumers
  share these paths. Existing ownership/route/pairing guards still apply.
- A stricter refresh rechecks the actual shared wires and sends an update error
  to all borrowers of an affected connection. Other Macs/builds remain live.
  A later relaxed policy permits a fresh connection, never revives a closed wire.
- Authenticated observations produce account-incarnation/device/build-specific
  row warnings in saved, discovered, management and hidden lists. A dialog gives
  the reported/required versions and a fixed official stable/nightly release
  link. Remote download fields never control navigation. Warnings clear after
  a compatible handshake or relaxed policy, and disappear on account retirement.

## Verification

85 focused JVM tests passed (policy/gate, Iroh runtime, saved Tailscale runtime,
Tailscale pairing authority and shared RPC pool), including admission denial,
pending-handshake refresh, all-borrower retirement, sibling isolation, account
incarnations, cache fallback and retry after update. Debug and instrumentation
APKs built in 56 seconds. Two Android UI tests passed in 14.123 seconds on the
existing API 37 / 16 KB emulator: exact-build warning isolation, fixed stable and
nightly links, live warning updates and dismissal when the scope is retired.
The stable dialog screenshot was visually inspected. APK hashes are recorded in
the parity checkpoint. The emulator was stopped after testing.

The initial run had 81/82 passes: an existing route-change test observed the
logical closed flag just before the route observer finished transport.close().
It now waits for the actual transport closure; it still requires admission
rejection and completes under a bounded deadline. The initial log is retained.

## Remaining work

This implements minimum-version admission, not the entire separate iOS build
audience policy. Official namespace/tag admission, the narrow legacy Tailscale
exception, development tag grants, and discovery/presence audience filtering
still require implementation. Android debug must not inherit iOS DEBUG's
development-only audience unintentionally.

Version observations are retained only for the current process/account scope;
offline warning restoration after process death and warning-only presence
metadata are not implemented. New pairings without a saved row receive the
connection error; full iOS onboarding warning presentation remains unverified.
Physical stable/nightly Mac acceptance and current iOS visual comparison remain
open. Signed build 474 predates this feature; no signed milestone was dispatched
for this individual feature.
