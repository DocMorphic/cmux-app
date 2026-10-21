# Chief conversations

Oct 21 transcript-frame checkpoint:
the transcript now fills the available Android surface under both the header and
composer. Stable subcomposition slots measure the bars before computing source
scroll insets; the same editor state/IME connection stays mounted. Resting top
insets use header bottom plus 3⅓ points in portrait and zero extra in compact.
Bottom padding combines the source controller inset, six-point layout ending and
last-row tail overhang to leave the body 16.71 points above the field. Pinned-tail
correction and near-bottom checks subtract the actual after-content padding;
keyboard/editor growth therefore uses the unobscured bottom rather than the full
viewport's bottom. History thresholds use the real full viewport. Catch-up target
observations exclude rows wholly above the adjusted top; outgoing visibility
still follows the source's full collection region.

The plain-background iOS 26 wash uses the executed source ramp: peak opacity
0.60 dark/0.855 light, clearing 46 points below the header. Its window anchoring
accounts for Android's already-consumed status bar. Source drawing order is
transcript, composer, wash, header, then overlay controls; the fade has no pointer
handlers. This ports the inspected iOS 26 plain-background branch. Bottom soft
edge/blur, iOS 27/background branches, exact materials/fonts/insets and animation
still need work; no whole-screen or physical acceptance is claimed.

Actual source execution now covers 50 wash and 54 controller-inset cases alongside
the prior geometry/default/type/string checks. The controller expressions run
verbatim against inert frame inputs, guarded by the exact source SHA. Eighty-five
Chief JVM cases pass with no skips/failures; debug and instrumentation builds
pass. Initial layout checks pass (full viewport, scrolling behind the header,
source resting body gap). The first broad portrait run found three harness
failures after changing list bounds: default swipes began on header controls,
and separate field/Send bounds were read across IME frames. The corrected harness
starts real swipes within the unobscured transcript and uses the existing stable
IME geometry helper; all position/containment/trim assertions are retained. The final portrait run
passes 34 Chief/root cases in 66.588 s, with its two landscape-only assumptions
skipped there. All four landscape cases pass in 6.52 s, including both orientation-specific
cases, full viewport/body-gap checks and normal/accessibility details
navigation. Both crash buffers are empty. Five portrait and two landscape scene
PNG/metadata pairs are hash/fixture/viewport/density verified. The normal portrait,
scrolled-overlay and both landscape images were inspected. Emulator restored to
1080×2400/420 and stopped; no phone/Mac/account/pairing/helper mutation. Logs are `chief-overlay-*` under
`captures/recovery-20261021/`. Physical Pixel/Mac, remaining row/thread/poll/editor/
locale/accessibility/lifecycle/source inventory and full parity gates stay open.

Oct 21 symbol-frame correction: Android now reserves the native 8⅓×14⅔-point
symbol canvas in compact mode, with the source four-point text gap and eight-point
trailing reserve. Portrait keeps its 4.7-point ink reserve and centers that same
canvas over it. The project-authored vector is taller to approximate the measured
native ink; it is not an exported SF Symbol. The actual unmodified Swift geometry
now runs 72 compact cases spanning both the original diagnostic size and measured
native canvas, plus the prior 56 details/five unread/eight name cases. Focused JVM
geometry and debug/instrumentation builds pass. Both real landscape emulator
cases pass in 4.659 s: fixed symbol canvas, 44-point avatar, centered full cluster,
text/symbol/trailing spacing and actual details open/close at normal and actual
28.056-point accessibility body type. Their 2622×1206 @3x screenshot/metadata
hashes and shared authored-scene fixture are verified; both images were inspected.
Both crash buffers are empty. The portrait invocation passes 32 Chief/root
cases in 62.396 s; its two landscape-only assumptions are explicitly skipped
there and pass in the separate landscape invocation. Five fresh portrait scene
PNG/metadata hashes and dimensions are verified; the normal portrait header image
was inspected. The owned emulator was restored to 1080×2400/420 and stopped.

This accepts these header dimensions and interactions, not the full compact
screen. Source and Android font metrics, system/cutout insets, the transcript's
scrolling/initial position, material and animation still differ or need
verification. The source reference's service intro can sit behind the transparent
header. The later transcript-frame checkpoint above supersedes Android's old
header-above-list column; its remaining gates are recorded there. The broader quote/thread,
poll/failed UI, environment/accessibility/IME/lifecycle and real Mac/Pixel gates
remain open. Signed candidate 707 predates this symbol correction.

