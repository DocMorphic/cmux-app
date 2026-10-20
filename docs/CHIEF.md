# Chief conversations

The first Android Chief destination is mounted only when the exact admitted
foreground Mac advertises all seven required methods. It adds Home and the
workspace toolbar action, resets to Workspaces when support is lost and keeps
the existing native account, pairing and foreground-owner admission. The current
custom Mac has now been replaced by the independently verified Chief-capable
`a5911a1f` build. Account, capability and UI checks await manual Mac unlock; a
compiled/started host alone does not establish runtime Chief support.

This scoped port follows cmux `a5911a1f92f3eeb11c4d7f3e2ef89204718c5009`.
`third_party/cmux-chief/manifest.json` pins the client/question source hashes and
separate Home/backend/shell/artwork hashes. The global implemented/reviewed pins
are unchanged. The avatar is copied unmodified and attributed in both notices.

Home sorts pinned/recent rows, searches title/preview, shows unread/working
state, reports real startup failures and waits on the Mac's opaque change cursor
with bounded retry. A started-but-empty host can supply its default conversation.
Transcript sessions use event-backed reads, fill missed-page gaps, retain older
action cards, page history and track working/read state. The visible foreground
conversation marks all loaded incoming messages read, matching the source's
viewing contract; this is independent of scroll position. The original unread
backlog is retained as a catch-up marker/count. Its up-arrow loads history through
the first unread incoming message, adds context and jumps there. Seeing that
target (or an outgoing message beyond the marker) clears the arrow. Backgrounding
pauses reads and resets capture for a fresh resume baseline; leaving the
conversation ends its visit. Pending local reads reject stale server markers.
Initial/ordinary history pages contain 50 messages; event and gap reads retain
the source's 200-message limit. The transcript starts at newest, follows arrivals
while pinned, preserves history browsing and returns to newest for a new send.
Live snapshots cannot pull unknown older rows across the loaded window; explicit
history loading widens it. Action refreshes publish only their own send/card,
preserving unrelated newer updates. Genuine reader drag/fling state controls
pinning; programmatic motion does not unpin the reader.
Loaded windows above 1,500 rows now keep the newest 1,000 acknowledged rows when
resting at newest, outside scrolling, history loading and open action sheets.
The cut preserves unread/catch-up context, reserved action cards, quoted parents
and every unacknowledged send. Dropped history remains pageable.
Current-position history prefetch now requests within four viewport heights and
stops wanting failed reads beyond six, matching the pinned geometry policy. A
bounded, unplaced measurement of the same row composition supplies real pixel
offsets for variable-height text, quotes and cards; it exposes no extra controls
to accessibility. Positions beyond the measured six-screen region only need a
proven lower bound for the stop decision. Requests share one retained owner task
and retry at 1/2/4/8-second intervals while wanted. Moving away allows an active
read to finish and stops subsequent retries; pause/retirement cancels the task,
with a generation fence against a late cancelled task resetting its replacement.
The owner exposes read activity to the composition: a paused layout cannot
restart history, and resume rechecks the existing viewport without another drag.
Catch-up shares cancellation of the active history read and fences stale results
from a resumed jump, without cancelling retained sends.
History pages insert unknown rows before their requested boundary and preserve
current known values. Predicting a fling's resting target before reaching this
region remains open; this is current-position prefetch, not complete scrolling
parity.
The same owner retains query, drafts and navigation state across recomposition;
backgrounding pauses reads. Account/Mac retirement cancels operations and clears
content. Every request still uses the real client's admission checks, without a
terminal selection ticket.

Sends use stable client IDs. Poll/read echoes replace the optimistic row while
keeping its lazy-row identity, including canonical IDs first learned from a late
acknowledgment. Immediate accepted-send reads permit the source's five-second
clock skew; ordinary poll/history text fallback remains strict. Exact IDs win
across a whole page before text matching. Already-correlated rows and sequence
watermarks prevent an old identical message from absorbing a newer send. No
ambiguous write is automatically resent. In-flight sends trail acknowledged
history; failed sends retain their source sequence anchor before later replies.

The retained transcript now owns a FIFO send tail, matching the pinned store's
ordering contract. Each message/retry waits for the prior send and immediate
acknowledgment read, so concurrent phone actions cannot reorder host writes.
Failure releases the next item without resending the failed one. Leaving a view
cancels its waiter and pauses event reads, while already-admitted writes remain
owned by the retained transcript; retiring that account/Mac cancels the owner
job and clears content. A queued retry whose original exact echo has arrived
skips a redundant write. This does not establish cold-process persistence.

