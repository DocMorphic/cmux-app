# Content preview lifecycle

## Combined PDF/notification runtime milestone — 2026-10-06

The clean `ca00fde` source built debug/test APKs in 80 seconds. The existing API37
/16 KiB AVD passed **10/10 Android cases in 41.576 seconds**. Five are PDF cases,
including both new forced-compatibility checks: crop/all right-angle page rotations,
search/word lookup, native raster rendering, link preservation and close/reopen.
The native text/search/link cases and UI link navigation, highlighted matches,
recreation and clipboard case also passed. The actual match-highlight screenshot
was visually inspected. Five notification/empty-state cases passed in the same
run; see `WORKSPACE_ROWS.md`.

No new crash/ANR events appeared; the crash buffer was empty. Font scale remained
1.0 and the sole emulator was stopped/reaped. APK hashes, command, logs and images
are retained in `captures/runtime/feed-pdf-milestone/`. This supersedes the pending
runtime status of the two compatibility cases below. Forcing the compatibility
engine on API37 does **not** establish API26–34 class loading or device behavior;
older-Android runtime, embedded/non-Latin fonts, full PDFKit selection/destination
behavior and physical Pixel/Mac acceptance remain open. No signed release changed.

## Compatibility PDF text source batch — 2026-10-06

The viewer no longer disables text controls below API35. Older Android versions
now use the already-pinned PDFBox parser for page text, document search and word
lookup; API35+ retains Android's native text engine. Rendering stays on Android
PdfRenderer on every supported version. The compatibility engine is selectable
internally in instrumentation so it can also be exercised on the existing API37
AVD without allocating another emulator.

The parser collects reading-order text and glyph bounds, restores PDFBox's
removed crop origin, and applies the same crop/rotation transform used for link
annotations. Search ignores case and joins inferred whitespace/line breaks while
retaining original UTF-16 offsets. Long-press word lookup uses Unicode word
boundaries. Ligature/bidi normalization retains normalized reading text; where
normalization changes character order/count, highlights retain the whole word's
glyph bounds rather than inventing a character mapping. This does not implement
direct on-page range handles or cross-page selection.

The document owner serializes native and compatibility operations and closes the
shared parser. Only two extracted pages are cached. Extraction checks document
permissions and rejects pages beyond 100,000 glyph callbacks or 500,000 UTF-16
units; a search rejects more than 10,000 matches. Those limits surface the existing
retry/error UI instead of silently truncating text. They are not a complete bound
on arbitrary PDF parsing/font resource consumption. PDFBox may now decode font
metadata for compatibility text; embedded-file extraction, script execution and
PDFBox raster rendering remain unused.

**14 focused JVM checks pass**, covering phrase/offset mapping, missing bounds,
Unicode words, surrogate pairs, normalized glyph groups, large searches and
existing link/zoom coordinate rules. The first run caught a Kotlin receiver-
shadowing error in word lookup; the original failure log is retained. Main and
instrumentation Kotlin compilation succeeded (17 s final pass). Two new Android
cases cover forced compatibility extraction with crop/all right-angle page
rotations, actual render, links/word selection and close/reopen. **Those Android
cases have not run**; they join the next integration milestone. Actual older-
Android runtime, visible highlight/text acceptance, embedded/non-Latin fonts and
physical Pixel acceptance remain open. No APK/emulator was started for this batch.

Evidence: `captures/runtime/pdf-text-compat/`. Global upstream parity/review pins
are unchanged. The iOS reference remains its PDFKit-backed vertical continuous
viewer at scoped commit `186cec79781256867ad4516f0802118738bd2393`.

## PDF annotation compatibility — 2026-10-05

The combined workspace/PDF milestone exposed a real Android native extraction
gap: valid direct `/Dest` links were missing. The original fixture is retained.
The metadata parser now covers direct, named and GoTo destinations and permitted
URI annotations, using crop/rotation-aware bounds and the destination page's own
geometry. Native inferred text URLs remain available. Rendering and text/search
still use Android PdfRenderer. Parser failure retains native fallback and exposes
incomplete links; document disposal closes both parsers.

PdfBox-Android is pinned with source/license provenance in
`third_party/pdfbox-android/README.md`; no new native library is added. Scratch
storage is bounded, and embedded-file extraction and script execution are unused.
The dependency uses the existing Bouncy Castle 1.86 family; APK packaging merges
its license metadata and excludes unused duplicate OSGi descriptors.

Eight focused JVM checks pass, and debug/app-test APK assembly succeeds. The
combined Android milestone passed **14/14 tests in 66.575 s**, including three PDF
cases covering direct/named/GoTo links, rotated source and differently sized target
pages, real accessibility navigation, text/word lookup, search highlight pixels,
recreation and clipboard. The highlight screenshot was visually checked. The
initial run had 11/13 passing cases; its two failures and the subsequent fix are
preserved under `captures/runtime/workspace-pdf-milestone/`. No crash or ANR was
recorded in the final run. This source change does not
close full PDFKit selection, destination X/zoom, older-Android acceptance,
large/hostile PDF resource acceptance or physical Pixel/Mac parity.

## PDF text, search and links source batch — 2026-10-05

The scoped iOS `ChatArtifactPDFView.swift` at
`186cec79781256867ad4516f0802118738bd2393` hosts PDFKit's PDFView with vertical
continuous display and auto-scaling. Android previously exposed rendered page
images with zoom and paging. This batch adds document interaction using the
[Android PdfRenderer.Page text/search/link APIs](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer.Page),
which are available on API35+. Older Android versions retain the existing image
renderer; complete text/link support there remains open, not an unavoidable
platform difference. The Pixel's Android17 supports the new API level.

- Document search runs off the UI thread, cancels on query changes, reports page
  progress and highlights matches in page coordinates. Previous/Next match wraps
  across pages and scrolls to the selected result. Query and selected result are
  saved, and search results are rebuilt from the local PDF after recreation.
- Page text uses a selectable dialog with Copy. Long-press resolves a word through
  the native selection API after inverting the page's zoom/pan transform. Saved
  selection stores only page/coordinates; text is read again after recreation.
  The dialog offers full-page selection for longer passages. Direct on-page range
  handles and cross-page selection remain open; this is not full PDFKit parity.
- PDF URL annotations open on a user tap through ACTION_VIEW for HTTP(S), mailto
  and tel only. File/content/intent/custom schemes and credential-bearing web URLs
  are not dispatched. Internal links navigate to their target page and vertical
  position. Horizontal destination/zoom equivalence and back-navigation still
  need comparison. Accessibility actions expose page text and link destinations.
- Navigation resets page zoom so an old pan does not conceal the new destination.
  Rendering, search, text extraction and link discovery share the document's
  synchronized renderer ownership; disposal closes the renderer off the UI thread.

**Verification:** 11 focused JVM checks passed; main and instrumentation Kotlin
compiled (22 s final pass). They cover coordinate inversion, invalid/outside
points, link target admission, bounded rectangle hit testing and overflow-safe
match navigation, plus existing zoom gestures. Two new Android fixtures compile
but have **not run**: a generated two-page PDF exercises real native text/search,
word selection and both link kinds; the UI case checks search navigation, actual
highlight pixels, recreation and clipboard text. No network link is opened by
these tests. Run these with the existing PDF lifecycle cases at the next combined
milestone. Actual rendering, touch/link behavior, clipboard, large/scanned PDFs,
TalkBack, old-Android fallback and iOS/Pixel visual acceptance remain unverified.

Evidence and exact source hashes: `captures/runtime/pdf-text-batch/`. No APK,
emulator, device installation or release publication was performed. Global
upstream parity pins are unchanged. Media/Quick Look source was inspected in the
same scoped comparison; PiP/routing and wider document-format coverage remain open.

## Export reclamation and process ownership — 2026-10-05

Save maintenance now reclaims UUID-named private directories with missing,
malformed or oversized journals after seven days, only when every entry is old
and neither UI nor writer ownership is held. Valid retryable saves and valid
receipts that own provider grants retain their existing protection. Maintenance
skips busy writers rather than waiting behind a transfer. Corrupt metadata never
authorizes a provider write, document deletion or permission release. An I/O read
failure or coroutine cancellation propagates instead of being treated as invalid
metadata. Unrecoverable corrupt grant metadata is not used to release permissions.

Share/Open/Copy cleanup now discovers orphan payload directories and interrupted
atomic receipt writes as well as valid receipts. Invalid or missing receipts have
a seven-day grace period across the receipt, partial and all payload entries;
recent/future timestamps and active owners are preserved. Existing handed-off
retention remains one day; other valid receipt retention remains seven days.
Deletion never follows symbolic links, and receipt paths come from validated
UUIDs rather than malformed journal fields. Payload deletion precedes receipt
deletion so a failed removal stays discoverable.

UI, writer, destination and grant ownership now uses one zero-byte range-lock
file per private store. Stable hashed positions use separate namespaces for nested
lock kinds; a same-kind collision conservatively serializes unrelated operations.
The coordination inode is never unlinked. All instances in a process share its
channel until the last lease closes, following the
[FileLock single-channel requirement](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/nio/channels/FileLock.html).
No new per-request lock files accumulate. Historical zero-byte UI/destination
lock files from older versions are left untouched; their migration cleanup is
still open. They are not live synchronization objects in this version.

Temporary export cache leases now also use these OS locks, with reference counts
for overlapping local owners. This closes the previous main/browser cleanup gap.
Lock acquisition runs on IO; cancellation during acquisition still cleans any
unpublished source bytes. Cache contents remain temporary until durable adoption.

**Verification: 72 focused JVM tests passed, main Kotlin compiled (16 s final
run).** Separate Java processes verify contention, retained ownership after failed
and unrelated claims, release after a killed owner, and cache cleanup protection.
A 2,000-identity check leaves one zero-byte coordination file. Reclamation checks
cover missing/corrupt/oversized journals, recent/future timestamps, UI/writer
ownership, retryable records, partial receipts, symlink targets and read-error
classification. Existing Save/export/remote-share/cancellation checks also pass.

Retained intermediate failures exposed cancellation being an IllegalStateException
subclass and an unpublished-source leak when cache-lock acquisition was cancelled;
both were corrected. Two old empty-cache assertions now explicitly require only
the zero-byte coordination file, while retaining payload/cancellation assertions.
Evidence and source hashes: `captures/runtime/export-reclamation/`.

No APK, emulator or Pixel run for this batch. Actual Android main/browser process
locking, kill/reboot recovery, large/slow providers, notification cancellation,
shared grants and visible iOS/Pixel acceptance remain at the next integration
milestone. This does not establish full file-action parity or advance upstream pins.

## Combined media and pairing milestone — 2026-10-05

At source `132b6f6`, the debug and instrumentation APKs built together successfully
(1m 24s). **All 11 Android checks passed in 86.408 seconds** on the sole existing
API37/16 KB emulator: five media cases and six attach-ticket cases. No new AVD
was created. Settings were unchanged and the emulator was stopped and reaped.

