# Pinned UIKit Chief references

This disposable XCTest host renders the actual `CmuxConversationUI` package at
the Chief audit revision. The complete package tree is pinned separately in the
Chief manifest; this is an executable reference dependency, not a claim that
every source file has received semantic review. The package is extracted from
Git without modifying it. Chief's real presentation options and unmodified
avatar are paired with an immutable offline backend.

Nine authored scenes cover incoming/outgoing runs, light/dark appearance, long
and Unicode text, quotes, accessibility type, literal markup, failed local sends,
an approval poll and emoji-only messages. XCTest attaches actual UIKit window
PNGs and visible-label geometry/font metadata. Capture excludes the system status
bar and does not establish keyboard, animation, account, transport, physical-device
or Android parity. Reference images require inspection and matched Android states.

Prepare a fresh project with:

```sh
python3 scripts/prepare-ios-chief-reference.py --upstream /path/to/upstream --output /fresh/reference-project
```

The manual `ios-chief-reference.yml` workflow supplies a full Xcode installation
and a disposable simulator on its runner. It is independent of the Mac companion
build. Its exporter verifies the exact project commit, pinned source tree,
fixture hash, complete scene set, PNG payload hashes and pixel dimensions before
emitting canonical named files. The result bundle and failed diagnostics are
retained. A green export is integrity evidence; it does not replace rendering
inspection or the full-parity completion gates.

Local preparation, `plutil` project parsing and Swift parsing can run with command
line tools. UIKit typechecking and rendering require full Xcode and an iOS runtime;
the current Mac has only the command line tools, so hosted compilation remains
mandatory. Never interpret a parser pass as a successful UIKit build.

The host builds only the selected simulator's active architecture, matching
SwiftPM's Debug modules. First run `39510100505` built the upstream package but
failed when the generated test target also requested x86_64 and tried to import
the ARM-only modules. The host setting corrects that mismatch; it does not patch
the pinned upstream package or establish a rendered pass by itself.

Follow-up `39511521895` compiled and captured all nine scenes, but its body-font
guard found 17-point text in the accessibility scene. A per-controller trait
override did not exercise the application's real Dynamic Type environment.
The workflow now runs eight standard scenes and the accessibility scene in
separate test invocations, setting the disposable simulator's actual content
size before each. Tests require the application category to match and the
accessibility body font to reach the source's 28-point threshold. The exporter
also rejects mislabeled or ordinary-size accessibility captures. All nine scenes
remain required; no reference batch is accepted until both invocations pass.

Run `39513912473` at project `e431401c2279e7b7423d4439bffea187b9e46f4d`
passed both hosted invocations and export. Its nine PNG/metadata pairs were
independently verified after download and all nine images inspected locally.
The accessibility scene reports the real `UICTContentSizeCategoryAccessibilityM`
application category and two 28-point body labels at 402×874 @3x. The artifact
is `12307880734`, outer SHA-256
`c8cf04f878ddc63372028f99c5d200b8a43edbc5894d1a739658d53571fa1a54`.
This accepts the offline UIKit reference batch; matched Android comparison,
interactions, transport and physical-device acceptance remain separate gates.

The next expanded batch keeps `Scenes.json` and its nine accepted input hashes
unchanged. A separate `Interactions.json` adds normal/accessibility details and
normal/accessibility landscape headers. The host opens the source name control,
edits the real composer, exercises the actual unsupported settings and back
action, and records draft/focus/viewing behavior. Modal viewing is an observation,
not a presumed suspension. Public scene geometry requests must yield real
landscape bounds and compact vertical traits; no controller-only size override
substitutes for rotation. Image-view metrics and safe-area/control frames accompany
the captures. These are settled offline states with animations disabled, not
keyboard animation, interactive zoom, accessibility or physical acceptance.

New workflow exports use `--with-interactions` and require all thirteen scenes,
both fixture hashes, source provenance, real title fonts, disabled unavailable
actions, settings rollback and draft/focus checks. Default exporter mode retains
the original nine-scene acceptance contract for older artifacts. Twelve synthetic
integrity tests verify rejection of incomplete, altered or mislabeled evidence;
they do not render UIKit. Expanded hosted compilation and rendering are pending.
First expanded run `39533735026` failed Swift 6 compilation because a nested
test helper captured XCTest's non-Sendable `self` across actor isolation. The
helper is replaced by direct main-actor view lookup; the pinned package and
behavior assertions are unchanged. Hosted rendering remains pending a new run.
Run `39534383622` compiled and rendered the standard scenes, then found two
incorrect harness assumptions: this VC's typing callback does not populate
`store.draft`, and UIKit's native Back bar item need not expose a `UIControl`.
The interaction test now asserts actual editor text retention and dispatches the
real `UIBarButtonItem` target/action (or its public accessibility activation).
It requires handled activation and actual dismissal/return; manual dismissal
cannot substitute. PNGs attach before closing so a failure retains visual
diagnostics. No details/compact batch is accepted until the new run passes all
thirteen scenes and both category invocations. These calls exercise public
control actions; physical touch targeting and animations still need acceptance.
Run `39535704928` captured the normal details PNG and passed its title/action/
switch/focus checks, then could not activate the item object itself: UIKit exposed
no legacy target/action and the item's accessibility activation returned false.
The harness now locates the rendered control inside the labeled native bar-item
wrapper and dispatches its registered public primary/touch action. A public
accessibility activation of that rendered wrapper is also supported. Navigation
view/control-event diagnostics attach before activation. The real dismissal and
return checks remain mandatory; no manual modal dismissal is permitted.
Run `39537005113` confirmed why label-based lookup fails: UIKit exposes neither
the item's identifier nor label on any rendered navigation view. The diagnostic
tree contains exactly one enabled event-bearing control, registered for event
8192 (`primaryActionTriggered`). The harness now proves that the bar's top item
is the presented controller's own navigation item, that its sole left item is
the pinned Back item, and that it has no right items. It requires exactly one
registered primary control in that bar before dispatching that public event.
The exporter requires those facts as well as the resulting close/focus/draft/
viewing behavior; fourteen integrity tests reject ambiguous or manual shortcuts.

Expanded run `39538527277`, source `7b54fafa51475be2e920cc464672d625aa3d479c`,
passed both actual system-size invocations and all thirteen export checks.
Artifact `12319300862`, outer SHA-256
`db23803667da5036bf640f42535e6e67b39e95e469d441f1704f2236d648f714`,
was independently downloaded, hashed, extracted and canonicalized with the exact
source commit and both fixture hashes. All four new images were inspected;
the original nine scene inputs/package pin remain unchanged. Normal/AX details
retain the actual editor text, resign focus during/after details, keep viewing
true during/after the modal and roll both unsupported switches back. The native
Back-only bar has exactly one registered primary control and actually closes.
Both landscape windows are 874×402 with real compact traits and safe area
`[0,20,62,62]`. This accepts the expanded offline reference batch, not Android
matching, touch targeting, animations, transport or physical-device acceptance.
