# Chief rich composer

The production Android Chief composer now mounts the formatting owner, edit-menu
palette and effect renderer ported from the pinned cmux conversation package at
`a5911a1f92f3eeb11c4d7f3e2ef89204718c5009`. Local optimistic rows retain formatting
through failed sends/retry; the Chief wire and canonical/ACK rows remain plain,
following the source backend. This does not close Chief or full app parity.

## Source requirements

ChiefHomeView mounts the shared conversation with `supportsAttachments: false`.
ConversationComposerView nevertheless inserts Text Effects into the text edit
menu without checking that option. The source formatting editor is applicable.

ConversationComposerFormatting defines four independently combinable styles:
bold, italic, underline and strikethrough. Styles act on the selection; at a
caret they change the typing attributes. Effects act on the selection, or the
whole draft at a caret. Only an empty draft changes its typing effect. Choosing
the effect already shared by the target range removes it. The active style is
the intersection of selected styles; the active effect requires uniformity.

Its 330-point custom keyboard has a title/close control, four style buttons and
eight previews in a two-column grid: Big, Small, Shake, Nod, Explode, Ripple,
Bloom and Jitter. Focus end and send restore the normal keyboard. Send clears
typing formatting before Store admission. Restyling skips marked text; it preserves the
selection and typing attributes. The verified live-size call path removes nonempty rich formatting and preserves
its earlier base for later restyling; emoji-base transitions follow their own path.

The renderer derives bold/italic fonts, underline/strike, and Big/Small type size
from semantic runs. Big enlarges paragraph pitch. It hides effect ink in the base
layout and draws a separate layer per composed character, switching to words
above 80 UTF-16 units per effect run. Wrapped word fragments are unioned. The
motion cycle is three seconds. Composer seed is zero; tile previews use a stable
effect-name seed. Reduced Motion retains effect typography and static glyph ink.
The source samples motion at 60 Hz, removes only redundant interior equal-pose
frames, and omits an entirely opaque opacity track. Its linear transform tracks
contain actual matrices; a future renderer must preserve that interpolation,
not simply animate a few scale/position endpoints or re-evaluate nonlinear poses.

ConversationStore.send trims and stores the formatting on an optimistic row and
passes it in ConversationOutgoingDraft. ChiefConversationBackend.send forwards
only `draft.text` to MobileChiefClient; canonical mapped rows and the local ACK
fallback have no text runs. Failed optimistic rows retain their formatting for
retry. Generic conversation wire/purchase/media capabilities do not establish
that Chief supports them. The Android port must preserve this distinction and
must not extend the Chief RPC spec by inference.

## Implemented engine and source evidence

`NativeConversationRichText.kt` ports semantic style/effect names, UTF-16 run
normalization with later-run precedence, plain overrides, clipping/merging,
plain-edit prefix/suffix carry, Foundation send trim, per-unit lookup, common
selection attributes, style toggles and effect toggles. Swift's full 64-bit semantic
style mask survives runs while wire names emit only the four known flags. It deliberately
does not equate plain-edit carry with UIKit typing-attribute inheritance.

`NativeConversationTextEffectMotion.kt` ports all eight poses, stagger/duration/
font-scale rules, wrapping UInt64 randomization, UTF-8 FNV-1a identity seeds and
the shared renderer's compressed 60 Hz transform/opacity tracks. Distances in
poses use font-size units; generated transform frames use the supplied font size.
This engine checkpoint originally supplied renderer inputs only; the subsequent
mounted implementation is described below. It does not prove motion acceptance.

`scripts/check-chief-rich-text.py` builds the whole unmodified Swift core as a
module and the whole shared renderer as an executable dependency of the fixture.
It uses Foundation semantic attributed-string keys and real QuartzCore matrices.
No source-body extraction, replacement rich-text implementation or UIKit stub is
used. UI/Store/backend and two original test files are hash-bound for applicability
and authored inputs, not executed by this fixture. The committed output contains:

- 104 semantic cases and 2,473 sequential style/effect mutations, including
  mixed/empty/out-of-bounds selections, overlapping/plain/unknown spans, both
  preserved ends of a middle edit, Foundation whitespace, emoji UTF-16 offsets,
  combining/ZWJ text and RTL;
