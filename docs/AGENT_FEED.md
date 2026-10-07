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
swipe triage and Display settings. Remaining work includes expansion accessibility/RTL coverage
and bubble-tail geometry, full-text and composer
recreation, accessibility/large-text layout, and routed browser/sidebar Feed
navigation. The dated runtime checks below cover specific fixture flows, not full
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
absent/default-off in production; production uses the leading quote bar. Exact
bubble-tail geometry and inline See-more placement still require visual refinement.

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
