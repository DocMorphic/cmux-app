# Agent Feed companion

## Measured Close capsule and toolbar allocation verified — 2026-10-23

Correction to the earlier control audit: the original36pt AX frame is inset4pt
inside a44pt painted capsule. Read-only raster segmentation verifies all13 loaded
profiles, excluding shadow. Compact painted leading inset is16pt; wide10pt.
The native Android control now paints44dp, keeps a48dp expanded touch target and
Button semantics, and aligns Close/title22dp below the54dp toolbar top. Its
normal optical role is17sp capped at21dp. Four default-state glyph comparisons
(English, German normal/enlarged, Arabic) differ by at most1pt in each dimension;
this does not establish the unexposed actual iOS Close UIFont. All source-sized
profiles remain one row under enlarged text; very narrow Android windows retain
a complete translated fallback row. Static native fill/border/shadow follows the
observed default capsule; dynamic glass/backdrop/tablet material remains open.

The installed production APK e6087a8cdbc5c3eaf4234c86a947153a0f3a6a6a6d7cbd94595518c8dc1ae7cf
passes ten native/control/header/Activity/process cases125.524s, compact dark/light
5.472s/5.815s, and wide landscape/portrait4.937s/4.737s (14 invocations). These
include long painted text, links/code/selection, cancellation/retry, adaptive
recreation and two real process/task restores within the emulator scope. A
subsequent test-only capture-order adjustment keeps keyboard focus after default
screenshots; its one header case passes11.255s on the same production APK, with
21 actual edge taps and one Enter activation. Full cohort test APK6cb7f189…;
final header test APKf0f1d055…. Exact hashes/source bindings are retained separately.
Both runs have empty crash buffers, no preflight System UI ANR, restored settings/
window overrides and owned-emulator exit0. Selected dark/light/wide/enlarged/RTL/
narrow frames reviewed. Initial missing-opt-in compile failure and earlier focused
captures remain retained. No physical device, real account/transport, TalkBack or
full material acceptance is implied by these tests.

Evidence under captures/recovery-20261023/: native-feed-reader-capsule-checkpoint.json,
native-feed-reader-capsule-paint-audit.json, native-feed-reader-capsule-glyph-comparison.json,
and native-feed-reader-capsule-runtime-v1/ and-v2/. Next: rendered secondary/link/
code roles, loading/error allocation, landscape-specific horizontal policy and
remaining full-source/device gates. Candidate722/physical712/account/pairing/global
pins unchanged; no new signed package or Mac/phone action. Goal remains active.

## Original control/color collection verified; next layout differences measured — 2026-10-23

Run40003411274/job120585108804 succeeds at5140226d. Actual original capture
methods pass: standard255.061s, enlarged38.013s, tablet44.181s. Strict CI/local
receipts are identical for all52 compositor pages and22 unchanged original files;
31 evidence guards pass. Artifact12523178842 is8,808,544 bytes, digest
2b1ff8f3a8c3a7d461ec838ed1d5c3bd2b8712fdffdfe256b731f267bbff0489.
Dark/light, enlarged German and tablet loaded frames reviewed. No job remains live.
Scope is the original component in its authored offline presenter, not real
account/transport, the full application, Android or physical/full parity.

All39 presented observations expose only the title UILabel; zero public buttons
and zero Close label candidates. Close UIFont remains unobserved. Source AX and
glyphs measure a36pt AX frame within44pt paint (corrected by the paint audit
below),54pt bar, title and
Close center22pt below bar top in all13 loaded profiles. At that checkpoint Android
header centers at about27dp and grows under enlarged text (e.g. German59.333dp,
narrow German91.667dp stacked). Original German accessibility stays54pt/one row.
Close glyph ink is73×13pt ordinary German and87.667×15.667pt enlarged; these are
thresholded compositor bounds, not actual UIFont sizes. Use those measurements
to verify a native control role and vertical allocation without inventing a font
observation. See native-feed-reader-close-layout-audit.json.

Raw UIKit link traits are9/132/255 dark and0/122/255 light; dominant rendered blue
in the reviewed loaded phone frames is0/145/255 and0/136/255. The inline link is
the only visible blue element there, so this is a candidate rendered link tint;
the color-space/style cause of the difference is unproven. Do not equate raw
semantic components with compositor output. Code-padding pixels are40/40/43 dark
and243/243/244 light. TertiaryLabel alpha is76/255. Tablet material remains visibly
tinted despite scalar systemBackground28/28/30; its cause/material port stays open.

Evidence: captures/recovery-20261023/ios-feed-reader-controls-checkpoint.json,
ios-feed-reader-controls-rendered-color-audit.json, native-feed-reader-close-layout-audit.json,
and ios-feed-reader-controls-40003411274/canonical-local/. Next port remaining
Close/capsule/vertical allocation and actual rendered color roles, loading/error
and broader gates. Candidate722/physical712/pairing/global pins unchanged.

## Measured reader background and primary labels matched — 2026-10-23

Read-only pixel/trait comparison bound to original run39961701278 showed the
reader still inherited the shell palette: dark11/12/14 with244/245/247 text and
light242/242/247 with28/28/30 text. Original presented-sheet padding pixels and
UIKit label traits are dark28/28/30 with white text, light white with black text.
The reader now scopes those background/surface and primary-label roles through
its own MaterialTheme; loading ownership and saved coordinates remain outside it.
Other palette roles, controls and materials are not accepted by this change.

Two actual compact-landscape profiles pass, dark5.893s/light5.777s. The test reads
the compositor's blank native-body padding pixel and actual ready native heading
currentTextColor, matching those exact source values. Real window flags0/24,
geometry/insets, Back/release also pass. Both settled frames reviewed, crash
buffer empty, settings/window restored, owned emulator exits0. App SHA256:
3f421863158bfb0d3fb02af13f1bc9d2914e8a9ab76c047f21f5f700d1a210c7;
test8997db97b47bad3b1751de212e49dde7800e7a85689e51935410da8e445d67e5.
Evidence: captures/recovery-20261023/native-feed-reader-palette-audit.json,
native-feed-reader-palette-checkpoint.json and native-feed-reader-palette-runtime-v1/.
This is a separate two-profile artifact, not a repeat of the earlier13 checks.
Close, secondary/link/code roles, tablet/glass materials, loading/error allocation,
broader appearance/lifecycle/source and physical/TalkBack gates remain open.
Candidate722/physical712/pairing unchanged; no new signed artifact or user action.

Original control/color reference run40003411274/job120585108804 is complete and
verified at5140226d; the entry above supersedes its live capture observations.
All52 exports verify. See ios-feed-reader-controls-checkpoint.json for limited
measurement acceptance and the still-unobserved Close UIFont.

## Settled compact system-bar appearance verified — 2026-10-23

The next original-sheet collector also walks visible public UILabel/UIButton
observations outside UINavigationBar, including title-label and attributed-title
UIFont metadata, and resolves link/tertiaryLabel colors in actual traits. It does
not assume Close uses those controls or guarantee a font observation. The verifier
validates all raw control/font/color shapes and reports Close label candidates
only when contained in the independent AX Close frame. All31 synthetic integrity
guards pass; Swift syntax parses locally. Actual UIKit compilation/captures and
limited measurement acceptance are now verified on run40003411274, as above.
Whole pinned original source declarations and all13 profiles/four states remain
unchanged; these are observer/attribution additions only.

The focused diagnostic passes with forced dark6.760s and light5.949s app themes
on an actual dark-mode API36 system. Focused reader WindowInsetsController flags
are0/dark and24/light: status/navigation light-appearance bits both follow the
app theme. The native view ancestry reaches Material's own dialog layout, and
settled compositor frames show visible light/dark gesture indicators respectively.
The test now records initial/settled flags, window state and images, waits for
idle and asserts actual navigation appearance before Back/release. The earlier
isolated dark-indicator capture has not reproduced; its precise cause is unproven.
No production bar-code change was justified. This accepts these settled profiles,
not all transition timing or physical/platform variants.

App SHA25665dc2dc98dc5b2ba1b3c27779631e5d725e14421a919963225c0aaf8e2a2e416;
test8c2f1446abdfb1141290d9ecfb16d55973f3454ea4032704df758c83562f2ef6.
App source36baf84a; build metadata changed from the prior13-invocation artifact,
so this is a separate two-profile check. Both settled images reviewed. Crash
buffer empty, settings/window restored, owned emulator exits0. Evidence:
captures/recovery-20261023/native-feed-reader-bars-checkpoint.json and
native-feed-reader-bars-runtime-v2/. Candidate722/physical712/pairing unchanged.
Next: improve original Close UIFont/control/color observations before remaining
reader appearance changes. Broader app/source/physical/TalkBack gates stay open.

## Compact landscape and actual class-change restoration verified — 2026-10-23

The original reference spans874 points in compact landscape with24-point drag
spacing and54-point toolbar. Android now removes Material's640dp width cap for
compact sheets, fills their landscape height, keeps the portrait16dp/wide10dp
drag areas and applies actual platform safe insets. The pinned BOM2025.09.00
resolves Material1.3.2: its source applies all contentWindowInsets without
consuming the sheet offset. Consuming that positive offset preserves only the
remaining top camera/status clearance; the ordinary portrait runtime asserts
dragTop=max(sheetTop,safeTop), plus16dp drag and54dp header. This fixes the first
prototype's extra portrait gap while retaining real cutout protection.

New actual emulator checks cover the full2622×1206 compact window (874×402dp),
safe bounds left144/top84/right0/bottom72 pixels, body144,318–2622,1134, Back
dismissal and native reader release. Wide profiles retain580×650dp panels,
centering within actual usable bounds and real outside-tap/Back dismissal.
Orientation is requested through UiDevice and awaited on the actual Activity;
the host's settings/delay alone did not establish it reliably after a fresh boot.

The routed production Feed also survives real compact→wide→compact Activity
recreation using local socket RPC and a290,926-character body. Selected character
13,641 stays selected; original top character13,637 remains in the restored top
line starting13,628. The same admitted client survives and closes on finish.
Four final saved-state snapshots10,024/10,200/10,408/10,408 bytes contain the
Compose registry and coordinates, without the body marker. Native blocks/links/
horizontal code,14k-line painted text,21 header profiles, cancellation/retry,
rotation/type/recreation and two process/task restores also pass in this cohort.

Final runtime-v4: ten cases140.596s, compact5.148s, wide landscape4.873s and
portrait4.636s. App SHA256:
81b0743932369b9ff774ae17df6329f97ca193eb1a1d54bdc7cfb7c76cdc8e57;
test SHA256:8c8d3a5111b5f139549dd839fc70ea32b0db9b6cefbf4ce6c3c06b844d82264e.
Installed hashes/source bindings verified. Reviewed loading, compact and both
wide captures; crash buffer empty, no preflight System UI ANR, settings/window
read back restored, owned emulator exit0. Evidence:
captures/recovery-20261023/native-feed-reader-compact-checkpoint.json and
native-feed-reader-compact-runtime-v4/. Earlier lookup failure (merged drag
semantics), setup precondition failure and prototype passes/inset finding remain
in attempt1/2/3 checkpoints. The naive portrait top-omission variant was compiled
but never run or accepted; final source instead retains remaining safe padding.

One final compact capture shows a dark gesture indicator against the dark sheet,
where prototype/wide captures show a light indicator. Determine settled window
appearance flags and capture timing before accepting its system bars. This is an
unresolved observation, not proof of a specific cause. Close UIFont/capsule,
materials/palette/spinner/error allocation, broader adaptive/platform variants,
real accounts/transport, physical Pixel/Mac/TalkBack and full app parity remain
open. Candidate722, physical712 and pairing are unchanged; no user-device action.

## Reader changes packaged in signed candidate 722 — 2026-10-23

