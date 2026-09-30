# Workspace selection audit

Audited 2026-09-30 against upstream
`4c5272e9153eca2033c9f40ac749f0c3a5bcb291`. **Implementation remains open**, apart
from the local-browser workspace-lifetime protection in `LOCAL_BROWSER.md`.

## Reference code

- `Packages/iOS/CmuxMobileShell/Sources/`: `MobileShellComposite.swift` selection
  synchronization, last-tab restore and `WorkspaceAbsenceAuthority.swift`.
- `Packages/iOS/CmuxMobileShellModel/Sources/CmuxMobileShellModel/`:
  `MobileWorkspacePreview.swift`, `MobileTerminalPreview.swift`,
  `MobileWorkspaceLastTabStore.swift`.
- Upstream `MobileWorkspacePreviewDefaultSurfaceTests.swift` and
  `WorkspaceDetailViewDefaultSurfaceSelectionTests.swift` cover default routing.

Use targeted `git show`/`git ls-tree` in the pinned upstream checkout. Broad search
through its partial clone can trigger substantial lazy fetching.

## Required selection policy

1. Preserve the selected workspace and terminal while its owner is degraded;
   absence in an incomplete/disconnected inventory is not deletion.
2. On workspace open, restore the last displayed tab before current-selection,
   Mac-focus or default heuristics. Restore may succeed, wait for panel discovery,
   or be unavailable. An interim fallback must not overwrite a waiting restore.
3. Keep a newly created terminal selected while starting, scoped to the owning
   Mac/build/workspace. Release the pin on readiness, confirmed disappearance,
   scope change or bounded expiry. An existing selected terminal is retained when
   ready, or when no ready terminal exists.
4. Retain active Simulator/browser streams; resolve explicit Mac surfaces to a
   matching Simulator stream first, then browser stream, then the raw surface.
5. With no retained explicit terminal, prefer the Mac-focused non-terminal pane.
   When there are no terminals, use the first non-terminal in host surface order
   if nothing is focused. The fallback is not a separately grouped browser list.
6. If still unresolved, select the first Simulator panel, then browser panel,
   then the preferred terminal. Terminal preference is ready+focused, ready,
   focused, first. `MobileTerminalPreview.isReady` defaults to true; confirm wire
   mapping before adding it to Android's DTO.
7. Reevaluate when delayed panel discovery or Simulator inventory arrives.

## Persistent last-tab memory

Upstream persists a device-local map with five equal tab kinds: terminal,
Mac surface, browser stream, Simulator stream and local browser (`local` ID).
The key uses canonical owning Mac/build identity plus the RPC workspace ID,
independent of aggregate list-row IDs. Android should additionally isolate
account/team scope, consistently with its existing multi-account stores.

The store is bounded to 512 workspaces by a monotonic last-update sequence.
Recording an unchanged tab performs no write and does not bump recency. Unknown
tab kinds are ignored individually, without losing other entries. Explicit
selection cancels pending restoration; derived selection churn does not.

Terminal/Mac-surface restoration waits when inventory is empty; a missing entry
in populated inventory is unavailable. A remembered unready terminal waits when
another terminal is ready. Browser restoration waits for initial discovery or a
known browser pane; Simulator restoration requires a matching descriptor. A
local-browser restore can reopen its retained page or a default page after process
restart; upstream does not persist WebView history.

## Android continuation

Current `NativeScreen` default routing largely chooses first terminal, browser,
then Mac surface. It does not implement this complete policy. Local browser Back
already retains a one-shot in-memory restore intent, distinct from persisted
all-kind last-tab memory. Do not describe that as full selection parity.

Extract a pure selection resolver and bounded preference store, integrate explicit
versus derived selection separately, then verify pending discovery, process
recreation, cross-Mac/build identity, focused non-terminal defaults and new-terminal
startup. Keep additions outside the large `NativeScreen` method, which has already
hit Kotlin's JVM method bytecode size limit. Follow with Pixel/Mac acceptance.
