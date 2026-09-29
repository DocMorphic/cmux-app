# Native computer revocation

Reference: cmux `4c5272e9153eca2033c9f40ac749f0c3a5bcb291`.

## Active upstream path

This is verified through the active app composition, not inferred from a dormant
protocol or legacy broker implementation:

1. `ios/cmux/cmuxApp.swift:217` supplies `root.irxDiscovery` as
   `CMUXMobileRootScene.personalIrohForget`.
2. `MobileIrxDiscoveryProvider.forgetComputer` pins the row's account and the
   live account/team incarnation, resolves matching directory records, and
   rechecks the owner before each revocation.
3. `MobileIrxRuntimeComposition+Directory.revokeBinding` delegates to the current
   `V2ControlService.revokeDevice` with scope checks before and after.
4. The operation is `device.revoke.v1` with `deviceRecordId`, returning a
   correlated `operation.completed.v1` with a nonnegative safe integer revision.
5. `workers/iroh-v2/src/broker.ts` calls `manageableDevice`: the current user must
   own the registration or hold an explicit manage grant. The server then revokes
   that record in the selected team's store and broadcasts authority changes.

Thus the operation affects all clients observing that registration, within its
team. It is not a wildcard revocation across every team or every build on the
account. Older comments describing account-wide/wildcard deletion are broader
than this active V2 transport contract. The Android confirmation/local cleanup
must reflect the actual selected-team, exact-build scope.

## Implemented control/runtime layer

`IrohV2ControlSession.revokeComputer` refreshes a complete, unexpired directory,
then resolves only the requested device/build's current registration IDs. The
control state separately retains all non-revoked Mac registrations, including
those with mobile pairing disabled, while the normal Computers list still shows
only pairable Macs. This prevents a disabled Mac from being mistaken for an
already-absent registration when forgetting it.

Only UUID device identifiers normalize case, matching upstream
`CmxDeviceIDCanonicalization`. Other identifiers remain opaque (including case
and whitespace); build tags must match exactly. No caller-supplied record ID can
bypass the fresh directory lookup. No wildcard build or self-phone revocation
path is exposed.

Each mutation is scoped to the original control owner. Acknowledgements must have
a valid revision at least as new as the starting directory. Confirmed records
are immediately pruned from local connection authority and protected by the
existing revision tombstones; sibling builds remain. The runtime guards the
captured account/team, rejects simultaneous removals of the same target, and
bounds the whole operation to thirty seconds. No Mac connection is opened.

A denied management request fails without disabling unrelated directory or Mac
connections. A failed/unknown/partially completed operation throws to its caller;
it does not claim all bindings were removed. The control owner forces a fresh
directory read afterward, even when the caller timed out and no newer push
revision arrived. It never automatically replays a lost mutation. An explicit
later attempt performs fresh discovery, so already-removed records are skipped.
Explicit authentication rejection retains the existing one-time token recovery
path, since the server rejected that request before authorizing it.

## Verification

Sixty-five focused JVM tests pass: thirty-two control-session, five control-
transport, fifteen computer/runtime action and thirteen runtime/account cases.
New cases exercise WebSocket and signed HTTP revocation, refreshed record IDs,
disabled-pairing registrations, sibling-build preservation, absent targets,
management denial without losing unrelated connections, partial removal and
explicit retry, invalid acknowledgement revisions, lost replies, account switches
between bindings, duplicate admission, deadlines and UUID-only canonicalization.

The first 65-case run had one failure: after a lost reply, the ordinary directory
change queue skipped refresh when no newer revision had arrived. The fix forces
an owner-scoped read after removal instead of relying on that revision gate. The
full 65-case rerun passed. Both build logs and final XML are retained in ignored
`captures/runtime/computer-forget/`.

