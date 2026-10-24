# Published account identity and team authority

A restore can publish a new user before its membership request finishes. The old
Android team predicates compared the stored login and the last membership only.
If the stored login stayed the same, old team routes and retained connection
proofs remained current until membership finished. A return to the old user could
also make old authority appear current again without a new credential grant.

## Original requirements and Android mapping

| Pinned original requirement | Android behavior |
| --- | --- |
| Published account identity includes user and session generation | NativeAuthIdentity and the NativeSessionRestore identity StateFlow |
| New sign-in or different-user validation advances the generation | Verified publication creates a new identity before publishing the model user |
| Same-user revalidation/profile changes preserve the generation | Existing identity and connection proof remain stable |
| Current team scope requires membership loaded for the current account generation | NativeAccountTeams binds verified membership to the captured published identity |
| Scope checks are synchronous even though stream consumption is asynchronous | isCurrent/captureContinuation/allowsContinuation compare the current identity directly |
| Cached account/team presentation grants no membership authority | Cold private account owners validate the user before requesting membership |
| Stale team results cannot install after an account transition | Request and publication guards compare the operation identity and membership user |

Whole original source/test hashes are recorded in
`captures/recovery-20261023/team-identity-source-bindings.json` at audit candidate
`a5911a1f92f3eeb11c4d7f3e2ef89204718c5009`. The existing [original auth reference run](https://github.com/DocMorphic/cmux-app/actions/runs/40072945627)
passed 198 auth/push tests in 19 suites, including identity streams and scope
fences when stream events coalesce. This is original library evidence; this
checkpoint does not advance global implemented/reviewed pins.

## Publication and retirement

Identity is credential-free and redacts the user id in its description. Clearing
published auth state clears identity before cache cleanup. Generation moves on
explicit sign-in and different-user publication, and remains stable for ordinary
same-user validation. Returning to a prior user id retains a newer generation,
so old proofs cannot revive by comparing user strings alone.

The production NativeAccountTeams constructor receives the real account model's
identity getter and restoration entry. Verified membership captures that identity
in addition to its existing login, user, team, selection generation and membership
stamp. New connections and already-admitted continuations both decline on account
identity replacement. Team selection can still preserve a previously admitted
connection while that account's membership remains valid, as in the existing
foreground ownership policy. A fresh account generation resets membership stamps;
re-verifying the same user/team cannot revive an old continuation.

NativeAppConnections observes the identity stream to retire stale team presentation
and calls. The direct authority predicates do not wait for that collector. Request
results compare the captured identity, and responses naming a different user are
refused before requesting that user's team list. Cached profile restoration also
requires the displayed user to match the currently published identity.

## Cold background owners

The notification and reply workers construct private NativeAccount/NativeAccountTeams
owners. They have no Activity bootstrap. Their production team controller now
joins or performs the same initial session validation, including for a cached
user, before loading fresh membership. Once validation publishes a verified user,
membership uses that user without issuing a redundant second users/me request.
If the process owner already loaded membership in its verified-user hook, that
result is reused rather than fetching it twice.

A failed validation can preserve cached identity for display, but its user is not
returned as newly verified background authority. A later call retries validation.
Previously verified same-session identity can survive transient revalidation
failure. Account and membership requests keep their existing phase deadlines and
conditional encrypted writes. No push consent or SDK token is created here.

The injected-controller constructor remains a backend/fixture seam; its tests can
supply an identity provider explicitly. The production call-site inventory uses
the account-bound constructor in NativeAppConnections, NativeScreen,
PhoneFcmBackground.create and NativePhoneReplyBackground.create. The cold path
never opens a Mac/Iroh/SSH terminal merely to restore account membership.

## Remaining gates

This closes the published-principal/team-authority gap, not the complete auth
coordinator. Raw-token isSignedIn/taskSession consumers, access-only account/team
flows, coherent authenticated token snapshots and full backend admission still
need reconciliation. Full source team-recovery scheduling/rate-limit policy,
direct-membership user eligibility/error classification, signout-capture timing,
complete user metadata, post-sign-in effects and app-wide
identity-stream adoption remain open. Current team lists retire through the
collector; immediate admission checks are the synchronous security boundary.

Fixtures do not prove physical terminal/notification behavior, real provider/helper
HTTPS, actual OS process death/protected-data unlock, exact native UI or TalkBack.
All broader PARITY.md/REMAINING_WORK.md gates remain open. Existing encrypted
account, pairing and signing data, global source pins, phone/Mac installs and the
frozen signed release remain unchanged by this development checkpoint.

## Checkpoint verification

Final debug app and instrumentation APKs build. Full JVM: 3,304 tests across 447
suites, 3,300 passed, four existing SSH skips, zero failures. Fourteen new identity/
team cases cover priming, generation/stream behavior, same-user continuity, old
result fencing, immediate authority checks, return-to-user generation safety, cold
coalesced validation and cache preservation without background authority.

Owned API 36 runtime: 99/99 in 177.661s. Seven new production-constructor
account/Keystore/local-HTTP cases cover different-user restoration before blocked
membership, same-user continuity, unavailable-storage signout, controlled fresh
same-user publication, unprimed private ownership, cached private ownership after
failed validation and direct backend user mismatch. Two existing encrypted
notification/reply worker fixture cases and the prior 90-case cohort also passed.
The worker regressions inject their local membership seam; the new cold-owner
cases exercise the production account-bound controller. No live provider result
is inferred from those fixtures.

Crash log empty, no preflight ANR, settings/window restored and emulator exit 0.
Evidence under `captures/recovery-20261023`: `team-identity-build-v4.log`,
`team-identity-jvm-v4`, `team-identity-runtime-v1` and aggregate
`team-identity-checkpoint.json`. The aggregate binds six tested implementation/
test files, both APKs, ten whole original source/test files and open gates.

The later [team-refresh eligibility checkpoint](TEAM_REFRESH_ELIGIBILITY.md)
checks anonymous/restricted flags and aligns failed-read continuity with the
original coordinator. Broad HTTP rejection no longer erases loaded same-session
membership by itself. Full recovery/backoff, mutation eligibility and SDK shape
semantics remain open alongside the broader gates above.