Failed sends expose the source's Try Again / Delete / Cancel actions. Explicit
retry reuses the original client ID, refreshes the send time, clears the failed
anchor and moves the row to the in-flight tail. It reserves the row before
suspension, preventing repeated taps from issuing concurrent retries. Source
`ChiefService+Control.swift` maps that ID to `IdempotencyKey` for the host store;
its hash and the source gesture sheet are now in the behavior manifest. Delete
removes only a failed local row, never writes to the host and suppresses later
echoes with known deleted IDs for this retained owner session. A distinct send
with identical text remains visible. Owner retirement clears these local sets.
Long transport-error detail lives in a bounded scrolling area inside the action
sheet, keeping the row's retry control reachable with the keyboard and enlarged
text. Failed sends are never retried by polling or refresh.

Approval/question actions reserve a card before suspension. A confirmed commit
with a failed refresh stays reserved until its receipt; an unconfirmed answer
can be explicitly retried without polling resending it. Old history cards get a
targeted reread. Work parts show title/status/preview; settled cards show receipts.
Full questions support single/multiple choices, one Other value, qualified IDs,
defaults and previews. The Mac owns the final answer and respondent.

The transcript now applies first-in-run spacing, hour-gap separators and loaded
parent quote previews. Direct conversations omit redundant sender headings.
Timestamp date/time follows the device's locale, zone and clock preference; the
English/Japanese labels and date/time format are exact selected source values.
Home includes relative update stamps and the bounded pinned-only invitation;
the header uses a spoken Stop control and single-line titles at enlarged sizes.
These are scoped presentation steps, not a complete generic conversation port.

An isolated hosted UIKit reference is now prepared under
`tests/ios-chief-reference/`, with a manual `ios-chief-reference.yml` workflow.
It extracts the actual 227-file conversation package from its pinned Git tree,
uses Chief's presentation options and original avatar, and drives nine authored
offline scenes. Exported screenshots are paired with visible-label geometry and
font metadata by exact PNG hashes; the verifier requires the complete scene set,
fixture/source hashes, clean project commit and matching pixel dimensions.
The generated Xcode project and scheme parse, both Swift inputs parse, and six
integrity checks pass. These are not UIKit typechecking or rendering evidence.
The current local developer directory contains only command-line tools; full
Xcode and an iOS runtime were not found. Hosted compilation/capture and artifact
inspection have now passed; matched Android scenes remain required. This does not update
global implemented/reviewed pins or claim complete review of the reference tree.
First reference run `39510100505` at project source `b4606b9e` failed in the
generated test target: it also compiled x86_64 while SwiftPM supplied active ARM
simulator modules. The project now sets `ONLY_ACTIVE_ARCH = YES`, matching the
selected destination and leaving the pinned package unchanged. A fresh generated
project parses; the later successful run below supplies the rendering evidence.
Follow-up `39511521895` at `3b2cfc4a` compiled and rendered all nine scenes, but
failed the accessibility-font guard. The captured 402×874 @3x scene still had
17-point body text. The host now uses separate standard/accessibility invocations
with the simulator's actual system content size rather than a controller-only
trait override. Native application category and ≥28-point accessibility body
fonts are required in the test and exported metadata. The six integrity checks
include rejecting a mislabeled 17-point accessibility capture.
Run `39513912473` at `e431401c2279e7b7423d4439bffea187b9e46f4d` passed
both invocations and export. Artifact `12307880734` was downloaded, hash-verified
and independently revalidated; all nine images were inspected. The accessibility
scene is 402×874 @3x with actual application category AccessibilityM and two
28-point body labels. The source/fixture/PNG receipt is under
`captures/recovery-20261021/ios-chief-reference/verified-e431401c/`.
This accepts the offline UIKit reference batch, not matched Android or physical
acceptance. Observed differences include the portrait header, empty composer,
run-tail shape, original-sender quote outline/connector, failed-send badge and
poll styling. The single-emoji scene is large and unbubbled; its two emoji
separated by a space remain in a normal bubble. Preserve that actual distinction.

Evidence is ignored under `captures/recovery-20261020/`:

- Actual pinned Swift execution: 21 wire-model cases, nine client requests and
  eight shared question-validation cases; source/artwork hashes verified.
- 43 focused JVM cases pass, including six new send-correlation regressions and
  comparisons of 19 vectors from the actual Swift run-plan, store sort method
  and rowID property with an inert Chief-only model. Six timestamp resources
  match the pinned source catalog. The full JVM run passed 2,986, skipped four,
  zero failures (`chief-timeline-final-build-and-jvm.log`).
