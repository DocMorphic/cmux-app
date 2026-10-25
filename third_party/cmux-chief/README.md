# Chief protocol reference

Source: `manaflow-ai/cmux` at
`a5911a1f92f3eeb11c4d7f3e2ef89204718c5009`.
Copyright Manaflow, Inc.; GPL-3.0-or-later. See LICENSE and the root NOTICE.md.
`manifest.json` records five wire-source hashes, separate behavior/artwork hashes and three conversation timeline hashes. This scoped reference does not
advance the global implemented or reviewed upstream pins.
The dated counts and host observations below are historical; `docs/CHIEF.md` and
`docs/HANDOFF.md` hold the current implementation, reference and real-host state.

The text-only composer reference also guards four geometry/core/UI sources and
two localization catalogs at this revision. It executes the unmodified portable
Swift geometry, field measurement and trimming bodies: 60 row, 62 trim and five
height cases, with the actual local Foundation whitespace set. It verifies 11
copied placeholder/send values across nine locale resource folders. Run:

```sh
python3 scripts/check-chief-composer.py --upstream /absolute/path/to/cmux \
  --output /absolute/path/to/ignored-composer-evidence
```

The UIKit-only source is hash-checked, not compiled by this portable checker.
This is a scoped text-only comparison; keyboard/IME, rich editor, material and
physical acceptance require their own evidence.

The Home UI port follows the attributed ChiefHomeView.swift pinned/recent/
invitation/empty branches, working/unread summaries and source layout anchors.
`homeStrings` binds two exact catalogs and the shared L10n unread helper; all
135 copied strings in nine Android locales are checked by check-chief-home-strings.py.
Original built-app Bundle.main resolution and matched SwiftUI presentation remain
unverified. Android date/search/pull rendering and the authored sparkles vector
are not exported UIKit/SF assets. See CHIEF.md for runtime scope and remaining gates.

The full question form additionally binds `questionPresentationFiles` (the whole
ChiefHomeView/caller and ChiefConversationBackend sources) and `questionStrings`
(the exact ShellUI catalog). `check-chief-question-strings.py --upstream PATH`
checks 72 copied labels in nine Android locales. Captured opening form/payload,
editable pending controls, explicit Cancel and presentation-scoped late completion
follow those sources. The full-sheet cancel/reopen path now follows the source independently of compact
poll reservation. `check-chief-question-submission.py --upstream PATH --output DIR`
executes the complete unmodified answerQuestion method with controlled state/client
seams and checks 12 observations in `app/src/test/resources/parity/chief-question-submission.json`. Android
regressions consume the reopened-submission and follow-up-read observations. This
is not the whole backend, SwiftUI rendering, native Bundle.main resolution, actual
transport or physical parity.

The Android question form additionally expands its dialog for focused keyboard
input and brings Other into view; its saveable editor retains text/selection
through Activity replacement. Actual emulator IME-window bounds verify normal,
enlarged and reopened input. This adapter is not matched UIKit sheet/IME motion;
physical keyboard, selection/composition and accessibility acceptance remain open.

The rich composer keyboard port also follows the already attributed
ConversationComposerView.swift priority Command-B/I/U overrides and editable
action checks. Android Ctrl/Meta dispatch invokes the same semantic palette
toggles, with current visit/lifecycle/owner guards. Actual Android View dispatch
is covered separately from physical hardware and UIKit keyboard acceptance;
see CHIEF_RICH_TEXT.md. No global source pin or reference corpus changed.

The live text-metrics port additionally records `richComposerTextMetricsFiles`:
composer font reset/emoji-base updates, formatting/restyle, trait change detection
and body metrics. Native global run 40175245243 at fc39577c supplies four original
UIKit cases / 49 public actions / 53 states with eight actual system category
changes and readbacks. The Android typography/semantic transaction state follows
those observations, including the retained base used on later typing. See
`tests/chief-rich-composer/GlobalFontObservations.json` and CHIEF_RICH_TEXT.md.
This extends scoped attribution without changing the global pin or existing
semantic/matrix corpus; physical/matched presentation acceptance remains open.