Clean source726ccd3a produces signed722 with the existing signing identity;
full JVM3,148 pass/four skips, package22 native/22 viewer assets/16 debug fixtures
excluded and private Firebase identity checks pass. Two real cold starts render
reviewed sign-in roots with empty crash buffers on a new owned API36 AVD; that
AVD is removed after exit0. No authenticated user, upgrade or physical acceptance.
The signed artifact and receipt live in captures/runtime/parity-candidate-722/;
full checkpoint is captures/recovery-20261023/parity-candidate-722-checkpoint.json.

Original app-context run39983680775/job120517222421 succeeds at driver726ccd3a.
The complete original workspace/dependencies compile unchanged at a5911a1f,
including checked GhosttyKit and tracked Stack Auth sources. Original methods
testFeedFullTextReadingAndRetry (113.321s), testFeedRowTapOpensItsDestination
(27.415s) and testFeedStartsBelowToolbar (16.030s) all pass. Before/after source
statuses are empty; local verifier matches the complete22,141-entry source index.

Six exported1206×2622 compositor frames are attributed to their original method,
name and iPhone17Pro device, hashed and reviewed: Feed entry, toolbar/first row,
loaded markdown, final paragraph, scoped search and row destination. Ten mutations
of copied genuine evidence are rejected: wrong execution, dirty/missing source,
failed/missing method, wrong association/device, absent PNG, failure capture and
path traversal. Original evidence is unchanged. Verifier:
scripts/verify-ios-feed-context.py. Artifact12516630092,59,521,045 bytes, digest
b0166942a9f06c3c405b2780547e1fccc3616516b5ae3af9ae4f0b573e4bcb4d.

This accepts the phone dark Feed caller reference beyond the isolated presenter.
It shows the reader's capsule Close, elevated dark body and translucent toolbar
over scrolled text in the real root. The original DEBUG preview supplies synthetic
items/loaders and an explicit Cloud callback; its row destination is an Opened
preview tab placeholder. No real account/transport, general tab visibility, full
locale/profile coverage, Android or full app acceptance follows from this run.
Evidence: captures/recovery-20261023/ios-feed-context-checkpoint.json,
ios-feed-context-integrity-guards.json and
ios-feed-context-39983680775/canonical-local/receipt.json. Remaining reader visual,
source-audit, physical Pixel/Mac/TalkBack and full app gates remain open.

## Measured title role and native restoration verified — 2026-10-23

The original title UIFont is now observed directly: 17 points at ordinary text,
21 at accessibility medium versus body17/28. Android's title starts at17sp and
caps the role at21dp through the actual font scaler, without capping body text.
The measured wide bar starts10 points below its panel top; Android now uses that
10dp drag area and14dp horizontal bar inset. Original capture run39969462359
succeeds at8c3d0817: all52 images verify, original CI/local receipts match, and
stronger local guards bind all39 presented title fonts/frames to independent AX.
All25 synthetic integrity guards pass. Close UIFont is not among those observed
labels; its typography and the native material remain open.

A restored deep offset could scroll a one-line placeholder away before its large
paragraph finished precomputation. The native list now stages the target row at
zero offset, then applies the saved character/offset when real metrics exist.
Eleven invocations pass on the final candidate: nine native/controls/header/
Activity/process cases in108.875s, wide landscape4.057s and portrait3.798s. This
includes21 controlled header profiles, actual links/horizontal code,14k-line
painted tail/selection/body-free save, actual system rotation/type and recreation,
and two real process/task restorations. Final saved bundles10024/10028 bytes;
process viewports match at two successive positions. App/test installed hashes
match the captured APKs; preflight found no System UI ANR and took no Wait action.
Crash buffer empty, settings and wm read back restored, owned emulator exits0.
Reviewed Arabic, narrow German and wide frames. No new JVM-suite claim.

Evidence: captures/recovery-20261023/native-feed-reader-title-role-checkpoint.json
and ios-feed-reader-typography-checkpoint.json. Retain earlier Activity failures,
installation/boot observation timeouts and System UI ANR interference; old output
pulled by failed setup attempts is excluded. A timed-out APK installation later
proved complete by exact hashes, so the same recovered emulator resumed without
redundant installation. Observational timeouts are not app test outcomes.

Close/capsule/palette, loading/error layout, compact landscape widths, adaptive
class transitions, whole production caller, real transport/Pixel/Mac and TalkBack
remain open. Candidate721 and physical712 are unchanged; no signed release or
phone/Mac/pairing operation. The full goal and source-pin audit remain active.

## Title allocation and adaptive reader presentation verified — 2026-10-23

The title now stays centered while space permits, then moves away from Close
with the measured 16-point gap (dp on Android). It uses the full remaining row
before falling back to a separate row in unusually narrow/enlarged windows.
The ordinary compact toolbar is 54 dp with a 16 dp drag area; larger windows
use a centered panel capped at the original standard iPad capture's 580 × 650
points, expressed in dp and constrained by the actual Android window. Android
system bars/camera cutouts define the usable area. This does not accept all
UIKit sizing, colors or materials.

Nine invocations pass against identical production APK
02745e5b7664f3b1c9b0a6ed01f1261d464687c9bf1df83bad5857fd76ceb7e3:
seven compact checks / 123.189 s (21 translated/type/width profiles, loading,
retry/cancellation, actual rotation/system type and recreation, two actual
process restorations) and wide portrait/landscape / 4.199 s and 4.667 s. Actual
platform outside taps and Back produce two dismissal callbacks per wide profile,
remove the panel and release its reader. UiDevice's Back return flag is false
even though dismissal occurs; preserve that diagnostic, do not equate the flag
with app behavior. The initial whole-display center assertion ignored a measured
150 px landscape camera cutout; the final check uses actual WindowMetrics/insets.
Failed attempts are retained. Crash buffers empty, settings restored, final wm
readback matches, owned emulators exit 0. Reviewed compact/wide images.
Evidence: captures/recovery-20261023/native-feed-reader-presentation-checkpoint.json.
No new JVM claim is made for these UI-only changes.

Typography run 39969462359 at 8c3d0817 succeeds: all 52 images and original CI/local
receipts match. Stronger local verification binds the title UIFont/frame to its
independent AX target on all 39 presented pages; all 25 synthetic guards pass.
Title sizes are 17/21 points versus body 17/28; the bar is 54 points, tablet drag
area 10 points. Close UIFont remains unobserved. See the typography checkpoint.
The Android title-role/tablet-spacing candidate and staged large-row restoration
fix are under a new 11-invocation runtime audit. Its prior attempt had two Activity
selection waits fail; retain those failures and do not extend the earlier nine
passing results to this pending candidate. Whole original Feed preview
entry/root and original testFeedFullTextReadingAndRetry are now source-audited in
ios-feed-reader-production-preview-audit.json. This is not a compiled caller capture.

Fonts, capsule/material palette, spinner, error allocation, compact landscape
width, adaptive class-transition retention, production caller/account/transport,
physical Pixel/Mac and TalkBack remain open. Signed 721 and physical 712 stay
unchanged; these edits are not on the phone. No Mac app/pairing operation.

## Original full-sheet component reference accepted; visual gaps measured — 2026-10-23

Run 39961701278 at source a0b825d5 succeeds. All three actual XCUITest methods
pass (standard 438.043 s, enlarged 71.369 s, tablet 49.815 s), followed by strict
export association. The same verifier was rerun locally: the entire CI/local
receipt and all 52 image hashes match. The reference binds 22 whole original
files at a5911a1f, authored presenter/loader/adapter, clean source commit,
fixture/catalog/package/preparer and host/test hashes, actual runtime/traits/
localized controls and independent XCUIScreen PNGs. It covers 13 profiles and
loading/error/loaded/closed, including real Close cancellation and one successful
retry. The two earlier failed loading-selector attempts remain retained.

English dark loading/error/loaded, English light loaded, Arabic RTL loaded,
German enlarged loaded and tablet loaded were visually reviewed. This accepts
the original component reference in its declared authored offline context, not
Android UI parity or original production caller/account/transport/replay/global
appearance. The original caller and transparent replay-only overlay are separately
source-audited. An original DEBUG AgentFeedDecisionPreviewView using the production
Feed/scaffold is located for a broader caller comparison; its dependency closure
and production-global context remain unverified.

Actual data changes the next implementation work. UIKit does not force the title
to remain at the window center: German title centers are 206.333 pt normally and
236 pt enlarged in a 402-pt window; both leading edges are Close.right + 16 pt.
English stays centered. The earlier unconditional centered-title test is therefore
a bounded Android layout check, **not** the correct full source-parity contract.
Toolbar enlargement is also smaller than body enlargement: German body preferred
size 17→28 pt, title AX height 20.667→25.333 pt, Close AX height remains 36 pt.
These frames do not directly prove the toolbar's actual UIFont point size.

The presented semantic background is elevated level 1: dark 28/28/30 and light
white. Actual tablet panel pixels differ from that semantic color (dominant
20/25/18); the material/caller/runtime cause is unverified, so neither value is a
blanket production tablet color. The tablet presents a centered panel, while the
current Android wrapper delegates to a bottom sheet. Source cancellation uses a
capsule; the source loading indicator is a gray native spoke view. Error-group
allocation also differs: normal English text starts at 47 pt, loaded content at
16 pt; enlarged German error text starts at 16 pt.

Next: reconcile source collision allocation and toolbar role sizing (including the
current Android extra-row choice), state-specific sizing/padding, scoped reader
palette/cancel/loading appearance and wide presentation against this data.
Do not claim those differences are unavoidable OS limits without evidence. Native
glyph/material rendering and production caller context require separate checks.

Accepted receipt/images:
captures/recovery-20261023/ios-feed-reader-39961701278/canonical-local/.
Detailed measurements, open findings, hashes, reviewed frames and failed attempts:
ios-feed-reader-accepted-checkpoint.json and ios-feed-reader-caller-audit.json.
The full goal, global source pins, real Pixel/Mac/transport/TalkBack gates remain
open. No phone/cmux Mac/account/pairing action or new signed artifact. Physical
712 stays released; signed 721 excludes the newer reader changes.

## Enlarged translated header reflows without text loss — 2026-10-23

The previously reviewed narrow German truncation is repaired. The native reader
header measures the translated labels' intrinsic widths. It retains the centered
single row when they fit; otherwise Close stays leading and the centered title
uses a separate row, with up to two title lines. It preserves the chosen text
size instead of shrinking it. This is an explicit Android layout choice, not an
assertion of identical iOS toolbar typography or an unavoidable OS difference;
source toolbar metrics/appearance still require comparison before full UI acceptance.

The strengthened production-header test passes all 21 controlled locale/font/
width profiles with complete rendered text, no ellipsized lines, centered title,
nonoverlapping bounds, full semantics and click actions. Six profiles use the
extra row (enlarged German/Spanish/French and narrow enlarged English/Arabic/
German). Normal English and enlarged Arabic single-row frames plus narrow German
two-line text were visually reviewed. A seven-case affected cohort passes in
91.282 s, including loading/retry/cancellation and native Activity/system-change/
actual process recovery. Full JVM: 3,148 pass/four skips, no failures/errors,
429 XML. Crash buffer empty, original settings restored/read back, host wall and
monotonic times agree, emulator exits 0. Three optional pulls fail only because
the broader runner names test groups not selected in this seven-case cohort;
all required selected artifacts are present. Evidence/source/APK/archive binding:
captures/recovery-20261023/native-feed-reader-reflow-checkpoint.json.

The source reference job 39961701278 has passed standard and enlarged phone
capture steps and is currently capturing the tablet. Accept no complete gallery
until export association and actual images/traits are checked. Do not restart a
live job from a read timeout. The caller audit independently records the original
Feed's sheet(item:) composition and replay-only privacy mask. Its inspected whole
UIView types are transparent, noninteractive and AX hidden; they add no drawing
overrides. The offline presenter omits production Feed/account/global appearance
and replay integration. It does not establish replay/analytics or production
caller acceptance. Source hashes: ios-feed-reader-caller-audit.json.