- Eight API 36 ARM64 Android cases pass in 20.426 s, including failed ordering,
  late-echo identity, separators/quotes, explicit answer retry, full question
  forms, owner retirement, root capability gating and a font-scale-2 probe.
  A two-case inset-capture rerun passed in 5.110 s. The isolated harness now
  mirrors the production parent's system-bar inset consumption; the actual
  root and final timeline/enlarged-text screenshots were inspected. The crash
  buffer is empty. This does not establish physical keyboard/IME or TalkBack.
- The later retry/delete batch adds four session regressions (31 session and
  two timeline cases pass) and passes the full JVM suite: 2,990 passed, four
  skipped, zero failures (`chief-retry-final-build-and-jvm.log`). The source
  checks verify eight en/ja action strings as well as the six timestamp values.
  The initial enlarged-error UI run was stopped after exposing an unreachable
  action. A later system-font run passed its partial-visibility assertion but
  visual inspection found Cancel overlapping the navigation bar; it is not
  accepted as layout proof. The final implementation fully expands the sheet,
  bounds its error detail and respects bottom insets. All eight Chief UI cases
  pass in 16.694 s (`chief-retry-android-insets-final.log`). A stronger full-button
  bounds assertion plus actual Cancel/reopen/Delete interaction passes with
  Android system font scale 2.0 in 4.494 s. The final system-font screenshot was
  inspected; the emulator font setting was restored. This remains emulator,
  not physical-device or TalkBack acceptance.
- Four send-tail regressions pass: acknowledgment/read ordering, failure
  release, view-waiter cancellation versus owner retirement, and an exact echo
  resolving a queued retry. The full JVM suite passes 2,994, with four skips and
  zero failures (`chief-send-queue-build-and-jvm.log`); the focused session and
  timeline suite passes 37. Nine production Chief Android cases pass in 19.375 s
  (`chief-send-queue-android.log`), including Back/reopen during a held send,
  a second queued message and ordered unique echoes. These remain typed local
  owner/host fixtures, not a real Chief host or physical Pixel acceptance.
- Read/catch-up policy matches 16 vectors executed through the actual pinned
  Swift store's read-event and eight read/catch-up bodies, using inert direct
  message/read models and a no-op receipt backend. Two source labels match en/ja.
  Two policy tests and seven session regressions pass for viewing, stale receipts,
  pagination, fresh resume capture, retirement, authoritative Mark Unread and
  scoped live/action refreshes. The focused session/timeline/reading suite passes
  46; the full suite passes 3,003, with four skips and zero failures
  (`chief-reading-scoped-refresh-build-and-jvm.log`). Thirteen
  Android cases pass in 29.239 s (`chief-reading-window-android-final.log`): eleven production
  Chief cases and two admitted-root/capability cases. New flows check initial
  newest positioning, read-all with an older unread backlog, loading/jumping to
  the original first unread, stable viewport on arrival while reading history,
  and returning to newest on an explicit send. Rendering/performance and real
  account/device acceptance are separate gates.
  A separate settled capture run passes in 7.289 s with fixed header-window
  bounds before/after the jump (`chief-reading-settled-capture.log`). Its initial
  newest/backlog-arrow and first-unread target screenshots were inspected
  individually; the capture run's crash buffer is empty. This is scoped Compose
  and Android rendering evidence, not a matched iOS or physical-device result.
  An intermediate UI rerun failed the immediate own-echo visibility assertion.
  Pin tracking now distinguishes reader drags/flings from programmatic movement;
  the check waits for the rendered echo. It passes three consecutive targeted
  runs and the final full batch. The viewport check holds a visible message's
  actual top position, rather than comparing a virtual scroll index. A separate
  red/green regression reproduces receipt deduplication suppressing a fresh read
  after an acknowledged server-side Mark Unread; stale snapshots remain fenced.

- Loaded-window cuts match 21 vectors executed through the actual pinned Swift
  trim methods and row-ID property, including unread context, held cards,
  transitive quoted parents and local messages. The focused window/session/
  reading/timeline suite passes 50 cases. The full JVM suite passes 3,007 with
  four skips and zero failures (`chief-window-build-and-jvm.log`). Fourteen
  Android cases pass in 32.375 s (`chief-window-android.log`): twelve production
  Chief cases and two admitted-root/capability cases. The new flow retains 1,550
  loaded messages while browsing, then returns to newest and trims to 1,000
  acknowledged messages on an explicit send, preserving the pending row and its
  rendered echo. The final debug APKs compile and the crash buffer is empty.
  `check-chief-window.py` verifies exact source hashes and regenerates/compares
  the committed vectors; its fixture is inert state, not UIKit or a real host.
  This batch postdates signed candidate 705.

