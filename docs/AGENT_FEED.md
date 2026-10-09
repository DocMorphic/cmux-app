# Agent Feed companion

## Source and scope

This port is scoped to official iOS source at
`186cec79781256867ad4516f0802118738bd2393`:

- `Packages/iOS/CmuxMobileRPC/Sources/CmuxMobileRPC/MobileAgentFeedListResponse.swift`
- `Packages/iOS/CmuxMobileShellModel/Sources/CmuxMobileShellModel/MobileAgentFeedItem.swift`
- `Packages/iOS/CmuxMobileShell/Sources/CmuxMobileShell/MobileShellComposite+AgentFeed.swift`
- `Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/AgentFeed{View,Row,Projection,RowModel,QuestionAnswerComposer,ReplyComposer,FullTextView}.swift`
- `MobilePrimaryTab.swift`: Workspaces, Feed, Notifications, Cloud and Search.

This does not advance the global upstream implementation/review pin. The previous
Android `NativeFeed*` implementation was the notification/workspace aggregator;
it did **not** implement the distinct agent workstream Feed.

## Implemented behavior

- A primary Feed tab, with its own committed/draft/generation-bound search, the
  current computer selection, activity rows and All Activity / Needs Input filter.
- The main navigation hides Notifications by default, with the source's persistent
  **Legacy Notifications Tab** Display switch to bring it back. Turning that off
  while Notifications is selected moves to Feed and commits the outgoing search;
  separate destination queries and Feed drafts are retained. This applies to
  compact, wide and Cloud main navigation and now to the separate browser sidebar
  through its shared timeline and main-process display preferences.
- Only verified paired Macs advertising `feed.v1` get `feed.list` reads and a
  `feed.changed` subscription. Feed shares that Mac's existing authenticated
  connection. It has a separate refresh loop/revision watermark so notification
  revisions cannot suppress agent updates. Stale in-flight snapshots cannot
  overwrite newer invalidations. Failures allow a later equal-revision event to
  retry; repeated stale responses use the bounded existing refresh ladder.
- Row-tolerant strict wire decoding, per-Mac and global 400-row limits, Unicode
  byte bounds, deterministic ordering and inert forward-compatible unknown kinds.
  Notification-source history is excluded from this tab. Tool-use/user-prompt and
  successful tool-result events are retained for the model but hidden from the
  timeline, following iOS's notable-event projection.
- Duplicate stop deliveries are combined within the iOS 2-second exact / 120-second
  truncated-preview windows, scoped to the exact Mac build and workstream. Richer
  messages and recorded replies survive deduplication.
- Permission modes once/always/deny/all/bypass; plan modes manual/autoAccept/
  bypassPermissions/ultraplan/deny, with revision feedback using `manual`; multi-question and multi-select answer
  composition. Question answers submit human-readable labels in the displayed
  question order; custom text replaces preset choices.
- Each action rechecks the live item, owner and pending state. Duplicate aliases
  of the same request share single-flight admission. Confirmed decisions resolve
  locally while the authoritative snapshot refreshes. No mutation is automatically
  retried by the Feed session.
- Stop replies send `mobile.terminal.paste` with exact workspace/surface and
  `feed_event_id`, text and Return. Success requires `submitted == true`.
  Lost acknowledgements keep the draft with an unconfirmed-delivery warning.
- Full messages load through `feed.text`, with a pinned version, advancing UTF-8
  offsets, 16 KiB page / 8 MiB total bounds and connection checks around every page.
  The reader publishes only a complete message, using the existing bundled
  Markdown viewer and selectable source fallback.
- Open workspace/tab uses the owning saved Mac and currently listed destination.
  Read/triage actions remain separate from actual agent decisions. Baseline and
  the last 1,500 read keys persist under an account/team-scoped preferences key;
  transient triage overrides never answer or expire a pending request. Stops also
  record the turn key so a fuller duplicate does not become unread again.

## Acceptance still required

This implementation is not a declaration of full iOS Feed parity. The row
presentation follow-up below adds native inline Markdown, source-specific content,
swipe triage and Display settings. Remaining work includes broader full-text and
composer lifecycle, real TalkBack/RTL/large-text acceptance, and routed
browser/sidebar lifecycle acceptance. The dated runtime checks below cover specific fixture flows, not full
visual or physical acceptance.

Physical Mac/Pixel acceptance remains open for every decision family, terminal
reply outcomes, events during reconnect, revoked account/team scope and Mac-build
switching. Emulator fixtures do not establish real host compatibility. The global
source audit, signed delivery and other project completion gates remain open.

## Verification — 2026-10-07

- 78 JVM cases pass: 11 wire/session, 5 projection/read-state, 54 coordinator and
  8 search. Coverage includes a real framed local RPC peer proving the Feed shares
  the verified connection, does not subscribe unsupported Macs, and retires access
  when its exact owner is removed. Other cases cover stale/equal revision recovery,
  duplicate decisions, ambiguous terminal delivery, paged text, Mac-build
  deduplication and read/triage persistence semantics.
- The first expanded batch had one test assertion failure: substring matching
  mistook `notification.feed.changed` for `feed.changed`. The assertion now matches
  the complete JSON topic string. The corrected 78-case batch and debug/test APK
  build passed in 1m 4s.
- Local evidence: `captures/runtime/agent-feed/`, including build logs and APK
  hashes. Captures remain ignored rather than being committed.
- Actual Android main-shell flow: `NativeAgentFeedRuntimeTest` passed **1 case in
  108.102 s**, API 37 / 16 KiB, using the sole existing `cmux_api37_16k` AVD at
  2,048 MiB. It exercised Feed navigation/search, option-label question submission,
  permission approval, exact-event terminal reply, query preservation across the
  Notifications tab, and opening the owning terminal through the normal route.
  The captured timeline was visually inspected. This case does not exercise plan
  modes, full-text rendering, swipe/lifecycle gaps or a physical host.
- Startup caveat: Launcher, System UI and Media Provider ANRs occurred before the
  first attempt. The first instrumentation process failed during application
  binding with a 60.131 s startup ANR and no Java crash; no test result was produced.
  A retry of the **same APKs** passed. There were no new ANR/crash events during
  that successful attempt. Both attempts, exit-info and before/after event logs
  remain in the capture directory; this does not establish cold-start performance.