- 672 pose probes, seven UTF-8 seed observations and 72 keyframe tracks across
  three font sizes, glyph/run positions and full-width unsigned seeds;
- 3,501 transform frames with all 16 matrix components, plus 792 opacity frames.

Nine JVM cases compare the Kotlin engine against those exact outputs. The default
checker recompiles/replays and requires byte equality with the committed corpus;
its receipt binds source, compiler, generator, driver, inputs and output hashes.
See `third_party/cmux-chief/README.md` for the command. Local raw evidence lives
under `captures/recovery-20261025/rich-text-reference-*` and is excluded from Git.

Final whole-source replay v6 matches corpus SHA256
`30dda39ba706fc61ee8ce78ca0d70aae6474b53553ef4f219ac82e7ac7d612b0`.
Final JVM/build v5 passes 3,538 cases in 474 suites: 3,534 passed, four existing
SSH skips, no failures/errors; the nine new cases take 0.302 seconds. Both debug
APKs build, all 22 native libraries match the preceding checkpoint, 16 KiB ZIP
alignment passes and frozen signed722 is unchanged. No emulator/phone install
or mounted UI/runtime acceptance is claimed. The earlier v1 Kotlin test compile
error was an ambiguous fixture helper overload, fixed with an explicit return
type/receiver. Preliminary focused/full results and narrower mask corpora remain
local historical evidence; final acceptance is the full-width v5/v6 pair.

Checkpoint: `captures/recovery-20261025/rich-text-checkpoint.json`.

## Mounted editor and remaining acceptance — 2026-10-25

The installed Compose Foundation 1.9.1 source explicitly supports `addStyle` in
OutputTransformation only. Its TextFieldState does not store styled editing
annotations, and output annotations do not track subsequent edits. Text/selection/
composition observation via snapshotFlow alone also does not provide an exact
edit transaction stream. `NativeChiefRichEditing` now owns semantic runs and
rich inverse-edit history alongside the retained platform field. Exact buffer
changes handle ordinary input. A wrapped actual InputConnection preserves the
operation ranges that Foundation minimizes away for equal text. Deferred IME
operations replay at the real outer batch boundary, against the then-current
accepted document, preserving formatting changed while a batch is open.
Captured input strings are immutable; rejected commands cannot admit a speculative
style change. The model requires agreement with committed field text, selection
and composition. Owner reset/undo/retirement and effective text-scale changes
invalidate old connections.

The existing TextFieldState, caret, growth, internal scroll and synchronous send
authority remain in use. Revision notifications are posted outside input
transformations. Formatting-only changes advance the owner revision; an older
captured send cannot clear a newer rich draft. Trim shifts runs with the text;
failed rows keep them, retry reuses the client ID, and an ordinary plain ACK
clears them. No rich Chief RPC or disk-persisted rich draft is inferred.

The custom palette replaces the IME and supplies the four style/eight effect
controls, source labels, selected states, preview effects and close/refocus.
It exposes the Text Effects edit-menu entry and a custom accessibility action.
Its fixed 330-point source layout subtracts the host's already-consumed Android
navigation inset; a constrained landscape surface can scroll its controls.
That adaptation and Android surface material are not UIKit geometry/material
equivalence. English/Japanese labels and the other locales' source English
fallback are checked by `check-chief-rich-composer-strings.py` (144 total values;
Undo/Redo are authored Android labels).

The overlay uses the actual field/text layout and font resolver. It preserves
link annotations, hides base effect ink, rasterizes grapheme/word units and
samples the source's compressed matrix tracks linearly. Visible glyph images
are cached, share the three-second phase and move with internal scrolling.
Disabled animator duration keeps static ink/typography. While marked text is
active, the composer draws static base ink; composition/effect presentation needs
broader native comparison. Mounted Android motion is checked below; renderer
performance and matched UIKit animation remain open.

Stock clipboard access now delegates the actual platform entry and menu behavior,
while recognizing its exact replacement transaction to isolate rich undo groups.
The installed Foundation 1.9.1 source uses NeverMerge for native paste/cut history;
that flag is not exposed to InputTransformation. The bridge matches old text,
selection, replacement and the actual change range, including equal-text paste.
It fences clipboard callbacks and input actions to the current mounted visit.

