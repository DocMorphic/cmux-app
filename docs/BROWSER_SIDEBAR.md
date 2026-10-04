# Sidebar in the on-device browser

## Architecture

The separate `:browser` Activity now uses `NativeWorkspaceShell` at the same
840 dp width / 480 dp height breakpoint as the main screen. The main process
provides the global Mac/SSH workspace and notification display projection through
the existing same-UID, presentation-bound Messenger service. The browser receives
no pairing records, SSH credentials, RPC clients, or filesystem authorities.
This continues the scoped iOS shell reference in `WORKSPACE_SIDEBAR.md`; it does
not advance the global upstream parity pin.

The projection shares main-screen workspace sorting, search metadata, group
ordering and notification grouping policies. Computer selection, separate search
queries, unread filtering, local group expansion and notification expansion are
available while the webpage remains mounted. The controller reads only while the
sidebar is visible and the Activity is foreground. It starts with up to 100 rows,
loads additional pages on request and refreshes the visible window. Each page has
a 192 KiB UTF-8 JSON budget and a consistent revision; stale responses and mixed
revisions are rejected. A missing scoped computer yields an empty list and an
explanation rather than silently broadening to All Computers.

Rows contain opaque salted keys. The host issues a one-use navigation ticket only
for a transmitted destination that still resolves. On Activity return it checks
the presentation owner and destination again. Pairing replacement, removed rows,
SSH endpoint/key/generation replacement and changed account/team cannot redirect
an old selection to a new authority. The main provider keeps the captured owner
even when Compose updates its callbacks. Search/tab/computer/unread presentation
is adopted back into the parent on an accepted return.

A retained feed lease keeps all authorized native computer feeds active only
while the browser sidebar is visible. It is separate from the browser's own host
lease. Closing, hiding, backgrounding and process exit release/deactivate the
appropriate lease; the stable session salt survives parent Activity recreation.
SSH sidebar exits suppress the old browser route's completion callback so it
cannot clear a newly selected destination.

## Remaining parity and verification scope

This is the browser's global **navigation** sidebar. Its workspace rows and group
headers reuse existing components. Compound machine filtering, independent
workspace/notification searches and unread filters, sort mode and computer-order
editing are now shared with the main screen. Remote row mutations, New
Task/settings entry points, full notification row presentation/actions and
selection styling still need parity work. Main-screen controls remain implemented separately; this does not establish
that the separate browser has every iOS sidebar affordance.

Physical Pixel/Mac acceptance, actual account/team replacement during live
browser use, real native/SSH feed integration with the new browser sidebar,
large-text/accessibility review and authenticated process recovery remain open.
The compact browser stays stacked. Signed build 554 predates these changes.

## Verification

Evidence for this batch is under ignored `captures/runtime/browser-global-sidebar/`.
The JVM suite covers page byte limits/order, invalid revisions, issued-row and
one-use ticket gates, lost destinations, display-only serialization, search
isolation, hidden/background polling, stale responses, missing computer scope,
and stable keys across host recreation.

The first runtime batch passed the existing compact pane-return and rotation
checks, but failed two new sidebar checks (132.758 s). The unread marker was
concatenated into the notification's text; it is now a separate decorative node
with Read/Unread semantics. The other failure occurred when the test's bare
`ComponentActivity` was recreated on return after resizing; unlike `MainActivity`,
that harness cannot reconstruct its injected Compose content. Device lifecycle
logs confirmed destruction/recreation at return. The wide tests now configure
window size in an outer rule before launching the harness and restore it after
cleanup. They do not claim parent Activity recreation coverage.

### Final batch — 2026-10-04

- **33 JVM checks passed:** 17 sidebar/protocol/controller checks, nine shared
  ordering checks and seven shared search checks.
- **Four Android checks passed in 73.398 s** on the sole existing
  API37/16KB AVD: wide sidebar hide/show with an unsent webpage draft and validated
  workspace return; live paused-parent updates, stale destination rejection and
  notification return; existing compact browser/pane return; compact rotation
  preserving the draft, page history and host lease.
- Wide harness: 2400×1600 at the original 420 dpi, established before Activity
  launch. Compact checks: 1080×2400 / 420 dpi. Display settings restored afterward.
  The captured final wide screenshot was inspected; this uses a generated sidebar
  provider and private HTTP fixture, not a physical Mac/Pixel or live SSH account.