No signed build/physical phone/cmux Mac/account/pairing operation. Signed 721
excludes this change; physical 712 stays released and fresh availability is still
pending. Full sheet palette/materials/typography, real-device/transport/TalkBack
and all broader goal gates remain open. Global source pins remain unchanged.

## Native-only original reader controls and lifecycle regression pass — 2026-10-23

Full text now follows the original's native-renderer choice throughout. The
additional Source/Formatted toolbar and unused plain-source reader are removed.
Close remains the cancellation action; the title is centered independently of
the translated Close width, with bounded title space and ellipsis under narrow
constraints. Loading is centered with the indicator above its secondary label;
failure uses the original single generic localized message/Retry. Older modal
raw flags no longer select a different reader; native saved coordinates are used
when available. No migration from raw offsets into rendered-text offsets is
claimed. Historical Source-mode receipts describe earlier builds, not this UI.

The updated final owned-emulator cohort passes **12 tests / 113.567 s**: native
control/vertical-loading behavior, 21 controlled header locale/type/width profiles,
all nine native block/link/code/table checks, real saved horizontal-code position,
14,000-line painted tail and body-free state, two ordinary Activity recreations,
actual rotation/system-font changes, retained client/selection, two actual
isolated-process task restorations at successive native positions, native reader
reconnect/close reset and native/terminal/composer appearance-buffer regression.
The removed mode's tests are replaced with supported native-reader behavior.

The Activity cases reload 290,926 characters through 54 pages each, retain the
same non-null client until finish, preserve exact normal anchors/selection and
text identity under real reflow. Actual final provider-containing state snapshots
are 9,976 bytes (ordinary) / 9,980 (system changes), without message bodies.
Process restoration reloads 298,910 characters, matches the native text comparison
viewport at both positions and uses fresh PIDs/RPCs. The explicit right 20-dp
padding/scrollbar exclusion and lack of process-selection acceptance remain.
Full JVM: **3,148 passed/four skipped**, no failures/errors, 429 XML. Inputs,
removed source, archived APKs, results and image hashes:
captures/recovery-20261023/native-feed-reader-controls-checkpoint.json.

Loading, Arabic enlarged header, German narrow header and enlarged restored body
frames were reviewed. German narrow 260-dp/font-2 labels visibly ellipsize; full
semantic labels, centering, nonoverlap and click actions are verified, not complete
visual/accessibility acceptance. Spinner/materials/colors/fonts and full-sheet
comparison remain open. Crash buffers empty, original settings restored/read
back, wall/monotonic time agrees, emulator exits 0. No new signed artifact;
721 excludes these changes, physical 712 stays released. No phone/cmux Mac/
account/pairing action; pending availability and full goal/source pins remain open.

Original full-sheet runs 39955557256 and 39957344402 compile/launch all 22
whole source files but fail authored loading-target checks. Genuine diagnostics
show the nested ProgressView text has a zero AX frame; its public Activity
Indicator has the actual positive frame and localized label. The harness now
queries that native element, preserves role/hittability, enforces real geometry
and retains PNG/AX diagnostics. Twenty-one synthetic evidence guards pass,
including zero-frame/wrong-role rejection. Corrected capture 39961701278 at
a0b825d5 is running; no complete original gallery is accepted yet. Preserve both
failed attempts and poll the actual job. The English-dark forensic sheet is
elevated (level 1) with resolved background 28/28/30; it does not establish the
other profiles or production caller/account/transport/privacy-overlay context.

## Full-sheet source capture in progress; failure copy aligned — 2026-10-23

The new reference compiles **22 whole unchanged upstream files** at `a5911a1f`,
including original `AgentFeedFullTextView`, real model, embedded/native/web
renderer dependencies and both full localization catalogs. An explicitly authored
offline presenter/loader and public entry-point adapter host the original sheet.
No original model substitutes, extracted view bodies or disabled source branches
are used. The capture declares 13 profiles × loading/error/loaded/closed states,
plus loading cancellation and retry checks. It measures actual sheet traits and
interface level without inventing elevated-level requirements for compact height.
See `tests/ios-feed-reader-reference/README.md` for exact context and limitations.

Seventeen synthetic export/input guards pass; preparation/source hashes and PBX
lint pass. Local real model/shared-helper/macOS renderer builds pass, but their
conditional macOS compilation does **not** prove UIKit/iOS sheet behavior. Actual
capture run `39955557256` at `d1866fb4` is in progress. Its outputs are unaccepted
until the declared captures, controls, provenance, traits and images are verified
and reviewed. Keep polling that run; do not restart from an observation timeout.

One source-proven failure UI difference is fixed independently: Android now shows
only the existing localized generic unavailable message and Try again, matching
the original view, without the extra exception-message line. The focused actual
component test passes / 5.186 s: generic message visible, raw injected exception
text absent, retry acknowledges a successful read once and dismiss cancels a
pending read without another acknowledgment. Its error frame is reviewed; crash
buffer empty, settings restored/read back and emulator exits 0. Full JVM suite:
3,148 passed/four skipped, no failures/errors (3,152 cases, 429 XML). Evidence:
`captures/recovery-20261023/native-feed-reader-error-checkpoint.json` and its
runtime/full-JVM archives. Signed 721 excludes this newer copy change.

This closes that failure-copy difference, not sheet appearance or full parity.
Source/Formatted controls, title/loading layout/palette and full-sheet/elevated
comparison remain open along with real accounts/transport/Mac/Pixel, accessibility
and broader app gates. Phone availability remains pending; physical 712 stays
released, the pairing popup can stay closed, and no phone/cmux Mac/account/pairing
operation occurred. Global source pins and full goal remain open.

## Actual system configuration and process restoration — 2026-10-23