The retained rich owner keeps text, runs, selection and history through navigation
and configuration changes. Ending a visit fences its old field/input connection
before Foundation's blur can collapse the retained selection. Pausing suspends
input; resuming the same mount admits its still-valid connection. A replacement
mount cannot revive the old one. Configuration saved state contains only the
visit, palette/focus flags and selection integers. Text/runs/history stay in the
existing memory owner. Focus restoration waits for the new window before restoring
the saved selection and requesting focus; leaving the conversation closes the
palette for the next visit. This does not add process-death draft persistence.

Remaining acceptance includes HTML/URI/attributed clipboard transfer, wider
input/autocorrect/composition and reversed selections, other focus routes, process
restoration, remaining empty/font-trait/deep-navigation cases, animated scrolling/performance, all locale/contrast/
light profiles, real TalkBack, hardware keyboard and physical Gboard. Native public
input/control traces do not prove gesture or IME equivalence. Real account/backend/
device, release and all thirteen broader gates remain active. Account/pairing/
signing, frozen signed722 and global upstream pins remain unchanged.

## Bounded rich-effect reuse during animated scrolling — 2026-10-25

The mounted renderer now retains glyph images/keyframe tracks across viewport
scroll updates. A per-overlay 8MiB weighted LRU, with a 64KiB minimum entry weight,
limits both pixel storage and the entry count (at most 128). Layout/density/ink/
resolved-font/locale/seed changes replace the cache. Scroll updates still prepare
newly exposed units. Evicted bitmaps are left to Android's lifetime management so
recorded drawing commands can finish using them.

The real mounted field's atrace test explicitly enables motion, observes 146,376
changed viewport pixels between controlled frames, and makes four 90px scroll
round-trips. The trace-only original renderer prepares 230 rasters during warm-up
and 620 during the repeated viewport pass. The optimized renderer prepares 80 and 0.
All 32 controlled active/rest/next-cycle frames for the eight effects are identical
to the previous accepted Android renderer. This measures avoided preparation;
it does not establish physical FPS, GPU budgets or long-distance scroll smoothness.

Final debug APK build-v2 passes; disposable API 36 runtime-v2 passes 32/32 in 96.742s,
with empty crash log, no observed ANR, restored settings and owned emulator exit0.
It includes the previous 31 composition/clipboard/lifecycle/render regressions plus
the animated scroll trace. All 22 native libraries and frozen signed722 match;
16 KiB ZIP alignment passes. JVM sources are unchanged; the earlier full 3539-pass /
four-skip JVM checkpoint is retained and was not rerun for this draw-cache change.

The first scoped run passed its one method but hit an old driver pluralization
assertion ("1 tests"); it is excluded from final acceptance. The final driver fixes
that exact count, and motion is explicitly enabled in the final trace fixture.
The trace-only original baseline fails the reuse assertion as expected. Original
logs, traces, APKs and source snapshots are retained. Evidence:
`captures/recovery-20261025/rich-scroll-checkpoint.json`.

All thirteen full-app completion areas remain open. Native live-font controller
observations below are verified, but a mounted native global Settings transition
is still needed before choosing Android's font-reset behavior. Physical Gboard/
hardware, TalkBack, Pixel/Mac workflows, GPU budgets and matched UIKit presentation
remain. Account/pairing/signing, global source pins and signed722 are preserved.
Full parity remains active; no physical device was modified this continuation.

## Priority formatting keyboard commands — 2026-10-25

The original ConversationComposerView.swift at pinned a5911a1f defines priority
Command-B/I/U keyCommands, permits them when isEditable and invokes toggle(.bold),
toggle(.italic) and toggle(.underline). Its full attributed source SHA256 is
0c330915c1f033f577b6b44da624da41a0b862c463b88b44ec9b9f4e37e7d0a2, already recorded
in the manifest. The Android composer now handles Ctrl/Meta-B/I/U in its preview
key handler, before Foundation processes them. The existing semantic toggles
preserve mixed selection styles, effects, first-chunk typing and owner publication.
Formatting chords with Alt/Shift are not intercepted. Key-up/unrelated events
fall through. Current mounting, visit, resume and editable-owner guards cover
formatting and existing Z/Y undo/redo dispatch.

