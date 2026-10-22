## Background reply diagnostic candidate — 2026-10-22

The exact pinned Mac inbox client now classifies fetch/ack failures with fixed
categories and numeric codes, without credentials or content. Eighteen isolated
client cases and existing hook/auth/payload/receipt checks pass; installer,
packager and compiler-cache tests pass (5/5/8). The hosted full Mac build requires
the new check. See MAC_NOTIFICATION_BUILD.md and the ignored
`reply-inbox-diagnostics-v2/receipt.json` for evidence and scope. Live Mac runtime
and background terminal reply remain unverified; the running app and phone are
unchanged. Do not infer the cause from historical logs or unauthenticated 403s.
Continue the full build, verified artifact/runtime observation and every broader
parity gate. Full app parity remains active.

## Latest translation measurements and phone availability — 2026-10-22

The measured popup sizing gap is closed: final emulator v4 passes 19 regressions
and 17 fresh captures, with all eight body sizes within one physical pixel of
native references. Large choice is 366x313 1/3 with three lines. Source numeric
font advances, caption attachment spacing and baselines now guide the Android
layout; fonts/artwork, some word wrapping, materials and remaining adaptation/
inference/device gates are still open. Native run 39747401479 provides 17 scenes
and 12 intrinsic caption profiles; 35 canonical files match independently and
138 Python guards pass. See CHIEF.md and
`captures/recovery-20261022/chief-translation-fonts-checkpoint.json` for scope.

The Pixel is free for the user to use/disconnect; signed 712 and all data are
preserved. Current translation code is not installed on it. Background reply E
remains unaccepted. Historical Mac `sweepFetchFailed` entries lack individual
timestamps; no fresh event appeared in the bounded follow-up observation, and
current Mac auth reports signed in/team ready. Neither historical events nor
unauthenticated HTTP 403 proves the cause. Continue coarse authenticated failure
diagnostics and all remaining feature/UI/backend/release gates. Full app parity
remains active.

## Pixel 712 upgrade and live Mac checks — 2026-10-22

The user made the unlocked Pixel 6a available. Stable-signed candidate 712 from
355c88bd2c45ae36ee9c386adc54529590c451a9 upgraded release 703 with `adb install -r`;
its verified SHA256 is 06eecb1e90bebfc3185a4b4b6342b51a8d582e2ae9be0718a53674f35d8503e6.
Original first-install time, account/pairing and live workspace discovery were
preserved. The new owned acceptance terminal opened without a reattach workaround,
displayed the Mac's marker on the Pixel, and executed a validated phone composer
command on the Mac. An initial immediate post-focus text injection produced `cho`
rather than `echo`; that failed attempt is retained. The retry waited for focus
and checked the entire field before Send. This is actual UI input injection,
not physical Gboard acceptance.

A fresh private-helper alert was provider-accepted and displayed after the release
process was stopped. The background process was then killed, its absence and the
notification's continued presence were checked, and the real notification was
tapped. It restored the correct terminal and saved Mac without the earlier
"saved Mac is no longer available" failure. This accepts one cold route, not the
whole lifecycle/network/Doze matrix. A warm inline reply, with the app on Workspaces
and its native connection open, executed exactly once in the owned Mac terminal.
Its post-reply Pixel terminal output was not captured. A separate background inline
reply did not produce terminal output before cleanup and remains unaccepted.
Receiver dispatch/WorkManager SUCCESS do not establish terminal delivery. The Mac
log contains repeated coarse `phoneReply.sweepFetchFailed` events; its cause is
not established. Unauthenticated presence probes returning 403 do not diagnose
an authenticated failure. Do not label the later E/F alerts as FCM-only evidence.

All three original Mac workspace IDs and the original selected workspace were
verified after closing only the owned fixture. Build 712 remains installed;
`stay_on_while_plugged_in=0` is restored and no real credentials/settings were
cleared. The phone was returned to its original workspace list and the user was
told it can be used/disconnected. Evidence, hashes, failed assertions and scope:
`captures/recovery-20261022/pixel-712-live/checkpoint.json`. That APK predates the
current unshipped translation popup/caption work. Background reply completion,
Doze/token rotation/revocation, TalkBack and every remaining source/feature/UI/
backend/release gate remain open. Full app parity stays active.

## Anchored translation popup checkpoint — 2026-10-22

Chief's provisional translation sheets are replaced by anchored, focusable
popups with outside/Back dismissal, current-owner action admission and owner-keyed
in-memory Activity backdrop capture. The separate popup window now preserves the
parent's text scale/direction. See CHIEF.md for source measurements and scope.

Expanded native run 39731923761 supplies 17 verified UIKit compositor references
on actual iOS 26.4.1 (23E254a). Local canonical files match the uploaded artifact.
Android v9 passes 19 regressions (69.074 s) and 17 fresh matched scene captures,
with actual portrait/landscape validation and no crash-buffer events. Two measured
geometry JVM tests and 135 Python guards pass. APK/source/image hashes and observed
deltas are in `captures/recovery-20261022/chief-translation-popups-checkpoint.json`.

Normal popup body sizes and the large source-picker body size match. The large
choice still measures 373x347 1/3 against native 366x313 1/3 (four versus three
header lines). Typography, material/motion, caption glyph metrics, other locales/
type categories/Bold Text, live inference/download success, open-popup rotation/
recreation, Pixel/TalkBack and every broader account/transport/terminal/notification/
backend/release gate remain open. Component light captures do not establish global
light-theme support. Full app parity remains active; physical 703, signed 712 and
real account/pairing/signing/workspaces are unchanged. The owned AVD is stopped.
No further manual Mac pairing-window action or repeated phone request is needed.

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

## Signed candidate 712 ready — 2026-10-22

Candidate 712 supersedes local 711 and includes the font-following and RTL editor
fixes below. Built from clean source `355c88bd2c45ae36ee9c386adc54529590c451a9`.
APK: `captures/runtime/parity-candidate-712/app-release.apk` (277683065 bytes);
SHA256 `06eecb1e90bebfc3185a4b4b6342b51a8d582e2ae9be0718a53674f35d8503e6`.
Stable signer, all 22 native libraries/alignment, 21 viewer assets, 14 excluded
debug components, package/SDK/version and selected private Firebase resources
verify. Full unit suite: 3062 cases, 3058 passes/four opt-in skips, zero failures/
errors. All 414 XML reports are archived and hashed in
`captures/recovery-20261021/parity-candidate-712-checkpoint.json`.
The rendered matrix's exact source is committed at this same source revision.

The APK is not installed or published; Pixel remains 703 and USB is absent at
this checkpoint. The existing availability question is pending; use 712 for the
next data-preserving upgrade and actual terminal, encrypted FCM, cold/background
tap, lifecycle and TalkBack checks. First restore `svc power stayon false`.
Custom Mac 40840/helper 43849 and saved account/pairing/workspaces are preserved.
No further Mac action is requested; the full-parity goal remains active.

Next port the applicable source long-press/selection workflow. Further source
review confirms More's delete is local-only; Forward is hidden because Chief does
not supply an onForward callback. Pending approvals/questions are generic poll
models, so audit their Poll Details contract. Reply hands off from the lifted
snapshot after 0.12 s; preserve its measured origin and generation/owner checks.
This is a remaining feature gap, not covered by the six rendered checks below.

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

## Signed candidate 711 and real local relay — 2026-10-22

Candidate 711 is built from clean source
`c7967628e76f1f07683eccff2afa02070f26a229` and contains the accumulated caption,
reply motion, closing touch routing, synchronous header and copied-content fixes.
APK: `captures/runtime/parity-candidate-711/app-release.apk`, 277683069 bytes;
SHA256 `4045606fe5fe034f67aa7297f8a15124fd9bd3355e0fd3b3ccf5a299f4a910ea`.
Stable signer verified; 22 native libraries/alignment, 21 viewer assets, 14 excluded
debug components, package/SDK/version and selected private Firebase resources pass.
It is not installed or published; Pixel remains 703. Full JVM suite: 3062 cases,
3058 passed/four opt-in process checks skipped, zero failures/errors. All 414 XML
reports are archived and hashed in `parity-candidate-711-checkpoint.json`.

The skipped real cmux relay case was then run separately with the actual bundled
Mac binary: one pass in 2.882 s. Production provider/control exercised discovery,
capabilities/leases, Unicode input/output, resize, detach/reattach, owner guards,
workspace close and attach-only browser protocol. Its private HOME/session and
terminal hosts were cleaned up. This is local process evidence, not SSH network,
Pixel/native Iroh, browser pixels or personal-workspace acceptance. Details:
`captures/recovery-20261021/parity-711-real-relay-checkpoint.json`.

Custom Mac 40840/helper 43849 are alive; account, pairing and workspaces are
preserved. No Mac action is requested. Pixel availability has been requested for
a data-preserving 711 upgrade and about 15 minutes of live terminal/notification
tap checks; no answer or USB device yet. First on return restore
`svc power stayon false`. While unavailable, continue the source/rendered audit,
including font, IME and geometry changes during entry/return and RTL, then the
remaining full-app completion gates. Full-parity goal remains active.

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

## Caption source equations and corrected UIKit probe — 2026-10-21

Follow-up UIKit run 39555560226 is in progress at exact source
`be5c80c05bbcaf983b7b28dee2c4122188c3345f`. Do not redispatch it; inspect that run.

NativeChiefCaptionGeometry now matches 104 executed source font/scale/tall-script
cases for caption insets/gaps, sender geometry and TimestampCell row/label /
baseline formulas. All 96 focused Chief JVM cases pass with no skips/failures.
It is not mounted in Android yet; no caption UI acceptance is claimed. The next
step is verified UIFont inputs, matched normal/AX thread captures and source
main-transcript post-timestamp spacing. Details/receipts are in CHIEF.md and
`captures/recovery-20261021/chief-caption-source-final/`.

UIFont probe run 39554150141 at 19f300df rendered both categories, then failed
export: the harness sampled UIFontMetrics.scaledValue(17) as body size, whereas
source bubbles use a preferred body descriptor with tight leading (AX1: 26⅓
versus actual 28). Its hash-verified diagnostic artifact is retained. The probe
now uses the exact source descriptor construction, records scaled values
separately and validates actual app body/caption fonts. All 24 verifier tests and
local Swift/project parsing pass; follow-up hosted verification remains required.
Do not treat the failed metrics batch as accepted. Existing fifteen-scene thread
reference and signed 709 remain accepted in their recorded scopes; 709 is
uninstalled and its actual Android UI is unchanged by this geometry groundwork.
Full-parity/physical Mac/Pixel/terminal/FCM/cold-tap gates remain open.

## Verified candidate 709 — 2026-10-21

Signed candidate 709 includes the tested swipe work at clean source
`ec108cd30441e09d6602b4df6967689c36e8a20b`. APK SHA-256
`e75ce4efceffa9f4655fab2a5f66b6f7f097c7c297f36afafcf37fc243e0ada1`,
277,633,913 bytes. Independent checks pass the existing signing certificate,
22 native libraries/alignment, 21 viewer assets, 14 debug-fixture exclusions and
selected Firebase resources. APK/source receipt:
`captures/runtime/parity-candidate-709/`; strict receipt:
`captures/recovery-20261021/apk-verification-709/apk-verification.json`.
It is not installed or published. Gradle daemon/emulator stopped; display restored.
Mac recheck still reports locked; Pixel USB absent. Preserve existing release
app data and first restore `svc power stayon false` on authorized Pixel return.

The native thread batch is accepted and swipe behavior tests pass as recorded
below. Next source/UI comparison: matched normal/AX thread states, caption type,
timestamp geometry (current Android labelSmall/padding/semibold differs from the
actual source caption2/explicit row geometry/medium), material gray and exact
font/inset/glyph treatment, followed by ReplyTrip travel/release-offset unwind.
`captures/recovery-20261021/chief-thread-match-next.json` preserves source rules
and actual native 28⅔/44⅓-point timestamp row measurements. Physical Mac/Pixel
terminal, encrypted FCM/cold-tap/lifecycle and the remaining completion gates stay
open. Full-parity goal remains active.

## Swipe entry and accepted native thread reference — 2026-10-21

Acknowledged balloons now support source-shaped rightward swipe admission,
logarithmic travel, direction-qualified commit/uncommit, cancellation and a
stationary quote/connector. Pending rows cannot start a swipe. The arrow has
source growth/drift/reset/pulse timing and uses a system haptic; exact rendering,
haptic feel, text-selection/multitouch/IME/RTL/TalkBack and full native motion
remain open. Release offset is retained in owned thread state; ReplyTrip vertical
travel/horizontal unwind is still required. See CHIEF.md for the implementation
and the reproduced/fixed zero-velocity-at-slop failure.

Actual source execution: 34 geometry cases, six update sequences/19 steps.
Chief JVM: 94 pass/no skips or failures; debug/instrumentation builds pass.
Portrait Chief/route invocation: 44 pass/two landscape-only skips in 82.67 s;
separate capability gate: one pass in 2.869 s. Landscape: six pass in 8.884 s,
including entry and held-balloon/stationary-quote/reversal. Both crash buffers
empty; seven capture pairs verified; normal portrait and held-swipe images in
both orientations inspected. Emulator restored/stopped. Receipt:
`captures/recovery-20261021/chief-reply-swipe-checkpoint.json`.

Native reference run 39550609607 at 9324a2b0 SUCCEEDED. Artifact 12323841685,
outer SHA `9eb7b4546a72f45a61cd1b94d6162761ccb8d822074b394460251dc27f8008e3`,
passes independent source/fixture/PNG/geometry/behavior checks for all fifteen
captures. Both new thread images inspected. Real quote/Close callbacks preserve
draft/focus/viewing and restore hidden Back/backing accessibility. The reference
exposes remaining caption (11/20-point), material gray, font and inset differences;
matched Android normal/AX captures and motion remain next. Full parity active.
Signed verified 708 predates swipe work and is uninstalled. Verified Mac fix
bundle remains staged; old custom app/account/pairing untouched. Pixel USB absent;
first on authorized return restore `svc power stayon false` and preserve app data.

## Signed candidate 708 and native thread reference run — 2026-10-21

Candidate 708 is built from clean source
`9324a2b003f634303fb38b1f67be0b5422b73c7c`, including the reply workflow,
transcript overlays and corrected header symbol canvas. APK SHA-256
`e18487ba729fdaa22eaa5dfbdfae8932bd2d94e2167326c0d02058f78320528c`,
277,616,941 bytes. Independent verification passes the existing signer, 22 native
libraries/alignment, 21 viewer assets, 14 debug-fixture exclusions and the selected
Firebase client resources. APK/source receipt are in
`captures/runtime/parity-candidate-708/`; strict receipt is
`captures/recovery-20261021/apk-verification-708/apk-verification.json`.
It is not installed or published. The release Gradle daemon is stopped.

Native thread reference run 39550609607 at that exact source is in progress;
export guard tests/preparation passed and standard UIKit compilation/rendering
has started. Do not redispatch. New fixture requires fifteen captures; the
original nine scenes/package remain unchanged. Nineteen synthetic exporter tests,
Swift syntax and project parsing pass, but do not substitute for the hosted run,
image inspection or matched Android/physical acceptance. Mac remains locked,
old custom app untouched, Pixel USB absent. First on authorized Pixel return,
restore `svc power stayon false`, then preserve existing release data for any
upgrade. Full-parity goal remains active.

## Reply workflow and verified Mac build — 2026-10-21

Chief now opens a loaded root/direct-reply snapshot through quotes and the source
Reply accessibility action, retains draft/editor state through close/Escape,
blocks trim while open and purges copies on owner retirement. Send captures the
local target, exits reply mode and retains the existing text/client-ID boundary;
the actual Chief backend ignores generic reply metadata, so ordinary successful
acknowledgment clears the optimistic quote. No wire parameter was invented.
The source guard/projection checker passes four cases and 17 catalog label values.
Ninety Chief JVM cases pass; debug/instrumentation builds pass. Final portrait:
40 passes and two landscape-only skips in 77.942 s. All four separate landscape
baseline cases pass in 6.559 s (not new reply workflow landscape acceptance).
Both crash buffers are empty. Seven shared-scene PNG/metadata pairs are verified;
three new workflow, normal portrait and normal compact images were inspected.
Emulator restored/stopped. See CHIEF.md and `chief-reply-checkpoint.json` for
scope, preserved failed diagnostics and remaining gesture/motion/card/native
thread-reference/keyboard/locale/accessibility/lifecycle/physical gates.

Mac run 39541508905 SUCCEEDED at a477efa1. Its downloaded bundle now passes
independent immutable source/14-pin/33-file, archive/symlink, ARM64/environment
and deep strict signature verification; see PUSH_HELPER_RUNTIME.md and
`captures/runtime/mac-companion-39541508905-app/verification.json`. The old
custom app remains untouched. Mac locked and Pixel USB absent; runtime transition,
real Keychain/terminal/FCM/cold-tap acceptance remain open. First on Pixel return,
restore `svc power stayon false`. Signed 707 predates the latest Android changes
and remains uninstalled. Prepare a fresh stable-signer candidate before upgrade.
Full parity goal stays active; offline checks do not complete it.

## Transcript overlay checkpoint — 2026-10-21

Chief now uses a full transcript viewport with header/composer overlays, measured
scroll insets, tail-overhang-adjusted 16.71-point resting body gap and the pinned
iOS 26 plain-background wash. Exact source execution covers 50 wash/54 inset cases;
85 Chief JVM cases and debug/instrumentation builds pass. Final portrait: 34 pass
in 66.588 s, two landscape-only assumptions skip there. Landscape: all four pass
in 6.52 s. Crash buffers are empty. Five portrait/two landscape capture pairs are
verified; normal portrait, scrolled overlay and both landscape images inspected.
The initial three harness failures and corrections are retained in CHIEF.md;
all history-position, send containment and trim assertions remain. Emulator
restored/stopped; source is newer than uninstalled signed 707. The Mac fix build
39541508905 is still compiling at a477efa1; do not redispatch it. Pixel USB absent.
Bottom soft edge/blur, other background/OS branches, materials/fonts/insets,
scroll physics, quote/thread/poll/failed presentation, environment/IME/a11y/lifecycle,
remaining source audit and actual Mac/Pixel/terminal/FCM/cold-tap gates stay open.
Full parity remains active; these fixtures/emulator runs do not complete it.

## Oct 21 Mac and reference update

Mac Keychain exchange fix `a477efa1` is in full build run `39541508905`;
11 actual patched-handler cases and the coalesced/cancelable deadline lane pass
with inert Keychain/auth boundaries. The running Mac app is unchanged and live
acceptance remains required. Signed Android candidate 707 at that source is built
and strictly verified with the existing signer/Firebase/native inventory, but is
not installed or published. It predates subsequent compact-header source work.
Compact symbol canvas/spacing correction now passes two actual landscape checks
and 32 portrait regressions (two landscape-only assumptions skipped in portrait
and passed separately), with empty crash buffers. Full header/transcript overlay,
inset/font/material/scroll-position/animation and physical gates remain open.
Expanded UIKit run `39538527277` now accepts all thirteen reference exports;
see CHIEF.md/HANDOFF.md for provenance and limitations. Pixel USB remains absent.

## Latest Chief emoji checkpoint — 2026-10-21

The source/debug emoji port now covers bare one-to-three graphemes, fixed 72/48
logical font sizes, composer eligibility/marked-text font retention and explicit
font-dependent field sizing. Actual Swift/JVM/Android agree on 95 cases. Full
JVM: 3,028 pass/four skip; all 32 Chief/root Android checks pass in 60.276 s with
an empty crash buffer. See CHIEF.md for exact source/capture scope and failed
diagnostics. Platform emoji artwork/metrics differ. Expanded UIKit details/
compact export, animations, rich/physical IME, accessibility and the full app's
remaining requirement-to-evidence audit still need completion. Candidate 706
predates this source batch; the Pixel remains unavailable.

## Current checkpoint — 2026-10-20

Latest Oct 21 header/details checkpoint: portrait photo/name/back count/Stop,
both details entrances and exhausted-history intro are mounted. Source disabled
actions, default settings and unsupported rollback preserve actual phone/helper
preferences. Draft and owner-retirement behavior passes. Compact coordinates
normalize the shell's system inset. Actual Swift/primary type/source-string
checks plus 3,024 JVM passes/four skips and 28 Android passes (55.414 s, empty
crash log) support this scoped port. Fresh scene/details captures were inspected;
source Stop ink and actual large-details type are checked. CHIEF.md holds limits.
Continue actual UIKit details/landscape captures, header overlay/status/edge/
material/zoom, rich editor/full IME/lifecycle/font/locale/contrast/light/TalkBack,
emoji/quote/thread/poll/failed badge, remaining source audit and real Mac/Pixel/
terminal/FCM/cold taps. USB is absent; no physical/account/pairing/helper/signing
change. Emulator restored/stopped; signed 705 is stale. Full-parity goal active.
Retain mid-animation input/trim stress and modal read/viewing/keyboard checks:
an earlier combined send/trim timeout did not identify its cause; three isolated
fast-touch runs passed while tapping before IME visibility. The final normal
harness awaits the actual IME and stable target, preserving admission and trim
assertions. This is not evidence for every mid-animation timing case.

Newest Oct 21 text-composer checkpoint: source internal Send/blank visibility,
Foundation trimming, growing/scrolled field, revision-fenced memory-only editor
and synchronous retained-owner send/local delete admission are implemented.
Measured tail correction keeps reflowed controls visible for a pinned reader.
Actual Swift: 60 row/62 trim/five height cases, 11 source strings/nine locales.
JVM: 3,020 pass/four skip/zero failures. Android: 25 pass in 46.624 s, empty crash
buffer; four fresh reference-input pairs plus expanded-draft/failed-sheet images
verified/inspected. CHIEF.md records limits and diagnostics. Continue exact glass/
keyboard/wide-screen/rich editor, header/details/service intro/drawer, emoji,
quote/thread/poll/failed-badge, font/locale/contrast/accessibility/lifecycle and
broader source gates. Actual Mac/Pixel/terminal/FCM/cold taps remain required.
The phone is not yet attached; preserve its 703 app/data. Prepared signed 705 is
stale relative to this work: prepare a fresh signed candidate before upgrade.
The emulator was restored/stopped. Full-parity goal stays active.

Oct 21 bubble checkpoint: source run-tail curves, width/padding rules, native
text-fitting bodies, window-anchored blue fills and separate original-sender
quote outlines/connectors are implemented. Actual Swift path/metric vectors,
3,016 JVM passes (four skips) and 21 Android passes (41.16 s, empty crash log)
support this scoped port. Four identical-input dark scene captures use the
UIKit reference viewport and were inspected; timestamps/system bars/fonts and
the header/composer still differ. See CHIEF.md for provenance and failed-run
diagnostics. Complete header/composer/drawer, quote/thread actions, emoji,
poll/failed-badge UI, all font/locale/contrast/keyboard/wide-screen/animation and
accessibility gates, plus remaining source inventory and actual Mac/Pixel/FCM
verification. The owned emulator was restored/stopped; signed 705 is unchanged.

HANDOFF.md contains the current physical Mac/Pixel and signed-candidate state;
older dated entries below are historical evidence. Signed local 705 is verified
and awaits a data-preserving Pixel upgrade. Chief's source/debug implementation
now includes explicit failed-send retry/delete, foreground read/catch-up and
guarded loaded-window trimming and current-position history prefetch/retry, with details and remaining gates
in CHIEF.md. The previous custom Mac's pairing menu is accepted. Its verified
Chief-capable replacement is now launched with saved workspace IDs and stored
phone enrollment retained; account/capability, menu and live helper checks are
pending manual Mac unlock. Full parity and the completion gates remain open.

The actual UIKit offline reference batch now passes at `39513912473` /
`e431401c`; all nine downloaded PNG/metadata pairs were independently verified
and inspected, including actual 28-point accessibility body text. This supplies
source visuals for comparison, not Android or physical acceptance. Android
Chief bodies/quotes/pending rows now preserve literal markup and detected
web/email/phone links; 14 Chief runtime cases pass (30.684 s), with an empty
crash buffer. Source date/address actions and detector/underline fidelity remain
open. Both changes are newer than signed candidate 705. Full run-tail, quote,
header, composer, failed badge and poll presentation comparisons remain open.

## Current native delivery checkpoint — 2026-10-13

The corrected Mac sign-in build **38433167598 / 06b0e294** passed and is now
verified, extracted and running. All 27 source hashes, archive/signatures, ARM64
and production routing match. A real fresh-code request reaches the corrected
six-character form; the owner is entering the code. Successful verification and
native account/listener/Pixel/FCM acceptance remain open. The official app and
phone data are preserved. See MAC_NOTIFICATION_BUILD.md for exact evidence.

Follow-up **38437521385 / 758d3688** is compiling the helper heartbeat fix and
first bounded compiler-cache recipe. It remains a specific live job, not a reason
to redispatch. New Android source **24b3db79** adds the first static localization
pass: 212 Text calls, 84 source strings/nine locale resources, successful main/test
compilation and 756 exact compiled values. Full localization/RTL/ART/physical
acceptance stays open; signed 659 is unchanged. See PARITY.md and the selected
catalog provenance. None of these checkpoints establishes full parity.

## Mac email-code validation correction — 2026-10-13

The owner clarified that input was accepted but Sign In showed an error; a focus
failure is not established. Source review found a real validator bug: the custom
form accepted only six ASCII digits, while upstream iOS
`SignInCodeInputPolicy` accepts ASCII letters/digits, uppercases, strips formatting
and clamps to six characters. The custom model now matches that policy and the
form says six-character code. The coordinator still composes the normalized
prefix with its own opaque nonce and performs the existing verified sign-in flow.

The Swift fixture now compiles the actual pinned iOS policy/action files and
compares ten representative numeric, mixed-case, formatted, oversized and Unicode
inputs. Its successful verification path uses `ab12cd` and observes `AB12CD` at
the injected coordinator seam. Validation/retry/cancellation/busy/late-result
checks pass locally. This establishes normalization and form state, not a real
code exchange. It was not a phone/Android code change; signed build 659 remains
current. The already running Mac artifact predates this fix and needs rebuilding.

The attempted full local UI check still reports an unaccepted Xcode license,
despite `xcodebuild -version` identifying Xcode 27.0; version reporting is not
license acceptance. The hosted build keeps its mandatory UI check. No raw code,
nonce or credential was recorded. Logs are ignored under
`captures/runtime/companion-email-code-fix-20261013`.

## Updated Mac companion verified and launched — 2026-10-13

Hosted run 38417815664 succeeded at source `3247ee9a`, upstream `f4b15090`.
Full app compilation took **79m47s**. Artifact 11829858319 was downloaded;
archive SHA-256 is `2ae5d42766a1693f4af57700500dff739b6c148c7bc9919a40c843d4ee5863e2`.
All **27 reviewed patch-file hashes** match. Archive paths/12 relative symlinks,
ARM64, deep ad-hoc signatures, isolated bundle ID and production routes verify.

The old custom app quit normally and the new one launched through the explicit
private-owner launcher. The official app was not replaced or stopped. Actual UI
now displays the native email-code form, and a real code request successfully
advanced to its code-entry stage without a browser callback. The owner then
reported being unable to enter the code; input focus/error/window diagnosis is
pending, with the exact symptom requested. This is not successful sign-in or
native push acceptance. Subsequent UI inspection currently returns a ScreenCaptureKit
invalid-parameter error after the user changed the app state.

The current local bundle is under ignored
`captures/runtime/mac-companion-38417815664-local-app/`. Verification/manifest,
source/ZIP receipt and checker are under the corresponding ignored verified
folder. Native account/key-store/helper listener, Pixel pairing and real FCM still
remain open. No private code, token or provider credential was recorded in this
checkpoint. Signed Android build 659 is separately verified and published.

## Signed Android milestone 659 — 2026-10-13

Workflow 38422493021 passed at `1f2616f7` and published development build 659.
Independent APK verification agrees with the release/CI receipts: stable signer,
22 aligned native libraries, 21 viewer assets, 14 debug-component exclusions and
correct private Firebase resources. CI retains six successful Android 17 ART
class checks. The Cloud checkpoint passed. See PIXEL_INSTALL.md for the download,
full digest and scope. This delivers accumulated source changes, but does not close
signed-in Pixel upgrade, real custom Mac pairing, FCM or full parity gates.

## Android connection cleanup validation batch — 2026-10-13

Latest debug/test APKs built in **2m59s**. Six selected classes pass Android 17
ART loading/reflection on **16 KB pages**, including `NativeScreenKt`,
`NativeFeedSession` and the four cleanup/visibility classes. This checks the
changed class boundary; it is not an authenticated MainActivity/NativeScreen run.

