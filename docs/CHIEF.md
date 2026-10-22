## Signed 715 ready; physical upgrade remains pending — 2026-10-23

Local/unpublished candidate 715 includes the recent localized Plan UI, shared
window/editor continuity and Chief underline-opacity changes. Clean source:
`ef4f24851e4224875c9c7f6cf2e19043d6985813`. APK:
`captures/runtime/parity-candidate-715/app-release.apk`, 278,083,649 bytes,
SHA256 `77a2e55cf0f4ed20103ac3088f15c3f6b7902cd71d4e9e97e2b53c29b9a36537`.
The stable signer remains
`1118815b3831ae18005306c10bb0953a6fc2e9e20d14407223da52cda971abd4`.
Verification passes 22 native libraries, 21 viewer assets, private Firebase
configuration matching and exclusion of all 16 test-only components. Full JVM:
3,114 passes, four opt-in skips, no failures/errors (3,118 cases, 424 reports).

The actual signed APK passed two starts on a fresh disposable API 36 arm64
Pixel 6a emulator. Both sign-in frames were visually reviewed; UI trees and
images are hashed, crash buffers are empty, and only that owned AVD was deleted
after its emulator exited 0. This is fresh unauthenticated release evidence,
not an existing-account upgrade, real notification reply or full app parity.
Checkpoint: `captures/recovery-20261023/parity-candidate-715-checkpoint.json`;
runtime: `release-715-cold-v1/receipt.json` in that folder.

Pixel is still released at last verified signed 712. A fresh availability reply
is pending; no physical command was issued. The current immutable 715 identity
supersedes 714 for the same 15-minute, data-preserving terminal/background-reply/
cold-tap checks in `pixel-715-test-plan.json`. Do not use old availability
replies. The Mac pairing controls are human-confirmed visible; no new pairing
code or registration was created. Date/address detection/actions, native UI/
font/material and physical/accessibility/lifecycle gates, billing/backend
contracts and broader source reconciliation remain open. Full parity is active.

## Chief detected underline opacity verified; date/address actions open — 2026-10-23

NativeChiefText now draws detected web/email/phone underlines separately from
opaque glyphs, using the pinned a5911a1f rule: near-white 46%, otherwise 26%.
The line replaces alpha independently of glyph alpha, follows actual UTF-16
selection paths through wrapping/Bidi, and excludes hidden ellipsized text.
Interactive URLs/dialer behavior is retained; closing previews retain pixels
without link targets. Android font metrics remain platform geometry, not a
claim of matching UIKit glyph/underline geometry. Source attribution and three
exact file hashes are in NOTICE.md and the Chief manifest's dataDetectionFiles.

Owned API 36 emulator `chief-underlines-v5` passes both cases in 7.680 seconds:
eight raster profiles (white, black, colored, translucent, RTL, wrapping,
ellipsis, actual font scale 2) plus the existing production transcript/quote/
pending-send literal/link regression. Integrated raster opacity runs span
144–380 px; original glyph colors remain. All eight closing frames are pixel
identical with no URL hit targets; all eight captures were visually reviewed.
Both APKs and exact changed sources are archived, crash buffer is empty,
settings restore has no diagnostics and the owned emulator exited 0. Checkpoint:
`captures/recovery-20261023/chief-underlines-checkpoint.json` (66 evidence hashes).
Earlier cohorts are excluded: the full-annotation equality guard suppressed
lines; the first raster assertion wrongly required full subpixel-row coverage;
TextPaint's public underlineThickness field then masked the font metric getter.
The final renderer uses Paint metrics and literal-string identity, and the
raster assertion integrates coverage without changing the target opacity.

The separate real SystemTextClassifier probe passes one observation case
(0.425 s), not detector parity. It finds English dates in three public samples,
but no German date or sample addresses; modern actions expose View/Schedule,
while all legacy intents are null. No action was fired. The exact provider
snapshot, inputs/APKs and observations are preserved under
`chief-detector-provider-v1/receipt.json`. This provider is not enabled as a
production substitute. Date/address actions, broader locales, physical
accessibility, native geometry and authenticated/backend gates stay open.

The user confirmed the Mac protected pairing window's instructions/controls
are visible. No pairing code was generated and no capture protection bypassed.
Pixel remains released at last verified signed 712; availability for the pending
live checks remains unresolved. Signed 714 predates this change and the recent
Plan/shared-window work. A newer signed candidate is being prepared; no phone,
real account, purchase or backend was operated. Full parity remains active.

## Native Poll Details rotation independently verified — 2026-10-22

Run 39801868525 succeeds at exact source
438e29fa8f983447432209f70fe7a0258c931ea3. Artifact 12436752288 is 4,389,296
bytes; downloaded ZIP SHA256 matches the service receipt:
fd6367c7d1880e12713fbed42c9cd5769cd61605919b01f2120df8c76d680dbb.
All 19 canonical files match independent verification using the immutable source
snapshot. The seven original scenes and three live-rotation screenshots were
visually inspected. Actual runtime is iOS 26.4.1 (23E254a).

The real source bubble/menu opens one Poll Details controller/table. Its observer
lifetime, controller and table identities stay the same through 402x874 portrait,
874x402 landscape and returned portrait; native Done dismisses it and all states
report zero backend writes. This supplies native rotation evidence supporting
Android's retained-owner implementation. It does not prove Chief RPC/votes/live
changes, process restoration, physical devices, all type/locales or full visual
parity. Checkpoint: `captures/recovery-20261022/chief-poll-native-lifecycle-checkpoint.json`.

The next reference candidate adds measured UIKit body/footnote font inputs for
all twelve compatible content-size categories and compares the actual current
application category and visible table footer/row fonts. Covered transcript
labels are excluded from that comparison. Public navigation image geometry/tint
is also observed. Seventeen adversarial guards and Swift syntax pass; new native
execution remains required before replacing Android's provisional footer scaling.
Compatible-trait font inputs alone will not establish rendered layout acceptance
for the ten additional categories or Bold Text. Source fixtures/global pins and
signed Android candidate 713 are unchanged.

## Signed candidate 713 and active native checks — 2026-10-22

Candidate 713 from clean 438e29fa is signed and verified with the stable signer,
22 native libraries, 21 viewer assets and all 15 debug components excluded.
The full unit suite has 3,100 passes/four opt-in skips and zero failures/errors.
Two fresh-emulator release starts visibly reach sign-in with no crashes; its
owned AVD is removed. This does not prove authenticated/device/full parity.
Pixel stays on preserved signed 712; candidate 713 is local and unpublished.
Native rotation capture 39801868525 and corrected Mac build 39796614645 remain
active. Follow their exact handles and verify artifacts/runtime before physical
work; CUA currently reports the Mac locked. See HANDOFF.md for hashes, immutable
inputs, evidence paths and all remaining scope. Full parity remains active.

## Live Poll Details rotation reference candidate — 2026-10-22

The native reference host now opens the real source Poll Details menu, captures
portrait, rotates the already-open sheet to landscape and back, and dismisses
through the native Done control. Weak public UIKit identity observations plus
one observer-lifetime UUID detect table/controller replacement or reference-host
recreation without retaining or modifying the source controllers. Each step
includes settled actual geometry, exact source/fixture provenance, zero write
attempts and a hash-bound XCUIScreen compositor image.

The hosted Poll workflow requires this roundtrip in addition to the seven
existing scenes. `verify-ios-chief-polls.py --require-lifecycle` rejects missing
or reordered phases, recreated observation/table/controller identities, changed
source/sections, wrong actual orientation, missing images and failed dismissal.
Fourteen adversarial guard tests, Swift syntax and workflow YAML parsing pass.
No native execution or lifecycle acceptance is claimed until that hosted run
and its independently verified artifact complete. The Android recreation result
and original seven-scene native checkpoint remain scoped as recorded below.

## Poll Details retained-owner lifecycle verified — 2026-10-22

Poll Details now belongs to its retained conversation session. Recreating the
Compose view no longer discards the sheet or its last answered-poll snapshot.
Content stays only in memory; it is not serialized into Activity saved state.
A presentation generation prevents an old dismissal callback from closing a
newer sheet. Leaving the conversation or retiring its Mac/account owner clears
the presentation; pausing reads for configuration/background retains it.

`captures/recovery-20261022/chief-poll-lifecycle-v1` passes twelve UI checks in
41.059 s, a separate clean participant capture, and all five matched pending
layout captures. Actual ActivityScenario.recreate keeps the same transcript,
complete answered snapshot and real scroll offset (600 physical pixels before
and after); live device rotation keeps the dialog and uses the actual landscape
viewport. Revocation followed by recreation removes the sheet. All 66 session
unit tests pass, including stale-dismissal, answered-receipt admission, visit-end
and owner-retirement cases. Source/APK/image/fixture hashes and native metrics
are independently checked; crash buffer is empty and owned emulator stopped.
Screenshots were reviewed. Checkpoint:
`captures/recovery-20261022/chief-poll-lifecycle-checkpoint.json`.

This uses the production Chief view and an injected retained production owner
inside a debug-only Activity with controlled read-only API. It does not prove
full routed NativeScreen/process restoration, actual RPC/live delivery, a matched
native live-rotation capture or physical Pixel/TalkBack behavior. The existing
font/material/motion/type/locale and broader parity gates remain open. Pixel
stays on preserved signed 712 and is available to the user. Corrected Mac build
39796614645 at 4f896154 remains in full compilation; follow that run and verify
its artifact/runtime before requesting another physical reply test. Full parity
remains active.

## Grouped Poll Details candidate verified — 2026-10-22

Poll Details now renders its source-measured grouped table, prompt above the
centered title, circular Done control and read-only participant rows in a separate
full-width dialog. Normal and rendered accessibility reference row heights,
section/footer spacing and label baselines follow native run 39762098177. Actual
Android safe areas protect system bars/cutouts; landscape rows use equal side
insets and remain centered. Body labels expand for taller fallback-script glyphs.
Done, Android Back, header drag and pull at the top dismiss; transition timing,
interactive motion and native glass remain provisional.

Final `captures/recovery-20261022/chief-poll-sheet-v4` passes ten regressions in
31.701 s, a separate clean participant capture in 5.007 s, and all five matched
pending scenes. The very-large Arabic/Devanagari test checks real viewport
advancement for accessibility ScrollBy and Compose touch scrolling, then label
bounds inside the visible participant row. StandardTestDispatcher requires an
explicit virtual-clock advance for its scroll coroutines; earlier timeouts and
failed hardware-injection observations remain diagnostics, not app-freeze or
accepted accessibility evidence. All tests assert no Chief write calls.

