# Chief rich composer

The standalone Android formatting and motion engine is ported from the pinned
cmux conversation package at `a5911a1f92f3eeb11c4d7f3e2ef89204718c5009`.
The production Chief composer remains plain text. The palette, edit transactions,
retained formatting and rendered effects are still to be implemented and verified;
this checkpoint does not close Chief or full app parity.

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
This is a renderer input, not a mounted renderer or animation acceptance.

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

## Editor integration still required

The installed Compose Foundation 1.9.1 source explicitly supports `addStyle` in
OutputTransformation only. Its TextFieldState does not store styled editing
annotations, and output annotations do not track subsequent edits. Text/selection/
composition observation via snapshotFlow alone also does not provide an exact
edit transaction stream. A correct integration needs a coherent formatting owner
and verified edit/undo transactions; a longest-prefix/suffix heuristic alone is
insufficient for repeated text, composing replacements or same-text edits.

Retain the existing state-based Chief editor's caret, IME, growth and send authority
while resolving that integration. Do not add palette controls whose spans silently
drift or disappear. Confirm the original typing/selection/undo/marked-text behavior
through the real pinned UIKit composer, then verify the Android transaction model,
palette and glyph overlay together. Local xcodebuild reports CommandLineTools,
so native UIKit execution requires the established hosted reference route.

Remaining acceptance includes draft navigation/retirement and owner guards,
formatting-only changes, exact selection and caret typing rules, paste/undo/redo,
composition and autocorrect, sending/failed retry/ACK replacement, internal field
scrolling, effect loops and reduced motion, dynamic type/emoji/RTL/dark/light,
TalkBack, accessibility-sized palette and physical Gboard/hardware input. Account,
pairing and signing data, frozen signed722 and the global upstream pins remain
unchanged. Full app feature/UI/device/backend/release gates remain active.

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
Big is 27.2/44.8 points. The composer paragraph uses natural pitch (observed minimum/
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
