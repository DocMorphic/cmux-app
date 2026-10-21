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