**Three runtime cases pass in 8.587s**: actual Settings team-picker components
keep the original mock-server root; confirmed removal survives real Activity
recreation while preserving the other team's identical locator/selection; and
encrypted local removal rejects a late guard without a disk change, then preserves
the other team's resume locator on storage reopen. The latter two cases now assert
`pairing_code` explicitly, closing the prior gap where surviving rows/selection
could pass while the resume code was accidentally cleared.

This is verified mock Stack/peer, encrypted Android storage and component/lifecycle
evidence. It does not establish Pixel/Mac Iroh, signed upgrades or physical UI
parity. No new signed preview is published. The sole existing AVD was stopped and
reaped after 42.987s; Gradle was stopped before starting it. APK/test-source hashes,
ART output, runner, lifecycle and original instrumentation logs are ignored under
`captures/runtime/android-selection-milestone-20261013`.

## Backend update coverage — 2026-10-13

The watcher now includes website API routes, auth handlers/callback policy,
shared web helpers/services, RPC/OpenAPI/schema/types, backend dependency patches
and root dependency/routing/security configuration. A backend-only change such as
`web/app/lib/native-callback.ts` must enter the mobile review queue: native routing
can remain unchanged while the production server stops accepting a companion
callback. Changes in ordinary blog/marketing content remain outside this scope.
The review report groups these paths under **Authentication and mobile backend**
and includes a backend-contract checklist. Removed files and renames out of these
paths retain their original review area.

All **19 update-policy tests pass**. A read-only live watcher run at upstream
`cdbe99596fe13784ec5ea24d2e1c720a8f67f5cb` used complete recursive trees, reported
**5,276 changed paths** and included **339 backend paths**. It did not publish an
issue, build an APK or advance either reference. Logs and reports are ignored
under `captures/runtime/upstream-backend-watch-20261013`. Normal main-branch
scheduled runs retain their existing bot-owned review issue and quiet-on-unchanged
policy; preview builds remain opt-in. Path detection does not constitute content
review or Android feature parity.

A separate local tree comparison from the global implemented pin to the reviewed
custom Mac pin `f4b1509054949eaad5d695569ad443c4c18ed68d` found **623 changed mobile
paths** in `Packages/iOS`, `Packages/Shared/CMUXMobileCore` and `ios/cmuxPackage`.
Many have scoped feature audits in this repository; the full requirement-to-source
and physical acceptance reconciliation is still open. The inventory is retained
under ignored `captures/runtime/upstream-mobile-delta-f4.tsv`. Neither pin is
advanced merely from these counts.

## Isolated Mac email-code sign-in implementation — 2026-10-13

The custom Android-push Mac app now exposes the existing production
`AuthCoordinator.sendCode(to:)` / `verifyCode(_:)` flow instead of requesting a
browser callback rejected by cmux.com. The new form uses the composition's same
coordinator and account projection; it does not read another app's credentials,
register an official callback scheme or change server trust. Validation excludes
the development `42` shortcut. It handles one request at a time, resend/change
email, retry, auth busy/restoring state, cancellation and late UI completion;
verification succeeds only once the coordinator reports authenticated state.

Both new and reused sign-in workspaces suppress their automatic browser start
for the exact isolated bundle. Host account sign-in and switch-account UI also
route there. Other builds retain the original hosted view/body and account paths.
The custom form's account, Mobile and workspace presentations use the same auth
owner. Browser-only/socket sign-in URLs are not made valid by this change.

The installer now checks **11 upstream pins / 27 patched files**, including the
new state/model and view's Xcode source membership. The production form state
machine compiles/executes locally in Swift 6; validation, duplicate actions, send
and verify retry, busy auth, cancellation, late completion and non-authenticated
verification cases pass. The original hosted view body remains byte-identical.
Full native hooks parse/project checks and compiled auth/source/receipt/private
pipe checks are run with the changed installer.

Local full UI typechecking is currently blocked by the CLT SDK's missing
`SwiftUIMacros` plugin. Selecting the installed Xcode tools instead reports an
unaccepted Apple license; the owner was asked to finish Xcode setup. The explicit
local `--model-only` check records `uiTypechecked: false`. CI uses the default
mandatory SwiftUI interface-fixture typecheck before a full tagged app build;
no UI typecheck, linked app or actual account acceptance is claimed yet. Logs and
receipts are ignored under `captures/runtime/companion-email-sign-in-20261013`.
The existing local Mac artifact predates this change and remains signed out.
Real code sign-in, storage restart, native listener/helper enrollment and Pixel
FCM acceptance still remain required. No Android build or emulator was started.

## Selection cleanup and hidden foreground ownership — 2026-10-13

Removing or hiding an original-team pairing could erase the global resume locator
of a different team's selected row when both used the same public Tailscale URL.
The encrypted-state cleanup now preserves that locator only when the distinct
selected record still exists and owns it. Removed selection, legacy selection and
stale selection still clear affected locators. Forget, scoped-code removal, local
pairing removal and hiding share this rule; unrelated drafts/credentials survive.

Main-screen hiding now resolves the actual admitted or pending record before
retiring a connection. It cannot infer ownership from a shared locator or from the
picker's selected team. Without a captured record, only a unique saved selection
may resolve it. Computer Details dismissal also matches the original owner/build;
hiding another team's copy cannot dismiss the original page.

**39 focused JVM tests pass**, including nine new selection/reload, live/pending
visibility, ambiguous locator and exact-owner details cases. Android source and
unit tests compile; the final incremental run took 17 seconds. No APK/ART/device
acceptance is claimed. Source hashes, XML and logs are ignored under
`captures/runtime/selection-cleanup-20261013`. The earlier first run passed 31
cases. No emulator or physical phone was started, and no account data was changed.

# Remaining work for iOS parity

## Settings team-switch retention integration — 2026-10-13

Settings now builds connection rows from the record-qualified inventory, including
the admitted original-owner Mac alongside the selected team's connected records.
Detail actions resolve that original live access first and capture its own scope;
a team round trip cannot relabel it with the newer selected scope generation.
Names/transport/status remain record-qualified instead of borrowing a same-locator
or same-device/build sibling's data.

Settings selection and creation now call the shared ViewModel team-transition
methods. They preserve only an exact admitted record/client with readiness,
matching code/connected code, matching retry generation and a live original
membership proof. Pending/unadmitted attempts are closed and their code cleared.
The Iroh eligibility effect also permits this exact retained session. Ordinary
selected-team eligibility and new connection admission remain unchanged; the
connection effect already returns early for an authorized retained wire.

**71 focused JVM tests pass; debug/test APKs build. Six classes pass Android 17 ART
reflection on 16 KB pages. Four runtime cases pass in 9.838s**: the Settings team
picker and original detail/check after a switch/round trip with zero new dials,
retirement after membership removal, pending unadmitted retirement, previous
Activity-recreation/removal lifetime, and confirmed team creation with one POST
and preservation of the root. The fixture uses the production transition methods,
actual Settings components, verified mock Stack membership and encrypted storage.
Source audit covers the corresponding main-screen handlers and connection-effect
retention branch; this is not full MainActivity or physical transport acceptance.

Evidence/XML/source/APK hashes are ignored under
`captures/runtime/settings-team-switch-20261013`. Only the sole existing AVD was
used and stopped/reaped. No physical phone or production account was touched.
**The earlier explicit Settings selection/create and Iroh teardown barriers are
now replaced by admitted-session retention.** Next verify the entire main-screen
Mac/Pixel flow, including terminal input, pending/secondary transport retirement,
recovery, background notifications and account revocation. Signed preview 654
predates this source; full parity remains unproven.

## Detail presentation and removal lifetime — 2026-10-12

The ViewModel now owns Computer Details and the exact pending reconnect record.
The host accepts an original-owner foreground detail after the list switches
teams, uses record-qualified connection data, and stops claiming a live connection
once that captured root closes. Controls still address the original owner; an
already approved removal may remain visible for server completion/local cleanup.
Ordinary selected-team operation and new Mac admission guards remain unchanged.

A presentation retains one Forget flow and its confirmation state. The ViewModel
runs confirmed actions independently of Activity composition. Attachment tokens
replace view callbacks on recreation; stale detach/update cannot overwrite the
new view, and detached preparation can stop the exact root through ViewModel data.
Completion is delivered once to the current view. Replacing/clearing a presentation
cancels its action and drops callbacks; logout clears the retained state. A late
change of screen context also blocks beginning a foreground revoke.

**42 focused JVM tests pass; debug/test APKs build. Ten classes pass Android 17 ART
reflection on 16 KB pages. Three UI tests pass in 9.138s**: online membership
verification with a mock Stack server, original detail/check after selection changes,
actual Activity recreation while server removal is paused, one original-scope revoke,
completion to the new view, and preservation of the other team's identical-code
pairing/selection; plus existing root check/power/reentry and local cleanup retry.
The build exposed a Compose/D8 non-local-return marker; an explicit conditional
fixes it. Two initial UI assertions were corrected for duplicate title/name nodes
and asynchronous RPC completion. Original diagnostic logs are preserved.

Evidence/XML/source/APK hashes are ignored under
`captures/runtime/computer-details-lifetime-20261012`. The sole AVD was stopped/
reaped; no production account or physical phone was touched. **Remaining:** add
the original row to Settings, audit the combined team-switch flow before removing
explicit selection/create/eligibility teardown, then verify Pixel/Mac workflows.
Actual Settings team selection still disconnects; these isolated host fixtures
are not full-flow acceptance. Signed 654 predates this source; parity is unproven.

## Original-owner Tailscale route management — 2026-10-12

Connected Mac details now uses `NativeForegroundTailscaleRoutes` for explicit
add/edit/remove actions under its original account/team. Each probe verifies the
captured Mac/build, requests a constrained manual ticket (only definitive unsupported
methods may fall back), performs the authenticated workspace read and validates the
current compatibility policy. It closes the temporary connection on every exit;
it cannot borrow the terminal root, return a session, or relax ordinary selected
team admission. Production supplies the compatibility validator explicitly.

Credential writes check both membership/record authority and the captured screen's
connection context inside the shared account/credential transaction. Another team's
identical route and sibling builds survive. One commit fence permits completion
when replacing the active grant retires the old root, while forbidding further
probes, RPCs or token refresh. Permission loss cancels blocked probes; failed edits
preserve the previous route. The original root stays separate from probe ownership.

**144 focused JVM tests pass; debug/test APKs build. Eight classes pass Android 17
ART reflection on 16 KB pages. Three UI tests pass in 11.104s**, including actual
online-verification/team-switch continuation with mock Stack/peer servers, add/
edit/remove through encrypted storage, and an attempted commit after the screen
context changes while its management root remains live. Existing encrypted-login
fencing and route-edit retry checks also pass. Initial tests caught and fixed
refresh configuration on the wrong handle and the missing screen-context guard;
original failure logs are retained. Evidence/XML/source/APK hashes are ignored
under `captures/runtime/foreground-tailscale-routes-20261012`. The sole AVD was
stopped/reaped; no production account, actual VPN or physical phone was used.

**Remaining:** durable detail/removal presentation, original-row Settings inventory,
full original-owner detail hosting across team selection, combined scope/UI audit,
and real Pixel/Mac acceptance. The host still requires the selected team; explicit
selection/create/eligibility teardown still disconnects. Route operation fixtures
do not prove that entire flow or full parity. Signed preview 654 predates this source.

## Original-owner private addresses — 2026-10-12

Computer Details now loads and changes private addresses through its captured
foreground owner. `foregroundPrivatePaths` constructs settings for that owner's
enrolled identity without starting account control, directory observation, endpoint
binding or a Mac connection. It checks the exact live record/membership/runtime
before and after setup and access, and closes/awaits the temporary backend on every
normal and failure path. The constructor rejects a management record from another
owner or pairing.

`NativePrivatePathStore.computer` exposes only the captured device/build. Reads,
upserts, removal and reset are restricted to that projection; commits merge with
the owning file under the shared storage lock, preserving sibling Macs/builds.
Retargeted writes fail, and a late permission loss during the final merge prevents
a write. Another selected team's identity file remains separate. Source review at
scoped upstream pin f4b15090 confirms the iOS editor is pinned to this Mac/build.

**68 focused JVM tests pass**, including the original-owner local settings tests,
existing 37-case runtime and 11-case continuation authority suites. Debug/test APKs
build in 1m20s; six changed/relevant classes pass Android 17 ART reflection on 16 KB
pages. **Three UI tests pass in 13.717s**: the actual editor persists into separate
AtomicFile identities while preserving a sibling and the selected team's file,
rejects a late membership loss, keeps its root alive; the previous detail check/
power/reentry flow; and the existing exact-build editor without global reset. The
fresh failed-save screenshot was inspected. Evidence/XML/source and APK hashes
are ignored under `captures/runtime/foreground-private-addresses-20261012`.
The sole existing AVD was stopped/reaped; no physical phone/account was touched.

**Remaining:** original-owner Tailscale add/edit/remove admission and ticket/
compatibility checks, durable detail/removal presentation, original-row Settings
inventory, and combined team-switch UI/real-device acceptance. The host still
requires the selected team and explicit selection/create/eligibility teardown
still disconnects. These fixtures do not prove actual Pixel/Mac networking or
full parity. Signed preview 654 predates this source.

## Connected Mac detail controls — 2026-10-12

Computer Details now captures a stable, exact foreground access object from the
admitted record/client and original management authority. The presentation rejects
another owner or device/build; a harmless name change remains valid. Connection
checks use the existing root instead of dialing a second connection. Host/status,
workspace reads, power status/mutations and the fixed power subscription recheck
permission through token lookup and after response admission.

The detail page starts a fresh power observer on each lifecycle entry. Stopping
that observer unsubscribes without closing the terminal root; reentry reads current
Mac state and does not replay a mutation. Foreground appearance and connection
preference saves use the captured live guard. Confirmed foreground removal uses
the original-owner management flow. An unconfirmed failure can be cancelled,
reopened and explicitly retried through its still-valid removal permit after root
stop; it does not permit a new arbitrary action after connection loss.

**44 focused JVM tests pass; debug/test APKs build. Eleven changed classes pass
Android 17 ART reflection on 16 KB pages. Four UI cases pass in 9.643s**, including
the actual detail host's connection check, power toggle, dismissal/reentry on one
root; existing saved-Tailscale check; local cleanup without another revoke; and
cancel/reopen of an unconfirmed removal after root stop. The fresh detail screenshot
was inspected. Evidence, XML and source/APK hashes are ignored under
`captures/runtime/foreground-computer-details-20261012`. Only the existing AVD was
used and stopped/reaped; no physical device or production account was touched.

**Remaining:** the host still requires its selected team, and Settings still lists
selected-team rows only. Integrate original-owner private paths and Tailscale route
probes/tickets, durable detail/removal presentation, then the original row and full
team-switch flow before removing selected-team teardown. These fixture checks do
not prove Pixel/Mac, Iroh, actual team switching or FCM acceptance. Signed preview
654 predates this source; full parity remains unverified.

## Original-owner confirmed computer-removal foundation — 2026-10-12

A management context can now be captured only from the exact live admitted
record/client and verified original account continuation. A confirmed revoke
creates a target-specific permit before stopping the root. That permit can finish
or explicitly retry the same removal while original membership and the unchanged
pairing remain valid. It cannot begin a new action after root loss, admit a Mac
transport, change target, survive membership/record changes, or start duplicate
concurrent control operations. A confirmed acknowledgement prevents another revoke.

`NativeIrohRuntime.forgetForegroundComputer` uses a short-lived account control
backend for the permit's original scope. Production `startManagement` connects the
control session without starting endpoint observation or dialing a Mac. Setup,
revoke and acknowledgement are guarded; every path closes the control backend.
The selected team and ordinary selected-operation/dial policy remain unchanged.
This also avoids depending on a stopped saved-Tailscale root's native backend.

The new foreground Forget flow captures this permit before the existing stop
callback, then shares exact-owner credential/appearance/connection cleanup with
the selected flow. Local-save retry after a confirmed revoke neither requires the
stopped root nor repeats the remote call. Confirmation copy now says the owning
cmux team, rather than incorrectly calling it the selected team.

**66 focused JVM cases pass**, including real membership/record authority tests,
control-only scope isolation, permission loss, explicit retry, duplicate/closed
runtime handling, and the existing 37-case Iroh runtime suite. Debug/test APKs build
and nine classes pass Android 17 ART reflection. **Three runtime cases pass in
9.089s**: the new confirmation/credential pipeline after root stop preserves the
other team's same-locator binding and selection; failed local save retries without
another revoke; and the existing selected-flow cleanup retry remains correct.

Evidence/XML/source and APK hashes are ignored under
`captures/runtime/foreground-computer-management-*`. The sole existing 16 KB AVD
was stopped/reaped; no physical device was touched. The control backend is a
fixture and no production account registration was revoked. These checks do not
prove actual team-switch UI, Pixel/Mac/Iroh or FCM acceptance. Signed 654 predates
this source; full parity remains unverified.

This is the removal foundation, not completed detail-page integration. Next wire
the exact original-owner detail route and its live check, power, appearance,
connection/private-route settings and confirmed removal. Preserve the original
live record guard for new actions, retain only the explicitly confirmed removal
permit after stopping its root, and keep general new Mac admission selected-team
scoped. The Settings inventory still omits the original-team row until that route
can handle its full actions. Explicit Settings selection/create and Iroh eligibility
teardown remain enabled: **team selection still disconnects the app**.

## Captured-owner local removal and root-stop fencing — 2026-10-12

The local Settings removal action now captures login, pane owner, exact saved Mac,
code, client and retry generation. It checks current presentation/permission before
and inside the credential transaction. Only the unchanged captured pairing is
removed; same-code other-team rows and sibling builds survive. Changed routes or
tickets, ambiguous records, account changes and lost permission cannot mutate it.
An unsaved pasted code can be cleared without deleting saved records. Scoped route
grants/tickets are pruned for the captured owner. Drafts, sign-in and another saved
selection/code remain intact. The accepted action closes its captured root and
clears its pane instead of rebinding to a same-code sibling.

Remote Forget preparation now distinguishes the exact live admitted record from
its exact pending reconnect record. A selected-team row sharing the root's public
locator cannot stop that root; a genuinely pending matching request can be stopped.
Owner/target identity remains checked. This corrects the preparation callback; it
does not yet enable remote removal through a retained original-team detail page.

**56 focused JVM cases pass**, including six new same-locator/build, revision,
account, late-guard, unsaved-code and live/pending-root cases. Debug/test APKs build
and five classes pass Android 17 ART reflection. **Three runtime cases pass in
6.292s**: the real Settings button removes its pairing/closes the root while keeping
sign-in; encrypted storage rejects a late guard without a disk change and retains
the other team's same-locator row on success; and local cleanup retry does not
repeat an already confirmed remote revoke.

Evidence/XML/source and APK hashes are ignored under
`captures/runtime/foreground-local-removal-*`. The sole existing 16 KB AVD was
stopped/reaped; no physical device was touched. These are local fixtures, not actual
account team switching, Mac/Iroh/Pixel or server revoke acceptance. Signed 654
predates this source; full parity remains unverified.

Source review confirmed iOS `MacComputerDetailView.forgetComputer()` builds removal
from the pairing's own stored user/team (reviewed scoped pin f4b15090), and
`NativeIrohRuntime` retains an original native backend while its foreground lease
is held. Android's selected detail host and runtime operations still require the
selected owner. Next: an exact-target original-owner detail/management path for
appearance, connection settings, live checks, power, private paths/routes and
remote Forget; preserve selected-only new Mac admission and safe post-revoke local
cleanup. Saved Tailscale transport retention also needs its original control-owner
path. Do not simply relax global `isCurrent` or bypass the selected operation guard.

Explicit Settings selection/create and Iroh eligibility teardown remain enabled:
**team selection still disconnects the app**. Combined scope/UI and real-device
acceptance are still required before removing them.

## Foreground agent-feed ownership and lifecycle — 2026-10-12

`NativeFeedSession` now owns one agent-feed session on the exact admitted root
record/client/codes/capability and original live permission. It shares the existing
connection, survives Activity recreation, publishes into the foreground workspace
projection, and retires on client replacement, disconnect, record/permission loss
or disposal. Main and routed agent views use this owner before selected-team feed
sessions. Source inventory, allowed rows and workspace/tab navigation use the pane
Mac; secondary admission remains selected-team scoped.

Subscriptions/polling pause in the background and resume with a fresh snapshot
before actions are available. Newer event revisions also require refresh before
action. Decisions, terminal replies and text reads carry ownership, visibility and
item guards through asynchronous token lookup and wire admission on both root and
secondary feeds. Interrupted writes retain uncertainty while the owner is still
valid, including while inactive; resuming never automatically replays them. A
monitor that loses its owner stops safely instead of throwing out of its parent.
Authorization rejection retires the root.

Read-marker/modal owner keys now follow original login/user/team identity without
a membership-refresh generation. The matching prior generation-key entry is a
migration fallback; unrelated account/team preferences are not scanned. Pull-to-
refresh and routed refresh wake the root session as well as existing feeds.

**114 focused JVM cases pass**, including five new foreground lifecycle, fresh-read,
token-lookup, uncertainty, rejection and read-owner cases, plus the existing 60-case
feed coordinator suite. Debug/test APKs build and seven classes pass Android 17 ART
reflection. **Three runtime cases pass in 23.539s**: an action on the retained root
with the secondary feed paused, then recreation with identical root/session and no
replay; real feed-tab/search/question/permission/reply/tab navigation; and delayed
IME submission using the newest draft with dismissal confinement. The initial
compile identified a refresh method needing `suspend`; it was corrected before the
final verification.

Evidence/XML/source and APK hashes are ignored under
`captures/runtime/foreground-agent-feed-*`. The sole existing 16 KB AVD was
stopped/reaped; no physical device was touched. These are local fixtures, not actual
account team selection or Mac/Iroh/Pixel/FCM acceptance. Signed 654 predates this
source; full parity remains unverified.

Remaining: foreground Settings/detail/local-pairing-removal operations, final
combined scope integration and actual account-switch UI acceptance. The selected-
team detail host still needs a valid original-owner path. Explicit Settings
selection/create and Iroh eligibility teardown remain enabled: **team selection
still disconnects the app**. Keep fresh connection admission selected-team scoped.

## Record-qualified connection presentation and same-locator replies — 2026-10-12

Connection status now has a record projection keyed by account owner and app
instance, with exact origin/route/ticket matching on lookup. Foreground count and
transport remain separate from a selected-team same-device/same-locator feed's
count, transport and keep-awake state. Pending status requires the intended record,
not code equality. Ambiguous owned instances are omitted. A harmless name change
can keep its verified source; route/ticket changes cannot. This is presentation
metadata and never admits a connection or settings action.

`NativeScreen` derives selected-team legacy maps from this projection, supplies its
record getter to the computer selector, and uses it for selected Settings rows.
Inventory union coalesces harmless foreground/selected name differences without
choosing ambiguous records. Settings row keys and captured action lookup include
account ownership, preventing a removed original row from retargeting to a same-
instance row in another team. The component can render both owners safely; the
production Settings inventory still awaits the foreground detail integration.

Reply candidates now search both foreground and selected inventories by original
notification origin, coalesce harmless renames and reject conflicting aliases.
The root branch requires `paneConnected(record)` rather than code equality. A
same-locator selected peer can therefore borrow its already verified feed while
the original root stays untouched. Selected feed permission and all encrypted
receiver/outbox/epoch and admitted-paste fences remain; no new dial is introduced.

**96 focused JVM cases pass**, including a new real-RPC same-locator selected-feed
reply test, record projection isolation/pending/revision cases and the existing
60-case feed coordinator suite. Debug/test APKs build and seven classes pass
Android 17 ART reflection. **Three runtime cases pass in 7.593s**: same-instance
owner rows retain separate transport and reject captured-row retargeting;
component owner/membership/disposal guards; and the encrypted original-owner
notification reply across an injected picker change.

Evidence/XML/source and APK hashes are ignored under
`captures/runtime/foreground-record-connections-*`. The sole existing 16 KB AVD
was stopped/reaped; no physical device was touched. These are local fixtures, not
actual account team selection or Mac/Iroh/Pixel/FCM acceptance. Signed 654 predates
this source; full parity remains unverified.

Remaining: foreground Settings/detail/local-removal operations, original-owner
agent-feed source/read/actions when its secondary feed retires, then integration
and actual account-switch UI acceptance. The selected-team detail host still
requires its selected discovery/runtime state. Explicit Settings selection/create
and Iroh eligibility teardown remain enabled: **team selection still disconnects
the app**. Do not broaden fresh connection admission to make a detail view work.

## Foreground replies, workspace inventory and display metadata — 2026-10-12

The direct notification reply provider now finds the individually admitted pane Mac
and uses its original live owner. Historical action generation zero is matched by
login/user/team identity to that live owner; it grants no fresh connection. The
exact admitted record/client/codes/readiness and saved pairing guard still apply.
Other Macs remain selected-team feed scoped. Existing encrypted action, peer epoch,
helper epoch and outbox permission fences remain in the receiver.

New `nativeForegroundReplyAttempt` captures one ready workspace/surface on the
existing handle, keeps input ordering and literal text/Return submission, and
rechecks topology and authorization through asynchronous token lookup and after
acknowledgement. Authorization rejection retires that handle. `pasteAdmitted`
passes this request-local guard through the normal input dispatcher and identified
input RPC; it does not replace the input stream identity. Secondary feed replies
also use admitted paste. A lost acknowledgement remains unconfirmed and cannot
automatically become a relay duplicate.

The workspace list, selected computer and sort inventory now include the admitted
pane Mac. Root snapshot fallback and sort connection status require `paneConnected`
instead of code equality. Inventory memoization tracks the live owner, record and
readiness. A retained Mac also prevents empty-computer onboarding and participates
in additive color slots when absent from the newly selected directory. Scoped
presence/build-label reads reject another team's same-device metadata; local
last-seen history and build-tag fallback remain available. Legacy unscoped selected
rows retain their prior display behavior.

**68 focused JVM cases pass**, debug/test APKs build, and seven classes pass
Android 17 ART reflection. **Four runtime cases pass in 14.261s**: real encrypted
notification/action/receiver delivery on the existing connection after an injected
picker change; lost acknowledgement without relay/second write; original/picker
same-device presence and build labels; and root workspace reordering while the
secondary feed is paused. JVM cases include original-owner isolation, zero paste
bytes after proof loss during token lookup, authorization retirement, literal text,
ready-topology confinement and identified dispatcher submission.

The first JVM run had a new fixture expecting input sequence zero; the actual
protocol starts at one. Only the expectation was corrected, and its original XML
is preserved. A missing test-tag import in the Android fixture was also repaired.
Final XML/logs/source and APK hashes are ignored under
`captures/runtime/foreground-reply-metadata-*`. The sole existing 16 KB AVD was
stopped/reaped; no physical device was touched. These are local fixtures with
injected scope/proof, not actual account team switching or Mac/Iroh/Pixel/FCM
acceptance. Signed 654 predates this source; full parity remains unverified.

Remaining integration found by source review: owner-qualified selected-computer
connection/count/transport presentation (the shared identity map still belongs to
the picker), foreground Settings/detail/local-removal actions, and agent-feed
source/read/action presentation while the original secondary feed is retired.
Explicit Settings selection/create and Iroh eligibility teardown remain enabled.
**Team selection still disconnects the app.** Keep teardown until those remaining
paths and actual account-switch UI acceptance work together.

## Foreground Mac forwarding settings — 2026-10-12

Mac forwarding controls now use the admitted pane's original team and appearance
name. Read and write guards require the exact current handle, admitted saved Mac
record, login/user/team, both code bindings, readiness and live selected or retained
foreground authority. A same-locator selected-team sibling cannot borrow the old
handle after its proof disappears. Route/ticket/record changes, removal, ambiguity,
logout, replacement and disconnect deny settings traffic. Controls disappear when
the guard becomes false; cached settings never grant permission.

**37 focused JVM cases pass**, including four new identity/authority cases. The
existing authenticated RPC test now exercises the actual foreground guard with the
picker scope unavailable; revoking its proof during token lookup sends no mutation
bytes. Debug/test APKs build and four classes pass Android 17 ART reflection.
**Two runtime cases pass in 4.847s**: real Mac settings RPC/UI preserves the original
owner and alias across an injected picker change, changes a setting once, and removes
controls after proof loss; the confirmed/stale controls case also passes.

Evidence/XML/source and APK hashes are ignored under
`captures/runtime/foreground-mac-settings-*`. The sole existing 16 KB AVD was
stopped/reaped. No physical device was touched. The picker change is a component
fixture, not actual account team selection or full Mac/Iroh/Pixel acceptance.
Signed 654 predates this source; FCM delivery and full parity remain unverified.

Targeted source review found remaining work before removing Settings teardown:

- `NativeScreen.settingsContent`: connection rows/current-detail guards use the
  selected team, and local pairing removal captures the selected owner for the
  active code. Retained foreground controls need their own owner-qualified route.