The rich-text reference executes the whole unmodified ConversationRichText and
ConversationTextEffectMotion files as a Swift core module and the whole shared
ConversationRichTextRendering file linked against real QuartzCore. It records
104 semantic cases, 2,473 mutation steps, 672 motion probes, seven UTF-8 seeds and
72 compressed 60 Hz tracks (3,501 transform and 792 opacity frames). The tracks
include all 16 actual CATransform3D components. Eleven whole-file hashes bind
the executed files, original authored test inputs and Chief/UI applicability
sources; hashing the latter does not execute UIKit, Store or Chief's backend.
Only selected source test inputs are used; this does not execute those test suites.

```sh
python3 scripts/check-chief-rich-text.py --upstream /absolute/path/to/cmux \
  --output /absolute/path/to/fresh-ignored-rich-text-evidence
```

Default mode recompiles/reexecutes and compares the committed reference bytes;
`--vectors /absolute/path/to/vector.json` intentionally writes regenerated output.
Outputs must be fresh directories. The Android editor and optimistic row renderer
now mount the semantic engine. Rich editor typing/selection/undo/IME, palette,
actual glyph/word rendering, accessibility, pending sends and physical acceptance
have separate evidence and remaining gates in the document below.
See [CHIEF_RICH_TEXT.md](../../docs/CHIEF_RICH_TEXT.md).

Fourteen palette labels are copied from the same pinned conversation catalog.
English/Japanese are its exact translations; other supported locales retain its
English fallback. Undo/Redo are project-authored Android action labels. Check all
144 values in nine resource folders with:

```sh
python3 scripts/check-chief-rich-composer-strings.py --upstream /absolute/path/to/cmux
```

The emoji checker guards five source files and executes the actual prefilter,
one-to-three grapheme predicate, count, fixed font-size and composer eligibility
functions. Ninety-five cases cover joined families, flags, keycaps, variation
selectors, source-accepted non-RGI combinations, adjacent/spaced runs, Foundation
trimming and the byte limit. The same exact vectors run against JVM ICU and the
Android runtime. Android supplies its system Unicode data and emoji artwork;
these checks do not imply identical glyphs, UIKit metrics, send animations or
future Unicode coverage. Run:

```sh
python3 scripts/check-chief-emoji.py --upstream /absolute/path/to/cmux \
  --output /absolute/path/to/ignored-emoji-evidence
```

The direct-conversation header/details checker guards eight additional Swift
sources and executes the unmodified portable header/details geometry and list
defaults. It verifies the Chief backend inherits the unsupported list-state
default and does not implement shared backgrounds. Copied labels are checked
against both pinned catalogs. The Android continuous text-scale mapping combines
the source's quick-action category sizes with the factual Body/Title 1/Caption 2
anchors in Apple's published iOS Dynamic Type table. Supply a local download of
the exact primary JSON URL/hash recorded by `headerTypography` in the manifest:

```sh
python3 scripts/check-chief-header.py --upstream /absolute/path/to/cmux \
  --typography /absolute/path/to/ignored-apple-typography.json \
  --output /absolute/path/to/ignored-header-evidence
```

The portable execution covers 36 compact layouts, 56 direct details layouts,
five unread capsules, eight name-size cases, 12 type categories and list defaults.
The static portrait layout also uses guarded UIKit source plus Android layout
assertions; this checker does not render UIKit or validate the details blur/zoom,
OS interaction, physical account/connection or whole-screen parity.

The Kotlin port covers the Chief capability set, nine RPC request shapes, typed
response defaults, UInt64 sequence counters and shared structured questions,
answers and validation. It uses the existing authenticated MobileRpcClient and
checks the caller's current account/connection authority before transport
admission and after responses. Chief requests omit terminal selection tickets.
An ambiguous write is never automatically resent by this client.

