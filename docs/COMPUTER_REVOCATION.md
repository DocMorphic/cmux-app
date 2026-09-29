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

Only local fake transports/servers received revocations. No live cmux registration
was removed. No APK was assembled or installed for this backend-only checkpoint;
the latest APK remains the preceding indicator build, and the last installed
Pixel checkpoint remains `f0dfc7f`. No Android UI test or real Mac removal is
claimed here.

## Remaining integration and acceptance

This checkpoint provides the server/runtime operation. The user-facing Forget
confirmation and durable local cleanup are still to be wired; the existing
Android “Forget current Mac” remains local-only until that work is complete.

Next integration must:

- Capture the target, owning account/team and exact saved pairing codes before
  starting. Confirm the selected-team registration removal and explain that an
  online Mac may reappear when it registers again.
- Prevent duplicate submissions and dismissal while a removal is pending.
- Stop the foreground reconnect for the confirmed target before mutation, so a
  late handshake cannot re-save a pairing during cleanup. Other Macs remain live.
- Retain local rows on denied or unconfirmed removal. Explain partial/unknown
  outcomes; retries must resolve fresh authority rather than blindly resend.
- After confirmed server removal, atomically remove only captured native rows
  from their captured login/account/team. Clear affected selection and appearance
  metadata without deleting other builds/accounts or user task drafts/files.
- If local cleanup fails after remote confirmation, offer a local-only retry in
  that open flow, without revoking a newly re-registered Mac again.
- Refresh discovery after cleanup and allow new registrations to reappear.
- Exercise confirmation/cancel, double-tap, account/team flips, offline/denied
  errors, storage failure and restart/reconnect before the physical Mac/Pixel
  acceptance test. Do not revoke the user's live Mac merely to run a fixture.

The full companion goal remains active. A final APK build/installation is deferred
until this UI/local-storage integration is ready, consistent with batching builds.