The integrated build passes 85 focused JVM cases: the preceding 65 backend cases,
eight appearance cases and twelve new confirmation/local-cleanup cases. The new
cases cover operation ordering, duplicate admission, remote failure, local-only
retry, cancellation, account changes, pending unsaved foreground handshakes,
exact captured-code selection, duplicate stored rows, UUID aliases and failed appearance persistence.
Debug and instrumentation APKs assembled successfully. The first two integration
builds hit Kotlin's method-size limit in `NativeScreen`; extracting existing
pairing and preference components resolved it. Native ELF LOAD/RELRO and APK ZIP
16 KB alignment checks passed.

## Confirmation and durable local cleanup

Computer Details now offers **Forget This Computer** with a selected-team,
exact-build confirmation and a warning that an online Mac may register again.
Cancel sends nothing. The confirmation, back navigation and repeat submission are
disabled while pending. The presentation is owned above directory rows, so removing
a row from discovery cannot dispose an operation before local cleanup finishes.

The flow captures the owning login/account/team, target and saved native pairing
codes before mutation. It stops only that computer's foreground handshake or
reconnect, including a discovered computer that has not saved its first pairing
row yet. Unrelated foreground computers remain connected. Denied/unconfirmed
removal retains the saved rows and offers an explicit retry using fresh discovery.

After confirmed server removal, the captured appearance store removes that
computer's UUID aliases for the exact build. The subsequent Direct settings
integration also clears that target's method and direct addresses from its scoped
connection-preference file. A single encrypted credential-store
commit then removes only captured rows still matching their original code,
device/build and native account/team scope, and clears affected selections.
Rotated/new pairing codes, sibling builds, other accounts/teams, credentials,
task drafts and files are preserved. Appearance is saved first; failure in either
store leaves the flow retryable. The metadata files and credential file are not a global transaction.
A failed save is never published as successful.

A local-only retry in the same open Details flow retains the confirmed server
phase and never sends another revocation. Closing Details or restarting discards
that in-memory phase: a later confirmation is a new explicit request, using fresh
directory authority. No background mutation is replayed across process death.
After cleanup, the list reloads and discovery refreshes; newly registered Macs may
reappear. Account/team changes retire the old dialog and fence stale operations.

Legacy or incomplete pairings without a resolvable native detail retain an
explicitly labeled **Remove local pairing** action. This action does not imply
server revocation.

## Acceptance still required

Only fake transports/servers are used for automated revocation checks. Do not
revoke the user's live Mac merely to run a fixture. A real disposable registration
and physical Mac/Pixel workflow remain an acceptance gate, as do process restart
and reconnect with actual server registration behavior. The full companion goal
remains active.

## Final APK and Android UI evidence

The final debug APK passes all twelve Android 17 emulator cases: four Forget UI
cases, four existing Details cases and four existing Appearance cases
(`OK (12 tests)`, 41.172 seconds). They exercise cancel, blocked repeated submit
and Back while pending, explicit server retry, local-only retry after cancel and
reopen, and a captured Details presentation surviving discovery removal until
real encrypted fixture-store cleanup commits. The fixture verifies sibling-build
pairing and draft preservation without opening a Mac transport. Final confirmation
and local-retry screenshots were visually inspected; the standalone retry fixture
is not a full-app layout reference. An earlier 12-case run also passed, but its
confirmation screenshot caught the Android window fade. The final capture waits
for that animation and is opaque/readable.

- Main APK SHA-256: `bef8fc84d8dbe21fcae039c96e5848c04676a6350a444cdd2aaa49cb19556860`.
- Test APK SHA-256: `eca6ff0fff0e6a36e6da136fead3c5c2fba37cd6f5e207d79c168ad15ae6b706`.
- Final build log, JVM XML, instrumentation output, alignment logs, APK receipts
  and screenshots are retained locally in ignored `captures/runtime/computer-forget/`.

ADB saw only the emulator throughout this integration; no physical Pixel was
available for installation. The last installed Pixel checkpoint remains `f0dfc7f`;
published signed build 157 is unchanged. No real registration was revoked, phone
power setting changed or real account fixture created. The emulator was stopped
after verification. Physical testing and signed release acceptance remain open.