Actual Android View key dispatch verifies selected B/I/U combination, both Ctrl
and Meta families, left/right modifier keys, partial-selection unformatting and
unchanged Big effects. Palette selection and published owner runs agree. At the
caret, a multi-commit Foundation batch styles only its first chunk; Meta-Z,
Ctrl-Shift-Z and Ctrl-Y restore semantic history without inserting shortcut
letters. Extra-modifier chords, a retired disabled field, paused Activity and
obsolete current-owner predicates cannot apply formatting; resume permits it.

Final build-v3 and combined runtime-v2 pass 38/38 in 122.464s. Runtime-v1 passed
all four new keyboard checks but the existing real system-font check asserted
focus while the new window was still restoring it. The final fixture waits at
most 10s for actual focus, then performs the same assertions; it neither requests
focus nor changes production restoration. Build-v1 required the Compose test API
opt-in; build-v2 was the first successful APK pair. Those attempts remain excluded
from final combined acceptance. Final crash log is empty, settings restore, owned
emulator exits 0, all 32 controlled effect frames are identical and warm scroll
preparation remains 80 then 0. All 22 native libraries/frozen signed722 match;
16 KiB ZIP alignment passes. The previous 3,541-pass/four-skip JVM checkpoint is
reused after binding unchanged semantic/JVM sources and corpora; only the mounted
UI handler and Android instrumentation changed. Evidence:
`captures/recovery-20261025/rich-keyboard-checkpoint.json`.

The keyboard source is hash-mapped, not newly executed through UIKit hardware
dispatch. Injected View events do not establish physical keyboard/global OS
shortcut behavior, actual Gboard, TalkBack or Pixel/Mac acceptance. Wider input/
clipboard/focus, empty/font traits/deep navigation, long-distance scrolling/GPU
budgets, matched UIKit pixels and all thirteen full-app gates remain open.

## Mounted global system-font behavior verified and ported — 2026-10-25