- `NativeComputerDetailsPresentationHost`: `state.account != team` dismisses the
  details view. The original foreground's details need valid per-owner state;
  do not relax the runtime's selected-team authority for new connections.
- `ObservePhoneReplyDirect`: the direct reply provider still requires the selected
  team and selected paired-Mac list. Retained foreground replies need exact owner,
  record, workspace/surface and reply authority before/after delivery.
- `machineColorIndices`/`scopedPresence`: verify foreground display metadata when
  the original Mac is absent from the selected directory, including same-device
  rows. Root sort-computer status still compares code rather than `paneConnected`.
- Explicit Settings selection/creation and the Iroh eligibility effect still close
  the root. Keep those until remaining integration and actual team-switch UI
  acceptance are proven. **Team selection still disconnects the app.**

These findings are a targeted audit, not a claim that all scope sites are complete.

## Foreground appearance and task choices — 2026-10-12

The admitted pane now reads name, color and icon from its original user/team.
An owner-qualified appearance overlay preserves same-device picker rows in the
selected team. Failed foreground reads fall back to that Mac's host name; appearance
metadata grants no connection authority. Task draft restoration, Mac choices and
model-cache retention use the admitted pane's available Macs. Routed sidebar actions
use the existing exact-record pane admission guard, and workspace shell ownership
follows the pane team.

**70 focused JVM cases pass**, debug/test APKs build, and five changed classes pass
Android 17 ART reflection. **Three runtime cases pass in 24.232s**: real appearance
editing/reopening, paginated folder selection with exact path/name/group, and task
model/effort submission. The folder fixture initially skipped host capability
negotiation; it now requests host status and advertises group support before
creation. Product mutation admission checks remain intact. An appearance fixture
was also corrected to use UUID case normalization rather than arbitrary identifiers.
Initial failures, XML, source/APK hashes and final logs are preserved under ignored
`captures/runtime/foreground-appearance-task-*`.

The sole existing 16 KB AVD was stopped/reaped; no physical device was touched.
These are fixture checks, not actual Mac/Iroh/Pixel or full cross-team UI acceptance.
Settings, push controls and remaining selected-scope presentation still need review
before retention is enabled. **Team selection still disconnects the app.** Signed
654 predates this source; full parity and Mermaid pixel acceptance remain unverified.

## Foreground Changes access and reconnect — 2026-10-12

Changes file lists, diffs, stat and fetch requests now prefer the exact admitted
foreground workspace. Requests check client/record/account/capability/descriptor
before and after asynchronous work. Ordinary loss can retain authorized cached
presentation; authorization rejection retires the client and invalidates it.
Main-pane and browser/row Changes actions use this access path, while other Macs
keep their verified feed access.

The modal presentation can rebind to a newly admitted replacement while retaining
its navigation and store. An unchanged foreground does not refresh on recreation;
a healthy feed-backed viewer is not opportunistically upgraded by recreation.
Neither access changes nor cached metadata admit a new connection. Child browser
lifetime guards wrap shared access independently.

**90 focused JVM cases pass**, including five new guarded-wire cases covering all
four read types, descriptor/authority changes, ordinary loss and rejection.
Debug/test APKs build; five changed classes pass Android 17 ART reflection.
**Three runtime cases pass in 39.599s**: foreground Changes with secondary feed
paused, Activity recreation and fresh-client reconnect; selected diff/scroll across
recreation/rotation and retirement on sign-out; and renamed-image Before bytes,
zoom/pan and PDF page retention through recreation without refetching.

Initial failures and screenshots are preserved. The reconnect test initially
observed an old cached page before the asynchronous fresh read; it now waits for
that confirmed read. A preview pixel probe sampled the antialiased center boundary
of a correctly restored green/red pan. The unchanged interior green/red probes,
artifact identity, fetch counts and PDF page checks prove restoration; the redundant
boundary expectation was removed. Successful image and page-2 screenshots were
pulled and visually inspected. Rendering was not changed to satisfy that probe.

Evidence/XML/source and APK hashes are ignored under
`captures/runtime/foreground-changes-access-*`. The existing 16 KB AVD was
stopped/reaped; no new AVD or physical phone was used. These are local fixtures,
not actual Mac/Iroh/Pixel or full cross-team UI acceptance. Signed 654 predates
this source.

Appearance and remaining task/presentation guards still need review before removing
Settings teardown. **Team selection still disconnects the app.** Full parity remains
unverified; Markdown/Mermaid pixel acceptance remains a separate outstanding gate.

## Foreground pane, tab and picker guards — 2026-10-12

Pane recovery, browser discovery, terminal startup expiry, workspace routes and
remembered tab updates now use the admitted Mac's own team context. Activity
restoration uses that live context when available; cold process restoration still
uses the selected team. Local-browser creation and routed sidebar data/actions can
use the individually admitted foreground record without admitting another old-team
Mac. Notification suppression and Changes pane keys follow the displayed Mac.

The connection owner retains its verified Mac record. Warm picker matches and
foreground status require the exact device/build/account/origin/ticket record,
readiness, both code bindings and current permission. A same public locator in
another team cannot borrow the foreground count/status. Explicit selection of a
different record sharing the code advances retry and pins fresh admission. Browser
and sidebar workspace fallbacks also require the exact admitted record, preventing
another same-code row from displaying its data. Metadata alone is not authority;
existing saved/foreground proof and fresh-connection checks remain.

**73 focused JVM cases pass**, including three new exact-record selection/status
cases. Debug/test APKs build and five changed classes pass Android 17 ART reflection.
**Three runtime cases pass in 11.426s**: exact remembered-browser discovery, late
browser discovery respecting a newer terminal choice, and streamed-browser picker
terminal creation in its own workspace. An earlier run lost its process handle
without a test/lifecycle report; no emulator remained, and that run is unverified.
The fresh terminal run provides the authoritative result.

Evidence/XML/source and APK hashes are ignored under
`captures/runtime/foreground-pane-guards-*`. Only the existing 16 KB AVD was used
and stopped/reaped; the physical phone was untouched. Same-locator cross-team cases
are controller tests, not full real-account UI or Mac/Iroh/Pixel acceptance. Signed
654 predates this source.

Appearance and remaining task/presentation/Changes access guards still need review
before removing Settings teardown. **Team selection still disconnects the app.**
Full parity remains unverified.

## Captured foreground disconnect recovery — 2026-10-12

The screen connection now retains the admitted recovery owner and retry generation
through Activity recreation. Disconnect handling identifies the actual client,
login, code and generation instead of requiring the picker to remain on its team.
Replacement handles, login/code changes and newer retry generations ignore stale
failures. The timer rechecks ownership and selected scope before retrying; a picker
change during that delay enters selected-scope recovery. Cold connection entry also
consumes a closed client's recorded failure before starting another attempt.

Admission rejection remains held across a fresh generation of the same team, so
A → B → A cannot erase its failure without explicit retry. Other users/teams/routes
do not inherit that block. New admissions still capture the selected exact scope;
recovery metadata never authorizes a connection.

An encrypted per-user/team bookmark records only a fully admitted foreground's
origin/device/build, without its pairing code or credentials. Following a drop in
a different selected scope, recovery uses that scope's current, unhidden, permitted
saved candidate and normal fresh admission. Missing, replaced, ambiguous or
unpermitted candidates return to the selected team's computer list. Foreground
panes/routes and stale connection ownership retire; saved pairings remain.

**40 focused JVM cases pass**, including new disconnect decisions, candidate history
and same-team rejection-generation coverage. Debug/test APKs build and five changed
classes pass Android 17 ART reflection. **Three runtime cases pass in 11.091s**:
ordinary loss enters a fresh connection and retains the pane; a rejected admitted
foreground stays the same closed handle/generation through recreation until explicit
retry; and a live admitted client survives recreation and still performs RPC.
The first rejection case counted all factory connections and saw the independently
resumed feed. The repaired check explicitly proves the foreground handle, generation,
readiness and rejection block. The original run is preserved.

Evidence/XML/source and APK hashes are ignored under
`captures/runtime/foreground-disconnect-recovery-*`. The existing 16 KB AVD was
stopped/reaped; no new AVD or physical phone was used. Cross-team recovery choices
are controller/metadata tests, not complete real-account UI, Mac/Iroh/Pixel or
process-death acceptance. Signed 654 predates this source.

Remaining tab/sidebar/browser creation and presentation scopes still need integration
before removing Settings teardown. **Team selection still disconnects the app.**
Full parity remains unverified.

## Foreground notification actions — 2026-10-12

Notification reads, read/unread acknowledgements and mark-all actions now prefer
the exact admitted foreground client. Notification navigation and resume/banner
reconciliation accept that Mac's original live scope; saved/cached membership alone
does not authorize it. A navigation acknowledgement is pinned to its captured
client and cannot fall back to another feed connection. Secondary Macs keep their
existing verified feed path. Foreground acknowledgements wake an existing matching
feed without admitting a new connection.

The retained adapter serializes reads and writes, verifies current notification
IDs, and fences snapshots with server revisions. Older post-acknowledgement feeds
get up to three reads without repeating the mutation; they cannot publish old
unread state. Authorization rejection retires the client. Its revision survives
Activity recreation and resets on client/code replacement. Foreground projections
can supply newer notifications while preserving newer feed revisions and exact
saved identity. Selected-team feed filtering remains in place.

**91 focused JVM cases pass**, including seven new wire/failure cases. Debug/test
APKs build, and five changed classes pass Android 17 ART reflection. **Three actual
runtime cases pass in 12.512s**: read and mark-all through the retained client with
secondary feed paused, revision retention without replay through recreation,
Mac-specific banner reconciliation on resume, and late-background reconciliation
rejection with a later resume retry. The first runtime action case completed both
writes but failed its revision assertion because the shared fixture omitted
revisions. Optional versioned fixture responses now exercise that assertion;
the original failure is retained. Production checks were not weakened.

Evidence, XML and source/APK hashes are ignored under
`captures/runtime/foreground-notification-actions-*`. Only the existing 16 KB AVD
was used and stopped/reaped; the physical phone was untouched. These are local TCP
fixtures, not actual Mac/Iroh/Pixel, FCM/Doze or complete team-switch acceptance.
Signed 654 predates this source.

The disconnect handler still ignores a foreground failure when the picker scope
changed. Fix its captured-client recovery ownership, remaining tab/sidebar/browser
creation and presentation guards before removing Settings teardown. **Team
selection still disconnects the app.** Full parity remains unverified.

## Foreground reordering and customization — 2026-10-12

Optimistic workspace moves now accept a live foreground projection and dispatch
through the exact admitted client. The queue retains its three-request bound,
intermediate-order reconciliation, rollback and owner-removal behavior. Fresh
window, destination, pin and group-anchor checks remain. A refresh that consumes
the prediction does not turn its confirmed acknowledgement into a failure; live
connection authority is still checked after the response and refresh.

Customization uses the same admitted client and serialized owner. The existing
field-by-field save/rebase workflow keeps fresh reads, conflicts, truncated
descriptions, partial results and explicit retry behavior. Each read/write checks
owner and capabilities, and authorization rejection retires the client. Editor
keys and browser customization use their Mac's original team context. The last
accepted presentation rows remain available during an in-flight mutation; subsequent
writes independently require a fresh snapshot. Foreground projections retire on
client replacement, disconnect or lost membership/saved-record proof. Feed metadata
is merged only for the same saved identity.

**115 JVM cases pass**, including 13 foreground-wire cases and 14 move-queue cases.
Debug/test APKs build, and five changed classes pass Android 17 ART reflection.
**Three actual Android runtime checks pass in 26.027s**: reordering and terminal
creation on the existing client while the secondary feed is paused, plus offline
editor recreation/reconnect with no automatic edit replay and all four metadata
writes. Evidence/XML/source and APK hashes are ignored under
`captures/runtime/foreground-workspace-editing-*`. The existing 16 KB AVD was
stopped/reaped after 56.161s; no new AVD or physical phone was used. These are local
fixtures, not complete Mac/Iroh/Pixel or team-switch acceptance. Signed 654 predates
this source.

Remaining selected-scope sidebar/browser creation, notification, tab/recovery and
presentation guards still need integration before removing Settings teardown.
**Team selection still disconnects the app.** Full parity remains unverified.

## Foreground workspace and task actions — 2026-10-12

Workspace actions, terminal/browser creation, workspace/group creation and group
actions now prefer the exact admitted foreground client. Task creation also uses
that owner. A retained adapter serializes foreground mutations, resolves current
window/group destinations, keeps capability and attach-ticket checks, and checks
admission before and after asynchronous work. Authorization rejection retires the
client; rejection during the subsequent refresh cannot return success. Each write
reconciles the workspace list, including failed or uncertain outcomes, without
repeating the write. Secondary Macs retain their existing feed path.

Creation requests and saved waiters use their Mac's original team scope. Pane
creation and task/browser destinations use the foreground Mac context. The first
runtime check with the feed paused exposed a completed creation stuck in Ready:
SideEffect read the result without observing it during composition. The navigation
owner now observes that state eagerly, so completion opens the terminal without
waiting for an unrelated feed publication. The original failure and diagnostic
are preserved.

**116 focused JVM cases pass**, including eight new mutation-wire cases; debug/test
APKs build. Four changed classes pass Android 17 ART reflection. **Three runtime
checks pass in 27.548s**: creation on the existing client with secondary feed paused
and restoration without another write; a delayed pre-create refresh cannot remove
the new terminal; and explicit terminal/back navigation survives recreation.
Evidence, XML and source/APK hashes are under
`captures/runtime/foreground-workspace-actions-*` (ignored). Only the existing
16 KB AVD was used and stopped/reaped; no physical phone was touched. Task/group
wire cases are local fixtures, not full physical UI acceptance. Signed 654 predates
this source.

Reorder/customization, notification/sidebar/tab recovery and remaining presentation
scopes still require integration. Settings teardown remains enabled: **team
selection still disconnects the app**. Complete Pixel/Mac parity remains unverified.

## Foreground browser ownership — 2026-10-12

Existing browser tabs and Mac tunnel networks now retain the admitted foreground
Mac's original team generation across picker changes. Secondary networks retire;
pending browser creation is cancelled. The retained owner requires the exact saved
record, current login and live foreground proof. Losing that proof closes the
network, including while the screen composition is inactive.

Foreground browsing uses the existing admitted client's lanes and checks authority
before and after asynchronous work. Browser keys, sidebar selection and pane
rendering use the foreground Mac context. New browser creation and task operations
still require their remaining scope integration; Settings teardown remains enabled.
**The app still disconnects on team selection.**

Verification: **53 focused JVM cases passed**, debug/test APKs built, four changed
classes passed Android 17 ART reflection, and **three browser runtime checks passed
in 9.228s** (retained DOM/history/WebView through recreation, admitted native HTTP
proxy, and failed POST non-replay). The initial run exposed two outdated fixture
expectations: native RPC authorization is supplied by the authenticated admission
handshake, and dynamic text was not found through UIAutomator. Retention now checks
the DOM directly; dynamic accessibility updates remain unverified. Original failure
logs are preserved. The existing 16 KB AVD was stopped/reaped after 37.828s; no new
AVD or physical phone was used. These are local fixtures, not complete Pixel/Mac
acceptance. Signed build 654 predates this source.

Evidence and source/APK hashes: `captures/runtime/foreground-browser-scope-*`.

## Foreground Files and panel access — 2026-10-12

Files and file panels now prefer the exact admitted foreground client instead of
requiring a secondary selected-team feed connection. Existing feed access remains
a recovery fallback. The pane's original Mac/team supplies the panel and Files
keys. Foreground admission checks the exact saved identity, live client, account,
capabilities and current workspace/surface descriptor. A same public locator in
another team cannot reuse that client. Panel requests are limited to the displayed
workspace/surface/path; terminal requests keep their terminal binding and existing
same-Mac chat gallery/folder capabilities. Native artifact lanes still use the
server-issued resource capability after the authorized RPC.

Every transfer checks admission before and after asynchronous work. Descriptor,
capability, visibility, saved-record or authority changes reject old results.
Completed bytes can survive ordinary transport loss under valid saved admission;
explicit authorization/trust rejection invalidates them. A rejected foreground
artifact RPC retires its client. Foreground presentations do not hold a secondary
feed merely to keep their transfer alive.

All **108 focused JVM cases pass** (five new foreground-access, four pane-context,
nine foreground-holder, five cache-policy, 59 feed-coordinator, 13 preview-controller,
nine preview-failure and four Files-memory cases). Debug/test APKs build, and four
changed classes pass Android 17 ART loading/reflection. **Three actual panel runtime
cases pass in 28.458s**: transfer through recreation/removal, rendered reading state
through network loss/recreation/refresh, and private-byte deletion on native and
workspace-refresh revocation. The first runtime run reproduced retained bytes
after explicit refresh rejection. All rejection paths now set the cache-denial
flag; foreground access honors it immediately, and panel composition observes
feed-side denial even when foreground access wins. The original failure is retained.

Evidence, XML and source/APK hashes are ignored under
`captures/runtime/foreground-artifact-access-*`. The existing 16 KB AVD was
stopped/reaped after 60.335s on the final run; no new AVD or physical phone was used.
These use an emulator-local TCP fixture, not actual Mac/Iroh/Tailscale/Pixel or
complete team-switch UI acceptance. Signed 654 predates this source.

Still integrate browser, creation/task, notification and remaining tab/recovery
scopes before removing Settings teardown. **The app still disconnects on team
selection.** Full parity and the physical workflow remain unverified.

## Foreground pane and input scopes — 2026-10-12

The main pane now derives its saved Mac/team from the exact admitted foreground
binding, separately from selected-team computer discovery. The binding query checks
route, device/build, account/team, origin, native route and ticket revision;
renaming is harmless. A same-code record in another team cannot borrow it. The
pane context requires the active client and verified matching login/user/team;
saved/cached records cannot supply continuation, and ambiguous candidates fail.
Picker data remains selected-team scoped. For identical public locators, only the
admitted identity wins in the pane's list; the picker list is unchanged.

Pane navigation, displayed/checkpoint tab identity, workspace snapshot retention
and input/sizing owner retention use this context. Foreground workspace access
checks the exact client/record, while new admissions still use the selected scope.
Terminal input compares the complete computed owner instead of treating missing
account fields as wildcards; the admitted foreground can continue independently
of the picker. Workspace events/polling use the client's connected code, so a late
old-client event cannot fetch under a newly selected locator and mislabel its cache.

All **50 focused JVM cases pass**: four pane-context, nine foreground-holder, five
pane-navigation, ten tab-navigation, eight tab-restoration, 11 input-session and
three retained-connection cases. They include actual verified MockWebServer
membership selection, exact bound-record rejection, same public locators in two
teams, original pane/tab identity, ambiguous/closed/missing-proof contexts and old
event retirement. Debug/test APKs build in 1m25s. Evidence and source/APK hashes are
ignored under `captures/runtime/foreground-pane-scope-*`. These are controller/data
and fixture-wire tests; no emulator or Pixel/Mac workflow was run for this batch.
Signed 654 predates it.

Browser, Files/panel, creation/task, notification and remaining tab/recovery guards
still need their per-Mac contexts. Settings selection and the old selected-scope
teardown remain enabled until those owners work together. **The app still
disconnects on team selection.** Complete team-switch UI and full parity remain
unverified.

## Foreground Activity ownership — 2026-10-12

`NativeFeedSession` now owns the admitted screen connection and sizing subscription
through Activity recreation. Connection data (client/code/readiness, retry admission,
host capabilities/name, notification snapshot and terminal transport) lives in a
memory-only owner. Recreated compositions read that owner; view/touch/scroll callbacks
remain local. Repeated client assignment does nothing; replacement or ViewModel
clear closes only the previous handle and releases its foreground/input/sizing
resources. Cold process restart constructs a new owner and performs fresh admission.

The connection effect reuses a ready, live, still-authorized handle only for its
same code and admitted retry generation. Explicit retry, changed code, lost proof,
closed client or failed readiness follows normal recovery. Terminal replay and
viewport request generations now belong to the retained owner, preventing an old
composition's cleanup from clearing a newer viewport on the same connection.
The sizing event-session job belongs to the ViewModel and handles connection loss.

All **37 focused JVM cases pass** (three connection-state, ten resume, eight
foreground-holder, four recovery and 12 Mac-switch cases). Debug/test APKs build;
the final source build took 57s and Android-test corrections assembled in 21s.
All four changed classes pass Android 17 ART loading/reflection. **Three actual
Activity lifecycle cases pass in 12.688s**: identical foreground client and RPC
following recreation, closure when the Activity finishes, task-draft/background
restoration, and loaded notification-window restoration with the server offline.
The existing 16 KB AVD was stopped/reaped after 40.855s; no new AVD or physical
phone was used. These run against an emulator-local TCP fixture, not the live
Pixel/Mac or Iroh/Tailscale transport. Evidence/source/APK hashes are ignored under
`captures/runtime/activity-foreground-ownership-*`.

The first runtime run had two test setup failures, preserved separately: the new
case enabled a background-feed action without opening a foreground connection;
the existing pagination case used obsolete list keys. It now explicitly opens
and waits for foreground admission, while pagination uses the renderer's current
keys (unchanged from the prior commit). The same three cases then passed.

Per-Mac scopes for panes/feeds/browser/notifications and Settings team selection
still require integration. **The app still disconnects on team selection.** Signed
build 654 predates this source; full parity and physical workflow remain unverified.

## Legacy Tailscale foreground ownership — 2026-10-12

The legacy authority now distinguishes admitted sessions from pending connection
attempts. Only an exact registered, admitted wire can activate a single foreground
reservation under the verified account membership proof. Unknown, forged, pending
and unactivated handles cannot keep a session. The original owner/grant, captured
routing epoch, learned build and saved/external ticket guards remain required.
Selected-scope confirmation and new connections are unchanged. Foreground release
and membership, grant, visibility or routing invalidation retire the original wire.

Each pending admission has an owned child coroutine, including resolver waits.
Retirement cancels invalid attempts and closes secondary sessions without cancelling
the caller. Cancellation at the dispatcher/scope return boundary closes an acquired
client even if its result is discarded. The production screen's reservation hook,
compatibility predicate and saved-ticket guard now select both native and legacy
transport ownership. Legacy route predicates carry their own route authorization;
the authority separately enforces selected scope or exact foreground continuation.
Credential revision caching uses an atomic stamp instead of a monitor around reads.

Initial tests exposed five missing disconnect signals: explicit socket close
suppressed the root event observed by UI/foreground ownership. Retirement now
fails the root wire before disposing its view, delivering the disconnect event.
Original failure XML/logs are preserved. All **175 focused JVM cases pass**: 64
legacy-authority, five common-ticket, 14 external-route, six saved-route, 30
saved-runtime, 37 Iroh, eight foreground-holder and 11 compatibility cases. New
cases verify a manual ticket across verified team selection/return, secondary
retirement, pending resolver cancellation, membership removal/re-addition, grant
replacement, rejected reservations, external and saved ticket guards, and cancelled
result cleanup. Debug/test APKs build in 1m16s. Evidence is ignored under
`captures/runtime/legacy-tailscale-continuation-*`, with source/APK hashes and XML.
These use fixture sockets/credentials and MockWebServer memberships, not actual
Pixel/Tailscale or Mac workflow acceptance. No emulator was started.

Per-Mac panes/feeds/browser/notifications, Activity lifetime and actual terminal UI
still need integration before removing Settings teardown. **The app still
disconnects on team selection.** Signed build 654 predates this source batch.

## Initial native ticket continuation — 2026-10-12

Native `connectTicket()` now attaches the initial ticket through the same bound
client/session guard used for saved tickets. Account, ticket-route, expected-host
and selected-scope admission checks still run before binding. The returned ticket
view can continue only under the runtime's activated original foreground wire.
Failed attachment/binding closes the acquired handle; foreground ownership cannot
admit a new ticket view while that scope is not selected.

External-link admission now has separate confirmation and route-only continuation
checks. Confirmation still requires the selected generation and matching discovery.
Continuation preserves the captured native build/method/epoch or exact Tailscale
grant and routing policy, rather than resolving from the newly selected team's
list. An unresolved cold proposal cannot capture identity through continuation.
These route-only checks grant nothing without the separately bound session and
live runtime ownership. Unspecified continuation preserves the original strict
check. Native wire identity, directory grants, namespace/audience and revocation
remain enforced by its runtime. Ticket expiry/narrowing policies are unchanged.

All **175 focused JVM cases pass**: 14 external-route, 15 ticket-pairing, five
saved/common-session, 37 Iroh, 30 saved-runtime, 58 legacy-authority, eight foreground
holder and eight RPC-ticket cases. A production-runtime fixture uses the actual
attachment factory, retains the original ticket through verified team selection/
return on one wire, and rejects writes after route/foreground loss. Other cases
cover cold confirmation, method round trips, grant replacement, strict fallback
and acquired-handle cleanup. Debug/test APKs build in 1m33s. Exact source/APK hashes,
XML and logs are ignored under `captures/runtime/initial-ticket-continuation-*`.
These use fixture HTTP/credentials/wires; no actual QUIC, Pixel or live ticket
service was tested. No emulator was started. Signed 654 predates this source.

The Tailscale initial/external path still invokes its strict legacy authority:
its transport, saved-route and learned-route guards need foreground integration.
Per-Mac panes/feeds/browser/notifications and Activity ownership still need work
before removing Settings teardown. **The app still disconnects on team selection.**

## Saved primary-ticket continuation — 2026-10-12

`connectSaved()` now binds its primary-ticket guard to the one returned client.
Before binding, admission requires the exact selected scope. Afterward it can use
the native runtime's activated foreground ownership for that original account/Mac
wire. The guard checks authorization before and after its saved-record read; an
old foreground exception cannot bind a new handle or authorize a reconnect.
Binding failure closes the returned ticket view and underlying acquired handle.

The original owner, saved ticket revision, visibility and route binding remain
checked by `NativeAttachTicketStore`. The captured routing choice and configured
audience remain required. Revision caching now uses an atomic stamp instead of a
monitor around credential reads, and rejects changes during revalidation. Existing
attach-ticket expiry, narrowing, account fallback and mutation policies are
unchanged; this continuation cannot mint or widen a ticket.

All **157 focused JVM cases pass**: four new saved-ticket session, 15 ticket-store,
30 saved-runtime, six saved-route, 58 legacy authority, 36 Iroh and eight foreground
holder cases. The real saved-runtime reservation fixture keeps its original ticket
through verified account selection/return and rejects RPC before transport write
when released. Other cases cover saved record removal/hiding/replacement, logout,
route changes, failed binding, rename and unchanged expiry authority. Debug/test
APKs build in 1m13s. XML, logs and exact source/APK hashes are ignored under
`captures/runtime/saved-ticket-continuation-*`. Account HTTP, credentials and wires
are fixtures; no physical phone, emulator, actual QUIC or live account was used.

External/initial pairing ticket guards and legacy Tailscale still require their
own continuation integration. Per-Mac panes/feeds/browser/notifications and Activity
lifetime must be fixed before removing Settings teardown. **The app still
disconnects on team selection.** Signed build 654 predates this batch.

## Foreground compatibility policy tracking — 2026-10-12

The version gate now keeps tracking an already admitted foreground Iroh or saved
Tailscale wire after selected-team discovery changes. It consults the runtime's
activated exact wire and verified continuation, using a read-only atomic lease
lookup without taking the runtime monitor. A matching pooled borrower refers to
the same authenticated wire; another wire, login/user/team, unactivated or closed
reservation does not qualify. A fresh selection generation in the same verified
account/team can reuse that live wire. New admissions remain selected-team scoped.

Remote policy refresh still retires every borrower of an incompatible retained
wire with `MacUpdateRequired`. Observations and cached picker warnings remain
selected-team scoped; warning restoration cannot register or revive a wire.
Releasing ownership removes its exception, and runtime logout/revocation/route
retirement still closes the transport independently.

All **126 focused JVM cases pass**, including an actual saved-runtime reservation
with verified MockWebServer membership followed by a policy rejection after team
selection, pooled-borrower rejection, stale-admission/cache regressions and exact
Iroh/saved ownership checks. Debug/test APK assembly succeeds in 1m08s. Evidence
is ignored under `captures/runtime/foreground-compatibility-*`. These are fixture
transports, not actual QUIC, Pixel or live policy-service acceptance. No emulator
was started and no physical data was touched. Signed 654 predates this source.

Still integrate saved/external ticket continuation, legacy Tailscale ownership,
per-Mac panes/feeds/browser/notifications and Activity lifetime before removing
Settings teardown. **The app still disconnects on team selection.**

## Saved Tailscale foreground transport — 2026-10-12

Saved native identities routed over Tailscale now have separate selected-team and
foreground ownership. A verified, already admitted pooled client can activate one
foreground reservation through the native runtime facade. Selection retires
secondary wires and pending admissions; returning to the retained team reuses its
wire/manual ticket with a fresh selection generation. Grant/route replacement,
membership removal and logout invalidate continuation. Unknown, forged and
unactivated reservations cannot preserve a client. Settings construction occurs
outside the runtime monitor; a selected-team configuration failure reports an
error without retiring another team's valid foreground.

