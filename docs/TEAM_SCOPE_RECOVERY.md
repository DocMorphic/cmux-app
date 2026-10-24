# Team-scope recovery and rate limits

The process account now owns one recovery loop for an authenticated session whose
team list has not loaded for its published generation. This replaces the old
fixed 60-second membership polling and 15-second failure polling. A successfully
loaded empty list is healthy even though it grants no team scope. Background
workers keep their bounded operations and do not start indefinite loops.

## Source mapping

The audit uses immutable candidate a5911a1f92f3eeb11c4d7f3e2ef89204718c5009.
AuthCoordinator+TeamScopeRecovery.swift, AuthCoordinator.swift,
AuthCoordinator+SessionPriming.swift, AuthRateLimit.swift, SDK APIClient.swift,
the original recovery and rate-limit boundary tests, and Mac host signals were
reviewed. Seventeen whole original file hashes are recorded in
captures/recovery-20261023/team-recovery-source-bindings.json.

Normal delays are 2, 4, 8, 16, 32 and then 60 seconds. Each recovery validates the
user before membership. Loaded membership ends recovery only after the owning
validation and its transition hook finish. Sign-out, connection-owner closure
and a fresh interactive publication cancel the old loop and clear its throttle.
A new sign-in starts again at two seconds. Stale-generation failures cannot
throttle the replacement account. Admission of a new validation is checked under
the shared account monitor; an existing validation still coalesces.

HTTP 429, effective429 status headers and known RATE_LIMIT/RATE_LIMITED errors
remain distinct from unavailable-user errors, including oversized HTML edge
pages. Known body types need no payload read; an unknown type reads at most 64
bytes for classification. Only a fixed endpoint label, json/html/other body kind,
bounded Retry-After, selected delay and attempt number reach the in-memory
reporting producer. No response body, hostname, query, credentials or account id
is retained there. This producer has no automatic upload or consent-aware sink.
Token-refresh SDK cooldown remains a separate policy/type.

Retry-After accepts bounded nonnegative seconds or the HTTP date parser, is
truncated once to milliseconds, and is clamped to 1...900 seconds before the
source's 1...1.5 stretch and final 900-second cap. Without it, the selected delay
starts at 30 seconds, doubles to 900, and applies 0.5...1 jitter with millisecond
truncation. Jitter is drawn once per 429. The actual wait also respects the normal
backoff. A classified non-rate failure clears the throttle; user-validation or
token-probe timeouts and failed refresh-token reads preserve it.

A recovery-only network signal expedites missing membership, does nothing for a
healthy account and cannot bypass a recorded throttle, even after its first
elapsed deadline. The backoff loop owns that retry. Ordinary foreground and
explicit-retry validation remains unconditional, matching the iOS shell route;
it is not claimed to be suppressed by the recovery-only rule. A real default
network callback signals when an Internet path becomes validated. Foreground or
network signals also recheck Android elapsed-time deadlines. A newly observed 429
extends an already-sleeping retry's not-before deadline, so an earlier timer
cannot bypass a newer throttle. This is a deliberate strengthening of the
original timer implementation, not a claim of mechanical timer equivalence.

## Evidence

The reference generator executes the three complete original pure declarations
for normal backoff, rate delay and Retry-After parsing. It does not translate
their predicates into Python. Android matches 10 normal, 48 rate and 14 header
rows. Independent regeneration is byte-identical; whole-file and declaration
hashes are embedded in the tested reference. This is local policy execution,
not execution of the entire original coordinator. The unchanged prior original
auth reference run 40072945627 remains the separate 198-test execution evidence.

Full JVM validation passed 3,364 tests with four existing SSH skips: 3,368 total,
452 suites, zero failures. Twenty-four new cases cover those policy rows, retry
and throttle state, host admission, reset, elapsed-clock wake, timeout preservation,
hook completion, coalescing and real local HTTP classification. Both debug APKs
built successfully.

Owned API 36 runtime passed 130/130 in 217.104 seconds, including the previous
123 regressions. Seven new production account/Keystore/local-HTTP cases cover
ordinary failed membership recovery and stopping after success, oversized team
and user throttles, encrypted cache preservation, loaded empty membership,
local sign-out, a controlled fresh verified publication resetting the old
throttle, and the actual validated default network callback. The publication
case does not execute a provider sign-in. The network case observes initial
registration, not an actual loss/reconnection. Every new case retained its saved
pairing marker. Crash log empty, no preflight ANR, settings/window read back as
restored, and owned emulator exit0.

Evidence: captures/recovery-20261023/team-recovery-build-v1.log,
team-recovery-jvm-v1, team-recovery-runtime-v1, team-recovery-reference.log and
team-recovery-checkpoint.json. The checkpoint binds the 20 local source/context/
driver inputs, both installed APKs and the 17 whole original files. The frozen
signed 722 artifact is unchanged.

## Remaining limits

This does not finish full app parity. Private-worker retry/rate integration,
locked encrypted IO, actual device sleep/Doze/network reconnection and process
death remain open. [The phase/publication follow-up](TEAM_OPERATION_DEADLINES.md)
removes the membership serializer, separates public counts from actual task
handles, bounds network phases and tests the real default 60-second wait. Injected
noncooperative token faults run outside the encrypted-store monitor; these checks
do not prove recovery from a thread blocked while holding that monitor. [The mutation/picker checkpoint](TEAM_MUTATIONS.md) adds fresh
selection eligibility and failure-tolerant backend creation. [Independent refresh](TEAM_REFRESH_READS.md)
adds fresh callback user/selected reads, preserved empty-list choice and account
profile projection. Complete choice-store/new-login lifetime, SDK malformed-list/
default-model semantics, full user metadata/profile bounds and complete transport/
retry behavior remain open.

The reporting producer is not the original consent-aware aggregation/recovery
telemetry consumer. Interactive sign-in's separate rate-retry flow is outside
this checkpoint. Raw-token/access-only consumers, complete backend/identity
adoption, real provider/Firebase/helper behavior, physical Pixel/Mac, signed
release and matched UI/accessibility acceptance remain open. The global source
pins and frozen signed 722 remain unchanged. No physical phone or Mac install or
production account/pairing/signing data changes were made for this checkpoint.