Media coverage includes real platform transient/permanent audio-focus requests,
explicit Pause and injected output-disconnect handling; actual MediaController
commands and media-key Pause; fullscreen, seek/speed/mute, background pause and
recreation; and an original silent MP4 containing two audio and two caption
tracks. The latter verifies the selected native French audio track, preservation
of a paused 15-second bookmark, English/French cues, Subtitle Off and restored
track choices. Screenshot pixel assertions and visual review confirm the gold/blue
video and readable French cue after recreation. No physical sound, Bluetooth,
call, PiP or Pixel/iOS comparison is implied.

The six pairing cases cover entry-specific chooser options, explicit confirmation
and cancellation, memory-only unconfirmed tickets across Activity recreation,
saved text-state filtering, and isolated Keystore persistence/forget. Reviewed
screenshots show only the numeric Tailscale choice for in-app entry and only the
native choice for an external mixed ticket. Actual host connection remains open.

Local evidence: `captures/runtime/media-connection-milestone/`, including source
and APK hashes, complete instrumentation output, fresh captures and settings.
This supersedes the earlier compiled-only status for these specific fixtures.
Build 616 remains the latest verified signed artifact; this milestone used debug
APKs and did not publish or promote a release.

## Media session source batch — 2026-10-05

Each Android preview now owns a framework MediaSession with play, pause, stop,
seek, ±10-second skip and supported playback-speed callbacks. Media-button events
use the framework callback dispatch. Metadata includes only the displayed file
name and duration; playback state publishes actual playing/paused/buffering state,
position and speed. Commands use the same player and audio-focus path as the UI.
Opening a paused preview does not activate its session; actual playback does.
Backgrounding deactivates it and rejects commands. Closing or replacing the view
releases the session and clears its callbacks, preventing an old controller from
starting the replacement. This adds no background playback service.

