## Access-only local session authority reconciled — 2026-10-25

Successful live access-only user validation now establishes a fenced encrypted
local session receipt. Account/team restoration and local workspace, draft,
pairing, route and history guards can use that incarnation without inventing a
refresh token. Bare SDK reads cannot establish it; fresh membership still gates
team authority. Initial v2 control access retains the original authenticated-pair
requirement; forced control access uses the public force accessor.

Owning restoration callers can cancel their wait while synchronous encrypted
storage retires in an independently tracked IO job. Late canceled, replaced or
revision-stale verification cannot commit or publish. Coalesced continuation
waiters retain the original owning-run completion behavior. This adds no hard
bound to underlying synchronous storage IO.

Full JVM: 3,484 total in 467 suites, 3,480 passed, four existing SSH skips, zero
failures/errors. Owned API36: 264 unique methods pass across two bound cohorts.
The full run completed 264 in 393.791 seconds with 262 passes and two fixture
failures; after fixture-only corrections, all 17 methods in both affected classes
passed in 14.46 seconds. The other 247 passes are retained from the full run;
the main APK is byte-identical. This is not a single full green 264-method run.
The corrections distinguish workspace keys per row and admit both user requests
before ineligible replies, preserving exact counts and authority assertions.
Full-v8 JVM XML is unchanged by the final test-APK build-v10. Earlier failures
and the unreproduced two-second JVM HTTP fixture timeout remain recorded.

The mounted team selector/PATCH, selected semantics and local terminal-input
retirement were verified; terminal wire input/output remains open. The full run's
real 60-second phase returned at 60017ms with ciphertext/pairing intact and no
PATCH. Both runs have empty crash logs, no preflight ANR, restored settings/window
and owned emulator exit 0. No new whole-original execution reference is claimed.

Full parity remains active. Complete route-family and access-only background
lifecycle, caller/phase admission/high-water reconciliation, snapshot storage-error
classification/deadlines, authentication-project reset/scoping, remaining
callback/form/backend/reporting, metadata/image/demo/identity/worker/diagnostic UI,
actual OS/locked storage/death, physical Pixel/Mac/provider/helper/Firebase,
matched full UI/accessibility and signed delivery remain open. No physical
install, real account/pairing/signing data, global pin or frozen signed722 changed.

Details: [Access-only session authority](ACCESS_ONLY_SESSION.md).
Evidence: `captures/recovery-20261024/access-only-checkpoint.json`.
Historical checkpoints below retain their original scope and open items.

## Application token misses and state clearing reconciled — 2026-10-24

Application token reads now distinguish a recoverable miss from an absent
session. Surviving refresh credentials, unavailable storage and current
transitions produce retryable errors. A winning unauthorized result clears
cached account presentation and its hint while preserving pairing, keys,
tokens, drafts and the email challenge. Definitive refresh rejection retains
its separate credential cleanup. Caller cancellation, session generation,
document revision and writer-registration fences prevent an old encrypted clear
from publishing over a new account. Token phases check their deadline after
both successful and failed operations.

Full JVM: 3,478 total in 466 suites, 3,474 passed, four existing SSH skips, zero
failures/errors. Owned API36: 254/254 in 388.728 seconds, 14 new methods, one
existing draft-ownership race and 239 regressions. The new reference executes
13 complete unchanged release declarations across 464 cases; independent
regeneration is byte-identical. Real encrypted-store tests cover cold clearing,
blocked encryption, cancellation, current/stale transitions, actual OAuth
replacement, sign-out capture, same-epoch revision replacement, preserved email
challenge and foreign-environment preservation. The real 60-second phase
returned at 60012ms with ciphertext/pairing intact and no PATCH. Crash log empty,
no preflight ANR, settings/window restored, owned emulator exit 0. Full JVM
build-v4 and final debug APK build-v6 bind this checkpoint; earlier fixture
failure and its queue repair remain recorded.

Full parity remains active. The original timer race, full phase-finish/high-water
cleanup and original cache backends are not executed by this reference. Complete
caller/phase admission, authentication-project reset/scoping, access-only account/
team/terminal authority, callback/form/backend/reporting, metadata/image/demo/
identity/worker and diagnostic/team-error UI, actual OS/locked storage/death,
physical Pixel/Mac/provider/helper/Firebase, matched full UI/accessibility and
signed delivery remain open. Reference host is macOS26.5.2/CoreFoundation5026.5,
not iOS. No physical install, real account/pairing/signing data, global pin or
frozen signed722 changed.

Details: [Application token state](APPLICATION_TOKEN_STATE.md).
Evidence: `captures/recovery-20261024/application-token-checkpoint.json`.
Historical checkpoints below retain their original scope and open items.

## Ordinary SDK token consumers reconciled — 2026-10-24

Ordinary verified and token-only reads now share the executed SDK resolver with
pending reads. Optional SDK access, typed resolved access and the application
retry bridge have separate outcomes. User/team preflights use SDK access;
coherent/deletion captures use resolved access and retain their own guards.
User HTTP reads headers after preflight; SDK list/PATCH requests read stored
headers without an extra freshness check. New header reads run on IO. Epoch,
receipt and fast-read fences remain, including cancellation when replacement
lands immediately after definitive refresh rejection. Pending credentials remain
excluded from ordinary and worker views.

Full JVM: 3,474 total in 464 suites, 3,470 passed, four existing SSH skips, zero
failures/errors. Owned API36: 239/239 in 373.973 seconds, nine new methods and
230 regressions. The 64-case reference executes three complete unchanged
StackAuthClient adapters with inert Stack results and real child cancellation;
it runs on ART beside the prior110-case resolver reference. Regeneration is
byte-identical. Actual encrypted-store/HTTP checks cover nil versus typed
failure, shared timeout/replacement, token-only ownership, restored-user probes,
application retry and post-rejection replacement. Stored-header checks cover
preflight versus HTTP snapshots and request counts. The real 60-second phase
returned at 60007ms with ciphertext/pairing intact and no PATCH. Crash log
empty, no preflight ANR, settings/window restored, owned exit0. Earlier fixture
failures and the unreproduced two-second JVM fixture timeout remain separate
evidence; its budget/assertions are unchanged and transport diagnostics were
added. Final full build-v7 and runtime-v2 bind the delivered development sources.

Full parity remains active. Complete application absent-token/unavailable-storage
projection and winning-owner state/cache clearing, access-only account/team/
terminal authority, callback/form/backend/reporting and caller-budget paths,
metadata/image/demo/reset/identity/worker and diagnostic/team-error UI, iOS-runtime
proof, actual OS/locked storage/death, physical Pixel/Mac/provider/helper/Firebase,
matched full UI/accessibility and signed delivery remain open. Source reference
host: macOS26.5.2/CoreFoundation5026.5; no whole original coordinator or iOS proof.
No physical install, real account/pairing/signing data, global pin or frozen
signed722 changed.

Details: [Ordinary token consumers](ORDINARY_TOKEN_CONSUMERS.md).
Evidence: `captures/recovery-20261024/ordinary-token-checkpoint.json`.

## Pending token resolution and shared refresh reconciled — 2026-10-24

Interactive and orphaned pending-user validation now applies the original SDK's
proactive and forced token-selection policy through the existing shared refresh
coordinator. Separate account objects coalesce exchanges and share cooldowns;
only an unexpired access token can survive a proactive transient failure or
timeout. Forced rejection cannot reuse that access. Pending and published owners
remain distinct, and public/worker callers still reject pending credentials.
Metadata writes retain the attempt; publication/replacement retires it. Late
responses cannot overwrite publication. A reconstructed background account cannot
validate an interactive flow's pending receipt while that flow owns publication.

Full JVM: 3,472 total in 462 suites, 3,468 passed, four existing SSH skips, zero
failures/errors. Owned API36: 230/230 in 363.422 seconds, ten new methods plus
220 regressions. The 110-case source resolver reference executes three complete
unchanged declarations and runs on ART. Regeneration is byte-identical. Actual
Android coalescing/cooldown/timeout/commit checks use disposable encrypted stores
and controlled HTTP. The real 60-second phase returned at 60008ms with
ciphertext/pairing intact and no PATCH. Crash log empty, no preflight ANR,
settings/window restored, owned exit0. Build-v6 reused identical JVM results and
rebuilt corrected Android fixtures; the app APK is unchanged across runtime-v1/v2.
Earlier fixture failures remain separate evidence.

Full parity remains active. Ordinary SDK consumer adoption and error/budget
projections, callback failures, direct OAuth/form/backend contracts, reporting/
diagnostic/team-error UI, metadata/image/demo/reset/identity/worker paths,
iOS-runtime proof, actual locked storage/OS/death, physical Pixel/Mac/provider/
helper/Firebase, matched full UI/accessibility and signed delivery remain open.
The reference uses inert getters, validity predicates and coordinator outcomes
on macOS26.5.2/CoreFoundation5026.5. Real child cancellation executes. No physical
install, real account/pairing/signing data, global pin or frozen signed722 changed.

Details: [Token resolution](TOKEN_RESOLUTION.md).
Evidence: `captures/recovery-20261023/token-resolution-checkpoint.json`.

