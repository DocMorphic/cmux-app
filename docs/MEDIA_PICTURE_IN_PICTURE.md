# Media picture-in-picture

## Scoped comparison — 2026-10-06

Reviewed `ChatArtifactMediaView.swift` under
`Packages/iOS/CmuxAgentChatUI/Sources/CmuxAgentChatUI/Artifacts/` at
`186cec79781256867ad4516f0802118738bd2393`. It uses `AVPlayerViewController`
without overriding its default PiP eligibility. Apple documents
[`allowsPictureInPicturePlayback`](https://developer.apple.com/documentation/avkit/avplayerviewcontroller/allowspictureinpictureplayback)
as enabled by default. This establishes an API-level comparison, not an observed
iPhone playback result. Global implemented/reviewed pins are unchanged.

Android follows the platform's
[PiP lifecycle and parameter guidance](https://developer.android.com/develop/ui/views/picture-in-picture).

## Feature batch

- Shared video previews expose Picture in picture when the device supports it.
  Audio-only media retains the existing controls without a PiP action.
- A dedicated playback Activity receives position, playback intent, speed,
  mute and track selections. Its initial preparation enters PiP; expansion
  exposes the full controls. Android 12+ also enables automatic entry when
  leaving an actively playing expanded player. Older versions use the explicit
  leave callback. Aspect ratios are bounded to Android's supported range.
- PiP hides app controls while keeping video and captions visible. The existing
  media session provides system playback commands, including Play when the
  dedicated player starts paused. Hidden playback stops; visible PiP can continue.
- The playback file has an independent private name and lease. Hard links avoid
  copying immutable completed downloads on supported filesystems; an I/O failure
  falls back to a bounded cancellable copy. Dismissing the source preview therefore
  does not delete the player's bytes. Closing playback retires its file; stale
  unleased files use the existing cache recovery policy.
- Main and browser processes each have a nonexported playback Activity and a
  process-local, single-use handoff ID. Intents contain no private file paths or
  credentials. A nonexported provider in the main process supplies an account
  incarnation digest; the player checks ownership before rendering and every
  500 ms, closing on change or query failure. This is account-scoped ownership;
  broader host/team revocation acceptance remains open.
- Returning to an existing preview restores the position and choices, paused.
  A killed process cannot reclaim an old handoff or automatically reopen media.

## Verification and next integration run

Main and Android-test Kotlin compilation passed; the final check took 30 seconds.
**15 JVM cases passed:** five file ownership/copy/cancellation/cache cases, six
audio-focus cases and four media-control cases. An initial instrumentation
compile failure from an unavailable framework flag was corrected to public
configuration flags. Logs, test XML and the source receipt are saved locally in
`captures/runtime/media-pip-feature/` (ignored by Git). Gradle was stopped.

Two compiled Android cases are queued for the next combined milestone:
private component declarations, and actual video PiP entry/advancing playback,
source dismissal/file lifetime and close cleanup. They have **not run yet**.
Also check browser-process handoff, expansion/return bookmark and track retention,
system playback actions, Home/close/rotation, account retirement, activity/process
recreation and physical Pixel playback. Inspect visible frames and captions.
No APK, emulator/device run or signed release is claimed by this batch.

## Combined viewer integration — 2026-10-06

Both PiP Android cases now pass on the existing Android 17/16 KiB emulator.
The run verifies private component declarations, entry through the real video
preview action, actual PiP mode, advancing playback, a visible gold/blue fixture
frame, independent file lifetime after deleting the source and dismissing its
preview, and file cleanup after closing playback. The initial screenshot was
taken during the transition and was blank despite playback advancing; the
strengthened test waits for both fixture colors before accepting rendering.
The final screenshot visibly shows the video in the system PiP window.

This is part of the [13-case viewer milestone](PDF_DESTINATIONS.md#combined-viewer-integration--2026-10-06).
Evidence is in `captures/runtime/media-pdf-integration/`. The deliberate source
deletion can make the underlying source preview unavailable; it tests ownership
independence and is not a media-format acceptance result. No Pixel or signed
release was used. Browser-process handoff, expansion/return/track retention,
system-button interaction, account retirement and wider lifecycle acceptance
remain open.

## Expansion, return and accurate resume — 2026-10-06

`ArtifactMediaPipReturnRuntimeTest` now opens an actual video preview, seeks to
8 seconds, enters the system PiP window, taps Android's Expand control, changes
audio/subtitles to French, sets speed to 1.5× and mute, and seeks to 12 seconds.
Done returns the bookmark to the original preview paused; its private playback
copy is removed while the source remains. Recreating that preview retains the
choices and native audio track. Starting it resumes beyond 12 seconds at the
native 1.5× speed, with a visibly rendered video and French subtitle.

The integration exposed two production defects:

- Black fullscreen surfaces inherited an unsuitable content color, hiding the
  filename and timestamps. Both playback Activity and fullscreen Dialog now
  explicitly use white content. The regression checks actual timestamp pixels.
- `VideoView.seekTo(int)` used backward keyframe seeking. The original fixture's
  keyframes are 25 seconds apart, so an apparent 12-second paused bookmark could
  resume at zero. Prepared playback now calls `MediaPlayer.seekTo(long,
  SEEK_CLOSEST)` for the closest frame. The test waits for seek completion, then
  verifies position after playback starts; accepting the reported position while
  a seek was pending had missed this defect. See the official
  [MediaPlayer seek contract](https://developer.android.com/reference/android/media/MediaPlayer#seekTo(long,int)).

Verification used the existing `cmux_api37_16k` AVD (Android 17, actual page size
16384), with no new emulator or physical device. Both the new return case and
existing track-selection/recreation case passed together in **52.496 seconds**.
The final strengthened return case, including a wait for rendered video pixels,
passed in **24.582 seconds**. Inspected final screenshots show the readable
expanded filename/time and returned video with French cue, 1.5× and Unmute.
Native position after resuming was **13472 ms**, retaining the 12-second bookmark.
The Cloud machine failure/retry case also passed in the initial combined run;
that run's media case failed before the test's PiP coordinates were corrected.

Earlier attempts are preserved: the first tapped window-relative coordinates;
the second passed weaker assertions but screenshots revealed hidden text and a
zero-time resume; the third failed bookmark retention; the fourth passed native
state checks but captured a transient black frame. The final test requires actual
video pixels, not only player state. This is not one all-green broad suite.

Evidence and build logs: `captures/runtime/media-pip-return/` (ignored by Git).
Final debug APK SHA-256:
`136ab36149678b5c48472e28498a896418813fbdd84c97035e240adc0f5722a3`.
Final test APK SHA-256:
`ed8fcf5450ad27378079958f44ef9b95f7da34e029812d03bb51bcbda4ea35da`.
The boot/test event logs contain no ANR/crash events; the crash buffer is empty.
Gradle stopped and the sole emulator was stopped and reaped after the run.

This closes fixture coverage for expansion/return/track retention and the system
Expand action. It does not prove browser-process handoff, Home/close/rotation,
account retirement, process recovery, broad format behavior or physical Pixel
playback. Published signed APK remains **616**; full parity is still unverified.
