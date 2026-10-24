# Team refresh eligibility and failed-read continuity

The original StackAuthClient.listTeams first calls SDK getUser(.throw), whose
defaults exclude anonymous and restricted users. Android's direct team-refresh
path parsed an id and profile directly and skipped those flags. It also treated
broad HTTP401/403 failures as evidence that the old membership was gone. The
original coordinator retains an already-loaded team list when a refresh fails;
a failed read is different from a successful empty membership list.

## Source behavior and mapping

| Original contract | Android mapping |
| --- | --- |
| User.swift decodes anonymous/restricted flags with Foundation Bool casts | The common NativeSignInUser decoder is exercised against 29 rows from executing the entire pinned User.swift |
| getUser(.throw) excludes those users before listTeams | Direct refresh wraps decoding/user-read failures as unavailable before requesting teams |
| Most getUser errors collapse into user-unavailable; rate limiting is special | Cancellation is preserved and 429 remains distinct; bounded process recovery now follows [the source policy](TEAM_SCOPE_RECOVERY.md); the reporting consumer remains open |
| AuthCoordinator.refreshTeams retains already-loaded teams on failure | Same-session scope and continuation stamps remain loaded after failed user or team reads |
| Failed sign-in membership cannot revive an older session's loaded teams | New published identity resets verification before its fallible membership call |
| A published user survives membership failure | The verified profile is retained and cached without inventing a team scope |
| Successful membership removal and session transitions retire old authority | Empty valid membership removes stamps; login loss, identity replacement and logout still fence old proofs |

The 29-row fixture executes the complete original SDK User.swift with swiftc,
without translating its flag predicate. Cases include absent/null values,
booleans, numeric 0/1/2/-1/1.0/0.5, strings, arrays, objects and both flags together.
Only boolean true and numeric 1/1.0 mark a single flag true in this reference.
The generator records the immutable upstream path/hash in the fixture. Android
compares eligibility for every row, including the unauthorized decoder verdict.
This is a model-reference execution, not an original full-app acceptance result.

## Authority and cached display

A failed refresh neither verifies new membership nor promotes a saved profile to
authority. A controller with a previously loaded list for the same published
session retains its usual scoped authority. A cold controller or cache-only
presentation has no proof to retain. A subsequent successful removal invalidates
old continuation stamps; readding the team creates a new stamp. A changed
published identity or actual credential-session loss still makes old predicates
false, independently of asynchronous stream delivery.

All application team controllers use the account-bound constructor. Cold private
owners still join user validation. The injected token/controller constructor
remains a backend fixture seam. Direct refresh now uses the same account-user
decoder as sign-in, so anonymous/restricted reads stop before a new team request.
Malformed user bodies become a sanitized unavailable result; malformed team JSON
is not copied into the visible error. A late user error also checks the operation
identity before changing presentation or caches.

A freshly verified user is projected before membership starts. If that read
fails in a new generation, the basic profile remains while old verification is
gone. In a same-generation revalidation, the updated profile can coexist with the
previously loaded membership, matching the original coordinator's two independent
projections. Persisted caches remain display-only after reconstruction.

## Evidence and remaining scope

Whole original hashes are in
captures/recovery-20261023/team-eligibility-source-bindings.json at audit candidate
a5911a1f92f3eeb11c4d7f3e2ef89204718c5009. The twelve whole files include SDK user,
current-user and client implementations, the coordinator/client/team-scope and
recovery paths, token identity and original tests. The prior unchanged original
auth [reference run](https://github.com/DocMorphic/cmux-app/actions/runs/40072945627)
passed 198 tests in 19 suites; the new 29-row model
reference is a separate local execution. Global implemented/reviewed pins remain
unchanged.

This checkpoint aligns eligibility and the failed-refresh continuity policy.
[The later recovery checkpoint](TEAM_SCOPE_RECOVERY.md) adds process-owned
scheduling and Retry-After/backoff metadata. Noncooperative IO/public-phase
counting and the reporting consumer remain open. [The later mutation checkpoint](TEAM_MUTATIONS.md)
adds fresh-user selection and failure-tolerant creation; complete public-phase/
cached admission, exact separate/parallel selected-team reads and SDK malformed-list
/default-model semantics. The bounded Android parser
can reject malformed lists that the SDK would decode as empty; this checkpoint
does not call that behavior equivalent. Profile shape/id/size bounds and complete
user metadata remain part of the broader audit.

Raw-token/access-only consumers, complete backend admission, signout identity
stream timing, actual OS process death/unlock, real provider/helper/Firebase,
physical Pixel/Mac, signed-release and matched source/UI/accessibility acceptance
remain open. Fixtures cannot prove those gates. No phone/Mac install, production
account/pairing/signing data, source pin or frozen signed722 changes here.

## Checkpoint verification

Final debug app/test APKs build. Full JVM: 3,344 tests across 450 suites, 3,340
passed, four existing SSH skips, zero failures. Ten new cases compare all 29 model
rows and cover cold/loaded ineligibility, allowed flag shapes, failed user reads
with 429 distinguished, error versus empty membership, cached presentation,
new-generation profile retention, late cache guards and sanitized response text.
Three older broad-rejection clearing expectations were reconciled to the source
policy. The first full run exposed the remaining cache expectation; it was
updated without weakening the replacement-login/cache guard, then all JVM passed.

Owned API 36 runtime: 123/123 in 193.491s. Seven new production account/team/Keystore/
local-HTTP cases cover cold restricted ownership without ciphertext mutation,
anonymous/restricted direct reads with no new membership request, retained proof
on failed user reads with 429 distinct, failure versus successful empty membership,
actual definitive refresh rejection and stored-session loss, controlled new
publication with failed membership/profile retention, and late ineligible reply
protection for a replacement profile. The prior 116 regressions also passed.
Identity publications and provider responses are controlled fixtures, not a
physical/provider acceptance result. Crash log empty, no preflight ANR,
settings/window restored and emulator exit0.

Evidence under captures/recovery-20261023: team-eligibility-build-v2.log,
team-eligibility-jvm-v2, team-eligibility-runtime-v1, model-reference JSON/log and
aggregate team-eligibility-checkpoint.json. Eleven local changed/context files,
both installed APKs, twelve whole original files and the unchanged signed722 hash
are bound. The complete SDK model reference was independently regenerated to an
ignored output and compared byte-for-byte with the tested fixture. Swift 6.3.3 /
arm64-apple-macosx26.0 supplied that local model execution.