- Debug APK SHA-256: `0ed2e75641c99247b837f23142dbb19ace0694117d2aaa944a078d9dc4f7bea2`.
  Test APK: `03246041b32d237b46a9cf37f435d2542d87110633591012de9ac0f076f935af`.
  Gradle stopped before the runtime check; emulator stopped and reaped afterward.
  No signed APK was published and no physical device was available to ADB.

## Row presentation follow-up — 2026-10-07

The next source batch replaces the generic title/body fallback with the iOS
presentation rules: source-normalized author names, no redundant finished-turn
headline, workspace/cwd and optional tab, compact relative times, question preview
suppression, kind-specific output and quote selection, readable tool payloads,
old/double-encoded plan extraction, friendly decisions and reply-reference snippets.
Empty non-notable rows do not mount; todo activity follows iOS's checklist-update
headline (the source does not provide editable checklist controls in this row).
Tool/plan JSON envelopes are not shown as raw transport data. Truncated snippets
retain complete grapheme clusters using the existing task text helper.

Rows now have the existing agent-brand avatars, leading unread dots, a 40 dp avatar
gutter, three-line quote treatment, eight-line output and two-line tool previews.
Leading swipes use Done / Needs Input, retaining the existing full-swipe, RTL,
single-open-row and viewport-hold behavior. Explicit context-menu/TalkBack actions
provide triage and destination navigation. The Done action only changes local
triage, leaving a pending approval actionable. Destination actions move into the
row tap/context menu instead of a repeated action bar on every event.

`Show Tab in Feed` is a persistent Display preference, default off as on iOS.
Quote bubbles follow the audited iOS debug option: editable/default-on in debug,
absent/default-off in production; production uses the leading quote bar. The
reply-row follow-up below adds the source bubble-tail geometry; later inline-text
checks cover expansion placement and accessibility.

Inline Markdown uses [commonmark-java 0.30.0](https://github.com/commonmark/commonmark-java/tree/commonmark-parent-0.30.0)
with its strikethrough extension and an inline-only block adapter. Paragraph breaks,
block markers and outer whitespace are preserved while emphasis, strong emphasis,
code, escapes/entities, strike and links become native text runs. It does not create
WebViews per row, fetch images, or execute HTML. Link activation uses the same
http/https/mailto/tel policy as the existing full document viewer. A bounded 512-entry
cache serves repeated row text. The upstream Android test project targets API19
with AGP bytecode backports; this project's minSdk remains 26. No broad platform
compatibility claim is made from compilation alone.

These refinements have their own verification scope. The earlier 108.102 s emulator
receipt proves the preceding row implementation, not this changed presentation.


Verification for this follow-up: **30 focused JVM cases passed** (Feed model,
projection, Markdown, presentation, routed Display and shared swipe policies),
and `compileDebugAndroidTestKotlin` succeeded. The Markdown regression caught
blank-line/indentation normalization in CommonMark's document parser; the adapter
now gives the inline parser original lines through one synthetic block, with
explicit space/tab runs. Tests retain authored trailing spaces and formatting
across blank lines. Logs, including earlier failures, and passing XML receipts are
in `captures/runtime/agent-feed-presentation/`. No APK assembly, emulator or Pixel
run was performed for this batch; the new row layout remains visually unverified.


## Inline question controls and revision protocol — 2026-10-07

`AgentFeedQuestionControls` now follows the source's inline one-question-at-a-time
interaction. Multiple questions use a native horizontal pager with current-page
natural height, Previous/Next, direct page indicators, answered count and a final
Submit all answers button. Next remains available before answering; submission
requires every question. A single question gets inline options and Send.
Inactive pages hide accessibility descendants and disable their controls.

Full-width preset controls implement single choice or multiple choice, show
Markdown labels/descriptions and clear custom text on selection. Other… reveals
a focused multiline field; typing there clears preset choices. Wire answers use
trimmed custom text or labels in displayed option order, then question order.
Pending requests disable paging, choices, typing and submission. Drafts and page
position use Compose saved state under the row/request/question-content identity;
changed requests/content start fresh. The main Feed's account/team and computer
composition scope remains authoritative. Save-state behavior still needs broader
real process/account lifecycle acceptance beyond local fixture checks.

The source audit found that `AgentFeedReplyComposer.send()` sends plan revision
feedback as `mode: manual`, not `mode: revise`. Android now uses `manual` with
`feedback` and rejects the unsupported invented mode. A question decision also
checks that the displayed question content still matches the current RPC snapshot
before sending. This prevents old labels/custom answers from being submitted to
a changed prompt with an otherwise reused request identity.

The old all-questions dialog has been removed. Remaining Feed gaps include exact
inline See-more/bubble geometry, reply-composer quote/thread presentation, reader
and composer restoration, routed sidebar integration, large-text/TalkBack and
real Mac/Pixel acceptance. No scope-wide parity claim follows from this batch.

### Verification for inline questions

- **34 focused JVM checks** passed; debug and instrumentation APKs built.
- Three Android scenarios now have passing evidence on API37/16KiB: main-shell
  Feed/RPC navigation and inline question submission, draft/page saved-state
  restoration and gating, and nested horizontal paging with no row triage/open
  side effects plus variable page height. The first run was **56.785 s**, with
  two passing cases and one test-tag lookup failure; after a selector-only fix,
  that remaining case passed in **4.04 s** on the identical application APK.
- Screenshots of the main timeline and both question pages were visually
  inspected: controls/text/footer are visible without clipping. This provides
  initial runtime/visual evidence for the preceding row presentation batch too.
  Full font/accessibility/geometry acceptance remains open.
- Restored state used Compose `StateRestorationTester`; this is not an actual
  app-process kill or real account/Mac acceptance. Event logs contain no ANR or
  crash entries before or after these runs.
- Evidence: `captures/runtime/agent-feed-questions/`, retaining the first failure,
  both run receipts, build logs, XML results, screenshots and APK hashes.
  Application SHA-256: `6b46e1d15051a8bd602bffa50a7a3fa2b8420970a45a2007df0c5d6215e9df31`.
  Corrected test APK: `5435190c896a02827e4f7de3c3e3cc904e5a6cd4e61909c1194da65e4eec5789`.
- Sole existing AVD used at 2,048 MiB and stopped/reaped afterward; Gradle stopped.
  No Pixel was connected and no signed release was published.


## Reply sheet, plan actions and modal restoration — 2026-10-07

Plan rows now expose direct Approve, Revise, Deny and More approval modes as in
`AgentFeedExitPlanControls`. Approve forwards the agent's preselected mode (or
`manual` when absent); an unfamiliar advertised mode is not silently replaced.
Explicit menu modes remain manual, autoAccept, bypassPermissions and ultraplan.
The session admits an unfamiliar mode only when it equals the current event's
advertised default, while retaining normal request/pending/owner checks.