The routed native reader now has real Android-system evidence beyond manual
`ActivityScenario.recreate()`. The final two-case cohort passes in **45.519 s**:
actual landscape rotation replaces the Activity and changes native reader width
from 1,206 to 1,920 px; actual system font scale 1 to 2 replaces it again and
changes native Source text from 48 to 84 px and Formatted text from 51 to 87 px
(Android's nonlinear font scaling). The same selected text survives. The original
character remains in the top visible line after reflow, rather than requiring an
invalid identical pixel offset across different line breaks. Source scroll moves
from 8,000 to 31,184 px while retaining its text anchor and selection. The ordinary
recreation regression still retains its exact anchor/8,000-px scroll. Each case
reloads a 290,926-character body through 54 page RPCs, retains the same non-null
admitted client, closes it on finish and saves only 10,020–10,032-byte final
provider-containing state snapshots without message text. Ten native frame/trait
records and unchanged screenshots retain the measured reflow evidence.

`WorkspaceProcessRestorationTest.fullFeedReaderRestoresSamePaintedPassageAfterRealProcessDeath`
separately passes in **46.717 s**. It drives the actual MainActivity subclass via
UIAutomator, waits for bound native text, performs real swipes and proves that the
painted passage changes. Android retains task 1580 and its saved Bundle while only
the isolated UI process is killed: PIDs 3527 → 3666 → 3736; instrumentation PID
3234 survives. Restoring Formatted, then Source, reloads the 298,910-character
message through fresh page RPCs (38 reads after the first restore, 57 after the
second), retaining both modes' same text viewport. Full raw screenshots are saved.

The automated text comparison explicitly excludes the right 20-dp native padding/
scrollbar strip. Independent full-image comparison finds the entire Source pair
identical; Formatted differs only in a 12×24-px indicator rectangle, with all text
pixels identical. Earlier whole-viewport assertions correctly fail on that
indicator; a one-second fade wait does not remove it. This exclusion is a stated
text-position check, not whole-UI equivalence. The first attempt also exposed an
insufficient list-ready/scroll-Boolean harness check, fixed by real body admission
and verified swipe-driven paint change. Failed attempts and the test-only missing
import build failure remain retained. Process-restored selection is **not** tested.

Final restored frames are reviewed; crash buffers are empty, original settings
are restored/read back, host wall/monotonic times agree and both owned emulators
exit 0. The final configuration and process cohorts use the identical production
debug APK. No production source changed after `7700152`; signed 721 remains the
verified candidate, and no additional release is needed for these test changes.
Evidence/checkpoints: `captures/recovery-20261023/native-feed-reader-system-config-*`
and `native-feed-reader-process-*`. No phone/cmux Mac/account/pairing action;
physical 712 stays released, availability is pending, full goal/pins remain open.

Original full-sheet UI is still unaccepted. The unchanged upstream Full text view
at `a5911a1f` declares NavigationStack, inline title and Close, while Android adds
Source/Formatted and a transport error line. The accepted original reference is
only the native leaf in a standalone base window. Capture original sheet/elevated
traits and reconcile these controls/error/title/chrome differences next; see
`native-feed-fulltext-chrome-audit.json`. Broader in-flight/revocation/selection,
real authentication/Iroh/Mac/Pixel, older APIs, TalkBack and app parity remain open.

## Routed Activity reader recovery fixed — 2026-10-23

Real Activity recreation exposed a load race that the leaf's state-restoration
test did not cover: cached rows and the restored Full text sheet return before
the retained Feed session is fresh. The one-shot load immediately failed with
“Connect to this computer to use Feed” and stayed in Retry after readiness
returned. The read delegate now waits up to 30 seconds for an admitted session,
checking the same live row/owner throughout and again before the RPC. Closing,
replacement, account changes and coroutine cancellation retire the wait. A real
unavailable session ends in the existing actionable error. RPC admission, identity
and version/size guards remain authoritative; this never queues a mutation.

Four new JVM cases cover readiness, changed owner/row, close cancellation and
bounded unavailability. Full suite: **3,148 pass, four skips, zero failures/errors**
(3,152 cases, 429 reports), archived in
`native-feed-reader-activity-full-jvm-v5.zip`.

`AgentFeedReaderActivityTest` drives the production NativeScreen Feed through an
emulator-local socket and synthetic credentials. It opens a 290,926-character
message, performs two real `ActivityScenario.recreate()` calls, preserves the
Formatted anchor exactly at 13,637 and selection offset 13,641, preserves Source
scroll exactly at 8,000 px with its selection, returns to the previous Formatted
position and reloads the body via 54 real 16-KiB-page RPCs. The admitted foreground
client is explicitly non-null, stays identical through both recreations and
closes on Activity finish. The final strict run passes in 20.233 seconds. The
preceding two-case cohort also passes the existing full Feed search/question/
permission/reply/navigation regression in 36.767 seconds on the same app APK.

Actual final saved-state bytes are copied at
`Application.onActivityPostSaveInstanceState`, before the new Activity consumes
the restored providers. Both final snapshots are 10,024 bytes and contain the
real root/dialog Compose registry providers; neither UTF-8 nor UTF-16 data
contains the message sentinel. This replaces an insufficient intermediate test
that retained a mutable Bundle reference and inspected it after restoration had
removed provider entries. An earlier fixture also incorrectly returned the whole
body in one response; it now implements the real 16-KiB paging protocol. The
original routed failure/error frame and all intermediate diagnostics are retained.

Restored Formatted/Source frames were reviewed. Final crash buffers are empty,
settings restores are read back, host wall/monotonic timing agrees, and the owned
emulators exit 0. Evidence: `native-feed-reader-activity-runtime-v6/` and strict
`native-feed-reader-activity-runtime-v7/`; checkpoint:
`captures/recovery-20261023/native-feed-reader-activity-checkpoint.json`.
This verifies same-configuration Activity recreation with local socket RPC,
not process death, actual system rotation/font changes, real authentication,
Iroh/Mac/Pixel, TalkBack or all lifecycle/parity gates. No phone, cmux Mac app,
account or pairing operation occurred; full goal/source pins remain unchanged.

## Original native UI comparison and RTL fixes — 2026-10-23

All 39 declared original SwiftUI compositor images (13 profiles) are now bound to
source commit `a18ed616`, upstream `a5911a1f`, the unchanged native declaration,
five whole pure files and the exact fixture/host/test hashes. Capture run
`39916186491` passes all three UI-test methods; its tablet step times out during
result finalization after the test passes. Its old export guard also rejects
UIKit white's actual `1.0000001192092896` conversion. The repaired verifier accepts
only a 1e-6 floating epsilon without rewriting observations; all 18 adversarial/
input guards pass. Retained artifact association run `39943618331` succeeds with
all 39 original images. This is a verification of retained source pixels, not a
new UI execution or a claim that the original capture workflow was green.

Original English/Arabic, light, landscape, accessibility and tablet frames were
reviewed. The earlier `39914921101` run contains 30 validated portrait frames but
fails because the authored visibility predicate invents a 30-pt inset. The
landscape source heading is legitimately at 16 pt; the corrected predicate uses
the actual viewport. Both failures and original exports remain preserved.

Comparison exposes and fixes concrete Android native-reader differences:
interface-leading alignment now follows RTL even for English paragraphs;
ordered markers retain punctuation; code captions follow the interface direction;
wide code/table views initially expose the RTL leading end, with explicit saved
offsets retained; native inline code has no extra background, links have no extra
underline, and rules use one physical pixel. Timeline/reply styling retains its
existing default. An initial RTL refinement fails because TextView's separate
horizontal-scroll mode aligns against a virtual 1,048,576-px line. The enclosing
horizontal scroll view already measures intrinsically; disabling that TextView
mode fixes the off-view text while retaining long horizontal code.

The final cohort `native-markdown-refinement-runtime-v2` passes all eight cases:
21 locale/font/theme/width profiles (63 images), geometry/initial RTL offset and
inline-decoration checks, actual long-press/Copy/clipboard, native code/table/link
and long-message restoration, plus all four existing Feed loading/Source cases.
The 548,915-character formatted paragraph reaches 826,059 px, with 6,788 of 6,835
expected tail ink pixels painted. Reviewed final Arabic normal/narrow code, tail
and Copy-menu frames match those bounded behaviors. Crash buffer is empty;
settings restore is read back and the owned emulator exits 0. The reported
1,351.425-second duration spans repeated host sleep/dark-wake events, recorded in
`host-power-events.log`; it is not accepted as an awake performance measurement.
The final full JVM suite passes 3,144 with four skips and no failures/errors
(3,148 cases, 428 XML), archived in `native-markdown-refinement-full-jvm-v2.zip`.

This is a standalone base-window source leaf, not original Feed sheet chrome or
its elevated palette. Android uses its own system font metrics/nonlinear scaling
and app palette; these are recorded rather than called exact iOS glyph/material
parity. Actual system locale/Activity/process changes, TalkBack, broader selection,
real transport and Mac/Pixel remain open. No new signed artifact or physical/
account/pairing operation occurred. Checkpoint: `native-markdown-ui-checkpoint.json`.

## Native leaf layout and actual Copy gesture — 2026-10-23

The same byte-identical document used by `tests/ios-native-markdown-reference/`
now runs in `NativeMarkdownLayoutTest`: 21 controlled locale/font/theme/width
profiles, 63 actual Android compositor images, nine locales, font scales 1/2,
English light appearance and English/Arabic 260-dp readers. The native context's
locale, direction, font scale, text metrics and row frames are recorded; all
profiles pass in 15.074 seconds. Reviewed normal dark/light, large German code
and narrow large Arabic frames remain readable. This uses configured native
contexts/composition traits, not actual system locale/font changes, Activity
recreation, TalkBack, original Feed sheet or physical-device acceptance. Android
nonlinear font scaling is recorded rather than equated to an iOS Dynamic Type
category. The original iOS Xcode capture is still pending; there is no completed
matched source UI comparison yet.

`NativeMarkdownSelectionTest` also passes in 5.499 seconds: a real stationary
long press selects the bold rendered word, Android exposes its actual Copy menu,
and tapping it puts exactly `copyword` in the OS clipboard. The menu screenshot
was reviewed. This closes that bounded synthetic native gesture check, not
TalkBack, multi-block selection, physical gestures or all selection/lifecycle
gates. Both owned emulator runs have empty crash buffers, read-back setting
restores and exit 0. The phone/Mac/account/pairing were not accessed.

Evidence: `captures/recovery-20261023/native-markdown-layout-runtime-v1/`,
`native-markdown-selection-runtime-v1/` and `native-markdown-ui-checkpoint.json`.
The 15 export/input integrity guards pass. The source capture compiles the whole
unchanged SwiftUI native declaration plus five whole pure files; its authored
standalone base-window host excludes original Feed chrome/elevated colors,
transport and production URL routing. Full parity and source pins remain open.

## Native formatted full-text reader — 2026-10-23

The a5911a1f source rendering choice is now mounted: Feed Formatted mode uses
native block rows rather than the web viewer. Artifact/terminal Markdown still
uses its source-selected web route. `NativeMarkdownDocument` follows the five
original block/segment/parser files: headings, paragraphs, indented bullets and
ordered markers, quotes, rules, fenced code and raw monospaced table rows. Code
and table rows scroll horizontally. Font roles, spacing, quote bar, code caption/
card and indentation follow the original native leaf, mapped to Android fonts.
This is not matched UIKit/SwiftUI font/material/layout acceptance.

Local CLT execution compiles five whole unchanged source files and the original
`renderedInline` method body (without its SwiftUI view). All 32 document and 20
Foundation inline cases match, within the existing Android representable-URL
policy. Unicode numeric graphemes, Foundation whitespace, Swift CRLF Character
splitting, code-fence edges and table boundaries are preserved. Source comparison
caught image-label style differences: inner alt formatting is flattened while
outer styles/links remain, now also fixed in the shared inline renderer. Images
and HTML remain native text with no image/network execution. Relative/file/other
URL routing still needs comparison; raw original URL attributes remain in the
fixture rather than being erased to claim full equivalence.

The reader uses lazy native rows to avoid Compose's packed height limit for a
single large paragraph. Block parsing and inline/precomputed-text preparation run
off the UI thread, with owner/recycled-row cancellation and late-result guards.
The full-message path has no 8 KiB timeline cap or full-body preview cache. The
structural JVM cases preserve near-8-MiB paragraphs and unclosed code; that is not
8-MiB physical-device performance acceptance. Only block/character anchors, line
fractions, selection and horizontal coordinates are saveable. Native hierarchy
saving is disabled; loaded messages/spans never enter the Bundle. Existing modal
account/Mac/event fences, authorized load/read and cancellation remain in place.

Source/Formatted/Close controls now use natural widths with complete one-line
labels, fixing the enlarged Source word split. A strict generic Compose overflow
flag initially rejected an intact label: its glyph intrinsic width 109.5 fits the
measured 110 px slot. The final guard checks complete text, one line, height and
actual intrinsic glyph width with a one-physical-pixel quantization fence.

The final owned API 36 cohorts pass **six normal-size cases in 38.812 s and three
enlarged-size cases in 43.513 s** (nine executions/six distinct methods). The
formatted 14,000-line paragraph is 826,059 px tall at font scale 1 and 2,856,204 px at
actual system font scale 2. Anchor/selection survive StateRestorationTester saved-state
emulation and mode switches; read/load counts stay correct, hierarchy contains
no message, a real link tap is intercepted correctly and code scroll restores
900 px. The tail wraps onto two large-type lines and actual screenshot ink
matches 20,751/20,988 reference pixels; normal tail matches 6,788/6,835. Native
rows have no WebView. Four existing Source/load/retry/late-close cases pass in
the normal cohort, with the long Source case repeated at large type.

The enlarged test exposed a real legacy Source paint bug: position reported the
end while old text stayed painted after a long jump. Parent scrolling now
invalidates clipped TextView display lists in both readers; the large Source
case passes with its actual final-line pixels. The emulator initially retained
font scale 2 over cold boot after an asynchronous settings write; final runners set and
assert each actual Activity font profile, delay/read back/sync restoration and
subsequent boots observe the restored 1.0. Earlier failures and frames are retained.
These are owned-emulator settings only, not the phone's settings.

The final full JVM suite passes **3,144 with four opt-in skips, zero failures or
errors** (3,148 cases/428 reports). Ten focused Markdown cases pass. All 22 debug
viewer assets and exact packaged NOTICE verify; native engine inputs are
unchanged. Normal/enlarged code and tail frames are reviewed; crash buffers are
empty and owned emulators exit 0. Evidence:
`captures/recovery-20261023/native-markdown-reader-checkpoint.json`, final
`native-markdown-reader-runtime-v5/` and `native-markdown-reader-large-v2/`.
Reference/hash/case files: `tests/native-markdown-reference/`.

This closes the known web-versus-native rendering-choice gap only. Matched
original iOS UI, light/dark/RTL and broader type/reflow, real Activity/process
recreation, native selection gestures, TalkBack, older API 26/27 runtime (font
fallback implemented), URL routing, supporting Mac and physical Pixel acceptance
remain open. No new signed build, publication, account/pairing/Mac/phone action
occurs. 720 excludes this and the prior approval port; physical 712 stays released.
Global source pins and the full-parity goal remain unchanged/active.


## Source and scope

This port is scoped to official iOS source at
`186cec79781256867ad4516f0802118738bd2393`:

- `Packages/iOS/CmuxMobileRPC/Sources/CmuxMobileRPC/MobileAgentFeedListResponse.swift`
- `Packages/iOS/CmuxMobileShellModel/Sources/CmuxMobileShellModel/MobileAgentFeedItem.swift`
- `Packages/iOS/CmuxMobileShell/Sources/CmuxMobileShell/MobileShellComposite+AgentFeed.swift`
- `Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/AgentFeed{View,Row,Projection,RowModel,QuestionAnswerComposer,ReplyComposer,FullTextView}.swift`
- `MobilePrimaryTab.swift`: Workspaces, Feed, Notifications, Cloud and Search.

This does not advance the global upstream implementation/review pin. The previous
Android `NativeFeed*` implementation was the notification/workspace aggregator;
it did **not** implement the distinct agent workstream Feed.

## Reader/reply integration follow-up — 2026-10-10

The debug app built from main `5d254f84` passed **25 Android cases in 197.697 s**:
four full-text reader, 12 reply-sheet, eight Cloud machine and one paused-video
case. This executes the two lifecycle regressions and changed editor/reader
restoration, keyboard and reconnect cases from the source batch below. The reply
sheet and restored Source-mode screenshots were inspected. It is fixture
integration evidence, not matched iOS or physical Mac/Pixel acceptance.

Screenshot review exposed a weak final-line check: native geometry reached the
end of the 14,000-line message while the captured image still showed line 252.
Choosing only attached, shown, focused reader windows did not resolve it; that
two-case follow-up passed in 39.046 s but still had stale painted content.
The capture now waits for Compose to settle before waiting for platform frames,
and independently checks the final marker's painted ink pixels against the
native text paint at its actual screen coordinates (over 85% required).
The strengthened reader case and Cloud upgrade case **passed in 13.889 s**.
The inspected image now visibly shows line 14,000 and `TAIL_MARKER`; scrollY was
684,201 for a 686,133-pixel body and 1,932-pixel viewport. No production reader
change was needed. The check still verifies reloading without saving message
bytes and restored selection/viewport before scrolling to the end.

App APK SHA-256:
`a7cda17a990876b94d6bf87361d9ec93ccdc4cef079d140136ac2270b83e85eb`.
The final test APK SHA-256 was
`7dc3ab94756435a580d8d78ea97cefe4f8b411bc1e1ed95e35f9e3a1d8a6cdc1`.
Evidence is ignored under `captures/runtime/ui-integration/`,
`captures/runtime/ui-integration-capture/`, `captures/runtime/feed-reader-pixels/`
and the corresponding `ui-integration*` / `feed-reader-pixels*` logs.
The 25-case run had empty selected crash/ANR logs. Later cold boots recorded
Google/Digital Intelligence/Photos startup ANRs; no cmux app crash/ANR was in those
selected logs. A test-fixture theme import error was repaired before the final
successful test build; an install attempted during boot failed, then succeeded
after boot. The one existing AVD and Gradle were stopped and reaped, without
overlap. No Pixel, live Mac/Cloud request or signed upgrade was used. Signed 635
predates these production changes; full Feed parity remains open.

## Reader and reply-sheet source batch — 2026-10-10

Scoped source: `AgentFeedReplyComposer.swift` and `AgentFeedFullTextView.swift`
at `f4b1509054949eaad5d695569ad443c4c18ed68d`. Their SHA-256 values are
`fe3b1a61a97b2bed7d6e54035bc7bd03cbe266c9ccb4dc94f523240c6cc4b694`
and `c39525f7ab862052cf491ad1fb5f892677bd93e4939b191a7c6e97fb8edc6378`.
The global parity/review pin remains unchanged.

- Full text now opens in a large draggable sheet with the visible handle,
  centered **Full text** title, leading **Close** action, labeled loading state,
  reconnect/retry presentation and scrollable errors. Source/Formatted remains
  the Android reader's existing selectable-source option. The saved formatted
  viewport and source scroll/selection owners remain outside the sheet window.
- Reply and plan-revision sheets use the existing question sheet's dark palette
  and primary-button treatment. The draft now uses BasicTextField with the source's
  8 dp top inset and 3–12 lines, eliminating the Material field's internal padding.
  Agent icons are 22 dp within the 40 dp quote avatar; unknown agents use the
  source's terminal fallback. The user avatar is 36 dp, the replying line has a
  10 dp bottom inset, and long headlines ellipsize within their remaining space.
- IME Send admits the latest trimmed draft and rejects empty or retired-editor
  submission even if a native callback arrives before recomposition or after
  dismissal. Full-text and expanded-quote loads check cancellation after the read;
  a noncooperative transport completion cannot mark a closed reader's message read.

Main and Android-test Kotlin **compiled successfully in 37 seconds**. Two
lifecycle regressions were added: late noncooperative full-text
completion after Close, and delayed IME submission with a cleared/new draft and
after Cancel. Existing reader/reconnect cases were updated for Close/Try again.
At source commit `d421317b`, these new cases had not been executed. The integration
follow-up above now covers all four `AgentFeedFullTextTest` and 12
`AgentFeedReplySheetTest` cases, including native quote/keyboard/reconnect
restoration; older runtime results alone did not validate the changed editor.

Evidence: ignored `captures/feed-sheet-source-compile.log`,
`captures/AgentFeedFullTextView-f4.swift` and the retained reply-composer source in
`captures/runtime/feed-reply-keyboard/`. The source-only batch used no emulator,
APK build, signed release or Pixel run; the later debug integration is recorded
above. The Markdown body
still uses the existing bundled Android renderer; matched iOS text/rendering,
sheet screenshots, large-text/TalkBack/gestures and real Mac acceptance remain
open. Signed 635 predates this implementation; full Feed parity is unverified.

## Implemented behavior

- A primary Feed tab, with its own committed/draft/generation-bound search, the
  current computer selection, activity rows and All Activity / Needs Input filter.
- The main navigation hides Notifications by default, with the source's persistent
  **Legacy Notifications Tab** Display switch to bring it back. Turning that off
  while Notifications is selected moves to Feed and commits the outgoing search;
  separate destination queries and Feed drafts are retained. This applies to
  compact, wide and Cloud main navigation and now to the separate browser sidebar
  through its shared timeline and main-process display preferences.
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
  bypassPermissions/ultraplan/deny, with revision feedback using `manual`; multi-question and multi-select answer
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
swipe triage and Display settings. Remaining work includes broader full-text and
composer lifecycle, real TalkBack/RTL/large-text acceptance, and routed
browser/sidebar lifecycle acceptance. The dated runtime checks below cover specific fixture flows, not full
visual or physical acceptance.

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
absent/default-off in production; production uses the leading quote bar. The
reply-row follow-up below adds the source bubble-tail geometry; later inline-text
checks cover expansion placement and accessibility.

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


## Inline question controls and revision protocol — 2026-10-07

`AgentFeedQuestionControls` now follows the source's inline one-question-at-a-time
interaction. Multiple questions use a native horizontal pager with current-page
natural height, Previous/Next, direct page indicators, answered count and a final
Submit all answers button. Next remains available before answering; submission
requires every question. A single question gets inline options and Send.
Inactive pages hide accessibility descendants and disable their controls.

Full-width preset controls implement single choice or multiple choice, show
Markdown labels/descriptions and clear custom text on selection. Other… reveals
a focused multiline field; typing there clears preset choices. Wire answers use
trimmed custom text or labels in displayed option order, then question order.
Pending requests disable paging, choices, typing and submission. Drafts and page
position use Compose saved state under the row/request/question-content identity;
changed requests/content start fresh. The main Feed's account/team and computer
composition scope remains authoritative. Save-state behavior still needs broader
real process/account lifecycle acceptance beyond local fixture checks.

The source audit found that `AgentFeedReplyComposer.send()` sends plan revision
feedback as `mode: manual`, not `mode: revise`. Android now uses `manual` with
`feedback` and rejects the unsupported invented mode. A question decision also
checks that the displayed question content still matches the current RPC snapshot
before sending. This prevents old labels/custom answers from being submitted to
a changed prompt with an otherwise reused request identity.

The old all-questions dialog has been removed. Remaining Feed gaps include exact
inline See-more/bubble geometry, reply-composer quote/thread presentation, reader
and composer restoration, routed sidebar integration, large-text/TalkBack and
real Mac/Pixel acceptance. No scope-wide parity claim follows from this batch.

### Verification for inline questions

- **34 focused JVM checks** passed; debug and instrumentation APKs built.
- Three Android scenarios now have passing evidence on API37/16KiB: main-shell
  Feed/RPC navigation and inline question submission, draft/page saved-state
  restoration and gating, and nested horizontal paging with no row triage/open
  side effects plus variable page height. The first run was **56.785 s**, with
  two passing cases and one test-tag lookup failure; after a selector-only fix,
  that remaining case passed in **4.04 s** on the identical application APK.
- Screenshots of the main timeline and both question pages were visually
  inspected: controls/text/footer are visible without clipping. This provides
  initial runtime/visual evidence for the preceding row presentation batch too.
  Full font/accessibility/geometry acceptance remains open.
- Restored state used Compose `StateRestorationTester`; this is not an actual
  app-process kill or real account/Mac acceptance. Event logs contain no ANR or
  crash entries before or after these runs.
- Evidence: `captures/runtime/agent-feed-questions/`, retaining the first failure,
  both run receipts, build logs, XML results, screenshots and APK hashes.
  Application SHA-256: `6b46e1d15051a8bd602bffa50a7a3fa2b8420970a45a2007df0c5d6215e9df31`.
  Corrected test APK: `5435190c896a02827e4f7de3c3e3cc904e5a6cd4e61909c1194da65e4eec5789`.
- Sole existing AVD used at 2,048 MiB and stopped/reaped afterward; Gradle stopped.
  No Pixel was connected and no signed release was published.


## Reply sheet, plan actions and modal restoration — 2026-10-07

Plan rows now expose direct Approve, Revise, Deny and More approval modes as in
`AgentFeedExitPlanControls`. Approve forwards the agent's preselected mode (or
`manual` when absent); an unfamiliar advertised mode is not silently replaced.
Explicit menu modes remain manual, autoAccept, bypassPermissions and ultraplan.
The session admits an unfamiliar mode only when it equals the current event's
advertised default, while retaining normal request/pending/owner checks.

