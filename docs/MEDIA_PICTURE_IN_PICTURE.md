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