## User-fetch error outcomes reconciled — 2026-10-24

Post-resolution SDK user reads now preserve the source's distinct nil, missing-
user and exact SDK rate-limit outcomes. Ineligible users and HTTP/known/network
failures become USER_NOT_SIGNED_IN and the generic sign-in message; malformed
successful user JSON becomes nil and the interactive coordinator's unauthorized
message. Foreign errors spelling RATE_LIMITED do not escape as SDK rate limits.
Parent cancellation remains cancellation. User JSON parsing runs off the UI
dispatcher. Real account tests verify unverified interactive credentials remain
pending, while orphaned nil-user cleanup and transient-failure preservation
remain distinct and keep pairing data.

Full JVM: 3,470 total in 461 suites, 3,466 passed, four existing SSH skips, zero
failures/errors. Owned API36: 220/220 in 352.36 seconds, five new methods plus
215 regressions. The 252-case reference executes complete original getUser,
adapters, coordinator completion and display-safe error projection with stated
inert seams; it also runs on ART. Real encrypted account/HTTP and live Compose
cases cover the production callers. The 60-second phase returned at 60,009ms
with ciphertext/pairing intact and no PATCH. Crash log empty, no preflight ANR,
settings/window restored, owned exit0. Receipt: 95 local inputs, 47 originals,
installed APKs and the corpus asset hash. Regeneration is identical. The two
failed runtime attempts and their corrected deadline/readiness fixtures remain
separate; the production APK is identical across the three attempts.

Full parity remains active: token-freshness/preflight and shared pending/verified
refresh ownership, callback failures, direct OAuth/form/backend protocols,
reporting/diagnostic/team-error UI, remaining metadata/image/demo/reset/identity/
worker work, iOS-runtime proof, actual locked storage/OS/death, physical Pixel/
Mac/provider/helper/Firebase, matched full UI/accessibility and signed delivery
remain open. Token presence and original phase/publication/receipt are inert
reference seams on macOS26.5.2/CoreFoundation5026.5. No physical install, real
account/pairing/signing data, global pin or frozen signed722 changed.

Details: [User-fetch error outcomes](USER_FETCH_ERRORS.md).
Evidence: `captures/recovery-20261023/user-fetch-checkpoint.json`.

## SDK transport and pending-session refresh reconciled — 2026-10-24

Shared SDK requests now send Hexclave-only headers, snapshot both tokens and
keep one nonce/body/refresh header through network and authentication retries.
Only exact known invalid-access-token 401s refresh; bare 401s and other known
errors do not. Effective status, empty known errors, source error details and
idempotent network retries follow the executed original declarations. Completed
429s return immediately; interrupted transfers follow network retry rules.
Interactive/orphaned pending sessions refresh through a receipt/epoch/phase
fence without granting unverified account authority. Delayed ordinary rejections
reuse an already-installed token. The duplicate raw userId HTTP path is removed.

Full JVM: 3,467 total in 460 suites, 3,463 passed, four existing SSH skips, zero
failures/errors. Owned API 36: 215/215 in 363.715 seconds, 14 new methods plus
201 prior regressions. The original 82-case transport reference runs on ART,
alongside the prior 368-case byte corpus. Real isolated account/Keystore/HTTP
cases exercise stable retries, interrupted responses, interactive and orphaned
recovery and replaced receipts. The 60-second phase returned at 60,024ms with
ciphertext/pairing intact and no PATCH. Crash log empty, no preflight ANR,
settings/window restored, owned exit0. Receipt: 87 local inputs, 44 originals,
exact installed APKs and source-reference asset hash. Regeneration is identical.

Full parity remains active: complete getUser/adaptor error/freshness projection,
teams/restore refresh-failure projection, direct OAuth/form/backend contracts,
Retry-After parser edges, SDK observer/callerReports and consent-aware reporting,
remaining JSON/metadata/token/mutation/identity/worker/reset work, image/demo UI,
iOS-runtime equivalence, actual locked storage/OS/death, physical Pixel/Mac/
provider/helper/Firebase, matched full UI/accessibility and signed delivery
remain open. The oracle uses inert transport/refresh/clock seams on measured
macOS26.5.2/CoreFoundation5026.5. The public unauthenticated probe did not
reproduce the source comment's alias-triggered429 claim. No physical install,
real account/pairing/signing data, global pin or frozen signed722 changed.

Details: [SDK transport](SDK_TRANSPORT.md).
Evidence: `captures/recovery-20261023/sdk-transport-checkpoint.json`.

## SDK response-byte decoding and cache extensions reconciled — 2026-10-24

SDK response and error JSON now decode from bytes with measured Foundation
semantics: encodings, duplicate-first keys, permitted trailing commas, rejected
trailing material/controls/Unicode and numeric eligibility boundaries. Invalid
user/list outcomes retain their existing separate nil/error/loaded-empty behavior.
Malformed legacy cached-user selection extensions cannot discard the shared
account or override its independent remembered team choice. Backend and terminal/
cloud decoders retain their separate existing contracts.

Full JVM: 3,454 total in 458 suites, 3,450 passed, four existing SSH skips, zero
failures/errors. Owned API 36: 201/201 in 357.094 seconds, six new cases plus 195
prior regressions. The exact 368-case original wire corpus executes on ART; eight
shared-cache cases execute in JVM tests. Actual account/Keystore/HTTP cases cover
encoded replies, duplicate flags through recovery, invalid-list scope retirement,
cache extensions and primary nil-user clearing with pairing/key retention. The
real 60-second phase returned at 60,015ms with ciphertext/pairing intact and no
PATCH. Crash log empty, no preflight ANR, restored settings/window, owned exit0.
The receipt binds 72 local inputs, 41 originals, installed APKs and the corpus
asset hash. Independent reference regeneration is byte-identical.

The oracle is measured macOS26.5.2/CoreFoundation5026.5, not an iOS-runtime proof;
this host has no Xcode/iOS Simulator SDK installed. Full parity remains active:
iOS Foundation equivalence, arbitrary numeric serialization/metadata consumers,
other parser/Codable paths, complete SDK transport/error/retry/nonce, remaining
backend/token/mutation bounds and identity/worker adoption, image/demo UI, full
auth-project reset, consent-aware reporting/rate integration, actual locked
storage/OS/death, physical Pixel/Mac/provider/helper/Firebase, matched full UI/
accessibility and signed release remain open. No physical install, real account/
pairing/signing material, global pin or frozen signed 722 changed.

Details: [SDK JSON response bytes](SDK_JSON_BYTES.md).
Evidence: `captures/recovery-20261023/sdk-json-checkpoint.json`.

## SDK user/team projections and empty-id authority reconciled — 2026-10-24

SDK user/team fields now preserve raw strings and defaults, including repeated
and empty-id rows. Common successful malformed/missing-items team bodies count as
loaded empty membership and retire old scope/proofs; transport failures remain
distinct. Android-only successful SDK user/team body, row and field caps are
removed. Profile-image/demo metadata survives encrypted account reconstruction;
its downstream rendering/demo flow remains open. Verified auth generation is
separate from optional connection identity, so empty-id users can load/recover
membership without obtaining scope, and older empty-id sessions cannot publish.

Full JVM: 3,446 total in 457 suites, 3,442 passed, four existing SSH skips, zero
failures/errors. Owned API 36: 195/195 in 371.92 seconds, five new account cases,
one new component case and 189 prior regressions. After a test-container inset
correction, the one component case passed again in 2.151 seconds; production APK
unchanged. The corrected image was inspected. Both runs have empty crash logs,
no preflight ANR, restored settings/window and owned exit0. The real 60-second
phase returned at 60,015ms with fixture ciphertext/pairing intact and no PATCH.
34 user, 19 list and 13 cached-user original projections regenerate identically,
including original 4,097-row/2,238,212-byte acceptance. The receipt binds 65 local
inputs and 41 whole originals, with the two test-APK runs kept distinct.

Full parity remains active: complete JSON lexical/Unicode/number/error and SDK
transport/retry/nonce behavior, remaining backend/token/mutation bounds and
identity/worker consumer adoption, image/demo UI, full auth-project reset,
consent-aware reporting/rate integration, actual locked storage/OS/death,
physical Pixel/Mac/provider/helper/Firebase, matched full UI/accessibility and
signed release remain open. No physical install, real account/pairing/signing
material, global pin or frozen signed 722 changed.

Details: [SDK user/team projections](SDK_USER_TEAM_PROJECTIONS.md).
Evidence: `captures/recovery-20261023/sdk-model-checkpoint.json`.

## Independent remembered team choice reconciled — 2026-10-24

The remembered team choice now lives separately from cached account/member rows
inside the existing encrypted document. New sign-in receipts, changed principals
and interrupted publication keep the preference while discarding old membership.
It grants no connection scope or continuation proof. Migration precedes card
pruning; explicit nil cannot be resurrected from a legacy card. Local logout and
readable empty-token restore clear it while preserving saved Macs. Choice-only
project reset is covered; complete auth-project token/backend reset remains open.

