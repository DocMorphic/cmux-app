# Workspace customization

Scoped reference: cmux `0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`,
`WorkspaceCustomizationDraft.swift`, `WorkspaceCustomizationSheet.swift`,
`WorkspaceShellView+WorkspaceActions.swift`,
`MobileShellComposite+WorkspaceActions.swift`,
`MobileShellComposite+Helpers.swift`, and `MobileWorkspaceMetadataLimits.swift`.

Android provides **Customize Workspace** in the workspace row menu and the
shared native pane picker, including the local browser. The form contains name, pinned state, description,
and an optional workspace color. RGB sliders and hex entry cover opaque colors;
this uses Android controls in place of Apple's system ColorPicker. Workspace
color is shown as its own 3dp rail, independent of Mac appearance settings.
See [WORKSPACE_ROWS.md](WORKSPACE_ROWS.md) for the newer row layout without a Mac
avatar in the row body.

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

## Local browser process integration — 2026-10-04

The routed browser now presents the same editor inside its existing Activity.
Saving goes through its existing bound service to the main process's owning-Mac
coordinator. Opening, cancelling or saving the editor does not finish the browser
Activity, reload its WebView, discard page JavaScript state or release its host
lease. The direct browser view also exposes the editor when its owner supports it.

Only draft/result metadata crosses the process boundary. The service resolves
the workspace from the registered browser session; the request cannot choose a
Mac or workspace. The same-UID and attached-Messenger checks apply. The parent
checks its current destination, account/pairing and capability snapshot again
before invoking the coordinator. Live capability changes update the editor/menu
while the parent Activity is stopped. SSH menus do not expose Mac metadata.

One save per registered browser session is permitted. Browser-session exit
cancels an in-flight save; removal of the editor sends cancellation through IPC.
This stops future work but cannot undo a field the Mac has already accepted.
Existing fresh-read/partial-failure reconciliation still applies on retry.
A multi-field customization request has a 120-second outer IPC deadline; its
individual Mac requests keep their existing deadlines. No save is auto-replayed.

### Browser integration verification

**14 JVM tests pass**: customization policy/RPC/coordinator (11) and routed
return ownership (3). **Eight Android cases pass**: seven cases in 94.815s, plus
the targeted IPC-cancellation case in 21.463s. These exercise the production
browser Activity, bound service and proxy against a generated local HTTP page,
with a fixture save callback in the main process. The coordinator's exact Mac RPC
framing/conflict paths are covered separately; this is not live Mac acceptance.

The tests verify failed-save retry, new metadata on reopening, capability
revocation/restoration, page JavaScript state and request-count preservation,
host-lease retention, cancellation across IPC, duplicate-save rejection,
session-exit cancellation, metadata round trips and the three shared editor
regressions. Form/page screenshots were inspected; both crash buffers were empty.
The sole existing AVD was stopped and reaped after testing.

Main debug APK SHA-256:
`07be9ec71565b06364cbb5659c0107aa4f99cab0eba29344a3972028c65f63b6`.
Local receipt: `captures/runtime/browser-workspace-customization/verification.json`.
Signed build 517 is unchanged.

## Offline discovery and save admission — 2026-10-04

At the scoped upstream revision, `WorkspaceListRowModel.rowModel` and
`WorkspaceDetailContainer` discover Customize using the owning workspace's
`supportsWorkspaceActions` and `supportsWorkspaceMetadata`. In
`MobileShellComposite`, `markSecondaryMacUnavailable` and
`clearRemoteConnectionContext` downgrade retained rows' status/authority while
preserving their action-capability snapshots. `sendWorkspaceMutation` returns
not-connected when the owning route is absent; it never substitutes another Mac.

Android now follows that separation. The last known capability snapshot keeps
Customize available during an outage or feed pause. An unknown host or one
missing either capability does not offer it. Every save still requires the
current, allowed, verified owning connection and its fresh capability set.
Offline save failure leaves the editor/draft open. Reconnection does not submit
pending edits automatically, and unpair/account retirement removes retained rows.

### Offline and Activity restoration verification

**13 JVM tests pass**, including a paused production coordinator that retains
menu capabilities but rejects writes, owner removal, and both-capability gates
across all connection states. The expanded real Activity/local RPC case passes
in **41.571s**: disconnect, open the row editor offline, edit all four fields,
recreate the Activity, reject an offline save with no writes, reconnect with no
automatic replay, explicitly save the exact four actions, then clear description
and color from the terminal's pane picker. Restored form and saved-row screenshots
were inspected; the crash buffer was empty. The sole AVD was stopped/reaped.
The fixture records expected socket-closed exceptions from the deliberate outage.

This verifies Activity recreation with the retained runtime and local peer.
Whole-process death, account/team migration and live Pixel/Mac behavior remain
separate acceptance work. The main/test APK build passed in 42s; the local receipt
`captures/runtime/workspace-offline-restoration/verification.json` records hashes.
Signed build 517 is unchanged.

## Remaining acceptance

Whole-process restoration and production account/team transitions, physical Pixel/Mac
metadata changes, broader row visuals and full source/UI parity remain separate
acceptance work. This scoped feature does not establish completion of the app's
production account/network graph or overall goal. Signed build 517 is unchanged.