Terminal replies and plan feedback now use a bottom sheet with the quoted event,
agent avatar, author/headline, six-line preview, thread line, Replying to label,
focused multiline editor, keyboard Send and top Cancel/Reply/Send controls.
See more reads the complete `feed.text` result through the authenticated session;
it uses the bundled Markdown renderer rather than the 8 KiB timeline parser.
An expansion can be retried after read failure and falls back to selectable source
if the renderer fails. Sending still uses the existing single-flight session and
ambiguous-delivery handling. Plan feedback uses manual mode plus feedback.

`AgentFeedModal` saves only the account/team scope digest, exact Mac/build/event
and request/workspace/surface identity, mode, draft and presentation flags. It
never serializes pairing credentials or the loaded message. A recreated sheet
waits for the still-authorized Mac's initial Feed snapshot; authoritative event
removal, changed request/destination, successful terminal reply, resolved plan,
Mac/build removal or account/team change clears it. Restoration never sends a
reply automatically. Full-message reader mode and Markdown viewport coordinates
are saveable; message bodies reload and are kept out of the Activity Bundle.

This is not final visual or lifecycle acceptance. Expanded quotes currently use
a 320 dp independently scrolling document viewport inside the composer, while
iOS lays out its native Markdown in the outer scroll. Matching that continuous
scroll behavior, source icon/chrome details, exact inline expansion geometry,
TalkBack/large fonts, real process death during loading and actual account/Mac
recovery remain open. A Compose saved-state fixture does not prove a physical
process-death or authenticated production workflow.

### Verification for reply/modal work

- **40 focused JVM cases passed**, covering saved identity/draft encoding, scope/
  removal/replacement fences, initial-snapshot waiting, preselected approval mode
  forwarding and the existing Feed transport/projection/Markdown cases.
- **All five Android cases passed in 58.916 s** on API37/16KiB: reply draft saved-
  state restoration through snapshot reload with no auto-send, reader reload/raw
  mode and account cleanup, plan revision feedback, explicit approval-mode menu,
  and main-shell question/permission/terminal reply/navigation via loopback RPC.
  The first four-case attempt had a duplicate-text test lookup failure; it is
  retained with the corrected run, not counted as passing.
- A blank early expansion screenshot exposed insufficient DOM/visual-callback
  assertions. The strengthened test now checks the tail beyond 8 KiB and actual
  light-text pixels within native WebView bounds, saving that exact bitmap. It
  passed **one case in 12.465 s** on the unchanged app APK. The painted screenshot
  visibly contains the expanded report; the reply screenshot shows the typed
  draft and keyboard. No fixed rendering-latency claim follows from this result.
- Evidence: `captures/runtime/agent-feed-composer/`, including all runs, early
  frames, painted frame, geometry, 40 passing XML cases, build logs and APK hashes.
  No ANR/crash events occurred. AVD and Gradle stopped; no signed release or Pixel
  run. StateRestorationTester evidence does not replace actual process-death or
  authenticated Mac/Pixel acceptance.


## Inline expansion follow-up — 2026-10-07

Feed output/tool previews and collapsed composer quotes now measure the rendered
Markdown using the available width and current font settings. They reserve space
on the final visible line for an inline `… See more` action. Text shortened by the
host exposes the same action even when the received preview fits. Prefix slicing
preserves Markdown spans and URL annotations; Android ICU character boundaries
keep emoji sequences, flags and combining marks intact. The expansion uses a
separate native Compose link action, and callbacks stay current after recomposition.
Disabled previews have no expansion link action.

The preview has a 48 dp minimum height when expandable. When an extreme font/width
combination cannot fit even the control within the requested line count, the
control wraps instead of becoming inaccessible through clipping. The composer
thread line is drawn after normal measurement; it no longer requests unsupported
intrinsic measurements from the width-aware preview.

Verification for this change is recorded below. Continuous outer scrolling for
expanded composer messages, bubble tails/avatar details, full TalkBack and RTL
coverage, scrolling performance and physical Mac/Pixel acceptance remain open.

### Verification

- Four existing Markdown JVM cases passed; debug and instrumentation APKs built.
- The first Android batch passed three preview cases and exposed a real composer
  failure: its intrinsic-size thread layout was incompatible with the width-aware
  preview. Replacing that parent layout with a line drawn after measurement fixed
  the issue. The original failed run (four cases, one failure, 37.602 s) is retained.
