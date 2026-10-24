# Team selection and creation source reconciliation

Team selection now follows the pinned client/coordinator boundary: an eligible
fresh user read precedes PATCH, HTTP success is sufficient even without an
optional JSON response or selection echo, and reselecting the current team still
persists. A nil selection sends an empty object, matching the SDK omitted field,
while projecting the explicit selection as nil locally. The effective selection
and authenticated scope still fall back to the first member when the list is
nonempty. It does not claim that the server cleared an omitted field. Anonymous/restricted or unavailable users cannot reach PATCH;
rate limits remain typed. These failures preserve an already loaded same-session
proof. A fresh principal mismatch still retires old authority and cannot mutate
the replacement account.

Each operation captures its own controller epoch, published identity and mutation
number. Newer selections can finish while an older PATCH is stalled; stale replies
cannot overwrite the newer selection, cache or error state. Unknown choices are
rejected before taking a mutation claim. Creation is excluded while any selection
is active, including a superseded request still in flight. Selection and a second
create are refused throughout the first create, including its final selection.
Concurrent membership reads no longer wait in the former refresh/create serializer;
see [the phase and publication follow-up](TEAM_OPERATION_DEADLINES.md).

## Creation and membership authority

The production client creates through the cmux backend with its authenticated
credential pair. The original backend route does not require an extra client
user preflight; this audit corrected the earlier broad description of mutation
eligibility. No automatic create POST replay was added. After creation, the
client attempts a fresh eligible user/list read, tolerates its failure using the
previous available list, adds the authoritative created team if absent and makes
a fresh-user selection attempt. A failed post-create list no longer forces the
user to repeat creation or complete a second manual selection.

The shared creation API also accepts a primed cached account: backend credentials
are still required and the server authorizes the mutation. A freshly verified
account may create even when its initial membership read failed. The result is visible and selectable, but creation or selection cannot
invent the loaded-membership generation that a normal list refresh establishes.
Connection scope stays nil until that separate refresh succeeds. When membership
was already loaded, an authoritative post-create list updates it before the
selection attempt, so removed-team continuation proofs retire even if the final
PATCH fails. An old selected id may remain projected while the effective scope falls back to
the first remaining member. Its removed-team proof is still retired. Android's saved team card never grants connection authority after cold
reconstruction. The existing Android Create Team dialog remains an extension;
the pinned iOS Settings view exposes the team picker, without a create dialog.

## Picker behavior

Settings now keeps its inline picker enabled during selection, immediately shows
the pending choice, cancels the prior UI task on a newer choice and clears pending
state only for that task's current request. Failure restores the committed/effective
choice and shows the pinned localized retry footer; retry clears the error.
The main screen awaits the selection action, so the picker receives its actual
completion instead of an unrelated launched job. Cached rows and retired account/
lifecycle actions retain their guards. The Android-only create dialog remains
exclusive while its submission is pending.

## Original source evidence

The audit candidate is a5911a1f92f3eeb11c4d7f3e2ef89204718c5009. Whole-file hashes
cover coordinator TeamSelection and TeamScopes, StackAuthClient, SDK CurrentUser,
User and APIClient, the backend teams route and original team-action tests,
alongside the existing restoration/recovery dependencies. The unchanged prior
original-auth CI run 40072945627 passed 198 tests in 19 suites, including the
Auth coordinator team actions suite. That is separate from this local reference.

The new generator executes the complete original CurrentUser update and
setSelectedTeam(id:) declarations, the complete original resolveTeamID declaration
and the complete original User and CMUXAuthTeam models. The actor harness uses
a recording HTTP boundary; the coordinator helper runs in a wrapper that can
call its unchanged private declaration. Nine cases compare selected/current/nil
ids across JSON, empty and malformed successful response bodies. Android compares
method, path, authentication header, request object and successful projection.
Seven additional rows verify effective selection for nil, valid, stale and empty
team lists. Independent regeneration is byte-identical. This harness does not
execute the whole original SDK or its network stack. The two picker labels are
extracted from the pinned catalog in all nine supported locales; their metadata
and nine Android XML resources also regenerate byte-identically.

## Checkpoint verification

Final debug app and instrumentation APKs build. Full JVM: 3,386 tests in 453
suites, 3,382 passed, four existing SSH skips, zero failures. Eighteen new cases
cover fresh eligibility, success without an echoed body, reselection, nil/effective
selection, failure-tolerant creation, unloaded/cached authority, removed proofs,
replacement identities, independent latest-choice completion and create exclusion.
Existing transport/profile fixtures now explicitly answer the extra user GET;
their authority and continuation assertions remain in place.

Owned API 36 runtime: 150/150 in 234.927s. Ten new account/Keystore/local-HTTP and
Settings-component cases passed, alongside ten existing Settings/inline-picker/
foreground regressions and the previous 130 cases. The new cases include two
ineligible fresh reads without PATCH, encrypted cache restoration after 204,
creation before loaded membership, post-create list failure, replacement
publication, an independent newer choice while an old PATCH is blocked, cached
backend creation before bootstrap, Android create completion, pending choice UI
and localized failure/retry. Every new case retained its saved pairing marker.

Two picker images were inspected: the effective choice/checkmark and the failure
footer are readable without overlapping controls; successful retry removes the
error. These are isolated component captures with a wrapping Surface, not a
matched full Settings/iOS design acceptance result. Only the runtime's active
locale is rendered here; all nine resource outputs match the pinned catalog.

Crash log empty, no preflight ANR, settings/window read back as restored and owned
emulator exit0. Evidence under captures/recovery-20261023: team-mutation-build-v4.log,
team-mutation-jvm-v4, team-mutation-runtime-v2, reference/localization reproduction
outputs and team-mutation-checkpoint.json. The checkpoint binds 38 local source/
resource/context/driver inputs, both installed APKs and 24 whole original files.
The earlier 139-case cohort predates the final picker/cache/fallback changes and
is retained separately; the 150-case result binds the final code.

## Remaining acceptance

This is not full app parity. [The phase/publication follow-up](TEAM_OPERATION_DEADLINES.md)
adds source network budgets, actual/public lifetime accounting, a real default
60-second phase check, idle-versus-call timeout mapping and readonly cached nil
selection. Locked encrypted storage, OS sleep/Doze/network loss and process-death
acceptance remain open. [Independent refresh reads](TEAM_REFRESH_READS.md) now cover
parallel user/list and selected-team requests, remembered choice and profile
projection. Malformed-list/default-model semantics, complete user metadata/profile
bounds, consent-aware telemetry and
worker retry/rate integration remain open. The SDK-only create fallback without
the configured cmux backend is not an Android production route. Readonly cached nil selection is covered in that follow-up; complete independent choice-store/new-login
lifetime remains part of the admission/model audit. Current Android row-size and body bounds are not claimed equivalent
to every SDK default.

Raw-token/access-only consumers, complete backend/identity adoption, live provider,
Firebase/helper behavior, physical Pixel/Mac, signed release and matched UI/
accessibility acceptance remain open. Fixtures and source alignment do not prove
those gates. Source pins, signing material and frozen signed 722 are unchanged.
No physical phone or Mac install or production account/pairing data changed.
