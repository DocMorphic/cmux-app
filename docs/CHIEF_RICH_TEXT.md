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
selection and typing attributes. Dynamic type/emoji font changes require checking
the actual composer call path before assuming rich spans survive them.

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
and composition. Owner reset/undo/retirement invalidates old connections.

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
restoration, live font changes, animated scrolling/performance, all locale/contrast/
light profiles, real TalkBack, hardware keyboard and physical Gboard. Native public
input/control traces do not prove gesture or IME equivalence. Real account/backend/
device, release and all thirteen broader gates remain active. Account/pairing/
signing, frozen signed722 and global upstream pins remain unchanged.

## Native live-font probe candidate — 2026-10-25

The pinned source's applyScaledFonts replaces nonempty attributedText using plain
body attributes and restores selection, while its emoji-base update explicitly
preserves semantic keys and restyles. Android currently retains its rich metadata
on font changes. The effective native behavior, including marked text, typing and
undo, needs observation before choosing the Android change.

FontCases.json adds four separate cases / 44 public actions for formatted selection,
emoji-base changes, marked text and caret formatting. The unchanged original UIKit
package runs under public controller traitOverrides, with actual controller/editor
category, span fonts, selection, runs, history and fixture sends recorded. This is
a controller trait transition, not a global Settings change or physical IME check.
The prior accepted Cases.json and NativeObservations.json are unchanged.

All 116 Python reference guards pass; the Swift fixture parses and the host binds
both fixture resources to the exact package tree. Native UIKit compile/run/export
acceptance is pending. Do not clear Android formatting from source inference alone.
Use the fonts probe on the existing ios-chief-rich-composer workflow; verify its
artifact against the exact source commit with --font-cases. Account/pairing/signing,
signed722 and global pins remain unchanged. Full parity stays active.

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