- Installed app hash matched the built APK; source hashes matched the frozen
  sources; crash buffer was empty. The emulator was stopped and reaped. No new AVD
  was created, no physical device was connected, and no signed build was dispatched.
- App APK SHA-256: `0f019c891717d33d5df0ff99bae3fb2d06c1363b40f982a3b199df52edd32418`.
- Test APK SHA-256: `032dcf80ba67700afe7ee41e303b904fe827a89161f95f87200913a159933690`.
- Evidence: `verification.json`, `source-hashes.json`, `android.log`,
  `sidebar-final.png/xml`, `display-before.json`, `display-after.json`, and
  `crash-final.log` under the ignored batch directory.

Build history is retained there: initial compile found an incorrect named
parameter (18 s); the first JVM run exposed swapped name/device fixture arguments
(1m32s); corrected APK/JVM build passed (1m33s); IPC-bound check build passed
(27 s); window-test setup build passed (18 s); accessibility/harness repair build
passed (40 s). No emulator ran during these builds.

## Shared filters, sorting and return state — 2026-10-04

Scoped iOS reference remains `0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc`:
`WorkspaceListFilterState.swift`, `WorkspaceListFilterControls.swift` and
`MobilePrimarySearchCoordinator.swift` in `Packages/iOS/CmuxMobileShellUI`.
Their shared session state, independent search scopes and composable read/machine
filter are applied to the browser's separate process.

The browser now sends both queries and both unread filters plus opaque selected
machine keys. It adopts all of them back together. A dedicated final state message
runs when leaving the browser, including after hiding the sidebar, so an edit does
not depend on another visible feed poll before return. Removed machine choices
are pruned using the same policy as the main screen; a scoped title picker clears
hidden machine-filter choices. Pruned choices do not reappear on a later poll.

The shared filter menu exposes Last Opened, Custom Order and Recent Activity for
All Computers. The existing computer-order sheet supports drag and accessibility
moves. Sort writes run in the main process against the existing local preference
store, behind the presentation/owner check. Order submissions must contain exactly
the issued/current computer keys; stale, missing, duplicate and foreign keys are
rejected. No host RPC is issued for local sorting. Browser save requests are
serialized, and action failures remain visible across successful feed refreshes.
The order sheet shows save progress/errors and resets its draft after a rejection.

Machine-filter metadata reuses the bounded computer display list rather than
repeating names/build labels. Every IPC page retains its byte/revision checks.
The schema is internal to the same installed APK's two processes.

The first verification batch passed **45 JVM tests** (25 sidebar, nine sorting,
four filtering and seven search) and **five Android tests in 78.018 s**. Build
and APK packaging passed in 1m34s. The new browser test uses the production sidebar
projection with generated two-Mac snapshots: compound filtering, saved mode/order,
both search scopes, both unread filters and return after hiding an active search.
The other checks cover browser draft retention, stale destination rejection,
shared-editor drag/accessibility actions and sort controls without machine choices.
This remains fixture evidence, not live Mac/account/SSH or physical Pixel proof.

The initial order screenshot was taken while rows still appeared in the prior
order despite the persisted preference and underlying list having changed. A
focused follow-up now also requires the displayed row positions to match before
capturing the editor; its outcome is recorded below. Initial verification and
frozen-source receipts are under `captures/runtime/browser-sidebar-controls/`.

The focused repeat passed **one Android test in 45.243 s**, after a **20 s**
test-APK-only build. It now requires Mac B's rendered reorder handle to be above
Mac A's, and the inspected `computer-order-verified.png/xml` shows that order.
App and JVM sources were unchanged from the five-test batch; this is a repeat of
one of those five scenarios, not a sixth distinct test. Installed app/test hashes
and frozen source hashes matched. Display settings were restored, the crash
buffer was empty, and the sole emulator was stopped and reaped.

- App APK SHA-256: `96945c1f34bd1a88691af2510b70529d327dc62f4d05e60e604b1b067c51dddf`.
- Final test APK SHA-256: `5f7dae661303b3cac1dd108c95d85d4c8beca24d698ba4e0a5f092cd2017ec27`.
- Receipts: `verification.json`, `verification-visual.json`,
  `source-hashes-visual.json`, `build.log`, `test-build-visual.log` and runtime
  logs under the ignored `captures/runtime/browser-sidebar-controls/` directory.

No physical Pixel was connected or modified. Real NativeScreen account/native/SSH
integration and parent recreation remain unverified by this fixture. Signed build
554 is unchanged; PR #1 remains draft and the full parity goal remains active.
