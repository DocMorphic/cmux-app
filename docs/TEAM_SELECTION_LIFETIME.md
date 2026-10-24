# Remembered team selection lifetime

The Android companion now stores its remembered team choice independently from
the cached account card and member rows. A new login receipt or a published new
principal keeps that preference while discarding old membership. An interrupted
exchange or failed user fetch cannot erase it merely by pruning the old card.
The choice is presentation state: it supplies no verified membership, connection
scope or continuation proof.

The preference remains inside the existing encrypted account document under
`auth_team_selection`; it introduces no key, plaintext preference, token or
Keychain operation. The record identifies the auth environment, not a login or
user. Explicit nil is a versioned tombstone so a legacy account card cannot
resurrect a cleared choice. Unknown/malformed independent records never fall back
to the legacy card. Choice normalization uses the existing policy executed against
Foundation, including Unicode whitespace, without imposing the card's id bound.
Other SDK model and cache bounds remain a separate audit.

The previous version's choice migrates before receipt-based card pruning. Legacy
choice decoding does not depend on valid member/profile fields. A malformed
choice does not invalidate an otherwise readable display card. Reading an old
snapshot does not itself rewrite it. Current writes migrate it in the existing
encrypted transaction. Owned card retirement preserves the choice; stale-owner
writes remain rejected. An owned explicit choice can be saved without a card.

Local logout, guarded definitive session clearing and readable empty-token restore
clear both forms of the choice. Unreadable storage is retained; the existing
durable local-logout intent removes the choice when retirement replays. Switching
the team controller's auth environment clears its remembered choice before
exposure and switching back cannot revive the old card's choice. This is the
choice reset only; complete auth-project token/user/backend reset remains open.

## Original behavior

Source remains pinned to audit candidate
`a5911a1f92f3eeb11c4d7f3e2ef89204718c5009`.
`AuthCoordinator.beginSignInFlow`, `advanceSessionGeneration` and
`applySignedInUser` retain selection. `clearAuthState` sets it to nil. The property
observer writes through the separate `CMUXAuthTeamSelectionStore`. The original
tests explicitly cover persisted selection surviving sign-in, unavailable team
refresh and changed-account publication, and selection disappearing on sign-out.

`scripts/generate-native-team-selection-lifetime-reference.py` executes ten complete
original coordinator declarations, plus whole original flow context, timeouts,
user/team models, selection store and backing protocol. Ten cases cover sign-in
entry, generation advancement, failed membership at sign-in, changed principal,
successful empty membership, fallback/server choice, both clear variants and cold
construction. Regeneration is byte-identical. The harness supplies memory backing,
a fake team client, nonblocking phase/preflight/hook seams and inert user/session
caches and publication notifications. It does not execute the original SDK,
exchange, timers, transport or full scope publisher. Existing original auth CI
40072945627 remains separate evidence.

## Verification

Full JVM: 3,434 total in 456 suites, 3,430 passed, four existing SSH skips, zero
failures/errors. Eighteen new tests exercise the lifecycle, migration, explicit
nil, corrupt records, stale owners, environment reset and shared completion
interruption/failure/publication. Existing changed-principal expectations now
match the original source. Two broad attempts exposed older FIFO HTTP fixture
races: parallel user and membership requests could consume each other's replies.
Those test queues now route by endpoint; production request ordering was not
serialized. The first focused attempt also corrected a test expectation for
U+200B using the existing executed Foundation reference (it trims to nil).

Both debug APKs build. Owned API 36 runtime: 189/189 in 365 seconds, comprising
six new cases plus the prior 183 regressions. Evidence is under
`captures/recovery-20261023/team-choice-runtime-v2`. Six new cases use real Android Keystore
encryption and current completion/storage code. The migration input is an actual
old-shape encrypted fixture snapshot, because current store writes already migrate
it. All six preserve an encrypted fixture pairing marker. Existing unavailable-key
logout tests preserve a valid saved-Mac record, now seed the independent preference
and assert its retirement. These use an injected unavailable-key provider, not a
physically locked device. User/team completion replies are controlled seams.

The first runtime attempt completed all 189 cases with one fixture setup failure:
the empty-token test removed the legacy card before migrating the standalone
choice it intended to test. The corrected fixture explicitly migrates before
removing that card. The final rerun retains the first attempt as distinct evidence.
The production 60-second deadline returned at 60,010ms with fixture ciphertext
and pairing intact and no PATCH. Crash log is empty, preflight had no System UI
ANR, settings/window read back as restored and the owned emulator exited zero.
The receipt binds 51 local source/reference/context/driver inputs, both installed
APKs and 40 whole original source files. Documentation hashes are bound separately.

Evidence: `team-choice-build-v3.log`, `team-choice-jvm-v3`,
`team-choice-android-build-v5.log`, both original reference outputs/logs and
`team-choice-checkpoint.json` under `captures/recovery-20261023`.

## Remaining acceptance

This closes the identified choice-versus-card receipt/publication lifetime gap,
not full app parity. [SDK user/team projections](SDK_USER_TEAM_PROJECTIONS.md)
cover the default fields/raw strings, model/cache metadata and former successful
response/row bounds. Complete JSON lexical and transport/retry behavior,
auth-project token/backend adoption, worker retry/rate/
reporting integration, actual locked Keystore, OS sleep/network loss/process
death, physical Pixel/Mac/provider/helper/Firebase, matched full UI/accessibility
and final signed release gates remain open. Neither isolated reference execution
nor emulator fixtures substitutes for those requirements. No real account,
pairing/signing material, phone/Mac install, global source pin or frozen signed
722 changes in this checkpoint.