- The corrected batch passed **four cases in 35.677 s** on the existing API 37 /
  16 KiB AVD (`cmux_api37_16k`, 2,048 MiB). It checked last-line placement,
  Markdown/link preservation, expansion without triggering row navigation,
  host-shortened previews, 2x font scale at 90 dp width, updated callbacks,
  disabled expansion, composed Unicode boundaries, and real composer/reader
  expansion with complete text, painted pixels, restoration and account clearing.
- Final preview and expanded-composer screenshots were visually inspected.
  The quote still uses the previously documented nested 320 dp viewport.
- Startup produced System UI / Google-service ANRs and a launcher crash before
  instrumentation. Event logs did not gain new ANR/crash entries during either
  test run. These environment failures are retained alongside test results.
- Evidence: `captures/runtime/agent-feed-inline/` (ignored), with build/test logs,
  screenshots, event logs and exact final APK hashes. Emulator stopped and reaped;
  Gradle stopped. No new signed APK or physical-host acceptance is claimed.

## Continuous composer quote — 2026-10-07

The composer now uses one Android `ScrollView` for the quoted message and the
reply draft. Expanded text is a native `TextView`, using the same inline-only
Markdown model as iOS; the quote no longer has a nested WebView/scroll area or a
320 dp height limit. Existing Compose controls are hosted inside this scroll
container with stable view IDs. The draft remains in the scoped modal state,
and the scroll offset is saved separately from the body. Full text is reloaded
through the authenticated session after restoration.

Full-message Markdown parsing runs off the UI thread, bypasses the row cache and
8 KiB preview cap, preserves styles across blank lines, and accumulates adjacent
runs with a string builder. Ordinary text after whitespace is consumed as a run,
avoiding a temporary AST node for every word in a long paragraph. Plain messages
have a direct path. URL spans retain the existing allowed-scheme policy.

The draft avatar now uses the platform person-circle icon, and the editor has no
underline, matching the source composer structure. The generic full-message
reader keeps its existing Markdown renderer and source toggle.