- Current-position history policy matches seventeen geometry and eight backoff
  vectors executed through the actual pinned Swift geometry file and store
  backoff body. The async pager itself is Android owner logic, not executed Swift
  concurrency. Six session regressions cover coalescing/retry timing, moving
  away, cancelled replacement reads, paused layout requests, cancelled catch-up
  and overlapping/empty older pages. Two geometry/policy tests plus the existing
  session/window/reading/timeline cases pass 58 focused checks. The full JVM suite
  passes 3,015 with four skips and zero failures
  (`chief-history-lifecycle-build-and-jvm.log`). The source checker compares the
  committed vectors and validates both immutable source hashes. This also
  postdates signed candidate 705 and does not establish fling-target prefetch,
  UIKit comparison, performance or real-host acceptance.
  Fifteen Android cases pass in 33.931 s with the final production build and
  test harness (`chief-history-settled-android-final.log`); the crash buffer is
  empty and the emulator was stopped. The variable-height/quote case requests
  before reaching the oldest row, stops a failed retry beyond six screens,
  prevents a paused layout restarting reads, resumes without another gesture
  and preserves the actual visible message's pixel position after prepend.
  Earlier attempts exposed harness assumptions: index movement alone did not
  represent a reader drag, a send click could precede the enabled control, and
  a deletion assertion could precede its coroutine-driven render. The final
  harness starts with a real drag and uses bounded observable readiness/result
  waits; late-echo and full-button bounds assertions remain. Failed attempts
  are retained in `chief-history-android.log`,
  `chief-history-lifecycle-android-final.log` and
  `chief-history-ready-android-final.log`. These are scoped typed-owner emulator
  checks, not physical-device, matched-iOS or accessibility acceptance.

This is an initial mounted implementation, not Chief parity acceptance. Finish
exact bubble/tail/drawer geometry and colors, thread/reply interactions,
remaining detected-item behavior (including source address/date actions),
fling-target history prefetch, remaining localization
and TalkBack. Deeper uncertainty recovery, new-visit positioning and remaining scroll/keyboard behavior also
remain open. Verify cold process and connection replacement restoration and
wide-screen navigation. Compare matched iOS states and exercise a supporting
real Mac with the Pixel. The verified newer custom host is running with both
saved workspaces retained, but the Mac is locked. Current account/capability and
helper/menu checks are pending; no supporting-host acceptance is inferred.
Local signed candidate 705 includes this mounted port and timeline at clean
source `6f1d7446`. Its stable signer, native libraries/alignment, viewer assets,
debug-component exclusion and real Firebase resources passed strict verification.
It is prepared for a data-preserving Pixel upgrade, not installed or published;
see HANDOFF.md for the exact source/hash and receipts. Candidate 704 is retained
as an older pre-UI artifact.
The new failed-send actions postdate candidate 705 and are currently source/debug
validation work, not part of the prepared signed Pixel candidate.
The send-tail and read/catch-up changes also postdate 705. The first Chief-capable
Mac run 39493457582 failed in the pinned CLI initializer. The exact guarded leaf
fix reproduces that failure and passes locally; follow-up 39498469545 at project
source `d8f268b7`, upstream `a5911a1f`, succeeded. Its archive was independently
verified and launched; current runtime checks are pending manual Mac unlock.
The actual UIKit reference batch is now accepted at run `39513912473`, as scoped
above. Earlier failed runs remain retained as diagnostics.

Chief message bodies, loaded-parent quote text and local pending messages now
preserve literal Markdown/HTML characters. The source backend supplies no rich
text runs, and `MessageCellLayout.buildAttributedBody` begins with verbatim text;
the literal UIKit scene independently demonstrates this behavior. The Android
Chief renderer no longer uses the Agent Feed Markdown parser for those rows.
It underlines detected web/email/telephone links without replacing the message
text or recoloring outgoing links. Web/email taps use the system handler and
telephone taps open the dialer. Android's native detector supplies ranges, so
equivalence to all Apple date/address/phone/locale detection is still open;
source date/address actions and translucent underline fidelity remain pending.
Question option previews retain their existing format-specific renderer.
Both relevant source files are pinned as behavior references; this does not
claim a complete semantic review of either file.

Main and instrumentation APK assembly passed. All **14 Chief production
composition cases pass in 30.684 s** on the disposable API 36 ARM64 emulator,
including literal body/quote content, a held pending send and its echo, exact
Unicode link ranges, email/telephone detection and an injected URL callback. The
crash buffer is empty and the owned emulator was stopped. The source checker
also verifies the two additional pins and executes 21 models, nine requests and
eight question cases. Evidence is under `captures/recovery-20261021/` with the
`chief-literal-` prefix. This source/debug batch postdates signed candidate 705;
it is not installed on the Pixel and does not establish matched visual or
physical acceptance. The preceding 3,015-pass/four-skip JVM result is historical
and was not rerun for this Android-native text-only change.
