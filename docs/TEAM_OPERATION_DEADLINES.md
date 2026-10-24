# Team operation deadlines and publication

The team controller now applies the original network phase boundaries to direct,
Settings and worker-owned calls. Creation, the subsequent membership attempt and
final selection each receive a separate 60-second budget. A selection's fresh-user
GET and PATCH share one budget; a list's user/token work and membership GET share
one budget. The phase wraps token access and HTTP together. The existing
session/sign-in callback wrapper remains in place.

## Source phase and lifetime mapping

| Original boundary | Android mapping |
| --- | --- |
| AuthTimeouts.default.network is 60 seconds | NativeAccountTeams.NETWORK_TIMEOUT_MILLIS is 60,000 |
| teamSelection uses the plain phase race | TEAM_SELECTION/PHASE ends the public wait without joining losing work; another attempt is allowed |
| listTeams uses token-touching phase tracking | TEAM_LIST/TOKEN retains actual work and its retry gate until retirement |
| Creation holds exclusion across its separate phases | The create claim ends when its public flow ends; failed post-create list still falls back and selects |
| A late result cannot cross session/mutation ownership | Each phase carries a retired ticket plus the operation's account/identity/mutation binding |
| Token work checks elapsed expiry even if a timer callback is delayed | Production list tickets check Android elapsed time before further HTTP or result delivery |
| A plain operation can win its actual race without a second elapsed verdict | Plain tickets do not impose the token phase's extra absolute-expiry check |

A paused-dispatcher test reproduced a second deadline defect: the operation
finished before its timer, but delayed public continuation dispatch let the old
withTimeout wrapper replace that success with a timeout. The waiter now atomically
settles operation versus timer before resuming the caller. Caller cancellation
still cancels promptly, and token phases retain their additional absolute-expiry
check. The failing XML/log and corrected full-suite case are preserved.

Actual task handles remain retained for cancellation/diagnostics after the public
wait ends. Plain team data/backend tasks do not block token quiescence; their
credential-refresh children remain separately tracked. Token-touching list tasks
remain joinable, including the bounded sign-out hook wait. Source send-code and
exchange admission policies are unchanged. The tests distinguish a released
public counter/claim from a cancellation-ignoring actual task still present.

The former read/create serializer is removed: one stalled membership read cannot
make a second call spend its budget waiting in a mutex. Create/select exclusion
still uses the mutation claims; concurrent reads follow the coordinator's source
behavior. Network bodies produce values; membership publication happens only on
the public winning path. Retired work cannot issue additional HTTP or publish its
late result. Principal-mismatch handling also stays on that winning path.

## Transport timeouts

The original SDK explicitly sets URLRequest.timeoutInterval to 30 seconds. The
installed Foundation headers define that property as an idle interval reset by
load activity, rather than a total-call duration. Android team Stack requests now
use 30-second connect/read/write limits, backend requests 60, and no additional
hard whole-call timeout underneath the phase budget. OkHttp 4.12's release source
separately defines its call and individual-read timeouts; its exact artifact and
the installed Foundation header hashes are in team-phase-platform-bindings.json.
This mapping applies to this team client; other OAuth/backend clients and their
complete transport/retry semantics remain separate audits.

The progressing-response runtime case deliberately delivers five chunks with
20-second gaps: no gap reaches the SDK idle timeout, and the full body would need
about 80 seconds. It verifies the real default phase deadline, without a token
fault or shortened timer. Fixture-server cleanup waits separately for its local
socket writer; that cleanup time is not the measured phase duration.

## Current membership, cache and atomic projection

Selection changes the current projection instead of restoring its starting
membership/profile snapshot. Creation's failed-list fallback also uses the latest
available list, excluding readonly cached rows. An intervening refresh therefore
cannot have a removed team or old profile reinstated by an older selection or
create result. Existing continuation proofs retire when membership is removed.

