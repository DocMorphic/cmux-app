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
headers reuse existing components, but remote row mutations, compound machine
filter controls, sort/order editing, New Task/settings entry points, full
notification row presentation/actions and selection styling still need parity
work. Main-screen controls remain implemented separately; this does not establish
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