Full JVM: 3,434 total in 456 suites, 3,430 passed, four existing SSH skips, zero
failures/errors. Owned API 36: 189/189 in 365 seconds, six new encrypted-store/
completion cases plus 183 prior regressions. Existing unavailable-key logout
cases now assert independent-choice retirement and saved-Mac retention. The real
60-second phase returned at 60,010ms with fixture ciphertext/pairing intact and
no PATCH. Crash log empty, no preflight ANR, restored settings/window, owned
emulator exit0. Ten cases execute ten complete original lifecycle declarations
and regenerate identically. The receipt binds 51 local inputs and 40 originals.

Full parity remains active: primary SDK default/malformed models and metadata/
bounds/transport, full auth-project/backend/identity adoption, worker retry/rate/
reporting integration, actual locked storage/OS sleep/network loss/process death,
physical Pixel/Mac/provider/helper/Firebase, matched full UI/accessibility and
final signed release remain open. No real account/pairing/signing material,
physical install, global source pin or frozen signed 722 changed.

Details: [remembered team selection lifetime](TEAM_SELECTION_LIFETIME.md).
Evidence: `captures/recovery-20261023/team-choice-checkpoint.json`.

## Independent team refresh reads reconciled — 2026-10-24

Membership now fetches a fresh eligible user even after sign-in publication, and
the selected-team lookup runs independently alongside it under one network phase.
A selected-read failure cannot fail membership or replace a remembered choice.
Successful empty membership retains the explicit choice, grants no connection
scope and retires removed-team proofs. Team lookups preserve the authoritative
published account profile. Saved choices follow the original whitespace policy;
same-owner user publication keeps the choice while clearing cached member rows.

Full JVM: 3,416 total in 455 suites, 3,412 passed, four existing SSH skips, zero
failures/errors. Owned API 36: 183/183 in 347.427s, six new cases plus the prior 158,
two cached-computer cases and 17 further existing ingress/reply-worker checks. The real default phase fired at
60,014ms with fixture ciphertext/pairing preserved and no PATCH. Crash log empty,
no preflight ANR, restored settings/window, owned exit0. Ten refresh, ten selected
constructor and nine selection-store rows execute original declarations/sources
and regenerate identically; the receipt binds 42 local inputs and 38 originals.

Full parity remains active. Complete independent choice-store/new-login lifetime,
primary SDK default/malformed models and metadata/bounds/transport, worker rate/
retry/reporting integration, actual OS/locked-storage/death, physical/provider/
helper/Firebase, full matched UI/accessibility and final signed-release gates
remain open. No physical install, real account/pairing/signing data, global pin or
frozen signed 722 changed.

Details: [independent team refresh](TEAM_REFRESH_READS.md).
Evidence: `captures/recovery-20261023/team-reads-checkpoint.json`.

## Team network deadlines and publication races reconciled — 2026-10-24

Selection's fresh-user read and PATCH share one network phase; backend creation,
post-create membership and final selection have separate 60-second budgets.
Cancellation-ignoring actual tasks remain tracked after the public wait ends;
plain retries and token retry gates follow their separate source contracts.
Concurrent membership calls no longer wait in the old serializer. A completed
operation keeps its result if caller dispatch is delayed past the timer.

Publication changes membership proofs, scope and profile atomically before cache
persistence. Selection and failed-create fallback use current membership/profile,
so they cannot restore a removed team or older profile. Explicit cached selection
is independent from saved rows, including nil/stale ids; cached-only nil selection
still grants no loaded membership or connection authority.

Full JVM: 3,405 total, 3,401 passed, four existing SSH skips, zero failures in 454
suites. Owned API 36: 158/158 in 331.848s, including eight new cases and 150 prior
regressions. The real production deadline fired at 60,010ms on a progressing body,
with fixture ciphertext/pairing preserved and no PATCH. Crash log empty, no
preflight ANR, restored settings/window, owned exit0. Two before-fix invariant
failures, 19 local inputs, 35 whole original files and platform source/header
bindings support the checkpoint. Full parity remains active: callback fresh-user
and separate selected-team reads, full startup projection/SDK models, blocked
storage/real OS behavior, physical/provider/helper, complete UI/accessibility and
signed-release gates remain open. No phone/Mac install, global pin or frozen 722
change.

Details: [team operation deadlines and publication](TEAM_OPERATION_DEADLINES.md).
Evidence: `captures/recovery-20261023/team-phase-checkpoint.json`.

## Team selection and creation source behavior aligned — 2026-10-24

Selection now reads an eligible user before PATCH, accepts successful optional
response bodies and keeps independent request identities so a newer choice can
finish while an older PATCH is stalled. The inline picker shows the pending
choice, stays usable, and restores the committed choice on failure with the
pinned retry message in nine locales. The main screen awaits the actual action.
The existing Android create dialog remains an extension to the pinned iOS picker.

Backend creation is not replayed. A failed post-create list falls back to the
prior available teams and still attempts selection. A primed/cold API owner or
initially failed membership can create without inventing a loaded team generation
or connection scope. Loaded membership removal retires old proofs before final
selection, and effective nil/stale selection falls back like the source helper.
Create/select exclusion, latest-choice caching and account replacement are fenced.

Full JVM: 3,386 total, 3,382 passed, four existing SSH skips, zero failures in 453
suites. Owned API 36: 150/150 in 234.927s, including ten new cases and 140 existing
regressions; empty crash log, no preflight ANR, restored settings/window, exit0.
Nine wire and seven effective-selection rows execute complete original declarations;
those and nine locale resources reproduce byte-identically. 38 local inputs and 24
whole original files bind the evidence. Full parity remains active: public phase
budgets/noncooperative IO, remaining SDK/load/cache admission semantics, physical/
provider/helper/OS, full source/UI/accessibility and signed-release gates remain
open. No phone/Mac install, global source pin or frozen signed 722 change.

Details: [team mutation and picker mapping](TEAM_MUTATIONS.md).
Evidence: `captures/recovery-20261023/team-mutation-checkpoint.json`.

## Team-scope recovery follows upstream backoff — 2026-10-24

The process account now retries an unloaded team list at 2, 4, 8, 16, 32 and
then 60 seconds, with the separate source Retry-After/jitter policy for 429.
Successful empty membership counts as loaded. Recovery-only network signals
cannot bypass throttles; ordinary foreground validation still follows the iOS
route. Fresh sign-in/sign-out retire the old budget, stale failures cannot
throttle replacement accounts, and recovery ends after owning validation hooks.
The old periodic membership polling is removed.

JVM: 3,368 total, 3,364 passed, four existing SSH skips, zero failures, 452 suites.
Owned API 36: 130/130 in 217.104s, seven new account/Keystore/local-HTTP cases plus
123 regressions; empty crash log, no preflight ANR, restored settings/window,
emulator exit0. Complete original pure declarations supply 10 delay, 48 rate and
14 header reference rows, independently reproduced byte-for-byte. This closes
the bounded process recovery policy gap described in the older entry below;
it does not close full parity. Noncooperative IO/public-phase counting,
private-worker retry integration, real sleep/network loss, mutation eligibility,
SDK fallback/metadata, consent-aware reporting, physical/provider/helper/OS and
matched UI/accessibility/release gates remain open. Source pins and frozen
signed 722 are unchanged; no physical phone or Mac install was changed.

Details: [recovery source mapping and limits](TEAM_SCOPE_RECOVERY.md).
Evidence: `captures/recovery-20261023/team-recovery-checkpoint.json`.

## Team-refresh eligibility and failure continuity aligned — 2026-10-24

Direct team refresh now checks anonymous/restricted flags through the common user
decoder before fetching membership. Most user-read failures become unavailable;
cancellation and 429 remain distinct. The original coordinator retains a loaded
same-session team list when refresh fails, so broad HTTP 401/403 no longer erase
that authority or cached display. Successful removal, actual credential-session
loss, identity replacement and logout still retire old proofs. New-generation
membership failure retains the verified profile without its predecessor's proof,
and late user errors cannot overwrite a replacement profile/cache.

The complete pinned SDK User.swift was executed to derive 29 flag cases; Android
matches them and the generator reproduces the fixture byte-for-byte. Full JVM:
3,344 total, 3,340 passed, four existing SSH skips, zero failures in 450 suites. Ten new
JVM cases and seven account/Keystore/local-HTTP cases passed. Owned API 36: 123/123
in 193.491s, empty crash log, no preflight ANR, restored settings/window, exit0.
The previous 116 regressions also pass. Eleven local implementation/test/reference/
dependency files and twelve whole original files bind the evidence.

Full parity remains active. Full team recovery scheduling, Retry-After/backoff/
reporting, mutation eligibility, separate selected-team reads and malformed-list/
default-model semantics remain open; the bounded parser is not claimed equivalent
to every SDK fallback. Raw-token/access-only consumers, full backend/metadata/
identity adoption, actual OS/provider/physical Pixel/Mac, signed release and
broader source/UI/accessibility acceptance remain open. No phone/Mac install,
account/pairing/signing data, source pin or frozen signed722 was changed.

Details: [team-refresh source mapping and limits](TEAM_REFRESH_ELIGIBILITY.md).
Evidence: `captures/recovery-20261023/team-eligibility-checkpoint.json`.