Oct 21 expanded UIKit reference checkpoint: run `39538527277` at source
`7b54fafa51475be2e920cc464672d625aa3d479c` passed both native system-size
invocations and all thirteen export guards. Artifact `12319300862` has verified
outer SHA-256 `db23803667da5036bf640f42535e6e67b39e95e469d441f1704f2236d648f714`;
source/fixture/PNG/dimension checks pass independently. The four new details/
compact images were inspected. Both details categories retain actual editor text,
clear focus during/after the modal, keep store viewing true throughout, roll
unsupported switches back and close through the unique real primary Back control.
The source VC does not synchronize typed text into `store.draft`; this reference
observes the editor itself. This verifies the existing Android choice to retain
view/read admission during details, not every lifecycle path.

Landscape references have real compact traits and 874×402 bounds, safe area
`[0,20,62,62]`, 44-point avatars and intrinsic title-chevron images 8⅓×14⅔ points.
The portrait layout reserves 4.7 points for ink while centering the same intrinsic
symbol image over that reserve; compact layout reserves the full image. A later
pixel check found roughly 4.7–5 × 12.3–12.7 points of visible ink in both modes,
so the earlier assumption that portrait uses a nine-point-tall symbol was wrong.
Matched compact sizing/insets remain a separate gate.
Details controls report 63×28-point switch frames while their rendered tracks
extend below those bounds; compare ink/control frames before changing the Android
custom switch. Image-view observations can include descendants of hidden views;
use visible screenshot/control regions when selecting metrics. Exact materials,
header overlay/scroll edge, fonts, interactions, animations and physical acceptance
remain open. The expanded offline reference is accepted; whole-app parity is not.

Oct 21 emoji checkpoint: source-shaped bare emoji rows and composer scaling are
mounted. The actual predicate allows one to three adjacent graphemes, preserves
source-accepted non-RGI combinations, and rejects a spaced pair as ordinary text.
Fonts stay at 72 logical points for one and 48 for multiple emoji even at large
body-text sizes. The iOS 26 branch supplies 26.84 points of vertical balloon room,
32.208 at accessibility body sizes, with no fill/tail or tail-spacing discount.
Quotes retain their own outline/connector. Pending and failed sends use the same
bare glyph presentation; existing status/retry UI remains subject to its separate
source presentation gate.

Draft scaling additionally requires no Foundation-trimmed boundary whitespace.
Marked IME text retains the previous base font until composition finishes.
Explicit current-font text measurement now controls the field's growing/capped
height, preserving the editor state, selection, undo and IME connection. This
fixes a reproduced case where a 313-pixel field stayed tall after its font had
returned to body size. The 30-line draft still scrolls below the header with
Send inside; geometry checks wait for actual IME visibility and a stable set of
field/Send/header bounds. Mid-animation and physical IME stress remain open.

Five exact source hashes guard the extracted, unchanged Swift prefilter,
predicate, grapheme count, fixed font size and composer-eligibility bodies.
Ninety-five vectors run through JVM ICU and the real Android runtime, including
families, flags, keycaps, variation selectors, controls, zero-width space and the
192-byte cutoff. `scripts/check-chief-emoji.py` regenerates/verifies the two exact
JVM/runtime fixtures; vector SHA-256 is
`9a6f61d51209117c39a3323381e5e0da190348db5892f5079e7cffcc46526c11`.
The full JVM run passes 3,028 with four skips and no failures; all 32 Android
Chief/root checks pass in 60.276 seconds with an empty crash buffer. Logs are
`chief-emoji-height-accepted-build.log` and `chief-emoji-accepted-android.log`.

Five fresh shared-input PNG/metadata pairs in `chief-emoji-accepted-scenes/`
have verified payload hashes, fixture identity and 1206×2622 viewport at density
3. Normal and accessibility emoji, the failed emoji row and expanded editor were
inspected. Android uses system emoji artwork/Unicode/font metrics: the 72-point
thumb's Android text frame is 89.67 logical points wide, versus UIKit's 77-point
frame. This is not pixel identity or future/older-OS Unicode acceptance. The
source/debug checkpoint does not close animation, rich IME, accessibility,
physical Mac/Pixel or whole-app parity gates, and postdates signed candidate 706.

