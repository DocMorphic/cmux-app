# Combined Computers management — 2026-10-03

## Scope and source

Scoped reference: cmux 0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc,
Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/DeviceTreeView.swift.
The global parity pin remains unchanged.

Android now has a dedicated Computers destination, reachable from Settings,
the workspace toolbar and the saved reconnect screen. It shows existing Mac
method groups and SSH hosts together. The plus menu offers Pair Mac and Add SSH
Computer; Done and Android Back return to the caller.

Mac rows open per-computer details without selecting another active Mac or
workspace. Existing native details retain scoped connection settings, diagnostics,
appearance and removal controls. A legacy TCP record has an informational detail
page and an explicit Reconnect action. Viewing that page never dials the Mac.
Reconnect retains the existing pairing/account checks and retries a failed same
route; an already connected route does not acquire a stale reconnect guard.

The combined list reuses the existing SSH editor, keys, connection, workspace,
shell and confirmed-delete controls. Native rows remain usable while the SSH
runtime loads or reports a retained-data failure; Add SSH becomes available when
that runtime is admitted. The global SSH prompt host is reused.

The destination captures login/team ownership. Stale account/team callbacks and
changed saved pairings cannot open another account's details or select a stale
route. Existing connection and feed policy remains in force: showing management
does not itself select or dial a Mac, but it does not disable unrelated existing
background connection policy.

## Remaining work

This is an integration step, not complete iOS Computers parity. A subsequent
[visibility change](COMPUTER_VISIBILITY.md) implements local switches and hidden
rows. Version warnings, exact refresh behavior, setup-help and full visual
comparison remain open. Rotation currently falls back to Settings rather
than restoring this nested destination. Pair Mac enters Android's existing
discovery flow; preserving the previous foreground pane behind an iOS-like
pairing presentation is not implemented here. Legacy TCP details remain narrower
than native per-Mac controls.

Physical Pixel/Mac acceptance remains pending. Build 468 is still the last
verified signed APK and does not contain this feature.

## Verification

The debug and instrumentation APKs built successfully in 1m 9s; packaging the
updated attribution took a further 10s. Four Android tests passed in 26.817s on
the existing API 37 / 16 KB emulator, with no failures or skipped cases:

- Combined native and SSH rows; opening a Mac invokes details, with no SSH dial;
  Add SSH opens the editor, cancellation returns to management, Pair Mac and Done
  invoke their own actions.
- Editing a saved SSH host shows the correct route; cancelling deletion preserves
  the host and returns to management.
- Production NativeScreen with a local RPC fixture: viewing another Mac's legacy
  details leaves the current computer selection and dial count unchanged; Done
  returns to Workspaces or Settings according to the original entry point.
- The existing native Details-button regression remains passing.

The combined-screen screenshot was visually inspected: method heading, native
row, status/endpoint, add controls, SSH card and keys entry fit without clipping.
The isolated ComponentActivity fixture has light system bars and captured a
pressed-row ripple; it is not evidence of the production window's system-bar
styling or exact iOS visual parity.

The first launcher dump returned a null root during startup. After waking and
dismissing the emulator keyguard, a fresh dump showed the launcher without an OS
error dialog. The emulator was stopped after testing; no additional AVD was
created. ADB showed no physical Pixel, so this run makes no physical acceptance
claim. Packaged NOTICE matches the plain source asset.

Evidence: ignored captures/runtime/computers-management/, with build logs,
instrumentation report, launcher dump, screenshot and APK hashes.