Terminal replies and plan feedback now use a bottom sheet with the quoted event,
agent avatar, author/headline, six-line preview, thread line, Replying to label,
focused multiline editor, keyboard Send and top Cancel/Reply/Send controls.
See more reads the complete `feed.text` result through the authenticated session;
it uses the bundled Markdown renderer rather than the 8 KiB timeline parser.
An expansion can be retried after read failure and falls back to selectable source
if the renderer fails. Sending still uses the existing single-flight session and
ambiguous-delivery handling. Plan feedback uses manual mode plus feedback.

`AgentFeedModal` saves only the account/team scope digest, exact Mac/build/event
and request/workspace/surface identity, mode, draft and presentation flags. It
never serializes pairing credentials or the loaded message. A recreated sheet
waits for the still-authorized Mac's initial Feed snapshot; authoritative event
removal, changed request/destination, successful terminal reply, resolved plan,
Mac/build removal or account/team change clears it. Restoration never sends a
reply automatically. Full-message reader mode and Markdown viewport coordinates
are saveable; message bodies reload and are kept out of the Activity Bundle.

This is not final visual or lifecycle acceptance. Expanded quotes currently use
a 320 dp independently scrolling document viewport inside the composer, while
iOS lays out its native Markdown in the outer scroll. Matching that continuous
scroll behavior, source icon/chrome details, exact inline expansion geometry,
TalkBack/large fonts, real process death during loading and actual account/Mac
recovery remain open. A Compose saved-state fixture does not prove a physical
process-death or authenticated production workflow.

### Verification for reply/modal work

- **40 focused JVM cases passed**, covering saved identity/draft encoding, scope/
  removal/replacement fences, initial-snapshot waiting, preselected approval mode
  forwarding and the existing Feed transport/projection/Markdown cases.
- **All five Android cases passed in 58.916 s** on API37/16KiB: reply draft saved-
  state restoration through snapshot reload with no auto-send, reader reload/raw
  mode and account cleanup, plan revision feedback, explicit approval-mode menu,
  and main-shell question/permission/terminal reply/navigation via loopback RPC.
  The first four-case attempt had a duplicate-text test lookup failure; it is
  retained with the corrected run, not counted as passing.
- A blank early expansion screenshot exposed insufficient DOM/visual-callback
  assertions. The strengthened test now checks the tail beyond 8 KiB and actual
  light-text pixels within native WebView bounds, saving that exact bitmap. It
  passed **one case in 12.465 s** on the unchanged app APK. The painted screenshot
  visibly contains the expanded report; the reply screenshot shows the typed
  draft and keyboard. No fixed rendering-latency claim follows from this result.
- Evidence: `captures/runtime/agent-feed-composer/`, including all runs, early
  frames, painted frame, geometry, 40 passing XML cases, build logs and APK hashes.
  No ANR/crash events occurred. AVD and Gradle stopped; no signed release or Pixel
  run. StateRestorationTester evidence does not replace actual process-death or
  authenticated Mac/Pixel acceptance.


## Inline expansion follow-up — 2026-10-07

Feed output/tool previews and collapsed composer quotes now measure the rendered
Markdown using the available width and current font settings. They reserve space
on the final visible line for an inline `… See more` action. Text shortened by the
host exposes the same action even when the received preview fits. Prefix slicing
preserves Markdown spans and URL annotations; Android ICU character boundaries
keep emoji sequences, flags and combining marks intact. The expansion uses a
separate native Compose link action, and callbacks stay current after recomposition.
Disabled previews have no expansion link action.

The preview has a 48 dp minimum height when expandable. When an extreme font/width
combination cannot fit even the control within the requested line count, the
control wraps instead of becoming inaccessible through clipping. The composer
thread line is drawn after normal measurement; it no longer requests unsupported
intrinsic measurements from the width-aware preview.

Verification for this change is recorded below. Continuous outer scrolling for
expanded composer messages, bubble tails/avatar details, full TalkBack and RTL
coverage, scrolling performance and physical Mac/Pixel acceptance remain open.

### Verification

- Four existing Markdown JVM cases passed; debug and instrumentation APKs built.
- The first Android batch passed three preview cases and exposed a real composer
  failure: its intrinsic-size thread layout was incompatible with the width-aware
  preview. Replacing that parent layout with a line drawn after measurement fixed
  the issue. The original failed run (four cases, one failure, 37.602 s) is retained.
- The corrected batch passed **four cases in 35.677 s** on the existing API 37 /
  16 KiB AVD (`cmux_api37_16k`, 2,048 MiB). It checked last-line placement,
  Markdown/link preservation, expansion without triggering row navigation,
  host-shortened previews, 2x font scale at 90 dp width, updated callbacks,
  disabled expansion, composed Unicode boundaries, and real composer/reader
  expansion with complete text, painted pixels, restoration and account clearing.
- Final preview and expanded-composer screenshots were visually inspected.
  The quote still uses the previously documented nested 320 dp viewport.
- Startup produced System UI / Google-service ANRs and a launcher crash before
  instrumentation. Event logs did not gain new ANR/crash entries during either
  test run. These environment failures are retained alongside test results.
- Evidence: `captures/runtime/agent-feed-inline/` (ignored), with build/test logs,
  screenshots, event logs and exact final APK hashes. Emulator stopped and reaped;
  Gradle stopped. No new signed APK or physical-host acceptance is claimed.

## Continuous composer quote — 2026-10-07

The composer now uses one Android `ScrollView` for the quoted message and the
reply draft. Expanded text is a native `TextView`, using the same inline-only
Markdown model as iOS; the quote no longer has a nested WebView/scroll area or a
320 dp height limit. Existing Compose controls are hosted inside this scroll
container with stable view IDs. The draft remains in the scoped modal state,
and the scroll offset is saved separately from the body. Full text is reloaded
through the authenticated session after restoration.