Oct 21 trimming follow-up: Home queries, work title/status/preview and question
Other text/admission now reuse the composer policy verified against Foundation.
All 62 actual Swift trim vectors are exercised through these consumers, including
U+0085/U+200B trimming and retained U+001C. The full JVM suite passes 3,025 with
four skips and no failures. Localized search, Unicode canonical-equivalence and
remaining visual/interaction/device acceptance are still open.

Latest header/details checkpoint (Oct 21): the centered 60-point portrait photo,
source name capsule/back count/Stop controls, direct details page and exhausted
history service introduction are mounted. Both details entrances preserve the
draft; retirement closes details, and unsupported settings roll back without
changing phone/helper preferences. Full JVM: 3,024 pass, four skip, zero failures.
Final Android: 28 pass in 55.414 s, empty crash buffer. This is scoped source/debug
acceptance; exact materials/transitions, wider lifecycle/accessibility and real
host/device acceptance remain open. Detailed provenance and limits follow below.

Latest text-composer checkpoint (Oct 21): the source/debug port now uses a
growing text-only field with an internal send capsule, Foundation-compatible
send trimming, synchronous send admission and synchronous local failed-row
deletion. The final full JVM run passes 3,020 with four skips and zero failures;
25 production Android cases pass in 46.624 s with an empty crash buffer.
The detailed scope, provenance and remaining gates are recorded at the end.

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

This is a mounted implementation with the scoped bubble port below, not Chief
parity acceptance. Finish drawer/header/composer geometry, remaining typography
and colors, quote taps and thread/reply interactions,
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
Body and pending rows underline detected web/email/telephone links without replacing the message
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

The later dark transcript bubble port uses the pinned continuous tail curves,
circular tailless wrapper, source width reserve/85% ceiling and accessibility
inset, 48-point minimum and centered short text. It measures at the wrapping
limit, then sizes the body to its longest rendered line rather than Android
Text's full wrapped box. Natural 17sp text replaces the inherited Material line
spacing. Padding switches at an actual rendered body size of 28 points; caption
spacing and corners use Android's measured system font. Tail reservation is
subtracted from the following row's body gap. The source dark incoming fill,
five-stop outgoing gradient and window anchor are ported; scroll position is
read during drawing so movement keeps the path cached. Light colors are present
in the component but the app still mounts its existing dark theme. High contrast,
keyboard/rotation gradient behavior and exact color/font matching remain open.

Quotes now sit above replies on the original sender's physical side, with an
outline, at most two lines and the source leading connector control points.
Quoted text follows the source's plain UILabel and has no inline URL actions;
body and pending text retain link detection. Quote taps/thread navigation and
animations are still open. Cards retain their initial action controls; this
does not establish poll or failed-status/badge presentation parity.

`check-chief-bubble.py` guards four immutable source files and executes the
actual shared CoreGraphics geometry/metrics plus the unmodified theme width
function with an explicit accessibility-input seam. Kotlin matches **36 paths**
(18 tailed, 18 shared diagnostic tailless), **11 regular/nine accessibility
widths, five pixel-rounding, four drop and 28 spacing vectors**. The UIKit wrapper
uses circular tailless corners instead of the shared diagnostic path; Android
does the same. The manifest pins are selected behavior references, not complete
semantic review of the four files. Android and UIKit authored scene assets have
the exact same fixture hash.

The final build and full JVM suite pass **3,016 tests, four skips, zero failures**
(3,020 across 404 XML classes). **21 Android cases pass in 41.16 s**: 19 Chief
production compositions plus two real admitted-root/capability compositions.
The crash buffer is empty. Four dark authored scene captures use the same
402×874 logical window (@3x, 1206×2622 pixels) as the UIKit references: runs,
quotes, long/Japanese text and enlarged text. All four PNG/metadata payloads were
hash-paired and visually inspected; actual enlarged body size is **28.056341**
Android logical points, compared with 28 in the UIKit capture. Metadata preserves
the differing clock/date/zone and Android system bars. These are comparable
scene inputs and scoped structural checks, not a matched whole-screen pass:
header, composer, timestamps, glyph metrics/line breaks and other visible
differences remain. TalkBack, full Dynamic Type mapping/AX hyphenation, light/high
contrast, wider windows, performance and real-host/physical acceptance remain.