Generate reference vectors using a local Git checkout containing that revision:

```sh
python3 scripts/check-chief-wire.py --upstream /absolute/path/to/cmux \
  --output /absolute/path/to/ignored-evidence
```

The script checks every hash, removes only unavailable external module imports,
and compiles the actual Swift models, coder, request builder and shared question
validation with a local recording RPC stub. No network request is made. The
committed vectors in `app/src/test/resources/parity/chief-wire.json` hold 21 model
cases, nine actual client requests and eight validation cases. Passing
`--vectors` explicitly regenerates a vector file for review.

`NativeChiefModelsTest` compares defaults, strict decoding, large counters and
question validation. `NativeChiefClientTest` exercises the real framed Android
RPC transport, request parameters/timeouts, owner retirement during token lookup,
late responses and cancellation without closing a shared connection. Together
with the existing RPC, attach-ticket and JSON cases, 101 focused JVM cases pass.
Main and instrumentation Kotlin compile.

The initial mounted Home/transcript/question port and retained session state are
now documented in `docs/CHIEF.md`. Twenty-one additional JVM session/form/race cases
and five Android production-composition/root cases pass. The generic conversation
UI comparison, complete lifecycle/restoration, localization/accessibility and real
host acceptance remain open. The installed custom Mac does not advertise Chief;
a supporting real host and Pixel checks are required.

The timeline checker executes the actual pinned ConversationRunPlan, store sort
method and rowID property with an inert Chief-only model. It compares 19 cases,
including failed sequence anchors, time boundaries, typing and replies. It also
checks the six English/Japanese timestamp resource values against the source
catalog. Run:

```sh
python3 scripts/check-chief-timeline.py --upstream /absolute/path/to/cmux \
  --output /absolute/path/to/ignored-timeline-evidence
```

The latest full JVM run passed 2,986 and skipped four, with zero failures. Eight
Android cases pass, including source timeline presentation and a font-scale-2
probe. This is not full UIKit, matched-iOS or supporting-host acceptance.

The header checker now also executes the unmodified `ConversationTopEdgeGeometry`
(50 wash cases) and verbatim controller inset expressions with inert frames
(54 cases), guarded by the recorded a591 file hashes. The Android transcript
frame uses the full viewport with measured header/composer overlays, source
resting body gap and plain-background iOS 26 wash. Eighty-five focused Chief JVM
cases pass. Android portrait passes 34 cases (two landscape-only assumptions
skip there); the separate landscape run passes all four. These source/fixture/
emulator checks do not establish full UIKit, other OS/background branches,
animation/accessibility or real-host/phone parity. See docs/CHIEF.md for scope,
failed-run diagnostics and capture provenance.

Chief's action-menu foundation now ports the pinned `MessagePressTiming`,
`MessageMenuGeometry`, fixed menu metrics and Chief-only `layoutFinal` / lifted
preview branch. `menuFiles` records source hashes. `scripts/check-chief-menu.py`
compiles the whole geometry declarations and verbatim menu-size/lift/layout
bodies with inert view dependencies and reactions/custom emoji disabled, matching
the actual Chief host configuration. Source-generated vectors cover 1,152 menu
placements/clips, 32 recognizer pairs, 11 release/tap boundaries and six sizes.
This foundation is not a mounted Android menu or gesture implementation. Native
UIKit action-reference capture is a separate workflow and must pass its source,
fixture, template, geometry, callback and PNG association guards before acceptance.

The retained session also ports Core's local deletion by stable row ID. Queued
rows removed before transmit are skipped; already-started requests complete and
their canonical acknowledgement IDs join local tombstones. Three additional
session tests cover refreshed history, late aliases, equal-text neighbours,
draft/read preservation and owner retirement. This backend foundation is not
a mounted More/selection toolbar or a remote deletion API.