## Account deletion now binds confirmation and results to identity — 2026-10-24

The dialog pins the published account, session generation and credential epoch.
Controller admission, live token resolution, DELETE completion and local sign-out
all verify that binding. A stale dialog callback or late response cannot act on
a replacement account, including a new same-user generation. Both application
entry points use the bound controller. Orphan pending credentials cannot authorize
confirmation. Version2 receipts contain encrypted authority metadata, preserve
uncertainty without resubmission and wait for verified user restoration in a new
process. Legacy unbound receipts cannot trigger sign-out. Explicit logout removes
the receipt while preserving pairing records.

Credential capture joins bootstrap outside token work and resolves a coherent
live pair under one 60-second budget; HTTP retains its separate 60-second deadline.
The controller permits both within 125 seconds. Pre-request auth failures follow
the original generic/timeout/unauthorized presentation; DELETE transport failures
keep their separate classification. No automatic DELETE retry was added.

Full JVM: 3,334 total, 3,330 passed, four existing SSH skips, zero failures in 449 suites.
Owned API 36: 116/116 in 182.774s; crash log empty, no preflight ANR, settings/window
restored, emulator exit0. Thirteen new JVM cases and nine encrypted/local-HTTP/
Compose cases cover bindings, stale callbacks/results, resolved headers, definitive
refresh rejection, bootstrap joining, prior-process verification, legacy receipt
handling and pending tokens. The prior 107 regressions passed. Dialog captures were
inspected for presence/dismissal; the plain fixture host is not full NativeScreen
layout evidence. Fourteen local files and twelve whole original files are bound.

Full parity remains active. Direct membership eligibility/error handling, team
recovery, raw-token/access-only consumers, full backend/metadata/identity adoption,
actual default deadline/noncooperative IO and OS death/unlock, exact response/UI/
localization, physical Pixel/Mac/provider and signed-release acceptance remain open.
No live account deletion, phone/Mac install, signing/source-pin or signed722 change.

Details: [account deletion ownership and acceptance limits](ACCOUNT_DELETION.md).
Evidence: `captures/recovery-20261023/deletion-identity-checkpoint.json`.

## Authenticated snapshots and Cloud/web capture advanced — 2026-10-24

Cloud and mobile web handoff now capture published account identity, session
generation and a coherent access/refresh pair. A complete saved pair returns
without waiting for launch/foreground validation or refreshing over the network.
Shared sign-in/signout capture guards reject writer windows across account
objects. Fallback capture brackets refresh rotation; current checks retire on
identity/epoch replacement while ordinary same-session token rotation stays valid.
Refresh-only capture has a separate contract. Legacy missing-login receipts now
bind to the restored owner; orphan pending credentials remain retryable.

Full JVM: 3,321 total, 3,317 passed, four existing SSH skips, zero failures in448
suites. Owned API36:107/107 in189.023s, empty crash log, no preflight ANR, restored
settings/window and emulator exit0. Eight new encrypted/local-HTTP cases accompany
prior99 regressions. A stale saved pair returned in10ms while launch refresh was
blocked, with zero additional requests. Seventeen new JVM cases cover the policy.
Nine implementation/test files and nine whole original files bind the evidence.
The existing original auth reference passed198 tests, including snapshot suites.

This is snapshot foundation and Cloud/mobile-web adoption. Account deletion still
needs identity pinned at confirmation and durable result handling with coherent
resolved tokens; legacy raw-token/access-only consumers, complete backend/team
recovery/eligibility and signout identity-stream timing remain open. Refresh-only
capture has no new Android browser consumer. Physical Pixel/Mac/provider, actual
OS/device, signed release and broader source/UI/accessibility gates remain open.
Phone/Mac installs, account/pairing/signing data, source pins and frozen signed722
are unchanged. Full parity remains active.

Details: [authenticated account snapshots](AUTHENTICATED_SNAPSHOTS.md).
Evidence: `captures/recovery-20261023/authenticated-snapshot-checkpoint.json`.

# Native sign-in

## Published account identity now fences team authority — 2026-10-24

A published user/session generation now gates team admission and already-admitted
connection proofs. Account changes invalidate old authority before membership
finishes; returning to the same user id cannot revive old proofs. Ordinary
same-user revalidation keeps existing connections. Request/response guards and
cache projection reject mismatched principals. The process owner observes identity
changes, while direct authority checks do not wait for stream delivery.

Cold notification/reply owners now join initial user validation before membership,
including when a display cache exists. Failed validation preserves presentation
without granting fresh background authority; retry can verify the user and load
membership without a duplicate user fetch.

Full JVM: 3,304 total, 3,300 passed, four existing SSH skips, zero failures.
Owned API 36: 99/99 in 177.661s, empty crash log, no preflight ANR, restored
settings/window and emulator exit 0. Seven new identity cases plus two encrypted
notification/reply worker regressions accompany the previous 90 cases. Existing
original auth reference passed 198 tests; ten whole source/test files match the pin.

Raw-token consumers, authenticated snapshots, full backend/user eligibility and
team-recovery policy, actual OS/device/provider and broader source/UI/accessibility
acceptance remain open. Phone/Mac installs, signing data, global source pins and
frozen signed722 are unchanged. Full parity remains active.

Details: [published identity and team authority](TEAM_IDENTITY_AUTHORITY.md).
Evidence: `captures/recovery-20261023/team-identity-checkpoint.json`.

## Shared refresh ownership and rate-limit cooldown advanced — 2026-10-24

Refresh callers now share one exchange/result per encrypted document/environment.
One cancelled waiter leaves peers running; the last retires the attempt. Saved
owner changes retire old waiters immediately. Actual work stays tracked through
encryption, while a final-commit fence rejects retired or late writes. The default
shared deadline is 30 seconds. OAuth 429s use the original Retry-After/backoff rules
and expose only safe in-memory metadata; oversized HTML cannot bypass cooldown.

Full JVM: 3,290 total, 3,286 passed, four existing SSH skips, zero failures.
Owned API 36: 90/90 in 189.034s, empty crash log, no preflight ANR, restored
settings/window and emulator exit 0. Actual default deadline: 30032ms,
one HTTP request drained, ciphertext preserved. Original reference: 198 auth/push
and 33 selected SDK tests passed; two backend-dependent original signup methods
remain separate open acceptance. Eleven whole original files match the pin.

The first Android run had four harness failures; actual waiter/producer lifetime
and IME-settlement checks were corrected. Final runtime uses the identical app APK.
This does not close complete coordinator/backend admission, app telemetry/consent,
actual OS sleep/unlock/process death, physical Pixel/Mac/provider or broader
source/UI/accessibility gates. Full parity remains active.

Details: [shared ownership, cooldown and acceptance limits](SHARED_TOKEN_REFRESH.md).
Evidence: `captures/recovery-20261023/shared-refresh-checkpoint.json`.
Phone/Mac installs, global source pins and frozen signed722 remain unchanged.

## Access-token expiry and refresh decisions advanced — 2026-10-24

The account reader now declines expired access-only tokens and refuses to reuse
an access token after a forced-refresh failure. Proactive refresh may keep the
observed token while it is still valid. Original freshness lead/lifetime rules,
strict unreadable-storage handling and account/epoch checks protect those reads.
Refresh uses the injected cancellable account HTTP client, with raw OAuth status
classification and conditional mint/rejection writes.

Full JVM: 3,276 total, 3,272 passed, four existing SSH skips, zero failures.
Owned API 36: 82/82 in 147.232s, empty crash log, no preflight ANR, restored
settings/window and emulator exit 0. Sixteen rows from executing whole original
SDK expiry declarations match Android for expiry and both refresh leads. These
checks do not establish shared refresh coalescing/backoff, complete coordinator/
backend admission, actual OS unlock/process death or physical Pixel/Mac/provider
acceptance. Every broader source/UI/accessibility parity gate remains open.

Details: [token-reader behavior and remaining requirements](ACCESS_TOKEN_RESOLUTION.md).
Evidence: `captures/recovery-20261023/access-token-checkpoint.json`.
Phone/Mac installs, global source pins and frozen signed722 remain unchanged.

## Durable Firebase callback recovery advanced — 2026-10-24

Token callbacks now persist an opaque wakeup before entering encrypted storage.
Replay preserves ciphertext on failure and rejects a stale SDK result if a newer
callback arrives during encryption. A separate read generation keeps consent,
revision and helper proof stable for duplicate tokens; confirmed rotation renews
through the existing helper protocol. Unknown storage at the helper HTTP guard
now retries rather than removing the pending enrollment checkpoint. No-consent
callbacks do not create a token lifecycle, encrypted record or Keystore key.

Full JVM: 3,273 total, 3,269 passed, four existing SSH skips, zero failures.
Owned API 36 runtime: 75/75 in 153.922s, empty crash log, no preflight ANR,
settings/window restored and emulator exit 0. Original pinned auth/push reference:
198 tests in 19 suites passed. SDK results in Android callback cases are injected;
these checks do not establish actual Firebase callback/rotation, live helper HTTPS,
OS process death/unlock or physical Pixel/Mac acceptance. Full SDK/metadata,
source/UI/accessibility and all broader parity gates remain open.