Initial runtime work exposed a non-settling transcript composition while font
metrics were recalculated; cached measurements keyed by density/style and an
explicit natural text style fixed the observed loop. A later 20/21 run exposed
the harness's linear font-scale assumption. Inspection of the actual installed
Compose unit implementation confirmed nonlinear conversion; the corrected case
solves/verifies its effective rendered size before checking accessibility
padding. The stricter padding assertions remain. A first capture comparison
also exposed wrapped Text boxes staying at maximum width; the final layout hugs
the measured longest line, checked against actual rendered text bounds.
Failed and diagnostic runs are retained. Final evidence:
`captures/recovery-20261021/chief-bubble-hugging-build-jvm.log`,
`chief-bubble-hugging-android-final.log`, `chief-bubble-hugging-crash.log`,
`chief-bubble-swift-accepted/`, `chief-bubble-android-scenes/` and
`chief-bubble-checkpoint.json`. Display overrides were restored to the owned
emulator's original 1080×2400 /420 dpi, then the emulator was stopped. This is
source/debug work newer than signed candidate 705; Pixel installation and fresh
Mac/Pixel/FCM acceptance remain pending. The full-parity goal stays active.

The later text-only composer port replaces the Material outlined field and
external Send button with the Chief branch of ConversationComposerView. Empty
and Foundation-whitespace-only drafts hide Send; an ordinary read error does
not disable a meaningful draft's explicit send attempt. Chief has no attachment
plus or microphone affordance. The internal capsule is 38×28 with a 13×16 arrow,
the source blue fills, trailing reserve 48, left inset 16 and fixed vertical
padding 10. Phone side insets interpolate from 28 to 16 during IME motion; the
source's pixel-ceiled send position is derived from the field's one-line height.
The field grows beyond five lines until the available header/keyboard bound,
then its real vertical scroll state keeps the end selection visible.

The editor uses memory-only TextFieldState with its own IME selection and
composition, plus revision-fenced publication to the retained session. It adds
no saveable plaintext draft. Committed snapshots are published outside input
transformations, following Android's text-field guidance:
https://developer.android.com/develop/ui/compose/text/user-input . A stale queued
snapshot is checked against the current editor before publication. Immediate
navigation flushes unpublished input; owner retirement clears the editor and
undo history and disables further input. Clicking Send reads and admits the
current text synchronously before clearing it. The owner, rather than a view
coroutine, retains FIFO transport/acknowledgment work. A captured draft revision
cannot clear a newer identical draft. Foundation trimming, including U+0085 and
U+200B, happens before id allocation and optimistic insertion; U+001C is retained.
Local failed-row deletion is also synchronous, matching the source store action.

New layout following uses the measured last row after delivery controls or
keyboard/font reflow finish measuring. It corrects overflow only for an already
pinned reader with the tail visible. A new explicit send still returns from
history to newest, then safely trims the acknowledged window; ordinary browsing
retains its viewport and loaded rows. Large-text retry controls must fit fully
inside the list before a test taps them. Sheet action tests wait for the actual
window target bounds to remain stable before a real touch, because OS IME/window
motion is outside Compose's test clock; no accessibility-action bypass is used.

`scripts/check-chief-composer.py` guards four composer/core/geometry sources and
two source string catalogs at `a5911a1f`. It compiles the actual unmodified
portable Swift row, field-height and plain-text trimming functions and executes
60 text-only row, 62 trim and five height cases. The actual local Foundation
whitespace enumeration contains 26 BMP scalars. It also verifies 11 exact source
placeholder/send strings across nine locale resource folders; missing source
send translations inherit English. The committed vector SHA-256 is
`57e6ea7d61959eee2e6105a7edd93d1552d1d8e5d123c4197707a861efdcae8c`.
UIKit-only composer code is hash-checked, not compiled by this portable checker.
Global implemented/reviewed upstream pins remain unchanged.

Final full JVM evidence is 3,024 cases in 405 XML classes: 3,020 pass, four skip,
zero failures/errors. The 25-case Android run comprises 23 Chief production
composition cases and two framed-root admission cases. It includes blank-send
visibility, Foundation trimming, sending through a read failure, a 30-line draft
with its actual end selection and scroll range, same-event edit/navigation,
retirement clearing, history preservation/window trimming and enlarged failed
retry/delete/late-echo suppression. It passed in 46.624 s with zero crash-buffer
bytes. Final build evidence is `chief-composer-accepted-build.log` plus the
subsequent UI-only `chief-composer-sync-delete-build.log`; Android evidence is
`chief-composer-accepted-android.log` / `chief-composer-accepted-crash.log` under
`captures/recovery-20261021/`. Debug/test APKs are recorded by hash in
`chief-composer-checkpoint.json`.

