# Workspace selection audit

Audited 2026-09-30 against upstream
`4c5272e9153eca2033c9f40ac749f0c3a5bcb291`. Fresh-open default selection and local
browser workspace-lifetime protection are implemented. **Persisted last-tab memory,
selection synchronization and physical acceptance remain open.**

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
   focused, first. The pinned `MobileWorkspacePreview+RemoteMapping.swift` maps
   `Terminal.isReady` (`is_ready`) with `nil` defaulting to true. Android now
   preserves the field and applies the same default for absent/null values.
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

`NativeWorkspaceSelection` now resolves fresh workspace opens through the focused
non-terminal, spatial non-terminal fallback for terminal-less workspaces, available
Simulator descriptors, discovered browser streams and readiness/focus terminal
preference. Explicit terminal/browser/surface routes remain exact and reject missing
targets. Changes keeps its dedicated route. `NativeScreen` uses the resolver for
workspace routes; notification routing retains its separate behavior.

Wire browser surfaces and discovered browser streams are different inventories.
The resolver takes a separate discovered-browser list; an unfocused browser surface
alone does not override a ready terminal. Android has not yet connected that
discovery list to selection synchronization. Simulator descriptors are already
available in workspace snapshots. A matching descriptor promotes the selected
raw Simulator surface to its streaming view, preserving focus and host order.

Local browser Back already retains a one-shot in-memory restore intent, distinct
from persisted all-kind last-tab memory. Do not describe that as full selection
parity. The default resolver is not yet the refresh/restore state machine.

The bounded preference store and pure restore decision/controller foundation below
are implemented. Integrate explicit versus derived selection separately, then verify pending discovery, process
recreation, cross-Mac/build identity, focused non-terminal defaults and new-terminal
startup. Keep additions outside the large `NativeScreen` method, which has already
hit Kotlin's JVM method bytecode size limit. Follow with Pixel/Mac acceptance.

## Default-routing verification (2026-09-30)

- **31 JVM tests passed**: nine selection/readiness cases, five existing surface
  cases, four workspace parity cases and 13 local-browser navigation cases.
- Debug and instrumentation APKs build successfully.
- **12 Android 17 / 16 KiB tests passed in 22.773 seconds**: four full-screen
  selection cases (ready/focused terminal, focused Mac pane and explicit terminal
  choice, non-terminal spatial order, exact focused-browser stream), six local
  browser routing regressions, file/Markdown/unknown-surface navigation, and the
  existing Simulator surface-view route through a borrowed RPC client.
- The previous panel test expected the first file despite a focused canvas.
  It now asserts the focused canvas first, then explicitly selects the file,
  preserving its subsequent text/Markdown preview and Mac-focus checks. Captured
  text and Markdown screenshots were inspected and contain the expected content.
- Ignored evidence: `captures/runtime/workspace-selection/`; final build log:
  `/tmp/cmux-workspace-selection-final-build.log`. The owned emulator was stopped.
  No physical Pixel was visible to ADB and no Pixel account/data was touched.
- Debug APK SHA-256: `451570cc2f8899b98424d7e694092bafa2dbf4ffdba59efd07fb55bcaae2f21d`.
- Test APK SHA-256: `d5d91759a4d3930080a225ee5c98da782d66308857ebdcc99cc218609f2710b3`.

Signed build 261 predates this change. No new signed milestone was dispatched.

## Last-tab storage and restore foundation (2026-09-30)

**This foundation is not yet wired into `NativeScreen`; it does not yet make the
app restore all last-opened tabs.**

`NativeWorkspaceLastTabs` supports all five upstream kinds and at most 512 entries.
Unchanged writes do not alter recency. Loading isolates malformed/unknown entries;
unknown kinds remain stored but cannot be selected. Recency overflow rebases the
bounded map while preserving order. Keys use account/team, canonical Mac device
and normalized build tag, and RPC workspace ID. Route/name changes do not change a
known Mac's key. Anonymous pairings use their origin; unverified unscoped accounts
use the login incarnation. This follows the pinned `CmxMacAppInstanceIdentity`
normalization, including trimming build-tag whitespace and treating empty as nil.

`NativeCredentialStore.lastWorkspaceTab` and `rememberWorkspaceTab` use its existing
Keystore-encrypted state. The shared storage lock covers login validation and the
whole read/modify/commit operation, so concurrent store instances merge writes.
Unchanged selections skip encryption/commit/revision changes. Retired login calls
cannot read/write preferences or recreate signed-out account state. Account/team
scope belongs in the computed key; UI callbacks must still check their captured
authorization/navigation scope before choosing that key.

`restoreWorkspaceTab` returns restored, waiting, or unavailable. It distinguishes
empty hydration from populated missing panes, waits for a remembered unready
terminal when a ready sibling exists, and distinguishes undiscovered browser
inventory (`null`) from a successful empty list. A remembered Mac browser surface
stays a raw surface until its stream descriptor is discovered. Simulator restore
requires its descriptor; local browser restore requests a view-owned reopen.
Matching active streams are retained; a different active stream wins over memory.
`NativeWorkspaceTabRestoration` owns one pending key/tab, keeps it across waiting
results, and disarms it on resolution/cancellation/new opens.

Verification:

- **23 JVM tests passed**: six bounded-store/key cases, eight restore cases and
  nine default-selection regressions.
- Debug/instrumentation APKs built. **Three Android 17 / 16 KiB storage tests passed
  in 0.125 seconds**: encrypted round trips across store instances for all five
  kinds and idempotent writes; login replacement/sign-out fencing; and concurrent
  store-instance writes preserving every tab and unrelated account update.
- Android tests use disposable, separately named preferences and the real
  Keystore adapter. They do not exercise screen restoration, process death or a
  real cmux account. The owned emulator was stopped after testing.
- Ignored evidence: `captures/runtime/workspace-tab-memory/`; final build log:
  `/tmp/cmux-workspace-tab-memory-final-build.log`.
- Debug APK SHA-256: `040bde1b768e001ce779fb8539bb1e78ac311843ebc679f1e8c4a36d9091a580`.
- Test APK SHA-256: `463c5b13d7a5b27dc595eaf8ba5a0b07aed417da8a4200635edb150276be5859`.

### Next integration steps

1. Record committed route/default opens, explicit terminal/surface/stream picks,
   task-created terminals, and local-browser opens. Disarm pending restore on
   explicit selection. Do not record an interim derived fallback while waiting.
2. On a default workspace open, consult memory before other defaults; local-browser
   restore must directly reopen retained/default local state without a Mac create
   RPC. Existing one-shot local restore is insufficient after process death.
3. Feed pending restores fresh owning snapshots even while a terminal is visible.
   Cross-Mac/account navigation, notifications, Back and sign-out must retire old
   pending work. Preserve selected pages across degraded/missing inventory.
4. Wire the currently unused `MobileRpcClient.browserPanels(workspaceId)` read to
   scoped discovery. Pinned `MobileBrowserListResponse` requires a `panels` array;
   descriptors use `panel_id`, `workspace_id`, optional URL/title, page dimensions,
   navigation/loading booleans and optional dialog. Reject wrong-workspace or
   malformed results without converting them into confirmed empty discovery.
   Capability gate is `browser.stream.v1`; an old client response cannot update a
   new owning session. See `MobileShellComposite+BrowserStream.swift`.
5. Integrate readiness/created-terminal pins and delayed surface promotion without
   stealing an explicit selection. Verify full-screen restoration for every kind,
   delayed results, Account/Activity/process changes and multiple Macs/builds.

Signed build 261 predates this foundation. No new signed build was dispatched.
