# Workspace customization

Scoped reference: cmux `0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`,
`WorkspaceCustomizationDraft.swift`, `WorkspaceCustomizationSheet.swift`,
`WorkspaceShellView+WorkspaceActions.swift`,
`MobileShellComposite+WorkspaceActions.swift`,
`MobileShellComposite+Helpers.swift`, and `MobileWorkspaceMetadataLimits.swift`.

Android provides **Customize Workspace** in the workspace row menu and the
shared native pane picker. The form contains name, pinned state, description,
and an optional workspace color. RGB sliders and hex entry cover opaque colors;
this uses Android controls in place of Apple's system ColorPicker. Workspace
color is shown as its own 3dp rail, separate from the Mac avatar's color.

| Field | Mac action through `workspace.action` |
| --- | --- |
| Name | `rename` with `title` |
| Pinned | `pin` / `unpin` |
| Description | `set_description` with `description` / `clear_description` |
| Color | `set_color` with `color` / `clear_color` |

The owning Mac must advertise both `workspace.actions.v1` and
`workspace.metadata.v1`. The form's availability uses that owner's capability
snapshot; the coordinator checks its current verified connection again before
reading or writing. Each request uses the latest workspace/window identity and
its own RPC client identifier, never a foreground sibling's connection.

Descriptions normalize line endings, trim editor whitespace and must fit in
4,096 UTF-8 bytes when changed. The wire's `description_truncated` flag is now
retained, including for an omitted description. Such descriptions are read-only
so a mobile projection cannot overwrite the full Mac value. Unexpected oversized
host descriptions are also protected. Literal description text `null` is
preserved rather than confused with a JSON null. Colors use `#RRGGBB`; clearing
metadata sends explicit clear actions, without invented null parameters.

## Save and conflict handling

Only fields changed from the editor's baseline are submitted, in name,
description, color, pinned order. The coordinator serializes a save against its
other owning-Mac operations, refreshes authoritative snapshots between writes,
and checks owner identity throughout. Unedited fields changed on the Mac are
preserved. If an edited field has independently changed, saving stops and the
form rebases to the latest Mac values for review. A field already equal to the
submitted value requires no repeat write.

After partial failure, the form receives the latest readable baseline and keeps
unsent edits. Retrying does not repeat an accepted rename or a change whose
acknowledgement was lost but whose current value matches. Mutations are not
retried automatically. The host protocol has no atomic compare-and-swap for all
four fields: these freshness/conflict checks follow the iOS sequence and cannot
make separate remote requests an atomic transaction.

Cancel, Back dismissal and field editing are disabled while saving. Dialog
removal cancels its coroutine; owner cancellation propagates. Draft/baseline
values use saved state. Specialized save errors stay in the editor; ordinary
workspace-action diagnostic-only presentation is unchanged.

## Verification — 2026-10-04

Seven policy/RPC JVM cases and four production-coordinator JVM cases pass.
Four Android cases pass on the existing API 37 / 16 KB AVD in **70.401s**:
editor restoration and failed-save retry, truncated-description protection,
duplicate-save/busy-dismissal protection, and a real Activity/local RPC flow
through row and native pane menus that saves then clears metadata. Final form
and row screenshots were inspected; the crash buffer was empty. The sole
emulator was stopped and reaped. No physical Pixel was connected.

The final debug/test APK build passed in 33s. SHA-256:

- Debug: `5ce1700c87fd61f5b399d27dfc703a974db22b82af524a659b902db7b37be237`
- Test: `c5a6c6230fb12c85684d1bf7cd6a9bad6e7682c2b2e82d9341be76c36d29fe5a`

Local evidence: `captures/runtime/workspace-customization/verification.json`.
Earlier component runs exposed asynchronous dialog attachment assertions and a
stale test APK caused by editing during compilation. Those attempts are retained;
the synchronized rebuild and final four-case run pass. Editor saved-state testing
does not establish full Activity/process restoration or live Mac acceptance.

## Remaining acceptance

The routed local-browser process has a separate pane command path and does not
yet expose this editor. Offline entry-point parity still needs a source audit;
the current menu is offered only for a connected owning Mac. Full
Activity/process-restoration, physical Pixel/Mac
metadata changes, broader row visuals and full source/UI parity remain separate
acceptance work. This scoped feature does not establish completion of the app's
production account/network graph or overall goal. Signed build 517 is unchanged.
