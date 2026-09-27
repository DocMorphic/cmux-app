# Android parity tracker

Reference: [cmux iOS](https://cmux.com/ios) and
[`manaflow-ai/cmux`](https://github.com/manaflow-ai/cmux) at
`4d3385b9d7ac80a9bbdf5c886cc276849b1e4fa0` (2026-09-26).
The upstream code changes frequently, so update this pin before a release.
The iOS implementation lives mainly in `ios/cmuxPackage`,
`Packages/iOS`, and `Packages/Shared/CMUXMobileCore`.

The target is the real iOS companion workflow on a Pixel 6a (Android 17).
A feature is complete only after the Android behavior is implemented, covered
by a focused automated check where practical, and exercised against the Mac.
UI resemblance alone does not count.

| Area | iOS source / contract | Android status | Acceptance check |
| --- | --- | --- | --- |
| Account | `MobileAuthComposition`, `MobileRootAuthGate` | OTP sign-in and encrypted token refresh coded; unverified on phone | Sign in with the Mac's cmux account; restore session after restart; sign out |
| Computers | `MobilePairedMac`, `MacComputerListSection` | Encrypted saved-Mac list, select, forget, and reconnect coded; notification identities include device and installation as well as route; unverified on phone | Discover, choose, forget, and reconnect multiple Macs |
| Pairing | `CmxPairingQRCode`, `CmxAttachTicketCompactCoder` | QR scan, deep link, and current v2 Tailscale code path coded; unverified on phone; v3 Iroh parse only | Scan official Mac QR, validate version/identity, authorize exact route, revoke |
| Transport | `CmxNetworkByteTransport`, `MobileCoreRPCSession` | Framed RPC, VPN-bound 100.64/10 route resolution, and reconnect coded; unverified on phone; Iroh missing | Persistent framed RPC over authorized Tailscale; Iroh route; reconnect without duplicate input |
| Workspaces | `MobileSyncWorkspaceListResponse`, `DeviceTreeView` | Native list, sections, previews, colors, compact toolbar, computer picker, unread filter, workspace/terminal/browser creation, rename, pin/read, close, group create/rename/pin/ungroup, and move RPCs coded; anchor hierarchy, durable empty headers, saved collapse state, and bounded drag reordering coded; numeric unread badges and common group icon equivalents coded; arbitrary custom SF Symbols and phone QA pending | Live hierarchy, add/rename/close/reorder/group, status and selection |
| Terminal | `MobileTerminalRenderGridFrame`, `GhosttySurfaceView` | Styled render-grid with grapheme cell placement, semantic colors, wide cursor, delta/screen continuity and bounded local scrollback verified with JVM/emulator fixtures; actual viewport reporting/clear coded; native VT fallback, hybrid delivery, byte-gap recovery and screen-anchor negotiation verified with JVM/emulator fixtures and a captured Vim session; iOS-style View as Text with native selection/copy, cell tap, coalesced wheel RPCs and host viewport scroll responses verified with emulator fixtures; complete Ghostty fidelity, kinetic scrolling/inline graphics, and phone resize QA missing | Stream render grid or VT bytes; colors, cursor, Unicode, alternate screen, scrollback, resize |
| Input | `TerminalInputTextView`, `MobileTerminalInputResponse` | Native multiline paste/submit, encrypted per-Mac/terminal drafts, pending-send guards and acknowledgement reconciliation verified with an emulator fixture; direct IME keyboard, Unicode composition, repeated deletion, ordered input and explicit recovery from rejected delivery verified with an emulator fixture; modifier/navigation/control toolbar and hardware keys coded; photo/file picker, encrypted attachments, image paste and chunked file upload verified with an emulator fixture; rich keyboard paste, complete mode handling, and phone QA missing | Soft and hardware keyboard, modifiers, paste, image/file input, shortcuts, safe retry |
| Notifications | `NotificationFeedView`, `CmuxAppDelegate` | Native in-app feed, workspace/source/preview/time rows, read sync, provenance-based moved-terminal navigation and per-Mac view state coded; search/navigation verified with emulator fixtures; encrypted per-pairing alert identity and exact terminal routes, independent saved-Mac workers, read/forget cleanup and host-identity checks coded; combined saved-Mac feed, day/history grouping, unread filter, pull refresh, read/unread gestures and confirmed bulk read coded; offline snapshots and revision guards tested with loopback peers; event/mutation revision floors, bounded refresh retries and Activity-recreation retention coded; computer picker, scoped unread badge/bulk actions and live-destination filtering before the global cap coded; server push fallback and phone QA pending | Feed, unread counts, actions, deep links, Android background delivery, read sync |
| Browser | `CmuxMobileBrowser`, `MobileBrowserFrameEvent` | JPEG/PNG stream, navigation, tap, scroll, text, and dialog RPC paths coded; downloads and phone QA missing | Show browser panels; navigate, scroll, tap, type, handle dialogs and downloads |
| Search | `MobilePrimaryTabScaffold`, `MobilePrimarySearchCoordinator` | Independent workspace/notification queries, bounded Unicode editing, group/computer/description and notification metadata matching, submit/clear and result navigation verified with JVM/emulator fixtures; cross-computer notification search and exact target navigation verified with emulator fixtures; iOS 26 primary-tab structure with separate Search control, cancel/submit lifecycle, unread badge and floating New Task entry coded; cross-computer workspace aggregation coded; phone QA pending | Search workspaces and notifications with matching navigation |
| Changes | `CmuxMobileChanges` | Changed-file list and bounded unified diffs coded; file content actions and phone QA missing | View changed files and diffs from the active workspace |
| Tasks and agents | `CmuxAgentChatUI`, task composer in `CmuxMobileShellUI` | Built-in Claude/Codex/OpenCode/Shell templates, live model/effort choices, scoped discovery/cache, encrypted saved drafts with stable retry IDs, completed-operation refresh/start-again recovery and new-workspace task RPC coded; editable templates, task attachments, chat UI, full composer styling and phone QA missing | Create and navigate tasks; handle agent prompts and attachments |
| Settings | `MobileSettingsView` | Account, saved-computer, background notification, terminal size, and connection status controls coded; full network diagnostics and reset missing | Account, computers, notification, display, network, diagnostics, reset |
| Device behavior | iOS lifecycle, accessibility, background push | Keyboard resizing verified with an Android 17 emulator fixture; visible terminal text exposed to accessibility; broader lifecycle and phone QA missing | Rotation, keyboard, process death, offline recovery, screen reader, battery |
| Delivery | iOS release checks | Native debug/release builds and stable signing verified in CI; Pixel run pending | Stable signed APK, upgrade in place, reproducible CI, Pixel acceptance run |

## Automated evidence (2026-09-27)

142 JVM tests pass. The Android suite now contains 52 cases: twenty-two Compose
flows, seven task model/submission controls checks, five task draft checks, six completed-task recovery checks, one
explicit two-process draft check, two workspace drag checks, three Activity-recreation
checks, four notification/service checks and two
renderer checks. Earlier checks have passing evidence across suite and focused
runs. The All Computers workspace flow passes separately; nine navigation,
notification, lifecycle and terminal-entry cases passed for the preceding primary
navigation changes, including a focused rerun confirming Search keyboard visibility. They run on Android 17
(API 37) ARM64 emulator using the Pixel 6a display profile. The checks cover unread
filtering, terminal output appearing, keyboard-driven viewport reduction,
exactly one paste/submit request, and viewport cleanup on return to workspaces.
The composer flow also checks multiline preservation, separate terminal drafts,
encrypted persistence before sending, a disabled send action while an
acknowledgement is pending, retained text after rejection, explicit retry, and
insertion without submission. JVM checks cover delayed acknowledgements,
newer edits, Mac isolation, interrupted-send restoration, and sign-out cleanup.
The attachment flow checks the Android document-picker callback, 2048-pixel
image preparation, encrypted payloads and restored metadata, image acknowledgement,
file retention after rejected text, stable upload IDs on explicit retry, and cleanup.
The picker result is intercepted with local fixture files; cloud document
providers and a real Mac are not exercised. JVM checks also cover multi-chunk
byte fidelity, shell quoting, stopping on connection changes, attachment removal,
and resource budgets. The direct-input flow calls Android InputConnection APIs
through the actual native keyboard view and RPC client, checking uncommitted
composition, UTF-16/code-point deletion, control/navigation/function keys,
rejected delivery without queued replay, explicit resume, preserved composer
drafts, and rejection of a stale keyboard connection after switching terminals.
The on-screen keyboard and resized terminal were visually inspected. This does
not prove live vim/htop behavior, physical keyboard layouts, or every Android IME.
The rendering checks compare actual pixels from mixed Unicode spans against
separate cell placements, exercise device ICU cluster boundaries and widths,
and verify invisible glyphs, indexed backgrounds, and a wide underline cursor.
JVM cases cover alternate-screen/full-frame fences, stale revisions, carried
burst scrollback, resize history invalidation, semantic theme colors and
accessible column gaps. The rendering bitmap was visually reviewed.
The raw-stream flow checks split UTF-8 and ANSI bytes, duplicate suppression,
alternate-screen restoration, forced-gap recovery, scrollback/Latest without a
viewport resize, and suppression of parser-generated input. JVM checks add
transport selection, hybrid transitions, malformed/overflowing sequences,
bounded pending output, grid-to-VT seeding and actual captured Vim output.
The touch/copy flows check an immutable local copy sheet, exact Copy All text,
Android selection handles and selected-text copying, menu/long-press entry,
suppression of remote scroll on a screen-anchored primary screen, alternate-screen
wheel direction, cell coordinates and workspace/surface/client scoping, and a
viewport-anchored host's returned scroll frame. JVM cases cover shared hit-test
geometry, slow-response coalescing, prefetch cadence, cancellation, uncertain
scroll delivery, copy history boundaries and trimming blank screen tails.
Search/navigation checks cover independent tab queries, submit/cancel semantics,
bounded scalars without broken surrogate pairs, accent/width folding for
notifications, group/computer/description/notification metadata matches, and
returning from a search result with the committed filter intact. Moved-terminal
notifications resolve to the live owner only with the host provenance flag;
unresolved or ambiguous workspace destinations do not mark a notification read. Decoder cases
cover nullable metadata, duplicate IDs and redundant row content. The emulator
exercises moved-surface RPC scoping and an unavailable destination, and captures
notification search and its error state. Additional alert checks use two local
fixture Macs for exact saved-pairing and terminal navigation, cancellation of a
superseded initial feed handshake, repeat opening and
forgotten-Mac rejection. Delivery tests exercise real Android notification and
PendingIntent identities, Keystore-backed destination restoration, per-Mac read
cleanup, sibling cmux installation identity, foreground-service lifecycle and
stale-feed suppression. The production feed worker's invalidation and disconnect
path runs against a framed TCP peer. The combined foreground feed independently follows every saved Mac while the UI
is started, retains the latest snapshot during a disconnect, and closes its feed
sessions when the UI stops. It merges at most 2,000 items, sorts deterministically,
and uses a 300-item row window with load more. Projection tests cover local day
boundaries, daylight saving, contiguous same-pane groups within two hours,
filter-before-grouping and expansion/anchor retention. Coordinator tests cover
read/unread revision fences, offline bulk-action failures and replacement Mac
identity. Emulator flows cover expandable history, the unread filter, long-press
and swipe actions, bulk confirmation/cancel, identical IDs from two Macs, computer
search, exact terminal navigation and bulk actions despite a narrower search.
Computer filtering and live-destination visibility are applied before the global
cap, preserving older valid rows. Source snapshots remain intact, including
hidden notifications. Bulk read targets the captured computer scope and includes
its retained rows hidden by search; an unknown/forgotten captured scope cannot
widen to all computers. Selection persists in the encrypted account store, is
restored on Activity recreation, and falls back to All Computers after removal.
All Computers now aggregates saved-Mac workspace/group snapshots. Workspace and
group keys and local group expansion include the owning pairing origin; search
includes the owning Mac and uses the same scope as the computer picker. Each
workspace action routes to that exact background session, re-resolves the latest
window/group scope, and refreshes the owning list after success or rejection.
Unavailable or forgotten owners never fall back to the foreground Mac. Workspace
snapshots advance independently of notification revision fences. Opening a row,
its changes or an exact browser/created terminal promotes the owning foreground
connection and revalidates the route and fresh destination before navigation;
workspace navigation never marks a notification read.

Workspace unread counts preserve the host's optional 64-bit count. Expanded
headers show the anchor's count; collapsed headers aggregate all members, and an
unknown contributor keeps the aggregate unknown. Unread states with unknown or
zero counts display the iOS minimum badge of 1. Read rows retain their gutter so
icons do not jump. TalkBack receives the state/count on the row; decorative
badges and glyphs are excluded from duplicate announcements. Overflowing remote
aggregates remain unknown rather than wrapping into a negative count.

The focused Android 17 unread flow passes with actual Compose controls and the
framed loopback RPC client: expanded/collapsed counts, legacy unknown counts,
read/unread refresh and search flattening. It passed together with three existing
hierarchy/drag regressions (four tests total, 80.235 seconds). Expanded/collapsed
screenshots were visually inspected. The same badge flow also passed at 150% system text size (62.47 seconds),
with expanded and collapsed screenshots reviewed. Other accessibility sizes and
physical Pixel behavior remain unverified.

Group headers use a folder or a common custom-symbol equivalent, a separate
collapse chevron, and a pin next to the name. Lucide sources, hashes, ISC/MIT
notices and the conversion script are committed. These are Android vector
shapes, not Apple's SF Symbol glyphs: unknown symbol names currently fall back
to a folder. Complete custom-symbol fidelity remains open. The existing cmux
brand logo remains the upstream asset.

Workspace hierarchy now preserves spatial host order, represents anchors only
through group headers, indents children, aggregates unread visibility on collapse,
and retains empty headers in their pin tier. Collapse overrides are persisted per
Mac/group. Long-press reordering and edge scrolling use explicit inside/outside
group slots, with accessible Move up/Move down actions. Only an unfiltered,
connected, single-window/single-computer list can reorder. Up to three predictions
may be pending; writes are serialized and scoped to the verified owner/window.
Rejection, owner removal, external order changes, and changed pin tiers invalidate
dependent predictions. Live titles, previews, created rows and deleted rows remain
current while order is predicted. A fresh move can proceed after a rejected chain.

The pinned unmodified Swift algorithms generate 46 reference snapshots, 2,434
rendered drops, and 20,360 direct proposals. JVM tests compare hierarchy, canonical
RPC intents, and predicted ordering against those reference results, including
empty/promoted groups, collapsed groups, noncontiguous membership, pins and
invalid targets. Two empty-group defects were found and corrected by this
comparison. Loopback RPC tests exercise delayed acknowledgements, intermediate
snapshots, the three-move bound, rejection, external pins, forgetting and stale
window/owner guards. On the Android 17 Pixel-profile emulator, the full hierarchy flow opens the
anchor, preserves collapse across navigation, drags a row through the actual RPC
client, verifies the new order and disables reordering in search. A held gesture
moves beyond recycled source rows using edge scrolling; an accessibility check
exercises Move down and its disabled state. The first runtime pass exposed a
conflict with LazyColumn touch scrolling; disabling touch scrolling while a row is
held fixes cancellation while retaining programmatic edge scrolling. All three
new checks pass in focused runs. Filtering/terminal keyboard entry, scoped search
and multi-computer workspace mutation/navigation also pass for this batch.
`workspace-hierarchy-reordered.png` was visually reviewed. Initial interrupted
or failed diagnostic runs are not passes. Physical Pixel testing remains pending.
Connection cleanup captures the specific composed client; route effects capture
the same session as their keys and commit keyboard/navigation changes on the
Android main thread after revalidating the route.
Legacy alert identities without device/installation binding are retired on
upgrade; saved accounts, pairings and terminal drafts are retained.
The pinned iOS implementation retains feed snapshots in memory and clears them
with account state; it does not persist the feed to disk. Android follows that
model: a ViewModel retains snapshots and expansion across Activity recreation,
while saved tab/query/filter and loaded-row-window state is restored and a new process fetches fresh
content. Opaque Android alert routes remain encrypted on disk independently.
System-shade taps through actual process death, boot/sleep reliability, server
push fallback and physical-device navigation remain unverified or incomplete.
Screenshots were inspected with and without the keyboard. This caught and
fixed terminal background overdraw, skipped render updates, and a false
cancellation error during resize. It uses a local RPC fixture; live Mac auth,
pairing, real terminal applications, and the physical Pixel remain unverified.
See [ANDROID_TESTING.md](ANDROID_TESTING.md) for reproduction.

## Navigation presentation

The primary scaffold follows the pinned iOS 26 structure: Workspaces and
Notifications in a rounded tab group, a separate round Search control, a
floating New Task action on Workspaces, and Settings through the cmux logo.
The selected tab and unread badge have explicit Android accessibility semantics.
Search temporarily replaces the tab group with an editor and cancel button;
submit/result navigation commits its scope, while cancel/app Back clears it.
Committed filters survive tab/terminal navigation and Activity recreation.
The Android implementation uses Compose-drawn translucent surfaces and native
Android focus/IME handling. Apple's system glass material is not reproduced by
this implementation; final material, typography and physical-device visual QA
remain open.

## Work order

1. Make pairing usable and durable: encrypted credential storage, QR scanner,
   state restoration, and connection recovery. The helper remains an interim
   transport while the native cmux account and pairing path is validated.
2. Port the official pairing/ticket/frame/RPC contracts and replace the helper
   with the Mac's mobile endpoint. Verify against a pinned cmux build.
3. Finish terminal rendering and the full input contract, including remaining
   Ghostty fidelity, kinetic scrolling, live mouse reporting and inline graphics. Use vim, htop, Claude Code, and Codex as acceptance cases.
4. Complete workspaces, notifications, browser, search, changes, tasks,
   settings, accessibility, and background behavior.
5. Run the parity checklist on the Pixel and ship a stable signed APK.

The current Android build is not a parity release. Keep this table honest as
implementation and physical-device verification progress.

## Task model and effort contract

Task creation probes `mobile.task.models.list` for the selected provider and
fetches the cmux `/api/agent-models` catalog concurrently. A cold catalog can
populate the picker while Mac discovery is pending; nonempty discovered Mac
results (including default-only metadata) take priority. Cache identity includes
the exact pairing origin and provider, and account clearing or forgetting a Mac
fences stale refreshes. Transient failures preserve previously usable data.
Discovery retries while the composer is open with the iOS 500 ms–15 s capped
backoff; cancellation, provider unavailability, unsupported/disabled RPCs, authorization
failures, account mismatch and invalid requests stop it. Structured RPC error
codes are preserved without closing an otherwise usable connection.

The model menu captures its presented choices so a delayed Mac catalog cannot
replace an option while the user taps it. A chosen model survives delisting.
Efforts come only from the selected model or the Mac's Default metadata; a
Default model choice adds no explicit model argument. Claude `--effort`, Codex
`-c model_reasoning_effort=`, and OpenCode `--variant` values are quoted with the
upstream provider algorithm. Prompts remain in `CMUX_TASK_PROMPT`.

The command port is compared with 695 results from the pinned unmodified Swift
provider implementation, including repeated/existing flags, quoted arguments,
end-of-options, command boundaries, redirects, Unicode and embedded apostrophes.
JVM checks cover discovery completion order, default-only host results, stale
refreshes, forgetting/sign-out, owner cancellation, timeouts, and selected model/
effort reconciliation. This does not verify the installed agent CLIs on the Mac.
Editable templates, task attachment flows, full composer layout parity and agent
chat remain open. Saved drafts and completed-operation recovery are described below.

The Android 17 Pixel-profile emulator passes all five task controls flows and the
existing primary navigation/search/composer-entry flow together (six tests,
175.423 seconds). The five task flows use the production Compose/RPC path with a
synthetic Mac and injected catalog. They verify explicit model/effort submission,
Default, plain Shell, older-host fallback, and prompt/operation-ID retention
after a timeout followed by explicit retry. The final screenshots were inspected.
This remains separate from actual
Mac agent launch, task recovery and physical Pixel acceptance.


## Task submission identity and response handling

Task submission now compares the effective `workspace.create` parameters and
exact Mac pairing origin. Prompt or directory whitespace that produces the same
request keeps the retry ID. An effective command/environment/destination change
gets a distinct ID; reverting an unsent edit returns to the submitted baseline.
This follows `MobileTaskSubmissionIdentity` and `MobileTaskSubmissionSnapshot`
at the pinned upstream revision. Caller JSON mutations cannot alter the baseline.

The task composer requires a nonempty `created_workspace_id` identifying a unique
workspace in the returned list. Incomplete or malformed responses keep the prompt
and retry identity visible. A valid partial creation response updates existing
rows and appends new workspaces without dropping unrelated rows. Group metadata
is retained until a full list refresh, matching the iOS merge path. Navigation selects
the returned workspace and terminal, clearing any prior browser selection.
Requests are fenced to their original client and Mac: a late response after
connection replacement cannot navigate or apply a workspace list to a new session.

The initial submission batch covered a mounted composer session. Durable drafts
and capability gating are described below. Completed-operation reconciliation is
covered in the recovery section; physical-Mac agent launch remains required.


## Saved task drafts

The task composer owns a durable draft identity independent of its view or RPC
connection. Its Drafts sheet lists other drafts newest first, supports resuming,
deleting and starting a new draft, and saves the active draft before switching.
Leaving edited content offers Save Draft, Delete Draft and Keep Editing. Empty
selection-only drafts are omitted; a submitted untitled Shell keeps its retry
record. The collection retains the newest 20 nonempty drafts, matching iOS.

Prompt and directory text, built-in agent selection, explicit model metadata,
Default-model effort metadata and the last composed request are encrypted with
the Android Keystore account store. Autosaves are ordered and conflated, background
and disposal request a flush, and task creation waits for a durable request/ID
write before any RPC. Restoring equivalent content reuses the persisted ID;
changing effective parameters resolves a different operation. Successful creation
removes only its own draft. A failed local save prevents transmission.

Account login incarnations and per-editor leases fence stale writers. Sign-out
removes task drafts, and a delayed old token refresh cannot overwrite credentials
or clear a newer account's collection. Delayed model reconciliation is also fenced
to its originating provider. A resumed draft selects its saved computer and waits
for that exact pairing connection before mounting the composer. Task creation
requires the host's `workspace.task_create.v1` capability.

Remaining task work includes editable templates, attachments, destination group/directory/name controls,
offline composer editing, draft rebinding after a pairing code changes, full
composer styling and agent chat. Saved drafts remain bound to the saved pairing;
they are not automatically redirected to a different or renewed pairing. Physical
Pixel/Mac verification remains required.


## Completed-operation task recovery

A normalized `already_completed` creation error preserves an immutable copy of
the accepted request and immediately retires its normal retry ID. The anchor and
fresh identity are stored with the encrypted draft. Equivalent requests cannot
use Create Task. Refresh Workspaces refreshes the full workspace/group listing,
then explicitly reconciles using the old operation ID and exact original request.
A successful response follows the existing validated workspace/terminal navigation
and removes only the recovered draft. No automatic retry is sent.

If reconciliation again returns `already_completed`, Refresh Again remains
available and Start Again opens the same duplicate-task confirmation as iOS.
Only confirmation submits the new operation ID. A list-refresh error, disk-save
error or unrelated RPC error cannot authorize that action. Replacing the client
or editor prevents a late result from installing recovery or navigating.

Late discovery preserves the submitted model/effort snapshot during recovery.
Effective edits detach the recovery controls; reverting to equivalent content
restores them. A genuinely different submitted request clears the old anchor.
Cold restoration preserves the anchor and retired identity and requires refresh
again, matching iOS's restored recovery phase. Older encrypted drafts remain
readable. Persistence and RPC checks use the same account/editor/origin guards
as normal task submission. Physical Pixel/Mac verification is still required.
