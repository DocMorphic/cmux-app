# Independent team refresh reads

The production refresh callback now performs the same two client reads as the
pinned coordinator: fresh eligible-user membership and a separate selected-team
lookup run concurrently inside one token-touching network phase. The callback's
already-published user does not bypass the membership client's fresh user read.
A selected lookup failure supplies no new choice and does not fail membership.
That includes a selected-only 429; the primary list's rate limit stays typed.
Actual parent cancellation still cancels the whole operation.

## Selection and profile projection

A successful refresh resolves the independent server choice, or the current
remembered choice when the selected read supplies nil. Nonempty membership falls
back to its first member when the choice is unavailable. Empty membership keeps
the explicit remembered/server choice but grants no connection scope. Successful
removal retires old continuation proofs, and an empty successful list counts as
loaded rather than starting recovery.

The selected-user projection follows the complete original CurrentUser and Team
initializers: a nonobject selected_team is absent, and an object with a missing or
nonstring id produces the SDK's empty string. Unrelated profile field types do
not decide whether this optional lookup supplies a choice. Anonymous/restricted
flags retain the existing original-model eligibility mapping. The Android
published-principal fence remains in place.

The original coordinator does not replace its published account profile with the
SDK user fetched for a team list. Production Android now reads that profile from
the owning NativeSessionRestore model. A callback's supplied published user stays
authoritative as well. Late membership/selection results still must pass the
existing operation, phase, session and mutation fences before atomic publication.

## Saved choice

Selection persistence now uses the existing Foundation-compatible text policy,
including the Unicode whitespace cases executed through the original selection
store. Blank choices restore as nil; a nonblank saved choice is trimmed. The live
projection remains separate from its normalized persisted representation.
Publishing a verified user for the same login, environment and user retains the
remembered choice while clearing the saved member rows. Cached rows and choices
continue to grant no membership proof or connection scope.

At this checkpoint the Android card still combined choice and presentation
storage. [The selection-lifetime follow-up](TEAM_SELECTION_LIFETIME.md) separates
them and covers new receipts, changed principals and interrupted publication.
Actual process-death and locked Keystore acceptance remain open; neither
checkpoint substitutes for those gates.

## Original evidence

The audit candidate is unchanged: a5911a1f92f3eeb11c4d7f3e2ef89204718c5009.
The new generator executes the complete original refreshTeams(generation:),
resolveTeamID, CurrentUser initializer and Team initializer declarations. It also
executes complete User, AuthTimeouts, CMUXAuthTeam, selection store and key-value
protocol sources. The coordinator wrapper supplies a recording phase boundary,
telemetry and controllable client; it does not execute the full original SDK or
the original phase timer. The constructor wrappers execute the unchanged complete
initializers against an inert client. The persistence store uses a memory backing,
without touching the developer's defaults.

Ten refresh rows include independent starts, selected-read error/cancellation,
remembered and stale selection, empty membership, primary failure and stale
session generation. Ten selected constructor rows cover absent, invalid, empty
and string ids, and irrelevant profile types. Nine store rows cover nil, blank,
trimmed and Unicode choices. Independent regeneration is byte-identical. Combined
whole-file evidence binds 38 original files. The prior original auth library CI
run 40072945627 remains separate evidence, not a new original-network run.

## Verification

Full JVM: 3,416 total in 455 suites, 3,412 passed, four existing SSH integration
skips, zero failures/errors. Eleven new test methods cover the protocol and saved
choice; existing identity, continuation, mutation, shared deadline, rate and
transport fixtures now answer the actual independent reads. The token-fault JVM
fixture gives the selected read a separate explicit token/reply seam so its old
primary-token faults stay targeted; a selected-read noncooperative fault is
covered separately. Both debug APKs build.