Four fresh dark scene PNG/metadata pairs use the accepted UIKit fixture hash and
1206×2622 viewport at density 3. Their hashes, dimensions and metadata were
verified and all four images inspected, along with the expanded keyboard draft
and enlarged failed-send sheet. The enlarged body remains actually 28.056341
logical points. Accepted files are in `chief-composer-accepted-scenes/`.
The end caret, internal arrow and full sheet controls are visible. This accepts
the scoped text-composer structure and tested behaviors, not full visual parity.

Earlier diagnostic runs remain retained. The classic string-driven field passed
23 cases but its long-draft capture did not keep the end caret visible. The first
state-based batch found a missing public scroll range, a placeholder-sensitive
empty-text assertion and history/footer reflow failures; two incomplete runs
were intentionally stopped. Their instrumentation termination is not a native
crash acceptance result. A later 25-case run and focused run failed after rapidly
reopening the action sheet. Stronger checks established that the sent owner and
editor drafts were empty; the remaining failure waited for deletion admission,
not draft disappearance. Instrumented isolated touch runs admitted deletion
successfully. The final tests synchronize actual target stability, and local
deletion no longer depends on a view coroutine. Temporary traces were removed
before the accepted build/run. An early gated session test also hung because its
fake reply was never completed; that harness error was corrected. None of these
diagnostic runs substitutes for the final passing batch.

Still open: exact glass/material and keyboard resting/interactive pin behavior,
wide-window insets, rich editing/formatting/paste/effects/link previews, full IME,
selection/rotation/process restoration, all locales/TalkBack/contrast/light mode,
header/details/service-intro/drawer, emoji, quote/thread taps, poll/failed-badge
presentation, detector/font/timestamp differences and the broader source audit.
The owned emulator was restored to 1080×2400 /420 dpi and stopped. No Pixel app,
Mac account/pairing or helper enrollment was changed. Pixel 703 and prepared
signed 705 are unchanged; this source requires a fresh signed candidate before
physical upgrade, rather than installing stale 705. The full-parity goal remains
active and real Mac/Pixel/native terminal/FCM/cold notification taps remain gates.

The later header/details port follows the same frozen `a5911a1f` conversation
package. Portrait controls sit at the 16/20-point side margin in 44-point
capsules; the photo is 60 points, with the source's wider 80-point portrait touch
region. The name capsule starts 55 points below the consumed safe top, measures
its actual text and caps the headline at 17–21 points. It grows its 32⅓-point
height by the measured line-height difference. The back control speaks the
original Home unread count as its value; the photo and name both open details.
Stop retains its existing real session action. The source's compact avatar/name
layout is ported too, normalizing coordinates by the status-bar inset already
consumed by the Android shell. Pure geometry tests cover safe tops 0/24/48;
actual landscape/wide-window lifecycle acceptance is still open.

The direct details page uses the source 80-point photo, title, three disabled
call/video/mail circles and two Info switches, without a Backgrounds tab or empty
media sections. Bounded scrolling drives the source's collapse equations.
The transcript behind the page is blurred; exact native material, scroll-edge
pocket, negative-offset rubber band and avatar zoom transitions remain open.
The Dialog explicitly retains its host density: the large-font case inspects the
actual TextLayoutResult and requires fontScale 2 and a title of at least 38
logical points. Opening details resigns composer focus, following source
`openInfo`'s `view.endEditing(true)`; returning preserves the draft and does not
refocus the field. Owner retirement clears and removes the page. UIKit details snapshots have not yet been captured
for a matched visual comparison. The authored Android glyphs and custom switches
still need that comparison, plus full TalkBack/RTL/locale/contrast/light testing.

Chief's backend constructs a direct ConversationInfo with default list state,
does not override `updateListState`, and does not implement background support.
Its inherited method rejects with `-32601 / list state unsupported`. The source
Info page nevertheless offers Hide Alerts and Send Read Receipts; the store
publishes optimistically and restores the previous snapshot on rejection, with a
generation fence. Android now mirrors that behavior in the retained owner. It
does not invent an unsupported Chief wire method, persist a new setting, change
the local FCM/helper enrollment or gate read admission on a transient switch.
The source generation behavior is preserved even for successive optimistic
updates: the newest rejection restores its own previous snapshot, rather than
letting an older rejection overwrite it. JVM tests cover that behavior, missing
initial info, retirement and absence of new send/answer/approval transport calls.