Details: [callback recovery and verification limits](FCM_CALLBACK_RECOVERY.md).
Evidence: `captures/recovery-20261023/fcm-callback-checkpoint.json`.
No phone/Mac install, global source pin or frozen signed722 changed. Goal remains active.

## Captured-session bounded signout teardown advanced — 2026-10-24

The production process-owned Sign out path now captures its old token pair,
keeps local logout effective first, joins tracked teams/post-sign-in effects,
then attempts helper cleanup and Stack session revocation under a separate
five-second network budget. Refresh-only/stale access resolves ephemerally;
DELETE authenticates only with captured credentials, never a later live account,
and retries exact rejected access once. The shared capture gate and actual-job
registry make fresh exchanges join the old bounded attempt. Injected connector
fixtures keep their local-only lifecycle and do not send synthetic credentials
to the production account service.

Final full JVM:3266 total,3262 passed,4 existing SSH skips,0 failures in443 suites.
Owned API36 cohort:67/67 in151.144s; crash empty, no preflight ANR, settings/window
restored, emulator exit0. The real default5000ms deadline completed in5017ms with
HTTP calls drained; fresh login joined the old attempt for4999ms before exchange.
Two focused disconnect tests initially failed because localhost retries selected
an unbound IPv6 mock route; explicit IPv4 loopback fixed the fixture without
changing the product deadline. Four whole original source files match the pin.

This does not close real helper HTTPS/Firebase, untracked post-sign-in effects,
full SDK/backoff/metadata, hanging local crypto/UI cleanup, actual OS unlock/
process death or physical Pixel/Mac/provider acceptance. All broader source/UI/
accessibility gates remain open. No phone/Mac install or frozen signed722 changed;
global source pins and the full parity goal remain active.

Details: [captured-session teardown and remaining gates](SIGN_OUT_TEARDOWN.md).
Evidence: `captures/recovery-20261023/signout-teardown-checkpoint.json`.

## Durable local logout and shared sign-in fences advanced — 2026-10-24

Explicit Sign out now records a non-secret durable intent before encrypted IO,
clears the auth model without its encrypted-cache callback, and preserves the
ciphertext if storage is unavailable. Reads/writers replay retirement before
exposing old credentials; new ciphertext and intent removal commit together.
Saved Mac records and the existing key alias remain. Failed replay can retry;
a fresh verified login cannot be erased by an old retry. Conditional deletion
callbacks still require a readable matching owner. Shared account epochs now
fence parked exchanges/user results across account objects and reentrant writes.
Optional UI cleanup failure does not reverse the successful local logout verdict.

Final full JVM:3256 total,3252 passed,4 existing SSH skips,0 failures in441 suites.
Owned API36 cohort:62/62 in141.508s; crash empty, no preflight ANR, settings/window
restored, emulator exit0. New cases cover unavailable/reconstructed accounts,
failed encryption replay, malformed/empty intent metadata, actual fresh OAuth
completion, reentrant snapshot retirement, primary helper cleanup-key retention,
conditional deletion and root Sign out UI. One setup attempt was intentionally
stopped to correct the test browser-host claim; its app APK was unchanged.
These fixtures do not prove actual protected-data unlock, process death or a
real provider/server revoke. Source5-second server teardown, captured credentials,
push hook ordering/joining, hanging crypto/UI cleanup bounds and unavailable-store
FCM callbacks remain open. Full parity remains active; physical installs, global
source pins and frozen signed722 are unchanged.

Details: [local logout semantics and remaining source/device gates](LOCAL_LOGOUT.md).
Evidence: `captures/recovery-20261023/local-logout-checkpoint.json`.

## Cached session startup and foreground restoration advanced — 2026-10-24

Normal cached-user/token-hint priming, coalesced launch/foreground validation,
8-second probe/user deadlines, live-refresh clear/preserve policy and stale
writer fences now drive the root signed-in state. Orphaned pending exchanges
resume through the real bootstrap path. Verified identity publishes before
membership; a saved display cache never supplies team or connection authority.
The production methods screen now shows the original10-second warning/Retry
and pauses sign-in methods while keeping email editable. Five original labels
match all45 catalog values across nine locales.

Final full JVM:3251 total,3247 pass,4 existing SSH skips,0 failures. Owned API36
cohort:50/50 in133.967s; crash empty, no preflight ANR, settings/window restored,
emulator exit0. Two earlier runs each failed only the keyboard UI harness;
resize/insets/frame handling were corrected, with the production app APK unchanged.
Original Xcode auth reference run40060677846 passed146 tests in18 suites on
untouched pinned libraries. Four Android normal priming rows match execution
of whole original Swift declarations;64 launch combinations remain reference-only.
Neither source CI nor emulator fixtures establish whole-app parity.

Local-first signout/unlock recovery, unavailable-storage FCM callbacks, complete
SDK/user metadata, backend/coordinator admission, exact team recovery and real
post-sign-in effects remain open. So do native paint/window/TalkBack, physical
Pixel/Mac/provider acceptance and every broader parity gate. Global source pins,
phone712 and frozen signed722 are unchanged. Goal remains active.

Details: [saved-session behavior and remaining gates](AUTH_SESSION_RESTORE.md).
Evidence/bindings: `captures/recovery-20261023/session-restore-checkpoint.json`.

## Encrypted read preservation and complete source references advanced — 2026-10-24

NativeCredentialStore no longer deletes its encrypted document after a read,
Keystore, GCM, JSON or preference-type failure. Reads never generate replacement
keys. Read availability is separate from credential revisions; unavailable is
not an absent document. Strict mutation snapshots stop updates and conditional
account clears before they can replace unreadable account/pairing/push data.
Failures expose a sanitized message with no parser/crypto plaintext cause.
The existing key alias, cipher format and stored account schema are unchanged.

Helper maintenance and enrollment now require readable snapshots. FCM grant,
owner and cleanup reconciliation also stop on unknown storage instead of treating
it as logout or opt-out. Background recovery contains those failures. Six native
crypto cases use isolated real Keystore keys and encrypted documents; three new
maintenance cases protect unknown account, ledger and FCM-grant boundaries.

Final full JVM:3229 total,3225 pass,4 skipped SSH cases,0 failures. The first run
had a feed timing failure; that case passed alone and the final full run passed
without changing feed code/tests. Final owned API36 cohort:41/41 in105.258s,
including existing auth/methods/code/error/recovery/recreation, encrypted profile,
push identity and helper-maintenance cases. Crash empty, no preflight ANR,
settings/window restored, emulator exit0. Hashes/bindings:
recovery-20261023/native-credential-read-checkpoint.json.

Original iOS reference run40056063765 succeeded: all10 scenes pass actual
source/traits/control/PNG association, including tablet landscape. Actual fixed/
current screen coordinates, native pixels, window conversion, UIImage orientation
and CGImage size resolve the earlier mismatch without rotating/synthesizing PNGs.
Local re-verification is idempotent and still rejects genuine duplicate scenes.
This is an offline original-UI reference, not full app or Android parity proof.

The full cached-user/has-tokens startup/foreground coordinator, storage-unavailable
UI/unlock retry, local-first sign-out while storage is unreadable, callbacks that
arrive while FCM storage is unavailable, exact membership/loading/publication and
post-sign-in boundaries remain open. So do native paint/window/accessibility,
real provider/browser, physical Pixel/Mac and every broader parity gate.
No phone, Mac, live account, signed delivery or global source pin changed.
Goal remains active; last known Pixel712 and frozen signed722 are unchanged.

Accepted runtime: native-credential-read-runtime-v1/; final JVM report:
credential-read-jvm-v2/. Earlier full/isolated reports are diagnostics.
App APK SHA256: b99bd4f3f9160abb498c36095a92a09ff8b9aaf76091f565fc0cb2f5cd33ce05.
Test APK SHA256: 00ce4a24a82f2003c2464adefd077d3ece2b2b7ff20aac2fae4a9ad9b38c871e.

Read-only legacy callers still receive no snapshot when a document cannot be
read; readability distinguishes this from empty storage. The complete auth/root
integration must use that distinction to preserve cached presentation and avoid
a false signed-out state. It is not accepted as complete here. All real device
first-unlock/Keystore availability, foreground restoration and notification
recovery still need acceptance. Missing/corrupt data is preserved, not magically
recoverable; safe user-facing recovery remains open. The removed key in the
native test belonged only to a unique fixture alias; the app key was untouched.

storage-availability-reference.json binds nine whole source policy/test files,
all unchanged at detected79. The source has four normal priming combinations of
cached-user presence and a separate has-tokens hint; current NativeAccount still
uses persisted refresh presence outside pending sign-ins. Its full coordinator
has not been replaced. The original publishes a verified user before fallible
team refresh and preserves cached state on transient live-validation failures;
those publication/validation boundaries must be ported, not replaced by an
Android-only authority policy. Global source pins are unchanged.

## Original methods card and editable busy email advanced — 2026-10-24