All **109 focused JVM cases pass**: 28 saved-route, 36 Iroh, 15 pool, eight
foreground-holder and 22 account cases. Debug/test APKs build in 1m16s. Original
logs, final XML and source/APK hashes are retained under ignored
`captures/runtime/saved-tailscale-continuation-*`. These use fixture wires and
MockWebServer account membership. No emulator or physical phone was used for this
batch, and no new AVD was created.

This is transport ownership support. Existing ticket guards, legacy pairing,
compatibility tracking, per-Mac pane/feed/browser/notification state and Activity
ownership still need integration before removing explicit Settings teardown.
**The app still disconnects on team selection.** Signed build 654 predates these
saved-route changes; neither live Tailscale nor full UI continuation is verified.

## Signed integration milestone 654 — verified and published

[Run 38198098744](https://github.com/DocMorphic/cmux-app/actions/runs/38198098744)
completed successfully at `5ea6573c104d9512a3eac091900decefd5ecc3ef`.
[Download signed 654](https://github.com/DocMorphic/cmux-app/releases/download/android-preview-654/app-release.apk).
The published manifest/checksum, CI APK and local verification receipt agree:
277,153,506 bytes, SHA-256
`f41674047e59466b69ad622f9c24f148178c114f64e57a6c2ec6c854a8347dec`, unchanged
signer, 21 viewer assets, 22 native libraries with 16 KB ELF/RELRO/ZIP alignment,
14 debug components excluded and the selected Gmail Firebase resources. Six CI
Android 17 ART release probes passed. The Cloud native checkpoint was reused and
verified, not freshly compiled. Evidence is ignored under
`captures/runtime/release-5ea6573/`; its duplicate CI APK was removed after comparison.

This includes the Iroh foreground foundation and configured companion audience,
but predates the subsequent saved-Tailscale source batch. No physical update,
live custom Mac/FCM acceptance or full UI parity is established.

## Configured Mac companion profile — 2026-10-12

The application supports the exact `android-push` / private bundle namespace
alongside public Macs. Compatibility, discovery/presence, cached presentation and
FCM/reply workers consistently select it. Its independent 0.65.0 baseline follows
higher remote protocol floors; Android enrollment identity is unchanged. Public
consumer rules remain available. Unknown development builds, missing/wrong
namespaces and incorrect pinned bundles cannot use this companion profile.

All 84 focused JVM cases, six Android worker cases (8.95s) and seven ART probes
pass; debug/test APKs built in 42s. The existing 16 KB AVD was stopped/reaped and
the Pixel untouched. These use fixture crypto and local storage/HTTP. Live Mac
account/key-store/listener, broker/QUIC, trusted pairing, actual FCM and reply
execution remain open. Signed 652 predates the change. See
[PRIVATE_COMPANION_PROFILE.md](PRIVATE_COMPANION_PROFILE.md). The local audience
blocker below is resolved in source; full parity remains unverified.

## Native foreground transport ownership — 2026-10-12

Iroh discovery now follows the selected team while an admitted foreground wire can
retain its original backend under a verified membership proof. Returning to its
team reuses the endpoint; fresh selection generations still fence admissions and
delayed operations. Other old-team wires/candidates retire. Endpoint teardown is
joined, rejected reservations grant nothing and cancellation cannot leak a returned
client. The foreground holder supplies this reservation hook in production.

All 92 focused JVM cases pass; debug/test APKs build in 1m21s. A new case reproduced
generic EOF hiding a backend auth rejection; its original failure is retained and
the classified rejection now precedes directory cleanup. Tests use fixture wires
and verified MockWebServer memberships, not actual QUIC or live accounts.
Saved/legacy Tailscale, compatibility/pane/feed/notification/lifecycle ownership and
Settings teardown remain open: the app still disconnects on selection. See
[TEAM_SWITCH.md](TEAM_SWITCH.md). Signed 652 predates this batch.

Ten changed debug classes pass Android 17 ART loading/reflection on the existing
16 KB AVD, which was stopped/reaped. No new AVD, install or app clear. This checks
the compiled classes, not retained native UI or physical pairing.

## Custom Mac audience blocker — 2026-10-12

The verified tagged Mac build declares `android-push` and bundle
`com.cmuxterm.app.debug.android.push`. Scoped upstream `MobileHostIdentity.swift`
publishes the launch tag, and `CmxIrohMacBundleNamespace.swift` derives the exact
bundle namespace. Android's immutable consumer policy accepts only default/nightly/
RC tags and official namespaces, including in FCM/reply workers. It will reject
this custom app. Before live pairing, implement an explicitly scoped companion
profile and audit its namespace/identity, version policy, discovery and push paths.
Preserve the existing consumer and account/host checks. No broad development-build
allowance or private companion admission is implemented or verified yet.

## Team-switch authority foundation — 2026-10-12

Verified account memberships now have memory-only continuation stamps separate
from selected-team admission generations. A captured proof survives selection
but retires on logout, account replacement, auth rejection and membership removal;
re-addition cannot revive it. Offline profile caches cannot mint a proof. The
foreground holder captures the actual verified client/Mac scope and supplies that
scope to disconnect ownership, with atomic replacement and exact-handle cleanup.
The existing selected-team `isCurrent()` guard remains unchanged.

This is the authority foundation, not completed terminal retention. Backend
lifetime, Iroh/legacy/Tailscale permission callbacks and per-Mac pane/feed scopes
still need separation; Android continues to disconnect on selection. See
[TEAM_SWITCH.md](TEAM_SWITCH.md). Signed 652 is unchanged and predates this batch.

All 41 focused JVM cases passed on the final source, including two deterministic
concurrency checks. Debug/test APKs built in 55s. All five affected debug APK
classes passed Android 17 ART loading/reflection on the existing 16 KB AVD; the
owned emulator was stopped/reaped after 17.763s. No new AVD was created and no app
was installed or cleared. This verifies the capture foundation and compiler
output, not native backend continuation, Activity/UI or physical workflows.

## Team-switch source audit — 2026-10-12

At scoped upstream `f4b1509054949eaad5d695569ad443c4c18ed68d`, iOS retains the
connected foreground Mac while invalidating pending scope work and old-team
secondary subscriptions/lists. The root starts replacement stored-Mac recovery
only while disconnected. Android still tears down the complete selected-team
runtime, tickets and pane ownership, in addition to its explicit Settings closes.
This is a confirmed unimplemented parity gap. [TEAM_SWITCH.md](TEAM_SWITCH.md)
records the source sinks, affected production owners, implementation order and
acceptance matrix. Removing just the Settings closes or globally weakening
`isCurrent()` would leave incorrect transport/operation ownership. The subsequent
authority foundation above does not establish transport/UI retention acceptance;
the global source pin remains unchanged.

## Signed Android milestone 652 — verified and published

Manual [run 38188383241](https://github.com/DocMorphic/cmux-app/actions/runs/38188383241)
succeeded at `0445cab230770bed5540f6a9c477280c19f879a3` and published
[preview 652](https://github.com/DocMorphic/cmux-app/releases/tag/android-preview-652).
Independent downloaded-byte verification matches published source/version/digest,
size, checksum and the CI receipt. The stable signer is unchanged; all 21 viewer
assets, 22 native libraries with 16 KiB ELF/RELRO/ZIP alignment and exclusion of 14
debug components pass. Compiled Firebase resources match the Gmail-owned client.
CI passed JVM/APK gates and six Android 17 ART class-loading probes; Cloud restored
and verified the matching native checkpoint without recompiling it. See
[PIXEL_INSTALL.md](PIXEL_INSTALL.md) for the exact hash, download and evidence.

This includes the subsequent Settings/setup/auth-recovery batches. No Pixel update
or live account/pairing/FCM acceptance is established. No local AVD was started.
The separately running custom Mac app still awaits completed sign-in after its
initial browser attempt timed out; the existing owner question remains pending.
No helper/native authority or full parity is implied by either verified artifact.

## Custom Mac artifact and local launch — 2026-10-12

Hosted retry 38180269049 succeeded: full tagged build (67m42s), packaging and upload.
Downloaded bytes independently match the archive receipt and all 22 current
reviewed patch hashes. Local deep signatures, ARM64, isolated ID and production
routing pass. The 0.65.0 ad-hoc app launched with the private owner paths through
the checked isolated launcher; no process was stopped or official state copied.
Its actual UI is signed out, and the production sign-in pane is open. The owner
is asked to sign in there with the Pixel's cmux account/team and reconnect the
phone. Native Keychain/account authority, helper startup/enrollment and real FCM
remain open. See MAC_NOTIFICATION_BUILD.md for exact archive/source hashes and
runtime scope. Signed Android 650 remains published; full parity is unverified.

## Authorization recovery — 2026-10-12

The scoped iOS auth sink now maps to Android's normalized host RPC codes/messages.
Foreground auth/terminal failures stop automatic redial and survive Activity
recreation in the retained owner/generation gate. Primary feed failures retire
only that Mac's shared wire and remain parked across pause/name changes until
fresh verified foreground recovery. Core auth errors are not silently swallowed;
network failures still retry, capped at 60s. The Sign Out action is scoped and
retired when its auth-error UI disappears.

87 distinct JVM cases have passing evidence (initial 86/87, corrected explicit
verification case passed separately). Both Android checks passed in 20.132s,
including real recreation with no extra rejected dial, explicit recovery and
terminal input. No crash/ANR records; timeout restored and sole AVD stopped/reaped.
Signed 650/global pin unchanged; physical/full auth acceptance remains open.
See SETUP_HELP.md for source, original expectation failure and exact evidence.

## Highlighted setup guidance — 2026-10-12

Sign-in, disconnected Computers and onboarding now open setup help with the
current durable gate first and a You are here marker. The classifier follows the
scoped iOS precedence: confirmed authentication rejection, signed out, previously
paired Mac, then first pairing. It reads saved/hidden history, not discovery or
error-message text. The scoped reauthentication signal clears on verified success
and resets with route/login/team changes; it never grants authority or drives a
connection. Settings keeps its plain reference. Onboarding help restores without
repeating entry/pairing/permission actions and stays open when the Mac becomes
ready; large text places the marker below the heading.

13 focused JVM cases passed. Seven distinct Android cases passed across a
six-case run (61.689s) and the actual font-scale-2 marker follow-up (7.051s),
including host-framed unauthorized rejection, highlighted recovery, reconnect and
actual terminal input. The connected Settings reference case was repeated after
the density fix. Three screenshots inspected, no new crash/ANR records, timeouts
restored and sole AVD stopped/reaped. Signed 650/global pin unchanged; physical
native/account, matched-iOS/localization/TalkBack and full parity remain open.
See SETUP_HELP.md for exact source scope, results and artifact hashes.

## Settings connected computers — 2026-10-12

Settings now has one row per current connected Mac app instance, its actual
opened Iroh/Tailscale method and the existing Computer Details action. Sibling
builds remain distinct; raw sockets/unknown methods are not relabeled. Metadata
follows wire/attach-ticket leases and is cleared on disconnect/reconnect/pause.
All Computers remains available offline. Dispatch rechecks current account/team,
pairing and allowed route; cached actions recheck membership/component lifetime.
Narrow or large-text rows place the method below the name.

All 95 focused JVM cases passed. The initial and final debug/test builds succeeded
(2m59s, 42s); all three distinct Android cases passed in each run (27.083s, 26.33s),
including stale actions, actual font-scale-2 layout, and Settings → legacy Details
→ Settings → live terminal input with pairing/client preservation. Screenshot
inspection prompted the responsive correction and stronger placement assertion.
No crash/ANR records; timeout restored, sole AVD stopped/reaped. Signed 650/global
pin unchanged. Physical native Details/push, matched iOS and TalkBack remain open.
See [SETTINGS_CONNECTIONS.md](SETTINGS_CONNECTIONS.md) for exact scope and hashes.

## Private HTTPS setup and storage cleanup — 2026-10-12

The owner enabled Tailscale HTTPS Certificates. A certificate/key pair was issued
privately and the explicit runtime setup succeeded against the production sealer,
Gmail-owned Firebase clients/provider and independent owner keys. Offline validation
verified the hostname, matching key, validity and public-root chain. Configuration
is 0600 in a 0700 runtime directory; a second setup preserved configuration/key
bytes, inodes and identities. No helper listener, native authority publication or
FCM request was started. The tagged Mac artifact/account/Keychain and real Pixel
handshake/delivery gates remain open; follow the same hosted build retry.

At the owner's request, obsolete staged Codex/cmux-nightly update copies, the Go
build cache and unused Gradle 8.13 caches/daemon records were removed. Active/latest
installers, code, private credentials, current toolchains, captures and the sole
Android AVD were preserved. Free storage rose from about 12 GiB to 25 GiB while
Xcode was downloading; availability can change during installation. Cleanup and
secret-free provisioning receipts are in ignored `captures/runtime/`.

Updated 2026-10-12. **The goal is active and full parity is unverified.** This is
the current completion checklist; dated entries in [PARITY.md](PARITY.md) preserve
the detailed evidence and history. A passing fixture or signed APK does not close
a physical workflow gate. These are work areas, not equal-sized progress units.

Development now targets `main` at the user's request. Implement features in larger
batches; broad regression and signed builds belong at integration milestones and
final acceptance. Scheduled APK builds are opt-in via `CMUX_AUTOMATIC_PREVIEWS=true`
and currently disabled; the upstream watcher is active on main with review issue #2.

## Current checkpoint — 2026-10-11

A manual GitHub macOS ARM64 full-build route is now implemented in
MAC_NOTIFICATION_BUILD.md. It uses the pinned full upstream checkout, reviewed
22-file patch, documented tagged reload and production account/Iroh routing.
Local Xcode/storage no longer need to gate producing the custom app artifact.
The development packager verifies isolated identity, exact source patch and code
signatures, removes runner paths and records provenance. Five packaging-policy
groups and workflow parsing pass. First CI full compilation succeeded (73m49s);
packaging failed on the verifier's incorrect bundle-tag separator. The exact ID
and runner-home daemon locator are repaired; future failed packaging preserves
an explicitly unverified recovery archive. Verified artifact/runtime/physical
acceptance remain pending. See MAC_NOTIFICATION_BUILD.md for original evidence.

The custom Mac notification-pairing window is implemented in the application
menu: release/debug selection, fresh QR, matching key fingerprint, expiry,
copy/paste and cancellation. Native invalidation clears displayed offers. Actual
Swift UI/client/composition typechecks, exact QR pixel decoding, isolated clipboard,
menu reconstruction, Unix failure checks and production helper control pass.
All 13 labels have nine locale entries; the full patched catalog validator passes.
Installer scope is now 22 files / eight audited originals. This is custom-build
source, not a change to the running official app; whole tagged app/Pixel/FCM
acceptance remains pending. See NATIVE_PUSH_PAIRING.md. Signed 650 is unchanged.

Native Mac sender-key lifecycle is now bound into encrypted helper enrollment.
A changed descriptor fences old delivery and renewal, including held OAuth and
helper restart. The running Swift owner refreshes its public descriptor without
requiring a child restart; originals persist before held key preparation. Exact
committed renewal receipts recover across retry/restart while current trust holds.
Four behavioral regression commits preserve the failures. Current Node 22/26 runs
pass 191 of 192 checks with one existing optional skip; actual owner, nine recovery
cases, native mapping/auth/receipt and installer/launch checks pass. Installer scope
remains 17 files / seven pins. APK 650 is unchanged. See NATIVE_PUSH_MAC_KEY.md for
binding, evidence and the remaining actual Keychain/custom-Mac/Pixel/FCM gates.

Native phone pin lifecycle is now integrated into authenticated host snapshots,
helper enrollment/renewal and preparation/provider authorization. Changed native
pins fence the old helper; missing projection retains original work, while explicit
removal denies delivery. Regression `dd625cfa` reproduced enrollment and held-OAuth
delivery without a current pin. The actual upstream peer store and injected Swift
hook compile/execute with synthetic public pins; complete Node 22/26 runs pass
188 of 189 checks with one existing optional reference-decryptor skip. Installer
scope is now 17 changed files / seven audited originals. See NATIVE_PUSH_TRUST.md
for exact evidence and remaining whole-app/Keychain/native-sender-key/Pixel gates.

Signed milestone **650** is now published and independently verified at source
`d34daed53231997ffa68de63233357e6fe98d489`:
https://github.com/DocMorphic/cmux-app/releases/tag/android-preview-650.
It includes worksheet charts and configured Gmail-owned Firebase resources. CI
passed JVM/APK/helper/viewer/signature/alignment and six Android 17 class-loading
checks; the Cloud native check used its verified cache. Downloaded bytes match
published/CI receipts, and local verification independently confirms the signer,
21 assets, 22 native libraries, 14 excluded debug components and exact compiled
Firebase project/sender/application/client-key resources. No actual provider
request, Pixel upgrade or full custom Mac build is established. Exact hashes,
download and install scope are in PIXEL_INSTALL.md; native phone-pin source is
newer than this APK and is built separately on the Mac.

The production native source owner now commits encrypted originals before helper
transmission and retires them only after exact durable receipts. Fresh verified
account/selected-team/Mac scope gates recovery; temporary loss retains work,
confirmed sign-out/team retirement clears it, and stop joins owned storage work.
The installer now changes 16 files against six audited originals.

Regression `b67d7483` reproduced loss of helper source identity after all native
captures were acknowledged. Encrypted context metadata now survives the last
receipt, is committed before host transmission, and is reused only after fresh
matching native authority. It never establishes a lease or renews event expiry.
Nine actual producer→production-helper recovery cases pass on Node 22 and 26,
including SIGKILL, selected-team mismatch/ABA, sign-out, held IO and full-store
backpressure. Eight storage groups, both production-owner runs and current native
mapping/auth/receipt/installer/launch checks pass. Keys/accounts are synthetic;
actual Keychain/full tagged app/FCM/physical-device acceptance remain open.
See NATIVE_PUSH_CAPTURE_STORAGE.md for preserved red/green evidence and next gates.

Helper sender/storage keys are provisioned privately and the sealer compiled.
Eight key-setup cases pass on Node 22/26; repeated setup preserves identity/keys.
Tagged launch configuration loss was reproduced/repaired, with eight executed
launch-environment cases passing. Current installer is 13 files / six pins; four
installer cases and current native syntax/mapping/receipt checks pass. Full Xcode
is absent and Tailscale HTTPS Certificates are requested. No live listener,
configured APK or physical delivery was exercised. TLS/runtime configuration,
whole tagged build and actual account/FCM/Doze acceptance remain open.

Native composition/public descriptor publication now exist in a 12-file installer
against five audited originals. Actual Swift owner→production helper scenarios pass
on Node 22/26, including privacy replacement, authority recovery and joined cancelled
preparation, plus 123 focused JS cases on both runtimes. Stale socket restart and
native tuple team mismatch were reproduced and repaired; selected-team verification/
epoch fences remain. Provision helper keys/TLS, apply/build the tagged full Mac
integration and configured Android, then verify actual account/Keychain/Pixel
FCM/Doze behavior. Standalone checks do not close those acceptance gates. See
PUSH_HELPER_RUNTIME.md.

The production helper entrypoint/private configuration and local owner pairing
control now exist. 122 focused cases pass on Node 22/26, including real runtime/CLI
startup, native authority, descriptor/provider binding and socket/EOF shutdown.
No FCM delivery or full Mac app build is established. The later native batch above
supplies publication/injection. Next: helper keys/TLS provisioning, followed by
tagged Mac/configured Android and physical delivery. See PUSH_HELPER_RUNTIME.md.

Native notification hooks now have a pinned nine-file installer and an implemented
private process supervisor. Actual Swift-owner/Node-child scenarios pass on
Node 22/26, including original-expiry encrypted capture, privacy reap/replacement,
malformed/stalled receipts, fresh-authority recovery, stop and owner-release cleanup.
The initial actor callback SIGTRAP was reproduced (f337e279) and fixed. Earlier
66 Node cases, eight auth projection cases, ten receipt/replay groups and four
installer cases remain recorded in NATIVE_PUSH_SOURCE.md. Full custom app build,
helper keys/TLS/native installation and encrypted persistence through
Mac app death remain open.

The helper lifecycle now composes private native input, encrypted storage, FCM
forwarding and TLS enrollment/maintenance. 101 focused checks pass on Node 22/26,
including nine real TLS assembly cases with injected test encryption/provider.
Fresh native authority gates offers and enrollment; awaited shutdown fences
admission and completes active work before closing keys/stores. Production
native installation and real provider/device delivery are still required; the
entrypoint/owner control are supplied by the later runtime batch above.

Correct-account Firebase project `cmux-app-push-private-20261011` is active with
billing off. Both Android clients and private/CI configuration exist; generated
resources independently match both variants. A limited FCM provider service account
and role exist with one private RSA credential. Explicit private-file loading and
real messaging-scope OAuth/token caching succeeded; fifteen focused loader/sender
checks pass on Node 22/26. No FCM request or real Mac/Pixel delivery occurred.
Production installation/build, keys/TLS and physical delivery remain open.
See FIREBASE_SETUP.md. Signed 642/global parity pin are unchanged.

Signed milestone [642](https://github.com/DocMorphic/cmux-app/actions/runs/38063365533)
at `7147bc62` passed, including the previously failing Cloud native job's cached
checkpoint verification. Its downloaded APK independently verifies the stable
signer, 21 vendor assets, all 22 aligned native libraries and exclusion of all
14 debug fixture components; six Android 17 ART release probes passed. It
includes the task prompt/attachment/prefetch, SSH/Cloud keyboard, Privacy and RTF
batches plus workbook pictures. Public preview publication was disabled.
[PIXEL_INSTALL.md](PIXEL_INSTALL.md) now links the current artifact and exact
SHA-256. Signed-in Pixel upgrade and live Mac acceptance remain open.

The subsequent XLSX chart batch reuses the pinned presentation engine for
worksheet chart parts and shared drawing geometry, with local cache hydration,
typed theme relationships and chart-only/far-range navigation. 55 focused Node
checks passed on Node 22/26 and six reader-state JVM cases passed. Six Android
workbook cases passed in 41.92 s; the stronger chart-paint follow-up passed in
14.698 s with all 11 captures matching 64/64 actual canvas samples. Initial
restoration/stale-capture failures and actual scope are tracked in
[WORKBOOK_CHARTS.md](WORKBOOK_CHARTS.md). Charts are not included in signed 642.
Full Quick Look/physical acceptance and the other completion gates below remain
open; the global parity pin is unchanged. Final crash buffer empty, no new
ANR/crash events; sole AVD/Gradle stopped and reaped, prior screen timeout restored,
no new AVD. The Pixel was absent from ADB for this fixture batch.

## Setup reference follow-up — 2026-10-12

The Settings Set Up Computer reference is implemented in source order with four
gates/network guidance, a fixed Done header, restoration and safe download links.
Actual scale-2 rendering now passes after repairing reproduced Dialog font-scale
loss. The separate production Settings/transport/pairing/terminal-input fixture
flow passed. Two distinct Android cases have passing results across the recorded
runs; no crash/ANR records, sole AVD stopped/reaped and timeout restored. Signed
650 unchanged. Highlighted disconnected/onboarding guidance, matched-iOS styling,
TalkBack/localization and physical workflows remain open. See SETUP_HELP.md.

## Settings screen follow-up — 2026-10-12

Fixed Settings title/Done header and source-ordered groups are implemented. The
inline multi-team picker shows one selection and revalidates cached actions;
account/login/team keys fix a reproduced replacement-row dispatch. 25 JVM cases
and ten distinct Android cases have passing results across the recorded runs,
including actual process death and terminal replay/input after recreation. Initial
failures, selector repairs, source scope and final APK hashes are recorded in
DISPLAY_SETTINGS.md. One existing AVD was used and stopped/reaped; timeout restored,
no crash/ANR records. Signed 650/global pin unchanged. Full connected-Mac/setup/debug
Settings content, matched iOS/TalkBack, physical acceptance and the iOS live-terminal
retention behavior during team switching remain open.

## Settings integration — 2026-10-11

Terminal/Haptics/Display now use full-row single-switch actions, scalable labels,
heading semantics and light checked knobs. Folder taps and missing-file visibility
share the observed safe snapshot, preserve stored keys/defaults and respond to
malformed values/whole-store clearing. Scoped iOS placement is corrected for these
groups and controls. Five JVM and four distinct Android cases passed; the two new
cases passed again with actual knob-pixel verification on the final APK. The
isolated 260 dp/font-scale-2 screenshot was inspected. Both runs have no crash/ANR
events; only the existing AVD was used, stopped/reaped, and timeout restored.
Signed 650/global pin unchanged, Pixel/matched iOS/full Settings/TalkBack remain
open. See DISPLAY_SETTINGS.md for hashes, initial compiler failure and exact scope.

## Completion gates

Full-parity goal active by owner request on 2026-10-20. New audit candidate:
`a5911a1f92f3eeb11c4d7f3e2ef89204718c5009`; implemented/reviewed pins remain
unchanged until reconciliation. See the latest HANDOFF.md and PARITY.md entries.

| Area | What remains | Evidence required to close it |
| --- | --- | --- |
| Chief conversations | Finish the initial capability-gated Android Home/transcript/question port from the Oct 20 audit candidate; CHIEF.md records its source, session/race and Android fixture evidence. | Exact source mapping for list/search, unread state, history/live cursor, messages/parts, send/stop, approval/question actions, Mac/account ownership, transcript UI and navigation/recovery; runtime checks and a supporting real Mac. |
| Agent Feed | Verify the revised reply editor/full-text sheet and broader composer/reader lifecycle and routed sidebar navigation; verify the new source-specific rows, Markdown, swipe triage and Display settings; physically verify all decision/reply families and event/reconnect/revocation behavior. | Scoped source mapping and evidence in [AGENT_FEED.md](AGENT_FEED.md); main-tab transport/UI implementation and fixture checks are not full Feed acceptance. |
| Account, pairing and connections | Verify edited/replacement-grant reconnect on Pixel/Mac; verify the implemented legacy pre-tag identity adoption and authenticated legacy-to-native upgrade on the physical devices (see ATTACH_TICKETS.md). Verify native authorization, ticket/rejected-token recovery, saved-method guards and optional relay hints against actual hosts; exercise account/team changes, Iroh/Tailscale/SSH recovery and network transitions. | Pixel/Mac runs covering first pairing, reuse, expiry, revocation, logout, host restart, phone process death and Wi-Fi/mobile-data transitions. Preserve existing credentials and workspaces. Record host capabilities and APK/source versions. |
| Terminal and input | Finish real Gboard/hardware-keyboard, TUI, selection/copy/paste, resize, background/foreground and reconnect acceptance. | Visible Pixel output/input checks against disposable Mac terminals, including independent input/output lanes and recovery without lost or duplicated commands. Recheck the recent protocol changes, even where an older build passed. |
| Workspace, task, search and browser flows | Finish physical acceptance of sidebar/navigation, task creation/attachments/drafts, notifications/search destinations, browser gestures/dialogs/downloads; check large-list paging/autoscroll and slow hosts. | Successful end-to-end Mac operations plus lifecycle/rotation and failure recovery. Verify drafts, selections and nested destinations survive the lifecycle events supported on iOS. |
| Files, Changes and content viewers | Finish the remaining format/menu comparisons and modal/binary preview restoration, including native errors outside RPC and broader real-route retry acceptance, directory/rename/read/export/decoder failure coverage, live panel-kind changes, and broader main Files/direct-tap/transport restoration, forced browser-parent recreation with binary content, rendered-Markdown reflow and real-route/process recovery, broader Save/provider recovery and remote-file freshness semantics, video acceptance and zoom across aspect-ratio changes. | An explicit supported-format matrix checked against pinned iOS code, visible rendering and file actions on Pixel, and restoration tests that verify the displayed content. |
| Background notifications | The native-owned private helper is running on the new Mac, TLS and phone enrollment are verified, and Firebase-only delivery after process stop is proven. The full-host pairing menu is accepted on run 39429836746, with sign-in/workspace restoration and helper liveness. Verify the cold-tap retention fix and fresh post-update provider delivery on Pixel; finish Doze, token rotation, reply, dismissals and revocation. | Oct 20 HANDOFF.md records exact Mac/APK versions and live provider/worker/Android display evidence. Process-stopped delivery used `am stop-app`, not Android force-stop. Local candidate 703 upgraded the phone with the retention fix; live tap revalidation awaits phone availability. Keep foreground and background transport evidence distinct, and document demonstrated platform differences. |
| Analytics and privacy settings | Verify the implemented Privacy consent section on Pixel/matched iOS; establish the reviewed Android event/server contract and integrate consent/identity retirement with actual uploads; resolve crash/replay provider support and sensitive-content masking. Android currently has no equivalent uploader/recorder. | Source mapping, nine focused gate checks and two passed Android fixtures in ANALYTICS_PRIVACY.md. Accepted Android event contract, identity retirement/transport cancellation and configured runtime acceptance remain required. The scoped iOS proxy only accepts its iOS catalog; backend support is unresolved. |
| Account plan and billing | Verify native plan status and external management; establish Android purchase/restore support with products and server verification. | Source mapping in ACCOUNT_PLAN.md, live authenticated status/browser return, and verified Android purchase/restore-to-entitlement flow. Read-only plan status and pricing links do not establish native purchase parity. |
| Cloud machines and terminals | Finish remaining Cloud source/UI comparison and real-account acceptance of the mounted machine controller, native terminal/WireGuard bridge and shared workspace integration. Verify system-VPN/private-port and lifecycle/recovery behavior. | Source-to-behavior mapping, fixture transport and UI evidence, then authorized real account/Pixel terminal and lifecycle workflows. See CLOUD_COMPANION.md; do not confuse Cloud machine access with Android background push. |
| UI and accessibility | Finish screen-by-screen iOS comparison, keyboard insets, dynamic text, TalkBack, gestures/haptics and performance on Pixel. | Matched-state screenshots and interaction checks for all main screens and sheets; usable enlarged text and accessibility traversal; measured investigation of any remaining freezes/ANRs. |
| Release and updates | Verify an upgrade while signed in; verify the configured Android notice feed across cold-start/offline restart, cookie exchange and What's New; carry an upstream review through a port and chosen preview milestone; scheduled previews remain opt-in during feature-first development. | Stable-signer upgrade preserving account/pairing/settings, actual configured feed and update checks, and one observed upstream-change-to-review/build cycle. The watcher has succeeded on main and created review issue #2; port/build acceptance remains. Scheduled APK builds require `CMUX_AUTOMATIC_PREVIEWS=true` (currently disabled). |
| Final source audit | Finish the broad upstream delta inventory and reconcile every remaining iOS behavior with Android; establish the exact upstream version for the parity release. | A requirement-to-source/test/physical-evidence mapping with no unexplained omissions. Scoped audits at newer commits do not advance the global pin. Document only evidenced, unavoidable platform differences. |

## Earlier implementation and acceptance records

The dated records below describe their respective checkpoints; signed 642 and
the worksheet-chart record above supersede older delivery/test status. They do
not close the broader physical and matched-iOS completion gates.

- The task prompt now uses saved native text and scroll state, with a drag-owned
  viewport retained through keyboard/picker/layout changes until typing or caret
  movement. Native edits are synchronized before save/submit/lifecycle retirement
  and same-turn image admission; submission reads the current draft snapshot.
  **37 focused JVM cases passed.** The scroll-idle integration passed 22/24 in
  246.883 s; two dock-coordinate failures remain recorded. The final focused
  follow-up passed **12/12 in 156.391 s**, using real accessibility actions for the
  Options and response/retry logic checks while retaining touch/IME-layout checks.
  The production APK is unchanged between those two runs. Inspected captures show
  retained line-28 viewport, caret-following after typing and the full task form.
  Final crash buffer empty, zero new ANR record; sole AVD/Gradle stopped and reaped,
  timeout restored, no extra AVD. See `TASK_COMPOSER_PROMPT.md` for exact APKs and
  failed attempts. Manual-viewport fixture acceptance is now closed; actual
  Activity/process/font-width reflow, physical/matched iOS and broader composer
  acceptance remain open. Signed 641/global parity pin unchanged at this checkpoint.

- Initial task keyboard focus now waits for the resumed, placed, current editor
  and focused window; the request is consumed per presentation. Prompt selection
  survives agent changes, Options and saved-state restoration. Explicit IME text
  paste now uses ordered edits, fixing a selection race in the installed Compose
  delegate. **12 JVM and eight Android cases passed**, Android runtime 93.581 s,
  including the two attachment cases below. Captures inspected; final run had
  zero new crash/ANR records. See `TASK_COMPOSER_PROMPT.md` for corrected failures
  and exact APKs. Sole AVD/Gradle stopped, sleep timeout restored, no extra AVD.
  Manual viewport/scroll was subsequently checked as described above; actual
  Activity/process restoration, physical/matched
  iOS and broader task acceptance remain open. No Pixel or signed release;
  signed 641 and the global parity pin are unchanged.

- Task attachment selection now stages the prefix fitting the remaining count,
  blocks pickers when full, and continues past recoverable aggregate-size
  rejections while preserving later smaller files. Ownership/cancellation/durable
  writes remain authoritative. **21 focused JVM checks passed**; two new Android
  cases compiled at that checkpoint and now pass in the task-prompt integration
  above. See `TASK_ATTACHMENT_STAGING.md`. The original batch used no APK/emulator;
  physical and broader composer/UI acceptance remain open, signed 641 unchanged.

- Task model discovery now warms all three providers across visible paired Macs,
  with four concurrent slots, a shared five-minute catalog and composer refresh
  joining on the actual RPC wire. Account/connection retirement fences stale
  results; leaving the foreground cancels warming. **26 focused JVM checks passed**
  and main/Android test Kotlin compiled in 14 s. See `TASK_MODEL_PREFETCH.md`.
  Physical composer/multi-Mac/reconnect acceptance remains open; no new signed
  APK or Android runtime run, signed 641 and the global parity pin are unchanged.

- The shared SSH/Cloud direct keyboard now applies armed modifiers to native
  IME deletion and editor Return, and retains one-shot Shift through a pending
  hardware dead accent. **Seven existing JVM checks and four Android screen
  cases passed**, build 65 s/runtime 55.623 s. Production editor/queue plus an
  actual virtual key map verify exact bytes and composition behavior; see
  `TERMINAL_SHORTCUTS.md`. A System Intelligence service ANR was logged, no cmux
  crash. Sole AVD/Gradle stopped and reaped; sleep timeout restored, no extra AVD.
  Physical Gboard/keyboard and live SSH/Cloud acceptance remain open; no signed
  release, 641/global pin unchanged.

- The combined Privacy/RTF Android 17 integration **passed all four cases in
  28.205 s**, with inspected Settings, raster/vector and restored final-marker
  captures. The initial run had an obstructing startup System UI ANR dialog;
  the first passing rerun still captured a blank restored frame. Stronger native
  loading/visual-state/frame and pixel checks now verify actual restored ink/
  paper. No production viewer change was required. Exact attempts/APKs/receipts
  are in `DOCUMENT_FORMATS.md` and `ANALYTICS_PRIVACY.md`. No new crash/ANR events
  in the final run; sole AVD/Gradle stopped, no extra AVD, Pixel absent and signed
  641 unchanged. Full privacy, RTF and matched iOS acceptance remain open.

- RTF binary files now route to a bundled offline reader with authored text/styles,
  safe links and raster/vector pictures. Owned snapshots and RTF/vector/DOM budgets
  precede decoding/mounting; original actions and saved reader state are shared.
  **21 JVM, eight Node DOM/vector cases (Node22/26), seven package-verifier cases
  and 21 pinned vendor hashes passed**; main/test Kotlin compiled. Both Android
  RTF cases now pass in the combined integration above. Tables/advanced features,
  actual routes and matched Quick Look remain
  open. See `DOCUMENT_FORMATS.md`. Debug fixture integration is recorded above;
  no Pixel or signed release, signed 641 unchanged.

- Privacy Settings now persists the iOS-default telemetry opt-out, shares the
  main-process consent owner, retires stale generations and cancels registered
  jobs on revocation/closure. **Nine focused JVM checks passed**; main and two
  new Android cases compiled in 52 s and now pass in the integration above.
  Pixel/matched-screen acceptance remains open. No uploader/identity/
  replay or server was enabled; the UI discloses that this build does not upload
  analytics. See `ANALYTICS_PRIVACY.md`. No Pixel or signed release;
  Gradle stopped, signed 641 unchanged.

- The host push pipeline now has an explicit source replay scheduler, bounded
  timed recovery, safe source/provider shutdown and durable arrival ordering for
  equally due records. **47 focused checks passed on Node26.8.2 and Node22.16.0**,
  including real encrypted SQLite migration/reopen and the existing SIGKILL
  handoff recovery. See `PUSH_DELIVERY.md`. The native source choice/adapter,
  provisioning and actual Android provider/device delivery remain open; this is
  a host implementation batch, not a running notification service or APK release.

- Signed milestone [641](https://github.com/DocMorphic/cmux-app/actions/runs/38045004590)
  at `ddf16db8` **passed**, including cached Cloud checkpoint, policy/helper/JVM/APK,
  signing/package and six Android 17 ART class probes. The downloaded APK also
  independently passed verification against that exact source: stable signer,
  18 pinned viewer assets, 22 aligned native libraries and exclusion of all 14
  debug components. SHA-256:
  `88c088efc1b4d5a2237b40886155f0ca423b4252c12ff2f63af46dded07010d4`.
  It includes the integrated Feed/Cloud/media work but predates workbook pictures
  below. See `PIXEL_INSTALL.md`; 641 supersedes 635 as the current downloadable
  signed Actions artifact. It is not a public release, signed-in Pixel upgrade
  or physical Mac acceptance result. Production push and full parity remain open.

- The XLSX preview now projects embedded raster pictures with DrawingML anchors,
  crop/rotation/flips, safe links and image-only/later-range navigation.
  **47 focused Node checks passed**, and main/test Kotlin compiled in 25 seconds.
  The five-case Android workbook integration **passed in 45.795 s**, including
  painted image/navigation/restoration and four existing XLSX/ODS cases. Initial
  failures were corrected in test paint synchronization and visual-viewport
  coordinates; exact failed attempts, hashes, captures and Google Play Services ANR
  are retained in `DOCUMENT_FORMATS.md`. Charts, other drawing formats/effects,
  off-window automatic-row sizing, physical and matched Quick Look acceptance
  remain open. The existing AVD and Gradle were stopped/reaped; no additional
  virtual device was created. Signed 641 predates workbook pictures.

- The latest debug integration at `5d254f84` **passed 25 Android cases in
  197.697 seconds**: four full-text readers, 12 reply sheets, eight Cloud machine
  flows and one paused-video regression. The strengthened reader/Cloud follow-up
  **passed two cases in 13.889 seconds**. Final-line pixels and the inspected
  screenshot now prove the reader paints line 14,000 and the final marker;
  waiting for Compose to settle corrected the earlier stale capture without a
  production change. Cloud's create-form capture uses the actual app theme.
  See `AGENT_FEED.md` and `CLOUD_COMPANION.md` for exact scope, hashes and startup
  ANRs. The sole emulator and Gradle are stopped; no Pixel/live host or signed
  upgrade was used. Physical/matched iOS acceptance remains open; signed 635
  predates the accumulated features; signed milestone 641 above now includes them.

- Cloud's create form now explains locked size/plan requirements, supplies the
  corresponding upgrade action and supports partial/full expansion. The shared
  Workspaces destination is explained below the machine list. **Six existing
  model/contract tests passed**, and the integration above now covers upgrade
  actions/profile retention with an inspected expanded-form screenshot. Changed
  sheet gestures and physical/matched iOS acceptance remain pending. See
  `CLOUD_COMPANION.md`; no live Cloud operation was used, and signed 635 is unchanged.

- Feed full text now uses a large draggable sheet with the iOS title/Close/loading
  presentation; reply editor spacing, avatar sizes and controls follow the scoped
  source. Cancelled reads and retired/empty IME submissions are fenced. Main/test
  Kotlin compiled, and the integration above now passed both added lifecycle
  regressions and all existing keyboard/reconnect/restoration cases on the
  changed editor. See `AGENT_FEED.md`. No Pixel or signed release for this batch;
  signed 635 is unchanged and Feed parity remains unverified.

- Native video now preserves VideoView's surface-size listener and mirrors its
  dimensions after measurement. The changed app passed the paused recreation,
  audio replacement and fullscreen-return regression. The MPEG-TS diagnostic
  remains **unresolved and opt-in**: its revised H.264/AAC stream reaches the end
  but the emulator reports only the initial dimensions. It is not counted as a
  passing adaptive-video test. See `CONTENT_PREVIEW_LIFECYCLE.md` for exact
  results, startup failures and fixture/code hashes. Physical and matched iOS
  adaptive acceptance remain open; signed 635 is unchanged.

- Paused embedded 3GPP captions now refresh through an independent bounded file
  reader, without starting playback. Seek/selection/player changes invalidate
  older reads, blank cue gaps clear the overlay, and unchanged tracks retain
  playing text. **12 focused JVM checks passed.** All four strengthened runtime
  cases have passing results across scoped runs on the unchanged app APK.
  The pending playing-track case and a new paused-video recreation/audio/
  fullscreen case **passed together in 28.979 seconds**. The stronger actual OS
  PiP-return case **passed in 20.775 seconds**, requiring native video colors
  and French caption pixels while paused at 12 seconds, with speed/mute/tracks
  retained. The earlier black capture preceded rendered-frame evidence; waiting
  for video pixels produced correct paused frames, so the experimental player
  gate was removed. See `CONTENT_PREVIEW_LIFECYCLE.md` for earlier interrupted
  runs, exact hashes and inspected screenshots. Physical codecs/routes,
  matched iOS UI/runtime and the adaptive video-dimension audit remain open.
  Only the existing AVD was used, with no Gradle overlap; both are stopped.
  No Pixel or signed release was used. Signed 635 predates the caption/player
  changes, and media/full parity remain unverified.

- Fullscreen media now uses controls over the fitted video, with playback
  auto-hide, menu/gesture/accessibility guards and a shared expanded PiP layout.
  **The comprehensive video and actual OS PiP-return cases passed together in
  143.871 seconds with software graphics.** Before-release captures and fresh
  accessibility queries isolated the held-touch failure to a stale automation
  tree; the test now also checks painted control pixels. PiP uses settled system
  window bounds and fresh menu nodes, preserving bookmark/French tracks/speed/mute
  through return and recreation. Earlier hardware/normal-graphics black frames
  remain unverified on Pixel. The final paused expanded-player capture lacked a
  caption cue although resumed playback showed it; the caption and paused-frame
  follow-up above supersedes that fixture gap. See `CONTENT_PREVIEW_LIFECYCLE.md`
  for all attempts and remaining media gates.
  The sole emulator and Gradle are stopped; no Pixel or signed release was used.
  Signed 635 predates this batch. Matched iOS UI and physical acceptance remain open.

- Video rotation now preserves paused bookmarks instead of replacing them with
  a destroyed surface's zero position. **Three Android media cases passed**:
  real-video geometry/rotation/fullscreen/background, plus existing lifecycle
  and speed/seek/mute regressions. Five video states have visible pixel evidence;
  screenshots inspected and emulator/Gradle stopped. See
  `CONTENT_PREVIEW_LIFECYCLE.md` for the initial harness error and actual rotation
  failure. The fullscreen overlay batch above supersedes the persistent controls;
  broader codecs, real routes and Pixel acceptance remain open. Signed 635 predates it.

- Cached panels now invalidate on typed native admission rejection, including
  wrapped revocation/trust failures, while preserving valid lifecycle/wire-loss
  reading state. **Eight JVM and three Android cases passed**, followed by a
  stronger visible-text recheck. The actual main route changes Markdown/plain
  file/image/path and returns to Markdown without a title change; old private
  bytes are deleted and image recreation does not refetch. Screenshots and pixel
  checks verified; emulator/Gradle stopped. See `CONTENT_PREVIEW_LIFECYCLE.md` for
  the original loading-frame capture and exact evidence. Physical host updates,
  native revocation and signed upgrade remain open; signed 635 predates it.

- Native file previews now retain typed Irx/Iroh admission, control and HTTP
  failure reasons, including expired or revoked access. Transparent native
  wrappers are inspected with bounds; unknown errors remain generic. **28
  focused JVM checks passed**, including the actual RPC client read-loop route,
  retained selection and explicit retry through a verified replacement with no
  retired-connection request. Main Kotlin compiled; Gradle stopped. See
  `CONTENT_PREVIEW_LIFECYCLE.md`. No APK/emulator/physical run for this batch;
  signed 635 predates it.

- Reply composition now fills the sheet and supports interactive keyboard
  dragging while retaining quote/draft state. Native view release and disposed
  editor callbacks are fenced; unchanged edits no longer copy a stale modal.
  **All 11 Android reply-sheet cases passed**, including exact reconnect offset,
  held-drag/hide/reopen, long quote restoration and account changes. Screenshots
  inspected; emulator/Gradle stopped. See `AGENT_FEED.md` for the earlier
  intermittent failures and final evidence. Signed 635 predates this batch;
  physical Pixel/Mac acceptance remains open.

- The reported Cloud native workflow was rechecked. A fresh standalone run on
  main `0b58a302` **passed**:
  [38001133689](https://github.com/DocMorphic/cmux-app/actions/runs/38001133689).
  It verified 756 artifacts / 410 notice components and uploaded the source-keyed cached checkpoint;
  no fresh native compilation occurred. The last failed standalone run remains
  `37449713438`, fixed by `819b6943`. Signed
  integration 635 also passed. See `CLOUD_COMPANION.md`; physical Cloud
  acceptance remains open.

- Feed completion previews now collapse Unicode whitespace like the scoped iOS
  source, including nonbreaking/em spaces, and reuse normalization across unchanged
  snapshots. Each main/browser presentation retains only its current stop reasons.
  **54 focused JVM checks passed**, including reply preservation, exact Mac/build
  separation, browser row/badge consistency and snapshot removal. Main Kotlin
  compiled; Gradle stopped. See `AGENT_FEED.md`. No APK/emulator or physical run
  for this batch; signed 635 predates it.

- Image zoom now preserves the viewed focal point when rotation changes
  aspect-fit letterboxing. **Nine JVM checks and the expanded Android gesture
  case passed**, including landscape recreation, portrait return, reset and
  image file actions. Screenshots inspected; emulator/Gradle stopped. See
  `CONTENT_PREVIEW_LIFECYCLE.md` for the original failure and startup ANRs.
  Signed build 635 predates this fix; physical acceptance remains open.

- Signed milestone [635](https://github.com/DocMorphic/cmux-app/actions/runs/37922453797)
  at `5888cd68` **passed**: Cloud native checkpoint, unit/APK build, signed-package
  verification and six release class-loading checks on Android 17 ART. The
  downloaded stable APK also passed local verification: stable signer, 22 native
  libraries with 16 KiB alignment, 18 pinned viewer assets and exclusion of all
  14 debug fixture components. SHA-256:
  `a134a4d1232f774efe4f2268859af31cb83e425a5c75c288ec519904fc23e742`.
  Evidence: `captures/milestone-635/`. This supersedes 616 as the last verified
  downloadable signed Actions artifact; it is not a GitHub release or physical
  Pixel upgrade/acceptance result. The preceding 633/634 failures below are resolved.

- Interactive keyboard dragging now works in the question answer sheet, following
  the iOS source. Three existing Android regressions passed; the new held-drag,
  hide/reopen and draft-retention case passed after correcting its animation
  measurement. Screenshots inspected; emulator/Gradle stopped. See `AGENT_FEED.md`.
  Physical Pixel/Mac verification remains open.

- Run 634 passed signing and all native alignment checks, then exposed a second
  stale native inventory in the notice-engine verifier. Both verifiers now share
  the reviewed app-module manifest plus the pinned GeckoView library list.
  **Seven verifier tests and the actual local APK's engine/notices/extension
  check passed.** The corrected full signed CI run 635 subsequently passed,
  including release ART checks; see the delivery record above.

- Signed milestone run 633 at `7a5ff354` built the APKs and passed its unit-test
  stage, then failed a stale packaging assertion expecting 19 native libraries.
  The verifier now checks the exact 22-library arm64 inventory (including Cloud
  adapters), rejects missing/substituted/duplicate libraries, and verifies every
  pinned viewer-manifest entry without the old 17-asset count. **Seven Python
  verifier tests passed; the local debug APK has 22 aligned native libraries and
  all 18 pinned viewer hashes match.** Verification diagnostics will now be kept
  even when the APK check fails. This does not establish a signed release; the
  corrected CI milestone must complete, including release ART checks.
  Follow-up: Ghostty's verified output is now cached immediately after its native
  build, so a later APK verification failure does not discard that reusable work.
  Run 634 started before this cache-policy follow-up at `8de0d9c2`; its later
  notice-inventory failure and correction are recorded above.

- Feed snapshot decoding now runs off the UI thread with cooperative cancellation,
  current-owner/revision admission and live local decision overlays. **82 focused
  JVM checks passed**, including a busy worker with intervening decisions/events
  and a cancelled worker that finishes late. See `AGENT_FEED.md`. No APK/emulator
  run for this batch; physical scrolling performance remains unverified.

- Task picker choices now persist separately for each paired Mac, including
  Stable/Nightly, and restore in fresh tasks and explicit Mac switches. A manually
  chosen folder no longer follows a different Mac; first-handshake input and
  existing drafts remain intact. **36 JVM and four Android checks passed**,
  screenshots inspected; see the per-Mac task picker section in `PARITY.md`.
  No new crash/ANR events during testing; emulator/Gradle stopped. Real Pixel/Mac
  switching remains open. Signed milestone run 633 targets the earlier
  `7a5ff354` source and does not include this batch.

- Interrupted creation now keeps an account/team-scoped notice through terminal
  reconnect, with a saved Dismiss action and the app's dark-grey styling.
  **12 JVM and three actual Android process-restoration tests passed**, followed
  by a final workspace/process/screenshot recheck after the color correction.
  No duplicate create RPCs or crash/ANR events occurred; Android restored the same
  task into distinct UI processes. See `WORKSPACE_SELECTION.md` for exact scope.
  Emulator/Gradle stopped; real Mac/Pixel acceptance remains open; signed 616 unchanged.

- Ordinary native workspace/group-destination and terminal creation now survive
  Activity recreation through a retained session and a saved waiter, without
  replaying a mutation. Leaving the destination rejects late automatic navigation;
  process loss reports an uncertain result. **12 JVM and seven Android checks
  passed** after fixing a stale busy-flag callback caught by the startup regression.
  See `WORKSPACE_SELECTION.md` for scope, initial emulator ANRs and evidence.
  Final run had no crash/ANR events; emulator/Gradle stopped. The process-loss
  follow-up above covers Android restoration; physical acceptance remains open.
  Signed APK 616 is unchanged.

- Native terminal View as Text now copies logical lines through Ghostty, preserving
  wrapped commands without inserting visual-row newlines. Capture/capping/text
  measurement run off-main; pending reads are fenced on replacement/dismissal,
  and the title contrast is corrected. **Six JVM and seven Android checks passed**;
  final screenshot and 16 KB alignment verified, no crash/ANR events. See
  `TERMINAL_TEXT_COPY.md` for fixture corrections, scope and remaining acceptance.
  Emulator/Gradle stopped; no physical run or signed release.

- ODS rich cell spans now retain nested styles, authored whitespace and separate
  links through restoration. Internal addresses are normalized, and self-closing
  empty rows no longer shift later values. **31 Node and four Android checks
  passed**; screenshot inspected, no crash/ANR events. See `DOCUMENT_FORMATS.md`
  for the initial link/row failures, preview limits and remaining format parity.
  Emulator/Gradle stopped; no phone run or signed release.

- SSH/Cloud direct clipboard and IME image paste now return held history to the
  prompt, alongside typing and composer submission. Staging, raw mouse/wheel
  packets, rejected/retired input and late acknowledgements after a newer scroll
  preserve the viewport. **12 input and two screen Android checks passed**;
  see `TERMINAL_PIXEL_SCROLL.md` for scope, corrected test assertions and initial
  emulator boot ANRs. No physical-device run or signed release.

- SSH/Cloud history now holds a native tracked content reference while output
  grows or earlier rows are pruned, preserving the fractional position. Latest,
  reflow, screen changes, expired content and emulator replacement release it.
  **15 JVM, 14 native Android and six app Android checks passed**, including the
  visible held-finger/output case and replacement routing. See
  `TERMINAL_PIXEL_SCROLL.md` for the corrected pruning fixture, local native
  build and scope. 16 KB alignment passed; no crash/ANR events; emulator/Gradle
  stopped. Physical sessions and replay continuity remain open; no signed release.

- SSH/Cloud primary history now uses pixel-precise scrolling; mouse-capturing
  programs retain whole-row wheel input. Grid/screen/ownership changes clear the
  held viewport, reconnect stops gestures, and boundaries stop momentum. **Eight
  JVM and five Android terminal checks passed**, including a painted 3px drag.
  The pending three workbook cases also passed. See `TERMINAL_PIXEL_SCROLL.md`
  for the initial regression, fixture corrections and remaining running-output
  anchor/physical gates. No crash/ANR events; emulator/Gradle stopped, no release.

- ODS now respects hidden sheets, collapsed/filtered rows and columns, grouped
  visibility, basic inherited cell styles and authored dimensions. Paging skips
  hidden ranges in both directions. **17 JVM and 25 Node checks passed**; three
  Android cases initially passed before the final paging correction. **All three
  passed again with the paging correction** in the later SSH scrolling batch.
  Earlier app-startup ANRs are retained in the evidence; see `DOCUMENT_FORMATS.md`.
  Emulator/Gradle stopped; no phone install or signed release.

- ODS spreadsheets now open inside the shared workbook viewer with saved values,
  cached formulas, merged/repeated cells, sheet navigation and saved position.
  Expanded repetitions are checked before decoding. **17 JVM, 17 Node and two
  Android workbook cases passed**; ODS screenshots inspected and XLSX regression
  passed. See `DOCUMENT_FORMATS.md` for remaining formatting/hidden-content and
  live-route/Pixel gaps. Empty crash/ANR logs; emulator/Gradle stopped; no release.

- Explicit Firebase client configuration now supports local and optional GitHub
  builds. **Configured and unconfigured SDK checks both passed**, including removal
  without cleaning the previous build; no enrollment/token was created. Both
  variant IDs and invalid-input cases were checked. See `FIREBASE_CONFIGURATION.md`
  for scope and the pretest Play services ANR. Emulator/Gradle stopped, no new AVD
  or signed release. Real project/helper setup, token/worker/device delivery and
  the Mac source decision remain open.

- PPTX now has an offline slide preview in the shared Files/Changes/attachment
  viewer, with previous/next, slide jumps, internal links and saved slide position.
  **25 JVM checks, five Python checks and three distinct Android format cases
  passed**, with a strengthened PPTX paint check rerun and inspected screenshots.
  All 18 vendor hashes match the debug APK. See `DOCUMENT_FORMATS.md` for initial
  stale captures, library boundaries and remaining real-route/physical/Quick Look
  comparison. Empty crash/ANR logs; emulator/Gradle stopped; no signed release.

- Terminal shortcut editor routes now retain unfinished drafts across saved-state
  restoration; Mac and SSH/Cloud sheets save visibility. Removed actions cannot
  be resurrected by stale editors, and unreadable storage blocks their Save.
  **Seven JVM checks and seven Android cases passed** (93.127 s Android), including
  the real app's local-RPC macro flow and previously pending toolbar geometry
  cases. See `TERMINAL_SHORTCUTS.md` for exact restoration scope and remaining
  physical/process/accessibility gates. Screenshots inspected; no crash/ANR events;
  emulator/Gradle stopped. No signed release.

- Actual browser Activity recreation now has a passing Feed composer/reader case
  (**one Android test, 59.992 s**): unsent reply and expanded content survive,
  Source mode stays selected, and screenshots show the reader at the same line.
  The browser DOM survives without reloading. See `AGENT_FEED.md` for test scope
  and pretest Launcher/GMS startup failures; no new crash/ANR events appeared.
  Emulator and Gradle stopped. Process death, large IPC, live reconnect/revocation,
  physical and accessibility acceptance remain open; no production change or release.

- Task failures now distinguish draft-save, upload, host rejection and uncertain
  creation outcomes with the source-style title/message banner and a single polite
  accessibility announcement. Equivalent retries retain operation IDs; failed
  refresh/save does not unlock Start Again. **15 JVM checks and six Android cases
  passed**, three screenshots inspected, no ANR/crash events; emulator/Gradle
  stopped. See the task-failure section in `PARITY.md` for scope. Physical and
  broader accessibility/lifecycle acceptance remain open; no signed release.

- Task submission can now be cancelled during draft persistence or attachment
  upload, while Back stays locked after workspace creation is admitted. Draft
  choices appear after cancellation settles; prior uncertain operation IDs survive
  cancelling an edited request and reverting it. **Six distinct Android scenarios
  have passing evidence across two runs**; the two older recovery fixtures needed
  host-capability discovery before mutation. See the task-preparation section in
  `PARITY.md` for exact scope. No physical workflow, process-death acceptance or
  signed release is established by these checks; emulator and Gradle are stopped.

- Settings now has native personal-plan status, billing-source labels, retry,
  refresh and fixed web/Apple management links. Team coverage avoids personal
  offers, and returning from an opened page refreshes status. **16 distinct JVM
  checks and three Android UI scenarios passed** across the focused builds;
  screenshots inspected, empty ANR/crash logs, emulator/Gradle stopped. See
  `ACCOUNT_PLAN.md` for source mapping and exact scope. Live authenticated account
  and browser verification, plus native Android purchase/restore infrastructure,
  remain open. This does not advance the global parity pin or signed release.

- Open Feed readers/composers retain saved UI through an authorized snapshot's
  waiting state, including restoration while waiting. Closing or changing accounts
  discards the state; reopening starts fresh. **Nine distinct Android scenarios
  have passing evidence across two runs**, including the existing socket-RPC Feed
  flow and three corrected reconnect/lifecycle cases. See `AGENT_FEED.md` for the
  initial test-selector/cursor failures, screenshot limits and exact scope.
  Empty ANR/crash logs; emulator/Gradle stopped. Actual device/network, browser IPC
  reconnect and OS process-death acceptance remain open; no signed release.

- Feed Source view now supports native selection and long messages beyond Compose's
  text-height limit, retaining its character anchor and selection through mode
  switches and saved-state reload without saving full bodies into the Activity Bundle.
  **Five focused Android cases passed**, including composer/reader and browser Feed
  integration. The corrected long-source screenshot check passed after recovering
  from emulator startup System UI ANRs; the final 14,000th line is visibly rendered.
  See `AGENT_FEED.md` for exact runs and retained initial evidence. Emulator/Gradle
  stopped; no physical verification, OS process-death check or signed release.

- Feed reply rows now use the source's tailed quote/reply geometry, compact bar
  references, Reply/Sending/Replied affordances and explicit uncertain-reply
  Try Again/Open Terminal actions. Retrying opens the saved draft without sending.
  See more is now an independent accessibility control; RTL outline-cache and
  punctuation-order issues found in screenshots were fixed. **Nine final Android
  cases passed in 98.483s**, including main socket RPC and the separate browser flow.
  Final screenshots/hierarchy inspected; no ANR/crash events. See `AGENT_FEED.md` for
  repaired initial failures and scope. Gradle/emulator stopped; no physical run or
  signed release. Full locale/TalkBack, composer details and lifecycle acceptance remain.

- The separate browser sidebar now has the shared Feed timeline, questions,
  approvals, reply composer, full-message transport, read/triage actions and native
  workspace/tab navigation. Its searches/filters and main-app Display preference
  projection are wired; issued opaque keys revalidate the current owner and target.
  Cancellation after an admitted write retains delivery uncertainty. **62 focused
  JVM checks passed; four Android cases passed across the final checks**, including
  the actual browser process with no WebView reload and the main socket-RPC flow.
  Screenshot inspected; no ANR/crash events; sole 1,536 MiB emulator and Gradle stopped.
  See `AGENT_FEED.md` for initial fixture-selector failures and exact scope. Remaining
  browser work is broader large-message, process-death, live preference, filter/triage,
  reconnect and accessibility acceptance; no signed APK or Pixel/Mac run in this batch.

- Feed's timeline, row controls, reader and reply sheets now share a display-only
  model and action interface. The main screen resolves actions back to live Mac
  sessions; reused events cannot receive an old read completion. **24 focused JVM
  checks passed; eight Android scenarios passed before that final identity-guard
  refinement**. See `AGENT_FEED.md` for the exact receipts. This is groundwork for
  the separate browser sidebar, whose Feed IPC and navigation are implemented by the newer browser integration above.
  Emulator/Gradle stopped; no signed release or physical-device run.

- Main navigation now follows iOS's default-hidden Notifications tab and persistent
  **Legacy Notifications Tab** Display switch. Hiding the selected tab returns to
  Feed while preserving its draft and both search queries. **Four Android cases
  passed**: settings/remount/defaults, existing Display layout, the main Feed/RPC
  flow, and legacy notification search/navigation. Screenshots inspected;
  emulator/Gradle stopped. Separate-browser Feed and that process's preference
  integration are implemented by the newer browser integration above; see `AGENT_FEED.md`. No signed release or physical-device run.

- Cloud now appears inside the wide workspace sidebar while the selected detail
  remains mounted; compact Cloud retains its full-page flow. Its introduction
  measures the actual column width and gives Back to the visible wide detail.
  **Eight focused Android scenarios passed**, covering Cloud navigation,
  introduction restoration/replay, sidebar state and retained renderer/draft.
  Screenshots inspected; emulator stopped. See `WORKSPACE_SIDEBAR.md` for scope.
  The Cloud native checkpoint also passed on main (`e90154ef`, run
  `37617582848`), restoring and verifying the cached native artifact. Earlier
  failed runs predate the Android errno fix `819b6943`.

- Sidebar viewport/drafts now survive hide/show and compact detail navigation;
  Feed question choices survive primary-tab switches. Account replacement clears
  retained sidebar state. **Four Android scenarios passed**, including the real
  local socket-RPC main Feed flow and Back from its terminal. See
  `WORKSPACE_SIDEBAR.md`. Separate-browser Feed and its
  Feed-replaces-Notifications preference integration are implemented by the newer
  browser integration above; broader lifecycle acceptance remains. Emulator/Gradle stopped; physical acceptance and signed release remain.

- Cloud now matches the source's fresh-input retry after an initial attachment
  failure: newly typed bytes can reconnect, while old unconfirmed input stays
  discarded. The shared composer and ordered lane use one admission rule.
  **17 focused JVM checks passed; the corrected Cloud UI case and nine ordered
  input cases passed on Android**. Two existing Cloud renderer/reconnect cases
  passed in the initial run. Physical daemon/account recovery remains open;
  see `CLOUD_COMPANION.md`. Emulator/Gradle stopped; no signed release.

- Expanded Feed quotes now share one native scroll area with the reply draft,
  preserve inline Markdown, and restore the reading offset after authenticated
  body reload. The native long-paragraph layout stall and text painting over the
  fixed toolbar were fixed. **Six Markdown JVM checks and five final Android
  composer scenarios passed**; final screenshots were inspected. Android runtime
  evidence covers a styled 1.1 MB body; the near-8 MiB check is parser-only.
  Process death, large-font/RTL/TalkBack, touch/IME performance and physical
  acceptance remain open. See `AGENT_FEED.md`. Emulator/Gradle stopped; no signed
  release. This supersedes the continuous-scroll limitation in older entries.

- Feed previews now place See more inline, preserve Markdown and whole graphemes,
  and keep expansion reachable at large font sizes. The composer thread layout
  was fixed after integration testing exposed an intrinsic-measurement failure.
  **Four Markdown JVM checks and four Android scenarios passed**, including full
  quote/reader expansion. Final screenshots were inspected. TalkBack/RTL,
  continuous expanded scrolling and physical acceptance remain open; see
  `AGENT_FEED.md`. Emulator/Gradle stopped, no signed release.

- Feed plan rows now approve directly using the advertised mode and expose all
  source modes in a menu. Reply/revision sheets quote the parent event and keep
  drafts and modal targets through Compose saved-state restoration, waiting for
  an authorized snapshot reload. Reader mode/viewport are saveable; bodies reload.
  **40 JVM cases and five Android scenarios passed**; a stronger native-bounds
  pixel check also verified visible expanded text after early screenshots caught
  late painting. See `AGENT_FEED.md` for receipts and limits. Continuous-scroll
  expanded quotes, remaining visual details and real process/device acceptance
  remain open. Emulator and Gradle stopped; no signed release.

- Feed questions now use iOS-style inline paging, full-width options, custom
  answers, current-page height, saved drafts and complete-answer gating. Request
  changes reject stale answers, and plan revision now sends source-correct
  `manual` mode plus feedback. **34 focused JVM tests** and all three distinct
  Android scenarios have passing evidence (the initial nested-pager selector
  failure was corrected and only that case rerun). Screenshots reviewed; no
  ANR/crash events. The AVD and Gradle are stopped. See `AGENT_FEED.md` for exact
  scope, receipts and remaining composer/reader/sidebar/physical requirements.

- Agent Feed now has source-specific row content, native inline Markdown, brand
  avatars/compact times/quote treatment, Done / Needs Input swipes, accessible
  context navigation and persistent tab/quote display options. **30 focused JVM
  cases pass** and Android test sources compile. Runtime/visual acceptance of
  these new rows was initially pending; the subsequent inline-question batch
  above now supplies initial fixture/visual evidence. Bubble
  geometry, composer/reader presentation/restoration and physical gates remain.

- The upstream audit found the missing **Agent Feed** primary tab. Android now
  implements its verified-Mac subscription/snapshot pipeline, main-tab timeline,
  separate search, read/triage state, permission/question/plan decisions, exact-event
  terminal replies and versioned full-message reader. **78 focused JVM checks**
  passed, debug/test APKs built, and the main-shell Feed flow passed one Android
  case in 108.102 s after a startup-ANR retry of the same APKs. The sole AVD is
  stopped. See `AGENT_FEED.md` for remaining presentation,
  lifecycle, sidebar and physical acceptance gates; no signed milestone yet.

- A real default-process Save worker was killed after a partial 1 MiB write and
  recovered a complete **32 MiB** export in a new process, with exact SHA-256,
  destination reopening and private-copy cleanup verified. The recovery stage
  passed in 28.71 s; no replacement work was enqueued by the verifier. This uses
  an isolated generated provider; external-provider permission changes,
  force-stop/reboot and physical workflows remain open. See
  `CONTENT_PREVIEW_LIFECYCLE.md`. Release checks now exclude all debug component
  kinds and verify all 17 packaged viewer assets. AVD stopped/reaped; no signed build.

- Pending Save and Cancel now pass after an actual isolated preview-process kill
  behind Android's real file picker: **two cases, 55.148 s**, with a new PID,
  Android-restored Bundle/task and exact 204,800-byte saved output after deletion
  of the original. This verifies the existing production Save path; it does not
  close interrupted worker writes, provider revocation or physical/browser-parent
  acceptance. Startup/first-attempt failures are preserved in
  `CONTENT_PREVIEW_LIFECYCLE.md`. The sole AVD was stopped/reaped; no signed build.

- Workbook previews now retain supported inline/shared rich-text formatting,
  including mixed colors, explicit style removal, underline/strike and super/subscript.
  **15 Node checks and the expanded Android viewer case (7.703 s) passed**;
  rendered glyph pixels, internal links and saved-position restoration were
  verified. The sole AVD was stopped/reaped. See `DOCUMENT_FORMATS.md` for source
  mapping, limits and remaining format/physical comparison work. No signed build.

- Terminal composition now preserves selected text and selection direction during
  IME surrounding deletion; hardware Backspace removes a selection or a complete
  grapheme locally. **Six Android checks passed in 24.136 s** on the existing
  Android 17 / 16 KiB AVD, which was stopped/reaped afterward. See
  `TERMINAL_SHORTCUTS.md` for source mapping, APK hashes and the pretest System UI
  ANR baseline. Physical Gboard/keyboard and Pixel/Mac acceptance remain open.

- Automatic browser recovery now passes through the real routed Activity/service/
  SOCKS proxy. Integration found WebView's generic (-1) mapping for tunnel failures,
  now covered by the bounded GET retry. **Three Android cases passed in 39.069 s**
  and three JVM classification cases passed; cookies, history, manual Retry,
  rendered pixels and host release were checked. Failed attempts and a corrected
  fixture timing assumption are documented in `LOCAL_BROWSER.md`. The sole AVD
  was stopped/reaped; actual Pixel/Mac and SSH/Iroh recovery remain open.

- Routed browser GET connection failures now prepare the existing computer route
  and retry once, with Stop/replacement cancellation and no automatic POST replay.
  **Four Android checks passed together in 21.498 seconds**, including visible
  recovery and a bounded persistent failure. The original two failed checks and
  fixes are recorded in `LOCAL_BROWSER.md`. The sole AVD was stopped/reaped.
  Real proxy/SSH/Iroh and Pixel/Mac recovery remain unverified; no signed build.

- Embedded browser video now supports fullscreen with renderer controls and
  lifecycle cleanup. **Two Android checks passed together in 21.602 seconds** on
  the existing Android 17 / 16 KiB AVD, including visible playback, Back, retained
  draft and no reload through recreation. Screenshots were inspected and the
  emulator stopped. Actual routed-process/proxy and physical acceptance remain
  open; see `LOCAL_BROWSER.md`. No signed release was made.

- Real browser-parent recreation with a binary Changes PDF now passes on the
  existing Android 17 / 16 KiB AVD (**one case, 19.344 seconds**). Android relaunch
  events, visible page-two pixels, unchanged request counts, retained artifact/
  sheet ownership and the unsent browser draft establish this specific path.
  Test settings were restored and the emulator stopped/reaped. Media/PiP and Save
  from this route, process death and physical Pixel/Mac acceptance remain open.
  See `LOCAL_BROWSER.md` for evidence and the earlier unsuccessful attempts.

- PiP expansion and return now have Android 17 / 16 KiB runtime coverage for
  position, French audio/subtitles, speed, mute, source recreation, visible video
  and private-copy cleanup. Integration found and fixed backward keyframe snapping
  on resume and black-on-black labels in the expanded/fullscreen player. Two media
  cases passed together; the strengthened visible-frame follow-up also passed.
  The existing sole AVD was stopped and reaped. Browser-process PiP, account
  retirement, wider lifecycle and physical Pixel acceptance remain open. See
  `MEDIA_PICTURE_IN_PICTURE.md` for failures, final evidence and limitations.

- Cloud machine rows and workspace recovery now receive live connection failures,
  including attach/create/input/output errors, with stale-owner rejection and
  current-tunnel subscriptions. Retry includes these failures while preserving
  healthy connections. **47 JVM tests passed**; the new machine-row Android UI
  case subsequently passed in the combined media/Cloud integration run on the
  existing Android 17 / 16 KiB AVD. See `CLOUD_COMPANION.md`; real transport/physical
  acceptance and autonomous-recovery comparison remain open.

- Cloud explicit reconnect now waits for a newer verified catalog before attaching,
  retains the selected renderer and scopes creation failures to their machine.
  Attachment/creation user copy no longer includes raw native diagnostics.
  **32 JVM checks and two Android terminal checks passed** (16.516 s on the existing
  Android 17 / 16 KiB AVD). The emulator was stopped and reaped; no Pixel or real
  Cloud network was used. Real link recovery, service/process recovery and the
  broader iOS audit remain. See `CLOUD_COMPANION.md` for exact evidence and limits.

- Cloud integration now has Android 17 / 16 KiB runtime evidence for the real
  Rust/JNI and WireGuard libraries, machine UI, onboarding, visibility and common
  terminal renderer/input. A repeated terminal check found a closed-Ghostty focus
  callback; retirement now publishes ENDED before releasing the native display,
  with a passing regression. The 15 selected cases have passing results across
  the initial batch and targeted follow-ups, not one clean final full-suite run.
  Four fixture screenshots were inspected; full-shell insets and physical Cloud/
  VPN acceptance remain open. The existing sole AVD was stopped. Details and
  failed attempts are recorded in `CLOUD_COMPANION.md`.

- Cloud machine rows now expose connection failures, immediate retry, lifecycle
  status colors, long-press/overflow actions and non-executing swipe reveals with
  confirmed deletion. Refresh also reaches failed connections, retaining healthy
  links. Stable failure copy replaces raw diagnostics in management/waiting views.
  **41 JVM checks passed**; Android UI sources compile, including two new gesture/
  retry checks. Physical gesture/accessibility/RTL and real recovery acceptance
  remain. See `CLOUD_COMPANION.md` for source mapping and verification limits.

- Accounts with no computers can now reach Cloud from the computer picker and
  return to Workspaces, matching the iOS first-computer tab scaffold. Cloud has
  account-scoped saveable tab state. Locked sizes open their specific plan;
  returning from pricing refreshes machine limits. **25 JVM checks passed**;
  main/Android tests compile. Two new UI checks remain unrun. Native Android
  billing, actual first-machine creation and physical navigation/restoration
  acceptance remain. See `CLOUD_COMPANION.md` for the source mapping and limits.

- Saved VPN restoration is implemented: reuse the existing peer/configuration only
  after the same user/team/login is verified and VPN consent remains granted.
  Sticky service recovery waits for verification; recorded disconnects, account
  replacement and expired permission cannot silently restore. **34 JVM checks
  passed**; debug/test APKs built and all 22 native libraries plus ZIP alignment
  passed 16 KiB checks. Physical service/process recovery, offline startup and
  Tailscale transitions remain unverified; no Pixel is currently visible in ADB.
  See `CLOUD_COMPANION.md` for evidence and recovery limits.

- VPN cleanup drains multiple bounded batches automatically, preserves active
  routes and supports retry while connected. Disconnect intent is persisted before
  awaited shutdown; storage failure still stops the local VPN. The recovery
  checkpoint above extends the earlier 28-check cleanup verification.

- Cloud system VPN is now wired through the official WireGuard backend, protected
  foreground service, shared-account lifetime, status controls and OS consent in
  Cloud management/onboarding. **22 JVM checks passed**; debug/test APKs built;
  all 22 packaged native libraries and APK ZIP alignment pass 16 KiB checks.
  The two backend Android checks and live consent/private-web/Tailscale/background
  flows remain unrun (no Pixel in ADB). Restoration and cleanup scheduling
  are implemented above; exact UI/runtime acceptance remains. See `CLOUD_COMPANION.md`.

- The system VPN lifecycle controller and native account adapter now implement
  explicit enrollment, serialized install/stop, late-operation fencing and durable
  account-owned cleanup. **22 JVM checks passed**; main/Android test compilation
  passed at that checkpoint. Service, notification, app-lifetime binding and
  consent/settings UI are now integrated; physical acceptance remains. See
  `CLOUD_COMPANION.md` for tested behavior and remaining integration details.

- System VPN now has private-route validation and an encrypted profile/peer-cleanup
  journal. **19 JVM checks passed** and main/Android test compilation passed.
  Tests also fixed padded WireGuard public keys in terminal fallback config.
  The official WireGuard AAR passed the supported-ABI 16 KiB gate and is now
  integrated as recorded above. Device acceptance remains.
  See `CLOUD_COMPANION.md` for the precise boundary and implementation sequence.

- Cloud now has the iOS-style three-page first-visit introduction and Cloud basics
  replay, with saved completion/page state, persistence failure handling and
  responsive layouts. Android-specific key/VPN copy reflects actual capabilities.
  Main and Android UI tests compile; the three new UI checks remain unrun without
  a device. System VPN and first-computer entry are now implemented above;
  private-port acceptance, native billing and first-run runtime checks remain open. See `CLOUD_COMPANION.md` for verification scope.

- Cloud composer text now uses the shared encrypted draft repository, partitioned
  by login/user/team/machine/terminal. Sends persist a pending marker before input
  and wait for native transport admission before clearing the submitted revision.
  Interrupted delivery retains text with an unconfirmed warning and never replays
  automatically. **35 JVM checks passed**; Android test compilation passed. The
  renderer fixture and real encrypted-storage/process recovery remain unrun.
  Continue Cloud onboarding/source audit and physical acceptance.

- Cloud now uses the bounded per-workspace last-tab store and a destination-only
  Android saved-activity checkpoint. Restoration waits for a fresh catalog, honors
  account/hidden-machine ownership and yields to explicit navigation. Removed
  terminals fall back safely; only the active Cloud view regains input admission.
  **53 JVM checks passed**, main/Android test sources compile. Real Android process
  restoration remains open; persisted composer implementation is recorded above.
  See `CLOUD_COMPANION.md`.

- Cloud visibility now persists separately per user/team and is controlled in
  Computers. Hidden hosts leave navigation/create menus while remaining available
  to re-enable; hiding the open host closes its terminal and input path. Only a
  successful machine inventory prunes saved IDs. **28 JVM checks passed**, main
  and Android test sources compile; the management fixture remains unrun. Next:
  terminal selection/process restoration, onboarding and live acceptance. See
  `CLOUD_COMPANION.md` and `captures/runtime/cloud-computer-visibility/`.

- Cloud workspace/terminal creation now uses the native daemon and common list,
  picker and routed-sidebar menus. Workspace IDs appear immediately; starter and
  new-terminal selection follow catalog confirmation. Synthetic unassigned rows
  create a real workspace, and failures never automatically repeat mutations.
  **100 focused JVM checks passed**, main/Android test compilation passed; the
  Android menu fixture remains unrun. Next: hidden-machine persistence, terminal
  restoration, onboarding and live acceptance. See `CLOUD_COMPANION.md`.

- Cloud computer selection now uses the common dropdown and survives sidebar
  return/loading/disconnect without broadening to other hosts. Source lists,
  refresh and creation menus respect the Cloud scope; retired Retry callbacks
  cannot reset a replacement account. **84 JVM checks passed**, main/Android test
  compilation passed; the selector fixture remains unrun. No APK rebuild/emulator.
  Next: workspace/terminal creation, hidden-machine persistence, terminal process
  restoration and live acceptance. See `CLOUD_COMPANION.md`.

- Cloud workspaces now mount in the shared list/sidebar and use the common Ghostty
  surface, toolbar, keyboard and composer through account-owned machine hosts.
  **92 JVM checks passed**; debug/test APKs built and 16 KiB ZIP alignment passed.
  The real-renderer fixture compiled but has not run (ADB has no device).
  Next: creation, full computer-picker/scope return, hidden-machine persistence,
  restoration and live Android/native acceptance. See `CLOUD_COMPANION.md` and
  `captures/runtime/cloud-ui-integration/`. Earlier mounting gaps below are
  historical checkpoints superseded by this entry; no full parity claim.

- Cloud's single-slot terminal attachment owner now serializes blocking attaches,
  fences old send/resize/detach by native token, delivers ordered output, buffers
  early input and debounces resize repaint. **25 focused JVM checks passed**, with
  main/instrumentation compilation. It still needs account/renderer/composer and
  navigation mounting; live Android terminal behavior is unverified. See
  `CLOUD_COMPANION.md` and `captures/runtime/cloud-terminal-attachment/`.

- The account-owned Cloud workspace catalog now loads native snapshots with legacy
  list fallback and projects daemon hierarchy into shared workspace/terminal models.
  Running machines wait for the tunnel; disconnects retain rows, failures back off,
  and stale reads cannot resurrect removed/account-retired rows. **22 focused JVM
  checks passed** with main compilation. Shared list/filter/sidebar mounting and
  terminal selection/output/input are the next implementation work; no live transport
  or Pixel acceptance is claimed. See `CLOUD_COMPANION.md`.

- Cloud tunnel lifecycle is mounted in the retained account owner: foreground
  shells with machines keep the tunnel across tabs; background/account changes
  retire links and input before asynchronous native cleanup. Startup is bounded
  to 30 seconds with explicit retry and late-handle disposal. Cloud-first registry
  initialization shares the computer-discovery ID with corruption guards.
  **37 focused JVM checks passed**, main/app/Iroh test sources compile; Android
  identity and live transport checks remain unrun (no connected device). Common
  workspace/terminal mounting is next. See `CLOUD_COMPANION.md` and
  `captures/runtime/cloud-lifecycle/`.

- Cloud native packaging is integrated: both libraries, verifier AAR, keep rules,
  portable Gradle receipt checks, in-app notices and cached/reusable CI checkpoint.
  Native run `37454996751` passed with **756 artifact hashes**, **410 notice components**
  and no missing texts. Debug/test APKs built (1m17s); all **21 native libraries**
  pass alignment and APK ZIP alignment passes. **749 notice assets** match their
  receipt; Cloud binary bytes match after reproducing AGP symbol stripping.
  The real-library initialization/coexistence test compiled but has not run; no
  ADB device was present and a Pixel request is pending. No emulator/signed release.
  Next: account-owned tunnel/session mounting, common workspaces and renderer/input,
  plus physical initialization/DNS/WSS gates. See `CLOUD_COMPANION.md`.

- Cloud's Android DNS/TLS adapter is implemented in the exported native build:
  process-lifetime JNI context, one-time initialization, Android WSS certificate
  verification and the matching verifier AAR. **26 focused JVM checks passed**;
  native run `37452130548` at `e3322507` **passed**. Downloaded hashes and 16 KiB
  alignment are verified. Packaging/notices are completed by the newer checkpoint
  above; device DNS/TLS checks and mounting the account tunnel owner remain open.

- Cloud attach/approval now has an account-owned handshake: parallel native connect
  and bounded invitation polling, prompt cancellation and late-handle disposal.
  Explicit tunnel enrollment uses the existing Iroh registry ID and encrypted Cloud
  identity. **18 JVM checks passed**; main and Iroh instrumentation compilation passed.
  This foundation is not mounted; the Android identity-read case was not run.
  Continue Android initialization, tunnel leases and workspace/renderer integration.

- Cloud's Android C/JNI adapter and Kotlin handle/output ownership now exist,
  including catalog, attachment, input, resize acknowledgments and ordered replay.
  **8 focused JVM checks passed**, main compilation and NDK C checks passed.
  These use a fake native boundary; real Android runtime remains unverified.
  Hosted run `37447383957` failed on a missing Zig PATH; fixed follow-up
  `37447939408` then found Android PTY compile gaps. The builder now patches
  those exported-source cfg/libc cases. Subsequent runs found missing Ghostty source
  layout and errno accessors in two crates; fixed in the driver. Run `37450983946`
  at `819b6943` **passed**, including native/JNI linking, C exports and 16 KiB
  alignment. Downloaded artifacts match all receipt hashes and passed the local
  alignment check. The dependency audit identified required separate DNS and TLS
  initialization and a WSS trust-store adapter; their implementation is recorded above.
  The new native dependency is now packaged, but not mounted in the terminal flow.
  Continue with account-owned tunnel/attachment lifecycle and workspace/renderer
  integration. See `CLOUD_COMPANION.md`.

- Cloud terminal identity/configuration is implemented: an encrypted no-backup
  installation identity, X25519 key, atomic resolve, and server/fallback wg-quick
  completion. **25 focused JVM checks passed** with main compilation (24 seconds).
  The factory is not mounted until the tunnel owner exists; Android Keystore and
  native route checks still need integration coverage. Upstream's Rust/Ghostty
  build lacks an Android target mapping; adapt it before linking the C ABI and
  verifying 16 KiB compatibility. No APK/emulator or live enrollment.
  See `CLOUD_COMPANION.md`.

- Cloud machine management is now mounted in a third primary tab, with a
  retained account/team ViewModel, machine lifecycle menus, delete confirmation,
  size/plan/resource creation controls and real controller actions. **18 focused
  JVM checks passed**, main/instrumentation compilation passed. Three Android UI
  cases are queued, not run. Tutorial, native plan sheet, tunnel/Cloud workspace
  integration, tablet layout and actual account/Pixel acceptance remain open;
  no live Cloud action or APK/emulator. See `CLOUD_COMPANION.md`.

- Cloud machine state now has an owned controller: retained rows, bounded
  refresh/provisioning polling, per-machine lifecycle actions, account retirement
  and durable creation retry identities. **29 focused JVM checks passed** and
  main compilation passed (39 seconds). UI construction, hidden-machine state,
  tunnel/attachment ownership and real account/device acceptance remain open;
  no live Cloud operation, APK or emulator. See `CLOUD_COMPANION.md`.

- Cloud is enabled by the newer iOS production composition and projects its
  machines into the common workspace UI. Android now has the machine/tunnel/
  attach API contracts, typed decoding, bounded cookie-free HTTP and an exact
  account/team adapter. **15 focused JVM checks passed**, with main compilation;
  no live Cloud requests or APK/device run. The controller, WireGuard/Rust bridge,
  navigation/screens and live acceptance remain open. See `CLOUD_COMPANION.md`.

- Shared sizing now sends a stable per-install phone UUID across viewport,
  replay, counts and reattach, following newer iOS device-priority identity.
  Names are bounded/sanitized; the UUID is stored outside backups and persists
  independently of login. **19 focused JVM checks passed**, main compilation
  passed (36 seconds); no APK/device run. Physical priority/upgrade acceptance
  remains. See `TERMINAL_SHARED_SIZING.md`.

- The Cloud composition audit is recorded in `CLOUD_COMPANION.md`; Android's
  API foundation does not yet provide the actual machine/terminal UI. This belongs
  to the broad delta audit, and the global upstream pin remains unchanged.

- Composer Send now requires explicit `submitted: true` before clearing its draft.
  A refused, absent or malformed submit acknowledgment preserves text/files and
  never retries automatically; Insert without Enter retains its existing behavior.
  **18 focused JVM checks passed** and main/instrumentation compilation passed in
  one 28-second run. No APK/device run; include composer acknowledgment UI in the
  next terminal integration batch. See `TERMINAL_INPUT_DELIVERY.md`.

- The terminal shortcut row now follows the newer iOS resting-edge and active-
  gesture geometry behavior, with a gradual leading fade. **17 focused JVM
  checks passed** and final main/instrumentation compilation passed. Two Android
  geometry cases are queued with existing arrow-pad cases for the next terminal
  integration batch; no APK/emulator. Physical gestures, RTL/dynamic text and
  matched iOS UI remain open. This scoped port does not advance the global pin.
  See [TERMINAL_SHORTCUTS.md](TERMINAL_SHORTCUTS.md#shortcut-row-geometry-follow-up--2026-10-06).

- Word and workbook previews now pass **two distinct Android runtime checks**
  on API37/16 KiB, including painted pixels, workbook navigation and composition
  state restoration. Screenshot review found and fixed shrunken spreadsheet
  columns; the strengthened workbook rerun passed. All 17 pinned assets matched
  inside the APK. Gradle and the sole reused emulator are stopped, with no new
  AVD, Pixel install or signed promotion. Real routes/actions, process recovery,
  Word zoom/restoration, broader formats and iOS/Pixel comparison remain open.
  See [DOCUMENT_FORMATS.md](DOCUMENT_FORMATS.md#office-viewer-integration).

- Offline XLSX preview now shares the Office reader with DOCX: sheet navigation,
  author-formatted/cached values, hidden rows/columns, merges, basic styles,
  bounded grid windows and saved reader location. **23 JVM checks and ten Node
  model checks passed**; 17 asset hashes matched. Visual/navigation/restoration
  cases for both Word and workbooks are queued for the next Android integration
  batch. Charts/drawings, broader Excel formatting, other Office formats and
  actual Quick Look/Pixel comparison remain open. No APK/emulator or signed
  promotion. See [DOCUMENT_FORMATS.md](DOCUMENT_FORMATS.md#xlsx-and-shared-office-reader--2026-10-06).

- Offline DOCX preview is implemented in the shared Files/Changes/attachment
  viewer, with bundled rendering, bounded archive preparation, local-only loading
  and existing file actions. **20 JVM checks passed**, main and instrumentation
  compilation passed, and all **16** pinned viewer assets matched. A rich-document
  visual/security fixture is compiled but awaits the next Android integration
  batch; no APK/emulator or signed promotion. Broader Office formats and
  Quick Look-equivalent layout remain open. See [DOCUMENT_FORMATS.md](DOCUMENT_FORMATS.md).

- Combined Android push-settings/storage milestone: **nine runtime tests passed
  in 25.383 seconds** on API37/16 KiB. Four setup/Mac-controls UI cases, two
  key/reply regressions and three real Keystore cleanup-hook cases now have device
  evidence. Three fixture captures inspected; no recorded app crash/ANR. One
  57-second combined APK build plus a 3-second test-only capture build. Gradle and
  the sole reused emulator are stopped; no new AVD or signed promotion. Actual
  WorkManager/Firebase/provider and physical Mac/Pixel gates remain open. See
  [PUSH_DELIVERY.md](PUSH_DELIVERY.md#android-settings-and-cleanup-storage-integration--2026-10-06).

- Source events now have an encrypted durable handoff through preparation and
  delivery admission, with identical-ciphertext recovery after an actual process
  kill in the acknowledgment gap. **24 Node checks passed on Node22/26**. Latest
  cached cmux producer recheck confirms that history alone misses phone alerts.
  The user was asked whether a custom Mac source hook/build is acceptable; that
  source decision is pending. Adapter/scheduler, provisioning and live delivery
  remain open. No APK/emulator/listener. See
  [PUSH_DELIVERY.md](PUSH_DELIVERY.md#durable-source-to-delivery-handoff--2026-10-06).

- The Mac helper components now have a composed forwarding pipeline with live
  host policy and encrypted per-event privacy/identity metadata across restarts.
  **49 Node checks passed on Node22/26**, including privacy tightening during OAuth,
  account/key retirement and atomic fanout. The actual authenticated source remains
  open: native phone alerts can occur without history insertion, so a feed diff
  cannot establish parity. Next: qualifying source/dismissals, durable replay,
  key/TLS/Firebase provisioning and test/device delivery. No APK/emulator/listener.
  See [PUSH_DELIVERY.md](PUSH_DELIVERY.md#forwarding-pipeline-and-durable-privacy-policy--2026-10-06).

- Android Settings now exposes the connected Mac's authenticated forwarding,
  away/always and privacy controls, with event/poll refresh, stale-state recovery
  and account/connection checks at RPC write admission. **Nine JVM checks passed**;
  main/instrumentation compile. One new Compose case awaits the next integration
  run. The official Apple test-alert RPC is not presented as Android delivery.
  Helper source/policy/provisioning, its test action and physical acceptance remain
  open. No APK/emulator. See
  [PUSH_DELIVERY.md](PUSH_DELIVERY.md#authenticated-mac-forwarding-controls--2026-10-06).

- Automatic Android helper maintenance is now wired to token/account/key changes,
  opt-out/logout and recovery. Durable proof/receipt sequencing settles an uncertain
  token update before the next token or removal; encrypted cleanup credentials
  survive account clearing within a bounded window. Settings shows update/repair
  status. **32 JVM checks passed**, including a real local HTTPS/CryptoKit/SQLite
  coordinator flow with lost receipts, newer tokens and logout; **36 Node checks
  passed on Node22/26**. Main/instrumentation sources compile. Keystore/WorkManager
  runtime checks, host provisioning/source/policy and forwarding/test controls,
  Firebase configuration and actual Pixel delivery remain open. No APK/emulator.
  See [PUSH_DELIVERY.md](PUSH_DELIVERY.md#automatic-android-registration-maintenance--2026-10-06).

- Existing helper registrations now have matching Android/Node renewal/removal
  protocols, with phone-key proof, atomic mutation/receipt storage and recovery
  after a host restart. FCM-retired tokens retain bounded encrypted phone trust
  for renewal without receiving alerts. **34 Node checks passed on Node22/26**
  and **19 JVM checks passed**, including real local HTTPS/CryptoKit/SQLite
  enroll → renew → remove. Automatic Android queue/token/logout wiring is the
  next step; production provisioning and device acceptance remain open. No APK
  or emulator. See
  [PUSH_DELIVERY.md](PUSH_DELIVERY.md#authenticated-registration-maintenance-protocol--2026-10-06).

- Native Settings now exposes push consent/readiness, per-Mac pairing progress,
  QR/paste offer review, endpoint/fingerprint confirmation with expiry, cancellation
  and retry. Account/token/key changes fence stale confirmation. **25 JVM checks
  passed**; main/instrumentation sources compile, with three new Compose cases
  awaiting execution. No APK/emulator run. The iOS status/toggle/repair structure
  was checked at the scoped reference in the linked evidence. Firebase/host
  provisioning, automatic renewal/revocation, Mac forwarding/privacy/test controls,
  UI/worker runtime acceptance and real Pixel delivery remain open. See
  [PUSH_DELIVERY.md](PUSH_DELIVERY.md#push-settings-and-offer-confirmation--2026-10-06).

- Android now persists initial helper enrollment before network writes, retries
  the same request/proof after lost responses, and commits a verified receipt with
  its helper pin. Startup/resume/boot recovery and a WorkManager worker are wired.
  **26 JVM checks passed**, including nine new restart/retirement/disk-failure
  cases and the existing real local TLS/CryptoKit/SQLite handshake. Main and test
  sources compile; no APK/emulator run. Android Keystore/worker restart acceptance,
  confirmation UI, automatic token renewal/revocation, production host provisioning
  and Firebase/Pixel acceptance remain open. See
  [PUSH_DELIVERY.md](PUSH_DELIVERY.md#durable-enrollment-recovery--2026-10-06).

- Helper enrollment now has production Kotlin/Node HTTPS transport with bounded
  requests, no redirects/ambient credentials, retry timing and admission checks.
  **13 JVM checks passed**, including a real local TLS/CryptoKit/SQLite handshake
  and repeated finish; **13 Node checks passed** on Node22/26 after fixing a
  timed-out upload capacity leak. Main/instrumentation compilation passed. No APK
  or emulator. Durable recovery was added in the subsequent checkpoint above;
  automatic renewal/revocation, setup UI and real
  hosting/Firebase/Pixel acceptance remain open. See
  [PUSH_DELIVERY.md](PUSH_DELIVERY.md#helper-https-transport--2026-10-06).

- Initial helper enrollment now has matching Node/Android challenge proofs, real
  HPKE key-possession checks, generation-safe registration commit and authenticated
  acknowledgment before local helper pinning. **16 Node checks passed** on Node22/26;
  **15 JVM checks passed**, including a CryptoKit-to-Kotlin fixture. HTTPS transport,
  durable recovery, automatic renewal/revocation and confirmation UI remain open.
  No live enrollment, APK or emulator run. See
  [PUSH_DELIVERY.md](PUSH_DELIVERY.md#helper-enrollment-handshake--source-checkpoint-2026-10-06).

- Android now keeps independent helper sender pins alongside native Mac keys;
  incoming helper pushes reuse delivery/replay handling, while replies remain
  encrypted for the official Mac. Helper retirement fences actions and queued
  replies, including restoration and direct/relay fallback. **54 focused JVM
  checks passed**; main/instrumentation compilation passed. The authenticated
  enrollment handshake/UI, helper service and Firebase setup remain open; no
  real enrollment/delivery or APK/emulator run occurred. See
  [PUSH_DELIVERY.md](PUSH_DELIVERY.md#independent-helper-sender-trust--source-checkpoint-2026-10-06).

- Android now has explicit-consent token acquisition, renewal and durable SDK
  deletion across logout, with generation checks for late callbacks and serialized
  provider operations. Startup/resume/boot/settings and WorkManager recovery are
  wired. **Ten JVM checks passed**; main/instrumentation compilation passed.
  Firebase configuration and the helper enrollment UI are absent, so cloud push
  remains disabled. No APK/emulator was created. Native SDK/Keystore/WorkManager
  runtime and real delivery remain open. See
  [PUSH_DELIVERY.md](PUSH_DELIVERY.md#android-token-lifecycle--source-checkpoint-2026-10-06).

- Zoomed PDF detail integration now passes **four Android checks in 18.481
  seconds**: crop/rotation pixels, cancellation/renderer reuse, visible fine-line
  contrast after magnification/recreation, and XYZ navigation/history. Four
  screenshots inspected; no recorded crash/ANR markers. The final combined APK
  build passed in 39 seconds after a test-only Canvas overload correction.
  Emulator stopped/reaped; no new AVD or signed promotion. Broad pinch/pan,
  overlay alignment, memory and physical acceptance remain open. See
  [PDF_DESTINATIONS.md](PDF_DESTINATIONS.md#detail-rendering-integration--2026-10-06).

- The PDF floating-menu integration now passes **three Android checks in 63.572
  seconds**: unchanged viewport/visible native actions, Select all across
  background/resume, exact copy/clear, cross-page drag/autoscroll/recreation and
  the existing search/copy flow. Four screenshots inspected; no recorded app
  crash/ANR. All actions fit onscreen, so overflow/large-font/TalkBack/high-zoom
  and physical acceptance remain open. One 56-second combined APK build; Gradle
  stopped before the sole existing emulator, which is now stopped/reaped. See
  [PDF_SELECTION.md](PDF_SELECTION.md#floating-menu-integration--2026-10-06).

- PDF selection now uses a native floating Copy/Select all/Select page/Clear menu
  instead of rows that change document height. It follows visible selection bounds,
  hides during scroll/handle drag/copy and retains accessible page actions.
  **11 JVM checks passed** and main/instrumentation compilation passed; the changed
  device test checks stable viewport geometry but is queued for the next combined
  viewer milestone. No APK/emulator was built or launched. See
  [PDF_SELECTION.md](PDF_SELECTION.md#floating-selection-controls--source-checkpoint-2026-10-06).

- Large push dismissals now split into deterministic provider-sized parts with
  complete ID coverage and atomic local queue admission. Late capacity, identity,
  storage or authorization failures cannot admit only a prefix. **61 Node checks
  passed** on Node22.16.0 and Node26.8.2, including actual Mac encryption sizing.
  Source subscription/replay, oversized notify handling and real cloud/Pixel
  delivery remain open. See
  [PUSH_DELIVERY.md](PUSH_DELIVERY.md#large-dismissal-admission-checkpoint--2026-10-06).

- Push event preparation and Mac CryptoKit sealing now feed the existing queue
  contract with redacted/bounded content, independently matched identity and
  original event ID/expiry. **50 Node checks passed** on Node22.16.0 and Node26.8.2,
  including the pinned upstream decryptor opening production-adapter envelopes.
  No cloud/phone delivery is claimed. Host subscription/policy, Keychain lifecycle,
  explicit helper trust/enrollment, oversized event handling, Firebase setup and
  Android token lifecycle remain integration work. See
  [PUSH_DELIVERY.md](PUSH_DELIVERY.md#event-sealing-checkpoint--2026-10-06).

- Push host registration storage and scheduling now integrate with the encrypted
  outbox/FCM sender: encrypted enrollment, token/key generations, exact retirement,
  scope revocation, live-policy checks, retry/expiry scheduling and awaited shutdown.
  **38 Node checks passed** on both Node22.16.0 and Node26.8.2. No cloud resource,
  listener, token enrollment or Android build was created. Firebase project choice,
  authenticated helper enrollment, host policy/subscription/key lifecycle, Android
  token lifecycle and real provider/Pixel/Doze acceptance remain open. See
  [PUSH_DELIVERY.md](PUSH_DELIVERY.md#registration-and-scheduling-checkpoint--2026-10-06).

- Combined PDF content-fit/selection milestone: **16 distinct Android cases now
  have passing evidence**. Initial run: 15/16 in 73.434 seconds. The selection
  fixture expected an offscreen endpoint to remain visible; screenshots retained
  selection/position. A stronger real edge-hold drag proves autoscroll, recreation,
  visible highlights and exact two-page clipboard text; it passed in 28.141 seconds.
  Content-fit pixels/history, four geometry-extraction cases and existing PDF
  navigation/zoom/search checks passed. One 62-second app/test build, then 17/16-second
  test-only builds with an unchanged app APK. Screenshots inspected; no recorded
  crash/ANR markers. Emulator/Gradle stopped, no new AVD or signed promotion.
  Broad selection/format/UI and physical acceptance remain open. See
  [PDF_SELECTION.md](PDF_SELECTION.md#combined-content-fit-and-selection-integration--2026-10-06).

- On-page PDF word/range selection now has highlights, draggable handles,
  cross-page ranges, edge scrolling, Copy/Select page/Select all/Clear, accessible
  endpoint actions and saved offsets. Glyph baselines follow crop/rotation.
  **16 focused JVM cases passed** and main/instrumentation compilation passed.
  Two Android extraction/drag/recreation/pixel/copy cases are compiled, not run;
  combine them with content-fit coverage at the next viewer milestone. See
  [PDF_SELECTION.md](PDF_SELECTION.md). Pixel, broad font/gesture/accessibility
  and large-copy acceptance remain open.

- PDF FitB/FitBH/FitBV now resolve geometric content bounds lazily, including
  vectors, glyph outlines and images, with clipped forms, rotation and explicit
  axis retention. Blank pages fit the page; extraction errors retain the current
  location and show the existing link error. **20 focused JVM cases passed**;
  Android extraction/pixel acceptance remains queued. See the new
  [content-bound feature batch](PDF_DESTINATIONS.md#content-bounding-box-destinations--2026-10-06).

- Connection checklist correction: pre-tag/raw-grant identity adoption,
  background successor refresh, explicit-confirmation upgrade and appearance
  recovery are already implemented. The 20-case identity milestone and later
  appearance milestone are recorded in ATTACH_TICKETS.md. Physical Mac/Pixel
  acceptance remains open. A scoped read at upstream `c2715faa02c260b07012bc0b386597cfb333021d`
  confirms `CMUXMobileRootScene.makeBackedUpPairedMacStore` still returns the local
  team-scoped store without constructing the dormant cloud-backup decorator.
  No cloud-restore behavior is inferred from the unused library. Global pins
  are unchanged.

- Combined media/PDF milestone: **13 distinct Android cases now have passing
  evidence**, including actual PiP video pixels/cleanup, PDF XYZ/FitR pixels,
  recreation/return, pinch focal-point retention, magnified vertical scrolling,
  crop/rotation extraction, search/copy, short pages and Changes restoration.
  Fixed return-control accessibility ownership and a pointer-up event with no
  valid centroid that reset PDF scroll. Final focused run: **3/3 passed**.
  Screenshots inspected; no final crash/ANR markers. Existing emulator and
  Gradle stopped; no new AVD or signed promotion. PiP browser/account/system
  controls, PDF bounding-box fit/selection and physical acceptance remain open.
  See [the integration evidence](PDF_DESTINATIONS.md#combined-viewer-integration--2026-10-06).

- PDF internal-link destinations now carry X/Y and XYZ zoom; the reader applies
  bounded magnification and keeps a saved return history. Restoring search
  results no longer overrides the reading location. Extraction, pixel zoom and
  link/return Android checks passed in the combined milestone above.
  Main/instrumentation Kotlin compilation and **19 focused JVM cases passed**.
  Follow-up shared document zoom makes magnified pages fully scrollable, and
  implements Fit/FitH/FitV/FitR plus null-coordinate retention through crop/rotation.
  Main/instrumentation compilation and **24 focused JVM cases passed** for that
  follow-up. The listed runtime cases now pass; FitB/FitBH/FitBV content bounding boxes,
  selection handles, high-zoom quality and physical checks remain open. See
  [PDF_DESTINATIONS.md](PDF_DESTINATIONS.md).

- Video picture-in-picture feature batch implemented in the shared media viewer,
  with dedicated main/browser playback Activities, independent private file
  lifetime, account retirement, system controls and paused return bookmarks.
  Main and instrumentation Kotlin compilation and **15 JVM cases passed**.
  Both new Android cases now pass, including visible PiP video and file cleanup.
  Browser handoff, broader lifecycle/track retention, system controls, account
  retirement and physical acceptance remain unverified. No additional AVD.
  See [MEDIA_PICTURE_IN_PICTURE.md](MEDIA_PICTURE_IN_PICTURE.md).

- Combined notice feed/UI milestone: **all 11 distinct Android cases now have
  passing evidence** across initial and focused follow-up runs; **22 JVM cases
  passed**. This covers regular/compact/scroll archive layouts, replay without
  acknowledgement, debug launch suppression, short/long/back sheet resizing,
  actual public-feed initialization and saved-ledger recovery with a failed
  fetch, plus visible local web rendering/retry. Fixed TLS cleanup on Main and
  a natural-height cache invalidation that left short sheets full-height.
  Enlarged-text/disabled-animation and landscape variants passed; screenshots
  inspected. Sole emulator and Gradle stopped; no additional AVD or signed
  promotion. Real OS offline/process restart, cookie exchange and physical
  acceptance remain open. See [NOTICE_LAYOUT_REPLAY.md](NOTICE_LAYOUT_REPLAY.md#combined-feedui-integration--2026-10-06).

- The Android-owned public notice feed retains the existing two native IDs, with
  no new release claim or production override. Endpoint maintenance and bounded
  anonymous transport are documented in [WHATS_NEW.md](WHATS_NEW.md#android-owned-announcement-feed--2026-10-06)
  and [feed maintenance](../distribution/README.md).

- Combined composer layout/menu milestone: **12/12 initial Android cases passed**;
  after review corrections **5/5 focused cases passed**, totaling **14 distinct
  passing cases**, plus **six provider/ownership JVM cases**. Native/SSH inline
  Send, multiline cap, IME/rich paste, retry, real photo picker and local provider
  error behavior were checked. Menu ownership now closes stale popups on terminal
  replacement; provider I/O/access errors no longer display raw paths. Screenshots
  inspected; no crash/ANR markers. DEX: 3,511 methods, largest 13,156 code units,
  none rejected. Existing AVD and Gradle stopped. Physical acceptance and the
  broader completion gates above remain open. See [the milestone](TERMINAL_COMPOSER_LAYOUT.md#combined-layoutmenu-integration--2026-10-06)
  and [attachment menu](COMPOSER_ATTACHMENT_MENU.md).

- Combined preview/dictation/media milestone: **all ten distinct queued Android
  cases now have passing evidence** across the initial and focused follow-up
  runs. Fixed visible keyboard return after dictation Send and kept attachment
  errors beside the composer while terminal output remains visible. The task
  fixture now performs authenticated host capability discovery before Create.
  DEX: 3,535 methods, largest 13,156 code units, none rejected. Existing AVD and
  Gradle stopped; no new AVD, Pixel action or signed-release promotion. See
  [the detailed milestone](CONTENT_PREVIEW_LIFECYCLE.md#composer-integration-milestone--2026-10-06).

- Native/SSH composers include live dictation, explicit Send focus and 14-line
  editors. Native/New Task Photos accept photo/video library selections and
  continue past unreadable providers. Preview snapshots survive send completion
  until dismissal or ownership revocation. Their feature batches passed 22, 22
  and 31 focused JVM cases respectively; queued Android integration is now done.
  Real speech/permissions/Gboard, video decoding/cloud selection, picker and
  process restoration, SSH non-image support and physical acceptance remain open.
  See [dictation](COMPOSER_DICTATION.md), [media picker](COMPOSER_MEDIA_PICKER.md)
  and [preview lifecycle](CONTENT_PREVIEW_LIFECYCLE.md).

- Combined composer/appearance milestone: **five distinct Android cases now
  have unassisted passing evidence**, covering appearance acknowledgement recovery,
  task PDF page/file retention through Activity recreation, Open export lifetime,
  native staged image/text previews plus send retry, and SSH preview without
  sending. Initial chooser/provider fixture failures were corrected; visual
  review also caught a loading-frame image capture, and the stronger native
  pixel-check case passed. One app APK build; two test-only rebuilds, unchanged
  app hash. Green PDF, cyan/blue images, text and chip screenshots inspected.
  No new crash/ANR events. Sole AVD stopped/reaped; no new AVD or physical-device
  claim. See [CONTENT_PREVIEW_LIFECYCLE.md](CONTENT_PREVIEW_LIFECYCLE.md#combined-composer-preview-milestone--2026-10-06).

- Native/SSH terminal attachment chips now open the shared full viewer, with
  exact terminal/account/binding ownership. Task and terminal chips use iOS-like
  thumbnail sizes, file labels and separate remove controls. **33 focused JVM
  checks passed**; main/instrumentation compilation passed. Native image/text
  and SSH visible-image checks passed in the combined milestone above (cases in
  [CONTENT_PREVIEW_LIFECYCLE.md](CONTENT_PREVIEW_LIFECYCLE.md#native-and-ssh-terminal-attachment-previews--2026-10-06)).
  Keyboard return, full visual/accessibility, process death and physical-device
  acceptance remain open.

- Task attachments now use the full shared image/PDF/text/Markdown/media viewer
  with filename/Done and Open/Share/Save. Exact staged bytes, retained ownership,
  cancellation/retry cleanup and cache leases replace the image-only popup.
  Preview remains available while task mutation is disabled. **30 focused JVM
  checks passed** and main/instrumentation compilation passed. The two-color
  PDF/Activity-recreation/disabled-editor and Open export-lifetime cases passed
  in the combined milestone above. Physical acceptance, process
  death and the full Quick Look format/visual matrix remain open. See
  [CONTENT_PREVIEW_LIFECYCLE.md](CONTENT_PREVIEW_LIFECYCLE.md#task-composer-attachment-viewer--2026-10-06).

- Authenticated legacy upgrades now preserve custom computer name/color/icon.
  Pairing commits queue the move; atomic appearance receipts make cross-file
  recovery safe through restart, failed acknowledgement and later edits/resets.
  Exact tagged customization wins, sibling builds stay separate, and Forget
  disarms queued work before cleanup. UUID spelling changes retain other edited
  fields. **67 focused JVM checks passed**; main/instrumentation compilation
  passed (16 s final run). The Android Keystore/appearance-reload case passed
  in the combined milestone above. See the dated appearance section in
  [ATTACH_TICKETS.md](ATTACH_TICKETS.md).

- NativeScreen compiler-size obstacle addressed: composition-local state and
  separate feed/terminal, foreground-effect and rendering groups reduce the
  largest debug project DEX method from **44,283 to 13,147 code units**. All
  3,497 associated methods are below ART's size/register guard. **Ten distinct
  Android screen cases have unassisted passing evidence** (initial nine passes
  and one stale filter-selector failure; corrected follow-up passed). No new
  compiler-size warning, crash or ANR; relevant screenshots inspected. One
  17 s app/test packaging build, then test-only rebuilds; sole AVD stopped/reaped.
  This is not a frame-time benchmark or Pixel/Mac acceptance. The new
  `scripts/check-native-screen-size.py` can check future DEX archives without
  packaging an APK. See [SCREEN_COMPOSITION.md](SCREEN_COMPOSITION.md).

- Combined identity-upgrade Android milestone: **20 distinct cases have
  unassisted passing evidence** (17 Details/Keystore cases, two corrected recovery
  flows, and the final explicit re-pair/build/reconnect/visible-terminal case).
  The screen test exposed and verified a same-locator restart fix. Initial stale
  external-link test failures and an IME-assisted run are preserved, not counted
  as clean passes. Final strengthened terminal case passed in 17.638 s and its
  output screenshot was inspected. One 96 s app/test build; subsequent builds
  changed only the test APK. Existing API37/16 KiB AVD stopped/reaped; no new AVD,
  no Pixel/Mac or signed-release acceptance. See `ATTACH_TICKETS.md` and local
  `captures/runtime/confirmed-upgrade-milestone/`. Next concrete observations:
  NativeScreen's compilation-size warning is addressed above; frame-time
  profiling and reconciliation of remaining obsolete raw-launch fixtures with
  actual entry policy remain.

- Fresh confirmation of an older saved Mac now takes the explicit pairing path
  and commits its new address grant and saved identity together. Existing
  unscoped ownership/history survives; revocation, removal, hiding, account
  changes and write failures cannot leave a partial upgrade. The admitted session
  switches to its new grant after commit. **108 focused JVM tests passed**;
  main/instrumentation Kotlin compiled (20 s final run). New Keystore case is
  compiled only. Confirmation UI/session continuity and actual Mac/Pixel upgrade
  remain queued; broader legacy metadata reconciliation remains. No APK/AVD run.
  See `ATTACH_TICKETS.md`, `TAILSCALE_CONNECTION.md` and local
  `captures/runtime/confirmed-legacy-upgrade/`.

- Background workspace feeds and the notification service now persist authenticated
  identity/locator upgrades and retire the captured session before subscribing or
  delivering data. Selection remains on the same computer; foreground reconnect
  follows the scoped successor's history. **104 focused JVM tests passed**;
  main/instrumentation Kotlin compiled (68 s). New Keystore reload case compiled
  only; no APK/AVD/device run. Real foreground/feed/service restart acceptance,
  fresh explicit confirmation against an old untagged grant and broader metadata
  reconciliation remain. See `ATTACH_TICKETS.md`, `TAILSCALE_CONNECTION.md` and
  `captures/runtime/background-identity-refresh/`.

- Old raw Tailscale grants now support authenticated foreground build learning:
  exact address/device and any provisional build are checked, the learned method
  is applied before ticket/workspace admission, and row plus grant commit together.
  History stays intact; the old ticket is discarded and the foreground restarts
  under the new binding before terminal input. **123 focused JVM tests passed**;
  main/instrumentation Kotlin compiled (21 s). New Android Keystore reload case
  is compiled only, queued for the next milestone. No APK/AVD/device run.
  Background-only adoption, fresh confirmation against an old grant, actual
  restart/upgrade and metadata reconciliation remain. See `ATTACH_TICKETS.md`,
  `TAILSCALE_CONNECTION.md` and `captures/runtime/legacy-raw-build/`.

- Combined legacy/Details milestone at `af1d085`: one debug/test APK build (74 s),
  **14/14 Android UI/Keystore tests passed in 80.669 s** on the existing API37 /
  16 KiB AVD. Previously queued raw-only Details and legacy-build reload cases
  are now executed, plus connection display, private-address, ticket and directory
  regressions. Seven screenshots reviewed; no new runtime crash/ANR, final crash
  buffer empty, sole emulator stopped/reaped. No Pixel/Mac or signed-release
  acceptance. See `ATTACH_TICKETS.md`, `COMPUTER_DETAILS.md` and local
  `captures/runtime/legacy-details-milestone/`. The subsequent old-grant build
  learning batch is documented above; its new runtime case remains queued.

- Sole-build directory enrichment now supplies provisional native routes for
  untagged native and raw saved Macs. Details/method selection and foreground
  reconnect use the exact scoped build; host authentication still supplies the
  final tag. Existing tickets cannot cross a provisional build boundary, even
  with an unchanged source. **104 focused JVM tests passed**; main/instrumentation
  Kotlin compile passed (20 s final run). An initial screen method-size failure
  was resolved by extracting the reconnect-key helper. No APK/AVD. The queued
  Details and Keystore tests passed in the combined milestone above;
  live old-raw-grant build adoption and Pixel/Mac recovery remain open. See
  `ATTACH_TICKETS.md` and `captures/runtime/legacy-directory-routes/`.

- Authenticated legacy build adoption now preserves a captured or sole owned
  untagged computer's draft/notification/selection origins, keeps sibling builds
  separate and removes ticket credentials whose old build binding has changed.
  58 focused JVM tests and main/instrumentation Kotlin compilation passed (23 s).
  The Keystore reload case subsequently passed in the combined milestone above.
  Pre-tag directory route enrichment, raw Tailscale handshake/grant adoption,
  metadata reconciliation and physical acceptance remain. See `ATTACH_TICKETS.md`.

- Saved raw-primary reconnect now uses current exact Mac/build grants after an
  address edit, with captured row/account/method admission and transport-only
  fallback. Old tickets are restricted to their original source; replacement
  destinations use authenticated manual-ticket/account admission. Cold-start
  persistence retains public locator, ticket and history without recreating an
  old grant. **94 focused JVM tests passed**, main/instrumentation Kotlin
  compilation passed (36 s final run). An initial test-constructor compilation
  error was corrected and logged. No APK/emulator; Pixel absent on ADB. Physical
  address-edit/reconnect/notification acceptance remains; see `TAILSCALE_CONNECTION.md`
  and `captures/runtime/edited-tailscale-reconnect/`.

- Scoped Tailscale-only rows now expose Computer Details, saved-route diagnostics
  and shared feed-owned Keep Mac Awake controls without native discovery. Remote-
  confirmed cleanup includes only the captured scoped identity. 46 focused JVM
  tests and main/instrumentation Kotlin compilation passed (20 s final run).
  The new Android UI case is queued for the next milestone; no APK/emulator.
  Main raw-primary reconnect with edited/replacement grants is implemented by
  the following checkpoint; legacy pre-tag adoption and physical acceptance remain. See `COMPUTER_DETAILS.md`.

- Combined connection-route Android milestone: one debug/test APK build (72 s),
  **8/8 ticket/Keystore cases passed in 30.332 s** on the existing API37 / 16 KiB
  AVD at `a803fe0` plus a test screenshot line. External reuse/revocation UI and
  directory-enrichment persistence checks are now executed. All three chooser
  screenshots were inspected. A pre-test System UI ANR was captured and dismissed;
  no new crash/ANR during tests, final crash buffer empty, emulator stopped/reaped.
  No physical or signed-release acceptance. See `ATTACH_TICKETS.md` and local
  `captures/runtime/connection-route-milestone/`. No new virtual device was created.

- Authenticated account discovery now enriches an existing owned Tailscale pairing
  with an unambiguous native identity for the exact device/build. It preserves
  ticket/grants/origin/history and never removes a native pin on an empty or failed
  discovery snapshot. Pending reconnects can adopt only the locator refresh.
  All 55 focused JVM cases pass and main/instrumentation Kotlin compile (17 s);
  the Keystore reload test subsequently passed in the combined milestone above.
  The surrounding upstream caller also corrected the prior Direct checklist:
  fresh in-app exact-address authorization can override the stored Direct choice;
  no new restriction is needed. See `ATTACH_TICKETS.md`. Real upgrade/reconnect
  acceptance and legacy pre-tag identity adoption remain open. Scoped Tailscale-only
  Details is implemented by the newer checkpoint above.

- External legacy tickets now reuse independently authenticated exact Tailscale
  destinations under captured account/build/method authority. External confirmation
  never mints fresh consent; raw transport checks the captured grant during auth
  and I/O. A DNS/multi-route source can acquire a public ticket alias only after
  authentication and atomic original-grant validation. Native cold-launch proposals
  remain available while discovery loads. All 78 focused JVM cases pass; main
  and instrumentation Kotlin compile (17 s). The Compose reuse/revocation check
  subsequently passed in the combined Android milestone above.
  See `ATTACH_TICKETS.md` and `captures/runtime/external-ticket-grants/`.

- Tailscale ticket acceptance now preserves an existing authenticated native
  locator for the same owner/device/build. Automatic/Direct select that native
  route without applying the raw ticket to it; authenticated promotion preserves
  origin/history and retires uncovered ticket context. Raw-grant removal does
  not erase independent native authority. All 85 focused JVM cases pass and
  main/instrumentation Kotlin compile (32 s); no APK/emulator. Physical reconnect
  acceptance, discovery-only legacy upgrade, truly Tailscale-only Details and
  external tickets using stored grants remain open. See `ATTACH_TICKETS.md`.

- Saved legacy Tailscale reconnects now enforce the per-build connection method
  and captured epoch before authentication and during I/O. Direct cannot use a
  retained raw route; Automatic cannot use it when an exact owned native identity
  is retained. Fresh explicit entry stays distinct. Fifty-two focused JVM cases
  pass; main/instrumentation compile, no APK/emulator. The scoped source comparison
  identified route-retention/legacy-upgrade, Tailscale-only Details and already-
  authorized external ticket gaps; see `ATTACH_TICKETS.md`. Physical acceptance
  and complete saved-route parity remain open.

- Combined notification/PDF milestone at clean `ca00fde`: one debug/test APK
  build (80 s), **10/10 Android cases passed in 41.576 s** on the existing API37 /
  16 KiB AVD. Covers two notification gesture/membership cases, three empty-state
  recovery cases and five PDF cases including the compatibility engine. Visible
  native PDF highlights and empty timeout UI were inspected. No new crash/ANR,
  empty crash buffer, font scale 1.0, AVD stopped/reaped. No physical/signed release
  acceptance. Evidence: `captures/runtime/feed-pdf-milestone/`. This supersedes
  the queued runtime checks in the next two source-batch entries.

- PDF text/search/word-copy controls now have a compatibility engine below API35
  using the existing pinned parser. API35+ keeps the native engine; rendering is
  still native on all versions. Crop/rotation geometry, normalized text offsets,
  Unicode word lookup and bounded page caching are implemented. Fourteen focused
  JVM checks pass; main/instrumentation compile. Two new Android cases passed in
  the milestone above, including forced compatibility mode on the existing
  AVD. Older-Android runtime/font coverage and full PDFKit selection/destination
  behavior remain open; see `CONTENT_PREVIEW_LIFECYCLE.md`.

- Notification rows now support revealed/full read swipes with stable intent and
  current action admission. The main feed reconciles all keyed rows and anchors
  live changes; history controls, browser notification geometry and native rich
  empty/retry content now use measured deferral. Fourteen focused feed JVM checks
  pass; main/instrumentation compile, and three new UI cases passed in the
  milestone above. This supersedes the remaining implementation gaps for native
  rich empty and browser notification-row geometry in the older checkpoint below.
  Runtime/route/recycling/large-font/accessibility/performance/physical acceptance
  remain open. See `WORKSPACE_ROWS.md`; no APK/emulator was started for this batch.

- Explicit Mac/SSH notices and browser progress/error/status/empty/load-more rows
  now share body membership deferral and viewport anchors. Their measured heights
  wait during gestures; cached actions check the current row. Group drawing follows
  measured deferral, and indentation is measured inside the workspace body.
  Group callbacks recheck capabilities/pinning. The native rich empty/recovery view,
  notification-row geometry, recycling, font/width changes and broader physical/
  performance acceptance remain open. Combined milestone: 21 unique Android cases
  passed (14 initial passes, then 7/7 on focused rerun after fixture corrections
  and a System UI boot-ANR interruption). Production APK was unchanged between
  runs; no new retry-run crash/ANR events. AVD stopped/reaped, no physical/signed
  acceptance. See `WORKSPACE_ROWS.md` and `captures/runtime/workspace-chrome/`.
  This supersedes the pending runtime status of the previous measured-row batch.

- Workspace body rows now distinguish equal-height content refresh from measured
  geometry changes during gestures. Shared main/browser hold state and standalone
  swipe state defer taller/shorter visual models until release; revealed read
  actions preserve their intent while callbacks check current capabilities.
  Cached revoked actions and pending close confirmations are retired. Source and
  instrumentation compile; three new Android cases await the next combined
  milestone. Group/header/footer/status geometry and runtime/performance/physical
  acceptance remain open. See `WORKSPACE_ROWS.md`.

- Combined workspace/PDF milestone: **14/14 Android tests passed in 66.575 s**
  on the existing API37/16 KiB AVD; eight focused PDF JVM tests also passed.
  This supersedes the queued Android status in the three workspace source batches
  and PDF source batch below. Direct/named/GoTo PDF links now use a pinned metadata
  parser after the native extractor omitted valid direct destinations. Actual
  enlarged text, workspace anchoring/gesture retirement and PDF link/search/
  recreation/copy paths passed. Debug APKs built; font scale restored, no final
  crash/ANR entries, AVD stopped/reaped. Physical acceptance and a new signed
  delivery remain pending; see `WORKSPACE_ROWS.md` and `CONTENT_PREVIEW_LIFECYCLE.md`.

- Main/browser workspace membership and order now wait through scroll/fling,
  row holds and open swipes. Surviving content stays current; removed rows are
  inert and cached callbacks check membership/lifetime. Recycled swipe owners
  release their hold; SSH ownership uses qualified row keys. Four new policy
  cases and eight anchor cases pass. Android swipe/retirement cases are queued
  for the combined milestone. Row-height/action changes and status/footer
  geometry still need full reconciliation; see `WORKSPACE_ROWS.md`.

- Native/browser workspace lists now select a stable visible neighbor for idle
  structural updates, include connection-status prefix indices, show new rows at
  absolute top and omit live placement animations. Eight focused policy cases
  cover moved/deleted rows, pixel boundaries and a 50,000-row feed; Android bounds
  cases are queued for the integration milestone. Full gesture-time geometry
  buffering and height/action reconciliation remain implementation work. See
  `WORKSPACE_ROWS.md`; no APK/emulator was started.

- Workspace view options now use the iOS-style illustrated sort tiles and stay
  open during sort/read-state/machine changes. Custom Order no longer opens its
  editor automatically in either the main or separate-browser sidebar. Main and
  instrumentation Kotlin compile; revised interaction cases await the next
  integration milestone, including enlarged text and TalkBack. The newer viewport
  checkpoint above starts anchoring; full gesture deferral remains open. See
  `WORKSPACE_ROWS.md`. No APK/emulator was used for this batch.

- Added the encrypted sender outbox with atomic local SQLite claims, crash/restart
  recovery, original ciphertext/expiry, bounded deduplication receipts, persisted
  backoff and exact-generation retirement intent. Local checks include SIGKILL
  and concurrent subprocesses. Credential-store key, scheduler, authenticated
  enrollment/policy store and live provider integration are still host work;
  see `PUSH_DELIVERY.md` and `push/README.md`. No Android build was needed.

- Added the FCM sender transport for the pending private-helper/backend choice:
  encrypted data-only delivery, explicit recipient/admission checks, expiry and
  size bounds, OAuth, retry timing and exact unregistered-token classification.
  Eleven local Node checks passed and join milestone CI. No cloud resources,
  registration or live push were created. Provider choice, authenticated helper
  enrollment/sender trust, durable outbox integration and Android token lifecycle remain open;
  see `PUSH_DELIVERY.md` and `push/README.md`.

- PDF interaction source now adds search/highlights, selectable page text and
  native word lookup, URL/internal links and accessibility actions on API35+.
  Query/selection coordinates restore through recreation. Eleven focused JVM
  checks passed; main/instrumentation Kotlin compiled. Two native-PDF/runtime UI
  checks are compiled but queued for the next combined milestone. Direct range
  handles/cross-page selection, older Android text support, link destination zoom,
  broad format handling and real iOS/Pixel UI acceptance remain open. No APK or
  emulator was started; see `CONTENT_PREVIEW_LIFECYCLE.md`.

- Export storage batch now reclaims aged malformed/missing journals, orphan
  payloads and partial receipts; preserves active/retryable saves; skips busy
  writers; and protects temporary exports across processes. One stable zero-byte
  range-lock file per store prevents new per-request lock-file growth. All 72
  focused JVM checks passed, including separate-process lock and killed-owner
  checks; main Kotlin compiled. Android process/provider acceptance and cleanup
  of historical empty lock files remain open. No new APK or emulator was used.
  See `CONTENT_PREVIEW_LIFECYCLE.md` and its retained failure evidence.

- Combined milestone at `132b6f6`: all 11 Android checks passed (86.408 s),
  covering media focus/session/track selection, visible subtitle restoration,
  playback lifecycle/controls and six pairing cases. Debug/test APKs built once
  for the batch; the existing AVD was stopped afterward. This supersedes the
  queued/compiled-only fixture status in the historical entries below. Physical
  acceptance and full parity remain open. See `CONTENT_PREVIEW_LIFECYCLE.md`.

- Follow-up reconnect review restores the historical saved-row path when no build
  tag is recorded: exact endpoint plus verified device/account only, without peer
  replacement. The source audit also resolves the Direct port question: iOS's
  current editor and v2 transport require a port, matching Android. Focused JVM
  checks and Kotlin compilation cover the correction; physical legacy upgrade
  and authenticated row enrichment remain open. See `DIRECT_CONNECTION.md`.

- Saved native connections can now follow a changed Iroh endpoint using their
  verified device/build within the current account/team directory. Fresh links
  still require their exact endpoint. Reconnect keys track the saved identity,
  old wires retire and Direct/Tailscale preferences remain in force. Focused
  runtime-policy tests and Kotlin compilation cover this batch; physical peer
  rotation and saved-ticket acceptance remain open. The optional field in iOS's
  shared Direct model does not mean its editor or v2 transport accepts a missing
  port; the follow-up above resolves that question. See `DIRECT_CONNECTION.md`.

- Pairing now preserves the in-app versus external-link boundary from the iOS
  source. External Tailscale links cannot create authorization; mixed external
  tickets offer native choices, while explicit in-app mixed tickets prefer only
  exact numeric Tailscale routes. Legacy tickets containing a self-dialing route
  are rejected as a whole. Focused JVM checks and main/instrumentation compilation
  pass; revised Android chooser cases and real Intent/Mac/Pixel connection checks
  await the next milestone. Full saved-method/default selection remains open.
  See `ATTACH_TICKETS.md` for the scoped source comparison and evidence.

- Media-session callbacks now connect platform/headset playback controls to the
  preview's existing player and audio-focus owner. Initial paused previews stay
  inactive; background/retired previews reject commands and released sessions
  cannot restart replacement views. Main/instrumentation Kotlin compile; the
  real-controller fixture is compiled but not run. Run it with tracks and focus
  at the next combined milestone, then verify real headset/system UI behavior
  and iOS equivalence. Routing/PiP and physical acceptance remain open.

- Alternate audio and embedded subtitle selection is implemented, with saved
  choices, subtitle Auto/Off and timed-text cues. Six selection-policy JVM tests
  passed and main/instrumentation Kotlin compile. Actual multi-track playback,
  rendered cues, rapid changes, saved playback position and recreation are queued
  with audio-focus checks for the next combined media milestone. Full styling,
  routing/PiP and physical acceptance remain open; see `CONTENT_PREVIEW_LIFECYCLE.md`.

- Media audio-focus ownership and output-disconnect handling are implemented.
  Paused previews leave other audio alone; transient focus recovery respects
  playback intent and user Pause, and speed changes defer until focus is granted.
  Ten focused JVM tests passed; main and Android tests compiled. The new platform
  focus fixture and existing media lifecycle cases are queued for the next combined
  device milestone. Actual headset/Bluetooth/call/audible-output acceptance and
  media-session/remote-control acceptance remain open (source implemented above).
  See `CONTENT_PREVIEW_LIFECYCLE.md`.
  The signed build at `19698a4` excluded this newer source batch and failed before
  building because GitHub could not acquire a hosted runner (run 37364121139).
  No new APK was produced; retry is deferred to the next combined milestone.

- Media previews now have seek/time controls, ±10-second skips, speed, mute and
  fullscreen with saved bookmarks/options and guarded player replacement. Four
  control-policy tests passed. Both focused Android media cases now pass after
  fixing delayed fullscreen-view foreground synchronization; combined integration
  evidence is in `CONTENT_PREVIEW_LIFECYCLE.md`.
  Playback/audio, video layouts, interruptions, embedded tracks/PiP/routing,
  accessibility and physical acceptance remain open. Build 616 excludes this
  batch; see `CONTENT_PREVIEW_LIFECYCLE.md`.

- Native text/Markdown previews now stream readable prefixes with strict UTF-8,
  progress, EOF-gated Copy Contents, exact remote Copy path, Latest/End tail
  following and reading/selection retention. Thirty-three JVM checks passed and
  main/instrumentation Kotlin compiled. The growing-document Android UI fixture
  now passes after correcting its disabled-action selector. Live routes, large-document
  performance, Markdown reflow and physical acceptance remain open. Build 616
  excludes this batch; see `CONTENT_PREVIEW_LIFECYCLE.md`.

- Image viewer source comparison now implements iOS 3× tapped-point double-tap,
  centroid-anchored pinch, minimum-scale paging tolerance and long-press
  Share/Save/Copy Image using the shared retained action owners. Image/PDF decoder
  errors are readable. Six geometry tests and Android zoom/menu/action checks
  passed. Integration fixed a completed-Share busy-state leak; 14 export JVM
  checks and both Android export recovery cases passed.
  Different-aspect-ratio restoration, animation, accessibility and physical
  acceptance remain open. See `CONTENT_PREVIEW_LIFECYCLE.md`.
- Signed milestone build 616 passed CI at `f72f036` (Actions run 37352608676),
  without preview publication. It excludes the newer image interaction batch.
  Local packaging verification passed; six CI Android 17 ART probes passed.
  Signed-616 launch/upgrade and physical acceptance remain open.

- The Files/Save/export integration milestone passed 18 Android checks on the
  existing API37/16 KB AVD: real Save picker and Share chooser, exact read-only
  provider bytes, restored-action confirmation/no replay, fresh failed-Save
  recovery, retained Files previews/transfers and reconnect, and image clipboard
  bytes. A screenshot-found local text error was fixed; the strengthened recovery
  case passed separately in 9.104 s. Screenshots reviewed, settings unchanged,
  no successful-run crashes/new ANRs, sole AVD stopped. See
  `CONTENT_PREVIEW_LIFECYCLE.md` for evidence and the first assertion failure.
- Durable Share/Open/Copy Image preparation and persistent background Save writes
  are implemented, including fresh-launch Save recovery, exclusive ownership and
  conservative cleanup. Earlier focused JVM batches passed 23 export, 35 recovery
  and 27 transfer checks. Android integration now exercises a real destination
  write and new-owner receipt restoration. Actual process killing/reboot, separate
  main/browser-process concurrency, notification Cancel/shared grants and large or
  slow providers remain open. Malformed/orphan reclamation is implemented in the newer batch above;
  historical empty lock-file cleanup remains open. No physical Pixel was available.
- Upstream monitoring has succeeded on main and created bot review issue #2.
  The complete-tree fallback and expanded release-script coverage pass 16 policy
  tests; the live inventory detects 1,060 relevant paths at `1012a019`. Detection
  does not close the source audit. See `UPDATES.md`; no app build was triggered.
- Share/Open/Copy Image and gallery-row Share now have Activity-owned preparation
  and one-time system presentation, with cancellation and cache leases for active
  exports. Main Kotlin compiled and 17 focused JVM checks passed. Real Activity
  rotation/chooser/clipboard and process-death behavior remain unverified; no
  APK or emulator run. See `CONTENT_PREVIEW_LIFECYCLE.md`.
- Remote Save/Share/Open now re-stat and stream the current Mac file, including
  when a preview exceeds its size limit. Save streams directly to its durable
  copy with preparation progress/cancellation. Main Kotlin compiled and 26
  focused JVM checks passed. Actual toolbar/chooser/picker, network changes and
  large-file behavior remain pending. The subsequent export-owner batch implements
  retention through recreation; its Android runtime acceptance remains pending. See `CONTENT_PREVIEW_LIFECYCLE.md`.
- Save recovery now has durable private copies, atomic phase records, integrity
  checking before destination writes, and cancellable progress. Main Kotlin
  compiled and 17 focused JVM checks passed. Actual Android
  process/picker/provider recovery and background completion remain pending.
  The subsequent remote-actions batch implements current-loader materialization
  identified by the iOS comparison. See `CONTENT_PREVIEW_LIFECYCLE.md` for scope.
- Latest source batch adds Files/gallery/direct-preview reconnect retention,
  retained folder listings, and typed folder/direct-path failures. Main Kotlin
  compiled and 27 focused JVM cases passed. Android/Pixel route, visible reading
  state and rotation/reconnect acceptance for this batch remain pending; no APK
  build or emulator run was performed. See `CONTENT_PREVIEW_LIFECYCLE.md`.
- Build **616** at `f72f036` is the latest signed development download. CI and local
  packaging checks passed; signed-616 runtime/upgrade remains open. Earlier signed-out
  596 → 606 upgrade/reboot evidence does not verify this APK. [PIXEL_INSTALL.md](PIXEL_INSTALL.md) is authoritative for downloads.
- Rejected-token recovery passed **109 focused JVM tests** and is now in build
  606. Actual Mac rejection/recovery acceptance remains open. Continue batching
  features into signed milestones rather than signing every small commit.
- Token recovery is committed/pushed as `d8edb8b`. Route ordering now has 12
  Swift-reference cases within 104 passing JVM checks; the revised chooser test
  passed Android execution in the optional-relay batch. That batch also passed
  39 JVM and 25 Android checks. Next: real Mac/Pixel connection acceptance,
  entry-source route policy, and remaining content lifecycle gaps.
- Changes preview retention now passed 29 JVM, seven Android regression checks,
  and a strengthened one-test PDF/image recreation check. See
  [CONTENT_PREVIEW_LIFECYCLE.md](CONTENT_PREVIEW_LIFECYCLE.md). Next viewer work:
  browser-parent recreation, rendered-Markdown/Save state, video acceptance and zoom across aspect-ratio changes.
- Short/mixed PDF navigation and image zoom/pan recreation are now verified by a
  further seven Android checks (79.850 s), including real pinch/pan/reset and file
  paging. An initial one-finger pan failure was fixed before the final pass.
- Browser view retention and routed Changes rotation now passed four Android checks
  (79.876 s), including exact DOM/history and real picker upload. See
  [LOCAL_BROWSER.md](LOCAL_BROWSER.md) for the debug-host versus production-route
  scope, ninth debug fixture, and remaining parent/process/picker checks.
- Text/media recreation and native text clipping now passed seven Android checks
  (94.922 s), including exact search/selection/reading state, paused/playing media
  bookmarks, Go-to-line drafts and a toolbar pixel assertion. See
  [CONTENT_PREVIEW_LIFECYCLE.md](CONTENT_PREVIEW_LIFECYCLE.md). Rendered-Markdown,
  pending Save, process death, video/aspect-ratio and physical acceptance remain;
  ten debug Activities are confirmed excluded from signed 606.
- Main terminal Files now retains its gallery/download owner through recreation.
  68 JVM checks, one actual NativeScreen Files test (24.449 s) and nine Files
  regressions (76.590 s) passed. PDF pixels/page/bounds and an in-flight transfer
  retained their owner without duplicate fetches; closing deleted private files.
  Screenshots inspected; settings unchanged; sole AVD stopped. This is newer than
  signed 606. Next: native Markdown-panel retention, direct-tap and long-text route
  recreation, pending Save results and revocation UI. See
  [CONTENT_PREVIEW_LIFECYCLE.md](CONTENT_PREVIEW_LIFECYCLE.md) for exact scope.
- Shared Save now retains its result handler and independent bytes across Activity
  recreation and loading-preview replacement. 17 JVM and three Android picker
  checks (47.142 s) passed, with exact saved bytes, cancellation cleanup and failed
  destination retry state. Fourteen viewer regressions also passed (158.618 s);
  screenshots inspected, no run crashes/new ANRs, settings unchanged, AVD stopped.
  Save process death,
  large/interrupted writes, provider transitions, remote-file freshness comparison
  and full physical routes remain open; see `CONTENT_PREVIEW_LIFECYCLE.md`.
- Native file/Markdown panels now retain admitted downloads across recreation.
  60 JVM and four Android checks passed: Raw Markdown search/reading position,
  exact content/owner with one fetch, a pending transfer, panel-removal cleanup
  and a visible closed-panel message, plus existing panel/Files regressions.
  Screenshots inspected, no run crashes/new ANRs, settings unchanged, AVD stopped.
  Shared rendered Markdown recovery is verified by the next checkpoint. Full
  file-panel visual restoration and descriptor refresh remain; compare actual feed-loss retention
  against iOS. Process death and physical workflows remain open. Details in
  `CONTENT_PREVIEW_LIFECYCLE.md`; signed 606 unchanged.
- Rendered Markdown now saves scroll/zoom/block anchors through recreation,
  mode toggles and renderer termination. Seven JVM tests and two Android tests
  (33.480 s) passed, including real pinch, late layout changes, paragraph geometry,
  painted text and two retries followed by raw fallback. Three Markdown regressions
  (23.465 s) passed on the identical production APK, with Mermaid/Vega pixel checks.
  Screenshots inspected; all 14 assets matched; no new run ANRs/app crashes; sole
  AVD stopped and reaped. Next: different-width/text reflow, full native panel
  rendered/transport recovery, process death/refetch and physical acceptance.
  Signed 606 is unchanged; details in `CONTENT_PREVIEW_LIFECYCLE.md`.
- Native panel feed-loss recovery now passed 58 JVM and four Android panel checks
  (63.287 s), plus two existing Files/panel regressions (39.722 s). Completed
  rendered documents keep reading state without refetch; interrupted transfers
  use a fresh verified feed; title changes/revocation delete old bytes. Fourteen
  screenshots inspected, settings unchanged, no new successful-run ANRs/crashes,
  14 assets verified; sole AVD stopped/reaped. Signed 606 is unchanged. Follow up
  the documented intermittent JVM two-Mac startup timeouts and early Raw-menu
  interaction; typed panel failures/manual retry and physical routes remain open.
- Typed file/Markdown preview failures now passed 31 JVM checks, seven Android
  panel checks (99.758 s) and nine Files regressions (86.445 s). Eight new
  failure/recovery screenshots inspected; retries load fresh bytes and
  non-retryable cases show specific explanations. All 14 assets matched;
  settings unchanged, no successful-run crashes/new ANRs, sole AVD stopped/reaped.
  See `CONTENT_PREVIEW_LIFECYCLE.md` for the fixture correction, pre-test boot ANRs,
  rounding display issue and remaining transport/local-storage/physical scope.
  Signed 606 remains unchanged.
- Local preview output now distinguishes ENOSPC/EDQUOT from other local IO errors,
  and ambiguous rounded size-limit messages include exact byte counts. 19 JVM and
  five Android checks (10.210 s) passed, including a real proxy-file ENOSPC write;
  size UI inspected, 14 assets matched, successful-run crash/ANR checks clear,
  settings unchanged, sole AVD stopped/reaped. Directory/rename/read/export/decoder
  and physical storage acceptance remain open. See `CONTENT_PREVIEW_LIFECYCLE.md`
  for the corrected restricted-device test and boot ANRs. Signed 606 unchanged.
- Explicit panel Retry now uses a freshly verified connection after feed
  replacement; failed/interrupted selection survives offline recreation and
  revoked/stale callbacks cannot reopen it. 16 JVM checks and three changed
  Android cases (28.731 s) passed; four screenshots inspected, no successful-run
  crashes/new ANRs, settings unchanged and sole AVD stopped/reaped. Broad
  regression is deferred to integration milestones per the updated user request.
  Details and the initial interruption failure are in `CONTENT_PREVIEW_LIFECYCLE.md`.
- The Pixel was absent during the latest batch. One USB/unlock request is pending
  for real Mac/Pixel acceptance; continue independent development while awaiting it.
- Use only the existing AVD and stop it after testing. No extra virtual devices.

## Latest Agent Feed question port — 2026-10-09

Scoped to upstream `f4b1509054949eaad5d695569ad443c4c18ed68d`: the shared native/browser
Feed now opens an all-question sheet from a compact preview, preserves inactive
custom text, restores drafts only for the same ordered prompts, and defaults quote
bubbles on in release builds. Measured viewport changes keep the focused answer
above the keyboard. Seventeen JVM checks and four Android feature cases passed;
see `AGENT_FEED.md` for run evidence, initial failures and visual verification.
This does not advance the global source pin or close physical, browser-process,
process-death or signed-release gates. Interactive keyboard drag dismissal remains
to compare/port. The Pixel was absent; signed 616 unchanged.

## How completion is recorded

For each row, link the exact source revision, checks and runtime evidence in the
relevant detailed document. Record failed attempts and the scope of the final
pass. Update this checklist when evidence changes; do not turn unresolved items
into exclusions or claim a percentage based on test/build counts. The user authorized main integration on 2026-10-05. Source integration is not
full-parity or release acceptance; production release promotion still requires
authorization.