The final source/APK/image/fixture hashes, original native metrics and artifact
hash were independently checked. Row height, top relative to each sheet's safe
origin, and horizontal centering match within 1.01 physical pixels. All five
captures and script stress screen were visually inspected. The crash buffer is
empty and the owned Android 16/API 36 emulator is stopped. Checkpoint:
`captures/recovery-20261022/chief-poll-sheet-checkpoint.json`.

Raw portrait sheet top is Android 48 vs iOS 62 points. Landscape centered row
width is 738 vs 710 because actual OS safe insets differ; these differences are
recorded, not accepted as full visual parity. Android glyph shapes/advances,
Done check shape/tint/glass/glow, continuous corners, gesture motion, other type
categories/Bold Text, actual Arabic/Hebrew locales, open-sheet recreation and
physical TalkBack remain open. Component light style does not prove a global
app light theme. Controlled Chief API cases do not prove real RPC, retained votes
or live updates; generic populated native voter scenes remain a separate source
applicability gate. The Pixel stays on preserved signed 712. Full parity remains
active.

## Poll Details reference verified and participant behavior corrected — 2026-10-22

Native run 39762098177 at 266b6a61bd0784f1e031027baaf3ddbd4896e822
succeeds for all seven real compositor scenes. Artifact 12418312787 is 3,494,835
bytes; the downloaded ZIP independently hashes to
f3dcb3e7f46c0a047be2b2f6c90916ba810b23ecdd54c4285c286df8da5a97b3.
All 15 uploaded canonical files match independent local verification. Actual
runtime is iOS 26.4.1 (23E254a). All seven screens were visually inspected.
`captures/recovery-20261022/chief-poll-native-checkpoint.json` records the scope.

The source uses an inset-grouped table, a question prompt above the centered
navigation title, and the native Done check control. Normal rows are 53 points
high with 17-point labels; measured accessibility rows are 64 2/3 with 28-point
labels. Footers are 13/23 points; the navigation prompt stays 13 and title grows
17 to 21. Portrait row inset is 20 points, with another 16 for labels; landscape
row inset is 82. The table backgrounds are #1C1C1E dark / #F2F2F7 light. These
are measured reference inputs, not Android layout acceptance.

Android Poll Details now identifies only the selected first human as You,
preserves other author names/first-seen order and avoids a duplicate fallback
Chief identity. On live pending changes it updates options and participants
together; once the poll becomes an answered receipt, it retains the entire last
snapshot, matching the source reload guard. The footer now reads 0 Votes and
four labels use the exact source English/Japanese catalog translations (other
source locales fall back to English); Done uses the existing localized resource.

Final Android evidence is in `captures/recovery-20261022/chief-poll-snapshot-v2`:
eight UI regressions pass in 26.89 s, plus a separate fresh participant-capture
invocation in 4.613 s. The source/APK/image hashes are checked, crash buffer is
empty and the owned emulator is stopped. The initial compile typo and an earlier
passing run with a clipboard-contaminated screenshot are retained as diagnostics;
the clean capture visibly shows all three participant names. Checkpoint:
`captures/recovery-20261022/chief-poll-snapshot-checkpoint.json`.

This is a behavior correction within the still-provisional Material sheet. The
grouped table, presentation/header/Done geometry, typography/materials, large-text/
RTL/landscape/recreation and physical TalkBack acceptance remain open. The seven
native scenes use controlled pending/generic data: they do not prove Chief RPC,
actual vote submission or live-update behavior. Offscreen cell contents are not
observed directly. The host RTL override with English system locale is not broad
Arabic/Hebrew locale acceptance. Full app parity remains active, the physical
Pixel stays on preserved signed 712, and the user's phone is free.

## Poll Details compositor reference candidate — 2026-10-22

The new optional `--with-poll-ui` host links the same unmodified pinned
conversation package and adds seven reference scenes: pending Chief-style
approval options in dark/light/RTL/landscape/actual accessibility type, plus
populated generic poll sections in normal and scrolled accessibility layouts.
The latter exercise the shared table's existing voter order, You alias, unknown
participant fallback, added-choice footers and non-voters; they do not imply
that the Chief backend supports generic poll creation or retained answered votes.
Pinned ChiefConversationBackend.pendingPoll projects only pending approvals/
questions and removes answered/cancelled cards. Its info projection names only
the selected first human participant You, then preserves other author names.

The UI test physically holds the source bubble, taps its real Poll Details menu,
captures `XCUIScreen.main.screenshot`, and taps the native Done control. An
observation-only AX node records public UIKit labels, inset-grouped table frames,
source data-source headers/footers and visible cell contents. Offscreen cell
contents and live update/vote acceptance are not inferred. Read-only backend
write attempts, actual traits/orientation and dismissal are checked.

Eight adversarial export tests cover source/image association and reject false
activation, wrong participant/footer data, missing/different traits and geometry.
All 146 Python tests pass; Swift syntax and project parsing pass. Refactoring the
project preparer preserves every generated byte for the existing default and
translation modes (237 and 240 files respectively). The new host has not yet
executed under UIKit: no native Poll Details set or Android parity is accepted.
The hosted ios-chief-poll workflow is the next source-evidence step. Current
Android Poll Details remains provisional. Full app parity remains active.

## Translation font and caption checkpoint — 2026-10-22

Native run 39747401479 at b7ca4c87817cb6838cb065ba399c0a3f68877595
adds actual UIFont advances/baselines and numeric UIImage attachment canvas
measurements to the same 17 compositor scenes. All 35 canonical files match the
uploaded artifact independently; all 138 Python reference guards pass. Twelve
intrinsic Caption 2 profiles are API measurements, not rendered acceptance of
every category or Bold Text. The upstream pin is unchanged.

Android now measures its actual runtime font against source Latin probes,
calibrates horizontal advance while preserving type size, and uses the measured
caption attachment canvas, attachment-plus-space advance and baseline. The
caption row applies the source's asymmetric message-side inset and physical
sender-edge alignment in RTL. Its translation artwork is an original Android
vector. Choice popups retain their measured width rather than expanding for a
platform-font rounding difference; fitting preserves type size. Pane titles use
localized resources.

Final emulator run `chief-translation-fonts-v4` passes 19 regressions in 69.742 s
and 17 fresh matched captures with no crash-buffer events. All eight popup body
sizes match the native measurements within one physical pixel. The large choice
is now 366x313 1/3 with three header lines, replacing the previous 373x347 1/3
four-line result. Normal choice is 240x246 1/3; source picker is 240x180 1/3
normal and 240x245 1/3 large. Normal/large caption line boxes, attachment slots
and baselines were inspected alongside source captures. Capture/source/APK
hashes, retained failed attempts and actual deltas are recorded in
`captures/recovery-20261022/chief-translation-fonts-checkpoint.json`.

This closes those measured sizing differences, not full typography or visual
parity. Android glyph outlines, per-word wrapping (including the large prompt),
original caption artwork and glass/material/motion still differ. Other locales,
Bold Text, unrendered categories, source transcript positions, open-popup
rotation/recreation, live inference/downloads, TalkBack and all broader gates
remain open. Light captures still override only the component theme. The owned
emulator is stopped. This work is unshipped to the Pixel: its preserved signed
712 installation predates it. The user can use/disconnect the phone. Full app
parity remains active.

## Anchored translation popups mounted and compared — 2026-10-22

The provisional Material bottom sheets are replaced by focusable, anchored
translation popups. Choice and source-language menus follow the current placed
message rather than the lifted menu preview. Back, Escape and outside taps
cancel without a visible Cancel row. Actions still recheck the live owner,
message and presentation identity. Parent text scale and layout direction are
explicitly supplied to the popup's separate AndroidComposeView. Presentation
state and captured material are keyed by the UI owner, so an equal row/rectangle
in a replacement session cannot reuse the former owner's pixels.

The material copies only the owning Activity surface with PixelCopy, remains in
memory, respects FLAG_SECURE and cancels on disposal. It does not sample its own
popup or another application's window. Arrow spacing, normal 240-point width,
header/button spacing, baseline frames and accessibility source-picker dimensions
come from the measured UIKit presentation. Caption blue now uses the observed
#0091FF dark / #0088FF light values; glyph artwork/advance/baseline remain open.

Expanded native run 39731923761 succeeds at
3fec8e2e1d007fffa6393e373ee8d262ea7c6a3d, with 17 real compositor captures.
The observer now records actual iOS 26.4.1 (23E254a), size classes, source/popup
rectangles, actual visible/hittable buttons, caption color and attachment canvas
sizes. Xcode 26.6 is the build selection, not the runtime version. Artifact
12406775620 is 21,555,694 bytes, SHA256
83dc99d3208bab8e587fb8c7531596af7e2f0d59891141d730a427da85ea9842.
Independent local verification matches all 35 uploaded canonical files exactly.
The six added adaptation scenes were visually inspected. The global upstream
pin remains unchanged.

Android evidence is in `captures/recovery-20261022/chief-translation-popups-v9/`:
19 focused regression tests pass in 69.074 seconds, followed by 17 successful
matched scene invocations, no skips and an empty crash buffer. The driver removes
only this disposable debug app's screenshot files before each capture; it keeps
app data. PNG bytes/hashes, actual display orientation, fixture bytes, original
wire messages, production source hashes and both APK hashes were independently
checked. Two geometry JVM tests pass against eight measured source rectangles;
all 135 Python reference guards pass. All 17 Android scenes were visually viewed.
The checkpoint is `captures/recovery-20261022/chief-translation-popups-checkpoint.json`.

Actual landscape captures are 2622x1206, with choices left of the outgoing row
and the source picker right of the incoming row. Normal choice/picker body sizes
match 240x246 1/3 and 240x180 1/3 points. At accessibility type the source picker
matches 240x245 1/3; the choice remains 373x347 1/3 versus native 366x313 1/3,
because the Android header wraps to four lines instead of three. This is an open
typography/layout difference, not visual parity acceptance. Android's nonlinear
font conversion is calibrated to the first scale at or above the source's
28-point accessibility threshold; actual body size is recorded as 28.056 points.
An earlier diagnostic run's setting-only rotation stayed portrait and its scale
fell below that threshold. Those adaptation captures are not accepted.