The methods screen now follows the original bottom card, brand, provider order,
artwork/label spacing, divider and50-point email pill. Existing Android setup,
Mac help, helper and license routes remain in a separately attributed options
menu. Six original labels and five Android-only labels cover nine locales.
Google/GitHub SVG paths and colors are preserved; Apple uses the original web
mark rather than native apple.logo. UIKit26 blue is taken from real source traits.
Native glass/shadow/font paint is not accepted as equivalent.

The original methods email remains editable while send/provider work is busy.
Draft edits preserve the admitted request and pending challenge. A synchronous,
memory-only TextFieldValue bridge prevents an opening IME from echoing an old
empty value into the retained form. Background taps dismiss the keyboard.

Final full JVM suite:3229 total,3225 passed,4 skipped SSH cases,0 failures.
Localization/resources and27 Python guards pass. Final owned API36 cohort:
25/25 in107.900s, including36 methods profiles, editable busy send/providers,
setup/helper/license actions, pending draft edits across actual recreation and
all previous19 auth/challenge/error/recovery/recreation cases. Crash empty,
no preflight ANR, settings/window restored, emulator exit0. Evidence and hashes:
recovery-20261023/native-signin-methods-checkpoint.json; details in SIGN_IN.md.

Nine original phone scenes were individually bound to actual UIKit traits,
control frames and compositor PNGs from clean source a39c6266, run40053276605.
The full reference workflow remains failed: the passing tablet test exceeded its
six-minute teardown budget, and its raw portrait pixel axes do not match the
landscape UIKit viewport. The full association guard rejects that tablet capture.
The future tablet step budget is ten minutes; orientation metadata still needs
repair. This is scoped reference evidence, not full source-app acceptance.

Restore/cache/loading UI and membership boundaries, post-sign-in effects,
queued/source callback details, real browser/provider flows, exact UI/window/
keyboard/accessibility variants and every broader parity gate remain open.
No phone, live account, Mac, signed delivery or global source pin was changed.
Goal remains active; Pixel712 and frozen signed722 are unchanged.

Current debug app SHA256: fa49fd0b6836b02c0873c3d767d3d0bc1ae03cff955a11f812c6fc7d4edd121f.
Test APK SHA256: d53bfe0e2027a6be0d422d9d95e3cbaaad7ad01174ec7a938b5c68b226603a08.
Accepted runtime: native-signin-methods-runtime-v4/; full JVM:
signin-methods-jvm-v9/. Earlier runs are diagnostic failures, not acceptance.
The provider geometry check uses shaped line extents, height and ellipsis rather
than the cached intrinsic paragraph container's width. Current light and Arabic
2x screenshots were inspected. Light paint still lacks native glass/shadows;
large-font profiles are controlled composition tests, not OS/TalkBack proof.

Original reference: methods-reference.json binds19 whole originals and two real
support/core package trees; all19 selected files are identical at detected79.
The host executes unchanged UI declarations and helper leaves with an authored
offline service/state adapter. Nine phone images individually pass scene/source/
trait/label/PNG association checks; phone-verification.json records this subset.
The full10-scene guard fails on the tablet. The adapter is not the original
coordinator, restored account, backend or browser; source status UI alone does
not supply Android restore behavior. None of the global adoption pins moved.

## Tracked phase waits and shared token-work preflight — 2026-10-24

NativeAuthWork owns actual operation jobs independently of UI waiters. Deadline
or caller cancellation can finish the wait while cancellation-ignoring work stays
registered to true termination. Token operations reject an absolutely overdue
result even if timer scheduling is delayed; prompt/exchange operations follow the
original race winner. Exclusive slots have the original30-second automatic reset.
Timed-out token work remains automatically damped while active; an explicit user
reset may release both dampers, preserving actual handles. Manual pairing proposal
now invokes that reset. Sign-in preflight still cancels/joins its token-work
snapshot, excluding prompt-only send-code work. Fifteen whole originals in
auth-work-reference.json are identical at detected79; global pins are unchanged.

The registry is keyed by the encrypted credential document, so independently
constructed foreground/background NativeAccount and NativeCredentialStore objects
share ownership. Normal and forced access phases are distinct; credential reads,
refreshes and conditional writes/clears run off the caller's UI dispatcher. Worker
cancellation is checked at both successful-refresh and invalid-refresh store
transactions. Unconditional sign-out cancels registered work before local clear;
conditional retirement still compares the captured login before retiring others.

Completion now uses a separate flow monitor, so deadline/error bookkeeping cannot
wait behind a blocked credential writer. Challenge/browser locks cover their
state rather than storage IO. Conditional cleanup runs as independently owned,
tracked work: a deadline can return before storage finishes, and a retry must
wait for that true completion before its next exchange. Late exchange/user
results cannot publish a session. Failed user verification retains its pending
credentials for the existing refresh-aware restore path. These checks do not
establish the complete root UI's response during blocked storage: it has other
synchronous storage reads. The original membership/cache publication and loading
boundaries remain an explicit source gate; no new deferral policy is accepted.

Final build passes in71s. Full app JVM suite:3229 total,3225 pass,4 skipped SSH
integration cases and0 failures, including120 focused authentication cases. New
model gates cover cancellation-ignoring exchange/user/membership work, deadline
return during a parked credential write, retry preflight, late-user rejection,
actual cleanup joins, source-specific reset/expiry policies and nonblocking
challenge retirement. Only the final full report in auth-work-jvm-v9/ is accepted.

Final owned API36 cohort passes19/19 in64.419s. Two new cases use the production
account/access APIs and separate store/account objects for one real encrypted
fixture document. A background ordinary or forced refresh is parked; a new
provider request cancels it but sends no HTTP until its actual termination.
Cancelled success cannot store stale access, and cancelled definitive rejection
cannot clear the session. Both then complete one controlled provider exchange,
verify the user and preserve pairing records. Existing17 cases and36 code-card/
18 error profiles pass. Browser results and HTTP remain controlled; there is no
real account/provider/browser login or phone/Mac use. Crash empty, no preflight
ANR, settings/window restored, owned emulator exit0. Earlier passing cohorts are
superseded. Development failures/assertion corrections remain retained in logs.

Exact bindings: recovery-20261023/native-auth-work-checkpoint.json,
auth-work-jvm-v9/, native-auth-work-runtime-v4/ and auth-work-source-audit/.
No new signed delivery; frozen722/last known physical712/personal account,
pairing/signing data and global pins unchanged. Full queued/cross-owner publication,
existing-session validation/restoring UI, token-result classification, membership/
cache boundaries, remaining explicit-entry resets, post-sign-in push hooks,
access-only responses, original metadata/UI and all real browser/Pixel/Mac/
TalkBack/full-app gates remain open. The goal stays active.


## Verified-user completion and conditional pending-session cleanup — 2026-10-24

NativeAccount now routes email/OAuth token writers through NativeSignInCompletion.
The receipt is the actual encrypted store writer's task_session plus a persisted
sign_in_pending marker. Until users/me succeeds, isSignedIn(), accessToken() and
taskSession() expose no authenticated session, including through a fresh account
owner. The source getUser(.throw) rejects anonymous/restricted users; the Android
decoder now does too. Actual Foundation JSONSerialization/as?Bool output verifies
true/false, numeric0/1 (including decimal forms), other numbers, strings and null.
Thirteen whole coordinator/SDK/model/test originals remain identical at detected79;
completion-reference.json and the scoped comparison bind the reference.

Exchange cancellation after a real write or across a dispatcher return clears
only that pending receipt. A newer writer is protected before its UI publishes;
a newer attempt that never wrote cannot steal cleanup ownership. User-validation
failure/deadline retains pending credentials for later restore. A fresh owner can
verify them or refresh rejected access using the captured refresh token; a late
refresh cannot overwrite a replacement writer. Transport/server/malformed-refresh
failures preserve the pending session; definitive credential rejection conditionally
clears it. Existing already-published legacy sessions are not reclassified by the
new marker.

Verified identity/cache publication is atomic with removing the pending marker.
The retained owner's membership hook uses the fetched user and verifies teams;
team failure preserves the user while granting no scope. Completed-form cleanup
waits for the busy owner and does not cancel its published membership phase;
sign-out does cancel it. Periodic refresh avoids overlapping the active sign-in.
Exchange, user and membership phases have separate guards (network60s,
interactive300s); orphan refresh probe8s. The real HTTP transports cooperate with
cancellation. Exact independent deadlines against cancellation-ignoring work,
ordinary token-refresh/session-validation preflight registration, and complete
post-sign-in side effects remain open; this is not full coordinator acceptance.

Final build succeeds in71s. Full app JVM suite:3216 total,3212 passed,4 skipped
SSH integration cases and0 failures;107 focused auth cases included. Final owned
API36 run passes17/17 in65.482s. Eight controlled OAuth cases cover the production
client/account/encrypted fixture store/retained form/ActivityResult and actual
exported callback Activity, including stored-token/user-request gates, production account
sign-out API against a fixture store, fresh-owner restore, expired-access refresh
without a second credential exchange, and both restricted flags. The other nine
cases preserve code/recovery/lifecycle behavior and36 code-card plus18 error
profiles. Fixture pairing records survive. One final pending-provider frame
reviewed, crash empty, no preflight ANR, settings/window restored, emulator exit0.
Browser results and HTTP are controlled: no real account/provider/browser session
or physical phone/Mac action. Earlier15-case run passed but its host expected13;
the count guard was fixed. Earlier16-case run is superseded. A new JUnit method's
inferred non-Unit return was fixed before the final full unit run. Diagnostic logs
remain retained.

