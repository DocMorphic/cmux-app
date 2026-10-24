# Saved-session restoration

This checkpoint ports the normal saved-session path from pinned original
`a5911a1f92f3eeb11c4d7f3e2ef89204718c5009`. It does not advance the global
implemented/reviewed/detected source pins or establish whole-app parity.

## Source-to-behavior mapping

| Original requirement | Android implementation | Evidence scope |
| --- | --- | --- |
| Cached user, token hint, authenticated and restoring are separate states | `NativeAuthState.primed`; encrypted `NativeAuthUserCache`; environment-scoped non-secret hint | Four normal rows compared with execution of unchanged original Swift declarations; all64 launch combinations retained as a reference |
| Bootstrap once; foreground guest is a no-op; overlapping callers join one validation | `NativeSessionRestore.start`, `revalidate`, shared completion | JVM deadlines/coalescing/cancelled-waiter cases; production graph starts and requests foreground validation |
| Probe actual access OR refresh tokens even if the hint is absent | Phase-bounded strict token snapshot plus access refresh | Model cases and encrypted credential fixtures; access-only backend authorization remains a separate open gate |
| Preserve cached presentation on token probe, timeout, unreadable-storage and transient user errors | Restore policy checks live refresh state; storage errors never become empty documents | JVM failure/deadline cases and real Android Keystore/provider-failure fixtures |
| Definitive empty storage clears presentation; nil user or definitive rejection conditionally retires credentials | Generation/write/owner/refresh fences and strict compare-and-clear | JVM nil/rejection/CAS cases; encrypted signout/late-HTTP fixture |
| Publish verified identity before membership; unchanged-account revalidation does not replay transition hooks | Atomic model publication, then cache and membership phases; generation-guarded optional hook | JVM phase ordering; production NativeAccount callback observed while membership is suspended |
| Late validation must not replace a newer login or signout | Model generation plus document-shared credential epoch and monitor | JVM competing writer cases; real second-account signout while HTTP is in flight |
| Restore user request retries only exact `invalid_access_token` once; SDK no-user and rate-limit paths differ | `NativeOAuthClient.restoredCurrentUser` | MockWebServer verifies token headers, retry count, malformed JSON, error collapse and rate-limit classification |
| Restore status warns after10seconds, Retry resets warning and joins validation, auth methods pause | Production `NativeSignInRestoreStatus` and methods card | Controlled production Compose screen/timer and disabled semantics; editable email preserved. Actual TalkBack acceptance remains open |
| Original five restore/retry labels across nine locales | Generated `cmux_signin_restore_strings.xml` | All45 values compared directly with original catalog bytes and SHA; mutation guards cover inventory/resource/locale/format drift |

The source reference workflow builds the untouched original auth libraries and
runs146 coordinator tests in18 suites with full Xcode. Run40060677846 succeeded
at project commit27926770. The local library build also succeeded; local testing
could not import Swift Testing from CLT, so Xcode CI supplies that reference.
This is library/reference evidence, not an original full app or Android result.
The fixture generator executes whole original state/user/auto-login declarations;
Android consumes only the four normal release priming rows. Debug fixture,
clear-auth, mock and auto-login launch modes are reference-only here.

The production process connection owner starts restoration on its IO scope.
Injected local connectors deliberately bypass that process graph. Existing
local-connector UI tests now seed an explicit cached-user presentation instead
of assuming a refresh token alone proves the signed-in shell. Those fixtures
supply no verified team scope and do not count as live authentication evidence.

## Data and remaining gates

The existing Android Keystore alias, cipher format, account, pairing and signing
data are retained. New display-cache fields are optional and encrypted. Only a
non-secret has-tokens hint lives outside the encrypted document. Cached identity
never supplies membership or connection authority. The warning triangle is an
Android vector analogue of the original SF Symbol; native font, glass, shadow,
button paint, sizing and accessibility still require platform comparison.

Local-authority retirement during unreadable storage has a durable intent and
replay path; see [local logout](LOCAL_LOGOUT.md). The bounded server tail and
tracked-effect joining follow [signout teardown](SIGN_OUT_TEARDOWN.md). Live
helper/backend and untracked effects remain open, along with actual protected-data
unlock, bounded initial cache reads, real Firebase callback/rotation acceptance,
complete user metadata/profile image/demo state and full SDK behavior.

The [published-identity/team checkpoint](TEAM_IDENTITY_AUTHORITY.md) now fences
team admission and retained connection proofs by account generation, with cold
private-owner restoration. Broader coordinator/backend ownership across consumers,
exact access-only session snapshots, source team recovery and real post-sign-in
side effects remain open. Optional hook tests do not prove those app effects.

Token-only expiry, forced-refresh refusal and proactive valid-token fallback now
follow the [access-token reader checkpoint](ACCESS_TOKEN_RESOLUTION.md). This does
not close access-only backend admission. Shared refresh ownership/cooldown now
has a separate [registry checkpoint](SHARED_TOKEN_REFRESH.md); actual OS scheduling
and full app reporting integration remain open.
The legacy raw-token `isSignedIn` API still has backend consumers; root UI now
observes the restore model. Finish that ownership/admission audit before calling
the complete auth coordinator equivalent.

Physical Pixel/Mac, real browser/provider, terminal, encrypted notification,
lifecycle, navigation and accessibility acceptance remain required alongside
all broader PARITY.md and REMAINING_WORK.md gates. The last known Pixel712 and
frozen signed722 were not replaced by this development checkpoint.

## Checkpoint verification

Final full JVM:3251 total,3247 passed,4 existing SSH skips,0 failures in440
suites. The debug app and instrumentation APKs build. Final owned API36 run:
50/50 in133.967s, crash log empty, no preflight ANR, settings/window restored,
emulator exit0. This includes the six new restore cases and44 previous auth,
methods, credential/helper/profile, lifecycle and local-connector screen checks.
The pending-bootstrap case uses the new production startup path, not the retained
legacy restore helper. Two earlier49-case runs each failed only the keyboard UI
harness; the corrected harness uses the actual app resize/insets, resumes frames
before scroll checks, and captures progress/warning. The app APK is identical
across these attempts. Exact original keyboard/window geometry remains open.

JVM log: `captures/recovery-20261023/session-restore-build-v6.log`;
final instrumentation build: `session-restore-build-v8.log` in that folder.
Runtime: `session-restore-runtime-v3`; aggregate hashes, tested source bindings,
prior attempts and open gates: `session-restore-checkpoint.json` in that folder.
The source reference is [GitHub run40060677846](https://github.com/DocMorphic/cmux-app/actions/runs/40060677846).
