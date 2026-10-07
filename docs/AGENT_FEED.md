# Agent Feed companion

## Source and scope

This port is scoped to official iOS source at
`186cec79781256867ad4516f0802118738bd2393`:

- `Packages/iOS/CmuxMobileRPC/Sources/CmuxMobileRPC/MobileAgentFeedListResponse.swift`
- `Packages/iOS/CmuxMobileShellModel/Sources/CmuxMobileShellModel/MobileAgentFeedItem.swift`
- `Packages/iOS/CmuxMobileShell/Sources/CmuxMobileShell/MobileShellComposite+AgentFeed.swift`
- `Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/AgentFeed{View,Row,Projection,RowModel,QuestionAnswerComposer}.swift`
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
  bypassPermissions/ultraplan/revise/deny; multi-question and multi-select answer
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
swipe triage and Display settings. Remaining work includes exact inline See-more
and bubble-tail geometry, inline question paging, full-text and composer
recreation, accessibility/large-text layout, and routed browser/sidebar Feed
navigation. The changed rows still require Android runtime/visual verification.

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
