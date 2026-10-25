Current snapshot follow-up: [Snapshot capture](SNAPSHOT_CAPTURE.md) records the
2026-10-25 application storage-error projection, one aggregate coherent deadline,
refresh-only ordering and 270/270 owned-emulator checkpoint. The historical
checkpoint below keeps its earlier scope; full parity and physical/UI acceptance
remain open.

## Current-owner follow-up — 2026-10-25

[Current-session owners](CURRENT_SESSION_OWNERS.md) records credential-free
snapshot/deletion owner checks and retained deletion outcomes during unavailable
storage. This supersedes the older current-owner open item below. Complete
phase/transition wiring and Android's extra deletion-receipt admission behavior
remain open; this is not complete account-deletion or UI/device parity.

## Cloud credential follow-up — 2026-10-25

[Cloud credential context](CLOUD_CREDENTIAL_CONTEXT.md) adds fresh Cloud pair
capture, optional-team ownership and bootstrap-inclusive deletion credential
capture. Native membership and stored snapshot admission remain separate.
Credential-free snapshot/deletion current-owner checks and whole deletion
confirmation/client behavior under unreadable storage remain open; direct
credential-capture coverage does not close those gates.

Current local-authority follow-up: [Access-only session authority](ACCESS_ONLY_SESSION.md)
records the 2026-10-25 live-verification receipt, restoration cancellation and
initial v2 control credential split. The earlier evidence below retains its
scope; full parity and live-device acceptance remain open.

Current application-layer follow-up: [Application token state](APPLICATION_TOKEN_STATE.md)
records the 2026-10-24 miss classification, presentation clearing, writer fences
and 254/254 owned-emulator checkpoint. The evidence below retains its earlier
scope; full parity and live-device acceptance remain open.

Follow-up: [Ordinary SDK token consumers](ORDINARY_TOKEN_CONSUMERS.md) records
the later optional/resolved adapter separation, live request headers and ordinary
shared-resolver adoption. The earlier checkpoint below keeps its original scope.

# SDK token resolution and pending-session refresh

The audited resolver at `a5911a1f92f3eeb11c4d7f3e2ef89204718c5009`
separates proactive freshness checks from refresh after an access-token rejection.
Android now has that selection policy and uses it when validating interactive
and orphaned pending credentials. Ordinary verified/token-only consumer adoption
and every caller's error and phase-budget projection remain separate open work.

| Situation | Resolver result |
| --- | --- |
| Token-only proactive read | Unexpired access, provided both stored tokens remain unchanged |
| Fresh proactive access with refresh present | Reuse access; do not exchange |
| Access changed since a rejected request and is unexpired | Reuse the replacement |
| Proactive timeout or transient exchange failure | May reuse the old access only while unexpired and the refresh receipt still matches |
| Forced refresh failure | Never fall back to the rejected access |
| Receipt replaced or caller canceled | No usable token pair |

Pending validation now joins the encrypted document/environment's existing shared
refresh coordinator. This gives separate account objects one exchange, shared
rate-limit cooldown, the existing bounded attempt and a fenced final write.
Pending owners have a distinct domain from published owners. Registry bookkeeping
can track them, but normal account/worker views still reject them. Metadata-only
writes keep the attempt alive; publication or receipt/epoch replacement retires
it. A late response after timeout cannot overwrite published credentials.

The shared exchange does not capture its first waiter's phase/cancellation guard:
remaining waiters own the same bounded attempt. Each caller checks its own phase,
cancellation and epoch before continuing. A separately constructed background
account cannot validate an interactive flow's pending receipt while that flow
still owns publication. Bootstrap readers of an orphaned receipt can coalesce;
only a matching receipt can publish its verified user.

The user-fetch preflight establishes token presence. The following request reads
stored access and refresh again, as the original SDK does. It can therefore omit
an access header if that snapshot no longer contains one. Definitive refresh
rejection clears only its matching pending credentials/profile data; unrelated
pairing data remains intact.

## Executed source and limits

`scripts/generate-native-token-resolution-reference.py` executes three complete,
unchanged original declarations: `getOrFetchLikelyValidTokensFromStore`, the
store/rejected-token `fetchNewAccessToken`, and `resolveRefresh`. The 110 cases
compare returned pairs, getter order and exchange inputs. Regeneration is
byte-identical; corpus SHA-256:
`791f7b3463e6e0242bf90159d29f400f9c89294db8954c116156d23f34ba66b8`.

Getter sequences, freshness/expiry predicates and planned coordinator outcomes
are inert seams. Child-task cancellation is real. JWT validity has its own
existing source reference. This corpus does not execute the original shared
coordinator, actual refresh/timers/HTTP or iOS runtime. The reference host is
macOS26.5.2 / CoreFoundation5026.5 with Swift6.3.3. Separate Android runtime tests
exercise actual shared refresh, disposable Keystore-backed stores and controlled
HTTP; that is not physical-provider or original race/timing equivalence proof.

## Verification

Full JVM: 3,472 total in 462 suites, 3,468 passed, four existing SSH skips,
zero failures/errors. Owned API36: 230/230 in 363.422 seconds, ten new methods
and 220 regressions. The 110-case resolver reference runs on ART. Eight real
pending-account methods cover proactive exchange, interactive exchange,
proactive fallback versus forced rejection, reconstructed cooldown, timeout and
late response, definitive cleanup, shared bootstrap readers and cross-instance
interactive publication exclusion. Nine records preserve encrypted-store and
pairing markers.

The real 60-second phase returned at 60008ms with ciphertext/pairing intact
and no PATCH; the shared two-second phase returned at 2062ms. Crash log empty,
no preflight ANR, settings/window restored, owned emulator exit0. Immutable
source/APK/installed-APK hashes and original-file hashes are bound in
`captures/recovery-20261023/token-resolution-checkpoint.json`.

JVM-v2/v3/v4 exposed FIFO fixtures for parallel account reads and a canceled
anonymous user's sibling request count. Endpoint-specific user/PATCH/membership
queues and cancellation-aware counts preserve the existing authority and HTTP
admission assertions. Production foreground/Iroh/eligibility code is unchanged.
Runtime-v1 passed 227/230: the new background-owner fixture misrouted OTP exchange,
and two older fresh-sign-in fixtures supplied opaque access placeholders. Only
those test inputs changed. Runtime-v2 passes all 230 using the identical app APK.
Build-v5 executed the full JVM suite; build-v6 reused identical XML/main/unit
inputs and rebuilt the corrected Android fixtures. Earlier failures are retained
separately.

Full parity remains active. Ordinary consumer adoption and error/budget mappings,
callback failures, direct OAuth/form/backend contracts, reporting/diagnostic and
team-error UI, metadata/image/demo/reset/identity/worker paths, iOS-runtime proof,
actual OS/locked storage/death, physical Pixel/Mac/provider/helper/Firebase,
matched full UI/accessibility and signed delivery remain open. Global pins and
frozen signed build722 are unchanged; no real account, pairing or signing data
is part of these fixtures.