Bindings: recovery-20261023/native-signin-completion-checkpoint.json,
signin-completion-full-jvm-v2/, native-signin-completion-runtime-v3/ and
signin-completion-source-audit/. Debug app SHA256:
a021470e9450482cb0ead87d260424cc964781321483f219fa421c370336e78a.
Test SHA256:b8178c91c7f5fb3c1d3fc576e7b70c058f7e659f35e6f5e335cf4d11e71e0319.
No new signed delivery; frozen722/last known physical712/global pins/personal
account/pairing/signing data unchanged. Access-only token responses, complete
cached/restoring auth state and original metadata, exact queue/validation/late-work
semantics, real browser/provider behavior and all methods UI/Pixel/Mac/TalkBack/
full-app gates remain open. The full goal remains active.


## Provider authentication wired — 2026-10-24

Apple, Google and GitHub buttons now invoke the retained authentication owner.
The client follows the original Stack JSON authorization-location preflight,
legacy scope,32-character random state,64-character verifier, S256 challenge and
form-encoded code exchange. Authorization/token requests have30-second network
bounds; token success requires HTTP200. The owner has the original300-second
interactive bound and existing60-second preflight/quiescence bound. Cancellation
is checked before the credential transaction; sign-out conditionally retires the
OAuth generation under the same writer lock. Storage writes run off the UI thread.

AndroidX Browser1.10.0 supplies Auth Tab with ephemeral browsing requested and
Custom-Tab fallback routing. Callback paths include the application ID to separate
debug/release installations, and cmux_flow binds errors even when the backend
omits OAuth state. Success also requires the matching state; wrong origins,
paths, duplicate fields, mixed outcomes and stale callbacks cannot start an
exchange. The exported redirect Activity consumes only an already-admitted,
memory-only flow and clears its Intent. Per-presentation registry keys survive
recreation and temporary host removal; an old browser result cannot target the
new request. The existing NativeAccount constructor remains source-compatible.

Apple's original native ASAuthorizationController is unavailable on Android;
the existing project also configures Apple's web provider. Fresh public API
preflights returned200 and provider locations at accounts.google.com, github.com
and appleid.apple.com, accepting the debug callback path/nonce. No actual provider
login, code exchange or personal account was used for that preflight. Seven whole
original provider/coordinator/client/SDK/composition sources are identical at
detected79d982f9; hashes, AndroidX artifact and official API references are in
third_party/cmux-signin/oauth-reference.json. Five original UI keys contribute45
exact values across nine locales, including the fixed email-button label so an
OAuth request no longer incorrectly says “Sending code…”.

All53 focused JVM tests and22 localization checks pass. Final owned API36 cohort
passes13 cases in57.345s. Four new cases exercise the production client/account/
encrypted fixture store/retained owner/Compose/Android ActivityResult contract:
two actual Activity recreations keep one browser launch and two HTTP requests;
the actual exported fallback Activity completes once; a776-byte saved state omits
the actual verifier; an old native cancellation/fallback cannot retire a timeout
retry; sign-out rejects late callbacks; and a detached host consumes its pending
result after reattachment without relaunching. Fixture pairing rows remain intact.
Browser results and HTTP are controlled, so these are not real provider sessions.

Existing code/paste/replacement/timeout/recovery/recreation and36 code-card plus18
error locale profiles pass. Earlier runs each failed a different email-request
wait. Native IME/touch timing was inferred; the final harness waits for native
keyboard geometry to settle before pointer taps without weakening request-count
assertions. Diagnostic attempts remain retained. A passing13-case v3 is superseded
by v4's original email-button copy and fixture Surface/inset placement repair.
Three final frames reviewed, crash empty, no preflight ANR, settings/window
restored, owned emulator exit0. Exact bindings:
recovery-20261023/native-oauth-checkpoint.json and native-oauth-runtime-v4/.
App SHA256:646f1ef771ee3530bf425f015db2be3d20adbac5b12a36d81b0bfbf1c4333aab.
Test SHA256:d953d3e4b7cb84936192dc9526ccdf8992829e8ac0526befcbb419c88b9e411a.

Full acceptance remains open. The current provider operation completes after a
token pair is stored; it does not yet run the original fetched-user/team-publication
and conditional post-write rollback phases. Deadline/sign-out during and after
storage writes, user fetch and newer-flow interleavings need that full coordinator
contract. Android currently requires both access and refresh tokens; the original
SDK callback permits optional refresh. Real browser/provider sign-in, cancellation,
timeout dismissal, cookie isolation and unsupported-browser fallback are unproven.
The methods page still uses the earlier scaffold and lacks source provider artwork;
restore status, typography/glass/transitions/motion, OS/window variants, real
Pixel/Mac/autofill/TalkBack and every full-app gate remain open. No new signed
delivery or physical action;722/physical712/global pins/account/pairing/signing
unchanged. Full goal remains active.

## Separate challenge screens and source code card — 2026-10-24

Code and email-verification states now replace the methods form instead of stacking
below its email/request/help controls. The code card uses the original brand mark,
check-email heading, sent-to address, placeholder and Verify code label. Five
original main-catalog keys supply45 exact values across nine languages; only the
single email format argument changes from `%@` to `%1$s`. The unmodified SVG,
Android vector conversion and seven whole original source hashes are attributed
in third_party/cmux-signin/ and native-signin-card-source-comparison.json. Those
whole originals are identical at detected79d982f9; global pins remain unchanged.

The code card ports the430-point maximum width,20/24/20 horizontal/top/bottom
padding,18-point stack spacing,28-point mark and60-point centered code pill with
fixed32-point semibold monospaced input. It sits at the bottom of an independently
scrollable viewport. Recovery shares the brand/card/backdrop and busy dimming;
its existing recovery controls remain. The existing Cloud colony/Canvas provides
the matching source grid/seed/tick/paint rules. Exact iOS glass/font rendering and
sign-in motion under accessibility settings are not accepted: the shared Android
backdrop also has lifecycle/reduced-motion handling. This is an open fidelity
question, not an asserted unavoidable platform difference.

The original send action captures whether the email field is focused. Android
now requests code focus only for that path; an unfocused send leaves code entry
unfocused. Verification recovery continues to skip autofocus. Focused code Back
still restores email focus, deferring it while an admitted exchange is busy.

All37 focused JVM tests and21 localization checks pass. Final owned API36 cohort
passes nine cases in46.792s. Thirty-six controlled language/scale/appearance
profiles retain one send/one failed exchange, complete localized errors, a fixed
60dp input and reachable code/Verify/Back controls. Every challenge viewport ends
at the actual keyboard edge (1614px in this provider); large-text screenshots
scroll upper content out of view rather than claiming simultaneous visibility.
Actual focused/unfocused paths pass. Existing clipboard/replacement/late-callback/
timeout,18 error-locale profiles and code/recovery Activity-retention cases pass.
Seven final frames reviewed. Crash empty, preflight no ANR, settings/window
restored, owned emulator exit0. No real account/email/backend request was made.

Evidence: recovery-20261023/native-signin-card-checkpoint.json and-runtime-v3/.
App SHA256:13fc897704bd99e96526efc71565f31343690f4d198129eb4768ef6b452488ae.
Test SHA256:f847edb85b6d631808a426d3ec3aa5909dc15c6c6b29d6f999acaf2d2bb7c2c1.
Runtime-v1 retains two harness failures (synthetic locale context hid the Activity
owner, and a pre-backdrop Surface-color expectation). Runtime-v2 passed nine
functional cases but its generic host showed a keyboard/viewport gap. V3 matches
MainActivity's adjustResize, waits for native IME idle and asserts measured
keyboard-edge geometry across all36 profiles. Production APK is unchanged across
these attempts; earlier profile frames do not establish accepted placement.

Next: original methods/providers and entry scaffold, restore status, exact
typography/glass/transitions/motion, landscape/tablet/older OS and real OS settings.
Complete coordinator/backoff/connectivity/user/team/token rollback, real account/
Pixel/Mac/autofill/TalkBack and all full-app gates remain open. No new signed
delivery or physical-device action;722/physical712/account/pairing/signing remain
unchanged. The full goal remains active.

## Automatic code entry and exchange retirement — 2026-10-24

Code entry now keeps ASCII letters/digits, uppercases and caps at six characters.
Completing normalized input automatically verifies, including platform clipboard
paste. The field stays editable during verification; manual submit stays disabled.
Ordinary code entry requests focus/keyboard once. The verification-recovery
continuation does not autofocus. Returning from focused code entry retains its
admitted exchange and defers email focus until the email form becomes idle.
The code field declares the OTP autofill content type and ASCII/character-capital
keyboard options with autocorrection disabled.