[Run 40175245243](https://github.com/DocMorphic/cmux-app/actions/runs/40175245243)
at fc39577c42420931ff374b6c505499dae01b61c8 succeeds on iOS 26.4.1: four cases /
49 public actions / 53 records in 76.917s. Eight actual simctl system changes and
readbacks propagate to the same original editor, controller and application;
Large is restored. Artifact 12591394770 is 191,285 bytes and matches service SHA256
900eaad6534d6af9d850cb8464622aac4f88ad13abdd09c97307b4c314df0665.
The exact eleven-file native source snapshot independently reproduces all five
canonical files. The new archive-bound GlobalFontObservations.json projection
hashes 027c3a5ce706548777e490e0ebc9614715bb0efe7cc675a0d820884b0d9c9602.
Replay instructions are in tests/chief-rich-composer/README.md. Existing accepted
transaction/controller traces and the semantic/matrix corpus remain unchanged.

In these nonempty cases, a real Large→Accessibility Medium→Large change removes
semantic runs, marked text, typing decorations and undo/redo while preserving text
and selection. Selecting the new size really changes the span font 17→28 points.
Later typing can restyle from the retained earlier 17-point base despite the still-
enlarged global category. The emoji case resets 115.2-point Bold/Big emoji to body
28; adding a space uses current body 28, deleting it restores emoji 72, and returning
the system category resets to body 17. Subsequent source Store sends are plain to
the authored rejecting backend. This does not execute real Chief RPC/account code.

NativeChiefRichTypography stores current body, retained base, display/typing points
and emoji classification with the memory-only rich editing state. It forks/adopts
with actual edit predictions, so rejected commands or obsolete batches cannot
admit a speculative font change. First mount initializes the actual platform body
metrics; later effective scale changes clear the observed nonempty rich/history
state, preserve selection and invalidate old input connections. Same-value public
field replacement commits composition. No-op unmark/deletion does not rebase an
already-reset font. Source emoji-base transitions and retained-body restyling are
preserved; placeholder sizing stays on the current body. Plain field measurement
uses the existing source-rounded line/height policy. The obsolete per-view emoji
font cache was removed; Unicode classification behavior remains checked.

The new JVM replay checks all 53 text/run/selection/composition/typing/history/
selected-control/span-font states against the original capture. ICU4J supplies the
same Unicode policy through the separately checked Foundation corpus. Full final
build-v5 passes 3,545 tests in 476 suites: 3,541 passed, four existing SSH skips, no
failures/errors. Final debug/test APK build-v6 succeeds. Android runtime-v4 passes
34/34 in 97.581s: the earlier 32 regressions, a controlled public-InputConnection
metric-reset case and an actual system-scale lifecycle case. The latter verifies
the retained computer/editor, cleared owner-published formatting and history,
selection/focus/palette state, subsequent typing at the retained base while the
real scale remains enlarged, and the next reset. The input case rejects an old
open batch even on close, ends marked text and checks no-op commands. Deterministic
transaction cases suspend OS binding; actual system IME is used in palette and
Activity cases. API/transport fixtures are read-only; physical Gboard/real-host
acceptance is not implied. Crash log is empty, settings/window read back restored,
no app ANR is observed and the owned emulator exits 0.

All 32 existing controlled effect frames match the previous renderer. Warm animated
scroll preparation remains 80 rasters then 0 on four repeated viewport roundtrips.
All 22 native libraries and frozen signed722 match; 16 KiB ZIP alignment passes.
The 128 reference guards pass, and replay rejects four altered archive/source/
canonical/bridge inputs. Global pins and real account/pairing/signing are unchanged.

Earlier attempts remain for audit. Native-v1 stopped before testing on a container
query timeout; v2 compiled/tested but its 10s propagation deadline was too short.
One accepted v3 command/readback alone takes 12.908s. V3 keeps strict categories with
a 60s observation window, 75s global action export bound, explicit completion and
failed-action rejection. The baseline Android case kept runs/history; the port
corrects that observed mismatch. Intermediate Android builds/runs and the final
body-style precision adjustment are retained but final acceptance uses build-v5 /
v6 and runtime-v4. Raw sources/APKs/XML/traces/receipts are local under
`captures/recovery-20261025/rich-global-fonts-checkpoint.json`.

This verifies the observed nonempty short drafts and controller lifetime. Empty-
draft history, Bold Text/legibility (not handled by the current scale observer),
other font traits/categories, wider input/clipboard/focus and deep navigation/font
lifetime still require reconciliation. The source's priority Command-B/I/U actions
also need the Android keyboard port; current Z/Y history handling is insufficient.
Live long-text scrolling/GPU budgets, physical Gboard/hardware, TalkBack, real
account/host/notification/terminal/release and matched UIKit pixels remain open.
Android uses its actual platform font-scale metrics; the 17/28-point JVM context
comparison does not claim identical SF/Android typography. No full-app completion
gate is closed. Full parity remains active.

## Native live-font controller observations verified — 2026-10-25

[Run 40171337742](https://github.com/DocMorphic/cmux-app/actions/runs/40171337742)
at 96c52a8e8be1ed3e88806a49a3dc8bae398c6359 passes the original pinned UIKit
controller/composer in actual Large and Accessibility Medium: four cases / 44
public actions per run, eight cases / 88 actions / 96 records total. Tests take
13.281s and 11.082s on iOS 26.4.1. Artifact 12590397040 is 280,782 bytes and matches
service SHA256 332233c17389359a2655e3eb439afbae7fd6836f0bf72520343c8a5fc0c0d5ce.
The exact commit's nine-file source snapshot independently reproduces all ten
canonical files. All 116 Python reference guards pass; original transaction
Cases.json/NativeObservations.json remain unchanged.

The public controller trait override propagates to the original editor. A changed
category can clear rich runs, caret formatting, marked text and undo history while
retaining text/selection. Reapplying the already-effective category preserves the
observed formatting. Later undo preservation differs when UIKit receives otherwise
identical plain attributes; a blanket clear-history rule is not established.

The run's global application category stays fixed. Actual span/body fonts still
follow that global category, including the observed emoji-to-body font reset.
This is not a live global Settings transition, rendered pixels or physical IME
acceptance. Before porting Android global font behavior, observe a mounted native
global-category transition and its continued typing/restyling/history behavior.
No Android font-reset behavior was changed from this narrower probe. Source
applyScaledFonts rebuilds plain attributed text; the emoji-base path explicitly
preserves semantic keys. Raw evidence: captures/recovery-20261025/
rich-fonts-native-checkpoint.json. Full parity and the live-font gate remain open.

## Rich continuity and mounted motion verification — 2026-10-25

Full continuity build-v11 passes all 3,543 JVM cases in 475 suites: 3,539 passed,
four existing SSH skips and no failures/errors. Its production/JVM sources match
the final runtime snapshot; subsequent changes are Android test fixtures and Git
build metadata. Final APK build-v13 succeeds. All 22 native libraries and frozen
signed722 match the previous checkpoint; 16 KiB ZIP alignment passes. The original
native observations, semantic/matrix corpus and copied locale labels are unchanged.
The earlier native/label guard results below are retained, not claimed as rerun.

Final untraced runtime-v10 passes 31/31 in 90.993s on disposable Android16/API36:
the earlier twenty methods plus eleven new mounted checks. Six new rich-composer
methods cover stock clipboard paste/cut and equal-text rich undo, an actual stock
Paste toolbar touch, ink at both ends of a scrolled effect draft, and stale input,
clipboard and old field callbacks after ending a visit. Three actual Activity
lifecycle methods cover recreation with selected Bold/Big palette and undo/redo,
rotation/background followed by account revocation, and normal system keyboard
restoration with noncollapsed selection followed by a newer selection on resume.
They use the retained production computer/session and a controlled read-only API;
no backend mutation is admitted. A production navigation method checks retained
rich history and selection, palette closure on return and owner retirement.

The eleventh new method captures the actual mounted renderer for all eight effects:
active pixels differ from rest, rest remains stable and the next cycle moves again.
It changes the actual OS animator setting live, verifies static ink across another
cycle, then re-enables motion. This uses a controlled Compose frame clock and
isolated canvas; it does not measure physical smoothness, GPU/frame budgets,
animated scrolling or UIKit pixel equality. The scroll method checks static visible
ink and retained semantic runs, not animated scroll performance.

Deterministic public-InputConnection cases still suspend OS binding. Two original
palette cases and the three Activity cases bind the real system IME; normal-keyboard
lifecycle checks assert OS IME visibility. This does not establish physical Gboard
or hardware-keyboard acceptance. The final crash log is empty, settings/window
values read back restored and the owned emulator exits0. Selected palette, normal
keyboard, scroll endpoints, stock toolbar and active/rest screenshots were inspected.
Fixture system bars/materials do not establish whole-app presentation parity.

Earlier continuity attempts are excluded from combined acceptance. They exposed
Foundation blur collapsing the retained selection and late teardown mutating saved
focus; mounted/paused/visit fences and window-focused selection restoration fix
those paths. Android's clipboard preview also physically covered the Paste target;
the final fixture waits for it and touches the empty upper fixture area to dismiss
it before the actual stock toolbar tap. Focused runtime-v9 passes2/2 in10.907s;
the final31 run includes both checks. Temporary lifecycle tracing used for diagnosis
is absent from final sources. One early read-error send wait failed; the diagnostic
check and final combined run pass without a production send change.

Two preexisting account HTTP fixture races were repaired separately: unused queued
failure responses could leak into a later refresh, and sibling read cancellation
could defeat an exact request-count assertion. Phase-based responses and bounded
request rendezvous preserve the intended assertions. Full JVM checks and60 actual
HTTP replays (30 per method) pass. Production authentication code is unchanged.
Earlier failed XMLs and raw traced runs remain local for audit.

Evidence: `captures/recovery-20261025/rich-continuity-checkpoint.json` and
`rich-continuity-handoff.json` bind current sources, tested APKs, archived source,
JVM XMLs, driver, logs, screenshots, restoration and native integrity. Raw artifacts
stay local; source/tests/docs are committed. All thirteen full-app gates remain open.

## Earlier mounted Android verification

Final build-v12 passes all 3,543 JVM cases in 475 suites: 3,539 passed, four
existing SSH skips, no failures/errors. The native editor trace test compares
all 141 recorded states, including text/runs, selection/composition, typing
metadata, undo/redo availability and palette selection. The matrix sampler is
checked between the actual QuartzCore frames. Session coverage includes
formatting-only revisions, trimmed sends, rejection/retry/plain ACK and owner
retirement. Both debug APKs build. All 22 native libraries and frozen signed722
are unchanged; 16 KiB ZIP alignment passes. The 112 existing native-reference
guards pass, and the catalog checker verifies all 144 label values.

Runtime-v5 passes 20/20 in 43.986 seconds on disposable Android 16 / API 36.
Eight new composer cases cover first-chunk formatting in a real field batch,
same-text replacement, modern composing/replace APIs, rich undo/redo, an obsolete
open batch after reset, code-point deletion, rejected same-valued composition,
and a formatting change during an open batch with mutable input text captured
at call time. Two of these cases exercise the actual system keyboard with the
palette: selected Bold/Big/send and enlarged RTL inventory/close/keyboard return.
The other six directly call the actual Foundation InputConnection while the test
interceptor suspends OS binding; Gboard cannot independently finish their marked
text. That deterministic seam is not physical Gboard acceptance.

One new renderer method records ten actual static raster/layout profiles: all
eight effects on the source three-grapheme string, 22 words at scale 2 across
style boundaries and five wrapped lines, and an RTL detected link. Base effect
spans are transparent, actual visible glyph pixels are present and the link
annotation survives. The other eleven methods are existing composer/navigation/
retirement/emoji/reply/large-text and underline-raster regressions. Screenshots of
the selected palette and enlarged RTL controls were inspected. Their fixture
system-bar styling is not whole-app presentation acceptance. The crash log is
empty, settings/window values read back restored, and the owned emulator exits 0.

Earlier attempts are excluded from acceptance. They exposed a keyboard show
overtaking the palette, transient focus loss on output restyling and RTL tiles
anchored to the wrong edge. The final field suppresses automatic keyboard show
while the palette is requested, observes field/group focus after a 100 ms settle,
and places tiles from physical left. The early capture helper also assumed one
semantics root despite selection-handle windows. One full build-v10 run hit the
unchanged account test's nondeterministic forced-refresh count (expected 2, saw
1); final full build-v12 passes. Raw attempts are retained, including that failure.

Evidence: `captures/recovery-20261025/rich-composer-mounted-checkpoint.json` and
`rich-composer-mounted-handoff.json`; the final runtime directory is
`rich-composer-runtime-v5`. These local, hash-bound logs/binaries are excluded
from Git. Source, tests, copied labels, attribution and these scope notes are
committed. None of the thirteen full-app completion gates is closed by this run.

## Native transaction reference candidate — 2026-10-25

The optional original host mode --with-rich-composer now mounts the actual pinned
controller with attachments disabled. Eleven synthetic cases contain130 actions
per actual system category. They exercise public UITextInput editing/selection/
marked-text APIs, native undo/redo admission, source palette toggles, focus reset,
effect layers and source Store send/reset behavior. The delegate's real Text
Effects action is obtained with two authored suggested menu groups, then invoked
through a temporary UIButton primaryActionTriggered event. No private selector,
source-package modification or replacement formatter is used.

Palette/send actions are programmatic UIControl events, and UIKit input APIs do
not prove actual IME, hardware-keyboard or native-menu gesture behavior. There
are no pixel/XCUIScreen exports. The in-memory backend rejects or returns a plain
ACK by fixture choice; this is not an execution of Chief backend mapping,
correlation, RPC or account/transport behavior. The hosted manual workflow
requests actual Large and Accessibility Medium. Independent export guards bind
source, cases, exact action order, unchanged editor identity, ranges/attributes,
source palette inventory, undo admission and fixture-only writes without imposing
unobserved formatting outcomes. Native compile/run/export acceptance remains
required before interpreting the observations or mounting the Android editor.

The four old generated hosts are byte-identical except truthful local dirty
provenance (237/240/240/239 files); the new mode generates239 files. Existing
source/package pins, Android code, signed722 and personal data remain unchanged.

## Native transaction reference accepted — 2026-10-25

[Run 40157384521](https://github.com/DocMorphic/cmux-app/actions/runs/40157384521)
at 60e3fcef609d6863bfb2615c2cc0190a872e4b8a passes eleven cases / 130 actions in
each actual Large / Accessibility Medium category. The native tests take 37.627
and 30.018 seconds. Runtime is iOS 26.4.1 (23E254a). Artifact 12585616903 is 525,195
bytes and its downloaded SHA256 agrees with the service:
7d3316fe6bc0a886878ccccdd19c3fdaff69caab690d467ed9fd2086afb2a5e5.
A six-file verifier/fixture/source snapshot from the immutable native commit
independently reproduces all 24 uploaded canonical files. All 282 snapshots pass the export guards; their shared semantic traces and
specific display differences were inspected. No screenshot or pixel was captured.

In these specific public-API traces:

- A caret Bold or empty-draft Nod toggle formats the first inserted chunk, then
  typing semantic keys reset. Repeated insertion is not conventional sticky bold.
- Marked ka/かな text retains Italic typing through both marked updates; unmark
  resets typing metadata while retaining the composed span. Undo/redo restores
  the native grouped text and rich metadata. This is not actual IME acceptance.
- Replacing selected aa with aa leaves the string aaaa unchanged while shrinking
  the initial two-unit Bold run to one unit. Exact edit intent is necessary.
- A short composed-character sample creates three effect glyph layers; an 88-unit
  repeated-word run creates 22. Source animation tracks are installed, without
  proving rendered frames, movement, accessibility motion or Android segmentation.
- Source Store sends trim the styled draft, clear the editor/palette/typing state,
  and preserve runs on a fixture rejection. A deliberately plain fixture ACK
  replaces them. That backend choice does not execute Chief's adapter.

Text, selection, marking, runs, typing semantic keys, undo flags, source control
selection and Store/draft observations agree across the two categories. The
committed tests/chief-rich-composer/NativeObservations.json preserves 141 shared
semantic states and 282 display profiles. Base font observations are 17/28 points;
Big is 27.2/ 44.8 points. The composer paragraph uses natural pitch (observed minimum/
maximum line heights 0), so a blanket fixed-pitch rule would be incorrect here.
These are source platform/font inputs, not matched Android glyph measurements.

Presentation is explicitly separate. Two Large first-open records (all-eight-
effects and grapheme-effect-units) have paletteInWindow=false; the following
toggle records are true. AX Medium first-open records are true. The cause is
unestablished. Their actual geometry is preserved in two Large/one AX palette
layouts and category-specific display records. The initial projector correctly
refused to collapse window presence; its final schema keeps that property and
the explicit differences in display data while only collapsing equal text/
formatting semantics. No capture/verifier check was removed to assert visibility.
Stable visible keyboard/menu geometry, gestures, pixels and motion remain open.

The projector revalidates every canonical payload using the immutable source
verifier and checks all receipt file hashes plus the service-bound archive hash.
Use scripts/project-ios-chief-rich-composer.py with the accepted canonical/source
snapshot, exact native commit/run/artifact identities and archive. Default mode
requires byte equality with NativeObservations.json; --output explicitly writes
a projection. It binds source test, verifier, projector, fixture and all 22 native
payload hashes. Raw source/runtime/ZIP evidence is local under recovery-20261025.

The installed Foundation 1.9.1 commitEditAsUser checks operation count and runs
InputTransformation even for same-text replacement, but skips composition-only
updates. Official Google Maven 1.12.1 sources expose tracked SpanStyle/ParagraphStyle
with default-enabled styled editing and buffer-scoped handles. Their undo record
still contains strings/selections and replaces text on undo/redo. This is source
inspection only, not a rich-undo runtime proof or reason to assume that an upgrade
solves semantic effect ownership. No app dependency changed. Exact source/archive
hashes are in rich-composer-foundation-source-v1/receipt.json.

Android's retained formatting owner, exact transaction/undo/composition adapter,
full source palette and glyph overlay are next. Production Chief remains plain
text; all broader feature/UI/physical/backend/release gates remain active. No
personal device, account, pairing, Keychain, purchase or signing operation ran.
Frozen signed722 and global implemented/reviewed/audit/detected pins are unchanged.