Full-message Markdown parsing runs off the UI thread, bypasses the row cache and
8 KiB preview cap, preserves styles across blank lines, and accumulates adjacent
runs with a string builder. Ordinary text after whitespace is consumed as a run,
avoiding a temporary AST node for every word in a long paragraph. Plain messages
have a direct path. URL spans retain the existing allowed-scheme policy.

The draft avatar now uses the platform person-circle icon, and the editor has no
underline, matching the source composer structure. The generic full-message
reader keeps its existing Markdown renderer and source toggle.

Text metrics are prepared on `Dispatchers.Default` with `PrecomputedTextCompat`.
Native wrapping uses simple line breaking without hyphenation. On API 35+, the
quote explicitly uses advance widths instead of glyph bounds, with drawing room
at both edges for overhangs; see the
[TextView API](https://developer.android.com/reference/android/widget/TextView#setUseBoundsForWidth(boolean)).
A captured Android 17 main-thread trace identified `GreedyLineBreaker` /
`StyleRun::getBounds` as the stall in a very large styled paragraph. The quoted
row also disables baseline alignment to avoid measuring its weighted text column
at an unbounded provisional width.

New composers focus the editor. A restored expanded composer resumes its saved
reading position; tapping the draft focuses the editor again. Focus-driven smooth
scrolling is disabled so an old animation cannot overwrite an explicit restored
position. User drag/fling scrolling remains native, and dragging dismisses the
keyboard.


The scroll offset is owned above the sheet window, so screen saved-state
restoration can recover it before the authenticated body reload completes. The
native viewport is clipped at both the Android and Compose boundaries; scrolled
text must not draw over the fixed Reply/Cancel toolbar.

### Verification and limits

- Six Markdown JVM checks passed, including a styled paragraph near the 8 MiB
  transport limit. This is parser evidence, not a maximum-size Android rendering
  or latency claim. Debug and test APKs built.
- The Android fixture renders a styled 1.1 MB paragraph as a native quote over
  262,143 pixels tall, reaches the draft in the same scroll area, retains bold
  spans and the complete tail, then reloads the body after saved-state restoration
  and recovers both the reading offset and draft without sending a reply.
- The initial runs caught invalid native tag use and a real native text-layout
  stall; the captured main-thread trace informed the wrapping changes above.
  The stalled debug process was manually stopped. Later runs exposed focus-driven
  scrolling and lost state inside the temporary sheet window; both were fixed.
  Fixture errors (an invalid Markdown delimiter and asynchronous smooth-scroll
  assertion) were corrected without dropping the full-body/draft requirements.
- The first emulator session also had launcher/System UI ANR dialogs covering
  screenshots. Those frames are not visual proof. The same existing AVD was
  restarted headless at 1,536 MiB; no second AVD was created. The screenshot checks
  now require app window focus. A subsequent visual review found text escaping
  above the scroll viewport; the final test also checks toolbar pixels.
- Real process death, maximum-size on-device rendering, enlarged-font/RTL/TalkBack
  traversal, touch/fling and IME behavior, and authenticated Pixel/Mac acceptance
  remain open. No signed release was produced.
- Raw failures, native stack, final screenshots, build logs, APK hashes and test
  receipts are retained in ignored `captures/runtime/agent-feed-continuous/`.

- Final corrected batch: **five Android composer cases passed in 45.540 s** on
  the existing API 37 / 16 KiB AVD, including plan/manual feedback, draft/snapshot
  restoration, approval modes, quote/reader/account clearing, and the long quote
  with toolbar clipping and saved-position restoration. The preceding targeted
  restoration batch also passed two cases in 26.939 s. Final quote and bottom/draft
  screenshots were visually inspected. The headless session's before/after event
  logs have no ANR/crash entries. Emulator and Gradle were stopped and reaped.


### Retained tab/sidebar state — 2026-10-07

Feed now has an account-owned saved-state holder outside its conditional primary
navigation branch. Switching to Notifications and back preserves its local
question choices, list state and modal draft state; the existing request/computer
keys still invalidate changed targets. The adaptive shell likewise retains
sidebar-local saveable state while hidden or behind a compact workspace. The
main Feed/socket-RPC test verifies a selected question survives tab switching
without submission and that terminal Back returns to Feed; shell checks cover
viewport/draft restoration and account clearing. See `WORKSPACE_SIDEBAR.md` for
all four passing cases, exact scope, evidence and outstanding navigation work.

## Legacy Notifications display preference — 2026-10-07

Rechecked `MobileDisplaySettings.swift`, `MobileSettingsView.swift` and
`WorkspaceShellView.swift` at scoped `186cec79781256867ad4516f0802118738bd2393`.
The source defaults `feedReplacesNotifications` to true and exposes its inverse
as the **Legacy Notifications Tab** switch. The shell hides that primary/sidebar
button and moves an already selected Notifications destination to Feed.

Android now persists the same default and switch behavior through the existing
Display preferences. Main compact, wide and Cloud navigation receive the setting.
Hiding a selected Notifications destination commits its active search, closes the
keyboard and selects Feed. Re-enabling the tab does not change the current
selection. Existing notification data, delivery and action paths are retained;
notification-specific fixtures explicitly enable the legacy destination. Feed's
empty-state copy no longer assumes that the old tab is always visible.

Verification used the existing API 37 / 16 KiB AVD at 1,536 MiB headless:

- **Four Android cases passed in 71.085 seconds.** The new settings case checks
  default-hidden behavior, compact/sidebar button visibility, persistence across
  remount, and invalid stored types falling back to the source default.
- The main Feed/socket-RPC case checks default-hidden Notifications, chooses an
  inline question answer, enables Notifications, starts an uncommitted search,
  and hides the tab while that search is active. Feed returns selected with its
  prior query and unanswered choice. Re-enabling Notifications exposes its saved
  query. The case then completes the existing exact-label question, permission,
  terminal reply, destination-open and Back checks.
- Existing Display wrapping/preview behavior and the legacy notification search,
  metadata matching, committed filters and destination navigation still pass.
- Build passed in 2m22s; an incremental build including the corrected empty-state
  copy passed in 21 seconds. No unrelated JVM/native suite or signed build.
- Phone layout was 1080×2400 / 420 dpi. A persisted wide override from the previous
  session was reset before these tests; final display output confirms no override.
  Boot, intermediate and final ANR/crash event logs are empty. Settings fixture and
  default Feed navigation screenshots were inspected. The settings screenshot is
  an isolated component harness, not full Settings window/inset acceptance.
- Evidence: ignored `captures/runtime/legacy-notifications/`, including exact source
  excerpts, build/runtime logs, display output, screenshots and APK/source hashes.
  Emulator and Gradle stopped; emulator process reaped. No physical Pixel connected.

The separate browser sidebar still has only workspace/notification projections
and a Boolean destination across its IPC query/presentation. It needs a full
Feed projection, actions, reader/reply state, search, adoption and preference
integration; hiding its Notifications button before adding Feed would remove a
usable destination without supplying its replacement. This remains implementation
work, not an Android platform limitation. Actual process death, accessibility,
large text and full physical/source parity remain open.

## Shared timeline for the browser sidebar — 2026-10-07

The main Feed UI previously depended directly on `NativeFeedSource` and concrete
Mac sessions, so it could not be mounted in the separate browser process without
replicating the UI or moving connection ownership. `AgentFeedTimeline` now consumes
an `AgentFeedUiSnapshot` and `AgentFeedTimelineActions`. The snapshot contains
renderable items, identities, labels, pending/error/read state and aggregate load
status; it contains no paired-Mac object, credential code, RPC client or session.
The browser adapter can supply opaque owner and entry identifiers.

`NativeAgentFeedView` remains the main-process adapter. It keeps the existing
aggregation, read-state storage and live sessions, projects their current display
state, and resolves each action against the current item. The common timeline owns
all existing list/filter/search behavior, inline decisions, quote/reply sheets,
full-text reader and saved modal state. `NativeAgentFeedRow` and
`AgentFeedReplySheet` consume the same display entries. Native modal callers retain
compatible saved target/draft fields; the display path also checks the owner when
matching a restored target.

A final review tightened read completion: the adapter now ignores a completion if
the event's owner/build, request, kind, workstream, destination or questions no
longer match the displayed event. This prevents a late reader completion from
marking an unrelated replacement event read under a reused ID. Decisions and
replies use the same identity predicate, followed by the existing session's live
ownership and pending-request checks.

Verification:

- Initial build and **23 JVM checks passed in 1m25s**, including four new display
  projection/modal cases, five modal regressions and fourteen wire/session cases.
- On the sole API 37 / 16 KiB AVD at 1,536 MiB, **eight Android cases passed in
  113.025 seconds**: all five reply/reader tests, both question-control tests and
  the real main-screen/local socket-RPC Feed flow. This covers saved drafts during
  snapshot reload, account replacement, expanded quote/reader reload, the styled
  1.1 MB quote's continuous scroll/restoration and toolbar clipping, plan revision
  and approval mode, question paging/restoration, and exact question/permission/
  terminal reply RPCs and destination/Back navigation. Screenshots inspected.
- After the read-completion review, the final identity predicate and its regression
  compiled and **24 JVM cases passed in 21 seconds**. The Android run predates
  this final predicate extraction/read guard; it was not repeated. The recorded
  APKs therefore represent the immediately preceding UI checkpoint, while the
  final source hashes include the guard. No signed release was built.
- Existing 1080×2400 / 420 dpi display, empty boot/intermediate/final ANR/crash event
  logs, no extra AVD. Emulator and Gradle stopped; emulator process reaped. No Pixel.
- Evidence: ignored `captures/runtime/feed-shared-timeline/`, with logs, XML results,
  composer screenshots/geometry and APK/source receipt.

**At this checkpoint browser integration was still pending.** The following
section supersedes that implementation gap. The next work was to extend its query/presentation and
paged display projection with Feed, bridge decisions/triage/replies/reader loads
through the main process with live account and issued-row checks, and preserve
separate search, filters, drafts and destination adoption. Large full messages
need bounded transfers across the process boundary, rather than a single Binder
bundle. Then apply the legacy-tab preference in that sidebar and verify the real
browser process while retaining its active WebView. This refactor does not prove
browser Feed, authenticated transport or physical parity.


## Feed in the separate browser process — 2026-10-07

The browser sidebar now mounts the shared timeline, question and approval controls,
reply composer, full-message reader and row triage/navigation. Feed has separate
search/filter state and saved UI state across primary-tab and sidebar switches.
The browser reads the main app's Legacy Notifications Tab and Feed row preferences;
turning off a selected legacy tab transfers selection to Feed. Returning to the
main app adopts Feed selection, search and Needs Input filter.

The main app remains the owner of authenticated sessions and read-state storage.
The display projection uses salted opaque entry and owner keys. Pairing codes and
session objects do not enter the browser process. Every command resolves an issued
key against the current account, computer scope, request, questions and destination;
prepared navigation is one-use and resolves the live destination again on return.
Hiding/leaving the presentation cancels pending operations. A cancelled admitted
write retains its unconfirmed-delivery warning and draft in the owning session;
there is no automatic resend.

Snapshots, reply commands and full messages cross Binder through bounded file
descriptors backed by unlinked private cache files. The receiver rejects pipes,
invalid UTF-8 and oversized payloads. Both endpoints close descriptors on normal
completion, cancellation and orphaned replies. Full-message RPC paging retains the
existing 8 MiB bound and version/offset checks. The display codec preserves already
normalized model values, including locally recorded replies longer than RPC preview
limits. Initial loading does not mount an empty authoritative timeline that could
invalidate a restored composer.

Verification is recorded in the ignored `captures/runtime/feed-browser-sidebar/`.
The combined build passed **62 focused JVM cases** and produced debug/test APKs.
All four targeted Android cases passed across the final checks:

- Two payload cases: a 2 MB UTF-8 round trip after unlinking, descriptor closure on
  success/pre-dispatch cancellation, and pipe rejection.
- The existing main-screen real socket-RPC Feed flow passed on the integrated APK.
- The actual separate-browser-process case passed in **237.047 seconds**: independent
  Feed search, question choice retention across tab switches, exact question and
  permission methods/parameters, expanded quote text loaded through the main app,
  terminal reply acknowledgement/display, sidebar hide/show with no browser reload,
  retained WebView draft, and native terminal destination/Feed-state adoption.
  Two earlier fixture attempts exposed selector errors (the browser address field
  instead of sidebar search, then the whole quote instead of its inline link).
  Corrected selectors passed; no product changes were needed for those failures.

The browser screenshot was inspected. Boot/intermediate/final ANR/crash event logs
are empty. The sole headless API 37 / 16 KiB AVD used 1,536 MiB; its process was
stopped/reaped and Gradle stopped. Large-payload testing is a descriptor round trip;
the browser fixture's message is small. Large actual cross-process messages,
process-death drafts, legacy-preference changes while browsing, offline/reconnect,
all plan modes, filter/triage gestures and accessibility still need broader browser
acceptance. The runtime reader check exercised expanded composer text, not every
standalone reader lifecycle.
Physical Mac/Pixel, production push, broad lifecycle/accessibility and signed-release
gates remain open; this integration does not establish full parity.


## Reply row and expansion accessibility — 2026-10-07

A scoped comparison with `AgentFeedRow.swift` and `AgentFeedBubbleShape.swift` at
`186cec79781256867ad4516f0802118738bd2393` found visible gaps after browser Feed
integration. Android now uses the source's continuous 4 dp tail / 18 dp corner
bubble outline, 40 dp opposite-side inset and asymmetric content padding. Logical
alignment and geometry mirror in RTL. Replacing the border outline cache when
layout direction changes fixes a stale tail in an already-mounted row. Text uses
content direction so English punctuation remains correctly ordered in an RTL
shell. Quoted user prompts use outlined bubbles;
recorded replies use filled trailing bubbles with a single “You: …” accessibility
label and no duplicate reference quote. The production quote-bar style retains a
one-line “Replying to …” reference and compact You/reply marker. Reply, Sending and
Replied states include the outline arrow, progress indicator or checkmark.

An unconfirmed terminal reply has explicit Try Again and Open Terminal controls.
Try Again uses the existing saved-failure draft composer and does not resend.
Open Terminal resolves the live Feed target through the same action interface.
Offline or pending controls stay gated. Permission/plan/question delivery errors
continue to use their existing failure state.

Inline “See more” retains attributed text and pointer links, and now exposes an
independent accessibility button over its measured substring. Its bounds use the
actual text layout with physical positioning, including RTL, and disappear when
expansion is disabled. This replaces the browser test's coordinate-derived tap
with an accessibility-description lookup. It does not claim full TalkBack or
Switch Access acceptance without a physical assistive-technology run.

Verification:

- Debug and test APK builds passed. Final integrated runtime: **9 tests passed in
  98.483 seconds** on the sole API 37 / 16 KiB AVD, headless at 1,536 MiB.
- Five inline-text cases cover grapheme/style/link retention, last-line expansion,
  enlarged text/current callbacks, independent RTL accessibility/touch activation
  without row navigation, disabled-action removal, and English punctuation in RTL.
- Two reply-row cases cover reopening an uncertain draft without sending, exact
  terminal destination, bubble/bar presentation, accessible sent label and direction
  switching. Final LTR/RTL screenshots were visually inspected.
- Main real socket-RPC Feed flow and actual separate-browser Feed flow passed. The
  browser activates See more by accessibility description, sees the new reply label,
  keeps its WebView draft without reloading, and returns to the exact terminal.
  The final Android hierarchy exposes See more as a clickable, enabled node.
- The first run had two failures: the overlay's guard compared annotated strings,
  but Text adds resolved link styles. Comparing the displayed string fixes the guard.
  Screenshot review separately found the stale RTL border cache and punctuation;
  both were corrected. The next run passed seven cases but hit Android 17's stale
  initial browser-title accessibility cache (paint was correct). The browser fixture
  now refreshes its known provider node, as existing notification tests already do.
  All initial logs/screenshots remain alongside final evidence.
- Evidence: ignored `captures/runtime/feed-reply-rows/`. Empty boot/intermediate/final
  ANR/crash event logs. Gradle stopped and the sole emulator process stopped/reaped.
  No Pixel/Mac run, signed release or broader regression suite in this batch.

Full Feed parity remains unproven. Physical assistive-technology, all locale/font
combinations, long-message performance and broader account/lifecycle acceptance
remain part of the project gates.

## Full-message Source reading — 2026-10-07

Rechecked `AgentFeedReplyComposer.swift` and `AgentFeedFullTextView.swift` at the
scoped source revision `186cec79781256867ad4516f0802118738bd2393`. The source reader
loads complete event text, exposes explicit retry, and cancels work when dismissed.
Android keeps its existing formatted Markdown view and optional Source view.

The Source branch previously mounted one Compose Text and a branch-local scroll
state. Switching through Formatted discarded that position; sufficiently tall
messages also exceeded Compose's packed text measurement constraints. It now uses
a selectable native TextView inside a clipped ScrollView. A saved character anchor,
fractional line offset and selection belong to the reader, outside the mode branch
and nested dialog. The message is reloaded; neither the Compose saver nor native
view hierarchy saves its contents into the Activity Bundle. The source view uses
simple paragraph wrapping and content-based text direction, as the expanded quote
already does. This change also reaches the shared reader in the browser sidebar.

The first focused Android run passed **five cases in 90.931 seconds**: three reader
cases plus the composer/account-change/reader flow and actual separate-browser Feed
flow. Reader cases cover mode-switch position/selection without another load,
14,000-line complete text above the Compose height limit, saved-state reload and
selection restoration, native hierarchy exclusion, retry/read marking and dismissal
cancellation. Screenshot review caught a capture taken before the final native
scroll painted; the fixture now waits for drawing before taking visual evidence.
The original screenshot is retained and is not evidence of the final line painting.

The corrected single-case run passed in 17.903 seconds, but its screenshot was
covered by a System UI ANR dialog. Second-boot event logs show System UI/Google
service startup ANRs at 15:27:43–15:28:21, before that test started at 15:28:56.
After choosing Wait and confirming the launcher recovered, the same APK/test
passed again in **9.928 seconds**. Its final screenshot visibly shows line 14,000
and `TAIL_MARKER`, with no dialog. No new ANR/crash events appeared during that
repeat; the initial five-case run had empty ANR/crash logs. All original and final
evidence is retained in ignored `captures/runtime/feed-reader/`. Both debug/test
builds passed; Gradle and the sole 1,536 MiB emulator were stopped and reaped.

These are emulator fixtures and Compose saved-state restoration, not a physical
Pixel/Mac or OS process-death run. Broader rendered/source reflow, accessibility and
full Feed acceptance remain open.

## Retaining the open modal through snapshot reload — 2026-10-07

The main and browser timelines now retain the open reader/composer's saveable UI
inside a modal-owned state holder while waiting for its authorized Mac snapshot.
Previously, the READY-to-WAITING transition disposed the scroll state even though
the modal target and draft survived. The holder lives across this transition and
can itself be restored while waiting. Full messages are loaded again, and their
prior load coroutines are cancelled when the content leaves composition.

Closing the sheet or invalidating its account/target discards the holder. Opening
the same item later starts a fresh reading/composing session. Existing ownership
and target checks still decide whether a reloaded snapshot can reopen the modal;
waiting does not send anything. The waiting message now describes a message for
readers and a draft for composers.

Verification: debug/test APKs built successfully. The initial nine-case run took
128.976 seconds: all six existing composer/reader and real socket-RPC Feed cases
passed, while three new cases failed on test assumptions. The two composer cases
matched both the timeline and sheet's See more controls; their selectors now scope
to the composer preview. The reader correctly discarded its selected range on
close, but Android initialized a collapsed cursor at zero rather than minus one;
the assertion now checks for no range at the native initial position. No app-code
change was needed for those three failures.

The corrected **three cases passed in 39.548 seconds**, giving passing evidence
for all nine distinct scenarios across the runs. They save/restore while WAITING,
reload full text, restore the expanded quote offset/draft and Source offset/selection,
verify fresh reopening and account invalidation, and assert no unintended terminal
paste. The restored composer screenshot was inspected. The waiting-screen screenshot
was captured during the transition and is retained but does not establish final
sheet paint; that copy is covered by the displayed-node assertion. Evidence:
ignored `captures/runtime/feed-reconnect/`. Boot and final ANR/crash logs are empty.
Gradle and the sole headless 1,536 MiB emulator were stopped/reaped.

These checks simulate snapshot reload in the shared timeline; they do not prove
actual Pixel/Mac disconnection, OS process death, or this transition through the
browser IPC. Those broader acceptance gates remain open. No signed APK was published.

## Routed browser Feed recreation — 2026-10-07

`RoutedBrowserPresentationTest#globalSidebarFeedModalsSurviveBrowserActivityRecreation`
passed **one Android case in 59.992 seconds** on the existing API 37 / 16 KiB AVD
at 1,536 MiB. No production change was needed. Font-scale changes caused two actual
`RoutedBrowserActivity` relaunches, confirmed by system events, while its browser
process remained alive. The bound service/PFD route reloaded the 160-line message.

The expanded reply retained its unsent Unicode draft; the reader retained Source
mode. Before/after screenshots show the reader at the same partially clipped
Line 031 despite the font-size change. This anchor observation is visual evidence,
not an automated pixel assertion. The composer helper scrolls to its draft, so the
case does not establish exact composer viewport restoration. The browser DOM draft
survived without another page load, no terminal paste was sent, and the browser
presentation hold was released on exit.

Debug/test builds succeeded (40 seconds, then an 8-second test-only rebuild after
correcting a selector before the runtime run). The single runtime run passed.
A Launcher crash and GMS startup ANR preceded testing; the baseline and final
crash/ANR event logs were identical. Screenshots were inspected. Original font
scale and display size were restored, and the emulator and Gradle were stopped.
Local logs, screenshots, APK hashes and receipt are under
`captures/runtime/browser-feed-restoration/` (ignored).

This covers Activity recreation within a live browser process. OS process death,
large IPC messages, live reconnect/revocation, physical Pixel/Mac workflows and
broader accessibility acceptance remain open. No signed release was produced.

## All-question answer sheet — 2026-10-09

Scoped source: `manaflow-ai/cmux` commit
`f4b1509054949eaad5d695569ad443c4c18ed68d`, specifically
`AgentFeedQuestionControls`, `AgentFeedQuestionComposer`, `AgentFeedQuestionCard`,
`AgentFeedQuestionOptionRow`, `AgentFeedQuestionCustomAnswerRow`,
`AgentFeedQuestionAnswerBuilder`, and `MobileDisplaySettings` under
`Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/`.
This does not advance the overall parity pin or claim the remaining upstream
Feed analytics, scroll-performance, task-picker, or privacy changes are ported.

The shared native/browser timeline now shows a compact first-prompt preview and
an Answer action. Its sheet presents all questions vertically, progress, answered
indicators, single/multiple selections with leading controls, and an always-visible
custom answer. Submit sends ordered labels or trimmed custom text only after
all prompts are answered and the source is connected with no pending action.
Focus or selecting the custom control activates that mode and clears presets;
selecting presets retains the inactive custom text. Single choices stay selected
when tapped again. Keyboard/field height changes bring the focused editor into view.

The saved modal includes answer state and a SHA-256 digest of the ordered prompt,
header, option and selection-mode content. It does not save the remote prompt
bodies. Authorized snapshot loading retains the draft; changed request/content,
account/owner removal, resolved events and cancellation invalidate it. Both Feed
presentations use the same sheet and existing transport ownership checks.
Quote bubbles now default to enabled in every build and honor the stored setting;
the existing debug-only A/B switch remains, as in the iOS source.

Verification: **17 focused JVM tests passed**, covering answer modes/order,
codec migration and request/content/owner identity. Debug/test APKs built.
The final feature run passed **four Android cases in 101.475 seconds**, covering
all-question completion, retained inactive custom text, blank focused answers,
o-option questions, offline/pending gating, WAITING saved-state restoration,
changed-prompt/cancel invalidation, release-setting persistence behavior, and
real socket-RPC submission of labels. The display-setting code
is unconditional across build variants; no new release APK was built.

The initial Android attempt failed one assertion because it counted the visible
placeholder as an answer. Screenshots also exposed a System UI ANR overlay even
though semantic interactions could succeed. The test now requires actual window
focus, stable IME geometry and full editor bounds above the keyboard. Earlier
boots recorded System UI/Google service ANRs and a Launcher crash; those logs are
retained. The earlier 88.520-second feature run had empty crash/ANR logs. The
final run followed an emulator System UI restart; its before/after crash/ANR logs
are identical, with no new events during the run.

Keyboard screenshot inspection exposed missing scroll-follow when the modal
consumed IME insets. The sheet now reacts to the **measured scroll viewport** and
editor height, matching iOS's focused-answer geometry handling. Capture polling
also waits for stable editor geometry and flushes Compose after native IME
settlement. A settled follow-up exposed an intermittent focus/draft transition;
updates now reduce synchronously against the latest local answer state and skip
no-op persistence, so delayed IME callbacks cannot restore older modes. Submit
recomputes the current answers when tapped. The test explicitly verifies focus
when switching to an empty answer. This is why earlier keyboard
screenshots, although retained, are not accepted as final visual proof.

A repeat of the previously intermittent case passed in **17.777 seconds** on the
same final app/test APKs. The final preview, sheet and stable-keyboard screenshots
were inspected; the editor is fully visible above the IME, with geometry recorded
alongside the image. Installed APK hashes match the local build outputs. No new
crash/ANR events appeared during either final run. Emulator and Gradle were stopped.

Evidence is under ignored `captures/runtime/feed-question-sheet/`, including
original attempts, JVM XML, runtime logs, installed APK hashes and screenshots.
These checks use the existing API 37 / 16 KiB AVD, requested at 1,536 MiB/2 cores;
the image reports about 4 GiB guest memory despite that request. No additional
AVD was created. Physical Pixel/Mac, question-specific browser IPC/recreation,
OS process death and a signed release remain unverified by this batch. The iOS
interactive drag-to-dismiss keyboard gesture was not covered by that batch;
the follow-up below adds and verifies it on the existing Android emulator.
Signed build 616 is unchanged.

## Background snapshot decoding (2026-10-09)

Scoped source: `MobileShellComposite+AgentFeed.swift`'s
`decodeAgentFeedSnapshot` at `f4b1509054949eaad5d695569ad443c4c18ed68d`.
Android's retained Feed session previously ran timestamp parsing, byte-bounded
field conversion, row validation and sorting on its owning UI dispatcher after
the RPC. It now decodes on `Dispatchers.Default`, then returns to the owner for
revision admission, local reply/decision overlays and publication. Cancellation
is checked before, between rows and after decoding; row-tolerant malformed-data
handling does not swallow cancellation. Current owner and revision checks remain
after the worker returns. The existing row/field limits are unchanged.

**82 focused JVM checks passed**: 18 wire/session (four new worker cases),
54 coordinator, five projection and five timeline checks. The worker tests block
the synchronous decoder while the owner handles an event or decision, verify the
new revision/local acknowledgement wins, and reject a completed decoder result
after session cancellation. A separate case verifies cancellation between rows
aborts the entire snapshot. These are concurrency-correctness checks, not a
measured Pixel scrolling-performance claim.

Evidence: ignored `captures/agent-feed-decode-jvm.log` and
`captures/runtime/agent-feed-decode/` (JUnit XML). No emulator, APK build, physical
workflow or signed release was performed for this batch; the active signed
milestone run 633 uses earlier `7a5ff354` sources. The iOS stop-reason cache,
scroll instrumentation/analytics, remaining privacy changes and physical Feed
performance still require comparison and acceptance; the global pin is unchanged.

## Interactive question-sheet keyboard (2026-10-09)

The question sheet now follows `AgentFeedQuestionComposer.swift`'s
`scrollDismissesKeyboard(.interactively)` at the same scoped `f4b1509` source.
Its scrolling content participates in Android's native IME animation controller
through [Compose keyboard dragging](https://developer.android.com/develop/ui/compose/system/keyboard-animations).
Typed answers, selected options, the submit action and the sheet remain intact
while the keyboard is dragged down and reopened.

The existing three question-sheet regressions passed. A new physical-touch
instrumentation case initially timed out while checking root insets for a partial
animation; its corrected measurement uses the rendered scroll viewport plus
screenshots. It **passed in 27.195 seconds** on the same application APK,
including a held partial drag, complete hide, retained answers, an undismissed
sheet and reopening the editor. The initial viewport was 1,208 px high; during the
held gesture it was 1,519 px high, with an initial 883 px IME. Partial, hidden and
reopened screenshots were inspected. The first run's boot service ANRs preceded
the test; the final run's before/after crash/ANR logs are identical.

Evidence: ignored `captures/runtime/agent-feed-keyboard/` and
`captures/agent-feed-keyboard-*.log`. The existing emulator and Gradle were
stopped. No additional AVD was created. This is API 37 emulator evidence;
physical Pixel/Mac acceptance, broader gesture/accessibility behavior and a
successful signed milestone remain open.

## Completion-reason normalization and retained cache (2026-10-09)

Scoped source: `AgentFeedStopReasonCache.swift` and the deduplication call sites in
`MobileShellComposite+AgentFeed.swift` at
`f4b1509054949eaad5d695569ad443c4c18ed68d`. The cache source SHA-256 is
`bf7539a8575645a734b1b151617ad9f78f37d5b058c433abfbe018ebd01976c1`.
The global parity pin remains unchanged.

Android previously compiled an ASCII whitespace regex on every same-turn
comparison. Nonbreaking/em spaces therefore prevented a truncated preview from
matching its full completion. The normalizer now collapses Unicode White_Space
without a regex, retaining authored nonspace text. Matching still requires equal
normalized reasons or an explicitly ellipsized prefix; distinct complete
responses remain separate. Existing exact Mac/build, time-window, fuller-text and
reply-preservation rules remain intact.

Main Feed/list badge and browser Feed/sidebar presentations now own reusable
reason caches. Each projection prunes to stop reasons present in its input
snapshot; removed reasons are not carried forward indefinitely. Caches contain
normalization results only, do not persist, and do not supply rows or RPC targets.
Live browser actions continue resolving the current authoritative snapshot.

**54 focused JVM checks passed:** three cache/normalization, seven native
projection, ten browser Feed and 34 sidebar cases. These cover all six source
matching fixtures, Unicode whitespace and authored nonspace content, 100 unchanged
projections with only two normalization calls, changed/removed reason pruning,
reply and build separation, plus browser badge/row/live-read agreement and snapshot
removal. The first focused invocation covered the ten cache/projection cases;
the final invocation includes the actual `RoutedAgentFeedTest`/`RoutedSidebarTest`
classes. Main Kotlin compiled in the same batch. This is deterministic reuse and
correctness evidence, not a measured scrolling or latency claim.

Evidence: ignored `captures/agent-feed-stop-reason*-jvm.log`,
`captures/runtime/agent-feed-stop-reason/` (JUnit XML), and
`captures/AgentFeedStopReasonCache.swift`. Gradle is stopped; no APK build, emulator
or Pixel run was performed for this batch. Signed 635 predates this change.
Broader Unicode/grapheme equivalence, physical Feed performance, remaining
privacy/analytics source changes and full Feed acceptance remain open.
