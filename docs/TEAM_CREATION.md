# Android team creation

Reference: cmux commit `4c5272e9153eca2033c9f40ac749f0c3a5bcb291`:

- `Packages/Shared/CmuxAuthRuntime/Sources/CmuxAuthRuntime/Coordinator/AuthCoordinator+TeamSelection.swift`
- `Packages/Shared/CmuxAuthRuntime/Sources/CmuxAuthRuntime/Client/StackAuthClient.swift`
- `web/app/api/subrouter/teams/route.ts`

The shared iOS authentication coordinator trims the name, creates the team,
refreshes memberships (appending the authoritative created team when the list
lags), and selects it through Stack. Production creation uses
`POST https://cmux.com/api/subrouter/teams` with `displayName`, bearer access token
and `X-Stack-Refresh-Token`. The backend accepts 1–120 UTF-16 code units after
trimming and creates the team for the authenticated user. It also updates the
server selection; the response contains a team id/name and selected team id.

## Android behavior

Settings → Create Team opens a name dialog, trims input, bounds the field to
120 characters and disables duplicate submission while pending. Creation is
available after the account has been verified, including users with no teams.
A successful create refreshes membership and confirms selected-team persistence
before publishing a new native connection scope. The existing Settings picker
remains available for switching teams.

Network requests have deadlines, response bounds, no redirect following and no
connection-failure retry. The create POST is never automatically repeated, even
on an authentication error, since the backend could fail after creation. An
unconfirmed response preserves the name and asks the user to refresh before
trying again. A confirmed team is retained in the picker if subsequent membership
refresh or selection fails, preserving the existing active scope; the user can
select it without creating another team. A changed login or cleared controller
prevents late responses from publishing or selecting a team.

This extends the existing controller and extracts its Settings UI into
`NativeAccountTeamSection.kt`; it does not create any real team during startup or
testing. Account caching and account deletion remain separate missing features.

## Verification — 2026-09-29

**16 JVM account-controller tests passed**, no failures/errors/skips. The six new
cases cover production URL/headers/body and trimming; lagging membership; recovery
from failed selection; the first team after a failed membership refresh; invalid
names/unverified accounts; no retry on disconnect, 401, 503 or redirects; and login
replacement during an outstanding create. Existing refresh and selection tests
remain passing. The debug APK and instrumentation APK build successfully.

The initial UI run passed the existing-team selection and uncertain-creation
cases, but its pending-submit case checked the unmerged Text child rather than
the button's disabled semantics. The selector was corrected to the merged button;
no production behavior or assertion was weakened. The original failure log is
retained as `runtime.txt`.

The corrected combined UI run passed **3 tests in 11.909 seconds** on the Android
17 arm64 emulator with host GPU rendering. It verifies blank-name validation,
trimmed submission, disabled controls/no duplicate submission while pending,
retention of name/warning after uncertainty, and existing-team selection. Pending
and error dialog screenshots were inspected; the isolated test fixture does not
include the full Settings screen's system-bar padding.

Native ELF alignment checks passed for the new main APK. Artifact SHA-256:

| Artifact | SHA-256 |
| --- | --- |
| Main debug APK | `2410aa71882cb703e533357934c3c696a5d779c0cf3ac043743ba5b471393140` |
| Instrumentation APK | `482bd35341f4e22f60083d2f8b040b13f96754a140a8b121305d52aada6bd52b` |

Local evidence is in ignored `captures/runtime/team-creation/`, including build
logs and `account-tests.xml`. No real cmux account was mutated. Physical Pixel /
live-account creation acceptance and full app parity remain open. Signed release
build 157 remains the last published APK.
