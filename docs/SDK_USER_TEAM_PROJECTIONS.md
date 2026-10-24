# SDK user and team projections

The companion now follows the pinned SDK's exposed user/team field defaults and
raw strings. Missing/nonstring user and team ids become empty strings; nonstring
optional user fields become nil, and nonstring team names become empty strings.
Names, emails and ids are not trimmed. The shared account's profile-image string
and server-written demonstration-content flag now survive user publication and
encrypted account-cache reconstruction. Only an actual boolean true under
`client_read_only_metadata.cmuxReviewDemoContent` enables that stored flag;
numeric/string lookalikes and client-writable metadata do not. This implements
model/cache preservation, not profile-image rendering or the complete demo flow.

Successful team-list responses without an all-object `items` array count as
loaded empty membership, including ordinary unreadable JSON bodies. They retire
old membership proofs and connection scope instead of retaining stale authority
or starting recovery. Request/transport failure remains distinct and preserves
only the already-loaded current session's membership. Duplicate and empty-id rows
remain in the display model, in order. Empty ids cannot supply connection scope.

The SDK paths no longer impose Android-only 128-character ids, 512-character
profile/team names, 4,096 rows or 256 KiB/2 MiB successful user/team response limits.
The source reference executes 4,097 rows in a 2,238,212-byte response. Cache fields
retain their own version/environment/type checks and owner fencing, but accept the
source strings and repeated/empty rows. Cache reconstruction is still display-only.
Other backend/token/mutation validation and bounds remain separately audited.

## Authentication versus connection identity

The original can publish an authenticated empty-id user while its published
connection identity remains nil. Android now records verified auth generation
separately from its optional connection identity. A primed empty-id cached user
must validate even though both cached and verified connection identities are nil.
Verified empty-id users can load membership and own recovery, without granting a
connection scope. Each team operation, publication and error/recovery outcome is
also fenced by auth generation, so an older empty-id session cannot publish after
a newer empty-id sign-in. Nonempty whitespace ids remain raw, matching the source's
`isEmpty` guards rather than Android's former `isBlank` test.

The process recovery binding consumes credential-free auth-generation outcomes;
it does not invent an empty connection identity to communicate an outcome. The
older identity callback remains available for existing observers. Session clear
retires the explicit validation proof as well as identity and cached state.

## Rows and accessibility

The account picker preserves raw visual labels, including blank labels. Android
composition keys include row position so repeated SDK ids can render separate
rows. The original SwiftUI picker uses `ForEach` ids, whose duplicate-id rendering
is not established by the constructor reference. Android's existing spoken
`Unnamed team` fallback remains for blank labels; it is an accessibility-only
platform accommodation, not substituted visual text. Physical TalkBack and full
matched iOS/Android screen acceptance remain open.

## Source and verification

Audit candidate remains `a5911a1f92f3eeb11c4d7f3e2ef89204718c5009`.
`scripts/generate-native-sdk-model-reference.py` executes complete original
`CurrentUser` and `Team` initializers, `CurrentUser.listTeams`, shared-user mapping
and demo-flag projection declarations. Whole SDK User and shared CMUXAuthUser,
identity-store and backing-protocol sources execute as well. The fake APIClient
only returns controlled data or a transport failure; wrapper property accessors
expose the original User data. This does not run the original getUser/token/API
client, timers, full scope publisher, platform cache or UI.

The generated fixture contains 34 user cases, 19 list cases and 13 cached-user
cases plus the large-list/body and throwing-transport checks. Independent
regeneration is byte-identical. The original connection-identity and team-scope
guards and mobile picker source are inspected separately; they are not executed
by that generator.

Full JVM: 3,446 tests in 457 suites, 3,442 passed, four existing SSH integration
skips, zero failures/errors. Twelve new test methods consume the source projections
and cover HTTP/cache boundaries, empty/whitespace ids, unavailable cached proof,
generation retirement and recovery outcomes. Earlier failures retain the old
expectations (trimmed names, rejected empty ids/default fields, duplicates and
successful malformed list preservation) as distinct evidence. A fixture routing
correction keeps a membership 403 in the membership queue rather than the new
separate user queue. Both debug APKs build.

Owned API 36 runtime: 195/195 in 371.92 seconds under
`captures/recovery-20261023/sdk-model-runtime-v1`, comprising five new account cases,
one new component case and the prior 189 regressions. Five new real account/Keystore/local-HTTP cases cover
raw metadata/rows and cold reconstruction, malformed successful lists, empty-id
generation replacement, actual empty-id recovery, and oversized successful
responses. One new account-component test renders duplicate/blank rows, selects
the blank id and reorders the rows. User replies and one replacement publication
are controlled seams. They do not execute a live provider or physical device.

The initial component image exposed a test-container status-bar overlap. Only that
test container changed to include safe-drawing insets; production code/APK stayed
identical. A separately bound focused rerun passed 1/1 in 2.151 seconds under
`sdk-model-ui-runtime-v2`. Its image was inspected: the header clears the status
bar and the blank/duplicate rows, labels, footer and controls remain visible.
This rerun does not repeat or replace the 195-case cohort. Both runs have empty
crash logs, no preflight System UI ANR, restored settings/window and owned emulator
exit0. The cohort's production 60-second phase returned at 60,015ms with fixture
ciphertext/pairing intact and no PATCH. The receipt binds 65 local inputs and
41 whole originals, both test-APK versions and the unchanged production APK.

Evidence: `sdk-model-build-v4.log`, `sdk-model-jvm-v4`, `sdk-model-ui-build-v5.log`,
both runtime directories, original reference regeneration and
`sdk-model-checkpoint.json` under `captures/recovery-20261023`.

## Remaining scope

This is not full SDK or app parity. Foundation versus Android JSON lexical,
Unicode/number/error behavior beyond the executed matrix remains open; Android's
general JSONObject parser still accepts some forms Foundation rejects. Complete
SDK transport/error/retry/nonce semantics, remaining backend/token/mutation bounds
and full identity/backend/worker consumer adoption also remain open. Preserving
profile-image/demo fields does not close their downstream UI/demo-content gates.
Complete auth-project token/user reset, consent-aware reporting/rate integration,
actual locked storage/OS sleep/network loss/process death, physical Pixel/Mac/
provider/helper/Firebase, matched full UI/accessibility and final signed-release
acceptance remain open. Account/pairing/signing data, the global source pins and
frozen signed 722 remain unchanged; this checkpoint performs no physical install.
