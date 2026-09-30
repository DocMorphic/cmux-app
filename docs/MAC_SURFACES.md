# Mac surface inventory and panel previews

Reference: cmux `4c5272e9153eca2033c9f40ac749f0c3a5bcb291`.
Sources inspected: `MobileSurfaceKind`, `MobileSurfacePreview`,
`MobileSurfaceInventoryTests`, `MacSurfaceRenderer`,
`WorkspaceDetailView+Surfaces`, `WorkspaceDetailView+PanelArtifacts`,
`MobileChatEventSource+PanelArtifacts`, `PanelFileSurfaceView`,
`MarkdownSurfaceView`, `SurfaceFallbackCardView`, and the surface-focus RPC.

## Implemented checkpoint

The Android workspace model previously kept terminals and browser descriptors
and discarded all other surface inventory. It now preserves each surface's
Mac-local ID, open kind string, title, focus flag, file path and serialized Todo
snapshot. Invalid descriptors are discarded and duplicate IDs collapse to one
inventory entry. Unknown kinds remain navigable. Workspaces with only panels
can open without inventing a terminal. Surface rows and the panel title picker
route to the owning saved Mac through the existing origin/client/route fences.
Moved-surface notifications can resolve nonterminal owners too.

File-preview and Markdown panels use a separate `ArtifactAuthorization.Panel`.
Only `mobile.panel.artifact.stat`, `.fetch` and `.thumbnail` can be called; every
request contains the exact workspace, surface and displayed path. Folder listing,
neighboring paths, missing panel capability, and terminal/chat fallback are
rejected locally. The Mac remains the authority on whether the panel still
displays that file. Downloads reuse the bounded chunk, size and offset checks,
private preview files and cleanup of the existing artifact viewer. Markdown
surfaces explicitly select Markdown rendering even for extensionless paths.
Local Markdown links/assets remain unavailable, matching the pinned panel path.

The embedded viewer supports the existing text/image/PDF/media paths and viewer
actions. Its title/path identity restarts loading when a descriptor changes.
Completed content remains mounted over a reconnect; new reads check capability
and the current connection before dispatch, then fence stale connection replies. Entering
a panel from the terminal menu retains that terminal's state owner and draft,
stops direct input and motion, and hides the keyboard. Creating a new task clears
the previous surface selection.

Todo surfaces now have native checklist/status controls; see [TODO.md](TODO.md).
Other unsupported kinds have a card and capability-gated `mobile.surface.focus` action.
Focusing requires a tap, preserves the exact workspace/surface IDs, displays
pending/error state, and cancels with the card's lifecycle. No Mac focus action
is sent just because a card opens.

## Verification — 2026-09-30

- Eleven focused JVM tests pass: five inventory/scope/notification tests and six
  artifact RPC regression tests. The named `NativeNotificationTest` glob had no
  matching suite; it is not included in that count.
- Debug/test APKs build; all native LOAD/RELRO and APK ZIP alignment checks pass.
- Android 17, 16 KiB kernel: **three UI/RPC tests pass in 13.629 seconds**:
  panel-only workspace text/Markdown/fallback flow, multi-Mac workspace routing
  with colliding IDs, and moved-notification navigation/missing-destination guard.
- Panel fixture checks the exact workspace/surface/path for every read, no
  terminal/chat artifact calls, no automatic focus, and exact focus action IDs.
  It checks actual native text, Markdown DOM content **and >500 painted light
  pixels inside the WebView**, then checks the exact raw Markdown after switching.
- The initial test passed but captured Markdown before it painted. That screenshot
  was not accepted as visual proof. The stronger final test passes, and final
  text, Markdown and fallback screenshots were visually inspected.
- Evidence is local/ignored under `captures/runtime/surfaces/`. Emulator stopped.
- Final debug APK SHA-256:
  `37c58b7855357c31a883a17949292f6b111a90bf02dde2074659214d1d6c9a6f`.
- Final instrumentation APK SHA-256:
  `14414140286e21d5e6cf06ec428713dd987853f9f7606df1e46d227332145684`.
- The physical Pixel was not visible to ADB; no Pixel install/acceptance claimed.
  This work is newer than signed build 244.

## Remaining surface work — required for full parity

This audit exposed missing implementations; these are **not Android platform
limitations**, and a fallback card does not satisfy their native iOS behavior.

1. **Todo acceptance**: native controls and recovery are implemented and verified
   against the framed RPC fixture. Real Mac/Pixel behavior, touch feel, accessibility
   and visual comparison still need acceptance; see [TODO.md](TODO.md).
2. **Simulator streaming**: pinned `WorkspaceDetailView+Surfaces` has legacy and
   V2 simulator paths, device switching and recovery. Android has no corresponding
   surface UI/runtime. Audit exact protocols before implementation.
3. **Browser stream path**: the iOS view distinguishes `.browser` and
   `.browserStream`; compare the latter against Android's current image/RPC
   browser rather than treating the existing browser tests as proof of both.
4. **Selection and UI**: exact focus/default-selection policy, surface glyphs,
   card styling, terminal/browser restoration, complete live descriptor changes,
   panel error classification, and phone accessibility/theme behavior still need
   source comparison and acceptance. Pixel tests must use dedicated workspaces.

These add to the existing account, notification/push, terminal and physical-device
acceptance items in `PARITY.md`; they do not replace that scope.