Replacement verification cancels old exchanges and waits for their terminal
completion before starting another. Retired UI waiters cannot update the form;
cancelled/late callbacks cannot publish credentials. Independent60-second bounds
cover exchange and preflight waits. Timed-out uncooperative work remains tracked
until terminal so a later attempt cannot overlap it. The latest Android queued
edit wins; exact scheduling of multiple queued original coordinator flows and
the full source user/team/token rollback contract remain unaccepted.

Eleven whole original files at a5911a1f (identical at detected79d982f9) bind the
input, view/caller, text options, coordinator, preflight and timeout review in
recovery-20261023/native-signin-code-source-audit/review.json. The fixture generator
compiles/executes the two unchanged original Swift input declarations:514 cases
match Android normalization/completion, and a separate original reexecution passes.
This oracle proves pure input policy, not SwiftUI event ordering or full auth.

All36 focused JVM tests pass, including actual challenge/publication fencing,
multiple queued replacements, timeout/preflight recovery and focus consumption.
Seven owned API36 instrumentation cases pass in26.26s. Actual clipboard paste
submits ABC123; replacement GHI789 waits for the retired callback, then publishes
once. Code Back preserves its admitted request and restores email focus after its
failure. An injected500ms deadline releases the form and blocks late credentials;
this does not prove a real backend's60-second wall-clock behavior. Existing code/
recovery Activity retention and18 controlled locale/font error profiles pass.
Observed EditorInfo inputType0x1001/imeOptions0x82000006 confirms character capitals,
no autocorrect flag and force-ASCII. OTP semantics are checked; actual autofill
service delivery remains open. Four new frames reviewed; the timeout frame's
lower code field extends below the keyboard and remains part of the open scaffold
layout review. Crash empty, preflight no ANR, settings/window restored, emulator exit0.

Bindings: recovery-20261023/native-signin-code-checkpoint.json and-runtime-v1/.
App SHA256:0d18bee9b1a219c684c25ce0766c90aa30958d25a6cec08874665f224266a7ba.
Test SHA256:bac02027ec66de55e54108f62dde49be667ac4736ab3644e2e9744bda6f595f0.
Next: original entry/code scaffold, methods, materials, geometry and remaining
copy/localization; complete coordinator/backoff/connectivity/user/team rollback,
OS variants, real account/Pixel/Mac/TalkBack and all full-app gates remain open.
No real email/account request or new signed delivery;722/physical712/global pins
and stored account/pairing/signing unchanged. Goal remains active.

## Original sign-in error roles and copy applied — 2026-10-24

The form now retains a display role rather than arbitrary provider/exception
text. The original SignInErrorPresentation/AuthError display-safe mapping covers
29 specific provider codes (case-normalized), unverified-email distinction,
unauthorized, network, unknown, local-validation and phase-timeout roles.
Cancellation stays silent and preserves an entered code; failed authentication
still clears it. Ordinary and verification-recovery views resolve their role
through resources, with a polite live-region semantic on ordinary errors.
Missing nonce/local invalid code use the original short invalid-code role.
This changes error presentation, not account/pairing storage or recovery requests.

Twenty original main-catalog keys provide180 exact translations across nine
locales. The additional OFFLINE role keeps the exact English AuthError.swift
default: the pinned iOS main catalog lacks that key. Its source-default origin
is explicit, rather than attributing Mac translations to iOS. Role/key/resource,
source hashes, locale completeness and this fallback are guarded;20 localization
checks pass. The data is sign-in-error-strings.json and the common generator
writes/checks cmux_signin_error_strings.xml in all nine folders.

All27 focused JVM tests pass (the previous21 plus six mapping/privacy/domain/
cancellation tests). Final owned API36 cohort passes four cases in16.234s.
One retained error remains complete through18 controlled locale/font profiles,
with one request total, no provider marker and no text overflow. Arabic/German
large-text frames with the actual keyboard visible were reviewed. A known invalid
OTP displays the original localized message in the existing recovery flow; its
four Activity recreations and existing pending-code/authentication checks retain.
Crash empty, preflight no ANR, settings/window restored, emulator exit0.

Exact source/data/APK/semantic/runtime bindings:
recovery-20261023/native-signin-error-checkpoint.json and-runtime-v1/;
original source/value comparison: native-signin-error-source-comparison.json.
App SHA256:d50678f1253de5fcf59914d6c5ca9dc7c34e325bd66c77bd9d320720a7b8585e.
Test SHA256:e44c98f453366ff232919d0f38633c89f70b87c87412ba341e9a702134b1aa83.
These are controlled composition locales/scales and fixture/local-HTTP callbacks;
not actual OS-language/font transitions, real account/backend, Pixel/Mac,
TalkBack or full original sign-in UI/method/phase-orchestration acceptance.
Automatic-backoff orchestration and its separate retrying state remain open.
Real connectivity preflight/deadline behavior is not proven by mapped roles.

The subsequent automatic-code section above supersedes this checkpoint's former
next step. Original entry states/scaffold/method/localization gates remain open.
No new signed build or real-device action;722/physical712/global pins and stored
account/pairing/signing unchanged. Full goal remains active.

## Unverified-email recovery — 2026-10-24

Android now preserves the typed refusal needed by the original native iOS
SignInEmailCodeFailurePolicy. When requesting a code returns
`USER_EMAIL_ALREADY_EXISTS` with a true boolean/numeric
`would_work_if_email_was_verified` detail, the form requests verification and
enters the verification screen. A provider failure leaves its controls reachable.

The screen offers the original resend, “I verified my email,” paid-account
recovery and different-email actions. Continuing requests a code again; another
unverified refusal shows the original pending message without automatically
sending another verification email. Successful code preparation returns to code
entry. Only an actual successful OTP exchange publishes credentials/completes
sign-in. A failed exchange clears its submitted code as in the original view.

Both recovery requests use the existing cmux HTTPS backend, POST only normalized
email, and carry no account token. Verification uses `/api/auth/email-verification`;
paid recovery uses `/api/billing/recover`. Any2xx is accepted without inferring
whether an address exists or has paid. The neutral acceptance message is the
original source copy. Requests have60-second bounds, close responses, cancel
with their coroutine, do not follow redirects and do not automatically retry.
Recovery429 stays typed internally but presents the original coordinator's safe
generic server-error copy. OTP responses preserve current/legacy known-error and
actual-status headers, including known errors transported with HTTP200 and HTML429.

One retained owner serializes the flow and fences retired/cancelled callbacks.
Different-email returns to entry with its address retained and challenge cleared;
closing purges the memory-only form. Neither recovery operation changes stored
account/pairing/signing data. Both retained production wiring and the fallback
composition owner receive the real recovery callbacks/challenge invalidator.

Source audit: eleven whole original view/policy/coordinator/client/config/SDK
files at `a5911a1f92f3eeb11c4d7f3e2ef89204718c5009`, each identical at detected
79d982f9, are bound in recovery-20261023/signin-verification-source-audit/review.json.
The two original catalogs contribute ten keys across nine locales: all90 values
match the original files byte-for-value. The one email argument maps `%@` to
`%1$s`. Data, catalog hashes, exact key/resource/catalog association and generated
resources are checked by mobile-ui-localization.py;16 localization checks pass.

All21 focused JVM tests pass (4 retained form,9 challenge/nonce,8 typed/provider/
recovery/cancellation). They cover actual local HTTP requests, normalized payloads,
privacy-neutral2xx, redirects/429/503, refusal typing, verified retry and late
uncooperative callbacks. Final owned API36 cohort passes three cases in9.581s:
actual recovery input with local HTTP and OTP fixture exchange; existing pending
code/authentication recreation; new verification/billing recovery recreation.
Four actual Activity recreations repeat neither request; its916-byte saved state
omits the fixture email. Existing saved state omits the code. Return-to-email,
failed-code clearing, one publication/completion and privacy-neutral recovery are
verified. Final dark Surface frames reviewed, crash empty, preflight no ANR,
settings/window restored and emulator exit0.

Exact source/APK/test/provider bindings and screenshots:
`captures/recovery-20261023/native-signin-recovery-checkpoint.json` and `-runtime-v2/`.
App SHA256:777508de0ddd8cb57b3fe7899989a4e05241954e40222a7f873645757897991b.
Test SHA256:01ebc96085586925bab4353c76c140655ae56db8da099e05a9a27856bf819d80.
The earlier two-case runtime-v1 passed functional checks on the same production
APK, but its isolated host omitted the themed Surface/insets; its screenshots
are superseded. The final host matches the actual root Surface/content placement
and checks compositor background. No production change was needed for that repair.

## Remaining acceptance

Real backend delivery/verification, real-account restore and the signed Pixel
upgrade remain open. No real email/account request or physical phone/Mac action
was performed in this fixture cohort. Exact original full sign-in scaffold,
materials/motion/provider methods, complete error presentation/localization,
large text/RTL/OS-language/keyboard/older-API variants, process death and TalkBack
remain open alongside all full-app gates. Existing signed candidate722 and
physical712 remain unchanged; this work is source/debug evidence, not a new
signed delivery. Native Google Play purchase/restore is a separate backend gate;
this existing paid-account recovery endpoint does not implement Play billing.
