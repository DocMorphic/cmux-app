# Empty workspace guidance and recovery — 2026-10-03

## Source and scope

Scoped review of upstream `0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`:

- `Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/MobileWorkspaceListEmptyRow.swift`
- `Packages/iOS/CmuxMobileShellUI/Tests/CmuxMobileShellUITests/WorkspaceListEmptyGuidanceTests.swift`

The global parity pin is unchanged. This is one completed source mapping within
the larger audit, not a full parity or physical-device acceptance claim.

## Android behavior

`NativeWorkspaceEmptyRow` supplies one centered scaffold, 44 dp decorative computer
icon, heading, guidance and optional recovery actions. The content width is
bounded on larger displays. Mac lists show **No workspaces yet**, the exact Mac
**Enable iOS pairing** setting name, same-account/team guidance, **Retry** and
**See Docs**. The latter opens `https://cmux.com/docs/ios#prerequisites` through
Android's URI handler and reports an actionable error when no handler can open it.
The iOS source uses a Safari sheet; Android uses the user's browser.

Per-host SSH lists share the scaffold when connected, settled and empty. They
point to the real **New cmux Workspace** and **New Shell** controls below, without
Mac pairing actions. Search and unread filters explain their empty results without
implying that the user needs to pair again. The Android per-host SSH copy reflects
its navigation rather than referring to an aggregate computer menu it does not have.

`NativeWorkspaceEmptyRecovery` owns a single retry, with the iOS 30-second deadline
and timeout copy. Its lifetime sits above the lazy-list row, so structural row
removal does not lose an in-flight attempt. Changing account, team or computer
selection retires the owner; backgrounding cancels its request. A late result from
a canceled attempt cannot replace the current attempt's state. Errors use an
actionable message rather than transport internals.

`NativeFeedCoordinator.refreshWorkspaceLists` awaits a fresh workspace read for
each captured Mac. It checks the exact current handle, waits for authenticated
host identity, serializes with existing reads and retains snapshot acceptance
rules. It neither substitutes another foreground Mac nor fetches notifications.
Canceling the UI-owned request leaves shared connection consumers intact. The
retry callback also checks current credentials, team, visibility and build policy.

## Verification

- 30 JVM tests passed: 26 coordinator tests (including three new selected-host,
  identity/handle replacement and shared-connection cancellation cases), plus four
  retry lifetime/deadline/error tests.
- Debug and instrumentation APKs built successfully; final assembly took 27 seconds.
- Existing API 37 / 16 KB AVD: **OK (2 tests)** in **18.814 seconds**, covering
  docs routing, Mac/SSH/filter guidance, disabled retry, structural row removal,
  timeout and re-enabled retry. The UI fixture uses a one-second deadline; the
  production default remains 30 seconds.
- Mac, SSH and timeout screenshots reviewed at normal portrait text size; labels
  and actions are visible. Crash buffer empty. No additional AVD was created.
- Evidence: `captures/runtime/empty-workspaces/` (ignored local logs, receipts,
  screenshots and JVM XML). Fixtures use callback/socket peers, not live auth.
- Debug APK SHA-256: `f2224a4670c40810a588dbea5c854951aa6193d4d60566be401fb83b88e502d3`.
- Test APK SHA-256: `c6d273c76a5cc1d7d950451d94a97b6a10177c50c2d3676aefd2c8ea315f4a29`.

## Remaining acceptance

The native Mac/Pixel empty-list and offline-to-online recovery flow still needs
physical verification, including account/team changes during retry and browser
return from the documentation. UI fixtures and socket peers establish local
behavior, not live Iroh or SSH server acceptance. Signed build 481 predates this
change and the preceding onboarding implementation.