A cache-writer invariant test reproduced a separate bug: the old published scope
could remain visible after its membership proof had already retired. Publication
now changes proofs, effective scope and complete profile under the account monitor
as one projection, before persistence. Final cache/loading/error decoration uses
the newest projection if another valid publication intervened. It cannot restore
an older result. The first failing invariant log is retained as reproduction
evidence, and the corrected full suite includes the invariant.

Explicit selection is stored independently from readonly cached membership. Nil,
a stale bounded id or a remembered id with an empty list no longer makes an
otherwise valid card disappear. Login/environment/user/schema and field bounds
still apply, and cached rows still restore no scope or proof. A cached-only nil
selection requires a fresh eligible-user GET and successful empty-object PATCH,
persists the explicit nil through a narrowly bound cache update, and leaves the
saved rows readonly. It does not verify the new account owner or permit selecting
cached member rows. No credentials or pairing fields are changed by that update.

## Checkpoint verification

Full JVM: 3,405 tests in 454 suites, 3,401 passed, four existing SSH skips, zero
failures. Nineteen new cases cover shared/separate budgets, public versus actual
noncooperative lifetimes, concurrent membership reads, no late HTTP or POST replay,
intervening membership/profile publication, readonly cached nil selection, atomic
scope/proof publication and a completed operation whose caller is dispatched late.

Owned API 36 runtime: 158/158 in 331.848s, eight new cases using the real encrypted
store, Keystore and local HTTP, plus 150 existing regressions. The progressing-body production
phase timed out at 60,010ms with no PATCH, unchanged encrypted fixture account data
and its saved pairing marker intact. All eight new cases preserved their pairing
markers. Crash log empty, no preflight ANR, settings/window read back as restored,
and owned emulator exit0. No physical device was used.

Evidence under captures/recovery-20261023: team-phase-build-v4.log,
team-phase-jvm-v4, team-phase-runtime-v3 and team-phase-checkpoint.json. The receipt
binds 19 local source/context/reference/driver inputs, both installed APKs, 35
whole original files and the platform source/header evidence. The atomic and
winner before-fix logs/XML each preserve the original failing invariant. Earlier
v1/v2 runtime evidence predates the final race fix; the v3 result binds final code.

## Original evidence and remaining acceptance

The audit candidate remains a5911a1f92f3eeb11c4d7f3e2ef89204718c5009. Whole-file
bindings include the phase router, plain race, token deadline/tracker/registry,
team actions, SDK/API/client, models and original tests. The unchanged original
reference run 40072945627 passed 198 tests in 19 suites, including timeout and
token-touching cancellation suites. A separate local generator executes the
complete original AuthTimeouts model, its defaults and constructor fallback, and
inspects Foundation's default request interval. Its output regenerates identically.

This is not full app parity. The cancellation-ignoring token faults occur outside
the encrypted-store monitor. Android-specific cache commits still use that
monitor: a thread blocked while holding it, actual locked-Keystore/unlock and
process death remain open. Network wait bounds alone do not prove every storage
or controller wait can finish. Android coroutine timers and elapsed guards also
do not establish actual sleep/Doze/wake equivalence with the source clock.

[The independent-refresh follow-up](TEAM_REFRESH_READS.md) adds the fresh callback
user and separate parallel selection read, empty-membership remembered choice and
published-profile projection. [Independent selection lifetime](TEAM_SELECTION_LIFETIME.md)
covers receipt/publication storage, with actual locked-storage/death and full
project reset still open. [SDK projections](SDK_USER_TEAM_PROJECTIONS.md) cover raw/default
fields, model/cache metadata, repeated/empty rows and former successful body bounds.
Complete JSON lexical/Unicode/number semantics and
transport/retry semantics, worker retry/rate consumers, complete identity/backend
adoption and consent-aware reporting remain open.
Physical Pixel/Mac, actual provider/helper/Firebase, signed-release and matched
full UI/accessibility acceptance remain open. The Create Team dialog is an
existing Android extension, not an iOS creation dialog. No physical phone/Mac
install, production account/pairing/signing material, global source pin or frozen
signed 722 changes were made.