The service introduction appears only after older history is exhausted, including
an empty loaded conversation. It shows the source title and locked subtitle in
caption type. It occupies the same leading index as the old-history row, and
newest scrolling/catch-up offsets account for it. The existing history probe and
loaded-window trim continue to operate on their real measured/message rows.
Exact caption/timestamp spacing and accessibility grouping remain part of the
broader typography/accessibility comparison.

`scripts/check-chief-header.py` guards eight additional geometry/UI/core files
and both label catalogs, compiles the actual unmodified portable Swift sources
and verifies 36 compact frames, 56 direct details frames, five unread capsules,
eight capped-name sizes, 12 quick-action category sizes and list defaults.
UIKit portrait/details code and the unsupported backend branch are source
guards, not executable UIKit acceptance. The checker also verifies 34 source
label values across nine resource folders. The factual Body/Title 1/Caption 2
point-size anchors come from Apple's primary iOS Dynamic Type specifications at
`https://developer.apple.com/tutorials/data/design/human-interface-guidelines/typography.json`;
the exact downloaded SHA is recorded in `headerTypography`. Android's continuous
font scaling interpolates between those source categories. This does not replace
the Android system font or establish whole-app typography parity. Final vector
SHA-256: `807602026bb3ec0451ddd2930c8fbba67a05e852f45b97a31c8615494ba23aeb`.
Global implemented/reviewed pins remain unchanged.

Final build `captures/recovery-20261021/chief-header-accepted-build.log` passes the
full JVM suite: 3,028 cases in 406 XML classes, 3,024 pass/four skip/zero errors or
failures. Subsequent UI-only focus and test-harness builds are in
`chief-header-focus-build.log` and `chief-header-ime-final-test-build.log`.
The final Android log is `chief-header-ime-final-android.log`: 26 Chief
cases plus two framed-root admission cases, **28 pass in 55.414 s**. The matching
crash log has zero bytes. New cases cover both details entrances, the real draft,
disabled quick actions, setting rollback, retirement dismissal, actual enlarged
title metrics and the history-exhaustion introduction. Existing send, keyboard,
read/catch-up, prefetch, history-window, literal-text and failed-send cases still
pass. The earlier two Android failures asserted before queued scroll/dismissal
work settled; tests now wait for observable completion and retain the full
visibility/retirement assertions. Initial compile diagnostics and earlier runs
are retained, not counted as accepted evidence.

A later focus batch hit an intermittent combined send/window-trim timeout. Its
original condition did not distinguish missed touch admission from trimming,
so that log alone does not establish the cause. Three isolated diagnostic runs
all admitted and trimmed correctly, while their recorded touch occurred before
the native software IME became visible. The final harness waits for actual IME
visibility and stable target bounds before its real Send touch, and separately
requires one admitted request and the unchanged 1,000-row window. No product
trim rule was relaxed. Temporary diagnostic flags/logging were removed before
the final batch. Mid-animation input/trim stress and real-device keyboard/modal
read-viewing behavior remain acceptance work; the stabilized batch is not proof
of every pointer/IME timing case.

Fresh captures are in `chief-header-accepted-scenes/`: four hashed reference-input
PNG/metadata pairs at 1206×2622 /density 3, normal/large details, the expanded
draft and enlarged failed sheet. The four pairs' hashes/dimensions/fixture inputs
were verified and all four plus both details images visually inspected. The
reference helper now also supplies unread count zero, matching the actual UIKit
reference options. System bars, clock/time zone and font/line-break differences
remain explicit. A read-only AppKit pixel measurement found the source Stop ink
at 51×51 pixels (17 logical points), versus the initial Android 46×46. The final
vector sizing produces 51×51 too; measurements and script are under
`chief-header-source/`. This validates that one rendered dimension, not glyph or
whole-screen equivalence. `chief-header-checkpoint.json` records final artifacts.

The owned emulator was restored to 1080×2400 /420 dpi and stopped. No Pixel app,
Mac account/pairing, helper enrollment, signing key or prepared signed artifact
was changed. Pixel USB remains absent. Signed 705 is stale relative to this source;
prepare a fresh stable-signer candidate before physical upgrade. Remaining gates
include actual UIKit details/landscape references, full header overlay/status/
edge-fade/blur/zoom/safe-area behavior, complete IME/rich composer/rotation/process
restoration, emoji, quote/thread actions, poll/failed-badge presentation, all
font/locale/contrast/light/TalkBack checks, remaining source inventory and real
Mac/Pixel/native terminal/FCM/cold notification taps. The full-parity goal remains
active; passing local references and typed-owner tests do not complete it.