Text metrics are prepared on `Dispatchers.Default` with `PrecomputedTextCompat`.
Native wrapping uses simple line breaking without hyphenation. On API 35+, the
quote explicitly uses advance widths instead of glyph bounds, with drawing room
at both edges for overhangs; see the
[TextView API](https://developer.android.com/reference/android/widget/TextView#setUseBoundsForWidth(boolean)).
A captured Android 17 main-thread trace identified `GreedyLineBreaker` /
`StyleRun::getBounds` as the stall in a very large styled paragraph. The quoted
row also disables baseline alignment to avoid measuring its weighted text column
at an unbounded provisional width.

New composers focus the editor. A restored expanded composer resumes its saved
reading position; tapping the draft focuses the editor again. Focus-driven smooth
scrolling is disabled so an old animation cannot overwrite an explicit restored
position. User drag/fling scrolling remains native, and dragging dismisses the
keyboard.


The scroll offset is owned above the sheet window, so screen saved-state
restoration can recover it before the authenticated body reload completes. The
native viewport is clipped at both the Android and Compose boundaries; scrolled
text must not draw over the fixed Reply/Cancel toolbar.

### Verification and limits

- Six Markdown JVM checks passed, including a styled paragraph near the 8 MiB
  transport limit. This is parser evidence, not a maximum-size Android rendering
  or latency claim. Debug and test APKs built.
- The Android fixture renders a styled 1.1 MB paragraph as a native quote over
  262,143 pixels tall, reaches the draft in the same scroll area, retains bold
  spans and the complete tail, then reloads the body after saved-state restoration
  and recovers both the reading offset and draft without sending a reply.
- The initial runs caught invalid native tag use and a real native text-layout
  stall; the captured main-thread trace informed the wrapping changes above.
  The stalled debug process was manually stopped. Later runs exposed focus-driven
  scrolling and lost state inside the temporary sheet window; both were fixed.
  Fixture errors (an invalid Markdown delimiter and asynchronous smooth-scroll
  assertion) were corrected without dropping the full-body/draft requirements.
- The first emulator session also had launcher/System UI ANR dialogs covering
  screenshots. Those frames are not visual proof. The same existing AVD was
  restarted headless at 1,536 MiB; no second AVD was created. The screenshot checks
  now require app window focus. A subsequent visual review found text escaping
  above the scroll viewport; the final test also checks toolbar pixels.
- Real process death, maximum-size on-device rendering, enlarged-font/RTL/TalkBack
  traversal, touch/fling and IME behavior, and authenticated Pixel/Mac acceptance
  remain open. No signed release was produced.
- Raw failures, native stack, final screenshots, build logs, APK hashes and test
  receipts are retained in ignored `captures/runtime/agent-feed-continuous/`.

- Final corrected batch: **five Android composer cases passed in 45.540 s** on
  the existing API 37 / 16 KiB AVD, including plan/manual feedback, draft/snapshot
  restoration, approval modes, quote/reader/account clearing, and the long quote
  with toolbar clipping and saved-position restoration. The preceding targeted
  restoration batch also passed two cases in 26.939 s. Final quote and bottom/draft
  screenshots were visually inspected. The headless session's before/after event
  logs have no ANR/crash entries. Emulator and Gradle were stopped and reaped.


### Retained tab/sidebar state — 2026-10-07

Feed now has an account-owned saved-state holder outside its conditional primary
navigation branch. Switching to Notifications and back preserves its local
question choices, list state and modal draft state; the existing request/computer
keys still invalidate changed targets. The adaptive shell likewise retains
sidebar-local saveable state while hidden or behind a compact workspace. The
main Feed/socket-RPC test verifies a selected question survives tab switching
without submission and that terminal Back returns to Feed; shell checks cover
viewport/draft restoration and account clearing. See `WORKSPACE_SIDEBAR.md` for
all four passing cases, exact scope, evidence and outstanding navigation work.

## Legacy Notifications display preference — 2026-10-07

Rechecked `MobileDisplaySettings.swift`, `MobileSettingsView.swift` and
`WorkspaceShellView.swift` at scoped `186cec79781256867ad4516f0802118738bd2393`.
The source defaults `feedReplacesNotifications` to true and exposes its inverse
as the **Legacy Notifications Tab** switch. The shell hides that primary/sidebar
button and moves an already selected Notifications destination to Feed.

Android now persists the same default and switch behavior through the existing
Display preferences. Main compact, wide and Cloud navigation receive the setting.
Hiding a selected Notifications destination commits its active search, closes the
keyboard and selects Feed. Re-enabling the tab does not change the current
selection. Existing notification data, delivery and action paths are retained;
notification-specific fixtures explicitly enable the legacy destination. Feed's
empty-state copy no longer assumes that the old tab is always visible.

Verification used the existing API 37 / 16 KiB AVD at 1,536 MiB headless:

- **Four Android cases passed in 71.085 seconds.** The new settings case checks
  default-hidden behavior, compact/sidebar button visibility, persistence across
  remount, and invalid stored types falling back to the source default.
- The main Feed/socket-RPC case checks default-hidden Notifications, chooses an
  inline question answer, enables Notifications, starts an uncommitted search,
  and hides the tab while that search is active. Feed returns selected with its
  prior query and unanswered choice. Re-enabling Notifications exposes its saved
  query. The case then completes the existing exact-label question, permission,
  terminal reply, destination-open and Back checks.
- Existing Display wrapping/preview behavior and the legacy notification search,
  metadata matching, committed filters and destination navigation still pass.
- Build passed in 2m22s; an incremental build including the corrected empty-state
  copy passed in 21 seconds. No unrelated JVM/native suite or signed build.
- Phone layout was 1080×2400 / 420 dpi. A persisted wide override from the previous
  session was reset before these tests; final display output confirms no override.
  Boot, intermediate and final ANR/crash event logs are empty. Settings fixture and
  default Feed navigation screenshots were inspected. The settings screenshot is
  an isolated component harness, not full Settings window/inset acceptance.
- Evidence: ignored `captures/runtime/legacy-notifications/`, including exact source
  excerpts, build/runtime logs, display output, screenshots and APK/source hashes.
  Emulator and Gradle stopped; emulator process reaped. No physical Pixel connected.

The separate browser sidebar still has only workspace/notification projections
and a Boolean destination across its IPC query/presentation. It needs a full
Feed projection, actions, reader/reply state, search, adoption and preference
integration; hiding its Notifications button before adding Feed would remove a
usable destination without supplying its replacement. This remains implementation
work, not an Android platform limitation. Actual process death, accessibility,
large text and full physical/source parity remain open.

## Shared timeline for the browser sidebar — 2026-10-07

The main Feed UI previously depended directly on `NativeFeedSource` and concrete
Mac sessions, so it could not be mounted in the separate browser process without
replicating the UI or moving connection ownership. `AgentFeedTimeline` now consumes
an `AgentFeedUiSnapshot` and `AgentFeedTimelineActions`. The snapshot contains
renderable items, identities, labels, pending/error/read state and aggregate load
status; it contains no paired-Mac object, credential code, RPC client or session.
The browser adapter can supply opaque owner and entry identifiers.

`NativeAgentFeedView` remains the main-process adapter. It keeps the existing
aggregation, read-state storage and live sessions, projects their current display
state, and resolves each action against the current item. The common timeline owns
all existing list/filter/search behavior, inline decisions, quote/reply sheets,
full-text reader and saved modal state. `NativeAgentFeedRow` and
`AgentFeedReplySheet` consume the same display entries. Native modal callers retain
compatible saved target/draft fields; the display path also checks the owner when
matching a restored target.

A final review tightened read completion: the adapter now ignores a completion if
the event's owner/build, request, kind, workstream, destination or questions no
longer match the displayed event. This prevents a late reader completion from
marking an unrelated replacement event read under a reused ID. Decisions and
replies use the same identity predicate, followed by the existing session's live
ownership and pending-request checks.

Verification:

- Initial build and **23 JVM checks passed in 1m25s**, including four new display
  projection/modal cases, five modal regressions and fourteen wire/session cases.
- On the sole API 37 / 16 KiB AVD at 1,536 MiB, **eight Android cases passed in
  113.025 seconds**: all five reply/reader tests, both question-control tests and
  the real main-screen/local socket-RPC Feed flow. This covers saved drafts during
  snapshot reload, account replacement, expanded quote/reader reload, the styled
  1.1 MB quote's continuous scroll/restoration and toolbar clipping, plan revision
  and approval mode, question paging/restoration, and exact question/permission/
  terminal reply RPCs and destination/Back navigation. Screenshots inspected.
- After the read-completion review, the final identity predicate and its regression
  compiled and **24 JVM cases passed in 21 seconds**. The Android run predates
  this final predicate extraction/read guard; it was not repeated. The recorded
  APKs therefore represent the immediately preceding UI checkpoint, while the
  final source hashes include the guard. No signed release was built.
- Existing 1080×2400 / 420 dpi display, empty boot/intermediate/final ANR/crash event
  logs, no extra AVD. Emulator and Gradle stopped; emulator process reaped. No Pixel.
- Evidence: ignored `captures/runtime/feed-shared-timeline/`, with logs, XML results,
  composer screenshots/geometry and APK/source receipt.

**At this checkpoint browser integration was still pending.** The following
section supersedes that implementation gap. The next work was to extend its query/presentation and
paged display projection with Feed, bridge decisions/triage/replies/reader loads
through the main process with live account and issued-row checks, and preserve
separate search, filters, drafts and destination adoption. Large full messages
need bounded transfers across the process boundary, rather than a single Binder
bundle. Then apply the legacy-tab preference in that sidebar and verify the real
browser process while retaining its active WebView. This refactor does not prove
browser Feed, authenticated transport or physical parity.


## Feed in the separate browser process — 2026-10-07

The browser sidebar now mounts the shared timeline, question and approval controls,
reply composer, full-message reader and row triage/navigation. Feed has separate
search/filter state and saved UI state across primary-tab and sidebar switches.
The browser reads the main app's Legacy Notifications Tab and Feed row preferences;
turning off a selected legacy tab transfers selection to Feed. Returning to the
main app adopts Feed selection, search and Needs Input filter.

The main app remains the owner of authenticated sessions and read-state storage.
The display projection uses salted opaque entry and owner keys. Pairing codes and
session objects do not enter the browser process. Every command resolves an issued
key against the current account, computer scope, request, questions and destination;
prepared navigation is one-use and resolves the live destination again on return.
Hiding/leaving the presentation cancels pending operations. A cancelled admitted
write retains its unconfirmed-delivery warning and draft in the owning session;
there is no automatic resend.

Snapshots, reply commands and full messages cross Binder through bounded file
descriptors backed by unlinked private cache files. The receiver rejects pipes,
invalid UTF-8 and oversized payloads. Both endpoints close descriptors on normal
completion, cancellation and orphaned replies. Full-message RPC paging retains the
existing 8 MiB bound and version/offset checks. The display codec preserves already
normalized model values, including locally recorded replies longer than RPC preview
limits. Initial loading does not mount an empty authoritative timeline that could
invalidate a restored composer.

Verification is recorded in the ignored `captures/runtime/feed-browser-sidebar/`.
The combined build passed **62 focused JVM cases** and produced debug/test APKs.
All four targeted Android cases passed across the final checks:

- Two payload cases: a 2 MB UTF-8 round trip after unlinking, descriptor closure on
  success/pre-dispatch cancellation, and pipe rejection.
- The existing main-screen real socket-RPC Feed flow passed on the integrated APK.
- The actual separate-browser-process case passed in **237.047 seconds**: independent
  Feed search, question choice retention across tab switches, exact question and
  permission methods/parameters, expanded quote text loaded through the main app,
  terminal reply acknowledgement/display, sidebar hide/show with no browser reload,
  retained WebView draft, and native terminal destination/Feed-state adoption.
  Two earlier fixture attempts exposed selector errors (the browser address field
  instead of sidebar search, then the whole quote instead of its inline link).
  Corrected selectors passed; no product changes were needed for those failures.

The browser screenshot was inspected. Boot/intermediate/final ANR/crash event logs
are empty. The sole headless API 37 / 16 KiB AVD used 1,536 MiB; its process was
stopped/reaped and Gradle stopped. Large-payload testing is a descriptor round trip;
the browser fixture's message is small. Large actual cross-process messages,
process-death drafts, legacy-preference changes while browsing, offline/reconnect,
all plan modes, filter/triage gestures and accessibility still need broader browser
acceptance. The runtime reader check exercised expanded composer text, not every
standalone reader lifecycle.
Physical Mac/Pixel, production push, broad lifecycle/accessibility and signed-release
gates remain open; this integration does not establish full parity.


## Reply row and expansion accessibility — 2026-10-07

A scoped comparison with `AgentFeedRow.swift` and `AgentFeedBubbleShape.swift` at
`186cec79781256867ad4516f0802118738bd2393` found visible gaps after browser Feed
integration. Android now uses the source's continuous 4 dp tail / 18 dp corner
bubble outline, 40 dp opposite-side inset and asymmetric content padding. Logical
alignment and geometry mirror in RTL. Replacing the border outline cache when
layout direction changes fixes a stale tail in an already-mounted row. Text uses
content direction so English punctuation remains correctly ordered in an RTL
shell. Quoted user prompts use outlined bubbles;
recorded replies use filled trailing bubbles with a single “You: …” accessibility
label and no duplicate reference quote. The production quote-bar style retains a
one-line “Replying to …” reference and compact You/reply marker. Reply, Sending and
Replied states include the outline arrow, progress indicator or checkmark.

An unconfirmed terminal reply has explicit Try Again and Open Terminal controls.
Try Again uses the existing saved-failure draft composer and does not resend.
Open Terminal resolves the live Feed target through the same action interface.
Offline or pending controls stay gated. Permission/plan/question delivery errors
continue to use their existing failure state.

Inline “See more” retains attributed text and pointer links, and now exposes an
independent accessibility button over its measured substring. Its bounds use the
actual text layout with physical positioning, including RTL, and disappear when
expansion is disabled. This replaces the browser test's coordinate-derived tap
with an accessibility-description lookup. It does not claim full TalkBack or
Switch Access acceptance without a physical assistive-technology run.

Verification:

- Debug and test APK builds passed. Final integrated runtime: **9 tests passed in
  98.483 seconds** on the sole API 37 / 16 KiB AVD, headless at 1,536 MiB.
- Five inline-text cases cover grapheme/style/link retention, last-line expansion,
  enlarged text/current callbacks, independent RTL accessibility/touch activation
  without row navigation, disabled-action removal, and English punctuation in RTL.
- Two reply-row cases cover reopening an uncertain draft without sending, exact
  terminal destination, bubble/bar presentation, accessible sent label and direction
  switching. Final LTR/RTL screenshots were visually inspected.
- Main real socket-RPC Feed flow and actual separate-browser Feed flow passed. The
  browser activates See more by accessibility description, sees the new reply label,
  keeps its WebView draft without reloading, and returns to the exact terminal.
  The final Android hierarchy exposes See more as a clickable, enabled node.
- The first run had two failures: the overlay's guard compared annotated strings,
  but Text adds resolved link styles. Comparing the displayed string fixes the guard.
  Screenshot review separately found the stale RTL border cache and punctuation;
  both were corrected. The next run passed seven cases but hit Android 17's stale
  initial browser-title accessibility cache (paint was correct). The browser fixture
  now refreshes its known provider node, as existing notification tests already do.
  All initial logs/screenshots remain alongside final evidence.
- Evidence: ignored `captures/runtime/feed-reply-rows/`. Empty boot/intermediate/final
  ANR/crash event logs. Gradle stopped and the sole emulator process stopped/reaped.
  No Pixel/Mac run, signed release or broader regression suite in this batch.

Full Feed parity remains unproven. Physical assistive-technology, all locale/font
combinations, long-message performance and broader account/lifecycle acceptance
remain part of the project gates.

## Full-message Source reading — 2026-10-07

Rechecked `AgentFeedReplyComposer.swift` and `AgentFeedFullTextView.swift` at the
scoped source revision `186cec79781256867ad4516f0802118738bd2393`. The source reader
loads complete event text, exposes explicit retry, and cancels work when dismissed.
Android keeps its existing formatted Markdown view and optional Source view.

The Source branch previously mounted one Compose Text and a branch-local scroll
state. Switching through Formatted discarded that position; sufficiently tall
messages also exceeded Compose's packed text measurement constraints. It now uses
a selectable native TextView inside a clipped ScrollView. A saved character anchor,
fractional line offset and selection belong to the reader, outside the mode branch
and nested dialog. The message is reloaded; neither the Compose saver nor native
view hierarchy saves its contents into the Activity Bundle. The source view uses
simple paragraph wrapping and content-based text direction, as the expanded quote
already does. This change also reaches the shared reader in the browser sidebar.

The first focused Android run passed **five cases in 90.931 seconds**: three reader
cases plus the composer/account-change/reader flow and actual separate-browser Feed
flow. Reader cases cover mode-switch position/selection without another load,
14,000-line complete text above the Compose height limit, saved-state reload and
selection restoration, native hierarchy exclusion, retry/read marking and dismissal
cancellation. Screenshot review caught a capture taken before the final native
scroll painted; the fixture now waits for drawing before taking visual evidence.
The original screenshot is retained and is not evidence of the final line painting.

The corrected single-case run passed in 17.903 seconds, but its screenshot was
covered by a System UI ANR dialog. Second-boot event logs show System UI/Google
service startup ANRs at 15:27:43–15:28:21, before that test started at 15:28:56.
After choosing Wait and confirming the launcher recovered, the same APK/test
passed again in **9.928 seconds**. Its final screenshot visibly shows line 14,000
and `TAIL_MARKER`, with no dialog. No new ANR/crash events appeared during that
repeat; the initial five-case run had empty ANR/crash logs. All original and final
evidence is retained in ignored `captures/runtime/feed-reader/`. Both debug/test
builds passed; Gradle and the sole 1,536 MiB emulator were stopped and reaped.

These are emulator fixtures and Compose saved-state restoration, not a physical
Pixel/Mac or OS process-death run. Broader rendered/source reflow, accessibility and
full Feed acceptance remain open.

## Retaining the open modal through snapshot reload — 2026-10-07

The main and browser timelines now retain the open reader/composer's saveable UI
inside a modal-owned state holder while waiting for its authorized Mac snapshot.
Previously, the READY-to-WAITING transition disposed the scroll state even though
the modal target and draft survived. The holder lives across this transition and
can itself be restored while waiting. Full messages are loaded again, and their
prior load coroutines are cancelled when the content leaves composition.

Closing the sheet or invalidating its account/target discards the holder. Opening
the same item later starts a fresh reading/composing session. Existing ownership
and target checks still decide whether a reloaded snapshot can reopen the modal;
waiting does not send anything. The waiting message now describes a message for
readers and a draft for composers.

Verification: debug/test APKs built successfully. The initial nine-case run took
128.976 seconds: all six existing composer/reader and real socket-RPC Feed cases
passed, while three new cases failed on test assumptions. The two composer cases
matched both the timeline and sheet's See more controls; their selectors now scope
to the composer preview. The reader correctly discarded its selected range on
close, but Android initialized a collapsed cursor at zero rather than minus one;
the assertion now checks for no range at the native initial position. No app-code
change was needed for those three failures.

The corrected **three cases passed in 39.548 seconds**, giving passing evidence
for all nine distinct scenarios across the runs. They save/restore while WAITING,
reload full text, restore the expanded quote offset/draft and Source offset/selection,
verify fresh reopening and account invalidation, and assert no unintended terminal
paste. The restored composer screenshot was inspected. The waiting-screen screenshot
was captured during the transition and is retained but does not establish final
sheet paint; that copy is covered by the displayed-node assertion. Evidence:
ignored `captures/runtime/feed-reconnect/`. Boot and final ANR/crash logs are empty.
Gradle and the sole headless 1,536 MiB emulator were stopped/reaped.

These checks simulate snapshot reload in the shared timeline; they do not prove
actual Pixel/Mac disconnection, OS process death, or this transition through the
browser IPC. Those broader acceptance gates remain open. No signed APK was published.

## Routed browser Feed recreation — 2026-10-07

`RoutedBrowserPresentationTest#globalSidebarFeedModalsSurviveBrowserActivityRecreation`
passed **one Android case in 59.992 seconds** on the existing API 37 / 16 KiB AVD
at 1,536 MiB. No production change was needed. Font-scale changes caused two actual
`RoutedBrowserActivity` relaunches, confirmed by system events, while its browser
process remained alive. The bound service/PFD route reloaded the 160-line message.

The expanded reply retained its unsent Unicode draft; the reader retained Source
mode. Before/after screenshots show the reader at the same partially clipped
Line 031 despite the font-size change. This anchor observation is visual evidence,
not an automated pixel assertion. The composer helper scrolls to its draft, so the
case does not establish exact composer viewport restoration. The browser DOM draft
survived without another page load, no terminal paste was sent, and the browser
presentation hold was released on exit.

Debug/test builds succeeded (40 seconds, then an 8-second test-only rebuild after
correcting a selector before the runtime run). The single runtime run passed.
A Launcher crash and GMS startup ANR preceded testing; the baseline and final
crash/ANR event logs were identical. Screenshots were inspected. Original font
scale and display size were restored, and the emulator and Gradle were stopped.
Local logs, screenshots, APK hashes and receipt are under
`captures/runtime/browser-feed-restoration/` (ignored).

This covers Activity recreation within a live browser process. OS process death,
large IPC messages, live reconnect/revocation, physical Pixel/Mac workflows and
broader accessibility acceptance remain open. No signed release was produced.

## All-question answer sheet — 2026-10-09

Scoped source: `manaflow-ai/cmux` commit
`f4b1509054949eaad5d695569ad443c4c18ed68d`, specifically
`AgentFeedQuestionControls`, `AgentFeedQuestionComposer`, `AgentFeedQuestionCard`,
`AgentFeedQuestionOptionRow`, `AgentFeedQuestionCustomAnswerRow`,
`AgentFeedQuestionAnswerBuilder`, and `MobileDisplaySettings` under
`Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/`.
This does not advance the overall parity pin or claim the remaining upstream
Feed analytics, scroll-performance, task-picker, or privacy changes are ported.

The shared native/browser timeline now shows a compact first-prompt preview and
an Answer action. Its sheet presents all questions vertically, progress, answered
indicators, single/multiple selections with leading controls, and an always-visible
custom answer. Submit sends ordered labels or trimmed custom text only after
all prompts are answered and the source is connected with no pending action.
Focus or selecting the custom control activates that mode and clears presets;
selecting presets retains the inactive custom text. Single choices stay selected
when tapped again. Keyboard/field height changes bring the focused editor into view.

The saved modal includes answer state and a SHA-256 digest of the ordered prompt,
header, option and selection-mode content. It does not save the remote prompt
bodies. Authorized snapshot loading retains the draft; changed request/content,
account/owner removal, resolved events and cancellation invalidate it. Both Feed
presentations use the same sheet and existing transport ownership checks.
Quote bubbles now default to enabled in every build and honor the stored setting;
the existing debug-only A/B switch remains, as in the iOS source.

Verification: **17 focused JVM tests passed**, covering answer modes/order,
codec migration and request/content/owner identity. Debug/test APKs built.
The final feature run passed **four Android cases in 101.475 seconds**, covering
all-question completion, retained inactive custom text, blank focused answers,
o-option questions, offline/pending gating, WAITING saved-state restoration,
changed-prompt/cancel invalidation, release-setting persistence behavior, and
real socket-RPC submission of labels. The display-setting code
is unconditional across build variants; no new release APK was built.

The initial Android attempt failed one assertion because it counted the visible
placeholder as an answer. Screenshots also exposed a System UI ANR overlay even
though semantic interactions could succeed. The test now requires actual window
focus, stable IME geometry and full editor bounds above the keyboard. Earlier
boots recorded System UI/Google service ANRs and a Launcher crash; those logs are
retained. The earlier 88.520-second feature run had empty crash/ANR logs. The
final run followed an emulator System UI restart; its before/after crash/ANR logs
are identical, with no new events during the run.

Keyboard screenshot inspection exposed missing scroll-follow when the modal
consumed IME insets. The sheet now reacts to the **measured scroll viewport** and
editor height, matching iOS's focused-answer geometry handling. Capture polling
also waits for stable editor geometry and flushes Compose after native IME
settlement. A settled follow-up exposed an intermittent focus/draft transition;
updates now reduce synchronously against the latest local answer state and skip
no-op persistence, so delayed IME callbacks cannot restore older modes. Submit
recomputes the current answers when tapped. The test explicitly verifies focus
when switching to an empty answer. This is why earlier keyboard
screenshots, although retained, are not accepted as final visual proof.

A repeat of the previously intermittent case passed in **17.777 seconds** on the
same final app/test APKs. The final preview, sheet and stable-keyboard screenshots
were inspected; the editor is fully visible above the IME, with geometry recorded
alongside the image. Installed APK hashes match the local build outputs. No new
crash/ANR events appeared during either final run. Emulator and Gradle were stopped.

Evidence is under ignored `captures/runtime/feed-question-sheet/`, including
original attempts, JVM XML, runtime logs, installed APK hashes and screenshots.
These checks use the existing API 37 / 16 KiB AVD, requested at 1,536 MiB/2 cores;
the image reports about 4 GiB guest memory despite that request. No additional
AVD was created. Physical Pixel/Mac, question-specific browser IPC/recreation,
OS process death and a signed release remain unverified by this batch. The iOS
interactive drag-to-dismiss keyboard gesture was not covered by that batch;
the follow-up below adds and verifies it on the existing Android emulator.
Signed build 616 is unchanged.

## Background snapshot decoding (2026-10-09)

Scoped source: `MobileShellComposite+AgentFeed.swift`'s
`decodeAgentFeedSnapshot` at `f4b1509054949eaad5d695569ad443c4c18ed68d`.
Android's retained Feed session previously ran timestamp parsing, byte-bounded
field conversion, row validation and sorting on its owning UI dispatcher after
the RPC. It now decodes on `Dispatchers.Default`, then returns to the owner for
revision admission, local reply/decision overlays and publication. Cancellation
is checked before, between rows and after decoding; row-tolerant malformed-data
handling does not swallow cancellation. Current owner and revision checks remain
after the worker returns. The existing row/field limits are unchanged.

**82 focused JVM checks passed**: 18 wire/session (four new worker cases),
54 coordinator, five projection and five timeline checks. The worker tests block
the synchronous decoder while the owner handles an event or decision, verify the
new revision/local acknowledgement wins, and reject a completed decoder result
after session cancellation. A separate case verifies cancellation between rows
aborts the entire snapshot. These are concurrency-correctness checks, not a
measured Pixel scrolling-performance claim.

Evidence: ignored `captures/agent-feed-decode-jvm.log` and
`captures/runtime/agent-feed-decode/` (JUnit XML). No emulator, APK build, physical
workflow or signed release was performed for this batch; the active signed
milestone run 633 uses earlier `7a5ff354` sources. The iOS stop-reason cache,
scroll instrumentation/analytics, remaining privacy changes and physical Feed
performance still require comparison and acceptance; the global pin is unchanged.

## Interactive question-sheet keyboard (2026-10-09)

The question sheet now follows `AgentFeedQuestionComposer.swift`'s
`scrollDismissesKeyboard(.interactively)` at the same scoped `f4b1509` source.
Its scrolling content participates in Android's native IME animation controller
through [Compose keyboard dragging](https://developer.android.com/develop/ui/compose/system/keyboard-animations).
Typed answers, selected options, the submit action and the sheet remain intact
while the keyboard is dragged down and reopened.

The existing three question-sheet regressions passed. A new physical-touch
instrumentation case initially timed out while checking root insets for a partial
animation; its corrected measurement uses the rendered scroll viewport plus
screenshots. It **passed in 27.195 seconds** on the same application APK,
including a held partial drag, complete hide, retained answers, an undismissed
sheet and reopening the editor. The initial viewport was 1,208 px high; during the
held gesture it was 1,519 px high, with an initial 883 px IME. Partial, hidden and
reopened screenshots were inspected. The first run's boot service ANRs preceded
the test; the final run's before/after crash/ANR logs are identical.

Evidence: ignored `captures/runtime/agent-feed-keyboard/` and
`captures/agent-feed-keyboard-*.log`. The existing emulator and Gradle were
stopped. No additional AVD was created. This is API 37 emulator evidence;
physical Pixel/Mac acceptance, broader gesture/accessibility behavior and a
successful signed milestone remain open.

## Completion-reason normalization and retained cache (2026-10-09)

Scoped source: `AgentFeedStopReasonCache.swift` and the deduplication call sites in
`MobileShellComposite+AgentFeed.swift` at
`f4b1509054949eaad5d695569ad443c4c18ed68d`. The cache source SHA-256 is
`bf7539a8575645a734b1b151617ad9f78f37d5b058c433abfbe018ebd01976c1`.
The global parity pin remains unchanged.

Android previously compiled an ASCII whitespace regex on every same-turn
comparison. Nonbreaking/em spaces therefore prevented a truncated preview from
matching its full completion. The normalizer now collapses Unicode White_Space
without a regex, retaining authored nonspace text. Matching still requires equal
normalized reasons or an explicitly ellipsized prefix; distinct complete
responses remain separate. Existing exact Mac/build, time-window, fuller-text and
reply-preservation rules remain intact.

Main Feed/list badge and browser Feed/sidebar presentations now own reusable
reason caches. Each projection prunes to stop reasons present in its input
snapshot; removed reasons are not carried forward indefinitely. Caches contain
normalization results only, do not persist, and do not supply rows or RPC targets.
Live browser actions continue resolving the current authoritative snapshot.

**54 focused JVM checks passed:** three cache/normalization, seven native
projection, ten browser Feed and 34 sidebar cases. These cover all six source
matching fixtures, Unicode whitespace and authored nonspace content, 100 unchanged
projections with only two normalization calls, changed/removed reason pruning,
reply and build separation, plus browser badge/row/live-read agreement and snapshot
removal. The first focused invocation covered the ten cache/projection cases;
the final invocation includes the actual `RoutedAgentFeedTest`/`RoutedSidebarTest`
classes. Main Kotlin compiled in the same batch. This is deterministic reuse and
correctness evidence, not a measured scrolling or latency claim.

Evidence: ignored `captures/agent-feed-stop-reason*-jvm.log`,
`captures/runtime/agent-feed-stop-reason/` (JUnit XML), and
`captures/AgentFeedStopReasonCache.swift`. Gradle is stopped; no APK build, emulator
or Pixel run was performed for this batch. Signed 635 predates this change.
Broader Unicode/grapheme equivalence, physical Feed performance, remaining
privacy/analytics source changes and full Feed acceptance remain open.

## Reply-sheet keyboard and callback lifecycle (2026-10-09)

Scoped source: `AgentFeedReplyComposer.swift` at
`f4b1509054949eaad5d695569ad443c4c18ed68d`, SHA-256
`fe3b1a61a97b2bed7d6e54035bc7bd03cbe266c9ccb4dc94f523240c6cc4b694`.
The iOS sheet fills its available height and uses
`scrollDismissesKeyboard(.interactively)`. The global parity pin is unchanged.

Android's quoted-message composer now fills the sheet and uses AndroidX
`NestedScrollView` inside `AndroidView` with `imeNestedScroll()`, replacing the
abrupt keyboard-hide call on each intercepted move. The existing large native
TextView remains the full-message renderer. Native child scroll deltas reach
Compose's IME animation controller through the documented
[AndroidView nested-scroll bridge](https://developer.android.com/develop/ui/compose/touch-input/scroll/nested-scroll-modifiers).
Resolved AndroidX Core is 1.19.0; no new dependency was required.

Removed native scroll views stop publishing saved offsets. Reply edits reduce
against the latest local modal, skip unchanged drafts and ignore callbacks after
the editor is disposed; parent admission checks the current account scope as well
as row/mode. This addresses stale callback paths that could overwrite a retained
expanded flag or reading position. Drafts are not automatically submitted.

The initial full run passed eight of nine cases, but reconnect restoration timed
out. The diagnostic isolated case and a keyboard/reconnect sequence then passed;
adding the removed-scroll guard alone still left the full run at nine of ten.
Its screenshot and native geometry showed an absent quote (`body=0`, `length=0`,
`shown=false`, `scroll=0`), rather than merely a shifted offset. These results
motivated the editor reducer and disposal guard; the intermittent failure's
precise originating callback was not traced.

The final debug/test APK build succeeded. **All 11 Android cases passed in
105.422 seconds**, including a held partial keyboard drag, complete hide/reopen
with a Unicode draft, exact reconnect position, delayed editor callback ordering,
removed-view callback suppression, plan feedback, account change, explicit-send
admission and a continuous quote taller than 262,143 px. Held-drag viewport grew
from 1,208 to 1,509 px with an initial 883 px IME. Reconnect restored exactly
1,200 px with the visible complete 10,024-character/8,729 px body. Partial,
reopened and reconnect screenshots were inspected. Before/after event logs have
no crash/ANR entries.

Evidence: ignored `captures/feed-reply-keyboard-*.log` and
`captures/runtime/feed-reply-keyboard/`, including initial failures, isolated
checks, source/dependency evidence, final APK hashes and `complete.log`/`complete/`.
Gradle and the existing API 37 emulator were stopped; no additional AVD was
created. Physical Pixel/Mac gestures, accessibility and real transport acceptance
remain open. Signed 635 predates this batch.