The scoped `ChatArtifactMediaView.swift` at
`186cec79781256867ad4516f0802118738bd2393` uses AVPlayerViewController and pauses
on dismantling. The same revision's `ios/Config/Info.plist` declares only
remote-notification background mode. Those source facts do not prove every AVKit
remote-control/background behavior; physical comparison remains necessary.
Android lifecycle/callback requirements were checked against the
[framework MediaSession reference](https://developer.android.com/reference/android/media/session/MediaSession).

Verification: main and instrumentation Kotlin compile. The initial test compile
caught nullable metadata access, corrected with an explicit non-null assertion.
The new guarded Android fixture sends real MediaController commands, a media-key
event, and checks metadata, clamped seeking, speed, background rejection and
release across recreation. It is **compiled but not run**, queued with the audio
focus and track-selection cases at the next combined milestone. Hardware headset
dispatch, system UI visibility, audible output, routing/PiP and Pixel/iOS
comparison remain unverified. No APK or emulator was started for this batch.
Local evidence: `captures/runtime/media-session-batch/`.

## Audio and subtitle selection source batch — 2026-10-05

The scoped iOS media reference delegates track controls to AVPlayerViewController.
Android now exposes alternate audio and embedded subtitle/timed-text tracks with
language labels, distinct names for duplicate languages, and subtitle Auto/Off.
Explicit choices survive preview recreation and fullscreen transitions in saved
state. Audio changes reprepare the same local artifact, retaining the bookmark,
playback intent and focus lease: Android guarantees audio-track selection only
in the prepared state. Auto captions consider the system caption preference,
language and forced/default metadata; this policy still needs runtime comparison.

Native subtitle rendering remains with VideoView. Timed-text cues use an overlay
that follows system caption font scale. Selection confirmations are bounded and
guarded against retired players and superseded choices; a late native selection
is reconciled with the newest choice. Failures are shown independently of normal
playback-control errors. Full cue styling/positioning and accessibility remain open.

References: [Apple media selection](https://developer.apple.com/documentation/avfoundation/selecting-subtitles-and-alternative-audio-tracks?language=objc)
and [Android track selection](https://developer.android.com/reference/android/media/MediaPlayer#selectTrack(int)).
Verification for this source batch: six focused JVM tests cover malformed/unknown
languages, ISO language matching, explicit/Off/Auto choices, forced subtitles,
missing tracks and duplicate labels. Main and instrumentation Kotlin compile.
No APK or device run is claimed. Next combined media milestone must verify actual
multi-track audio, visible subtitle cues/Off, rapid selection changes, preserved
bookmark/playback intent and recreation, together with the audio-focus cases.
Local evidence: `captures/runtime/media-tracks-batch/`.

The earlier signed milestone run
[37364121139](https://github.com/DocMorphic/cmux-app/actions/runs/37364121139)
at `19698a4` failed before any build step: GitHub reported that the job could not
be acquired by a hosted runner after multiple attempts. No APK was produced.
Signed 616 remains the last verified signed artifact. Retry belongs to the next
combined build milestone; automatic previews remain disabled.

## Audio interruptions source batch — 2026-10-05

The scoped upstream `ChatArtifactMediaView.swift` at
`186cec79781256867ad4516f0802118738bd2393` delegates playback to AVPlayer/
AVPlayerViewController. Android now owns the preview's audio-focus lifecycle
explicitly. The inspected [AOSP VideoView implementation](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/widget/VideoView.java)
requests focus during preparation with a null listener, so its default behavior
was insufficient for keeping the app's playback intent synchronized.

- Decoding/paused previews do not acquire focus. Playback requests a lease with
  matching media/movie audio attributes, and starts only when granted. Denial
  leaves the preview paused with a retry message.
- Transient loss pauses while retaining playback intent; focus gain resumes only
  the current foreground player. Permanent loss clears that intent. User Pause,
  backgrounding, closing/replacing a player, completion and errors release the
  lease; generation guards discard late callbacks. Duck notifications adjust
  volume without overriding Mute.
- A receiver exists only while playback owns or temporarily awaits its focus
  lease. Headphone/Bluetooth output-disconnect notification pauses and cancels
  automatic resume. No route is selected or changed by the app.
- Speed changes while paused are saved without touching MediaPlayer playback
  parameters. They apply after focus is granted, avoiding that API's implicit
  start when the user is merely changing a paused preview's speed.

The behavior follows Android's [audio-focus guidance](https://developer.android.com/media/optimize/audio-focus)
and [output-disconnect notification](https://developer.android.com/reference/android/media/AudioManager#ACTION_AUDIO_BECOMING_NOISY).
This is not proof of every AVKit interruption, session, route or remote-control
behavior. Headset buttons, audio routing/casting and real audible output remain
open, along with video, codecs, tracks/subtitles and PiP.

Verification: six focus-owner and four media-control JVM tests passed; main and
Android test Kotlin compiled in 1m30s. The initial compile caught an unqualified
receiver `this` reference; it was corrected before that successful run. The new
guarded Android fixture uses real competing platform focus requests; it invokes the disconnect
receiver locally because this is a protected system broadcast. It is queued with
the existing media restoration/control cases for the next combined device run.
No APK, emulator or Pixel run is added for this source batch. Local evidence:
`captures/runtime/audio-interruptions-batch/`. The already-dispatched signed
milestone at `19698a4` excludes this newer batch.

## Combined viewer integration — 2026-10-05

The image, streaming-text and media batches were integrated at `8f631c6` on the
existing arm64 Android 17/API 37 AVD with 16 KB pages. The first combined run
completed **12 cases in 217.085 s: nine passed, three failed**. Existing media
restoration, raw-text/Markdown state, Changes image/PDF retention, native text
controls and Markdown viewport/renderer recovery passed. No crash or ANR was
recorded in that run; screen settings were unchanged.

The failures were retained and narrowed:

- Copy Contents was correctly disabled during streaming, but its separate label
  node remained enabled in Android accessibility. The test now checks the action
  row. The follow-up passed prefix display, exact Unicode remote path copying,
  selection/reading retention across growth and recreation, Latest/End following
  and complete text copying.
- The speed button exposes its description and visible value as sibling nodes
  beneath the clickable control. Both initial selectors incorrectly required
  them on one node or in a parent/child relationship. The test now selects their
  common actionable ancestor and still checks the actual MediaPlayer speed.
- Image Share completed and its durable receipt reached HANDED_OFF, but the
  toolbar remained busy, preventing the next long-press menu. The receipt write
  survives lifecycle cancellation; its caller previously skipped clearing busy
  when returning to a cancelled Activity collector. Handoff cleanup now runs in
  `finally`, guarded by the original request identity. A deterministic JVM test
  cancels that collector during persistence and verifies retained receiver bytes,
  no replay, cleared busy/saved ID, and acceptance of a subsequent action.
  The UI test also checks the enabled action ancestor, not its label.

The first focused follow-up ran three cases in 76.228 s: streaming passed;
image reproduced the stuck busy state and media exposed the sibling-node layout.
Fourteen export controller/recovery JVM tests pass after the cleanup fix; debug
and instrumentation APKs build. The next focused Android run completed four
cases in 87.183 s: image actions and both export-recovery cases passed; media
reached fullscreen recreation but failed its playback assertion. This remaining
case reproduced with a 120-second clip (42.952 s), ruling out completion of the
short fixture. Captured player state showed the bookmark and Play request intact,
but `foreground=false` after recreation. The fullscreen Dialog can create its
player after ON_START; the observer previously updated only an existing view.
It now records foreground/background state even before a player exists, including
clearing play intent on a genuine background transition. The test records player
state, checks time progression at the selected speed and attempts to dismiss
Android's first-use fullscreen education. Final focused media run: **2/2 passed
in 34.493 s**, covering fullscreen recreation at actual 2× playback plus paused/
playing inline restoration and explicit-play behavior after backgrounding.
The recorded fullscreen bookmark progressed from 60,567 to 61,688 ms with
`playing=true`, `foreground=true` and speed 2.0. Seek, skip, mute, speed, Back to
inline and paused bookmarks also passed. Three final media screenshots were
reviewed: Android's education still overlays the initial fullscreen capture;
post-recreation fullscreen and returned-inline captures are clear. The silent
WAV fixture verifies player behavior, not audible output or video layout.
No final-run crash/ANR, screen settings unchanged, and the sole AVD stopped and
reaped. No additional emulator was created.

Local evidence is retained under `captures/runtime/viewer-controls-integration/`,
including initial/follow-up failures, XML hierarchies, APK receipts and logs.
These are synthetic emulator workflows; real remote streaming, physical media,
accessibility and Pixel/Mac acceptance remain open. Signed build 616 excludes
these source changes.

## Media controls and fullscreen source batch — 2026-10-05

The scoped `ChatArtifactMediaView.swift` at upstream
`186cec79781256867ad4516f0802118738bd2393` hosts a local AVPlayer in
AVPlayerViewController with aspect-fit video and releases it on dismantle.
Apple's [AVPlayerViewController reference](https://developer.apple.com/documentation/avkit/avplayerviewcontroller)
and [speed-control reference](https://developer.apple.com/documentation/avkit/avplayerviewcontroller/speeds)
confirm system playback-speed and fullscreen capabilities. This is a scoped
comparison, not a claim that every AVKit feature is now matched.

Android's preview now provides a seek slider with current/duration labels,
10-second backward/forward skips, Play/Pause, Restart, speed choices from 0.5×
to 2×, mute and fullscreen. Controls wrap on narrow layouts and include labeled
accessibility actions and position/speed descriptions. The old floating
MediaController is replaced by these controls. Speed, mute, fullscreen and the
position bookmark survive recreation; normal background/task restoration keeps
the prior requirement for an explicit Play gesture. Progress polling runs only
while the owner is started. Play at the end seeks back to the start.

Fullscreen uses a separate full-window dialog, hides its system bars with
transient swipe access, and closes through Back or Exit fullscreen. The old player
surface is released before composing the new host, preserving position and play
intent; release is idempotent and retired callbacks cannot overwrite a new owner.
The Activity's own window flags are untouched. Format failures provide Retry and
retain Open in the viewer toolbar.

Android's [MediaPlayer contract](https://developer.android.com/reference/android/media/MediaPlayer#setPlaybackParams(android.media.PlaybackParams))
says a nonzero playback speed starts a prepared player. Speed changes therefore
silence the transition and immediately restore Pause when playback is not
requested, then restore the requested volume. Unsupported speed changes show a
readable message. This guard is implemented; audible behavior has not yet been
verified on a device.

**Verification:** four focused control-policy tests passed (seek bounds/overflow,
unknown duration, time formatting and invalid saved speeds). Main and existing
instrumentation Kotlin compiled in the 22 s check. A new guarded media test then
compiled with the final source in 19 s. It exercises speed changes while paused,
actual MediaPlayer speed during playback, skip/scrub, mute UI state, fullscreen
window ownership, bookmark transfer, recreation and return to inline playback.
**The new Android test has not run**; no claim of runtime/video/audio acceptance
is made from the policy tests. Evidence/source hashes and the upstream excerpt are
under `captures/runtime/media-controls-batch/`.

This batch is queued with image gestures and progressive text for the next
combined Android milestone. No APK, emulator or physical-device run was added;
signed build 616 excludes it. Remaining media work includes actual video and
aspect/layout checks, codec coverage, audio focus/headphone interruptions,
embedded tracks/subtitles, Picture in Picture and routing/casting comparison,
TalkBack/large-font behavior and real Pixel playback. Global parity pins remain
unchanged and these are pending features/checks, not claimed platform exclusions.

## Progressive text and viewer-path actions — 2026-10-05

The scoped iOS source at `186cec79781256867ad4516f0802118738bd2393` displays
text/Markdown during transfer, accepts only valid UTF-8, disables Copy Contents
until EOF and at sizes over 4 MiB, labels the incomplete-text jump Latest, and
supports a durable follow-tail request ended by user interaction. Its full-viewer
menu includes Copy path. Inspected ViewerModel, ViewerActionsMenu, Action and
TextBottomPinStateMachine sources and hashes are under
`captures/runtime/streaming-text-batch/upstream.json`. MediaView was also inspected:
it uses a local AVPlayer with system controls and pauses/releases on dismantle;
this check alone does not establish Android media-control parity.

Native Files/direct-file/panel previews now publish incremental immutable text
snapshots from the retained download owner. A strict streaming UTF-8 decoder carries
split code points across chunks and publishes the first prefix, EOF, and updates
at most every 100 ms between them. Document/string/line-index construction stays
on IO. Downloads keep the existing size limits and private partial-file ownership;
complete status is published only after sync and rename. The displayed path stays
stable across completion, and the shared view does not reread an unfinished file.
Malformed UTF-8 retires the text snapshot; after transfer, the complete unchanged
bytes use the external/binary viewer so Open/Share/Save remain available.

Raw text and rendered Markdown accept the progressive snapshots. The header shows
received/total bytes. Copy Contents is disabled until the verified local copy is
complete; Share/Save/Open still use the captured remote source and its independent
fresh transfer. Copy path copies the exact remote path, including Unicode, rather
than a private Android cache path. Menus show Latest while loading and End at EOF.
Append updates capture/restore reading anchor and selection. An explicit
Latest/End request follows appended text; touching the text or choosing another
jump ends that request. Its small bookmark is saved through Activity recreation.
Connection loss clears unfinished text and late callbacks cannot replace the
failure. Existing completed-file retention remains in place.

**Verification:** **33 focused JVM tests passed** (6 incremental decoder,
3 progressive-controller, 10 retained-controller, 7 preview files and 7 native-lane
checks). They cover every UTF-8 split boundary in a multilingual string, malformed
and truncated sequences, buffer overflow, throttling/EOF, visible-prefix state
before the final chunk, stable final filename/exact bytes, disconnect and binary
fallback. Main and instrumentation Kotlin compiled in the 45 s check; the new
runtime fixture then compiled in 24 s. Logs/XML/source hashes are under
`captures/runtime/streaming-text-batch/`.

A guarded Android fixture now injects growing documents into the existing debug
host to check readable prefixes, disabled/enabled Copy Contents, exact remote
path, selection/scroll/recreation and Latest-to-End following. **It has not run**;
it does not itself test live RPC delivery. It is queued with the image gesture
batch for the next combined Android milestone. No APK, emulator or physical device
run occurred in this batch. Build 616 excludes these changes.

Remaining: real-route progressive rendering, large-document memory/layout and
search/highlight performance, Markdown incremental reflow/scroll, precise tail
behavior through reflow/hardware input and interruptions, process death and
physical Pixel acceptance. This is the native artifact pipeline; Changes and
other local-only text routes retain their separate loading policies. Full format,
Quick Look-equivalent rendering, media and UI parity audits remain open, and the
global upstream parity pin is unchanged.

## Image interaction source parity batch — 2026-10-05

Compared `ChatArtifactZoomableImageView.swift`, `ChatArtifactZoomPolicy.swift` and
`ChatArtifactActionVisibilityPolicy.swift` at scoped upstream
`186cec79781256867ad4516f0802118738bd2393`. iOS uses an aspect-fit view inside a
zooming scroll view, scales 1–8, a 0.01 minimum-scale tolerance, 3× double-tap zoom
centered on the touched point, pager ownership at minimum, and a long-press menu
with Share, Save and Copy Image. Exact excerpts/hashes are retained in
`captures/runtime/image-actions-batch/upstream.json`; global parity pins stay put.

Android now uses that 3× image double-tap policy and centers the tapped point
subject to viewport bounds. Pinch transforms preserve the point under the moving
finger centroid and use the actual clamped zoom ratio at scale limits. Saved
scale/pan remain normalized to the view. A long-press menu at the touch location
and an accessibility long-click action expose the three iOS image actions. Both
this menu and the toolbar use a common dispatcher into existing retained owners:
remote Share/Save still re-stat/refetch, and Copy Image uses the displayed bytes.
Busy owners disable new dispatch. Image and PDF read failures now show readable
recovery guidance instead of filesystem exception text. The existing PDF 2×
double-tap target is retained; shared pinch focal-point correction also applies.

**Verification:** six focused geometry tests passed; main Kotlin and Android
instrumentation Kotlin compiled. Checks cover tapped-point centering, moving
pinch centroids, clamped ratios, bounded pans, minimum tolerance and invalid
pointer input. The first pass caught signed negative zero at minimum zoom;
minimum now canonicalizes to the reset state. Logs and passing XML are under
`captures/runtime/image-actions-batch/`.

A new guarded Android fixture is prepared for off-center zoom/recreation,
long-press menu contents, Share/Save dispatch and exact Copy Image bytes. It
intercepts Share and Save intents; real system UI was covered by the preceding
integration milestone. The existing striped-image retention test's pan distance
was adjusted for 3× zoom. **These changed Android tests have not been executed**;
no APK or AVD run was added for this feature batch. Runtime checks will be grouped
at the next integration milestone. Different-aspect-ratio focal restoration,
animated transitions, touch/menu placement and gesture arbitration, full PDFKit
behavior, accessibility traversal and physical Pixel acceptance remain open.
Build 616 is an earlier source milestone at `f72f036`; it excludes this batch.

## Files, Save and export integration milestone — 2026-10-05

Source: `8690d1f` plus the integration tests and friendly text-read error fix in
this checkpoint. **18 Android tests passed in 193.768 s** on the existing
`cmux_api37_16k` AVD. One combined debug/test APK build covered the accumulated
Files retention, remote materialization, background Save and durable export work.

- Four Save cases exercised the real document picker, picker cancellation and
  recreation, a provider failure/retry, fresh failure recovery without an old
  bundle, and a real destination write after the original preview was replaced
  and deleted. Only synthetic fixtures and their exact destination URI were used.
- Two new export cases exercise restored READY confirmation through recreation,
  a real Android Sharesheet, MIME/ClipData/read-grant and exact provider bytes,
  completed-receipt replay prevention, and ambiguous PRESENTING recovery while
  preserving potentially delivered bytes. No recipient was selected or message
  sent. Provider bytes are read through the test resolver, not a recipient app.
  A debug-only Activity injects a saved request ID into a new owner; this is
  **not** an Android process-kill test.
- Nine Files cases cover folder/filter/grid/search, remote-path copy, direct
  references, gallery Share, transfer retry, reconnect and replaced-session
  authority. One route-level Files test retains PDF reading state and a pending
  transfer across recreation. Two Changes cases verify exact image clipboard
  bytes, including extensionless image MIME.

All eleven fresh screenshots were inspected. The recovered-Save screenshot
exposed a raw filesystem exception behind the modal after preview deletion.
Text and Markdown reads now show a friendly instruction to reopen the preview,
while preserving coroutine cancellation. The strengthened recovery test then
**passed in 9.104 s**, with two corrected screenshots inspected. Its first
follow-up attempt failed because a modal hides background text from accessibility;
the assertion now checks that text after the modal closes. The app change was
unchanged between those attempts. The original failure evidence is retained.

Successful runs had empty crash logs, no new ANR events, unchanged screen/sleep
settings, and the sole AVD was stopped and reaped. No new AVD or physical-device
operation occurred. One preflight hierarchy read caught a transient splash/null
root; a fresh hierarchy and visible sign-in screen were confirmed before testing.
Evidence, exact APK hashes, original failures and screenshots are under ignored
`captures/runtime/file-actions-integration/` and its `read-error-followup/` folder.

This closes the stated fixture integration checks, not full iOS parity. Actual
process kill/reboot, separate main/browser-process concurrency, large/slow provider
writes, notification cancellation/shared grants, receiver-side access, broad
format/UI/accessibility and real Mac/Pixel acceptance remain open. Malformed and
orphan storage reclamation remains implementation work. Signed 606 remains the
last independently verified download until the next signed milestone succeeds.
Global upstream parity pins are unchanged.

## Durable Share/Open preparation — 2026-10-05

Share, Open and Copy Image now retain a private export identity in the Activity's
SavedStateHandle. After preparation, the owned cache directory is moved to durable
app files storage; size and SHA-256 are recorded before READY. This avoids a second
full copy on the normal shared filesystem. The file provider grants only the
payload directory; receipts and ownership locks remain outside its roots in
no-backup storage. No RPC client, authorization token or remote path is serialized.

Restoring a READY receipt verifies its bytes and shows Continue/Cancel before a
chooser or clipboard operation. A persisted PRESENTING transition precedes the
Android call; a HANDED_OFF receipt prevents an old Activity bundle from replaying
it. If the process stops around that call, recovery cannot infer whether Android
accepted it: it shows an interruption message, retains the potentially delivered
bytes and does not reopen the chooser. A failure to write the final receipt keeps
the conservative PRESENTING record. Interrupted preparation must be requested
again from its preview; no stale remote authority is reused to restart a download.

Closing the owner preserves READY data. Explicit cancellation before presentation
removes it and records cancellation. Exclusive file leases prevent competing
owners and protect active actions from expiry cleanup. Owner shutdown waits for
in-flight presentation receipt writes before releasing its lease. Source cache
leases cover moving the payload; the durable store has separate cleanup.
Unowned handed-off/ambiguous exports expire after one day, and other valid records
after seven days. Cleanup rechecks age/phase after taking ownership. Malformed
records/orphan payloads and empty lock-file reclamation still need handling;
receiver reads beyond the retention window are not guaranteed by this policy.

**Verification:** main Kotlin compiled; **23 focused JVM tests passed** in 19 s
(7 new recovery cases, 6 controller, 3 remote sharing, 7 local/Changes export).
They cover new-controller restoration with the old owner gone, explicit recovery
confirmation, one-time handoff, ambiguous presentation without replay, cancellation,
corrupted payload rejection, traversal metadata rejection, exclusive ownership
and expiry protection. These simulate controller loss and reconstruct storage;
they do not kill an Android process or launch a real chooser. The earlier pass
and final logs/XML/source hashes are under ignored
`captures/runtime/export-recovery-batch/`.

No APK, emulator or Pixel run. Signed 606 is unchanged. The next integration pass
should cover the accumulated Files/Save/Share/Open changes together: provider URIs
and exact receiver bytes, real SavedState recreation/process loss, main/browser
processes, chooser/clipboard handoff, cancellation, pending Save recovery and
background providers. Layout/accessibility and full iOS source parity remain open.
This is Android lifecycle implementation against the existing scoped file-action
contract; no upstream parity pin is advanced.

## Fresh-launch Save recovery and reclamation — 2026-10-05

The Activity root now discovers unfinished Save records when resumed or after
its current Save/error closes. It does not continually poll the directory while
idle. A fresh Activity can observe a background write or show a retained failure
with its filename, Retry and Cancel. READY/WAITING copies recovered without their
original Activity bundle require explicit Retry before opening another picker;
they do not assume a picker result is still outstanding. A saved Activity bundle
retains the existing picker-return path. Prepared copies are verified before retry.

File-based UI leases prevent multiple Activities/processes from adopting one
copy or opening duplicate pickers. Preparation acquires ownership before publishing
its journal. Closing an Activity releases UI ownership after its job/handoff stops;
WRITING and FAILED copies remain recoverable. Cancellation removes the private
copy after producer/writer shutdown. Cleanup retaining a borrowed permission now
dispatches another worker to finish releasing it. Per-request lease tracking also
covers a new Save starting while cancellation of an older Save is still finishing.

Main-process startup now performs conservative reclamation. Unowned PREPARING
records without a seal and older than 24 hours lose their incomplete bytes and
become CANCELLED receipts. COMPLETED/CANCELLED records older than seven days can
be removed after owned permissions are released. Active UI leases, WRITING,
FAILED, READY, WAITING, sealed preparation, recent/future timestamps and records
still owning grants are preserved. Retryable copies have no automatic expiry;
the user can remove them through Cancel. These policies cover valid journals;
malformed/orphan directory handling and safe reclamation of empty coordination
lock files remain open. The cleanup does not inspect or delete user destinations.

**Verification:** **35 focused JVM tests passed** in 24 s: 8 new ownership,
candidate-discovery and reclamation cases, plus the existing 27 transfer/recovery
cases. Main and Android instrumentation Kotlin then compiled successfully; the
final compile took 15 s. A new guarded emulator test exercises recovery without
an old bundle, no automatic picker, recreation, explicit Retry, picker cancellation
and private-copy removal. That test has been compiled **but not executed**.
Logs, XML and source hashes: ignored `captures/runtime/save-recovery-ui-batch/`.

No APK, emulator or Pixel run. Signed 606 is unchanged. Still required: combined
Android acceptance of fresh launch/process death, concurrent main/browser owners,
picker-result ordering, notifications, grant sharing and large/slow providers.
Share/Open process restoration and the remaining viewer/UI format audit also
remain work; this checkpoint does not establish full iOS parity.

## Persistent destination writes — 2026-10-05

After the user selects a Save destination, its sealed private copy and URI/grant
ownership are recorded before dispatch to a WorkManager writer. The picker result
handoff completes even if the requesting ViewModel is cleared during that handoff.
Closing an Activity after dispatch leaves the writer running. A restored Activity
observes the durable journal/progress rather than starting a second inline writer.
Main-process startup recovers WRITING journals, including the gap between recording
the destination and enqueueing work. Scheduler interruption retains the sealed copy
and WRITING state; a later worker restarts the destination write from those bytes.

The writer uses a data-sync foreground notification with progress and Cancel.
The non-exported scheduling receiver runs in the main process so the isolated
browser process does not own a second WorkManager scheduler. WorkManager input
contains only the private UUID; the URI stays in the private journal. File locks
serialize writers for one export, different exports targeting the same URI, and
persisted-grant acquisition/release across processes. Permissions acquired by this
export are not released while another WRITING export borrows that URI. A terminal
receipt retaining a grant is eligible for later cleanup; workers retry that cleanup.
Pre-existing grants remain owned by their original callers.

Cancellation is durable and checked before opening the destination and between
write chunks. Cleanup waits for the writer to close its output. A queued cancelled
write never opens the provider. An already-started provider write can leave partial
destination bytes; cleanup does not delete a user-selected document. Terminal
receipts suppress duplicate delivery. Provider failures retain the sealed copy for
explicit destination retry; they do not fail the WorkManager dependency chain,
which could otherwise cancel a retry queued as the previous worker finishes.

**Verification:** main Kotlin compiled; **27 focused JVM tests passed** in 20 s
(10 transfer/locking cases, 10 recovery cases, 7 snapshot cases). An earlier
25-case pass took 38 s before the shared-destination/grant locking cases were added.
Checks use real private files and file locks with independent store instances:
duplicate delivery, scheduler-style coroutine cancellation and resumption, competing
writers, cancellation before open, cleanup ordering, rejected provider access,
deferred grant release and persisted progress. They do not execute WorkManager,
Android process death, or an actual DocumentsProvider. Logs/XML/source hashes are
retained in ignored `captures/runtime/save-worker-batch/`.

Android contracts: [long-running workers](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running)
and [document URI permissions](https://developer.android.com/training/data-storage/shared/documents-files).
Foreground admission, job quotas and provider permission persistence still need
actual Android acceptance; a temporary-only or revoked provider grant may require
the user to choose a destination again. No unavoidable platform limitation is
claimed from these source/JVM checks. The existing scoped iOS Save materialization
reference remains `186cec79781256867ad4516f0802118738bd2393`; global pins unchanged.

No APK, emulator or Pixel test ran. Signed 606 remains the verified download.
Next: combined Android/Pixel acceptance of picker return, closing/rotating Activities,
browser-process scheduling, kill/reboot recovery, notification Cancel, shared URI
permissions, slow/large/provider writes and the earlier Files/Share/Open batches.
Fresh-launch recovery UI for failed background saves, abandoned-copy/receipt/lock
reclamation and Share/Open process restoration remain implementation work.

## Retained Share/Open ownership — 2026-10-05

Share, Open and Copy Image now use an Activity-owned `FileExportModel` and a
view-independent controller. Gallery-row Share uses the same owner. Preparation
and errors outlive the requesting row/preview composable; the Activity root
observes the result and launches the system action only when resumed. A prepared
request can be claimed once. Recreated or stale observers cannot present it a
second time or consume a newer request. Requests retain their captured remote
scope and do not silently switch Macs/connections.

Preparation has a cancellable root dialog. Cancellation, failed presentation
and permanent owner closure release unpublished copies after their producer
stops. Successful handoff leaves the exported copy available to the receiving
app or clipboard. Local export cleanup now also covers cancellation while
returning from the IO dispatcher. In-process cache leases protect transfers and
prepared files awaiting presentation from the existing one-hour cache cleanup;
durable Save copies use their separate store. Expired unleased exports remain
eligible for cleanup. The controller retains files/data, never Android views
or an Activity context; system intents use the currently resumed Activity.

Scoped iOS reference at `186cec79781256867ad4516f0802118738bd2393`:
`ChatArtifactViewerPageModel` owns running/error/presentation state and serializes
file-action preparation; `ChatArtifactViewerFileActionState` is its immutable
presentation state. Android adapts this ownership to Activity recreation.
This does not establish identical dismissal/process behavior or advance the
global parity pin. Source copies/hashes are under
`captures/runtime/export-owner-batch/`.

**Verification:** main Kotlin compiled and **17 focused JVM tests passed** in a
23-second Gradle run: six controller cases, one cache-lease case, three remote
sharing regressions and seven local/Changes export regressions. They cover a
single presentation claim, stale callbacks, late cancelled bytes, receiver-owned
file lifetime, failed chooser cleanup, retained ready/error state and overlapping
cache leases. Source hashes, logs and XML are preserved in that evidence folder.
These are controller/IO checks, not a real Activity rotation or chooser launch.

No emulator, Android runtime suite or APK build ran. Signed 606 is unchanged.
Still required: actual rotation/background/foreground and chooser/clipboard
handoff on Pixel, starting actions while a preview changes, concurrent Activities,
account/route revocation during preparation, large/slow transfers and delayed
receiving-app reads. Share/Open state is retained in memory through recreation;
process-death restoration before presentation remains open. Persistent background
Save completion and abandoned durable-copy/receipt cleanup remain implementation
work. Integrate these with the earlier Files/Save batches before final acceptance.

## Current Mac bytes for remote file actions — 2026-10-05

Remote Save, Share and Open now capture the visible path's current scoped RPC,
stat that path again, and stream its current bytes. This is wired through the
shared artifact page for terminal Files, direct terminal paths, session galleries
and native file/Markdown panels. A reconnect supplies the current page's new
admission for the next action; an action already in flight stays on its captured
connection. There is no fallback to stale local preview bytes after a missing
file, rejected authorization or failed transfer. The reader's displayed preview
and position are not intentionally reloaded by exporting.

Export actions remain available when a remote file has not loaded or exceeds the
inline preview limit. Each export still checks Mac metadata/authorization and
stream chunk bounds. Remote Save streams straight into its durable private
snapshot, avoiding a second full staging download. Fresh metadata supplies the
picker filename/MIME; the journal can reconcile a PREPARING bundle that predates
that metadata. Preparation now has progress/cancellation UI as well as writing.
Share/Open use a separately materialized file for the system chooser. Copy Image
and Copy Contents continue to use the displayed content; local downloads and
Changes revision previews retain their existing local snapshot behavior.

Reference: `ChatArtifactFileActionStore.materialize` and
`ChatArtifactTemporaryFileStore.fetch` at scoped iOS commit
`186cec79781256867ad4516f0802118738bd2393`. They establish a fresh stat followed
by scoped materialization, without the inline-preview byte limit. Android streams
fresh bytes for each action rather than adding an iOS-style full-content cache.
The global parity pin is unchanged. Source copies, hashes, build log and test
XML are under `captures/runtime/remote-file-actions-batch/`.

**Verification:** main Kotlin compiled and **26 focused JVM cases passed** in a
29-second Gradle run (6 new remote-source cases, 17 Save snapshot/recovery cases,
3 sharing regressions). New checks cover changing host bytes while the displayed
copy remains unchanged, exact terminal/session/panel scopes, refreshed MIME/name
restoration, missing files, reaching the stream beyond the preview limit, and
cancellation rejecting late bytes/cleaning only the owned snapshot. The oversized
case confirms stream admission then injects failure; it does not transfer a
512 MB file or prove low-memory behavior.

No Android runtime suite, emulator or APK build ran. Signed 606 is unchanged.
Still verify real toolbar availability for oversized/failed previews, picker
name/MIME/results and fresh chooser bytes on Pixel; revoke or replace connections
during preparation; rotate/close previews during Save/Share/Open; and exercise
large-file storage pressure. Share/Open preparation is still tied to its Compose
coroutine, so presentation ownership across recreation remains implementation
work. Background export completion and orphan/receipt cleanup from the Save
batch also remain open. This completes the source implementation of the freshness
follow-up below, not the device acceptance gate.

## Durable Save recovery and write progress — 2026-10-05

Pending exports now use an independent copy under Android's private
`noBackupFilesDir/file-saves`, rather than evictable cache storage. Preparation
records its identity, copies with an exact size bound, syncs the payload, and
publishes a SHA-256 seal. Before opening/truncating a selected destination, the
writer verifies the retained copy. A corrupt, missing or incomplete copy cannot
be exported as if it were complete. Existing waiting/writing saves can migrate
from the old cache layout; a legacy PREPARING copy is never assumed complete.

Each export has an atomic durable journal. Restoration uses that journal over
an older Activity bundle, covering preparation, picker wait, writing, failure,
completion and cancellation. A sealed preparation can finish restoring to READY.
Picker results arriving during asynchronous restoration wait for reconciliation.
WRITING resumes from the saved destination when the Activity owner is restored;
provider authorization is still enforced when opening the document. Small
completion/cancellation receipts prevent an old bundle or late journal update
from resurrecting a finished export. Completion cleanup is repeatable, including
when a process stopped after recording success but before removing its payload.

Large writes now report progress with a Cancel action. Cancellation waits for
the active writer to stop before removing its copy or releasing its acquired
provider grant. Grants already held by other app features remain unowned by
Save. Grant acquisition and journal writes run off the UI dispatcher. No file
bytes or RPC credentials enter Android saved state, and the durable copies are
excluded from Android backup by their storage location.

Android explicitly notes that `SavedStateHandle` updates made while an Activity
is stopped may not reach the saved bundle until another start/stop cycle:
[Saved state ViewModel documentation](https://developer.android.com/topic/libraries/architecture/viewmodel/viewmodel-savedstate).
Provider permission persistence is separate from saved UI state:
[Storage Access Framework documentation](https://developer.android.com/training/data-storage/shared/documents-files).
This is why the journal is needed; these references do not establish runtime
acceptance of the implementation.

**Verification:** main Kotlin compiled and all **17 focused JVM tests passed**
in the final 19-second Gradle run: seven existing snapshot cases and ten recovery
cases. These cover exact bytes, stale-bundle reconciliation, terminal receipts,
corruption before destination opening, cache migration, interrupted preparation,
late journal writes, and write cancellation/progress. An earlier 16-test pass
preceded the repeatable completion-cleanup regression. Logs, XML and source
hashes are in `captures/runtime/save-recovery-batch/`.

The existing Android picker regression source now uses the durable storage
location and asserts payload cleanup (small terminal receipts intentionally
remain); it was not executed in this batch. No emulator, Android runtime suite,
or APK build ran. Signed build 606 remains unchanged.

**iOS freshness follow-up:** `ChatArtifactFileActionStore.materialize` at scoped
commit `186cec79781256867ad4516f0802118738bd2393` stats the host path and passes
size/modifiedAt into its content cache before export. Android's viewer toolbar
still copies its already downloaded preview. Add an explicit current-loader
materialization path for remote Save/Share/Open, preserving account/path scope;
keep local browser/download/Changes snapshot behavior separate. This source
finding is not an unavoidable platform difference. The scoped source and hash
are in `captures/runtime/save-recovery-batch/`; the global parity pin is unchanged.

Still required: actual process termination with the system picker open and during
writing; restored Activity result/grant behavior; provider denial/reboot/disconnect;
large-file progress and cancellation UI on Pixel; background completion without
reopening the Activity; abandoned-copy/old-receipt reclamation; and handling any
partial document left by a cancelled provider write. These remain acceptance or
implementation work, not exclusions. The journal tests reconstruct storage
objects; they do not simulate Android process death or prove those device flows.

## Files and folder recovery batch — 2026-10-05

Implemented on main after `f8ee24d`; this is a source milestone, not a new APK.
Terminal Files now separates permission to retain local content from permission
to make a request. Transient feed loss keeps the sheet, selected file, gallery
rows/session/cursor and completed preview. A replacement connection must verify
the same Mac and obtain its own workspace snapshot before the retained owner
adopts it for future operations. Old RPC objects never switch connections.
Rejected snapshots, account access changes, removed terminals and changed
capabilities invalidate retention; equal-looking later snapshots cannot revive
an invalidated admission.

Completed previews retain both their local file and presentation identity, so
connection adoption does not deliberately unmount the reader. Interrupted
transfers become explicit retryable failures; reconnection alone does not fetch
file bytes. Gallery paging/search jobs pin their RPC, cancel on loss and retain
readable state. A failed In view refresh keeps the scan that admits the open
preview. An interrupted initial scan can resolve its session when retried.
Open folders now have retained controllers for both gallery and direct terminal
routes, keeping listings across view recreation/reconnect and rejecting late
responses. Folder and initial direct-path errors use the shared typed failure
copy and retry policy. Direct-path metadata resolution may restart when the
connection returns; an already selected byte preview waits for explicit Retry.

Reference: scoped iOS commit `186cec79781256867ad4516f0802118738bd2393`,
`WorkspaceDetailView+TerminalArtifacts`, `TerminalArtifactFilesSheet`,
`ChatArtifactFolderView` and `ChatArtifactLoader`. The iOS sheet's initial task
is keyed to terminal identity; its session selection persists independently of
wire changes. Terminal loaders leave `sourceIdentity` nil, and the folder's
load identity is path plus that source identity. Copies/hashes are under
`captures/runtime/files-recovery-batch/ios-source/`. This does not advance the
global parity pin or establish matched-screen/real-route parity.

**Verification:** main Kotlin compiled and 27 focused JVM cases passed in the
final 19-second Gradle run (10 preview-controller, 13 gallery, 2 folder-controller,
2 coordinator cases). They check preserved content/identity/cursors, explicit
retry, late-response rejection, terminal removal and irreversible revocation of
an old admission. The first run had 24 passes and two new folder-test failures:
the fixture supplied `path` instead of the wire contract's child `name`, so the
parser correctly returned no entries. Corrected the fixture and retained the
original failures. Logs, XML and source hashes are in
`captures/runtime/files-recovery-batch/`.

**Still pending:** real Compose route/rotation/reading-position evidence for this
batch, direct-tap and long-text acceptance, account/network transitions on Pixel,
folder back-stack scroll behavior and process death. No Android runtime suite,
emulator or APK build ran for this batch, per the feature-first cadence. `adb`
reported no devices. Signed build 606 remains the last verified download. Add
this batch to the next integrated physical/Android acceptance pass; the JVM
controller checks do not prove visible UI restoration.

## Explicit panel retry after connection replacement — 2026-10-05

A failed panel now retains its error and exact selection through transient feed
loss and recreation, just as a completed panel retains its document. Reconnect
alone does not reload a failed panel. Retry on a live original connection keeps
its owner; Retry after replacement obtains a fresh panel admission from the
coordinator, with the same account/Mac/workspace/surface/path/kind/title. It
acquires the new connection hold before releasing the old owner. The old request
object cannot send requests on the replacement connection.

While no verified replacement is available, Retry makes no network request and
shows the connection-specific failure hint. The hint comes from the panel feed's
actual availability, including OFFLINE, rather than inferring CONNECTING from
the foreground UI. Revocation or descriptor/account changes still discard the
retained failure. An old Retry callback cannot replace the currently selected
owner or reopen a revoked panel.

This compares the explicit retry counter/load task in `MarkdownSurfaceView`,
the path-stable embedded preview and its current loader actions, and the panel
loader construction at scoped iOS revision `186cec79781256867ad4516f0802118738bd2393`.
Five source files and hashes are copied under `captures/runtime/panel-retry-recovery/`.
The global parity pin is unchanged. **16 focused JVM tests and three changed
Android recovery cases (28.731 s) passed.** They cover retained errors, offline
recreation, fresh-connection Retry, stale callbacks, revoked access and interrupted
loads. Four of seven captured screenshots were inspected (offline recreation,
both successful retry renders, revoked state). Source/APK receipts match; all
14 assets verified; no successful-run crashes/new ANRs; screen/sleep settings
unchanged; sole AVD stopped/reaped. Both cold boots encountered System UI ANRs
before testing; screenshots and recovered home screens are preserved. Signed
606 is unchanged. The user requested larger feature batches and deferred broad
regressions during this run; the final pass therefore reruns the three changed
cases, not the full nine-case suite.

The first runtime pass completed eight checks and failed the old interrupted-load
assertion, which expected the panel owner to be discarded and the file to reload
automatically. It exposed ordering-dependent behavior: a socket error published
before feed retirement could now retain the owner, whereas cancellation before
publication still discarded it. Pending requests now consistently retain their
selection, cancel the unfinished transfer and show a connection failure. Late
chunks cannot publish over that failure; partial files are cleaned up. Retry
uses the newly admitted owner and keeps the original request unusable. The
updated regression asserts no second fetch until an actual Retry tap, and exact
replacement bytes afterward. The initial failure is preserved under
`interruption-attempt/`; this supersedes the earlier automatic-refetch checkpoint.

Main Files connection recovery, native errors outside the RPC contract,
process death, live panel-kind changes and physical Pixel/Mac acceptance remain
open; it does not establish whole-screen or all-route parity.

## Local preview storage and size-limit clarity — 2026-10-05

Local open/write/sync/close operations now classify Android errno causes separately
from remote transfer failures. ENOSPC and EDQUOT produce the storage-full state;
other local IO failures produce local-storage-unavailable. The scan is bounded by
identity to tolerate cyclic causes, and does not parse localized error messages.
Stream exceptions from the Mac remain outside this boundary, and partial files
still get cleaned up. The controller retains only the structured state and a
short diagnostic, not the exception/cause graph.

This follows `ChatArtifactLocalFailureClassifier.swift` at scoped reference
`186cec79781256867ad4516f0802118738bd2393`; source/hash recorded under
`captures/runtime/preview-storage/`. The global parity pin is unchanged.
When formatted actual and limit sizes round to the same text, the oversized-file
message now explicitly says the limit was exceeded and includes localized exact
byte counts. The ordinary short size message remains for distinct rounded sizes.

The runtime fixture uses Android's documented
[proxy-file callback](https://developer.android.com/reference/android/os/ProxyFileDescriptorCallback#onWrite(long,int,byte[]))
to return ENOSPC through a real file descriptor without filling storage. An initial
attempt using `/dev/full` instead was rejected by Android access controls and
failed its storage-full assertion; the other four checks passed. The failure and
runner preflight correction are preserved under `captures/runtime/preview-storage/`.
No device permissions were changed.

**19 focused JVM checks and five Android runtime checks (10.210 s) passed.**
The latter verify an actual file-descriptor write returning ENOSPC, nested errno
and quota classification, cause-cycle termination, real local open failure,
exact successful bytes, socket-failure classification and partial cleanup, plus
the actual native-panel size-limit UI with no content fetch. The corrected size
screenshot was inspected. All 14 viewer assets verified; source and both APK
hashes match the final receipts. The successful run has no crash entries or new
ANRs and unchanged screen/sleep settings. Two cold boots before the runtime runs
hit System UI ANRs; their screenshots and recovered home screens are retained.
An earlier startup was stopped before testing to add the scoped iOS quota case.
The sole AVD is stopped/reaped. Signed 606 remains the verified download.

Storage classification here covers output operations;
directory creation/rename still report generic local-storage-unavailable, and
decoder/read/export errors and physical-device acceptance remain open. It does
not complete the broader native admission/retry, lifecycle or parity gates.

## Typed preview failures and explicit retry — 2026-10-05

The shared file preview controller now retains a structured failure alongside its
diagnostic string. File previews use specific messages for request, authorization,
file, transfer and response failures, with retry offered only where the scoped
iOS reference permits it. Markdown panels apply their separate iOS vocabulary:
missing file, forbidden preview, closed panel, Mac update required, unreachable
Mac, temporary transfer failure and generic load failure. Unknown/malformed host
failures do not claim that the Mac is unreachable or display raw host messages.

Metadata failures, oversized files, changed transfer sizes and invalid chunk
content retain typed reasons. Oversized previews retain actual/limit byte counts
and use Android's localized file-size formatting. No content request or private
file is created for an oversized preview. Legacy not_found errors retain the
terminal/session authorization distinction. The failure object contains no
Throwable, view or full host error payload.

Reference: `MobileChatArtifactFailureClassifier.swift`,
`ChatArtifactFailurePresentation.swift`, `MarkdownSurfaceModel.swift` and
`MarkdownSurfaceView.swift` at `186cec79781256867ad4516f0802118738bd2393`.
Source hashes and execution evidence are under `captures/runtime/panel-failures/`;
this scoped comparison does not advance the global parity pin.

**31 focused JVM tests passed. Seven Android panel tests passed in 99.758 s**,
including failure retention through recreation, distinct file/Markdown messages,
non-retryable errors, successful explicit retry and oversized-file rejection
without fetching bytes. **Nine Files regressions passed in 86.445 s** on the same
production APK. The latter ran before a test-only fixture correction. Source and
APK hashes match the receipts. Eight new failure/recovery screenshots were
inspected; nine repeated lifecycle screenshots were captured but not reinspected
in this batch. The Files regression class produces no screenshots. All 14 viewer
assets verified. Both successful runs have unchanged screen/sleep settings,
no app crash entries and no new ANRs; the sole AVD is stopped/reaped. No Pixel
was attached. Signed 606 remains the verified download.

Two cold boots encountered System UI ANRs before instrumentation; those logs and
screenshots are preserved. The first seven-test run passed six checks but failed
the file-specific case because the fixture changed panel kind after launch,
racing the initial workspace list. Configuring kind before launch fixed that
test setup; all seven then passed. This does not verify live panel-kind changes.
The size-limit screenshot also exposes rounded sizes that can appear equal when
the file exceeds the limit by one byte; clarify that presentation in follow-up.

**Remaining:** native transport/authorization errors raised outside the RPC error
contract, complete local-storage/decoder failure classification, route recovery
and manual retry after the original feed owner has been retired, process death,
live panel-kind changes, icons/layout/accessibility and physical Pixel/Mac acceptance. Completed-document
connection-loss retention is covered by the preceding source checkpoint below;
that does not prove every iOS failure/retry workflow.

## Native panel connection recovery — 2026-10-05

Completed native file/Markdown panels now keep their downloaded content and
reading state during a transient feed disconnect. A snapshot identity preserves
only the exact account, Mac, workspace, surface, path, kind and title. The old
request object remains bound to its original connection and cannot issue requests
on a replacement wire. Interrupted transfers discard their old owner and can
load again after the replacement connection passes host verification.

An authorization, identity or protocol rejection invalidates the snapshot
identity. Restoring an apparently identical workspace list cannot resurrect an
old admission. Account removal, a removed/changed panel or a title refresh also
releases the old preview and its private bytes. The UI displays Preview unavailable
after rejected access, including if the feed subsequently disconnects.

Scoped iOS reference: eight source files at
`186cec79781256867ad4516f0802118738bd2393`, recorded with hashes in
`captures/runtime/panel-wire-recovery/upstream-source.json`. The iOS surface owns
its loaded view by surface identity; Markdown load tasks use path/title/retry,
and file preview refresh uses the title. Connection status supplies failure hints
without replacing an already loaded document. The global parity pin is unchanged.

**58 focused JVM checks passed** (50 feed coordinator, two cache policy and six
preview controller). **Four Android panel checks passed in 63.287 s** on the sole
API 37 / 16 KB arm64 AVD. Real pinch/scroll, native viewport and paragraph-geometry
assertions verify a rendered panel through socket loss, offline recreation and
verified feed reconnect, with unchanged owner, exact bytes and one fetch. Title
refresh fetches new content and deletes the old file. Revocation deletes its
replacement, leaves the old request unusable and shows Preview unavailable even
after socket loss. An interrupted transfer is discarded and fetched on a newly
verified feed connection. Existing Raw Markdown search/reading and pending
recreation checks also pass. Removal now broadcasts a workspace-list event and
checks the final empty-workspace route as well as private-file cleanup.

**Two existing Files/panel regressions passed in 39.722 s** on the identical
production APK (before a test-only rebuild). Nine panel and five regression
screenshots were inspected; content is visibly drawn. The pending-recreation
capture catches the menu dismissal animation; the pending-transfer reconnect
capture precedes foreground-connection recovery and still shows its banner.
Those images do not establish menu-animation or whole-screen reconnect parity.
Both successful runs have no new ANR or app crash entries, unchanged screen/sleep
settings, and matching source/APK receipts. All 14 assets verified; sole AVD
stopped and reaped. Signed build 606 is unchanged.

Original failures remain in `captures/runtime/panel-wire-recovery/`: two JVM
runs timed out at the initial two-Mac connection wait in different tests; a
subsequent 58-test run passed with added connection diagnostics, so the cause is
still unresolved. The first Android attempt was blocked by an emulator System UI
boot ANR. Subsequent attempts passed both new recovery checks but exposed a Raw
menu lookup failure and an assertion against the temporary Panel closed message.
The isolated Raw test passed unchanged (27.183 s); final retention tests wait for
the rendered document before opening that menu, so early-load menu interaction
still needs separate investigation. The removal test now waits for the final
route after the foreground list refresh: Android's existing reconciliation drops
the removed selection, consistent with iOS's `selectedMacSurface` fallback and
`WorkspaceActiveSurface.derive`. Test failures now capture a screenshot/hierarchy.
The final four-test run passed after these test-only changes.

**Still open:** the iOS typed failure/manual retry comparison, main terminal Files
transport-loss recovery, different-width/text reflow, process-death/refetch,
physical Pixel/Mac acceptance and full format/accessibility behavior. This
checkpoint does not establish those workflows.

## Rendered Markdown reading state — 2026-10-05

`ArtifactViewerState` now saves a small Markdown viewport bookmark: CSS scroll
coordinates, pinch zoom and a document-block anchor. Capture is synchronous on
Android's save callback, while the JavaScript adapter tracks block geometry.
The saved state contains no document bytes or Android view. Raw/Rendered toggles
share this bookmark, and renderer replacements reuse it. Older saved bundles
without the five new values remain readable.

`MarkdownViewportBinding` restores after the new WebView has committed a visual
state and follows subsequent layout changes above the anchor. It updates the
bookmark's coordinate origin after those changes, so resuming interaction and
reopening the document preserve the paragraph-relative position. Touch, keyboard,
mouse and explicit accessibility scroll/click actions end automatic restoration;
automatic accessibility focus does not. Anchor lookup uses binary search through
the document's block children. Disposal clears native callbacks and the saved
capture closure before destroying the WebView. The upstream shell and all other
hash-pinned assets are unchanged.

**Seven JVM policy checks passed. Two Android lifecycle checks passed in
33.480 s** on the sole arm64 API 37 / 16 KB AVD. Real pinch/scroll followed by
Activity recreation and Raw/Rendered switching preserved native zoom/scroll and
the visual-viewport position of the same heading. A late 180 CSS-pixel height
increase above the reader, then interaction and reopening the original layout,
preserved the paragraph-relative bookmark. Two actual WebView renderer
terminations restored that viewport; a third displayed the existing raw-source
fallback with the real source text. The checks wait for a committed visual state
and require painted text pixels. All seven final screenshots were inspected.

**Three existing Markdown regressions passed in 23.465 s** on the identical
production APK: table/code/Mermaid/Vega rendering and mode switching, sanitization
and image-consent boundaries, and the large-file raw-only limit. Mermaid labels
and Vega bars passed actual screenshot pixel assertions; the screenshot was
inspected. All 14 pinned asset hashes matched. Final runs had no app-process crash
entries or new ANRs, device settings were unchanged, and the single AVD was
stopped and reaped. Source/APK hashes and raw outputs are retained locally in
`captures/runtime/markdown-lifecycle/`.

Earlier attempts are retained: the first fallback assertion used an accessibility
label absent from the native raw view; the next layout assertion failed to account
for the heading's existing CSS padding. Both test errors were corrected. A later
39.823 s two-test pass still captured one unpainted recovery frame and a transient
layout frame, so its coordinate assertions were strengthened with heading geometry,
visual-state commitment and screenshot text pixels before the final pass above.
The separate regression run remains valid because its production APK hash matches
the final APK. Emulator cold boots produced launcher/System UI ANRs; those dialogs
were dismissed and healthy UI confirmed before testing. They are recorded apart
from test-run results.

Scoped reference: `MarkdownWebContentView.swift` under
`Packages/iOS/CmuxAgentChatUI/Sources/CmuxAgentChatUI/Markdown/` at
`186cec79781256867ad4516f0802118738bd2393`. Its coordinator owns page zoom and
allows two WebKit content-process recovery attempts. Android retains its existing
two-retry/raw-source fallback policy. The scoped reference and SHA-256 receipt
are in `captures/runtime/markdown-lifecycle/`; the global parity pin is unchanged.

This does not close physical Pixel acceptance, application-process death with
remote artifact refetch, different-width/text-reflow restoration, the full native
panel transport-loss route, or large-document performance/accessibility gates.
The signed download remains build 606 until the next bundled release.

## Native file and Markdown panel retention — 2026-10-05

The actual native panel route now uses `NativePanelPresentation`, retained by
`NativeFeedSession`, for its selected file and in-progress transfer. Its feed
connection lease survives Activity recreation and reconnection of the separate
foreground terminal client. No Activity or Android view is retained. Leaving the
panel releases its transfer and private files.

Admission captures one exact verified Mac connection, account, workspace,
surface, path, kind and title. Requests are limited to that panel's displayed
file; neither session authorization nor a different panel/path can be substituted.
The owner checks admission before and after requests and native artifact lanes.
Focus-only descriptor changes preserve the download; path, kind or title changes
invalidate it. The title is the upstream refresh token. Removing the panel hides
and deletes its old bytes and displays “Panel closed”. Unavailable connections,
changed descriptors and unsupported hosts have separate presentation messages.
The UI integration lives in `NativePanelRetention.kt` to avoid adding another
large block to `NativeScreen`'s generated JVM method.

**60 JVM checks passed** (48 feed/admission, six preview-controller and six
artifact-RPC tests). The two actual NativeScreen panel checks passed in
**32.104 s** on the sole arm64 API 37 / 16 KB AVD. They verify Raw Markdown mode,
search result `2/2`, reading position within five pixels and exact content, the same retained owner
and local file with one fetch through recreation, an in-progress transfer surviving
another recreation, cleanup on Back, and removal of the private file/native view
with a visible “Panel closed” message when the feed withdraws the panel.
All three panel screenshots were inspected. Two existing Android regressions
also passed in **34.940 s**: the full terminal Files retention case and native
file/Markdown/unknown-surface flow. All three regression screenshots were inspected.
No final-run crash entries or new ANRs; device settings unchanged; source/APK
hashes matched. The sole AVD was stopped and its process reaped.

The initial two panel checks passed in 32.841 s, and two existing panel/Files
regressions passed in 36.350 s. Screenshot review then found a misleading
“Connecting” message after panel removal; the final panel pass above includes its
fix and a stronger visible-message assertion. The first implementation exceeded
NativeScreen's JVM method-size limit; extracting the integration component fixed
that compilation failure. A later build process stopped during test-APK packaging
and was incrementally resumed after confirming that it was no longer running.
The final build succeeded in 1 m 54 s. One emulator System UI startup ANR was
dismissed and recovery visually confirmed before the final tests. Failed build
logs, prior screenshots and both attempts are retained in
`captures/runtime/panel-retention/`, alongside source/APK hashes and JUnit XML.
No new debug Activity was added; the release exclusion inventory remains ten.
Signed build **606** is unchanged and remains the latest independently verified
download. No physical Pixel was attached.

Scoped upstream references at `186cec79781256867ad4516f0802118738bd2393`:
`WorkspaceDetailView+PanelArtifacts.swift`, `PanelFileSurfaceView.swift`,
`MarkdownSurfaceModel.swift` and `MarkdownSurfaceView.swift`, under
`Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/`. They establish the
non-browsable panel loader, path/title/retry refresh identity, generation-fenced
Markdown transfer and explicit closed-panel presentation. Their SHA-256 hashes
are recorded in `captures/runtime/panel-retention/upstream-panel-source.json`;
this does not advance the global parity pin.

This change does not establish rendered-Markdown scroll/zoom, renderer or process
recovery, descriptor-refresh visual acceptance, every typed load-failure message,
physical Pixel/Mac workflows, or retention through an actual feed transport loss.
An old feed admission is retired on transport replacement and the panel refetches
after readmission; compare that lifecycle with iOS before closing the reconnect
gate. The global completion checklist remains in `REMAINING_WORK.md`.

## Save picker ownership — 2026-10-05

Save now has one Activity-owned `FileSaveModel` and a stable result launcher at
`FileSaveHost`, above conditional preview content. The toolbar delegates to that
owner. Before presenting Android's real document picker, the owner makes a
private copy with the correct suggested filename and MIME type. The pending
copy, result callback and asynchronous write survive Activity recreation and
replacement of the preview's loading/loaded call site. They do not retain an
Activity or view. A failed destination keeps the copy for retry; successful Save,
cancellation and permanent owner closure clean up only that request's directory.
Persisted write grants acquired by Save are released, preserving preexisting
write grants. Errors present recovery actions without exposing provider URLs.

**17 JVM tests passed**: seven snapshot tests, seven existing preview-file tests,
and three sharing tests. **Three Android Save checks passed in 47.142 s** on the
sole arm64 API 37 / 16 KB AVD:

- The real system picker remains open across Activity recreation and replacement
  with a loading preview. Deleting the original preview after the independent
  copy is ready does not change the exact saved Unicode bytes. The picker launches
  once, uses the expected MIME/name and releases the private copy on completion.
- Cancelling the real picker after recreation cleans up the copy, preserves the
  preview and allows a second independent Save/cancellation.
- A synthetic unavailable-provider result shows a friendly error, preserves the
  exact retry bytes and owner across recreation, then cleans up after retry is
  cancelled. This does not claim a successful retry to a second real provider.

Fourteen existing Android regressions passed in **158.618 s**: three text/media
lifecycle cases, the actual NativeScreen Files retention case, nine artifact
Files cases and the native file/Markdown-panel flow. Their six screenshots were
also inspected. Final-run crash log empty, no new ANRs, settings unchanged and
Downloads empty after generated-file cleanup. The only AVD is stopped and its
process reaped. Final sources and debug/test APKs match the recorded hashes;
no new debug Activity was added (release exclusion inventory remains ten).

All four Save screenshots were inspected. The loading label is debug fixture
content. The fixture's cancelled-picker screenshot also has dark system-bar
icons; production MainActivity separately configures light icons. Physical
system-picker return appearance remains part of Pixel acceptance.

Earlier failed attempts are retained: ActivityScenario's foreground-only recreate
helper rejected recreation while DocumentsUI held the foreground; a debug fixture
restored a stale loading flag; startup System UI ANR dialogs obscured controls;
and test cleanup attempted document deletion after closing the Activity and
losing its temporary grant. The test now requests recreation directly while the
picker is foregrounded, retains its loading fixture and deletes its generated
document before Activity closure. The final Save run passed after those fixes.

Scoped iOS references at `186cec79781256867ad4516f0802118738bd2393`:
`ChatArtifactViewerFileActionState.swift`, `ChatArtifactFileActionPresentation.swift`
and `ChatArtifactFileActionStore.swift`, under
`Packages/iOS/CmuxAgentChatUI/Sources/CmuxAgentChatUI/Artifacts/`. iOS presents
`UIDocumentPickerViewController(forExporting:asCopy:true)` for a materialized
local URL and cleans it on completion/cancellation. Exact source hashes are in
`captures/runtime/file-save-lifecycle/upstream-save-source.json`; this scoped
audit does not advance the global parity pin. Android currently exports the
displayed preview snapshot. Fresh remote-file materialization/cache semantics
still need call-site comparison before claiming that aspect of iOS parity.

The owner serializes bounded picker metadata with SavedStateHandle, but **actual
process death, interrupted large writes, provider permission transitions and
abandoned-cache cleanup after a killed process remain unverified**. This shared
viewer test does not prove full browser-parent or main Files Save flows on Pixel.
Local evidence is in `captures/runtime/file-save-lifecycle/`, including raw results,
source/APK hashes, failed attempts, screenshots and source references. Signed
build **606** remains the latest independently verified download.

## Main terminal Files retention — 2026-10-05

Main terminal Files now uses a `TerminalFilesPresentation` retained by
`NativeFeedSession`. The gallery, its admitted session, navigation and selected
preview download survive Activity recreation. A connection lease retains the
verified feed channel while the main terminal reconnects. Direct terminal-path
sheets use the same presentation with a separate selected-preview controller.
Only the selected pager file downloads; closing/backing out releases its private
files. No Activity, Android view or file bytes enter retained navigation state.

`NativeFeedCoordinator.terminalArtifactAccess` captures one exact verified Mac,
connection and terminal. It checks account/caller eligibility and terminal
membership before and after requests, cancels borrowed operations when the feed
retires, and never rebinds an old admission to a replacement connection. Main
Files hides and closes an invalid presentation; reopening creates a new scan,
so saved session routes must pass the existing session-identity checks.

Verification: **68 JVM tests** passed (six controller, seven preview files, six
artifact RPC, 45 feed coordinator, four navigation memory). The actual
`NativeScreen` terminal → Files gallery test passed in **24.449 s** on the sole
arm64 API 37 / 16 KB AVD. It verifies PDF page 2 pixels and exact bounds, the same
retained owner/file and unchanged fetch count after recreation, one in-progress
text transfer completing after another recreation, exact visible/file content,
and cleanup on Done. The nine existing Files Android regressions passed in
**76.590 s**, covering session replacement, reconnect navigation, direct relative
folder authorization, corrupt-transfer retry and share ownership.

Both screenshots were inspected. The first attempt never reached the workspace
because of an Android System UI startup ANR; its log/screenshot are preserved.
After grounded dismissal and a responsive hierarchy, the unchanged test passed.
No app crashes or subsequent ANRs; device settings unchanged; sole AVD stopped
and process reaped. Local evidence: `captures/runtime/files-retention/`, including
source/APK hashes, JUnit XML, raw Android results and screenshots.

This source change is newer than signed build **606**. This test does not close
physical Pixel/Mac acceptance, direct-tap recreation, long-text reading/search
restoration through the full Files route, account/connection revocation UI,
native Markdown-panel retention, pending Save results, rendered-Markdown state,
or process death. Continue those specific checks rather than treating the
shared-viewer or gallery PDF test as proof for every content route.

## Historical source audit before terminal Files retention (2026-10-05)

The following audit preceded the implementation above. Its native Markdown-panel
and shared Save ownership findings are addressed above for Activity recreation. The text/media tests below recreate the
shared viewer with the same local file.
Changes supplies that stable file through its retained preview controller. The
main Files flow and native Markdown panels still need equivalent integration:
`ArtifactFilesSheet` creates its gallery store in `remember`, while
`ArtifactPreviewPage` downloads in `produceState`, closes its private directory
on disposal, and keys the viewer by the local absolute path. `ArtifactPreviewFiles`
uses a new UUID directory per download. MainActivity does not handle orientation
changes itself, so recreation can redownload into a different key and discard
saved viewer state even when the remembered remote destination is restored.
This is source evidence of an outstanding integration gap, not a new runtime
result. The earlier component tests must not be described as end-to-end Files
rotation acceptance.

Next: move the gallery/download lifetime to the owning retained presentation,
keep authorization/session invalidation explicit, and test the actual Files and
Markdown-panel routes across recreation with exact content, position, selection
and fetch-count assertions. Cover an in-progress download and closing/revoking
the owner as well. Pending Save-result ownership must work across both the loading
and loaded states; saving a local path only inside `FilePreviewActions` is not
sufficient because those states currently mount it at different call sites.

## Text and media recreation — 2026-10-05

The shared artifact viewer now saves raw-text reading position as a character
anchor with a fractional line offset, horizontal position, selection, search
query/result, line-number visibility, Raw/Rendered choice and Go-to-line dialog
input. It reloads text/syntax rather than storing a document in the saved-state
bundle. Restoring a search no longer jumps back to its first result. Go waits
for the reloaded document before accepting navigation.

Media previews save their position, restore paused or active playback across
Activity recreation, pause on backgrounding, and stay paused on return. A small
saved bookmark replaces the old polling-only state; player/views are released
with the composition. Only configuration recreation saves an autoplay request.
The tests use a 30-second WAV and actual Android playback/seek positions; video
frame/codec coverage and process-death recovery are still separate gates.

Screenshot review found that the native text view could draw scrolled document
lines over the search row, toolbar and system bars. The raw-text viewport is now
explicitly clipped. A pixel assertion checks the empty center of the toolbar for
leaked document glyphs, and the final screenshot visibly confirms the correction.

**Seven Android tests passed in 94.922 s** on the sole existing 16 KB AVD:

- Paused seek at eight seconds, Activity recreation, continued playback from the
  saved position, background/foreground pause, another paused recreation,
  Restart and player release.
- Search result `2/2`, exact reading position, selected text range, line-number
  setting and unchanged document bytes across recreation; subsequent search
  wrap to `1/2`, plus the toolbar pixel check.
- Markdown Raw mode and a pending Go-to-line `120` draft across recreation,
  followed by navigation in the native source view.
- Existing three text checks for search/navigation, Unicode selection/copy,
  line-number rendering, per-kind wrap/font persistence and pinch; existing
  Changes audio prepare/play/restart/close check.

All three final screenshots were inspected. No crash entries or ANR events in
the final run; device settings unchanged; emulator stopped and reaped. The first
cold boot had a System UI startup ANR before testing and a stalled launcher was
restarted. The initial seven-test attempt had six passes and one test selector
failure: UI Automator could not see a Compose-only raw-view label. A diagnostic
screenshot proved that raw Markdown was displayed; the test now checks the
actual native view and exact source bytes. The next seven passed in 101.277 s,
but screenshot review exposed the clipping bug; the final run above includes its
fix and stronger pixel assertion. Initial build setup/import errors were fixed
before runtime testing. Original logs and screenshots are retained.

Scoped iOS references at `186cec79781256867ad4516f0802118738bd2393`:
`ChatArtifactMediaView.swift` (SHA-256
`a3d4533995547f50ffae8efdd007a6c2a44f18ae6926e905c8c040a2237f36b9`)
uses AVKit controls and releases its player on dismantle;
`ChatArtifactTextView.swift` (SHA-256
`b279ae19d45f030e94f6a60957de0c8fe092570c9b2fcfd806daa32b253b7964`)
passes search/navigation/display state into a native text container. These scoped
references do not advance the global parity pin.

Evidence: `captures/runtime/text-media-lifecycle/` (source/APK hashes, runner,
results, screenshots, settings, event/crash logs, earlier attempts and references).
The new nonexported `ArtifactPreviewTestActivity` is debug-only, bringing the
release exclusion inventory to **ten** Activities; release verification derives
this list dynamically. No signed APK was produced in this batch; **596** remains
the verified download. No Pixel was attached.

**Still open:** rendered-Markdown scroll/zoom and renderer recovery; pending Save
picker restoration; forced browser-parent recreation with binary previews;
video/codec and changed-aspect-ratio acceptance, process death, accessibility and
physical Pixel/Mac testing. Other completion gates remain in
[REMAINING_WORK.md](REMAINING_WORK.md).

## Browser parent follow-up — 2026-10-05

The routed text Changes sheet now passes recreation plus rotation in both
directions while the underlying webpage retains its JavaScript draft. The
browser also has a retained view owner, with exact DOM/history retention tested
through two Activity recreations in the production view components. See
[LOCAL_BROWSER.md](LOCAL_BROWSER.md#retained-browser-view-and-changes-rotation--2026-10-05)
for the four-test evidence and precise scope. Forced separate-process parent
recreation with a binary preview, process recovery and pending-picker/media/text/Save
restoration remain open. Signed 596 is unchanged.

## PDF navigation and image transform restoration — 2026-10-05

Short final PDF pages now get enough trailing layout space to reach the top of
the continuous viewer. Next/Previous and the page label therefore follow the
selected page instead of remaining on a sliver of the preceding page. The
spacing is recalculated from the viewport and final page dimensions.

Zoom and pan are now saved per artifact. Pan uses bounded viewport fractions
rather than storing old pixel dimensions. Real touch testing exposed a separate
one-finger pan failure in the existing gesture path: double-tap zoom worked but
the drag did not move the image. The viewer now claims touch transforms in the
initial pointer pass, after the gesture crosses touch slop. At minimum zoom it
leaves one-finger swipes to the containing file pager. Foundation's transform
modifier remains for its non-touch input handling; keyboard/mouse acceptance was
not part of this run.

**Seven Android checks passed in 79.850 s**, covering:

- Short/deleted PDF: select page two, verify `2 / 2` and disabled Next, then return
  to page one with Previous. Existing PDF rendering/base-revision checks pass.
- Four mixed-height pages (40, 40, 600, 80 points): navigate forward and backward
  through every page, check labels/visible destinations and boundary buttons,
  with one file fetch.
- A striped image: prove initial colors, double-tap zoom, one-finger pan,
  Activity recreation with the same zoomed/panned pixels, revision, artifact and
  fetch count; then reset, pinch out, reset again, and swipe into the next file.
- Retain the tall PDF page's `2 / 2` label, exact visible bounds and green pixels
  across recreation without another download. Closing the owner clears files.
- Existing retry, audio playback/release, image clipboard/export and extensionless
  image MIME checks.

Final screenshots inspected; no crash entries, no new ANR events during testing,
and unchanged device settings. The existing AVD was stopped/reaped. Each cold
boot had a System UI startup ANR before testing; the affected system component
was restarted and the fresh UI was checked before running. The first seven-test
attempt had one actual app gesture failure (pan) and six passes; its screenshot
and output are retained. The final pass follows the gesture fix.

Reference: `ChatArtifactPDFView.swift` at upstream
`186cec79781256867ad4516f0802118738bd2393`, path
`Packages/iOS/CmuxAgentChatUI/Sources/CmuxAgentChatUI/Artifacts/ChatArtifactPDFView.swift`,
SHA-256 `cdf21836a120538deb88667ee37a66b215d106f52107ece600e9b1a4f456eefc`.
That implementation uses PDFKit's vertical continuous mode and automatic scaling.
This scoped check does not establish every Quick Look/PDFKit behavior. The actual
Compose Foundation 1.9.1 source was also inspected from its
[official source artifact](https://dl.google.com/dl/android/maven2/androidx/compose/foundation/foundation/1.9.1/foundation-1.9.1-sources.jar)
while diagnosing consumed touch events. Global parity pins remain unchanged.

Evidence: `captures/runtime/viewer-navigation/` (source/APK hashes, failed attempt,
final instrumentation output, screenshots, device settings, event/crash logs,
upstream excerpt and source artifact). Signed **596** remains the verified APK;
this source has not yet been published in a signed build. No Pixel was attached.

**Still open:** browser-parent recreation, media position, text viewer and Save
picker restoration; PDF zoom and image focal-point behavior across aspect-ratio
changes; process death, accessibility and physical Pixel/Mac acceptance. The
short-PDF navigation and same-size image zoom/pan recreation issues described in
the earlier checkpoint below are now fixed and verified within the scope above.

## Retained binary preview — 2026-10-05

The Changes presentation now owns the selected Before/After revision, active
transfer, progress/error and private downloaded artifact. Activity recreation
reattaches to the same artifact instead of resetting the revision and downloading
again. The stable artifact key also lets the existing PDF scroll state restore.
Only the selected binary page loads content; neighboring pager items retain their
text-diff prefetch behavior. The owner retains one active artifact and at most 32
revision choices. It retains no Activity or view.

Selecting another file, returning to the file list, refreshing repository data,
forcing a diff refresh, or closing the presentation cancels the old transfer and
retires its private directory. Request identities prevent a late cancelled
transfer from publishing over or deleting its replacement. Retry creates a new
request; choices are revalidated against the changed-file policy.

### Reference and scope

Inspected `Packages/iOS/CmuxMobileChanges/Sources/CmuxMobileChanges/UI/FileDiffBinaryView.swift`
at upstream `186cec79781256867ad4516f0802118738bd2393` (SHA-256
`06a7858e203e966503b2908098ad3b3632e59bd5b5e74d9e9678e940981919a9`).
It binds the selected revision and passes it into the inline preview. This is a
scoped reference check, not an audit of every iOS preview cache/lifecycle path;
the global parity pin is unchanged.

### Verification

- **29 JVM tests passed:** retained controller 6, preview files 7, Changes store 7,
  content transfer 6 and routed sidebar Changes 3. Includes cancellation races,
  refresh, retry, revision policy, bounded choices and cleanup.
- **7 Android tests passed in 71.973 s** on the existing `cmux_api37_16k` AVD:
  new retained preview test, five existing image/PDF/audio/retry/clipboard tests,
  and the existing text-diff recreation/landscape/sign-out test.
- The new test was then strengthened after screenshot review showed that a
  partially visible second PDF page could satisfy the original pixel assertion.
  **The strengthened test passed in 28.727 s:** a tall second page retains the
  `2 / 2` label, exact visible bounds and green pixels across recreation, without
  another file fetch. The renamed image retains Before, the old path, red pixels,
  the same owner/artifact and fetch count. Closing Changes leaves no preview files.
  Production code was unchanged between these passes.
- Both final screenshots were visually inspected. Final run: no crash entries or
  ANR events; screen/sleep settings unchanged; emulator stopped and reaped.
- Three earlier attempts were obstructed by boot-time System UI/Pixel Launcher
  ANR dialogs. Logs/screenshots are preserved. The launcher was restarted and
  one abandoned synthetic preview directory from the interrupted process was
  removed before the clean seven-test run. The final cold boot and stronger test
  had no ANR event. The first instrumentation build also had a test-only
  PdfDocument Closeable mismatch, corrected with explicit try/finally cleanup.

Local evidence: `captures/runtime/changes-preview-retention/`, including original
failures, `initial-seven-pass/`, final instrumentation output, JVM XML, source/APK
hashes, device settings and screenshots. Synthetic peer fixtures only; no physical
Pixel/Mac acceptance was performed. Signed **596** remains the verified download;
this change is newer and has not been published as a signed APK.

### Remaining work

- Browser-parent recreation and process-death recovery remain unverified.
- Image zoom/pan, media position, text viewer state and an open Save picker need
  their own restoration work and tests; this change does not retain those views.
- The initial short-page PDF screenshot exposed a separate navigation issue:
  after Next page reaches the scroll limit, page two can dominate the viewport
  while the label/buttons still use the partially visible first page's index.
  Fix the current-page calculation or final-page alignment, and verify navigation
  for short/mixed page sizes. The stronger retention fixture deliberately uses
  tall pages to test a definite selected-page transition; it does not resolve
  that short-page navigation issue.
- Full format/UI/accessibility and physical Mac/Pixel validation are still open.