The controlled test uses registered bubble accessibility activation and actual
popup taps, with fixed detection/translation results. It does not establish real
inference, long-press capture parity, physical-device/TalkBack acceptance, UI
recreation or open-popup rotation. Light captures override the component theme;
production CmuxTheme is still dark. Material/motion, other locales/type categories/
Bold Text, caption glyph metrics, source body positions and date detectors need
further work. Continue these and every broader PARITY/REMAINING_WORK gate. The
owned AVD is stopped; account/pairing/signing/workspaces, Pixel 703 and signed 712
are unchanged. No further manual Mac pairing-window step is needed.

## Eleven translation compositor references verified — 2026-10-22

Native capture 39728880829 at edfdf666f475fee875c8f1028c9f8041cfd4f45e
passed both real UI-test invocations: nine standard scenes in 181.256 seconds
and two actual accessibility-type scenes in 38.888 seconds. All eleven images
were inspected. States cover choices/source picker, incoming/outgoing, original
and menu toggles, automatic indicator, RTL, landscape, light and large type.
They use the unmodified pinned package, real physical presses/taps, actual
traits/frames and controlled translations; native inference is not established.

The capture job's export conclusion remains failure: XCUIScreen encodes the
landscape PNG as 1206x2622 pixels with EXIF orientation 6, correctly displaying
2622x1206 for the actual 874x402-point window. The old exporter compared raw
axes. The corrected verifier checks PNG chunk CRCs and TIFF orientation, rejects
mirrored/malformed metadata and unmarked swapped axes, and keeps every original
PNG byte. Sixty-eight reference guards and all 131 Python guards pass.

Independent workflow 39730641718 at f3c3d9507aafd1c830e18b0c6dd03680c40288e9
passes against that immutable capture artifact. It requires both native capture
steps to have succeeded and checks exact capture commit, current fixture/template
hashes and every presentation state/image. Downloaded verified artifact
12405072484 (3,363,216 bytes), SHA256
9ad5c21845046d57d004799cca8adf64d339989e5f882cf76985d4638d840906,
matches local canonical metadata, receipt and all eleven original image hashes.
The source capture's red export status is retained; this is separate verified
reference evidence. Full proof:
`captures/recovery-20261022/chief-translation-reference-checkpoint.json`.

The normal source choice/picker is anchored inline, with implicit outside
cancellation. Apple documents this iOS 26 behavior at
https://developer.apple.com/videos/play/wwdc2025/284/?time=873. Android's bottom
sheet remains provisional. Source caption blue is #0091FF dark / #0088FF light;
Caption 2 is 11 points normally and 20 at accessibilityMedium. The indicator
stays 32 points/13-point title. Android still needs matching glyph advance/art,
colors, anchoring, material and adaptations. The actual source menu also shows
a small clipped blue caption fragment in its snapshot; preserve this observation.
Date-phrase underlines in bodies are another broader detector-parity check.

Next capture the same rows/translations on Android and implement/refine anchored
choices/source picker, outside/Back dismissal and owner admission. Add source
popup large/RTL/light/landscape variants and explicit runtime/size-class/
attachment metrics where needed. The current source build uses Xcode 26.6;
the observer did not record the exact OS runtime version. Do not infer it from
the SDK. Complete actual inference, UI recreation, physical Pixel/TalkBack and
every account/transport/terminal/notification/backend/release gate separately.
Full parity remains active. The owned emulator is stopped; real account/pairing/
signing/workspaces, Pixel 703 and signed 712 are unchanged. No further Mac
pairing-window step or repeated phone request is needed.

## Cold translation preferences verified; native RTL host repaired — 2026-10-22

An opt-in emulator probe now drives the actual installNativeChiefTranslation
factory and its production SharedPreferences adapter across distinct app
processes. It verifies an actual XML disk write of only version/source/target,
restoration after force-stop, account/team/Mac/installation/conversation
isolation, and a different target language starting fresh. A second conversation's
real saved pair also cold-restores and survives stopping the first conversation,
so preservation is checked against a populated sibling key. Cleanup verifies
both probe keys are removed from disk. No account store/RPC/message text/model
download/settings UI is used; this is not UI recreation or inference acceptance.

The tracked driver scripts/check-chief-translation-persistence.py reproduced
three phases successfully: write 0.459 s, cold read 0.455 s, cleanup 0.406 s,
zero skips/failures and empty crash buffer. Writer PID 2946 differs from reader
3487. Exact source/APK hashes and phase payloads are in
`captures/recovery-20261022/chief-translation-persistence-checkpoint.json` and
`chief-translation-persistence-accepted/verification.json`. Earlier narrower
probes stay separately recorded. Build both debug APKs with no Gradle daemon,
then run the driver with --output pointing to a new evidence directory; it
requires no connected devices before starting/stopping its owned disposable AVD.
The production Android implementation is unchanged in this checkpoint.

Native run 39727396812 compiled and produced eight independently associated
compositor/JSON diagnostic pairs; choices, source picker, message/original/menu
and automatic flows reached the actual source controls. It then rejected LTR
traits for the RTL fixture. The SwiftUI host now supplies layoutDirection, as
it does colorScheme. Run 39728880829 at edfdf666f475fee875c8f1028c9f8041cfd4f45e
is in progress; the full eleven-scene reference contract is still unaccepted.
Do not substitute the partial failed batch for complete reference evidence.