Owned API 36 runtime: 183/183 in 347.427s, comprising six new account/store/
Keystore/local-HTTP cases, the prior 158 regressions, two cached-computer
cases and 17 further existing notification-ingress/reply-worker cases. The production 60-second progressing-body deadline fired
at 60,014ms with fixture ciphertext and pairing intact and no PATCH. All six new
cases preserve their saved pairing markers. Crash log empty, no preflight ANR,
settings/window read back as restored, owned emulator exit0.

The new runtime cases verify actual callback HTTP counts and authoritative
profile projection, both fresh requests starting before either reply, remembered
choice after successful empty membership and encrypted cold reconstruction,
ineligible fresh membership after user publication, one budget spanning fresh
user/list HTTP, and old replies rejected after a replacement publication. The
replacement publication is controlled; it does not execute a provider sign-in.
The shared-budget case uses a shortened timer; the separate default case executes
the real production 60-second phase.

Offline/verified account component screenshots were inspected: saved rows remain
disabled with a readable error/footer, and verified rows/profile/actions render
without clipping. These are isolated Android component captures, not matched full
iOS UI or TalkBack acceptance. The older cached-computer assertion treated an HTTP
401 alone as definitive credential loss; it now checks cache preservation after
the failed read and removal after actual local sign-out, retaining saved Macs.

Evidence under captures/recovery-20261023: team-reads-build-v1.log (full JVM and
APKs), team-reads-jvm-v1, team-reads-android-build-v3.log, team-reads-runtime-v3,
reference regeneration outputs and team-reads-checkpoint.json. The final receipt
binds 42 local source/context/reference/driver inputs, both installed APKs and 38
whole original files. The first runtime attempt retains five fixture failures;
the second passed 166 cases, and the final expanded third attempt binds final
code and passed all 183. All eight ingress and eleven reply-worker tests ran,
including no SDK auto-initialization, encrypted delivery/dismissal, revocation,
cooldown, account replacement, cancellation and private WorkManager notices.

## Remaining acceptance

[The SDK projection follow-up](SDK_USER_TEAM_PROJECTIONS.md) covers exposed
user/team defaults, raw strings, duplicate/empty rows, common successful malformed
list bodies and the former Android-only successful response/field/row bounds.
[The byte-decoder follow-up](SDK_JSON_BYTES.md) adds the measured grammar, encoding
and numeric-eligibility projections on SDK response paths. iOS-runtime equivalence,
general metadata/numeric serialization and complete SDK transport/error/retry
behavior remain open. Choice-versus-card lifetime is covered in the linked follow-up;
actual OS/storage acceptance and complete auth-project reset remain open.
The endpoint-routed runtime fixture separates GET-user and other reply queues so
parallel calls cannot consume a teams response; unscripted user requests fail the
fixture on shutdown. It is local evidence, not a live provider, backend or phone.

Worker retry/rate consumers, consent-aware reporting, complete token/backend and
identity adoption, actual OS sleep/network loss/locked storage/process death,
physical Pixel/Mac/provider/helper/Firebase, matched full UI/accessibility and final
signed release acceptance remain open. The Android create dialog remains an
extension to the pinned iOS picker. No physical install, production account,
pairing/signing material, global source pin or frozen signed 722 changed here.

## Shared SDK transport checkpoint — 2026-10-24

[SDK transport and pending-session refresh](SDK_TRANSPORT.md) now supplies the
request/header/error/retry layer for SDK user/team paths, email and captured
revocation. Body/list projections and existing owner/generation/deadline fences
remain in place; backend creation and direct OAuth/form/refresh protocols retain
their separate contracts. Exact invalid-access-token401 retries keep the nonce
and refresh snapshot, including receipt-owned interactive/orphaned recovery.
Completed429 and interrupted transfers follow different source branches.

Full JVM: 3,463 passed, four skips. Owned API36: 215/215, including 14 new methods
and 201 regressions. Complete getUser/adaptor error/freshness behavior, remaining
transport/reporting/consumer/reset/UI work and real-device acceptance remain open.
See the linked checkpoint for measured source seams, earlier failed attempts,
remaining scope and exact evidence bindings. This does not establish full parity.