The diagnostics reveal a substantive remaining UI mismatch: the source choices
and source picker are anchored inline action sheets, with no visible Cancel
button; Android currently uses a bottom sheet. This matches Apple's iOS 26
UIKit guidance (https://developer.apple.com/videos/play/wwdc2025/284/?time=873),
which specifies implicit cancellation by tapping outside. Source normal-caption
pixels are #0091FF dark / #0088FF light, with 11-point Caption 2; Android still
uses an older fixed link blue and a different glyph. These observations guide
implementation, not visual acceptance. Next finish native runtime/traits/
attachment metrics and all scenes, then match action anchoring, outside dismissal,
glyph/color/indicator/material and rotation/accessibility variants on Android.

The owned emulator is stopped and probe keys cleaned up. Pixel 703, signed 712,
account/pairing/signing/workspaces are unchanged; Mac app/helper remain running.
No additional Mac pairing-window or repeated phone request is needed. All broader
physical/device/transport/terminal/notification/backend/release gates remain
open, and the full parity goal stays active.

## Translation RTL caption regression fixed; compositor reference in progress — 2026-10-22

Chief translation captions now use absolute left/right arrangement, matching the
pinned UIKit sender-side paragraph alignment. A new mounted runtime regression
first reproduced the English glyph order failure under RTL composition, then
passed with incoming captions on the physical left and outgoing captions on the
physical right. The final screenshot was inspected. All 18 combined translation
and message-action checks pass in 60.051 seconds, with no skips/failures and an
empty crash buffer. Exact inputs and APK/screenshot hashes are recorded in
`captures/recovery-20261022/chief-translation-rtl-checkpoint.json`. This covers
English labels under RTL layout, not Arabic/Hebrew bidi or full visual acceptance.

The separate iOS translation UI-test host links the unmodified pinned package,
drives real bubble long presses/menu/UIAlert controls and captures the OS
compositor. Eleven fixtures cover choices, source picker, toggles, automatic
indicator, outgoing/light, RTL, landscape and actual accessibility type. A
transparent AX observation node exports UIKit geometry; it is not VoiceOver
acceptance. Both project modes/schemes/resources, Swift syntax and all 125 Python
guards pass locally; the corrected host's 62 reference guards also pass.
First run 39726179590 compiled and physically reached the source choice sheet,
then rejected actual light traits for the dark fixture. The host now supplies
SwiftUI preferredColorScheme and retains state/images before final assertions.
Run 39727396812 at baae106135efb42717c590c4cea60eda1c4fbbd4 is in progress;
complete native frames and independent verification remain required. Failed
run artifacts are diagnostics. Controlled outputs never prove native inference.

Next finish that reference run, compare/refine Android sheets, captions, indicator,
glyphs, colors and material, then complete locale/rotation/recreation/native-output
and every broader physical/account/terminal/notification/backend/release gate.
The owned emulator was restored/stopped; Pixel 703, signed 712, account/pairing/
signing/workspaces are unchanged. The latest CUA check reports a locked Mac;
no further Mac pairing-window step or repeated phone request is needed. Full
parity stays active.

## Translation UI mounted with scoped preferences — 2026-10-22

Production Chief now supplies an account/team/Mac-installation preference scope
and can install the native engine in its retained transcript. Translate choices,
source-language selection, captions, original/translation toggles, automatic
translation indicator and foreground download admission are mounted. The store
persists only source/target tags per conversation; translated text stays in the
retained owner. Navigation retains the model, while retirement clears it.
This supersedes the unmounted-foundation status below; signed 712/Pixel 703 do
not yet contain this checkpoint.

The full pinned ConversationRows -> rendered MessageRowModel -> menu/accessibility
trace corrected an earlier assumption: Copy uses the rendered message text,
including the translation while shown. Menu Copy freezes that text at capture;
Select uses live displayed text. Store/RPC originals and drafts remain unchanged.
Poll question/options remain original, as in pinned PollRowModel. Four executable
Swift row-substitution/Copy cases with an inert pasteboard and actual Android
clipboard/range checks establish these distinct paths. The earlier Copy-original
translation tests are excluded from acceptance.

The download controller belongs to the retained owner, rejects stale request
IDs, and waits for a real foreground departure/return rather than an immediate
activity-result callback. Declining automatic model admission also defers batches
already queued, fixing an independently reproduced immediate second prompt.
Removed messages dismiss their stale language picker without translating text.

Final combined API 36 runtime: 17 passes, no skips/failures, 63.989 seconds,
empty crash buffer, 14 diagnostic captures. Checks cover the mounted flows,
actual production IntentSender settings handoff, Copy/Select/Reply, queued
consent, 32+6-point indicator geometry, long-tail reflow/history intent and
existing menu/More regressions. Translation outputs in these UI tests are
explicit controlled fixtures; actual native inference is not proved.
Full JVM suite: 3100 cases, 3096 passes/four opt-in skips, zero failures/errors,
418 archived reports. Production/JVM source inputs match that run; later changes
were instrumentation waits only. All 109 Python guards pass; repeated execution
matches 324 language pairs, four row/Copy vectors and 34 source strings (English
and Japanese). Twelve translation source files are pinned; global revision is
unchanged. Receipt: `captures/recovery-20261022/chief-translation-ui-checkpoint.json`.

Next: native iOS/Android visual comparisons and refinement of sheets/captions/
indicator/glyph/material/motion; locale/RTL/large-text/rotation/IME and real
preference persistence/recreation checks; native model installation/output and
cancellation on the Pixel. In particular, review physical RTL caption alignment
and icon ordering against the source. Captures can include transient Android
clipboard overlays and are not clean compositor parity references. Generic
emulator model-download failure remains separately recorded; it establishes no
all-Android exception. Every broader physical/account/terminal/notification/
backend/release gate below remains open. No ML Kit dependency was added.

The owned emulator was restored/stopped; account, pairing, signing and workspace
data, installed Pixel 703 and signed 712 are unchanged. Mac app/helper remain
running. Full parity stays active; no additional Mac action or repeated phone
request is needed for this checkpoint.

## Native translation settings verified; emulator download failure recorded — 2026-10-22

A foreground ActivityScenario host now launches the actual TranslationManager
settings PendingIntent through startIntentSenderForResult. The probe verifies
cmux is visible before launch and Android System Intelligence is visible after
it, then captures the OS screenshot and accessibility hierarchy. Its Live
Translate screen exposes Add a language and Google's model-download consent.
The earlier Activity launch before instrumentation was not a valid foreground
host: instrumentation restarted the app, and that pending-intent send was
blocked. It is excluded from foreground acceptance.

The admitted French-to-English download fails inside this generic emulator's
provider UI. The provider logs “There is no package supporting language pair”
while its capabilities continue to report DOWNLOADABLE. The opt-in real-output
probe correctly fails its INSTALLED precondition and submits no message text.
Actual model download/inference remains an open gate; this result establishes
neither Pixel behavior nor an unavoidable Android limitation. ML Kit was
reviewed in primary docs only and was not added; future use would require its
Google attribution and explicit model admission.

Final combined provider run: three passes, no skips/failures, 8.164 seconds,
empty crash buffer: capability/detection/324 language-pair checks, absent-model
deferral and the real foreground settings launch. The failed download/output
probe is separately retained as a diagnostic. Production sources and app APK
are unchanged from the foundation's successful 3089-case JVM run. Receipt:
`captures/recovery-20261022/chief-translation-provider-checkpoint.json`.
Display/animation/accessibility settings were restored and the owned emulator
stopped. French selection/related provider test assets remain confined to the
disposable AVD. Real account, pairing, signing and workspace data, Pixel 703
and signed 712 remain unchanged.

Continue mounting translation choices/source picker/captions/indicator, scoped
preferences and the foreground download host. Controlled translator fixtures
can establish UI behavior, as in upstream's explicit lab translator; real
Pixel installation/output is still required. Full parity remains active, and
no additional Mac action or repeated phone-availability question is requested.

## Translation foundation and Android provider checks — 2026-10-22

Chief translation now has a retained, owner-scoped model for single messages,
automatic conversation translation, original/translated toggles, edit
invalidation, serial batches and missing-model wait/retry. Stable pending-send
keys survive acknowledgement. Retirement clears translated text and rejects
late work; the retained transcript supplies original receipt/display text and
keeps RPC payloads and drafts unchanged. A preferences interface stores only a
source/target pair; its scoped Android storage adapter is still to implement.
The production UI does not install this engine yet.

The native Android adapter queries actual TranslationManager capabilities,
uses system language detection, defers absent models to a foreground host,
and owns cancellable translator sessions. A real provider probe caught empty
capabilities that recovered after one second; bounded cancellable retries now
handle that initialization window. Native checks also exposed deprecated-code
canonicalization differences (iw/he, in/id); the factory preserves the pinned
Foundation language-matching behavior for the reference tags.

Final API 36 emulator run: two passes, no skips/failures, 5.173 seconds, empty
crash buffer. Android System Intelligence reports 240 downloadable pairs;
French and Spanish detection succeed. The real adapter verifies French-to-English
missing-model deferral and all 324 matching pairs from 18 independently executed
Swift/Foundation language vectors. It does not download models or prove actual
translated output. Full JVM suite: 3089 cases, 3085 passes/four opt-in skips,
zero failures/errors, 416 archived reports. All 109 Python guards pass; repeated
source generation matches. Six translation source hashes are pinned without
advancing the global upstream revision. Exact inputs and excluded diagnostics:
`captures/recovery-20261022/chief-translation-foundation-checkpoint.json`.

Next mount Translate choices/source picker/captions/indicator, add scoped
preferences and the admitted download host, then verify actual model inference,
native reference UI and lifecycle/cancellation. Full locale/script/OEM coverage,
physical Pixel checks and every broader gate below remain open. No Android
platform exception or full translation/parity acceptance is claimed. Signed 712
and installed Pixel 703 remain unchanged; the owned emulator was restored/stopped.
The Mac app/helper remain running with saved pairing intact. No new Mac action
or repeated phone request is needed; the full parity goal remains active.

## Accessibility Copy and Android feedback verified — 2026-10-22

Nonempty Chief messages now expose Copy as an accessibility custom action in
the main transcript, More selection and active Reply copies. Text uses the
same receipt/display projection as the pinned Chief backend; structured prompts
are copied, truly empty text omits Copy, and whitespace is retained. Captured
actions preserve their text across live updates. Copy preserves drafts,
selection and Reply targets, and rejects a retired computer/account owner.
Reply copies also reject actions from a closed or superseded presentation.
Local failed-message Copy is exercised before explicit Retry reuses its ID.

Android uses a transient Message copied stateDescription instead of the source's
UIAccessibility announcement. Android 16 deprecates forced announcements and
recommends semantic state/live-region updates:
https://developer.android.com/about/versions/16/behavior-changes-all#disruptive-a11y
This is an Android feedback implementation, not proof of spoken TalkBack parity.

Final combined runtime: 22 passes, no skips/failures, 85.893 seconds, empty crash
buffer. An independent test outside Compose's test clock enables a test-only
framework accessibility service, invokes the actual exported Android Copy action,
observes the state-change event (change mask 64), verifies repeated feedback,
More selection preservation, Reply Copy and closed/stale action rejection.
It uses Settings.Secure with temporary test permission, restores settings and
permissions, and the runner independently confirms restoration after the test.
The probe exists only in the instrumentation APK; the app manifest excludes it.
No Compose accessibility force flag substitutes for this platform event path.

Full JVM suite: 3068 cases, 3064 passes/four opt-in skips, zero failures/errors;
415 XML reports are archived. The final production sources and app APK are
identical to that unit run; subsequent changes were instrumentation only.
Current source/APK hashes, actual platform events, settings before/after and
excluded diagnostic runs are recorded in
`captures/recovery-20261022/chief-accessibility-copy-checkpoint.json`.
Provider text-search, event-environment setup and quoted-setting experiments
remain failed diagnostics. They are not accepted as final proof; the final
settings API and actual-provider path pass independently and in the combined run.

Next implement the remaining source translation feature and continue the native
selection handles/edit-menu, full Poll Details table/participants, menu/More
motion/material/reduced-motion/haptic work. Other accessibility actions (Open
Thread/failed Try Again and full More action-list/Reply coexistence), actual
TalkBack speech/focus, locale/large-text/rotation and every broader/physical gate
below remain open. Signed 712 and installed Pixel 703 are unchanged; the owned
emulator was restored/stopped. Account, pairing, signing and workspace data were
not changed. No new Mac action is requested; phone availability remains pending.
Full parity remains active.

## More selection rejects reply swipes — 2026-10-22

An actual physical-swipe regression reproduced a source mismatch: with More
selection active, swiping the incoming parent entered a Reply thread. The
identical test now passes after the transcript withholds its reply-swipe
callback during selection. Pinned native gestureRecognizerShouldBegin explicitly
guards !isSelecting before horizontal-pan admission. This changes gesture
admission without changing the source's intentional incoming-content shift.

The test verifies incoming and outgoing swipes keep Reply closed, the initial
selection unchanged and Cancel available. After Cancel the same incoming swipe
enters Reply and the draft remains. Two normal swipe controls, the eleven prior
menu workflow checks and the standalone real-uptime press check also pass:
15 passes, no skips/failures, 58.36 seconds, empty crash buffer. The full JVM run
has 3068 cases: 3064 passes/four opt-in skips, zero failures/errors; all 415 XML
reports are archived. Source/APK/test hashes, the retained failing before run
and identical-test comparison are recorded in
`captures/recovery-20261022/chief-more-swipe-checkpoint.json`.
Fresh selection PNG inspection retains the expected layout, including the
previously source-confirmed quote clipping. This verifies gesture admission,
not source animation/material/selection-handle or physical-device parity.

Next implement the source accessibility Copy action with captured-text and
retired-owner checks, then continue the open translation, native selection
handles/edit-menu, full Poll Details table/participants and menu/More motion,
material, reduced-motion and haptic work. Broader app and real Pixel/Mac gates
below remain open. Signed 712 and installed Pixel 703 are unchanged; the owned
emulator was restored/stopped, and no account, pairing, signing or workspace
data was changed. No new Mac action is requested; the phone availability
question remains pending. Full parity remains active.

## Captured Copy lifetime and conditional Retry verified — 2026-10-22

Menu Copy now retains its captured text after a live message update or after
its row leaves the resting history window. The original session/account owner
must still be valid; live-row actions retain their current-row admission. This
matches the pinned native Copy closure over message.text in
MessageActionOverlay.swift:974. The two new rendered checks pass, along with
existing stale-owner clipboard rejection. A new menu Retry check verifies an
explicit retry reuses the original client ID and that Retry disappears after
acknowledgement. Its first attempt used an incorrect test tag (missing local:);
that failed diagnostic is preserved, and the corrected run passes independently.

Focused final runtime: 12 passes, no skips/failures, 51.115 seconds, empty crash
buffer. It includes the preceding eight menu/More/Select/Poll checks and actual
Android-uptime press/release/timeout/movement check. Final production source and
APK exactly match the successful full JVM run: 3064 passes/four opt-in skips,
zero failures/errors, 415 archived XML reports. Only the instrumentation test
tag changed afterward. The earlier 83-pass/four-skip broad run belongs to the
preceding mounted-menu commit a97df0a26c0ac0a9a29a377990ebc31685659c11,
not this subsequent Copy fix. Receipt:
`captures/recovery-20261022/chief-menu-copy-checkpoint.json`.

Correction to the preceding visual-review note: the incoming selection row's
physical-right quote clipping is also present in the fresh native selection PNG.
Pinned source shifts the full incoming shiftable container, including quotes,
by 42 points. Preserve this source behavior; do not reflow that quote solely to
remove the clipping. Source/image hashes are in
`captures/recovery-20261022/chief-menu-copy-source-audit.json`.

Next audit select-mode horizontal gesture rejection: the root still passes a
swipe-reply callback during More selection, while source gestures forbid it.
Add a real horizontal-drag rejection check and fix admission if reproduced.
Accessibility Copy, source animations/materials/reduced motion/haptics, native
selection toolbar/handles, full Poll Details table/participants and translation
remain open, together with every broader/physical acceptance gate below.
The owned emulator was restored and stopped. Signed 712, physical Pixel 703,
Mac account/pairing/workspaces and the pending phone-availability question are
unchanged. No additional Mac action is needed. Full parity remains active.

## Chief message actions mounted — 2026-10-22

The Android transcript now mounts a recorded lifted-message menu with Reply,
Copy, Select, conditional Retry/Poll Details and More. More retains stable row
selection, Cancel and confirmed local deletion, preserves drafts and protects
selected history from the resting retention window. Menu Reply passes the
lifted origin into the existing reply workflow after its 0.12 s delay. Captures,
actions and selection clipboard callbacks reject retired owners. Select uses
an actual Android TextView range; Poll Details is read-only and observes live
pending options. These are initial functional implementations.

Exact source/APK receipts match the final focused run (9 passes) and broader
Chief/navigation regression (83 passes, four expected skips, zero failures).
Both crash buffers are empty. The final JVM run has 3068 cases: 3064 passes,
four opt-in skips, zero failures/errors; all 415 XML reports are archived.
46 native-export guards also pass. Evidence and hashes:
`captures/recovery-20261022/chief-menu-mounted-checkpoint.json`.
The physical press test uses Android uptime outside Compose's virtual clock:
short release cancels, qualified release opens, held timeout opens before up,
and movement cancels. Accessibility observation latency is recorded separately;
this does not establish native frame-timing or haptic parity. Failed exploratory
runs remain diagnostic evidence and are excluded from acceptance.

Native run 39695701927 at f817f0a08b569feaac68698e985937421300f092
also passes independent existing-15 and new-five-scene verifiers. It executes
actual source AX and Copy/Reply/More callbacks, including selection confirmation
and Cancel. Its drawHierarchy glass captures do not establish OS compositor
agreement; actual native destructive alert activation remains separate.

Full parity remains active. Next fixes include captured Copy surviving row
retention/update, selection-mode reply-quote clipping observed in fresh PNGs,
conditional Retry and accessibility Copy coverage. Translation, source menu
present/dismiss/cancel/reduced-motion/haptic behavior, source More transitions,
menu material/icons, visible selection handles/edit-menu geometry, full Poll
Details table/participants/optimistic states, RTL/landscape/large-text/lifecycle
stress and broader app/physical gates remain open. Poll Details currently uses
a provisional Material bottom sheet; Select range/copy/collapse tests do not
prove visible native handles or toolbar. Screenshots taken after Copy include
the system clipboard overlay and must not be used to claim that acceptance.

Signed candidate 712 and installed Pixel 703 are unchanged. No phone upgrade,
publishing or new Mac action occurred. The saved Mac recovery receipt preserves
its account, pairing and registration; no additional pairing-window click is
requested. The Pixel availability question remains pending. Keep the first
return step `svc power stayon false` and all real terminal/FCM/Doze/notification-
tap/revocation/accessibility gates below.

## Action-menu foundation and remaining contracts — 2026-10-22

Menu geometry/press timing now matches source-executed Swift vectors; retained
local deletion passes late-ack/history, queue and owner checks. Full JVM suite:
3064 passes/four skips, zero failures/errors. These foundations are committed
at 13d7af5f; the actual Android menu/selection UI remains unmounted. Native action
run 39692913894 failed after Copy; harness lifetime and frame repairs are at
88973fe0/52bda7fc and their hosted runs remain pending. See HANDOFF.md and
captures/recovery-20261022/chief-menu-foundation-checkpoint.json for exact scope.

Further pinned source review resolves the conditional Poll Details contract:
ConversationPolls.swift presents a read-only table with Done, the question,
option/voter sections, vote counts and No Votes participants. It reloads on live
store changes and has no vote/write action. ChiefConversationBackend projects
pending approvals and questions into polls with initially empty votes; complex
questions have one Answer questions option. Answered/cancelled cards become
receipts and no longer qualify. This remains a required Android menu destination.

Select applies to a visible nonempty text label with an actual text frame. Its
transparent noneditable selection overlay selects all at the label's frame,
resigns the composer keyboard, uses system handles/edit actions, and copies the
text currently displayed. Collapse or resign ends it; handle/text/loupe touches
inside the frame expanded by 24 points do not start transcript gestures. This
is distinct from menu Copy, which copies captured message.text.

Translation remains an implementation gate. The source's installed translator
plus nonblank message text controls eligibility. Its choice sheet supports this
message or automatic conversation translation; uncertain/same-language detection
opens a source-language chooser. Translated rows toggle original/translation,
with translating, download and unsupported states plus a conversation indicator.
No Android platform/backend exception has been established. Source hashes and
contracts are in captures/recovery-20261022/chief-select-poll-translation-audit.json;
this is review evidence, not executed Android or native UI acceptance.

Android implementation candidate (documentation review only): API 31's
[TranslationManager](https://developer.android.com/reference/android/view/translation/TranslationManager)
exposes on-device language-pair capabilities and asynchronous translator creation.
Capability queries must run on a worker thread; creation can return null. Its
[Translator](https://developer.android.com/reference/android/view/translation/Translator)
accepts cancellation plus an executor/callback and has an explicit destroy
lifetime. This is a viable API surface to investigate before adding an SDK;
actual OEM service availability, language detection/download settings and Pixel
behavior remain unverified. No unsupported-platform exception follows from API
availability alone.

## Signed checkpoint — 2026-10-22

Verified local candidate 712 contains the font-following/RTL fixes below, source
`355c88bd2c45ae36ee9c386adc54529590c451a9`. Full JVM suite: 3058 passes/four skips;
rendered source/APK proof is recorded below. HANDOFF.md has its exact APK hash,
signing/package/asset checks and archived test reports. It is not installed or
published; physical 703 remains and full parity stays open. Next: source-qualified
long-press/More selection, local-only delete, conditional Poll Details and the
0.12 s lifted-bubble Reply handoff; Chief Forward remains hidden (no callback).

## Font reflow, active reply geometry and RTL editor — 2026-10-22

Following newest now explicitly repositions on font-scale changes. The old
collector only observed the last row while it remained visible: growing text
from scale 1 to 2 dropped message 40 entirely and displayed messages 30–35.
The pinned source's trait-change handler explicitly retains following-newest.
The corrected view keeps message 40 above the composer; a separate history
scroll remains unpinned across font growth. This is an in-place rendered
component check, not a physical system-font-setting/Activity recreation test.

Screenshot review found a second real bug despite the initial sender-lane RTL
check passing: start/end editor padding mirrored away from the physical-right
Send button. Its text viewport overlapped Send by 85 px at 3×. Physical left/right
padding now matches the source text-view frame and leaves 11 px clearance; the
complete Hebrew draft is visible. The strengthened test independently measures
placed editor/Send bounds and verifies local text-layout/glyph/cursor bounds fit
the editor viewport. Prototype global glyph coordinates used an incorrect origin
and are excluded from the before proof; its actual viewport overlap is valid.

Six new checks cover font following/history intent, composer growth during entry
and return, actual OS IME hide/show requested during entry, and RTL sender/editor
geometry. Moving copies are asserted before the geometry requests. The outer
bubble/editor gap stays 18 px at 3× while the composer grows 177 px and the IME
moves it 972 px; return copies retire and draft/focus are retained without sends.
These observations do not establish native animation timing, in-flight copy font/
rotation, full RTL, physical Gboard/TalkBack or complete optical/material parity.

113 Chief JVM tests pass. Final focused three pass (11.773 s); portrait 74 pass/
four expected skips (243.529 s); landscape 26 pass/two skips (82.284 s); disabled
animations 15 pass/three skips (36.203 s). Four crash buffers are empty. All twelve
full PNGs and metadata were checked and inspected; accepted runs share guarded
source/APKs. Receipt `captures/recovery-20261021/chief-text-rtl-final-checkpoint.json`;
verifier `verify-chief-text-rtl-final.py`. Earlier font failures, unhelpful raw
Compose timeouts and the green-but-incomplete RTL sender-lane matrix are diagnostic.

Emulator 66338 was restored to 1080×2400/420 and animator scale 0, then stopped;
Gradle stopped. Custom Mac 40840/helper 43849 are alive; account/pairing/workspaces
are preserved. No Mac action is requested. USB is absent and Pixel remains 703.
Build a clean signed 712 with these fixes, superseding 711 for pending physical
upgrade/terminal/FCM/cold-tap/lifecycle/TalkBack checks. First on Pixel return
restore `svc power stayon false`. Its availability question remains pending.

Next feature gap is source-qualified: Chief disables reactions/editing/unsend/
attachments, but shared MessageActionOverlay still offers Reply, Copy, conditional
Translate, text Select, failed-message/poll items and More multi-message selection.
Android currently has SelectionContainer, swipe and AX Reply without this lifted
long-press/More workflow. Audit exact eligibility/action/backend/selection contracts,
pin geometry and native observations, then implement the applicable source flows.
Source audit receipt: `captures/recovery-20261021/chief-next-action-menu-audit.json`.
All broader completion gates and the full-parity goal remain active; the global
upstream pin is unchanged.

## Copied reply ownership across synchronous actions — 2026-10-22

Close/Open/Close and Send/Open/Close admitted within one UI event now replace
copied content at admission, before Compose can conflate the intermediate open.
Reconciliation reads live session state, so an older collected snapshot cannot
restore the replaced root. A close before first layout waits for measured copies,
constructs their entry origins without an entry step, then returns/fades them.
The normal copies remain sharp at their measured twins; reduced motion retains
its fade behavior. Retirement clears both open and closing content.

The pre-fix reproduction had two failures: it retained parent/reply instead of
reply/next. Both paths now verify the newest generation, exact row membership,
measured twin positions, alpha/settling, draft/focus and one send. A separate
24-message check verifies fresh tail position after scrolling and reopening the
same root, with actual IME geometry stable first. Generation-keyed overlay state
already existed; this check validates it rather than claiming a new scroll fix.

113 Chief JVM tests pass. Focused 13 pass (51.213 s); full portrait 68 pass/four
expected skips (207.327 s); landscape 22 pass (67.966 s); disabled animations 12
pass (29.242 s). Four landscape setup cases also pass (15.380 s). All runs use the
same guarded source/APKs. Four captured crash buffers are empty. The focused run
passed 13 tests, then an expected-count typo stopped its runner before crash
capture; that buffer is unavailable. The broader suites resumed with identical
inputs. An earlier landscape timeout was inside test setup's scroll-to-node,
before the transitions; scrolling to the lazy item avoids asking a tall action
container to fit above the landscape IME. Neither failed harness run is an app
acceptance result. Four full PNGs and copy geometry were checked and inspected;
a landscape twin may lie outside the short keyboard viewport.

Evidence: `captures/recovery-20261021/chief-copy-scroll-checkpoint.json`, guarded
by `verify-chief-copy-scroll.py`. Owned emulators were restored to 1080×2400/420
and animator scale 0, then stopped; Gradle stopped. Custom Mac 40840/helper 43849
remain alive. The temporary pairing window is closed; no Mac action is requested.
Account, pairing and signing data are preserved. Pixel USB is absent; installed
703 is unchanged. First on its authorized return restore `svc power stayon false`.

Next prepare a clean signed candidate containing the accumulated changes, then
verify its data-preserving Pixel upgrade, real Mac terminal, encrypted FCM and
cold/background notification taps, lifecycle and TalkBack. Continue optical /
material / font, in-flight IME/geometry/RTL and the broader completion gates.
The full-parity goal remains active; this checkpoint does not establish full app
or physical-device parity.

## Synchronous reply admission and Back interaction — 2026-10-21

Open/close/send now admit header transitions synchronously into a session-owned
`NativeChiefHeaderMotionState`, before Compose can conflate a closed state between
two replies. Its frame clock rebases after idle; scale changes retain phase. Live
session state reconciles retirement, and cached Back/Stop actions check the
admitted mode. Same-frame Close/reopen and Send/reopen now keep the new icon delay,
preserve the expected draft/focus, and admit only one send.

Close/send fetch the live shown presentation and freeze its motion before header
invalidation. The strict interrupted-entry check exposed a real extra movement:
with zero admission-time clock advance, the copy moved 1170→1183 px. The corrected
path moves 1170→1130 toward its unchanged 957 px twin and clears the swipe offset.
The earlier pointer version also advanced the clock 16 ms before touch-up, so the
exact admission check now invokes the actual UI action synchronously and asserts
identical capture/click clocks. Position/direction assertions remain intact;
real pointer close/reopen tests remain separate. Both final focused and broad
frame traces verify the zero-time condition and correct return direction.

Back now has separate drawing, pointer and AX behavior. Draw-only opacity keeps
its restored AX node available at alpha 0. Pointer taps work during the 300 ms
invisible delay, are blocked during the 200 ms return fade, then work at rest;
its public AX action remains enabled throughout return. Actual pointer and AX
activation checks pass, matching the pinned native normal-hit observations.
This does not claim physical TalkBack or exact interrupted UIKit behavior.

113 Chief JVM tests pass. Final focused nine pass in 41.033 s; full portrait 64
pass/four expected skips in 195.626 s; landscape 18 pass in 55.897 s; disabled
animations eight pass in 19.790 s. Same source/APKs across the final matrix;
all crash buffers empty. Five full PNGs/geometry were verified and inspected.
The generic close ink remains 49×49 px at 3×; Back return brightness is 0.502 of
settled. Source/APK hashes, logs, host clocks and raw frame traces are guarded in
`captures/recovery-20261021/chief-header-admission-checkpoint.json`.
Verifier: `captures/recovery-20261021/verify-chief-header-admission.py`.
Earlier failed header, gesture-clock and live-copy checks remain diagnostic.

Owned emulator 60192 was restored to 1080×2400/420 and animator scale 0, then stopped;
Gradle stopped, USB absent. Mac 40840/helper 43849 remain alive. No account, pairing,
signing data, signed release or phone install changed. Physical 703 and older
candidate 710 retain their scopes; first on Pixel return restore
`svc power stayon false`. Full-parity goal remains active.

Next extend the admitted-action matrix to copied-content ownership across
close/open/close and send/open/close, including owner retirement and layout effects;
header admission is covered, but all coalesced copy lifetimes are not established.
Continue the optical/material/font, in-flight IME/geometry/RTL and remaining
screen/source audit; generic X bounds remain one pixel down/right of native at 3×.
Then prepare a current signed candidate for the outstanding physical Pixel/Mac
terminal, encrypted FCM, cold/background notification taps, lifecycle and TalkBack
checks. The older completion gates still apply in full.

## Android reply header and closing touch routing — 2026-10-21

The production header now uses the pinned iOS 26 timing profile: Back fades over
60 ms; the old trailing glyph holds 320 ms, fades out for 50 ms, swaps its image /
AX label, then fades in for 200 ms. Exit restores the normal label immediately,
cross-dissolves the glyph over 60 ms and delays Back 300 ms before its 200 ms fade.
The glass remains visible around the trailing glyph. Each observed reply generation
owns one timeline. The four tested early-close/reopen sequences recover correctly,
intentionally avoiding the stale UIKit fades observed in run 39668896472. Android
animation-duration changes preserve phase; disabling motion settles immediately.

Back stays drawn while fading but loses its actions/AX immediately on reply entry.
The generic close mark now uses a 29 dp canvas: its bright ink measures 49×49 px
at 3×, matching the accepted native reference size (previous Android 40×40 px).
Drawn Back return brightness is 0.498 of the settled arrow. Four full PNGs and
control geometry were independently checked and inspected. This establishes
size/fade behavior, not identical Apple artwork, optical placement or glass.

A rendered reopen check exposed a real closing-overlay hit-test bug: empty
pointer-input handlers still owned the hit path and swallowed transcript taps.
Closing copies now remove backdrop/row/scroll pointer nodes altogether. Their
text retains underlining without active link annotations, and selection/AX stay
disabled. Actual quote taps now reopen during return; an early pointer tap on the
still-Stop-looking trailing control closes rather than stopping/sending.

107 Chief JVM tests pass. Broad portrait: 58 pass/four expected skips in 175.874 s.
Landscape: 12 pass in 39.059 s; disabled animations: eight pass in 19.424 s. After
the broad portrait run, only the two new tests' initial parent-list scroll setup
changed to reach virtualized quotes with the landscape keyboard open; both were
rerun in portrait (two pass, 16.691 s) and landscape. App source was unchanged.
All crash buffers are empty. The failed pre-pointer-fix checks and initial
landscape setup failures remain diagnostic. Source/APK hashes, logs, host clocks,
PNG hashes and limits: `captures/recovery-20261021/chief-header-android-checkpoint.json`.
The guarded local verifier refuses changed source/APKs. Emulator size/density
1080×2400/420 and animator scale 0 were restored, owned emulator stopped, Gradle
stopped, USB absent. Mac 40840/helper 43849 remain alive. No signed release or
phone installation changed; physical 703 / older candidate 710 retain prior scope.

The synchronous-header admission and normal Back pointer/AX gaps described at
this earlier checkpoint are resolved in the newer entry above. Complete optical /
material / font matching, all coalesced copy lifetimes and physical acceptance
remain open. The broad app completion gates remain unchanged. First on Pixel
return restore `svc power stayon false`.

## Animated native header observations — 2026-10-21

The animation-enabled pinned UIKit probe passes on run 39668896472, source
`db9e4cd03ca29462a8a9fb735fe5f9340fbc04e5`. Actual iOS 26.4.1 build 23E254a,
iPhone 17 Pro simulator, 402×874 @3; five independent fresh conversation hosts,
14 phases/666 samples, largest sampling gap 24.779 ms. One XCTest passes in 19.719 s;
35 export-integrity tests pass. Actual quote activation and trailing/escape close
preserve draft/focus. Model/presentation opacity, labels, symbols, ancestor AX and
window hit tests are recorded; this is not physical touch/VoiceOver evidence.

Artifact 12380095577 is 193002 bytes, SHA256
`afc81ccdc15aee60de43ebb1b661d2d2e6681092b561e9333cd6c5bda2afe371`.
Download size/hash, template hashes against exact Git source, raw export and
independent verification all match hosted receipt. Local checkpoint:
`captures/recovery-20261021/chief-header-timeline-checkpoint.json`.
The first run 39667374397 passed its XCTest but export rejected a 237.7 ms initial
sampling gap. It remains diagnostic only. An unmeasured real warm-up removes
first-use render-resource cost; every measured sequence still uses a fresh host.
Continuous sampling guards were retained. A 10 ms exit-before-reopen may have one
immediate observation because the synchronous source exit can itself exceed 10 ms.

Settled first Close label occurs at 409.949 ms; Back is AX-hidden/non-hittable on
entry and restored logically at exit. Both early-close variants leave native Stop
opacity 0 at completion. Reopen during return leaves Back presentation opacity 1
while AX-hidden; reopening during a pending glyph swap changes to Close after
47.391 ms instead of a fresh full delay. These are observed upstream interruption
defects, not desired forced expectations. Normal source delays/fades remain in
ConversationHeaderView/ConversationViewController+Reply; iOS 27 is not measured.

At this earlier probe checkpoint the Android header still switched immediately;
no app implementation, signed release or phone installation changed in that probe. Next port normal timing/labels/fades, retain
Back visually during its fade, and cancel superseded transitions safely. Record
intentional correction of stale callbacks explicitly rather than claiming exact
interrupted UIKit matching. Recheck rendered interruption, touch/AX, draft/focus,
reduced motion, orientation and owner retirement. The existing generic Android X
also needs sizing review: accepted settled 3× PNGs have 49×49 px bright ink versus
40×40 px in the historical Android 44 pt control (RGB > 200); symbol image dimensions
alone do not establish drawn ink size. No Apple symbol artwork is packaged.

Custom Mac 40840/helper 43849 remain alive; USB absent. Last physical 703 and stale
candidate 710 retain their recorded scopes. First on Pixel return restore
`svc power stayon false`. Full app/source/UI and physical terminal, encrypted FCM,
cold/background taps, lifecycle and accessibility gates remain open; goal active.

# Chief conversations

## Partial-cell reply origins — 2026-10-21

Reply origins now use visible message-cell bounds and separately convert the
primary bubble. A quote or local footer can keep its cell visible while its body
lies beyond the conversation viewport. Timestamps remain separate, and current
LazyColumn row identity still excludes reused/prefetched cells. The new rendered
quote case proves body-over-body entry and a real return target in both orientations.
Portrait viewport ends1614px, quote1578..1673, body1691; copy1691→1278→1571 on
return. Landscape viewport ends462px, quote426..521, body539; copy539→126→455.
Draft/focus survive and no send occurs. Footer membership is implemented; this new
focused evidence specifically exercises the quote-visible/body-outside case.

101 Chief JVM cases pass. Final portrait Chief plus two route checks:56 pass/three
expected skips in252.683 s; landscape10 pass in28.036 s; reduced7 pass in31.718 s.
All crash buffers empty. Six PNGs/paired layout receipts independently verify and
were inspected. Evidence: `captures/recovery-20261021/chief-partial-cell-checkpoint.json`.
The standalone portrait's176.609 s is diagnostic, not a performance claim. The
final matrix has host-clock receipts. A duplicate emulator start was rejected;
the verified existing owned instance52291 was reused, then restored/stopped. Size /
density1080x2400/420 and animator0 restored; Gradle daemon stopped; USB empty.
No signed APK/phone app changed; last physical release703 and candidate710 remain
at their recorded scopes. First on Pixel return restore `svc power stayon false`.
Verified Mac40840/helper43849 still run. Full-parity/physical terminal, encrypted
FCM, cold/background taps, remaining source and visual gates stay open.

The next-step animation probe described at this checkpoint is now accepted in
the newer animated-header entry above; the Android port remains outstanding.

## Retained reply closing and accessibility reading intent — 2026-10-21

Logical reply state now clears immediately while owned copies remain through both
source return dynamics and the background fade. Sends/reduced motion fade in place
and release the backing; missing twins fade instead of inventing destinations.
Close/send stop entry on the admitted input event, and a prior generation cannot
retire a reopened thread. Owner retirement/rejection clears both phases. Backing
accessibility returns immediately; closing copies have no interactive controls.
Visible row keys qualify cached coordinates against LazyColumn reuse/prefetch.

Landscape testing exposed tail-follow undoing accessibility scrolling. AX scroll
and index actions now record reading intent immediately; the old overflow collector
rechecks live flags before scrolling. Arrivals preserve an older AX reading position;
returning to newest resumes following. Rendered checks cover both directions.

Final verification: 101 Chief JVM cases pass; portrait Chief plus two route checks
55 pass/three expected skips in164.966 s; landscape nine pass in26.887 s; reduced
motion seven pass in17.916 s. Skips are two landscape-only and one reduced-only
case, exercised separately. All crash buffers empty. Five 1206x2622 PNGs verify
and were inspected. Short-landscape geometry uses actual unclipped layout frames,
with the source 10-point gaps unchanged; clipped AX bounds cannot measure rows
outside the viewport. Receipt: `captures/recovery-20261021/chief-reply-close-checkpoint.json`.
The first focused batch's anomalous977.604 s is diagnostic, not timing acceptance;
independent repeats and final runs have host-clock receipts. Earlier failing race /
AX/setup/measurement attempts are retained separately. Emulator size/density and
animator scale restored1080x2400/420/0; owned emulator/Gradle daemon stopped.

Full parity stays open. Next audit/refine origin filtering for a visible message
cell whose quote is visible while its primary bubble lies outside the viewport;
current bubble-intersection filtering is stricter than the source cell lookup.
Then implement Back/X sequencing: enter Back.06, old glyph retained.32 then.05 out/
.2 in; closing glyph.06, Back delayed.3 then.2. In-flight IME/font/orientation/RTL /
TalkBack, material/font/line-break, complete source coverage and physical acceptance
remain open. Existing Swift vectors cover dynamics/entry projection; these closing
flows are source-reviewed/Compose-tested, not new UIKit animation or Pixel proof.
Candidate710 predates entry/closing work; no signed APK or physical app changed.
Pixel USB absent, last verified release703; first on return restore
`svc power stayon false`. Real Mac/Pixel terminal, encrypted FCM and cold-tap gates
remain open. Verified Mac40840/helper43849/control socket still alive at the check;
no further Mac action is requested. The full-parity goal remains active.

## Mounted reply entry motion — 2026-10-21

The source ReplyTrip engine now drives actual Compose entry. Visible bubble and
heading coordinates are frozen before changing reply state/requesting the keyboard;
copies align bubble over bubble, unseen rows follow the anchor, whole rows travel
vertically and only the selected balloon unwinds its swipe. Outgoing gradients
follow actual placed coordinates. Layout changes invoke containerMoved; deliberate
thread scrolling does not. The veil/blur and anchor-only sender chrome use the
source background curve. Android's disabled system animations select reduced
motion: final poses, zero swipe offset and a content fade.

101 Chief JVM cases pass. Enabled-animation timed capture passes (6.627 s);
portrait Chief plus two real route checks: 48 pass/three expected skips in138.599 s
(two landscape checks, reduced-motion check). Reduced mode separately passes in
4.261 s; six landscape cases with animations enabled pass in16.145 s. Three crash
buffers are empty. Six 1206x2622 PNGs and both pose receipts verify; start/middle/
landed normal and middle reduced images inspected. Normal reply y=957→1170→1278px,
x=631→617→486px, versus original x486/y957; reduced poses stay x486/y1278.
Receipt: `captures/recovery-20261021/chief-reply-entry-checkpoint.json`.

The first timed diagnostic ran with emulator animation scale zero; one attempted
settling helper required an absent empty-draft Send. With animations enabled, a
composition-read alpha clipped the first travelling row; moving the fade read to
the layer fixed it and the rendered checks now pass. Diagnostic runs are retained
separately. Emulator size/density restored1080x2400/420, animator scale restored0,
owned emulator/Gradle daemon stopped. No changes to the Pixel or a signed APK.

Full parity remains open. Next: retain closing copies through return motion and
background fade, distinguish send fade, restore backing/accessibility immediately,
handle close/reopen/retirement ownership, then source Back/X header timing. Stress
in-flight IME/font/orientation changes and compare physical rendering. Candidate
710 contains captions but predates this motion work; Pixel installed703 remains
preserved/USB absent. First on return restore `svc power stayon false`. Real Mac/
Pixel terminal, encrypted FCM, cold taps and all remaining app gates stay open.
The Mac helper remains online and owned by the verified app; its temporary pairing
window is closed and no Mac action is requested. Full-parity goal remains active.

## Reply motion equations and recovered Mac helper — 2026-10-21

The private helper recovered after reconnecting the existing personal Tailscale
profile, which had been stopped. Its configured listener address was absent from
the Mac; keys and enrollment were intact. The verified custom Mac process now owns
the helper and mode-0600 control socket. The actual production Swift pairing client
and offer parser pass against the real helper with the same account, instance,
endpoint and descriptors; the test offer was cancelled. HTTPS verifies with the
existing certificate (GET returns expected 405). One active/zero retired phone
registrations and their ciphertexts, helper configuration and native descriptor
are unchanged. Current-build Pair Android Notifications opened and New Pairing
Code produced a QR with Copy enabled, then the temporary window was closed.
Subsequent reopening hit stale UI controls; no further Mac action is requested.
Evidence: `captures/recovery-20261021/mac-helper-recovered-checkpoint.json`.
This supersedes the earlier absent-helper/pending-menu observations below; it
proves neither phone enrollment nor encrypted FCM delivery/cold taps.

NativeChiefReplyMotion/ReplyTrip now port the source per-frame travel, horizontal
swipe unwind, geometry-based easing, pixel snap and moving-container behavior.
The checker compiles the whole pinned Swift dynamics and verbatim threadRows
projection with inert view identities/frames: 227 geometry cases, 16 trajectories,
641 frame/container actions and five projection cases match; 101 Chief JVM tests
pass with zero failures/errors/skips. Upstream observation arrays are source test
inputs, not our device evidence. Source revision remains a5911a1f; GPL attribution
and four file pins are recorded in the Chief manifest. Independent regeneration
matches vectors SHA `4cd25506b6f69006ac190429b0afe4ad019d9f850613c0bd00ab3e9a52613a49`.
Receipt: `captures/recovery-20261021/chief-reply-motion-checkpoint.json`.

The engine is not mounted in the UI yet and is not in candidate 710. Next: actual
row coordinate capture, frame driving, close/send/reopen lifetime, reduced motion
and IME/accessibility/orientation validation. Candidate 710 remains uninstalled;
Pixel USB absent and installed 703 preserved. First on authorized return restore
`svc power stayon false`, then preserve data/signing for any upgrade. Full parity
and the physical Mac/Pixel terminal, FCM, cold-tap and remaining app gates stay open.

## Signed candidate 710 — 2026-10-21

Candidate710 includes mounted captions at clean source
`89e25a56efa0a0cb52e6b2ba8da259fcd03c8667`. APK SHA-256
`780cfabe467528e3a73373ba96eefb7a2d7a219c2b85af7d9f72c6e1c3b5eff1`,
277,650,297 bytes. Independent stable-signer,22 native libraries/alignment,
21 viewer assets,14 debug-fixture exclusions and selected Firebase-resource checks
pass. APK/source receipt: `captures/runtime/parity-candidate-710/`; strict receipt:
`captures/recovery-20261021/apk-verification-710/apk-verification.json`.
It is not installed/published; physical acceptance and full parity remain open.
Gradle daemon and owned emulator stopped; emulator display restored1080x2400/420.

Mac preservation additionally verifies byte-identical registration database,
helper configuration and producer ciphertext, and the same previously verified
native public descriptor. Account/team/three workspaces remain restored. The
helper child/control socket remains absent after manual Keychain approval; current
CUA menu is stale/invalid and the requested manual pairing-window check is pending.
Pixel USB absent, installed703 preserved. First on return restore
`svc power stayon false`; then upgrade with existing signer and preserved data.


## Mounted captions and restored Mac session — 2026-10-21

UIKit run 39555560226 SUCCEEDED at exact source be5c80c0. Artifact 12325422816
(31,997,172 bytes, SHA `4a361a073e37a22992eae278e2f48ff8b28194b94728ddcb13e33c7aed519781`)
passes independent verification for all fifteen scenes and twelve measured Caption
2 categories. Native normal/AX thread images inspected. Android now mounts those
font/spacing inputs and the source timestamp row/label/baseline and sender geometry;
day text uses medium, base regular, and normal transcripts retain the separate
four-point post-timestamp gap. Android glyph artwork remains platform-specific.

97 Chief JVM cases pass. Portrait Chief: 45 pass/two landscape-only skips;
correct route class: two pass; six landscape cases pass. The first combined
command also named a nonexistent route test class and failed initialization;
the corrected route invocation passed. Stable normal/AX capture checks pass,
including actual body17/28.05634, caption11/20.04507 and row heights86/133px at3x.
IME-visible/stable frames are rechecked after screenshots; nine PNG/metadata pairs
verify, all crash buffers empty, emulator restored/stopped. Initial asynchronous
capture metadata and an attempted exact nonlinear font-scale inverse are diagnostic,
not accepted evidence. See CHIEF.md and `chief-caption-mounted-checkpoint.json`.

The verified custom Mac39541508905 replaced39498469545 through normal quit/launch
after private backups/live session export. All three workspace UUIDs/order/selection,
terminal scrollback, signed-in account/resolved team and one active/no retired helper
registration survived. The owner approved the matching producer Keychain prompt;
subsequent sample has no Keychain wait, but the helper child/control socket is still
absent. The prior pairing window opened; new-build menu verification is pending a
manual action because CUA retains a stale Cloud menu/rejects controls. Official cmux
untouched. Pixel USB absent; installed703 preserved; first on return restore
`svc power stayon false`. Signed709 predates mounted captions and is now stale.

Full parity remains unproven. Material gray/blur, font/line-break fidelity (the AX
outgoing reply wraps here but is one line natively), ReplyTrip travel/unwind,
locale/keyboard/accessibility stress, helper recovery and real Mac/Pixel terminal,
encrypted FCM/cold-tap/lifecycle and other completion gates remain open.


Oct 21 caption metrics probe in progress: the reference host now samples the
public UIKit Caption 2 point size, line height, leading and neutral system-font
metrics across all twelve source categories. Both actual system-size invocations
cross-check explicit compatible-category metrics against the application's real
preferred caption font. Optional export guards require a complete, finite,
cross-scene-identical table and recompute the actual thread TimestampCell row /
label frames from those inputs. Twenty-four integrity tests and local Swift /
project parsing pass. Hosted compilation/rendering remains required; existing
accepted fifteen-scene artifacts retain their original verifier contract. These
inputs will replace the current generic Android timestamp label sizing; no
new Android caption geometry is accepted yet.

Probe run 39554150141 at 19f300df compiled and rendered both category invocations
but failed export. Verified diagnostic artifact 12325485516 has outer SHA
`217d85c68d23fec80d45325ea6d1c03e818c6aaa1eb48801932087bb61bae516`.
Its body probe used UIFontMetrics.scaledValue(17), which yields 26⅓ at AX1;
the actual source bubble uses a preferred body descriptor with tight leading
and renders 28. The probe now constructs that exact descriptor/font, retains
the scaled value as a separate observation and checks actual application body
point size too. This is a harness correction, not accepted Android type evidence.
All 24 integrity tests and local Swift/project parsing pass after the correction;
new hosted verification remains required. The failed artifact is retained intact.

Separately, NativeChiefCaptionGeometry ports source caption insets/spacing,
sender line/bubble offset and TimestampCell row/label/baseline formulas. The
checker compiles the complete actual TranscriptMetrics and exact selected
TimestampCell/pixelCeil/label-frame declarations against inert font inputs.
All 104 source cases agree with Kotlin, including tall-script neutral spacing,
interpolated/extrapolated caption insets and scale fallbacks. The 96 focused Chief
JVM cases pass with no skips/failures. The helper is not mounted in Android UI
yet: actual font data, matched normal/AX captures, source main-transcript four-
point post-timestamp gap versus direct thread placement, and physical acceptance
remain next. Source receipts/logs are `chief-caption-*` under recovery-20261021.

Oct 21 swipe-to-reply checkpoint: acknowledged balloons now accept physical
rightward pans within the source 18-degree rule and incoming 28-point inset /
156-point minimum area. They track the finger to 40 points, then logarithmically.
Only the balloon moves; its quote/connector remain stationary. The 26-point arrow
grows/sharpens from offset 22 to 40, with outgoing drift, a 300 ms pulse, delayed
system haptic and source reset durations. Crossing above 40 while moving right
commits; reversing below it while moving left uncommits. Cancellation and pending
rows never open a thread. Release targets the selected message inside its loaded
root and retains the release offset in owned thread state for the remaining
ReplyTrip work. Vertical travel and horizontal unwind into the thread are not
yet rendered. The arrow is project-authored artwork, not an exported SF Symbol;
exact pulse/blur rendering and physical haptic feel remain unaccepted.

The source checker compiles the complete actual ConversationReplyMotion plus the
verbatim update/release decision with inert cell/indicator/haptic dependencies:
34 geometry cases, six update sequences/19 steps. Ninety-four Chief JVM cases
pass without skips/failures, and debug/instrumentation builds pass. Initial touch
run: four pass, held-drag offset fails at zero. Compose's fitted velocity can be
zero at the first touch-slop crossing; using the actual per-event velocity until
the fit exists fixes admission. All five focused touch tests then pass in 8.927 s,
retaining exact offset/quote-position assertions. Full portrait Chief/route run:
44 pass in 82.67 s, two landscape-only assumptions skip. The separate missing-
capability root gate passes in 2.869 s. All six landscape checks pass in 8.884 s:
four existing compact/details/viewport/gap checks plus swipe entry and held
balloon/quote/reversal. This is not full thread/keyboard landscape acceptance.
Both crash buffers are empty. Seven shared-scene PNG/metadata pairs are verified;
normal portrait and both held-swipe images were inspected. The emulator was
restored to 1080×2400/420 and stopped. Evidence is `chief-reply-swipe-*` under
recovery-20261021, with `chief-reply-swipe-checkpoint.json` as the scoped receipt.
Signed 708 predates this swipe work and remains uninstalled.

Oct 21 native thread reference extension: prepared normal/actual-accessibility
thread cases through the real public quote element activation and registered
Close header event. It requires loaded root/direct copies, settled transforms,
draft/focus/viewing, hidden Back/backing accessibility and restored normal state,
with thread/content/editor geometry. Image/control observations now honor hidden,
transparent and clipping ancestors, with an actual runtime observation guard.
All 19 exporter integrity tests and local Swift/project parsing pass. Run
39550609607 at `9324a2b003f634303fb38b1f67be0b5422b73c7c` then passed both
hosted UIKit invocations and all fifteen export checks. Artifact 12323841685 has
verified outer SHA-256
`9eb7b4546a72f45a61cd1b94d6162761ccb8d822074b394460251dc27f8008e3`.
Independent source/fixture/PNG/dimension/interaction verification passes at
`captures/recovery-20261021/ios-chief-thread-verified-9324a2b0/`. Both new thread
images were inspected. The original nine scene bytes and 227-file source package
remain unchanged; interaction fixture SHA is
`77f80ccb3b17a79edbc6792ab24d92bf18274f720f88a0e1f26af8a265203717`.
Actual entry shows parent/reply and excludes nested next; close retains editor
draft/focus/viewing and restores Back/backing accessibility. Normal/AX body is
17/28 points and timestamp/sender captions are 11/20. Native material produces a
gray background where Android's current pure blur/wash remains black; matched
caption, material, font, inset and motion work stays open. The accepted offline
reference does not establish Android, gesture, animation or physical parity.

Oct 21 reply workflow checkpoint: quotes now request the loaded
parent thread, and the source Reply accessibility action targets the selected
message within its loaded root. Missing roots cause no fallback read. The retained
owner holds a one-open snapshot of root/direct replies; later arrivals/read changes
update the backing conversation while the copies stay frozen until reopened,
matching the actual source populate call. Open threads hold window trimming.
Closing retains the draft; owner retirement clears both the draft and copies.

Reply mode hides Back, changes Stop to Close, focuses the same editor, and uses
Reply as its placeholder. Close/backdrop/Escape restore normal chrome while
retaining editor state. Send captures the local reply target on the optimistic
row, exits the mode, and uses the existing text/client-ID send. The actual Chief
backend ignores the generic draft's reply identifier; successful acknowledgment
therefore removes the optimistic quote. An uncertain/failed send keeps its quote
and stable client ID. No new wire parameter or provider/account setup is added.

Thread copies have no nested quotes, use incoming sender names/no direct-chat
avatars, force tails, and space whole rows four points for the same sender or ten
for another. Their unclipped scroll surface sits six points above the composer
container and bottom-aligns shorter content. Layout reads the IME animation target
so the thread aims at the model's final keyboard geometry. The backing transcript
blurs, its copied bodies hide, other outgoing bodies dim to 0.7, and its
accessibility subtree is hidden. The thread exposes a pane title, Reply actions,
and both accessibility scroll-action forms. The thread owns its actions before
the scrolling modifier's built-ins; the installed Compose 1.9.1 bytecode audit
identified the conflicting built-in action. Structured copies currently use
read-only content summaries, including the source one-entry form for noncompact
questions. Ordinary pending cards also avoid duplicating the projected prompt.

The source checker compiles the actual loaded-root guard and projection block
against inert row-builder/model inputs (four cases), audits the Chief transport
boundary, and verifies 17 label values from the pinned UI/Core/Shell catalogs.
Ninety focused Chief JVM cases pass with no skips/failures; debug/instrumentation
builds pass. Final portrait invocation: 40 actual passes in 77.942 s and two
landscape-only assumptions skipped (42 runner cases). All four separate landscape
baseline cases pass in 6.559 s; these cover compact headers/details and transcript
geometry, not the new reply workflows in landscape. Both crash buffers are empty.
The six new reply tests cover loaded-root/frozen-copy/draft ownership, local send
and ordinary acknowledgment, backdrop/retirement cleanup, accessibility retargeting
and Escape, read-only structured copies, and both accessibility scroll-action forms
on a long thread. Five portrait and two landscape PNG/metadata pairs pass hash,
shared-fixture, viewport and density checks. Loaded snapshot, scrolled long thread,
optimistic quote, normal portrait and normal compact images were inspected. The
owned emulator was restored to 1080×2400/420 and stopped. Prior failures and logs
are retained as `chief-reply-*` under recovery-20261021; the final receipt is
`chief-reply-checkpoint.json`. These are authored local-owner workflow captures,
not matched UIKit thread reference captures.

This is a workflow port in progress, not full thread or app parity. Exact swipe/
pulse/haptic fidelity, ReplyTrip row travel and close/blur/chrome fades,
exact poll/failed-card presentation, matched Android/UIKit thread captures,
complex editor/keyboard motion, rotation/locale/contrast/full TalkBack and physical
Mac/Pixel acceptance remain required. The current UIKit reference can be extended
through the real public quote accessibility element's activation; source-package
patching/private selector shortcuts are unnecessary. Signed 707 predates this work.

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
